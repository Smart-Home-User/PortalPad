package com.portalpad.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.portalpad.app.data.ButtonAppearance
import com.portalpad.app.data.ButtonIcons

/**
 * Renders a navigation-button glyph from a [ButtonAppearance].
 *
 * If the appearance carries a [ButtonAppearance.customIconPath] that decodes to a
 * bitmap, that user-picked image is drawn UNTINTED (full color) — its [iconColor]
 * is intentionally ignored. Otherwise the vector [ButtonAppearance.iconId] is
 * drawn tinted with [tint] (falling back to [fallback] when the id is unknown).
 *
 * This is the single external-bitmap render path shared by the trackpad nav bar
 * and the customize preview, and the foundation the icon-pack browser (Build 2)
 * will reuse.
 */
@Composable
fun ButtonGlyph(
    appearance: ButtonAppearance?,
    fallback: ImageVector,
    tint: Color,
    contentDescription: String?,
    size: Dp = 24.dp,
) {
    val path = appearance?.customIconPath
    // Decode once per path (icons are small); null on missing/invalid file.
    val bitmap = remember(path) {
        if (path.isNullOrBlank()) {
            null
        } else {
            runCatching { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = Modifier.size(size),
        )
    } else {
        Icon(
            ButtonIcons.resolve(appearance?.iconId, fallback),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}
