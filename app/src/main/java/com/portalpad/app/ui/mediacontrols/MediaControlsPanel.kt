@file:Suppress("DEPRECATION") // D-pad arrows deliberately keep non-AutoMirrored icons — see DpadCluster comment.

package com.portalpad.app.ui.mediacontrols

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.portalpad.app.ui.common.toArgbInt
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbDanger
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceDim
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimary
import com.portalpad.app.ui.theme.AbPrimaryBright
import com.portalpad.app.ui.theme.AbSurface
import com.portalpad.app.ui.theme.AbSurfaceElevated
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Replaces the trackpad surface when the user toggles into media mode.
 *
 * Layout (top → bottom):
 *  - compact album art (160dp square)
 *  - title + artist
 *  - scrub bar
 *  - primary controls (prev / -10s / play-pause / +10s / next)
 *  - secondary FF / rewind row
 *  - D-pad cluster (up/down/left/right + OK)  ← for TV-style apps
 *  - volume slider
 *
 * The column scrolls vertically so the D-pad never clips on shorter phones.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MediaControlsPanel(
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSkip10sForward: () -> Unit,
    onSkip10sBack: () -> Unit,
    onFastForward: () -> Unit,
    onRewind: () -> Unit,
    onVolUp: () -> Unit,
    onVolDown: () -> Unit,
    onSetVolumePct: (Int) -> Unit,
    onMute: () -> Unit,
    onDpadUp: () -> Unit,
    onDpadDown: () -> Unit,
    onDpadLeft: () -> Unit,
    onDpadRight: () -> Unit,
    onDpadCenter: () -> Unit,
    onReturn: () -> Unit,
    onProgRed: () -> Unit,
    onProgGreen: () -> Unit,
    onProgYellow: () -> Unit,
    onProgBlue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = com.portalpad.app.PortalPadApp.instance

    // Remote input mode: 0 = D-pad, 1 = Scroll, 2 = Gesture. Selected by the
    // Input pill (in the cluster, and on the gesture overlay). Session-only,
    // defaults to D-pad. scrollMode / gestureMode stay as derived flags so the
    // existing D-pad callbacks below read unchanged.
    var inputMode by remember { mutableStateOf(0) }
    val scrollMode = inputMode == 1
    val gestureMode = inputMode == 2
    val readerMode = inputMode == 3
    val inputModeName = when (inputMode) { 1 -> "Scroll"; 2 -> "Gesture"; 3 -> "Reader"; else -> "D-pad" }
    val readerFlip by app.prefs.readerFlipMethod.collectAsState(initial = 1)
    val readerScope = androidx.compose.runtime.rememberCoroutineScope()
    // Persist the Input sub-mode so Reader (and Scroll/Gesture) survive a
    // disconnect / relay round-trip / activity recreate. Seed ONCE from the
    // saved value on (re)composition — a fresh composition after recreate
    // re-reads it here. The seed assigns inputMode directly (not via the
    // helper) so it does not write back. Every user-driven change goes through
    // setInputMode, which updates the live state AND persists.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val saved = app.prefs.remoteInputMode.first()
        if (saved != inputMode) inputMode = saved
    }
    val setInputMode: (Int) -> Unit = { v ->
        inputMode = v
        readerScope.launch { app.prefs.setRemoteInputMode(v) }
    }
    // Reader brightness: a TRANSIENT override of the GL color pass (value 0 of
    // the tuning vector). Seeded from the saved value on Reader open, driven live
    // while reading, never persisted, reverted to saved on exit. Only usable
    // off-mirror with the GPU pipeline on — the only state where that GL pass
    // exists — so it's greyed otherwise.
    val readerSavedTuning by app.prefs.colorTuning.collectAsState(initial = "")
    val readerGpuOn by app.prefs.gpuColorPipeline.collectAsState(initial = true)
    val readerMirror by app.prefs.panelSystemMirror.collectAsState(initial = false)
    val readerBrightnessAvailable = readerGpuOn && !readerMirror
    var readerBrightness by remember { mutableStateOf(1f) }
    var readerBrightnessTouched by remember { mutableStateOf(false) }
    // value == null reverts to the saved brightness; otherwise overrides value 0.
    fun readerApplyBrightness(value: Float?) {
        readerScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val svc = com.portalpad.app.service.PortalPadForegroundService.instance ?: return@launch
            val vals = com.portalpad.app.presentation.GlColorRenderer.parseValues(readerSavedTuning)
            if (value != null && vals.isNotEmpty()) vals[0] = value
            val mtx = com.portalpad.app.presentation.GlColorRenderer.matrixFromValues(vals)
            runCatching { svc.applyGlassesColorMatrix(mtx, vals.getOrElse(5) { 1f }) }
        }
    }
    androidx.compose.runtime.LaunchedEffect(readerMode) {
        if (readerMode) {
            readerBrightness = com.portalpad.app.presentation.GlColorRenderer
                .parseValues(readerSavedTuning).getOrElse(0) { 1f }
        } else if (readerBrightnessTouched) {
            readerApplyBrightness(null) // revert to the saved Display brightness
            readerBrightnessTouched = false
        }
    }
    var inClusterMenuOpen by remember { mutableStateOf(false) }
    var overlayMenuOpen by remember { mutableStateOf(false) }
    var showRemoteGuide by remember { mutableStateOf(false) }
    if (showRemoteGuide) {
        com.portalpad.app.ui.trackpad.RemoteGestureGuideDialog(onDismiss = { showRemoteGuide = false })
    }

    // Notification-access state for the slider's "tap to grant" hint. Read once
    // AND re-checked on every ON_RESUME, so granting access in system settings
    // and returning updates the hint immediately (previously it only refreshed
    // after navigating away and back).
    var notifAccessGranted by remember { mutableStateOf(app.media.hasNotificationAccess) }
    val notifLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(notifLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                notifAccessGranted = app.media.hasNotificationAccess
            }
        }
        notifLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { notifLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Live media-stream volume, shown in the D-pad box corner. A ContentObserver
    // on the system volume settings updates this whenever volume changes from
    // ANY source — including the phone's hardware volume buttons. Reflects the
    // system media stream (accurate for most cases; an app managing its own
    // internal volume separately won't be mirrored here).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val panelHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val audioManager = remember {
        ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    fun readVolPct(): Int {
        val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        return (cur * 100 / max)
    }
    fun readMuted(): Boolean {
        // Muted if AudioManager says so OR the effective volume is zero.
        // Some apps mute by setting volume to 0 directly without using
        // AudioManager's mute flag, so we check both.
        return audioManager.isStreamMute(android.media.AudioManager.STREAM_MUSIC) ||
            audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0
    }
    var volPct by remember { mutableStateOf(readVolPct()) }
    var isMuted by remember { mutableStateOf(readMuted()) }
    // Volume observer that (a) runs its reads on a background thread — this is
    // what makes hardware-volume changes actually report to the slider — and
    // (b) RE-REGISTERS on every ON_RESUME. The remote interface is only paused
    // (not destroyed) when you open Settings, so a one-time DisposableEffect
    // registration silently went dead after navigating away and back. Tying
    // (un)registration to the lifecycle re-establishes it each time you return.
    val volLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(volLifecycleOwner) {
        val bgThread = android.os.HandlerThread("portalpad-vol-observer").apply { start() }
        val bgHandler = android.os.Handler(bgThread.looper)
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val observer = object : android.database.ContentObserver(bgHandler) {
            override fun onChange(selfChange: Boolean) {
                val p = readVolPct()
                val m = readMuted()
                mainHandler.post { volPct = p; isMuted = m }
            }
        }
        var registered = false
        fun register() {
            if (registered) return
            ctx.contentResolver.registerContentObserver(
                android.provider.Settings.System.CONTENT_URI, true, observer,
            )
            registered = true
            // Re-sync immediately on (re)register so the slider reflects any
            // change that happened while we were away.
            val p = readVolPct(); val m = readMuted()
            mainHandler.post { volPct = p; isMuted = m }
        }
        fun unregister() {
            if (!registered) return
            runCatching { ctx.contentResolver.unregisterContentObserver(observer) }
            registered = false
        }
        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> register()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> unregister()
                else -> {}
            }
        }
        volLifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        // If already resumed when this effect runs, register now.
        if (volLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            register()
        }
        onDispose {
            volLifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            unregister()
            bgThread.quitSafely()
        }
    }
    // The ContentObserver above doesn't fire reliably for mute-flag-only
    // changes (vs actual volume-level changes) on some Android builds. So
    // we wrap the mute callback to force a state re-read right after the
    // toggle, guaranteeing the UI reflects the new state immediately.
    val onMuteWithRefresh: () -> Unit = {
        onMute()
        // Small delay lets the AudioManager state settle before we read.
        // 30ms is below human perceptual threshold and reliably catches the
        // post-toggle state on Samsung.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            volPct = readVolPct()
            isMuted = readMuted()
        }, 30)
    }

    // Scrub state: read live from active media session every second when user
    // isn't actively dragging. When user grabs the thumb, we stop polling so the
    // value doesn't fight them. On release, we seek to the value.
    var userDragging by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val hasActiveSession = durationMs > 0L

    LaunchedEffect(Unit) {
        while (true) {
            if (!userDragging) {
                val live = app.media.getCurrentPlaybackPosition()
                if (live != null) {
                    positionMs = live.first
                    durationMs = live.second
                } else {
                    durationMs = 0L
                }
            }
            kotlinx.coroutines.delay(1000L)
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .fillMaxSize()
            .drawBehind {
                // Focal "pool of light" biased up toward the D-pad, fading to a
                // dark vignette at the edges. Center at 40% height ≈ the D-pad.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF271C4C), Color(0xFF181126), Color(0xFF0E0A1A)),
                        center = androidx.compose.ui.geometry.Offset(
                            size.width * 0.5f,
                            size.height * 0.40f,
                        ),
                        radius = size.maxDimension * 0.62f,
                    ),
                )
            },
    ) {
        // Proportional scale for the whole remote, derived from the available
        // height. ~640dp tall is the "comfortable reference" (scale 1.0); shorter
        // screens scale the controls down, taller screens up — each CLAMPED so
        // buttons never become untappable-small or absurdly large. Width is
        // pinned to 411dp by PinnedDensityActivity, so height is the real
        // cross-device variable we scale against.
        val refHeight = 640f
        val scale = (maxHeight.value / refHeight).coerceIn(0.8f, 1.15f)
        // Scaled sizes used throughout (clamped via the scale bounds above).
        val transportSize = (44.dp * scale)
        val playSize = (76.dp * scale)
        val gapLarge = (12.dp * scale)
        val gapSmall = (6.dp * scale)

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 0.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp * scale),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ─── D-PAD with volume vertically centered on the right ───────
        // This region takes the leftover vertical space (weight) so the fixed
        // sections below (scrub bar, transport, custom actions) always get their
        // full height, and the D-pad scales to fit whatever room remains — so the
        // Remote fits any screen height WITHOUT scrolling or clipping.
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f),
        ) {
            // Cap the cluster a little smaller than the region so there's a real
            // gap above (and below) it for the Mode label — the cluster otherwise
            // nearly fills the region, leaving only ~11dp that crowded the label
            // against the ring. Kept CENTER-aligned so the volume column (also
            // centered) stays vertically aligned with the D-pad. The reserve is
            // kept just big enough for the label (~24dp) plus a small margin, so
            // the D-pad barely shrinks (56dp was too much — it visibly shrank the
            // ring and pulled the arrows toward the center).
            val topReserveDp = 32.dp
            val maxByHeight = maxHeight - topReserveDp
            val maxByWidth = maxWidth - 96.dp   // reserve room for the volume column
            val clusterSize = listOf(220.dp * scale, maxByHeight, maxByWidth)
                .minByOrNull { it.value }!!
                .coerceAtLeast(140.dp)
            // The D-pad cluster and its Back button shift right together by this
            // amount so the cluster reads centred in the gap between the left
            // column (power / Input) and the volume column, while Back keeps its
            // exact current position and gap relative to the ring (it rides
            // along). Tune on-device.
            val dpadGroupShift = 14.dp
            // Half the current gap between the Back button and the D-pad's left
            // edge, so Back tightens toward the cluster. Derived from the live
            // cluster size + region width (68dp = the D-pad's 40dp end-padding
            // halved plus the 48dp Back width), then halved. Tune via the constant.
            val backDpadGap = (maxWidth / 2 - clusterSize / 2 - 68.dp).coerceAtLeast(0.dp)
            val backShift = dpadGroupShift + backDpadGap / 2
            // Volume column nudged right so its centred label + pills sit at the
            // margin (matching the left controls' gap). Tune on-device.
            val volColShift = 12.dp
            // D-pad shifted slightly left so it doesn't crowd the volume column.
            // Bottom-anchored so the entire reserved band sits at the TOP for the
            // Mode label (centering the cluster would split the reserve top/bottom,
            // wasting half and forcing a bigger shrink). The volume column spans
            // the full region height independently, so this doesn't misalign it.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(end = 40.dp)
                    .offset(x = dpadGroupShift),
            ) {
                DpadCluster(
                    onUp = { if (scrollMode) app.injector.remoteScrollUp() else onDpadUp() },
                    onDown = { if (scrollMode) app.injector.remoteScrollDown() else onDpadDown() },
                    onLeft = { if (scrollMode) app.injector.remoteScrollLeft() else onDpadLeft() },
                    onRight = { if (scrollMode) app.injector.remoteScrollRight() else onDpadRight() },
                    onCenter = { if (scrollMode) app.injector.remoteCenterTap() else onDpadCenter() },
                    onCenterLongPress = { if (scrollMode) app.injector.remoteCenterLongPress() },
                    onReturn = onReturn,
                    size = clusterSize,
                    accent = inputMode != 0,
                )
            }
            // Access-mode status (Mode: Shizuku/Root + check/warning), centered in
            // the reserved band at the TOP of this region (the cluster is
            // bottom-anchored, so the whole reserve sits above it). Label centers
            // in the band = band/2 minus half the measured label height.
            val density = androidx.compose.ui.platform.LocalDensity.current
            var labelHeightDp by remember { mutableStateOf(0.dp) }
            // Centered in the reserved band, then nudged up slightly (−6dp) so it
            // sits a touch higher in the gap.
            val labelTopPad = (topReserveDp / 2 - labelHeightDp / 2 - 6.dp).coerceAtLeast(0.dp)
            com.portalpad.app.ui.common.ModeStatusLabel(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged {
                        labelHeightDp = with(density) { it.height.toDp() }
                    }
                    .padding(top = labelTopPad),
            )
            // Power button — top-left of the panel. Reusable composable that
            // handles all Extinguish state, dialogs, and intents internally.
            com.portalpad.app.ui.power.ExtinguishPowerButton(
                modifier = Modifier.align(Alignment.TopStart),
            )
            // Return button — left edge, lined up under the power button (same
            // left X), sitting low in the region. Medium-grey background with a
            // border matching the D-pad outer ring. Sends KEYCODE_BACK.
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = backShift)
                    .size(width = 48.dp, height = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF555559))
                    .border(1.5.dp, Color(0xFFEDEDE8).copy(alpha = 0.8f), RoundedCornerShape(20.dp))
                    .combinedClickable(
                        onClick = {
                            com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                            onReturn()
                        },
                        onLongClick = {
                            com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
                            com.portalpad.app.service.PortalPadForegroundService.showWallpaper()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.KeyboardReturn,
                    contentDescription = "Return",
                    tint = Color(0xFFEDEDE8),
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { scaleY = -1f },   // flip vertically: arrow-left, curve on top
                )
            }
            // Input-mode toggle — center-left, between the power button (top) and
            // Return (bottom). Switches the D-pad between regular keys and Scroll
            // (directional swipes + centre tap / long-press) for apps that ignore
            // D-pad navigation. Bordered pill so it reads as a button; labelled
            // "Input:" to avoid clashing with the "Mode:" access label up top.
            Box(Modifier.align(Alignment.CenterStart)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (inputMode != 0) AbPrimary.copy(alpha = 0.16f) else Color(0xFF1C1C20))
                        .border(
                            1.5.dp,
                            if (inputMode != 0) AbPrimary.copy(alpha = 0.78f) else Color(0xFFEDEDE8).copy(alpha = 0.18f),
                            RoundedCornerShape(14.dp),
                        )
                        .clickable {
                            com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                            inClusterMenuOpen = true
                        }
                        .padding(vertical = 5.dp, horizontal = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Input:",
                            color = if (inputMode != 0) AbPrimaryBright else Color(0xFF8E8E96),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                inputModeName,
                                color = if (inputMode != 0) Color(0xFFC9A9FF) else Color(0xFFE2E2DE),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                            Text(
                                " \u25BE",
                                color = if (inputMode != 0) AbPrimaryBright else Color(0xFF8E8E96),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = inClusterMenuOpen,
                    onDismissRequest = { inClusterMenuOpen = false },
                ) {
                    listOf(0 to "D-pad", 1 to "Scroll", 2 to "Gesture", 3 to "Reader").forEach { (value, label) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    color = if (value == inputMode) AbPrimaryBright else AbOnSurface,
                                    fontWeight = if (value == inputMode) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                                setInputMode(value)
                                inClusterMenuOpen = false
                            },
                        )
                    }
                }
            }
            // Volume — vertical slider on the right. The whole group spans the
            // region's full height, so its top aligns with the power button's
            // top edge and its bottom with the Return button's bottom edge.
            // Layout top→bottom: "Vol: %" label, slider (fills the middle via
            // weight — always the same long size, no grow/shift at 100%), mute.
            // Drag/tap the slider to set volume absolutely (top=100%, bottom=0%),
            // no Android volume popup.
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = volColShift)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Vol: $volPct%",
                    color = AbPrimaryBright,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                    // Fixed width sized for the widest value ("Vol: 100%"). The
                    // column centers its children, so a label that changes width
                    // with the digit-count (5% vs 100%) was re-centering the
                    // whole column and shifting the slider left/right. A constant
                    // width keeps the slider's X fixed.
                    modifier = Modifier.width(72.dp),
                )
                VolumeSlider(
                    volPct = volPct,
                    onSetVolumePct = { pct ->
                        volPct = pct                 // optimistic UI; observer confirms
                        onSetVolumePct(pct)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                )
                // Mute toggle — pill-shaped, sized to match the Return button on
                // the far left of the D-pad (48×40). Stays at the bottom of the
                // strip. The slider above keeps its 8dp gap to it.
                Box(
                    Modifier
                        .size(width = 48.dp, height = 40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, AbPrimaryBright, RoundedCornerShape(20.dp))
                        .clickable {
                            com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                            onMuteWithRefresh()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isMuted) Icons.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isMuted) "Muted (tap to unmute)" else "Mute",
                        tint = AbPrimaryBright,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // Gap between D-pad bottom and the slider, scaled with the panel.
        Spacer(Modifier.height(gapLarge))

        // ─── Scrub bar (only renders meaningfully if a media session exists) ──
        Column(Modifier.fillMaxWidth()) {
            val sliderValue = if (userDragging) dragValue
            else if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f

            // When there's no active session, show the status message ABOVE the
            // slider, centered (was below). "Needs notification access" is a red
            // tappable deep-link; otherwise a muted "needs an active session".
            if (!hasActiveSession) {
                val hasNotifAccess = notifAccessGranted
                if (!hasNotifAccess) {
                    Text(
                        "Slider needs Notification Access — tap to grant",
                        color = AbDanger,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Same flow as the "Media Controls" setting:
                                // deep-link to PortalPad's notification-access
                                // toggle, falling back to the general list.
                                runCatching {
                                    app.startActivity(
                                        android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                                            .putExtra(
                                                "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                                                "${app.packageName}/${app.packageName}.service.PortalPadNotificationListenerService",
                                            )
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }.onFailure {
                                    runCatching {
                                        app.startActivity(
                                            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                }
                            },
                    )
                } else {
                    Text(
                        "Slider needs an active media session",
                        color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Slider(
                value = sliderValue.coerceIn(0f, 1f),
                onValueChange = { v ->
                    userDragging = true
                    dragValue = v
                },
                onValueChangeFinished = {
                    if (durationMs > 0) {
                        val target = (dragValue * durationMs).toLong()
                        app.media.seekTo(target)
                        positionMs = target
                    }
                    userDragging = false
                },
                enabled = hasActiveSession,
                colors = SliderDefaults.colors(
                    thumbColor = AbPrimaryBright,
                    activeTrackColor = AbPrimary,
                    inactiveTrackColor = AbSurfaceElevated,
                    disabledThumbColor = AbOnSurfaceDim,
                    disabledActiveTrackColor = AbSurfaceElevated,
                    disabledInactiveTrackColor = AbSurfaceElevated,
                ),
            )
            // Time-stamp row only when there's an active session (otherwise two
            // lonely em-dashes read as artifacts).
            if (hasActiveSession) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        formatTime((sliderValue * durationMs / 1000).toFloat()),
                        color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "-${formatTime(((1 - sliderValue) * durationMs / 1000).toFloat())}",
                        color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // The Material Slider above reserves a tall touch target, leaving a big
        // invisible gap below its visible track. Pull the whole media-controls
        // group (transport + Custom Actions) UP with a negative offset to close
        // that gap so the slider→play/pause gap matches play/pause→Custom
        // Actions. The Custom Actions box uses mediaGroupLift; the transport
        // row uses a slightly smaller lift (transportLift) so it sits a touch
        // lower than the box — nudged down 4dp per the requested spacing.
        val mediaGroupLift = (-16).dp
        val transportLift = (-11).dp
        // The Custom Actions box is the LAST child (bottom-anchored). On the shared
        // mediaGroupLift it had ~10dp above (gap to the Note tip) but ~16dp below
        // (the lift vs the panel's 0 bottom-pad) — visibly lopsided. Its own lift
        // of -13 makes both gaps ~13dp, so the box sits centered in its slot.
        // TUNABLE: raise/lower a couple dp to nudge the centering.
        val customActionsLift = (-13).dp

        // Transport row: Previous | Rewind | Play-Pause | Fast-forward | Next.
        // Play/pause uses a single normal-sized icon and stays neutral because
        // media-playback state isn't reliably reported by all apps.
        Row(
            Modifier.fillMaxWidth().offset(y = transportLift),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlIconButton(Icons.Default.SkipPrevious, "Previous", onClick = onPrev, size = transportSize)
            ControlIconButton(Icons.Default.FastRewind, "Rewind", onClick = onRewind, size = transportSize)

            // Big play/pause — combo icon (▶ + ❙❙) with NEUTRAL background.
            // Stays unhighlighted because media playback state isn't reliably
            // reported by all apps; an always-correct "current state" tint
            // would be wrong half the time. Same size as before for thumb
            // comfort, just no longer the primary-color accent.
            Box(
                Modifier
                    .size(playSize)
                    .clip(CircleShape)
                    .background(AbSurfaceElevated)
                    .border(1.dp, AbOnSurfaceMuted.copy(alpha = 0.4f), CircleShape)
                    .combinedClickable(
                        onClick = {
                            com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                            onPlayPause()
                        },
                        onLongClick = {
                            com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
                            onStop()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play / Pause",
                        tint = AbOnSurface,
                        modifier = Modifier.size(26.dp * scale),
                    )
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = null,
                        tint = AbOnSurface,
                        modifier = Modifier.size(22.dp * scale),
                    )
                }
            }

            ControlIconButton(Icons.Default.FastForward, "Fast forward", onClick = onFastForward, size = transportSize)
            ControlIconButton(Icons.Default.SkipNext, "Next", onClick = onNext, size = transportSize)
        }

        // ─── Tip note (below the media controls) ────────────────────────
        // No explicit Spacers around this block: the Column already applies
        // spacedBy(10dp) between every child, and this region lives in the
        // weight(1f) layout where every extra dp shrinks the D-pad cluster
        // above. Keep it tight.
        Column(
            Modifier
                .fillMaxWidth()
                .offset(y = mediaGroupLift)
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    width = 1.dp,
                    color = AbPrimary.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = AbPrimary, fontWeight = FontWeight.Bold)) {
                        append("Note: ")
                    }
                    withStyle(SpanStyle(color = AbOnSurfaceMuted)) {
                        append(
                            "Some apps don't respond to the controls above. " +
                                "Use D-pad left/right to seek and OK to play/pause.",
                        )
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ─── Custom Actions ────────────────────────────────────────────
        // Bordered card to visually separate this section from the transport
        // controls above. Inside: header, four colored buttons, helper note.
        // Shorter button height + tighter padding so the section fits without
        // scrolling on common phone sizes. Uses its own customActionsLift (vs the
        // group's mediaGroupLift) so its top and bottom gaps stay balanced.
        Column(
            Modifier
                .fillMaxWidth()
                .offset(y = customActionsLift)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AbSurface.copy(alpha = 0.5f))
                .border(
                    width = 1.dp,
                    color = AbOnSurfaceDim.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                "Custom Actions",
                color = AbOnSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            ProgramKeysRow(
                onRed = onProgRed,
                onGreen = onProgGreen,
                onYellow = onProgYellow,
                onBlue = onProgBlue,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Long-press a button to assign an action or rename it.",
                color = AbOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

        // ── Gesture overlay ──────────────────────────────────────────────
        // When Input = Gesture, a full-container swipe surface floats on top of
        // the remote (which stays composed but hidden underneath, so nothing is
        // resized or restructured). Volume keeps its own column on the right;
        // power, the Mode label, and the Input dropdown ride on the overlay so
        // they stay reachable.
        if (gestureMode) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFF271C4C), Color(0xFF181126), Color(0xFF0E0A1A)),
                                center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.40f),
                                radius = size.maxDimension * 0.62f,
                            ),
                        )
                    },
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 16.dp),
                ) {
                    // Gesture surface: fills the width (even 20dp gutters on both
                    // sides) and takes all remaining height above the volume row +
                    // exit strip below. weight(1f) auto-shrinks it to make room for
                    // those, so no manual repositioning is needed.
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        GesturePad(
                            onUp = onDpadUp,
                            onDown = onDpadDown,
                            onLeft = onDpadLeft,
                            onRight = onDpadRight,
                            onTap = onDpadCenter,
                            onBack = onReturn,
                            onHome = { app.injector.home() },
                            onPlayPause = onPlayPause,
                            onPrev = onPrev,
                            onNext = onNext,
                            onRewind = { app.injector.mediaRew() },
                            onFastForward = { app.injector.mediaFf() },
                            onStop = { app.injector.mediaStop() },
                            onGuide = { showRemoteGuide = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                        com.portalpad.app.ui.power.ExtinguishPowerButton(
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        )
                        com.portalpad.app.ui.common.ModeStatusLabel(
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
                        )
                        // Slim "Input: Gesture ▾" pill, centered between the Mode label and the ▲.
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 26.dp)) {
                            androidx.compose.foundation.layout.Row(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(AbPrimary.copy(alpha = 0.16f))
                                    .border(1.5.dp, AbPrimary.copy(alpha = 0.62f), RoundedCornerShape(50))
                                    .clickable {
                                        com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                                        overlayMenuOpen = true
                                    }
                                    .padding(horizontal = 14.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Input: ", color = AbPrimaryBright, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    inputModeName,
                                    color = Color(0xFFC9A9FF),
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(" \u25BE", color = AbPrimaryBright, style = MaterialTheme.typography.bodySmall)
                            }
                            androidx.compose.material3.DropdownMenu(
                                expanded = overlayMenuOpen,
                                onDismissRequest = { overlayMenuOpen = false },
                            ) {
                                listOf(0 to "D-pad", 1 to "Scroll", 2 to "Gesture", 3 to "Reader").forEach { (value, label) ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Text(
                                                label,
                                                color = if (value == inputMode) AbPrimaryBright else AbOnSurface,
                                                fontWeight = if (value == inputMode) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                        },
                                        onClick = {
                                            com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                                            setInputMode(value)
                                            overlayMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // ─── Volume row (under the surface) ───────────────────────
                    // Equal-width side slots (72dp) so the pill is centered with
                    // the SAME gap to "Vol: %" (left) and the mute button (right):
                    // the label is right-aligned in its slot (hugs the pill), the
                    // mute is left-aligned (hugs the pill), and the pill carries
                    // matched 12dp side margins.
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().height(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                "Vol: $volPct%",
                                color = AbPrimaryBright,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                        HorizontalVolumeSlider(
                            volPct = volPct,
                            onSetVolumePct = { pct ->
                                volPct = pct
                                onSetVolumePct(pct)
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterStart) {
                            Box(
                                Modifier
                                    .size(width = 48.dp, height = 40.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .border(1.dp, AbPrimaryBright, RoundedCornerShape(20.dp))
                                    .clickable {
                                        com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                                        onMuteWithRefresh()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (isMuted) Icons.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = if (isMuted) "Muted (tap to unmute)" else "Mute",
                                    tint = AbPrimaryBright,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                    // ─── Tap-to-exit strip ────────────────────────────────────
                    // Elevated card (a step above the surface) spanning the same
                    // width. Tapping returns to the normal remote by setting the
                    // input mode back to D-pad, which drops gestureMode.
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(AbSurfaceElevated)
                            .border(1.dp, Color(0xFF3C3358), RoundedCornerShape(16.dp))
                            .clickable {
                                com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                                setInputMode(0)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Tap to exit",
                            color = AbOnSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        // ── Reader overlay ───────────────────────────────────────────────
        // Input = Reader: a reading surface (flip-method selector + big
        // Previous/Next page zones + pinch zoom) floating on the remote, with
        // the same chrome as the gesture overlay. Flip/scroll drive
        // focus-following keys/scroll, so they work in freeform windows too.
        // Tap-to-exit drops back to D-pad.
        if (readerMode) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0xFF271C4C), Color(0xFF181126), Color(0xFF0E0A1A)),
                                center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.40f),
                                radius = size.maxDimension * 0.62f,
                            ),
                        )
                    },
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 16.dp),
                ) {
                    Box(Modifier.fillMaxWidth().height(58.dp)) {
                        com.portalpad.app.ui.power.ExtinguishPowerButton(
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        )
                        com.portalpad.app.ui.common.ModeStatusLabel(
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
                        )
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 26.dp)) {
                            androidx.compose.foundation.layout.Row(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(AbPrimary.copy(alpha = 0.16f))
                                    .border(1.5.dp, AbPrimary.copy(alpha = 0.62f), RoundedCornerShape(50))
                                    .clickable {
                                        com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                                        overlayMenuOpen = true
                                    }
                                    .padding(horizontal = 14.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Input: ", color = AbPrimaryBright, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    inputModeName,
                                    color = Color(0xFFC9A9FF),
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(" \u25BE", color = AbPrimaryBright, style = MaterialTheme.typography.bodySmall)
                            }
                            androidx.compose.material3.DropdownMenu(
                                expanded = overlayMenuOpen,
                                onDismissRequest = { overlayMenuOpen = false },
                            ) {
                                listOf(0 to "D-pad", 1 to "Scroll", 2 to "Gesture", 3 to "Reader").forEach { (value, label) ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Text(
                                                label,
                                                color = if (value == inputMode) AbPrimaryBright else AbOnSurface,
                                                fontWeight = if (value == inputMode) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                        },
                                        onClick = {
                                            com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                                            setInputMode(value)
                                            overlayMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    ReaderControls(
                        flipMethod = readerFlip,
                        onSelectFlip = { readerScope.launch { app.prefs.setReaderFlipMethod(it) } },
                        onPrev = {
                            when (readerFlip) {
                                2 -> app.injector.remoteScrollUp()
                                1 -> app.injector.dpadLeft()
                                else -> app.injector.pageUp()
                            }
                        },
                        onNext = {
                            when (readerFlip) {
                                2 -> app.injector.remoteScrollDown()
                                1 -> app.injector.dpadRight()
                                else -> app.injector.pageDown()
                            }
                        },
                        onZoomIn = { app.injector.zoomStep(true) },
                        onZoomOut = { app.injector.zoomStep(false) },
                        onFind = {
                            // Ctrl+F opens the find bar in apps that honor it;
                            // KEYCODE_SEARCH is a fallback some apps (Kindle) map to
                            // in-app search. Then the a11y service finds the search
                            // field by NODE and clicks it to give it input focus —
                            // Kindle re-opens search with the field unfocused, so
                            // relay typing otherwise lands nowhere. It opens the
                            // relay on the field (or a plain relay if none found).
                            app.injector.pressKeyWithMeta(
                                android.view.KeyEvent.KEYCODE_F,
                                android.view.KeyEvent.META_CTRL_ON,
                            )
                            app.injector.pressKey(android.view.KeyEvent.KEYCODE_SEARCH, repin = false)
                            val a11y = com.portalpad.app.service.PortalPadAccessibilityService.instance
                            if (a11y != null) {
                                a11y.focusReaderSearchField(app.externalDisplayId.value)
                            } else {
                                readerScope.launch {
                                    kotlinx.coroutines.delay(250)
                                    runCatching {
                                        app.startActivity(
                                            android.content.Intent(app, com.portalpad.app.KeyboardRelayActivity::class.java)
                                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle(),
                                        )
                                    }
                                }
                            }
                        },
                        brightnessAvailable = readerBrightnessAvailable,
                        brightness = readerBrightness,
                        onBrightnessChange = { v -> readerBrightness = v; readerBrightnessTouched = true; readerApplyBrightness(v) },
                        onExitZoom = { app.injector.remoteCenterTap() },
                        onFixMirror = { readerScope.launch { app.prefs.setPanelSystemMirror(false) } },
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(AbSurfaceElevated)
                            .border(1.dp, Color(0xFF3C3358), RoundedCornerShape(16.dp))
                            .clickable {
                                com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                                setInputMode(0)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Tap to exit",
                            color = AbOnSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

    }
}

/** Resolved appearance of a program button — its custom [ProgKeyAppearance] if
 *  set, else the built-in defaults. Used both for the in-bar button and (so they
 *  match) the floating remap helper. */
private data class ProgLook(val bg: Color, val border: Color, val borderW: Float, val text: Color)

/** Temporarily hide the "Floating remap helper" flow. The feeder only works for
 *  a key-mapping app when the emitted key looks like real hardware input, which
 *  needs root (uinput) — not available on Shizuku-only devices, where the
 *  injected key is invisible to Key Mapper. All the floating-helper code
 *  (spawnFloating, the explainer, FloatingKeyButton, the size/position/live-
 *  refresh, the display-0 emit) is kept intact; flip this back to true to
 *  restore the menu entry once a root/uinput path exists. */
private const val SHOW_FLOATING_REMAP_HELPER = false

@Composable
private fun progLook(pickerTarget: String, base: Color): ProgLook {
    val app = com.portalpad.app.PortalPadApp.instance
    val style by app.prefs
        .let { prefs ->
            when (pickerTarget) {
                "prog_red" -> prefs.progKeyStyleRed
                "prog_green" -> prefs.progKeyStyleGreen
                "prog_yellow" -> prefs.progKeyStyleYellow
                else -> prefs.progKeyStyleBlue
            }
        }
        .collectAsState(initial = null)
    val bg = style?.let { Color(it.bgColor) } ?: base.copy(alpha = 0.85f)
    val border = style?.let { Color(it.borderColor) } ?: AbOnSurfaceMuted.copy(alpha = 0.4f)
    val w = style?.borderWidthDp ?: 1f
    val text = style?.let { Color(it.textColor) } ?: Color.White
    return ProgLook(bg, border, w, text)
}

/** Row of four colored, renameable programmable-shortcut keys. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ProgramKeysRow(
    onRed: () -> Unit,
    onGreen: () -> Unit,
    onYellow: () -> Unit,
    onBlue: () -> Unit,
) {
    val app = com.portalpad.app.PortalPadApp.instance
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val keys = com.portalpad.app.data.PreferencesRepository.Keys

    data class ProgKey(
        val nameKey: androidx.datastore.preferences.core.Preferences.Key<String>,
        val actionKey: androidx.datastore.preferences.core.Preferences.Key<String>,
        val pickerTarget: String,
        val default: String,
        val color: Color,
        val onPress: () -> Unit,
    )
    val progKeys = listOf(
        ProgKey(keys.PROG_KEY_NAME_RED, keys.PROG_KEY_ACTION_RED, "prog_red", "Red", Color(0xFFD7263D), onRed),
        ProgKey(keys.PROG_KEY_NAME_GREEN, keys.PROG_KEY_ACTION_GREEN, "prog_green", "Green", Color(0xFF2E9E4F), onGreen),
        ProgKey(keys.PROG_KEY_NAME_YELLOW, keys.PROG_KEY_ACTION_YELLOW, "prog_yellow", "Yellow", Color(0xFFE0B500), onYellow),
        ProgKey(keys.PROG_KEY_NAME_BLUE, keys.PROG_KEY_ACTION_BLUE, "prog_blue", "Blue", Color(0xFF2D7DD2), onBlue),
    )
    var renaming by remember { mutableStateOf<ProgKey?>(null) }
    var menuFor by remember { mutableStateOf<ProgKey?>(null) }
    var customizing by remember { mutableStateOf<ProgKey?>(null) }
    var explainFor by remember { mutableStateOf<ProgKey?>(null) }
    val floatingKey = remember { com.portalpad.app.service.FloatingKeyButton(app) }
    // One-time explainer suppression for this app session.
    var explainerSeen by remember { mutableStateOf(false) }
    // Which key's floating helper is currently up (pickerTarget), so a Save in the
    // appearance editor can live-refresh that pill in place.
    var floatingFor by remember { mutableStateOf<String?>(null) }
    // Measured on-screen size of each colored button, so the floating pill can be
    // laid out at the button's exact width/height (same phone, same density).
    var buttonSizesPx by remember { mutableStateOf<Map<String, IntSize>>(emptyMap()) }
    // The colored buttons' text size in PX, taken from the SAME Compose density
    // the pill's measured width/height come from. Applied to the pill as a raw px
    // unit so the overlay TextView (which lives on the app/phone context) can't
    // re-scale it by a different density — that mismatch was shrinking the label.
    val labelPx = with(LocalDensity.current) {
        MaterialTheme.typography.bodyMedium.fontSize.toPx()
    }

    fun spawnFloating(pk: ProgKey, label: String, look: ProgLook, preservePosition: Boolean = false) {
        floatingFor = pk.pickerTarget
        // The floating helper feeds a key-mapping app on the PHONE, so emit to
        // display 0 (the *OnPhone variants) rather than pk.onPress, which targets
        // the external display like a normal tap.
        val emit: () -> Unit = {
            when (pk.pickerTarget) {
                "prog_red" -> app.injector.progRedOnPhone()
                "prog_green" -> app.injector.progGreenOnPhone()
                "prog_yellow" -> app.injector.progYellowOnPhone()
                else -> app.injector.progBlueOnPhone()
            }
        }
        val sz = buttonSizesPx[pk.pickerTarget]
        floatingKey.show(
            label = label,
            labelPx = labelPx,
            bgColorArgb = look.bg.toArgb(),
            borderColorArgb = look.border.toArgb(),
            borderWidthDp = look.borderW,
            textColorArgb = look.text.toArgb(),
            fixedWidthPx = sz?.width ?: 0,
            fixedHeightPx = sz?.height ?: 0,
            preservePosition = preservePosition,
            onEmit = emit,
            onDismiss = { floatingFor = null },
        )
    }

    val progKeyHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        progKeys.forEach { pk ->
            val name by app.prefs.string(pk.nameKey, pk.default)
                .collectAsState(initial = pk.default)
            // Launch action for this key (null = key-send mode). When set, tapping
            // launches it instead of emitting the key event.
            val launchAction by app.prefs
                .let { prefs ->
                    when (pk.pickerTarget) {
                        "prog_red" -> prefs.progActionRed
                        "prog_green" -> prefs.progActionGreen
                        "prog_yellow" -> prefs.progActionYellow
                        else -> prefs.progActionBlue
                    }
                }
                .collectAsState(initial = null)
            // Per-key custom appearance (null = built-in look). When set, bg is
            // opaque, border uses the stored width (0 = none), text uses the stored
            // color. Defaults reproduce the original look so unedited keys are
            // unchanged.
            val style by app.prefs
                .let { prefs ->
                    when (pk.pickerTarget) {
                        "prog_red" -> prefs.progKeyStyleRed
                        "prog_green" -> prefs.progKeyStyleGreen
                        "prog_yellow" -> prefs.progKeyStyleYellow
                        else -> prefs.progKeyStyleBlue
                    }
                }
                .collectAsState(initial = null)
            val bgColor = style?.let { Color(it.bgColor) } ?: pk.color.copy(alpha = 0.85f)
            val borderColor = style?.let { Color(it.borderColor) }
                ?: AbOnSurfaceMuted.copy(alpha = 0.4f)
            val borderW = style?.borderWidthDp ?: 1f
            val textColor = style?.let { Color(it.textColor) } ?: Color.White
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .onGloballyPositioned { buttonSizesPx = buttonSizesPx + (pk.pickerTarget to it.size) }
                    .clip(RoundedCornerShape(12.dp))
                    .background(bgColor)
                    .then(
                        if (borderW > 0f) {
                            Modifier.border(borderW.dp, borderColor, RoundedCornerShape(12.dp))
                        } else {
                            Modifier
                        },
                    )
                    .combinedClickable(
                        onClick = {
                            com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                            val act = launchAction
                            if (act != null) {
                                com.portalpad.app.launchEntry(
                                    act, com.portalpad.app.PortalPadApp.instance.injector,
                                )
                            }
                            // Unassigned = DO NOTHING. The old default sent a gamepad
                            // keycode (pk.onPress → progRed()/…), but those BUTTON_1..4
                            // codes aren't received by apps on the external display
                            // (confirmed: Key Mapper couldn't even record them), so the
                            // default tap did nothing useful anyway. Assign an action via
                            // long-press to make a button do something. The keycode
                            // plumbing (pk.onPress) is left intact but unwired so it can
                            // be restored if those codes are ever made to work.
                        },
                        onLongClick = {
                            com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
                            menuFor = pk
                        },
                    )
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name,
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
        }
    }

    // Long-press action menu: assign a Button Action, use as a remap helper, or
    // rename. Actions are vertical rows so all three fit and stay tappable.
    menuFor?.let { pk ->
        val name by app.prefs.string(pk.nameKey, pk.default).collectAsState(initial = pk.default)
        val look = progLook(pk.pickerTarget, pk.color)
        val hasAction by app.prefs
            .let { prefs ->
                when (pk.pickerTarget) {
                    "prog_red" -> prefs.progActionRed
                    "prog_green" -> prefs.progActionGreen
                    "prog_yellow" -> prefs.progActionYellow
                    else -> prefs.progActionBlue
                }
            }
            .collectAsState(initial = null)
        val menuCtx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { menuFor = null },
            containerColor = AbSurfaceElevated,
            title = { Text(name, color = AbOnSurface) },
            text = {
                Column {
                    Text(
                        "Assign a Button Action (app, activity, or shortcut) to launch " +
                            "when tapped. You can also rename it.",
                        color = AbOnSurfaceMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    // Show what's currently assigned (app icon + app label +
                    // activity name) so it's clear which app/activity this key
                    // launches — not just a generic "Change action" label.
                    hasAction?.let { assigned ->
                        com.portalpad.app.ui.common.AssignedActionLabel(
                            entry = assigned, fallback = "", iconSizeDp = 28,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    // Button Action — opens the same picker Home/Back use. Setting an
                    // action switches this key to launch mode (replaces key-send).
                    ProgMenuRow(if (hasAction == null) "Button Actions" else "Change Button Action") {
                        menuFor = null
                        com.portalpad.app.ui.dock.launchDockPickerOnPhone(menuCtx, pk.pickerTarget)
                    }
                    // Floating remap helper — clears any launch action (→ key-send
                    // mode) and shows the floating button. Hidden for now (see
                    // SHOW_FLOATING_REMAP_HELPER) since it needs root/uinput to be
                    // captured by a key-mapping app; the code path is kept for later.
                    if (SHOW_FLOATING_REMAP_HELPER) {
                        ProgMenuRow("Floating remap helper") {
                            menuFor = null
                            scope.launch { app.prefs.setProgAction(pk.actionKey, null) }
                            if (explainerSeen) spawnFloating(pk, name, look) else explainFor = pk
                        }
                    }
                    // Clear the launch action without spawning the floating button.
                    if (hasAction != null) {
                        ProgMenuRow("Clear Button Action") {
                            menuFor = null
                            scope.launch { app.prefs.setProgAction(pk.actionKey, null) }
                        }
                    }
                    ProgMenuRow("Rename") {
                        menuFor = null
                        renaming = pk
                    }
                    ProgMenuRow("Customize appearance") {
                        menuFor = null
                        customizing = pk
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { menuFor = null }) {
                    Text("Close", color = AbOnSurfaceMuted)
                }
            },
        )
    }

    // One-time explainer before the floating button appears.
    explainFor?.let { pk ->
        val name by app.prefs.string(pk.nameKey, pk.default).collectAsState(initial = pk.default)
        val look = progLook(pk.pickerTarget, pk.color)
        val explainerCtx = androidx.compose.ui.platform.LocalContext.current
        fun openPlayStore(packageId: String) {
            val marketUri = android.net.Uri.parse("market://details?id=$packageId")
            val webUri = android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageId")
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, marketUri)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                explainerCtx.startActivity(intent)
            } catch (_: android.content.ActivityNotFoundException) {
                explainerCtx.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, webUri)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { explainFor = null },
            containerColor = AbSurfaceElevated,
            title = { Text("Remapping this key", color = AbOnSurface) },
            text = {
                Column {
                    Text(
                        "A floating button will appear on screen. It only SENDS this key — " +
                            "PortalPad can't remap it itself.\n\n" +
                            "To remap it: open a key-mapping app, start recording a trigger, " +
                            "then tap the floating button — the app should capture the key. " +
                            "Drag to move it; tap ✕ to dismiss.",
                        color = AbOnSurfaceMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Compatible apps:",
                        color = AbOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Key Mapper — install",
                        color = AbPrimaryBright,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openPlayStore("io.github.sds100.keymapper") }
                            .padding(vertical = 4.dp),
                    )
                    Text(
                        "Button Mapper — install",
                        color = AbPrimaryBright,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openPlayStore("flar2.homebutton") }
                            .padding(vertical = 4.dp),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    explainerSeen = true
                    explainFor = null
                    spawnFloating(pk, name, look)
                }) { Text("Got it", color = AbPrimaryBright) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { explainFor = null }) {
                    Text("Cancel", color = AbOnSurfaceMuted)
                }
            },
        )
    }

    renaming?.let { pk ->
        val current by app.prefs.string(pk.nameKey, pk.default).collectAsState(initial = pk.default)
        var text by remember(pk) {
            mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(current))
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = AbSurfaceElevated,
            title = { Text("Rename key", color = AbOnSurface) },
            text = {
                val maxLen = 8 // color keys are small — keep names short. Tunable.
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        text = if (v.text.length <= maxLen) {
                            v
                        } else {
                            val capped = v.text.take(maxLen)
                            androidx.compose.ui.text.input.TextFieldValue(
                                text = capped,
                                selection = androidx.compose.ui.text.TextRange(capped.length),
                            )
                        }
                    },
                    singleLine = true,
                    supportingText = { Text("${text.text.length}/$maxLen", color = AbOnSurfaceMuted) },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AbOnSurface, unfocusedTextColor = AbOnSurface,
                        focusedBorderColor = AbPrimaryBright, unfocusedBorderColor = AbSurface,
                        cursorColor = AbPrimaryBright,
                    ),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    // Allow a blank name (= no label on the key). Previously this
                    // fell back to the color-name default, so the user could never
                    // clear the text. Stored "" reads back as "" (the default only
                    // applies when the key was never set).
                    val v = text.text.trim()
                    scope.launch { app.prefs.setString(pk.nameKey, v) }
                    renaming = null
                }) { Text("Save", color = AbPrimaryBright) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { renaming = null }) {
                    Text("Cancel", color = AbOnSurfaceMuted)
                }
            },
        )
    }

    customizing?.let { pk ->
        val style by app.prefs
            .let { prefs ->
                when (pk.pickerTarget) {
                    "prog_red" -> prefs.progKeyStyleRed
                    "prog_green" -> prefs.progKeyStyleGreen
                    "prog_yellow" -> prefs.progKeyStyleYellow
                    else -> prefs.progKeyStyleBlue
                }
            }
            .collectAsState(initial = null)
        val name by app.prefs.string(pk.nameKey, pk.default).collectAsState(initial = pk.default)
        ProgKeyCustomizeDialog(
            keyLabel = name,
            keyColor = pk.color,
            current = style,
            onSave = { appearance ->
                scope.launch { app.prefs.setProgKeyStyle(pk.pickerTarget, appearance) }
                // If this key's floating helper is up, refresh it in place with the
                // new colors/opacity — no need to re-launch it to see the change.
                if (floatingFor == pk.pickerTarget && floatingKey.isShown) {
                    spawnFloating(
                        pk, name,
                        ProgLook(
                            Color(appearance.bgColor),
                            Color(appearance.borderColor),
                            appearance.borderWidthDp,
                            Color(appearance.textColor),
                        ),
                        preservePosition = true,
                    )
                }
                customizing = null
            },
            onReset = {
                scope.launch { app.prefs.resetProgKeyStyle(pk.pickerTarget) }
                customizing = null
            },
            onDismiss = { customizing = null },
        )
    }
}

/** A full-width tappable row used in the color-key long-press menu (vertical
 *  list of actions, so all options fit and stay easy to tap). */
@Composable
private fun ProgMenuRow(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AbBackground)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, color = AbPrimaryBright, fontWeight = FontWeight.SemiBold)
    }
}

/** A label + tappable color swatch row used in the program-key appearance editor. */
@Composable
private fun ProgSwatchRow(label: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = AbOnSurface, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color)
                .border(1.dp, AbOnSurfaceMuted.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
        )
    }
}

/** Per-key appearance editor: background / border / text colors, background &
 *  border opacity, and border width, with a live preview and Reset. */
@Composable
private fun ProgKeyCustomizeDialog(
    keyLabel: String,
    keyColor: Color,
    current: com.portalpad.app.data.ProgKeyAppearance?,
    onSave: (com.portalpad.app.data.ProgKeyAppearance) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Colors hold RGB only; opacity is tracked separately so the HSV picker
    // (which outputs opaque colors) and the alpha sliders don't clobber each
    // other. Existing customized keys were saved opaque, so the alphas init to
    // 1f and their look is unchanged until a slider is moved.
    var bg by remember(current) {
        mutableStateOf(current?.let { Color(it.bgColor).copy(alpha = 1f) } ?: keyColor)
    }
    var bgAlpha by remember(current) {
        mutableStateOf(current?.let { Color(it.bgColor).alpha } ?: 1f)
    }
    var border by remember(current) {
        mutableStateOf(current?.let { Color(it.borderColor).copy(alpha = 1f) } ?: AbOnSurfaceMuted)
    }
    var borderAlpha by remember(current) {
        mutableStateOf(current?.let { Color(it.borderColor).alpha } ?: 1f)
    }
    var textColor by remember(current) {
        mutableStateOf(current?.let { Color(it.textColor) } ?: Color.White)
    }
    var borderW by remember(current) { mutableStateOf(current?.borderWidthDp ?: 1f) }
    var editing by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AbSurfaceElevated,
        title = { Text("Key appearance", color = AbOnSurface) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Live preview of the key with the working style.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bg.copy(alpha = bgAlpha))
                        .then(
                            if (borderW > 0f) {
                                Modifier.border(borderW.dp, border.copy(alpha = borderAlpha), RoundedCornerShape(12.dp))
                            } else {
                                Modifier
                            },
                        )
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        keyLabel.ifBlank { "Key" },
                        color = textColor,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
                ProgSwatchRow("Background", bg.copy(alpha = bgAlpha)) { editing = "bg" }
                ProgSwatchRow("Border", border.copy(alpha = borderAlpha)) { editing = "border" }
                ProgSwatchRow("Text", textColor) { editing = "text" }
                Spacer(Modifier.height(14.dp))
                Text("Background opacity: ${(bgAlpha * 100).toInt()}%", color = AbOnSurface)
                androidx.compose.material3.Slider(
                    value = bgAlpha,
                    onValueChange = { bgAlpha = it },
                    valueRange = 0f..1f,
                )
                Text(
                    "Border width: ${(borderW + 0.5f).toInt()} px",
                    color = AbOnSurface,
                )
                androidx.compose.material3.Slider(
                    value = borderW,
                    onValueChange = { borderW = it },
                    valueRange = 0f..5f,
                    steps = 4, // discrete stops at 0,1,2,3,4,5
                )
                Text("Border opacity: ${(borderAlpha * 100).toInt()}%", color = AbOnSurface)
                androidx.compose.material3.Slider(
                    value = borderAlpha,
                    onValueChange = { borderAlpha = it },
                    valueRange = 0f..1f,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                onSave(
                    com.portalpad.app.data.ProgKeyAppearance(
                        bgColor = bg.copy(alpha = bgAlpha).toArgbInt(),
                        borderColor = border.copy(alpha = borderAlpha).toArgbInt(),
                        textColor = textColor.toArgbInt(),
                        borderWidthDp = borderW,
                    ),
                )
            }) { Text("Save", color = AbPrimaryBright) }
        },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = onReset) {
                    Text("Reset", color = AbOnSurfaceMuted)
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Cancel", color = AbOnSurfaceMuted)
                }
            }
        },
    )

    editing?.let { which ->
        val cur = when (which) {
            "bg" -> bg
            "border" -> border
            else -> textColor
        }
        com.portalpad.app.ui.common.ColorPickerDialog(
            title = when (which) {
                "bg" -> "Background"
                "border" -> "Border"
                else -> "Text"
            },
            color = cur,
            onColorChange = { c ->
                when (which) {
                    "bg" -> bg = c
                    "border" -> border = c
                    else -> textColor = c
                }
            },
            onDismiss = { editing = null },
        )
    }
}
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DpadCluster(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onCenter: () -> Unit,
    onCenterLongPress: () -> Unit = {},
    onReturn: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 220.dp,
    accent: Boolean = false,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Alt-input modes (Scroll/Gesture) tint the ring/center/arrow accents with the
    // violet used by the input-mode pill — a color-consistent "not plain D-pad"
    // signal. Dark bases stay; only the accents (borders, arrows, OK) shift.
    val ringBorderColor = if (accent) AbPrimaryBright.copy(alpha = 0.9f) else Color(0xFFEDEDE8).copy(alpha = 0.8f)
    val ringBgColor = if (accent) Color(0xFF150C22) else Color(0xFF0A0A0C)
    // Accent (Scroll/Gesture) modes: tint the OK disc to match the active
    // input-mode toggle button's fill (AbPrimary @16% alpha) so they read as a set.
    val centerBgColor = if (accent) AbPrimary.copy(alpha = 0.16f) else Color(0xFF2C2C30)
    val okColor = if (accent) AbPrimaryBright else Color(0xFFB8B8BE)
    Box(
        Modifier
            .size(size),
        contentAlignment = Alignment.Center,
    ) {
        // Fire-TV-style outer ring: black disc with a thin off-white border.
        // The arrows sit directly on this ring (transparent arrow buttons), and
        // the medium-grey OK circle sits in the center.
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(ringBgColor)
                .border(1.5.dp, ringBorderColor, CircleShape)
        )

        // Place each chevron's CENTER midway between the OK disc edge (radius
        // 38dp) and the ring rim (radius size/2), on its radial axis — so all
        // four are equidistant from center and visually centered in the ring
        // band, rather than hugging the rim (the old TopCenter/CenterStart/etc.
        // alignment pushed them to the bounding-box edges).
        val r = 19.dp + size / 4f

        DpadButton(
            icon = Icons.Default.KeyboardArrowUp,
            contentDescription = "D-pad up",
            onPress = onUp,
            modifier = Modifier.align(Alignment.Center).offset(y = -r),
            ringStyle = true,
            accent = accent,
        )
        DpadButton(
            icon = Icons.Default.KeyboardArrowDown,
            contentDescription = "D-pad down",
            onPress = onDown,
            modifier = Modifier.align(Alignment.Center).offset(y = r),
            ringStyle = true,
            accent = accent,
        )
        // D-pad left/right deliberately use the non-AutoMirrored icons. A
        // D-pad's "left" arrow must physically point left regardless of
        // language direction — it's a directional input control, not a
        // language-flow semantic. AutoMirrored versions would flip in RTL
        // locales, which would be wrong here. The file-level @Suppress at
        // the top silences the deprecation warning for these specific uses.
        DpadButton(
            icon = Icons.Default.KeyboardArrowLeft,
            contentDescription = "D-pad left",
            onPress = onLeft,
            modifier = Modifier.align(Alignment.Center).offset(x = -r),
            ringStyle = true,
            accent = accent,
        )
        DpadButton(
            icon = Icons.Default.KeyboardArrowRight,
            contentDescription = "D-pad right",
            onPress = onRight,
            modifier = Modifier.align(Alignment.Center).offset(x = r),
            ringStyle = true,
            accent = accent,
        )
        // Center OK button — no repeat, single press. Darker-grey disc with a
        // thin off-white border; the "OK" label blends into the disc (low
        // contrast) rather than standing out.
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(centerBgColor)
                .border(1.5.dp, ringBorderColor, CircleShape)
                .combinedClickable(
                    onClick = {
                        com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                        onCenter()
                    },
                    onLongClick = {
                        com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
                        onCenterLongPress()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "OK",
                color = okColor,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * A directional button. Fires `onPress` once immediately on touch-down. Only
 * if the finger is HELD past a deliberate threshold does it begin auto-
 * repeating — so a normal tap is always exactly one press, never a burst.
 * The repeat loop lives inside LaunchedEffect(isPressed): when the finger
 * lifts, isPressed flips false, the effect is cancelled, and repeating stops
 * immediately with no trailing events.
 */
@Composable
private fun DpadButton(
    icon: ImageVector,
    contentDescription: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    ringStyle: Boolean = false,
    accent: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    LaunchedEffect(isPressed) {
        if (isPressed) {
            // Haptic on the initial press only — fires on every tap, but
            // deliberately NOT inside the auto-repeat loop below. Continuous
            // vibration during a held button feels like a stuttering motor
            // and gets uncomfortable fast.
            com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
            onPress()            // exactly one press on touch-down
            // Wait out a deliberate hold before repeating. A normal tap lifts
            // well before this, so isPressed flips false, this effect is
            // cancelled here, and NO repeat fires. Only a sustained hold
            // reaches the loop.
            delay(500)
            while (true) {
                onPress()
                delay(90)        // ~11/sec while held — smooth list scrolling
            }
        }
    }

    // ringStyle = the arrow sits directly on the Fire-TV black ring: no plate,
    // no border, off-white glyph that blends with the ring. Default = the
    // standalone elevated-circle button used elsewhere.
    val base = if (ringStyle) {
        modifier.size(size).clip(CircleShape)
    } else {
        modifier.size(size).clip(CircleShape)
            .background(AbSurfaceElevated)
            .border(1.dp, AbOnSurfaceMuted.copy(alpha = 0.4f), CircleShape)
    }
    Box(
        base.clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = {},    // press/repeat handled above
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (ringStyle) (if (accent) AbPrimaryBright else Color(0xFFEDEDE8)) else AbPrimaryBright,
            modifier = Modifier.size(if (ringStyle) 34.dp else 28.dp),
        )
    }
}

private val VolButtonColor = Color(0xFF5B4A8F) // muted purple — softer than AbPrimary

/**
 * Vertical volume slider strip. Drag (or tap) anywhere on the track to set the
 * volume absolutely: the top of the track is 100%, the bottom is 0%. Reports
 * the chosen percentage via [onSetVolumePct]; the caller routes that to
 * MediaController.setVolumePct which changes the level WITHOUT the Android
 * volume popup. The filled portion reflects the current [volPct].
 */
@Composable
private fun VolumeSlider(
    volPct: Int,
    onSetVolumePct: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackWidth = 44.dp
    // Track height in px, captured on layout, so a touch Y can be mapped to a
    // percentage. Updated via onSizeChanged.
    var trackHeightPx by remember { mutableStateOf(1f) }

    fun pctFromY(y: Float): Int {
        // Top (y=0) = 100%, bottom (y=height) = 0%.
        val frac = 1f - (y / trackHeightPx).coerceIn(0f, 1f)
        return (frac * 100f).toInt().coerceIn(0, 100)
    }

    Box(
        modifier
            .width(trackWidth)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF1E1E24))
            .border(1.dp, AbPrimaryBright, RoundedCornerShape(22.dp))
            .onSizeChanged { trackHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                // Throttle writes: only call onSetVolumePct when the rounded
                // percentage actually changes during the drag, instead of on
                // every pointer move. Avoids flooding AudioManager (which is the
                // expensive part) with dozens of identical writes per second.
                var lastSent = -1
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                        val p = pctFromY(offset.y)
                        lastSent = p
                        onSetVolumePct(p)
                    },
                    onDragEnd = { lastSent = -1 },
                ) { change, _ ->
                    val p = pctFromY(change.position.y)
                    if (p != lastSent) {
                        lastSent = p
                        onSetVolumePct(p)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                    onSetVolumePct(pctFromY(offset.y))
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Filled portion = current volume level.
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(volPct / 100f)
                .clip(RoundedCornerShape(22.dp))
                .background(VolButtonColor),
        )
    }
}


/**
 * Horizontal volume pill used in gesture mode. Same theme as the vertical
 * [VolumeSlider] — dark #1E1E24 track, AbPrimaryBright border, VolButtonColor
 * fill — but laid out left→right (0% left, 100% right) with a draggable thumb.
 * The visible track is slim (16dp) while the touch target is the full row height
 * (so it's easy to grab). Writes are throttled to actual percent changes, the
 * same as the vertical slider.
 */
@Composable
private fun HorizontalVolumeSlider(
    volPct: Int,
    onSetVolumePct: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var trackWidthPx by remember { mutableStateOf(1f) }

    fun pctFromX(x: Float): Int {
        val frac = (x / trackWidthPx).coerceIn(0f, 1f)
        return (frac * 100f).toInt().coerceIn(0, 100)
    }

    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .onSizeChanged { trackWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                // Throttle: only write when the rounded percent actually changes
                // during the drag (AudioManager writes are the expensive part).
                var lastSent = -1
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                        val p = pctFromX(offset.x)
                        lastSent = p
                        onSetVolumePct(p)
                    },
                    onDragEnd = { lastSent = -1 },
                ) { change, _ ->
                    val p = pctFromX(change.position.x)
                    if (p != lastSent) {
                        lastSent = p
                        onSetVolumePct(p)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                    onSetVolumePct(pctFromX(offset.x))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val fullWidth = maxWidth
        // Slim track + left→right fill.
        Box(
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF1E1E24))
                .border(1.dp, AbPrimaryBright, RoundedCornerShape(50)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((volPct / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(VolButtonColor),
            )
        }
        // Thumb centered on the fill edge (18dp, with the usual half-overhang at
        // the extremes, matching a normal slider handle).
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = fullWidth * (volPct / 100f) - 9.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(AbPrimaryBright)
                .border(2.dp, Color(0xFF2A2433), CircleShape),
        )
    }
}


@Composable
private fun ControlIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Dp,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(AbSurfaceElevated)
            .border(1.dp, AbOnSurfaceMuted.copy(alpha = 0.4f), CircleShape)
            .clickable {
                com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = AbOnSurface)
    }
}

private fun formatTime(seconds: Float): String {
    val s = seconds.toInt().coerceAtLeast(0)
    val m = s / 60
    val r = s % 60
    return "%d:%02d".format(m, r)
}
