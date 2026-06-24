package com.portalpad.app.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as lazyGridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.res.ResourcesCompat
import com.portalpad.app.ui.theme.AbAccent
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbSurfaceElevated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** An installed icon-pack app. */
data class IconPackInfo(val packageName: String, val label: String)

// Theme actions various launchers' icon packs declare on their activities. We
// query each so installed packs become visible (paired with <queries> in the
// manifest for Android 11+ package visibility).
private val ICON_PACK_ACTIONS = listOf(
    "com.novalauncher.THEME",
    "org.adw.launcher.THEMES",
    "com.gau.go.launcherex.theme",
    "com.anddoes.launcher.THEME",
    "com.teslacoilsw.launcher.THEME",
    "com.fede.launcher.THEME_ICONPACK",
    "com.dlto.atom.launcher.THEME",
    "ginlemon.smartlauncher.THEME",
    "com.phonemetra.turbo.launcher.THEME",
)

/** Installed icon-pack apps, de-duped and sorted by label. */
private fun installedIconPacks(ctx: Context): List<IconPackInfo> {
    val pm = ctx.packageManager
    val pkgs = linkedSetOf<String>()
    for (action in ICON_PACK_ACTIONS) {
        runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(action), 0).forEach { pkgs.add(it.activityInfo.packageName) }
        }
    }
    return pkgs.mapNotNull { pkg ->
        runCatching {
            val ai = pm.getApplicationInfo(pkg, 0)
            IconPackInfo(pkg, pm.getApplicationLabel(ai).toString())
        }.getOrNull()
    }.sortedBy { it.label.lowercase() }
}

/**
 * Drawable names offered by a pack. Prefers the curated picker list (drawable.xml);
 * falls back to the full component map (appfilter.xml). De-duped, order preserved.
 */
private fun iconDrawableNames(ctx: Context, pkg: String): List<String> {
    val res = runCatching { ctx.packageManager.getResourcesForApplication(pkg) }.getOrNull()
        ?: return emptyList()
    val out = LinkedHashSet<String>()
    parseItems(res, pkg, "drawable", out)
    if (out.isEmpty()) parseItems(res, pkg, "appfilter", out)
    return out.toList()
}

/** Parse <item drawable="..."/> entries from res/xml/<base>.xml or assets/<base>.xml. */
private fun parseItems(
    res: android.content.res.Resources,
    pkg: String,
    base: String,
    out: MutableSet<String>,
) {
    val parser: XmlPullParser? = runCatching {
        val id = res.getIdentifier(base, "xml", pkg)
        if (id != 0) res.getXml(id) else null
    }.getOrNull() ?: runCatching {
        XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(res.assets.open("$base.xml"), "UTF-8")
        }
    }.getOrNull()
    parser ?: return
    runCatching {
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && parser.name == "item") {
                parser.getAttributeValue(null, "drawable")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { out.add(it) }
            }
            ev = parser.next()
        }
    }
}

/** Load a pack drawable by name, rasterized to a square [sizePx] bitmap (0 = native). */
private fun loadPackIcon(ctx: Context, pkg: String, name: String, sizePx: Int): Bitmap? {
    val res = runCatching { ctx.packageManager.getResourcesForApplication(pkg) }.getOrNull() ?: return null
    val id = res.getIdentifier(name, "drawable", pkg)
    if (id == 0) return null
    val d = runCatching { ResourcesCompat.getDrawable(res, id, null) }.getOrNull() ?: return null
    return runCatching { drawableToBitmap(d, sizePx) }.getOrNull()
}

private fun drawableToBitmap(d: android.graphics.drawable.Drawable, sizePx: Int): Bitmap {
    if (d is BitmapDrawable) {
        d.bitmap?.let { return if (sizePx > 0) Bitmap.createScaledBitmap(it, sizePx, sizePx, true) else it }
    }
    val w = if (sizePx > 0) sizePx else d.intrinsicWidth.coerceAtLeast(1)
    val h = if (sizePx > 0) sizePx else d.intrinsicHeight.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, w, h)
    d.draw(Canvas(bmp))
    return bmp
}

/** Bake the picked icon into app storage (one file per target) and return its path. */
private fun savePickedIcon(ctx: Context, target: String, bmp: Bitmap): String? = runCatching {
    val dir = java.io.File(ctx.filesDir, "nav_icons").apply { mkdirs() }
    dir.listFiles()?.filter { it.name.startsWith("$target-") }?.forEach { it.delete() }
    val out = java.io.File(dir, "$target-${System.currentTimeMillis()}.png")
    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    out.absolutePath
}.getOrNull()

/**
 * Two-stage picker: a list of installed icon-pack apps, then a searchable grid of
 * that pack's icons. Tapping an icon bakes it to storage and returns the path via
 * [onPicked]. All package/resource work runs off the main thread.
 */
@Composable
fun IconPackPickerDialog(
    target: String,
    onPicked: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf<List<IconPackInfo>?>(null) }
    var chosen by remember { mutableStateOf<IconPackInfo?>(null) }
    var names by remember { mutableStateOf<List<String>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { packs = withContext(Dispatchers.IO) { installedIconPacks(ctx) } }
    LaunchedEffect(chosen) {
        names = null
        query = ""
        chosen?.let { p -> names = withContext(Dispatchers.IO) { iconDrawableNames(ctx, p.packageName) } }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(18.dp))
                .background(AbBackground),
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (chosen != null) {
                        Text(
                            "\u2039 Packs",
                            color = AbAccent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { chosen = null }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        chosen?.label ?: "Choose a custom icon",
                        color = AbOnSurface,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "\u2715",
                        color = AbOnSurfaceMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDismiss() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))

                if (chosen == null) {
                    when {
                        packs == null -> CenterSpinner()
                        packs!!.isEmpty() -> Text(
                            "No icon packs found. Install one from the Play Store (search \u201Cicon pack\u201D), then come back \u2014 it'll show up here.",
                            color = AbOnSurfaceMuted,
                        )
                        else -> LazyColumn(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            lazyListItems(packs!!) { pack ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(AbSurfaceElevated)
                                        .clickable { chosen = pack }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(pack.label, color = AbOnSurface, modifier = Modifier.weight(1f))
                                    Text("\u203A", color = AbOnSurfaceMuted)
                                }
                            }
                        }
                    }
                } else {
                    val all = names
                    when {
                        all == null -> CenterSpinner()
                        all.isEmpty() -> Text("No icons found in this pack.", color = AbOnSurfaceMuted)
                        else -> {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text("Search icons") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            val filtered = remember(query, all) {
                                if (query.isBlank()) all else all.filter { it.contains(query.trim(), ignoreCase = true) }
                            }
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(56.dp),
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                lazyGridItems(filtered) { name ->
                                    IconCell(ctx, chosen!!.packageName, name) {
                                        scope.launch {
                                            val path = withContext(Dispatchers.IO) {
                                                loadPackIcon(ctx, chosen!!.packageName, name, 144)
                                                    ?.let { savePickedIcon(ctx, target, it) }
                                            }
                                            if (path != null) {
                                                onPicked(path)
                                                onDismiss()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconCell(ctx: Context, pkg: String, name: String, onClick: () -> Unit) {
    var bmp by remember(pkg, name) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pkg, name) { bmp = withContext(Dispatchers.IO) { loadPackIcon(ctx, pkg, name, 96) } }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(AbSurfaceElevated)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(it.asImageBitmap(), contentDescription = name, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun CenterSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AbAccent)
    }
}
