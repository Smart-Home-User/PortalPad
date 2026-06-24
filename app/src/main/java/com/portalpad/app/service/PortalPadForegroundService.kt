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
            // A re-add that closely follows a removal is a flap-recreate (the
            // shell process was reaped + the VD recreated with a new id), NOT a
            // fresh user attach. Captured before the refresh so it reflects the gap.
            val flapMs = if (lastDisplayRemovedAt > 0L)
                System.currentTimeMillis() - lastDisplayRemovedAt else Long.MAX_VALUE
            maybeShowTransitionSpinner("displayAdded", holdUntilReady = true)
            refreshExternalDisplay(autoLaunchOnFirstAttach = true, trigger = "displayAdded($displayId)")
            if (flapMs < 30_000L) {
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
            if (isDisplayListenerSuppressed()) {
                Log.d(TAG, "Display removed (suppressed): $displayId")
                return
            }
            Log.d(TAG, "Display removed: $displayId")
            maybeShowTransitionSpinner("displayRemoved", holdUntilReady = false)
            // Reset IME policy before tearing down so a dangling policy on the
            // departing display can't strand the phone keyboard.
            runCatching { (applicationContext as PortalPadApp).injector.resetImePolicy() }
            // Backup keyboard-wedge cleanup: Extinguish can leave the phone IME
            // wedged after screen on/off cycles during the session. Stopping its
            // service clears that; we then restart it so the user can still use
            // Extinguish elsewhere. Best-effort via Shizuku; no-op without it.
            // Guarded so it can NEVER abort the rest of teardown (dock/VD dismiss,
            // id clearing) — same defensive pattern as the IME reset above.
            runCatching { cleanupExtinguishOnDisconnect() }
            dismissDock()
            dismissVdMirror()
            bubble.hide()
            // Finish the home-backdrop if it's up, so the now-orphaned task can't
            // get migrated by the system onto the phone's default display (which
            // would make the wallpaper hijack the phone screen).
            runCatching { com.portalpad.app.WallpaperActivity.finishIfShowing() }
            // Release the app-owned VirtualDisplay on a real disconnect. Without
            // this, the VD ("PortalPad Session") stays registered in DisplayManager
            // and other apps keep listing it as an available external display. Mirror
            // onDestroy's order: stop the session (releases the VD) BEFORE clearing
            // the tracked ids. Suppress the listener so the VD's own removal event
            // doesn't re-enter onDisplayRemoved and double-tear-down.
            if (virtualDisplaySession.isActive) suppressDisplayListener("displayRemoved-stop")
            runCatching { virtualDisplaySession.stop() }
            val tdApp = applicationContext as PortalPadApp
            tdApp.setExternalDisplayId(null); tdApp.setPhysicalExternalDisplayId(null)
            tdApp.setVirtualDisplayId(null)
            lastDisplayRemovedAt = System.currentTimeMillis()
            // The display we'd reconciled is gone — clear the cache so a
            // future re-attach doesn't get short-circuited as "same as last".
            lastReconciledDisplayId = null
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
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()

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
                    if (!isDisplayListenerSuppressed() &&
                        a.activeBoundBackend?.isReady == true &&
                        pickExternalDisplay() != null &&
                        !virtualDisplaySession.isActive
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
                            // require a maximized window to exist (you've maximized what
                            // you're watching). Heuristic: doesn't correlate the exact
                            // playing package to the maximized task, but that mismatch
                            // (maximize A, play in freeform B) is rare. Reads the already
                            // -polled WindowMonitor snapshot — no extra binder calls.
                            val snap = app.windowMonitor.snapshot.value
                            val fullscreenContext = !snap.desktop || snap.maximizedId != null
                            val idleMs = android.os.SystemClock.elapsedRealtime() - lastMoveMs
                            if (playing && fullscreenContext && !hiddenByUs && idleMs >= secs * 1000L) {
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
                            if (enabled) app.freeform.enableFreeform()
                            else app.freeform.disableFreeform()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
        scope.launch {
            // Let the recreated display + rebound backend + dock settle first.
            kotlinx.coroutines.delay(1500)
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
                var any = false
                for (comp in comps) {
                    runCatching { app.access.startActivityOnDisplay(comp, newDisplayId) }
                    kotlinx.coroutines.delay(500)
                    if (runCatching {
                            app.activeBoundBackend?.moveFocusedTaskToDisplay(newDisplayId) == true
                        }.getOrDefault(false)
                    ) any = true
                }
                // Nothing recorded (or none moved) → old behavior: move whatever
                // is focused. Harmless if there's nothing to move.
                if (!any) {
                    any = runCatching {
                        app.activeBoundBackend?.moveFocusedTaskToDisplay(newDisplayId) == true
                    }.getOrDefault(false)
                }
                Log.d(
                    "PortalPadSleep",
                    "RECOVERY move-first comps=${comps.size} moved=$any display=$newDisplayId",
                )
                any
            }
            if (moved) return@launch
            // 2) relaunch-fallback: same path the "Restore last session" button uses.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val session = runCatching { app.prefs.lastSession.first() }.getOrNull()
                if (session == null || session.windows.isEmpty()) {
                    Log.d("PortalPadSleep", "RECOVERY relaunch skipped (no saved session)")
                    return@withContext
                }
                val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                val disp = dm?.getDisplay(newDisplayId)
                val m = android.util.DisplayMetrics()
                    .also { @Suppress("DEPRECATION") disp?.getRealMetrics(it) }
                val w = m.widthPixels.let { if (it <= 1) 1920 else it }
                val h = m.heightPixels.let { if (it <= 1) 1080 else it }
                runCatching { app.freeform.restoreSession(session, newDisplayId, w, h) }
                Log.d("PortalPadSleep", "RECOVERY relaunch via restoreSession display=$newDisplayId")
            }
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
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val session = runCatching { app.prefs.lastSession.first() }.getOrNull() ?: return@withContext
                if (session.windows.isEmpty()) return@withContext
                val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                val disp = dm?.getDisplay(displayId)
                val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") disp?.getRealMetrics(it) }
                val w = m.widthPixels.let { if (it <= 1) 1920 else it }
                val h = m.heightPixels.let { if (it <= 1) 1080 else it }
                runCatching { app.freeform.restoreSession(session, displayId, w, h) }
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        runCatching { sessionWakeLock?.let { if (it.isHeld) it.release() } }
        sessionWakeLock = null
        runCatching { unregisterReceiver(screenStateReceiver) }
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
        // Finish the home-backdrop so the orphaned task can't migrate to the
        // phone display when the VD tears down (same reason as onDisplayRemoved).
        runCatching { com.portalpad.app.WallpaperActivity.finishIfShowing() }
        bubbleWatcher?.cancel()
        runCatching { (applicationContext as PortalPadApp).signal.stop() }
        autoDisableJob?.cancel()
        externalAppTrackerJob?.cancel()
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
                suppressDisplayListener("aspect-live")
                virtualDisplaySession.start(physId, w, h, dpi)
                Log.d(TAG, "Applied aspect $aspect → ${w}x$h dpi=$dpi to live VD (phys=$physId)")
            }.onFailure { Log.w(TAG, "live aspect apply failed", it) }
        }
    }

    private fun recreateCursorOnTop(overlayDisplay: Display) {
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

        // Cursor overlay — visible amber arrow at injector.cursorPosition.
        // Honors "Custom cursor on external display" so users whose OEM
        // already draws a cursor can turn ours off.
        cursorOverlay?.dismiss(); cursorOverlay = null
        val cursorEnabled = runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.bool(PreferencesRepository.Keys.ENABLE_MOUSE_HOVER, default = true).first()
            }
        }.getOrDefault(true)
        if (cursorEnabled) {
            try {
                cursorOverlay = com.portalpad.app.presentation.CursorOverlay(
                    this, overlayDisplay, app.injector,
                ).also { it.show() }
            } catch (t: Throwable) {
                Log.w(TAG, "CursorOverlay attach failed (need overlay permission?)", t)
            }
        }

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

        // Re-assert the DOCK here too. The dock is first attached in the main
        // refreshExternalDisplay flow, but that can run BEFORE the VD mirror's
        // surface is ready — so on connect the dock window can be composited
        // against a not-yet-presenting display and stay blank (the same class of
        // bug the cursor used to have, which is why toggling "Show dock on
        // external display" off/on — a fresh attach after the display settles —
        // fixed it). Re-attaching here (from onSurfaceReady + the delayed
        // reassert) makes the dock reliably appear on connect without the toggle.
        // Idempotent: skip if it's already showing on this display.
        val dockEnabled = runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.bool(PreferencesRepository.Keys.DOCK_ENABLED, default = true).first()
            }
        }.getOrDefault(true)
        if (dockEnabled && !isDockShowing(overlayDisplay.displayId)) {
            dismissDockOnly()
            attachDock(overlayDisplay, DockDisplayMode.OVERLAY)
            Log.d(TAG, "Dock re-asserted in attachVdOverlays on display ${overlayDisplay.displayId}")
        }
    }

    private fun isDockShowing(displayId: Int): Boolean =
        (dockOverlay?.isShowing == true && dockOverlay?.displayId == displayId) ||
            (dockPresentation?.isShowing == true && dockPresentation?.display?.displayId == displayId)

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

    private fun refreshExternalDisplay(
        autoLaunchOnFirstAttach: Boolean = false,
        force: Boolean = false,
        trigger: String = "?",
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

        if (external == null) {
            app.setExternalDisplayId(null)
            app.setPhysicalExternalDisplayId(null)
            app.setVirtualDisplayId(null)
            if (lastId != null) {
                // We had a display; release VD and any overlays now that it's gone.
                virtualDisplaySession.stop()
                dismissDock()
                dismissVdMirror()
            }
            lastReconciledDisplayId = null
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
                val vdId = virtualDisplaySession.start(external.displayId, w, h, dpi)
                if (vdId >= 0) {
                    Log.d(TAG, "AirGlassesSession started: vd=$vdId glasses=${external.displayId}")
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
        if (usingVd) {
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
                        if ((autoLaunchOnFirstAttach && wasNullBefore) || vdJustCreated) {
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

        if (dockEnabled) attachDock(overlayDisplay, DockDisplayMode.OVERLAY)

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

        if (autoLaunchOnFirstAttach && wasNullBefore) {
            // Non-VD path: the glasses-visual auto-launch isn't driven by a VD
            // surface-ready callback, so fire it here (once per fresh attach).
            // VD path handles its own launchAutoStart() after the surface is live.
            if (!usingVd) launchAutoStart()
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
        // it runs AFTER this frame's dock/taskbar attaches, and (in VD mode) the
        // reassert posts another recreate after its delay as a backstop.
        mainHandler.post { recreateCursorOnTop(overlayDisplay) }

        // Mark this displayId as reconciled. Future onDisplayChanged events
        // for the same display will short-circuit (see the force/lastId
        // check at the top of this function).
        lastReconciledDisplayId = external.displayId
    }

    private fun attachDock(display: Display, mode: DockDisplayMode) {
        when (mode) {
            DockDisplayMode.OVERLAY -> {
                try {
                    dockOverlay = DockOverlay(this, display).also { it.show() }
                    Log.d(TAG, "Dock overlay attached on display ${display.displayId}")
                } catch (t: Throwable) {
                    // OEM rejected overlay on secondary display, or permission missing
                    Log.w(TAG, "DockOverlay.show failed — falling back to Presentation", t)
                    try {
                        dockPresentation = DockPresentation(this, display).also { it.show() }
                    } catch (t2: Throwable) {
                        Log.e(TAG, "Dock presentation also failed", t2)
                    }
                }
            }
            DockDisplayMode.PRESENTATION -> {
                try {
                    dockPresentation = DockPresentation(this, display).also { it.show() }
                    Log.d(TAG, "Dock presentation attached on display ${display.displayId}")
                } catch (t: Throwable) {
                    Log.e(TAG, "Could not show DockPresentation", t)
                }
            }
        }
        // Z-order fix: the dock window we just attached lands on TOP of the cursor
        // (same overlay layer — last-added wins). Re-assert the cursor above it now
        // that the dock is actually shown — posted so it runs after the dock's view
        // is attached. Deterministic fix for the cursor sometimes drawing BEHIND
        // dock icons at startup (the prior reassert at attach time was posted
        // speculatively and could run before the dock — esp. the async VD path —
        // actually attached, leaving the cursor under the dock until some later
        // event happened to recreate it).
        mainHandler.post { recreateCursorOnTop(display) }
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
            Log.d(TAG, "skipping display ${d.displayId} '${d.name}' (not a real external display)")
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
        // 2. Slow safety-net poll.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                captureSessionNow(app)
            }
        }
    }

    /**
     * Snapshot + persist the current session, skipping junk: nothing open, or a
     * transient "just launched" state where every window is fullscreen/zero (which
     * would otherwise overwrite a good positioned layout). A set with at least one
     * genuinely positioned (windowed) app is saved as-is.
     */
    private suspend fun captureSessionNow(app: PortalPadApp) {
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
                    app.prefs.setLastSession(snap)
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
                    app.externalDisplayId,
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
            // addView failure. Idempotent (only acts when shown != shouldShow) and
            // still respects session-hide via bubble.show()'s own guard.
            launch {
                while (true) {
                    kotlinx.coroutines.delay(3000)
                    val shouldShow = floatingBubbleEnabled &&
                        !app.appInForeground.value &&
                        app.externalDisplayId.value != null
                    if (shouldShow && !bubble.isShown) {
                        Log.d(
                            "BubbleActivate",
                            "watchdog: SELF-HEAL — shouldShow=true but bubble not shown " +
                                "(reactive path missed it); calling show()",
                        )
                        bubble.show()
                    } else if (!shouldShow && bubble.isShown) {
                        Log.d("BubbleActivate", "watchdog: self-heal — hiding stale bubble")
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
        val m = vdMirror ?: return "FAIL: no active VD mirror (display not connected via VD)"
        return m.setColorMatrix(matrix4x4, gamma)
    }

    companion object {
        private const val TAG = "PortalPadFgService"
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
        fun showWallpaper() {
            Log.w(TAG, "DIAG-WALLPAPER showWallpaper() called", Throwable("caller trace"))
            val svc = instance ?: return
            val app = svc.applicationContext as PortalPadApp
            val displayId = app.externalDisplayId.value ?: app.injector.displayId
            val component = "${app.packageName}/com.portalpad.app.WallpaperActivity"
            svc.scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (app.access.isReady) {
                        runCatching { app.access.startActivityOnDisplay(component, displayId) }
                    }
                }
            }
        }

        /**
         * Decide and launch what should appear on the glasses at SESSION START
         * (service start / display attach) per the user's Auto Launch setting.
         * Independent of the Home button. Called once per fresh attach — NOT on
         * trackpad re-launch. Falls back to the wallpaper backdrop when there's
         * nothing to restore.
         */
        fun launchAutoStart() {
            val svc = instance ?: return
            val app = svc.applicationContext as PortalPadApp
            svc.scope.launch {
                val mode = runCatching { app.prefs.autoLaunchOnStart.first() }
                    .getOrDefault(com.portalpad.app.data.AutoLaunch.Wallpaper)
                val entry: AppEntry? = when (mode) {
                    com.portalpad.app.data.AutoLaunch.Wallpaper -> null
                    com.portalpad.app.data.AutoLaunch.LastApp ->
                        runCatching { app.prefs.lastForegroundApp.first() }.getOrNull()
                    is com.portalpad.app.data.AutoLaunch.Launch -> mode.entry
                }
                val displayId = app.externalDisplayId.value ?: app.injector.displayId
                val wallpaperComponent = "${app.packageName}/com.portalpad.app.WallpaperActivity"
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (!app.access.isReady) return@withContext
                    // ALWAYS lay down the wallpaper backdrop FIRST, as the base
                    // layer beneath whatever else launches. This makes the display
                    // never black: the wallpaper shows on start, and when the user
                    // closes every app the wallpaper underneath is revealed instead
                    // of black. (Previously the backdrop was only launched in
                    // Wallpaper mode or on launch failure, so Last-App / specific-
                    // app modes left the display black once their app was closed.)
                    runCatching { app.access.startActivityOnDisplay(wallpaperComponent, displayId) }
                    // Wallpaper-only mode: nothing else to launch on top.
                    if (entry == null) return@withContext
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
                        if (app.launchedRecently()) {
                            // Skip wallpaper fallback briefly after a launch (the
                            // refocus can race the launch and wrongly blank it).
                        } else if (realAppPresent) {
                            // A real app is on the display; refocus just couldn't
                            // see it. Don't replace it with the wallpaper.
                        } else if (app.access.isReady) {
                            val component = "${app.packageName}/com.portalpad.app.WallpaperActivity"
                            runCatching { app.access.startActivityOnDisplay(component, displayId) }
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
            val i = Intent(context, PortalPadForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, PortalPadForegroundService::class.java))
        }
    }
}
