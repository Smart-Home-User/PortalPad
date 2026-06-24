package com.portalpad.app.ui.dock

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sign

// Superellipse exponent for the universal app-icon squircle. ~3 sits between a
// circle (2) and a square (toward 8). One knob for the whole app's silhouette.
private const val SQUIRCLE_N = 3f

/** Closed superellipse (squircle) Compose path spanning [size]. */
private fun squirclePath(size: Size, n: Float = SQUIRCLE_N): Path {
    val path = Path()
    val a = size.width / 2f
    val b = size.height / 2f
    val ex = 2f / n
    val steps = 96
    var i = 0
    while (i <= steps) {
        val t = (i.toFloat() / steps) * (2.0 * Math.PI).toFloat()
        val ct = cos(t)
        val st = sin(t)
        val x = a + a * sign(ct) * abs(ct).pow(ex)
        val y = b + b * sign(st) * abs(st).pow(ex)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        i++
    }
    path.close()
    return path
}

/**
 * The squircle as a Compose [Shape], for clipping Compose CONTENT (e.g. folder
 * faces) where there's no bitmap to bake. A geometric clip works on ANY surface
 * — including the software-rendered virtual-display dock overlay, where the old
 * offscreen + BlendMode.DstIn mask silently no-op'd and left square corners.
 */
internal val SquircleShape: Shape = object : Shape {
    // createOutline runs every draw for every folder/clip user; the path only
    // depends on size, so cache it per size instead of rebuilding 96 points each
    // frame. Folder faces + preview plates use a few distinct sizes, so a tiny
    // bounded map covers them; it's cleared if it ever grows past the cap.
    private val cache = HashMap<Size, Path>()
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = synchronized(cache) {
            cache[size] ?: run {
                if (cache.size >= 8) cache.clear()
                squirclePath(size, SQUIRCLE_N).also { cache[size] = it }
            }
        }
        return Outline.Generic(path)
    }
}

/** Clip Compose content to the universal squircle. Surface-independent. */
internal fun Modifier.squircleClip(): Modifier = this.clip(SquircleShape)

// ---- bitmap-baked squircle (app icons) ------------------------------------
// App ICONS are real bitmaps, so we bake the squircle straight into the pixels
// with an antialiased android.graphics path fill. Crisper than a geometric clip
// AND, like the clip, it shows on a software-rendered overlay surface (the dock
// lives on our virtual display, whose overlay window can't be HW-accelerated —
// so offscreen/DstIn/RenderEffect all die there).

private fun androidSquirclePath(sizePx: Float, n: Float = SQUIRCLE_N): android.graphics.Path {
    val path = android.graphics.Path()
    val a = sizePx / 2f
    val b = sizePx / 2f
    val ex = 2f / n
    val steps = 96
    var i = 0
    while (i <= steps) {
        val t = (i.toFloat() / steps) * (2.0 * Math.PI).toFloat()
        val ct = cos(t)
        val st = sin(t)
        val x = a + a * sign(ct) * abs(ct).pow(ex)
        val y = b + b * sign(st) * abs(st).pow(ex)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        i++
    }
    path.close()
    return path
}

/** Mask [src] (a square px x px icon) to an antialiased squircle. */
private fun bakeSquircle(src: android.graphics.Bitmap, n: Float = SQUIRCLE_N): android.graphics.Bitmap {
    val px = src.width
    val out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    canvas.drawPath(androidSquirclePath(px.toFloat(), n), paint)            // opaque squircle
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(src, 0f, 0f, paint)                                  // icon, only inside it
    paint.xfermode = null
    return out
}

/**
 * Pre-built reflection: the top slice of a vertical mirror of [squircleIcon],
 * dimmed and faded to transparent — all baked in, so it renders on any surface.
 * (The runtime DstIn fade we used before dies on the software dock overlay.)
 */
private fun bakeReflection(
    squircleIcon: android.graphics.Bitmap,
    heightFrac: Float = 0.30f,
    topAlpha: Float = 0.32f,
): android.graphics.Bitmap {
    val px = squircleIcon.width
    val reflectPx = (px * heightFrac).toInt().coerceAtLeast(1)
    val out = android.graphics.Bitmap.createBitmap(px, reflectPx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val paint = android.graphics.Paint(
        android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG,
    )
    // Vertical flip: only the icon's bottom slice lands in this short bitmap,
    // with the icon's bottom edge becoming the reflection's top.
    val m = android.graphics.Matrix().apply {
        setScale(1f, -1f)
        postTranslate(0f, px.toFloat())
    }
    canvas.save()
    canvas.concat(m)
    canvas.drawBitmap(squircleIcon, 0f, 0f, paint)
    canvas.restore()
    // Bake dim + vertical fade: multiply alpha by a (topAlpha -> 0) ramp.
    val fade = android.graphics.Paint()
    fade.shader = android.graphics.LinearGradient(
        0f, 0f, 0f, reflectPx.toFloat(),
        android.graphics.Color.argb((255 * topAlpha).toInt(), 0, 0, 0),
        android.graphics.Color.TRANSPARENT,
        android.graphics.Shader.TileMode.CLAMP,
    )
    fade.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    canvas.drawRect(0f, 0f, px.toFloat(), reflectPx.toFloat(), fade)
    // Visibility floor: a dark icon's mirror is dark and vanishes against the
    // dark dock (e.g. the Settings gear), so lift every reflection toward white
    // by a small amount. SRC_ATOP composites the white only where the reflection
    // already exists and keeps its (already-faded) alpha — so it tints the icon
    // shape, preserves the vertical fade, and never paints the transparent
    // surround. Dark icons gain a faint visible sheen; near-white icons are
    // ~unchanged (just slightly milkier). ← tune floorAlpha (0 = off).
    val floorAlpha = 0.32f
    val floor = android.graphics.Paint()
    floor.color = android.graphics.Color.argb((255 * floorAlpha).toInt(), 255, 255, 255)
    floor.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
    canvas.drawRect(0f, 0f, px.toFloat(), reflectPx.toFloat(), floor)
    return out
}

// In-memory cache of baked icon bitmaps keyed by package+size+reflect, so the
// dock tearing down/rebuilding — or the same app appearing in dock + folder +
// search — reuses one baked bitmap instead of re-running the Canvas bake.
// Access-ordered + bounded so it acts as an LRU and can't grow without limit.
private object IconBakeCache {
    private const val MAX = 96
    private val map = object :
        LinkedHashMap<String, Pair<ImageBitmap, ImageBitmap?>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<ImageBitmap, ImageBitmap?>>,
        ): Boolean = size > MAX
    }

    @Synchronized fun get(key: String): Pair<ImageBitmap, ImageBitmap?>? = map[key]

    @Synchronized fun put(key: String, value: Pair<ImageBitmap, ImageBitmap?>) {
        map[key] = value
    }
}

@Composable
fun AppIcon(packageName: String, sizeDp: Int, reflect: Boolean = false) {
    val context = LocalContext.current
    var iconBmp by remember(packageName, sizeDp) { mutableStateOf<ImageBitmap?>(null) }
    var reflectionBmp by remember(packageName, sizeDp, reflect) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName, sizeDp, reflect) {
        val key = "$packageName|$sizeDp|$reflect"
        IconBakeCache.get(key)?.let {
            iconBmp = it.first
            reflectionBmp = it.second
            return@LaunchedEffect
        }
        // Bake off the main thread (a 4× bitmap + two Canvas passes per icon
        // would jank first render if a dozen ran on the UI thread at once).
        val pair = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val pm = context.packageManager
                val icon = pm.getApplicationIcon(packageName)
                val px = sizeDp * 4
                // Square source with NO OEM mask, so every icon gets OUR squircle
                // (baked below) rather than the device's own adaptive-icon shape.
                val square: android.graphics.Bitmap =
                    if (android.os.Build.VERSION.SDK_INT >= 26 &&
                        icon is android.graphics.drawable.AdaptiveIconDrawable &&
                        (icon.background != null || icon.foreground != null)
                    ) {
                        val sq = android.graphics.Bitmap.createBitmap(
                            px, px, android.graphics.Bitmap.Config.ARGB_8888,
                        )
                        val c = android.graphics.Canvas(sq)
                        icon.background?.apply { setBounds(0, 0, px, px); draw(c) }
                        icon.foreground?.apply { setBounds(0, 0, px, px); draw(c) }
                        sq
                    } else {
                        icon.toBitmap(px, px)
                    }
                val squircle = bakeSquircle(square)
                val refl = if (reflect) bakeReflection(squircle) else null
                squircle.asImageBitmap() to refl?.asImageBitmap()
            }.getOrNull()
        }
        if (pair != null) {
            IconBakeCache.put(key, pair)
            iconBmp = pair.first
            reflectionBmp = pair.second
        }
    }
    iconBmp?.let { bmp ->
        if (!reflect) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.size(sizeDp.dp),
            )
        } else {
            // Icon plus a faint, vertically-flipped reflection beneath it
            // (macOS-dock style). Both bitmaps are pre-shaped/pre-faded, so no
            // runtime blend is needed — it survives the software dock surface.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.size(sizeDp.dp),
                )
                Spacer(Modifier.height((sizeDp * 0.05f).dp))
                reflectionBmp?.let { rb ->
                    Image(
                        bitmap = rb,
                        contentDescription = null,
                        modifier = Modifier
                            .width(sizeDp.dp)
                            .height((sizeDp * 0.30f).dp),
                    )
                }
            }
        }
    }
}
