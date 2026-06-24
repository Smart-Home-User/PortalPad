package com.portalpad.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.painterResource
import com.portalpad.app.data.Wallpaper
import kotlinx.coroutines.flow.first

/**
 * Full-screen "home backdrop" shown on the EXTERNAL DISPLAY when it would
 * otherwise be empty (no home app assigned, or all apps closed). Launched onto
 * the virtual display via `am start --display <vdId>` so it behaves like the
 * display's home/launcher — real apps launched later simply cover it.
 *
 * This is a normal PortalPad activity drawing a PortalPad-owned image; it does
 * NOT touch the Android system wallpaper in any way.
 */
class WallpaperActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        // Safety guard: this backdrop is only meant for the EXTERNAL display.
        // If Android ever relocates it to the phone's default display (e.g. when
        // the glasses disconnect and the system migrates the orphaned task), it
        // would hijack the phone screen. Refuse to run there — finish instead.
        if (isOnDefaultDisplay()) { finish(); return }
        val app = applicationContext as PortalPadApp
        // Read the selected wallpaper + custom path SYNCHRONOUSLY up front so the
        // very first composition already knows what to draw. Without this, the two
        // prefs flows resolve independently and there's a startup window where
        // `wallpaper` is CUSTOM but `customPath` is still null — and because CUSTOM
        // has no drawable fallback (unlike the presets), it falls through to black
        // and may not repaint on the VD until something re-launches it. Seeding the
        // initial values removes that race. (Presets never hit it: their drawable
        // paints immediately.) One-shot DataStore reads, same pattern the service
        // uses for homeAction.
        // Canvas-aware default: on an ultrawide canvas a fresh setup (no explicit
        // pick) should open on a native ultrawide image, not the cropped standard
        // default. Classify the canvas from the display's real size.
        val canvasUltrawide = runCatching {
            val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") display?.getRealMetrics(it) }
            Wallpaper.isUltrawideCanvas(m.widthPixels, m.heightPixels)
        }.getOrDefault(false)
        val initialRaw: Wallpaper? = runCatching {
            kotlinx.coroutines.runBlocking { app.prefs.homeWallpaperRaw(canvasUltrawide).first() }
        }.getOrNull()
        val initialCustomPath = runCatching {
            kotlinx.coroutines.runBlocking { app.prefs.customWallpaperPath.first() }
        }.getOrNull()
        setContent {
            val raw by app.prefs.homeWallpaperRaw(canvasUltrawide).collectAsState(initial = initialRaw)
            val customPath by app.prefs.customWallpaperPath.collectAsState(initial = initialCustomPath)
            val wallpaper = raw ?: Wallpaper.effectiveDefault(canvasUltrawide)
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                val res = wallpaper.drawableRes
                when {
                    wallpaper == Wallpaper.CUSTOM && customPath != null -> {
                        val bmp = remember(customPath) {
                            runCatching { android.graphics.BitmapFactory.decodeFile(customPath) }.getOrNull()
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    res != null -> {
                        Image(
                            painter = painterResource(res),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    // NONE (or CUSTOM with no path) → black background above.
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check on resume: if the task was migrated to the phone display while
        // backgrounded (display removed), bail before the user sees it.
        if (isOnDefaultDisplay()) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    private fun isOnDefaultDisplay(): Boolean {
        val displayId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching { display?.displayId }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching { windowManager.defaultDisplay?.displayId }.getOrNull()
        }
        return displayId == android.view.Display.DEFAULT_DISPLAY
    }

    companion object {
        @Volatile private var instance: WallpaperActivity? = null

        /** Finish the backdrop activity if it's currently showing. Called on
         *  external-display disconnect so the orphaned task can't migrate to the
         *  phone screen. */
        fun finishIfShowing() {
            instance?.let { act -> act.runOnUiThread { runCatching { act.finish() } } }
            instance = null
        }
    }
}
