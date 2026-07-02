package com.portalpad.app.service

import android.os.ParcelFileDescriptor
import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/**
 * Common surface for a bound, privileged [ShellUserService] — regardless of
 * whether it's launched via Shizuku (shell uid 2000) or as root (uid 0) via
 * libsu's RootService. Both backends expose the same operations so the rest of
 * the app (AirGlassesSession, the click pipeline, the VD-wrap gate) can use
 * whichever source the user picked without caring which one it is.
 *
 * Every method is best-effort and returns a safe default if the service isn't
 * bound, so callers don't need to null-check first.
 */
interface BoundShellBackend {
    /** True when the privileged service is bound and its binder is alive. */
    val isReady: Boolean

    /** Emits [isReady] transitions so UI/state can react to bind/unbind. */
    val readyFlow: StateFlow<Boolean>

    /** Start binding the service (idempotent — no-op if already bound). */
    fun bind()

    /** Unbind/stop the service. */
    fun unbind()

    // ─── Input injection ────────────────────────────────────────────────
    fun tap(displayId: Int, x: Float, y: Float): Boolean
    fun longPress(displayId: Int, x: Float, y: Float, durationMs: Long = 700L): Boolean
    fun swipe(displayId: Int, sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long = 80L): Boolean
    /** Two-pointer pinch/zoom centered at (cx,cy). Default false = unsupported by this backend. */
    fun injectPinch(displayId: Int, cx: Float, cy: Float, startSpan: Float, endSpan: Float, durationMs: Long): Boolean = false
    /** Continuous pinch held open across calls (begin → move… → end). Default false = unsupported. */
    fun pinchBegin(displayId: Int, cx: Float, cy: Float, span: Float): Boolean = false
    fun pinchMove(span: Float): Boolean = false
    fun pinchEnd(span: Float): Boolean = false
    fun key(displayId: Int, keyCode: Int): Boolean
    /**
     * Inject a single hardware-keyboard key edge (KeyEvent.ACTION_DOWN/UP) with
     * modifier [metaState] on [displayId]. Used by the physical-keyboard relay:
     * unlike [key] (a complete press, no meta), this forwards the literal down/up
     * and meta so Shift/Ctrl/Alt, capitals, symbols and held keys compose.
     */
    fun injectKey(keyCode: Int, action: Int, metaState: Int, displayId: Int): Boolean
    fun gamepadKey(displayId: Int, keyCode: Int): Boolean
    fun pointer(action: Int, x: Float, y: Float, displayId: Int, source: Int, buttonState: Int, downTime: Long): Boolean

    // ─── IME policy ─────────────────────────────────────────────────────
    fun setImePolicy(displayId: Int, policy: Int): Boolean

    /** Force the soft keyboard to show on the currently focused editor.
     *  Best-effort; reachability varies by Android version. */
    fun showImeOnFocusedEditor(): Boolean

    // ─── Display promotion (experimental app-owned VD path) ──────────────
    /** Promote an app-owned display so it can host other apps' activities +
     *  system decorations. Best-effort; needs the privileged process. */
    fun setShouldShowSystemDecors(displayId: Int, enabled: Boolean): Boolean

    // ─── Virtual display lifecycle ──────────────────────────────────────
    fun createVirtualDisplay(name: String, w: Int, h: Int, dpi: Int, flags: Int, surface: Surface): Int
    fun resizeVirtualDisplay(vdId: Int, w: Int, h: Int, dpi: Int): Boolean
    fun setVirtualDisplaySurface(vdId: Int, surface: Surface): Boolean
    fun releaseVirtualDisplay(vdId: Int): Boolean

    // ─── Surface mirror ─────────────────────────────────────────────────
    fun startSurfaceMirror(glassesId: Int, surface: Surface): Boolean
    fun stopSurfaceMirror(glassesId: Int)

    // ─── Task / activity / shell ────────────────────────────────────────
    fun moveFocusedTaskToDisplay(displayId: Int): Boolean
    fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean
    fun refocusTopTaskOnDisplay(displayId: Int): Boolean
    /** Display id of the focused root task (or -1). Reliable per-display focus signal. */
    fun getFocusedTaskDisplayId(): Int
    fun runCommand(cmd: String): String
    fun amStart(component: String, displayId: Int): String
    fun dumpDisplays(): String
    /** EXPERIMENTAL probe: try adding a trusted overlay from the privileged process. */
    fun probeOverlayWindow(displayId: Int, windowType: Int, holdMs: Long = 6000L): String
    /** EXPERIMENTAL color spike: apply a 16-float color matrix to a display. */
    fun setDisplayColorTransform(displayId: Int, matrix: FloatArray): String
    /** Probe + set the display's real (panel) color mode. */
    fun getDisplayColorModes(displayId: Int): String
    fun setDisplayColorMode(displayId: Int, colorMode: Int): String
    /** SPIKE #3: apply a 12-float (3x3+vec3) color transform to a layer from
     *  the privileged process. */
    fun setLayerColorTransform(layer: android.view.SurfaceControl, matrix12: FloatArray): String

    // ─── Diagnostics ────────────────────────────────────────────────────
    fun streamLogcat(filter: String? = null): ParcelFileDescriptor?
    fun stopLogcatStream()

    /** Discrete mouse-wheel scroll (no fling) at (x,y) on [displayId]. */
    fun injectScroll(x: Float, y: Float, vScroll: Float, hScroll: Float, displayId: Int)

    // ─── Physical mouse capture (Phase 1) ───────────────────────────────
    /** Start privileged evdev capture; deltas are written to [writeEnd].
     *  Returns a status string (device + grab result), or "ERR ..." on failure. */
    fun startMouseCapture(grab: Boolean, nativeLibDir: String?, writeEnd: ParcelFileDescriptor): String
    /** Stop the native read loop, ungrab, and close the device. */
    fun stopMouseCapture()
}
