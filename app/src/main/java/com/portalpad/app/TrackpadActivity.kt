package com.portalpad.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalpad.app.data.BackAction
import com.portalpad.app.service.InputInjector
import com.portalpad.app.ui.mediacontrols.MediaControlsPanel
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.PortalPadTheme
import com.portalpad.app.ui.trackpad.DisplaySelectorPill
import com.portalpad.app.ui.trackpad.GestureSink
import com.portalpad.app.ui.trackpad.TrackpadBottomBar
import com.portalpad.app.ui.trackpad.TrackpadClickButtons
import com.portalpad.app.ui.trackpad.TrackpadSurface
import com.portalpad.app.ui.trackpad.TrackpadTopBar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest

/** The four configurable 3-finger swipe actions (and, for LAUNCH_APP, the app to
 *  launch per direction), snapshotted for the gesture sink to read. */
data class GestureActions(
    val up: com.portalpad.app.data.GestureAction,
    val down: com.portalpad.app.data.GestureAction,
    val left: com.portalpad.app.data.GestureAction,
    val right: com.portalpad.app.data.GestureAction,
    val upApp: com.portalpad.app.data.AppEntry? = null,
    val downApp: com.portalpad.app.data.AppEntry? = null,
    val leftApp: com.portalpad.app.data.AppEntry? = null,
    val rightApp: com.portalpad.app.data.AppEntry? = null,
)

class TrackpadActivity : PinnedDensityActivity() {

    private val app get() = application as PortalPadApp
    private val injector get() = app.injector

    // Keeps hardware volume keys working across a screen-off→on cycle. Trackpad-
    // Activity is FLAG_NOT_FOCUSABLE (so it doesn't steal focus off Chrome on the
    // glasses), so after screen-off→on no window holds key focus and volume keys
    // would time out (ANR). An active MediaSession (local playback) gives the
    // system a focus-independent target, routing keys to the normal Media stream.
    // NOTE: this fixes the screen-off→on path only; the Settings→back path still
    // bugs out (no MediaSession variant could hold routing there on this device).
    private var volumeSession: android.media.session.MediaSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockOrientationDefault()

        // SECOND dropdown-dismiss cause (found via focus trace v0.17.56):
        // even after FLAG_ALT_FOCUSABLE_IM stopped TrackpadActivity from
        // stealing the IME *control target*, the focus trace showed system
        // WINDOW focus still ping-ponging Chrome (taskId 5239) ↔
        // TrackpadActivity (taskId 5250), with the IME display bouncing
        // 81 (VD) ↔ 0 (phone) in lockstep. Every time we touch the
        // trackpad, TrackpadActivity becomes the focused window, which
        // pulls focus off Chrome on the VD and dismisses its dropdown.
        //
        // FLAG_NOT_FOCUSABLE makes this window receive touches WITHOUT ever
        // becoming the focused window. The trackpad doesn't need keyboard/
        // input focus — we read raw touches off the surface and inject
        // events elsewhere; we never type INTO the trackpad. So giving up
        // focusability costs us nothing and lets Chrome on the VD remain
        // the focused window, keeping its address-bar dropdown alive.
        //
        // (FLAG_ALT_FOCUSABLE_IM is implied/compatible; we keep both so the
        // IME control target also stays on Chrome.)
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
        )

        // Show the PortalPad controls over the lock screen (default on), so the
        // user can drive the trackpad / remote / air-mouse without unlocking the
        // phone mid-session. This is the standard activity-level keyguard API
        // (setShowWhenLocked) — it shows THIS activity over the keyguard; leaving
        // PortalPad to other apps still requires unlocking. Gated by the
        // "Show controls over the lock screen" toggle (ALLOW_LOCKSCREEN).
        val showOverLock = runCatching {
            kotlinx.coroutines.runBlocking {
                PortalPadApp.instance.prefs.bool(
                    com.portalpad.app.data.PreferencesRepository.Keys.ALLOW_LOCKSCREEN,
                    true,
                ).first()
            }
        }.getOrDefault(true)
        setShowWhenLocked(showOverLock)
        // Same as MainActivity: if the glasses attach while locked, prompt unlock —
        // the desktop can't extend onto them until the phone is unlocked.
        promptUnlockWhenDisplayAttachesLocked(enabled = showOverLock)

        val initialMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_TRACKPAD
        setContent {
            PortalPadTheme {
                TrackpadScreen(injector = injector, initialMode = initialMode)
                RestoreSessionOffer()
            }
        }

        // Auto-finish when the external display disconnects.
        //
        // The trackpad is an "input device for the external display" — once
        // the user unplugs the glasses / HDMI cable, there's nothing to
        // control. Staying on the trackpad UI is disorienting (no obvious
        // target for cursor moves) and a tap could accidentally inject
        // events somewhere unintended. So we finish() the moment the
        // external display goes null.
        //
        // We use lifecycleScope + repeatOnLifecycle so the watcher pauses
        // when the activity is backgrounded and resumes on return; we also
        // require the activity to actually be STARTED before the first
        // sample, so the initial null-state at process start doesn't
        // immediately kill us.
        lifecycleScope.launch {
            var sawDisplay = false
            // Pending auto-finish; armed when the display goes null, cancelled if it
            // comes back within the grace window.
            var finishJob: kotlinx.coroutines.Job? = null
            // When the display went null (elapsedRealtime). Lets a re-armed finish
            // (after the activity was backgrounded mid-countdown and returned) wait
            // only the REMAINING grace instead of a fresh full window — so coming
            // back to a still-disconnected trackpad completes the return promptly
            // rather than sitting on a stuck banner. Reset to 0 when the display
            // returns. Lives in the outer launch scope so it survives stop/start.
            var goneAt = 0L
            // A phone screen-off briefly drops the external display to null, then the
            // service's flap-recovery re-creates it (~3s in practice; see
            // PortalPadSleep "REBUILD-FLAP"). Without a grace window the trackpad
            // finished on that transient null and kicked the user out to the start
            // screen on every screen-off→on. Wait past the flap before giving up; a
            // real unplug leaves the display null and this still fires (after ~5s).
            val goneGraceMs = 5000L
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                val watcherScope = this
                app.externalDisplayId.collect { id ->
                    if (id != null) {
                        sawDisplay = true
                        // (Re)attached — the prior null was a flap, not an unplug.
                        finishJob?.cancel()
                        finishJob = null
                        goneAt = 0L
                    } else if (sawDisplay) {
                        // ── ANR guard (synchronous, fastest path) ──
                        // The external display held window focus; with it gone, this
                        // FLAG_NOT_FOCUSABLE window leaves the app with NO focused
                        // window, so a queued mouse/touch event can't be dispatched
                        // and Android fires a "no focused window" ANR after ~5s
                        // (black screen, frozen cursor — confirmed in the field).
                        // Reclaim focus to our own window RIGHT HERE, synchronously,
                        // on the gone-edge. The combine-based focusability watcher
                        // below also reclaims, but it hops through collectLatest +
                        // a suspending prefs read and lags under the display churn
                        // that triggers this — so by the time it reacts the input
                        // timer has already expired. This direct call has no such
                        // delay. Re-applied on every null edge so rapid flapping
                        // can't leave us stranded non-focusable. (Idempotent; only
                        // ever sets focusable=true, so it can't fight recovery,
                        // which hands focus back to the display via the watcher.)
                        setWindowFocusable(true)
                        if (goneAt == 0L) goneAt = android.os.SystemClock.elapsedRealtime()
                        // Re-arm if there's no LIVE finish pending. After the activity
                        // was backgrounded mid-countdown, the old finishJob was
                        // cancelled (not nulled) — so a `== null` check would wrongly
                        // see it as still pending and never re-arm, leaving the banner
                        // stuck. `isActive != true` covers null AND cancelled/done.
                        if (finishJob?.isActive != true) {
                            finishJob = watcherScope.launch {
                                // Only wait the grace time still remaining since the
                                // display first went away — so returning after the
                                // window already elapsed finishes right away.
                                val elapsed = android.os.SystemClock.elapsedRealtime() - goneAt
                                val remaining = (goneGraceMs - elapsed).coerceAtLeast(0L)
                                kotlinx.coroutines.delay(remaining)
                                // Re-check against the live value (the flow may not
                                // re-emit while it stays null).
                                if (app.externalDisplayId.value == null) {
                                    Log.d(TAG, "External display gone >${goneGraceMs}ms — finishing trackpad")
                                    // Return to PortalPad rather than dropping to whatever
                                    // app was behind the trackpad in the task stack.
                                    runCatching {
                                        launchSettingsOnPhone(
                                            this@TrackpadActivity,
                                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                                        )
                                    }
                                    finish()
                                }
                                finishJob = null
                            }
                        }
                    }
                }
            }
        }

        setupVolumeSession()

        // ── ANR fix: "Input dispatching timed out (no focused window)" ──
        // TrackpadActivity is FLAG_NOT_FOCUSABLE so it never steals window focus
        // off Chrome on the glasses. But that means when we CAN'T drive the
        // glasses (Shizuku died, or no external display), there is no focusable
        // window anywhere — so a hardware volume/D-pad key has nothing to
        // dispatch to and Android kills us with an ANR after ~5s.
        //
        // The ONLY reason for NOT_FOCUSABLE is to protect Chrome's focus on the
        // glasses. When we can't drive the glasses, there's nothing to protect,
        // so being focusable then is harmless AND gives keys a home (no ANR).
        // So: focusable UNLESS (external display present AND access is ready).
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(
                    app.externalDisplayId,
                    app.shizuku.status,
                    displaySubpageOpen,
                ) { extId, shizukuStatus, subpageOpen ->
                    val mode = runCatching { app.prefs.accessMode.first() }
                        .getOrDefault(com.portalpad.app.data.AccessMode.NONE)
                    val accessAlive = when (mode) {
                        com.portalpad.app.data.AccessMode.SHIZUKU ->
                            shizukuStatus == com.portalpad.app.shizuku.ShizukuManager.Status.READY
                        com.portalpad.app.data.AccessMode.ROOT -> app.root.isReady
                        else -> false
                    }
                    // Keep NOT_FOCUSABLE only when we can actually drive the glasses
                    // AND the DPI subpage (which needs the soft keyboard) isn't open.
                    extId != null && accessAlive && !subpageOpen
                }.collectLatest { keepNotFocusable ->
                    if (keepNotFocusable) {
                        // Going BACK to NOT_FOCUSABLE happens on recovery (Shizuku
                        // reconnected, display restored). The recovery is a churn:
                        // the VD rebuilds, overlays re-attach, focus is unsettled.
                        // Flipping the flag forces a relayout — and if we do it
                        // mid-churn it yanks window focus to <null> and a key then
                        // strands → ANR (confirmed by logcat: "Changing focus from
                        // TrackpadActivity to null ... relayoutWindow"). So WAIT for
                        // the session to settle before removing focusability.
                        // collectLatest cancels this delay if state flips again.
                        kotlinx.coroutines.delay(1500)
                        setWindowFocusable(false)
                    } else {
                        // Losing the ability to drive the glasses (Shizuku died /
                        // display gone) — become focusable IMMEDIATELY so hardware
                        // keys always have a dispatch target (no ANR). This is the
                        // safety direction; never delay it.
                        setWindowFocusable(true)
                    }
                }
            }
        }
    }

    /** True while the trackpad-interface Display subpage is open. Folded into the
     *  focusability decision below so the window becomes — and STAYS — focusable for
     *  the DPI number keypad even amid display/Shizuku state churn (which otherwise
     *  re-schedules a setWindowFocusable(false) that clobbers it). */
    internal val displaySubpageOpen = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * Toggle whether this window can hold input focus. NOT_FOCUSABLE protects
     * Chrome's focus on the glasses; but when we can't drive the glasses we make
     * the window focusable so hardware keys have a dispatch target (no ANR).
     * Re-applies layout so the flag change takes effect on the live window.
     */
    fun setWindowFocusable(focusable: Boolean) {
        runCatching {
            val flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            if (focusable) {
                window.clearFlags(flags)
            } else {
                window.addFlags(flags)
            }
            Log.d(TAG, "setWindowFocusable($focusable)")
        }
    }

    /**
     * Active MediaSession with LOCAL playback. Gives the system a focus-
     * independent volume-key target (our window is FLAG_NOT_FOCUSABLE) so
     * hardware volume keys survive a screen-off→on cycle, while showing the
     * normal Media slider (no separate "PortalPad" control). Keys adjust
     * STREAM_MUSIC directly. Does NOT fix the Settings→back path (left as-is).
     */
    private fun setupVolumeSession() {
        runCatching {
            if (volumeSession != null) return
            val session = android.media.session.MediaSession(this, "PortalPadVolume")
            session.setPlaybackToLocal(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            session.setPlaybackState(
                android.media.session.PlaybackState.Builder()
                    .setState(android.media.session.PlaybackState.STATE_PLAYING, 0L, 1f)
                    .setActions(android.media.session.PlaybackState.ACTION_PLAY_PAUSE)
                    .build(),
            )
            session.isActive = true
            volumeSession = session
            Log.d(TAG, "Volume MediaSession active (local)")
        }.onFailure { Log.w(TAG, "Volume MediaSession setup failed", it) }
    }

    /**
     * Force a clean rebuild of the volume session: release the old one and
     * create a fresh one. Called when the user taps a mode tab (Air Mouse /
     * Trackpad / Remote) — the deliberate action used to return from Settings —
     * so volume-key routing is reclaimed at a stable moment, even if the old
     * session was left in a bad state by the window thrash (where just
     * re-flagging isActive wasn't enough).
     */
    fun refreshVolumeSession() {
        runCatching {
            volumeSession?.isActive = false
            volumeSession?.release()
        }
        volumeSession = null
        setupVolumeSession()
        refocusExternalDisplay()
    }

    /**
     * The actual fix (confirmed by logcat): hardware volume keys are dispatched
     * to whichever window holds focus on the *focused display*. When focus sits
     * on display 0 (the phone), the only candidate is this FLAG_NOT_FOCUSABLE
     * window, so keys are dropped ("Focus request ... but waiting because
     * NOT_FOCUSABLE"). When focus is on the external display (the glasses),
     * its foreground window receives the keys fine. Launch-Trackpad / settings
     * return leaves focus stranded on display 0; tapping Trackpad→Remote happened
     * to refocus the glasses, which is why that manual sequence "fixed" it.
     *
     * So we re-assert input focus onto the external display by bringing its
     * current top task to front (via the privileged ActivityTaskManager path) —
     * which restores hardware-key focus WITHOUT relaunching or covering whatever
     * app is already on the glasses. Only if nothing is running there yet does
     * it fall back to launching the wallpaper backdrop. We only do this when an
     * external display is actually connected.
     */
    private fun refocusExternalDisplay() {
        runCatching {
            if (app.externalDisplayId.value == null) {
                Log.d(TAG, "refocusExternalDisplay: no external display, skipping")
                return
            }
            com.portalpad.app.service.PortalPadForegroundService.refocusGlasses()
            Log.d(TAG, "refocusExternalDisplay: re-asserting focus on external display (top task)")
        }.onFailure { Log.w(TAG, "refocusExternalDisplay failed", it) }
    }

    override fun onDestroy() {
        runCatching {
            volumeSession?.isActive = false
            volumeSession?.release()
        }
        volumeSession = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        applyForcedOrientation()
        // Refresh privilege state so the trackpad banner reflects any changes
        // since the trackpad was last visible (e.g. user enabled Shizuku in the
        // Shizuku app, granted root, or toggled Accessibility).
        app.shizuku.refresh()
        kotlinx.coroutines.runBlocking {
            val mode = app.prefs.accessMode.first()
            // Strict opt-in: only probe root when Root is the selected mode.
            if (mode == com.portalpad.app.data.AccessMode.ROOT) {
                runCatching { app.root.refresh() }
            }
            if (mode == com.portalpad.app.data.AccessMode.SHIZUKU && app.shizuku.isReady) {
                app.shizukuClickBackend.bind()
            }
        }
        // Rebuild the volume session on EVERY resume — not just create-if-absent.
        // Returning from Settings (e.g. folder-window customization) leaves the
        // activity paused-not-destroyed, so the old session object persists but the
        // window thrash can leave it in a bad state: hardware volume keys then get
        // swallowed (no system volume pill) yet don't update the in-app pill — a
        // reliable precursor to the focus-stranding crash. setupVolumeSession() is
        // idempotent and would no-op on that stale session, so release + recreate
        // to guarantee a clean one. (On screen-off→on the activity is destroyed, so
        // the session was already null; the release is then a harmless no-op.)
        // Previously this clean rebuild only happened when the user tapped a mode
        // tab — so returning to Remote any other way left volume keys broken.
        runCatching {
            volumeSession?.isActive = false
            volumeSession?.release()
        }
        volumeSession = null
        setupVolumeSession()
        // Screen-off→on (and other resumes) can leave input focus stranded on
        // display 0 with no focused window, which kills hardware volume keys —
        // the same focus-stranding failure as the settings→back path (confirmed
        // by logcat: "Focused display: 165 -> 0" on screen-off, then "no window
        // has focus" on screen-on). Re-assert focus onto the external display.
        // Posted slightly after resume so it runs once the activity has finished
        // its visible/invisible churn rather than during it.
        window.decorView.postDelayed({ refocusExternalDisplay() }, 250)
        // Overlay permission can be revoked from Settings while we're backgrounded.
        // Unlike privilege loss (carried by the Mode strip) or display loss (the
        // display pill), losing overlay is otherwise SILENT here: this phone-side
        // trackpad keeps running, but the glasses-side cursor / dock / controls
        // (service overlay windows) are gone, so taps do nothing visible. Surface it
        // on return so it isn't a mystery. Intentionally NOT wired into accessAlive /
        // the NOT_FOCUSABLE flip — that path is ANR-sensitive; this is a passive notice.
        if (!android.provider.Settings.canDrawOverlays(this)) {
            runCatching {
                android.widget.Toast.makeText(
                    this,
                    "Display overlay is off — the cursor and controls won't appear on your glasses. Re-grant it in PortalPad's setup.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The composable below reads getIntent() inside a remember{} keyed on the
        // intent reference, so recomposing isn't strictly needed; setIntent updates
        // the source of truth for next-create.
        //
        // "Launch Trackpad" (and shortcuts) bring this singleTask instance forward
        // via onNewIntent — another deliberate return into the remote interface,
        // like a mode-tab tap. Re-arm the hardware-volume session here too so
        // volume-key routing is reclaimed on that path as well.
        refreshVolumeSession()
        // Bringing the singleTask instance forward (not a cold start) — re-apply
        // the configured Auto-Launch interface so service start / display connect
        // open the chosen interface instead of keeping the current one. Skipped
        // when the intent explicitly forces a mode (e.g. MODE_MEDIA from a button).
        if (intent.getStringExtra(EXTRA_MODE) == null &&
            !intent.getBooleanExtra(EXTRA_KEEP_CURRENT, false)
        ) {
            applyAutoLaunchInterface?.invoke()
        }
    }

    companion object {
        private const val TAG = "TrackpadActivity"
        const val EXTRA_MODE = "mode"
        const val MODE_TRACKPAD = "trackpad"
        const val MODE_MEDIA = "media"
        /** Set by the Launch button so a warm re-entry KEEPS the interface the
         *  user was last on, instead of resetting to the Auto-Launch interface —
         *  which is correct only for service start / display connect. */
        const val EXTRA_KEEP_CURRENT = "keep_current"

        /** Set by the trackpad screen while it's composed: runs the SAME Home
         *  action the trackpad's Home button does, in the Activity context with
         *  the live injector. The top-bar Home invokes this so it behaves
         *  identically. Null when no trackpad is in the foreground. */
        @Volatile
        var homeTrigger: (() -> Unit)? = null

        /** Set by the trackpad screen while composed: switches the visible
         *  interface (Air Mouse / Trackpad / Remote) to match the configured
         *  Auto-Launch interface. Invoked from [onNewIntent] so that bringing the
         *  singleTask instance forward (service start / display connect / Launch
         *  Trackpad) honors the chosen interface — not just the first cold start. */
        @Volatile
        var applyAutoLaunchInterface: (() -> Unit)? = null
    }
}

/**
 * Launch the settings screen (MainActivity) on the PHONE's built-in display,
 * never the external one. Without an explicit launch display, starting
 * MainActivity from a context whose task is on the external display could place
 * settings on the glasses (and shuffle the trackpad onto the phone) — the
 * display "swap" bug. Pinning to DEFAULT_DISPLAY keeps settings on the phone.
 */
private fun launchSettingsOnPhone(ctx: android.content.Context, extraFlags: Int = 0) {
    val intent = android.content.Intent(ctx, MainActivity::class.java)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or extraFlags)
    val opts = android.app.ActivityOptions.makeBasic().apply {
        runCatching { launchDisplayId = android.view.Display.DEFAULT_DISPLAY }
    }
    runCatching { ctx.startActivity(intent, opts.toBundle()) }
        .onFailure { runCatching { ctx.startActivity(intent) } }
}

@Composable
private fun RestoreSessionOffer() {
    val app = PortalPadApp.instance
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var offer by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    var decided by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    // Decide once when the trackpad opens: offer restore if there's a saved
    // multi-window session we can actually restore and it's not already open.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (decided) return@LaunchedEffect
        decided = true
        val displayId = app.externalDisplayId.value ?: return@LaunchedEffect
        // A flap auto-recovery (quick unplug/replug) is already restoring this
        // session on the external display — don't pop a phone dialog asking to do
        // what's already underway. Cold reconnects don't set this, so the offer
        // still appears there.
        if (app.isFlapRecovering()) return@LaunchedEffect
        // User can disable the auto-popup (manual Settings button still works).
        val offerEnabled = runCatching {
            app.prefs.bool(com.portalpad.app.data.PreferencesRepository.Keys.OFFER_RESTORE_ON_RECONNECT, default = true)
                .first()
        }.getOrDefault(true)
        if (!offerEnabled) return@LaunchedEffect
        val desktop = runCatching {
            app.prefs.bool(com.portalpad.app.data.PreferencesRepository.Keys.DESKTOP_MODE_ENABLED, default = false)
                .first()
        }.getOrDefault(false)
        if (!desktop) return@LaunchedEffect
        if (!app.hasLaunchPrivilege) return@LaunchedEffect
        val session = runCatching { app.prefs.lastSession.first() }.getOrNull() ?: return@LaunchedEffect
        val count = session.windows.size
        if (count == 0) return@LaunchedEffect
        // Skip if the saved apps already appear to be open on the display.
        val openNow = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.freeform.snapshotSession(displayId)?.windows?.size ?: 0
            }
        }.getOrDefault(0)
        if (openNow >= count) return@LaunchedEffect
        offer = count
    }

    val count = offer ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { offer = null },
        containerColor = com.portalpad.app.ui.theme.AbSurfaceElevated,
        title = { androidx.compose.material3.Text("Restore last session?", color = com.portalpad.app.ui.theme.AbOnSurface) },
        text = {
            androidx.compose.material3.Text(
                "Reopen $count app${if (count == 1) "" else "s"} on your external display, arranged where you left off.",
                color = com.portalpad.app.ui.theme.AbOnSurfaceMuted,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                offer = null
                com.portalpad.app.service.PortalPadForegroundService.instance?.restoreSavedSession()
                    ?: android.widget.Toast.makeText(ctx, "Service not running", android.widget.Toast.LENGTH_SHORT).show()
            }) { androidx.compose.material3.Text("Restore", color = com.portalpad.app.ui.theme.AbPrimaryBright) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { offer = null }) {
                androidx.compose.material3.Text("Not now", color = com.portalpad.app.ui.theme.AbOnSurfaceMuted)
            }
        },
    )
}

@Composable
private fun TrackpadScreen(injector: InputInjector, initialMode: String) {
    val app = PortalPadApp.instance
    // The hosting activity, used to re-arm the hardware-volume MediaSession when
    // the user taps a mode tab (the action used to return from Settings).
    // LocalContext may be a ContextWrapper, so unwrap to find the activity.
    val trackpadActivity = run {
        var c: android.content.Context? = androidx.compose.ui.platform.LocalContext.current
        while (c is android.content.ContextWrapper && c !is TrackpadActivity) c = c.baseContext
        c as? TrackpadActivity
    }
    // The interface's top tab selection. Air Mouse and Trackpad both show the
    // trackpad surface (Air Mouse just drives the cursor by tilting the
    // glasses); Remote shows the media/remote panel. Default = Trackpad.
    // Selecting Air Mouse vs Trackpad also writes the InputMode pref so the
    // injector/air-mouse controller behave accordingly — this replaces the
    // old input-mode selector that used to live in Settings.
    val storedInputMode = app.prefs.inputMode.collectAsState(
        initial = com.portalpad.app.data.InputMode.TRACKPAD,
    ).value
    // Was Remote the last-used interface? Read once, synchronously, so the very
    // first composition can restore it (Trackpad/AirMouse restore via InputMode;
    // Remote needs this flag). Only honored when the launch didn't explicitly ask
    // for a mode via the intent extra.
    val lastWasRemote = remember {
        runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.bool(
                    com.portalpad.app.data.PreferencesRepository.Keys.LAST_MODE_WAS_REMOTE,
                    default = false,
                ).first()
            }
        }.getOrDefault(false)
    }
    // The configured Auto-Launch interface. When set, it takes precedence over
    // the last-used restore on a default (non-Remote-forced) launch — so the
    // session opens in the user's chosen interface. Read once, synchronously.
    val autoIface = remember {
        runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.autoLaunchInterface.first()
            }
        }.getOrDefault(com.portalpad.app.data.AutoLaunchInterface.TRACKPAD)
    }
    var viewMode by remember(initialMode) {
        mutableStateOf(
            when {
                initialMode == TrackpadActivity.MODE_MEDIA ->
                    com.portalpad.app.ui.trackpad.TrackpadViewMode.REMOTE
                // Auto-Launch interface preference takes precedence on a default
                // launch (the intent didn't force Remote/media).
                initialMode == TrackpadActivity.MODE_TRACKPAD ->
                    when (autoIface) {
                        com.portalpad.app.data.AutoLaunchInterface.REMOTE ->
                            com.portalpad.app.ui.trackpad.TrackpadViewMode.REMOTE
                        com.portalpad.app.data.AutoLaunchInterface.AIR_MOUSE ->
                            com.portalpad.app.ui.trackpad.TrackpadViewMode.AIR_MOUSE
                        com.portalpad.app.data.AutoLaunchInterface.TRACKPAD ->
                            // Fall back to last-used / stored input mode when the
                            // preference is the default Trackpad.
                            when {
                                lastWasRemote -> com.portalpad.app.ui.trackpad.TrackpadViewMode.REMOTE
                                storedInputMode == com.portalpad.app.data.InputMode.AIR_MOUSE ->
                                    com.portalpad.app.ui.trackpad.TrackpadViewMode.AIR_MOUSE
                                else -> com.portalpad.app.ui.trackpad.TrackpadViewMode.TRACKPAD
                            }
                    }
                storedInputMode == com.portalpad.app.data.InputMode.AIR_MOUSE ->
                    com.portalpad.app.ui.trackpad.TrackpadViewMode.AIR_MOUSE
                else -> com.portalpad.app.ui.trackpad.TrackpadViewMode.TRACKPAD
            },
        )
    }
    val isTrackpadMode = viewMode != com.portalpad.app.ui.trackpad.TrackpadViewMode.REMOTE
    var showAppDrawer by remember { mutableStateOf(false) }
    var showQuickWheel by remember { mutableStateOf(false) }
    var showWindowWheel by remember { mutableStateOf(false) }
    // Quick Wheel ring contents (the bottom-bar drawer icon opens the wheel when
    // any slot is filled, else it falls straight through to the full drawer).
    val wheelSlots = app.prefs.wheelSlots.collectAsState(
        initial = List(com.portalpad.app.data.QUICK_WHEEL_SLOTS) { null },
    ).value
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val homeAction = app.prefs.homeAction.collectAsState(initial = null).value
    // Register the SAME Home action for the top-bar Home button to invoke, so
    // it runs in this Activity context with the live injector — identical to
    // tapping the trackpad's own Home. Cleared when this screen leaves.
    DisposableEffect(homeAction) {
        TrackpadActivity.homeTrigger = {
            com.portalpad.app.service.PortalPadForegroundService.dismissSearchOverlay()
            if (homeAction != null) launchEntry(homeAction, injector) else injector.home()
        }
        onDispose { TrackpadActivity.homeTrigger = null }
    }
    // Register the interface re-apply trigger so onNewIntent (service start /
    // display connect bringing this singleTask instance forward) can switch the
    // visible interface to the configured Auto-Launch choice. Without this, the
    // viewMode init only runs on a cold start, so subsequent launches kept the
    // current interface (always trackpad).
    DisposableEffect(Unit) {
        TrackpadActivity.applyAutoLaunchInterface = {
            val iface = runCatching {
                kotlinx.coroutines.runBlocking { app.prefs.autoLaunchInterface.first() }
            }.getOrDefault(com.portalpad.app.data.AutoLaunchInterface.TRACKPAD)
            viewMode = when (iface) {
                com.portalpad.app.data.AutoLaunchInterface.REMOTE ->
                    com.portalpad.app.ui.trackpad.TrackpadViewMode.REMOTE
                com.portalpad.app.data.AutoLaunchInterface.AIR_MOUSE ->
                    com.portalpad.app.ui.trackpad.TrackpadViewMode.AIR_MOUSE
                com.portalpad.app.data.AutoLaunchInterface.TRACKPAD ->
                    com.portalpad.app.ui.trackpad.TrackpadViewMode.TRACKPAD
            }
            // Keep the InputMode pref aligned for Air Mouse / Trackpad so the
            // injector + air-mouse controller match the shown interface.
            when (iface) {
                com.portalpad.app.data.AutoLaunchInterface.AIR_MOUSE ->
                    scope.launch { app.prefs.setInputMode(com.portalpad.app.data.InputMode.AIR_MOUSE) }
                com.portalpad.app.data.AutoLaunchInterface.TRACKPAD ->
                    scope.launch { app.prefs.setInputMode(com.portalpad.app.data.InputMode.TRACKPAD) }
                com.portalpad.app.data.AutoLaunchInterface.REMOTE -> { /* leave pointing mode */ }
            }
            // Reset the last-used-interface marker so the Launch button label and
            // its keep-current restore default to this interface after a connect.
            scope.launch {
                app.prefs.setBool(
                    com.portalpad.app.data.PreferencesRepository.Keys.LAST_MODE_WAS_REMOTE,
                    iface == com.portalpad.app.data.AutoLaunchInterface.REMOTE,
                )
            }
        }
        onDispose { TrackpadActivity.applyAutoLaunchInterface = null }
    }
    val backAction = app.prefs.backAction.collectAsState(initial = BackAction.System).value
    val backStyle = app.prefs.backButtonStyle.collectAsState(initial = null).value
    val homeStyle = app.prefs.homeButtonStyle.collectAsState(initial = null).value
    val appDrawerStyle = app.prefs.appDrawerButtonStyle.collectAsState(initial = null).value
    val keyboardStyle = app.prefs.keyboardButtonStyle.collectAsState(initial = null).value
    val navOrder = app.prefs.navButtonOrder
        .collectAsState(initial = com.portalpad.app.data.PreferencesRepository.DEFAULT_NAV_ORDER).value
    // Long-press menu: null = hidden, otherwise "back" | "home" target.
    var longPressMenuTarget by remember { mutableStateOf<String?>(null) }
    var navReorderMode by remember { mutableStateOf(false) }
    val showFingerDots = app.prefs.bool(
        com.portalpad.app.data.PreferencesRepository.Keys.SHOW_FINGER_DOTS, false,
    ).collectAsState(initial = false).value
    // First-tap capture explainer: the first time the user taps (or long-presses)
    // the screenshot/record button, show a one-time dialog teaching tap=screenshot
    // / hold=record and letting them act right there. After it's been seen the
    // gestures work directly. Default-false = show it; persisted true once seen.
    val captureIntroShown = app.prefs.bool(
        com.portalpad.app.data.PreferencesRepository.Keys.CAPTURE_INTRO_SHOWN, false,
    ).collectAsState(initial = false).value
    var showCaptureIntro by remember { mutableStateOf(false) }
    val showKeyboardButton = app.prefs.bool(
        com.portalpad.app.data.PreferencesRepository.Keys.SHOW_KEYBOARD, true,
    ).collectAsState(initial = true).value
    // Single source of truth for the keyboard pill's behavior. ON = the pill
    // routes the system keyboard to the glasses (tap a field there to type);
    // OFF = the pill opens the phone-side relay. Same pref the long-press menu
    // and the settings toggle drive.
    val keyboardOnExternal = app.prefs.bool(
        com.portalpad.app.data.PreferencesRepository.Keys.IME_ON_EXTERNAL, false,
    ).collectAsState(initial = false).value
    val tapToClick = app.prefs.bool(
        com.portalpad.app.data.PreferencesRepository.Keys.TAP_TO_CLICK, true,
    ).collectAsState(initial = true).value
    val pointerAccel = app.prefs.float(
        com.portalpad.app.data.PreferencesRepository.Keys.POINTER_ACCEL, 1.0f,
    ).collectAsState(initial = 1.0f).value
    val edgeScrollEnabled = app.prefs.bool(
        com.portalpad.app.data.PreferencesRepository.Keys.EDGE_SCROLL, true,
    ).collectAsState(initial = true).value
    val edgeScrollSide = app.prefs.string(
        com.portalpad.app.data.PreferencesRepository.Keys.EDGE_SCROLL_SIDE, "right",
    ).collectAsState(initial = "right").value
    val edgeStripOnLeft = edgeScrollSide == "left"

    // Flashlight — single instance held for the lifetime of this composable.
    // The controller exposes a Flow that also reflects external state
    // changes (Quick Settings tile, other apps' torch use) — so our UI
    // stays in sync regardless of how the flashlight is toggled.
    val flashlight = remember { com.portalpad.app.service.FlashlightController(app) }
    val flashlightOn by flashlight.isOnFlow.collectAsState()
    val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // Once granted, fulfill the originally-requested toggle.
            flashlight.toggle()
        } else {
            android.widget.Toast.makeText(
                app,
                "Flashlight needs camera permission",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val ctxForPerm = androidx.compose.ui.platform.LocalContext.current
    // Auto-turn-off flashlight when the activity goes away, and release the
    // TorchCallback so we don't leak it.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            if (flashlight.isOn) flashlight.setOn(false)
            flashlight.close()
        }
    }

    fun onFlashlightTap() {
        if (!flashlight.available) {
            android.widget.Toast.makeText(ctxForPerm, "No flashlight on this device", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctxForPerm, android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            flashlight.toggle()
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // Cursor visibility: shown while on the trackpad (driving the cursor),
    // hidden on other tabs (e.g. media). Updated reactively.
    LaunchedEffect(isTrackpadMode) {
        app.injector.setCursorVisible(isTrackpadMode)
    }

    // Air mouse — active when the Air Mouse tab is selected.
    val airMouseActive = viewMode == com.portalpad.app.ui.trackpad.TrackpadViewMode.AIR_MOUSE
    val showGuide = remember { mutableStateOf(false) }
    val showControls = remember { mutableStateOf(false) }
    val showDisplaySubpage = remember { mutableStateOf(false) }
    val showKeyboardMenu = remember { mutableStateOf(false) }
    val airMouse = remember {
        com.portalpad.app.service.AirMouseController(app, app.injector)
    }
    // Live-tune the air-mouse controller from prefs. Default 20f MUST match the
    // SpeedSlider default in settings, or an unset pref makes the slider show 20
    // while the engine runs at the fallback (felt slow until the slider moved).
    val amSensitivity = app.prefs.float(
        com.portalpad.app.data.PreferencesRepository.Keys.AIR_MOUSE_SENSITIVITY, 20f,
    ).collectAsState(initial = 20f).value
    val amSmoothing = app.prefs.float(
        com.portalpad.app.data.PreferencesRepository.Keys.AIR_MOUSE_SMOOTHING, 0.3f,
    ).collectAsState(initial = 0.3f).value
    val amInvertX = app.prefs.bool(
        com.portalpad.app.data.PreferencesRepository.Keys.AIR_MOUSE_INVERT_X, false,
    ).collectAsState(initial = false).value
    val amInvertY = app.prefs.bool(
        com.portalpad.app.data.PreferencesRepository.Keys.AIR_MOUSE_INVERT_Y, false,
    ).collectAsState(initial = false).value
    val amAccel = app.prefs.float(
        com.portalpad.app.data.PreferencesRepository.Keys.AIR_MOUSE_ACCEL, 1.0f,
    ).collectAsState(initial = 1.0f).value
    LaunchedEffect(amSensitivity, amSmoothing, amInvertX, amInvertY, amAccel) {
        airMouse.sensitivity = amSensitivity
        airMouse.smoothing = amSmoothing
        airMouse.invertX = amInvertX
        airMouse.invertY = amInvertY
        airMouse.accelStrength = amAccel
    }
    DisposableEffect(airMouseActive) {
        if (airMouseActive) airMouse.start()
        onDispose { airMouse.stop() }
    }

    // Restore cursor visible when leaving the app
    DisposableEffect(Unit) {
        onDispose { app.injector.setCursorVisible(true) }
    }
    val externalDisplayId by app.externalDisplayId.collectAsState()
    val shizukuStatus by app.shizuku.status.collectAsState()
    val shizukuUserServiceReady by app.shizukuClickBackend.readyFlow.collectAsState()

    // Resolve the underlying *physical* display's name + resolution for the
    // selector pill. We deliberately use the physical id (not the VD id):
    // when we wrap glasses in a trusted Virtual Display, the VD's display
    // object has our wrapper name ("PortalPad Session"), which isn't useful
    // to show the user. The physical display has the real hardware name
    // (e.g. "HDMI", "XREAL One Pro") that tells you what's actually
    // connected. Falls back to externalDisplayId if there's no VD wrap.
    val physicalExternalDisplayId by app.physicalExternalDisplayId.collectAsState()
    val isExternalRecording by com.portalpad.app.ScreenRecorder.isRecording.collectAsState()
    val externalRecElapsed by com.portalpad.app.ScreenRecorder.elapsedSec.collectAsState()
    val labelDisplayId = physicalExternalDisplayId ?: externalDisplayId
    val displayLabel = labelDisplayId?.let { id ->
        resolveDisplayLabel(app, id)
    }

    // Tell the injector where to send input. 0 (DEFAULT_DISPLAY) means events
    // go to the local screen, which is wrong; fall back to that only if no
    // external display is attached.
    injector.displayId = externalDisplayId ?: 0

    // Stable callback the remembered GestureSink can call to open the app
    // drawer (the sink is remembered once, so it can't capture the mutable
    // showAppDrawer setter directly).
    val openDrawer = rememberUpdatedState { showAppDrawer = true }

    // Configurable 3-finger swipe actions. Collected here and exposed to the
    // remembered sink via rememberUpdatedState so it always reads the current
    // assignment without being recreated.
    val gUp = app.prefs.gestureSwipeUp.collectAsState(initial = com.portalpad.app.data.GestureAction.NOTIF_CLOSE).value
    val gDown = app.prefs.gestureSwipeDown.collectAsState(initial = com.portalpad.app.data.GestureAction.NOTIF_OPEN).value
    val gLeft = app.prefs.gestureSwipeLeft.collectAsState(initial = com.portalpad.app.data.GestureAction.HOME).value
    val gRight = app.prefs.gestureSwipeRight.collectAsState(initial = com.portalpad.app.data.GestureAction.BACK).value
    val gUpApp = app.prefs.gestureAppUp.collectAsState(initial = null).value
    val gDownApp = app.prefs.gestureAppDown.collectAsState(initial = null).value
    val gLeftApp = app.prefs.gestureAppLeft.collectAsState(initial = null).value
    val gRightApp = app.prefs.gestureAppRight.collectAsState(initial = null).value
    val gestureActions = rememberUpdatedState(
        com.portalpad.app.GestureActions(
            up = gUp, down = gDown, left = gLeft, right = gRight,
            upApp = gUpApp, downApp = gDownApp, leftApp = gLeftApp, rightApp = gRightApp,
        ),
    )

    val sink = remember {
        object : GestureSink {
            override fun onMove(dx: Float, dy: Float) = injector.pointerMove(dx, dy)
            override fun onTap() = injector.leftClick()
            override fun onSecondaryTap() = injector.rightClick()
            override fun onDoubleTap() = injector.doubleClick()
            override fun onLongPressStart() {}
            override fun onLongPressEnd() {}
            override fun onScroll(dx: Float, dy: Float) = injector.scroll(dx, dy)
            override fun onDragStart() = injector.dragStart()
            override fun onDragMove(dx: Float, dy: Float) = injector.dragMove(dx, dy)
            override fun onDragEnd() = injector.dragEnd()
            override fun onPinch(scale: Float) = injector.pinchUpdate(scale)
            override fun onPinchEnd() = injector.pinchCommit()
            override fun isOnResizeEdge(): Boolean =
                injector.cursorType.value != com.portalpad.app.service.CursorType.ARROW
            override fun isOnCaption(): Boolean = injector.cursorOnCaption.value
            override fun onThreeFingerSwipe(dx: Float, dy: Float) {
                // Resolve the swipe direction, then run the user-configured action
                // for it (defaults preserve the original right→Back / left→Home /
                // down→open panel / up→close panel behavior).
                val a = gestureActions.value
                val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                val action = if (horizontal) {
                    if (dx > 0) a.right else a.left
                } else {
                    if (dy > 0) a.down else a.up
                }
                val app = if (horizontal) {
                    if (dx > 0) a.rightApp else a.leftApp
                } else {
                    if (dy > 0) a.downApp else a.upApp
                }
                when (action) {
                    com.portalpad.app.data.GestureAction.NONE -> {}
                    com.portalpad.app.data.GestureAction.BACK -> injector.backGuarded()
                    com.portalpad.app.data.GestureAction.HOME -> injector.home()
                    com.portalpad.app.data.GestureAction.NOTIF_OPEN ->
                        com.portalpad.app.service.PortalPadForegroundService.showNotificationPanel()
                    com.portalpad.app.data.GestureAction.NOTIF_CLOSE ->
                        com.portalpad.app.service.PortalPadForegroundService.dismissNotificationPanel()
                    com.portalpad.app.data.GestureAction.LAUNCH_APP ->
                        app?.let { launchEntry(it, injector) }
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(AbBackground)
            .padding(8.dp),
    ) {
        TrackpadTopBar(
            mode = viewMode,
            onSelectMode = { selected ->
                viewMode = selected
                // Re-assert the volume MediaSession on every tab tap. Returning
                // from Settings (the user taps a tab — usually "Remote" — to come
                // back) is exactly when hardware-volume routing has been lost to
                // the window thrash; re-asserting here, after the transition has
                // settled and on a deliberate user action, reclaims it at a
                // reliable moment that the lifecycle callbacks didn't catch.
                // Fires even when re-selecting the already-active tab.
                trackpadActivity?.refreshVolumeSession()
                // Air Mouse / Trackpad also drive the InputMode pref so the
                // injector + air-mouse controller match the chosen pointing
                // mode. Remote leaves the pointing mode untouched, but we record
                // whether Remote is the last-used interface so it's restored on
                // the next launch (e.g. after assigning a button shortcut).
                when (selected) {
                    com.portalpad.app.ui.trackpad.TrackpadViewMode.AIR_MOUSE ->
                        scope.launch {
                            app.prefs.setInputMode(com.portalpad.app.data.InputMode.AIR_MOUSE)
                            app.prefs.setBool(com.portalpad.app.data.PreferencesRepository.Keys.LAST_MODE_WAS_REMOTE, false)
                        }
                    com.portalpad.app.ui.trackpad.TrackpadViewMode.TRACKPAD ->
                        scope.launch {
                            app.prefs.setInputMode(com.portalpad.app.data.InputMode.TRACKPAD)
                            app.prefs.setBool(com.portalpad.app.data.PreferencesRepository.Keys.LAST_MODE_WAS_REMOTE, false)
                        }
                    com.portalpad.app.ui.trackpad.TrackpadViewMode.REMOTE ->
                        scope.launch {
                            app.prefs.setBool(com.portalpad.app.data.PreferencesRepository.Keys.LAST_MODE_WAS_REMOTE, true)
                        }
                }
            },
            onFlashlightClick = { onFlashlightTap() },
            flashlightOn = flashlightOn,
            onScreenshotClick = {
                // Capture the external display via `screencap -d <physicalId>`
                // through the Shizuku UserService. We use the PHYSICAL display
                // id (the hardware HDMI/USB-C display under our VD wrap) —
                // screencap only sees SurfaceFlinger-composited surfaces, and
                // VirtualDisplays don't appear there. The physical display
                // mirrors our VD's content via VirtualDisplayMirror's
                // SurfaceView, so capturing it gets what the user actually
                // sees on the glasses.
                val physicalId = app.physicalExternalDisplayId.value
                if (!captureIntroShown) {
                    showCaptureIntro = true
                } else {
                    takeExternalDisplayScreenshot(app, physicalId)
                }
            },
            onSettingsClick = {
                launchSettingsOnPhone(app, android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            },
            isRecording = isExternalRecording,
            recordingLabel = com.portalpad.app.ScreenRecorder.formatElapsed(externalRecElapsed),
            onStartRecording = {
                if (!captureIntroShown) showCaptureIntro = true
                else com.portalpad.app.ScreenRecorder.start(app)
            },
            onStopRecording = { com.portalpad.app.ScreenRecorder.stop(app) },
        )

        if (showCaptureIntro) {
            val markSeen = {
                showCaptureIntro = false
                scope.launch {
                    app.prefs.setBool(
                        com.portalpad.app.data.PreferencesRepository.Keys.CAPTURE_INTRO_SHOWN, true,
                    )
                }
                Unit
            }
            CaptureIntroDialog(
                onDismiss = { markSeen() },
                onScreenshot = {
                    markSeen()
                    val physicalId = app.physicalExternalDisplayId.value
                    takeExternalDisplayScreenshot(app, physicalId)
                },
                onRecord = {
                    markSeen()
                    com.portalpad.app.ScreenRecorder.start(app)
                },
            )
        }

        // (The old "Input access not ready — tap to fix" banner was removed: the
        // "Mode: Shizuku ⚠" strip now carries that state and a tap opens the
        // Shizuku control popup, which is more useful than routing to the app page.)

        // Only render the display pill when something is actually connected.
        if (displayLabel != null) {
            Spacer(Modifier.height(10.dp))
            DisplaySelectorPill(label = displayLabel, onClick = { showDisplaySubpage.value = true })
        }

        Spacer(Modifier.height(12.dp))

        AnimatedContent(
            targetState = isTrackpadMode,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val dir = if (targetState) -1 else 1
                (slideInHorizontally(animationSpec = tween(280)) { it * dir } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(animationSpec = tween(280)) { it * -dir } + fadeOut(tween(180))) using
                    SizeTransform(clip = false)
            },
            label = "mode",
        ) { trackpad ->
            if (trackpad) {
                // Gate the gesture exclusion (below) on an external display being
                // present — same rationale as TrackpadSurface: avoids leaving rects
                // for WindowManager to clean up against a torn-down display id
                // during a flap (which crashes system_server's WM).
                val hasExternalDisplay by app.externalDisplayId.collectAsState()
                // Wrap the trackpad surface(s) in a Box so we can overlay a
                // power button at the top-left. The button intercepts taps
                // in its small footprint but doesn't interfere with the
                // surrounding trackpad gesture surface (it's a sibling Box,
                // not part of the surface itself).
                // Exclude the trackpad's bounds from the system edge-gesture
                // zones. Without this, a touch near the phone's physical screen
                // edge (where the edge-scroll strip sits) can be grabbed by
                // Android's SystemGesturesPointerEventListener and dispatched as
                // a stray input — which landed as an unwanted click on the
                // external display wherever the cursor happened to rest. NOTE:
                // the OS caps how much vertical edge area an app may exclude
                // (~200dp per side), so this covers the main reach area, not
                // necessarily the full height.
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (hasExternalDisplay != null) Modifier.systemGestureExclusion()
                            else Modifier,
                        ),
                ) {
                    if (edgeScrollEnabled) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (edgeStripOnLeft) {
                                com.portalpad.app.ui.trackpad.ScrollBar(
                                    onScroll = { dx, dy -> injector.scroll(dx, dy) },
                                    modifier = Modifier
                                        .width(44.dp)
                                        .fillMaxHeight(),
                                )
                                androidx.compose.foundation.layout.Spacer(
                                    Modifier.width(8.dp),
                                )
                                TrackpadSurface(
                                    sink = sink,
                                    showFingerDots = showFingerDots,
                                    tapToClick = tapToClick,
                                    pointerAccel = pointerAccel,
                                    edgeStripSide = "left",
                                    modifier = Modifier.weight(1f).fillMaxSize(),
                                )
                            } else {
                                TrackpadSurface(
                                    sink = sink,
                                    showFingerDots = showFingerDots,
                                    tapToClick = tapToClick,
                                    pointerAccel = pointerAccel,
                                    edgeStripSide = "right",
                                    modifier = Modifier.weight(1f).fillMaxSize(),
                                )
                                androidx.compose.foundation.layout.Spacer(
                                    Modifier.width(8.dp),
                                )
                                com.portalpad.app.ui.trackpad.ScrollBar(
                                    onScroll = { dx, dy -> injector.scroll(dx, dy) },
                                    modifier = Modifier
                                        .width(44.dp)
                                        .fillMaxHeight(),
                                )
                            }
                        }
                    } else {
                        TrackpadSurface(
                            sink = sink,
                            showFingerDots = showFingerDots,
                            tapToClick = tapToClick,
                            pointerAccel = pointerAccel,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // Overlays (power / guide / gear) anchor to the TRACKPAD
                    // SURFACE region, not the whole Box. When the edge-scroll
                    // strip is on, the surface is narrower by the strip (8dp gap
                    // + 44dp strip = 52dp), so we inset the overlay layer by that
                    // much on whichever side the strip is on — otherwise the
                    // centered overlays would sit off-center toward the strip.
                    val overlayStartInset = if (edgeScrollEnabled && edgeStripOnLeft) 52.dp else 0.dp
                    val overlayEndInset = if (edgeScrollEnabled && !edgeStripOnLeft) 52.dp else 0.dp
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(start = overlayStartInset, end = overlayEndInset),
                    ) {
                        // Power button overlay — top-left, slightly inset so it
                        // sits inside the surface visually but doesn't compete
                        // with the corner gesture region.
                        com.portalpad.app.ui.power.ExtinguishPowerButton(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp),
                        )
                        // Gesture guide — subtle pill near the bottom edge,
                        // centered on the surface. Sibling overlay, so its tap is
                        // consumed here and never reaches the gesture surface.
                        com.portalpad.app.ui.trackpad.GuidePill(
                            onClick = { injector.buzz(longPress = false); showGuide.value = true },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                        )
                        // Settings gear — bottom-left, just the icon (no
                        // background), opens the mode-specific control subpage.
                        // Consumes its own tap.
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 12.dp, bottom = 12.dp)
                                .clickable { injector.buzz(longPress = false); showControls.value = true }
                                .padding(6.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = "Control settings",
                                tint = AbOnSurfaceMuted,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            } else {
                val mc = app.media

                MediaControlsPanel(
                    onPlayPause = mc::playPause,
                    onStop = mc::stop,
                    onNext = mc::next,
                    onPrev = mc::prev,
                    onSkip10sForward = mc::fastForward,
                    onSkip10sBack = mc::rewind,
                    onFastForward = mc::fastForward,
                    onRewind = mc::rewind,
                    onVolUp = mc::volumeUp,
                    onVolDown = mc::volumeDown,
                    onSetVolumePct = mc::setVolumePct,
                    onMute = mc::mute,
                    onDpadUp = injector::dpadUp,
                    onDpadDown = injector::dpadDown,
                    onDpadLeft = injector::dpadLeft,
                    onDpadRight = injector::dpadRight,
                    onDpadCenter = injector::dpadCenter,
                    onReturn = injector::back,
                    onProgRed = injector::progRed,
                    onProgGreen = injector::progGreen,
                    onProgYellow = injector::progYellow,
                    onProgBlue = injector::progBlue,
                )

            }
        }

        // Gesture guide dialog (trackpad / air-mouse modes only).
        if (isTrackpadMode && showGuide.value) {
            com.portalpad.app.ui.trackpad.GestureGuideDialog(
                isAirMouse = airMouseActive,
                onDismiss = { showGuide.value = false },
            )
        }

        // Click buttons only make sense in trackpad mode.
        if (isTrackpadMode) {
            Spacer(Modifier.height(10.dp))
            // Arrange button only appears when desktop windows is on (nothing to
            // arrange otherwise); the click buttons reflow to full width when off.
            val desktopMode by app.prefs
                .bool(com.portalpad.app.data.PreferencesRepository.Keys.DESKTOP_MODE_ENABLED, false)
                .collectAsState(initial = false)
            TrackpadClickButtons(
                onLeftClick = { injector.leftClick() },
                onRightClick = { injector.rightClick() },
                showArrange = desktopMode,
                // Tap opens the radial window-actions menu (arrange evenly is its
                // one-tap center). Long-press mirrors tap, so the gesture is gone.
                onArrange = { showWindowWheel = true },
                onArrangeLongPress = { showWindowWheel = true },
            )
        }

        Spacer(Modifier.height(if (isTrackpadMode) 10.dp else 2.dp))
        TrackpadBottomBar(
            onBack = {
                when (val ba = backAction) {
                    BackAction.System -> injector.backGuarded()
                    is BackAction.Launch -> launchEntry(ba.entry, injector)
                }
            },
            onHome = {
                // Close any on-display app-search first so the home app comes
                // to the front (search is a focusable overlay that otherwise
                // stays on top of the launched app).
                com.portalpad.app.service.PortalPadForegroundService.dismissSearchOverlay()
                if (homeAction != null) launchEntry(homeAction, injector) else injector.home()
            },
            onAppDrawer = {
                // The drawer pill opens the Quick Wheel. Do NOT bypass to the full
                // drawer when slots are empty: the only way to add the first app is
                // from inside the wheel ("+"), so bypassing left it unreachable for
                // first-time setup. An empty wheel is still useful — every slot is a
                // "+", and the center button opens the full drawer in one tap.
                showQuickWheel = true
            },
            // Keyboard pill — only shown when the user has the pill enabled.
            // Tap behavior depends on the keyboard-display preference:
            //  • Glasses → route the system keyboard to the glasses; the user
            //          taps a text field there to type (no relay window).
            //  • Phone   → force the NATIVE phone keyboard to show on the
            //          currently focused glasses field. If that fails (some
            //          fields/Android versions refuse a forced show), fall back
            //          to the relay page as the reliable recourse.
            // Null = pill hidden.
            onKeyboard = if (showKeyboardButton) ({
                if (keyboardOnExternal) {
                    app.injector.showImeOnExternal()
                    android.widget.Toast.makeText(
                        app,
                        "Keyboard on the glasses — tap a text field there to type",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    // Phone mode: try to raise the native keyboard on the phone
                    // for the focused glasses field. Routing already resolves to
                    // display 0; this forces the show the field didn't auto-do.
                    val shown = app.injector.forceShowImeOnPhone()
                    if (!shown) {
                        // Fallback: open the phone-side relay so the user can
                        // still type if the native forced-show was refused.
                        val relayOpts = android.app.ActivityOptions.makeBasic()
                            .setLaunchDisplayId(0)
                            .toBundle()
                        app.startActivity(
                            android.content.Intent(app, KeyboardRelayActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            relayOpts,
                        )
                    }
                }
            }) else null,
            onHomeLongPress = { longPressMenuTarget = "home" },
            onBackLongPress = { longPressMenuTarget = "back" },
            onAppDrawerLongPress = { longPressMenuTarget = "appdrawer" },
            onKeyboardLongPress = { showKeyboardMenu.value = true },
            backAppearance = backStyle,
            homeAppearance = homeStyle,
            appDrawerAppearance = appDrawerStyle,
            keyboardAppearance = keyboardStyle,
            order = navOrder,
            reorderMode = navReorderMode,
            onExitReorder = { navReorderMode = false },
            onCommitOrder = { scope.launch { app.prefs.setNavButtonOrder(it) } },
        )
    }

    // Mode-specific control subpage, opened from the gear on the surface. It's a
    // full-screen opaque overlay; Back simply hides it, returning to whichever
    // interface (trackpad / air mouse) was underneath — no mode change needed
    // since we never left the activity.
    if (showControls.value) {
        com.portalpad.app.ui.settings.ControlsSubpage(
            isAirMouse = airMouseActive,
            onBack = { showControls.value = false },
        )
    }

    // The trackpad window is FLAG_NOT_FOCUSABLE (so it won't steal focus from the
    // glasses) — which also blocks the soft keyboard. Mark the Display subpage open
    // so the unified focusability decision makes the window focusable (and keeps it
    // that way despite display/Shizuku churn), letting the DPI field raise the
    // number keypad; closing it restores the protection.
    LaunchedEffect(showDisplaySubpage.value) {
        trackpadActivity?.displaySubpageOpen?.value = showDisplaySubpage.value
    }

    // Display tuning subpage, opened from the display-info pill. Full-screen
    // overlay; "← Back" returns to whichever interface is underneath.
    if (showDisplaySubpage.value) {
        com.portalpad.app.ui.settings.DisplaySubpage(
            onBack = { showDisplaySubpage.value = false },
        )
    }

    // Keyboard button long-press menu (styled like the Home/Back menus).
    if (showKeyboardMenu.value) {
        KeyboardLongPressMenu(
            keyboardOnExternal = keyboardOnExternal,
            onAndroidKeyboardSettings = {
                showKeyboardMenu.value = false
                val action = android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS
                // Launch on the phone (display 0, fullscreen) via shell so the same
                // "Tap to exit" pill we use for quick-settings can attach over it;
                // fall back to a plain launch if the shell backend isn't ready.
                Thread {
                    var launched = false
                    if (app.access.isReady) {
                        com.portalpad.app.service.PortalPadForegroundService.suppressDisplayChanges(3000)
                        launched = runCatching {
                            val out = app.access.execShell("am start --display 0 -a $action")
                            !out.contains("Error:", ignoreCase = true) &&
                                !out.contains("Exception", ignoreCase = true)
                        }.getOrDefault(false)
                        if (launched) {
                            com.portalpad.app.service.PortalPadForegroundService.attachPhoneExitBands(action)
                        }
                    }
                    if (!launched) {
                        runCatching {
                            app.startActivity(
                                android.content.Intent(action)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }.start()
            },
            onSwitchKeyboard = {
                showKeyboardMenu.value = false
                runCatching {
                    val imm = app.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    imm.showInputMethodPicker()
                }
            },
            onShowKeyboardOnExternal = {
                showKeyboardMenu.value = false
                val newValue = !keyboardOnExternal
                scope.launch {
                    app.prefs.setBool(
                        com.portalpad.app.data.PreferencesRepository.Keys.IME_ON_EXTERNAL,
                        newValue,
                    )
                }
                android.widget.Toast.makeText(
                    app,
                    if (newValue) "Keyboard button → keyboard on the glasses"
                    else "Keyboard button → use phone as a keyboard",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
            onCustomize = {
                showKeyboardMenu.value = false
                app.startActivity(
                    android.content.Intent(app, com.portalpad.app.ui.settings.ButtonCustomizeActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("target", "keyboard"),
                )
            },
            onReorder = {
                showKeyboardMenu.value = false
                navReorderMode = true
            },
            onDismiss = { showKeyboardMenu.value = false },
        )
    }

    if (showAppDrawer) {
        com.portalpad.app.ui.trackpad.AppDrawerSheet(
            onLaunch = { entry -> launchEntry(entry, injector) },
            onDismiss = { showAppDrawer = false },
        )
    }

    if (showQuickWheel) {
        com.portalpad.app.ui.trackpad.QuickWheelOverlay(
            slots = wheelSlots,
            onLaunchSlot = { entry ->
                showQuickWheel = false
                launchEntry(entry, injector)
            },
            onAssignSlot = { index ->
                showQuickWheel = false
                app.startActivity(
                    android.content.Intent(app, com.portalpad.app.ui.settings.AppPickerActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("target", "wheel:$index"),
                )
            },
            onOpenDrawer = {
                showQuickWheel = false
                showAppDrawer = true
            },
            onDismiss = { showQuickWheel = false },
        )
    }

    if (showWindowWheel) {
        com.portalpad.app.ui.trackpad.WindowRadialOverlay(
            onDismiss = { showWindowWheel = false },
        )
    }

    longPressMenuTarget?.let { target ->
        ButtonLongPressMenu(
            target = target,
            onAssign = {
                longPressMenuTarget = null
                app.startActivity(
                    android.content.Intent(app, com.portalpad.app.ui.settings.AppPickerActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("target", target),
                )
            },
            onCustomize = {
                longPressMenuTarget = null
                app.startActivity(
                    android.content.Intent(app, com.portalpad.app.ui.settings.ButtonCustomizeActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("target", target),
                )
            },
            onRecenter = {
                longPressMenuTarget = null
                injector.recenterCursor()
            },
            onReorder = {
                longPressMenuTarget = null
                navReorderMode = true
            },
            onDismiss = { longPressMenuTarget = null },
        )
    }

    // When the external display disconnects, dismiss the radial + app-drawer
    // overlays. They're Dialogs (their own window, above the activity) and they
    // act on the external display's windows/apps — useless once it's gone — so
    // otherwise they sit ON TOP of the DisconnectBanner below and hide it.
    // Closing them lets the banner (rendered in the activity) come through.
    androidx.compose.runtime.LaunchedEffect(externalDisplayId) {
        if (externalDisplayId == null) {
            showWindowWheel = false
            showQuickWheel = false
        }
    }

    // External-display disconnect banner. Purely visual: the actual return to the
    // main screen is handled by the auto-finish watcher in onCreate (5s grace so a
    // screen-off flap doesn't kick the user out). This shows a centered card with a
    // depleting countdown bar the moment the display goes null, and slides away if
    // the display reconnects within the grace window. Driven off the same
    // externalDisplayId signal the watcher uses, so timing stays aligned.
    DisconnectBanner(externalDisplayId = externalDisplayId)
}

/**
 * Centered "External Display Disconnected" card shown over the trackpad / air-mouse
 * / remote interface while the display is gone. A thin bar depletes left over the
 * grace window. If the display reconnects within the window (e.g. a screen-off
 * flap), the card flips to an amber "reconnected" state briefly, then dismisses —
 * the user is NOT returned to the main screen. A real unplug leaves the display
 * null; the auto-finish watcher (onCreate) returns to MainActivity as the bar
 * empties. This composable is purely the visual; it doesn't call finish().
 */
@Composable
private fun DisconnectBanner(externalDisplayId: Int?) {
    // Grace window — keep in sync with the auto-finish watcher's goneGraceMs.
    val graceMs = 5000
    // Only engage after we've actually seen a display, so the initial null at
    // launch doesn't flash the banner.
    var sawDisplay by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(externalDisplayId) {
        if (externalDisplayId != null) sawDisplay = true
    }
    val disconnected = sawDisplay && externalDisplayId == null

    // Reconnect flash: when we were disconnected and the display returns, show a
    // brief amber "reconnected" state before dismissing.
    var showReconnected by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var visible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val progress = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }

    androidx.compose.runtime.LaunchedEffect(disconnected) {
        if (disconnected) {
            showReconnected = false
            visible = true
            progress.snapTo(1f)
            // Deplete to 0 over the grace window.
            progress.animateTo(
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = graceMs,
                    easing = androidx.compose.animation.core.LinearEasing,
                ),
            )
            // Bar emptied and we're still disconnected → the watcher is returning
            // to main; keep the card up through the transition.
        } else if (visible) {
            // Reconnected within the window — flash amber, then dismiss.
            showReconnected = true
            kotlinx.coroutines.delay(850)
            visible = false
            showReconnected = false
        }
    }

    if (!visible) return

    val danger = com.portalpad.app.ui.theme.AbDanger
    val warning = com.portalpad.app.ui.theme.AbWarning
    val accent = if (showReconnected) warning else danger

    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFF080510).copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier
                .fillMaxWidth(0.78f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .background(com.portalpad.app.ui.theme.AbSurface)
                .border(
                    1.dp,
                    accent.copy(alpha = 0.45f),
                    androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.foundation.layout.Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp, bottom = 16.dp, start = 20.dp, end = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = if (showReconnected) {
                        androidx.compose.material.icons.Icons.Default.CheckCircle
                    } else {
                        androidx.compose.material.icons.Icons.Default.Close
                    },
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.Text(
                    if (showReconnected) "Display Reconnected" else "External Display Disconnected",
                    color = com.portalpad.app.ui.theme.AbOnSurface,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.Text(
                    if (showReconnected) "Staying on this screen" else "Returning to main screen…",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            // Depleting countdown bar (full → empty, shrinking left). Frozen on the
            // reconnect flash.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(accent.copy(alpha = 0.18f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(if (showReconnected) 1f else progress.value)
                        .background(accent),
                )
            }
        }
    }
}

@Composable
private fun ButtonLongPressMenu(
    target: String,
    onAssign: () -> Unit,
    onCustomize: () -> Unit,
    onRecenter: () -> Unit,
    onReorder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (target) {
        "home" -> "Home Button"
        "appdrawer" -> "App Drawer"
        else -> "Back Button"
    }
    androidx.compose.ui.window.Popup(
        // Anchor near the bottom of the screen, horizontally centered, then lift
        // the menu up so it floats just ABOVE the bottom button row — close to
        // where the user's finger already is.
        alignment = androidx.compose.ui.Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -260),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier
                .width(230.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .background(com.portalpad.app.ui.theme.AbSurface)
                .border(
                    1.dp,
                    com.portalpad.app.ui.theme.AbPrimaryDim,
                    androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                )
                .padding(8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            androidx.compose.material3.Text(
                title,
                color = com.portalpad.app.ui.theme.AbOnSurfaceMuted,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 2.dp),
            )
            // App drawer has a fixed action (opens the wheel/drawer), so it only
            // gets appearance + recenter — no "Assign button action".
            if (target != "appdrawer") MenuRow("Assign button action", onAssign)
            MenuRow("Customize appearance", onCustomize)
            MenuRow("Recenter Cursor", onRecenter)
            MenuRow("Reorder buttons", onReorder)
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    androidx.compose.material3.Text(
        label,
        color = com.portalpad.app.ui.theme.AbPrimaryBright,
        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(com.portalpad.app.ui.theme.AbSurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

/** Keyboard pill long-press menu — mirrors [ButtonLongPressMenu]'s style. */
@Composable
private fun KeyboardLongPressMenu(
    keyboardOnExternal: Boolean,
    onAndroidKeyboardSettings: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onShowKeyboardOnExternal: () -> Unit,
    onCustomize: () -> Unit,
    onReorder: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Popup(
        alignment = androidx.compose.ui.Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -260),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier
                .width(280.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .background(com.portalpad.app.ui.theme.AbSurface)
                .border(
                    1.dp,
                    com.portalpad.app.ui.theme.AbPrimaryDim,
                    androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                )
                .padding(8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            androidx.compose.material3.Text(
                "Keyboard",
                color = com.portalpad.app.ui.theme.AbOnSurfaceMuted,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 2.dp),
            )
            MenuRow("Android Keyboard Settings", onAndroidKeyboardSettings)
            MenuRow("Switch Keyboard", onSwitchKeyboard)
            MenuRow(
                "Show Keyboard on External Display:  " +
                    if (keyboardOnExternal) "On" else "Off",
                onShowKeyboardOnExternal,
            )
            MenuRow("Customize appearance", onCustomize)
            MenuRow("Reorder buttons", onReorder)
        }
    }
}

/** Builds e.g. "HDMI (1920×1080)" from the actual Display for the given id. */
private fun resolveDisplayLabel(app: PortalPadApp, id: Int): String? {
    val dm = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val display = dm.getDisplay(id) ?: return null
    val name = display.name.ifBlank { "External" }
    val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
    return "$name (${metrics.widthPixels}×${metrics.heightPixels})"
}

internal fun launchEntry(entry: com.portalpad.app.data.AppEntry, injector: InputInjector) {
    val app = PortalPadApp.instance

    // ─── Phone shortcut (Tasker task / app shortcut) ─────────────────────────
    // If this entry carries a shortcut Intent URI, it is meant to fire on the
    // PHONE screen — not the external display. Parse the stored Intent and launch
    // it via the same cascade proven in the picker: try as an activity, then as a
    // broadcast (Tasker's IntentHandler receives these), then as a service.
    if (entry.isShortcut) {
        val uri = entry.shortcutUri
        if (uri == null) return
        val shortcutIntent = runCatching {
            android.content.Intent.parseUri(uri, 0)
        }.getOrNull() ?: run {
            android.util.Log.w("LaunchEntry", "Could not parse shortcut URI for ${entry.label}")
            return
        }
        val flagged = android.content.Intent(shortcutIntent)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(flagged) }
            .recoverCatching { app.sendBroadcast(shortcutIntent) }
            .recoverCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    app.startForegroundService(shortcutIntent)
                else app.startService(shortcutIntent)
            }
            .onFailure { android.util.Log.w("LaunchEntry", "Shortcut launch failed for ${entry.label}", it) }
        return
    }

    // Resolve the component to launch.
    val launchIntent = if (entry.isActivity) {
        android.content.Intent().apply {
            setClassName(entry.packageName, entry.componentName!!)
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        }
    } else {
        app.packageManager.getLaunchIntentForPackage(entry.packageName)
            ?.apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
            }
    } ?: return

    val component = launchIntent.component?.flattenToString() ?: "${entry.packageName}/${entry.componentName}"

    // ─── Primary path: when an external display is attached AND we have
    // privilege, launch through the SAME proven path the dock and search use
    // (PortalPadApp.launchAppOnExternal). This was the fix for Home/Back buttons
    // silently launching the app into the phone's BACKGROUND (it would then
    // surface only when the trackpad was disabled). launchAppOnExternal resolves
    // the external display id authoritatively and never falls back to a phone
    // launch, so the app reliably lands on the glasses.
    // Authoritatively re-resolve the live external display BEFORE choosing where
    // to launch — a stale cached id (e.g. after a screen-off VD flap) must not
    // make the cross-display path no-op and drop the launch onto the phone. This
    // also refreshes the cache so launchAppOnExternal (which reads it) sees the
    // fresh id.
    val liveExternalId = app.resolveLiveExternalDisplayId()
    if (liveExternalId != null && app.access.isReady) {
        app.launchAppOnExternal(component)
        // Auto-return to trackpad if the pref is set.
        val autoReturn = runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.bool(
                    com.portalpad.app.data.PreferencesRepository.Keys.AUTO_LAUNCH_AFTER_MOVE,
                    default = false,
                ).first()
            }
        }.getOrDefault(false)
        if (autoReturn) {
            app.startActivity(
                android.content.Intent(app, TrackpadActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return
    }

    // ─── No-privilege guard. If an external display IS attached but we have no
    // working privilege (no Shizuku/Root bound — the None/Accessibility case
    // counts, since Accessibility can't do cross-display launches either), a
    // cross-display launch is impossible: Android forbids a normal app from
    // using ActivityOptions.setLaunchDisplayId to another display
    // (SecurityException), and the attempt ends up dumping the app on the PHONE.
    // That was the real cause of "Home/Back launched on the phone": privilege
    // had dropped / was never bound. Rather than silently misfire, tell the user
    // and route them to set up privilege — do NOT launch.
    // ─── Fallback (no external display at all): resolve a display id defensively
    // and try the legacy launch. This keeps Home/Back working in the no-glasses
    // case (launches on the phone, which is the only place it can go).
    val displayId = injector.displayId.takeIf { it != Display.DEFAULT_DISPLAY }
        ?: app.externalDisplayId.value
        ?: injector.displayId

    android.util.Log.d(
        "LaunchEntry",
        "resolve displayId=$displayId (injector=${injector.displayId}, " +
            "ext=${app.externalDisplayId.value}, accessReady=${app.access.isReady}) " +
            "for ${entry.packageName}",
    )

    // ─── Path 1: ActivityOptions.setLaunchDisplayId — pure Android API.
    // Only used when we DON'T have privilege; when Shizuku/Root is available we
    // prefer the shell path (Path 2) which reliably re-targets the display for
    // already-running apps too. Running both would launch twice.
    var launchedToExternal = false
    if (!app.access.isReady && displayId != Display.DEFAULT_DISPLAY) {
        try {
            val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            app.startActivity(launchIntent, opts.toBundle())
            android.util.Log.d("LaunchEntry", "Launched $component via ActivityOptions on display=$displayId")
            showClickToast(app, "Launch → display=$displayId (ActivityOptions): ${entry.packageName}")
            launchedToExternal = true
        } catch (t: Throwable) {
            android.util.Log.w("LaunchEntry", "ActivityOptions launch failed, will try shell", t)
        }
    }

    // ─── Path 2: shell launch via Shizuku/Root. We run this whenever we have
    // privilege — not only as a fallback — because for an ALREADY-RUNNING app,
    // Path 1 can "succeed" yet surface the app on the phone (its existing
    // task's current display). The shell `am start --display` reliably
    // re-targets the window to the external display for both new and
    // already-open apps, which is the behavior that works on this hardware.
    if (app.access.isReady && displayId != Display.DEFAULT_DISPLAY) {
        try {
            app.access.startActivityOnDisplay(component, displayId)
            android.util.Log.d("LaunchEntry", "Launched/re-targeted $component via shell on display=$displayId")
            launchedToExternal = true
        } catch (t: Throwable) {
            android.util.Log.w("LaunchEntry", "Shell launch failed", t)
        }
    }

    // ─── Path 2b: system-context push via moveFocusedTaskToDisplay.
    // This is the mechanism that actually relocates an app to the external
    // display — especially an app that was ALREADY running (its task may get
    // brought forward on the phone instead of the external display). We retry
    // as focus settles, and use whichever privileged backend is active (root
    // OR Shizuku), not just Shizuku.
    if (launchedToExternal && displayId != Display.DEFAULT_DISPLAY) {
        val backend = app.activeBoundBackend
        if (backend?.isReady == true) {
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            for (delay in listOf(300L, 700L, 1200L)) {
                h.postDelayed({
                    runCatching { backend.moveFocusedTaskToDisplay(displayId) }
                }, delay)
            }
        }
    }

    // ─── Path 3: regular app.startActivity (local display only)
    // ─── No phone fallback. Every launch through THIS resolver is
    // external-intended (Settings / desktop top-bar / phone shortcuts pin
    // themselves to the phone at their own call sites and never reach here). So
    // if we couldn't place it on an external display — no display live, or no
    // privilege to cross-display launch — we ABORT with visible feedback rather
    // than silently dumping the app on the phone. This is the fix for "apps
    // sometimes launch on the phone": the phone is no longer a destination here.
    if (!launchedToExternal) {
        android.util.Log.w(
            "LaunchEntry",
            "ABORT (not launching on phone): ${entry.packageName} " +
                "(displayId=$displayId, accessReady=${app.access.isReady}, " +
                "live=$liveExternalId, ext=${app.externalDisplayId.value})",
        )
        showClickToast(
            app,
            if (app.access.isReady) "External display not found — ${entry.packageName} not launched"
            else "Set up Shizuku/Root to launch on the external display",
        )
    }

    // Auto-return to trackpad if pref on
    if (launchedToExternal) {
        val autoReturnToTrackpad = runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.bool(
                    com.portalpad.app.data.PreferencesRepository.Keys.AUTO_LAUNCH_AFTER_MOVE,
                    default = false,
                ).first()
            }
        }.getOrDefault(false)
        if (autoReturnToTrackpad) {
            app.startActivity(
                android.content.Intent(app, TrackpadActivity::class.java)
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
            )
        }
    }
}

/**
 * Surfaces a brief toast on the phone screen when the user has the diagnostic
 * "Show click debug toasts" pref enabled. Mirrors the helper inside
 * [InputInjector] but for launch events (which happen from the trackpad scope).
 */
private fun showClickToast(app: PortalPadApp, message: String) {
    val show = runCatching {
        kotlinx.coroutines.runBlocking {
            app.prefs.bool(
                com.portalpad.app.data.PreferencesRepository.Keys.SHOW_CLICK_TOASTS,
                default = false,
            ).first()
        }
    }.getOrDefault(false)
    if (!show) return
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        runCatching {
            android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Capture the external display via Shizuku-elevated `screencap`, copy
 * the resulting PNG into our app's cache, and open the system share
 * sheet. Designed for the trackpad's "screenshot" top-bar button so
 * users can quickly share what's rendering on the glasses.
 *
 * Why shell `screencap`? Because the rendered content lives on the
 * external display (or our VD wrapping it). Snapshotting our own
 * SurfaceView via PixelCopy would also work but only after the surface
 * has been drawn; `screencap -d N` is simple and works regardless of
 * window-layer issues.
 *
 * @param targetDisplayId  The display whose pixels we want. Pass the
 *                          VD id when we're wrapping (so we get the
 *                          composited content Chrome rendered onto the
 *                          VD); fall back to the physical display id
 *                          otherwise.
 */
private fun takeExternalDisplayScreenshot(
    app: PortalPadApp,
    targetDisplayId: Int?,
) {
    val physicalId = targetDisplayId
    val vdId = app.virtualDisplayId
    if (physicalId == null && vdId == null) {
        android.widget.Toast.makeText(
            app, "No external display to screenshot", android.widget.Toast.LENGTH_SHORT,
        ).show()
        return
    }

    val backend = app.clickBackend as? com.portalpad.app.service.ClickBackend.ShizukuUserService
    if (backend == null || !backend.backend.isReady) {
        android.widget.Toast.makeText(
            app, "Screenshot needs Shizuku to be connected", android.widget.Toast.LENGTH_SHORT,
        ).show()
        return
    }

    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val TAG = "Screenshot"
        try {
            val ts = System.currentTimeMillis()
            // Write directly into our app's own external-files directory.
            // Shell (the Shizuku UserService uid) can write under
            // /sdcard/Android/data/<pkg>/files because /sdcard is world-
            // accessible at the FS layer for shell, and our app can read
            // its OWN external-files dir without extra permissions. This
            // avoids two problems we hit before:
            //   • /data/local/tmp is shell-owned and unreadable by our uid
            //   • returning base64 over binder blows the ~1MB transaction
            //     limit (DeadObjectException "running out of binder buffer")
            val appExtDir = java.io.File(app.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            val devicePath = "${appExtDir.absolutePath}/portalpad_screen_$ts.png"

            // screencap's `-d` flag wants the SurfaceFlinger PHYSICAL display
            // id — a large 64-bit token like 4619827259835644672 — NOT the
            // logical display id (59, 60) we use elsewhere. Logical ids make
            // screencap fail with "Display Id 'N' is not valid."
            //
            // `dumpsys SurfaceFlinger --display-id` lists the physical
            // display tokens. We want the EXTERNAL one (the glasses), which
            // is the second entry (the first is the phone's internal panel).
            // Output looks like:
            //   Display 4619827259835644672 (HWC display 0): ...
            //   Display 4619827259835644673 (HWC display 1): ...
            val sfDump = backend.backend.runCommand("dumpsys SurfaceFlinger --display-id").trim()
            android.util.Log.d(TAG, "SurfaceFlinger display-id dump:\n$sfDump")
            val sfIds = Regex("Display (\\d+)").findAll(sfDump).map { it.groupValues[1] }.toList()
            android.util.Log.d(TAG, "parsed SF display ids: $sfIds")

            // Build the capture-target list. Prefer the EXTERNAL physical
            // display (usually the 2nd SF id when glasses are attached);
            // fall back to capturing with no -d (the primary display) so the
            // button always produces SOMETHING.
            val captureCmds = buildList {
                // External physical display = last SF id if there's more than one
                if (sfIds.size >= 2) {
                    val externalSfId = sfIds.last()
                    add("external-SF($externalSfId)" to "screencap -d $externalSfId -p '$devicePath'")
                }
                // Each individual SF id as a fallback
                sfIds.forEach { id ->
                    add("SF($id)" to "screencap -d $id -p '$devicePath'")
                }
                // Last resort: no display flag (primary display)
                add("default" to "screencap -p '$devicePath'")
            }

            var captured = false
            var lastErr = ""
            for ((label, baseCmd) in captureCmds) {
                val cmd = "$baseCmd 2>&1; echo \"RC=$?\""
                val out = backend.backend.runCommand(cmd).trim()
                // chmod so our app uid can read the shell-written file.
                backend.backend.runCommand("chmod 666 '$devicePath' 2>/dev/null")
                val f = java.io.File(devicePath)
                val size = if (f.exists()) f.length() else 0L
                android.util.Log.d(TAG, "attempt $label → size=$size out='$out'")
                if (size > 0L) {
                    captured = true
                    android.util.Log.d(TAG, "captured via $label")
                    break
                }
                lastErr = out
                backend.backend.runCommand("rm -f '$devicePath'")
            }

            if (!captured) {
                android.util.Log.w(TAG, "all capture attempts failed: $lastErr")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        app,
                        "Screenshot failed: $lastErr",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                return@launch
            }

            // Read the bytes directly via File API — NO binder transfer of
            // pixel data (that's what blew the transaction limit before).
            val bytes = try {
                java.io.File(devicePath).readBytes()
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "file read failed", t)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        app, "Screenshot failed: couldn't read file (${t.message})",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                return@launch
            }
            android.util.Log.d(TAG, "read ${bytes.size} bytes from $devicePath")

            // Clean up the device-side staging file now that we have bytes.
            backend.backend.runCommand("rm -f '$devicePath'")
            runCatching { java.io.File(devicePath).delete() }

            // Write PNG to the user's Pictures/PortalPad folder via
            // MediaStore so it shows in Gallery and survives uninstall.
            val filename = "external_display_$ts.png"
            val savedUri: android.net.Uri? = saveScreenshotToPictures(app, bytes, filename)
            if (savedUri == null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        app,
                        "Screenshot saved-failed (couldn't write to Pictures)",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                return@launch
            }

            // Step 5: open share sheet on UI thread. Also a short toast
            // confirming where the file landed so the user can find it
            // later without going through share.
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, savedUri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = android.content.Intent.createChooser(shareIntent, "Share external display screenshot")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    app, "Saved to Pictures/PortalPad", android.widget.Toast.LENGTH_SHORT,
                ).show()
                // Also flash a confirmation ON the external display so the
                // user, who is looking at the glasses (not the phone), gets
                // feedback that the capture happened and where it went.
                flashExternalDisplayMessage(app, "📸 Screenshot saved\nPictures/PortalPad")
                app.startActivity(chooser)
            }
        } catch (t: Throwable) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    app,
                    "Screenshot error: ${t.message ?: t.javaClass.simpleName}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}

/**
 * Save [bytes] (PNG-encoded) as `filename` in the user's
 * `Pictures/PortalPad/` folder.
 *
 * Android 10+ uses MediaStore.Images.Media with the standard
 * RELATIVE_PATH/DISPLAY_NAME contract. Earlier Android versions fall
 * back to direct File API.
 *
 * Returns a content:// or file:// URI suitable for sharing, or null
 * if the write failed.
 */
private fun saveScreenshotToPictures(
    ctx: android.content.Context,
    bytes: ByteArray,
    filename: String,
): android.net.Uri? = runCatching {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val resolver = ctx.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_PICTURES + "/PortalPad",
            )
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
        ) ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: run { resolver.delete(uri, null, null); return@runCatching null }
        // Flip IS_PENDING off so the file becomes visible to other apps.
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    } else {
        // Legacy path — requires WRITE_EXTERNAL_STORAGE granted.
        val dir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES,
            ),
            "PortalPad",
        ).apply { mkdirs() }
        val file = java.io.File(dir, filename)
        file.writeBytes(bytes)
        // Notify the media scanner so it shows up in Gallery.
        android.media.MediaScannerConnection.scanFile(
            ctx, arrayOf(file.absolutePath), arrayOf("image/png"), null,
        )
        androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", file,
        )
    }
}.getOrNull()

/**
 * Flash a short-lived confirmation message on the external display (the
 * glasses), where the user is actually looking. The phone-side Toast
 * isn't visible to someone wearing the glasses, so this gives feedback
 * where it's needed.
 *
 * Implementation: add a TYPE_ACCESSIBILITY_OVERLAY TextView to the VD
 * display's WindowManager (same overlay channel our cursor/dock use),
 * centered, then remove it after ~1.6s. Best-effort — if the overlay
 * can't be added (no display context, etc.) we silently skip; the phone
 * Toast still fired.
 */
private fun flashExternalDisplayMessage(app: PortalPadApp, message: String) {
    runCatching {
        // Target the VD (where content lives) if present, else the physical.
        val displayId = app.virtualDisplayId ?: app.physicalExternalDisplayId.value ?: return
        val dm = app.getSystemService(android.content.Context.DISPLAY_SERVICE)
            as android.hardware.display.DisplayManager
        val display = dm.getDisplay(displayId) ?: return
        val displayContext = app.createDisplayContext(display)
        val wm = displayContext.getSystemService(android.content.Context.WINDOW_SERVICE)
            as android.view.WindowManager

        val tv = android.widget.TextView(displayContext).apply {
            text = message
            setTextColor(android.graphics.Color.WHITE)
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setPadding(48, 32, 48, 32)
            // Rounded translucent dark pill.
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 36f
                setColor(android.graphics.Color.parseColor("#E0000000"))
            }
        }

        val type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // TYPE_ACCESSIBILITY_OVERLAY = 2032; falls back to APPLICATION_OVERLAY
            // if our a11y service isn't bound. Use 2032 directly — we already
            // add cursor/dock overlays this way.
            2032
        } else {
            @Suppress("DEPRECATION")
            android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }

        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.CENTER
        }

        wm.addView(tv, lp)
        // Remove after a short delay.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { wm.removeView(tv) }
        }, 1600)
    }
}

/**
 * One-time explainer for the screenshot/record capture button. Shown on the very
 * first tap (or long-press) of that button; teaches tap=screenshot / hold=record
 * and lets the user act immediately via the two buttons. After it's dismissed the
 * gestures work directly (gated by the CAPTURE_INTRO_SHOWN pref). Renders on the
 * phone, so it's a plain Compose Dialog.
 */
@androidx.compose.runtime.Composable
private fun CaptureIntroDialog(
    onDismiss: () -> Unit,
    onScreenshot: () -> Unit,
    onRecord: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(com.portalpad.app.ui.theme.AbSurfaceElevated)
                .border(
                    0.5.dp,
                    com.portalpad.app.ui.theme.AbPrimary.copy(alpha = 0.25f),
                    androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                )
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(com.portalpad.app.ui.theme.AbPrimary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(
                        com.portalpad.app.R.drawable.ic_screenshot,
                    ),
                    contentDescription = null,
                    tint = com.portalpad.app.ui.theme.AbPrimaryBright,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.Text(
                "Screen capture",
                color = com.portalpad.app.ui.theme.AbOnSurface,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            )
            Spacer(Modifier.height(14.dp))

            CaptureIntroRow(
                tintColor = com.portalpad.app.ui.theme.AbPrimaryBright,
                tintBg = com.portalpad.app.ui.theme.AbPrimary,
                useDot = false,
                label = "Tap",
                desc = "Take a screenshot of the external display.",
            )
            CaptureIntroRow(
                tintColor = com.portalpad.app.ui.theme.AbDanger,
                tintBg = com.portalpad.app.ui.theme.AbDanger,
                useDot = true,
                label = "Press and hold",
                desc = "Record the external display.",
            )

            Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(9.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .background(com.portalpad.app.ui.theme.AbPrimary)
                        .clickable { onScreenshot() },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Text(
                        "Take screenshot",
                        color = com.portalpad.app.ui.theme.AbOnSurface,
                        fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                }
                androidx.compose.foundation.layout.Row(
                    Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .border(
                            0.5.dp,
                            com.portalpad.app.ui.theme.AbPrimary.copy(alpha = 0.5f),
                            androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                        )
                        .clickable { onRecord() },
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(com.portalpad.app.ui.theme.AbDanger),
                    )
                    Spacer(Modifier.width(6.dp))
                    androidx.compose.material3.Text(
                        "Screen recording",
                        color = com.portalpad.app.ui.theme.AbPrimaryBright,
                        fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Text(
                "Shown once. Tap or hold the button anytime.",
                color = com.portalpad.app.ui.theme.AbOnSurfaceMuted.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun CaptureIntroRow(
    tintColor: androidx.compose.ui.graphics.Color,
    tintBg: androidx.compose.ui.graphics.Color,
    useDot: Boolean,
    label: String,
    desc: String,
) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(tintBg.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            if (useDot) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(tintColor),
                )
            } else {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(
                        com.portalpad.app.R.drawable.ic_screenshot,
                    ),
                    contentDescription = null,
                    tint = tintColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            androidx.compose.material3.Text(
                label,
                color = com.portalpad.app.ui.theme.AbOnSurface,
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            )
            androidx.compose.material3.Text(
                desc,
                color = com.portalpad.app.ui.theme.AbOnSurfaceMuted,
                fontSize = 13.sp,
            )
        }
    }
}
