package com.portalpad.app.service

import android.os.ParcelFileDescriptor
import android.util.Log
import com.portalpad.app.PortalPadApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 1 physical-mouse capture (app side).
 *
 * A real Bluetooth/USB mouse normally drives only the phone's system pointer.
 * This routes it to PortalPad's external-display cursor instead: the privileged
 * [ShellUserService] opens the device, (tries to) EVIOCGRAB it, and streams
 * 16-byte delta records over a pipe; here we read them and feed the same
 * [InputInjector.pointerMove] the trackpad/air-mouse already use.
 *
 * Phase 1 is movement only — buttons and scroll are captured into the record by
 * native but ignored here. The capture also reports whether the grab succeeded,
 * which is the spike's whole purpose: it tells us if the shell domain can
 * exclusively grab input on this device (screen-off capable) or if that needs
 * root on this hardware.
 */
class BluetoothMouseController(private val injector: InputInjector) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var job: Job? = null
    /** Bounded re-arm poller: retries enable() while a mouse is expected but not
     *  yet enumerated (BT settling on connect, or the mouse waking from sleep). */
    @Volatile private var pollJob: Job? = null
    /** Monotonic capture-session id. disable() records the session it stops in
     *  [stoppedSessionId]; the read loop uses the mismatch to tell a deliberate
     *  stop (no self-heal) from the device going away on its own (self-heal). */
    @Volatile private var sessionId = 0
    @Volatile private var stoppedSessionId = -1

    /** Linear sensitivity multiplier applied to raw mouse deltas (live from prefs). */
    @Volatile private var sensitivity: Float = DEFAULT_SENSITIVITY
    /** Mouse-specific wheel speed multiplier (live from prefs). */
    @Volatile private var scrollSpeed: Float = DEFAULT_SCROLL_SPEED
    /** Reverse the wheel direction (for devices whose wheel sign is inverted). */
    @Volatile private var reverseScroll: Boolean = false
    /** User opted into the external mouse (persisted) — drives auto-resume. */
    @Volatile private var userEnabled: Boolean = false
    /** Persisted exclusive-grab preference, used when auto-arming. */
    @Volatile private var userGrab: Boolean = true

    init {
        // Keep mouse settings in sync with prefs so sliders/toggles take effect
        // immediately, even between enable/disable cycles.
        val keys = com.portalpad.app.data.PreferencesRepository.Keys
        scope.launch {
            PortalPadApp.instance.prefs.float(keys.EXT_MOUSE_SENSITIVITY, DEFAULT_SENSITIVITY)
                .collect { sensitivity = if (it > 0f) it else DEFAULT_SENSITIVITY }
        }
        scope.launch {
            PortalPadApp.instance.prefs.float(keys.EXT_MOUSE_SCROLL_SPEED, DEFAULT_SCROLL_SPEED)
                .collect { scrollSpeed = if (it > 0f) it else DEFAULT_SCROLL_SPEED }
        }
        scope.launch {
            PortalPadApp.instance.prefs.bool(keys.EXT_MOUSE_NATURAL_SCROLL, false)
                .collect { reverseScroll = it }
        }
        scope.launch {
            PortalPadApp.instance.prefs.bool(keys.EXT_MOUSE_ENABLED, false)
                .collect {
                    userEnabled = it
                    // Opted out → stop any in-flight re-arm polling.
                    if (!it) { pollJob?.cancel(); pollJob = null }
                }
        }
        scope.launch {
            PortalPadApp.instance.prefs.bool(keys.EXT_MOUSE_GRAB, true)
                .collect { userGrab = it }
        }
    }

    /**
     * Auto-resume: if the user opted in, capture isn't already running, and an
     * external display is present, (re)arm capture. Called from the external
     * display-connect and Bluetooth-connect hooks so an opted-in user doesn't have
     * to re-toggle each session. No-ops (silently) when not opted in, already
     * running, no external display, or no mouse present (enable() returns ERR).
     * [delayMs] lets the display-connect path wait for the display to finish
     * setting up before discovery runs.
     */
    fun maybeAutoArm(delayMs: Long = 0L) {
        if (!userEnabled) return
        scope.launch {
            if (delayMs > 0L) kotlinx.coroutines.delay(delayMs)
            if (!userEnabled || running) return@launch
            if (PortalPadApp.instance.injector.displayId == 0) return@launch // no external display
            val st = enable(userGrab)
            Log.i(TAG, "auto-arm → $st")
        }
    }

    /** Last status string returned by the privileged start call (for the UI). */
    @Volatile var lastStatus: String = "idle"
        private set
    @Volatile var running: Boolean = false
        private set

    /** Start capture. [grab] requests exclusive access (suppresses the phone's
     *  own pointer). Returns the status string (also stored in [lastStatus]). */
    @Synchronized
    fun enable(grab: Boolean = true): String {
        // Internal teardown only — does NOT cancel the re-arm poller (this is
        // often called BY the poller). Public disable() handles opt-out.
        stopCapture()
        val backend = PortalPadApp.instance.activeBoundBackend
        if (backend == null || !backend.isReady) {
            lastStatus = "ERR no privileged backend (select Shizuku or Root)"
            return lastStatus
        }
        val pipe = ParcelFileDescriptor.createPipe() // [read, write]
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val libDir = PortalPadApp.instance.applicationInfo.nativeLibraryDir
        val status = try {
            backend.startMouseCapture(grab, libDir, writeEnd)
        } catch (t: Throwable) {
            Log.w(TAG, "startMouseCapture threw", t)
            "ERR ${t.message}"
        } finally {
            // The service dup'd its own copy across the binder; release ours so
            // EOF propagates correctly when the service stops.
            runCatching { writeEnd.close() }
        }
        lastStatus = status
        Log.i(TAG, "enable(grab=$grab) → $status")
        if (status.startsWith("ERR")) {
            runCatching { readEnd.close() }
            // "no-mouse-found" means the device isn't enumerated YET (BT still
            // settling, or the mouse is asleep) — keep looking for a bit so it
            // arms on its own when the mouse appears. Other errors are hard
            // failures that won't fix themselves by waiting, so don't poll them.
            if (userEnabled && status.contains("no-mouse-found")) startRearmPoller("enable-miss")
            return status
        }
        running = true
        val mySession = ++sessionId
        job = scope.launch { readLoop(readEnd, mySession) }
        return status
    }

    private suspend fun readLoop(readEnd: ParcelFileDescriptor, mySession: Int) {
        val input = ParcelFileDescriptor.AutoCloseInputStream(readEnd)
        val buf = ByteArray(20)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        var lastButtons = 0
        // True when the loop ended because the device's stream hit EOF (mouse
        // gone / asleep) rather than a deliberate stop — gates the self-heal.
        var endedByEof = false
        // Held modifier meta (Shift/Ctrl/Alt/Meta) for the keyboard relay — reset
        // per session so a modifier can't get "stuck" across reconnects.
        var heldMeta = 0
        // Left-button click-vs-drag state (per session).
        var leftHeld = false
        var leftDragging = false
        var dragResolved = false
        var dragAccX = 0f
        var dragAccY = 0f
        try {
            while (currentCoroutineContext().isActive) {
                var off = 0
                while (off < 20) {
                    val n = input.read(buf, off, 20 - off)
                    if (n < 0) { endedByEof = true; return } // EOF — service stopped or device gone
                    off += n
                }
                // Tagged 20-byte records: type 0 = mouse, type 1 = keyboard key.
                // The keyboard half of a combo device rides the SAME grabbed stream.
                // Map the Linux code to an Android keycode, track held modifiers,
                // and inject on the external display with the right meta so capitals,
                // symbols and chords (Ctrl+A, etc.) compose. Unmapped codes drop.
                val type = bb.getInt(0)
                if (type == 1) {
                    val linux = bb.getInt(4)
                    val down = bb.getInt(8) != 0
                    KeyboardRelayMap.modifierMeta(linux)?.let { bits ->
                        heldMeta = if (down) heldMeta or bits else heldMeta and bits.inv()
                    }
                    KeyboardRelayMap.toAndroid(linux)?.let { androidCode ->
                        Log.d(TAG, "KEY linux=$linux→android=$androidCode down=$down meta=$heldMeta")
                        injector.injectKeyEvent(androidCode, down, heldMeta)
                    }
                    continue
                }
                val dx = bb.getInt(4)
                val dy = bb.getInt(8)
                val buttons = bb.getInt(12)
                val wheel = bb.getInt(16)

                val s = sensitivity
                val sdx = dx * s
                val sdy = dy * s
                val pressed = buttons and lastButtons.inv()
                val released = lastButtons and buttons.inv()

                // Left DOWN: begin a pending press (click vs drag decided by
                // movement). Right DOWN: immediate context tap. Middle: unmapped.
                if (pressed and BTN_LEFT != 0) {
                    leftHeld = true; leftDragging = false; dragResolved = false
                    dragAccX = 0f; dragAccY = 0f
                }
                if (pressed and BTN_RIGHT != 0) injector.rightClick()

                // Movement: while left is held, accumulate until it crosses the
                // drag threshold, then promote to a real held touch-drag (window
                // move, or resize when grabbing an edge); below threshold it's
                // just cursor movement, so a quick press/release stays a click.
                if (dx != 0 || dy != 0) {
                    if (leftHeld && leftDragging) {
                        injector.dragMove(sdx, sdy)
                    } else if (leftHeld && !dragResolved) {
                        injector.pointerMove(sdx, sdy)
                        dragAccX += sdx; dragAccY += sdy
                        if (kotlin.math.hypot(dragAccX.toDouble(), dragAccY.toDouble()) > DRAG_THRESHOLD) {
                            dragResolved = true
                            leftDragging = true
                            injector.dragStart()
                        }
                    } else {
                        injector.pointerMove(sdx, sdy)
                    }
                }

                // Left UP: end the drag, or — if it never became one — click.
                if (released and BTN_LEFT != 0) {
                    if (leftDragging) injector.dragEnd() else injector.leftClick()
                    leftHeld = false; leftDragging = false; dragResolved = false
                }

                lastButtons = buttons

                // Wheel → discrete ACTION_SCROLL (no fling): content moves only
                // while the wheel turns. vNotches +1 = up; reverseScroll flips
                // for devices whose wheel reports the opposite sign.
                if (wheel != 0) {
                    var v = wheel * scrollSpeed
                    if (reverseScroll) v = -v
                    injector.wheelScroll(v)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "mouse read loop ended", t)
        } finally {
            runCatching { input.close() }
            running = false
            // Self-heal: if the DEVICE went away on its own (EOF) — not a stop we
            // initiated (which records this session in stoppedSessionId) — and the
            // user is still opted in with an external display, poll to re-arm so
            // the mouse reconnects on wake without toggling anything.
            if (endedByEof && stoppedSessionId != mySession && userEnabled &&
                PortalPadApp.instance.injector.displayId != 0
            ) {
                startRearmPoller("device-gone")
            }
        }
    }

    @Synchronized
    fun disable() {
        // User opt-out: stop polling too, then tear the capture down.
        pollJob?.cancel()
        pollJob = null
        stopCapture()
    }

    /** Tear down the active capture without touching the re-arm poller. Records
     *  the current session as deliberately stopped so its read loop doesn't
     *  self-heal. Used by both [enable] (re-arm) and [disable] (opt-out). */
    @Synchronized
    private fun stopCapture() {
        stoppedSessionId = sessionId
        job?.cancel()
        job = null
        running = false
        runCatching { PortalPadApp.instance.activeBoundBackend?.stopMouseCapture() }
        if (lastStatus != "idle" && !lastStatus.startsWith("ERR")) lastStatus = "stopped"
    }

    /**
     * Re-arm poller: while the user is opted in and an external display is
     * present but no capture is running, retry [enable] on an interval until a
     * mouse is found (BT finished settling / the mouse woke), the user opts out,
     * the display goes away, or the bounded window elapses. Started on a
     * no-mouse-found arm attempt and on an unsolicited device-gone EOF; only one
     * runs at a time. Keeps polling only while the result is still
     * "no-mouse-found" — a hard error won't fix itself by waiting.
     */
    private fun startRearmPoller(reason: String) {
        synchronized(this) {
            if (!userEnabled || running) return
            if (PortalPadApp.instance.injector.displayId == 0) return
            if (pollJob?.isActive == true) return
            pollJob = scope.launch {
                Log.i(TAG, "rearm poll start ($reason)")
                val deadline = System.currentTimeMillis() + REARM_WINDOW_MS
                while (System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(REARM_INTERVAL_MS)
                    if (!userEnabled || PortalPadApp.instance.injector.displayId == 0) {
                        Log.i(TAG, "rearm poll stop (opted out / no display)")
                        return@launch
                    }
                    if (running) return@launch
                    val st = enable(userGrab)
                    if (!st.startsWith("ERR")) {
                        Log.i(TAG, "rearm poll → armed ($st)")
                        return@launch
                    }
                    if (!st.contains("no-mouse-found")) {
                        Log.i(TAG, "rearm poll stop (hard error: $st)")
                        return@launch
                    }
                }
                Log.i(TAG, "rearm poll window elapsed")
            }
        }
    }

    companion object {
        private const val TAG = "BtMouseController"
        // Re-arm poll: total window to keep looking for the mouse, and the gap
        // between attempts. Bounded so an opted-in user with no mouse paired
        // doesn't spawn discovery forever.
        private const val REARM_WINDOW_MS = 60_000L
        private const val REARM_INTERVAL_MS = 1_500L
        private const val DEFAULT_SENSITIVITY = 2.5f
        private const val DEFAULT_SCROLL_SPEED = 1.0f
        // Cursor-space pixels of movement (post-sensitivity) before a held-left
        // press promotes from a click to a drag.
        private const val DRAG_THRESHOLD = 10.0
        private const val BTN_LEFT = 1   // record bit 0
        private const val BTN_RIGHT = 2  // record bit 1
        // private const val BTN_MIDDLE = 4 // bit 2 — reserved (Phase 3 mapping)
    }
}
