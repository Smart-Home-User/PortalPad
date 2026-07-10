package com.portalpad.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import com.portalpad.app.MainActivity
import com.portalpad.app.R
import com.portalpad.app.PortalPadApp
import com.portalpad.app.TrackpadActivity
import com.portalpad.app.data.DockDisplayMode
import com.portalpad.app.data.AppEntry
import com.portalpad.app.data.PreferencesRepository
import com.portalpad.app.presentation.DockOverlay
import com.portalpad.app.presentation.DockPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns:
 *  - the DisplayManager listener — keeps [PortalPadApp.externalDisplayId] in sync,
 *    attaches the [DockPresentation] when a secondary display appears, and
 *    auto-launches the trackpad UI on first detection
 *  - the floating bubble, which shows when the app is backgrounded
 *
 * What it does NOT do: launch the trackpad on Enable. That's MainActivity's job,
 * and only fires if a display is already present when the user taps Enable.
 */
class PortalPadForegroundService : Service() {

    private var dockOverlay: DockOverlay? = null
    private var dockPresentation: DockPresentation? = null
    private var cursorOverlay: com.portalpad.app.presentation.CursorOverlay? = null
    private var navButtonsOverlay: com.portalpad.app.presentation.NavButtonsOverlay? = null
    /** Desktop-windows (DeX-style) overlays. Only attached when the
     *  DESKTOP_MODE_ENABLED pref is on; null otherwise. */
    private var taskbarOverlay: com.portalpad.app.presentation.TaskbarOverlay? = null
    private var topWindowBarOverlay: com.portalpad.app.presentation.TopWindowBarOverlay? = null
    private var shizukuWarningOverlay: com.portalpad.app.presentation.ShizukuWarningOverlay? = null
    private var notificationPanel: com.portalpad.app.presentation.NotificationPanelOverlay? = null
    private var quickSettingsPanel: com.portalpad.app.presentation.QuickSettingsOverlay? = null

    // Phone-side "Tap to exit" pill shown over an app we launched on display 0.
    // phoneExitGen is the generation token: a newer launch (or a dismiss) bumps
    // it, so any in-flight poll/watchdog loop for an older launch exits.
    // phoneExitTaskId is the launched task to close on tap (-1 until located).
    @Volatile private var phoneExitBands: com.portalpad.app.presentation.PhoneExitBandsOverlay? = null
    @Volatile private var phoneExitTaskId: Int = -1
    @Volatile private var phoneExitGen: Int = 0
    /** Visible mirror of the trusted VD's content on the physical glasses
     *  display. Null when not in Display Glasses mode. */
    private var vdMirror: com.portalpad.app.presentation.VirtualDisplayMirror? = null
    /** Glasses logical display id whose panel layerStack is currently retargeted
     *  by the system-mirror path (>=0 = active). Lets teardown/reconcile restore
     *  the panel's original layerStack. -1 = no system mirror active. */
    private var systemMirrorGlassesId: Int = -1
    /** VD logical display id paired with [systemMirrorGlassesId], so the mirror
     *  can be re-applied after screen-on (One UI clobbers the panel's layerStack
     *  during the wake ColorFade). -1 when no mirror active. */
    private var systemMirrorVdId: Int = -1
    /** TRANSIENT mirror override for permission dialogs. When true,
     *  [refreshExternalDisplay]'s panel-feed selection treats the panel as if
     *  System mirror were on, WITHOUT touching the user's saved PANEL_SYSTEM_MIRROR
     *  pref — so an overlay-mode user's screen isn't blacked out by the security
     *  overlay-hide while an Android permission prompt is up. Reverts on a safety
     *  timeout (Stage 1) or when the dialog closes (Stage 2). Set only via
     *  [beginTransientMirrorForDialog] / cleared via [endTransientMirrorInternal]. */
    @Volatile private var transientMirrorForDialog = false
    /** Set when onCreate detects a sticky relaunch after a deliberate user stop
     *  and aborts before any setup — onDestroy/onStartCommand then skip
     *  teardown/restart for this instance (nothing was ever set up). */
    @Volatile private var abortedRelaunch = false
    /** True while a suspected mass window death (LMK/crash) has HELD one
     *  snapshot write — the next capture either confirms the reduced state
     *  (persists → written) or the layout recovered. See captureSessionNow. */
    @Volatile private var snapshotCollapsePending = false
    /** Pending safety-timeout revert for [transientMirrorForDialog] (main thread). */
    private var transientMirrorRevertJob: Runnable? = null
    /** Standalone Performance-overlay HUD used in system-mirror mode (there's no
     *  VD-mirror host to parent into). Null in overlay mode / when not shown. */
    private var mirrorHud: com.portalpad.app.presentation.PerformanceHud? = null
    /** Latest SurfaceFlinger delivered-fps for the mirror HUD, refreshed by a
     *  background poll while the mirror HUD is active. -1 = unavailable. */
    @Volatile private var mirrorSfFps: Float = -1f
    private var mirrorFpsJob: kotlinx.coroutines.Job? = null
    /** The display the cursor/nav/dock overlays were last attached to. Lets
     *  pref watchers re-attach overlays without a full VD teardown/rebuild. */
    private var lastOverlayDisplay: Display? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Pending one-shot cursor re-assert (see [scheduleOverlayReassert]). */
    private var pendingOverlayReassert: Runnable? = null
    private val displayManager by lazy { getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }
    private val bubble by lazy { FloatingBubbleManager(this) }
    private val virtualDisplaySession: com.portalpad.app.service.AirGlassesSession
        get() = (applicationContext as PortalPadApp).airGlassesSession
    private var autoDisableJob: Job? = null
    /** Wall-clock ms of when the last external display was removed (0 if currently present). */
    private var lastDisplayRemovedAt: Long = 0
    // Cached copies of two prefs the watchdog loops would otherwise re-read via
    // .first() every tick. Kept current by long-lived collectors; behavior-identical,
    // just avoids per-tick flow-collection setup.
    @Volatile private var floatingBubbleEnabled: Boolean = true
    @Volatile private var autoDisableAfterMin: Int = 0
    // Debounce for flap-recovery so a display that flaps repeatedly (e.g. a
    // screen-off that keeps reaping the Shizuku shell process) can't trigger a
    // storm of move/restore attempts.
    private var lastRecoveryAt: Long = 0L
    /** Polls the live external app so flap-recovery isn't blind (see startExternalAppTracker). */
    private var externalAppTrackerJob: Job? = null
    /**
     * The displayId we last successfully reconciled overlays/VD for. Used to
     * make [refreshExternalDisplay] idempotent: if onDisplayChanged fires
     * (which it does frequently — every input event, every window add/remove
     * on the display) but the external display itself hasn't changed AND no
     * pref change is forcing a re-reconcile, we skip the work. Without this,
     * cursor motion was triggering a feedback loop: cursor moves → window
     * activity on the display → onDisplayChanged → refreshExternalDisplay →
     * stop/restart VD → more window activity → onDisplayChanged → repeat.
     * That loop showed up in logs as dozens of "AirGlassesSession stopped"
     * lines per second and likely caused Chrome's PopupWindow (address-bar
     * suggestions) to dismiss defensively from the system churn.
     */
    private var lastReconciledDisplayId: Int? = null

    // True when the last reconcile WANTED a wrapped VD (backend ready) but VD
    // creation failed and we fell back to raw-glasses mode. On some devices (e.g.
    // Android 12 where the shell-uid VD create is rejected with "packageName must
    // match the calling uid") this fails every time, so retrying is pointless. The
    // VD watchdog uses this to avoid re-reconciling — and thus rebuilding the dock
    // + cursor overlays — every few seconds, which otherwise breaks auto-hide.
    private var lastAttachWasRawFallback = false

    // De-dups the "skipping display" log: pickExternalDisplay() runs on every
    // watchdog tick (~3s), so without this it logs the same built-in-display skip
    // forever. Only log when the skipped display identity changes.
    private var lastSkippedDisplaySig: String? = null

    // Tracks the VD id from the last reconcile that used a wrapped (trusted) VD.
    // Used to tell a FRESH/RECREATED VD (new id → empty → needs the wallpaper
    // backdrop re-laid) apart from a mid-session reconcile that reuses the same VD
    // (DOCK/DESKTOP toggles — must NOT re-lay, or it slams over the user's app).
    // This is what restores the wallpaper after Shizuku is stopped then restarted.
    private var lastVdId: Int = -1

    private val scope: CoroutineScope = MainScope()
    private var bubbleWatcher: Job? = null

    /**
     * Wall-clock ms after which we'll accept the next DisplayListener event.
     * Set after VD creation/teardown so the listener-loop (VD addition fires
     * onDisplayAdded which re-runs refreshExternalDisplay which would try to
     * wrap again) doesn't thrash. Matches a reference debounce pattern.
     */
    @Volatile private var suppressDisplayListenerUntil: Long = 0

    // Last (w,h,dpi) applyAspectToLiveVd() actually pushed to the live VD. The
    // aspectRatio pref flow can re-emit the SAME value repeatedly (any DataStore
    // write re-fires it), and each re-apply was needlessly restarting the VD AND
    // opening a 1500ms suppression window — the window that could swallow a
    // concurrent physical unplug. Skipping no-op re-applies closes that gap.
    // Cleared on teardown so the next attach always re-applies.
    @Volatile private var lastAppliedVdSpec: Triple<Int, Int, Int>? = null

    private fun suppressDisplayListener(reason: String, durationMs: Long = 1500) {
        suppressDisplayListenerUntil = System.currentTimeMillis() + durationMs
        Log.d(TAG, "Display listener suppressed ${durationMs}ms ($reason)")
    }

    private fun isDisplayListenerSuppressed(): Boolean =
        System.currentTimeMillis() < suppressDisplayListenerUntil ||
            // While a customization page is previewing, slider edits drive many
            // updateViewLayout calls on the overlays, each of which fires
            // onDisplayChanged. Refreshing the external display on those churns
            // both overlays (and pops the dock). Hold off the listener entirely
            // while previewing — the overlays restyle themselves in place.
            runCatching {
                val app = com.portalpad.app.PortalPadApp.instance
                app.zonePreviewActive.value || app.dockCustomizationActive.value ||
                    app.folderWindowPreviewActive.value || app.windowLimitActive.value
            }.getOrDefault(false)

    /**
     * Show the PortalPad spinner cover ON THE PHONE during a real display
     * connect/disconnect, but only while the app is in the foreground. Phone-only
     * — never renders onto the external display, keeping it clear of the delicate
     * VD/mirror setup path.
     *
     * @param holdUntilReady when true (connect), the cover stays up until input is
     *   actually live (dismissed from onSurfaceReady), with a safety timeout — so
     *   it covers the brief window where the trackpad isn't responsive yet. When
     *   false (disconnect), a short fixed cover, since there's nothing to wait for.
     */
    private fun maybeShowTransitionSpinner(reason: String, holdUntilReady: Boolean) {
        val app = applicationContext as? PortalPadApp ?: return
        if (!app.appInForeground.value) {
            Log.d(TAG, "Transition spinner skipped (app not foreground): $reason")
            return
        }
        Log.d(TAG, "Transition spinner shown ($reason, holdUntilReady=$holdUntilReady)")
        if (holdUntilReady) {
            // Stays up until dismissTransitionSpinnerWhenReady() is called from
            // onSurfaceReady; maxMs is a safety net if connect never completes.
            com.portalpad.app.presentation.DisableTransitionOverlay
                .showUntilDismissed(applicationContext, maxMs = 6000L, status = "Connecting to display…")
        } else {
            com.portalpad.app.presentation.DisableTransitionOverlay
                .show(applicationContext, durationMs = 1100L, status = "Disconnecting…")
        }
    }

    /**
     * Lift the connect spinner once the mirror surface is live AND the overlays
     * have been re-asserted (input genuinely responsive). Called from
     * onSurfaceReady; we wait the reassert delay + a cushion so the cover lifts
     * right when taps start landing, not before. The cushion is sized to cover
     * the initial overlay-reassert burst (the ~1s window after surface-ready
     * where the cursor/dock overlays are still being re-added and a tap can land
     * mid-swap and be lost) — so the user doesn't tap a not-yet-live trackpad on
     * connect. This HIDES the brief startup dead-window; it doesn't shorten it.
     */
    private fun dismissTransitionSpinnerWhenReady() {
        mainHandler.postDelayed(
            { com.portalpad.app.presentation.DisableTransitionOverlay.dismiss() },
            OVERLAY_REASSERT_DELAY_MS + 1250L,
        )
    }


    /**
     * Stop the Extinguish service (best-effort, via Shizuku) to clear any IME
     * wedge it can leave after screen on/off cycles. Stop-only — we deliberately
     * do NOT restart it, because a running (post-screen-cycle) Extinguish service
     * is what strands the phone keyboard. The user can reopen Extinguish himself
     * if he wants it. No-ops if Extinguish isn't installed or Shizuku isn't ready.
     * Called both on display disconnect and when PortalPad's service is disabled.
     */
    private fun stopExtinguishForKeyboardSafety(reason: String) {
        val pkg = "own.moderpach.extinguish"
        val svc = "$pkg/.service.ExtinguishService"
        val app = applicationContext as? PortalPadApp ?: return
        val installed = runCatching { packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        if (!installed) return
        val shizuku = (app.clickBackend as? ClickBackend.ShizukuUserService)?.backend
        if (shizuku == null || !shizuku.isReady) {
            Log.d(TAG, "stopExtinguishForKeyboardSafety($reason): skipped (Shizuku not ready)")
            return
        }
        runCatching {
            val out = shizuku.runCommand("am startservice -n $svc --ei stop 1")
            Log.d(TAG, "stopExtinguishForKeyboardSafety($reason): stop -> $out")
        }
    }

    private fun cleanupExtinguishOnDisconnect() = stopExtinguishForKeyboardSafety("disconnect")

    // onDisplayChanged fires in storms — every window/overlay/cursor operation
    // on the display (including our own) re-triggers it, which re-enters
    // refreshExternalDisplay and churns the VD/overlays. The idempotent guard
    // inside refresh helps, but the sheer volume of re-entrant calls (and the
    // force=true paths) still cause thrash that strands launches on the phone.
    //
    // Coalesce: instead of refreshing on every onDisplayChanged, post a single
    // delayed refresh and cancel/reschedule it if more changes arrive within
    // the window. Rapid bursts collapse into one refresh after they settle.
    // onDisplayAdded / onDisplayRemoved stay immediate (real connect/disconnect).
    private val pendingChangedRefresh = Runnable {
        if (!isDisplayListenerSuppressed()) {
            refreshExternalDisplay(trigger = "displayChanged(debounced)")
        }
    }

    private fun scheduleDebouncedChangedRefresh() {
        mainHandler.removeCallbacks(pendingChangedRefresh)
        mainHandler.postDelayed(pendingChangedRefresh, DISPLAY_CHANGED_DEBOUNCE_MS)
    }

    // ── DIAGNOSTIC: external-display mode dump ────────────────────────────────
    // 32:9 ultrawide and 3D SBS both arrive as a 3840x1080 buffer, so resolution
    // alone can't distinguish them. This dumps everything the OS DOES expose about
    // the display — name, active mode, refresh, HDR, flags, type, supported modes —
    // so flipping glasses modes and diffing the two log lines reveals whether any
    // of those fields actually changes (i.e. whether OS-side auto-detection is even
    // possible). Logged only when the signature CHANGES, since onDisplayChanged
    // fires on every window add/move/resize. Filter: `adb logcat -s PortalPadDisplayDiag`.
    @Volatile private var lastDisplayDiagSig: String? = null
    private fun logDisplayDiag(displayId: Int, trigger: String) {
        runCatching {
            val d = displayManager.getDisplay(displayId) ?: return
            if (!com.portalpad.app.DisplayUtil.isRealExternalDisplay(d)) return
            val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") d.getRealMetrics(it) }
            val mode = d.mode
            val supported = d.supportedModes.joinToString(",") {
                "#${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${"%.1f".format(it.refreshRate)}"
            }
            @Suppress("DEPRECATION") val rot = d.rotation
            val type = runCatching {
                android.view.Display::class.java.getMethod("getType").invoke(d) as? Int
            }.getOrNull()
            val sig = "id=$displayId name='${d.name}' real=${m.widthPixels}x${m.heightPixels} " +
                "mode=#${mode.modeId}:${mode.physicalWidth}x${mode.physicalHeight}" +
                "@${"%.1f".format(mode.refreshRate)} hdr=${d.isHdr} type=$type " +
                "flags=0x${Integer.toHexString(d.flags)} rot=$rot supported=[$supported]"
            // Deeper fields that could still distinguish ultrawide-vs-SBS IF the
            // firmware exposes any per-mode difference (EDID/product, physical DPI,
            // cutout, HDR/color). If these are also identical across modes, non-root
            // DisplayManager detection is genuinely impossible.
            val pi = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val p = runCatching { d.deviceProductInfo }.getOrNull()
                if (p == null) "null" else buildString {
                    append("name='").append(runCatching { p.name }.getOrNull()).append("'")
                    append(" pnp=").append(runCatching { p.manufacturerPnpId }.getOrNull())
                    append(" prod=").append(runCatching { p.productId }.getOrNull())
                    append(" year=").append(runCatching { p.modelYear }.getOrNull())
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        append(" conn=").append(runCatching { p.connectionToSinkType }.getOrNull())
                    }
                }
            } else "n/a"
            val cutout = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 29) (if (d.cutout != null) "present" else "none") else "n/a"
            }.getOrNull()
            val hdrCap = runCatching {
                val h = d.hdrCapabilities
                if (h != null) "hdrTypes=${h.supportedHdrTypes.joinToString("/")} maxLum=${"%.0f".format(h.desiredMaxLuminance)}/minLum=${"%.2f".format(h.desiredMinLuminance)}" else "null"
            }.getOrNull()
            val gamut = runCatching { "wideGamut=${d.isWideColorGamut}" }.getOrNull()
            val deep = "xdpi=${"%.1f".format(m.xdpi)} ydpi=${"%.1f".format(m.ydpi)} " +
                "density=${m.density} densityDpi=${m.densityDpi} product[$pi] cutout=$cutout $hdrCap $gamut"
            val full = "$sig | $deep"
            if (full != lastDisplayDiagSig) {
                lastDisplayDiagSig = full
                Log.d("PortalPadDisplayDiag", "[$trigger] $sig")
                Log.d("PortalPadDisplayDiag", "[$trigger] DEEP $deep")
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            logDisplayDiag(displayId, "added")
            runCatching {
                val a = applicationContext as? PortalPadApp
                Log.d(
                    "PortalPadSleep",
                    "DISPLAY_ADDED id=$displayId " +
                        "(backendReady=${a?.activeBoundBackend?.isReady == true})",
                )
            }
            if (isDisplayListenerSuppressed()) {
                Log.d(TAG, "Display added (suppressed): $displayId")
                return
            }
            Log.d(TAG, "Display added: $displayId")
            cancelGraceTeardown("display $displayId added")
            // A re-add that closely follows a removal is a flap-recreate (the
            // shell process was reaped + the VD recreated with a new id), NOT a
            // fresh user attach. Captured before the refresh so it reflects the gap.
            val flapMs = if (lastDisplayRemovedAt > 0L)
                System.currentTimeMillis() - lastDisplayRemovedAt else Long.MAX_VALUE
            // A flap (quick re-add after a removal) means recoverStrandedSessionOnFlap
            // below will bring the prior windows back — so suppress the Auto-Launch
            // wallpaper/app on this attach to avoid stacking a spurious extra window
            // over the recovered session. Trackpad re-activation still fires.
            val isFlap = flapMs < 30_000L
            maybeShowTransitionSpinner("displayAdded", holdUntilReady = true)
            refreshExternalDisplay(
                autoLaunchOnFirstAttach = true,
                trigger = "displayAdded($displayId)",
                suppressAutoStartContent = isFlap,
            )
            // External display connected — let the external mouse auto-resume if
            // opted in. Delayed so the display finishes setting up (injector
            // displayId assigned) before discovery runs.
            runCatching {
                (applicationContext as? PortalPadApp)?.btMouse?.maybeAutoArm(delayMs = 1500L)
            }
            if (isFlap) {
                Log.d("PortalPadSleep", "FLAP-RECREATE (gone ${flapMs}ms) -> recover stranded session")
                recoverStrandedSessionOnFlap(displayId)
            }
        }
        override fun onDisplayChanged(displayId: Int) {
            logDisplayDiag(displayId, "changed")
            if (isDisplayListenerSuppressed()) return
            // A portrait-only app (Reddit, etc.) can rotate the external display
            // to portrait, changing its height. Update the injector's cursor
            // bounds immediately (not debounced) so the cursor can still reach the
            // bottom after rotation — otherwise it stays clamped to the old
            // landscape height. No-op when the size hasn't actually changed.
            (applicationContext as? PortalPadApp)?.let { a ->
                if (displayId == a.injector.displayId) {
                    runCatching { a.injector.updateDisplaySizeForRotation() }
                }
            }
            // NOTE: force=false. onDisplayChanged fires frequently — every
            // window add/move/resize on the display, including our own
            // overlay updates. The idempotent skip inside refresh prevents
            // the feedback loop that was tearing down VD/overlays on every
            // cursor move. Additionally debounced: rapid bursts collapse into
            // a single refresh after they settle, killing the churn that was
            // stranding Home/app launches on the phone.
            scheduleDebouncedChangedRefresh()
        }
        override fun onDisplayRemoved(displayId: Int) {
            val physId = (applicationContext as? PortalPadApp)?.physicalExternalDisplayId?.value
            val isPhysicalUnplug = physId != null && displayId == physId
            val suppressed = isDisplayListenerSuppressed()
            runCatching {
                val a = applicationContext as? PortalPadApp
                val extId = a?.externalDisplayId?.value
                Log.d(
                    "PortalPadSleep",
                    "DISPLAY_REMOVED id=$displayId (extId=$extId " +
                        "isExternal=${displayId == extId} " +
                        "backendReady=${a?.activeBoundBackend?.isReady == true})",
                )
            }
            // The suppression window exists to swallow the VD's OWN add/remove
            // churn when we restart it (AirGlassesSession start/stop, aspect-live).
            // It must NEVER swallow the physical panel actually being unplugged:
            // that skips the disconnect bookkeeping below (gone-timestamp + ext-id
            // clear), which kills BOTH the disconnect banner and the flap recovery
            // on the next replug. So a real physical unplug bypasses suppression;
            // only non-physical (VD) removals honor it.
            if (suppressed && !isPhysicalUnplug) {
                Log.d(TAG, "Display removed (suppressed): $displayId")
                // Orphan sweep: if teardown already cleared the tracked external
                // id, NO dock window should exist anymore — but a stale queued
                // attach can sneak one in between the physical teardown and this
                // (deliberately swallowed) VD removal, and it lands on the phone
                // screen with nobody left to clean it. dismissDock is idempotent;
                // in the healthy case this is a no-op.
                val extNow = (applicationContext as? PortalPadApp)?.externalDisplayId?.value
                if (extNow == null) {
                    Log.d(TAG, "orphan sweep on suppressed removal (no tracked external) → dismissDock")
                    runCatching { dismissDock() }
                }
                return
            }
            Log.d(
                TAG,
                "Display removed: $displayId" +
                    if (suppressed && isPhysicalUnplug) " (physical unplug, bypassed suppression)" else "",
            )
            maybeShowTransitionSpinner("displayRemoved", holdUntilReady = false)
            // DIAG (disconnect-freeze): time each main-thread teardown step so a
            // capture shows exactly which one blocks. Look for "DISC-STEP".
            fun discStep(name: String, block: () -> Unit) {
                val t0 = android.os.SystemClock.uptimeMillis()
                runCatching { block() }
                val dt = android.os.SystemClock.uptimeMillis() - t0
                if (dt > 50) Log.w(TAG, "DISC-STEP $name took ${dt}ms")
                else Log.d(TAG, "DISC-STEP $name ${dt}ms")
            }
            // Reset IME policy before tearing down so a dangling policy on the
            // departing display can't strand the phone keyboard.
            discStep("resetImePolicy") { (applicationContext as PortalPadApp).injector.resetImePolicy() }
            // Backup keyboard-wedge cleanup: Extinguish can leave the phone IME
            // wedged after screen on/off cycles during the session. Stopping its
            // service clears that; we then restart it so the user can still use
            // Extinguish elsewhere. Best-effort via Shizuku; no-op without it.
            // Guarded so it can NEVER abort the rest of teardown (dock/VD dismiss,
            // id clearing) — same defensive pattern as the IME reset above.
            discStep("cleanupExtinguish") { cleanupExtinguishOnDisconnect() }
            discStep("dismissDock") { dismissDock() }
            discStep("dismissVdMirror") { dismissVdMirror() }
            // Stop the transient-mirror machinery so a late dialog-close tick or
            // safety-net timer can't revert against the (now gone, or freshly
            // replugged) display, and so a replug comes back on the user's real
            // mode. No reconcile — the display is departing.
            discStep("cancelTransientMirror") { cancelTransientMirrorForTeardown() }
            discStep("bubbleHide") { bubble.hide() }
            // Stop media (e.g. Netflix) RIGHT AWAY: its task stays alive briefly
            // under the grace window, so without this the audio keeps playing out
            // the phone speaker after the external display it was on is gone.
            // We momentarily take AUDIOFOCUS_GAIN, which makes the current player
            // lose focus and pause, then abandon it. This needs no Shizuku/root
            // (so it still works if elevated access dropped mid-session) and only
            // fires when something is actually playing. Trade-off: a resolution
            // flap also pauses playback, and this pauses whatever holds audio
            // focus (not strictly the glasses app) — both acceptable on unplug.
            discStep("pauseMediaOnDisconnect") {
                val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                if (am != null && am.isMusicActive) {
                    val req = android.media.AudioFocusRequest.Builder(
                        android.media.AudioManager.AUDIOFOCUS_GAIN,
                    ).setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    ).build()
                    runCatching { am.requestAudioFocus(req) }
                    // Abandon shortly after so we don't hold focus; the player has
                    // already paused on the permanent-loss and won't auto-resume.
                    mainHandler.postDelayed({
                        runCatching { am.abandonAudioFocusRequest(req) }
                    }, 600L)
                }
            }
            // ── GRACE-PERIOD disconnect model (rollback of the parking design,
            // which needed compensation on compensation: curtain, HOME pushes,
            // mode-conversion fallbacks, freeform floaters on the phone). The
            // VD and its tasks are simply KEPT ALIVE for a grace window:
            //   • replug within grace (a glasses-side resolution switch IS a
            //     ~2-3s disconnect/reconnect) → rebind the same VD to the new
            //     panel, resize in place — windows and their state never
            //     disturbed, nothing ever touches the phone screen;
            //   • no replug → the ORIGINAL destructive teardown runs, 10s
            //     late — disconnect UX exactly as it always was.
            // The "External Display Disconnected" banner is driven by the
            // PHYSICAL id (cleared inline below), so it shows immediately.
            val tdApp = applicationContext as PortalPadApp
            lastDisplayRemovedAt = System.currentTimeMillis()
            tdApp.setPhysicalExternalDisplayId(null)
            tdApp.flapRecoveryUntilMs = System.currentTimeMillis() + 25_000L
            lastReconciledDisplayId = null
            lastAppliedVdSpec = null
            armGraceTeardown()
        }
    }

    /** Grace window before a physical unplug becomes a destructive teardown.
     *  8s: long enough to ride out a glasses-side resolution flap (the glasses
     *  disconnect/reconnect in ~2-3s during a refresh-rate switch, and we want
     *  the session preserved across that), but short enough that a REAL unplug
     *  tears the VD ("PortalPad Session") down promptly. While the VD lives, its
     *  tasks stay alive — so media keeps playing (audio out the phone), other
     *  apps still list the VD as a connected display, and the session bubble
     *  lingers; all three clear the instant the VD is destroyed. 60s made that
     *  linger up to a minute. Cost of 8s: a deliberate unplug→replug slower than
     *  ~8s restarts the session instead of resuming it. The visible disconnect
     *  UX (banner, return-to-main) runs on the physical-id timeline (~5s) and is
     *  independent of this. */
    private val disconnectGraceMs = 4_000L
    /** Absolute SAFETY-NET timeout for a [transientMirrorForDialog] flip. The
     *  PRIMARY revert is now dialog-close detection (FreeformManager's close
     *  watch calls [endTransientMirrorForDialog] the moment the prompt closes),
     *  so this only fires if that watch ever wedges — hence generous, to avoid
     *  reverting into a black screen while a prompt the user is still reading is
     *  open. Tunable. */
    private val TRANSIENT_MIRROR_REVERT_MS = 120_000L
    // System mirror retargets the physical panel onto the VD's layerStack, so on
    // disconnect the system churns on the still-alive, previously-scanned-out VD
    // and blocks our main thread for seconds. In mirror mode we therefore tear
    // the VD down almost immediately instead of holding the full grace (still
    // long enough to catch a very quick resolution flap). Overlay mode keeps the
    // full 60s grace. Set true while a mirror session is active; NOT cleared by
    // dismissVdMirror, so armGraceTeardown can still see it at disconnect.
    private val mirrorDisconnectGraceMs = 500L
    @Volatile private var mirrorModeActive = false

    @Volatile private var graceTeardownPending = false

    private val graceTeardownRunnable = Runnable {
        graceTeardownPending = false
        val app = applicationContext as PortalPadApp
        if (app.physicalExternalDisplayId.value != null) {
            Log.d("PortalPadSleep", "grace teardown SKIPPED — display is back")
            return@Runnable
        }
        Log.d("PortalPadSleep", "grace expired (no replug in ${disconnectGraceMs}ms) → full session teardown")
        // The backdrop lives on the VD — only finish it now that the VD dies
        // (finishing it earlier would be pointless; keeping it past the VD's
        // death risks the system migrating it onto the phone).
        runCatching { com.portalpad.app.WallpaperActivity.finishIfShowing() }
        if (virtualDisplaySession.isActive) suppressDisplayListener("displayRemoved-stop")
        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { virtualDisplaySession.stop() }
                app.setExternalDisplayId(null)
                app.setVirtualDisplayId(null)
                // force_resizable_activities left ON system-wide crashes
                // SystemUI's split coordinator on later launches. Re-enabled
                // on the next connect.
                runCatching { app.freeform.disableFreeform() }
            }
        }
    }

    /** True when the VD lived CONTINUOUSLY through the last disconnect (grace
     *  cancelled by a replug). Gates the liveness filter below: on a surviving
     *  VD, a snapshot window whose task id is dead was closed BY THE USER
     *  (e.g. cleared recents) and must not be resurrected; after a real
     *  teardown, dead ids are the teardown's doing and restore is legitimate. */
    @Volatile private var vdSurvivedDisconnect = false

    private fun armGraceTeardown() {
        vdSurvivedDisconnect = false
        mainHandler.removeCallbacks(graceTeardownRunnable)
        graceTeardownPending = true
        // Full grace for all modes. (A short mirror-mode grace was tried and
        // reverted: tearing the VD down early landed inside the disconnect
        // transition and ADDED a main-thread window-op freeze rather than
        // avoiding one.)
        mainHandler.postDelayed(graceTeardownRunnable, disconnectGraceMs)
        Log.d("PortalPadSleep", "disconnect → grace armed (${disconnectGraceMs}ms, mirror=$mirrorModeActive): VD + windows kept alive")
    }

    private fun cancelGraceTeardown(reason: String) {
        if (!graceTeardownPending) return
        graceTeardownPending = false
        // VERIFY survival instead of asserting it: another teardown path can
        // kill the VD in the gap between the disconnect and this cancel
        // (observed: a debounced displayChanged reconcile raced the
        // DISPLAY_REMOVED handler and stopped the VD 500ms BEFORE grace was
        // even armed — the cancel then claimed "windows intact" over a corpse,
        // and the liveness filter trusted the claim and dropped the entire
        // restore as "user-closed").
        vdSurvivedDisconnect = virtualDisplaySession.isActive
        mainHandler.removeCallbacks(graceTeardownRunnable)
        if (vdSurvivedDisconnect) {
            Log.d("PortalPadSleep", "grace teardown CANCELLED ($reason) — session survives, windows intact")
        } else {
            Log.w(
                "PortalPadSleep",
                "grace teardown CANCELLED ($reason) — but the VD is already DEAD " +
                    "(another path tore it down mid-grace); treating as full teardown so restore runs",
            )
        }
    }

    // Held for the lifetime of an active foreground session so the CPU doesn't
    // fall into deep doze when the phone screen is turned off by hardware. The
    // VirtualDisplay is owned by the Shizuku/shell process; if the system reaps
    // that process during sleep, the display is destroyed and whatever was on it
    // (e.g. a SmartTube video) gets evacuated to the phone display — which is why
    // it appears the instant the screen wakes. A partial wake lock keeps the
    // session alive across a hardware screen-off. Released in onDestroy.
    private var sessionWakeLock: android.os.PowerManager.WakeLock? = null

    // DIAGNOSTIC (PortalPadSleep): logs the sleep/wake cycle + backend/VD state so
    // we can see WHAT dies during a hardware screen-off (shell process vs VD vs
    // this service). Capture with: adb logcat -s PortalPadSleep:D
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            runCatching {
                val app = applicationContext as? PortalPadApp ?: return
                val extId = app.externalDisplayId.value
                val vdAlive = extId != null &&
                    runCatching { displayManager.getDisplay(extId) != null }.getOrDefault(false)
                val backendReady = app.activeBoundBackend?.isReady == true
                Log.d(
                    "PortalPadSleep",
                    "${intent.action?.substringAfterLast('.')} extId=$extId " +
                        "vdAlive=$vdAlive backendReady=$backendReady",
                )
                // ── Keyguard re-assert (SCREEN_ON, locked, display connected) ──
                // One UI's lock transition demotes the top task on the FIRST
                // keyguard appearance after an unlock, regardless of its
                // SHOW_WHEN_LOCKED attribute (measured: task sent TO_BACK; the
                // second off/on in the same locked session then occludes fine).
                // Flag hygiene can't win that fight — the reliable lever is
                // ACTIVELY launching the showWhenLocked activity while the
                // keyguard is up, which occludes deterministically (the
                // alarm-clock pattern). singleTask + REORDER_TO_FRONT means an
                // already-front activity is a visual no-op and its mode is kept.
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    // System mirror: One UI resets the physical panel's
                    // layerStack to its native value during the screen-on
                    // ColorFade, which drops our retarget → black glasses. Re-
                    // apply the retarget shortly after wake (past the fade), off
                    // the main thread.
                    if (systemMirrorGlassesId >= 0 && systemMirrorVdId >= 0) {
                        val gid = systemMirrorGlassesId
                        val vd = systemMirrorVdId
                        // Two staggered attempts: the first re-applies as soon as
                        // the wake ColorFade is likely done (shorter flash); the
                        // second is a backup if the fade ran long. The idempotent
                        // original-layerStack save keeps restore correct.
                        for (d in longArrayOf(400L, 850L)) {
                            mainHandler.postDelayed({
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val ok = runCatching {
                                        virtualDisplaySession.startSystemMirror(gid, vd)
                                    }.getOrDefault(false)
                                    Log.w(TAG, "panel-feed: re-applied mirror retarget after wake @${d}ms (ok=$ok)")
                                }
                            }, d)
                        }
                    }
                    val km = runCatching {
                        getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                    }.getOrNull()
                    val locked = km?.isKeyguardLocked == true
                    val physConnected = app.physicalExternalDisplayId.value != null
                    val allowLock = runCatching {
                        kotlinx.coroutines.runBlocking {
                            app.prefs.bool(
                                com.portalpad.app.data.PreferencesRepository.Keys.ALLOW_LOCKSCREEN,
                                true,
                            ).first()
                        }
                    }.getOrDefault(true)
                    if (locked && physConnected && allowLock) {
                        Log.d("PortalPadSleep", "SCREEN_ON locked + display connected → re-asserting over keyguard")
                        // NO launch here — deliberately. History: a reorder
                        // returned DELIVERED_TO_TOP (no transition, no occlusion
                        // re-eval); the trampoline that replaced it turned out to
                        // SABOTAGE occlusion — measured to the millisecond:
                        // DOZING→OCCLUDED had already STARTED on wake (the
                        // showWhenLocked birth-attribute + relayout fixes work),
                        // and the translucent trampoline's launch CANCELED it
                        // into PRIMARY_BOUNCER (keyguard occlusion is granted
                        // only to OPAQUE activities; a translucent launch over
                        // the keyguard raises the bouncer instead). The flag
                        // machinery alone is the fix; this log line stays so
                        // captures show the moment's state.
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
        // §4.3 relaunch guard. One UI stops this FGS on its own (observed:
        // recents Close-All at 23:46:29 → START_STICKY relaunch 2s later), and
        // that relaunch is DESIRED — the user was mid-session. But a stray
        // sticky relaunch after the user deliberately pressed Stop must NOT
        // resurrect the session. The companion start()/stop() maintain the
        // marker; only a system-initiated relaunch can see it as true here.
        // startForeground first — the FGS contract applies to relaunches too.
        val userStopped = runCatching {
            kotlinx.coroutines.runBlocking {
                (applicationContext as PortalPadApp).prefs
                    .bool(PreferencesRepository.Keys.SERVICE_USER_STOPPED, default = false)
                    .first()
            }
        }.getOrDefault(false)
        if (userStopped) {
            runCatching { startForeground(NOTIF_ID, buildNotification()) }
            abortedRelaunch = true
            Log.w(TAG, "onCreate: sticky relaunch after a deliberate user stop — honoring the stop (stopSelf)")
            stopSelf()
            return
        }

        // Live external-display DPI: apply the user's DPI to the running VD via
        // `wm density` whenever the pref changes, so the slider/text field take
        // effect without a reconnect. Debounced (200ms) so dragging doesn't spam
        // shell calls. Initial DPI is set at VD creation; this handles live changes
        // while a VD is active. Dock/top bar are unaffected (they divide out /
        // hardcode their own density).
        scope.launch {
            val a = applicationContext as PortalPadApp
            var applyJob: kotlinx.coroutines.Job? = null
            a.prefs.displayDpi.collect { userDpi ->
                applyJob?.cancel()
                applyJob = launch {
                    kotlinx.coroutines.delay(200)
                    applyDpiToLiveVd(a, userDpi)
                }
            }
        }
        // Live panel-feed switch: flipping "System mirror" mid-session should
        // take effect immediately (no stop/start or replug). We drop the first
        // emission (the initial value is already applied by the normal setup)
        // and, on any real change, force a full external re-reconcile — which
        // tears down the current panel feed (dismissVdMirror restores the
        // retargeted layerStack / stops the mirror HUD) and rebuilds it in the
        // newly-selected mode. The VD itself persists, so the switch is quick.
        scope.launch {
            val a = applicationContext as PortalPadApp
            a.prefs.panelSystemMirror
                // distinctUntilChanged + drop(1): DataStore flows re-emit the WHOLE
                // preferences object on ANY key write, so unrelated writes during a
                // launch / permission flow (recordExternalApp, markExternalLaunch,
                // setLastForegroundApp) were re-firing this forced re-reconcile with
                // the SAME mirror value — a storm of panel-feed teardown/rebuilds that
                // recreated the cursor ~30x (so it never held a stable frame = "cursor
                // lost" in mirror+desktop) and transiently re-established the mirror
                // (phone briefly shown on external). Same guard DOCK_ENABLED /
                // DESKTOP_MODE already use; dedup FIRST, then drop the initial value.
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    Log.w(TAG, "panel-feed: System mirror toggled → $it, flipping feed live")
                    mainHandler.post {
                        // Narrow feed flip; full reconcile only when there's no
                        // live session to flip (e.g. toggled while disconnected —
                        // the reconcile no-ops or applies at next attach).
                        if (!setPanelFeed(toMirror = it, trigger = "panel-mirror-toggle")) {
                            refreshExternalDisplay(force = true, trigger = "panel-mirror-toggle")
                        }
                    }
                }
        }
        // Live external-display aspect ratio: resize the running VD in place when
        // the pref changes, so a new ratio takes effect immediately instead of on
        // reconnect. Debounced (200ms) like the DPI path; a no-op when no VD is
        // active (the creation path applies the stored ratio at attach time).
        scope.launch {
            val a = applicationContext as PortalPadApp
            var applyJob: kotlinx.coroutines.Job? = null
            a.prefs.aspectRatio.collect { aspect ->
                applyJob?.cancel()
                applyJob = launch {
                    kotlinx.coroutines.delay(200)
                    applyAspectToLiveVd(a, aspect)
                }
            }
        }
        // Live screen-size + Side Mode: scale (and, in Side Mode, corner-offset)
        // the VD frame in the GL mirror. No VD resize — the renderer shrinks/shifts
        // its output quad. Side Mode is per input interface, so this recomputes (and
        // repositions) whenever the active interface, its config, or the centered
        // size changes.
        scope.launch {
            val a = applicationContext as PortalPadApp
            kotlinx.coroutines.flow.combine(
                a.prefs.screenSizePct,
                a.prefs.sideModeConfig,
                a.prefs.activeSideInterface,
            ) { pct, cfg, iface -> Triple(pct, cfg, iface) }
                .collect { (pct, cfg, iface) ->
                    val si = cfg.forInterface(iface)
                    if (si.enabled) {
                        // Dock the frame into a corner at that corner's own size.
                        // The quad spans clip-space [-s, s]; shifting its center by
                        // (1−s) toward the corner pins that corner to the panel edge.
                        val s = si.sizeFor(si.corner).coerceIn(40, 90) / 100f
                        val ox = (1f - s) * when (si.corner) {
                            com.portalpad.app.data.SideCorner.TL,
                            com.portalpad.app.data.SideCorner.BL,
                            -> -1f
                            com.portalpad.app.data.SideCorner.TR,
                            com.portalpad.app.data.SideCorner.BR,
                            -> 1f
                        }
                        val oy = (1f - s) * when (si.corner) {
                            com.portalpad.app.data.SideCorner.TL,
                            com.portalpad.app.data.SideCorner.TR,
                            -> 1f
                            com.portalpad.app.data.SideCorner.BL,
                            com.portalpad.app.data.SideCorner.BR,
                            -> -1f
                        }
                        vdMirror?.setScreenTransform(s, ox, oy)
                    } else {
                        // Side Mode off: the normal centered Screen size control.
                        val s = pct.coerceIn(50, 100) / 100f
                        vdMirror?.setScreenTransform(s, 0f, 0f)
                    }
                }
        }
        runCatching {
            val pm = getSystemService(android.content.Context.POWER_SERVICE)
                as android.os.PowerManager
            sessionWakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "PortalPad:session",
            ).also { it.setReferenceCounted(false); it.acquire() }
        }
        runCatching {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, filter)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        (applicationContext as PortalPadApp).setServiceRunning(true)
        displayManager.registerDisplayListener(displayListener, null)
        displayManager.displays.forEach { logDisplayDiag(it.displayId, "baseline") }
        refreshExternalDisplay(autoLaunchOnFirstAttach = false, trigger = "onCreate")
        startBubbleWatcher()
        (applicationContext as PortalPadApp).signal.start()
        startAutoDisableWatcher()
        startSessionSnapshotter()
        startExternalAppTracker()

        val app = applicationContext as PortalPadApp

        // (Removed in v0.17.45: the DISPLAY_GLASSES_MODE pref listener
        //  that re-evaluated on toggle. The wrap is now always-on when
        //  Shizuku is bound; no toggle needed.)

        // When Shizuku UserService BINDER becomes ready, re-evaluate so the
        // AR-glasses VD wrap can kick in without needing a service restart.
        //
        // Previously this watched `shizuku.status == READY`, which only means
        // the Shizuku app gave us permission — the user-service binder may
        // still be connecting (typically ~100-200ms later). Watching the
        // backend's readyFlow directly avoids the race where we trigger
        // refresh BEFORE the binder is actually connected, end up with
        // usingVd=false, and inject to the raw untrusted glasses display.
        scope.launch {
            app.shizukuClickBackend.readyFlow.collectLatest { ready ->
                if (ready) {
                    // Self-heal: a Shizuku backend is now available, so flush any
                    // IME policies that were left DIRTY because an earlier reset
                    // ran while the backend wasn't Shizuku (e.g. Accessibility) or
                    // wasn't ready. This is what stops a stranded policy from
                    // killing the phone keyboard until reboot — it now clears the
                    // moment Shizuku is back. Always runs (not only when idle).
                    runCatching { app.injector.flushDirtyImePolicies() }
                    // Additional safety net: with no active session, also do a
                    // full reset in case a prior session crashed before resetting.
                    if (app.externalDisplayId.value == null) {
                        runCatching { app.injector.resetImePolicy() }
                    }
                    refreshExternalDisplay(force = true, trigger = "ShizukuBinderReady")
                }
            }
        }
        // Same for the root-bound UserService — when it connects, re-run the
        // reconcile so root can drive the trusted VD wrap just like Shizuku.
        scope.launch {
            app.rootClickBackend.readyFlow.collectLatest { ready ->
                if (ready) {
                    refreshExternalDisplay(force = true, trigger = "RootBinderReady")
                }
            }
        }

        // VD watchdog. A display can connect while the listener is suppressed (a
        // DeX toggle, an in-flight start/stop, or a quick reconnect right after a
        // disconnect) — onDisplayAdded then drops it, and because the backend was
        // already bound there's no binder-ready event to force a reconcile either,
        // so the VirtualDisplay never gets created until the service is restarted.
        // This loop self-heals that: if an external display is present and the
        // backend is ready but no VD session is active, force a reconcile. It does
        // NOTHING in steady state (a session is active) and only acts in the
        // genuinely-broken "display present, no VD" state, so it can't churn —
        // once a session starts, isActive is true and it stops firing.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(3000)
                runCatching {
                    val a = applicationContext as? PortalPadApp ?: return@runCatching
                    val ext = pickExternalDisplay()
                    // Skip when we're already in a STABLE raw-glasses fallback for the
                    // present display: VD creation was attempted and failed (it'll keep
                    // failing on this device), but the overlays are attached and working.
                    // Re-reconciling here would rebuild the dock + cursor every 3s and
                    // break auto-hide. The genuine "display present but never attached"
                    // case still self-heals: there, we haven't reconciled to this id (or
                    // the last attach used a VD that died), so the guard doesn't apply.
                    val stableRawFallback = lastAttachWasRawFallback &&
                        ext != null && lastReconciledDisplayId == ext.displayId
                    if (!isDisplayListenerSuppressed() &&
                        a.activeBoundBackend?.isReady == true &&
                        ext != null &&
                        !virtualDisplaySession.isActive &&
                        !stableRawFallback
                    ) {
                        Log.d(TAG, "VD watchdog: display present + backend ready but no session → forcing reconcile")
                        refreshExternalDisplay(force = true, trigger = "vdWatchdog")
                    }
                }
            }
        }

        scope.launch {
            app.prefs.bool(PreferencesRepository.Keys.DOCK_ENABLED, default = true)
                // CRITICAL: distinctUntilChanged + drop(1). DataStore flows re-emit
                // on EVERY write to the store, so without this, an unrelated pref
                // write during a dock launch (e.g. setLastForegroundApp) re-emitted
                // the same DOCK_ENABLED value and fired a forced VD refresh on every
                // launch — restarting the display and dropping the wallpaper backdrop
                // (black background behind the freeform windows). Only react when the
                // value actually changes.
                .distinctUntilChanged()
                .drop(1)
                .collectLatest {
                    dismissDock()
                    refreshExternalDisplay(force = true, trigger = "DOCK_ENABLED")
                }
        }
        // (Removed the dockDisplayMode watcher: display mode is no longer
        //  user-selectable — the dock is always OVERLAY, with an internal
        //  auto-fallback to Presentation if an OEM rejects the overlay.)
        // When the IME-on-external pref flips, re-pin so the IME relocates
        // without needing a click first.
        scope.launch {
            app.prefs.bool(PreferencesRepository.Keys.IME_ON_EXTERNAL, default = false)
                .collectLatest { app.injector.repinImePolicy(aggressive = false) }
        }
        // Mirror the experimental auto-open-relay toggle into a volatile flag the
        // poll loop reads each tick, and start the (idle-cheap) poller.
        // Live-tune input feel from prefs.
        scope.launch {
            app.prefs.float(PreferencesRepository.Keys.CURSOR_SPEED, default = 1.0f)
                .collectLatest { app.injector.cursorSpeed = it }
        }
        scope.launch {
            app.prefs.float(PreferencesRepository.Keys.SCROLL_SPEED, default = 1.0f)
                .collectLatest { app.injector.scrollSpeed = it }
        }
        scope.launch {
            app.prefs.bool(PreferencesRepository.Keys.INVERT_SCROLL, default = false)
                .collectLatest { app.injector.invertScroll = it }
        }
        // When the user toggles "Custom cursor on external display", re-attach
        // the overlays on the display we last used. Cheaper than a full
        // reconcile (no VD teardown), and makes the toggle take effect
        // immediately instead of only after some unrelated display event.
        scope.launch {
            app.prefs.bool(PreferencesRepository.Keys.ENABLE_MOUSE_HOVER, default = true)
                .distinctUntilChanged()
                .drop(1)   // first emission is the current value at startup
                .collectLatest {
                    lastOverlayDisplay?.let { attachVdOverlays(it) }
                }
        }
        // Media cursor auto-hide: when enabled AND a media session is actively
        // playing, hide the external-display cursor after N seconds of no cursor
        // movement. Any trackpad movement (cursorPosition change) shows it again
        // immediately and restarts the idle countdown. Requires the notification
        // listener (media-session detection); the UI gates the toggle on that.
        // collectLatest on the toggle means turning it off cancels the loop and
        // we restore the cursor.
        scope.launch {
            app.prefs.bool(PreferencesRepository.Keys.MEDIA_AUTOHIDE_CURSOR, default = false)
                .collectLatest { enabled ->
                    if (!enabled) {
                        // Feature off: make sure we're not leaving the cursor hidden.
                        app.injector.setCursorVisible(true)
                        return@collectLatest
                    }
                    var lastMoveMs = android.os.SystemClock.elapsedRealtime()
                    var hiddenByUs = false
                    val dm = applicationContext
                        .getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                    // Watch cursor movement on a child job: any change = activity.
                    val moveWatcher = launch {
                        app.injector.cursorPosition
                            .collect {
                                lastMoveMs = android.os.SystemClock.elapsedRealtime()
                                if (hiddenByUs) {
                                    app.injector.setCursorVisible(true)
                                    hiddenByUs = false
                                }
                            }
                    }
                    try {
                        while (true) {
                            kotlinx.coroutines.delay(500L)
                            val secs = app.prefs
                                .int(PreferencesRepository.Keys.MEDIA_AUTOHIDE_CURSOR_SEC, default = 5)
                                .first()
                                .coerceIn(1, 60)
                            val playing = runCatching { app.media.isPlaying() }.getOrDefault(false)
                            // Only auto-hide when the media is (almost certainly)
                            // fullscreen, not playing in a small freeform window the
                            // user is working alongside. When desktop mode is OFF apps
                            // always run fullscreen, so any playback qualifies. When ON,
                            // it's fullscreen IF either PortalPad maximized a window OR a
                            // visible window fills the external display — the latter
                            // catches Android's caption-bar maximize and app-driven
                            // fullscreen (e.g. YouTube), neither of which sets PortalPad's
                            // own maximize flag. Reads the already-polled WindowMonitor
                            // snapshot; the only extra work is one local display-metrics
                            // read, and only while playback is the gating concern.
                            val snap = app.windowMonitor.snapshot.value
                            val fillsExternal = snap.maximizedId == null &&
                                hasWindowFillingExternal(dm, snap)
                            val fullscreenContext =
                                !snap.desktop || snap.maximizedId != null || fillsExternal
                            val idleMs = android.os.SystemClock.elapsedRealtime() - lastMoveMs
                            // Don't hide while the cursor is parked on the visible
                            // dock — the user is about to use it. cursorOverDock is
                            // published by DockBar and is already gated on dock
                            // visibility, so when the dock idle-hides this releases
                            // and normal auto-hide resumes.
                            if (playing && fullscreenContext && !hiddenByUs &&
                                !app.injector.cursorOverDock && idleMs >= secs * 1000L) {
                                app.injector.setCursorVisible(false)
                                hiddenByUs = true
                            } else if ((!playing || !fullscreenContext) && hiddenByUs) {
                                // Playback stopped/paused, OR the window left fullscreen
                                // (e.g. user un-maximized into freeform) — restore cursor.
                                app.injector.setCursorVisible(true)
                                hiddenByUs = false
                            }
                        }
                    } finally {
                        moveWatcher.cancel()
                        // On cancel (toggle off / service teardown), don't leave it hidden.
                        if (hiddenByUs) app.injector.setCursorVisible(true)
                    }
                }
        }
        // Re-attach overlays when the desktop-windows beta toggle flips, so
        // it takes effect without a service restart. A full reconcile is used
        // (not just attachVdOverlays) because the regular dock is suppressed in
        // desktop mode, and the dock attach lives in refreshExternalDisplay.
        // Keep the injector's resize-cursor gate in sync with the desktop-mode
        // pref (initial value + every change). Self-contained; no drop(1) here so
        // the flag is correct from startup.
        scope.launch {
            app.prefs.bool(PreferencesRepository.Keys.DESKTOP_MODE_ENABLED, default = false)
                .collect { app.injector.desktopModeEnabled = it }
        }
        scope.launch {
            app.prefs.bool(PreferencesRepository.Keys.DESKTOP_MODE_ENABLED, default = false)
                // Same fix as DOCK_ENABLED: distinctUntilChanged so an unrelated
                // DataStore write during a launch doesn't re-fire a forced refresh.
                .distinctUntilChanged()
                .drop(1)
                .collectLatest { enabled ->
                    // Either direction closes all windows on the external display.
                    // ENABLE: a fullscreen app launched before desktop mode can't be
                    // reliably converted to freeform in place on this platform, so we
                    // close it — the user reopens it from the dock and it launches as
                    // a window. DISABLE: leaving freeform windows around after turning
                    // freeform off orphans them (no taskbar/top bar to manage them).
                    // The UI shows a confirm before this runs. Closing is hardened
                    // with a second pass for any window that survives the first close.
                    runCatching {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            if (enabled) {
                                app.freeform.enableFreeform()
                            } else {
                                app.freeform.disableFreeform()
                                // Disabling is a deliberate teardown — forget the saved
                                // layout so re-enabling starts fresh instead of
                                // resurfacing stale windows. (A flap never toggles this
                                // pref, so reconnect recovery is unaffected.)
                                runCatching { app.prefs.setLastSession(com.portalpad.app.data.SavedSession()) }
                                runCatching { app.prefs.setSessionsByWidth(com.portalpad.app.data.SavedSessions()) }
                            }
                            closeAllExternalWindows()
                        }
                    }
                    dismissDock()
                    refreshExternalDisplay(force = true, trigger = "DESKTOP_MODE")
                }
        }
        // Re-anchor the dock when its position changes (BOTTOM/TOP/LEFT/RIGHT).
        // The overlay dock's window gravity is fixed at creation, so it must be
        // re-created to move; the presentation dock re-aligns via Compose but a
        // rebuild here is harmless. We watch position only (distinctUntilChanged)
        // so ordinary dock edits — adding/removing icons — don't rebuild it.
        scope.launch {
            app.prefs.dockConfig
                .map { it.position }
                .distinctUntilChanged()
                .drop(1)
                .collectLatest {
                    val disp = lastOverlayDisplay ?: return@collectLatest
                    val enabled = runCatching {
                        kotlinx.coroutines.runBlocking {
                            app.prefs.bool(
                                PreferencesRepository.Keys.DOCK_ENABLED, default = true,
                            ).first()
                        }
                    }.getOrDefault(true)
                    dismissDockOnly()
                    if (enabled) attachDock(disp, DockDisplayMode.OVERLAY)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (abortedRelaunch) START_NOT_STICKY else START_STICKY

    /**
     * Recover a session stranded on the phone after a VirtualDisplay "flap" — the
     * external display (owned by the Shizuku/shell process) was destroyed and then
     * recreated with a NEW id, e.g. when a hardware screen-off reaps that process.
     * When the old display dies, whatever was on it (a video, a browser) is
     * evacuated by the system onto the phone's default display. This brings it
     * back onto the new external display:
     *   1) move-first — moveFocusedTaskToDisplay slides the LIVE instance over,
     *      preserving state (a video keeps its playback position);
     *   2) relaunch-fallback — if the move doesn't take, restore the saved session
     *      onto the new display so at minimum the app returns to the glasses
     *      (a relaunch won't resume exact playback, but it beats being stranded).
     * Debounced via [lastRecoveryAt] so repeated flaps can't spam it.
     */
    private fun recoverStrandedSessionOnFlap(newDisplayId: Int) {
        val now = System.currentTimeMillis()
        if (now - lastRecoveryAt < 15_000L) {
            Log.d("PortalPadSleep", "RECOVERY skipped (debounced) display=$newDisplayId")
            return
        }
        lastRecoveryAt = now
        val app = applicationContext as PortalPadApp
        // Flap auto-recovery now owns the restore — silence the phone "Restore
        // last session?" popup for the duration (4s settle + relaunch + retile,
        // with headroom) so it doesn't ask a question we've already answered.
        app.flapRecoveryUntilMs = System.currentTimeMillis() + 25_000L
        scope.launch {
            // Show the black "Restoring your windows…" cover on the GLASSES for
            // the duration of recovery — but ONLY if there's actually a saved
            // layout to bring back. With nothing to restore (clean state) there's
            // no fullscreen shuffle to mask, so popping the cover would just flash
            // a message that restores nothing. Resolving vdDisp to null in that
            // case makes every cover call below (show AND done) a no-op.
            // Overlays live on the VD (injector display), not the physical id.
            val hasSavedLayout = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val snap = app.prefs.lastSession.first()
                    if (snap.windows.isEmpty()) return@withContext false
                    if (!vdSurvivedDisconnect) return@withContext true
                    // Surviving VD: count only windows whose tasks are still
                    // alive. Dead ids on a VD that never died were closed BY
                    // THE USER (cleared recents) — the liveness filter in
                    // mergedRecoverySession drops them from the restore, and
                    // if NONE survive there is nothing to restore: vdDisp
                    // resolves null and no "Restoring windows" cover flashes
                    // over a display that will stay exactly as the user left it.
                    val liveIds = app.freeform.listTasks().map { it.taskId }.toSet()
                    snap.windows.any { it.taskId <= 0 || it.taskId in liveIds }
                }
            }.getOrDefault(false)
            val vdDisp = if (!hasSavedLayout) null else runCatching {
                val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                dm?.getDisplay(app.injector.displayId)
            }.getOrNull()
            vdDisp?.let {
                com.portalpad.app.presentation.RestoreCover.showRestoring(applicationContext, it)
            }
            // This device re-homes the surviving apps onto the new display, and on
            // Samsung the windowing mode they land in depends on whether freeform
            // support is enabled AT THAT MOMENT. disableFreeform() ran on the
            // disconnect (force_resizable_activities -> 0), and the reconnect's
            // enableFreeform() is async + gated on desktop mode, so it can fail to
            // land before the platform re-homes the survivors (~3-4s) — they then
            // come back FULLSCREEN, and this device ignores live windowing-mode
            // changes, so a fullscreen survivor can't be tiled afterward. Force
            // freeform ON up front, awaited + unconditional, so survivors re-home
            // freeform-capable and the retile's resize can actually tile them.
            val ffOk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { app.freeform.enableFreeform() }.getOrDefault(false)
            }
            val ffState = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val ef = app.access.execShell("settings get global enable_freeform_support").trim()
                    val fr = app.access.execShell("settings get global force_resizable_activities").trim()
                    "enable_freeform=$ef force_resizable=$fr"
                }.getOrDefault("unread")
            }
            Log.d("PortalPadSleep", "RECOVERY enableFreeform up-front ok=$ffOk $ffState display=$newDisplayId")
            // Let the recreated display + rebound backend + dock settle first AND
            // give the platform time to re-home surviving apps onto the new display
            // on its own (a physical replug does this over ~3-4s). Checking earlier
            // saw present=0 and wrongly relaunched, duplicating apps the system was
            // about to bring back. Wait past that window so move-first's presence
            // check is accurate.
            kotlinx.coroutines.delay(4000)
            // 1) move-first: re-home each app we launched onto the glasses this
            //    session. After a screen-off flap the system evicts them to the
            //    phone, where they are NOT the focused task (the launcher/
            //    lockscreen is) — so a bare moveFocusedTaskToDisplay finds
            //    nothing (that was the moved=false we saw). Bring each recorded
            //    app to the foreground first (am start focuses its singleTask
            //    wherever it landed), then slide the now-focused task onto the
            //    new VD. Preserves the live task — and a video's position.
            val moved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val comps = synchronized(app.externalAppComponents) {
                    app.externalAppComponents.toList()
                }
                // Apps that survived the flap are STILL on the external display — do
                // NOT relaunch them. `am start` spawns a SECOND window for apps like
                // Chrome, which is the phantom "extra window behind" the arranged
                // ones. Only relaunch+move apps genuinely missing from the display
                // (e.g. evicted to the phone by a screen-off). retileToSaved then
                // arranges whatever survived.
                val livePkgs = runCatching { app.freeform.listExternalTasks() }
                    .getOrDefault(emptyList()).map { it.packageName }.toMutableSet()
                var any = false
                var present = 0
                for (comp in comps) {
                    val pkg = comp.substringBefore('/')
                    if (pkg in livePkgs) { present++; continue } // already home → skip relaunch
                    runCatching { app.access.startActivityOnDisplay(comp, newDisplayId) }
                    kotlinx.coroutines.delay(500)
                    if (runCatching {
                            app.activeBoundBackend?.moveFocusedTaskToDisplay(newDisplayId) == true
                        }.getOrDefault(false)
                    ) { any = true; livePkgs += pkg }
                }
                // Nothing was missing AND nothing already present → old behavior:
                // move whatever is focused. Harmless if there's nothing to move.
                if (!any && present == 0) {
                    any = runCatching {
                        app.activeBoundBackend?.moveFocusedTaskToDisplay(newDisplayId) == true
                    }.getOrDefault(false)
                }
                Log.d(
                    "PortalPadSleep",
                    "RECOVERY move-first comps=${comps.size} moved=$any present=$present display=$newDisplayId",
                )
                // Recovered if we moved something OR apps were already home — either
                // way retile (not the relaunch-fallback, which would re-duplicate).
                any || present > 0
            }
            if (moved) {
                // Fix 2: move-first kept the live tasks alive but not their layout
                // (they return default/minimized). Re-apply the saved arrangement
                // IN PLACE — no relaunch, so playback survives. Skipped silently if
                // there's no saved layout to apply.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                    val disp = dm?.getDisplay(newDisplayId)
                    val m = android.util.DisplayMetrics()
                        .also { @Suppress("DEPRECATION") disp?.getRealMetrics(it) }
                    val w2 = m.widthPixels.let { if (it <= 1) 1920 else it }
                    val h2 = m.heightPixels.let { if (it <= 1) 1080 else it }
                    // Per-resolution memory: restore THIS width's remembered layout
                    // exactly (scale 1f); a width never seen is seeded by scaling the
                    // most-recent layout to fit.
                    val (session, sxM, syM) = mergedRecoverySession(app, w2, h2)
                    if (session.windows.isNotEmpty()) {
                        // Let the re-homed tasks settle on the new display first.
                        kotlinx.coroutines.delay(600)
                        val n = runCatching { app.freeform.retileToSaved(session, newDisplayId, w2, h2, scaleX = sxM, scaleY = syM) }
                            .getOrDefault(0)
                        Log.d("PortalPadSleep", "RECOVERY re-tile applied=$n display=$newDisplayId scale=${sxM}x$syM")
                    }
                }
                vdDisp?.let {
                    com.portalpad.app.presentation.RestoreCover.showDone(applicationContext, it)
                }
                returnToPortalPadIfAlive()
                return@launch
            }
            // 2) relaunch-fallback: same path the "Restore last session" button uses.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                val disp = dm?.getDisplay(newDisplayId)
                val m = android.util.DisplayMetrics()
                    .also { @Suppress("DEPRECATION") disp?.getRealMetrics(it) }
                val w = m.widthPixels.let { if (it <= 1) 1920 else it }
                val h = m.heightPixels.let { if (it <= 1) 1080 else it }
                // Per-resolution memory: this width's remembered layout (exact), or
                // the most-recent layout scaled to fit if this width is new.
                val (session, sxF, syF) = mergedRecoverySession(app, w, h)
                if (session.windows.isEmpty()) {
                    Log.d("PortalPadSleep", "RECOVERY relaunch skipped (no saved session)")
                    return@withContext
                }
                runCatching { app.freeform.restoreSession(session, newDisplayId, w, h) }
                Log.d("PortalPadSleep", "RECOVERY relaunch via restoreSession display=$newDisplayId")
                // restoreSession only relaunches apps that were genuinely missing
                // (it skips survivors). Now tile EVERYTHING — survivors + relaunches
                // — to the saved layout. Brief settle so the relaunches register.
                kotlinx.coroutines.delay(800)
                val n = runCatching { app.freeform.retileToSaved(session, newDisplayId, w, h, relaunchFullscreen = true, scaleX = sxF, scaleY = syF) }
                    .getOrDefault(0)
                Log.d("PortalPadSleep", "RECOVERY fallback re-tile applied=$n display=$newDisplayId scale=${sxF}x$syF")
            }
            vdDisp?.let {
                com.portalpad.app.presentation.RestoreCover.showDone(applicationContext, it)
            }
            returnToPortalPadIfAlive()
        }
    }

    /**
     * Keep [PortalPadApp.externalAppComponents] populated with whatever user app
     * is actually live on the external display — not just apps launched via the
     * dock. recordExternalApp only fires on a dock/search launch, so an app that
     * was already running or resumed (e.g. SmartTube the user opened before a
     * screen-off) is invisible to flap recovery, which then logs comps=0 and has
     * nothing to relaunch. Polling the live external task list closes that gap:
     * after a screen-off reaps the Shizuku-owned VD and it rebuilds, recovery has
     * a real target to re-home.
     *
     * Cheap + guarded: only queries while an external display is present and the
     * backend is ready; one task query every few seconds; no-op otherwise.
     */
    private fun startExternalAppTracker() {
        externalAppTrackerJob?.cancel()
        externalAppTrackerJob = scope.launch {
            val app = applicationContext as PortalPadApp
            while (true) {
                kotlinx.coroutines.delay(8000)
                runCatching {
                    if (app.externalDisplayId.value == null) return@runCatching
                    if (app.activeBoundBackend?.isReady != true) return@runCatching
                    val tasks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        app.freeform.listExternalTasks()
                    }
                    for (rt in tasks) {
                        val pkg = rt.packageName
                        if (pkg.contains("launcher", ignoreCase = true)) continue
                        if (pkg.startsWith("com.portalpad")) continue
                        val comp = rt.topActivity ?: continue
                        app.recordExternalApp(comp)
                    }
                }
            }
        }
    }

    /** Relaunch + retile the saved session onto the current external display.
     *  Called by the trackpad-launch "Restore last session?" dialog. */
    fun restoreSavedSession() {
        val app = applicationContext as PortalPadApp
        scope.launch {
            val displayId = app.externalDisplayId.value ?: return@launch
            // Gate: only proceed if there's any saved layout at all.
            val hasAnything = runCatching { app.prefs.lastSession.first() }.getOrNull()
                ?.windows?.isNotEmpty() == true
            if (!hasAnything) return@launch
            // Same cover the flap recovery uses — so a popup-triggered restore
            // also hides the brief fullscreen shuffle on the glasses. Renders on
            // the VD (injector display), not the physical display id.
            val vdDisp = runCatching {
                val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                dm?.getDisplay(app.injector.displayId)
            }.getOrNull()
            vdDisp?.let {
                com.portalpad.app.presentation.RestoreCover.showRestoring(applicationContext, it)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                val disp = dm?.getDisplay(displayId)
                val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") disp?.getRealMetrics(it) }
                val w = m.widthPixels.let { if (it <= 1) 1920 else it }
                val h = m.heightPixels.let { if (it <= 1) 1080 else it }
                // Per-resolution memory: this width's remembered layout (exact), or
                // the most-recent layout scaled to fit if this width is new.
                val (session, sxR, syR) = app.prefs.sessionForCanvas(w, h)
                if (session.windows.isNotEmpty()) {
                    runCatching { app.freeform.restoreSession(session, displayId, w, h) }
                    // restoreSession only relaunches genuinely-missing apps; tile
                    // everything (survivors + relaunches) to the saved layout, closing
                    // and relaunching any that came back fullscreen.
                    kotlinx.coroutines.delay(800)
                    runCatching { app.freeform.retileToSaved(session, displayId, w, h, relaunchFullscreen = true, scaleX = sxR, scaleY = syR) }
                    Log.d(TAG, "restore: re-tiled display=$displayId scale=${sxR}x$syR")
                }
            }
            vdDisp?.let {
                com.portalpad.app.presentation.RestoreCover.showDone(applicationContext, it)
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        if (abortedRelaunch) {
            // This instance stopped itself in onCreate before ANY setup ran —
            // no listeners, no scope work, no VD, no overlays. Running the full
            // teardown here would (at minimum) yank MainActivity to the front
            // out of nowhere. Just clear the instance handle and go.
            Log.w(TAG, "onDestroy: aborted relaunch — skipping teardown (nothing was set up)")
            if (instance === this) instance = null
            return
        }
        runCatching { sessionWakeLock?.let { if (it.isHeld) it.release() } }
        sessionWakeLock = null
        runCatching { unregisterReceiver(screenStateReceiver) }
        // Revert freeform/force-resizable on teardown so a stopped/killed session
        // doesn't leave force_resizable_activities on system-wide (the SystemUI
        // crash / random black-screen). Synchronous + best-effort: scope is about to
        // be cancelled, and this no-ops if the privileged backend is already gone
        // (onDisplayRemoved is the primary, earlier hook).
        runCatching { (applicationContext as PortalPadApp).freeform.disableFreeform() }
        // Cover the phone with a black "PortalPad" transition BEFORE we tear down
        // the VD below — tearing down the VD migrates the glasses app (e.g.
        // Chrome) to the phone and flashes it for a moment before our MainActivity
        // reassert lands. The cover (hosted from the application context so it
        // survives this dying service) hides that flash behind a clean black
        // transition, then auto-dismisses after the reassert window.
        runCatching {
            com.portalpad.app.presentation.DisableTransitionOverlay.show(
                applicationContext, durationMs = 1100L,
            )
        }
        if (instance === this) instance = null
        (applicationContext as PortalPadApp).setServiceRunning(false)
        // FINAL session snapshot, synchronously, BEFORE any teardown below
        // destroys windows. The continuous snapshotter is debounced (~1.5s), so
        // a move/resize made moments before Stop may not be flushed yet — this
        // captures the true final arrangement while every window is still
        // alive. captureSessionNow's own junk-state filters still apply, and an
        // empty/mid-transition state never overwrites the previous good
        // snapshot (snapshotSession returns null on empty). This is what the
        // start-path "Restore last session?" offer restores from.
        runCatching {
            kotlinx.coroutines.runBlocking {
                captureSessionNow(applicationContext as PortalPadApp)
            }
        }
        // Finish the home-backdrop so the orphaned task can't migrate to the
        // phone display when the VD tears down (same reason as onDisplayRemoved).
        runCatching { com.portalpad.app.WallpaperActivity.finishIfShowing() }
        bubbleWatcher?.cancel()
        runCatching { (applicationContext as PortalPadApp).signal.stop() }
        autoDisableJob?.cancel()
        externalAppTrackerJob?.cancel()
        // Restore the system-mirror panel BEFORE cancelling the scope. dismissVdMirror()
        // (below) restores via `scope.launch(IO){ stopSystemMirror() }`, but scope.cancel()
        // runs first — so on stop-service that coroutine never executes, the panel stays
        // retargeted at the VD we're about to destroy, and the glasses freeze on their
        // last frame (Kindle-on-black). Do it synchronously here so teardown order can't
        // swallow it; dismissVdMirror()'s (now redundant) launch is a harmless no-op once
        // systemMirrorGlassesId is cleared.
        //
        // NOTE: this restores the panel to its saved native layerStack. On this device
        // the panel had been showing PortalPad's VD, so the immediate visual is a plain
        // panel (the phone's own DP output takes over once the VD is released). A prior
        // attempt to instead retarget the panel at display 0 HERE (mid-teardown) made the
        // panel unstable across the subsequent VD teardown — so we restore now (prevents
        // a stranded dead-stack), then AFTER virtualDisplaySession.stop() releases the VD
        // we retarget to the phone (display 0) so a stopped service mirrors the device.
        var mirrorGlassesForPhone = -1
        if (systemMirrorGlassesId >= 0) {
            val gid = systemMirrorGlassesId
            mirrorGlassesForPhone = gid
            systemMirrorGlassesId = -1
            systemMirrorVdId = -1
            runCatching { virtualDisplaySession.stopSystemMirror(gid) }
            Log.w(TAG, "onDestroy: restored system-mirror panel for glasses=$gid")
        }
        // OVERLAY mode (System mirror pref off): no retarget is active, so the
        // bookkeeping above is -1 — but stop-service should STILL leave the
        // glasses mirroring the phone. Previously this only ever happened by
        // accident (transient-mirror churn leaving stale bookkeeping at stop
        // time); now it's deliberate. Gated on a live VD session: in raw
        // fallback there's no VD teardown to go black over (and no backend to
        // retarget with anyway), and when no display is connected there's
        // nothing to point anywhere.
        if (mirrorGlassesForPhone < 0 && virtualDisplaySession.isActive) {
            mirrorGlassesForPhone =
                (applicationContext as PortalPadApp).physicalExternalDisplayId.value ?: -1
            if (mirrorGlassesForPhone >= 0) {
                Log.w(TAG, "onDestroy: overlay mode — phone-mirror glasses=$mirrorGlassesForPhone after VD release")
            }
        }
        scope.cancel()
        pendingOverlayReassert?.let { mainHandler.removeCallbacks(it) }
        pendingOverlayReassert = null
        bubble.hide()
        displayManager.unregisterDisplayListener(displayListener)
        dismissDock()
        dismissVdMirror()
        // Reset IME policy on every touched display BEFORE releasing the VD —
        // a non-default policy left on a VD that's then destroyed wedges IME
        // placement on some OEMs and kills the phone keyboard until reboot.
        runCatching { (applicationContext as PortalPadApp).injector.resetImePolicy() }
        // Also stop Extinguish (stop-only, guarded) on disable, so a wedged
        // keyboard isn't left behind after the user turns PortalPad off.
        runCatching { stopExtinguishForKeyboardSafety("disable") }
        runCatching { virtualDisplaySession.stop() }
        // VD is now released. Retarget the glasses panel at the PHONE's display so a
        // stopped service leaves the glasses mirroring the device (portrait/pillarboxed
        // is fine — that's just what the phone is showing), rather than a black panel.
        // Done HERE, post-release, not during teardown: an earlier attempt to retarget
        // mid-onDestroy was destabilised when the VD died under it. Display 0 is the
        // phone; the retarget reuses the proven primitive. Single synchronous binder
        // call, like the resetImePolicy/stop calls just above. If it fails, the panel
        // stays on its saved native stack (black) — acceptable; never a frozen dead stack.
        if (mirrorGlassesForPhone >= 0) {
            val ok = runCatching {
                virtualDisplaySession.startSystemMirror(
                    mirrorGlassesForPhone, android.view.Display.DEFAULT_DISPLAY,
                )
            }.getOrDefault(false)
            Log.w(
                TAG,
                if (ok) "onDestroy: stop → mirrored phone (display 0) on glasses=$mirrorGlassesForPhone"
                else "onDestroy: phone-mirror retarget failed → panel on native stack (glasses=$mirrorGlassesForPhone)",
            )
        }
        val tdApp = applicationContext as PortalPadApp
        tdApp.setExternalDisplayId(null)
        tdApp.setPhysicalExternalDisplayId(null)
        tdApp.setVirtualDisplayId(null)

        // When we tear down the VD, whatever app was running on it (often
        // Chrome) gets migrated by the system to the phone's display 0 and
        // surfaces to the foreground — so the user, who tapped "Disable"
        // expecting to land back in PortalPad, instead finds themselves in
        // Chrome. Counteract that by explicitly bringing our settings
        // (MainActivity) to the front. REORDER_TO_FRONT reuses the existing
        // task if present; NEW_TASK is required since we start from a
        // service context.
        //
        // Guarded by a pref check would be nice but a service onDestroy
        // can't easily block on DataStore; this behavior is what users
        // expect by default anyway.
        runCatching {
            // REORDER_TO_FRONT alone only works if MainActivity is already in a
            // task; if it isn't, the phone falls back to the last app. Use
            // CLEAR_TOP + a fresh start so PortalPad reliably comes to the
            // foreground on disable regardless of the prior task state.
            val back = Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            startActivity(back)
        }
        // The immediate bring-forward above often LOSES a race: tearing down the
        // VD (above) makes the system migrate the app that was on it (e.g.
        // Chrome) to display 0 and surface it — and that migration completes
        // AFTER our synchronous startActivity, so Chrome ends up on top. Fix:
        // schedule a second, DELAYED bring-forward from the application scope
        // (which outlives this dying service) so it runs after the migration
        // settles and PortalPad ends up in front. Two reasserts at staggered
        // delays to absorb timing variance across the teardown.
        runCatching {
            val appCtx = applicationContext
            (appCtx as PortalPadApp).appScopeReassertMainActivity(listOf(450L, 900L))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dismissDock() {
        dockOverlay?.dismiss(); dockOverlay = null
        dockPresentation?.dismiss(); dockPresentation = null
        cursorOverlay?.dismiss(); cursorOverlay = null
        navButtonsOverlay?.dismiss(); navButtonsOverlay = null
        taskbarOverlay?.dismiss(); taskbarOverlay = null
        topWindowBarOverlay?.dismiss(); topWindowBarOverlay = null
        shizukuWarningOverlay?.dismiss(); shizukuWarningOverlay = null
        notificationPanel?.dismiss(); notificationPanel = null
    }

    private fun dismissVdMirror() {
        vdMirror?.dismiss(); vdMirror = null
        activeVdMirror = null
        runCatching { mirrorHud?.stop() }; mirrorHud = null
        mirrorFpsJob?.cancel(); mirrorFpsJob = null; mirrorSfFps = -1f
        // System-mirror path: restore the panel's original layerStack so the
        // glasses aren't left retargeted at a dead VD. Symmetric with the
        // overlay dismiss above; the next setup re-establishes whichever path.
        if (systemMirrorGlassesId >= 0) {
            val gid = systemMirrorGlassesId
            systemMirrorGlassesId = -1
            systemMirrorVdId = -1
            // Off the main thread: this is a shell binder call that can block for
            // seconds while the system is contended (e.g. during USB unplug).
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { virtualDisplaySession.stopSystemMirror(gid) }
            }
        }
    }

    /**
     * Narrow panel-feed flip: switch what the physical panel shows — system
     * mirror (panel retargeted at the VD's layerStack) vs the overlay mirror
     * (fullscreen SurfaceView the VD renders into) — WITHOUT the full
     * refreshExternalDisplay reconcile. The VD, its windows, the dock, top bar,
     * and cursor are untouched; only the feed changes. This is all the transient
     * permission-dialog mirror and the Settings "System mirror" toggle need —
     * the full reconcile they previously ran re-derived the whole session per
     * flip (dock/cursor/mirror teardown+rebuild ×2 per dialog), and any pass
     * that sampled the backend during a momentary not-ready blip DESTROYED the
     * live VD, windows and all (the §"raw tasks=0" empty-snapshot loop).
     *
     * Returns false when there's no live VD session to flip — the caller falls
     * back to the full reconcile, which owns session creation.
     */
    private fun setPanelFeed(toMirror: Boolean, trigger: String): Boolean {
        val app = applicationContext as PortalPadApp
        if (!virtualDisplaySession.isActive) return false
        val gid = app.physicalExternalDisplayId.value ?: return false
        val vdId = app.virtualDisplayId ?: return false
        val overlayDisplay = runCatching { displayManager.getDisplay(vdId) }.getOrNull() ?: return false
        val external = runCatching { displayManager.getDisplay(gid) }.getOrNull() ?: return false
        if (toMirror) {
            if (systemMirrorGlassesId == gid && systemMirrorVdId == vdId) {
                Log.w(TAG, "panel-feed: setPanelFeed(mirror, $trigger) — already mirroring, no-op")
                return true
            }
            // Drop the overlay mirror only. Its system-mirror-stop branch is a
            // no-op here (systemMirrorGlassesId is -1 in overlay mode), and
            // onSurfaceGone returns the VD to its invisible placeholder surface
            // — the layerStack retarget below doesn't care what surface the VD
            // renders into.
            dismissVdMirror()
            systemMirrorGlassesId = gid
            systemMirrorVdId = vdId
            mirrorModeActive = true
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ok = runCatching {
                    virtualDisplaySession.startSystemMirror(gid, vdId)
                }.getOrDefault(false)
                if (ok) Log.w(TAG, "panel-feed: setPanelFeed($trigger) retargeted glasses=$gid → vd=$vdId")
                else Log.w(TAG, "panel-feed: setPanelFeed($trigger) retarget FAILED glasses=$gid vd=$vdId")
            }
            // Standalone HUD + fps poll — same as the reconcile's mirror block
            // (the overlay mirror's internal HUD died with it above).
            runCatching { mirrorHud?.stop() }
            mirrorFpsJob?.cancel(); mirrorSfFps = -1f
            mirrorFpsJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                while (true) {
                    mirrorSfFps = runCatching {
                        virtualDisplaySession.sampleDisplayFps(vdId)
                    }.getOrDefault(-1f)
                    kotlinx.coroutines.delay(1500)
                }
            }
            mirrorHud = com.portalpad.app.presentation.PerformanceHud.standalone(
                this, overlayDisplay, glActive = false,
                fpsSampler = { mirrorSfFps },
            )
            return true
        } else {
            if (systemMirrorGlassesId < 0 && vdMirror != null) {
                Log.w(TAG, "panel-feed: setPanelFeed(overlay, $trigger) — already overlay, no-op")
                return true
            }
            // Restores the panel's native layerStack, stops the standalone
            // HUD/fps poll, clears the mirror bookkeeping.
            dismissVdMirror()
            mirrorModeActive = false
            try {
                vdMirror = com.portalpad.app.presentation.VirtualDisplayMirror(
                    this,
                    external,
                    onSurfaceReady = { surf ->
                        virtualDisplaySession.bindMirrorSurface(surf)
                        // A static VD pushes no new frames into the fresh surface
                        // (the mirror only swaps on onFrameAvailable), so an idle
                        // session would sit on the mirror's black init frame until
                        // something recomposites. Recreate the cursor once: one
                        // cheap window add that forces a VD composition AND
                        // restores cursor-on-top — without the full
                        // attachVdOverlays churn.
                        mainHandler.post { recreateCursorOnTop(overlayDisplay) }
                    },
                    onSurfaceGone = { virtualDisplaySession.unbindMirrorSurface() },
                ).also { it.show() }
                activeVdMirror = vdMirror
            } catch (t: Throwable) {
                Log.w(TAG, "panel-feed: setPanelFeed(overlay, $trigger) mirror attach failed", t)
            }
            return true
        }
    }

    /**
     * Flip to a TRANSIENT system mirror for the duration of an Android permission
     * dialog (called from FreeformManager when the dialog is detected in overlay
     * mode). Makes the native prompt usable on the external display without the
     * security overlay-hide blacking the panel — no app kill, no relaunch — and
     * reverts to the user's real mode on a safety timeout. Never writes the
     * PANEL_SYSTEM_MIRROR pref. No-op when the user is already in system mirror.
     * Runs on the main thread.
     */
    private fun beginTransientMirrorInternal() {
        val app = applicationContext as PortalPadApp
        val prefOn = runCatching {
            kotlinx.coroutines.runBlocking { app.prefs.panelSystemMirror.first() }
        }.getOrDefault(false)
        // Already in mirror (by the user's own pref): the dialog is already
        // usable — nothing to flip, and we must not schedule a revert that would
        // later force the panel back.
        if (prefOn) return
        if (transientMirrorForDialog) {
            // Already flipped for an earlier dialog in this burst — just push the
            // safety-revert deadline out so a chained prompt doesn't get cut off.
            scheduleTransientMirrorRevert()
            return
        }
        transientMirrorForDialog = true
        Log.w(TAG, "transient mirror: ON for permission dialog (overlay→mirror, pref untouched)")
        // Narrow feed flip — leaves the VD/windows/dock/cursor alone. Full
        // reconcile only as a fallback when no live session exists to flip.
        if (!setPanelFeed(toMirror = true, trigger = "perm-dialog-mirror-on")) {
            refreshExternalDisplay(force = true, trigger = "perm-dialog-mirror-on")
        }
        scheduleTransientMirrorRevert()
    }

    /** (Re)arm the safety-timeout that reverts the transient mirror even if the
     *  dialog-close signal is missed. Main thread. */
    private fun scheduleTransientMirrorRevert() {
        transientMirrorRevertJob?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { endTransientMirrorInternal("safety-timeout") }
        transientMirrorRevertJob = r
        mainHandler.postDelayed(r, TRANSIENT_MIRROR_REVERT_MS)
    }

    /** Clear the transient mirror and reconcile back to the user's real mode
     *  (overlay). Idempotent; safe to call when no transient mirror is active. */
    private fun endTransientMirrorInternal(reason: String) {
        transientMirrorRevertJob?.let { mainHandler.removeCallbacks(it) }
        transientMirrorRevertJob = null
        if (!transientMirrorForDialog) return
        transientMirrorForDialog = false
        Log.w(TAG, "transient mirror: OFF ($reason) — reverting to user's mode")
        // Narrow feed flip back to overlay; the windows the user just granted
        // a permission to are on the VD and must survive the revert.
        if (!setPanelFeed(toMirror = false, trigger = "perm-dialog-mirror-off")) {
            refreshExternalDisplay(force = true, trigger = "perm-dialog-mirror-off")
        }
    }

    /** Disconnect-time teardown of the transient-mirror machinery: clear the
     *  flag, cancel the safety-net revert, and cancel the FreeformManager
     *  close-watch — WITHOUT reconciling (unlike [endTransientMirrorInternal]).
     *  The display is going away and the grace/teardown path handles the panel,
     *  so a forced refresh here is pointless; the point is purely to stop a late
     *  close-watch tick or safety-net timer from later reverting a transient
     *  mirror against a display that has been unplugged (or a fresh one after a
     *  quick replug). Also clears the flag so a replug comes back on the user's
     *  real mode rather than inheriting a stale transient mirror. Idempotent. */
    private fun cancelTransientMirrorForTeardown() {
        runCatching { (applicationContext as PortalPadApp).freeform.cancelDialogCloseWatch() }
        transientMirrorRevertJob?.let { mainHandler.removeCallbacks(it) }
        transientMirrorRevertJob = null
        transientMirrorForDialog = false
    }

    /** Dismiss only the dock (overlay or presentation), leaving the cursor and
     *  nav overlays attached. Used by the position watcher to re-anchor the
     *  dock without disturbing the cursor. */
    private fun dismissDockOnly() {
        dockOverlay?.dismiss(); dockOverlay = null
        dockPresentation?.dismiss(); dockPresentation = null
    }

    /** Re-run [attachVdOverlays] once after a short delay. See the call site in
     *  the VD mirror's onSurfaceReady for why this is needed. Idempotent —
     *  any previously-pending re-assert is cancelled first, so repeated
     *  surface callbacks coalesce into a single delayed re-attach. */
    private fun scheduleOverlayReassert(overlayDisplay: Display) {
        pendingOverlayReassert?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            attachVdOverlays(overlayDisplay)
            // Re-raise the cursor as the VERY LAST overlay so its window token
            // is the newest in the accessibility-overlay layer — this is what
            // actually puts it above the dock/taskbar (each overlay has its own
            // window token, so re-adding within the cursor's own token can't
            // beat another token; a fresh top-most attach can). Posted again at
            // the tail so it runs after any dock/taskbar (re)attach this frame.
            mainHandler.post { recreateCursorOnTop(overlayDisplay) }
        }
        pendingOverlayReassert = r
        mainHandler.postDelayed(r, OVERLAY_REASSERT_DELAY_MS)
    }

    /**
     * Fully tear down and re-create the cursor overlay so it becomes the most
     * recently added window in the accessibility-overlay layer — i.e. drawn on
     * top of the dock, taskbar, and top window bar. A plain raise() (remove +
     * re-add within the cursor's existing window token) did NOT lift it above
     * the other overlays' tokens on-device, so we recreate from scratch here.
     */
    /** Effective external-display DPI: the user's value if set (80..640), else the
     *  detected display density (213 fallback for implausible values). */
    private fun effectiveExternalDpi(userDpi: Int): Int {
        if (userDpi in 80..640) return userDpi
        val detected = runCatching {
            val app = applicationContext as PortalPadApp
            val id = app.physicalExternalDisplayId.value ?: app.externalDisplayId.value
                ?: return@runCatching 0
            val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
            val d = dm.getDisplay(id) ?: return@runCatching 0
            val m = android.util.DisplayMetrics().also { d.getRealMetrics(it) }
            m.densityDpi
        }.getOrDefault(0)
        return detected.takeIf { it in 120..480 } ?: 213
    }

    /** Apply the effective DPI to the live VirtualDisplay via `wm density`. No-op
     *  if no VD is active. Dock/top bar are unaffected (own density handling). */
    private suspend fun applyDpiToLiveVd(app: PortalPadApp, userDpi: Int) {
        if (!virtualDisplaySession.isActive) return
        val vdId = app.virtualDisplayId ?: return
        val effective = effectiveExternalDpi(userDpi)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { app.access.execShell("wm density $effective -d $vdId") }
                .onSuccess { Log.d(TAG, "Applied DPI $effective to VD $vdId (live)") }
                .onFailure { Log.w(TAG, "live DPI apply failed", it) }
        }
    }

    /**
     * Aspect-ratio override → VD pixel size. "AUTO"/unknown keeps the panel's
     * native resolution. Other ratios fit INSIDE the native panel: keep the
     * limiting dimension and shrink the other so the VD never exceeds the panel
     * (the physical panel letterboxes/pillarboxes the rest). Shared by the VD
     * creation path and the live re-apply so the two can never drift.
     */
    private fun aspectToVdSize(aspect: String, nativeW: Int, nativeH: Int): Pair<Int, Int> {
        val ratio: Float? = when (aspect) {
            "4:3" -> 4f / 3f
            "16:9" -> 16f / 9f
            "16:10" -> 16f / 10f
            "21:9" -> 21f / 9f
            "21:10" -> 21f / 10f
            "32:9" -> 32f / 9f
            "32:10" -> 32f / 10f
            else -> null // AUTO / unknown → native
        }
        return if (ratio == null) {
            nativeW to nativeH
        } else {
            val nativeRatio = nativeW.toFloat() / nativeH.toFloat()
            if (ratio >= nativeRatio) {
                nativeW to (nativeW / ratio).toInt().coerceAtLeast(1)
            } else {
                (nativeH * ratio).toInt().coerceAtLeast(1) to nativeH
            }
        }
    }

    /**
     * Live aspect-ratio change: resize the running VD in place (no reconnect).
     * AirGlassesSession.start() already routes a same-glasses call to
     * resizeVirtualDisplay(), so this just recomputes the size from the panel's
     * native metrics + the new ratio and re-runs start(). Listener is suppressed
     * briefly so the resulting onDisplayChanged doesn't bounce a refresh.
     */
    private suspend fun applyAspectToLiveVd(app: PortalPadApp, aspect: String) {
        if (!virtualDisplaySession.isActive) return
        val physId = app.physicalExternalDisplayId.value ?: return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val d = displayManager.getDisplay(physId) ?: return@runCatching
                val m = android.util.DisplayMetrics().also { d.getRealMetrics(it) }
                val nativeW = m.widthPixels.coerceAtLeast(1)
                val nativeH = m.heightPixels.coerceAtLeast(1)
                val (w, h) = aspectToVdSize(aspect, nativeW, nativeH)
                val dpi = effectiveExternalDpi(app.prefs.displayDpi.first())
                val spec = Triple(w, h, dpi)
                if (spec == lastAppliedVdSpec) {
                    // Same size+dpi already live. The aspectRatio flow re-emits on
                    // any DataStore write, so without this guard we'd restart the
                    // VD (flicker) and open a suppression window on every emit —
                    // the window that could swallow a concurrent physical unplug.
                    return@runCatching
                }
                val prev = lastAppliedVdSpec
                lastAppliedVdSpec = spec
                // Decide up front whether we'll rescale, and if so snapshot each
                // window's bounds BEFORE the resize — reading them after a shrink is
                // unsafe (the platform may clamp windows that no longer fit, and we'd
                // then scale the clamped wreck). Only when the canvas SIZE changes —
                // width or height; a pure DPI change keeps the same pixel canvas so
                // windows are left alone. (Wide SBS panel: aspect changes width; 1920
                // panel: ultrawide shortens height — both must fire.)
                val sizeChanged = prev != null && (prev.first != w || prev.second != h)
                val desktopOn = if (sizeChanged) runCatching {
                    app.prefs.bool(PreferencesRepository.Keys.DESKTOP_MODE_ENABLED, false).first()
                }.getOrDefault(false) else false
                val capturedBefore: Map<Int, com.portalpad.app.data.WindowBounds> =
                    if (sizeChanged && desktopOn) runCatching {
                        app.freeform.listTasks(app.injector.displayId)
                            .filter {
                                !it.packageName.contains("launcher", ignoreCase = true) &&
                                    it.packageName != app.packageName
                            }
                            .mapNotNull { t -> t.bounds?.let { t.taskId to it } }
                            .toMap()
                    }.getOrDefault(emptyMap()) else emptyMap()
                // Snapshot the OLD layout (apps + bounds + old width) so it's saved
                // under the old resolution's slot before we switch away from it.
                val oldSnap = if (capturedBefore.isNotEmpty()) runCatching {
                    app.freeform.snapshotSession(app.injector.displayId)
                }.getOrNull() else null
                suppressDisplayListener("aspect-live")
                virtualDisplaySession.start(physId, w, h, dpi)
                Log.d(TAG, "Applied aspect $aspect → ${w}x$h dpi=$dpi to live VD (phys=$physId)")
                // The listener is suppressed ("aspect-live"), so this resize's
                // onDisplayChanged never reaches the injector's bounds update —
                // re-read explicitly or the cursor stays clamped to the OLD
                // canvas (same stale-bounds bug as the rebind path). Immediate
                // read + one delayed re-read for DisplayManager propagation lag.
                runCatching { app.injector.updateDisplaySizeForRotation() }
                mainHandler.postDelayed({
                    runCatching { app.injector.updateDisplaySizeForRotation() }
                }, 600L)
                // Proportionally rescale the captured windows to the new canvas so a
                // custom layout is preserved rather than flattened into even columns
                // (manual "arrange evenly" stays the deliberate way to even them).
                if (capturedBefore.isNotEmpty()) {
                    // Remember the old resolution's layout under its own width slot
                    // (so switching back later restores it exactly).
                    oldSnap?.let {
                        if (it.canvasWidth > 0 && it.windows.isNotEmpty()) {
                            app.prefs.putSessionForWidth(it.canvasWidth, it)
                        }
                    }
                    // Let the resize propagate before re-applying bounds.
                    Thread.sleep(250)
                    // Baseline: proportionally rescale the captured windows to the new
                    // canvas, so even windows with no remembered spot at this width
                    // land somewhere sane (never off-screen). Synchronous.
                    com.portalpad.app.presentation.WindowArranger.scaleWindowsFromBounds(
                        capturedBefore,
                        oldW = prev!!.first,
                        oldH = prev.second,
                        newW = w,
                        newH = h,
                    )
                    Log.d(TAG, "aspect change → rescaled ${capturedBefore.size} windows ${prev.first}x${prev.second} → ${w}x$h")
                    // Overlay: if THIS width has a remembered layout, snap matched
                    // windows to their exact remembered bounds (in place — no relaunch,
                    // so live state is preserved). Windows not in it keep the scaled
                    // baseline above.
                    val slot = runCatching { app.prefs.sessionsByWidth.first().byWidth[w] }.getOrNull()
                    if (slot != null && slot.windows.isNotEmpty()) {
                        runCatching {
                            app.freeform.retileToSaved(slot, app.injector.displayId, w, h, relaunchFullscreen = false)
                        }
                        Log.d(TAG, "aspect change → applied remembered layout for width $w (${slot.windows.size} windows)")
                    }
                }
            }.onFailure { Log.w(TAG, "live aspect apply failed", it) }
        }
    }

    private fun recreateCursorOnTop(overlayDisplay: Display, attempt: Int = 0) {
        val app = applicationContext as PortalPadApp
        val cursorEnabled = runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.bool(PreferencesRepository.Keys.ENABLE_MOUSE_HOVER, default = true).first()
            }
        }.getOrDefault(true)
        if (!cursorEnabled) return
        runCatching {
            cursorOverlay?.dismiss(); cursorOverlay = null
            cursorOverlay = com.portalpad.app.presentation.CursorOverlay(
                this, overlayDisplay, app.injector,
            ).also { it.show() }
            Log.d(TAG, "Cursor recreated on top of overlays")
        }.onFailure { Log.w(TAG, "recreateCursorOnTop failed", it) }
        // The add can fail SILENTLY on a fresh VD: the display's a11y token is
        // transiently invalid for the first ~1-2s (log-proven 2026-07-09: two
        // cursor adds at +0.6s got "token null is not valid" while the dock's
        // add at +0.4s worked and later windows attached fine). show() swallows
        // that failure, and the old code never rechecked — leaving the session
        // with a working but INVISIBLE cursor until replug. Verify and retry
        // with backoff; the display-liveness check keeps a queued retry from
        // re-adding to a torn-down VD.
        if (cursorOverlay?.isAttached != true) {
            if (attempt < 6) {
                Log.w(TAG, "cursor overlay not attached (a11y token not ready?) — retry ${attempt + 1}/6 in 400ms")
                mainHandler.postDelayed({
                    if (runCatching { displayManager.getDisplay(overlayDisplay.displayId) != null }
                            .getOrDefault(false)
                    ) {
                        recreateCursorOnTop(overlayDisplay, attempt + 1)
                    }
                }, 400L)
            } else {
                Log.e(TAG, "cursor overlay attach FAILED after retries — cursor will be invisible this session")
            }
        } else if (attempt > 0) {
            Log.w(TAG, "cursor overlay attached on retry $attempt")
        }
    }

    /**
     * Attach the cursor + floating-nav overlays to [overlayDisplay],
     * honoring the user's prefs. Idempotent-ish: dismisses any existing
     * instances first so it's safe to call from onSurfaceReady (which can
     * fire more than once across surface recreations).
     *
     * Split out of reconcile() so it can be deferred until the VD mirror's
     * surface is ready (fixes the cursor being invisible on first launch).
     */
    private fun attachVdOverlays(overlayDisplay: Display) {
        val app = applicationContext as PortalPadApp

        // Cursor overlay is attached at the TAIL of this method (after the dock),
        // not here — see the end of attachVdOverlays. Attaching it here too would
        // just be torn down and re-added moments later, and worse, an early attach
        // lands UNDER the dock that attaches after it. We only clear any stale
        // instance now; the single authoritative attach happens last.
        cursorOverlay?.dismiss(); cursorOverlay = null
        val cursorEnabled = runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.bool(PreferencesRepository.Keys.ENABLE_MOUSE_HOVER, default = true).first()
            }
        }.getOrDefault(true)

        // Floating nav buttons — retired feature. The Controls toggle was
        // removed; we force this off (ignoring any previously-stored pref) so it
        // can't get stuck on with no way to disable it. NavButtonsOverlay is kept
        // in the codebase in case the feature is reinstated later.
        navButtonsOverlay?.dismiss(); navButtonsOverlay = null
        val navEnabled = false
        if (navEnabled) {
            try {
                navButtonsOverlay = com.portalpad.app.presentation.NavButtonsOverlay(
                    this, overlayDisplay, app.injector,
                ).also { it.show() }
            } catch (t: Throwable) {
                Log.w(TAG, "NavButtonsOverlay attach failed", t)
            }
        }

        // Desktop-windows (DeX-style) overlays: a bottom taskbar of running
        // tasks and a top window-control bar. Both are gated behind the beta
        // DESKTOP_MODE_ENABLED pref (default off) — when off we tear any down
        // and attach nothing, so the default configuration is untouched.
        taskbarOverlay?.dismiss(); taskbarOverlay = null
        topWindowBarOverlay?.dismiss(); topWindowBarOverlay = null
        shizukuWarningOverlay?.dismiss(); shizukuWarningOverlay = null
        val desktopMode = runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.bool(PreferencesRepository.Keys.DESKTOP_MODE_ENABLED, default = false).first()
            }
        }.getOrDefault(false)
        if (desktopMode) {
            // Enable platform freeform support once we're attaching desktop
            // overlays (idempotent; runs off the main thread since it shells out).
            scope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { app.freeform.enableFreeform() }
            } }
        }
        // Top window-control bar now shows in BOTH modes. In desktop mode it
        // offers window controls (minimize / ❐ / close); in non-desktop mode it
        // offers Back / Home / Close for the single fullscreen app. The mode is
        // passed in; the bar is recreated on the DESKTOP_MODE trigger so it
        // always reflects the current mode.
        try {
            topWindowBarOverlay = com.portalpad.app.presentation.TopWindowBarOverlay(
                this, overlayDisplay, app.freeform, app.injector, desktopMode,
            ).also { it.show() }
        } catch (t: Throwable) {
            Log.w(TAG, "TopWindowBarOverlay attach failed", t)
        }

        // Shizuku-disconnect warning banner. CRITICAL: this must live on the
        // PHYSICAL glasses display, NOT the virtual display. The VD is created via
        // Shizuku and is torn down / stops mirroring when Shizuku dies — so a
        // banner on the VD (like the other overlays) would vanish exactly when we
        // need it. The physical display exists as long as the cable is connected,
        // independent of Shizuku, so a banner there still renders after Shizuku
        // dies. Observes status itself and shows/hides automatically; only visible
        // when on Shizuku and it's installed-but-disconnected.
        try {
            shizukuWarningOverlay?.dismiss()
            val physicalDisplay = app.physicalExternalDisplayId.value
                ?.let { id -> runCatching { displayManager.getDisplay(id) }.getOrNull() }
                ?: overlayDisplay
            shizukuWarningOverlay = com.portalpad.app.presentation.ShizukuWarningOverlay(
                this, physicalDisplay,
            ).also { it.show() }
        } catch (t: Throwable) {
            Log.w(TAG, "ShizukuWarningOverlay attach failed", t)
        }

        // Attach / re-assert the DOCK here. In VD mode this is now the dock's
        // authoritative attach (the inline attach in refreshExternalDisplay is
        // skipped for usingVd — see there), running only once the mirror surface
        // and the VD's a11y overlay token have settled, so the 2032 add succeeds
        // instead of throwing BadToken and falling back to Presentation.
        //
        // Re-home condition: attach when there is no proper 2032 OVERLAY dock on
        // this display yet. Critically, a Presentation-fallback dock (left by an
        // early attach on a previous cycle, or by a transient 2032 failure) does
        // NOT count as satisfied when the a11y service is bound — we tear it down
        // and re-home to 2032, because Presentation is NOT exempt from
        // HIDE_NON_SYSTEM_OVERLAY_WINDOWS and would vanish during permission
        // dialogs. When a11y is NOT bound, 2032 can't succeed, so any showing dock
        // (Presentation / 2038) is accepted and left alone — that also prevents a
        // dismiss→re-add→fail loop across attachVdOverlays' repeated runs.
        val dockEnabled = runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.bool(PreferencesRepository.Keys.DOCK_ENABLED, default = true).first()
            }
        }.getOrDefault(true)
        val a11yReady = com.portalpad.app.service.PortalPadAccessibilityService.instance != null
        val dockSatisfied = isDockOverlayShowing(overlayDisplay.displayId) ||
            (!a11yReady && isDockShowing(overlayDisplay.displayId))
        if (dockEnabled && !dockSatisfied) {
            dismissDockOnly()
            attachDock(overlayDisplay, DockDisplayMode.OVERLAY)
            Log.d(TAG, "Dock (re)attached in attachVdOverlays on display ${overlayDisplay.displayId} (a11yReady=$a11yReady)")
        }

        // Cursor must be the LAST overlay attached so its window token is newest
        // in the layer — among equal-type overlays, z-order = add order, so the
        // last addView wins. The dock/taskbar attach ABOVE this point, so the
        // cursor has to be (re)added AFTER them; and this method re-runs several
        // times during startup surface churn, each run re-adding the dock — which
        // is why the cursor sat under the dock icons for ~5-6s until the churn
        // settled. Recreating the cursor on top here — at the tail of EVERY run,
        // after the dock — makes it topmost on every cycle, not just when the
        // dock's own attach callback happens to win. Posted so it runs after the
        // dock's async view attach this frame.
        if (cursorEnabled) {
            mainHandler.post { recreateCursorOnTop(overlayDisplay) }
        }
    }

    private fun isDockShowing(displayId: Int): Boolean =
        (dockOverlay?.isShowing == true && dockOverlay?.displayId == displayId) ||
            (dockPresentation?.isShowing == true && dockPresentation?.display?.displayId == displayId)

    /** True only when the dock is showing as a proper OVERLAY window
     *  ([dockOverlay], i.e. 2032 when a11y is bound / 2038 otherwise) on
     *  [displayId] — NOT the Presentation fallback. attachVdOverlays uses this
     *  to decide whether to re-home a stranded Presentation dock to a real
     *  overlay once the display has settled. */
    private fun isDockOverlayShowing(displayId: Int): Boolean =
        dockOverlay?.isShowing == true && dockOverlay?.displayId == displayId

    /**
     * Reconciles state with the current display set:
     *  - finds the first non-default display (if any)
     *  - updates [PortalPadApp.externalDisplayId]
     *  - attaches a dock (overlay or presentation per user pref) to it
     *  - on first attach, launches TrackpadActivity
     *
     * @param force when true, always re-reconciles even if the external
     *   display hasn't changed. Pref-change watchers pass true. The display
     *   listener (onDisplayChanged) passes false so unrelated display events
     *   (which fire on every window activity, including our own overlay
     *   updates) don't cause expensive teardown/rebuild loops.
     * @param trigger a short string identifying what called us, for
     *   diagnostic logging.
     */
    /**
     * Samsung only. If "Stop DeX when PortalPad starts" is enabled (default) AND
     * DeX is CONFIRMED currently on, turn it off so DeX and our VirtualDisplay
     * don't fight over the external display. We fire the DeX tile ONLY when we
     * positively read DeX as on — the tile is a blind TOGGLE, so firing it on a
     * false/unknown read could turn DeX ON at the worst possible moment. Returns
     * true only when it actually toggled DeX off, so the caller can let the
     * display topology settle before creating the VD. Best-effort, fully guarded;
     * runs on the caller's background thread.
     */
    private fun maybeStopDexForSession(app: PortalPadApp): Boolean {
        if (!android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return false
        val access = app.access
        if (!access.isReady) return false
        val wantStop = runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.bool(
                    PreferencesRepository.Keys.STOP_DEX_ON_START, default = true,
                ).first()
            }
        }.getOrDefault(true)
        if (!wantStop) return false
        val dexOn = runCatching {
            access.execShell("settings get system dex_on_external_display").trim() == "1"
        }.getOrDefault(false)
        if (!dexOn) {
            Log.d(TAG, "stop-DeX-on-start: DeX not confirmed on — skipping (no blind toggle)")
            return false
        }
        Log.d(TAG, "stop-DeX-on-start: DeX confirmed on → toggling tile OFF before VD creation")
        runCatching {
            access.execShell(
                "cmd statusbar click-tile com.sec.android.app.launcher/com.honeyspace.dexservice.DesktopModeTile",
            )
        }
        return true
    }

    /**
     * Public reconcile entry for bubble-launched interfaces. The floating bubble
     * (drawn by the foreground service) can outlive the trackpad activity — e.g.
     * the user swipes PortalPad from recents but the service survives. That can
     * leave the external-display session half-wired, so an interface launched
     * straight from the bubble flashes onto the glasses and dies. Calling this
     * first rebuilds the session on the live display (a forced reconcile, the
     * same work opening the app does) so the interface has something to attach to.
     */
    fun reconcileExternalForBubbleLaunch() {
        // Only rebuild if the session is actually torn — a forced reconcile when
        // it's healthy would needlessly flash the glasses. The real cause of the
        // "lands in a hidden task" bug was a manifest task-affinity collision
        // (fixed separately); this just covers the case where the service also
        // lost its VD session.
        val torn = !virtualDisplaySession.isActive
        Log.d(TAG, "reconcileExternalForBubbleLaunch: vdActive=${virtualDisplaySession.isActive} torn=$torn")
        if (torn) refreshExternalDisplay(force = true, trigger = "bubble-launch")
    }

    /**
     * True if a visible window fills (most of) the external display — the signal
     * for "fullscreen media" used by the media cursor auto-hide when PortalPad's
     * own maximize flag isn't set. That flag only tracks PortalPad's maximize
     * action, so it misses Android's caption-bar maximize and app-driven
     * fullscreen (e.g. tapping fullscreen in YouTube), which is what users
     * actually do. Here we instead compare each task's parsed bounds against the
     * external/VD display's real size: a window counts as filling when it covers
     * >=90% width and >=85% height (the height slack tolerates a system caption
     * strip). Returns false if the display size can't be read, so the cursor is
     * never hidden on a guess. Only meaningful in desktop mode (in extend mode
     * apps are always fullscreen and the caller short-circuits before calling).
     */
    private fun hasWindowFillingExternal(
        dm: DisplayManager,
        snap: WindowMonitor.Snapshot,
    ): Boolean {
        if (!snap.desktop) return false
        val a = applicationContext as? PortalPadApp ?: return false
        val dispId = a.externalDisplayId.value ?: return false
        if (dispId < 0) return false
        val display = runCatching { dm.getDisplay(dispId) }.getOrNull() ?: return false
        val m = android.util.DisplayMetrics()
        runCatching { display.getRealMetrics(m) }
        val dw = m.widthPixels
        val dh = m.heightPixels
        if (dw <= 0 || dh <= 0) return false
        val minW = (dw * 0.90f).toInt()
        val minH = (dh * 0.85f).toInt()
        return snap.tasks.any { t ->
            val b = t.bounds
            t.visible && b != null &&
                (t.displayId == dispId || t.displayId < 0) &&
                b.width >= minW && b.height >= minH
        }
    }

    private fun refreshExternalDisplay(
        autoLaunchOnFirstAttach: Boolean = false,
        force: Boolean = false,
        trigger: String = "?",
        // When true, skip the Auto-Launch CONTENT (wallpaper/app) on this attach,
        // but still do the rest of first-attach setup (trackpad re-activation, etc).
        // Set on a flap-recovery replug: recoverStrandedSessionOnFlap is bringing
        // the user's windows back, so firing the wallpaper/app on top would stack a
        // spurious extra window over the recovered session.
        suppressAutoStartContent: Boolean = false,
    ) {
        val app = applicationContext as PortalPadApp
        val external = pickExternalDisplay()
        val newId = external?.displayId
        val lastId = lastReconciledDisplayId
        Log.d(TAG, "refreshExternalDisplay(trigger=$trigger force=$force) external=$newId lastReconciled=$lastId")

        // Idempotent fast path: same external display as last successful
        // reconcile, and nobody is forcing a re-reconcile. Skip the work.
        // This prevents the cursor-motion → onDisplayChanged feedback loop
        // that was tearing down and rebuilding the VD/overlays dozens of
        // times per second.
        if (!force && newId != null && newId == lastId) {
            return
        }

        // Freeze guard: during/right after a physical unplug, the system's
        // display-disconnect transition heavily contends WindowManager, so any
        // reconcile that runs teardown/window ops here blocks the main thread
        // for seconds (observed 4-8s). The dedicated DISPLAY_REMOVED handler
        // already tears the session down on unplug (via the grace model), so a
        // non-forced displayChanged reconcile while the physical display is
        // absent should just no-op. A replug reconciles with force / a present
        // physical id. NOTE both signals are checked: the StateFlow is nulled
        // by the removal handler and LAGS, so a debounced displayChanged that
        // lands in the gap (observed 500ms before DISPLAY_REMOVED) used to slip
        // past this guard and tear the VD down with NO grace — then grace was
        // armed for the corpse, the replug "cancelled" it, vdSurvivedDisconnect
        // lied, and the liveness filter dropped the entire restore. external
        // (from pickExternalDisplay above) is the ground truth.
        if (!force && trigger.startsWith("displayChanged") &&
            (external == null || app.physicalExternalDisplayId.value == null)
        ) {
            if (external == null && app.physicalExternalDisplayId.value != null) {
                Log.w(TAG, "refreshExternalDisplay SKIP ($trigger): physical gone but removal handler hasn't run yet — deferring to DISPLAY_REMOVED/grace")
                return
            }
            Log.w(TAG, "refreshExternalDisplay SKIP ($trigger): physical absent — avoiding contended-WM block")
            return
        }

        if (external == null) {
            app.setExternalDisplayId(null)
            app.setPhysicalExternalDisplayId(null)
            app.setVirtualDisplayId(null)
            if (lastId != null) {
                // We had a display; release VD and any overlays now that it's
                // gone. The VD release can block on a contended system during a
                // disconnect transition, so run it off the main thread; the
                // overlay dismissals are fast UI ops and stay on-thread.
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { virtualDisplaySession.stop() }
                }
                dismissDock()
                dismissVdMirror()
            }
            lastReconciledDisplayId = null
            lastAppliedVdSpec = null
            return
        }

        if (!force && isDockShowing(external.displayId)) {
            Log.d(TAG, "refresh: dock already showing on disp=${external.displayId}, skip")
            lastReconciledDisplayId = external.displayId
            return
        }

        val wasNullBefore = app.externalDisplayId.value == null

        // Decide whether to wrap the external display in a trusted Virtual
        // Display. Comparable tools don't expose this as a user
        // toggle — they auto-detect based on the display:
        //
        //   TYPE_OVERLAY (4) — AR glasses (XREAL, Viture, etc.) →
        //     wrap. These displays are typically created as untrusted and
        //     reject injected events without wrapping in a TRUSTED VD.
        //   TYPE_EXTERNAL (2) — HDMI / DisplayPort / USB-C DP →
        //     wrap too. Same security-trust constraints apply, and
        //     wrapping gives us a controlled coordinate space.
        //
        // We require Shizuku to be bound (we can't create a TRUSTED VD
        // without elevated privilege). If Shizuku isn't bound, we fall
        // back to using the raw external display — clicks may not land
        // but at least the user sees their app on the glasses.
        //
        // The old DISPLAY_GLASSES_MODE pref is deprecated; we ignore it
        // and always wrap. (It's still in PreferencesRepository for
        // backwards-compatibility with stored values but no longer read.)
        val wrapInVd = app.activeBoundBackend?.isReady == true

        val (effectiveDisplayId, usingVd) = try {
            if (wrapInVd) {
                // Before creating the VD: on a FRESH attach, turn Samsung DeX off
                // if the user opted in and DeX is actually on (see
                // maybeStopDexForSession). Suppress the display listener across
                // the toggle + settle so DeX releasing the external display can't
                // re-enter this reconcile mid-flight. Only sleep when we really
                // toggled (the helper confirms DeX-on first).
                if (wasNullBefore) {
                    suppressDisplayListener("stop-DeX-on-start")
                    if (maybeStopDexForSession(app)) Thread.sleep(700)
                }
                val metrics = android.util.DisplayMetrics().also { external.getRealMetrics(it) }
                val nativeW = metrics.widthPixels.coerceAtLeast(1)
                val nativeH = metrics.heightPixels.coerceAtLeast(1)
                // Aspect-ratio override. "AUTO" (default) keeps the glasses'
                // native resolution. Otherwise we fit the chosen ratio INSIDE
                // the native panel: keep whichever native dimension is the
                // limiting one and shrink the other, so the VD never exceeds the
                // panel (the physical panel letterboxes/pillarboxes the rest).
                val aspect = runCatching {
                    kotlinx.coroutines.runBlocking { app.prefs.aspectRatio.first() }
                }.getOrDefault("AUTO")
                val (w, h) = aspectToVdSize(aspect, nativeW, nativeH)
                // DPI selection — at 1920x1080:
                //   213 dpi (XREAL native) → 811dp wide → DESKTOP Chrome
                //     (tab strip visible, like Samsung DeX).
                //   320 dpi → 540dp wide → MOBILE Chrome (no tabs).
                //   360 dpi (our default) → 480dp wide → comfortable mobile UI.
                //
                // We use 360 when the user hasn't set anything in Settings,
                // and honor whatever they pick (80-640 range) otherwise — no
                // silent overrides.
                val userDpi = runCatching {
                    kotlinx.coroutines.runBlocking { app.prefs.displayDpi.first() }
                }.getOrDefault(0)
                // Default DPI is 360 when the user hasn't set one (pref==0).
                // (Previously defaulted to the glasses' native DPI.) Users can
                // still override to anything in 80..640 from the Display tab.
                // When the user hasn't set a DPI (pref==0 = Auto), use the external
                // display's DETECTED density; fall back to 213 if that's implausible
                // (some glasses report odd native values). User picks in 80..640
                // override directly.
                val detectedDpi = metrics.densityDpi
                val dpi = if (userDpi in 80..640) userDpi
                    else detectedDpi.takeIf { it in 120..480 } ?: 213
                Log.d(TAG, "Starting AirGlassesSession: glasses=${external.displayId} ${w}x$h dpi=$dpi (userDpi=$userDpi detected=$detectedDpi)")

                suppressDisplayListener("AirGlassesSession.start")
                // rebind(): if the session survived the disconnect grace, adopt
                // the NEW physical id and resize the SAME VD in place — tasks
                // (and their in-app state, e.g. Chrome tabs) are never touched.
                // Falls through to a normal fresh start when nothing survived.
                val vdId = virtualDisplaySession.rebind(external.displayId, w, h, dpi)
                if (vdId >= 0) {
                    Log.d(TAG, "AirGlassesSession started: vd=$vdId glasses=${external.displayId}")
                    // Base layer, unconditionally. Must NOT depend on launchAutoStart's
                    // gating (suppressAutoStartContent / wasNullBefore / vdJustCreated):
                    // when those all fell false on a double-pass connect, nothing laid
                    // the backdrop and restored windows floated on black. Deduped by
                    // displayId, so a brand-new VD always gets one and a later
                    // launchAutoStart()/refocus-fallback call is a no-op.
                    ensureBackdrop(vdId, "vd-created")
                    // IME-policy BASELINE at birth: apply the pref-correct
                    // policy once now, so (with the dedupe in the injector)
                    // the first relay open of the session is already a
                    // policy NO-OP — the address-bar tap's suggestion
                    // dropdown survives the relay appearing.
                    runCatching { app.injector.repinImePolicy(aggressive = false) }
                    vdId to true
                } else {
                    Log.w(TAG, "AirGlassesSession failed to start; falling back to raw glasses id")
                    external.displayId to false
                }
            } else {
                Log.w(TAG, "External display attached but Shizuku UserService not bound; using raw id (no VD wrap)")
                if (virtualDisplaySession.isActive) suppressDisplayListener("AirGlassesSession.stop")
                virtualDisplaySession.stop()
                external.displayId to false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AirGlassesSession threw; falling back to raw glasses id", t)
            external.displayId to false
        }

        app.setExternalDisplayId(effectiveDisplayId)
        // Track the underlying physical display separately — needed for
        // screencap and other SurfaceFlinger-level ops that don't see the VD.
        app.setPhysicalExternalDisplayId(external.displayId)
        // Expose VD id so Settings (and other observers) can target it for
        // `am start --display`. Null when not using the VD wrap.
        app.setVirtualDisplayId(if (usingVd) effectiveDisplayId else null)
        // Wanted a VD (backend ready) but ended up raw → VD creation failed; mark
        // it so the watchdog doesn't retry forever on devices that can't create one.
        lastAttachWasRawFallback = wrapInVd && !usingVd
        // A wrapped VD whose id differs from the last one is brand-new and empty
        // (fresh attach, Shizuku stop→restart, or a screen-off flap-recreate) → its
        // backdrop must be (re)laid. A reused id is a mid-session reconcile (DOCK/
        // DESKTOP toggle) → leave the user's content alone.
        val vdJustCreated = usingVd && effectiveDisplayId != lastVdId
        lastVdId = if (usingVd) effectiveDisplayId else -1
        // Capture how long the external display was gone BEFORE we (maybe) clear
        // the marker. A short gap means this fresh VD is a screen-off flap-recreate
        // (the old VD was destroyed and whatever was on it got evacuated to the
        // phone), which we recover from below.
        //
        // We must NOT clear the marker here unconditionally: when the screen goes
        // off, the backend drops and an INTERMEDIATE refresh runs with no VD
        // (usingVd=false) before Shizuku rebinds and we rebuild. If that
        // intermediate pass cleared the marker, the real rebuild pass would see
        // gap=∞ and skip recovery (the bug). So the marker is only consumed in the
        // usingVd success path, after the recovery check.
        val flapGapMs = if (lastDisplayRemovedAt > 0L)
            System.currentTimeMillis() - lastDisplayRemovedAt else Long.MAX_VALUE
        // Targeting rule (the Chrome-dropdown architectural fix from
        // dumpsys-input comparison with the reference implementation):
        //
        //  - If we have a trusted VD running AND a mirror overlay binding
        //    its surface to the physical glasses display, apps launched via
        //    `am start --display <vdId>` will run on the VD. Mouse events
        //    injected to the VD's displayId land on those apps, and Chrome's
        //    anchor popups (address-bar suggestions) survive cursor motion
        //    because the VD is trusted.
        //
        //  - If we don't have the VD wrap (Display Glasses mode off, or
        //    Shizuku not bound), inject to the physical glasses display.
        //    Anchor popups will still dismiss on motion in this mode (the
        //    pre-VD bug); that's a known limitation when not running the
        //    full trusted-VD pipeline.
        val injectionDisplayId = if (usingVd) effectiveDisplayId else external.displayId
        app.injector.displayId = injectionDisplayId
        // The setter above dedupes on an UNCHANGED id and skips its metrics
        // read — which is exactly the resolution-switch case: rebind() resizes
        // the SAME VD in place (e.g. 1920→3840 ultrawide), the resize's
        // onDisplayChanged is swallowed by the suppression window around
        // session start, and the injector kept clamping the cursor to the old
        // width — the cursor dead-ended at the center of an ultrawide canvas.
        // Re-read the size explicitly: clamps without recentering and is a
        // documented cheap no-op when nothing changed, so fresh attaches are
        // unaffected. Second delayed read covers DisplayManager propagation
        // lag after an in-place resize (the aspect path sleeps 250ms for the
        // same reason).
        runCatching { app.injector.updateDisplaySizeForRotation() }
        mainHandler.postDelayed({
            runCatching { app.injector.updateDisplaySizeForRotation() }
        }, 600L)
        app.injector.usingVd = usingVd
        Log.d(TAG, "injection target: displayId=$injectionDisplayId (usingVd=$usingVd, physical=${external.displayId})")

        // Recover an app stranded on the phone by a screen-off flap. When the
        // screen turns off, the Shizuku-owned VD is destroyed and whatever was
        // on it (e.g. a SmartTube video) is evacuated to the phone's default
        // display. We rebuild a fresh VD here — but the display LISTENER's
        // flap-recovery can't fire for it, because we suppress the listener
        // while rebuilding, so the DISPLAY_ADDED for this new VD is swallowed.
        // Trigger recovery straight from the rebuild path instead. Gated on a
        // recent removal (flapGapMs) so a first/clean attach never triggers it.
        if (vdJustCreated && usingVd && flapGapMs < 30_000L) {
            Log.d(
                "PortalPadSleep",
                "REBUILD-FLAP (gone ${flapGapMs}ms) -> recover stranded session vd=$effectiveDisplayId",
            )
            recoverStrandedSessionOnFlap(effectiveDisplayId)
        }
        // Consume the flap marker only now that a real VD is up (or leave it set
        // when usingVd=false, so the eventual rebuild pass — and the idle
        // auto-disable watcher — still see it).
        if (usingVd) lastDisplayRemovedAt = 0

        // Pick the display where our cursor + dock overlays should live.
        // Computed here (before the mirror block) so the mirror's
        // onSurfaceReady callback can capture it.
        //
        //   When usingVd, all of PortalPad's overlays should be SIBLINGS
        //   of Chrome on the VD (the reference implementation's dump showed all their overlays
        //   on the VD alongside Chrome). Putting them on the physical
        //   glasses display while Chrome lives on the VD creates a cross-
        //   display arrangement that breaks Chrome's anchor popups.
        //
        //   Visually the VD's content is mirrored to the physical glasses
        //   via the SurfaceView, so VD-resident overlays still appear on
        //   the glasses — composited "inside" the VD's surface.
        //
        //   When NOT usingVd, overlays live on the physical glasses display.
        val overlayDisplay: Display = if (usingVd) {
            runCatching { displayManager.getDisplay(effectiveDisplayId) }
                .getOrNull() ?: external
        } else external
        Log.d(TAG, "overlay target: displayId=${overlayDisplay.displayId} (usingVd=$usingVd)")
        lastOverlayDisplay = overlayDisplay

        dismissDock()

        // VD mirror — only when usingVd. Adds a fullscreen SurfaceView
        // overlay to the PHYSICAL glasses display. When the SurfaceView's
        // surface becomes available, we redirect the VD's output into it
        // so VD content appears on the user's glasses. Without this the
        // VD renders to an invisible ImageReader and the user sees nothing.
        dismissVdMirror()
        // Panel-feed selection. When the system-mirror pref is on, drive the
        // glasses via a SurfaceControl layerStack retarget (no app overlay →
        // immune to the security overlay-hide that blanks the panel during
        // USB/permission dialogs). If the ROM refuses the retarget, we fall
        // through to the proven overlay + GL-shader path below. The toggle (and
        // the auto-fallback) make this fully reversible.
        val systemMirrorPref = usingVd && (transientMirrorForDialog || runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.panelSystemMirror.first()
            }
        }.getOrDefault(false))
        var systemMirrorStarted = false
        if (systemMirrorPref) {
            // Optimistically commit to the mirror path so the setup below runs
            // on the main thread without waiting on a shell binder call. The
            // actual layerStack retarget + color (synchronous binder calls to
            // the shell service) run OFF the main thread — on unplug the system
            // is contended and those calls can block for seconds, which froze
            // the UI. If already mirroring this display, skip re-retargeting so
            // reconcile/recovery churn doesn't repeatedly re-issue them.
            val gid = external.displayId
            val vdId = overlayDisplay.displayId
            // Must compare the VD too, not just the glasses display. System mirror
            // retargets the PHYSICAL panel at the VD's layerStack; when the VD is
            // destroyed and recreated (service restart, transient-mirror revert, a
            // force refresh) the glasses id is unchanged but the layerStack is new.
            // Comparing only `gid` made this look like "already mirroring", so
            // startSystemMirror was never re-issued and the panel stayed pointed at
            // the OLD, destroyed layerStack — which produces no frames, so the panel
            // froze on its last image while the dock/cursor rendered into a new VD
            // nobody was looking at. (Toggling System mirror off/on repaired it,
            // because that does stop→start against the current VD.)
            //
            // Re-issuing start on a live retarget is safe: startSystemMirror keeps the
            // originally saved panel layerStack (observed: savedPanelLS=29 across
            // consecutive starts at vdLS=30 then 31), so restore still returns the
            // panel to its real layerStack.
            val alreadyMirroring = systemMirrorGlassesId == gid && systemMirrorVdId == vdId
            systemMirrorGlassesId = gid
            systemMirrorVdId = vdId
            mirrorModeActive = true
            systemMirrorStarted = true
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                attachVdOverlays(overlayDisplay)
            }
            scheduleOverlayReassert(overlayDisplay)
            dismissTransitionSpinnerWhenReady()
            if (!alreadyMirroring) {
                // The retarget + color, off the main thread.
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val ok = runCatching {
                        virtualDisplaySession.startSystemMirror(gid, vdId)
                    }.getOrDefault(false)
                    if (ok) Log.w(TAG, "panel-feed: system mirror retargeted glasses=$gid → vd=$vdId")
                    else Log.w(TAG, "panel-feed: system mirror retarget failed (async) glasses=$gid vd=$vdId")
                }
                // Performance overlay (standalone on the VD) + background
                // SurfaceFlinger fps poll — also off the main thread.
                runCatching { mirrorHud?.stop() }
                mirrorFpsJob?.cancel(); mirrorSfFps = -1f
                mirrorFpsJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    while (true) {
                        mirrorSfFps = runCatching {
                            virtualDisplaySession.sampleDisplayFps(vdId)
                        }.getOrDefault(-1f)
                        kotlinx.coroutines.delay(1500)
                    }
                }
                mirrorHud = com.portalpad.app.presentation.PerformanceHud.standalone(
                    this, overlayDisplay, glActive = false,
                    fpsSampler = { mirrorSfFps },
                )
            }
            if (!suppressAutoStartContent &&
                ((autoLaunchOnFirstAttach && wasNullBefore) || vdJustCreated)) {
                mainHandler.postDelayed({ launchAutoStart() }, 400L)
            }
            Log.w(TAG, "panel-feed: system mirror active (overlay path skipped, alreadyMirroring=$alreadyMirroring)")
        }
        if (usingVd && !systemMirrorStarted) {
            mirrorModeActive = false
            // A FRESH VD in overlay mode must ensure the panel is scanning its
            // own native layerStack — a prior stop-service leaves it retargeted
            // at the PHONE (the deliberate phone-mirror-on-stop), and the mirror
            // overlay about to attach renders into the panel's native stack. If
            // the panel is still pointed elsewhere, the overlay is invisible and
            // the session looks black until a replug. stopSystemMirror restores
            // the shell-saved original stack and is a logged SKIP no-op when no
            // retarget is active, so firing it on every fresh VD is safe. Gated
            // on vdJustCreated so mid-session reconciles (DOCK/DESKTOP toggles)
            // don't shell out. Off the main thread (shell binder call).
            if (vdJustCreated) {
                val gid = external.displayId
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { virtualDisplaySession.stopSystemMirror(gid) }
                }
            }
            try {
                vdMirror = com.portalpad.app.presentation.VirtualDisplayMirror(
                    this,
                    external,
                    onSurfaceReady = { surf ->
                        virtualDisplaySession.bindMirrorSurface(surf)
                        // Attach the cursor + nav overlays only AFTER the
                        // mirror surface is live. Previously we added the
                        // cursor overlay to the VD immediately, before the
                        // mirror's SurfaceView surface existed — so the
                        // cursor's window was composited against a not-yet-
                        // ready display and stayed invisible until a relayout
                        // (the user toggling "Custom cursor" off/on forced
                        // one). Deferring to onSurfaceReady means the cursor
                        // shows on first launch without any toggle.
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            attachVdOverlays(overlayDisplay)
                        }
                        // ...and once more on a short delay. surfaceCreated
                        // fires the instant the SurfaceView's surface exists —
                        // before the VD's output is actually flowing into it —
                        // so the cursor window can get composited against a
                        // not-yet-presenting display and stay invisible until
                        // something forces a relayout (previously: the user
                        // toggling "Custom cursor" off/on). This delayed
                        // re-assert reproduces that relayout deterministically,
                        // so the cursor shows on first launch with no toggle.
                        scheduleOverlayReassert(overlayDisplay)
                        // Lift the connect spinner now that the mirror surface is
                        // live and overlays are being re-asserted — input becomes
                        // responsive right after the reassert delay.
                        dismissTransitionSpinnerWhenReady()
                        // If no home app is assigned, the VD would otherwise sit
                        // black on a fresh launch (nothing calls goHome()). Show
                        // PortalPad's own home backdrop once the surface is live.
                        // Small cushion so the VD output is actually flowing before
                        // we launch onto it (launching too early lands on black),
                        // but kept short so the wallpaper appears quickly. Skipped
                        // if a home app IS set (that path handles its own launch).
                        // Session-start visual is decided by the Auto Launch
                        // setting (wallpaper / specific app / last app), which is
                        // independent of the Home button. Small cushion so the VD
                        // output is actually flowing before we launch onto it
                        // (launching too early lands on black), but kept short so
                        // it appears quickly.
                        // Session-start auto-launch must fire ONLY on a fresh
                        // attach (display just connected), NOT on every VD surface-
                        // ready. onSurfaceReady fires again on each
                        // refreshExternalDisplay reconcile (DOCK_ENABLED /
                        // DESKTOP_MODE toggles that happen while using the dock);
                        // without this guard, launchAutoStart re-ran mid-session
                        // and (with Auto Launch = Wallpaper) slammed the wallpaper
                        // over the app the user had just opened. Matches the
                        // non-VD path's `autoLaunchOnFirstAttach && wasNullBefore`.
                        if (!suppressAutoStartContent &&
                            ((autoLaunchOnFirstAttach && wasNullBefore) || vdJustCreated)) {
                            mainHandler.postDelayed({
                                launchAutoStart()
                            }, 200L)
                        }
                    },
                    onSurfaceGone = { virtualDisplaySession.unbindMirrorSurface() },
                ).also { it.show() }
                activeVdMirror = vdMirror
            } catch (t: Throwable) {
                Log.w(TAG, "VirtualDisplayMirror attach failed", t)
            }
        }

        // (overlayDisplay computed earlier, before the mirror block.)

        // Dock display mode is no longer user-selectable — always OVERLAY.
        // attachDock() still auto-falls back to a Presentation internally if
        // an OEM rejects the overlay window on the secondary display, so the
        // dock remains robust without exposing the choice.
        //
        // The dock is now SHARED across desktop and non-desktop modes (the
        // separate taskbar is retired). Desktop mode additionally shows the top
        // window-control bar (attached in attachVdOverlays) and launches dock
        // apps as freeform windows; non-desktop launches replace the current
        // window. So we attach the dock whenever DOCK_ENABLED, regardless of
        // desktop mode.
        val dockEnabled = runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.bool(PreferencesRepository.Keys.DOCK_ENABLED, default = true).first()
            }
        }.getOrDefault(true)

        // In VD mode the dock is attached from attachVdOverlays (driven off the
        // mirror's onSurfaceReady), NOT here. Attaching it inline runs during the
        // displayAdded refresh, before the freshly-created VD's accessibility
        // overlay token is valid — the 2032 addView throws BadToken and the dock
        // silently falls back to a Presentation. That stray Presentation dock then
        // makes attachVdOverlays' re-assert guard treat the dock as "already
        // showing" and skip the 2032 re-home, so the dock stays on Presentation
        // for the whole session (and Presentation is NOT exempt from
        // HIDE_NON_SYSTEM_OVERLAY_WINDOWS, so it vanishes during permission
        // dialogs — the exact thing 2032 exists to prevent). The non-VD path has
        // no surface to wait on, so it still attaches inline here.
        if (dockEnabled && !usingVd) attachDock(overlayDisplay, DockDisplayMode.OVERLAY)

        // Cursor + nav overlays.
        //
        // When usingVd, these are attached from the mirror's onSurfaceReady
        // callback above (so the cursor is composited against a live display
        // and shows on first launch). When NOT usingVd, there's no mirror
        // surface to wait on, so attach them right here on the physical
        // display.
        if (!usingVd) {
            attachVdOverlays(overlayDisplay)
        }

        // First-attach actions, keyed on the OBSERVED null→attached transition
        // (wasNullBefore), NOT on which trigger delivered the attach. The old
        // gate also required autoLaunchOnFirstAttach, which only the
        // displayAdded listener passes — so a fresh attach arriving via
        // vdWatchdog (listener suppressed at replug: seen on-device) or via
        // onCreate (One UI stopped the FGS on recents Close-All; START_STICKY
        // relaunched it) could NEVER re-activate the trackpad, leaving the user
        // on the phone home screen with only the bubble.
        if (wasNullBefore) {
            // The glasses-visual auto-launch KEEPS the listener gate
            // (autoLaunchOnFirstAttach): the VD path drives its own
            // launchAutoStart off surface-ready, and non-listener reconciles
            // already recover content via vdJustCreated — an extra launch here
            // would stack a spurious wallpaper/app over it. Flap-recovery
            // (suppressAutoStartContent) skips content too, as before.
            if (autoLaunchOnFirstAttach && !usingVd && !suppressAutoStartContent) launchAutoStart()
            // Only auto-launch trackpad if the user has the pref enabled.
            val autoActivate = runCatching {
                kotlinx.coroutines.runBlocking {
                    app.prefs.bool(
                        PreferencesRepository.Keys.AUTO_ACTIVATE_EXTERNAL,
                        default = true,
                    ).first()
                }
            }.getOrDefault(true)
            if (autoActivate) {
                startActivity(
                    Intent(this, TrackpadActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
        }

        // Cursor Z-order fix: the dock/taskbar/top-bar each have their own
        // overlay window token, and the cursor must be the newest window in the
        // accessibility-overlay layer to draw above them. Post the recreate so
        // it runs AFTER this frame's dock/taskbar attaches.
        //
        // Non-VD path only. In VD mode the cursor is owned end-to-end by
        // attachVdOverlays (attached at its tail, after the dock, from
        // onSurfaceReady). Recreating it here too — before the mirror surface is
        // even ready — just tears down and re-adds a cursor that attachVdOverlays
        // immediately replaces, adding to the per-attach recreate churn for no
        // benefit. (The dock's own onWindowAttached reassert still fires the
        // topmost recreate once the dock actually attaches.)
        if (!usingVd) mainHandler.post { recreateCursorOnTop(overlayDisplay) }

        // Mark this displayId as reconciled. Future onDisplayChanged events
        // for the same display will short-circuit (see the force/lastId
        // check at the top of this function).
        lastReconciledDisplayId = external.displayId
    }

    private fun attachDock(display: Display, mode: DockDisplayMode, dockAttempt: Int = 0) {
        // EXECUTION-TIME validity guard. Attaches get queued/posted by the
        // flap-recovery flows and can execute AFTER a physical-unplug teardown
        // already ran (seen in the wild: teardown dismissed the dock at
        // t+0.899s, a stale queued attach re-added it to the now-removed VD at
        // t+1.067s — Android parked that window on the PHONE screen, where it
        // lived until the service was stopped). The display must still exist
        // in DisplayManager AND still be the display we're currently tracking;
        // a stale attach logs and does nothing.
        run {
            val id = display.displayId
            val stillExists = runCatching { displayManager.getDisplay(id) != null }.getOrDefault(false)
            val app = applicationContext as? PortalPadApp
            val tracked = app?.externalDisplayId?.value
            if (!stillExists || tracked == null || tracked != id) {
                Log.w(
                    TAG,
                    "attachDock SKIPPED (stale): display=$id exists=$stillExists tracked=$tracked",
                )
                return
            }
        }
        when (mode) {
            DockDisplayMode.OVERLAY -> {
                try {
                    dockOverlay = DockOverlay(this, display).also { d ->
                        // Z-order: recreate the cursor only once the dock window
                        // has ACTUALLY attached. The old speculative one-hop post
                        // could run before the dock's traversal attached its
                        // window (esp. the async VD path), leaving the cursor
                        // UNDER the dock icons until some later relayout — the
                        // "cursor hidden behind dock icons at startup" bug.
                        d.onWindowAttached = {
                            // Reassert TWICE: the attach fires when the dock
                            // joins the view hierarchy, but its SURFACE (which
                            // decides z-order among sibling overlays) and its
                            // async Compose draw can finalize a frame or two
                            // later — a single reassert on attach sometimes still
                            // lands under the dock. The recreate is idempotent
                            // (dismiss + re-add), so a second pass after the
                            // surface settles is free insurance and deterministic.
                            mainHandler.post { recreateCursorOnTop(display) }
                            mainHandler.postDelayed({ recreateCursorOnTop(display) }, 250L)
                        }
                        d.show()
                    }
                    Log.d(TAG, "Dock overlay attached on display ${display.displayId}")
                } catch (t: Throwable) {
                    // A TRANSIENT a11y-token error (fresh VD, token not yet
                    // valid — validates within ~1-2s, log-proven) must NEVER
                    // trigger the Presentation fallback: that fallback is a
                    // fullscreen dialog whose default window background is
                    // OPAQUE and which takes focus — on 2026-07-09 it obscured
                    // the ENTIRE session (grey screen, apps launching invisibly
                    // underneath) until replug. Retry the 2032 instead; only
                    // genuine, persistent rejection (an OEM that refuses 2032
                    // on secondary displays) falls through to the Presentation.
                    val tokenIssue = t is android.view.WindowManager.BadTokenException ||
                        (t.message?.contains("token", ignoreCase = true) == true)
                    if (tokenIssue && dockAttempt < 6) {
                        Log.w(
                            TAG,
                            "DockOverlay.show hit a transient token error — retry ${dockAttempt + 1}/6 in 400ms (NOT falling back)",
                            t,
                        )
                        mainHandler.postDelayed({
                            attachDock(display, mode, dockAttempt + 1)
                        }, 400L)
                        return
                    }
                    // OEM rejected overlay on secondary display, or permission missing
                    Log.w(TAG, "DockOverlay.show failed — falling back to Presentation", t)
                    try {
                        dockPresentation = DockPresentation(this, display).also { it.show() }
                        mainHandler.post { recreateCursorOnTop(display) }
                    } catch (t2: Throwable) {
                        Log.e(TAG, "Dock presentation also failed", t2)
                    }
                }
            }
            DockDisplayMode.PRESENTATION -> {
                try {
                    dockPresentation = DockPresentation(this, display).also { it.show() }
                    Log.d(TAG, "Dock presentation attached on display ${display.displayId}")
                    mainHandler.post { recreateCursorOnTop(display) }
                } catch (t: Throwable) {
                    Log.e(TAG, "Could not show DockPresentation", t)
                }
            }
        }
    }

    /**
     * The physical external display (HDMI/glasses), if any. Filters out:
     *  - DEFAULT_DISPLAY (phone screen)
     *  - Our own AirGlassesSession VD (so creating it doesn't make us pick it
     *    as "the external display" on the next refresh)
     *  - Any display named "PortalPad Session" (belt-and-suspenders)
     *  - VIRTUAL displays from other apps (Android Auto's
     *    `GhostActivityDisplay` for car projection, Google Cast / Chromecast
     *    virtual displays, MediaProjection screen-mirror, etc.). We only
     *    care about REAL physical displays — HDMI, DisplayPort, USB-C
     *    DisplayPort (XREAL/Viture/etc.).
     *  - DECORATIONS-less private displays (FLAG_PRIVATE without
     *    FLAG_PRESENTATION generally means "internal helper display").
     *
     * Detection mechanism: `Display.flags` exposes display capability
     * bits. We accept a display only if it's:
     *   - Not `FLAG_PRIVATE` set without `FLAG_PRESENTATION` (those are
     *     usually app-owned VDs)
     *   - Type is `Display.TYPE_EXTERNAL` (HDMI/DisplayPort) OR
     *     `Display.TYPE_OVERLAY` (XREAL-style overlay device)
     *     — NOT `TYPE_VIRTUAL` (app-created VDs).
     *
     * On older Android versions, Display.getType() is hidden but
     * still callable via reflection.
     */
    private fun pickExternalDisplay(): Display? {
        val ourVdId = if (virtualDisplaySession.isActive) virtualDisplaySession.virtualDisplayId else -1
        return displayManager.displays.firstOrNull { d ->
            isAcceptableExternalDisplay(d, ourVdId)
        }
    }

    /**
     * Returns true if [d] looks like a real physical external display
     * (HDMI/DisplayPort/USB-C from AR glasses) and not an app-created
     * virtual display we should ignore.
     */
    private fun isAcceptableExternalDisplay(d: Display, ourVdId: Int): Boolean {
        // Our own live session VD is excluded here (the shared predicate can't
        // know its runtime id); everything else — DEFAULT, virtual/projection,
        // type EXTERNAL/OVERLAY gating, and the reflection-failure fallback —
        // lives in DisplayUtil so the Enable gate and this reconcile agree.
        if (d.displayId == ourVdId) return false
        val ok = com.portalpad.app.DisplayUtil.isRealExternalDisplay(d)
        if (!ok) {
            val sig = "${d.displayId}:${d.name}"
            if (sig != lastSkippedDisplaySig) {
                lastSkippedDisplaySig = sig
                Log.d(TAG, "skipping display ${d.displayId} '${d.name}' (not a real external display)")
            }
        }
        return ok
    }

    /**
     * Polls every 30 seconds: if the user has set an auto-disable timeout AND
     * no external display has been present for that long, stops the service.
     * Default is 0 (never), so this is opt-in.
     */
    private fun startAutoDisableWatcher() {
        val app = applicationContext as PortalPadApp
        autoDisableJob = scope.launch {
            launch {
                app.prefs.int(PreferencesRepository.Keys.AUTO_DISABLE_AFTER_MIN, 0)
                    .collect { autoDisableAfterMin = it }
            }
            while (true) {
                kotlinx.coroutines.delay(30_000)
                val timeoutMin = autoDisableAfterMin
                if (timeoutMin <= 0) continue
                if (app.externalDisplayId.value != null) continue
                if (lastDisplayRemovedAt == 0L) {
                    // Service started without a display ever attaching — count from now
                    lastDisplayRemovedAt = System.currentTimeMillis()
                    continue
                }
                val idleMs = System.currentTimeMillis() - lastDisplayRemovedAt
                if (idleMs >= timeoutMin * 60_000L) {
                    Log.d(TAG, "Auto-disabling after ${timeoutMin}min idle")
                    stopSelf()
                    return@launch
                }
            }
        }
    }

    /**
     * Persist the open apps + positions as the "last session" so the user can
     * resume after a disconnect/disable. Triggered two ways:
     *   1. Event-driven (debounced ~1.5s) when windows change via PortalPad's
     *      controls — so we capture AFTER a move/resize settles (a window is often
     *      briefly fullscreen right at launch; the debounce lets it settle).
     *   2. A slow safety-net poll (~30s) to catch changes made outside our
     *      controls (e.g. dragging a window by Samsung's own title bar).
     * Both go through [captureSessionNow], which skips transient/junk states.
     * This replaces the old every-5s poll — more accurate AND less battery.
     */
    private fun startSessionSnapshotter() {
        val app = applicationContext as PortalPadApp
        // 1. Event-driven, debounced.
        scope.launch {
            app.windowsChanged.collectLatest {
                kotlinx.coroutines.delay(1_500) // settle; collectLatest cancels if another change arrives
                captureSessionNow(app)
            }
        }
        // 2. React to ANY window-state change — including native mouse-drag moves
        //    and resizes, which DON'T fire windowsChanged (only PortalPad's own
        //    window ops do). The shared WindowMonitor already polls bounds every 2s
        //    and dedups, so this emits only on a real change; the debounce waits for
        //    the drag to settle. This keeps each resolution's saved slot current, so
        //    a custom layout is remembered when you switch/unplug — not lost until
        //    the slow safety poll below.
        scope.launch {
            app.windowMonitor.snapshot
                .map { snap -> snap.tasks.map { t -> Triple(t.taskId, t.bounds, t.visible) } }
                .distinctUntilChanged()
                .collectLatest {
                    kotlinx.coroutines.delay(1_500)
                    captureSessionNow(app)
                }
        }
        // 3. Slow safety-net poll (backstop for anything the above misses).
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                captureSessionNow(app)
            }
        }
        // 4. Same-app window cap. Multiple windows of one app (e.g. a 3rd Chrome
        //    window) can be destroyed by the display re-enumeration a resolution
        //    switch triggers, with no way to relaunch a specific lost window — so we
        //    hold the line at MAX_SAME_APP_WINDOWS. The WindowMonitor poll is the
        //    only point that sees EVERY window however it opened (dock, drawer, or a
        //    link opening a new window from inside the app), so the cap lives here.
        //    Reactive by nature: the extra window opens, then is closed within ~2s
        //    with a glasses toast. NOT enforced during recovery — restore brings
        //    several same-app windows back at once and we must not race it.
        scope.launch {
            app.windowMonitor.snapshot
                .map { snap -> snap.tasks.map { it.taskId to it.packageName } }
                .distinctUntilChanged()
                .collectLatest { tasks ->
                    if (app.isFlapRecovering()) return@collectLatest
                    val overflow = tasks
                        .groupBy { it.second }
                        .filterValues { it.size > MAX_SAME_APP_WINDOWS }
                    if (overflow.isEmpty()) return@collectLatest
                    kotlinx.coroutines.delay(500) // let the just-opened task settle before closing
                    if (app.isFlapRecovering()) return@collectLatest
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        var closedAny = false
                        for ((pkg, group) in overflow) {
                            // Keep the oldest MAX_SAME_APP_WINDOWS (lowest taskIds);
                            // close the newer extras — i.e. undo the over-limit open.
                            val extras = group.sortedBy { it.first }.drop(MAX_SAME_APP_WINDOWS)
                            for ((taskId, _) in extras) {
                                runCatching { app.freeform.close(taskId) }
                                closedAny = true
                                Log.d(TAG, "same-app cap: closed extra window task=$taskId pkg=$pkg (cap=$MAX_SAME_APP_WINDOWS)")
                            }
                        }
                        if (closedAny) {
                            val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                            dm?.getDisplay(app.injector.displayId)?.let { disp ->
                                com.portalpad.app.presentation.GlassesToast.show(
                                    applicationContext, disp,
                                    "You can only have $MAX_SAME_APP_WINDOWS windows of the same app open.",
                                    title = "Window limit",
                                )
                            }
                        }
                    }
                }
        }
    }

    /**
     * Snapshot + persist the current session, skipping junk: nothing open, or a
     * transient "just launched" state where every window is fullscreen/zero (which
     * would otherwise overwrite a good positioned layout). A set with at least one
     * genuinely positioned (windowed) app is saved as-is.
     */
    /**
     * The session RECOVERY should restore: MEMBERSHIP from the most-recent
     * snapshot (the windows actually open when the display went down), BOUNDS
     * from this width's archive where a package match exists (per-resolution
     * layout memory), else the window's own bounds scaled to the new canvas.
     * The old behavior restored the width archive AS the membership list,
     * resurrecting residue from long-dead sessions (field report: 3 real
     * windows became 5 — phantom Play Store + Files from earlier testing —
     * and the retile aligned the phantoms while the real windows hid behind).
     * Returned scale is always 1f: bounds are already live-canvas space.
     */
    private suspend fun mergedRecoverySession(
        app: PortalPadApp,
        w: Int,
        h: Int,
    ): Triple<com.portalpad.app.data.SavedSession, Float, Float> {
        val (archive, ax, ay) = app.prefs.sessionForCanvas(w, h)
        var identity = runCatching { app.prefs.lastSession.first() }.getOrNull()
        if (identity == null || identity.windows.isEmpty()) return Triple(archive, ax, ay)
        // LIVENESS FILTER (only when the VD survived the disconnect): empty
        // snapshots are deliberately never saved, so lastSession can't record
        // "the user cleared everything" — it forever holds the last non-empty
        // set. On a SURVIVING VD, a dead task id means the user closed that
        // window; drop it instead of resurrecting a ghost. taskId<=0 (old
        // snapshots) can't be judged and is kept.
        if (vdSurvivedDisconnect) {
            val liveTasks = runCatching { app.freeform.listTasks() }.getOrDefault(emptyList())
            val liveIds = liveTasks.map { it.taskId }.toSet()
            // A dead task id alone is NOT proof of a user close: lastSession is
            // written on a debounce/poll, so right after a restore-then-flap the
            // saved ids can be a whole GENERATION stale while the same apps sit
            // live on the display under new ids (observed: 5 survivors present,
            // filter dropped all 5 as "user-closed", retile had no targets, and
            // the survivors were stranded fullscreen). If the package is
            // visibly live, keep the entry — its BOUNDS are what the retile
            // needs, and the launch path de-dupes against present windows.
            // Drop only when the id is dead AND the app is nowhere on screen.
            val livePkgs = liveTasks.map { it.packageName.lowercase() }.toSet()
            val alive = identity.windows.filter {
                it.taskId <= 0 || it.taskId in liveIds ||
                    it.packageName.lowercase() in livePkgs
            }
            if (alive.size != identity.windows.size) {
                Log.d(
                    "PortalPadSleep",
                    "RECOVERY liveness filter: dropped ${identity.windows.size - alive.size} " +
                        "user-closed window(s); ${alive.size} remain",
                )
            }
            if (alive.isEmpty()) return Triple(com.portalpad.app.data.SavedSession(), 1f, 1f)
            identity = identity.copy(windows = alive)
        }
        // Archive bounds are only trustworthy when the archive describes THIS
        // set of windows: its positions were computed as an ARRANGEMENT for a
        // specific window population. Cherry-picking slots out of a layout
        // made for a different set leaves the missing members' slots as holes
        // (field report: 3 windows restored into a 4-slot phantom-era layout
        // — evenly split, with an empty column where a phantom used to be).
        // Multiset equality on packages, order-independent.
        val archiveMatchesSet =
            archive.windows.groupingBy { it.packageName }.eachCount() ==
                identity.windows.groupingBy { it.packageName }.eachCount()
        val pool = archive.windows.toMutableList()
        val iw0 = identity.canvasWidth.takeIf { it > 0 } ?: w
        val ih0 = identity.canvasHeight.takeIf { it > 0 } ?: h
        val ix = w.toFloat() / iw0
        val iy = h.toFloat() / ih0
        val windows = identity.windows.map { iw ->
            // Slot claim: EXACT task id first. Package-first-fit assigned each
            // Chrome whichever Chrome slot came first in the archive — archive
            // and identity are both saved in FOCUS order, which routinely
            // differs, so windows #1/#2 swapped positions across switches
            // (deterministically, once retile paired by id). Clean archives
            // carry ids; a window reclaims ITS OWN remembered slot. Package
            // match remains the fallback for id-less archives.
            val match = if (archiveMatchesSet) {
                (
                    pool.firstOrNull { iw.taskId > 0 && it.taskId == iw.taskId }
                        ?: pool.firstOrNull { it.packageName == iw.packageName }
                    )?.also { pool.remove(it) }
            } else null
            if (match != null) {
                iw.copy(
                    left = (match.left * ax).toInt(),
                    top = (match.top * ay).toInt(),
                    right = (match.right * ax).toInt(),
                    bottom = (match.bottom * ay).toInt(),
                )
            } else {
                iw.copy(
                    left = (iw.left * ix).toInt(),
                    top = (iw.top * iy).toInt(),
                    right = (iw.right * ix).toInt(),
                    bottom = (iw.bottom * iy).toInt(),
                )
            }
        }
        Log.d(
            "PortalPadSleep",
            "RECOVERY merged session: membership=${windows.size} from lastSession " +
                "(width archive had ${archive.windows.size})",
        )
        return Triple(
            com.portalpad.app.data.SavedSession(windows, identity.savedAtMillis, w, h),
            1f,
            1f,
        )
    }

    /** After a flap recovery, put the user back on the PortalPad interface the
     *  HOME-to-recents parking pushed away — but ONLY if it was alive (a user
     *  who was genuinely on their launcher stays there). singleTask +
     *  REORDER_TO_FRONT: never recreates, mode preserved. */
    private fun returnToPortalPadIfAlive() {
        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val alive = am.appTasks.any { t ->
                runCatching {
                    t.taskInfo.baseActivity?.className == com.portalpad.app.TrackpadActivity::class.java.name
                }.getOrDefault(false)
            }
            if (!alive) return
            Log.d("PortalPadSleep", "RECOVERY complete → returning to PortalPad interface (was alive)")
            startActivity(
                Intent(this, com.portalpad.app.TrackpadActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    private suspend fun captureSessionNow(app: PortalPadApp) {
        // Recovery gate: a debounced snapshot firing MID-TRANSITION captures a
        // garbage half-state and OVERWRITES lastSession — the identity source
        // for refugee recovery (seen in the wild: {chrome=1, vending=1} saved
        // between VD teardown and restore, poisoning a healthy {chrome=2,
        // youtube=1} snapshot). Recovery windows mark flapRecoveryUntilMs;
        // hold snapshots until the dust settles.
        if (app.isFlapRecovering()) {
            Log.d(TAG, "captureSessionNow SKIPPED (recovery in progress)")
            return
        }
        val displayId = app.externalDisplayId.value ?: return
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val snap = app.freeform.snapshotSession(displayId) ?: return@withContext
                // Display pixel size, to detect fullscreen-looking (transient) bounds.
                val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                val disp = dm?.getDisplay(displayId)
                val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") disp?.getRealMetrics(it) }
                val dw = m.widthPixels
                val dh = m.heightPixels
                fun isPositioned(w: com.portalpad.app.data.SavedWindow): Boolean {
                    val zero = w.right <= w.left || w.bottom <= w.top
                    val full = dw > 0 && dh > 0 &&
                        w.left <= 2 && w.top <= 2 && w.right >= dw - 2 && w.bottom >= dh - 2
                    return !zero && !full
                }
                // Only persist if at least one window is genuinely positioned — else
                // it's a launch transient / all-maximized state; keep the old session.
                if (snap.windows.any { isPositioned(it) }) {
                    // MASS-DEATH GUARD: a window-count collapse of ≥2 within one
                    // capture interval means LMK reclaim or a crash wave, not
                    // deliberate closing (log-proven 2026-07-09: lmkd killed 4
                    // VISIBLE streaming apps in one second under min2x pressure,
                    // and the next snapshot faithfully saved the wreckage 6→2→1,
                    // destroying the only restore source). Hold the previous
                    // snapshot for ONE interval and re-arm the restore offer; if
                    // the reduced state persists at the NEXT capture (the user
                    // really closed things and kept working), accept it then.
                    // Deliberate one-at-a-time closes snapshot between each, so
                    // they shrink by 1 per capture and never trip this.
                    val prev = runCatching { app.prefs.lastSession.first() }.getOrNull()
                    val collapse = (prev?.windows?.size ?: 0) - snap.windows.size
                    if (collapse >= 2 && !snapshotCollapsePending) {
                        snapshotCollapsePending = true
                        Log.w(
                            TAG,
                            "captureSessionNow HELD: window count collapsed " +
                                "${prev?.windows?.size}→${snap.windows.size} (LMK/crash suspected) — " +
                                "keeping previous snapshot; restore offer re-armed",
                        )
                        app.bumpRestoreOfferNudge()
                        return@withContext
                    }
                    snapshotCollapsePending = false
                    app.prefs.setLastSession(snap)
                    // Per-resolution memory: remember this layout under its canvas
                    // width so each display size keeps its own arrangement.
                    if (snap.canvasWidth > 0) app.prefs.putSessionForWidth(snap.canvasWidth, snap)
                }
            }
        }
    }

    private fun startBubbleWatcher() {
        val app = applicationContext as PortalPadApp
        bubbleWatcher = scope.launch {
            // Primary, reactive path. The bubble shows when:
            //   - the user has enabled it in settings, AND
            //   - an external display is connected (no display = no point in returning to trackpad), AND
            //   - the app itself isn't visible
            launch {
                combine(
                    app.appInForeground,
                    app.physicalExternalDisplayId,
                    app.prefs.bool(PreferencesRepository.Keys.FLOATING_BUBBLE, default = true),
                ) { inForeground, displayId, enabled ->
                    floatingBubbleEnabled = enabled
                    // Reset session-hide whenever the user comes back to the app.
                    if (inForeground) bubble.clearSessionHide()
                    val shouldShow = enabled && !inForeground && displayId != null
                    Log.d(
                        "BubbleActivate",
                        "reactive: enabled=$enabled inForeground=$inForeground " +
                            "displayId=$displayId → shouldShow=$shouldShow",
                    )
                    shouldShow
                }
                    .distinctUntilChanged()
                    .collect { shouldShow ->
                        Log.d("BubbleActivate", "reactive: ${if (shouldShow) "show()" else "hide()"} (isShown=${bubble.isShown})")
                        if (shouldShow) bubble.show() else bubble.hide()
                    }
            }
            // Watchdog: re-assert the desired state every few seconds so the bubble
            // self-heals if it ever didn't appear on leaving the app — e.g. a flow
            // transition that settled on stale state, or a transient WindowManager
            // addView failure. Now keyed on isActuallyShown (real window attachment)
            // rather than the isShown reference flag: a display flap / overlay-hide
            // can tear the bubble window down without hide() running, leaving
            // isShown=true but nothing on screen — in which case show() would
            // early-return and never recover it. reshow() drops the stale reference
            // and re-adds. Idempotent (only acts when attachment != shouldShow) and
            // still respects session-hide via show()'s own guard.
            launch {
                while (true) {
                    kotlinx.coroutines.delay(3000)
                    val shouldShow = floatingBubbleEnabled &&
                        !app.appInForeground.value &&
                        app.physicalExternalDisplayId.value != null
                    if (shouldShow && !bubble.isActuallyShown) {
                        Log.d(
                            "BubbleActivate",
                            "watchdog: SELF-HEAL — shouldShow=true but bubble not attached " +
                                "(isShown=${bubble.isShown}); reshow()",
                        )
                        bubble.reshow()
                    } else if (!shouldShow && bubble.isShown) {
                        Log.d("BubbleActivate", "watchdog: self-heal — hiding stale bubble")
                        bubble.hide()
                    }
                }
            }
            // Prompt re-assert on external-display change. A disconnect→replug
            // arrives as physicalExternalDisplayId null→id; the reactive combine
            // above can land late or get collapsed by distinctUntilChanged across
            // the flap (observed in the wild: after a replug the bubble only
            // reappeared when the 3s watchdog happened to fire ~coincidentally).
            // Re-assert shortly after the display settles so the bubble returns
            // promptly instead of waiting up to 3s. drop(1) skips the initial
            // value (startup is already handled by the reactive path + watchdog);
            // collectLatest cancels the pending settle-delay if a rapid flap emits
            // again, so we only act on the settled final id.
            launch {
                app.physicalExternalDisplayId.drop(1).collectLatest {
                    kotlinx.coroutines.delay(500)
                    val shouldShow = floatingBubbleEnabled &&
                        !app.appInForeground.value &&
                        app.physicalExternalDisplayId.value != null
                    if (shouldShow && !bubble.isActuallyShown) {
                        Log.d(
                            "BubbleActivate",
                            "display-change re-assert: bubble not attached after settle — reshow()",
                        )
                        bubble.reshow()
                    } else if (!shouldShow && bubble.isShown) {
                        bubble.hide()
                    }
                }
            }
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PortalPad service", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Watching for external display")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    /** Show + manage the red "Tap here to exit" bands around a settings window we
     *  launched on the phone (display 0). Finds the launched task, sizes it to sit
     *  between a top and bottom band, shows the bands, and runs a watchdog that
     *  clears them if the window vanishes, gets maximized over a band, or after a
     *  90s timeout. Tapping a band closes the window + clears the bands. */
    private fun attachPhoneExitBandsImpl(action: String?, explicitPkg: String? = null) {
        val app = applicationContext as? PortalPadApp ?: return
        if (!app.access.isReady) return
        // Clear any previous pill (without closing that window); this also bumps
        // the generation so any older poll/watchdog loop exits.
        dismissPhoneExitBandsImpl(closeTask = false)
        val gen = phoneExitGen
        phoneExitTaskId = -1

        // Show the pill IMMEDIATELY so it ALWAYS appears (centered) on launch. Its
        // presence no longer depends on first locating the launched task — some
        // apps (e.g. My Glasses, which spins up the glasses pipeline) are slow to
        // create their window and used to miss the find-window entirely, so no
        // pill showed at all. The lookup below only wires up tap-to-close + the
        // auto-dismiss watchdog. If the task is never found, tapping the pill
        // falls back to bringing PortalPad to the front.
        val d0 = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        if (d0 != null) {
            mainHandler.post {
                if (phoneExitGen != gen) return@post
                phoneExitBands = com.portalpad.app.presentation.PhoneExitBandsOverlay(
                    this, d0, 0,
                ) {
                    if (phoneExitTaskId >= 0) {
                        dismissPhoneExitBandsImpl(closeTask = true)
                    } else {
                        dismissPhoneExitBandsImpl(closeTask = false)
                        bringPortalPadToFront()
                    }
                }.also { it.show() }
            }
        }

        Thread {
            runCatching {
                // A component/package launch passes the package directly; an action
                // launch resolves it the way the quick-settings tiles do.
                val pkg = explicitPkg ?: runCatching {
                    if (action != null) {
                        packageManager.resolveActivity(android.content.Intent(action), 0)
                            ?.activityInfo?.packageName
                    } else {
                        null
                    }
                }.getOrNull() ?: "com.android.settings"
                // Wait up to ~8s for the launched window to appear on display 0
                // (glasses/companion apps can take several seconds to show a window).
                var found: com.portalpad.app.data.RunningTask? = null
                val deadline = android.os.SystemClock.uptimeMillis() + 8000
                while (android.os.SystemClock.uptimeMillis() < deadline && phoneExitGen == gen) {
                    val t = findPhoneTask(app, pkg)
                    if (t != null) { found = t; break }
                    Thread.sleep(200)
                }
                if (phoneExitGen != gen) return@runCatching   // superseded
                val task = found ?: return@runCatching         // pill stays; exit uses fallback
                phoneExitTaskId = task.taskId
                Log.d(
                    "PhoneExitBands",
                    "wired exit to task=${task.taskId} pkg=${task.packageName} bounds=${task.bounds}",
                )
                // Watchdog: clear the pill if the window is gone or 90s elapses. We
                // deliberately do NOT dismiss based on the window being tall: the
                // pill is an overlay drawn ABOVE the window, so a full-screen app
                // doesn't hide it.
                val start = android.os.SystemClock.uptimeMillis()
                while (phoneExitGen == gen) {
                    Thread.sleep(1000)
                    if (phoneExitGen != gen) break
                    if (android.os.SystemClock.uptimeMillis() - start > 90_000L) {
                        dismissPhoneExitBandsImpl(closeTask = false); break
                    }
                    if (findTaskById(app, task.taskId) == null) {
                        dismissPhoneExitBandsImpl(closeTask = false); break
                    }
                }
            }.onFailure { Log.w(TAG, "attachPhoneExitBands failed", it) }
        }.start()
    }


    private fun dismissPhoneExitBandsImpl(closeTask: Boolean) {
        phoneExitGen++                 // invalidate any running poll/watchdog loop
        val tid = phoneExitTaskId
        phoneExitTaskId = -1
        val bands = phoneExitBands
        phoneExitBands = null
        bands?.dismiss()
        if (closeTask && tid >= 0) {
            val app = applicationContext as? PortalPadApp
            if (app != null) Thread { runCatching { app.freeform.close(tid) } }.start()
        }
    }

    /** Close every app window on the external display (skipping our own overlays
     *  and the launcher). Hardened with a second pass + small delay for any window
     *  that survives the first close (e.g. enumeration that briefly returns stale
     *  after a screen-off). Call off the main thread. */
    private suspend fun closeAllExternalWindows() {
        val app = applicationContext as? PortalPadApp ?: return
        repeat(2) { pass ->
            val tasks = runCatching {
                app.freeform.listExternalTasks().filter { t ->
                    t.packageName != packageName &&
                        !t.packageName.contains("launcher", ignoreCase = true)
                }
            }.getOrDefault(emptyList())
            if (tasks.isEmpty()) return
            tasks.forEach { t -> runCatching { app.freeform.close(t.taskId) } }
            if (pass == 0) kotlinx.coroutines.delay(300)
        }
    }

    /** Bring PortalPad (MainActivity) back to the front of display 0. Used as the
     *  exit fallback when we showed the pill but never located the launched task
     *  to close. */
    private fun bringPortalPadToFront() {
        runCatching {
            val back = android.content.Intent(this, com.portalpad.app.MainActivity::class.java)
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
            val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()
            startActivity(back, opts)
        }
    }

    private fun findPhoneTask(app: PortalPadApp, pkg: String): com.portalpad.app.data.RunningTask? {
        val onD0 = runCatching { app.freeform.listTasks(android.view.Display.DEFAULT_DISPLAY) }
            .getOrDefault(emptyList()).firstOrNull { it.packageName == pkg }
        if (onD0 != null) return onD0
        // Some builds register the task under a different display id; fall back to
        // searching all tasks by package, then by package prefix (a few apps report
        // a sub-package as the top activity).
        val all = runCatching { app.freeform.listTasks(-1) }.getOrDefault(emptyList())
        return all.firstOrNull { it.packageName == pkg }
            ?: all.firstOrNull { it.packageName.startsWith(pkg) }
    }

    private fun findTaskById(app: PortalPadApp, taskId: Int): com.portalpad.app.data.RunningTask? =
        runCatching { app.freeform.listTasks(-1) }
            .getOrDefault(emptyList()).firstOrNull { it.taskId == taskId }

    /** Apply a color matrix to the glasses display via the GPU color pipeline
     *  (the VD mirror renders through a color-matrix shader). Returns a
     *  diagnostic string. */
    fun applyGlassesColorMatrix(matrix4x4: FloatArray, gamma: Float = 1f): String {
        // System-mirror path: apply the LINEAR matrix via the display color
        // transform. Gamma isn't expressible in a display matrix, so it's
        // dropped (as with the whole system-mirror color path).
        if (systemMirrorGlassesId >= 0) {
            return virtualDisplaySession.applySystemMirrorColor(systemMirrorGlassesId, matrix4x4)
        }
        val m = vdMirror ?: return "FAIL: no active VD mirror (display not connected via VD)"
        return m.setColorMatrix(matrix4x4, gamma)
    }

    companion object {
        private const val TAG = "PortalPadFgService"

        // Hard cap on windows of the SAME app. 2 is the observed-safe limit across a
        // resolution flap (3+ same-app windows lost one in testing); a specific lost
        // multi-window-app window can't be relaunched by component, so we prevent the
        // unsafe state rather than try to recover from it.
        private const val MAX_SAME_APP_WINDOWS = 2
        private const val NOTIF_ID = 4011

        // How long to wait for the onDisplayChanged storm to settle before
        // doing a single coalesced refresh. ~400ms is long enough to absorb
        // the burst from a VD/overlay reconcile but short enough to feel
        // responsive on a genuine display change.
        private const val DISPLAY_CHANGED_DEBOUNCE_MS = 400L

        /** Live service instance, so overlays (e.g. the search panel) can ask to
         *  re-raise the cursor above a newly-shown overlay window. */
        @Volatile var instance: PortalPadForegroundService? = null
            private set

        /** Live VD mirror, so the screen recorder can tee the glasses output
         *  off its GL pipeline. Null when no external display is mirrored. */
        @Volatile var activeVdMirror: com.portalpad.app.presentation.VirtualDisplayMirror? = null
            private set

        /** Re-raise the cursor above all current overlays on its display. Safe
         *  no-op if the service or cursor isn't up. */
        /** Re-raise the cursor above all current overlays on its display by
         *  recreating it as the newest window. Safe no-op if not up. */
        fun raiseCursor() {
            val svc = instance ?: return
            val disp = svc.lastOverlayDisplay ?: return
            svc.mainHandler.post { svc.recreateCursorOnTop(disp) }
        }

        /** Lightweight cursor z-lift: restacks the EXISTING cursor window above
         *  current overlays without rebuilding it (no Compose recreate, so no
         *  flicker), unlike raiseCursor()'s full dismiss+re-add. Meant for
         *  frequent triggers like dock hover-enter. Safe no-op if not up. */
        fun raiseCursorFast() {
            val svc = instance ?: return
            svc.mainHandler.post { runCatching { svc.cursorOverlay?.raise() } }
        }

        /** Flip to a transient system mirror for a permission dialog (overlay-mode
         *  users), so the native prompt is usable on the external display without
         *  the security overlay-hide blacking the panel. Reverts on a safety
         *  timeout. Never touches the saved mirror pref. Safe no-op if not up. */
        fun beginTransientMirrorForDialog() {
            val svc = instance ?: return
            svc.mainHandler.post { svc.beginTransientMirrorInternal() }
        }

        /** Revert a transient dialog mirror immediately (e.g. once the dialog is
         *  known to have closed — Stage 2). Safe no-op if none is active. */
        fun endTransientMirrorForDialog() {
            val svc = instance ?: return
            svc.mainHandler.post { svc.endTransientMirrorInternal("dialog-closed") }
        }

        /** Temporarily stop reacting to onDisplayChanged storms. Used when
         *  PortalPad itself launches something onto the external display (e.g.
         *  a Settings page from the dock) so the resulting window-change storm
         *  doesn't trigger refreshExternalDisplay, which would re-assert the
         *  trackpad/overlays on TOP of what we just opened. */
        fun suppressDisplayChanges(durationMs: Long = 2500) {
            instance?.suppressDisplayListener("external launch", durationMs)
        }

        /** Show our own notification panel on the external display (the system
         *  shade can't be moved off display 0). Opened by three-finger-down. */
        fun showNotificationPanel() {
            val svc = instance ?: return
            val disp = svc.lastOverlayDisplay ?: return
            svc.mainHandler.post {
                if (svc.notificationPanel?.isShowing == true) return@post
                runCatching {
                    svc.notificationPanel = com.portalpad.app.presentation.NotificationPanelOverlay(svc, disp)
                        .also { it.show() }
                }.onFailure { Log.w(TAG, "showNotificationPanel failed", it) }
            }
        }

        /** Dismiss the notification panel if showing. Three-finger-up. */
        fun dismissNotificationPanel() {
            val svc = instance ?: return
            svc.mainHandler.post {
                svc.notificationPanel?.dismiss(); svc.notificationPanel = null
            }
        }

        /** Toggle the panel (kept for convenience / future button use). */
        fun toggleNotificationPanel() {
            val svc = instance ?: return
            if (svc.notificationPanel?.isShowing == true) dismissNotificationPanel()
            else showNotificationPanel()
        }

        /** Show our Windows-style quick-settings flyout (Wi-Fi / BT / VPN) on the
         *  external display. Opened by tapping the dock's connectivity cluster. */
        fun showQuickSettingsPanel() {
            val svc = instance ?: return
            val disp = svc.lastOverlayDisplay ?: return
            svc.mainHandler.post {
                if (svc.quickSettingsPanel?.isShowing == true) return@post
                runCatching {
                    svc.quickSettingsPanel = com.portalpad.app.presentation.QuickSettingsOverlay(svc, disp)
                        .also { it.show() }
                    com.portalpad.app.PortalPadApp.instance.setQuickSettingsOpen(true)
                }.onFailure { Log.w(TAG, "showQuickSettingsPanel failed", it) }
            }
        }

        fun dismissQuickSettingsPanel() {
            val svc = instance ?: return
            com.portalpad.app.PortalPadApp.instance.setQuickSettingsOpen(false)
            svc.mainHandler.post {
                svc.quickSettingsPanel?.dismiss(); svc.quickSettingsPanel = null
            }
        }

        /** Toggle the quick-settings flyout — the dock connectivity row calls this. */
        fun toggleQuickSettingsPanel() {
            val svc = instance ?: return
            if (svc.quickSettingsPanel?.isShowing == true) dismissQuickSettingsPanel()
            else showQuickSettingsPanel()
        }

        /** Show + manage the red "Tap here to exit" bands around a settings window
         *  just launched on the phone. Called by the quick-settings tiles after a
         *  privileged on-phone launch. No-op without elevated access. */
        fun attachPhoneExitBands(action: String) {
            instance?.attachPhoneExitBandsImpl(action)
        }

        /** Same as [attachPhoneExitBands] but for a window launched by explicit
         *  component (am start -n), where there's no action to resolve a package
         *  from — the caller supplies the package to track (e.g. the Samsung
         *  battery page in com.samsung.android.lool). */
        fun attachPhoneExitBandsForPackage(pkg: String) {
            instance?.attachPhoneExitBandsImpl(null, pkg)
        }

        /** Clear the phone exit bands without closing the window (e.g. teardown). */
        fun dismissPhoneExitBands() {
            instance?.dismissPhoneExitBandsImpl(closeTask = false)
        }

        /** Phone-side permission consent, called by FreeformManager AFTER it
         *  force-stopped the app (external display now clear). External side:
         *  wallpaper repaint (delayed past the ~1s post-kill settle window,
         *  during which a wallpaper launch is proven to fail) + a "check your
         *  phone" card. Phone side: the Allow/Deny request is pushed into the
         *  foreground trackpad's composition via
         *  [com.portalpad.app.TrackpadActivity.permConsentTrigger] — hosted
         *  there because a service overlay AND a separate dialog activity were
         *  both buried under the trackpad on this ROM. The caller verified the
         *  trigger was non-null before force-stopping; if it vanished in the
         *  gap (user left the trackpad), we log and stop — nothing granted,
         *  the user just relaunches the app. */
        fun showPermissionConsentFlow(
            pkg: String,
            permissions: List<String>,
            onDecision: (approved: List<String>) -> Unit,
        ) {
            val svc = instance ?: return
            val appLabel = runCatching {
                val pm = svc.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            // Group raw permissions the way Android's own App-info page does,
            // one checkbox per group (e.g. the three READ_MEDIA_* are one
            // "Photos and videos" row).
            val groups = permissions
                .groupBy { permissionGroupLabel(it) }
                .map { (label, perms) -> label to perms }
                .sortedBy { it.first }

            // External display: wallpaper after the post-kill settle window,
            // then the card once the wallpaper is up.
            svc.mainHandler.postDelayed({ showWallpaper() }, 1000L)
            svc.mainHandler.postDelayed({
                runCatching {
                    val app = PortalPadApp.instance
                    val dm = svc.getSystemService(android.hardware.display.DisplayManager::class.java)
                    val disp = dm?.getDisplay(app.injector.displayId) ?: return@runCatching
                    com.portalpad.app.presentation.GlassesToast.show(
                        svc.applicationContext, disp,
                        "Check your phone to allow or deny them.",
                        durationMs = 120_000L,
                        title = "$appLabel is asking for permissions",
                    )
                }
            }, 1800L)

            val request = com.portalpad.app.ui.trackpad.PermConsentRequest(
                appLabel = appLabel,
                groups = groups,
                onDecision = { approved ->
                    runCatching { com.portalpad.app.presentation.GlassesToast.dismiss() }
                    onDecision(approved)
                },
                onDismissed = {
                    // Ask-later: clear the card; the app stays closed until the
                    // user relaunches it (which re-runs this whole flow).
                    runCatching { com.portalpad.app.presentation.GlassesToast.dismiss() }
                    Log.w(TAG, "perm consent: dialog dismissed — no grants, $pkg stays closed")
                },
            )
            svc.mainHandler.post {
                val trigger = com.portalpad.app.TrackpadActivity.permConsentTrigger
                if (trigger != null) {
                    trigger(request)
                } else {
                    runCatching { com.portalpad.app.presentation.GlassesToast.dismiss() }
                    Log.w(TAG, "perm consent: trackpad UI left before dialog could show — aborting, nothing granted")
                }
            }
        }

        /** Human-readable permission GROUP for a raw dangerous permission —
         *  mirrors how Android's own App-info page groups them. */
        private fun permissionGroupLabel(perm: String): String = when (perm) {
            "android.permission.POST_NOTIFICATIONS" -> "Notifications"
            "android.permission.CAMERA" -> "Camera"
            "android.permission.RECORD_AUDIO" -> "Microphone"
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            -> "Location"
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.GET_ACCOUNTS",
            -> "Contacts"
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            -> "Calendar"
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            -> "Files and media"
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            -> "Photos and videos"
            "android.permission.READ_MEDIA_AUDIO" -> "Music and audio"
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_PHONE_NUMBERS",
            "android.permission.CALL_PHONE",
            -> "Phone"
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            -> "Call logs"
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_SMS",
            -> "SMS"
            "android.permission.BODY_SENSORS" -> "Body sensors"
            "android.permission.ACTIVITY_RECOGNITION" -> "Physical activity"
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_ADVERTISE",
            -> "Nearby devices"
            else -> perm.removePrefix("android.permission.")
        }

        /** Dismiss the on-display app-search overlay if it's showing — used by
         *  the Home button so pressing Home brings the home app forward instead
         *  of leaving the search panel on top. Safe no-op if nothing's up. */
        fun dismissSearchOverlay() {
            instance?.dockOverlay?.dismissSearchOverlay()
        }

        /** Mirror the trackpad's Home button for the top-bar Home: dismiss any
         *  on-display search, then relaunch the assigned home app on the
         *  external display (falling back to a HOME keypress if none assigned). */
        fun goHome() {
            // Prefer the trackpad's registered Home trigger — it runs in the
            // Activity context with the live injector, identical to tapping the
            // trackpad's own Home button (which works). A Service-context launch
            // to a display silently fails on some OEMs, which is why earlier
            // attempts from here did nothing.
            val trigger = com.portalpad.app.TrackpadActivity.homeTrigger
            if (trigger != null) {
                instance?.mainHandler?.post { runCatching { trigger() } }
                return
            }
            // Fallback: no trackpad foregrounded — do our best from the service.
            val svc = instance ?: return
            svc.dockOverlay?.dismissSearchOverlay()
            val app = svc.applicationContext as PortalPadApp
            svc.scope.launch {
                val home = runCatching { app.prefs.homeAction.first() }.getOrNull()
                if (home == null) { showWallpaper(); return@launch }
                val displayId = app.externalDisplayId.value ?: app.injector.displayId
                val component = if (home.componentName != null) {
                    "${home.packageName}/${home.componentName}"
                } else {
                    runCatching {
                        app.packageManager.getLaunchIntentForPackage(home.packageName)
                            ?.component?.flattenToString()
                    }.getOrNull() ?: home.packageName
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (app.access.isReady) {
                        app.markExternalLaunch()
                        runCatching { app.access.startActivityOnDisplay(component, displayId) }
                            .onFailure { app.injector.home() }
                    }
                }
            }
        }

        /** Launch PortalPad's own home-backdrop (WallpaperActivity) onto the
         *  external display. Used when no home app is assigned, and when the
         *  display goes empty after closing apps. The activity itself reads the
         *  selected wallpaper from prefs (and draws black for "None"). This never
         *  touches the Android system wallpaper. */
        /**
         * Lay PortalPad's wallpaper backdrop on [displayId], idempotently.
         *
         * The backdrop is the BASE LAYER of the external display — whatever else is
         * showing (restored freeform windows, an auto-launched app) sits on top of
         * it. It was previously only ever laid down inside [launchAutoStart], which
         * is gated on `!suppressAutoStartContent && ((autoLaunchOnFirstAttach &&
         * wasNullBefore) || vdJustCreated)`. When a connect ran twice (observed: two
         * "VD created" passes ~200ms apart, mirror dismissed and re-added between
         * them), the first pass could be suppressed and the second had
         * vdJustCreated=false — so NO pass laid the backdrop and a restored freeform
         * window floated on pure black.
         *
         * Making it unconditional here fixes that: the base layer never depends on
         * auto-start policy, which now only decides what goes ON TOP. Deduped per
         * display so the VD-created call, [launchAutoStart], and the refocus fallback
         * can all call it freely — the first wins, the rest are no-ops.
         */
        @Volatile private var lastBackdropDisplayId = -1
        @Volatile private var lastBackdropAt = 0L
        private const val BACKDROP_DEDUP_MS = 2500L
        private val backdropLock = Any()

        fun ensureBackdrop(displayId: Int, reason: String) {
            if (displayId < 0) return
            val svc = instance ?: return
            val app = svc.applicationContext as PortalPadApp
            val now = android.os.SystemClock.uptimeMillis()
            synchronized(backdropLock) {
                if (displayId == lastBackdropDisplayId && now - lastBackdropAt < BACKDROP_DEDUP_MS) {
                    Log.d(TAG, "ensureBackdrop($reason) deduped — laid ${now - lastBackdropAt}ms ago on display=$displayId")
                    return
                }
                lastBackdropDisplayId = displayId
                lastBackdropAt = now
            }
            val component = "${app.packageName}/com.portalpad.app.WallpaperActivity"
            // Dispatchers.IO, NOT the service's MainScope. `svc.scope` is a MainScope(),
            // so this body used to queue behind the connect path's overlay attach and its
            // ~13 runBlocking pref reads on the main thread — measured stalls of 100ms to
            // 5.1s before the `am start` even fired, i.e. seconds of an empty display.
            // Nothing here touches UI; it's a shell call.
            svc.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (!app.access.isReady) {
                    Log.d(TAG, "ensureBackdrop($reason) skipped — access not ready (display=$displayId)")
                    return@launch
                }
                // Idempotence by STATE, not by clock. The time-based dedup above still
                // coalesces bursts, but it stamps the INVOCATION; when the launch was
                // stalled (see above) the window could expire before the backdrop landed
                // and a second one was launched (observed: two backdrops for the same
                // taskId). Checking for an existing WallpaperActivity task makes a
                // duplicate impossible regardless of timing.
                val alreadyUp = runCatching {
                    listTasksForBackdrop(app, displayId).any { rt ->
                        rt.packageName == app.packageName &&
                            rt.topActivity?.contains("WallpaperActivity", ignoreCase = true) == true
                    }
                }.getOrDefault(false)
                if (alreadyUp) {
                    Log.d(TAG, "ensureBackdrop($reason) skipped — backdrop already on display=$displayId")
                    return@launch
                }
                Log.d(TAG, "ensureBackdrop($reason) launching backdrop display=$displayId")
                runCatching { app.access.startActivityOnDisplay(component, displayId) }
            }
        }

        /** Tasks on [displayId], for the backdrop-presence check. Isolated so a
         *  listTasks failure can never block laying the backdrop down. */
        private fun listTasksForBackdrop(app: PortalPadApp, displayId: Int) =
            runCatching { app.freeform.listTasks(displayId) }.getOrDefault(emptyList())

        fun showWallpaper() {
            val svc = instance ?: return
            val app = svc.applicationContext as PortalPadApp
            val displayId = app.externalDisplayId.value ?: app.injector.displayId
            val component = "${app.packageName}/com.portalpad.app.WallpaperActivity"
            Log.d(TAG, "showWallpaper() display=$displayId accessReady=${app.access.isReady}")
            svc.scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (app.access.isReady) {
                        runCatching { app.access.startActivityOnDisplay(component, displayId) }
                    }
                }
            }
        }

        /**
         * Self-heal the VD wallpaper backdrop after its task was destroyed out
         * from under us (recents swipe / trimInactiveRecentTasks). Called from
         * [WallpaperActivity.onDestroy] on a NON-intentional destroy. Relaunches
         * the backdrop only while the external is still physically connected — a
         * normal unplug finishes the backdrop deliberately and needs no heal, and
         * relaunching onto a dead display would be pointless. A short delay lets
         * any in-flight task transition settle, and the physical-id re-check
         * guards against a disconnect that lands during the delay.
         */
        @Volatile private var lastBackdropRelaunchAt = 0L
        private const val BACKDROP_RELAUNCH_DEBOUNCE_MS = 2500L

        fun onBackdropDestroyedUnexpectedly() {
            val svc = instance ?: return
            val app = svc.applicationContext as PortalPadApp
            if (app.physicalExternalDisplayId.value == null) return
            // Anti-fight guard: coalesce rapid relaunch requests so a backdrop
            // that's relaunched then immediately re-destroyed can't loop, and so
            // this can't race the fresh-attach launch. A genuine later re-clear
            // (beyond the window) still self-heals.
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastBackdropRelaunchAt < BACKDROP_RELAUNCH_DEBOUNCE_MS) {
                Log.w(TAG, "backdrop relaunch suppressed (debounce) — avoiding loop")
                return
            }
            lastBackdropRelaunchAt = now
            svc.mainHandler.postDelayed({
                if (app.physicalExternalDisplayId.value == null) return@postDelayed
                Log.w(TAG, "backdrop task destroyed unexpectedly (recents?) → relaunching wallpaper")
                showWallpaper()
            }, 500L)
        }

        /**
         * Run a window action (by id) from the trackpad radial menu, forwarding to
         * the live top-bar overlay which owns the tested handlers. No-ops if the
         * top bar isn't attached (e.g. not in desktop mode) — the radial is only
         * reachable in desktop mode, so the overlay is normally present.
         */
        fun runWindowAction(id: String) {
            instance?.topWindowBarOverlay?.runAction(id)
        }

        /**
         * Decide and launch what should appear on the glasses at SESSION START
         * (service start / display attach) per the user's Auto Launch setting.
         * Independent of the Home button. Called once per fresh attach — NOT on
         * trackpad re-launch. Falls back to the wallpaper backdrop when there's
         * nothing to restore.
         */
        /** Coalesces duplicate launchAutoStart() calls. The VD connect path invokes
         *  it twice (a direct call plus a postDelayed 400ms one), and each fired a
         *  redundant backdrop `am start` — observed as two identical site=B lines at
         *  the same millisecond. Keyed on displayId so a genuine new attach still
         *  runs; a repeat within the window is dropped. */
        @Volatile private var lastAutoStartDisplayId = -1
        @Volatile private var lastAutoStartAt = 0L
        private const val AUTOSTART_DEDUP_MS = 3000L
        private val autoStartLock = Any()

        fun launchAutoStart() {
            val svc = instance ?: return
            val app = svc.applicationContext as PortalPadApp
            val dedupDisplay = app.externalDisplayId.value ?: app.injector.displayId
            val now = android.os.SystemClock.uptimeMillis()
            synchronized(autoStartLock) {
                if (dedupDisplay == lastAutoStartDisplayId &&
                    now - lastAutoStartAt < AUTOSTART_DEDUP_MS
                ) {
                    Log.d(TAG, "autoLaunchOnStart deduped (ran ${now - lastAutoStartAt}ms ago for display=$dedupDisplay)")
                    return
                }
                lastAutoStartDisplayId = dedupDisplay
                lastAutoStartAt = now
            }
            svc.scope.launch {
                val displayId = app.externalDisplayId.value ?: app.injector.displayId

                // Base layer first, before any pref read. Normally already laid down at
                // VD creation, in which case this dedups to a no-op; it still matters on
                // the non-VD path and on re-attach.
                ensureBackdrop(displayId, "autoLaunchOnStart")
                if (!app.access.isReady) return@launch

                // Now resolve what (if anything) goes on top of the backdrop.
                val mode = runCatching { app.prefs.autoLaunchOnStart.first() }
                    .getOrDefault(com.portalpad.app.data.AutoLaunch.Wallpaper)
                val entry: AppEntry? = when (mode) {
                    com.portalpad.app.data.AutoLaunch.Wallpaper -> null
                    com.portalpad.app.data.AutoLaunch.LastApp ->
                        runCatching { app.prefs.lastForegroundApp.first() }.getOrNull()
                    is com.portalpad.app.data.AutoLaunch.Launch -> mode.entry
                }
                // Wallpaper-only mode: nothing else to launch on top.
                if (entry == null) return@launch
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (!app.access.isReady) return@withContext
                    // Launch the configured auto-start app ON TOP of the backdrop.
                    val component = if (entry.componentName != null) {
                        "${entry.packageName}/${entry.componentName}"
                    } else {
                        runCatching {
                            app.packageManager.getLaunchIntentForPackage(entry.packageName)
                                ?.component?.flattenToString()
                        }.getOrNull() ?: entry.packageName
                    }
                    app.markExternalLaunch()
                    runCatching { app.access.startActivityOnDisplay(component, displayId) }
                }
            }
        }

        /**
         * Re-assert input focus on the glasses display *without* disturbing
         * whatever app is currently running there: bring the display's existing
         * top task to front. Used by the volume-key focus fix. Only if no task
         * is found on the display (e.g. nothing launched yet) do we fall back to
         * launching the wallpaper backdrop, so focus is still restored.
         */
        fun refocusGlasses() {
            val svc = instance ?: return
            val app = svc.applicationContext as PortalPadApp
            val displayId = app.externalDisplayId.value ?: return
            svc.scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val backend = app.activeBoundBackend
                    val moved = backend != null &&
                        runCatching { backend.refocusTopTaskOnDisplay(displayId) }.getOrDefault(false)
                    if (!moved) {
                        // Refocus failed — usually because the task is reported
                        // under a different display id than our VD (a recurring
                        // enumeration mismatch), NOT because the display is empty.
                        // Only fall back to the wallpaper when the display is
                        // GENUINELY empty; if any real app (non-launcher, non-
                        // PortalPad) is present, leave it alone — slamming the
                        // wallpaper up would clobber whatever the user has open
                        // (YouTube, Chrome, a game, anything). This makes the
                        // trackpad-return safe for every app, not just freshly
                        // launched ones.
                        val realAppPresent = runCatching {
                            val ids = listOf(displayId, app.physicalExternalDisplayId.value)
                                .filterNotNull().distinct()
                            val tasks = ids.flatMap { app.freeform.listTasks(it) }
                            tasks.any { rt ->
                                !rt.packageName.contains("launcher", ignoreCase = true) &&
                                    !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                                    rt.packageName != "com.portalpad.app"
                            }
                        }.getOrDefault(false)
                        // The backdrop IS PortalPad, so `realAppPresent` (which excludes
                        // our own package) reads FALSE even when the backdrop is already
                        // up — the display looks "genuinely empty" and we relaunch a
                        // backdrop that's already showing. Observed: site=C firing 23ms
                        // after site=B's launch, then again ~1.7s later, for a total of
                        // 4 backdrop `am start`s per connect on the same taskId. Detect
                        // the existing backdrop and leave it alone; there's nothing to
                        // fall back to when the fallback is already on screen.
                        val backdropAlreadyUp = runCatching {
                            val ids = listOf(displayId, app.physicalExternalDisplayId.value)
                                .filterNotNull().distinct()
                            ids.flatMap { app.freeform.listTasks(it) }.any { rt ->
                                rt.packageName == "com.portalpad.app" &&
                                    rt.topActivity?.contains("WallpaperActivity", ignoreCase = true) == true
                            }
                        }.getOrDefault(false)
                        if (app.launchedRecently()) {
                            // Skip wallpaper fallback briefly after a launch (the
                            // refocus can race the launch and wrongly blank it).
                        } else if (realAppPresent) {
                            // A real app is on the display; refocus just couldn't
                            // see it. Don't replace it with the wallpaper.
                        } else if (backdropAlreadyUp) {
                            Log.d(TAG, "refocus-fallback skipped — backdrop already up on display=$displayId")
                        } else if (app.access.isReady) {
                            ensureBackdrop(displayId, "refocus-fallback")
                        }
                    }
                }
            }
        }

        /**
         * Expand or collapse the system notification shade via the privileged
         * `cmd statusbar` path. NOTE: SystemUI hosts the shade on the primary
         * display on most devices, so this may open on the phone rather than the
         * glasses depending on the device's SystemUI — the command itself is
         * reliable; which display it targets is OEM/version-dependent.
         */
        fun setNotificationShade(expand: Boolean) {
            val svc = instance ?: return
            val app = svc.applicationContext as PortalPadApp
            svc.scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val backend = app.activeBoundBackend ?: return@withContext
                    val cmd = if (expand) "cmd statusbar expand-notifications"
                              else "cmd statusbar collapse"
                    runCatching { backend.runCommand(cmd) }
                        .onSuccess { Log.d(TAG, "setNotificationShade(expand=$expand) ok") }
                        .onFailure { Log.w(TAG, "setNotificationShade(expand=$expand) failed", it) }
                }
            }
        }

        /** Delay before the post-surface-ready cursor re-assert (see
         *  [scheduleOverlayReassert]). Long enough for the VD output to start
         *  flowing into the live mirror surface. */
        private const val OVERLAY_REASSERT_DELAY_MS = 600L
        private const val CHANNEL_ID = "portalpad_service"

        fun start(context: Context) {
            // Deliberate start — clear the user-stopped marker BEFORE launching
            // so the onCreate relaunch guard can't misread this as a zombie
            // relaunch and immediately stop itself.
            runCatching {
                kotlinx.coroutines.runBlocking {
                    (context.applicationContext as PortalPadApp).prefs.setBool(
                        PreferencesRepository.Keys.SERVICE_USER_STOPPED, false,
                    )
                }
            }
            val i = Intent(context, PortalPadForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
        fun stop(context: Context) {
            // Deliberate stop — mark it so a later START_STICKY relaunch knows
            // the difference between "the system killed us, come back" (recents
            // Close-All) and "the user said stop, stay stopped".
            runCatching {
                kotlinx.coroutines.runBlocking {
                    (context.applicationContext as PortalPadApp).prefs.setBool(
                        PreferencesRepository.Keys.SERVICE_USER_STOPPED, true,
                    )
                }
            }
            context.stopService(Intent(context, PortalPadForegroundService::class.java))
        }
    }
}
