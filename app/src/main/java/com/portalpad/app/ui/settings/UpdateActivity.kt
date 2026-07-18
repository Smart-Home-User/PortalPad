package com.portalpad.app.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.portalpad.app.BuildConfig
import com.portalpad.app.service.UpdateChecker
import com.portalpad.app.ui.theme.AbAccent
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbDanger
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceDim
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimary
import com.portalpad.app.ui.theme.AbPrimaryBright
import com.portalpad.app.ui.theme.AbPrimaryDim
import com.portalpad.app.ui.theme.AbSuccess
import com.portalpad.app.ui.theme.AbSurface
import com.portalpad.app.ui.theme.AbSurfaceElevated
import com.portalpad.app.ui.theme.AbSurfaceVariant
import com.portalpad.app.ui.theme.AbWarning
import com.portalpad.app.ui.theme.PortalPadTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-app "App updates" page.
 *
 * Layout contract: everything stacks from the TOP; the page itself never
 * scrolls. The release-notes card ALWAYS takes the remaining screen height
 * (weight 1f) and scrolls internally with the house VerticalScrollbar, so
 * the page exactly fits every screen with no dead space.
 *
 * The version picker row lets the user select ANY non-draft release. The
 * selected release drives the notes, meta line, and action button:
 *  - newer than installed  -> violet download + install (normal update path)
 *  - same as installed     -> no action
 *  - older than installed  -> amber warning; Android refuses in-place
 *    downgrades, and an APK downloaded to app-private storage would be
 *    DELETED by the uninstall step, so the button opens the release page in
 *    the browser instead (browser downloads survive uninstall).
 */
class UpdateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PortalPadTheme {
                UpdateScreen(onBack = { finish() })
            }
        }
    }
}

private sealed class UpdatePhase {
    data object Checking : UpdatePhase()
    data class Ready(val result: UpdateChecker.CheckResult, val offline: Boolean) : UpdatePhase()
    data class Failed(val message: String) : UpdatePhase()
}

private sealed class DownloadPhase {
    data object Idle : DownloadPhase()
    data object Running : DownloadPhase()
    data class Done(val file: File) : DownloadPhase()
    data class Error(val message: String) : DownloadPhase()
}

@Composable
private fun UpdateScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf<UpdatePhase>(UpdatePhase.Checking) }
    var canInstall by remember { mutableStateOf(UpdateChecker.canInstall(ctx)) }
    var skipped by remember { mutableStateOf(UpdateChecker.skippedTag(ctx)) }
    var autoCheck by remember { mutableStateOf(UpdateChecker.autoCheckEnabled(ctx)) }
    // Minute ticker so "Checked just now" ages into "Checked N m ago".
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMs = System.currentTimeMillis()
        }
    }

    // Re-evaluate the install permission when returning from Android settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) canInstall = UpdateChecker.canInstall(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Fresh, un-throttled check on open (and on the header re-check control);
    // cached fallback when offline.
    var checkNonce by remember { mutableStateOf(0) }
    LaunchedEffect(checkNonce) {
        phase = UpdatePhase.Checking
        val fresh = withContext(Dispatchers.IO) {
            runCatching { UpdateChecker.performCheck(ctx) }
        }
        phase = fresh.fold(
            onSuccess = { UpdatePhase.Ready(it, offline = false) },
            onFailure = { err ->
                val cached = withContext(Dispatchers.IO) { UpdateChecker.loadCached(ctx) }
                if (cached != null) UpdatePhase.Ready(cached, offline = true)
                else UpdatePhase.Failed(err.message ?: "Couldn't reach GitHub")
            },
        )
        nowMs = System.currentTimeMillis()
    }

    val ready = phase as? UpdatePhase.Ready
    val result = ready?.result
    val latest = result?.latest
    val updateAvailable = result?.updateAvailable == true && latest != null

    // Same-tag verification (hash vs GitHub digest, timestamp fallback).
    // Informational only — never drives the badge. Hashing is a one-off
    // background cost (~56 MB read), then cached per install.
    var sameTag by remember(result) { mutableStateOf(UpdateChecker.SameTagStatus.NOT_APPLICABLE) }
    LaunchedEffect(result) {
        if (result != null) {
            sameTag = withContext(Dispatchers.IO) {
                val s = UpdateChecker.assessSameTag(ctx, result)
                // Keep the main screen's amber version tint in sync with what
                // this page just learned (hash is cached, so this is cheap).
                UpdateChecker.refreshVersionTint(ctx, result)
                s
            }
        }
    }
    var reinstall by remember(result) { mutableStateOf<DownloadPhase>(DownloadPhase.Idle) }
    var reinstallText by remember(result) { mutableStateOf("") }

    // Version picker: selected release, defaulting to latest (stable-first).
    var selectedTag by remember(result) { mutableStateOf(latest?.tag) }
    // Set only by an explicit picker tap. Rollback/reinstall actions stay
    // hidden until the user has actually chosen something — the page never
    // volunteers an uninstall warning about an action nobody asked for.
    var userPicked by remember(result) { mutableStateOf(false) }
    val selected = result?.releases?.firstOrNull { it.tag == selectedTag } ?: latest
    val installedVer = remember { UpdateChecker.parseVersionNumbers(BuildConfig.VERSION_NAME) }
    val selectedIsNewer =
        selected != null && UpdateChecker.isNewer(UpdateChecker.parseVersionNumbers(selected.tag), installedVer)
    val selectedIsOlder =
        selected != null && !selectedIsNewer &&
            UpdateChecker.isNewer(installedVer, UpdateChecker.parseVersionNumbers(selected.tag))
    val selectedIsEqual = selected != null && !selectedIsNewer && !selectedIsOlder
    // Download state resets whenever the selection changes.
    var download by remember(selectedTag) { mutableStateOf<DownloadPhase>(DownloadPhase.Idle) }
    var progress by remember(selectedTag) { mutableFloatStateOf(0f) }
    var progressText by remember(selectedTag) { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(AbBackground)
            .padding(14.dp),
    ) {
        // ----- header (with re-check control, mirroring the main screen's icon)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = AbOnSurfaceMuted,
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Text("App updates", color = AbOnSurface, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = phase !is UpdatePhase.Checking) { checkNonce++ }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text("Check for updates", color = AbOnSurfaceMuted, fontSize = 12.sp)
                Spacer(Modifier.width(5.dp))
                if (phase is UpdatePhase.Checking) {
                    CircularProgressIndicator(color = AbPrimary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                } else {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        tint = AbOnSurfaceMuted,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ----- install-permission banner (only when an install is on offer)
        if ((selectedIsNewer || (userPicked && selectedIsEqual)) && !canInstall) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF3A2E17))
                    .border(1.dp, AbWarning, RoundedCornerShape(8.dp))
                    .clickable { UpdateChecker.openInstallPermissionSettings(ctx) }
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = AbWarning, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Allow installs from PortalPad", color = AbWarning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Needed to install updates \u00b7 tap to open settings", color = AbWarning.copy(alpha = 0.75f), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // ----- status banner
        // Four distinct states:
        //  - user explicitly viewing a non-latest release  -> amber warning
        //  - installed build numerically AHEAD of GitHub's latest (unreleased
        //    build in the wild) -> amber caveat with an INFO icon: heads-up,
        //    not an error, and deliberately no "download the latest" call to
        //    action — the latest release would be a DOWNGRADE here
        //  - latest is newer -> update available
        //  - everything equal -> green
        val selectedNotLatest =
            userPicked && selected != null && latest != null && selected.tag != latest.tag
        val installedAhead = latest != null &&
            UpdateChecker.isNewer(installedVer, UpdateChecker.parseVersionNumbers(latest.tag))
        val (statusColor, statusText) = when (val p = phase) {
            is UpdatePhase.Checking -> AbOnSurfaceMuted to "Checking GitHub\u2026"
            is UpdatePhase.Failed -> AbDanger to "Couldn't reach GitHub \u2014 check connection"
            is UpdatePhase.Ready -> when {
                selectedNotLatest -> AbWarning to "Viewing older release: ${selected!!.tag}"
                p.offline -> AbWarning to "Offline \u2014 showing last result (${agoText(p.result.checkedAtMs, nowMs)})"
                installedAhead ->
                    AbWarning to "Installed build is newer than the latest GitHub release \u2014 likely an unreleased build"
                updateAvailable -> AbAccent to "Update available: ${latest!!.tag}"
                else -> AbSuccess to "You\u2019re on the latest version"
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(AbSurfaceVariant)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            when {
                phase is UpdatePhase.Checking ->
                    CircularProgressIndicator(color = AbPrimary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                phase is UpdatePhase.Failed || selectedNotLatest ->
                    Icon(Icons.Default.Warning, contentDescription = null, tint = statusColor, modifier = Modifier.size(15.dp))
                installedAhead ->
                    Icon(Icons.Default.Info, contentDescription = null, tint = statusColor, modifier = Modifier.size(15.dp))
                else ->
                    Icon(
                        if (updateAvailable) Icons.Default.ArrowUpward else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(15.dp),
                    )
            }
            Spacer(Modifier.width(8.dp))
            Text(statusText, color = statusColor, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))

        // ----- same-tag hint: installed version number matches the release,
        // but the binary may differ. Tap = reinstall the published APK
        // (same-version reinstall is allowed when signatures match).
        val sameTagConcern = sameTag == UpdateChecker.SameTagStatus.MISMATCH ||
            sameTag == UpdateChecker.SameTagStatus.PUBLISHED_AFTER_BUILD
        if (sameTagConcern && latest != null && latest.apkUrl != null && latest.apkName != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF3A2E17))
                    .border(1.dp, AbWarning, RoundedCornerShape(8.dp))
                    .clickable(enabled = reinstall !is DownloadPhase.Running) {
                        when (val r = reinstall) {
                            is DownloadPhase.Done -> UpdateChecker.installApk(ctx, r.file)
                            else -> {
                                if (!canInstall) {
                                    UpdateChecker.openInstallPermissionSettings(ctx)
                                    return@clickable
                                }
                                reinstall = DownloadPhase.Running
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        runCatching {
                                            UpdateChecker.downloadApk(ctx, latest.apkUrl!!, latest.apkName!!) { read, total ->
                                                reinstallText =
                                                    "${UpdateChecker.formatBytes(read)} of ${UpdateChecker.formatBytes(if (total > 0) total else latest.apkSizeBytes)}"
                                            }
                                        }
                                    }
                                    reinstall = res.fold(
                                        onSuccess = { f -> UpdateChecker.installApk(ctx, f); DownloadPhase.Done(f) },
                                        onFailure = { DownloadPhase.Error(it.message ?: "Download failed") },
                                    )
                                }
                            }
                        }
                    }
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = AbWarning, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (sameTag == UpdateChecker.SameTagStatus.MISMATCH)
                            "Your build differs from the published ${latest.tag}"
                        else
                            "${latest.tag} was published after this build was compiled",
                        color = AbWarning,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val hintSub = when (val r = reinstall) {
                    is DownloadPhase.Idle -> "Tap to reinstall the released build"
                    is DownloadPhase.Running -> "Downloading\u2026 $reinstallText"
                    is DownloadPhase.Done -> "Ready \u2014 Android install prompt opens \u00b7 tap again if it didn\u2019t"
                    is DownloadPhase.Error -> "Download failed \u2014 tap to retry (${r.message})"
                }
                Text(hintSub, color = AbWarning.copy(alpha = 0.75f), fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
        }

        // ----- version card (pure status: installed vs latest)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AbSurface)
                .padding(11.dp),
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Installed", color = AbOnSurfaceDim, fontSize = 11.sp)
                Text("v${BuildConfig.VERSION_NAME}", color = AbOnSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Icon(
                Icons.Default.ArrowUpward,
                contentDescription = null,
                tint = if (updateAvailable) AbPrimary else AbOnSurfaceDim,
                modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Latest (GitHub)", color = AbOnSurfaceDim, fontSize = 11.sp)
                Text(
                    latest?.tag ?: "\u2014",
                    color = if (updateAvailable) AbPrimaryBright else AbOnSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ----- version picker row
        val releases = result?.releases.orEmpty()
        val hasPrerelease = releases.any { it.prerelease }
        var pickerOpen by remember { mutableStateOf(false) }
        var relFilter by remember { mutableStateOf("all") }
        Box(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AbSurface)
                    .border(1.dp, AbPrimaryDim, RoundedCornerShape(10.dp))
                    .clickable(enabled = releases.isNotEmpty()) { pickerOpen = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Install a different version", color = AbOnSurfaceDim, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            selected?.tag ?: "\u2014",
                            color = if (userPicked) AbPrimaryBright else AbOnSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (selected != null && selected.tag == latest?.tag) {
                            Spacer(Modifier.width(5.dp))
                            Text("(Latest)", color = AbSuccess, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Icon(
                    if (pickerOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = AbPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = pickerOpen,
                onDismissRequest = { pickerOpen = false },
                modifier = Modifier.background(AbSurfaceElevated),
            ) {
                // Filter chips only exist once the repo actually has a pre-release.
                if (hasPrerelease) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        FilterChipMini("All", relFilter == "all") { relFilter = "all" }
                        FilterChipMini("Stable", relFilter == "stable") { relFilter = "stable" }
                        FilterChipMini("Pre-release", relFilter == "pre") { relFilter = "pre" }
                    }
                }
                releases.forEach { r ->
                    if (relFilter == "stable" && r.prerelease) return@forEach
                    if (relFilter == "pre" && !r.prerelease) return@forEach
                    val isNewest = r.tag == releases.firstOrNull()?.tag
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    r.tag,
                                    color = if (userPicked && r.tag == selectedTag) AbPrimaryBright else AbOnSurface,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (isNewest) {
                                    Spacer(Modifier.width(6.dp))
                                    TagBadge("Latest", AbSuccess)
                                }
                                if (r.prerelease) {
                                    Spacer(Modifier.width(6.dp))
                                    TagBadge("Pre-release", AbWarning)
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    UpdateChecker.formatDate(r.publishedAtMs),
                                    color = AbOnSurfaceDim,
                                    fontSize = 11.sp,
                                )
                            }
                        },
                        onClick = {
                            selectedTag = r.tag
                            userPicked = true
                            pickerOpen = false
                        },
                    )
                }
            }
        }
        if (selected != null) {
            Spacer(Modifier.height(4.dp))
            val meta = buildString {
                append("Published ${UpdateChecker.formatDate(selected.publishedAtMs)}")
                if (selected.apkSizeBytes > 0) append(" \u00b7 ${UpdateChecker.formatBytes(selected.apkSizeBytes)}")
                append(" \u00b7 ${selected.apkDownloads}")
                append(if (selected.apkDownloads == 1L) " download" else " downloads")
            }
            Text(
                meta,
                color = AbOnSurfaceDim,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(10.dp))

        // ----- release notes: ALWAYS fills the remaining screen height. Body
        // is a LazyColumn of per-line items so the house VerticalScrollbar
        // (item-index based) gets real metrics; it self-hides when everything
        // fits. The page itself never scrolls.
        val notes: List<UpdateChecker.ReleaseInfo> = when {
            selected == null -> emptyList()
            selected.tag == latest?.tag && result?.newerReleases?.isNotEmpty() == true -> result.newerReleases
            else -> listOf(selected)
        }
        // Flatten releases into rows: tag header, parsed markdown blocks, a gap.
        val noteRows: List<NoteItem> = notes.flatMap { r ->
            listOf(NoteItem.Tag(r.tag)) +
                parseMarkdown(r.body).map { NoteItem.Block(it) } +
                listOf(NoteItem.Gap)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(AbSurface)
                .padding(11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (selected != null && selected.tag == latest?.tag) "What\u2019s new" else "Release notes",
                    color = AbAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                val trailing = when {
                    selectedNotLatest -> "viewing an older release"
                    notes.size > 1 -> "${notes.size} releases since your version"
                    else -> ""
                }
                if (trailing.isNotEmpty()) Text(trailing, color = AbOnSurfaceDim, fontSize = 10.sp)
            }
            Spacer(Modifier.height(6.dp))
            if (noteRows.isEmpty()) {
                Text(
                    if (phase is UpdatePhase.Checking) "" else "No release notes available.",
                    color = AbOnSurfaceDim,
                    fontSize = 12.sp,
                )
            } else {
                val listState = rememberLazyListState()
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 10.dp),
                    ) {
                        items(noteRows.size) { i ->
                            when (val row = noteRows[i]) {
                                is NoteItem.Tag -> Text(
                                    row.tag,
                                    color = AbPrimaryBright,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(bottom = 2.dp),
                                )
                                is NoteItem.Block -> MdBlockView(row.block) { url -> openUrlExternal(ctx, url) }
                                NoteItem.Gap -> Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                    com.portalpad.app.ui.common.VerticalScrollbar(
                        listState = listState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ----- stats: one slim row of four
        val stats = result?.stats
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("Stars", stats?.stars, Modifier.weight(1f))
            StatTile("Forks", stats?.forks, Modifier.weight(1f))
            StatTile("Issues", stats?.openIssues, Modifier.weight(1f))
            StatTile("Downloads", stats?.totalDownloads, Modifier.weight(1.15f))
        }
        Spacer(Modifier.height(10.dp))

        // ----- action area, keyed to the SELECTED release.
        // Newer -> normal install (no pick needed). Equal -> reinstall, and
        // Older -> downgrade download, both ONLY after an explicit picker tap.
        val installable = selected != null && (selectedIsNewer || (userPicked && selectedIsEqual))
        if (installable && selected != null) {
            val apkReady = selected.apkUrl != null && selected.apkName != null
            val baseColor = if (selectedIsNewer) AbPrimary else AbPrimaryDim
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (download is DownloadPhase.Done) AbPrimaryDim else baseColor)
                    .clickable(enabled = apkReady && download !is DownloadPhase.Running) {
                        when (val d = download) {
                            is DownloadPhase.Done -> UpdateChecker.installApk(ctx, d.file)
                            else -> {
                                if (!canInstall) {
                                    UpdateChecker.openInstallPermissionSettings(ctx)
                                    return@clickable
                                }
                                download = DownloadPhase.Running
                                progress = 0f
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        runCatching {
                                            UpdateChecker.downloadApk(ctx, selected.apkUrl!!, selected.apkName!!) { read, total ->
                                                if (total > 0) progress = read.toFloat() / total
                                                progressText =
                                                    "${UpdateChecker.formatBytes(read)} of ${UpdateChecker.formatBytes(if (total > 0) total else selected.apkSizeBytes)}"
                                            }
                                        }
                                    }
                                    download = res.fold(
                                        onSuccess = { f -> UpdateChecker.installApk(ctx, f); DownloadPhase.Done(f) },
                                        onFailure = { DownloadPhase.Error(it.message ?: "Download failed") },
                                    )
                                }
                            }
                        }
                    }
                    .padding(11.dp),
            ) {
                val (label, sub) = when (val d = download) {
                    is DownloadPhase.Idle -> when {
                        !apkReady -> "No APK attached to this release" to "Use View on GitHub instead"
                        selectedIsNewer ->
                            "Download and install" to
                                "${selected.apkName} \u00b7 ${UpdateChecker.formatBytes(selected.apkSizeBytes)}"
                        else ->
                            "Download and reinstall" to "Same version \u00b7 installs over the current build"
                    }
                    is DownloadPhase.Running -> "Downloading\u2026" to progressText
                    is DownloadPhase.Done -> "Ready \u2014 Android install prompt opens" to "Tap again if the dialog didn\u2019t appear"
                    is DownloadPhase.Error -> "Download failed \u2014 tap to retry" to d.message
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = AbOnSurface, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = AbOnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (selectedIsNewer && selected.tag == latest?.tag) {
                        Spacer(Modifier.width(6.dp))
                        TagBadge("Latest", AbSuccess)
                    }
                }
                Text(sub, color = AbPrimaryBright, fontSize = 11.sp)
                if (download is DownloadPhase.Running) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        color = AbOnSurface,
                        trackColor = AbPrimaryDim,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ActionChip(
                    label = "View on GitHub",
                    icon = { Icon(Icons.Default.OpenInNew, contentDescription = null, tint = AbOnSurfaceMuted, modifier = Modifier.size(13.dp)) },
                    color = AbOnSurfaceMuted,
                    modifier = Modifier.weight(1f),
                ) { openUrlExternal(ctx, selected.htmlUrl) }
                if (selected.tag == latest?.tag) {
                    val isSkipped = skipped == selected.tag
                    ActionChip(
                        label = if (isSkipped) "Skipped \u2014 badge hidden" else "Skip this version",
                        icon = {
                            Icon(
                                if (isSkipped) Icons.Default.CheckCircle else Icons.Default.Close,
                                contentDescription = null,
                                tint = AbOnSurfaceDim,
                                modifier = Modifier.size(13.dp),
                            )
                        },
                        color = AbOnSurfaceDim,
                        modifier = Modifier.weight(1f),
                    ) {
                        UpdateChecker.setSkippedTag(ctx, selected.tag)
                        skipped = selected.tag
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        } else if (selected != null && selectedIsOlder && (userPicked || selected.tag == latest?.tag)) {
            // Downgrade action. The LATEST release is exempt from the pick
            // gate: when this build is ahead of GitHub, "get onto the
            // published release" is the most likely intent (testers on
            // interim builds), so its violet button shows by default. Deeper
            // rollbacks still require an explicit picker tap.
            // Shared path either way: stream the APK to PUBLIC Downloads
            // (in-page progress; survives uninstall), then privileged backend
            // -> streamed `pm install -d` (fully in-app; PortalPad is killed
            // by the swap), or no backend -> guided uninstall + Files.
            var dgPhase by remember(selectedTag) { mutableStateOf("idle") }
            var dgPath by remember(selectedTag) { mutableStateOf("") }
            var dgMsg by remember(selectedTag) { mutableStateOf("") }
            var dgProgress by remember(selectedTag) { mutableFloatStateOf(0f) }
            val elevated = UpdateChecker.elevatedReady()
            val apkReady = selected.apkUrl != null && selected.apkName != null
            // The LATEST published release always downloads from a violet
            // button (mint Latest chip, amber caution sub-label) — flagship
            // build looks like the flagship build even when it's a downgrade
            // for this install. Deeper rollbacks (past the current release)
            // keep the full amber treatment. Same machinery either way.
            val dgIsLatest = selected.tag == latest?.tag
            val dgTitleColor = if (dgIsLatest) AbOnSurface else AbWarning
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (dgIsLatest) AbPrimary else Color(0xFF3A2E17))
                    .let { if (dgIsLatest) it else it.border(1.dp, AbWarning, RoundedCornerShape(10.dp)) }
                    .clickable(enabled = apkReady && dgPhase != "downloading" && dgPhase != "installing") {
                        when (dgPhase) {
                            "blocked" -> UpdateChecker.requestUninstall(ctx)
                            "saved" -> {
                                if (elevated) {
                                    dgPhase = "installing"
                                    scope.launch {
                                        val out = withContext(Dispatchers.IO) {
                                            runCatching { UpdateChecker.installDowngradeElevated(dgPath) }
                                                .getOrElse { "ERR: ${it.message}" }
                                        }
                                        // If the install succeeded, Android kills
                                        // this process before we get here.
                                        dgMsg = out.trim().lines().firstOrNull().orEmpty()
                                        dgPhase = when {
                                            out.contains("Success", ignoreCase = true) -> "saved"
                                            // Production (user) builds refuse `pm install -d`
                                            // for release APKs — retrying can only fail the
                                            // same way, so route to the guided uninstall
                                            // flow instead (the APK is already in Downloads).
                                            out.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) ||
                                                out.contains("downgrade", ignoreCase = true) ||
                                                out.contains("version code", ignoreCase = true) -> "blocked"
                                            else -> "failed"
                                        }
                                    }
                                } else {
                                    UpdateChecker.requestUninstall(ctx)
                                }
                            }
                            else -> {
                                dgPhase = "downloading"
                                dgProgress = 0f
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        runCatching {
                                            UpdateChecker.downloadToPublicDownloads(
                                                ctx,
                                                selected.apkUrl!!,
                                                selected.apkName!!,
                                            ) { read, total ->
                                                if (total > 0) dgProgress = read.toFloat() / total
                                            }
                                        }
                                    }
                                    res.fold(
                                        onSuccess = { p -> dgPath = p; dgPhase = "saved" },
                                        onFailure = { dgMsg = it.message ?: "Download failed"; dgPhase = "failed" },
                                    )
                                }
                            }
                        }
                    }
                    .padding(10.dp),
            ) {
                val backendName = com.portalpad.app.PortalPadApp.instance.clickBackend.displayName
                val (dgLabel, dgSub) = when {
                    !apkReady ->
                        "No APK attached to this release" to "Use View on GitHub instead"
                    dgPhase == "idle" && dgIsLatest ->
                        "Download ${selected.tag}" to
                            if (elevated) "Replaces your newer unreleased build \u00b7 installs in place via $backendName \u00b7 app data is kept"
                            else "Replaces your newer unreleased build \u00b7 saves to Downloads \u00b7 uninstall required \u2014 Android blocks downgrades"
                    dgPhase == "idle" ->
                        "Download ${selected.tag} APK" to
                            if (elevated) "Rolls back past the current release \u00b7 installs in place via $backendName \u00b7 app data is kept"
                            else "Saves to Downloads \u00b7 uninstall PortalPad before installing \u2014 Android blocks downgrades"
                    dgPhase == "downloading" ->
                        "Downloading\u2026" to "Saving to Downloads"
                    dgPhase == "saved" && elevated ->
                        "Install ${selected.tag} \u2014 PortalPad will close" to
                            "In-place downgrade \u00b7 app data is kept; clear storage if the older build misbehaves"
                    dgPhase == "saved" ->
                        "Uninstall PortalPad" to
                            "APK saved to Downloads \u00b7 after uninstall, install it from Files (allow installs from Files if asked)"
                    dgPhase == "installing" ->
                        "Installing\u2026 PortalPad will close" to "If nothing happens, the device may have refused the downgrade"
                    dgPhase == "blocked" ->
                        "Uninstall PortalPad" to
                            "This device blocks in-place downgrades \u00b7 APK is in Downloads \u2014 install it from Files after (allow installs from Files if asked)"
                    else ->
                        "Downgrade failed \u2014 tap to retry" to
                            (dgMsg.ifEmpty { "This device may refuse in-place downgrades" })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (dgPhase == "saved") Icons.Default.CheckCircle else Icons.Default.Download,
                        contentDescription = null,
                        tint = dgTitleColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(dgLabel, color = dgTitleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (dgIsLatest) {
                        Spacer(Modifier.width(6.dp))
                        TagBadge("Latest", AbSuccess)
                    }
                }
                Text(
                    dgSub,
                    color = if (dgIsLatest) AbWarning else AbWarning.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
                if (dgPhase == "downloading") {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { dgProgress },
                        color = if (dgIsLatest) AbOnSurface else AbWarning,
                        trackColor = if (dgIsLatest) AbPrimaryDim else Color(0xFF5A4A26),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ActionChip(
                label = "View on GitHub",
                icon = { Icon(Icons.Default.OpenInNew, contentDescription = null, tint = AbOnSurfaceMuted, modifier = Modifier.size(13.dp)) },
                color = AbOnSurfaceMuted,
                modifier = Modifier.fillMaxWidth(),
            ) { openUrlExternal(ctx, selected.htmlUrl) }
            Spacer(Modifier.height(10.dp))
        } else if (phase !is UpdatePhase.Checking && selected != null) {
            ActionChip(
                label = "View on GitHub",
                icon = { Icon(Icons.Default.OpenInNew, contentDescription = null, tint = AbOnSurfaceMuted, modifier = Modifier.size(13.dp)) },
                color = AbOnSurfaceMuted,
                modifier = Modifier.fillMaxWidth(),
            ) { openUrlExternal(ctx, selected.htmlUrl) }
            Spacer(Modifier.height(10.dp))
        }

        // ----- auto-check toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AbSurface)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Check for updates on launch", color = AbOnSurface, fontSize = 13.sp)
                Text("At most once per day, Wi\u2011Fi or data", color = AbOnSurfaceDim, fontSize = 11.sp)
            }
            Switch(
                checked = autoCheck,
                onCheckedChange = {
                    autoCheck = it
                    UpdateChecker.setAutoCheckEnabled(ctx, it)
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = AbPrimary,
                    checkedThumbColor = AbOnSurface,
                    uncheckedTrackColor = AbSurfaceElevated,
                    uncheckedThumbColor = AbOnSurfaceMuted,
                ),
            )
        }
        Spacer(Modifier.height(8.dp))

        // ----- footer (ages via the minute ticker)
        val footerLead = when {
            result != null && ready?.offline == false -> "Checked ${agoText(result.checkedAtMs, nowMs)} \u00b7 "
            else -> ""
        }
        Text(
            footerLead + "github.com/Smart-Home-User/PortalPad",
            color = AbOnSurfaceDim,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

    }
}

@Composable
private fun FilterChipMini(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) AbOnSurface else AbOnSurfaceMuted,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) AbPrimary else AbSurfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun TagBadge(label: String, color: Color) {
    Text(
        label,
        color = color,
        fontSize = 10.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
private fun StatTile(label: String, value: Long?, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AbSurfaceVariant)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Text(
            if (value == null) "\u2014" else UpdateChecker.compactCount(value),
            color = AbOnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(label, color = AbOnSurfaceDim, fontSize = 10.sp)
    }
}

@Composable
private fun ActionChip(
    label: String,
    icon: @Composable () -> Unit,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, AbSurfaceElevated, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
    ) {
        icon()
        Spacer(Modifier.width(5.dp))
        Text(label, color = color, fontSize = 12.sp)
    }
}

private fun openUrlExternal(ctx: android.content.Context, url: String) {
    try {
        ctx.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (t: Throwable) {
        android.util.Log.w("UpdateChecker", "browser launch failed: ${t.message}")
    }
}

// ─────────────────────── GitHub-flavored release notes ──────────────────────
// Hand-rolled (no dependency): parse the release body into blocks and render each
// as a styled row. Covers headers, ordered/unordered/nested lists, task lists,
// blockquotes, fenced code, horizontal rules, and inline bold / italic /
// strikethrough / code / links. Themed to the notes card — not a pixel copy of
// github.com. Tables / images / raw HTML degrade to readable plain text.

private sealed interface MdBlock {
    data class Header(val level: Int, val text: AnnotatedString) : MdBlock
    data class Paragraph(val text: AnnotatedString) : MdBlock
    data class ListItem(val indent: Int, val marker: String, val text: AnnotatedString) : MdBlock
    data class Task(val indent: Int, val checked: Boolean, val text: AnnotatedString) : MdBlock
    data class Quote(val text: AnnotatedString) : MdBlock
    data class Code(val text: String) : MdBlock
    object Rule : MdBlock
    object Blank : MdBlock
}

private sealed interface NoteItem {
    data class Tag(val tag: String) : NoteItem
    data class Block(val block: MdBlock) : NoteItem
    object Gap : NoteItem
}

private val MD_H = Regex("^(#{1,6})\\s+(.*)$")
private val MD_HR = Regex("^\\s*([-*_])\\1{2,}\\s*$")
private val MD_TASK = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+(.*)$")
private val MD_UL = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val MD_OL = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val MD_QUOTE = Regex("^\\s*>\\s?(.*)$")

/** Trim a line and drop a trailing GFM hard-break backslash. */
private fun stripBreak(s: String): String = s.trimEnd().removeSuffix("\\").trimEnd()

/** Pull indented continuation lines into the current list/task item, joined as
 *  hard line breaks. Stops at a blank line, a new marker / header / rule / quote
 *  / code fence, or a non-indented line. Returns the next unconsumed index. */
private fun absorbContinuation(lines: List<String>, start: Int, sb: StringBuilder): Int {
    var j = start
    while (j < lines.size) {
        val c = lines[j]
        if (c.isBlank()) break
        if (c.trimStart().startsWith("```")) break
        if (MD_H.matches(c) || MD_HR.matches(c) || MD_QUOTE.matches(c)) break
        if (MD_TASK.matches(c) || MD_OL.matches(c) || MD_UL.matches(c)) break
        if (!(c.startsWith(" ") || c.startsWith("\t"))) break
        sb.append('\n').append(stripBreak(c.trim()))
        j++
    }
    return j
}

private fun parseMarkdown(md: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = md.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    var inCode = false
    val code = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = line.trimStart().startsWith("```")
        if (inCode) {
            if (fence) { out.add(MdBlock.Code(code.toString().trimEnd('\n'))); code.clear(); inCode = false }
            else code.append(line).append('\n')
            i++; continue
        }
        if (fence) { inCode = true; i++; continue }

        val task = MD_TASK.find(line)
        val ol = if (task == null) MD_OL.find(line) else null
        val ul = if (task == null && ol == null) MD_UL.find(line) else null
        when {
            line.isBlank() -> { out.add(MdBlock.Blank); i++ }
            MD_HR.matches(line) -> { out.add(MdBlock.Rule); i++ }
            MD_H.matches(line) -> {
                val m = MD_H.find(line)!!
                out.add(MdBlock.Header(m.groupValues[1].length, inlineMd(m.groupValues[2])))
                i++
            }
            MD_QUOTE.matches(line) -> { out.add(MdBlock.Quote(inlineMd(MD_QUOTE.find(line)!!.groupValues[1]))); i++ }
            task != null -> {
                val sb = StringBuilder(stripBreak(task.groupValues[3]))
                i = absorbContinuation(lines, i + 1, sb)
                out.add(MdBlock.Task(task.groupValues[1].length / 2, task.groupValues[2].lowercase() == "x", inlineMd(sb.toString())))
            }
            ol != null -> {
                val sb = StringBuilder(stripBreak(ol.groupValues[3]))
                i = absorbContinuation(lines, i + 1, sb)
                out.add(MdBlock.ListItem(ol.groupValues[1].length / 2, "${ol.groupValues[2]}.", inlineMd(sb.toString())))
            }
            ul != null -> {
                val ind = ul.groupValues[1].length / 2
                val sb = StringBuilder(stripBreak(ul.groupValues[2]))
                i = absorbContinuation(lines, i + 1, sb)
                out.add(MdBlock.ListItem(ind, if (ind % 2 == 1) "\u25E6" else "\u2022", inlineMd(sb.toString())))
            }
            else -> { out.add(MdBlock.Paragraph(inlineMd(stripBreak(line)))); i++ }
        }
    }
    if (inCode) out.add(MdBlock.Code(code.toString().trimEnd('\n')))
    return out
}

/** Inline spans → AnnotatedString: `code`, [links](url), bold-italic, bold,
 *  strikethrough, italic. URL targets are stored as "URL" annotations. */
private fun inlineMd(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val rest = s.substring(i)
        val code = Regex("^`([^`]+)`").find(rest)
        if (code != null) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = AbSurfaceElevated, color = AbOnSurface)) {
                append(code.groupValues[1])
            }
            i += code.value.length; continue
        }
        val link = Regex("^\\[([^\\]]+)]\\(([^)\\s]+)\\)").find(rest)
        if (link != null) {
            pushStringAnnotation("URL", link.groupValues[2])
            withStyle(SpanStyle(color = AbAccent, textDecoration = TextDecoration.Underline)) { append(link.groupValues[1]) }
            pop()
            i += link.value.length; continue
        }
        val bi = Regex("^\\*\\*\\*(.+?)\\*\\*\\*").find(rest)
        if (bi != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) { append(bi.groupValues[1]) }
            i += bi.value.length; continue
        }
        val bold = Regex("^\\*\\*(.+?)\\*\\*").find(rest) ?: Regex("^__(.+?)__").find(rest)
        if (bold != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold.groupValues[1]) }
            i += bold.value.length; continue
        }
        val strike = Regex("^~~(.+?)~~").find(rest)
        if (strike != null) {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(strike.groupValues[1]) }
            i += strike.value.length; continue
        }
        val ital = Regex("^\\*(.+?)\\*").find(rest) ?: Regex("^_(.+?)_").find(rest)
        if (ital != null) {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(ital.groupValues[1]) }
            i += ital.value.length; continue
        }
        append(s[i]); i++
    }
}

@Composable
private fun MdText(
    text: AnnotatedString,
    onUrl: (String) -> Unit,
    color: Color = AbOnSurfaceMuted,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    fontWeight: FontWeight? = null,
    italic: Boolean = false,
    modifier: Modifier = Modifier,
) {
    ClickableText(
        text = text,
        modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontStyle = if (italic) FontStyle.Italic else null,
            lineHeight = 18.sp,
        ),
        onClick = { offset ->
            text.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { onUrl(it.item) }
        },
    )
}

@Composable
private fun MdBlockView(block: MdBlock, onUrl: (String) -> Unit) {
    when (block) {
        is MdBlock.Header -> {
            val size = when (block.level) { 1 -> 16; 2 -> 15; 3 -> 14; else -> 13 }
            Column(Modifier.padding(top = 6.dp, bottom = 3.dp)) {
                MdText(
                    block.text, onUrl,
                    color = AbOnSurface,
                    fontSize = size.sp,
                    fontWeight = FontWeight.Bold,
                )
                // GitHub draws a rule under h1/h2 section headers.
                if (block.level <= 2) {
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(AbOnSurfaceDim.copy(alpha = 0.25f)))
                }
            }
        }
        is MdBlock.Paragraph -> MdText(block.text, onUrl, modifier = Modifier.padding(vertical = 1.dp))
        is MdBlock.ListItem -> Row(
            Modifier.padding(start = (block.indent * 14).dp, top = 1.dp, bottom = 1.dp),
        ) {
            Text("${block.marker} ", color = AbOnSurfaceMuted, fontSize = 12.sp, lineHeight = 18.sp)
            MdText(block.text, onUrl, modifier = Modifier.weight(1f))
        }
        is MdBlock.Task -> Row(
            Modifier.padding(start = (block.indent * 14).dp, top = 1.dp, bottom = 1.dp),
        ) {
            Text(if (block.checked) "\u2611 " else "\u2610 ", color = AbOnSurfaceMuted, fontSize = 12.sp, lineHeight = 18.sp)
            MdText(block.text, onUrl, modifier = Modifier.weight(1f))
        }
        is MdBlock.Quote -> Row(Modifier.padding(vertical = 1.dp)) {
            Box(Modifier.width(3.dp).height(18.dp).background(AbAccent.copy(alpha = 0.5f)))
            Spacer(Modifier.width(8.dp))
            MdText(block.text, onUrl, color = AbOnSurfaceDim, italic = true, modifier = Modifier.weight(1f))
        }
        is MdBlock.Code -> Text(
            block.text,
            color = AbOnSurface,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(AbSurfaceElevated)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        MdBlock.Rule -> Box(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(AbOnSurfaceDim.copy(alpha = 0.3f)),
        )
        MdBlock.Blank -> Spacer(Modifier.height(6.dp))
    }
}

private fun agoText(thenMs: Long, nowMs: Long): String {
    if (thenMs <= 0) return "unknown"
    val mins = (nowMs - thenMs) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins m ago"
        mins < 60 * 24 -> "${mins / 60} h ago"
        else -> "${mins / (60 * 24)} d ago"
    }
}
