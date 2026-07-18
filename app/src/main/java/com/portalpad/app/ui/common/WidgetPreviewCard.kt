package com.portalpad.app.ui.common

import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text

/**
 * Nova-launcher-style widget row, shared by the on-display picker, the phone
 * widget search, and the Workspace widget list so all three speak the same
 * visual language: a large CENTERED preview at its NATURAL aspect ratio
 * (capped, so a 5×1 bar renders wide-and-short and a calendar big-and-square),
 * the widget name centered beneath, a subtitle (dimensions or per-resolution
 * status) centered under that, and an optional trailing slot (e.g. Remove).
 * Falls back to the app icon when the provider ships no preview image.
 * Colors are caller-supplied: adaptive on the display, theme on the phone.
 */
@Composable
fun WidgetPreviewCard(
    info: AppWidgetProviderInfo?,
    name: String,
    /** Greyed, normal-weight text right after the name (e.g. "(1×1 cells)") —
     *  visually separate from the bold title. */
    nameSuffix: String? = null,
    subtitle: String,
    nameColor: Color,
    subtitleColor: Color,
    subtitleMaxLines: Int = 2,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled) { onClick() }
                else Modifier,
            )
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AndroidView(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .heightIn(max = 180.dp),
            factory = { ctx ->
                android.widget.ImageView(ctx).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                }
            },
            update = { iv ->
                val preview = info?.let {
                    runCatching { it.loadPreviewImage(iv.context, 0) }.getOrNull()
                }
                val drawable = preview ?: info?.let {
                    runCatching {
                        iv.context.packageManager
                            .getApplicationIcon(it.provider.packageName)
                    }.getOrNull()
                }
                iv.setImageDrawable(drawable)
                // App-icon fallback shouldn't balloon to the preview cap.
                val cap = if (preview == null) {
                    (56 * iv.resources.displayMetrics.density).toInt()
                } else {
                    (180 * iv.resources.displayMetrics.density).toInt()
                }
                iv.maxHeight = cap
                iv.maxWidth = (320 * iv.resources.displayMetrics.density).toInt()
            },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Medium)) {
                    append(name)
                }
                nameSuffix?.let {
                    withStyle(SpanStyle(color = subtitleColor, fontWeight = FontWeight.Normal)) {
                        append(" $it")
                    }
                }
            },
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            subtitle,
            color = subtitleColor,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = subtitleMaxLines,
        )
        trailing?.invoke()
    }
}
