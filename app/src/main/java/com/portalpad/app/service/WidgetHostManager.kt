package com.portalpad.app.service

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.OverlayWidget
import com.portalpad.app.data.WidgetOverlayConfig

/**
 * Owns the app-wide [AppWidgetHost] behind the widget overlay.
 *
 * One host, one stable HOST_ID — widget bindings are keyed to it, so it must
 * never change. The host [startListening]s only while the overlay is SHOWING:
 * widgets that update while the layer is hidden aren't visible anyway, and not
 * listening keeps provider broadcast traffic (and its RAM/CPU cost) at zero for
 * users who never summon the layer.
 *
 * Binding: [bindOrGrant] first tries the plain bindAppWidgetIdIfAllowed. When
 * the system refuses (we don't hold the BIND_APPWIDGET app-op yet), it runs
 * `appwidget grantbind` through elevated access (Shizuku / root) — the same
 * one-time whitelist launchers get — and retries. Without elevated access the
 * bind simply fails and the caller surfaces that; no partial state is left
 * behind (the caller deletes the allocated id).
 */
object WidgetHostManager {

    /** One installable widget provider, pre-labeled for UI. */
    data class ProviderEntry(
        val info: AppWidgetProviderInfo,
        val label: String,
        val appLabel: String,
    )

    /**
     * All installable widget providers across ALL user profiles — the plain
     * installedProviders only covers the primary profile, silently hiding
     * work-profile (and Samsung Secure Folder) app widgets. Sorted app-then-
     * widget for grouped display. Shared by the on-display picker and the
     * phone-side WidgetSearchActivity so both always show the same catalog.
     */
    /** Default grid span for a provider min value (dp interpretation, 70dp
     *  cells + 8dp gaps) — for picker subtitles ("8×4 cells"), mirroring the
     *  overlay's placement math. */
    fun defaultSpan(minDeclared: Int): Int =
        kotlin.math.ceil(minDeclared.toFloat() / 78f).toInt().coerceAtLeast(1)

    fun allProviders(context: Context): List<ProviderEntry> {
        val awm = AppWidgetManager.getInstance(context)
        val pm = context.packageManager
        val profiles = runCatching {
            (context.getSystemService(Context.USER_SERVICE) as android.os.UserManager)
                .userProfiles
        }.getOrDefault(emptyList())
        val infos =
            if (profiles.isEmpty()) {
                runCatching { awm.installedProviders }.getOrDefault(emptyList())
            } else {
                profiles.flatMap { profile ->
                    runCatching { awm.getInstalledProvidersForProfile(profile) }
                        .getOrDefault(emptyList())
                }
            }
        return infos
            .map { info ->
                val appLabel = runCatching {
                    pm.getApplicationInfo(info.provider.packageName, 0)
                        .loadLabel(pm).toString()
                }.getOrDefault(info.provider.packageName)
                val label = runCatching { info.loadLabel(pm) }
                    .getOrDefault(info.provider.className)
                ProviderEntry(info, label.toString(), appLabel)
            }
            .sortedWith(compareBy({ it.appLabel.lowercase() }, { it.label.lowercase() }))
    }
    private const val TAG = "WidgetHost"

    /** Stable host id — NEVER change: existing widget bindings are keyed to it. */
    const val HOST_ID = 0x50504144 // "PPAD"

    @Volatile private var host: AppWidgetHost? = null
    @Volatile private var listening = false
    @Volatile private var grantAttempted = false

    fun host(context: Context): AppWidgetHost {
        host?.let { return it }
        synchronized(this) {
            host?.let { return it }
            return AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }
        }
    }

    fun startListening(context: Context) {
        synchronized(this) {
            if (listening) return
            runCatching { host(context).startListening() }
                .onSuccess { listening = true }
                .onFailure { Log.w(TAG, "startListening failed", it) }
        }
    }

    fun stopListening() {
        synchronized(this) {
            if (!listening) return
            runCatching { host?.stopListening() }
                .onFailure { Log.w(TAG, "stopListening failed", it) }
            listening = false
        }
    }

    fun allocateId(context: Context): Int = host(context).allocateAppWidgetId()

    fun deleteId(context: Context, appWidgetId: Int) {
        runCatching { host(context).deleteAppWidgetId(appWidgetId) }
            .onFailure { Log.w(TAG, "deleteAppWidgetId($appWidgetId) failed", it) }
    }

    /**
     * Bind [appWidgetId] to [provider], escalating through `appwidget grantbind`
     * once per process if the plain bind is refused. BLOCKING (shell exec) —
     * call off the main thread. Returns true when bound.
     */
    fun bindOrGrant(context: Context, appWidgetId: Int, provider: ComponentName): Boolean {
        val awm = AppWidgetManager.getInstance(context)
        if (runCatching { awm.bindAppWidgetIdIfAllowed(appWidgetId, provider) }
                .getOrDefault(false)
        ) return true
        // Refused — try the one-time whitelist through elevated access, then retry.
        val app = context.applicationContext as? PortalPadApp ?: return false
        if (!app.access.isReady) {
            Log.w(TAG, "bind refused and no elevated access — cannot grantbind")
            return false
        }
        if (!grantAttempted) {
            grantAttempted = true
            val out = runCatching {
                app.access.execShell(
                    "appwidget grantbind --package ${context.packageName} --user 0",
                )
            }.getOrDefault("(exec failed)")
            Log.w(TAG, "grantbind attempted: $out")
        }
        return runCatching { awm.bindAppWidgetIdIfAllowed(appWidgetId, provider) }
            .getOrDefault(false)
    }

    /**
     * Restore pass: validate every saved widget's [OverlayWidget.appWidgetId]
     * against the live system and re-bind dead ids by provider component.
     *
     * Ids die on fresh installs / new devices (they aren't portable — the app
     * backup carries them, the system bindings don't follow). For each widget:
     *  - id valid and provider matches → keep as-is
     *  - id dead, provider installed → allocate + bind a new id (widget comes
     *    back UNCONFIGURED — per-widget config lives in the provider app and
     *    cannot be restored from here)
     *  - provider not installed → keep the entry untouched; the overlay renders
     *    it as a "not installed" placeholder so the user sees what's missing
     *    instead of wondering where a widget went
     *
     * BLOCKING (possible shell exec) — call from a background dispatcher.
     * Returns the updated config, or null when nothing changed.
     */
    fun validateAndRebind(context: Context, cfg: WidgetOverlayConfig): WidgetOverlayConfig? {
        if (cfg.widgets.isEmpty()) return null
        val awm = AppWidgetManager.getInstance(context)
        var changed = false
        val updated = cfg.widgets.map { w ->
            val provider = ComponentName.unflattenFromString(w.provider) ?: return@map w
            val live: AppWidgetProviderInfo? =
                runCatching { awm.getAppWidgetInfo(w.appWidgetId) }.getOrNull()
            if (live != null && live.provider == provider) return@map w // healthy
            // Is the provider even installed? (Query the installed list — a dead
            // id tells us nothing about the app's presence.)
            val installed = runCatching {
                awm.installedProviders.any { it.provider == provider }
            }.getOrDefault(false)
            if (!installed) return@map w // placeholder case — leave untouched
            val newId = allocateId(context)
            if (bindOrGrant(context, newId, provider)) {
                changed = true
                Log.w(TAG, "rebind: ${w.label} ${w.appWidgetId} → $newId")
                w.copy(appWidgetId = newId)
            } else {
                deleteId(context, newId)
                Log.w(TAG, "rebind FAILED for ${w.label} — keeping stale id")
                w
            }
        }
        return if (changed) cfg.copy(widgets = updated) else null
    }
}
