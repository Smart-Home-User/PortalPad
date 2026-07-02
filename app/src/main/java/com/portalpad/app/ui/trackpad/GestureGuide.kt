package com.portalpad.app.ui.trackpad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.portalpad.app.ui.theme.AbAccent
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbSurface

/**
 * A small, subtle "Guide" pill rendered on the trackpad/air-mouse surface (as a
 * sibling overlay, so its taps are consumed here and never reach the gesture
 * surface beneath it) plus the gesture-reference dialog it opens.
 *
 * Content is mode-aware: Air Mouse shares the trackpad's touch gestures and
 * ADDS phone-motion pointing, so its guide shows the motion line on top of the
 * same gesture list.
 */

/** One gesture row: bold name + plain description. */
private data class GestureRow(val name: String, val desc: String)

@Composable
fun GuidePill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(AbSurface.copy(alpha = 0.55f))
            .border(
                1.dp,
                AbOnSurfaceMuted.copy(alpha = 0.35f),
                androidx.compose.foundation.shape.RoundedCornerShape(50),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Guide",
            color = AbOnSurfaceMuted,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun GestureGuideDialog(
    isAirMouse: Boolean,
    onDismiss: () -> Unit,
) {
    val edgeScrollEnabled = com.portalpad.app.PortalPadApp.instance.prefs.bool(
        com.portalpad.app.data.PreferencesRepository.Keys.EDGE_SCROLL,
        default = true,
    ).collectAsState(initial = true).value
    val edgeScrollSide = com.portalpad.app.PortalPadApp.instance.prefs.string(
        com.portalpad.app.data.PreferencesRepository.Keys.EDGE_SCROLL_SIDE,
        default = "right",
    ).collectAsState(initial = "right").value
    val prefs = com.portalpad.app.PortalPadApp.instance.prefs
    val gUp = prefs.gestureSwipeUp.collectAsState(initial = com.portalpad.app.data.GestureAction.NOTIF_CLOSE).value
    val gDown = prefs.gestureSwipeDown.collectAsState(initial = com.portalpad.app.data.GestureAction.NOTIF_OPEN).value
    val gLeft = prefs.gestureSwipeLeft.collectAsState(initial = com.portalpad.app.data.GestureAction.HOME).value
    val gRight = prefs.gestureSwipeRight.collectAsState(initial = com.portalpad.app.data.GestureAction.BACK).value
    val gUpApp = prefs.gestureAppUp.collectAsState(initial = null).value
    val gDownApp = prefs.gestureAppDown.collectAsState(initial = null).value
    val gLeftApp = prefs.gestureAppLeft.collectAsState(initial = null).value
    val gRightApp = prefs.gestureAppRight.collectAsState(initial = null).value
    fun gLabel(a: com.portalpad.app.data.GestureAction, app: com.portalpad.app.data.AppEntry?): String =
        if (a == com.portalpad.app.data.GestureAction.LAUNCH_APP && !app?.label.isNullOrBlank()) app!!.label else a.short
    Dialog(onDismissRequest = onDismiss) {
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .background(AbSurface)
                .border(
                    1.dp,
                    AbAccent.copy(alpha = 0.4f),
                    androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                )
                .padding(20.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (isAirMouse) "Air Mouse Gestures" else "Trackpad Gestures",
                color = AbAccent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            if (isAirMouse) {
                Text(
                    "Air Mouse uses all the trackpad gestures below, plus:",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                GestureLine(GestureRow("Move the phone", "point the cursor in the air"))
            }

            SectionLabel("One finger")
            GestureLine(GestureRow("Slide", "move the cursor"))
            GestureLine(GestureRow("Tap", "left click"))
            GestureLine(GestureRow("Touch & hold", "select the word or item under the cursor"))
            GestureLine(GestureRow("Touch & hold, then move", "drag a window, or select text; grab a window edge to resize"))
            if (edgeScrollEnabled) {
                val sideLabel = if (edgeScrollSide == "left") "Left" else "Right"
                GestureLine(GestureRow("$sideLabel edge strip", "drag one finger to scroll"))
            }

            SectionLabel("Two fingers")
            GestureLine(GestureRow("Slide", "scroll"))
            GestureLine(GestureRow("Tap", "right click"))

            SectionLabel("Three fingers")
            // Reflect the user-configured swipe actions. Hidden if set to None.
            if (gRight != com.portalpad.app.data.GestureAction.NONE)
                GestureLine(GestureRow("Swipe right", gLabel(gRight, gRightApp)), shortened = true)
            if (gLeft != com.portalpad.app.data.GestureAction.NONE)
                GestureLine(GestureRow("Swipe left", gLabel(gLeft, gLeftApp)), shortened = true)
            if (gDown != com.portalpad.app.data.GestureAction.NONE)
                GestureLine(GestureRow("Swipe down", gLabel(gDown, gDownApp)), shortened = true)
            if (gUp != com.portalpad.app.data.GestureAction.NONE)
                GestureLine(GestureRow("Swipe up", gLabel(gUp, gUpApp)), shortened = true)
            Text(
                "The notifications panel shows on the external display — navigate it with the trackpad cursor.",
                color = AbOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                "Tap anywhere outside to close.",
                color = com.portalpad.app.ui.theme.AbDanger,
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = AbAccent,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun GestureLine(row: GestureRow, shortened: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "•",
                color = AbOnSurfaceMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                row.name,
                color = AbOnSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(140.dp),
            )
            if (shortened) {
                // Three-finger rows show a user-assigned action that can be long;
                // keep them to one line with an ellipsis.
                Text(
                    row.desc,
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                // One/two-finger + air-mouse rows show their full description,
                // wrapping to a second line rather than truncating.
                Text(
                    row.desc,
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Subtle hairline so each gesture reads as its own unit, especially now
        // that longer descriptions wrap to uneven heights.
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp),
            thickness = 1.dp,
            color = AbOnSurfaceMuted.copy(alpha = 0.12f),
        )
    }
}

/** Gesture map for the Remote's Gesture input mode (the full-overlay surface). */
@Composable
fun RemoteGestureGuideDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .background(AbSurface)
                .border(
                    1.dp,
                    AbAccent.copy(alpha = 0.4f),
                    androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                )
                .padding(20.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Remote Gestures",
                color = AbAccent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "The whole pad is a swipe surface \u2014 drive it without looking.",
                color = AbOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )

            SectionLabel("One finger")
            GestureLine(GestureRow("Swipe", "up / down / left / right = D-pad arrows"))
            GestureLine(GestureRow("Swipe + hold", "repeat that arrow while held"))
            GestureLine(GestureRow("Tap", "OK / select"))
            GestureLine(GestureRow("Tap + hold", "Back"))

            SectionLabel("Two fingers")
            GestureLine(GestureRow("Swipe up", "Home"))
            GestureLine(GestureRow("Tap", "play / pause"))
            GestureLine(GestureRow("Tap + hold", "Stop"))
            GestureLine(GestureRow("Swipe left / right", "rewind / fast-forward"))
            GestureLine(GestureRow("Swipe + hold left / right", "continuous rewind / fast-forward"))

            Text(
                "Volume stays on the slider at the right.",
                color = AbOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Tap anywhere outside to close.",
                color = com.portalpad.app.ui.theme.AbDanger,
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}
