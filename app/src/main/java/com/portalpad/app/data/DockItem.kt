package com.portalpad.app.data

import kotlinx.serialization.Serializable

/**
 * Item that lives in the dock shown on the external display. Items are either a
 * single app/activity launcher (Shortcut) or a Folder containing more items.
 *
 * Folders may now contain other folders (nesting), so [Folder.contents] holds
 * [DockItem] rather than only Shortcut. Backward-compatible: configs stored
 * before nesting had only Shortcut children, which still decode (Shortcut is a
 * DockItem) under the lenient JSON config in PreferencesRepository.
 */
@Serializable
sealed class DockItem {
    abstract val id: String
    abstract val label: String

    @Serializable
    data class Shortcut(
        override val id: String,
        override val label: String,
        val app: AppEntry,
    ) : DockItem()

    @Serializable
    data class Folder(
        override val id: String,
        override val label: String,
        val contents: List<DockItem> = emptyList(),
        /** Background color as #AARRGGBB or #RRGGBB hex; null = dock default. */
        val color: String? = null,
        /** How the folder icon renders. */
        val appearance: FolderAppearance = FolderAppearance.AUTO,
        /** Preset key when [appearance] == PRESET (see FolderIcons). */
        val presetKey: String? = null,
        /** Emoji/glyph when [appearance] == SYMBOL. */
        val symbol: String? = null,
        /** Currently-selected sort for this folder's contents, persisted so the
         *  folder UI can highlight the active sort. NONE = manual order.
         *  Defaulted for backward-compatibility with saved configs. */
        val sort: DockSort = DockSort.NONE,
        /** Sort direction. For ALPHA: true = A→Z, false = Z→A. For RECENT:
         *  true = newest installed first, false = oldest first. */
        val sortAscending: Boolean = true,
        /** Icon-pack package when [appearance] == CUSTOM. */
        val iconPackPackage: String? = null,
        /** Drawable name within [iconPackPackage] when [appearance] == CUSTOM. */
        val iconPackDrawable: String? = null,
    ) : DockItem() {
        /** Only the Shortcut children, for the 2x2 auto-preview. */
        val shortcutContents: List<Shortcut>
            get() = contents.filterIsInstance<Shortcut>()
    }
}

/** How a folder's icon is rendered. */
@Serializable
enum class FolderAppearance { AUTO, PRESET, SYMBOL, CUSTOM }

/** Sort order for dock items / folder contents. */
@Serializable
enum class DockSort { NONE, ALPHA, RECENT }

// ---- Recursive tree helpers (operate on a List<DockItem> forest) ----------

/** Find any item by id, searching into nested folders. */
fun List<DockItem>.findItem(id: String): DockItem? {
    for (item in this) {
        if (item.id == id) return item
        if (item is DockItem.Folder) item.contents.findItem(id)?.let { return it }
    }
    return null
}

/** Find a folder by id, searching into nested folders. */
fun List<DockItem>.findFolder(id: String): DockItem.Folder? =
    findItem(id) as? DockItem.Folder

/** All folders anywhere in the tree, flattened (parents before their children). */
fun List<DockItem>.allFoldersFlat(): List<DockItem.Folder> {
    val out = mutableListOf<DockItem.Folder>()
    fun walk(items: List<DockItem>) {
        for (item in items) if (item is DockItem.Folder) { out.add(item); walk(item.contents) }
    }
    walk(this)
    return out
}

/** Replace the folder with [id] via [transform], recursively. */
fun List<DockItem>.mapFolder(
    id: String,
    transform: (DockItem.Folder) -> DockItem.Folder,
): List<DockItem> = map { item ->
    when (item) {
        is DockItem.Folder ->
            if (item.id == id) transform(item)
            else item.copy(contents = item.contents.mapFolder(id, transform))
        else -> item
    }
}

/** Remove the item with [id] from anywhere in the forest. */
fun List<DockItem>.removeRecursive(id: String): List<DockItem> =
    filterNot { it.id == id }.map { item ->
        if (item is DockItem.Folder) item.copy(contents = item.contents.removeRecursive(id))
        else item
    }

/** True if the folder with [ancestorId] contains [descendantId] somewhere below. */
fun List<DockItem>.folderContains(ancestorId: String, descendantId: String): Boolean {
    val folder = findFolder(ancestorId) ?: return false
    return folder.contents.findItem(descendantId) != null
}

@Serializable
data class DockConfig(
    val items: List<DockItem> = emptyList(),
    val position: DockPosition = DockPosition.BOTTOM,
    val autoHide: Boolean = true,
    /** Icon tile size, in density-independent px. Adjusted only from Dock
     *  settings — the renderer compensates for the external display's DPI so
     *  this maps to a consistent on-screen size regardless of Display DPI.
     *  Default ~app-icon size so the dock reads as a slim macOS-style bar
     *  (matching the desktop taskbar) rather than a bulky strip. */
    val iconSizeDp: Int = 60,
    /** Label text size under each icon, in sp. 0 hides labels. */
    val labelSizeSp: Int = 13,
    /** Overall dock scale percent (50–160) applied on top of icon/label sizes,
     *  so the user can make the whole dock bigger/smaller in one control. */
    val scalePct: Int = 100,
    /** Soft cap on dock width. Items beyond this count are reachable via scroll. */
    val maxVisibleItems: Int = 8,
    /** Max dock span as a percent of the display's long edge (40–100). Default
     *  is wide — a near-full-width desktop-style bar with a small gap at each
     *  edge — and applies to BOTH desktop and non-desktop modes. The dock still
     *  shrinks to fit when it has fewer items than the span allows. */
    val dockWidthPct: Int = 75,
    /** Hide the dock after this many seconds of no interaction. 0 = never hide. */
    val autoHideAfterSec: Int = 7,
    /** The currently-selected top-level sort mode, persisted so the Settings UI
     *  can highlight which sort is active. NONE = no explicit sort chosen
     *  (manual order). Defaulted for backward-compatibility with saved configs. */
    val topLevelSort: DockSort = DockSort.NONE,
    /** Visual style (gradient preset, transparency, corner radius). */
    val style: BarStyle = BarStyle(),
    /** Dock clock time format. */
    val clockTimeFormat: ClockTimeFormat = ClockTimeFormat.AUTO,
    /** Dock clock date format. */
    val clockDateFormat: ClockDateFormat = ClockDateFormat.AUTO,
    /** Reveal detection-zone thickness in px (how close to the dock's edge the
     *  cursor must be to reveal an auto-hidden dock). Mirrors the top bar. */
    val revealZoneHeightPx: Int = 3,
    /** Reveal detection-zone span as a percent of the dock's edge (the centered
     *  band within which the cursor reveals the dock). 10–100. */
    val revealZoneWidthPct: Int = 50,
    /** Color (ARGB Long) of the detection-zone highlight shown while tuning the
     *  zone on the dock customization page. Default = translucent accent yellow. */
    val zoneHighlightColor: Long = 0x66FFC83A,
) {
    fun addShortcut(shortcut: DockItem.Shortcut) = copy(items = items + shortcut)

    /** Count of top-level dock items (folders + shortcuts). The cap applies to
     *  the top row; apps nested inside folders don't count against it. */
    val topLevelCount: Int get() = items.size

    /** True if the top-level dock is at the soft item cap. */
    val isFull: Boolean get() = items.size >= MAX_DOCK_ITEMS

    /** Remove a top-level item (kept for callers that only touch the top row). */
    fun removeItem(id: String) = copy(items = items.filterNot { it.id == id })

    /** Delete an item from anywhere in the tree (nested-aware). */
    fun deleteItem(id: String) = copy(items = items.removeRecursive(id))

    fun moveItem(fromIndex: Int, toIndex: Int): DockConfig {
        val list = items.toMutableList()
        if (fromIndex !in list.indices) return this
        val moved = list.removeAt(fromIndex)
        val dest = toIndex.coerceIn(0, list.size)
        list.add(dest, moved)
        return copy(items = list)
    }

    /** Reorder an item within a folder's contents by index. */
    fun moveItemInFolder(folderId: String, fromIndex: Int, toIndex: Int): DockConfig =
        copy(items = items.mapFolder(folderId) { f ->
            val list = f.contents.toMutableList()
            if (fromIndex !in list.indices) return@mapFolder f
            val moved = list.removeAt(fromIndex)
            list.add(toIndex.coerceIn(0, list.size), moved)
            f.copy(contents = list)
        })

    /** Drop item A onto shortcut/folder B → creates folder or appends. Top-level. */
    fun coalesce(sourceId: String, targetId: String): DockConfig {
        if (sourceId == targetId) return this
        val source = items.firstOrNull { it.id == sourceId } ?: return this
        val target = items.firstOrNull { it.id == targetId } ?: return this
        val newItems = items.toMutableList().apply { removeAll { it.id == sourceId } }
        val targetIdx = newItems.indexOfFirst { it.id == targetId }
        if (targetIdx < 0) return this
        val replaced = when (target) {
            is DockItem.Folder -> target.copy(contents = target.contents + source)
            is DockItem.Shortcut -> DockItem.Folder(
                id = "folder_${System.currentTimeMillis()}",
                label = "Folder",
                contents = listOf(target, source),
            )
        }
        newItems[targetIdx] = replaced
        return copy(items = newItems)
    }

    /** Create a new empty folder at the top level. */
    fun addFolder(label: String): DockConfig = copy(
        items = items + DockItem.Folder(
            id = "folder_${System.currentTimeMillis()}",
            label = label,
        )
    )

    /** Create a new top-level folder and move [itemId] into it in one step.
     *  Used by the dock item long-press "New folder with this app" action. */
    fun newFolderWithItem(itemId: String, label: String = "New folder"): DockConfig {
        val newId = "folder_${System.currentTimeMillis()}"
        val withFolder = copy(items = items + DockItem.Folder(id = newId, label = label))
        return withFolder.moveIntoFolder(itemId, newId)
    }

    /** Create a new empty sub-folder INSIDE [parentId]. */
    fun addSubFolder(parentId: String, label: String): DockConfig =
        copy(items = items.mapFolder(parentId) { f ->
            f.copy(contents = f.contents + DockItem.Folder(
                id = "folder_${System.currentTimeMillis()}",
                label = label,
            ))
        })

    /**
     * Move any item (shortcut OR folder) into a folder, from anywhere in the
     * tree. Refuses to move a folder into itself or into one of its own
     * descendants (which would orphan the subtree).
     */
    fun moveIntoFolder(itemId: String, folderId: String): DockConfig {
        if (itemId == folderId) return this
        val item = items.findItem(itemId) ?: return this
        if (item is DockItem.Folder && item.contents.findItem(folderId) != null) return this
        val without = items.removeRecursive(itemId)
        val added = without.mapFolder(folderId) { f -> f.copy(contents = f.contents + item) }
        return copy(items = added)
    }

    /** Pull an item out of its folder and place it at the top level. */
    fun extractToTopLevel(itemId: String): DockConfig {
        val item = items.findItem(itemId) ?: return this
        // No-op if it's already top-level.
        if (items.any { it.id == itemId }) return this
        val without = items.removeRecursive(itemId)
        return copy(items = without + item)
    }

    /** Delete a folder but keep its apps (they move up to the folder's parent
     *  level — top level for simplicity). */
    fun dissolveFolder(folderId: String): DockConfig {
        val folder = items.findFolder(folderId) ?: return this
        val without = items.removeRecursive(folderId)
        return copy(items = without + folder.contents)
    }

    /** Update a folder's appearance fields. */
    fun setFolderAppearance(
        folderId: String,
        color: String?,
        appearance: FolderAppearance,
        presetKey: String?,
        symbol: String?,
        iconPackPackage: String? = null,
        iconPackDrawable: String? = null,
    ): DockConfig = copy(items = items.mapFolder(folderId) {
        it.copy(
            color = color, appearance = appearance, presetKey = presetKey, symbol = symbol,
            iconPackPackage = iconPackPackage, iconPackDrawable = iconPackDrawable,
        )
    })

    /** Sort the top-level items. [recentMillis] returns a package's install
     *  time for RECENT sort (0 when unknown). Folders sort by label. */
    fun sortTopLevel(mode: DockSort, recentMillis: (String) -> Long): DockConfig =
        copy(items = items.sortedWithMode(mode, true, recentMillis), topLevelSort = mode)

    /** Sort a single folder's contents in the given direction. */
    fun sortFolder(folderId: String, mode: DockSort, ascending: Boolean, recentMillis: (String) -> Long): DockConfig =
        copy(items = items.mapFolder(folderId) { f ->
            f.copy(contents = f.contents.sortedWithMode(mode, ascending, recentMillis), sort = mode, sortAscending = ascending)
        })
}

/** Shared sort used for both top-level and folder contents. [ascending] flips the
 *  natural order of the mode (ALPHA natural = A→Z; RECENT natural = newest first). */
private fun List<DockItem>.sortedWithMode(
    mode: DockSort,
    ascending: Boolean,
    recentMillis: (String) -> Long,
): List<DockItem> {
    val base = when (mode) {
        DockSort.NONE -> return this
        DockSort.ALPHA -> sortedBy { it.label.lowercase() }
        DockSort.RECENT -> sortedByDescending { item ->
            when (item) {
                is DockItem.Shortcut -> recentMillis(item.app.packageName)
                // Folders have no install time; sort them to the end by 0.
                is DockItem.Folder -> 0L
            }
        }
    }
    return if (ascending) base else base.reversed()
}

@Serializable
enum class DockPosition { BOTTOM, TOP, LEFT, RIGHT }

/** Dock clock time format. AUTO follows the system 12/24-hour setting. */
@Serializable
enum class ClockTimeFormat { AUTO, H24, H12 }

/** Dock clock date format. AUTO follows the system locale date format. */
@Serializable
enum class ClockDateFormat { AUTO, MDY, DMY, YMD }

/** Horizontal placement of the top window-control bar on the external display. */
@Serializable
enum class TopBarPosition { LEFT, CENTER, RIGHT }

/**
 * Configuration for the top window-control bar on the external display. Mirrors
 * the DockConfig pattern (single JSON blob persisted in prefs). Phase 1:
 * position, width, auto-hide duration, and the cursor reveal-zone size.
 */
@Serializable
data class TopBarConfig(
    /** Horizontal placement: LEFT / CENTER / RIGHT of the top edge. */
    val position: TopBarPosition = TopBarPosition.CENTER,
    /** Bar width as a percent of the display width (30–100). The window is still
     *  capped in px so it can't stretch a super-ultrawide canvas absurdly. */
    val widthPct: Int = 30,
    /** Uniform size scale for the whole bar as a percent (60–160). Multiplies the
     *  bar's base density, so icons, text, padding, and the bar's height all grow
     *  or shrink together. Clamped both ends so icons never get untappable-small
     *  or absurdly large. */
    val sizePct: Int = 60,
    /** Auto-hide the bar after this many seconds of no interaction. 0 = always
     *  visible (truly pinned, never hides). Matches DockConfig.autoHideAfterSec
     *  (0–30s range, default 7). */
    val autoHideAfterSec: Int = 7,
    /** Reveal detection-zone height in px (how close to the top edge the cursor
     *  must be to reveal the bar). */
    val revealZoneHeightPx: Int = 3,
    /** Reveal detection-zone width as a percent of display width (the horizontal
     *  band, centered on the bar's position, within which the cursor reveals
     *  the bar). 10–100. */
    val revealZoneWidthPct: Int = 50,
    /** Color (ARGB Long) of the detection-zone highlight overlay shown while
     *  tuning the zone on the customization sub-page. Default = translucent
     *  accent yellow. */
    val zoneHighlightColor: Long = 0x66FFC83A,
    /** Visual style. Default mirrors the DOCK's theme with the gradient stops
     *  FLIPPED (start/end swapped) so the top bar reads as a sibling of the dock
     *  on a fresh install rather than identical. Derived from BarStyle()'s dock
     *  defaults so the two never drift. Defaults only — a user who has customized
     *  the top bar keeps their saved style. */
    val style: BarStyle = BarStyle().run { copy(colorA = colorB, colorB = colorA) },
)

/** Gradient geometry shared by the dock and top bar. */
@Serializable
enum class BarGradientType { LINEAR, RADIAL, CONICAL }

/**
 * Visual styling shared by the dock and the top bar: a custom two-point gradient
 * (colorA → colorB, each full ARGB including its own alpha), a gradient type
 * (linear / radial / conical), and a corner radius. Defaults reproduce the
 * original slate look.
 *
 * Colors are stored as Long ARGB so kotlinx-serialization handles them as plain
 * numbers. 0xAARRGGBB.
 */
@Serializable
data class BarStyle(
    /** Gradient start color (full ARGB). Default ≈ light slate at ~78% alpha. */
    val colorA: Long = 0xC83A4252,
    /** Gradient end color (full ARGB). Default ≈ near-black blue at ~95% alpha. */
    val colorB: Long = 0xF21A1D24,
    val gradientType: BarGradientType = BarGradientType.LINEAR,
    /** Corner radius in dp. Default 18 matches the original pill shape. */
    val cornerRadiusDp: Int = 18,
    /** LINEAR direction in degrees, clockwise, where 0 = top→bottom (the default,
     *  reproducing the original vertical gradient), 90 = left→right, 180 =
     *  bottom→top, 270 = right→left. Snapped to 45° steps so the Compose surfaces
     *  (dock / folder) and the GradientDrawable top bar — which only supports 8
     *  orientations — stay in lockstep. Ignored by RADIAL / CONICAL. */
    val angleDeg: Int = 0,
    /** Color balance: where the 50% A↔B blend sits, as a percent (5–95). 50 =
     *  even, identical to the original two-stop gradient. Lower shifts the
     *  transition earlier (B dominates); higher shifts it later (A dominates). */
    val midpointPct: Int = 50,
    /** RADIAL / CONICAL focal point, as a percent of width/height. 50/50 = the
     *  box center (the default). Ignored by LINEAR. */
    val centerXPct: Int = 50,
    val centerYPct: Int = 50,
    /** RADIAL spread: gradient radius as a percent of the box's short side. 50 =
     *  half the short side (the original default radius); higher = broader wash,
     *  lower = a tighter hot center. Ignored by LINEAR / CONICAL. */
    val radiusPct: Int = 50,
) {
    fun colorAInt(): Int = colorA.toInt()
    fun colorBInt(): Int = colorB.toInt()

    /** The two stops as an ARGB IntArray (A, B) for GradientDrawable. */
    fun stopColors(): IntArray = intArrayOf(colorA.toInt(), colorB.toInt())

    /** Midpoint as a 0..1 fraction, clamped to a sane range. */
    fun midpointFrac(): Float = midpointPct.coerceIn(5, 95) / 100f

    /** Focal point as 0..1 fractions of the box (RADIAL / CONICAL). */
    fun centerXFrac(): Float = centerXPct.coerceIn(0, 100) / 100f
    fun centerYFrac(): Float = centerYPct.coerceIn(0, 100) / 100f

    /** RADIAL spread as a 0..1+ fraction of the short side (0.5 = original). */
    fun radiusFrac(): Float = radiusPct.coerceIn(10, 150) / 100f

    /** True when the balance is even (no midpoint bias) — i.e. a plain 2-stop. */
    fun isEvenBalance(): Boolean = midpointFrac() == 0.5f

    /** Blend of A and B at 50%, in ARGB Int — the color placed at the midpoint
     *  when the balance is biased (so the transition crosses 50% at [midpointFrac]). */
    fun midColorInt(): Int {
        val a = colorAInt(); val b = colorBInt()
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + ((b shr shift) and 0xFF)) / 2
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** GradientDrawable colors honoring the balance: 2 stops when even, else a
     *  3-stop [A, mid, B] paired with [drawableOffsets]. */
    fun drawableColors(): IntArray =
        if (isEvenBalance()) intArrayOf(colorAInt(), colorBInt())
        else intArrayOf(colorAInt(), midColorInt(), colorBInt())

    /** Offsets to pair with [drawableColors] via GradientDrawable.setColors(c, o);
     *  null when even (let the constructor space them). */
    fun drawableOffsets(): FloatArray? =
        if (isEvenBalance()) null else floatArrayOf(0f, midpointFrac(), 1f)

    /** GradientDrawable orientation for the LINEAR [angleDeg] (snapped to 45°). */
    fun drawableOrientation(): android.graphics.drawable.GradientDrawable.Orientation {
        // Indexed by the 45° step (0..7). An enum class can't be aliased to a val,
        // so reference the constants directly via an ordered array.
        val orientations = arrayOf(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            android.graphics.drawable.GradientDrawable.Orientation.BL_TR,
            android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
            android.graphics.drawable.GradientDrawable.Orientation.BR_TL,
            android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT,
            android.graphics.drawable.GradientDrawable.Orientation.TR_BL,
        )
        return orientations[(((angleDeg % 360) + 360) % 360) / 45]
    }
}

/** Maps a [BarStyle.gradientType] to the matching Android GradientDrawable type. */
fun BarGradientType.toGradientDrawableType(): Int = when (this) {
    BarGradientType.LINEAR -> android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT
    BarGradientType.RADIAL -> android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
    BarGradientType.CONICAL -> android.graphics.drawable.GradientDrawable.SWEEP_GRADIENT
}

/**
 * Global styling for folder windows (the popup shown when a dock folder opens) —
 * applies to ALL folder windows. [background] is a two-point gradient (same model
 * as the bars); [labelColor] is the app-name text color inside the window.
 * Defaults reproduce the original flat-surface look closely.
 */
@Serializable
enum class FolderGridAlign { LEFT, CENTER }

@Serializable
enum class FolderWindowPosition { LEFT, CENTER, RIGHT }

@Serializable
enum class FolderWindowVPosition { TOP, CENTER, BOTTOM }

@Serializable
data class FolderWindowConfig(
    /** Default mirrors the DOCK's color theme (so folder windows match the dock on
     *  a fresh install), keeping the folder's larger 28dp corner radius. Uses
     *  BarStyle()'s dock default colors so the two never drift. Defaults only — a
     *  user who has customized the folder style keeps their saved value. */
    val background: BarStyle = BarStyle(cornerRadiusDp = 28),
    /** App-label text color inside the folder window (ARGB Long). Default ≈ the
     *  original muted on-surface tone. */
    val labelColor: Long = 0xFFB9B2C7,
    /** Apps per row (1–12). A fixed grid with this many columns guarantees the
     *  tiles never overlap; the window width follows from it. */
    val columns: Int = 8,
    /** Rows visible before the grid scrolls (1–12). The window height follows. */
    val maxRows: Int = 6,
    /** Icon tile size in dp inside the folder grid. */
    val tileSizeDp: Int = 64,
    /** How icons arrange within the window — affects a partial last row. */
    val gridAlign: FolderGridAlign = FolderGridAlign.CENTER,
    /** Show the app name under each icon. Off = denser icon-only grid. */
    val showLabels: Boolean = true,
    /** App-label text size (sp) under each icon. */
    val labelSizeSp: Int = 13,
    /** Where the folder window sits horizontally on the display. */
    val position: FolderWindowPosition = FolderWindowPosition.CENTER,
    /** Where the folder window sits vertically on the display. Default CENTER
     *  preserves the original always-vertically-centered placement; existing
     *  saved configs (which lack this field) decode to CENTER too. */
    val vPosition: FolderWindowVPosition = FolderWindowVPosition.CENTER,
)

/** Soft cap on the number of TOP-LEVEL dock items (apps nested in folders are
 *  not counted). Past this, the add flow warns instead of adding. */
const val MAX_DOCK_ITEMS = 24
