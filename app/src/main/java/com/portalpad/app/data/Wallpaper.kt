package com.portalpad.app.data

import com.portalpad.app.R

/** Which canvas a wallpaper is authored for. UNIVERSAL ones (None, Custom) fit
 *  any canvas and show under both categories in the picker. */
enum class WallpaperCategory { STANDARD, ULTRAWIDE, UNIVERSAL }

/**
 * Catalog of home-backdrop wallpapers shown on the EXTERNAL DISPLAY when it
 * would otherwise be empty (no home app assigned, or all apps closed). These are
 * PortalPad-owned images drawn on the virtual display — they never touch the
 * Android system wallpaper.
 *
 * [drawableRes] is null for [NONE], which renders a plain black backdrop (no
 * image, the default empty-display look).
 *
 * [category] splits the picker into Standard (16:9 / 1920x1080) and Ultrawide
 * (21:9 & 32:9, for the glasses' ultrawide modes). The presets are authored for
 * one or the other; a Standard image on a 32:9 canvas (or vice versa) gets
 * cropped, so the category steers the user to one that matches their canvas.
 */
enum class Wallpaper(
    val key: String,
    val drawableRes: Int?,
    val label: String,
    val category: WallpaperCategory,
) {
    NONE("none", null, "None (black)", WallpaperCategory.UNIVERSAL),
    CUSTOM("custom", null, "Custom Image", WallpaperCategory.UNIVERSAL),
    SUBZERO_ORBIT("subzero_orbit", R.drawable.wp_subzero_orbit, "Sub-Zero Orbit", WallpaperCategory.STANDARD),
    DARK_NEBULA_CITADEL("dark_nebula_citadel", R.drawable.wp_dark_nebula_citadel, "Dark Nebula Citadel", WallpaperCategory.STANDARD),
    DEEP_GLIMMER_CATHEDRAL("deep_glimmer_cathedral", R.drawable.wp_deep_glimmer_cathedral, "Deep Glimmer Cathedral", WallpaperCategory.STANDARD),
    EARTHS_LIVING_CANVAS("earths_living_canvas", R.drawable.wp_earths_living_canvas, "Earth's Living Canvas", WallpaperCategory.STANDARD),
    OCEAN_CARVED_MAJESTY("ocean_carved_majesty", R.drawable.wp_ocean_carved_majesty, "Ocean Carved Majesty", WallpaperCategory.STANDARD),
    TRANQUIL_ALPINE_DAWN("tranquil_alpine_dawn", R.drawable.wp_tranquil_alpine_dawn, "Tranquil Alpine Dawn", WallpaperCategory.STANDARD),
    UNIVERSE_IN_BLOOM("universe_in_bloom", R.drawable.wp_universe_in_bloom, "Universe In Bloom", WallpaperCategory.STANDARD),
    VERTICAL_GLASS_CANYON("vertical_glass_canyon", R.drawable.wp_vertical_glass_canyon, "Vertical Glass Canyon", WallpaperCategory.STANDARD),
    COSMIC_GENESIS("cosmic_genesis", R.drawable.wp_cosmic_genesis, "Cosmic Genesis", WallpaperCategory.ULTRAWIDE),
    EDGE_OF_ETERNITY("edge_of_eternity", R.drawable.wp_edge_of_eternity, "Edge of Eternity", WallpaperCategory.ULTRAWIDE),
    ;

    companion object {
        /** Default backdrop on a standard (16:9) canvas. */
        val DEFAULT = SUBZERO_ORBIT

        /** Default backdrop on an ultrawide canvas — a native ultrawide image so a
         *  fresh 32:9 setup doesn't open on a cropped 16:9 default. */
        val ULTRAWIDE_DEFAULT = COSMIC_GENESIS

        /** The default to use for the given canvas when the user hasn't picked one. */
        fun effectiveDefault(ultrawide: Boolean): Wallpaper =
            if (ultrawide) ULTRAWIDE_DEFAULT else DEFAULT

        fun byKey(key: String?): Wallpaper =
            entries.firstOrNull { it.key == key } ?: DEFAULT

        /** True when w:h is meaningfully wider than 16:9 (21:9, 32:9, …). Uses the
         *  long/short ratio so it's rotation-agnostic. 16:9≈1.78, 21:9≈2.33. */
        fun isUltrawideCanvas(w: Int, h: Int): Boolean {
            if (w <= 0 || h <= 0) return false
            val long = maxOf(w, h).toFloat()
            val short = minOf(w, h).coerceAtLeast(1).toFloat()
            return long / short >= 2.0f
        }
    }
}
