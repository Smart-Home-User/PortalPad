package com.portalpad.app.ui.trackpad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceDim
import com.portalpad.app.ui.theme.AbPrimary
import com.portalpad.app.ui.theme.AbSurfaceElevated

/**
 * Two pill buttons — Left click and Right click — sitting between the trackpad
 * surface and the system-nav bar. Both are equal-width, same-color momentary
 * buttons. They flash brighter while pressed (no persistent highlight), so the
 * user sees they're tap actions, not toggles.
 */
@Composable
fun TrackpadClickButtons(
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    onArrange: () -> Unit,
    onArrangeLongPress: () -> Unit,
    showArrange: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ClickPill(
            label = "Left click",
            onClick = onLeftClick,
            modifier = Modifier.weight(1f),
        )
        ClickPill(
            label = "Right click",
            onClick = onRightClick,
            modifier = Modifier.weight(1f),
        )
        // Narrow icon button (edge-strip width), only when desktop windows is on
        // (nothing to arrange otherwise). When hidden, the two click pills reflow
        // to their original full width. Tap = arrange evenly, long-press = arrange
        // in order (ordering screen on the phone).
        if (showArrange) {
            ArrangePill(
                onTap = onArrange,
                onLongPress = onArrangeLongPress,
                modifier = Modifier.width(44.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArrangePill(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val bgColor = if (pressed) AbPrimary else AbSurfaceElevated

    Box(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bgColor)
            .border(1.dp, AbOnSurfaceDim.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = rememberRipple(),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onTap()
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "\u2637", // ☷ — arrange evenly (matches the top bar glyph)
            color = AbOnSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
        )
    }
}

@Composable
private fun ClickPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Track press state ourselves so we can brighten while held — gives tactile
    // feedback without making the button look like a persistent toggle.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val bgColor = if (pressed) AbPrimary else AbSurfaceElevated

    Box(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bgColor)
            .border(1.dp, AbOnSurfaceDim.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(),
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = AbOnSurface,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}
