package com.portalpad.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.portalpad.app.R
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbSurfaceElevated

/** One destination chip: icon + label, optional second line, and a click. */
data class OpenInDest(
    val iconRes: Int,
    val label: String,
    val sublabel: String? = null,
    val onClick: () -> Unit,
)

/** Play Store chip with the standard icon + "Play Store" label. */
fun playStoreDest(sublabel: String? = null, onClick: () -> Unit) =
    OpenInDest(R.drawable.ic_playstore, "Play Store", sublabel, onClick)

/** GitHub chip with the standard icon + "GitHub" label. */
fun gitHubDest(sublabel: String? = null, onClick: () -> Unit) =
    OpenInDest(R.drawable.ic_github, "GitHub", sublabel, onClick)

/** F-Droid chip with the standard icon + "F-Droid" label. */
fun fDroidDest(sublabel: String? = null, onClick: () -> Unit) =
    OpenInDest(R.drawable.ic_fdroid, "F-Droid", sublabel, onClick)

/**
 * Compact "Open in… [chip] [chip]" row. A plain "Open in…" label followed by
 * one or more small icon-buttons. Chips with a [OpenInDest.sublabel] render a
 * little taller with the sublabel on a second line (e.g. "Play Store" /
 * "(Standard)"). Used in the Resources page and the Shizuku / Extinguish
 * "not installed" dialogs so they all look the same.
 */
@Composable
fun OpenInRow(
    destinations: List<OpenInDest>,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    Row(
        modifier = if (centered) modifier.fillMaxWidth() else modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
    ) {
        Text(
            "Open in\u2026",
            color = AbOnSurfaceMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(10.dp))
        destinations.forEachIndexed { i, dest ->
            if (i > 0) Spacer(Modifier.width(8.dp))
            DestinationChip(dest)
        }
    }
}

@Composable
private fun DestinationChip(dest: OpenInDest) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AbSurfaceElevated)
            .clickable { dest.onClick() }
            .padding(horizontal = 12.dp, vertical = if (dest.sublabel != null) 8.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(dest.iconRes),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(dest.label, color = AbOnSurface, style = MaterialTheme.typography.bodyMedium)
            if (dest.sublabel != null) {
                Text(
                    dest.sublabel,
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
