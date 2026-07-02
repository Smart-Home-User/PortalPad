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
enum class CursorType { ARROW, RESIZE_H, RESIZE_V, RESIZE_NWSE, RESIZE_NESW }

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
    // One-shot a11y pinch: accumulate scale; dispatch a 2-stroke pinch on commit.
    private var a11yPinchActive = false
    private var a11yPinchScale = 1f

    // ─── Resize-cursor hover feedback (desktop-windows only) ──────────────────
    /** Pushed by the foreground service from the DESKTOP_MODE_ENABLED pref. When
     *  off, the resize-cursor feature is fully dormant (cursor stays an arrow). */
    @Volatile var desktopModeEnabled: Boolean = false
    private val _cursorType = MutableStateFlow(CursorType.ARROW)
    val cursorType: StateFlow<CursorType> = _cursorType.asStateFlow()
    private val _onCaption = MutableStateFlow(false)
    /** True when the cursor is over a freeform window's top caption strip (below
     *  the top-resize zone, above the content). Caption grabs use the same quick
     *  press-hold engage as the resize edges but move the window 1:1 (no lock). */
    val cursorOnCaption: StateFlow<Boolean> = _onCaption.asStateFlow()
    // Throttled snapshot of freeform windows — listTasks() hits the task system,
    // so we never call it per-move; geometry runs against this cache.
    private var cachedResizeTasks: List<RunningTask> = emptyList()
    private var lastResizeTaskCacheAt = 0L

    /** On cursor move, if it's hovering a resizable freeform window's edge/corner,
     *  switch the cursor to the matching resize glyph. Gated so it's never a dead
     *  affordance: desktop mode on, a resize drag can actually land (trusted VD +
     *  ready backend), not over the dock, and the window isn't display-filling
     *  (maximized). Cheap rectangle math against a throttled task cache. */
    private fun updateResizeCursor() {
        if (!desktopModeEnabled || !usingVd || !activeBackendReady() || cursorOverDock) {
            if (_cursorType.value != CursorType.ARROW) _cursorType.value = CursorType.ARROW
            if (_onCaption.value) _onCaption.value = false
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastResizeTaskCacheAt > RESIZE_CACHE_TTL_MS) {
            cachedResizeTasks = runCatching {
                PortalPadApp.instance.freeform.listTasks(displayId)
            }.getOrDefault(emptyList())
            lastResizeTaskCacheAt = now
        }
        // Topmost window under the cursor — only a thin strip hugging each edge
        // counts (a little outside, a little inside), so the arrow sits on the
        // visible border instead of floating in the wallpaper or firing deep in
        // the window chrome.
        val b = cachedResizeTasks.firstNotNullOfOrNull { t ->
            t.bounds?.takeIf { bb ->
                cursorX >= bb.left - RESIZE_EDGE_OUT && cursorX <= bb.right + RESIZE_GRAB_IN &&
                    cursorY >= bb.top - RESIZE_GRAB_IN && cursorY <= bb.bottom + RESIZE_GRAB_IN
            }
        }
        val type = when {
            b == null -> CursorType.ARROW
            // Fills the display → maximized/fullscreen, not resizable.
            b.left <= 2 && b.top <= 2 &&
                b.right >= displayWidth - 2 && b.bottom >= displayHeight - 2 -> CursorType.ARROW
            else -> {
                val nearLeft = cursorX >= b.left - RESIZE_EDGE_OUT && cursorX <= b.left + RESIZE_EDGE_IN
                val nearRight = cursorX >= b.right - RESIZE_EDGE_IN && cursorX <= b.right + RESIZE_GRAB_IN
                val nearTop = cursorY >= b.top - RESIZE_GRAB_IN && cursorY <= b.top + RESIZE_TOP_IN
                val nearBottom = cursorY >= b.bottom - RESIZE_EDGE_IN && cursorY <= b.bottom + RESIZE_GRAB_IN
                when {
                    (nearLeft && nearTop) || (nearRight && nearBottom) -> CursorType.RESIZE_NWSE
                    (nearRight && nearTop) || (nearLeft && nearBottom) -> CursorType.RESIZE_NESW
                    nearLeft || nearRight -> CursorType.RESIZE_H
                    nearTop || nearBottom -> CursorType.RESIZE_V
                    else -> CursorType.ARROW // inside the window, not near an edge
                }
            }
        }
        if (_cursorType.value != type) _cursorType.value = type
        // Caption strip: the top band of a freeform window that isn't a resize edge
        // (type == ARROW there) and isn't maximized. A grab here moves the window.
        val maximized = b != null && b.left <= 2 && b.top <= 2 &&
            b.right >= displayWidth - 2 && b.bottom >= displayHeight - 2
        val onCap = b != null && !maximized && type == CursorType.ARROW &&
            cursorX >= b.left && cursorX <= b.right &&
            cursorY >= b.top && cursorY <= b.top + CAPTION_STRIP_H
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
    private var cursorX: Float = 100f
        set(value) {
            field = value
            _cursorPosition.value = Pair(value, cursorY)
        }
    private var cursorY: Float = 100f
        set(value) {
            field = value
            _cursorPosition.value = Pair(cursorX, value)
        }
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

    fun dragStart() = safe {
        if (useA11y()) {
            a11yDragActive = true
            a11yDragStartX = cursorX
            a11yDragStartY = cursorY
            clickHaptic()
            Log.d(TAG, "a11y dragStart @(${cursorX.toInt()},${cursorY.toInt()})")
            return@safe
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
        // Accessibility fallback: on a NON-trusted display (the trusted VD
        // couldn't be created), Shizuku-injected touches frequently don't land in
        // foreign app windows. An a11y-dispatched gesture is system-sourced and
        // honored without display trust, so prefer it there. Falls through to the
        // normal backend if the a11y service isn't connected / can't dispatch.
        if (displayId != 0 && (!usingVd || !activeBackendReady())) {
            val svc = PortalPadAccessibilityService.instance
            if (svc != null && svc.dispatchTap(displayId, x.toFloat(), y.toFloat())) {
                debugToast("Left click → a11y gesture display=$displayId ($x,$y)")
                return@safe
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
        val x = cursorX.toInt(); val y = cursorY.toInt()
        // Accessibility fallback (see leftClick): on an untrusted display, dispatch
        // the long-press through the a11y framework so it lands in foreign apps.
        if (displayId != 0 && (!usingVd || !activeBackendReady())) {
            val svc = PortalPadAccessibilityService.instance
            if (svc != null && svc.dispatchLongPress(displayId, x.toFloat(), y.toFloat(), 1200L)) {
                debugToast("Right click → a11y long-press display=$displayId ($x,$y)")
                return@safe
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
        // interpreted by Android as a TAP/CLICK, not a scroll. Below the real-
        // swipe threshold we no longer DROP the motion — we keep accumulating it
        // until it crosses the threshold, so slow scrolls aren't lost.
        val travel = kotlin.math.hypot((endX - cx).toDouble(), (endY - cy).toDouble())
        if (travel < 8.0) {
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

    /** Scroll-mode OK: a tap at the centre of the external display. */
    fun remoteCenterTap() = tapAt(displayWidth / 2f, displayHeight / 2f)

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
        //  • phone mode + auto-open relay ON → HIDE(2): the relay is meant to be the
        //    ONLY keyboard for glasses fields, so we forbid the native Samsung
        //    keyboard from ever showing for the VD field. The relay opens on field
        //    tap and types via injection; the native keyboard never leaks. (The
        //    detector keys on the focused-window display + soft-input mode, which are
        //    window attributes that survive HIDE, so HIDE no longer blinds it.)
        //  • phone mode + relay OFF → FALLBACK(1): no relay to type with, so route
        //    the native Samsung keyboard to the phone, bound to the glasses field.
        // Constants: 0=LOCAL, 1=FALLBACK_DISPLAY, 2=HIDE.
        val imeOnExternal = imeOnExternalEnabled
        val policy = if (imeOnExternal) 0 else if (autoOpenRelayEnabled) 2 else 1
        val ok = runCatching { shizuku.setImePolicy(targetDisplay, policy) }.getOrDefault(false)
        Log.d(TAG, "repin disp=$targetDisplay policy=$policy (imeOnExternal=$imeOnExternal autoOpenRelay=$autoOpenRelayEnabled) aggressive=$aggressive ok=$ok")
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
     * Fix: while relaying, explicitly set the VD's IME policy to HIDE (1).
     * Counter-intuitively, telling the VD "never show an IME here" stops
     * Chrome from *requesting* IME placement on the VD, so the failed-
     * placement → hide cascade never fires and the phone keyboard stays up.
     * On exit we restore the normal policy via repinImePolicy().
     *
     * HIDE is otherwise avoided (it was the original dropdown culprit), but
     * here it's scoped strictly to the relay session and the user is typing
     * on the phone, not interacting with a VD dropdown — so it's safe.
     */
    fun setRelayImeMode(active: Boolean) {
        suppressImeRepin = active
        val targetDisplay = displayId
        if (targetDisplay == 0) return
        val backend = PortalPadApp.instance.clickBackend
        val shizuku = (backend as? ClickBackend.ShizukuUserService)?.backend ?: return
        if (!shizuku.isReady) return
        if (active) {
            // 2 = DISPLAY_IME_POLICY_HIDE — tell the VD "never show an IME
            // here" so Chrome on the VD stops *requesting* IME placement on
            // it. That request is what triggers Samsung's failed-placement →
            // hide cascade (semComputeImeDisplayIdForTarget returns -1), which
            // collapses the phone keyboard serving the relay field. HIDE breaks
            // that cascade so the phone keyboard stays up. (Was previously set
            // to 1=FALLBACK_DISPLAY, which does NOT stop the request — the
            // relay keyboard kept vanishing after one keystroke.)
            val ok = runCatching { shizuku.setImePolicy(targetDisplay, 2) }.getOrDefault(false)
            touchedImeDisplays.add(targetDisplay)
            Log.d(TAG, "relay IME mode ON: disp=$targetDisplay policy=2(HIDE) ok=$ok")
        } else {
            // Restore normal policy (HIDE → LOCAL via repin).
            repinImePolicy(aggressive = false)
            Log.d(TAG, "relay IME mode OFF: restored normal policy on disp=$targetDisplay")
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
            if (ok) touchedImeDisplays.remove(d)
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
        private const val RESIZE_TOP_IN = 20f
        // Height of the freeform caption strip (below the top-resize zone) that a
        // grab treats as "move the window". Android captions run ~40–48px; tunable.
        private const val CAPTION_STRIP_H = 48f
        // The resize GRAB zone is more forgiving than the arrow strip so you don't
        // have to be pixel-perfect. Applied inward on left/right/bottom; the top
        // stays thin (RESIZE_EDGE_IN) so title-bar grabs remain clean moves.
        private const val RESIZE_GRAB_IN = 30f
        /** Reference finger span (px) for scale=1.0 in the injected pinch; the
         *  live span is PINCH_BASE_SPAN * pinchScale. */
        private const val PINCH_BASE_SPAN = 240f
    }
}
