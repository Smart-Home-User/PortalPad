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
                        val recognizer = GestureRecognizer(sink, tapEnabledRef, deadZoneRef, { pointerAccelState.value })
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
    //   Long-press 250ms+ (no slop) = right click. (Small dead zone
    //     200–249ms is intentional — guards against ambiguous medium-length
    //     touches that aren't clearly a quick tap nor a deliberate hold.)
    //   postDragSuppressMs 400: after a drag (slop tripped), the next tap
    //     within this window is suppressed. Eliminates the "drag → lift →
    //     finger re-touches to continue positioning" accidental-click class.
    //     Long-press right-clicks are still allowed (≥250ms held).
    private val slopPx = 5f
    // Long-press right-click tolerates more drift than a tap: a finger held
    // still for over a second jitters well past the 5px tap-slop (~12–17px
    // observed), but a genuine cursor-repositioning slide travels much farther.
    // 40px cleanly separates "still hold with jitter" from "actually sliding".
    private val longPressSlopPx = 40f
    private val tapMaxDurationMs = 200L
    private val doubleTapWindowMs = 280L
    private val longPressThresholdMs = 250L
    private val postDragSuppressMs = 400L
    private val twoFingerTapTimeoutMs = 220L
    private val twoFingerSlopPx = 24f
    // Minimum per-frame movement to actually emit onMove. Avoids sending a
    // flood of zero-motion hover events when the finger is steady.
    private val motionEpsilonPx = 0.5f

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
                // Right-click on long-press — a deliberate still hold ≥250ms.
                // It is EXEMPT from the tight 5px tap-slop: a finger held in
                // place for over a second naturally jitters past 5px (the log
                // showed ~12–17px on a dead-still hold), so gating this on the
                // tap-slop would (and did) swallow real long-presses. Instead we
                // allow a generous longPressSlopPx so jitter is fine but a true
                // cursor-repositioning slide (which travels much farther) is
                // still excluded and treated as movement, not a click.
                sink.onSecondaryTap()
                lastSingleAt = 0
                classified = "right-click(long-press motion=${totalMotion.toInt()}px)"
            } else if (!didMoveBeyondSlop && pressedAt > 0) {
                val inStripDeadZone = deadZone?.inZone?.invoke(pressStartX, surfaceWidth) == true
                if (inStripDeadZone) {
                    // Press began in the edge-strip dead-zone — a stray tap that
                    // missed the scroll strip. Never click here.
                    classified = "suppressed-edge-strip-zone"
                } else if (tapsEnabled && held < tapMaxDurationMs && !inPostDrag) {
                    if (now - lastSingleAt < doubleTapWindowMs) {
                        sink.onDoubleTap(); lastSingleAt = 0
                        classified = "double-tap"
                    } else {
                        sink.onTap(); lastSingleAt = now
                        classified = "left-click(tap)"
                    }
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
                // Stamp the drag-end time so the next quick tap can be
                // suppressed as a re-positioning gesture.
                lastDragEndAt = now
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
            // Arm a double-tap-drag if this press follows a recent tap. If the
            // finger then moves beyond slop, it becomes an active button-held
            // drag (for moving freeform windows, selecting text, etc.).
            dragArmed = lastSingleAt > 0 && (now - lastSingleAt) < doubleTapWindowMs
            dragActive = false
            android.util.Log.d(
                "Gesture",
                "PRESS at (${p.position.x.toInt()},${p.position.y.toInt()}) " +
                    "t=$now (anchor) dragArmed=$dragArmed",
            )
        }
        // DeX-style: cursor moves on every frame with any meaningful motion,
        // independent of slop. Slop is ONLY used to disqualify the lift as a
        // tap. That makes the cursor feel instant while still suppressing
        // accidental clicks from small adjustments.
        val dx = p.position.x - lastSingleX
        val dy = p.position.y - lastSingleY
        val moved = hypot(dx, dy) > motionEpsilonPx
        // If armed and the finger has now moved beyond slop, begin a real drag.
        if (dragArmed && !dragActive) {
            val totalFromPress = hypot(p.position.x - pressStartX, p.position.y - pressStartY)
            if (totalFromPress > slopPx) {
                dragActive = true
                sink.onDragStart()
                android.util.Log.d("Gesture", "DRAG START (double-tap-drag)")
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
        if (!didMoveBeyondSlop && hypot(totalDx, totalDy) > slopPx) didMoveBeyondSlop = true
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
            if (abs(scaleDelta - 1f) > 0.04f) sink.onPinch(scaleDelta)
            val ddx = centroid.x - twoFingerLastCentroid.x
            val ddy = centroid.y - twoFingerLastCentroid.y
            if (hypot(ddx, ddy) > 2f) sink.onScroll(ddx, ddy)
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
    }
}
