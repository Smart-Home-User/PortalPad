package com.portalpad.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log

/**
 * Discovers installed icon packs and loads individual icons from them.
 *
 * Icon packs are normal APKs that declare one of several well-known intent
 * filters (the "icon pack" / themer convention used by Nova, Apex, ADW, etc.).
 * Inside, they ship:
 *   - a res/xml/appfilter.xml (or drawable.xml) listing every drawable name, and
 *   - the drawables themselves under res/drawable*.
 *
 * For PortalPad's folder icons we only need to (a) list installed packs and
 * (b) given a pack + a drawable name, load that drawable. We deliberately do
 * NOT try to auto-map app package -> themed icon (that's appfilter's job and
 * is far more involved); the user just browses a pack's drawables and picks one.
 *
 * Everything here is defensive: icon packs vary wildly and many are malformed,
 * so every lookup is wrapped and failures return null rather than throwing.
 */
object IconPackManager {
    private const val TAG = "IconPackManager"

    /** Intent actions an icon pack may declare to advertise itself. */
    private val ICON_PACK_ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.gau.go.launcherex.theme",
        "com.novalauncher.THEME",
        "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "com.fede.launcher.THEME_ICONPACK",
        "ch.deletescape.lawnchair.ICONPACK",
        "app.lawnchair.icons.THEMED_ICON",
        "com.dlto.atom.launcher.THEME",
    )

    data class IconPack(val packageName: String, val label: String)

    /** All installed icon packs (deduped by package), sorted by label. */
    fun installedPacks(context: Context): List<IconPack> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, IconPack>()
        for (action in ICON_PACK_ACTIONS) {
            val infos = runCatching {
                pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA)
            }.getOrNull().orEmpty()
            for (ri in infos) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (found.containsKey(pkg)) continue
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
                found[pkg] = IconPack(pkg, label)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /**
     * The list of drawable names a pack exposes, parsed from its appfilter.xml
     * / drawable.xml. Returns distinct, non-blank names. May be large (1000s).
     * Returns empty list on any failure.
     */
    fun drawableNames(context: Context, packPackage: String): List<String> {
        val res = runCatching {
            context.packageManager.getResourcesForApplication(packPackage)
        }.getOrNull() ?: return emptyList()

        val names = LinkedHashSet<String>()
        // Prefer drawable.xml (a flat <item drawable="x"/> list); fall back to
        // appfilter.xml (which also has <item component=... drawable=x/>).
        for (xmlName in listOf("drawable", "appfilter")) {
            val id = runCatching {
                res.getIdentifier(xmlName, "xml", packPackage)
            }.getOrDefault(0)
            if (id == 0) continue
            runCatching {
                val parser = res.getXml(id)
                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG &&
                        (parser.name == "item" || parser.name == "icon")
                    ) {
                        val d = parser.getAttributeValue(null, "drawable")
                        if (!d.isNullOrBlank()) names.add(d)
                    }
                    event = parser.next()
                }
            }.onFailure { Log.w(TAG, "parse $xmlName.xml failed for $packPackage", it) }
            if (names.isNotEmpty()) break
        }
        return names.toList()
    }

    /** Load a single drawable by name from the pack. Null if missing/unloadable. */
    fun loadIcon(context: Context, packPackage: String, drawableName: String): Drawable? {
        return runCatching {
            val res = context.packageManager.getResourcesForApplication(packPackage)
            val id = res.getIdentifier(drawableName, "drawable", packPackage)
            if (id == 0) null
            else {
                @Suppress("DEPRECATION")
                res.getDrawable(id, null)
            }
        }.getOrNull()
    }
}
