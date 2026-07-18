package com.portalpad.app.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.RunningTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Injects mouse, key, and touch events to the system.
 *
 * Every public injection method is wrapped in try/catch — a misbehaving inject
 * must not be allowed to crash the host activity. Errors are logged and the
 * call silently no-ops.
 *
 * Cursor state is exposed via [cursorPosition] so the dock overlay can render
 * a custom cursor on the external display. This works regardless of whether
 * the OEM renders a system mouse cursor — many don't render one for injected
 * SOURCE_MOUSE events on secondary displays.
 */
/** Shape the cursor overlay should render. Driven by resize-edge hover in
 *  desktop-windows mode; ARROW everywhere else. */
enum class CursorType { ARROW, RESIZE_H, RESIZE_V, RESIZE_NWSE, RESIZE_NESW, MOVE }

class InputInjector(
    private val accessProvider: () -> ElevatedAccess,
    private val context: Context,
) {

    /**
     * Cached InputManager reflection — looking these up via reflection on every
     * single inject was the dominant latency contributor for d-pad repeat. We
     * resolve them once on first use and reuse forever.
     */
    private val cachedInputManager: Any? by lazy {
        runCatching {
            Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance").invoke(null)
        }.getOrNull()
    }
    private val cachedInjectMethod: java.lang.reflect.Method? by lazy {
        cachedInputManager?.let {
            runCatching {
                it.javaClass.getMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull()
        }
    }

    private fun injectInProcess(event: android.view.InputEvent): Boolean {
        val mgr = cachedInputManager ?: return false
        val method = cachedInjectMethod ?: return false
        return try {
            method.invoke(mgr, event, 0 /* ASYNC */)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "cached inject failed", t)
            false
        }
    }

    private val access get() = accessProvider()

    /**
     * Executor for key injection. Keeps the Shizuku IPC off the UI thread (so
     * buttons stay responsive) and serializes presses so a press's DOWN/UP
     * pair never interleaves with another's. Tasks are fast now (no blocking
     * sleep server-side — see injectKeyPress), so an unbounded queue won't
     * build a backlog under rapid tapping, and we never drop a press.
     */
    private val keyExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "portalpad-keys").apply { isDaemon = true }
        }

    /**
     * User-tunable input feel, updated from prefs by the foreground service.
     *  - cursorSpeed: multiplier on pointer-move deltas (1.0 = default)
     *  - scrollSpeed: multiplier on scroll deltas (1.0 = default)
     *  - invertScroll: flip scroll direction
     */
    @Volatile var cursorSpeed: Float = 1.0f
    @Volatile var scrollSpeed: Float = 1.0f
    @Volatile var invertScroll: Boolean = false

    /** Display the events are targeted at. Setting this resets the cursor. */
    var displayId: Int = 0
        set(value) {
            if (field == value) return
            val old = field
            field = value
            // Previous display's hover registration is invalid for the new
            // display — next HOVER_MOVE will re-issue HOVER_ENTER on the new
            // display via the hoverActive flag check.
            hoverActive = false
            Log.d(TAG, "displayId change $old → $value")
            refreshDisplayMetrics()
            runCatching { repinImePolicy(aggressive = false) }
        }

    /** True when injecting into a trusted VirtualDisplay wrap; false when we fell
     *  back to the raw physical display (trusted VD couldn't be created). On the
     *  raw display, Shizuku touches often don't land in foreign apps, so taps are
     *  routed through the accessibility framework instead. Set by the foreground
     *  service alongside [displayId]. */
    @Volatile var usingVd: Boolean = false

    /** Whether the Shizuku injection backend is bound and ready right now. When it
     *  isn't, taps can't inject the normal way, so the a11y fallback takes over
     *  (instead of dead-blocking). */
    /** Whether the active injection backend — Shizuku OR root shell — can deliver
     *  input right now. When it can't, gestures fall back to a11y. (Previously
     *  this checked only the Shizuku backend, which wrongly forced a11y on root
     *  even though root injects fine.) */
    private fun activeBackendReady(): Boolean =
        PortalPadApp.instance.clickBackend.isReady

    /** Route this gesture through the accessibility framework: external display,
     *  and either untrusted (no VD) or the active backend unavailable, and the
     *  a11y service is connected to dispatch. Mirrors the tap/long-press gate. */
    private fun useA11y(): Boolean =
        displayId != 0 && (!usingVd || !activeBackendReady()) &&
            PortalPadAccessibilityService.instance != null

    // One-shot a11y drag: record the start point; cursor tracks through dragMove;
    // dispatch a single start→end swipe on dragEnd.
    private var a11yDragActive = false
    private var a11yDragStartX = 0f
    private var a11yDragStartY = 0f
    // Caption/handle MOVE via shell setBounds instead of a faked touch-drag on the pill:
    // the injected touch only moves the window on long sustained strokes (Samsung ignores
    // short ones), so we move the window directly. Captured at dragStart; each dragMove
    // resizes the window to startBounds + cursor delta (throttled, on the inject thread).
    @Volatile private var captionMoveActive = false
    @Volatile private var moveTaskId = -1
    @Volatile private var moveStartLeft = 0
    @Volatile private var moveStartTop = 0
    @Volatile private var moveW = 0
    @Volatile private var moveH = 0
    @Volatile private var moveCursorStartX = 0f
    @Volatile private var moveCursorStartY = 0f
    @Volatile private var lastMoveResizeAt = 0L
    // One-shot a11y pinch: accumulate scale; dispatch a 2-stroke pinch on commit.
    private var a11yPinchActive = false
    private var a11yPinchScale = 1f

    // ─── Resize-cursor hover feedback (desktop-windows only) ──────────────────
    /** Pushed by the foreground service from the DESKTOP_MODE_ENABLED pref. When
     *  off, the resize-cursor feature is fully dormant (cursor stays an arrow). */
    @Volatile var desktopModeEnabled: Boolean = false
    private val _cursorType = MutableStateFlow(CursorType.ARROW)
    val cursorType: StateFlow<CursorType> = _cursorType.asStateFlow()

    /** One widget's on-screen rect (display px) while the widget overlay is in
     *  EDIT mode, with which axes its provider allows resizing. Published by
     *  the overlay (set on edit-enter, cleared on edit-exit/dismiss) so the
     *  resize-cursor scan can show the same glyphs freeform windows get. */
    data class WidgetEditRect(
        val rect: android.graphics.Rect,
        val hResize: Boolean,
        val vResize: Boolean,
    )

    /** Edit-mode widget rects — empty when the overlay isn't editing. Volatile:
     *  written from the overlay's UI thread, read on the cursor-move path. */
    @Volatile var widgetEditRects: List<WidgetEditRect> = emptyList()
    private val _onCaption = MutableStateFlow(false)
    /** True when the cursor is over a freeform window's top caption strip (below
     *  the top-resize zone, above the content). Caption grabs use the same quick
     *  press-hold engage as the resize edges but move the window 1:1 (no lock). */
    val cursorOnCaption: StateFlow<Boolean> = _onCaption.asStateFlow()
    // Throttled snapshot of freeform windows — listTasks() hits the task system,
    // so we never call it per-move; geometry runs against this cache.
    @Volatile private var cachedResizeTasks: List<RunningTask> = emptyList()
    // Live collapsed-caption handle rects (screen coords), refreshed alongside the
    // resize-task cache on the scan thread. Over one of these the cursor is a MOVE
    // zone, not a resize edge.
    @Volatile private var cachedHandles: List<CaptionHandle> = emptyList()
    // Tap-only caption controls (window buttons + any open handle menu) to keep drag off.
    @Volatile private var cachedButtonRects: List<android.graphics.Rect> = emptyList()
    // When ANY clickable (collapsed-pill) handle was last seen. While this memory is
    // fresh, an empty handle list means "the pill's node transiently vanished"
    // (SystemUI rebuilds the caption decor for a few seconds after pill
    // interactions), NOT "this window has no caption" — so the geometric
    // caption-band fallback must stay OFF. Field bug: the fallback turned the top
    // 52px of a collapsed-pill window into a MOVE zone during those windows,
    // letting drags engage beside/below the pill.
    @Volatile private var lastClickableHandleSeenAt = 0L
    // TRUE while the pill's popup menu is open (mirrored from the a11y scan).
    // The pill is TAP-ONLY then: no drag may engage from it, because the next
    // press there is nearly always "move the cursor to a menu item" (field
    // log: five for five unintended window drags, all from a pill press with
    // the menu open, an aiming pause, then movement toward the item).
    @Volatile private var cachedMenuOpen = false
    // When the handle rects last came back NON-empty. Used to hold the last good
    // rects across a transient empty (the pill menu opening/closing briefly drops
    // the caption_handle node) so the MOVE zone does not flicker to a resize edge.
    @Volatile private var lastHandleRectsAt = 0L
    @Volatile private var lastHandleCursorLog = 0L
    @Volatile private var lastEditRectLog = 0L
    @Volatile private var lastDragDiag = 0L
    @Volatile private var lastDragRateDiag = 0L
    @Volatile private var captionMoveEvents = 0L
    @Volatile private var lastDragRateCount = 0L
    // DIAG-DRAGPOS (caption drags, ~10 Hz): integrated cursor vs the COMMANDED
    // window origin, plus raw-input stats for the window between samples.
    // Purpose: split the "cursor doesn't stay on the green bar" complaint
    // three ways with ONE capture, measured against what the user sees —
    //  (a) rawSum/maxStep large & erratic while the hand moved steadily
    //      = upstream input noise (the cursor itself is jumping);
    //  (b) cursor steady but the periodic ListTasks readbacks lag cmd
    //      = the window lagging the cursor (resize path too slow);
    //  (c) both steady & dOff==0 while the eye still sees an offset
    //      = the cursor OVERLAY rendering, not the drag path.
    // dOff is (cursor − cmd) minus the grab offset: exactly 0 unless the
    // keep-on-screen clamp engaged or the delta math drifted.
    @Volatile private var lastDragPosDiag = 0L
    @Volatile private var dragRawSumX = 0f
    @Volatile private var dragRawSumY = 0f
    @Volatile private var dragMaxStep = 0f
    @Volatile private var dragGrabOffX = 0f
    @Volatile private var dragGrabOffY = 0f
    @Volatile private var lastCmdLeft = 0
    @Volatile private var lastCmdTop = 0
    @Volatile private var lastRawDragDiag = 0L
    @Volatile private var lastResizeTaskCacheAt = 0L
    @Volatile private var resizeScanInFlight = false

    /** Dedicated thread for the resize-hover window scan. `listTasks` is a
     *  full shell round-trip (`am stack list` through the privileged backend,
     *  20-100ms) — it previously ran SYNCHRONOUSLY inside [pointerMove] every
     *  time the 250ms cache expired, i.e. on the thread delivering touch
     *  events, hitching the cursor 4x/sec in desktop mode. Not the inject
     *  executor: parking a shell call there would delay queued hover/click
     *  injections behind it. */
    private val resizeScanExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "portalpad-resize-scan").apply { isDaemon = true }
        }

    /** On cursor move, if it's hovering a resizable freeform window's edge/corner,
     *  switch the cursor to the matching resize glyph. Gated so it's never a dead
     *  affordance: desktop mode on, a resize drag can actually land (trusted VD +
     *  ready backend), not over the dock, and the window isn't display-filling
     *  (maximized). Cheap rectangle math against a throttled task cache. */
    /** DIAG (#2): string sampled at trackpad-press time so the caption-move vs resize
     *  engage decision can be correlated with the actual cursor state and a FRESH
     *  onHandle re-test at the current cursor position (same outsets as the live hit
     *  test). Read by TrackpadActivity's pressDiag() override. */
    fun handlePressDiag(): String {
        val x = cursorX
        val y = cursorY
        val handles = cachedHandles
        val onHandleNow = handles.any { h ->
            h.clickable &&
                x >= h.rect.left - PILL_OUT && x <= h.rect.right + PILL_OUT &&
                y >= h.rect.top - PILL_OUT && y <= h.rect.bottom
        }
        return "type=${_cursorType.value} onCaptionFlow=${_onCaption.value} " +
            "onHandleNow=$onHandleNow cur=${x.toInt()},${y.toInt()} rects=${handles.size}"
    }

    /** Authoritative press-time onHandle test (see TrackpadSurface.pressOnHandleNow).
     *  Re-tests the live cursor position against the handle rects, querying ONE fresh
     *  set if the cache was momentarily empty — fixes both the stale-flow grab loss and
     *  the empty-cache fall-through to resize. */
    fun pressOnHandleNow(): Boolean {
        // Pill is TAP-ONLY while its menu is open — the next press there is
        // "move the cursor to a menu item", not "drag the window" (field log:
        // five of five unintended drags were exactly this).
        if (cachedMenuOpen) return false
        var handles = cachedHandles
        if (handles.isEmpty()) {
            handles = runCatching {
                PortalPadAccessibilityService.instance?.captionHandles()
            }.getOrNull().orEmpty()
            if (handles.isNotEmpty()) {
                cachedHandles = handles
                lastHandleRectsAt = SystemClock.uptimeMillis()
                if (handles.any { it.clickable }) {
                    lastClickableHandleSeenAt = SystemClock.uptimeMillis()
                }
            }
        }
        val x = cursorX
        val y = cursorY
        return handles.any { h ->
            // Only the clickable COLLAPSED pill forces a caption-move; the non-clickable
            // expanded caption strip must not, or its top edge could never resize.
            // Vertically EXACT (no PILL_OUT above/below), matching the region model.
            h.clickable &&
                x >= h.rect.left - PILL_OUT && x <= h.rect.right + PILL_OUT &&
                y >= h.rect.top && y <= h.rect.bottom
        }
    }

    /** Press-time test: is the cursor on a caption tap-only control (window
     *  buttons / open pill menu)? The REGION model already keeps our quick
     *  caption grab off these, but the generic touch-and-hold drag path
     *  injects a REAL touch there — and the ROM reads a touch drag starting
     *  on caption chrome as a window move, buttons included (field: windows
     *  dragged from the caption's far-right icons). The gesture layer uses
     *  this to refuse ANY drag engage on the buttons: taps only. */
    fun pressOnButtonNow(): Boolean {
        val x = cursorX
        val y = cursorY
        return cachedButtonRects.any { r ->
            x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
        }
    }

    private fun updateResizeCursor() {
        // Widget-overlay EDIT mode: the layer sits above every window, so its
        // published rects win over freeform task edges — and they're deliberately
        // NOT gated on desktop mode (the layer works regardless of it).
        val editRects = widgetEditRects
        if (editRects.isNotEmpty()) {
            val t = editRects.firstOrNull { er ->
                val bb = er.rect
                cursorX >= bb.left - WIDGET_EDGE_OUT && cursorX <= bb.right + WIDGET_EDGE_OUT &&
                    cursorY >= bb.top - WIDGET_EDGE_OUT && cursorY <= bb.bottom + WIDGET_EDGE_OUT
            }?.let { er ->
                val bb = er.rect
                // Three honest zones (field spec, all edit-mode only since these
                // rects only exist then): RESIZE along the full borders — which
                // now ARE whole-edge drag targets, not just the dots — reaching
                // WIDGET_EDGE_OUT outward but only WIDGET_EDGE_IN inward, so the
                // glyph can never sit on the widget's face (the old ±30 dot
                // boxes swallowed a 1x1's whole interior); the top-bar GRAB
                // cursor across the interior, inset past the edge reach,
                // because the interior really does drag the widget; ARROW
                // everywhere else. E/W checked first so corners read horizontal,
                // matching the strips' touch priority.
                val onE = cursorX >= bb.right - WIDGET_EDGE_IN && cursorX <= bb.right + WIDGET_EDGE_OUT &&
                    cursorY >= bb.top - WIDGET_EDGE_OUT && cursorY <= bb.bottom + WIDGET_EDGE_OUT
                val onW = cursorX >= bb.left - WIDGET_EDGE_OUT && cursorX <= bb.left + WIDGET_EDGE_IN &&
                    cursorY >= bb.top - WIDGET_EDGE_OUT && cursorY <= bb.bottom + WIDGET_EDGE_OUT
                val onN = cursorY >= bb.top - WIDGET_EDGE_OUT && cursorY <= bb.top + WIDGET_EDGE_IN &&
                    cursorX >= bb.left - WIDGET_EDGE_OUT && cursorX <= bb.right + WIDGET_EDGE_OUT
                val onS = cursorY >= bb.bottom - WIDGET_EDGE_IN && cursorY <= bb.bottom + WIDGET_EDGE_OUT &&
                    cursorX >= bb.left - WIDGET_EDGE_OUT && cursorX <= bb.right + WIDGET_EDGE_OUT
                val inside = cursorX >= bb.left + WIDGET_MOVE_INSET && cursorX <= bb.right - WIDGET_MOVE_INSET &&
                    cursorY >= bb.top + WIDGET_MOVE_INSET && cursorY <= bb.bottom - WIDGET_MOVE_INSET
                val result = when {
                    onE || onW -> CursorType.RESIZE_H
                    onN || onS -> CursorType.RESIZE_V
                    inside -> CursorType.MOVE
                    else -> CursorType.ARROW
                }
                val wnow = SystemClock.uptimeMillis()
                if (wnow - lastEditRectLog > 1000) {
                    lastEditRectLog = wnow
                    android.util.Log.d(
                        TAG,
                        "DIAG-EDITCURSOR cursor=(${cursorX.toInt()},${cursorY.toInt()}) " +
                            "rect=[${bb.left},${bb.top} ${bb.width()}x${bb.height()}] " +
                            "onE=$onE onW=$onW onN=$onN onS=$onS inside=$inside → $result",
                    )
                }
                result
            } ?: CursorType.ARROW
            if (_cursorType.value != t) _cursorType.value = t
            if (_onCaption.value) _onCaption.value = false
            return
        }
        // Widget overlay open (non-edit): the modal layer covers every window,
        // but the freeform task cache below still holds THEIR rects — the
        // resize/caption cursor was leaking through onto widgets sitting over a
        // hidden window's edge (field). Windows aren't interactable behind the
        // layer, so the window cursor model goes fully dormant.
        if (runCatching { PortalPadApp.instance.widgetOverlayOpen.value }.getOrDefault(false)) {
            if (_cursorType.value != CursorType.ARROW) _cursorType.value = CursorType.ARROW
            if (_onCaption.value) _onCaption.value = false
            return
        }
        if (!desktopModeEnabled || !usingVd || !activeBackendReady() || cursorOverDock) {
            if (_cursorType.value != CursorType.ARROW) _cursorType.value = CursorType.ARROW
            if (_onCaption.value) _onCaption.value = false
            return
        }
        // Refresh the window-bounds cache ASYNCHRONOUSLY (single-flight): keep
        // hit-testing against the stale bounds until fresh ones land — 250ms
        // staleness was already the accepted design, so an extra ~50ms of
        // shell latency is invisible, whereas paying it on this thread was
        // not. Monotonic clock: wall time jumps (NTP/user) broke the TTL.
        val now = SystemClock.uptimeMillis()
        if (now - lastResizeTaskCacheAt > RESIZE_CACHE_TTL_MS && !resizeScanInFlight) {
            resizeScanInFlight = true
            val scanDisplayId = displayId
            runCatching {
                resizeScanExecutor.execute {
                    try {
                        cachedResizeTasks = runCatching {
                            PortalPadApp.instance.freeform.listTasks(scanDisplayId)
                        }.getOrDefault(emptyList())
                        val freshHandles = runCatching {
                            PortalPadAccessibilityService.instance?.captionHandles()
                        }.getOrNull() ?: emptyList()
                        cachedButtonRects = runCatching {
                            PortalPadAccessibilityService.instance?.captionButtonRects()
                        }.getOrNull() ?: emptyList()
                        // captionButtonRects just stamped menu visibility; mirror it
                        // here so the region model and press tests read one flag.
                        cachedMenuOpen = runCatching {
                            PortalPadAccessibilityService.instance?.handleMenuVisibleNow
                        }.getOrNull() ?: false
                        if (freshHandles.isNotEmpty()) {
                            cachedHandles = freshHandles
                            lastHandleRectsAt = SystemClock.uptimeMillis()
                            if (freshHandles.any { it.clickable }) {
                                lastClickableHandleSeenAt = SystemClock.uptimeMillis()
                            }
                        } else if (SystemClock.uptimeMillis() - lastHandleRectsAt > HANDLE_RECTS_GRACE_MS) {
                            // Grace expired: the handle is genuinely gone — expanded to
                            // the caption bar, or the window closed. Safe to drop the zone.
                            cachedHandles = emptyList()
                        }
                        // else: transient empty — the pill menu opening/closing momentarily
                        // drops the caption_handle node, so hold the last rects and the MOVE
                        // zone does not flicker to a resize edge under the cursor.
                        lastResizeTaskCacheAt = SystemClock.uptimeMillis()
                    } finally {
                        resizeScanInFlight = false
                    }
                }
            }.onFailure { resizeScanInFlight = false }
        }
        // ===== Window-top region model — the single source of truth for cursor + drag. =====
        // Resolve the window under the cursor (thin edge margins so the cursor sits on the
        // visible border, not deep in chrome).
        val handles = cachedHandles
        val b = cachedResizeTasks.firstNotNullOfOrNull { t ->
            t.bounds?.takeIf { bb ->
                cursorX >= bb.left - RESIZE_EDGE_OUT && cursorX <= bb.right + RESIZE_GRAB_IN &&
                    cursorY >= bb.top - RESIZE_TOP_OUT && cursorY <= bb.bottom + RESIZE_GRAB_IN
            }
        }
        // The caption_handle for the window under the cursor. Its CLICKABLE flag classifies
        // it (width-independent, so resizing / resolution can't fool it): clickable ⇒ the
        // COLLAPSED mini pill; non-clickable ⇒ the EXPANDED caption drag strip.
        val capH = handles.firstOrNull { h ->
            b == null || (h.rect.centerX() >= b.left && h.rect.centerX() <= b.right && h.rect.top <= b.top + 40)
        }
        val collapsed = capH != null && capH.clickable
        val expandedCap = capH != null && !capH.clickable
        // On a tap-only caption control (window buttons / open handle menu)? Never drag there
        // — keyed off the controls' real accessibility bounds, so no window sizing exposes them.
        val onButton = cachedButtonRects.any { r ->
            cursorX >= r.left && cursorX <= r.right && cursorY >= r.top && cursorY <= r.bottom
        }
        // On the mini green pill? TIGHT: +PILL_OUT sides/top, NOTHING below (the pill menu
        // opens below and must stay tappable). Clickable pill only — never the wide strip.
        // Pill MOVE zone: tap-only while its menu is open (!cachedMenuOpen), and
        // vertically EXACT — no tolerance above or below the bar (field request:
        // the grab zone stays within the mini bar). Horizontal PILL_OUT kept as
        // an aiming aid; no sideways complaints and the pill is narrow.
        val onPill = collapsed && capH != null && !onButton && !cachedMenuOpen &&
            cursorX >= capH.rect.left - PILL_OUT && cursorX <= capH.rect.right + PILL_OUT &&
            cursorY >= capH.rect.top && cursorY <= capH.rect.bottom
        val hnow = SystemClock.uptimeMillis()
        val maximized = b != null && b.left <= 2 && b.top <= 2 &&
            b.right >= displayWidth - 2 && b.bottom >= displayHeight - 2
        var type = CursorType.ARROW
        var onCap = false
        if (onPill) {
            // Mini green bar → MOVE + drag.
            type = CursorType.MOVE
            onCap = true
        } else if (b != null && !maximized) {
            val nearLeft = cursorX >= b.left - RESIZE_EDGE_OUT && cursorX <= b.left + RESIZE_EDGE_IN
            val nearRight = cursorX >= b.right - RESIZE_EDGE_IN && cursorX <= b.right + RESIZE_GRAB_IN
            val nearTop = cursorY >= b.top - RESIZE_TOP_OUT && cursorY <= b.top + RESIZE_TOP_IN
            val nearBottom = cursorY >= b.bottom - RESIZE_EDGE_IN && cursorY <= b.bottom + RESIZE_GRAB_IN
            // Caption DRAG body, MINUS the top resize strip (so the very top edge resizes) and
            // MINUS the tap-only buttons. Expanded: the a11y caption_handle strip. No handle at
            // all: geometric top band minus the button zone — but ONLY when no collapsed pill
            // was seen recently, or a transiently-vanished pill node would hand its whole top
            // band to MOVE (field: drags engaged beside/below the pill for the few seconds
            // SystemUI rebuilds the decor). Collapsed: none (pill is onPill).
            val inCaptionBody = !nearTop && !nearBottom && !onButton && when {
                expandedCap && capH != null ->
                    cursorX >= capH.rect.left && cursorX <= capH.rect.right &&
                        cursorY >= capH.rect.top && cursorY <= capH.rect.bottom
                capH == null &&
                    SystemClock.uptimeMillis() - lastClickableHandleSeenAt > COLLAPSED_HANDLE_MEMORY_MS ->
                    cursorX >= b.left && cursorX <= b.right - CAPTION_BUTTON_ZONE &&
                        cursorY >= b.top && cursorY <= b.top + CAPTION_STRIP_H
                else -> false
            }
            type = when {
                (nearLeft && nearTop) || (nearRight && nearBottom) -> CursorType.RESIZE_NWSE
                (nearRight && nearTop) || (nearLeft && nearBottom) -> CursorType.RESIZE_NESW
                nearLeft || nearRight -> CursorType.RESIZE_H
                nearTop || nearBottom -> CursorType.RESIZE_V
                inCaptionBody -> CursorType.MOVE
                else -> CursorType.ARROW
            }
            onCap = type == CursorType.MOVE
        }
        // else: no window under the cursor, or a maximized window → ARROW, no drag.
        // Region DIAG now logs AFTER the final glyph is decided — the old
        // pre-decision line could never show WHICH cursor the zones resolved
        // to (field: "no drag cursor over the expanded caption bar" was
        // unverifiable because type was missing).
        if (hnow - lastHandleCursorLog > 1500) {
            lastHandleCursorLog = hnow
            android.util.Log.d(
                "PortalPadSleep",
                "region: cursor=(${cursorX.toInt()},${cursorY.toInt()}) type=$type onCap=$onCap onPill=$onPill onButton=$onButton capClick=${capH?.clickable} winTop=${b?.top} winBot=${b?.bottom} handles=${handles.map { it.rect.toShortString() }}",
            )
        }
        if (_cursorType.value != type) _cursorType.value = type
        if (_onCaption.value != onCap) _onCaption.value = onCap
    }

    /**
     * When true, pressKey() skips its trailing repinImePolicy() call.
     *
     * The "Type to external display" relay (KeyboardRelayActivity) keeps the
     * PHONE keyboard up on display 0 and forwards each keystroke to the VD
     * via pressKey(). But pressKey() normally re-pins the IME policy after
     * every key — and that re-pin re-evaluates the IME across displays,
     * which yanks the phone keyboard serving the relay field (it collapses
     * after each keystroke even though the text gets through). While the
     * relay screen is open we set this flag so typing doesn't fight the
     * phone keyboard. The relay sets it true in onResume and false in
     * onPause.
     */
    @Volatile var suppressImeRepin: Boolean = false

    private var displayWidth: Int = 1920
    private var displayHeight: Int = 1080
    // While TRUE the setters below keep updating the INTERNAL position (it
    // drives the caption shell-move target math) but stop publishing to the
    // rendered cursor — during a caption drag the visual cursor is GLUED to
    // the window instead: postCaptionMoveResize publishes grab-point-on-the-
    // commanded-bounds each step, so cursor and window move as one object on
    // one rhythm (field: the smooth cursor over the stepped window read as
    // the cursor slipping off the bar).
    @Volatile private var suppressCursorPublish = false
    private var cursorX: Float = 100f
        set(value) {
            field = value
            if (!suppressCursorPublish) _cursorPosition.value = Pair(value, cursorY)
        }
    private var cursorY: Float = 100f
        set(value) {
            field = value
            if (!suppressCursorPublish) _cursorPosition.value = Pair(cursorX, value)
        }
    // Last glued cursor point (grab point on the last COMMANDED bounds) — the
    // internal cursor snaps here at dragEnd so movement resumes from where the
    // user SEES the cursor, not from an internal position that kept going past
    // a clamp.
    @Volatile private var lastGlueX = 0f
    @Volatile private var lastGlueY = 0f
    private var downTime: Long = 0

    private val _cursorPosition = MutableStateFlow(Pair(100f, 100f))

    /** (cursorX, cursorY) updated on each pointer move. Observable by the cursor overlay. */
    val cursorPosition: StateFlow<Pair<Float, Float>> = _cursorPosition.asStateFlow()

    // ─── Dock right-click (in-process) ──────────────────────────────────────
    // The dock is PortalPad's own overlay; an injected touch long-press reaches
    // it as a quick tap (it can't carry mouse-button state), so it launched
    // instead of opening the item menu. Instead, when the cursor is over the
    // visible dock, the dock sets [cursorOverDock] = true; rightClick() then
    // emits [dockRightClickTick] and SKIPS injection, and the dock tile under
    // the cursor opens its own menu in-process. The non-dock path is unchanged.
    @Volatile var cursorOverDock: Boolean = false
    private val _dockRightClickTick = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val dockRightClickTick: kotlinx.coroutines.flow.SharedFlow<Long> = _dockRightClickTick.asSharedFlow()

    // Widget overlay: same in-process right-click routing as the dock. While the
    // overlay is showing, it sets [cursorOverWidgetOverlay] = true; rightClick()
    // then emits [widgetOverlayRightClickTick] and skips injection (an injected
    // long-press reaches this NOT_FOCUSABLE cursor-driven window as a plain tap,
    // never a hold). The overlay collects the tick to toggle its edit mode — so
    // the trackpad's press-and-hold (which drives rightClick) enters/exits edit.
    @Volatile var cursorOverWidgetOverlay: Boolean = false
    private val _widgetOverlayRightClickTick = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val widgetOverlayRightClickTick: kotlinx.coroutines.flow.SharedFlow<Long> = _widgetOverlayRightClickTick.asSharedFlow()

    // Dock reorder ("wiggle") mode state, hoisted here so it SURVIVES the dock
    // overlay recomposing/remounting when the dock config refreshes after a drop.
    // (Held as remember{} inside the dock, it reset to false on that remount and
    // ended reorder mode after the first move.)
    val dockReorderMode = androidx.compose.runtime.mutableStateOf(false)
    val dockReorderPickedUpId = androidx.compose.runtime.mutableStateOf<String?>(null)
    // Remove ("wiggle") mode: when active, top-level dock icons wiggle and a tap
    // removes that item (looping so the user can keep removing until Done). Held
    // here (not in the dock) for the same reason as reorder mode above.
    val dockRemoveMode = androidx.compose.runtime.mutableStateOf(false)

    // ─── Search query relay channel ─────────────────────────────────────────
    // The keyboard-relay activity (on the phone) writes the live search text
    // here as the user types; the on-display SearchOverlay observes it and
    // filters its list. This bypasses the unreliable "inject keys → focused
    // field on the external display" path: we already know the typed text in
    // our own process, so we share it directly instead of depending on
    // cross-display IME focus.
    private val _searchQuery = MutableStateFlow("")

    /** Live search text from the relay, observed by the on-display search overlay. */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Replace the live search query (relay → overlay). */
    fun setSearchQuery(text: String) { _searchQuery.value = text }

    /** A relayed Enter/submit signal (increment to notify observers). */
    private val _searchSubmit = MutableStateFlow(0)
    val searchSubmit: StateFlow<Int> = _searchSubmit.asStateFlow()
    fun submitSearch() { _searchSubmit.value += 1 }

    // ─── Click haptic (external-display dock / top-bar taps) ────────────────
    // A short tactile tick on each left-click so dock and top-bar presses feel
    // confirmed, matching the phone-side search haptics. Click-only: hover does
    // not route through leftClick(), so the cursor drifting over buttons never
    // buzzes.
    //
    // Global haptic strength in ms (0 = off), mirrored from the VIBRATION_MS
    // pref by a collector. The VibrationEffect and VibrationAttributes are
    // pre-built and cached so each click just fires the cached effect with NO
    // per-click allocation — this removes the construction latency that made
    // the buzz feel slightly delayed after a tap.
    @Volatile var vibrationMs: Int = 25
        set(value) {
            field = value
            // Rebuild the cached effect to match the new strength.
            cachedEffect = if (value > 0 &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
            ) {
                runCatching {
                    android.os.VibrationEffect.createOneShot(
                        value.toLong(),
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                    )
                }.getOrNull()
            } else null
        }
    // Dormant: the per-click/key aggressive IME re-pin. Default OFF (display-change
    // pinning still runs via repinImePolicy(aggressive = false)). Kept as a one-flag
    // revival point — no UI currently writes it.
    @Volatile var aggressiveImeRepin: Boolean = false
    @Volatile var imeOnExternalEnabled: Boolean = false
    // Mirrors the auto-open-relay pref. When true (and in phone-keyboard mode),
    // the glasses display is pinned to HIDE so the native keyboard never pops for
    // a glasses field — the relay is the sole on-phone keyboard. See repinImePolicy.
    @Volatile var autoOpenRelayEnabled: Boolean = false

    // Pre-built effect for the current strength (rebuilt only on change above).
    @Volatile private var cachedEffect: android.os.VibrationEffect? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching {
                android.os.VibrationEffect.createOneShot(
                    25L, android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                )
            }.getOrNull()
        } else null

    // Attributes never change — build once.
    private val touchAttrs: android.os.VibrationAttributes? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching {
                android.os.VibrationAttributes.Builder()
                    .setUsage(android.os.VibrationAttributes.USAGE_TOUCH)
                    .build()
            }.getOrNull()
        } else null
    }

    private val vibrator: android.os.Vibrator? by lazy {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
        }.getOrNull()
    }

    private fun clickHaptic() = buzz(longPress = false)

    /**
     * Fire a preset-strength haptic tick. Public so the whole PortalPad UI
     * (trackpad bars, media controls, power button, dock/overlays via clicks)
     * can route through the SAME Off/Light/Medium/Strong preset instead of the
     * View-level system haptic. [longPress] gives a stronger/longer buzz for
     * long-press actions (≈2× the preset duration), preserving the tap-vs-hold
     * distinction. No-op when the preset is Off (vibrationMs <= 0).
     */
    fun buzz(longPress: Boolean = false) {
        val base = vibrationMs
        if (base <= 0) return
        val v = vibrator ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // Common case (a click tick) fires the PRE-BUILT effect with no
                // per-call allocation — that construction was the avoidable latency.
                // Only the rarer long-press variant (2× duration) builds on demand.
                val effect = if (longPress) {
                    android.os.VibrationEffect.createOneShot(
                        (base * 2).toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                    )
                } else {
                    cachedEffect ?: android.os.VibrationEffect.createOneShot(
                        base.toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                    )
                }
                val attrs = touchAttrs
                if (attrs != null) v.vibrate(effect, attrs) else v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(if (longPress) (base * 2).toLong() else base.toLong())
            }
        }
    }

    private val _cursorVisible = MutableStateFlow(true)

    /**
     * Whether the cursor overlay should currently render. Controlled by:
     *  - The CursorOverlay observes this and short-circuits onDraw when false
     *  - TrackpadActivity sets this based on (mode == Media || hideInAppPref)
     *  - Service can also force it false if no display is attached
     */
    val cursorVisible: StateFlow<Boolean> = _cursorVisible.asStateFlow()

    fun setCursorVisible(visible: Boolean) { _cursorVisible.value = visible }

    /**
     * Emits the cursor coordinate on each committed left click. Desktop-mode
     * window controls observe this to drive a click-to-grab / move / click-to-
     * drop interaction, since injected taps don't stream MOVE events to overlay
     * windows the way a physical finger would. Replay 0, extra buffer so a
     * click is never dropped if the collector is briefly busy.
     */
    private val _clickEvents = MutableSharedFlow<Pair<Float, Float>>(
        replay = 0, extraBufferCapacity = 4,
    )
    val clickEvents: SharedFlow<Pair<Float, Float>> = _clickEvents.asSharedFlow()

    /** Called by leftClick() after a tap is injected. */
    private fun emitClickEvent(x: Float, y: Float) {
        _clickEvents.tryEmit(x to y)
    }

    private fun refreshDisplayMetrics() = runCatching {
        if (displayId == 0) {
            displayWidth = 1920; displayHeight = 1080
            cursorX = 100f; cursorY = 100f
            return@runCatching
        }
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: return@runCatching
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
        cursorX = displayWidth / 2f
        cursorY = displayHeight / 2f
    }.onFailure { Log.w(TAG, "refreshDisplayMetrics failed", it) }

    /**
     * Re-read the display size WITHOUT recentering the cursor. A portrait-only
     * app (Reddit, etc.) can rotate the external display to portrait (taller),
     * changing its height; if we don't update [displayWidth]/[displayHeight] the
     * cursor stays clamped to the old (landscape) bounds and can't reach the
     * bottom. Called from the display-changed listener on rotation. Cheap no-op
     * when the size is unchanged (so it's safe to call on every change event).
     */
    fun updateDisplaySizeForRotation() = runCatching {
        if (displayId == 0) return@runCatching
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: return@runCatching
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        if (w == displayWidth && h == displayHeight) return@runCatching
        Log.d(TAG, "display size ${displayWidth}x${displayHeight} -> ${w}x${h} (rotation?) — updating cursor bounds")
        displayWidth = w
        displayHeight = h
        // Clamp the existing cursor into the new bounds — do NOT recenter, so the
        // cursor doesn't teleport on minor display events.
        cursorX = cursorX.coerceIn(0f, (displayWidth - 1).toFloat())
        cursorY = cursorY.coerceIn(0f, (displayHeight - 1).toFloat())
    }.onFailure { Log.w(TAG, "updateDisplaySizeForRotation failed", it) }

    // ─────────────────────────── mouse ───────────────────────────

    /**
     * Move the virtual cursor by (dx, dy). Updates [cursorPosition] so the
     * custom overlay renders at the right place, and best-effort emits a
     * HOVER_MOVE event so apps tracking mouse hover can react.
     */
    /**
     * Cursor moved. Updates the local cursor coordinates and emits an
     * ACTION_HOVER_MOVE event with SOURCE_MOUSE so apps tracking real mouse
     * hover can respond (web pages, dropdowns, etc.). The event is sent via
     * the Shizuku UserService when available — IN-PROCESS hover injection
     * fails silently because our app lacks INJECT_EVENTS permission.
     *
     * Critically, this is a MOUSE-source hover event, not a TOUCHSCREEN move
     * event. The latter looks to Android like a finger dragging without
     * pressure, which causes focus loss on text fields (IME hides) and
     * clears text selections. the reference implementation uses the same hover-mouse pattern.
     */
    fun pointerMove(dx: Float, dy: Float) = safe {
        // SINGLE-WRITER rule during a caption drag: this pipeline (fed by the
        // air-mouse gyro among others) was writing the cursor IN PARALLEL with
        // dragMove's integration — every phone movement in the user's hand
        // fought the drag for the cursor, teleporting it hundreds of px
        // between samples while the window chased the chaos (drift3.txt: raw
        // drag deltas of ±5px with 300-700px cursor jumps between them; and
        // the original field report's "stabilizes when I leave the phone
        // alone" = gyro going quiet). Forwarding into dragMove keeps ONE
        // integration path — so air-mouse-driven window drags, where phone
        // motion IS the intent, still work.
        if (captionMoveActive) {
            dragMove(dx, dy)
            return@safe
        }
        val sx = dx * cursorSpeed
        val sy = dy * cursorSpeed
        cursorX = (cursorX + sx).coerceIn(0f, (displayWidth - 1).toFloat())
        cursorY = (cursorY + sy).coerceIn(0f, (displayHeight - 1).toFloat())
        scheduleHoverInject(cursorX, cursorY)
        updateResizeCursor()
    }

    // ─── Live double-tap-drag (touchscreen down→move→up) ─────────────────────
    // A real held touch drag, driven frame-by-frame from the trackpad. Used for
    // moving freeform windows (and text selection). We use TOUCHSCREEN source —
    // same reason leftClick does: the window manager / apps follow a touch drag,
    // whereas injected mouse-button drags are widely ignored. downTime is held
    // constant across the whole gesture so the dispatcher treats DOWN/MOVE/UP as
    // one continuous pointer stream.
    @Volatile private var dragDownTime: Long = 0L
    // When a drag grabs a window edge, we lock the touch X and/or Y to that edge
    // so the injected MOVE stream stays on the resize region (the cursor itself
    // can drift; the touch point follows the resized edge). null = free (move).
    @Volatile private var dragLockX: Float? = null
    @Volatile private var dragLockY: Float? = null

    // ─── Pinch-zoom (two-pointer, continuous) ───────────────────────────────
    // The two-pointer gesture is held open in the helper and its span is streamed
    // live as the user pinches, so the app's zoom tracks the fingers in real time
    // (vs. one jump on release). [pinchUpdate] begins the gesture on its first
    // call and streams thereafter; [pinchCommit] releases it. Shizuku-only — the
    // shell `input` path has no multitouch. The helper runs a watchdog that
    // force-releases a stale gesture so a dropped "end" can't leave a stuck touch.
    @Volatile private var pinchSessionActive = false
    private var lastPinchSpan = PINCH_BASE_SPAN

    fun pinchUpdate(scale: Float) = safe {
        if (useA11y()) {
            // One-shot a11y pinch: just accumulate; dispatched on commit.
            a11yPinchActive = true
            a11yPinchScale = scale
            return@safe
        }
        val backend = PortalPadApp.instance.clickBackend as? ClickBackend.ShizukuUserService ?: return@safe
        if (!backend.backend.isReady) return@safe
        val maxSpan = (displayWidth - 1).coerceAtLeast(80).toFloat()
        val span = (PINCH_BASE_SPAN * scale).coerceIn(40f, maxSpan)
        if (!pinchSessionActive) {
            pinchSessionActive = true
            lastPinchSpan = PINCH_BASE_SPAN
            // Center the zoom where the cursor is when the pinch starts.
            backend.backend.pinchBegin(displayId, cursorX, cursorY, PINCH_BASE_SPAN)
        }
        // Throttle redundant updates (finger jitter) to keep binder traffic sane.
        if (kotlin.math.abs(span - lastPinchSpan) >= 2f) {
            lastPinchSpan = span
            backend.backend.pinchMove(span)
        }
    }

    fun pinchCommit() = safe {
        if (a11yPinchActive) {
            a11yPinchActive = false
            val scale = a11yPinchScale
            a11yPinchScale = 1f
            if (kotlin.math.abs(scale - 1f) < 0.05f) return@safe
            val maxSpan = (displayWidth - 1).coerceAtLeast(80).toFloat()
            val endSpan = (PINCH_BASE_SPAN * scale).coerceIn(40f, maxSpan)
            PortalPadAccessibilityService.instance?.dispatchPinch(
                displayId, cursorX, cursorY, PINCH_BASE_SPAN, endSpan, 200L,
            )
            debugToast("Pinch → a11y zoom x${"%.2f".format(scale)} disp=$displayId")
            return@safe
        }
        if (!pinchSessionActive) return@safe
        pinchSessionActive = false
        val backend = PortalPadApp.instance.clickBackend as? ClickBackend.ShizukuUserService ?: return@safe
        backend.backend.pinchEnd(lastPinchSpan)
        debugToast("Pinch end → zoom x${"%.2f".format(lastPinchSpan / PINCH_BASE_SPAN)} disp=$displayId")
    }

    /**
     * Discrete zoom step for the Reader page — a quick pinch centered where the
     * cursor is (same centering the trackpad pinch uses). [zoomIn] magnifies
     * (pinch out); otherwise shrinks. The a11y path dispatches a single animated
     * 200ms pinch; the Shizuku path ramps the span over a few frames so it reads
     * as a real gesture before releasing.
     */
    fun zoomStep(zoomIn: Boolean) {
        val target = if (zoomIn) 1.4f else 0.72f
        if (useA11y()) {
            pinchUpdate(target)
            pinchCommit()
            return
        }
        pinchUpdate(1.0f)
        pinchUpdate((1.0f + target) / 2f)
        pinchUpdate(target)
        pinchCommit()
    }

    // Move the grabbed window to startBounds + cursor delta, clamped to keep it mostly
    // on-screen (caption reachable). Runs the shell resize on the inject thread so the
    // input path never blocks on it; latest call wins.
    private fun postCaptionMoveResize() {
        val ddx = (cursorX - moveCursorStartX).toInt()
        val ddy = (cursorY - moveCursorStartY).toInt()
        val left = (moveStartLeft + ddx).coerceIn(160 - moveW, (displayWidth - 160).coerceAtLeast(0))
        val top = (moveStartTop + ddy).coerceIn(0, (displayHeight - 80).coerceAtLeast(0))
        lastCmdLeft = left; lastCmdTop = top
        val tid = moveTaskId
        val bounds = com.portalpad.app.data.WindowBounds(left, top, left + moveW, top + moveH)
        // Direct-follow: the cursor setter already published the cursor as it
        // tracked the finger, so there's no separate "glue" point to republish
        // — the window simply follows the cursor here.
        submitCaptionResize(tid, bounds)
    }

    // ── Coalescing resize submitter ────────────────────────────────────────
    // Never issue a new resize while one is in flight: stash only the LATEST
    // target and fire it the instant the previous completes. Without this,
    // slow resize commands (the shell fallback, or a loaded binder) queued
    // faster than they completed — the COMMANDED position ran far ahead of the
    // ACTUAL window, so the glued cursor sailed off the caption bar while the
    // window lagged behind (field: drift early in a session, tight once the
    // fast path warmed up). With coalescing, commanded can never lead executed
    // by more than one step on ANY path; slow paths just take chunkier steps.
    private val captionResizeLock = Any()
    private var captionResizeBusy = false
    private var captionResizePendingTask = -1
    private var captionResizePendingBounds: com.portalpad.app.data.WindowBounds? = null

    private fun submitCaptionResize(taskId: Int, bounds: com.portalpad.app.data.WindowBounds) {
        synchronized(captionResizeLock) {
            if (captionResizeBusy) {
                captionResizePendingTask = taskId
                captionResizePendingBounds = bounds
                return
            }
            captionResizeBusy = true
        }
        injectExecutor.execute { runCaptionResizeLoop(taskId, bounds) }
    }

    private fun runCaptionResizeLoop(firstTask: Int, firstBounds: com.portalpad.app.data.WindowBounds) {
        var tid = firstTask
        var b: com.portalpad.app.data.WindowBounds? = firstBounds
        while (true) {
            val bb = b ?: break
            runCatching { PortalPadApp.instance.freeform.resize(tid, bb) }
            synchronized(captionResizeLock) {
                val nb = captionResizePendingBounds
                if (nb != null) {
                    tid = captionResizePendingTask
                    b = nb
                    captionResizePendingBounds = null
                } else {
                    b = null
                    captionResizeBusy = false
                }
            }
        }
    }

    /** If this click landed on a SystemUI close (X) control and the
     *  close-removes-from-Recents pref is on, the window is about to finish
     *  itself via Samsung's own handler — schedule a removeTask shortly after
     *  so the leftover Recents card is purged too (the DeX behavior).
     *  Entirely off-main; a scan miss or an already-gone task is a harmless
     *  no-op, so a normal content click costs one background node scan at
     *  most. The task is resolved from the click point BEFORE the window
     *  starts closing. */
    private fun maybePurgeClosedTask(x: Float, y: Float) {
        if (displayId == 0) return
        if (!runCatching { PortalPadApp.instance.freeform.closeRemovesFromRecents }.getOrDefault(false)) return
        val task = cachedResizeTasks.firstOrNull { t ->
            t.bounds?.let { b -> x >= b.left && x <= b.right && y >= b.top && y <= b.bottom } == true
        } ?: return
        Thread {
            try {
                val hit = PortalPadAccessibilityService.instance
                    ?.isCloseButtonAt(x.toInt(), y.toInt()) == true
                if (!hit) return@Thread
                Log.i(TAG, "DIAG-CLOSEX close control clicked → purging task=${task.taskId} in 600ms")
                Thread.sleep(600)
                val ok = runCatching {
                    PortalPadApp.instance.activeBoundBackend?.removeTask(task.taskId)
                }.getOrNull()
                Log.i(TAG, "DIAG-CLOSEX removeTask(${task.taskId}) → $ok")
            } catch (t: Throwable) {
                Log.w(TAG, "DIAG-CLOSEX purge failed", t)
            }
        }.start()
    }

    fun dragStart(captionMove: Boolean = false) = safe {
        // Fresh drag = fresh publish state (belt-and-braces against a leaked
        // suppression from an aborted caption move freezing the cursor).
        suppressCursorPublish = false
        if (useA11y()) {
            a11yDragActive = true
            a11yDragStartX = cursorX
            a11yDragStartY = cursorY
            clickHaptic()
            Log.d(TAG, "a11y dragStart @(${cursorX.toInt()},${cursorY.toInt()})")
            return@safe
        }
        // Caption/handle MOVE → shell setBounds. Find the window under the cursor (the
        // handle sits at its top, so allow the cursor a little above the top), capture its
        // bounds, and move it via resize() on each dragMove. No touch injected on the pill
        // (so it also stops accidentally toggling the pill menu).
        if (captionMove) {
            // LIVE menu check (not the TTL cache — full log 2026-07-17
            // 19:41:08.966: onPill=true while the menu was visibly open
            // because the cached flag was stale): with the pill menu up, a
            // hold on the pill area must NOT start a window move — the menu
            // items live right there and the user is aiming at them. The scan
            // stamps handleMenuVisibleNow fresh; on true we return with no
            // drag armed, so the gesture's dragMove/dragEnd fall through as
            // no-ops.
            val menuOpen = runCatching {
                PortalPadAccessibilityService.instance?.captionButtonRects()
                PortalPadAccessibilityService.instance?.handleMenuVisibleNow == true
            }.getOrDefault(false)
            if (menuOpen) {
                cachedMenuOpen = true
                Log.d(TAG, "dragStart CAPTION suppressed — pill menu open")
                return@safe
            }
            val task = runCatching {
                PortalPadApp.instance.freeform.listTasks(displayId).firstOrNull { t ->
                    val b = t.bounds
                    b != null && cursorX >= b.left && cursorX <= b.right &&
                        cursorY >= b.top - 80 && cursorY <= b.bottom
                }
            }.getOrNull()
            val b = task?.bounds
            if (task != null && b != null) {
                captionMoveActive = true
                // Every drag logs its resize path (see resetResizePathLog).
                runCatching { PortalPadApp.instance.freeform.resetResizePathLog() }
                moveTaskId = task.taskId
                moveStartLeft = b.left
                moveStartTop = b.top
                moveW = b.right - b.left
                moveH = b.bottom - b.top
                moveCursorStartX = cursorX
                moveCursorStartY = cursorY
                lastMoveResizeAt = 0L
                lastDragPosDiag = 0L
                dragRawSumX = 0f; dragRawSumY = 0f; dragMaxStep = 0f
                dragGrabOffX = cursorX - moveStartLeft
                dragGrabOffY = cursorY - moveStartTop
                lastCmdLeft = moveStartLeft; lastCmdTop = moveStartTop
                clickHaptic()
                Log.d(TAG, "dragStart CAPTION shell-move task=$moveTaskId from=($moveStartLeft,$moveStartTop) size=${moveW}x$moveH")
                Log.d(TAG, "DIAG-DRAGPOS start cursor=(${cursorX.toInt()},${cursorY.toInt()}) grabOff=(${dragGrabOffX.toInt()},${dragGrabOffY.toInt()})")
                return@safe
            }
            // Couldn't resolve the window — fall through to the touch-drag as a best effort.
        }
        val backend = PortalPadApp.instance.clickBackend as? ClickBackend.ShizukuUserService
            ?: return@safe
        if (!backend.backend.isReady) return@safe
        val now = SystemClock.uptimeMillis()
        dragDownTime = now
        dragLockX = null
        dragLockY = null

        // Edge detection: if the cursor is near the focused window's edge/corner,
        // snap the touch-down EXACTLY onto that edge so the injected touch lands on
        // Android's resize region (thin, ~a few px) — giving native resize its best
        // chance to trigger. Otherwise the touch-down is at the cursor (a move).
        var startX = cursorX
        var startY = cursorY
        // A caption/handle grab is an explicit MOVE: keep the touch-down AT the cursor
        // so Android moves the window. The collapsed handle sits ON the window's top
        // resize edge, so WITHOUT this guard the edge-snap below turned every handle
        // drag into a vertical resize. Only non-caption grabs snap to a resize edge.
        if (!captionMove) {
        runCatching {
            val tasks = PortalPadApp.instance.freeform.listTasks(displayId)
            // Focused = the task whose bounds contain the cursor (topmost wins;
            // listTasks is roughly front-to-back, so first match is fine).
            val b = tasks.firstNotNullOfOrNull { it.bounds?.takeIf { bb ->
                cursorX >= bb.left - RESIZE_EDGE_OUT && cursorX <= bb.right + RESIZE_GRAB_IN &&
                    cursorY >= bb.top - RESIZE_GRAB_IN && cursorY <= bb.bottom + RESIZE_GRAB_IN
            } }
            if (b != null) {
                // Forgiving grab: thin INSIDE the top (title-bar grab stays a move)
                // but generous OUTSIDE the top/right/bottom, where there's only
                // wallpaper. left stays generous inside.
                val nearLeft = cursorX >= b.left - RESIZE_EDGE_OUT && cursorX <= b.left + RESIZE_GRAB_IN
                val nearRight = cursorX >= b.right - RESIZE_GRAB_IN && cursorX <= b.right + RESIZE_GRAB_IN
                val nearTop = cursorY >= b.top - RESIZE_GRAB_IN && cursorY <= b.top + RESIZE_TOP_IN
                val nearBottom = cursorY >= b.bottom - RESIZE_GRAB_IN && cursorY <= b.bottom + RESIZE_GRAB_IN
                // left/top are INCLUSIVE coords (first pixel inside the window), so
                // landing exactly on them touches app content and won't resize.
                // Nudge the touch-down 1px OUTSIDE into Android's resize region —
                // right/bottom are exclusive coords and already sit just outside.
                if (nearLeft) { startX = b.left.toFloat() - 1f; dragLockX = startX }
                if (nearRight) { startX = b.right.toFloat(); dragLockX = startX }
                if (nearTop) { startY = b.top.toFloat() - 1f; dragLockY = startY }
                if (nearBottom) { startY = b.bottom.toFloat(); dragLockY = startY }
                if (dragLockX != null || dragLockY != null) {
                    Log.d(TAG, "dragStart EDGE grab L=$nearLeft R=$nearRight T=$nearTop B=$nearBottom @($startX,$startY)")
                }
            }
        }
        } else {
            // Snap the touch-down onto the ACTUAL handle pill. The grab ZONE is generous
            // (±HANDLE_GRAB_OUT), so the raw cursor can sit off the real pill; Samsung only
            // starts a handle-drag when the touch lands ON the pill (like a real finger in
            // DeX). Land it on the center of the pill under the cursor's x-span, and move
            // the cursor there so the drag is continuous and the cursor stays glued to it.
            val pill = cachedHandles.firstOrNull { h ->
                h.clickable &&
                    cursorX >= h.rect.left - HANDLE_GRAB_OUT_X && cursorX <= h.rect.right + HANDLE_GRAB_OUT_X &&
                    cursorY >= h.rect.top - 80f && cursorY <= h.rect.bottom + 80f
            }?.rect
            if (pill != null) {
                startX = (pill.left + pill.right) / 2f
                startY = (pill.top + pill.bottom) / 2f
                cursorX = startX
                cursorY = startY
                Log.d(TAG, "dragStart HANDLE snap → pill center ($startX,$startY)")
            }
        } // end if (!captionMove)

        sendHoverExitViaBackend(startX, startY)
        backend.backend.pointer(
            action = MotionEvent.ACTION_DOWN,
            x = startX, y = startY, displayId = displayId,
            source = InputDevice.SOURCE_TOUCHSCREEN, buttonState = 0, downTime = now,
        )
        clickHaptic()
        Log.d(TAG, "dragStart → touch DOWN display=$displayId ($startX,$startY) resize=${dragLockX != null || dragLockY != null}")
        // Instant resize border: Android paints the freeform resize outline only
        // once it sees motion, so nudge the grab 1px in the resize axis right after
        // the DOWN. Invisible (1px), but makes the border appear the moment you
        // grab, before you actually move.
        if (dragLockX != null || dragLockY != null) {
            val nudgeX = if (dragLockX != null) startX + 1f else startX
            val nudgeY = if (dragLockY != null) startY + 1f else startY
            backend.backend.pointer(
                action = MotionEvent.ACTION_MOVE,
                x = nudgeX, y = nudgeY, displayId = displayId,
                source = InputDevice.SOURCE_TOUCHSCREEN, buttonState = 0, downTime = now,
            )
        }
    }

    fun dragMove(dx: Float, dy: Float) = safe {
        if (captionMoveActive) {
            // Direct-follow: the cursor tracks the finger 1:1 and the window
            // follows the cursor. This is the ORIGINAL model — the glue /
            // coalescing / smoothing / velocity-clamp layers added on top of
            // it each chased a "bounce" that turned out to be upstream input
            // noise, and each added its own feel problems (lag, rubber-band,
            // visible detach). Reverted to simple and direct on purpose.
            cursorX = (cursorX + dx * cursorSpeed).coerceIn(0f, (displayWidth - 1).toFloat())
            cursorY = (cursorY + dy * cursorSpeed).coerceIn(0f, (displayHeight - 1).toFloat())
            captionMoveEvents++
            val now = SystemClock.uptimeMillis()
            // DIAG-DRAG rate: confirms move events are actually ARRIVING during
            // the drag (drift.txt showed a 14s caption drag with resizes only
            // at start/end — either dragMove wasn't called, or the throttle
            // starved it). One line/sec with the event count since last line.
            if (now - lastDragRateDiag > 1000) {
                lastDragRateDiag = now
                Log.d(TAG, "DIAG-DRAG move events in last ~1s: ${captionMoveEvents - lastDragRateCount}")
                lastDragRateCount = captionMoveEvents
            }
            // DIAG-DRAGPOS sample (~10 Hz; see the field block for what each
            // column discriminates). ASCII-only values — PowerShell captures
            // mangle non-ASCII.
            val stepX = kotlin.math.abs(dx * cursorSpeed)
            val stepY = kotlin.math.abs(dy * cursorSpeed)
            dragRawSumX += dx * cursorSpeed
            dragRawSumY += dy * cursorSpeed
            val step = if (stepX > stepY) stepX else stepY
            if (step > dragMaxStep) dragMaxStep = step
            if (now - lastDragPosDiag > 100) {
                lastDragPosDiag = now
                Log.d(
                    TAG,
                    "DIAG-DRAGPOS cursor=(${cursorX.toInt()},${cursorY.toInt()}) " +
                        "cmd=($lastCmdLeft,$lastCmdTop) " +
                        "dOff=(${(cursorX - lastCmdLeft - dragGrabOffX).toInt()}," +
                        "${(cursorY - lastCmdTop - dragGrabOffY).toInt()}) " +
                        "rawSum=(${dragRawSumX.toInt()},${dragRawSumY.toInt()}) " +
                        "maxStep=${dragMaxStep.toInt()}",
                )
                dragRawSumX = 0f; dragRawSumY = 0f; dragMaxStep = 0f
            }
            // Cadence: both resize paths pace at ~11 steps/s. The old claim
            // that the fast binder "sustains ~33 steps/s" measured SUBMISSION,
            // not application — WM wraps every resizeTask in a queued
            // transition and drains ~25-30/s max, so faster submits only grew
            // a playback backlog (see CAPTION_MOVE_THROTTLE_* for the drag-
            // test evidence).
            val moveThrottle = if (runCatching {
                    PortalPadApp.instance.freeform.lastResizeWasFast
                }.getOrDefault(false)
            ) CAPTION_MOVE_THROTTLE_FAST_MS else CAPTION_MOVE_THROTTLE_MS
            if (now - lastMoveResizeAt >= moveThrottle) {
                lastMoveResizeAt = now
                postCaptionMoveResize()
            }
            return@safe
        }
        if (a11yDragActive) {
            // Cursor tracks the finger so the user sees the drag; the actual drag
            // gesture is dispatched once on dragEnd (start→end).
            cursorX = (cursorX + dx * cursorSpeed).coerceIn(0f, (displayWidth - 1).toFloat())
            cursorY = (cursorY + dy * cursorSpeed).coerceIn(0f, (displayHeight - 1).toFloat())
            return@safe
        }
        val backend = PortalPadApp.instance.clickBackend as? ClickBackend.ShizukuUserService
            ?: return@safe
        if (!backend.backend.isReady || dragDownTime == 0L) return@safe
        val sx = dx * cursorSpeed
        val sy = dy * cursorSpeed
        // Resize axis lock: when a single edge was grabbed, pin the off-axis so the
        // cursor stays glued to the edge being dragged (corner grabs move both;
        // a plain move has no lock and moves both).
        val lockedX = dragLockX != null // vertical edge → X is the resize axis
        val lockedY = dragLockY != null // horizontal edge → Y is the resize axis
        val isResize = lockedX || lockedY
        if (!isResize || lockedX) cursorX = (cursorX + sx).coerceIn(0f, (displayWidth - 1).toFloat())
        if (!isResize || lockedY) cursorY = (cursorY + sy).coerceIn(0f, (displayHeight - 1).toFloat())
        // For a resize-edge grab, the touch follows the cursor on the free axis
        // but we still send the cursor coords (the dragged edge tracks the finger).
        backend.backend.pointer(
            action = MotionEvent.ACTION_MOVE,
            x = cursorX, y = cursorY, displayId = displayId,
            source = InputDevice.SOURCE_TOUCHSCREEN, buttonState = 0, downTime = dragDownTime,
        )
    }

    fun dragEnd() = safe {
        if (captionMoveActive) {
            captionMoveActive = false
            postCaptionMoveResize() // land the final position exactly
            moveTaskId = -1
            // The pill just MOVED with its window: the cached handle rect now
            // points at the pill's OLD position, and the 250ms scan + 600ms
            // grace kept honoring it — field bug: a phantom grab zone lingered
            // where the pill used to be (~100px off after a long move). Drop
            // the cache and force an immediate rescan; the press-time test
            // queries fresh rects on an empty cache, so the next grab is
            // judged against the pill's REAL position.
            cachedHandles = emptyList()
            lastHandleRectsAt = 0L
            lastResizeTaskCacheAt = 0L
            Log.d(TAG, "dragEnd CAPTION shell-move → ($cursorX,$cursorY) — handle cache invalidated")
            return@safe
        }
        if (a11yDragActive) {
            a11yDragActive = false
            // Slow swipe (350ms) so it reads as a drag, not a fling. Window-move
            // works; text-select may need a long-press dwell a11y can't easily add.
            PortalPadAccessibilityService.instance?.dispatchSwipe(
                displayId, a11yDragStartX, a11yDragStartY, cursorX, cursorY, 350L, "drag",
            )
            Log.d(TAG, "a11y dragEnd (${a11yDragStartX.toInt()},${a11yDragStartY.toInt()})→(${cursorX.toInt()},${cursorY.toInt()})")
            return@safe
        }
        val backend = PortalPadApp.instance.clickBackend as? ClickBackend.ShizukuUserService
            ?: return@safe
        if (dragDownTime == 0L) return@safe
        if (backend.backend.isReady) {
            backend.backend.pointer(
                action = MotionEvent.ACTION_UP,
                x = cursorX, y = cursorY, displayId = displayId,
                source = InputDevice.SOURCE_TOUCHSCREEN, buttonState = 0, downTime = dragDownTime,
            )
        }
        dragDownTime = 0L
        dragLockX = null
        dragLockY = null
        Log.d(TAG, "dragEnd → touch UP display=$displayId (${cursorX.toInt()},${cursorY.toInt()})")
        repinImePolicy()
    }

    /**
     * Snap the cursor to the center of the current external display. Used by the
     * trackpad long-press menu's "Recenter Cursor" action when the pointer has
     * drifted off-screen or gotten lost. Updates the visible overlay cursor via
     * the same hover path as a normal move.
     */
    fun recenterCursor() = safe {
        refreshDisplayMetrics()
        cursorX = displayWidth / 2f
        cursorY = displayHeight / 2f
        scheduleHoverInject(cursorX, cursorY)
        clickHaptic()
    }

    /**
     * Sends a single MOUSE-source HOVER_MOVE event via the active click
     * backend. Hover is always emitted (it drives hover-state UI like Chrome's
     * tab highlights); the only kill switch is the compile-time [EMIT_HOVER].
     */
    @Volatile private var lastHoverLogAt: Long = 0

    /**
     * Mirrors a reference mouse-active flag: true when we have an active mouse
     * hover registered with the input dispatcher (i.e. we sent HOVER_ENTER
     * since the last HOVER_EXIT or display change). HOVER_MOVE events without
     * a preceding HOVER_ENTER may be treated as stray pointers by Android,
     * which can cause Chrome's PopupWindow suggestion dropdown (and similar
     * anchor popups) to dismiss when the cursor moves into them. the reference implementation
     * always sends HOVER_ENTER first, then HOVER_MOVE on subsequent updates.
     */
    @Volatile private var hoverActive: Boolean = false

    // ─── Coalesced, off-thread hover injection (#5) ─────────────────────────
    // injectPointer is a BLOCKING binder round-trip to the UserService. Running
    // it on the gesture thread per trackpad delta blocks finger tracking. We
    // instead publish the LATEST position and let a single injector thread emit
    // only the most recent one (collapsing a burst of deltas into ~one inject
    // per cycle). The cursor VISUAL is unaffected — it still moves instantly via
    // the cursorPosition StateFlow on the caller's thread. sendHoverViaBackend
    // is therefore only ever invoked on this injector thread.
    private val injectExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "PortalPad-Inject").apply { isDaemon = true }
    }
    private val pendingHover = java.util.concurrent.atomic.AtomicReference<FloatArray?>(null)
    private val hoverDrainScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Publish the latest cursor position and ensure a drain is scheduled.
     *  Returns immediately — no IPC on the caller (gesture) thread. */
    private fun scheduleHoverInject(x: Float, y: Float) {
        // While the a11y input path is active (untrusted display), backend
        // hover events are at best useless (Shizuku events don't land in
        // foreign windows there) and at worst they RACE the a11y gestures and
        // get them cancelled — the Sony trackpad bug. Suppress the stream and
        // park any pointer left over from before the a11y state applied.
        // Throttled log so user-submitted captures show this mode is active.
        if (useA11y()) {
            pendingHover.set(null)
            val now = SystemClock.uptimeMillis()
            if (now - lastHoverLogAt > 1000) {
                Log.d(TAG, "hover SUPPRESSED (a11y input path active) disp=$displayId")
                lastHoverLogAt = now
            }
            if (hoverActive) {
                runCatching { injectExecutor.execute { sendHoverExitViaBackend(x, y) } }
            }
            return
        }
        pendingHover.set(floatArrayOf(x, y))
        if (hoverDrainScheduled.compareAndSet(false, true)) {
            runCatching {
                injectExecutor.execute {
                    hoverDrainScheduled.set(false)
                    val p = pendingHover.getAndSet(null) ?: return@execute
                    runCatching { sendHoverViaBackend(p[0], p[1]) }
                }
            }
        }
    }

    /** Drain the pending hover synchronously before a tap/click/drag so the last
     *  move lands BEFORE the touch (preserving move → exit → touch order). The
     *  injector thread is single-threaded, so once this returns no hover inject
     *  is in flight. Bounded wait — never hangs the gesture thread on a stall. */
    private fun flushPendingHover() {
        val p = pendingHover.getAndSet(null) ?: return
        runCatching {
            injectExecutor.submit { sendHoverViaBackend(p[0], p[1]) }
                .get(50, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Hover events provide live mouse-position feedback to whatever's on
     * the target display. With overlays now using
     * TYPE_ACCESSIBILITY_OVERLAY (2032) when accessibility is bound,
     * Chrome's popup behavior should be stable. Re-enabling hover so
     * users see proper hover state in apps (highlights, tooltips, etc.)
     *
     *   true  — hover events fire normally (default for production)
     *   false — hover injection entirely disabled at compile time
     */
    private val EMIT_HOVER = true

    private fun sendHoverViaBackend(x: Float, y: Float) {
        if (!EMIT_HOVER) {
            // Hard kill — bypass pref read entirely. Cursor overlay still
            // moves visually via the cursorPosition StateFlow; only the
            // injected MotionEvents are skipped.
            //
            // Log once per second so it's visible in logcat that this mode
            // is active, without spamming.
            val now = SystemClock.uptimeMillis()
            if (now - lastHoverLogAt > 1000) {
                Log.d(TAG, "hover HARD-DISABLED at compile time (EMIT_HOVER=false) disp=$displayId pos=(${x.toInt()},${y.toInt()})")
                lastHoverLogAt = now
            }
            return
        }

        val backend = PortalPadApp.instance.clickBackend
        when (backend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) return
                val now = SystemClock.uptimeMillis()
                // Mirror the reference implementation's pattern: send HOVER_ENTER (action 9) before
                // the first HOVER_MOVE following any HOVER_EXIT. The dispatcher
                // needs to see a registered enter event to treat subsequent
                // moves as the same hovering pointer; without it, individual
                // moves can be treated as stray pointers and cause anchor
                // popups (Chrome address-bar suggestions, autocomplete menus)
                // to dismiss when the cursor enters them.
                if (!hoverActive) {
                    // Action 9 = ACTION_HOVER_ENTER, source 8194 = MOUSE | POINTER
                    backend.backend.pointer(
                        action = 9, x = x, y = y, displayId = displayId,
                        source = 8194, buttonState = 0, downTime = now,
                    )
                    hoverActive = true
                    Log.d(TAG, "hover ENTER disp=$displayId pos=(${x.toInt()},${y.toInt()})")
                }
                // 8194 = SOURCE_MOUSE | CLASS_POINTER, action 7 = HOVER_MOVE
                backend.backend.pointer(
                    action = MotionEvent.ACTION_HOVER_MOVE,
                    x = x, y = y, displayId = displayId,
                    source = 8194, buttonState = 0, downTime = now,
                )
                // Throttled — every 150ms — to avoid log flooding while still
                // showing motion presence around suspected dismiss events.
                if (PortalPadApp.verboseHoverLog && now - lastHoverLogAt > 150) {
                    Log.d(TAG, "hover disp=$displayId pos=(${x.toInt()},${y.toInt()})")
                    lastHoverLogAt = now
                }
            }
            is ClickBackend.Shell -> {
                // No-op: shell `input` command doesn't support hover events.
                // The cursor overlay still moves visibly.
            }
        }
    }

    /**
     * Park the virtual mouse cursor with a HOVER_EXIT event before injecting
     * a touch sequence. Without this, Android's input dispatcher sees a
     * mouse mid-hover when a touch arrives at the same coordinates and may
     * reject the touch or misroute it. A reference mirror-cancel / hover-exit
     * routine does the same dance.
     */
    private fun sendHoverExitViaBackend(x: Float, y: Float) {
        if (!EMIT_HOVER) return
        val backend = PortalPadApp.instance.clickBackend
        if (backend !is ClickBackend.ShizukuUserService) return
        if (!backend.backend.isReady) return
        // Land any coalesced hover move still queued on the injector thread
        // BEFORE this exit (and the touch that follows), so ordering stays
        // move → exit → touch and the exit sees correct hoverActive state.
        flushPendingHover()
        if (!hoverActive) return  // Already exited; no-op
        val now = SystemClock.uptimeMillis()
        // Action 10 = ACTION_HOVER_EXIT, source 8194 = MOUSE
        backend.backend.pointer(
            action = 10, x = x, y = y, displayId = displayId,
            source = 8194, buttonState = 0, downTime = now,
        )
        hoverActive = false
        Log.d(TAG, "hover EXIT disp=$displayId pos=(${x.toInt()},${y.toInt()})")
    }

    /**
     * Click via whichever backend the user picked in Settings → System → Click method.
     * No silent fallback — if the chosen backend isn't ready, we fail loudly via
     * the debug toast so the user can fix it.
     */
    fun leftClick() = safe {
        clickHaptic()
        val x = cursorX.toInt(); val y = cursorY.toInt()
        // Record deliberate glasses taps so the accessibility detector can require
        // a recent tap before opening the relay (a tapped field opens it; an app's
        // launch-autofocused field does not). Only count taps on the glasses (the
        // VD), not the phone's own display 0.
        if (displayId != 0) {
            PortalPadApp.instance.lastGlassesTapAt = android.os.SystemClock.elapsedRealtime()
        }
        // Notify desktop-mode window controls of the click coordinate (used for
        // click-to-grab window move/resize). Cheap no-op when nothing observes.
        emitClickEvent(x.toFloat(), y.toFloat())
        // DeX-style Recents purge for SAMSUNG's own close controls (caption X /
        // pill-menu X) — SystemUI-owned buttons whose clicks never route
        // through FreeformManager.close.
        maybePurgeClosedTask(x.toFloat(), y.toFloat())
        // A click on the external display can open/close the pill menu, which
        // changes the whole zone model (the pill stops being a drag target
        // while its menu is up). Invalidate the TTL cache now AND after the
        // menu's animate-in, so the next region pass reads fresh state instead
        // of dragging on stale zones (19:41:08.966: onPill=true with the menu
        // open).
        if (displayId != 0) {
            lastResizeTaskCacheAt = 0L
            Thread {
                runCatching {
                    Thread.sleep(400)
                    lastResizeTaskCacheAt = 0L
                }
            }.start()
        }
        // Accessibility fallback: on a NON-trusted display (the trusted VD
        // couldn't be created), Shizuku-injected touches frequently don't land in
        // foreign app windows. An a11y-dispatched gesture is system-sourced and
        // honored without display trust, so prefer it there. Falls through to the
        // normal backend if the a11y service isn't connected / can't dispatch.
        if (displayId != 0 && (!usingVd || !activeBackendReady())) {
            val svc = PortalPadAccessibilityService.instance
            if (svc != null) {
                // Park any injected mouse hover FIRST (mirrors the Shizuku
                // branch below): a live backend hover pointer racing the a11y
                // gesture gets the gesture cancelled by the dispatcher — the
                // Sony "trackpad taps don't register / air mouse fine" bug
                // (trackpad = finger moving ms before the tap = hover in
                // flight; air mouse = still when clicking = clean pipeline).
                sendHoverExitViaBackend(x.toFloat(), y.toFloat())
                if (svc.dispatchTap(displayId, x.toFloat(), y.toFloat())) {
                    debugToast("Left click → a11y gesture display=$displayId ($x,$y)")
                    return@safe
                }
            }
        }
        val backend = PortalPadApp.instance.clickBackend

        when (backend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) {
                    debugToast("Left click BLOCKED — Shizuku UserService not bound")
                    return@safe
                }
                // 1) Park the mouse cursor with HOVER_EXIT so the touch event
                //    isn't filtered by Android's input dispatcher as a
                //    mouse-button spoof. A reference implementation does the same.
                sendHoverExitViaBackend(x.toFloat(), y.toFloat())
                // 2) Real TOUCHSCREEN tap — this is what the OS routes to
                //    the standard tap-gesture detectors that mobile apps use.
                //    Mouse BUTTON_PRIMARY events don't trigger taps in most
                //    apps, so we stick with touchscreen here.
                val ok = backend.backend.tap(displayId, x.toFloat(), y.toFloat())
                debugToast(
                    if (ok) "Left click → Shizuku (touch) display=$displayId ($x,$y)"
                    else "Left click → Shizuku tap returned false"
                )
                // Re-pin IME so it doesn't bounce displays after the click.
                repinImePolicy()
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) {
                    debugToast("Left click BLOCKED — ${backend.displayName} not ready")
                    Log.w(TAG, "leftClick: ${backend.displayName} not ready")
                    return@safe
                }
                debugToast("Left click → ${backend.displayName} display=$displayId ($x,$y)")
                backend.access.execShell("input ${displayFlag()}tap $x $y")
            }
        }
    }

    /**
     * Tap at an EXPLICIT point on the external display (not the cursor), via the
     * same proven backend path as [leftClick]. Used to raise a freeform window
     * by tapping its caption strip — the one reliable way to reorder freeform
     * windows on Samsung (move-to-front is a no-op there). No haptic, doesn't
     * move the cursor.
     */
    fun tapAt(x: Float, y: Float) = safe {
        val ix = x.toInt(); val iy = y.toInt()
        when (val backend = PortalPadApp.instance.clickBackend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) return@safe
                sendHoverExitViaBackend(x, y)
                backend.backend.tap(displayId, x, y)
                repinImePolicy()
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) return@safe
                backend.access.execShell("input ${displayFlag()}tap $ix $iy")
            }
        }
    }

    /**
     * Right-click. Long-press on TOUCHSCREEN source — most apps interpret this
     * as a context-menu request (where supported) or fall back to the standard
     * long-press behavior (which on text fields is select-word). Mouse
     * BUTTON_SECONDARY would be semantically cleaner but most mobile apps
     * don't react to mouse-button events at all, so we use the touch path
     * that universally works.
     *
     * Like leftClick, we send HOVER_EXIT first so any in-flight mouse-hover
     * state is cleared before the touch sequence — otherwise Android's input
     * dispatcher may reject or misroute the touch.
     */
    fun rightClick() = safe {
        clickHaptic()
        // If the cursor is over PortalPad's own dock overlay, handle the
        // right-click IN-PROCESS (open the item menu) and skip injection — an
        // injected long-press reaches the dock as a tap and would just launch.
        if (cursorOverDock) {
            _dockRightClickTick.tryEmit(android.os.SystemClock.uptimeMillis())
            return@safe
        }
        // Same in-process handling for the widget overlay (see the flag's doc):
        // an injected long-press would land as a tap, so route the hold to the
        // overlay's edit-mode toggle instead of injecting.
        if (cursorOverWidgetOverlay) {
            _widgetOverlayRightClickTick.tryEmit(android.os.SystemClock.uptimeMillis())
            return@safe
        }
        val x = cursorX.toInt(); val y = cursorY.toInt()
        // Accessibility fallback (see leftClick): on an untrusted display, dispatch
        // the long-press through the a11y framework so it lands in foreign apps.
        if (displayId != 0 && (!usingVd || !activeBackendReady())) {
            val svc = PortalPadAccessibilityService.instance
            if (svc != null) {
                // Park any injected hover first — see leftClick.
                sendHoverExitViaBackend(x.toFloat(), y.toFloat())
                // 600ms, was 1200ms: still comfortably a long-press to apps
                // (ViewConfiguration's default is ~400ms), but half the window
                // during which the single-gesture a11y pipe is OCCUPIED — the
                // Sony log showed 19 of 22 of these cancelled by the user's
                // next input fighting through, each one a 1.2s dead zone that
                // read as "input not ready". Also halves the overshoot when a
                // resting finger triggers touch-hold select unintentionally.
                if (svc.dispatchLongPress(displayId, x.toFloat(), y.toFloat(), 600L)) {
                    debugToast("Right click → a11y long-press display=$displayId ($x,$y)")
                    return@safe
                }
            }
        }
        val backend = PortalPadApp.instance.clickBackend

        when (backend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) {
                    debugToast("Right click BLOCKED — Shizuku UserService not bound")
                    return@safe
                }
                sendHoverExitViaBackend(x.toFloat(), y.toFloat())
                val ok = backend.backend.longPress(displayId, x.toFloat(), y.toFloat(), 1200L)
                debugToast(
                    if (ok) "Right click → Shizuku (touch long-press) display=$displayId"
                    else "Right click → Shizuku long-press returned false"
                )
                repinImePolicy()
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) {
                    debugToast("Right click BLOCKED — ${backend.displayName} not ready")
                    return@safe
                }
                debugToast("Right click → ${backend.displayName} display=$displayId ($x,$y)")
                backend.access.execShell("input ${displayFlag()}swipe $x $y $x $y 1200")
            }
        }
    }

    /**
     * Complete a double-tap. This is reached ONLY after the first tap of the
     * pair already fired as an eager [leftClick] (see the single-tap path in
     * TrackpadSurface). So a double-tap is finished by injecting exactly ONE
     * more tap through the SAME proven path as leftClick — hover-exit → touch
     * tap → repin — not a fresh pair of raw taps. Two clean taps separated by
     * the user's real inter-tap gap read as a proper double-tap in apps; the
     * previous two back-to-back taps with NO hover-exit were the ones the
     * dispatcher dropped, which is why double-taps landed as singles or were
     * ignored, and why one physical double-tap injected three taps total.
     */
    fun doubleClick() = safe {
        clickHaptic()
        val x = cursorX.toInt(); val y = cursorY.toInt()
        if (displayId != 0) {
            PortalPadApp.instance.lastGlassesTapAt = android.os.SystemClock.elapsedRealtime()
        }
        emitClickEvent(x.toFloat(), y.toFloat())
        // Accessibility fallback (see leftClick) — previously MISSING here:
        // singles routed a11y and landed while doubles went Shizuku-touch into
        // the untrusted display and vanished, i.e. "double tap reads as single
        // tap" on Sony-type devices. One atomic two-stroke gesture.
        if (displayId != 0 && (!usingVd || !activeBackendReady())) {
            val svc = PortalPadAccessibilityService.instance
            if (svc != null) {
                sendHoverExitViaBackend(x.toFloat(), y.toFloat())
                if (svc.dispatchDoubleTap(displayId, x.toFloat(), y.toFloat())) {
                    debugToast("Double click → a11y double-tap display=$displayId ($x,$y)")
                    return@safe
                }
            }
        }
        val backend = PortalPadApp.instance.clickBackend

        when (backend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) {
                    debugToast("Double click BLOCKED — Shizuku UserService not bound")
                    return@safe
                }
                sendHoverExitViaBackend(x.toFloat(), y.toFloat())
                val ok = backend.backend.tap(displayId, x.toFloat(), y.toFloat())
                debugToast(
                    if (ok) "Double click → Shizuku (touch) display=$displayId ($x,$y)"
                    else "Double click → Shizuku tap returned false"
                )
                repinImePolicy()
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) {
                    debugToast("Double click BLOCKED — ${backend.displayName} not ready")
                    return@safe
                }
                debugToast("Double click → ${backend.displayName} display=$displayId ($x,$y)")
                backend.access.execShell("input ${displayFlag()}tap $x $y")
            }
        }
    }

    // Scroll remainder accumulation — small/slow scroll deltas that don't yet
    // amount to a real swipe are KEPT and added to the next ones, instead of
    // being thrown away. That dropping is what made scrolling choppy at 1.0x
    // (sub-threshold steps discarded) yet smooth at 1.5x (steps big enough to
    // clear the threshold every time).
    private var scrollAccumX = 0f
    private var scrollAccumY = 0f
    private var lastScrollAt = 0L

    /** Scroll via short swipe — same backend logic as click. */
    fun scroll(dx: Float, dy: Float) = safe {
        val mult = scrollSpeed * if (invertScroll) -1f else 1f
        val now = SystemClock.uptimeMillis()
        // A pause resets the accumulator so a later flick doesn't combine with a
        // stale leftover from a previous, unrelated scroll.
        if (now - lastScrollAt > 250) { scrollAccumX = 0f; scrollAccumY = 0f }
        lastScrollAt = now
        scrollAccumX += dx * mult
        scrollAccumY += dy * mult
        val cx = cursorX.toInt(); val cy = cursorY.toInt()
        val endX = (cx - scrollAccumX * 2).toInt().coerceIn(0, displayWidth - 1)
        val endY = (cy - scrollAccumY * 2).toInt().coerceIn(0, displayHeight - 1)
        // GUARD: a swipe whose start and end are nearly the same point is
        // interpreted by Android as a TAP/CLICK, not a scroll — and a SHORT
        // swipe dispatched via the a11y path is short enough that some apps
        // (e.g. Chrome) still register it as a tap on whatever's under the
        // cursor. So we require a larger minimum travel before injecting: below
        // it we keep accumulating (slow scrolls aren't lost), and once we cross
        // it the injected gesture is unambiguously a swipe. This is what stops a
        // near-stationary edge-strip hold from landing as a click.
        val travel = kotlin.math.hypot((endX - cx).toDouble(), (endY - cy).toDouble())
        if (travel < 24.0) {
            return@safe
        }
        // The accumulated motion is being delivered as this swipe — clear it.
        scrollAccumX = 0f
        scrollAccumY = 0f
        if (useA11y()) {
            // Feed the swipe vector to the paced/coalesced a11y scroller — one
            // gesture in flight at a time, so swipes don't cancel each other.
            PortalPadAccessibilityService.instance?.scrollGesture(
                displayId, cx.toFloat(), cy.toFloat(),
                (endX - cx).toFloat(), (endY - cy).toFloat(),
                displayWidth, displayHeight,
            )
            return@safe
        }
        val backend = PortalPadApp.instance.clickBackend

        when (backend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) return@safe
                backend.backend.swipe(
                    displayId, cx.toFloat(), cy.toFloat(),
                    endX.toFloat(), endY.toFloat(), durationMs = 80L,
                )
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) return@safe
                backend.access.execShell("input ${displayFlag()}swipe $cx $cy $endX $endY 80")
            }
        }
    }

    /**
     * Mouse-wheel scroll via a discrete ACTION_SCROLL event at the cursor — no
     * fling/momentum, unlike [scrollPixels]'s swipe. [vNotches] +1 = up.
     */
    fun wheelScroll(vNotches: Float) = safe {
        val be = PortalPadApp.instance.activeBoundBackend ?: return@safe
        if (!be.isReady) return@safe
        be.injectScroll(cursorX, cursorY, vNotches, 0f, displayId)
    }

    /**
     * Scroll by an explicit pixel delta from the cursor, WITHOUT the trackpad's
     * scrollSpeed / invertScroll (the external mouse manages its own speed and
     * direction). A positive [dy] swipes the finger downward, which scrolls page
     * content UP (toward the top) — matching a standard mouse wheel-up.
     */
    fun scrollPixels(dx: Float, dy: Float) = safe {
        val cx = cursorX.toInt(); val cy = cursorY.toInt()
        val endX = (cx + dx).toInt().coerceIn(0, displayWidth - 1)
        val endY = (cy + dy).toInt().coerceIn(0, displayHeight - 1)
        val travel = kotlin.math.hypot((endX - cx).toDouble(), (endY - cy).toDouble())
        if (travel < 8.0) return@safe
        when (val backend = PortalPadApp.instance.clickBackend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) return@safe
                backend.backend.swipe(
                    displayId, cx.toFloat(), cy.toFloat(),
                    endX.toFloat(), endY.toFloat(), durationMs = 80L,
                )
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) return@safe
                backend.access.execShell("input ${displayFlag()}swipe $cx $cy $endX $endY 80")
            }
        }
    }

    // ── Remote D-pad "Scroll" mode ────────────────────────────────────────
    // In Scroll mode the remote's D-pad arrows fire a directional swipe anchored
    // at the centre of the external display (for apps that ignore D-pad keys),
    // and OK becomes a tap / long-press at that centre. Mirrors the backend
    // dispatch used by scroll() / tapAt() / rightClick().
    private fun remoteSwipe(dx: Float, dy: Float) = safe {
        val cx = displayWidth / 2f
        val cy = displayHeight / 2f
        val ex = (cx + dx).coerceIn(0f, (displayWidth - 1).toFloat())
        val ey = (cy + dy).coerceIn(0f, (displayHeight - 1).toFloat())
        when (val backend = PortalPadApp.instance.clickBackend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) return@safe
                backend.backend.swipe(displayId, cx, cy, ex, ey, durationMs = 80L)
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) return@safe
                backend.access.execShell(
                    "input ${displayFlag()}swipe ${cx.toInt()} ${cy.toInt()} ${ex.toInt()} ${ey.toInt()} 80",
                )
            }
        }
    }

    /** Scroll-mode arrows. "Down" reveals content below (finger swipes up). */
    fun remoteScrollDown() = remoteSwipe(0f, -displayHeight / 3f)
    fun remoteScrollUp() = remoteSwipe(0f, displayHeight / 3f)
    fun remoteScrollRight() = remoteSwipe(-displayWidth / 3f, 0f)
    fun remoteScrollLeft() = remoteSwipe(displayWidth / 3f, 0f)

    /** Tap point for the scroll-mode / reader center tap: the centre of the
     *  foreground app window on the injection display, or the display centre when
     *  that can't be resolved (a11y unbound, no app window, empty bounds). Never
     *  worse than the plain display-centre tap — only more accurate for a freeform
     *  window, where the app isn't at display centre. Cheap: in-memory a11y snapshot. */
    private fun centerTapPoint(): Pair<Float, Float> {
        val c = com.portalpad.app.service.PortalPadAccessibilityService.instance
            ?.let { runCatching { it.foregroundAppWindowCenter(displayId) }.getOrNull() }
        return c ?: (displayWidth / 2f to displayHeight / 2f)
    }

    /** Scroll-mode / reader OK: a tap at the centre of the foreground app window
     *  on the external display (display centre as fallback). Also what the Reader
     *  "Exit Zoom" button sends — a tap is what dismisses Kindle's zoom overlay. */
    fun remoteCenterTap() {
        val (x, y) = centerTapPoint()
        tapAt(x, y)
    }

    /** Scroll-mode long-press OK: a touch long-press at the centre. */
    fun remoteCenterLongPress() = safe {
        val x = displayWidth / 2f
        val y = displayHeight / 2f
        when (val backend = PortalPadApp.instance.clickBackend) {
            is ClickBackend.ShizukuUserService -> {
                if (!backend.backend.isReady) return@safe
                backend.backend.longPress(displayId, x, y, 1200L)
            }
            is ClickBackend.Shell -> {
                if (!backend.access.isReady) return@safe
                backend.access.execShell(
                    "input ${displayFlag()}swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} 1200",
                )
            }
        }
    }

    private fun setEventDisplay(event: MotionEvent) {
        if (displayId == 0) return
        runCatching {
            MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
        }
    }

    private fun displayFlag(): String = if (displayId != 0) "-d $displayId " else ""

    /**
     * Debug breadcrumb — when the SHOW_CLICK_TOASTS pref is on, surfaces every
     * click attempt as a short toast on the phone screen. Lets the user verify
     * whether clicks are being processed at all, and at what coordinates,
     * without needing to run logcat.
     */
    private fun debugToast(message: String) {
        // Always log to logcat so the in-app logcat viewer captures it
        // regardless of the toast pref.
        Log.d(TAG, message)
        // Toast is gated by the SHOW_CLICK_TOASTS pref.
        val show = runCatching {
            kotlinx.coroutines.runBlocking {
                com.portalpad.app.PortalPadApp.instance.prefs.bool(
                    com.portalpad.app.data.PreferencesRepository.Keys.SHOW_CLICK_TOASTS,
                    default = false,
                ).first()
            }
        }.getOrDefault(false)
        if (!show) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─────────────────────────── legacy touchscreen tap ───────────────────────────

    fun tap(x: Float, y: Float) = safe {
        val now = SystemClock.uptimeMillis()
        downTime = now
        sendTouchscreen(MotionEvent.ACTION_DOWN, x, y)
        sendTouchscreen(MotionEvent.ACTION_UP, x, y)
    }

    private fun sendTouchscreen(action: Int, x: Float, y: Float) {
        if (!access.canInjectInProcess) {
            if (access.isReady && action == MotionEvent.ACTION_UP) {
                runCatching {
                    access.execShell("input ${displayFlag()}tap ${x.toInt()} ${y.toInt()}")
                }
            }
            return
        }
        val event = try {
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).also {
                it.source = InputDevice.SOURCE_TOUCHSCREEN
                setEventDisplay(it)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MotionEvent.obtain failed for touchscreen", t)
            return
        }
        try {
            injectInProcess(event)
        } catch (t: Throwable) {
            Log.w(TAG, "touchscreen inject failed", t)
            if (access.isReady && action == MotionEvent.ACTION_UP) {
                runCatching {
                    access.execShell("input ${displayFlag()}tap ${x.toInt()} ${y.toInt()}")
                }
            }
        } finally {
            event.recycle()
        }
    }

    // ─────────────────────────── keys ───────────────────────────

    fun pressKey(keycode: Int, repin: Boolean = true) = safe {
        clickHaptic()
        // Run the actual injection off the UI thread so the button's visual
        // feedback stays instant while the key is delivered in the background.
        val backend = PortalPadApp.instance.clickBackend
        val targetDisplay = displayId
        val tapNs = System.nanoTime()
        keyExecutor.execute {
            val startNs = System.nanoTime()
            // Guard the whole injection: when Shizuku's binder is dead, the IPC
            // calls below (backend.key / execShell) throw DeadObjectException /
            // RemoteException on this background thread — an uncaught throw here
            // is FATAL and crashes PortalPad. Swallow it so a key press during a
            // Shizuku outage is a no-op instead of a crash (the on-screen banner
            // already tells the user Shizuku is down).
            try {
                when (backend) {
                    is ClickBackend.ShizukuUserService -> {
                        if (backend.backend.isReady) {
                            val ok = backend.backend.key(targetDisplay, keycode)
                            if (!ok && access.isReady) {
                                access.execShell("input ${displayFlag()}keyevent $keycode")
                            }
                        } else if (access.isReady) {
                            access.execShell("input ${displayFlag()}keyevent $keycode")
                        }
                    }
                    is ClickBackend.Shell -> {
                        if (access.isReady) {
                            access.execShell("input ${displayFlag()}keyevent $keycode")
                        }
                    }
                }
                // Re-pin IME so a key event doesn't bounce the IME display — but
                // ONLY for text-input keys. D-pad, media, volume, and nav keys
                // pass repin=false: they don't affect IME placement, and the
                // re-pin (a blocking DataStore read + a Shizuku IPC) added
                // noticeable latency. Skipped while the relay keyboard is active.
                if (repin && !suppressImeRepin) repinImePolicy()
            } catch (t: Throwable) {
                Log.w(TAG, "key inject failed (backend unavailable?) keycode=$keycode", t)
                return@execute
            }
            // Latency diagnostics: queue-wait (tap→worker start) + inject time.
            val doneNs = System.nanoTime()
            Log.d(
                TAG,
                "KEY $keycode queueWait=${(startNs - tapNs) / 1_000_000}ms " +
                    "inject=${(doneNs - startNs) / 1_000_000}ms " +
                    "total=${(doneNs - tapNs) / 1_000_000}ms",
            )
        }
    }

    /**
     * A complete key PRESS (down → 12ms → up) carrying [metaState], for typing
     * shifted characters from the relay keyboard: capitals and symbols like
     * @ # $ & * ( ) " : ! ? need META_SHIFT_ON on the key events themselves.
     * The 12ms gap matches the server-side injectKeyPressInternal spacing —
     * without a real gap the dispatcher silently drops occasional presses.
     * Runs on keyExecutor (12ms there costs nothing user-visible). Shell
     * fallback is degraded (input keyevent can't carry meta) and only fires
     * for meta-less presses so it can never type the WRONG character.
     */
    fun pressKeyWithMeta(keycode: Int, metaState: Int) = safe {
        val targetDisplay = displayId
        keyExecutor.execute {
            try {
                when (val backend = PortalPadApp.instance.clickBackend) {
                    is ClickBackend.ShizukuUserService -> if (backend.backend.isReady) {
                        backend.backend.injectKey(keycode, KeyEvent.ACTION_DOWN, metaState, targetDisplay)
                        try { Thread.sleep(12) } catch (_: InterruptedException) {}
                        backend.backend.injectKey(keycode, KeyEvent.ACTION_UP, metaState, targetDisplay)
                    }
                    is ClickBackend.Shell -> if (metaState == 0 && access.isReady) {
                        access.execShell("input ${displayFlag()}keyevent $keycode")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "pressKeyWithMeta failed keycode=$keycode meta=$metaState", t)
            }
        }
    }

    /**
     * Inject a raw hardware-keyboard key edge on the external display for the
     * physical-keyboard relay (the combo device captured by the mouse pipeline).
     * Forwards the literal DOWN/UP and [metaState] (Shift/Ctrl/Alt) so capitals,
     * symbols and chords compose. No haptic (unlike pressKey — this fires once per
     * keystroke), no IME re-pin. No-op when there's no external display.
     */
    fun injectKeyEvent(keycode: Int, down: Boolean, metaState: Int) = safe {
        val targetDisplay = displayId
        if (targetDisplay != 0) {
            val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            keyExecutor.execute {
                try {
                    when (val backend = PortalPadApp.instance.clickBackend) {
                        is ClickBackend.ShizukuUserService -> {
                            if (backend.backend.isReady) {
                                backend.backend.injectKey(keycode, action, metaState, targetDisplay)
                            }
                        }
                        is ClickBackend.Shell -> {
                            // Shell `input keyevent` can't carry meta or split down/up;
                            // emit a plain press on DOWN only (degraded fallback).
                            if (down && access.isReady) {
                                access.execShell("input ${displayFlag()}keyevent $keycode")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "injectKeyEvent failed keycode=$keycode down=$down", t)
                }
            }
        }
    }

    fun back() = pressKey(KeyEvent.KEYCODE_BACK, repin = false)

    /**
     * Back that won't black out the wallpaper. Pressing Back at the bare
     * wallpaper/home (no app open on this display) makes the system tear down the
     * wallpaper backdrop — there's nothing to pop, so it destroys the only layer
     * showing, leaving black. This checks for a REAL app on the display first and
     * only injects Back if one exists; at the wallpaper it's a no-op. On query
     * error we default to sending Back (don't break the primary function).
     */
    fun backGuarded() {
        keyExecutor.execute {
            val hasRealApp = runCatching {
                PortalPadApp.instance.freeform.listTasks(displayId).any { rt ->
                    !rt.packageName.contains("launcher", ignoreCase = true) &&
                        !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                        rt.packageName != "com.portalpad.app"
                }
            }.getOrDefault(true)
            if (hasRealApp) pressKey(KeyEvent.KEYCODE_BACK, repin = false)
        }
    }
    fun home() = pressKey(KeyEvent.KEYCODE_HOME, repin = false)
    fun recents() = pressKey(KeyEvent.KEYCODE_APP_SWITCH, repin = false)

    fun mediaPlayPause() = pressKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, repin = false)
    fun mediaNext() = pressKey(KeyEvent.KEYCODE_MEDIA_NEXT, repin = false)
    fun mediaPrev() = pressKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, repin = false)
    fun mediaFf() = pressKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, repin = false)
    fun mediaRew() = pressKey(KeyEvent.KEYCODE_MEDIA_REWIND, repin = false)
    fun mediaStop() = pressKey(KeyEvent.KEYCODE_MEDIA_STOP, repin = false)
    fun mediaSkip10s(forward: Boolean) { if (forward) mediaFf() else mediaRew() }
    fun volUp() = pressKey(KeyEvent.KEYCODE_VOLUME_UP, repin = false)
    fun volDown() = pressKey(KeyEvent.KEYCODE_VOLUME_DOWN, repin = false)
    fun volMute() = pressKey(KeyEvent.KEYCODE_VOLUME_MUTE, repin = false)

    fun dpadUp() = pressKey(KeyEvent.KEYCODE_DPAD_UP, repin = false)
    fun dpadDown() = pressKey(KeyEvent.KEYCODE_DPAD_DOWN, repin = false)
    fun dpadLeft() = pressKey(KeyEvent.KEYCODE_DPAD_LEFT, repin = false)
    fun dpadRight() = pressKey(KeyEvent.KEYCODE_DPAD_RIGHT, repin = false)

    // Reader page-flip keys (focus-following, so they work in freeform windows).
    fun pageUp() = pressKey(KeyEvent.KEYCODE_PAGE_UP, repin = false)
    fun pageDown() = pressKey(KeyEvent.KEYCODE_PAGE_DOWN, repin = false)
    fun dpadCenter() = pressKey(KeyEvent.KEYCODE_DPAD_CENTER, repin = false)
    fun enter() = pressKey(KeyEvent.KEYCODE_ENTER)

    // Programmable shortcut keys — visually colored Red/Green/Yellow/Blue on
    // the Remote tab, but they send the generic gamepad button keycodes
    // (BUTTON_1..4) tagged with SOURCE_GAMEPAD. Reasoning:
    //  • BUTTON_1..4 are obscure enough that nothing on Android claims them
    //    natively (unlike A/B/X/Y which some games may bind).
    //  • SOURCE_GAMEPAD routes through the gamepad input pipeline, which is
    //    more permissive to app-injected events than the keyboard pipeline.
    //  • Key Mapper / Button Mapper have first-class gamepad-button support,
    //    so these should be capturable for remapping. (Still a known wall:
    //    injected events from a synthetic device id may bypass accessibility
    //    listeners regardless — gamepad is our best shot.)
    fun progRed() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_1)
    fun progGreen() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_2)
    fun progYellow() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_3)
    fun progBlue() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_4)

    // Display-0 (phone) variants used ONLY by the floating remap helper. The
    // helper feeds a key-mapping app that runs on the PHONE, whereas normal
    // program-key taps target the external display (injector.displayId). Without
    // forcing display 0 here, the gamepad press was injected at the glasses and
    // the phone-side recorder never saw it — the cause of "remap stopped working".
    fun progRedOnPhone() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_1, displayOverride = 0)
    fun progGreenOnPhone() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_2, displayOverride = 0)
    fun progYellowOnPhone() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_3, displayOverride = 0)
    fun progBlueOnPhone() = pressGamepadKey(KeyEvent.KEYCODE_BUTTON_4, displayOverride = 0)

    /**
     * Like [pressKey] but injects with SOURCE_GAMEPAD. Used by the program
     * keys; never re-pins IME (gamepad buttons aren't text input).
     */
    private fun pressGamepadKey(keycode: Int, displayOverride: Int? = null) = safe {
        val backend = PortalPadApp.instance.clickBackend
        val targetDisplay = displayOverride ?: displayId
        // Shell-fallback display flag honoring the override (displayFlag() reads the
        // injector's own displayId, which the override exists to bypass).
        val flag = if (targetDisplay != 0) "-d $targetDisplay " else ""
        Log.d(
            TAG,
            "pressGamepadKey keycode=$keycode target=$targetDisplay " +
                "override=$displayOverride backend=${backend?.javaClass?.simpleName} " +
                "shizukuReady=${(backend as? ClickBackend.ShizukuUserService)?.backend?.isReady} " +
                "accessReady=${access.isReady}",
        )
        keyExecutor.execute {
            // Same dead-binder guard as pressKey: IPC on this background thread
            // would otherwise crash the app when Shizuku is down.
            try {
                if (backend is ClickBackend.ShizukuUserService && backend.backend.isReady) {
                    Log.d(TAG, "gamepad → Shizuku gamepadKey(disp=$targetDisplay, $keycode)")
                    backend.backend.gamepadKey(targetDisplay, keycode)
                } else if (access.isReady) {
                    // Shell fallback — `input keyevent` doesn't accept a source
                    // override, but at least the keycode goes out.
                    Log.d(TAG, "gamepad → shell: input ${flag}keyevent $keycode")
                    access.execShell("input ${flag}keyevent $keycode")
                } else {
                    Log.w(TAG, "gamepad → NO backend ready (key dropped) keycode=$keycode")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "gamepad key inject failed (backend unavailable?) keycode=$keycode", t)
            }
        }
    }

    /** Universal safety wrapper — any inject failure logs but never crashes. */
    private inline fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.e(TAG, "inject failed", t) }
    }

    /**
     * Re-pin the IME policy for the external display. the reference implementation's b() pattern:
     * call this after every click, every key event, every display change.
     * Without continuous re-pinning, Android's IME bounces between displays
     * when focus changes (which happens on every hover event over a text
     * field), causing the keyboard to disappear mid-type.
     *
     * Policy values from android.view.WindowManager:
     *  0 = DISPLAY_IME_POLICY_LOCAL    → IME on the focused display (external)
     *  1 = DISPLAY_IME_POLICY_FALLBACK → IME on default display (phone)
     *  2 = DISPLAY_IME_POLICY_HIDE     → no IME for this display
     *
     * IME policy is THE confirmed root cause of the Chrome address-bar
     * dropdown bug (found by comparing dumpsys window/input between the reference implementation
     * and PortalPad with a live dropdown):
     *
     *   the reference implementation: InputMethod window on displayId=71 (the VD, with Chrome).
     *            IME policy = LOCAL (0). Chrome's omnibox dropdown shares a
     *            display with its IME, so focus/IME re-evaluation stays
     *            within the VD and the dropdown survives cursor motion.
     *
     *   PortalPad (old): we forced IME policy = HIDE (2), so the
     *            InputMethod window lived on displayId=0 (the phone) while
     *            Chrome (the imeInputTarget) was on the VD. That cross-
     *            display IME relationship meant ANY display event (cursor
     *            move, screencap, relayout) re-evaluated the IME target
     *            across displays and dismissed Chrome's dropdown. This is
     *            why even a screenshot — which injects no input — killed
     *            the dropdown.
     *
     * Fix: default to LOCAL (0) so the IME lives on the VD with Chrome,
     * exactly like the reference implementation. The IME_ON_EXTERNAL pref still lets the user
     * force it, but the DEFAULT is now LOCAL, not HIDE.
     *
     * The [aggressive] parameter gates the per-click/key re-pin on the dormant
     * [aggressiveImeRepin] flag (currently always off). Display-change re-pins
     * pass aggressive = false and always run (one-shot policy setups).
     */
    private var lastImePolicyDisplay: Int = -1
    /** Every display id we've ever applied an IME policy to this process — so
     *  teardown can reset each one back to LOCAL and never strand the phone
     *  keyboard. (A non-default policy left on a VD that's then destroyed wedges
     *  IME placement on some OEMs until reboot.) */
    private val touchedImeDisplays = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /** Last IME policy successfully APPLIED per display. Every write goes
     *  through a dedupe against this: setting a display's IME policy — even
     *  to the SAME value — forces a display-wide IME re-layout, and Chrome
     *  dismisses its omnibox suggestion dropdown on that relayout (measured:
     *  suggestions died at relay open AND close, both redundant writes for
     *  the auto-relay config). Skipping no-op writes keeps the dropdown
     *  alive through the relay's whole lifecycle. Entries clear on
     *  resetImePolicy so a fresh session re-applies for real. */
    private val imePolicyApplied = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    fun repinImePolicy(aggressive: Boolean = true) {
        if (aggressive) {
            val aggressiveOn = aggressiveImeRepin
            if (!aggressiveOn) {
                Log.d(TAG, "repin SUPPRESSED (aggressive disabled)")
                return
            }
        }
        val targetDisplay = displayId
        if (targetDisplay == 0) return    // local display — no pinning needed
        val backend = PortalPadApp.instance.clickBackend
        val shizuku = (backend as? ClickBackend.ShizukuUserService)?.backend ?: return
        if (!shizuku.isReady) return
        // IME_ON_EXTERNAL pref means "force IME onto external (LOCAL, policy 0)".
        //  • glasses mode (IME_ON_EXTERNAL on) → LOCAL(0): keep the IME on the VD,
        //    the reference implementation's confirmed-good behavior.
        //  • phone mode → FALLBACK(1), relay on or off. History: relay-on used
        //    to pin HIDE(2) so the native keyboard "never leaks" for a VD field
        //    while the relay typed via INJECTED KEYSTROKES. That injection path
        //    is gone (FORWARD_LIVE_KEYSTROKES, KeyboardRelayActivity), and HIDE
        //    became the direct cause of the relay's blink cycle: every debounced
        //    a11y write migrates the IME target to the VD field for a beat, and
        //    a HIDE-policy target fires HIDE_DISPLAY_IME_POLICY_HIDE (kb tests
        //    2026-07-17: hide ~25ms after each write, recovery seconds later).
        //    Under FALLBACK the same migration keeps the keyboard UP on the
        //    phone, briefly bound cross-display, until the relay refocuses —
        //    no hide to recover from. (A 2025-era note says FALLBACK failed for
        //    the relay — that failure was per-key Chrome focus gain from the
        //    injected keystrokes, which no longer exist.)
        // Constants: 0=LOCAL, 1=FALLBACK_DISPLAY, 2=HIDE.
        val imeOnExternal = imeOnExternalEnabled
        val policy = if (imeOnExternal) 0 else 1
        if (imePolicyApplied[targetDisplay] == policy) {
            Log.d(TAG, "repin NO-OP disp=$targetDisplay policy=$policy already applied (dropdown-safe)")
            return
        }
        val ok = runCatching { shizuku.setImePolicy(targetDisplay, policy) }.getOrDefault(false)
        Log.d(TAG, "repin disp=$targetDisplay policy=$policy (imeOnExternal=$imeOnExternal autoOpenRelay=$autoOpenRelayEnabled) aggressive=$aggressive ok=$ok")
        if (ok) imePolicyApplied[targetDisplay] = policy
        lastImePolicyDisplay = targetDisplay
        touchedImeDisplays.add(targetDisplay)
    }


    /**
     * Enter/exit "relay typing" IME mode for the "Type to external display"
     * screen.
     *
     * The relay keeps the PHONE keyboard up on display 0 and forwards each
     * keystroke to Chrome on the VD. The logcat trace showed the failure
     * mechanism: each forwarded key makes Chrome (on the VD) gain window
     * focus and request the IME *visible on the VD*. Samsung's
     * `semComputeImeDisplayIdForTarget` then returns displayToShowIme=-1
     * (it can't place the IME on the VD) and fires
     * HIDE_DISPLAY_IME_POLICY_HIDE — which collapses the phone keyboard
     * serving the relay field. Result: one key types, keyboard vanishes.
     *
     * Fix (current): while relaying, set the VD's IME policy to
     * FALLBACK_DISPLAY (1) so any moment the VD field becomes the IME target
     * (the debounced a11y writes) the keyboard stays up on the phone instead
     * of hiding. HIDE (2) was the keystroke-forwarding-era fix and became the
     * blink cycle's cause once forwarding was removed — see the inline
     * comment below for the full history.
     * On exit we restore the normal policy via repinImePolicy().
     */
    fun setRelayImeMode(active: Boolean) {
        suppressImeRepin = active
        val targetDisplay = displayId
        if (targetDisplay == 0) return
        val backend = PortalPadApp.instance.clickBackend
        val shizuku = (backend as? ClickBackend.ShizukuUserService)?.backend ?: return
        if (!shizuku.isReady) return
        if (active) {
            // 1 = DISPLAY_IME_POLICY_FALLBACK_DISPLAY. HISTORY: this was 2
            // (HIDE) through the keystroke-forwarding era — each INJECTED key
            // made Chrome-on-the-VD gain focus and request IME placement
            // there, and HIDE was the only thing that broke Samsung's failed-
            // placement → hide cascade (FALLBACK was tried then and failed).
            // That per-key focus gain died with FORWARD_LIVE_KEYSTROKES
            // (typing is a11y SET_TEXT now); what remained of HIDE was pure
            // downside: every debounced write briefly makes the VD field the
            // IME target, and a HIDE-policy target fires
            // HIDE_DISPLAY_IME_POLICY_HIDE — the relay's blink cycle (kb
            // tests 2026-07-17). FALLBACK keeps the keyboard up on the phone
            // through those migrations. If the one-key-then-vanish pattern
            // ever returns, capture a log before touching this — the old
            // mechanism CANNOT fire without key injection.
            if (imePolicyApplied[targetDisplay] == 1) {
                Log.d(TAG, "relay IME mode ON: disp=$targetDisplay already FALLBACK — NO-OP (dropdown survives)")
                return
            }
            val ok = runCatching { shizuku.setImePolicy(targetDisplay, 1) }.getOrDefault(false)
            if (ok) imePolicyApplied[targetDisplay] = 1
            touchedImeDisplays.add(targetDisplay)
            Log.d(TAG, "relay IME mode ON: disp=$targetDisplay policy=1(FALLBACK) ok=$ok")
        } else {
            // LAZY restore — deliberately NO policy write here. The eager
            // repin at relay close forced the IME relayout that dismissed
            // Chrome's suggestion dropdown the instant the user tapped X,
            // making suggestions unclickable. The per-key repin machinery
            // (deduped above) restores the pref-correct policy the next time
            // a path actually needs it, and resetImePolicy still cleans up at
            // disconnect. Until then the dropdown stays up for the trackpad.
            Log.d(TAG, "relay IME mode OFF: policy left as-is (lazy restore; dropdown survives)")
        }
    }

    /**
     * "Keyboard on the glasses" button action (the pill tap when
     * IME_ON_EXTERNAL is ON). Guarantees the external display's IME policy is
     * LOCAL (0) so the system keyboard renders on the glasses for any field
     * focused there. There is no reliable shell command to *force* an arbitrary
     * app to pop its IME, so this just nails the routing — the user taps a text
     * field on the glasses to bring the keyboard up. Best-effort and guarded.
     */
    fun showImeOnExternal() {
        imeOnExternalEnabled = true
        repinImePolicy(aggressive = false)
    }

    /**
     * Force the native soft keyboard to show on the currently focused editor.
     * For phone-as-keyboard mode: after a glasses field is focused, IME routing
     * resolves to the phone (display 0) but the field may not auto-raise the
     * keyboard. This asks the privileged process to show it explicitly. Returns
     * true only if the call completed (not a guarantee the keyboard became
     * visible — some fields/Android versions still refuse). No-op without a
     * ready Shizuku backend.
     */
    fun forceShowImeOnPhone(): Boolean {
        val backend = PortalPadApp.instance.clickBackend
        val shizuku = (backend as? ClickBackend.ShizukuUserService)?.backend
        if (shizuku == null || !shizuku.isReady) {
            Log.w(TAG, "forceShowImeOnPhone SKIPPED: backend not ready")
            return false
        }
        val ok = runCatching { shizuku.showImeOnFocusedEditor() }.getOrDefault(false)
        Log.d(TAG, "forceShowImeOnPhone ok=$ok")
        return ok
    }

    /**
     * Reset IME policy to LOCAL (0 = the system default) on every display this
     * process touched, plus display 0 defensively. MUST be called on every
     * session teardown BEFORE the virtual display is released — leaving a VD at
     * a non-default IME policy when it's destroyed wedges IME placement on some
     * OEMs (Samsung), which kills the PHONE keyboard until a reboot. Also called
     * on service start as a safety net in case a prior session crashed before
     * resetting. Best-effort and fully guarded; no-ops without a bound backend.
     */
    fun resetImePolicy() {
        val backend = PortalPadApp.instance.clickBackend
        val shizuku = (backend as? ClickBackend.ShizukuUserService)?.backend
        if (shizuku == null) {
            Log.w(TAG, "resetImePolicy SKIPPED: backend is not Shizuku (backend=${backend?.javaClass?.simpleName}) — displays stay DIRTY for retry. dirty=${touchedImeDisplays}")
            return
        }
        if (!shizuku.isReady) {
            Log.w(TAG, "resetImePolicy SKIPPED: Shizuku not ready — displays stay DIRTY for retry. dirty=${touchedImeDisplays}")
            return
        }
        // Display 0 (phone) always reset to LOCAL so the phone is a valid IME
        // host again; plus every external/VD display we ever set a policy on.
        // Only REMOVE a display from the dirty set if its reset actually
        // succeeded — a failed one stays dirty so a later retry (on Shizuku
        // (re)connect / app start) cleans it up rather than stranding the
        // keyboard until reboot.
        val displays = (touchedImeDisplays.toSet() + 0)
        Log.d(TAG, "resetImePolicy: resetting displays=$displays")
        for (d in displays) {
            val ok = runCatching { shizuku.setImePolicy(d, 0) }.getOrDefault(false)
            Log.d(TAG, "resetImePolicy disp=$d -> 0(LOCAL) ok=$ok")
            if (ok) {
                touchedImeDisplays.remove(d)
                imePolicyApplied.remove(d)
            }
        }
        if (touchedImeDisplays.isEmpty()) lastImePolicyDisplay = -1
    }

    /**
     * Retry resetting any IME policies that are still "dirty" (set earlier but
     * never successfully reset — e.g. the disconnect reset ran while the backend
     * was Accessibility, not Shizuku, and got skipped). Called when a Shizuku
     * backend becomes available again, so a stranded policy SELF-HEALS instead of
     * requiring a reboot. No-op when nothing is dirty or no display is active
     * that we'd disturb. Safe to call repeatedly.
     */
    fun flushDirtyImePolicies() {
        if (touchedImeDisplays.isEmpty()) return
        Log.d(TAG, "flushDirtyImePolicies: dirty=${touchedImeDisplays} — attempting reset")
        resetImePolicy()
    }

    companion object {
        private const val TAG = "InputInjector"
        /** How close (px) the cursor must be to a window edge at drag-start for the
         *  drag to grab that edge (resize) rather than move the window. */
        private const val EDGE_GRAB_PX = 48f
        // Resize-cursor hover: refresh the freeform-task snapshot at most this
        // often (windows only move on drag, so mild staleness is invisible).
        private const val RESIZE_CACHE_TTL_MS = 250L
        // Resize-cursor hover strip: how far outside / inside each visible edge
        // the resize arrow appears. Kept thin so it hugs the border.
        private const val RESIZE_EDGE_OUT = 8f
        private const val RESIZE_EDGE_IN = 14f
        // The top edge's inside reach is a bit wider than the other edges' arrow
        // strip so it's easier to catch, while still leaving the caption below it
        // for title-bar moves.
        private const val RESIZE_TOP_IN = 12f
        // How far ABOVE the window's top edge the resize/move cursors reach. Small, so the
        // cursor never floats out in the wallpaper above the window — it only appears once
        // you're actually on the top edge.
        private const val RESIZE_TOP_OUT = 8f
        // Height of the freeform caption strip (below the top-resize zone) that a
        // grab treats as "move the window". Android captions run ~40–48px; tunable.
        private const val CAPTION_STRIP_H = 52f
        // The resize GRAB zone is more forgiving than the arrow strip so you don't
        // have to be pixel-perfect. Applied inward on left/right/bottom; the top
        // stays thin (RESIZE_EDGE_IN) so title-bar grabs remain clean moves.
        private const val RESIZE_GRAB_IN = 30f
        // Margin around the collapsed caption handle pill that still counts as "on the
        // handle" — makes the small pill easier to land the cursor on. Asymmetric:
        // the pill is only ~33px tall and sits dead-center on the window's full-width
        // top resize-V band, so a thin resize sliver hugs it (mostly above). A larger
        // VERTICAL outset swallows that sliver; a modest HORIZONTAL outset keeps the
        // top edge to the left/right of the pill available for real resizing.
        private const val HANDLE_GRAB_OUT_X = 16f
        // TIGHT mini-green-bar zone: a small margin on the sides/top of the pill, and
        // NOTHING below it (the pill menu opens right below and must stay tappable, not
        // draggable). Used for both the hover cursor and the press re-test.
        private const val PILL_OUT = 8f
        // Right-side carve-out on the Android caption bar: the maximize/close/fullscreen
        // button cluster lives here, so the drag body stops this far in from the right.
        private const val CAPTION_BUTTON_ZONE = 250f
        // How long to keep the last non-empty handle rects after captionHandles
        // momentarily returns empty (the pill menu opening/closing drops the node),
        // so the MOVE zone does not flicker to a resize edge mid-interaction.
        private const val HANDLE_RECTS_GRACE_MS = 600L
        // Widget edit-mode cursor zones (px). Edges reach generously OUTWARD
        // (covers the half-in dots and the 1-tall widget's fully-out S dot) but
        // barely inward, so resize glyphs never sit on the widget's face; the
        // interior MOVE zone insets past the inward reach so the two can't
        // overlap.
        private const val WIDGET_EDGE_IN = 10f
        private const val WIDGET_EDGE_OUT = 28f
        private const val WIDGET_MOVE_INSET = 14f
        // How long "we saw a collapsed pill" suppresses the geometric caption
        // fallback after the handle node vanishes. Long enough to ride out
        // SystemUI's decor rebuild (observed multi-second flicker after pill
        // interactions), short enough that a window GENUINELY losing its
        // handle regains the geometric band quickly.
        private const val COLLAPSED_HANDLE_MEMORY_MS = 4000L
        // How often the caption/handle shell-move re-sends bounds during a drag.
        // BOTH paths are throttled to ~11 steps/s — NOT for IPC cost, but
        // because on this ROM every resizeTask (fast binder OR shell) wraps
        // the bounds change in a WM TRANSITION that takes ~30-80ms to play,
        // serialized on one thread (~25-30/s max drain). The old 16ms fast
        // cadence submitted 60/s: WM queued nearly every one (drag test
        // 2026-07-17: 1621 of 1627 resizes hit "Queueing transition") and the
        // window played back a RECORDING of the finger — laggy tracking,
        // cursor off the caption bar, and 10.5 SECONDS of the window moving
        // by itself after release while the backlog drained. Coalescing can't
        // prevent this: resizeTask RETURNS when the transition is queued, not
        // when it's applied, so "not busy" never meant "caught up". At 90ms
        // the queue can't form; worst-case visual lag is one pending step
        // (~90ms) + one play (~40ms), and the window STOPS when the finger
        // stops. If drags still feel wrong, tune with a new DIAG-DRAGPOS
        // capture — do NOT drop this below the measured drain rate. (Future
        // smooth path: WindowContainerTransaction/applyTransaction, the
        // transition-less mechanism DeX-style drags use — a research project,
        // not a tweak.)
        private const val CAPTION_MOVE_THROTTLE_MS = 90L
        private const val CAPTION_MOVE_THROTTLE_FAST_MS = 90L
        /** Reference finger span (px) for scale=1.0 in the injected pinch; the
         *  live span is PINCH_BASE_SPAN * pinchScale. */
        private const val PINCH_BASE_SPAN = 240f
    }
}
