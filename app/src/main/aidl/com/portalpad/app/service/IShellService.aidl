// Privileged helper interface — runs inside Shizuku's process (shell UID).
// Architecture follows a reference implementation's pattern:
//   1) App creates an ImageReader-backed Surface (in app process)
//   2) Passes Surface to createVirtualDisplay → user service creates VD
//      with TRUSTED + OWN_FOCUS + PRESENTATION + SUPPORTS_TOUCH flags
//   3) Calls startSurfaceMirror(glassesDisplayId, surface) to mirror the
//      glasses' physical display INTO the VD's surface (so the VD becomes
//      a trusted, owned wrapper around the glasses)
//   4) Inject clicks to the VD id (not glasses physical id)
//   5) Push activities onto the VD via moveFocusedTaskToDisplay or
//      shell `am start --display N`
package com.portalpad.app.service;

import android.view.Surface;
import android.view.SurfaceControl;
import android.os.ParcelFileDescriptor;

interface IShellService {
    // ─── Input injection ────────────────────────────────────────────────
    // Action: 0=DOWN, 1=UP, 2=MOVE, 7=HOVER_MOVE, 9=HOVER_ENTER, 10=HOVER_EXIT
    // Source: 4098=TOUCHSCREEN|CLASS_POINTER, 8194=MOUSE|CLASS_POINTER
    void injectPointer(int action, float x, float y, int displayId, int source, int buttonState, long downTime);
    // Discrete mouse-wheel scroll (ACTION_SCROLL / AXIS_VSCROLL) at (x,y) on the
    // given display. Unlike a swipe, this does NOT fling — content moves only
    // while the wheel turns. vScroll +1.0 = up, hScroll +1.0 = right (one notch).
    void injectScroll(float x, float y, float vScroll, float hScroll, int displayId);
    void injectKey(int keyCode, int action, int metaState, int displayId, int deviceId);
    /**
     * Inject a complete key PRESS (DOWN then UP) with a proper wall-clock gap
     * between them, server-side. A bare back-to-back DOWN/UP with identical
     * timestamps is unreliable — the UP can reach InputDispatcher before the
     * DOWN session is registered and get dropped, which made the D-pad
     * "sometimes not respond." Doing the press in one call (one IPC) with the
     * gap on the shell side fixes both reliability and latency.
     */
    void injectKeyPress(int keyCode, int displayId);

    /**
     * Like injectKeyPress, but tags the event with SOURCE_GAMEPAD instead of
     * SOURCE_KEYBOARD. Used by the program-key buttons so key-mapping apps
     * (Key Mapper, Button Mapper) see them as gamepad input — the gamepad
     * event pipeline is more permissive to app-injected events than the
     * keyboard one.
     */
    void injectGamepadKeyPress(int keyCode, int displayId);
    void injectTap(int displayId, float x, float y);
    void injectLongPress(int displayId, float x, float y, long durationMs);
    void injectSwipe(int displayId, float sx, float sy, float ex, float ey, long durationMs);

    /**
     * Two-pointer pinch/zoom on [displayId], centered at (cx,cy): two synthetic
     * fingers start [startSpan] px apart (horizontally) and animate to [endSpan]
     * over [durationMs]. endSpan > startSpan = zoom in; smaller = zoom out. This
     * is the only multitouch primitive — single-pointer paths can't drive an
     * app's ScaleGestureDetector. FEASIBILITY-GATED: whether an injected
     * multitouch gesture is honored on a virtual display is hardware-dependent.
     */
    void injectPinch(int displayId, float cx, float cy, float startSpan, float endSpan, long durationMs);

    /**
     * Continuous pinch: hold a two-pointer gesture open across calls so zoom
     * tracks the fingers live. [pinchBegin] presses two fingers [span] px apart
     * centered at (cx,cy); [pinchMove] updates the span each frame; [pinchEnd]
     * lifts both. The helper runs a watchdog that auto-releases if no move/end
     * arrives within a short window, so a dropped call can't leave a stuck touch.
     */
    void pinchBegin(int displayId, float cx, float cy, float span);
    void pinchMove(float span);
    void pinchEnd(float span);

    // ─── IME policy ─────────────────────────────────────────────────────
    boolean setDisplayImePolicy(int displayId, int policy);

    // Force the soft keyboard to show on whatever editor is currently focused.
    // Used for phone-as-keyboard mode: after routing resolves the IME to the
    // phone (display 0), the app/field may not auto-raise the keyboard (e.g.
    // STATE_ALWAYS_HIDDEN), so we ask the system to show it explicitly.
    boolean showImeOnFocusedEditor();

    // ─── Display promotion (experimental app-owned VD path) ──────────────
    // Lets a non-trusted, app-process-owned display host other apps' activities
    // + system decorations (home/IME/wallpaper). Runs IWindowManager
    // .setShouldShowSystemDecors in the privileged process.
    boolean setShouldShowSystemDecors(int displayId, boolean shouldShow);

    // ─── Virtual display lifecycle (reference pattern) ────────────────────
    int createVirtualDisplay(String name, int width, int height, int densityDpi, int flags, in Surface surface);
    boolean resizeVirtualDisplay(int virtualDisplayId, int width, int height, int densityDpi);
    boolean setVirtualDisplaySurface(int virtualDisplayId, in Surface surface);
    boolean releaseVirtualDisplay(int virtualDisplayId);

    /**
     * EXPERIMENTAL color spike: apply a 4x4 (16-float, row-major) color matrix
     * to a display's composited output via the system's hidden
     * SurfaceControl/DisplayTransformManager color-transform path (which only a
     * privileged process can reach). Pass the PHYSICAL display id of the glasses.
     * Returns a diagnostic string describing whether it was applied, so the UI
     * can report success/failure. Pass an identity matrix to reset.
     */
    String setDisplayColorTransform(int displayId, in float[] matrix);

    /**
     * Probe: report the color modes the given display exposes + the active one,
     * via Display.getSupportedModes-style / getColorMode reflection. Returns a
     * diagnostic string. If a display exposes more than one color mode, a real
     * per-display color-mode picker is possible (setActiveColorMode).
     */
    String getDisplayColorModes(int displayId);

    /** Set the active color mode on a display (one of the ids the probe lists). */
    String setDisplayColorMode(int displayId, int colorMode);

    /**
     * SPIKE #3: apply a color matrix to a LAYER (SurfaceControl) from the
     * PRIVILEGED process. The layer is created in the app process and passed
     * here; applying the transform as a privileged caller may be honored by
     * SurfaceFlinger where the app-process attempt was silently ignored.
     * matrix = 9 floats (3x3) followed by 3 floats (translation) = 12 floats.
     */
    String setLayerColorTransform(in SurfaceControl layer, in float[] matrix12);

    // ─── Surface mirror: glasses physical → our virtual display ────────
    boolean startSurfaceMirror(int physicalDisplayId, in Surface surface);
    void stopSurfaceMirror(int physicalDisplayId);

    // ─── Task / activity management ────────────────────────────────────
    boolean moveFocusedTaskToDisplay(int displayId);
    // Move a SPECIFIC task (by id) to a display — used by evacuate-and-restore,
    // which must move named windows individually (not just whatever is focused).
    boolean moveTaskToDisplay(int taskId, int displayId);

    /**
     * Re-assert input focus on the given display by bringing its current top
     * task to front (no relaunch). Returns true if a task on that display was
     * found and moved to front. Used to restore hardware-key focus to the
     * glasses display without disturbing whatever app is already running there
     * (unlike launching the wallpaper, which would cover it).
     */
    boolean refocusTopTaskOnDisplay(int displayId);

    /**
     * The display id of the currently focused root task (the app the user is
     * interacting with), via ActivityTaskManager.getFocusedRootTaskInfo(), or -1
     * if unavailable. Reliable per-display focus signal — used to gate auto-open
     * of the typing relay so it only fires when the focused app is on the glasses.
     */
    int getFocusedTaskDisplayId();

    /**
     * Launch an activity on the given display via `am start --display N`.
     * Component may be either:
     *   - "package/activity" form (e.g. "com.android.chrome/com.google.android.apps.chrome.Main")
     *   - just a package name (uses ACTION_MAIN + CATEGORY_LAUNCHER as fallback)
     * Returns the raw command output for diagnostic display.
     * Used to relaunch apps onto our trusted VirtualDisplay so injected
     * mouse events (and the apps' anchor popups) work correctly.
     */
    String amStart(String component, int displayId);

    String runCommand(String command);

    /**
     * EXPERIMENTAL PROBE (research spike): attempt to add a small visible test
     * overlay window of [windowType] on [displayId] FROM THIS PRIVILEGED PROCESS
     * (root/shell), hold it for [holdMs] so the user can try touching it, then
     * remove it. Returns a diagnostic string: whether the add succeeded, and
     * whether any touch was received (which tells us if a long-press could land
     * on a privileged-process overlay without an accessibility service).
     * windowType examples: 2006=SYSTEM_OVERLAY, 2002=PHONE, 2003=SYSTEM_ALERT,
     * 2038=APPLICATION_OVERLAY (baseline), 2032=ACCESSIBILITY_OVERLAY.
     */
    String probeOverlayWindow(int displayId, int windowType, long holdMs);

    // ─── Diagnostics ────────────────────────────────────────────────────
    String dumpDisplays();
    String ping(String message);
    void shutdownUserService();

    /**
     * Start streaming filtered logcat output back to the caller. The user
     * service spawns `logcat -v threadtime` with a tag filter and pipes
     * stdout into the returned FD's write side. Caller reads lines from
     * the read side until calling stopLogcatStream() (which kills the
     * subprocess and closes the pipe).
     *
     * The filter argument is a logcat tag-filter spec like
     * "PortalPad:V InputInjector:V *:S" — the *:S suffix silences all
     * other tags, so only PortalPad's own logs flow through. Pass null
     * for our default safe filter.
     */
    ParcelFileDescriptor streamLogcat(String filterSpec);
    void stopLogcatStream();

    // ─── Physical mouse capture (Phase 1: privileged evdev read) ─────────
    /**
     * Find a connected physical pointer device (a /dev/input/eventN advertising
     * REL_X+REL_Y), open it, optionally EVIOCGRAB it for exclusive access, and
     * spawn a native read loop that writes packed 16-byte delta records
     * {int32 dx, dy, buttons, wheel} (little-endian) to [writeEnd].
     *
     * Returns a synchronous status string so the caller can immediately learn
     * whether the device was found and whether the grab succeeded — e.g.
     * "OK device=/dev/input/event12 grab=OK" or
     * "OK device=/dev/input/event12 grab=FAILED(errno=1)" or "ERR no-mouse-found".
     * The grab result is the key Phase-1 signal: it tells us whether the shell
     * domain can exclusively grab input on this device (vs. needing root).
     */
    String startMouseCapture(boolean grab, String nativeLibDir, in ParcelFileDescriptor writeEnd);

    /** Stop the native read loop, ungrab, and close the captured device. */
    void stopMouseCapture();
}
