package com.portalpad.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalpad.app.data.AppEntry
import com.portalpad.app.ui.dock.AppIcon
import com.portalpad.app.ui.theme.AbAccent
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted

/**
 * Shows an assigned action (button action, gesture, program key, auto-launch…)
 * with enough context to tell WHICH app it's from. The old displays showed just
 * an activity's name (e.g. "DDATransactions") with no app icon or app name, so
 * you couldn't tell what app the activity belonged to.
 *
 * Layout:
 *   • Activity entry  → app icon (left) + app label (top) + activity name (below).
 *   • Plain app       → app icon (left) + app label.
 *   • Phone shortcut  → app icon (left) + the shortcut's label.
 *   • Nothing set     → [fallback] text only (no icon).
 *
 * @param entry the assigned action, or null when nothing is assigned.
 * @param fallback text to show when [entry] is null (e.g. "Not set").
 */
@Composable
fun AssignedActionLabel(
    entry: AppEntry?,
    fallback: String,
    modifier: Modifier = Modifier,
    iconSizeDp: Int = 32,
) {
    if (entry == null) {
        Text(
            fallback,
            color = AbAccent,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = modifier,
        )
        return
    }

    // Internal "Widget Overlay" sentinel: its magic package isn't installed, so
    // the PackageManager lookups below would fall back to the raw package name
    // ("portalpad.internal.widget_overlay", truncated in tight layouts) and a
    // missing icon. Render the stored label with a widget glyph instead.
    if (entry.isWidgetOverlay) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                Icons.Default.Widgets,
                contentDescription = null,
                tint = AbAccent,
                modifier = Modifier.size(iconSizeDp.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                entry.label,
                color = AbAccent,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }

    val ctx = LocalContext.current
    // Resolve the app's own label from its package (independent of the activity
    // label, which may be "App · Activity" or just the activity class name).
    val appLabel = remember(entry.packageName) {
        runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(entry.packageName, 0)).toString()
        }.getOrDefault(entry.packageName)
    }
    // For an activity, the stored label is typically "App · Activity"; strip the
    // app prefix so the second line is just the activity name.
    val activityName = remember(entry.label, appLabel) {
        entry.label
            .substringAfter(" · ", entry.label)
            .substringAfter(" — ", entry.label.substringAfter(" · ", entry.label))
            .ifBlank { entry.label }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = entry.packageName, sizeDp = iconSizeDp)
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                appLabel,
                color = AbOnSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.isActivity) {
                Text(
                    activityName,
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (entry.isShortcut && entry.label != appLabel) {
                Text(
                    entry.label,
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
