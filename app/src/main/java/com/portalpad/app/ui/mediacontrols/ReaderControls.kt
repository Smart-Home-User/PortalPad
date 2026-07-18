package com.portalpad.app.ui.mediacontrols

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalpad.app.PortalPadApp
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimary

/** Selector labels, and the Previous/Next input each method actually sends.
 *  Index 1 (Left/Right) is the default — confirmed working in Kindle and Google
 *  Books. Label for index 0 is kept short so the three segments don't wrap on
 *  narrow screens. */
private val FLIP_LABELS = listOf("Page \u2191\u2193", "Left/Right", "Scroll")
private val FLIP_SUB = listOf(
    "Page Up" to "Page Down",
    "Left" to "Right",
    "Scroll up" to "Scroll down",
)

/**
 * The Reader input surface: a flip-method selector, two big Previous/Next page
 * zones, a Find-in-page launcher, a zoom row, and a transient brightness slider.
 * Flip/zoom/find call focus-following injection (keys, scroll, pinch, Ctrl+F),
 * so they work whether the reader app is fullscreen or in a freeform window.
 * Purely presentational — all state is passed in.
 */
@Composable
fun ReaderControls(
    flipMethod: Int,
    onSelectFlip: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFind: () -> Unit,
    onExitZoom: () -> Unit,
    brightnessAvailable: Boolean,
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    onFixMirror: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val method = flipMethod.coerceIn(0, 2)
    Column(modifier) {
        Text(
            "Flip pages with",
            color = AbOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF181226))
                .border(1.dp, Color(0xFF2E2743), RoundedCornerShape(12.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FLIP_LABELS.forEachIndexed { i, label ->
                val on = i == method
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (on) AbPrimary else Color.Transparent)
                        .clickable {
                            PortalPadApp.instance.injector.buzz(longPress = false)
                            onSelectFlip(i)
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (on) Color.White else AbOnSurfaceMuted,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Text(
            "Left/Right works in most readers (default)",
            color = AbOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp, top = 5.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlipZone(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.KeyboardArrowLeft,
                title = "Previous",
                sub = FLIP_SUB[method].first,
                onClick = onPrev,
            )
            FlipZone(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.KeyboardArrowRight,
                title = "Next",
                sub = FLIP_SUB[method].second,
                onClick = onNext,
            )
        }
        Spacer(Modifier.height(10.dp))
        // Find + Zoom share one row to save vertical space for the big Prev/Next
        // zones; Exit Zoom is a full-width button below.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Find in page — sends Ctrl+F to the reader, then opens the keyboard relay.
            Row(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF201A38))
                    .border(1.dp, Color(0xFF4A3E7A), RoundedCornerShape(12.dp))
                    .clickable {
                        PortalPadApp.instance.injector.buzz(longPress = false)
                        onFind()
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = "Find in page", tint = Color(0xFFC9A9FF), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Find in page", color = AbOnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            }
            // Zoom −/+ (pinch step on the reader).
            Row(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF181226))
                    .border(1.dp, Color(0xFF2E2743), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Zoom", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                ZoomButton(Icons.Filled.Remove, "Zoom out", onZoomOut)
                Spacer(Modifier.width(12.dp))
                ZoomButton(Icons.Filled.Add, "Zoom in", onZoomIn)
            }
        }
        Spacer(Modifier.height(10.dp))
        // Exit Zoom — a center tap on the reader window. Kindle's zoom overlay
        // swallows page-flip input until it's dismissed, and it only dismisses on
        // a screen TAP (not a d-pad key), so this reuses the scroll-mode center
        // tap (now window-aware, so it lands on the app even in a freeform window).
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF201A38))
                .border(1.dp, Color(0xFF4A3E7A), RoundedCornerShape(12.dp))
                .clickable {
                    PortalPadApp.instance.injector.buzz(longPress = false)
                    onExitZoom()
                }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Exit Zoom", color = AbOnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text("taps screen center", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(10.dp))
        // Brightness — reuses the GL color pass transiently (not persisted).
        // Only active off-mirror with the GPU pipeline on; greyed otherwise.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF181226))
                .border(1.dp, Color(0xFF2E2743), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Brightness", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                if (brightnessAvailable) {
                    Text(
                        "%d%%".format(Math.round(brightness * 100f)),
                        color = Color(0xFFC9A9FF),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (brightnessAvailable) {
                Slider(
                    value = brightness,
                    onValueChange = onBrightnessChange,
                    valueRange = 0.5f..1.5f,
                    colors = SliderDefaults.colors(
                        thumbColor = AbPrimary,
                        activeTrackColor = AbPrimary,
                        inactiveTrackColor = Color(0xFF2E2743),
                    ),
                )
                Text(
                    "Temporary — resets on exit or reconnect.",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            PortalPadApp.instance.injector.buzz(longPress = false)
                            onFixMirror()
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "System mirror is on — tap to turn off for brightness",
                        color = Color(0xFFE6B800),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun FlipZone(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF14101F))
            .border(1.dp, Color(0xFF2E2743), RoundedCornerShape(14.dp))
            .clickable {
                PortalPadApp.instance.injector.buzz(longPress = false)
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = title, tint = Color(0xFF8B82E6), modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(6.dp))
        Text(title, color = AbOnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(3.dp))
        Text(sub, color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ZoomButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0xFF241E38))
            .clickable {
                PortalPadApp.instance.injector.buzz(longPress = false)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color(0xFFC9C4E4), modifier = Modifier.size(20.dp))
    }
}
