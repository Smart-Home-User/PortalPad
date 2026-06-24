package com.portalpad.app.ui.dock

import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalpad.app.data.DockItem
import com.portalpad.app.data.FolderAppearance

/**
 * Preset folder icons. Each is a folder SHAPE (a filled folder glyph) tinted by
 * the folder's color with a small white category emblem sitting on the folder
 * body — so every preset unmistakably reads as a folder, themed by category.
 * Keyed by a stable string stored in DockItem.Folder.presetKey.
 */
enum class FolderPreset(val key: String, val emblem: ImageVector) {
    GENERIC("generic", Icons.Filled.Folder),
    GAMES("games", Icons.Filled.SportsEsports),
    MEDIA("media", Icons.Filled.Movie),
    MUSIC("music", Icons.Filled.MusicNote),
    PHOTOS("photos", Icons.Filled.CameraAlt),
    SOCIAL("social", Icons.Filled.Chat),
    WORK("work", Icons.Filled.Work),
    TOOLS("tools", Icons.Filled.Build),
    WEB("web", Icons.Filled.Public),
    SHOPPING("shopping", Icons.Filled.ShoppingCart),
    BOOKS("books", Icons.Filled.Book),
    TRAVEL("travel", Icons.Filled.Flight),
    ART("art", Icons.Filled.Brush),
    FAVORITES("favorites", Icons.Filled.Favorite),
    STARRED("starred", Icons.Filled.Star);

    companion object {
        fun byKey(key: String?): FolderPreset =
            entries.firstOrNull { it.key == key } ?: GENERIC
    }
}

/** Curated background swatches for folders (look good on the dark dock). */
val FolderColorSwatches: List<Color> = listOf(
    Color(0xFF3A3A44), // neutral charcoal (default-ish)
    Color(0xFF4C6EF5), // blue
    Color(0xFF12B886), // teal/green
    Color(0xFFF59F00), // amber
    Color(0xFFE8590C), // orange
    Color(0xFFE64980), // pink
    Color(0xFF7048E8), // violet
    Color(0xFF1098AD), // cyan
    Color(0xFF66A80F), // lime
    Color(0xFFD6336C), // magenta
    Color(0xFF495057), // slate
    Color(0xFF0CA678), // emerald
)

/** Parse a stored hex color, falling back to a neutral folder color. */
fun parseFolderColor(hex: String?): Color {
    if (hex.isNullOrBlank()) return Color(0xFF3A3A44)
    return runCatching {
        val clean = hex.removePrefix("#")
        val withAlpha = if (clean.length == 6) "FF$clean" else clean
        Color(withAlpha.toLong(16))
    }.getOrDefault(Color(0xFF3A3A44))
}

/** Serialize a Color to #AARRGGBB hex for storage. */
fun Color.toHex(): String {
    val a = (alpha * 255).toInt(); val r = (red * 255).toInt()
    val g = (green * 255).toInt(); val b = (blue * 255).toInt()
    return "#%02X%02X%02X%02X".format(a, r, g, b)
}

/**
 * Renders a folder's icon at [sizeDp] according to its appearance:
 *  - AUTO   → 2x2 preview of the first four contained app icons on the color
 *  - PRESET → folder shape + category emblem on the color
 *  - SYMBOL → the chosen emoji/glyph centered on the color
 */
@Composable
fun FolderIcon(folder: DockItem.Folder, sizeDp: Int, reflect: Boolean = false) {
    val bg = parseFolderColor(folder.color)
    // The folder face, extracted so the reflection can re-render it flipped.
    // Uses the SAME universal n=3 squircle as app icons (squircleClip) so folders
    // and apps share one silhouette instead of folders reading as rounded squares.
    val face: @Composable () -> Unit = {
        Box(
            Modifier
                .size(sizeDp.dp)
                .squircleClip()
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            when (folder.appearance) {
                FolderAppearance.SYMBOL -> {
                    androidx.compose.material3.Text(
                        text = folder.symbol?.takeIf { it.isNotBlank() } ?: "📁",
                        fontSize = (sizeDp * 0.5f).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
                FolderAppearance.PRESET -> {
                    val preset = FolderPreset.byKey(folder.presetKey)
                    androidx.compose.material3.Icon(
                        imageVector = preset.emblem,
                        contentDescription = folder.label,
                        tint = Color.White,
                        modifier = Modifier.size((sizeDp * 0.55f).dp),
                    )
                }
                FolderAppearance.AUTO -> {
                    AutoPreview(folder = folder, sizeDp = sizeDp)
                }
                FolderAppearance.CUSTOM -> {
                    CustomPackIcon(
                        packPackage = folder.iconPackPackage,
                        drawableName = folder.iconPackDrawable,
                        sizeDp = sizeDp,
                        fallbackLabel = folder.label,
                    )
                }
            }
        }
    }

    if (!reflect) {
        face()
        return
    }
    // macOS-dock-style reflection — same parameters as AppIcon (0.30 height, 0.32
    // alpha, light blur, fade-to-transparent) so apps and folders read as a set.
    // The face is re-rendered full-size, flipped, and clipped to a short top slice.
    val reflectH = (sizeDp * 0.30f)
    val reflectCorner = (sizeDp * 0.28f).dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        face()
        Spacer(Modifier.height((sizeDp * 0.05f).dp))
        Box(
            Modifier
                .width(sizeDp.dp)
                .height(reflectH.dp)
                .clip(RoundedCornerShape(topStart = reflectCorner, topEnd = reflectCorner))
                .graphicsLayer {
                    alpha = 0.32f
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        val r = (sizeDp * 0.04f).coerceAtLeast(2f)
                        renderEffect = androidx.compose.ui.graphics.BlurEffect(
                            r, r, androidx.compose.ui.graphics.TileMode.Decal,
                        )
                    }
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier
                    .requiredSize(sizeDp.dp)
                    .graphicsLayer { scaleY = -1f },
            ) { face() }
        }
    }
}

/** Renders a chosen icon-pack drawable; falls back to a folder glyph if the
 *  pack/drawable can't be loaded (uninstalled pack, renamed drawable, etc). */
@Composable
private fun CustomPackIcon(
    packPackage: String?,
    drawableName: String?,
    sizeDp: Int,
    fallbackLabel: String,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember(packPackage, drawableName) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(packPackage, drawableName) {
        bitmap = if (packPackage.isNullOrBlank() || drawableName.isNullOrBlank()) null
        else runCatching {
            com.portalpad.app.data.IconPackManager
                .loadIcon(context, packPackage, drawableName)
                ?.let { d ->
                    d.toBitmap(sizeDp * 4, sizeDp * 4).asImageBitmap()
                }
        }.getOrNull()
    }
    val bmp = bitmap
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp,
            contentDescription = fallbackLabel,
            modifier = Modifier.size((sizeDp * 0.92f).dp),
        )
    } else {
        // Fallback: plain folder glyph so the tile is never blank.
        androidx.compose.material3.Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = fallbackLabel,
            tint = Color.White,
            modifier = Modifier.size((sizeDp * 0.55f).dp),
        )
    }
}

/** 2x2 grid of the first four contained app icons (macOS/iOS-style preview). */
@Composable
private fun AutoPreview(folder: DockItem.Folder, sizeDp: Int) {
    val shortcuts = folder.shortcutContents.take(4)
    if (shortcuts.isEmpty()) {
        // Empty folder → plain folder glyph so it still reads as a folder.
        androidx.compose.material3.Icon(
            Icons.Filled.Folder,
            contentDescription = folder.label,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size((sizeDp * 0.5f).dp),
        )
        return
    }
    val cell = (sizeDp * 0.34f).toInt()
    Column(
        Modifier.padding((sizeDp * 0.12f).dp),
        verticalArrangement = Arrangement.spacedBy((sizeDp * 0.04f).dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((sizeDp * 0.04f).dp)) {
            PreviewCell(shortcuts.getOrNull(0)?.app?.packageName, cell)
            PreviewCell(shortcuts.getOrNull(1)?.app?.packageName, cell)
        }
        Row(horizontalArrangement = Arrangement.spacedBy((sizeDp * 0.04f).dp)) {
            PreviewCell(shortcuts.getOrNull(2)?.app?.packageName, cell)
            PreviewCell(shortcuts.getOrNull(3)?.app?.packageName, cell)
        }
    }
}

@Composable
private fun PreviewCell(packageName: String?, cellDp: Int) {
    Box(
        Modifier
            .size(cellDp.dp)
            .squircleClip()
            .background(Color.White.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        if (packageName != null) {
            AppIcon(packageName = packageName, sizeDp = cellDp)
        }
    }
}
