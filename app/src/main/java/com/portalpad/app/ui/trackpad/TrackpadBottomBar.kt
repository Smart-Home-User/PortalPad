package com.portalpad.app.ui.trackpad

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbSurfaceElevated

// Muted purple for the three navigation pills — quieter than the previous
// bright AbPrimary so they read as persistent utility rather than primary
// actions. Same shade used elsewhere for volume buttons; keeps the palette
// consistent.
private val NavPillColor = Color(0xFF5B4A8F)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackpadBottomBar(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onAppDrawer: () -> Unit,
    onKeyboard: (() -> Unit)?,
    onHomeLongPress: () -> Unit,
    onBackLongPress: () -> Unit,
    onAppDrawerLongPress: () -> Unit = {},
    onKeyboardLongPress: () -> Unit = {},
    backAppearance: com.portalpad.app.data.ButtonAppearance? = null,
    homeAppearance: com.portalpad.app.data.ButtonAppearance? = null,
    appDrawerAppearance: com.portalpad.app.data.ButtonAppearance? = null,
    keyboardAppearance: com.portalpad.app.data.ButtonAppearance? = null,
    order: List<String> = com.portalpad.app.data.PreferencesRepository.DEFAULT_NAV_ORDER,
    reorderMode: Boolean = false,
    onExitReorder: () -> Unit = {},
    onCommitOrder: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Per-id specs so the four pills can render in any saved order and be
    // reordered. Colors/icons default to the original fixed layout.
    val backBg = backAppearance?.let { Color(it.bgColor) } ?: NavPillColor
    val backTint = backAppearance?.let { Color(it.iconColor) } ?: AbOnSurface
    val homeBg = homeAppearance?.let { Color(it.bgColor) } ?: NavPillColor
    val homeTint = homeAppearance?.let { Color(it.iconColor) } ?: AbOnSurface
    val drawerBg = appDrawerAppearance?.let { Color(it.bgColor) } ?: NavPillColor
    val drawerTint = appDrawerAppearance?.let { Color(it.iconColor) } ?: AbOnSurface
    val kbBg = keyboardAppearance?.let { Color(it.bgColor) } ?: AbSurfaceElevated
    val kbTint = keyboardAppearance?.let { Color(it.iconColor) } ?: AbOnSurfaceMuted

    val specs = mapOf(
        "back" to NavButtonSpec("back", backBg, backTint, backAppearance, Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, onBackLongPress),
        "home" to NavButtonSpec("home", homeBg, homeTint, homeAppearance, Icons.Default.Home, "Home", onHome, onHomeLongPress),
        "appdrawer" to NavButtonSpec("appdrawer", drawerBg, drawerTint, appDrawerAppearance, Icons.Default.AutoAwesome, "App drawer", onAppDrawer, onAppDrawerLongPress),
        "keyboard" to NavButtonSpec("keyboard", kbBg, kbTint, keyboardAppearance, Icons.Default.Keyboard, "Use Phone as a Keyboard", onKeyboard ?: {}, onKeyboardLongPress),
    )
    // Keyboard pill renders only when its relay action exists; its slot in [order]
    // is preserved either way so positions stay stable when it toggles.
    val visible = order.filter { specs.containsKey(it) && (it != "keyboard" || onKeyboard != null) }

    // Reorder state: tap a pill to pick it up, tap another to drop it there
    // (mirrors the dock's reorder UX). Cleared whenever reorder mode exits.
    var pickedUpId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reorderMode) { if (!reorderMode) pickedUpId = null }
    fun reorderTap(id: String) {
        val picked = pickedUpId
        when {
            picked == null -> pickedUpId = id          // pick up
            picked == id -> pickedUpId = null          // tap held again → put back
            else -> {                                  // drop held at this pill's slot
                val from = order.indexOf(picked)
                val to = order.indexOf(id)
                if (from >= 0 && to >= 0 && from != to) {
                    val m = order.toMutableList()
                    m.add(to, m.removeAt(from))
                    onCommitOrder(m)
                }
                pickedUpId = null
            }
        }
    }

    androidx.compose.foundation.layout.Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            visible.forEach { id ->
                val spec = specs.getValue(id)
                Pill(
                    Modifier.weight(1f),
                    color = spec.bg,
                    wiggling = reorderMode,
                    pickedUp = reorderMode && pickedUpId == id,
                    phaseKey = id,
                    onClick = if (reorderMode) ({ reorderTap(id) }) else spec.onClick,
                    onLongClick = if (reorderMode) ({}) else spec.onLongClick,
                ) {
                    com.portalpad.app.ui.common.ButtonGlyph(
                        appearance = spec.appearance,
                        fallback = spec.fallback,
                        tint = spec.tint,
                        contentDescription = spec.contentDescription,
                    )
                }
            }
        }
        if (reorderMode) {
            ReorderBanner(holding = pickedUpId != null, onDone = onExitReorder)
        } else {
            // Hint: every nav button has a long-press menu (contents vary per
            // button). Kept generic — naming them in order would go stale once the
            // buttons are reordered.
            androidx.compose.material3.Text(
                text = "Long-press a button for more options.",
                color = AbOnSurfaceMuted,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 2.dp),
            )
        }
    }
}

/** One bottom-bar nav button, resolved to concrete colors/icon/handlers. */
private data class NavButtonSpec(
    val id: String,
    val bg: Color,
    val tint: Color,
    val appearance: com.portalpad.app.data.ButtonAppearance?,
    val fallback: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
    val onLongClick: () -> Unit,
)

/** Instruction + Done banner shown below the pills while reordering. */
@Composable
private fun ReorderBanner(holding: Boolean, onDone: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.material3.Text(
            text = if (holding) "Tap a spot to drop it" else "Reorder mode — tap a button to pick it up",
            color = AbOnSurface,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        )
        androidx.compose.material3.Surface(
            color = com.portalpad.app.ui.theme.AbPrimaryBright,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.clickable { onDone() },
        ) {
            androidx.compose.material3.Text(
                "Done",
                color = Color.Black,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pill(
    modifier: Modifier = Modifier,
    color: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    wiggling: Boolean = false,
    pickedUp: Boolean = false,
    phaseKey: String = "",
    content: @Composable () -> Unit,
) {
    // iOS-style jiggle while reordering; a per-pill phase offset desyncs them so
    // they don't wiggle in lockstep. A picked-up pill lifts (scales up) instead.
    val wigglePhase = remember(phaseKey) { (phaseKey.hashCode() % 360).toFloat() }
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "navwiggle")
    val wiggleAngle by infinite.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = 180,
                easing = androidx.compose.animation.core.LinearEasing,
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            initialStartOffset = androidx.compose.animation.core.StartOffset((wigglePhase * 2).toInt()),
        ),
        label = "navwiggleAngle",
    )
    val pickupScale by animateFloatAsState(if (pickedUp) 1.18f else 1f, label = "navpickup")
    Box(
        modifier
            .height(48.dp)
            .then(
                if (wiggling) Modifier.graphicsLayer {
                    rotationZ = if (pickedUp) 0f else wiggleAngle
                    scaleX = pickupScale
                    scaleY = pickupScale
                } else Modifier,
            )
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .combinedClickable(
                onClick = {
                    com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                    onClick()
                },
                onLongClick = {
                    com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
                    onLongClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}
