package com.portalpad.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalpad.app.ui.theme.AbDanger
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbSurface
import com.portalpad.app.ui.theme.AbWarning
import kotlinx.coroutines.delay

/**
 * Centered "External Display Disconnected" card shown over the trackpad / air-mouse
 * / remote interface while the display is gone. A thin bar depletes left over the
 * grace window. If the display reconnects within the window (e.g. a screen-off
 * flap), the card flips to an amber "reconnected" state briefly, then dismisses —
 * the user is NOT returned to the main screen. A real unplug leaves the display
 * null; the auto-finish watcher (onCreate) returns to MainActivity as the bar
 * empties. This composable is purely the visual; it doesn't call finish().
 */
@Composable
internal fun DisconnectBanner(externalDisplayId: Int?) {
    // Grace window — keep in sync with the auto-finish watcher's goneGraceMs.
    val graceMs = 10000
    // Only engage after we've actually seen a display, so the initial null at
    // launch doesn't flash the banner.
    var sawDisplay by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(externalDisplayId) {
        if (externalDisplayId != null) sawDisplay = true
    }
    val disconnected = sawDisplay && externalDisplayId == null

    // Reconnect flash: when we were disconnected and the display returns, show a
    // brief amber "reconnected" state before dismissing.
    var showReconnected by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var visible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val progress = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }

    androidx.compose.runtime.LaunchedEffect(disconnected) {
        if (disconnected) {
            showReconnected = false
            visible = true
            progress.snapTo(1f)
            // Deplete to 0 over the grace window.
            progress.animateTo(
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = graceMs,
                    easing = androidx.compose.animation.core.LinearEasing,
                ),
            )
            // Bar emptied and we're still disconnected → the watcher is returning
            // to main; keep the card up through the transition.
        } else if (visible) {
            // Reconnected within the window — flash amber, then dismiss.
            showReconnected = true
            kotlinx.coroutines.delay(850)
            visible = false
            showReconnected = false
        }
    }

    if (!visible) return

    val danger = com.portalpad.app.ui.theme.AbDanger
    val warning = com.portalpad.app.ui.theme.AbWarning
    val accent = if (showReconnected) warning else danger

    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFF080510).copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier
                .fillMaxWidth(0.78f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .background(com.portalpad.app.ui.theme.AbSurface)
                .border(
                    1.dp,
                    accent.copy(alpha = 0.45f),
                    androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.foundation.layout.Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp, bottom = 16.dp, start = 20.dp, end = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = if (showReconnected) {
                        androidx.compose.material.icons.Icons.Default.CheckCircle
                    } else {
                        androidx.compose.material.icons.Icons.Default.Close
                    },
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.Text(
                    if (showReconnected) "Display Reconnected" else "External Display Disconnected",
                    color = com.portalpad.app.ui.theme.AbOnSurface,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.Text(
                    if (showReconnected) "Staying on this screen" else "Returning to main screen…",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            // Depleting countdown bar (full → empty, shrinking left). Frozen on the
            // reconnect flash.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(accent.copy(alpha = 0.18f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(if (showReconnected) 1f else progress.value)
                        .background(accent),
                )
            }
        }
    }
}
