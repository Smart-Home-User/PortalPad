package com.portalpad.app.ui.trackpad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalpad.app.ui.theme.AbAccent
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimary
import com.portalpad.app.ui.theme.AbSurface
import com.portalpad.app.ui.theme.AbSurfaceElevated

@Composable
fun TrackpadTopBar(
    mode: TrackpadViewMode,
    onSelectMode: (TrackpadViewMode) -> Unit,
    onScreenshotClick: () -> Unit,
    onFlashlightClick: () -> Unit,
    onSettingsClick: () -> Unit,
    flashlightOn: Boolean,
    isRecording: Boolean = false,
    recordingLabel: String = "",
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(AbSurface, RoundedCornerShape(28.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(AbSurfaceElevated),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentButton(
                label = "Air Mouse",
                selected = mode == TrackpadViewMode.AIR_MOUSE,
                onClick = { onSelectMode(TrackpadViewMode.AIR_MOUSE) },
                modifier = Modifier.weight(1f),
                compact = isRecording,
            )
            SegmentButton(
                label = "Trackpad",
                selected = mode == TrackpadViewMode.TRACKPAD,
                onClick = { onSelectMode(TrackpadViewMode.TRACKPAD) },
                modifier = Modifier.weight(1f),
                compact = isRecording,
            )
            SegmentButton(
                label = "Remote",
                selected = mode == TrackpadViewMode.REMOTE,
                onClick = { onSelectMode(TrackpadViewMode.REMOTE) },
                modifier = Modifier.weight(1f),
                compact = isRecording,
            )
        }

        // Flashlight — quick toggle for AR-glasses users in dark rooms. Icon
        // and tint flip with state so the on/off state is unambiguous at a
        // glance. Requires CAMERA permission (requested on first tap by the
        // caller; if denied, an explainer briefly appears).
        IconBubble(onClick = onFlashlightClick) {
            Icon(
                if (flashlightOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                contentDescription = if (flashlightOn) "Flashlight on" else "Flashlight off",
                tint = if (flashlightOn) AbAccent else AbOnSurfaceMuted,
            )
        }

        // Screenshot / record. Tap = screenshot of the external display.
        // Long-press = start recording. While recording, the icon becomes a red
        // pulsing stop-square, a plain tap stops it (finalizes the MP4), and an
        // elapsed-time readout shows beside it (there's no system notification
        // for a shell screenrecord, so this is the only "it's live" cue).
        if (isRecording) {
            Text(
                recordingLabel,
                color = Color(0xFFE5484D),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(2.dp))
        }
        IconBubble(
            onClick = { if (isRecording) onStopRecording() else onScreenshotClick() },
            onLongClick = if (isRecording) null else onStartRecording,
        ) {
            if (isRecording) {
                val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "rec")
                val pulse = infinite.animateFloat(
                    initialValue = 0.45f,
                    targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        androidx.compose.animation.core.tween(700),
                        androidx.compose.animation.core.RepeatMode.Reverse,
                    ),
                    label = "recpulse",
                ).value
                Box(
                    Modifier
                        .size(15.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFFE5484D).copy(alpha = pulse)),
                )
            } else {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(
                        com.portalpad.app.R.drawable.ic_screenshot,
                    ),
                    contentDescription = "Screenshot external display (long-press to record)",
                    tint = AbOnSurfaceMuted,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        IconBubble(onClick = onSettingsClick) {
            Icon(Icons.Default.Settings, contentDescription = "Open settings", tint = AbOnSurfaceMuted)
        }
    }
}

/** The three modes selectable from the trackpad interface's top tab row. */
enum class TrackpadViewMode { AIR_MOUSE, TRACKPAD, REMOTE }

@Composable
private fun SegmentButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val bg = if (selected) AbPrimary else Color.Transparent
    val fg = if (selected) AbOnSurface else AbOnSurfaceMuted
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .clickable {
                com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                onClick()
            }
            .padding(vertical = 12.dp, horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            // When recording, the timer beside the capture icon squeezes the tab
            // row — shrink the label and keep it on one line so "Air Mouse" doesn't
            // wrap or hit the button edge.
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconBubble(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(AbSurfaceElevated)
            .combinedClickable(
                onClick = {
                    com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                    onClick()
                },
                onLongClick = if (onLongClick != null) {
                    {
                        com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
                        onLongClick()
                    }
                } else null,
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}
