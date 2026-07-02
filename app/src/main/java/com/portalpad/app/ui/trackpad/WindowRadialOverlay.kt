package com.portalpad.app.ui.trackpad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.portalpad.app.PortalPadApp
import com.portalpad.app.service.PortalPadForegroundService
import com.portalpad.app.ui.theme.AbAccent
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceDim
import com.portalpad.app.ui.theme.AbPrimary
import com.portalpad.app.ui.theme.AbPrimaryBright
import com.portalpad.app.ui.theme.AbSurface
import com.portalpad.app.ui.theme.AbSurfaceElevated
import com.portalpad.app.ui.theme.AbSurfaceVariant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radial window-actions menu for the trackpad / air-mouse interface, opened by
 * tapping the arrange button. Mirrors the top bar's eight desktop-window actions:
 * arrange-evenly is the center (one-tap, the safe default); the seven others sit
 * on an upper ring + a lower "window size" arch. Ring/arch actions arm on first
 * tap (showing their name) and run on a second tap; the center runs immediately.
 * Actions that don't apply at the current window count are dimmed and inert.
 *
 * State comes from the shared [com.portalpad.app.service.WindowMonitor] snapshot +
 * the saved-session flow (same source the top bar uses). Actions are dispatched to
 * the top-bar overlay via [PortalPadForegroundService.runWindowAction], which owns
 * the tested handlers — closeAll runs directly since the two-tap arm is the confirm.
 */
@Composable
fun WindowRadialOverlay(onDismiss: () -> Unit) {
    val app = PortalPadApp.instance
    val snap by app.windowMonitor.snapshot.collectAsState()
    val session by app.prefs.lastSession.collectAsState(initial = null)

    val count = snap.tasks.count {
        it.visible &&
            !it.packageName.contains("launcher", ignoreCase = true) &&
            !it.packageName.contains("nexuslauncher", ignoreCase = true) &&
            it.packageName != "com.portalpad.app"
    }
    val hasMaximized = snap.maximizedId != null
    val hasSession = session?.windows?.isNotEmpty() == true

    fun isLive(id: String): Boolean = when (id) {
        "arrangeEvenly", "arrangeInOrder", "stack" -> count >= 2
        "restoreSession" -> hasSession
        "restoreFreeform" -> hasMaximized
        else -> count >= 1 // minimize, maximize, closeAll
    }

    var armed by remember { mutableStateOf<String?>(null) }

    fun fire(id: String) {
        com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
        PortalPadForegroundService.runWindowAction(id)
        onDismiss()
    }

    fun tap(id: String) {
        if (!isLive(id)) return
        if (armed == id) {
            fire(id)
        } else {
            com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
            armed = id
        }
    }

    val countText = when (count) {
        0 -> "No windows open"
        1 -> "1 window open"
        else -> "$count windows open"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .padding(bottom = 72.dp)
                    .pointerInput(Unit) { detectTapGestures { } },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Window-count header above the wheel.
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(AbSurface)
                        .border(1.dp, AbPrimary.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(countText, color = AbPrimaryBright, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                // The wheel: center + upper ring (4) + lower size-arch (3).
                Box(
                    Modifier
                        .size(268.dp)
                        .pointerInput(Unit) { detectTapGestures { } },
                    contentAlignment = Alignment.Center,
                ) {
                    RING.forEach { (id, glyph, angle) ->
                        val rad = angle * PI / 180.0
                        WheelSlot(
                            glyph = glyph,
                            live = isLive(id),
                            armed = armed == id,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(
                                    x = (RING_RADIUS * cos(rad)).toFloat().dp,
                                    y = (RING_RADIUS * sin(rad)).toFloat().dp,
                                ),
                            onTap = { tap(id) },
                        )
                    }
                    ARCH.forEach { (id, glyph, angle) ->
                        val rad = angle * PI / 180.0
                        WheelSlot(
                            glyph = glyph,
                            live = isLive(id),
                            armed = armed == id,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(
                                    x = (ARCH_RADIUS * cos(rad)).toFloat().dp,
                                    y = (ARCH_RADIUS * sin(rad)).toFloat().dp,
                                ),
                            onTap = { tap(id) },
                        )
                    }
                    // Center: arrange evenly (one tap).
                    CenterHub(
                        live = isLive("arrangeEvenly"),
                        onTap = { if (isLive("arrangeEvenly")) fire("arrangeEvenly") },
                    )
                }

                // Label pill: icon + name of the armed action, else a prompt.
                LabelPill(armed)
            }
        }
    }
}

@Composable
private fun WheelSlot(
    glyph: String,
    live: Boolean,
    armed: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    var m = modifier
        .size(52.dp)
        .clip(CircleShape)
        .background(if (armed) AbPrimary else AbSurfaceElevated)
        .border(
            width = if (armed) 1.5.dp else 1.dp,
            color = if (armed) AbAccent else AbOnSurfaceDim.copy(alpha = 0.4f),
            shape = CircleShape,
        )
    if (live) m = m.clickable(interactionSource = src, indication = null) { onTap() }
    Box(m.alpha(if (live) 1f else 0.3f), contentAlignment = Alignment.Center) {
        Text(glyph, color = AbOnSurface, fontSize = 22.sp)
    }
}

@Composable
private fun CenterHub(live: Boolean, onTap: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    var m = Modifier
        .size(88.dp)
        .clip(CircleShape)
        .background(AbSurfaceVariant)
        .border(1.5.dp, AbPrimary.copy(alpha = 0.5f), CircleShape)
    if (live) m = m.clickable(interactionSource = src, indication = null) { onTap() }
    Box(m.alpha(if (live) 1f else 0.3f), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("\u2637", color = AbOnSurface, fontSize = 24.sp)
            Text("evenly", color = AbPrimaryBright, fontSize = 9.sp)
        }
    }
}

@Composable
private fun LabelPill(armed: String?) {
    val meta = armed?.let { META[it] }
    val confirm = meta != null
    Box(
        Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(AbSurfaceElevated)
            .border(
                1.dp,
                if (confirm) AbAccent else AbOnSurfaceDim.copy(alpha = 0.4f),
                RoundedCornerShape(15.dp),
            )
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (meta != null) {
                Text(meta.first, color = AbAccent, fontSize = 16.sp)
                Text(
                    "${meta.second}  \u2014  tap again",
                    color = AbAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                Text("Tap an action", color = AbOnSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// id, glyph, angle(deg: 0=right, 90=down, -90=up). Upper ring, clockwise from
// upper-left: close-all sits far from the center's one-tap arrange-evenly.
private val RING = listOf(
    Triple("closeAll", "\u2715", -144.0),
    Triple("arrangeInOrder", "\u229E", -108.0),
    Triple("stack", "\u29C9", -72.0),
    Triple("restoreSession", "\u21BB", -36.0),
)

// Lower "window size" arch below center: minimize, restore-freeform, maximize.
private val ARCH = listOf(
    Triple("minimize", "\u2212", 128.0),
    Triple("restoreFreeform", "\u2750", 90.0),
    Triple("maximize", "\u26F6", 52.0),
)

private const val RING_RADIUS = 104.0
private const val ARCH_RADIUS = 96.0

private val META = mapOf(
    "arrangeEvenly" to Pair("\u2637", "Arrange evenly"),
    "arrangeInOrder" to Pair("\u229E", "Arrange in order"),
    "stack" to Pair("\u29C9", "Stack"),
    "restoreSession" to Pair("\u21BB", "Restore session"),
    "closeAll" to Pair("\u2715", "Close all"),
    "minimize" to Pair("\u2212", "Minimize"),
    "restoreFreeform" to Pair("\u2750", "Restore freeform"),
    "maximize" to Pair("\u26F6", "Maximize"),
)
