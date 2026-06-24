package com.portalpad.app.ui.mediacontrols

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalpad.app.PortalPadApp
import kotlinx.coroutines.withTimeoutOrNull

private val ARROW_DIM = Color(0xFF7C71B0)
private val ARROW_GLOW = Color(0xFFD8C6FF)

/**
 * Gesture-mode surface for the Remote (the full-overlay pad). The whole area is
 * a swipe pad so it can be driven blind (phone in hand, eyes on the glasses):
 *
 *   1 finger : swipe = arrows · swipe+hold = arrow repeat · tap = OK · hold = Back
 *   2 finger : tap = play/pause · swipe L/R = rewind/FF · swipe+hold L/R =
 *              continuous rewind/FF · swipe up = Home · hold = Stop
 *
 * Volume stays on its own column (not handled here). The Guide pill (bottom
 * centre) opens the full gesture map.
 *
 * Detection is a single pointer loop: it tracks the max finger count and the
 * centroid travel, then classifies on release. Two-finger travel is measured
 * from the centroid AFTER the second finger lands, so a two-finger TAP isn't
 * mistaken for a swipe by the centroid jump. Fired gestures light up the
 * matching edge arrow (or pulse the OK ring) for a moment as confirmation; the
 * OK ring also breathes gently at idle.
 */
@Composable
fun GesturePad(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onTap: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onFastForward: () -> Unit,
    onStop: () -> Unit,
    onGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val injector = PortalPadApp.instance.injector
    val density = LocalDensity.current
    // Travel a touch must exceed (in px) before it counts as a swipe. Tune on-device.
    val swipeThr = with(density) { 34.dp.toPx() }
    val longPressMs = 380L

    // Visual feedback state. activeDir lights the matching arrow; tapSeq pulses OK.
    var activeDir by remember { mutableStateOf<String?>(null) }
    var dirSeq by remember { mutableIntStateOf(0) }
    var tapSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(dirSeq) {
        if (dirSeq == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(280)
        activeDir = null
    }
    val infinite = rememberInfiniteTransition(label = "gpadBreathe")
    val breathe by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "okBreathe",
    )
    val okPulse = remember { Animatable(1f) }
    LaunchedEffect(tapSeq) {
        if (tapSeq == 0) return@LaunchedEffect
        okPulse.snapTo(1f)
        okPulse.animateTo(1.16f, tween(110))
        okPulse.animateTo(1f, tween(200))
    }
    // Bounds of the Guide pill (in this pad's coordinates) so a tap that lands on
    // it opens the guide instead of also firing OK.
    var guideBounds by remember { mutableStateOf<Rect?>(null) }

    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF15151A))
            .border(1.dp, Color(0xFF332B4D), RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val startTime = System.currentTimeMillis()
                    val startPos = first.position
                    var startC2: Offset? = null
                    var lastC2 = Offset.Zero
                    var lastC1 = startPos
                    var twoFinger = false

                    // Live classification. A swipe direction is LOCKED when travel
                    // crosses the threshold, but the action waits: a quick swipe and a
                    // swipe-and-hold do different things, and they're only told apart by
                    // whether the finger is still down at holdMs. A periodic tick
                    // (withTimeoutOrNull) lets hold/repeat actions fire while the finger
                    // is held still — no move events arrive in that case.
                    var dir: String? = null
                    var dirLockTime = 0L
                    var holdEngaged = false   // swipe + hold engaged
                    var pressFired = false    // stationary long-press fired
                    var lastRepeat = 0L
                    val holdMs = longPressMs
                    val repeatMs = 120L       // 1-finger D-pad arrow auto-repeat
                    val seekRepeatMs = 320L   // 2-finger continuous rewind/FF repeat

                    fun fireArrow(d: String?) {
                        activeDir = d; dirSeq++
                        when (d) {
                            "up" -> onUp(); "down" -> onDown()
                            "left" -> onLeft(); "right" -> onRight()
                        }
                    }

                    while (true) {
                        val event = withTimeoutOrNull(40L) { awaitPointerEvent() }
                        val now = System.currentTimeMillis()
                        if (event != null) {
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            val cx = (pressed.sumOf { it.position.x.toDouble() } / pressed.size).toFloat()
                            val cy = (pressed.sumOf { it.position.y.toDouble() } / pressed.size).toFloat()
                            lastC1 = Offset(cx, cy)
                            if (pressed.size >= 2) {
                                if (startC2 == null) startC2 = lastC1
                                lastC2 = lastC1
                                twoFinger = true
                            }
                        }
                        // Travel so far. Two-finger is measured from the centroid AFTER
                        // the 2nd finger landed, so a 2-finger tap isn't read as a swipe.
                        val two = twoFinger && startC2 != null
                        val originX = if (two) startC2!!.x else startPos.x
                        val originY = if (two) startC2!!.y else startPos.y
                        val refX = if (two) lastC2.x else lastC1.x
                        val refY = if (two) lastC2.y else lastC1.y
                        val adx = kotlin.math.abs(refX - originX)
                        val ady = kotlin.math.abs(refY - originY)

                        if (dir == null && (adx > swipeThr || ady > swipeThr)) {
                            dir = if (adx > ady) (if (refX - originX > 0) "right" else "left")
                            else (if (refY - originY > 0) "down" else "up")
                            dirLockTime = now
                            activeDir = dir; dirSeq++   // glow immediately; action waits
                        }
                        // Swipe + hold engages once the finger lingers past holdMs.
                        if (dir != null && !holdEngaged && now - dirLockTime >= holdMs) {
                            holdEngaged = true
                            injector.buzz(longPress = true)
                            if (two) {
                                // 2-finger swipe-hold L/R = continuous rewind/FF (first now).
                                when (dir) { "left" -> onRewind(); "right" -> onFastForward() }
                                lastRepeat = now
                            } else {
                                // 1-finger swipe-hold = D-pad arrow repeat (first tick now).
                                fireArrow(dir); lastRepeat = now
                            }
                        }
                        // Auto-repeat while held: D-pad arrows (1-finger, fast) or
                        // continuous seek (2-finger L/R, gentler). 2-finger up has no
                        // repeat — it stays a quick-swipe = Home.
                        if (holdEngaged && now - lastRepeat >= (if (two) seekRepeatMs else repeatMs)) {
                            if (two) {
                                when (dir) { "left" -> onRewind(); "right" -> onFastForward() }
                            } else {
                                fireArrow(dir)
                            }
                            lastRepeat = now
                        }
                        // Stationary long-press (no swipe) fires while still held.
                        if (dir == null && !pressFired && now - startTime >= holdMs) {
                            pressFired = true
                            injector.buzz(longPress = true)
                            if (two) onStop() else onBack()
                        }
                    }

                    // Release: handle quick gestures that never engaged a hold/long-press.
                    val two = twoFinger && startC2 != null
                    if (dir != null && !holdEngaged) {
                        injector.buzz(longPress = false)
                        if (two) {
                            when (dir) {
                                "left" -> onRewind()
                                "right" -> onFastForward()
                                "up" -> onHome()
                            }
                        } else {
                            fireArrow(dir)
                        }
                    } else if (dir == null && !pressFired) {
                        injector.buzz(longPress = false)
                        if (two) {
                            tapSeq++
                            onPlayPause()
                        } else if (guideBounds?.inflate(16f)?.contains(startPos) == true) {
                            onGuide()
                        } else {
                            tapSeq++
                            onTap()
                        }
                    }
                }
            },
    ) {
        // Faint crosshair splitting the pad into four directional zones.
        Box(
            Modifier.align(Alignment.Center).fillMaxHeight().width(1.dp)
                .background(Color(0x1FB79CFF)),
        )
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth().height(1.dp)
                .background(Color(0x1FB79CFF)),
        )
        EdgeArrow("\u25B2", activeDir == "up", Modifier.align(Alignment.TopCenter).padding(top = 60.dp))
        EdgeArrow("\u25BC", activeDir == "down", Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp))
        EdgeArrow("\u25C0", activeDir == "left", Modifier.align(Alignment.CenterStart).offset(y = 16.dp).padding(start = 14.dp))
        EdgeArrow("\u25B6", activeDir == "right", Modifier.align(Alignment.CenterEnd).offset(y = 16.dp).padding(end = 14.dp))
        // Centre OK ring — breathes at idle, pulses on tap.
        Box(
            Modifier.align(Alignment.Center)
                .offset(y = 16.dp)
                .scale(breathe * okPulse.value)
                .size(56.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0x29B79CFF))
                .border(2.dp, Color(0xFF8B6DFF), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "OK",
                color = Color(0xFFC9B6FF),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // Guide pill — bottom centre, opens the full gesture map. Purple-tinted
        // (same size as the trackpad GuidePill) to match the gesture theme. Its tap
        // is handled by the pad's gesture detector (via guideBounds) so it opens the
        // guide instead of also firing OK — hence no clickable here.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .onGloballyPositioned { guideBounds = it.boundsInParent() }
                .clip(RoundedCornerShape(50))
                .background(Color(0x1FB79CFF))
                .border(1.dp, Color(0x66B79CFF), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                "Guide",
                color = Color(0xFFB79CFF),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun EdgeArrow(glyph: String, active: Boolean, modifier: Modifier) {
    val color by animateColorAsState(if (active) ARROW_GLOW else ARROW_DIM, tween(180), label = "arrowColor")
    val s by animateFloatAsState(if (active) 1.3f else 1f, tween(180), label = "arrowScale")
    Text(
        glyph,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.scale(s),
    )
}
