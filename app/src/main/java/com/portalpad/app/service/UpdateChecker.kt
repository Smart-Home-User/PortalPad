package com.portalpad.app.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import com.portalpad.app.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-app update checker backed by the public GitHub Releases API.
 *
 * Design notes:
 *  - Two unauthenticated calls per check: the releases list (also used to sum
 *    lifetime asset downloads) and the repo object (stars/forks/issues).
 *    GitHub's anonymous limit is 60 requests/hour per IP — a check costs 2.
 *  - "Latest" = the newest non-draft release by publish date, so it works
 *    whether or not releases are flagged pre-release on GitHub.
 *  - Auto-check on launch is throttled (once per [AUTO_CHECK_MIN_INTERVAL_MS])
 *    and gated on the user toggle + network availability. Opening the update
 *    page always performs a fresh, un-throttled check.
 *  - Raw API responses are cached in a private SharedPreferences file so the
 *    page can show the last known result while offline. This is machine
 *    state, deliberately NOT in PreferencesRepository/DataStore (it shouldn't
 *    ride along in settings backups).
 *  - [badgeVisible] is Compose state observed by the main screen's top bar to
 *    show the amber "« New" hint next to the update icon.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val REPO = "Smart-Home-User/PortalPad"
    const val REPO_URL = "https://github.com/$REPO"
    private const val API_RELEASES = "https://api.github.com/repos/$REPO/releases?per_page=100"
    private const val API_REPO = "https://api.github.com/repos/$REPO"

    private const val PREFS_NAME = "update_checker"
    private const val KEY_AUTO_CHECK = "auto_check_enabled"
    private const val KEY_LAST_AUTO_MS = "last_auto_check_ms"
    private const val KEY_CACHED_RELEASES = "cached_releases_json"
    private const val KEY_CACHED_REPO = "cached_repo_json"
    private const val KEY_CACHED_AT_MS = "cached_at_ms"
    private const val KEY_SKIPPED_TAG = "skipped_tag"
    private const val KEY_APK_HASH = "installed_apk_sha256"
    private const val KEY_APK_HASH_KEY = "installed_apk_sha256_key"

    private const val AUTO_CHECK_MIN_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Amber "« New" hint next to the top-bar update icon. */
    val badgeVisible = mutableStateOf(false)

    /**
     * Amber tint on the top-bar version number: true when this binary is NOT
     * the published latest — either numerically ahead of GitHub (unreleased
     * build) or same tag but different bytes (hash mismatch / published after
     * this build compiled). Never overlaps with [badgeVisible]: the badge
     * means "newer exists to install", the tint means "your binary isn't the
     * published one".
     */
    val versionTintAmber = mutableStateOf(false)

    /**
     * Recompute [versionTintAmber] from a check result. Blocking (may hash
     * the installed APK on first run) — background threads only.
     */
    fun refreshVersionTint(ctx: Context, result: CheckResult?) {
        val latest = result?.latest
        if (latest == null) {
            versionTintAmber.value = false
            return
        }
        val installed = parseVersionNumbers(BuildConfig.VERSION_NAME)
        val ahead = isNewer(installed, parseVersionNumbers(latest.tag))
        val same = assessSameTag(ctx, result)
        versionTintAmber.value = ahead ||
            same == SameTagStatus.MISMATCH ||
            same == SameTagStatus.PUBLISHED_AFTER_BUILD
    }

    data class ReleaseInfo(
        val tag: String,
        val name: String,
        val publishedAtMs: Long,
        val body: String,
        val prerelease: Boolean,
        val htmlUrl: String,
        val apkUrl: String?,
        val apkName: String?,
        val apkSizeBytes: Long,
        val apkDownloads: Long,
        /** sha256 hex of the APK asset, when GitHub attached a digest. */
        val apkSha256: String?,
    )

    data class RepoStats(
        val stars: Long,
        val forks: Long,
        val openIssues: Long,
        val totalDownloads: Long,
    )

    data class CheckResult(
        /**
         * Newest non-draft STABLE release (pre-release-flagged builds are
         * skipped), falling back to the newest release of any kind only when
         * every release is a pre-release. The badge and "update available"
         * state track this; pre-releases stay reachable via the picker.
         */
        val latest: ReleaseInfo?,
        /** True when [latest] is numerically newer than the installed build. */
        val updateAvailable: Boolean,
        /** Stable releases newer than the installed build, newest first (accumulated notes). */
        val newerReleases: List<ReleaseInfo>,
        /** Every non-draft release, newest first — feeds the version picker. */
        val releases: List<ReleaseInfo>,
        val stats: RepoStats?,
        /** True when served from the offline cache rather than a live fetch. */
        val fromCache: Boolean,
        /** Epoch ms of the fetch this result came from. */
        val checkedAtMs: Long,
    )

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- toggle

    fun autoCheckEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_CHECK, true)

    fun setAutoCheckEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    fun skippedTag(ctx: Context): String = prefs(ctx).getString(KEY_SKIPPED_TAG, "") ?: ""

    fun setSkippedTag(ctx: Context, tag: String) {
        prefs(ctx).edit().putString(KEY_SKIPPED_TAG, tag).apply()
        refreshBadgeFromCache(ctx)
    }

    // ---------------------------------------------------------- launch check

    /**
     * Fire-and-forget background check on app launch. Silent on every failure
     * path — the launch experience must never depend on GitHub being up.
     */
    fun autoCheckOnLaunch(ctx: Context) {
        val app = ctx.applicationContext
        val due = autoCheckEnabled(app) &&
            System.currentTimeMillis() - prefs(app).getLong(KEY_LAST_AUTO_MS, 0L) >= AUTO_CHECK_MIN_INTERVAL_MS &&
            networkAvailable(app)
        // EVERYTHING on a background thread, including the cached-badge
        // recompute: the cache is the raw GitHub releases JSON (potentially
        // hundreds of KB), and parsing it in MainActivity.onCreate taxed app
        // startup. Badge/tint are thread-safe Compose states, so they simply
        // light up a few ms after first draw. The tint additionally needs the
        // same-tag assessment (APK hash on first run), which must never touch
        // the main thread. Silent on every failure path.
        Thread {
            try {
                val cached = loadCached(app)
                applyBadgeFrom(app, cached)
                refreshVersionTint(app, cached)
                if (due) {
                    val result = performCheck(app)
                    prefs(app).edit().putLong(KEY_LAST_AUTO_MS, System.currentTimeMillis()).apply()
                    refreshVersionTint(app, result)
                    Log.i(TAG, "launch auto-check done; badge=${badgeVisible.value} tint=${versionTintAmber.value}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "launch auto-check failed: ${t.message}")
            }
        }.apply { name = "pp-update-check"; isDaemon = true }.start()
    }

    /** Recompute the badge from the offline cache without any network use. */
    fun refreshBadgeFromCache(ctx: Context) = applyBadgeFrom(ctx, loadCached(ctx))

    /** Badge recompute from an already-parsed check result (avoids re-parsing
     *  the cached JSON when the caller has it in hand). */
    private fun applyBadgeFrom(ctx: Context, cached: CheckResult?) {
        val latest = cached?.latest
        badgeVisible.value =
            cached != null && cached.updateAvailable && latest != null && latest.tag != skippedTag(ctx)
    }

    // ------------------------------------------------------------- the check

    /**
     * Live check. Blocking network I/O — call from a background thread.
     * On success the raw responses are cached and the badge is updated.
     */
    fun performCheck(ctx: Context): CheckResult {
        val releasesRaw = httpGet(API_RELEASES)
        val repoRaw = httpGet(API_REPO)
        val now = System.currentTimeMillis()
        prefs(ctx).edit()
            .putString(KEY_CACHED_RELEASES, releasesRaw)
            .putString(KEY_CACHED_REPO, repoRaw)
            .putLong(KEY_CACHED_AT_MS, now)
            .apply()
        val result = buildResult(releasesRaw, repoRaw, fromCache = false, checkedAtMs = now)
        val latest = result.latest
        badgeVisible.value =
            result.updateAvailable && latest != null && latest.tag != skippedTag(ctx)
        return result
    }

    /** Last successful check parsed from cache, or null if never checked. */
    fun loadCached(ctx: Context): CheckResult? {
        val p = prefs(ctx)
        val releasesRaw = p.getString(KEY_CACHED_RELEASES, null) ?: return null
        val repoRaw = p.getString(KEY_CACHED_REPO, null) ?: return null
        return try {
            buildResult(releasesRaw, repoRaw, fromCache = true, checkedAtMs = p.getLong(KEY_CACHED_AT_MS, 0L))
        } catch (t: Throwable) {
            Log.w(TAG, "cache parse failed: ${t.message}")
            null
        }
    }

    private fun buildResult(
        releasesRaw: String,
        repoRaw: String,
        fromCache: Boolean,
        checkedAtMs: Long,
    ): CheckResult {
        val arr = JSONArray(releasesRaw)
        val releases = ArrayList<ReleaseInfo>(arr.length())
        var totalDownloads = 0L
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optBoolean("draft", false)) continue
            var apkUrl: String? = null
            var apkName: String? = null
            var apkSize = 0L
            var apkDl = 0L
            var apkSha: String? = null
            val assets = o.optJSONArray("assets") ?: JSONArray()
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                totalDownloads += a.optLong("download_count", 0L)
                val n = a.optString("name", "")
                if (apkUrl == null && n.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url", null)
                    apkName = n
                    apkSize = a.optLong("size", 0L)
                    apkDl = a.optLong("download_count", 0L)
                    // GitHub attaches "digest": "sha256:<hex>" on newer assets.
                    val digest = a.optString("digest", "")
                    if (digest.startsWith("sha256:")) apkSha = digest.substring(7).lowercase(Locale.US)
                }
            }
            releases.add(
                ReleaseInfo(
                    tag = o.optString("tag_name", ""),
                    name = o.optString("name", o.optString("tag_name", "")),
                    publishedAtMs = parseIsoMs(o.optString("published_at", "")),
                    body = o.optString("body", ""),
                    prerelease = o.optBoolean("prerelease", false),
                    htmlUrl = o.optString("html_url", "$REPO_URL/releases"),
                    apkUrl = apkUrl,
                    apkName = apkName,
                    apkSizeBytes = apkSize,
                    apkDownloads = apkDl,
                    apkSha256 = apkSha,
                ),
            )
        }
        releases.sortByDescending { it.publishedAtMs }
        // Badge/update state tracks STABLE releases; fall back to the full
        // list only if the repo has nothing but pre-releases.
        val stablePool = releases.filter { !it.prerelease }.ifEmpty { releases }
        val latest = stablePool.firstOrNull()
        val installed = parseVersionNumbers(BuildConfig.VERSION_NAME)
        val newer = stablePool.filter { isNewer(parseVersionNumbers(it.tag), installed) }
        val updateAvailable = latest != null && isNewer(parseVersionNumbers(latest.tag), installed)

        val repo = JSONObject(repoRaw)
        val stats = RepoStats(
            stars = repo.optLong("stargazers_count", 0L),
            forks = repo.optLong("forks_count", 0L),
            openIssues = repo.optLong("open_issues_count", 0L),
            totalDownloads = totalDownloads,
        )
        return CheckResult(
            latest = latest,
            updateAvailable = updateAvailable,
            newerReleases = newer,
            releases = releases,
            stats = stats,
            fromCache = fromCache,
            checkedAtMs = checkedAtMs,
        )
    }

    // --------------------------------------------------------------- network

    fun networkAvailable(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return cm.activeNetwork != null
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "PortalPad/${BuildConfig.VERSION_NAME}")
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code from $urlStr")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Download an APK asset to the app-private updates dir. Blocking — call
     * from a background thread. GitHub redirects browser_download_url to its
     * CDN; HttpURLConnection follows same-protocol redirects automatically.
     * Returns the finished file.
     */
    fun downloadApk(
        ctx: Context,
        url: String,
        fileName: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): File {
        val dir = File(
            ctx.getExternalFilesDir(null)
                ?: throw IllegalStateException("External files dir unavailable"),
            "updates",
        )
        dir.mkdirs()
        // Clear stale APKs so failed/old downloads don't accumulate.
        dir.listFiles()?.forEach { if (it.name != fileName) it.delete() }
        val file = File(dir, fileName)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "PortalPad/${BuildConfig.VERSION_NAME}")
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code downloading APK")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        } catch (t: Throwable) {
            file.delete()
            throw t
        } finally {
            conn.disconnect()
        }
        return file
    }

    // --------------------------------------------------------------- install

    /** True when a privileged shell (Shizuku or root) is bound and usable. */
    fun elevatedReady(): Boolean = when (val b = com.portalpad.app.PortalPadApp.instance.clickBackend) {
        is ClickBackend.ShizukuUserService -> b.backend.isReady
        is ClickBackend.Shell -> b.access.isReady
    }

    /** Run [cmd] through whichever privileged backend is bound, or null. */
    private fun runElevated(cmd: String): String? =
        when (val b = com.portalpad.app.PortalPadApp.instance.clickBackend) {
            is ClickBackend.ShizukuUserService -> if (b.backend.isReady) b.backend.runCommand(cmd) else null
            is ClickBackend.Shell -> if (b.access.isReady) b.access.execShell(cmd) else null
        }

    /**
     * Stream an APK into the PUBLIC Downloads folder via MediaStore (no
     * storage permission needed; the app owns the file it creates). Used for
     * downgrades in BOTH tiers: the shell uid can't read app-private
     * Android/data on modern Android, and public Downloads also survives the
     * uninstall required by the non-elevated path. Blocking — background
     * threads only. Returns the absolute filesystem path (for `pm install`).
     */
    fun downloadToPublicDownloads(
        ctx: Context,
        url: String,
        fileName: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): String {
        val resolver = ctx.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = android.provider.MediaStore.Downloads.getContentUri(
            android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY,
        )
        val item = resolver.insert(collection, values)
            ?: throw IllegalStateException("Couldn't create file in Downloads")
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "PortalPad/${BuildConfig.VERSION_NAME}")
                val code = conn.responseCode
                if (code != 200) throw IllegalStateException("HTTP $code downloading APK")
                val total = conn.contentLengthLong
                resolver.openOutputStream(item)?.use { output ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            onProgress(read, total)
                        }
                    }
                } ?: throw IllegalStateException("Couldn't open Downloads for writing")
            } finally {
                conn.disconnect()
            }
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(item, values, null, null)
            // Resolve the real filesystem path (MediaStore may have renamed on
            // collision, e.g. "name (1).apk"). DATA is deprecated but still
            // populated for Downloads; fall back to the conventional path.
            var path: String? = null
            resolver.query(
                item,
                arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) path = c.getString(0) }
            return path ?: "/sdcard/Download/$fileName"
        } catch (t: Throwable) {
            runCatching { resolver.delete(item, null, null) }
            throw t
        }
    }

    /**
     * In-place downgrade via privileged `pm install -d -r`. The APK is
     * STREAMED into the install session from the shell process's own stdin
     * (`cat file | pm install -S <size>`) rather than passed as a path:
     * system_server is forbidden by SELinux from reading FUSE-backed
     * /storage paths directly (seen on One UI: "no access to read file
     * context u:object_r:fuse:s0"), but the shell CAN read the file, and the
     * session pipe is how `adb install` itself works. Still device/ROM
     * dependent (-d may be refused). Returns the raw pm output; "Success"
     * means it worked (at which point Android kills this process to swap the
     * APK, so callers may never see the return). Blocking — background
     * threads only.
     */
    fun installDowngradeElevated(apkPath: String): String =
        runElevated(
            "SZ=\$(stat -c %s '$apkPath') && cat '$apkPath' | pm install -d -r -S \$SZ 2>&1",
        ) ?: "ERR: no privileged backend bound"

    /** System uninstall dialog for PortalPad (non-elevated downgrade path). */
    fun requestUninstall(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "uninstall dialog launch failed: ${t.message}")
        }
    }

    /** Whether the OS will accept an install request from PortalPad. */
    fun canInstall(ctx: Context): Boolean =
        ctx.packageManager.canRequestPackageInstalls()

    /** Deep-link to PortalPad's own "Install unknown apps" toggle. */
    fun openInstallPermissionSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "unknown-sources settings launch failed: ${t.message}")
        }
    }

    /** Hand the downloaded APK to the system package installer. */
    fun installApk(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            ctx,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK,
                ),
        )
    }

    // ------------------------------------------------- same-tag verification

    /**
     * Outcome of comparing THIS build against GitHub's latest release when
     * both carry the same version number (e.g. an unreleased 1.3-beta build
     * vs a published v1.3-beta with different content).
     */
    enum class SameTagStatus {
        /** Tags differ (or nothing to compare) — normal update logic applies. */
        NOT_APPLICABLE,
        /** Digest match: this install IS the published build. */
        MATCH,
        /** Digest mismatch: this install is NOT the published build. */
        MISMATCH,
        /** No digest available, but the release was published after this build was compiled. */
        PUBLISHED_AFTER_BUILD,
        /** No digest, release predates this build — nothing to worry about. */
        INDETERMINATE,
    }

    /**
     * Blocking (hashes the installed APK on first call) — run on a background
     * thread. Purely informational: this NEVER drives the "« New" badge.
     */
    fun assessSameTag(ctx: Context, result: CheckResult): SameTagStatus {
        val latest = result.latest ?: return SameTagStatus.NOT_APPLICABLE
        val installed = parseVersionNumbers(BuildConfig.VERSION_NAME)
        val tagVer = parseVersionNumbers(latest.tag)
        if (isNewer(tagVer, installed) || isNewer(installed, tagVer)) return SameTagStatus.NOT_APPLICABLE
        // Tier 3: exact — GitHub's asset digest vs a hash of the installed APK.
        val digest = latest.apkSha256
        if (digest != null) {
            val mine = installedApkSha256(ctx)
            if (mine != null) {
                return if (mine.equals(digest, ignoreCase = true)) SameTagStatus.MATCH
                else SameTagStatus.MISMATCH
            }
            // Hashing failed — fall through to the timestamp heuristic.
        }
        // Tier 2: heuristic — release published after this build was compiled.
        return if (latest.publishedAtMs > BuildConfig.BUILD_TIME_MS) SameTagStatus.PUBLISHED_AFTER_BUILD
        else SameTagStatus.INDETERMINATE
    }

    /**
     * sha256 hex of the installed APK (base APK at applicationInfo.sourceDir).
     * Cached in prefs keyed on path+size+mtime so the ~56 MB hash runs once
     * per install. Blocking — background threads only.
     */
    fun installedApkSha256(ctx: Context): String? = try {
        val f = File(ctx.applicationInfo.sourceDir)
        val key = "${f.absolutePath}|${f.length()}|${f.lastModified()}"
        val p = prefs(ctx)
        val cached = p.getString(KEY_APK_HASH, null)
        if (p.getString(KEY_APK_HASH_KEY, "") == key && cached != null) {
            cached
        } else {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            val hex = md.digest().joinToString("") { b -> "%02x".format(b) }
            p.edit().putString(KEY_APK_HASH_KEY, key).putString(KEY_APK_HASH, hex).apply()
            hex
        }
    } catch (t: Throwable) {
        Log.w(TAG, "installed-APK hash failed: ${t.message}")
        null
    }

    // ------------------------------------------------------------ formatting

    /** "v1.3-beta" / "1.3-beta" -> [1, 3]. */
    fun parseVersionNumbers(s: String): List<Int> =
        Regex("\\d+").findAll(s).map { it.value.toInt() }.toList()

    /** Numeric component-wise compare; a longer, equal-prefix version wins. */
    fun isNewer(candidate: List<Int>, installed: List<Int>): Boolean {
        if (candidate.isEmpty()) return false
        val n = maxOf(candidate.size, installed.size)
        for (i in 0 until n) {
            val c = candidate.getOrElse(i) { 0 }
            val v = installed.getOrElse(i) { 0 }
            if (c != v) return c > v
        }
        return false
    }

    /** 999 -> "999", 12_345 -> "12.3k", 1_234_567 -> "1.2M". */
    fun compactCount(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 1_000_000 -> {
            val k = n / 100 / 10.0
            if (k >= 100 && k == k.toLong().toDouble()) "${k.toLong()}k" else "${k}k"
        }
        else -> "${n / 100_000 / 10.0}M"
    }

    fun formatBytes(b: Long): String = when {
        b <= 0 -> ""
        b < 1024 * 1024 -> "${b / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0))
    }

    fun formatDate(ms: Long): String =
        if (ms <= 0) "" else SimpleDateFormat("MMM d, yyyy", Locale.US).format(ms)

    private fun parseIsoMs(iso: String): Long = try {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        f.parse(iso)?.time ?: 0L
    } catch (t: Throwable) {
        0L
    }
}
