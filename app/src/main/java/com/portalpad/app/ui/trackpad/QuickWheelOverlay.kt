package com.portalpad.app.ui.trackpad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.portalpad.app.data.AppEntry
import com.portalpad.app.data.QUICK_WHEEL_SLOTS
import com.portalpad.app.ui.dock.AppIcon
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimary
import com.portalpad.app.ui.theme.AbSurfaceElevated
import com.portalpad.app.ui.theme.AbTeal
import com.portalpad.app.ui.theme.AbWarning
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The Quick Wheel: a phone-side radial quick-launcher that overlays the current
 * interface. The center is a permanent "All apps" drawer button; the ring holds
 * [QUICK_WHEEL_SLOTS] customizable slots (icons only).
 *
 * Gestures per ring slot:
 *  - filled: first tap latches its name in the always-visible label strip (no
 *    launch); a second tap on the same slot launches it. Tapping a different slot
 *    moves the latch. Long-press opens the picker to reconfigure/clear it.
 *  - empty ("+"): tap opens the picker to assign it.
 * Center: tap opens the full app drawer. Tap outside the wheel: dismiss.
 *
 * The whole cluster is bottom-anchored so the label strip lands in the gap just
 * above the bottom bar (see [WheelBottomLift]).
 */
private val WheelBottomLift = 84.dp   // lift above the safe-area bottom; tunable

@Composable
fun QuickWheelOverlay(
    slots: List<AppEntry?>,
    onLaunchSlot: (AppEntry) -> Unit,
    onAssignSlot: (Int) -> Unit,
    onOpenDrawer: () -> Unit,
    onDismiss: () -> Unit,
) {
    var preview by remember { mutableStateOf<Int?>(null) }

    val ringRadius = 116f
    val slotSize = 58.dp
    val centerSize = 112.dp
    val wheelSize = 300.dp

    // Soft violet-tinted radial depth for the backdrop, and a livelier violet
    // gradient for the center button.
    val backdropBrush = Brush.radialGradient(
        listOf(Color(0xFF1D1536), Color(0xFF140D26), Color(0xFF0F0A1A)),
    )
    val centerBrush = Brush.radialGradient(
        listOf(Color(0xFFA96BFF), Color(0xFF7A45C8), Color(0xFF5A32A0)),
    )

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            // Content fills the screen (to bottom-anchor the wheel), so the
            // platform "outside tap" can't fire — we catch scrim taps manually.
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                .navigationBarsPadding()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .padding(bottom = WheelBottomLift)
                    // Consume taps on the cluster (gaps/strip) so they don't dismiss.
                    .pointerInput(Unit) { detectTapGestures { } },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            Box(
                Modifier
                    .size(wheelSize)
                    // Swallow taps inside the wheel so a near-miss never dismisses;
                    // only taps on the scrim outside the wheel close it.
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                // Ring backdrop — soft violet-tinted radial depth.
                Box(
                    Modifier
                        .size(wheelSize - 24.dp)
                        .clip(CircleShape)
                        .background(backdropBrush)
                        .border(0.5.dp, AbPrimary.copy(alpha = 0.28f), CircleShape),
                )
                // Faint dashed "dial" ring through the slot positions.
                Box(
                    Modifier
                        .size((ringRadius * 2).dp)
                        .drawBehind {
                            drawCircle(
                                color = AbPrimary.copy(alpha = 0.22f),
                                radius = size.minDimension / 2f,
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 11f)),
                                ),
                            )
                        },
                )

                // Center: permanent app-drawer button — gradient fill + soft glow.
                Box(
                    Modifier
                        .size(centerSize)
                        .shadow(16.dp, CircleShape, ambientColor = AbPrimary, spotColor = AbPrimary)
                        .clip(CircleShape)
                        .background(centerBrush)
                        .border(1.dp, Color(0x73E5D8FF), CircleShape)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
                                onOpenDrawer()
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Apps,
                            contentDescription = "All apps",
                            tint = AbOnSurface,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "All apps",
                            color = AbOnSurface,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                // Ring slots.
                for (i in 0 until QUICK_WHEEL_SLOTS) {
                    val slot = slots.getOrNull(i)
                    val angle = Math.toRadians((i * 45.0) - 90.0)
                    val dx = (ringRadius * cos(angle)).toFloat()
                    val dy = (ringRadius * sin(angle)).toFloat()
                    SlotView(
                        slot = slot,
                        highlighted = preview == i && slot != null,
                        size = slotSize,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = dx.dp, y = dy.dp)
                            .pointerInput(slot, i) {
                                detectTapGestures(
                                    onTap = {
                                        when {
                                            slot == null -> {
                                                com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                                                onAssignSlot(i)
                                            }
                                            preview == i -> {                    // 2nd tap → launch
                                                com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
                                                onLaunchSlot(slot)
                                            }
                                            else -> {                            // 1st tap → latch name
                                                com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                                                preview = i
                                            }
                                        }
                                    },
                                    onLongPress = {
                                        com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
                                        onAssignSlot(i)
                                    },
                                )
                            },
                    )
                }
            }

            LabelStrip(preview?.let { slots.getOrNull(it) })
            }
        }
    }
}

@Composable
private fun SlotView(
    slot: AppEntry?,
    highlighted: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (highlighted) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "slotScale",
    )
    // Ring color encodes launch destination: violet = app, teal = activity (both
    // land on the glasses), amber = shortcut (lands on the PHONE).
    val accent = slot?.let { accentFor(it) } ?: AbOnSurfaceMuted
    val ringColor = when {
        slot == null -> AbOnSurfaceMuted.copy(alpha = 0.35f)
        highlighted -> accent
        else -> accent.copy(alpha = 0.7f)
    }
    val bg = when {
        highlighted -> accent.copy(alpha = 0.18f)
        slot != null -> Color(0xFF221A38)
        else -> Color.Transparent
    }
    // Outer box is unclipped so the corner badge can overhang the ring.
    Box(
        modifier
            .size(size)
            .scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (highlighted)
                        Modifier.shadow(16.dp, CircleShape, ambientColor = accent, spotColor = accent)
                    else Modifier,
                )
                .clip(CircleShape)
                .background(bg)
                .border(if (slot == null) 1.5.dp else 2.dp, ringColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (slot != null) {
                // Icon sits on a subtle elevated tile for depth. No clashing color —
                // real launcher icons already carry their own.
                Box(
                    Modifier
                        .size(size * 0.62f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AbSurfaceElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(packageName = slot.packageName, sizeDp = (size.value * 0.5f).toInt())
                }
            } else {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add app",
                    tint = AbOnSurfaceMuted,
                    modifier = Modifier.size(size * 0.4f),
                )
            }
        }
        // Phone badge — a shortcut launches on the phone, not the glasses.
        if (slot?.isShortcut == true) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-3).dp, y = 3.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(AbWarning)
                    .border(1.5.dp, AbBackground, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Smartphone,
                    contentDescription = "Launches on phone",
                    tint = Color(0xFF3A2A05),
                    modifier = Modifier.size(11.dp),
                )
            }
        } else if (slot?.isActivity == true) {
            // Layers badge — a specific activity (a screen within an app).
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-3).dp, y = 3.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(AbTeal)
                    .border(1.5.dp, AbBackground, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Layers,
                    contentDescription = "App activity",
                    tint = Color(0xFF05241B),
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

/** A slot's launch destination, encoded by ring color. */
private fun accentFor(entry: AppEntry): Color = when {
    entry.isShortcut -> AbWarning   // launches on the phone
    entry.isActivity -> AbTeal      // a specific activity (deeper entry point)
    else -> AbPrimary               // the whole app
}

@Composable
private fun LabelStrip(slot: AppEntry?) {
    val accent = slot?.let { accentFor(it) } ?: AbPrimary
    Box(
        Modifier
            .widthIn(min = 210.dp)
            // Fixed floor so the idle (1-line) and armed (2-line) states occupy
            // the same height — the bottom-anchored cluster must not jump when a
            // slot is latched.
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AbSurfaceElevated)
            .border(
                0.5.dp,
                if (slot != null) accent.copy(alpha = 0.4f) else AbOnSurfaceMuted.copy(alpha = 0.25f),
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (slot != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(packageName = slot.packageName, sizeDp = 24)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        slot.label,
                        color = AbOnSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (slot.isShortcut) {
                        // Spell out the consequence: this one opens on the phone.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Smartphone,
                                contentDescription = null,
                                tint = AbWarning,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "opens on phone · tap again to launch",
                                color = AbWarning,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    } else {
                        Text(
                            if (slot.isActivity) "activity · tap again to launch" else "tap again to launch",
                            color = AbOnSurfaceMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                // Accent dot, tinted to the slot type.
                Box(
                    Modifier
                        .size(7.dp)
                        .shadow(6.dp, CircleShape, ambientColor = accent, spotColor = accent)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        } else {
            Text(
                "Tap a slot to preview · tap outside to close",
                color = AbOnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
