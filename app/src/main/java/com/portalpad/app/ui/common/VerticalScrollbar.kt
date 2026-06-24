package com.portalpad.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A lightweight visual scrollbar for a [androidx.compose.foundation.lazy.LazyColumn].
 *
 * Compose has no built-in scrollbar, and our app lists/drawers gave no visual
 * cue that they scroll. This draws a thin thumb on the right edge whose size and
 * position reflect how far through the list the user is. It's indicator-only
 * (not draggable) — enough to show "there's more below".
 *
 * Place inside a Box that also contains the LazyColumn, aligned to CenterEnd.
 * Hidden automatically when everything fits (nothing to scroll).
 */
@Composable
fun VerticalScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    thumbColor: Color = Color(0x66FFFFFF),
    trackColor: Color = Color(0x14FFFFFF),
) {
    // Approximate scroll progress from item index + offset. Item-based (not
    // pixel-perfect, since LazyColumn doesn't expose total pixel height) but
    // smooth enough for an indicator.
    val info by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val total = layout.totalItemsCount
            val visible = layout.visibleItemsInfo
            if (total <= 0 || visible.isEmpty()) {
                ScrollMetrics(visibleFraction = 1f, scrolledFraction = 0f, scrollable = false)
            } else {
                val visibleCount = visible.size.toFloat()
                val visibleFraction = (visibleCount / total).coerceIn(0.08f, 1f)
                val first = listState.firstVisibleItemIndex.toFloat()
                val maxFirst = (total - visibleCount).coerceAtLeast(1f)
                val scrolledFraction = (first / maxFirst).coerceIn(0f, 1f)
                ScrollMetrics(
                    visibleFraction = visibleFraction,
                    scrolledFraction = scrolledFraction,
                    scrollable = total > visible.size,
                )
            }
        }
    }

    if (!info.scrollable) return

    Box(modifier.background(trackColor, RoundedCornerShape(2.dp))) {
        // Thumb: height = visibleFraction of track; offset = scrolledFraction of
        // the remaining track. Implemented with weights via a nested Box layout.
        androidx.compose.foundation.layout.Column(Modifier.fillMaxHeight()) {
            val above = info.scrolledFraction * (1f - info.visibleFraction)
            val below = (1f - info.visibleFraction) - above
            if (above > 0f) Box(Modifier.weight(above.coerceAtLeast(0.0001f)))
            Box(
                Modifier
                    .weight(info.visibleFraction)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(2.dp))
                    .background(thumbColor),
            )
            if (below > 0f) Box(Modifier.weight(below.coerceAtLeast(0.0001f)))
        }
    }
}

private data class ScrollMetrics(
    val visibleFraction: Float,
    val scrolledFraction: Float,
    val scrollable: Boolean,
)
