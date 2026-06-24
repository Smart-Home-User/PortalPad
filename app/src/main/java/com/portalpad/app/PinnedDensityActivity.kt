package com.portalpad.app

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Base activity that PINS PortalPad's phone-side UI to a consistent layout
 * regardless of the user's Android display-zoom / font-size settings.
 *
 * How it works: we override the configuration so the screen is always treated as
 * [TARGET_WIDTH_DP] logical dp wide (and font scale 1.0). The density is computed
 * from the device's REAL pixel width, so the app fills each screen proportionally
 * — a wider phone gets larger pixels-per-dp, a narrower one smaller — but every
 * layout sees the same 411dp working width it was designed against. This keeps the
 * trackpad / air-mouse / remote interfaces fitting without cutoff or overlap, and
 * stops the user's font setting from inflating text and breaking layouts.
 *
 * IMPORTANT: this is ACTIVITY-scoped on purpose. It is applied only to phone-side
 * UI activities (which extend this class). It is NOT applied to the foreground
 * service's external-display rendering (dock / top bar / cursor), nor to
 * WallpaperActivity (which renders on the external display) — those keep the
 * system / external-display metrics, so the external display is unaffected.
 */
abstract class PinnedDensityActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(pinDensity(newBase))
    }

    /**
     * Opt-in: auto-finish this activity when the external display disconnects, the
     * same way TrackpadActivity does. Call once from onCreate on phone-side surfaces
     * that only make sense while the glasses are connected (the relay, app search,
     * window-management screens). NOT for config/settings screens, which stay useful
     * with no display attached.
     *
     * A phone screen-off briefly drops the display to null before the service rebuilds
     * it (~3s), so we wait out a [graceMs] window before giving up — a real unplug
     * leaves it null and this still fires. [sawDisplay] guards against the initial
     * null at startup. On a genuine loss we bring PortalPad to the front so the user
     * lands somewhere sensible rather than on whatever was behind this screen.
     */
    protected fun finishWhenExternalDisplayGone(graceMs: Long = 5000L) {
        val app = application as? PortalPadApp ?: return
        lifecycleScope.launch {
            var sawDisplay = false
            var finishJob: kotlinx.coroutines.Job? = null
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val watcherScope = this
                app.externalDisplayId.collect { id ->
                    if (id != null) {
                        sawDisplay = true
                        if (finishJob != null) {
                            Log.w(
                                "DIAG-DISCONNECT",
                                "${this@PinnedDensityActivity.javaClass.simpleName}: display back ($id) — cancel pending finish",
                            )
                        }
                        finishJob?.cancel()
                        finishJob = null
                    } else if (sawDisplay && finishJob == null) {
                        Log.w(
                            "DIAG-DISCONNECT",
                            "${this@PinnedDensityActivity.javaClass.simpleName}: display null — arming finish in ${graceMs}ms",
                        )
                        finishJob = watcherScope.launch {
                            kotlinx.coroutines.delay(graceMs)
                            if (app.externalDisplayId.value == null) {
                                Log.w(
                                    "DIAG-DISCONNECT",
                                    "${this@PinnedDensityActivity.javaClass.simpleName}: display gone >${graceMs}ms — finishing + return to PortalPad",
                                )
                                runCatching {
                                    val intent = android.content.Intent(
                                        this@PinnedDensityActivity, MainActivity::class.java,
                                    ).addFlags(
                                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                            android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                                    )
                                    val opts = android.app.ActivityOptions.makeBasic().apply {
                                        runCatching {
                                            launchDisplayId = android.view.Display.DEFAULT_DISPLAY
                                        }
                                    }
                                    startActivity(intent, opts.toBundle())
                                }
                                finish()
                            }
                            finishJob = null
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Logical layout width the app is designed against (typical phone). */
        const val TARGET_WIDTH_DP = 411

        fun pinDensity(base: Context): Context {
            val res = base.resources
            val metrics = res.displayMetrics
            // Use the PHYSICAL display's real width, not res.displayMetrics.width,
            // because the latter reflects the current (possibly transient) window/
            // config state. During a screen-off→on cycle — or the virtual-display
            // churn when Shizuku drops/recovers — the config width can momentarily
            // report a wrong (smaller) value. pinDensity runs in attachBaseContext
            // at (re)creation, so a transient bad width here pins a wrong, smaller
            // density for the WHOLE activity → the remote pill, spinner, and other
            // dp-sized UI all render small until the activity re-measures (which
            // happens to coincide with Shizuku recovering). The hardware display
            // width is stable across these transitions, so derive density from it.
            val realWidthPx = runCatching {
                val wm = base.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                val display = wm?.defaultDisplay
                if (display != null) {
                    val real = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION") display.getRealMetrics(real)
                    real.widthPixels
                } else 0
            }.getOrDefault(0)

            // Prefer the stable hardware width; fall back to the config width only
            // if the real metric is unavailable. Guard against an implausibly small
            // value (mid-transition) so we never pin a tiny density.
            val configWidthPx = metrics.widthPixels
            val widthPx = when {
                realWidthPx >= 320 -> realWidthPx
                configWidthPx >= 320 -> configWidthPx
                else -> return base // both implausible — don't pin a bad density
            }
            // Choose a density so that widthPx / density == TARGET_WIDTH_DP.
            val targetDensity = widthPx.toFloat() / TARGET_WIDTH_DP
            val targetDensityDpi = (targetDensity * 160f).toInt().coerceAtLeast(1)

            val config = Configuration(res.configuration)
            config.densityDpi = targetDensityDpi
            config.fontScale = 1.0f
            return base.createConfigurationContext(config)
        }
    }
}
