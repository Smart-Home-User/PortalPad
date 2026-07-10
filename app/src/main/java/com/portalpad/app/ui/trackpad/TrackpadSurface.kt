package com.portalpad.app.ui.trackpad

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.portalpad.app.ui.theme.AbOnSurfaceDim
import com.portalpad.app.ui.theme.AbPrimary
import com.portalpad.app.ui.theme.AbPrimaryBright
import com.portalpad.app.ui.theme.AbSurface
import com.portalpad.app.ui.theme.AbSurfaceElevated
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Smooth trackpad surface — no grid, no look-alike pattern. Visual:
 *  - rounded-rect inset card with a soft radial gradient (center brighter)
 *  - thin violet border at low alpha so the trackpad area reads as a discrete region
 *  - per-touch finger dot with an outward halo that fades on release
 *
 * Gestures (wired through [GestureSink]):
 *  - 1 finger: move + tap (single click)  +  double-tap, long-press
 *  - 2 fingers: scroll, pinch  + 2-finger TAP → secondary click (right-click)
 *  - 3 fingers: swipe → back / recents / home depending on direction
 */
@Composable
fun TrackpadSurface(
    sink: GestureSink,
    modifier: Modifier = Modifier,
    showFingerDots: Boolean = false,
    tapToClick: Boolean = true,
    // When the edge-scroll strip is adjacent, taps that start near the strip-side
    // edge of the surface are stray (the user was aiming at the strip but missed
    // onto the surface). Suppress CLICKS there so the strip region only ever
    // scrolls. "left" / "right" = strip side; null = no strip, no dead-zone.
    edgeStripSide: String? = null,
    // Velocity-based pointer-acceleration strength. 1.0 = tuned default,
    // 0 = off (constant speed). Scales the base accel constant in the recognizer.
    pointerAccel: Float = 1.0f,
) {
    val activeTouches = remember { mutableStateMapOf<Long, FingerTouch>() }
    val context = LocalContext.current

    // UNBUFFERED TOUCH DISPATCH — Android batches touch events to the PHONE
    // display's vsync before delivery (~8ms of added staleness at 120Hz, 16ms
    // at 60Hz). This view is a latency-sensitive input surface (same class as
    // a game), so ask for events at full digitizer rate instead. API 30+
    // (our minSdk). One call; persists for this view.
    val rootView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(rootView) {
        runCatching {
            rootView.requestUnbufferedDispatch(android.view.InputDevice.SOURCE_CLASS_POINTER)
        }
    }

    // `pointerInput(Unit)` only runs its block once — but we need the
    // recognizer to see fresh `tapToClick` reads on every gesture, not
    // the value captured at first composition. `rememberUpdatedState`
    // gives us a State that always holds the latest value, so the
    // recognizer reads the current pref state every click decision.
    val tapToClickState = rememberUpdatedState(tapToClick)
    val edgeStripSideState = rememberUpdatedState(edgeStripSide)
    val pointerAccelState = rememberUpdatedState(pointerAccel)
    val density = LocalDensity.current
    // Dead-zone band width (px) on the strip side where taps won't click.
    // Strip is 44dp + 8dp gap; a stray tap that misses the strip lands just
    // inside the surface edge, so ~20dp catches those without eating much area.
    val deadZonePx = with(density) { 20.dp.toPx() }

    val haloAlpha by animateFloatAsState(
        targetValue = if (activeTouches.isNotEmpty()) 0.7f else 0.0f,
        animationSpec = tween(220), label = "halo",
    )

    // Gate the system-gesture-exclusion registration on an external display being
    // present. The exclusion only matters when there's a glasses display for stray
    // edge-touches to land on; with no external display the rects serve no purpose
    // yet still must be cleaned up by WindowManager per-display — and during a
    // display flap that cleanup can target an already-removed display id and crash
    // system_server's WM ("unregister system gesture exclusion event for invalid
    // display"). No display ⇒ no exclusion ⇒ nothing to dangle.
    val hasExternalDisplay by com.portalpad.app.PortalPadApp.instance
        .externalDisplayId.collectAsState()

    Box(modifier.padding(2.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                // SYSTEM GESTURE EXCLUSION — a reference trackpad uses this API:
                //     setSystemGestureExclusionRects(new Rect(0,0,w,h))
                //
                // Purpose: tell Android that touches inside this rect are
                // fully consumed by us, not system-level gestures. Beyond
                // its documented purpose of disabling swipe-from-edge
                // navigation gestures in the region, this also classifies
                // touches as "app-handled" rather than "system-level," which
                // (we believe) prevents them from propagating to cross-
                // display PopupWindow dismiss-on-outside-touch handlers.
                //
                // This is our best-evidence theory for the Chrome address-
                // bar dropdown bug — the reference implementation uses this API; we didn't. If
                // it works, the dropdown should now survive cursor motion.
                //
                // Gated on hasExternalDisplay so the exclusion isn't registered
                // (and can't dangle into a WM cleanup crash) when no glasses
                // display is attached — where it would do nothing anyway.
                .then(
                    if (hasExternalDisplay != null) Modifier.systemGestureExclusion()
                    else Modifier,
                )
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(AbSurfaceElevated, AbSurface),
                        radius = 1400f,
                    )
                )
                .border(1.dp, AbOnSurfaceDim.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        // Read from rememberUpdatedState — the closure sees
                        // the LATEST value of tapToClick each time, not the
                        // value captured when this pointerInput first ran.
                        // Without this, toggling "Tap to click" in Settings
                        // had no effect until the Activity was recreated.
                        val tapEnabledRef = TapEnabledRef { tapToClickState.value }
                        // Suppress clicks whose press began in the strip-side
                        // dead-zone band (computed against the live surface width).
                        val deadZoneRef = TapDeadZoneRef { pressX, width ->
                            when (edgeStripSideState.value) {
                                "left" -> pressX < deadZonePx
                                "right" -> pressX > width - deadZonePx
                                else -> false
                            }
                        }
                        val recognizer = GestureRecognizer(
                            sink,
                            tapEnabledRef,
                            deadZoneRef,
                            { pointerAccelState.value },
                            densityScale = density.density / 2.8125f,
                        )
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            try {
                                val now = System.nanoTime()
                                // Skip changes already consumed by a child
                                // region (the edge-scroll strip consumes in
                                // the Main pass, which runs child-before-
                                // parent). Without this, an edge-strip drag
                                // would ALSO be interpreted by the main
                                // recognizer as a 1-finger move/tap.
                                val unconsumed = event.changes.filter { !it.isConsumed }
                                unconsumed.forEach { c ->
                                    if (c.pressed) activeTouches[c.id.value] = FingerTouch(c.position, now)
                                    else activeTouches.remove(c.id.value)
                                    // Consume the change so it doesn't bubble
                                    // up to ancestor views, parent Compose
                                    // nodes, or — critically — the system
                                    // window-manager's outside-touch dispatch.
                                    // The trackpad surface fully owns these
                                    // touches; nothing else should react.
                                    //
                                    // Theory for the Chrome dropdown bug
                                    // (v0.17.49): an unconsumed Compose
                                    // pointer change may register as a
                                    // "system-level touch" with WindowManager,
                                    // which then broadcasts a window-state
                                    // event to all displays. Chrome's
                                    // PopupWindow listens for those events
                                    // and dismisses the address-bar dropdown
                                    // when one fires.
                                    c.consume()
                                }
                                if (unconsumed.isNotEmpty()) {
                                    recognizer.onPointerEvent(unconsumed, size.width.toFloat())
                                }
                            } catch (t: Throwable) {
                                // A misbehaving handler must NOT kill the activity.
                                // Log and continue receiving events.
                                android.util.Log.e("TrackpadSurface", "gesture handler threw", t)
                            }
                        }
                    }
                },
        ) {
            if (showFingerDots) {
                Canvas(Modifier.fillMaxSize()) {
                    activeTouches.values.forEach { t ->
                        drawCircle(
                            Brush.radialGradient(
                                colors = listOf(
                                    AbPrimaryBright.copy(alpha = 0.35f * haloAlpha),
                                    Color.Transparent,
                                ),
                                center = t.pos, radius = 120f,
                            ),
                            radius = 120f, center = t.pos,
                        )
                        drawCircle(AbPrimary, radius = 16f, center = t.pos)
                        drawCircle(
                            AbPrimaryBright, radius = 16f, center = t.pos,
                            style = Stroke(width = 2f),
                        )
                    }
                }
            }
        }
        // Access-mode status (Mode: Shizuku/Root + check/warning), top-center of
        // the SURFACE — same live readout as the main mode strip, so a Shizuku
        // drop is visible right here on the trackpad/air-mouse you're using. The
        // warning is tappable → opens Shizuku.
        com.portalpad.app.ui.common.ModeStatusLabel(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp),
        )
    }
}

private data class FingerTouch(val pos: Offset, val downTimeNs: Long)

/**
 * Standalone vertical scroll bar — a SEPARATE element from the trackpad
 * (placed as a sibling in a Row, not inside the trackpad surface). Drag up
 * or down anywhere on it to scroll. Because it's its own bounded composable,
 * its touch area is exactly its own rectangle — it can't trigger scrolls up
 * by the settings icon or down by the home button the way the in-surface
 * strip did.
 *
 * Visual: a rounded panel with an up-chevron at the top, a down-chevron at
 * the bottom, and a stack of horizontal lines between them.
 */
@Composable
fun ScrollBar(
    onScroll: (dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            // Faint background so the scroll lane stays discoverable and
            // aimable, but no hard border — reads as a soft lane rather than
            // a boxed panel.
            .background(AbSurface.copy(alpha = 0.35f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var activeId: Long? = null
                    var lastY = 0f
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        ev.changes.forEach { c ->
                            when {
                                activeId == null && c.pressed -> {
                                    activeId = c.id.value
                                    lastY = c.position.y
                                    android.util.Log.d("EdgeStrip", "PRESS id=${c.id.value} y=${c.position.y.toInt()}")
                                    c.consume()
                                }
                                c.id.value == activeId && c.pressed -> {
                                    // STRICT BOUNDS: only scroll while the
                                    // finger is actually within the bar's
                                    // vertical extent. Once a pointer is
                                    // claimed, Compose keeps delivering its
                                    // moves even after it slides off the top
                                    // or bottom of the bar — which made it
                                    // keep scrolling above/below the bar.
                                    // Gating on [0, size.height] confines the
                                    // active scroll zone to the bar itself:
                                    // slide past an edge and it stops; come
                                    // back on and it resumes. We still update
                                    // lastY when out of bounds so re-entering
                                    // doesn't produce a sudden jump.
                                    val y = c.position.y
                                    val inBounds = y in 0f..size.height.toFloat()
                                    val dy = y - lastY
                                    if (inBounds && abs(dy) > 2f) {
                                        onScroll(0f, dy)
                                        lastY = y
                                    } else if (!inBounds) {
                                        lastY = y
                                    }
                                    c.consume()
                                }
                                c.id.value == activeId && !c.pressed -> {
                                    android.util.Log.d("EdgeStrip", "RELEASE id=${c.id.value} (strip consumed; no click emitted)")
                                    activeId = null
                                    c.consume()
                                }
                            }
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val color = AbOnSurfaceDim.copy(alpha = 0.6f)
            val arrowHalf = size.width * 0.24f
            val arrowH = arrowHalf * 1.1f
            val pad = 16f
            val sw = 3f

            // Up chevron near top.
            val topY = pad
            drawLine(color, Offset(cx - arrowHalf, topY + arrowH), Offset(cx, topY), sw)
            drawLine(color, Offset(cx + arrowHalf, topY + arrowH), Offset(cx, topY), sw)

            // Down chevron near bottom.
            val botY = size.height - pad
            drawLine(color, Offset(cx - arrowHalf, botY - arrowH), Offset(cx, botY), sw)
            drawLine(color, Offset(cx + arrowHalf, botY - arrowH), Offset(cx, botY), sw)

            // Lines between the arrows.
            val lineW = size.width * 0.5f
            val gap = 18f
            val regionTop = topY + arrowH + gap
            val regionBot = botY - arrowH - gap
            val usable = regionBot - regionTop
            if (usable > 0) {
                val spacing = 18f
                val n = (usable / spacing).toInt().coerceAtLeast(2)
                val span = (n - 1) * spacing
                val startY = regionTop + (usable - span) / 2f
                for (i in 0 until n) {
                    val y = startY + i * spacing
                    drawLine(
                        color = AbOnSurfaceDim.copy(alpha = 0.45f),
                        start = Offset(cx - lineW / 2f, y),
                        end = Offset(cx + lineW / 2f, y),
                        strokeWidth = sw,
                    )
                }
            }
        }
    }
}

/** Lazy-eval'd boolean so the recognizer can read pref state at decision time
 *  without re-keying pointerInput on every pref change. */
private class TapEnabledRef(val get: () -> Boolean)

/** Predicate: was this press (at pressX, given surface width) in the edge-strip
 *  dead-zone where clicks should be suppressed? */
private class TapDeadZoneRef(val inZone: (pressX: Float, surfaceWidth: Float) -> Boolean)

interface GestureSink {
    fun onMove(dx: Float, dy: Float)
    fun onTap()
    fun onSecondaryTap()           // NEW: 2-finger tap = right click
    fun onDoubleTap()
    fun onLongPressStart()
    fun onLongPressEnd()
    fun onScroll(dx: Float, dy: Float)
    fun onPinch(scale: Float)
    fun onThreeFingerSwipe(dx: Float, dy: Float)
    // Double-tap-drag: press the primary mouse button and hold it while moving,
    // so Android performs a real drag (e.g. moving a freeform window) instead of
    // a hover. onDragStart fires once when the drag begins, onDragMove per frame,
    // onDragEnd on lift. Default no-op impls keep other GestureSink users working.
    fun onDragStart() {}
    fun onDragMove(dx: Float, dy: Float) {}
    fun onDragEnd() {}
    // Pinch released — commit the accumulated zoom (no-op if it was a scroll).
    fun onPinchEnd() {}
    // True when the cursor is currently on a window resize edge (the ↔/↕ arrow is
    // showing). On an edge the drag intent is unambiguous, so the recognizer skips
    // the touch-and-hold and lets a press-drag resize immediately.
    fun isOnResizeEdge(): Boolean = false
    // True when the cursor is over a freeform window's caption strip — a grab here
    // uses the same quick edge-hold engage but moves the window (no resize).
    fun isOnCaption(): Boolean = false
}

/**
 * Stream recognizer. Tracks pointer count + per-pointer state to disambiguate
 * single tap, double-tap, two-finger scroll/pinch/tap, and three-finger swipes.
 */
private class GestureRecognizer(
    private val sink: GestureSink,
    private val tapEnabled: TapEnabledRef,
    private val deadZone: TapDeadZoneRef? = null,
    // Live read of the user's Pointer-acceleration strength (1.0 = tuned
    // default, 0 = off). A getter so changes apply mid-gesture.
    private val pointerAccel: () -> Float = { 1f },
    // Density scale for the DISTANCE thresholds below. They were tuned in raw
    // pixels on a Galaxy S24 at its default display zoom (450dpi class,
    // density 2.8125) — on that device this is exactly 1.0 and nothing
    // changes. On lower/higher-density phones the same raw-px gates were
    // physically bigger/smaller fingerscape, so slop/drag feel didn't
    // transfer; scaling by density/2.8125 makes the gates the same PHYSICAL
    // size everywhere. Time thresholds are untouched.
    private val densityScale: Float = 1f,
) {
    // Latest surface width (px), updated each event, for dead-zone evaluation.
    private var surfaceWidth: Float = 0f
    // 1-finger state
    private var lastSingleAt: Long = 0
    private var pressedAt: Long = 0
    // Position at the moment the finger first touched down. Slop is computed
    // against THIS, not against the last frame's position — otherwise a slow
    // drag (small per-frame deltas) never trips slop even after travelling
    // 100+ px total, and lifts incorrectly as a tap. That was the
    // "accidental clicks while scrolling" bug.
    private var pressStartX: Float = 0f
    private var pressStartY: Float = 0f
    private var lastSingleX: Float = 0f
    private var lastSingleY: Float = 0f
    private var didMoveBeyondSlop = false
    // True once the finger crosses slop while STILL inside the hold window — i.e.
    // it began moving before it was ever "held", so this gesture is a cursor slide
    // and must never engage a press-drag. Without this, ordinary cursor movement
    // (a sustained touch that travels) injected a touch-drag, which made webpages
    // scroll/pan as the cursor moved.
    private var movedEarly = false
    // Latched at press: was the cursor on a resize edge? If so, the drag engages
    // without waiting for the hold (desktop-style grab-and-resize).
    private var pressOnEdge = false
    // Latched at press: was the cursor on a window caption strip? If so, a brief
    // hold grabs and moves the window (same quick engage as an edge).
    private var pressOnCaption = false
    // Rest tracking for the quick-zone (edge/caption) hold. The grab engages only
    // after the finger has stayed within restSlopPx of this anchor for edgeHoldMs.
    // Any drift past the slop moves the anchor and restarts the timer, so a slide at
    // ANY speed keeps resetting and never engages — only a deliberate still hold
    // does. Reset at press.
    private var restAnchorX = 0f
    private var restAnchorY = 0f
    private var restAnchorAt = 0L

    // Double-tap-drag state. When a press lands within the double-tap window of
    // the previous tap, the gesture is "armed" — if the finger then moves beyond
    // slop, we enter an active drag (primary mouse button held) and route moves
    // as drag-moves until lift.
    private var dragArmed = false
    private var dragActive = false

    // 2-finger state
    private var twoFingerStartTime: Long = 0
    private var twoFingerStartPositions: Pair<Offset, Offset>? = null
    private var twoFingerStartDist: Float = 0f
    private var twoFingerLastCentroid: Offset = Offset.Zero
    private var twoFingerMoved = false
    // Latches once a 2-finger gesture is clearly a pinch (scale moved past a
    // threshold), so it zooms instead of also scrolling.
    private var pinchLatched = false

    // 3-finger state
    private var threeFingerStart: Offset = Offset.Zero
    // Latch so one continuous 3-finger swipe fires ONCE (until fingers lift).
    // Without this, a held swipe re-fires every 60px and a tiny reverse drift
    // sends the opposite action (e.g. opens then immediately closes the panel).
    private var threeFingerFired = false

    private var lastPointerCount = 0

    /** When a drag ends, this stamps the time. Subsequent quick taps within
     *  [postDragSuppressMs] are suppressed — they're almost always the user
     *  re-placing their finger to continue cursor positioning, not actual
     *  clicks. Real laptop trackpads do this same suppression. */
    private var lastDragEndAt: Long = 0

    // DeX-tight click discrimination:
    //   Slop 5px: a finger that travels more than ~5px from where it landed
    //     can never tap-click on lift. Tight enough that small adjustment
    //     motions visibly drag the cursor before they could accidentally tap.
    //   Tap window 0–199ms (no slop) = left click.
    //   Touch-and-hold select 250ms+ (still) — the 200–249ms DEAD ZONE between
    //     them is intentional: a slightly slow tap (~200–220ms is common for
    //     deliberate tappers) must fall into safe nothing, NOT pop a context
    //     menu. (This buffer was lost in a tuning pass when the hold threshold
    //     drifted to 200ms, making slow taps fire select — restored.)
    //   postDragSuppressMs 220: after a REAL drag (button-held), the next tap
    //     within this window is suppressed. Eliminates the "drag → lift →
    //     finger re-touches to continue positioning" accidental-click class.
    //     Plain cursor moves do NOT arm this (see the moved-beyond-slop
    //     branch) — "slide to target, then tap" must always click.
    //   Distances scale by [densityScale]; times are absolute.
    private val slopPx = 5f * densityScale
    // Tap-slop is looser than the drag-start slop above: a finger tap (especially
    // a thumb) naturally rolls a few px between touch-down and lift, so the 5px
    // gate rejected real taps as "moved-beyond-slop". 8px admits that roll while
    // staying well short of a deliberate cursor slide. Used ONLY to disqualify a
    // lift from being a tap — drag-start still uses slopPx so double-tap-drag
    // begins just as crisply.
    private val tapSlopPx = 8f * densityScale
    // Long-press right-click tolerates more drift than a tap: a finger held
    // still for over a second jitters well past the 5px tap-slop (~12–17px
    // observed), but a genuine cursor-repositioning slide travels much farther.
    // 40px cleanly separates "still hold with jitter" from "actually sliding".
    private val longPressSlopPx = 40f * densityScale
    private val tapMaxDurationMs = 200L
    private val longPressThresholdMs = 250L
    // On a resize edge, a much shorter hold engages the resize so the border pops
    // almost immediately on a deliberate press-hold — but still long enough that a
    // quick press-and-slide near an edge stays a plain cursor move, not a resize.
    // Quick-zone (edge/caption) hold: engages only after the finger rests still for
    // this long. Gated on stillness (see restSlopPx), not raw elapsed time, so a
    // slow slide over the zone never trips it — long enough to read as a deliberate
    // press-and-hold.
    private val edgeHoldMs = 300L
    // Stillness slop for that hold: drift within this of the rest anchor counts as
    // "holding still" (absorbs natural hold jitter); drifting past it restarts the
    // timer. Set above typical hold jitter but below a deliberate slide.
    private val restSlopPx = 18f * densityScale
    // After a resize/caption drag ends, the cursor is parked on the edge with the
    // arrow still showing. For this grace window a fresh press there moves the
    // cursor instead of instantly re-grabbing, so you can slide away without timing
    // it. A sustained hold past the grace still re-grabs.
    private val edgeGraceMs = 300L
    // Press-drag (DeX "touch and hold + move"): once the finger has been held in
    // place for longPressThresholdMs, moving past this distance commits to a
    // button-held drag. Bigger than tapSlopPx so a still hold's natural jitter
    // (~12-17px) stays a "select" long-press rather than accidentally dragging.
    private val dragEngagePx = 24f * densityScale
    private val postDragSuppressMs = 220L
    private val twoFingerTapTimeoutMs = 220L
    private val twoFingerSlopPx = 24f * densityScale
    // Minimum per-frame movement to actually emit onMove. Avoids sending a
    // flood of zero-motion hover events when the finger is steady.
    private val motionEpsilonPx = 0.5f * densityScale

    fun onPointerEvent(changes: List<PointerInputChange>, surfaceWidthPx: Float = 0f) {
        surfaceWidth = surfaceWidthPx
        val pressed = changes.filter { it.pressed }
        val count = pressed.size

        // Transition: 1-finger gesture ended → fire tap or long-press right-click.
        // CRITICAL: this check must be here (before the when(count) below) because
        // when the finger lifts, count drops to 0 — the `pressed`-filter above
        // strips the lift event so handleOne never sees `!pressed && previousPressed`.
        // Without this transition block, single-finger taps and long-presses are
        // simply never recognized (only 2-finger tap, which has its own transition,
        // would work). That was the long-standing "left click on trackpad doesn't
        // work" bug.
        if (lastPointerCount == 1 && count == 0) {
            val now = System.currentTimeMillis()
            val held = now - pressedAt
            // If a double-tap-drag was active, end it (release the button) and
            // skip all tap/click classification — a drag lift is never a click.
            if (dragActive) {
                sink.onDragEnd()
                dragActive = false
                dragArmed = false
                lastDragEndAt = now
                lastSingleAt = 0
                android.util.Log.d("Gesture", "DRAG END (double-tap-drag)")
                pressedAt = 0
                didMoveBeyondSlop = false
                lastPointerCount = count
                return
            }
            val tapsEnabled = tapEnabled.get()
            val totalMotion = if (pressedAt > 0)
                kotlin.math.hypot(lastSingleX - pressStartX, lastSingleY - pressStartY)
            else 0f
            val postDragGap = now - lastDragEndAt
            val inPostDrag = lastDragEndAt > 0 && postDragGap < postDragSuppressMs
            var classified = "drag-or-stale"
            if (pressedAt > 0 && held >= longPressThresholdMs && totalMotion <= longPressSlopPx) {
                // Touch-and-hold with no real move = "select" (DeX touch-and-hold).
                // Injects a touch long-press, which selects the word/object under
                // the cursor or opens its context menu. EXEMPT from the tight
                // tap-slop: a finger held in place jitters past it (~12-17px
                // observed), so we tolerate longPressSlopPx of drift before this
                // counts as a slide. A hold that travels past dragEngagePx instead
                // becomes a drag (handled in the move loop) and never reaches here.
                sink.onSecondaryTap()
                classified = "select(touch-hold motion=${totalMotion.toInt()}px)"
            } else if (!didMoveBeyondSlop && pressedAt > 0) {
                val inStripDeadZone = deadZone?.inZone?.invoke(pressStartX, surfaceWidth) == true
                if (inStripDeadZone) {
                    // Press began in the edge-strip dead-zone — a stray tap that
                    // missed the scroll strip. Never click here.
                    classified = "suppressed-edge-strip-zone"
                } else if (tapsEnabled && held < tapMaxDurationMs && !inPostDrag) {
                    // Single left click. Double-click is NOT synthesized — two
                    // quick taps simply fire two clicks and the app pairs them
                    // itself, like a real trackpad. (This is what removed the old
                    // triple-count and double-tap unreliability.)
                    sink.onTap()
                    classified = "left-click(tap)"
                } else if (tapsEnabled && held < tapMaxDurationMs && inPostDrag) {
                    // The user just finished a drag and re-touched within the
                    // suppression window. This is almost always a finger
                    // re-placement to continue positioning the cursor, not a
                    // click. Suppress.
                    classified = "suppressed-post-drag(gap=${postDragGap}ms)"
                } else if (!tapsEnabled && held < tapMaxDurationMs) {
                    classified = "tap-suppressed-by-pref"
                } else {
                    classified = "dead-zone(${held}ms)"
                }
            } else if (didMoveBeyondSlop) {
                classified = "no-click(moved-beyond-slop)"
                // Deliberately do NOT stamp lastDragEndAt here. A plain cursor
                // move that was never a button-held drag is normally followed by
                // an intended tap ("slide to the target, then click"); stamping
                // here suppressed that legitimate tap. Post-drag suppression now
                // applies only after a real drag (see the DRAG END branch above).
            } else {
                classified = "no-click(no-press-recorded)"
            }
            android.util.Log.d(
                "Gesture",
                "LIFT held=${held}ms motion=${totalMotion.toInt()}px " +
                    "slop=$didMoveBeyondSlop pressedAt=$pressedAt " +
                    "pressX=${pressStartX.toInt()} surfaceW=${surfaceWidth.toInt()} " +
                    "deadZone=${deadZone?.inZone?.invoke(pressStartX, surfaceWidth)} → $classified",
            )
            pressedAt = 0
            didMoveBeyondSlop = false
        }

        // Transition: 2-finger gesture ended → if it was a quick no-move tap, fire right-click.
        if (lastPointerCount == 2 && count < 2 && twoFingerStartPositions != null) {
            val elapsed = System.currentTimeMillis() - twoFingerStartTime
            if (!twoFingerMoved && elapsed < twoFingerTapTimeoutMs) {
                sink.onSecondaryTap()
            } else if (pinchLatched) {
                sink.onPinchEnd()
            }
            resetTwoFingerState()
        }
        if (lastPointerCount == 3 && count < 3) {
            threeFingerStart = Offset.Zero
            threeFingerFired = false
        }

        when (count) {
            1 -> handleOne(pressed[0])
            2 -> handleTwo(pressed)
            3 -> handleThree(pressed)
            else -> { /* idle */ }
        }
        lastPointerCount = count
    }

    private fun handleOne(p: PointerInputChange) {
        val now = System.currentTimeMillis()
        // Coming DOWN from a 2-/3-finger gesture to a single remaining finger:
        // that finger was already pressed and has been moving as part of the
        // multi-touch gesture, so lastSingleX/Y still hold a stale anchor from
        // before the gesture. Emitting a move now sends one giant catch-up delta
        // — the "cursor jumps at the end of a two-finger scroll" bug. Re-anchor
        // to the finger's CURRENT position, mark it already-moved (so the
        // eventual lift isn't misclassified as a tap), and skip this frame;
        // normal 1-finger tracking resumes next frame from the new anchor.
        if (lastPointerCount >= 2) {
            lastSingleX = p.position.x
            lastSingleY = p.position.y
            pressStartX = p.position.x
            pressStartY = p.position.y
            pressedAt = 0
            didMoveBeyondSlop = true
            dragArmed = false
            dragActive = false
            return
        }
        if (!p.previousPressed) {
            // First touch: stamp the start time + position so the
            // transition handler in onPointerEvent can classify on lift,
            // and so slop is measured from the press anchor (not last frame).
            pressedAt = now
            pressStartX = p.position.x
            pressStartY = p.position.y
            lastSingleX = p.position.x
            lastSingleY = p.position.y
            didMoveBeyondSlop = false
            movedEarly = false
            // On a resize edge OR caption strip the drag is unambiguous — a brief
            // hold engages it below (border shows / window sticks) with no 24px move.
            pressOnEdge = sink.isOnResizeEdge()
            pressOnCaption = sink.isOnCaption()
            restAnchorX = p.position.x
            restAnchorY = p.position.y
            restAnchorAt = now
            // Drag is hold-primed (DeX touch-and-hold + move): a press held in place
            // that then travels becomes a button-held drag — decided in the move
            // loop below, not by a preceding tap. Nothing to arm here.
            dragActive = false
            android.util.Log.d(
                "Gesture",
                "PRESS at (${p.position.x.toInt()},${p.position.y.toInt()}) t=$now (anchor)",
            )
        }
        // DeX-style: cursor moves on every frame with any meaningful motion,
        // independent of slop. Slop is ONLY used to disqualify the lift as a
        // tap. That makes the cursor feel instant while still suppressing
        // accidental clicks from small adjustments.
        val dx = p.position.x - lastSingleX
        val dy = p.position.y - lastSingleY
        val moved = hypot(dx, dy) > motionEpsilonPx
        // Rest tracking: any drift past restSlopPx from the rest anchor moves the
        // anchor and restarts the hold timer, so continuous motion (slow OR fast)
        // over an edge/caption keeps resetting and never engages — you just move the
        // cursor. Only a deliberate STILL hold lets restedMs reach the threshold.
        val restDrift = hypot(p.position.x - restAnchorX, p.position.y - restAnchorY)
        if (restDrift > restSlopPx) {
            restAnchorX = p.position.x
            restAnchorY = p.position.y
            restAnchorAt = now
        }
        // Press-drag engage. On a resize edge or caption strip (the "quick zone"), a
        // deliberate still hold (finger resting for edgeHoldMs) grabs it — the resize
        // border pops / the window sticks — so press-and-HOLD acts and any slide over
        // the zone stays a plain cursor move. A grace window after a prior drag lets a
        // fresh press on the zone move the cursor instead of re-grabbing. Outside the
        // zone it's the DeX touch-and-hold: hold longPressThresholdMs, THEN travel
        // past dragEngagePx to move a window.
        val quickZone = pressOnEdge || pressOnCaption
        val graceOk = lastDragEndAt == 0L || now - lastDragEndAt >= edgeGraceMs
        val onQuickHold = quickZone && graceOk && now - restAnchorAt >= edgeHoldMs
        val offZoneReady = !quickZone && !movedEarly && now - pressedAt >= longPressThresholdMs
        if (!dragActive && pressedAt > 0 && (onQuickHold || offZoneReady)) {
            val totalFromPress = hypot(p.position.x - pressStartX, p.position.y - pressStartY)
            if (onQuickHold || totalFromPress > dragEngagePx) {
                dragActive = true
                sink.onDragStart()
                val what = if (pressOnEdge) "edge hold" else if (pressOnCaption) "caption hold" else "hold + move"
                android.util.Log.d("Gesture", "DRAG START ($what)")
            }
        }
        if (moved) {
            if (dragActive) {
                // Dragging stays 1:1 for precision (resizing/positioning windows).
                sink.onDragMove(dx, dy)
            } else {
                // Pointer acceleration on finger deltas — slow moves stay precise,
                // fast flicks cover more screen, like a real mouse. gain grows with
                // finger speed (px/frame), clamped. The base constant is scaled by
                // the user's Pointer-acceleration slider: 1.0 = tuned default,
                // 0 = off (gain pinned to 1 → constant speed).
                val accel = 0.04f * pointerAccel()
                val accelMax = 2.5f // cap so a fast flick doesn't teleport
                val speed = hypot(dx, dy)
                val gain = (1f + accel * speed).coerceIn(1f, accelMax)
                sink.onMove(dx * gain, dy * gain)
            }
            // Re-anchor ONLY after motion crossed the epsilon. Sub-threshold drift
            // then accumulates against the old anchor instead of being discarded
            // each frame, so slow/fine movement isn't lost (fixes grid-snapping).
            lastSingleX = p.position.x
            lastSingleY = p.position.y
        }
        // Track total motion from press for click classification.
        val totalDx = p.position.x - pressStartX
        val totalDy = p.position.y - pressStartY
        val totalFromPressNow = hypot(totalDx, totalDy)
        if (!didMoveBeyondSlop && totalFromPressNow > tapSlopPx) {
            didMoveBeyondSlop = true
        }
        // A finger that travels past the drag-engage distance BEFORE the hold
        // completes is a cursor slide, not a press-drag → lock out drag. Using
        // dragEngagePx (not the tight tap-slop) tolerates the ~12-17px jitter of a
        // finger held in place, so a real touch-and-hold still promotes to a drag
        // once you move. A quick slide clears 24px well within the hold window and
        // still locks out correctly.
        if (!movedEarly && pressedAt > 0 && now - pressedAt < longPressThresholdMs &&
            totalFromPressNow > dragEngagePx) {
            movedEarly = true
        }
        // No lift-detection here — it's unreachable when count==1 (the lift event
        // makes count drop to 0, which exits this branch). Lift is handled by the
        // onPointerEvent transition block above.
    }

    private fun handleTwo(ps: List<PointerInputChange>) {
        val a = ps[0].position; val b = ps[1].position
        val dist = hypot(a.x - b.x, a.y - b.y)
        val centroid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)

        if (twoFingerStartPositions == null) {
            twoFingerStartPositions = a to b
            twoFingerStartTime = System.currentTimeMillis()
            twoFingerStartDist = dist
            twoFingerLastCentroid = centroid
            twoFingerMoved = false
            return
        }

        // Tap-vs-gesture disambiguation: any meaningful movement disqualifies tap.
        val (startA, startB) = twoFingerStartPositions!!
        val driftA = hypot(a.x - startA.x, a.y - startA.y)
        val driftB = hypot(b.x - startB.x, b.y - startB.y)
        if (!twoFingerMoved && (driftA > twoFingerSlopPx || driftB > twoFingerSlopPx)) {
            twoFingerMoved = true
        }

        if (twoFingerMoved) {
            val scaleDelta = dist / twoFingerStartDist
            // Latch as a pinch once the span clearly changes, so a pinch zooms
            // instead of also scrolling. Below the latch, it's treated as scroll.
            if (abs(scaleDelta - 1f) > 0.15f) pinchLatched = true
            if (pinchLatched) {
                sink.onPinch(scaleDelta)
            } else {
                val ddx = centroid.x - twoFingerLastCentroid.x
                val ddy = centroid.y - twoFingerLastCentroid.y
                if (hypot(ddx, ddy) > 2f) sink.onScroll(ddx, ddy)
            }
        }
        twoFingerLastCentroid = centroid
    }

    private fun handleThree(ps: List<PointerInputChange>) {
        val centroid = Offset(
            ps.map { it.position.x }.average().toFloat(),
            ps.map { it.position.y }.average().toFloat(),
        )
        if (threeFingerStart == Offset.Zero) threeFingerStart = centroid
        val dx = centroid.x - threeFingerStart.x
        val dy = centroid.y - threeFingerStart.y
        if (!threeFingerFired && hypot(dx, dy) > 60f) {
            sink.onThreeFingerSwipe(dx, dy)
            threeFingerFired = true
        }
    }

    private fun resetTwoFingerState() {
        twoFingerStartPositions = null
        twoFingerStartTime = 0
        twoFingerStartDist = 0f
        twoFingerLastCentroid = Offset.Zero
        twoFingerMoved = false
        pinchLatched = false
    }
}
