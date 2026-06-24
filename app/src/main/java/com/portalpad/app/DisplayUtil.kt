package com.portalpad.app

import android.view.Display

/**
 * Single source of truth for "is this a REAL external display?" — shared by
 * [MainActivity] (the Enable / launch-trackpad gate) and the foreground
 * service's display reconcile, so the two can't drift apart.
 *
 * "Real external" means a physical display we should extend onto: HDMI,
 * DisplayPort, USB-C DisplayPort (XREAL / Viture / etc.), or an AR-glasses
 * OVERLAY display. It deliberately EXCLUDES:
 *   - the phone screen (DEFAULT_DISPLAY)
 *   - app-created virtual displays: our own session VD, scrcpy `--new-display`,
 *     MediaProjection / screen-mirror, Chromecast, Android Auto, and the
 *     developer "Simulate secondary displays" VD
 *
 * This exists because the old Enable-path check counted ANY non-default
 * display, so merely plugging the phone into a PC over USB-C (which can spawn
 * a virtual display) was enough to auto-launch the trackpad with no glasses
 * attached.
 */
object DisplayUtil {

    // Display.getType() constants (some are @hide on older APIs, so we reflect):
    //   TYPE_EXTERNAL = 2  ← HDMI / DisplayPort / USB-C DP (real)
    //   TYPE_OVERLAY  = 4  ← XREAL / AR-glasses overlay display (real)
    //   TYPE_VIRTUAL  = 5  ← app-created VDs (NOT real)
    private const val TYPE_EXTERNAL = 2
    private const val TYPE_OVERLAY = 4

    fun isRealExternalDisplay(d: Display): Boolean {
        if (d.displayId == Display.DEFAULT_DISPLAY) return false
        // Belt-and-suspenders: never treat our own session VD as external even
        // if its type were ever misreported.
        if (d.name == "PortalPad Session") return false

        val type = runCatching {
            Display::class.java.getMethod("getType").invoke(d) as? Int
        }.getOrNull()

        if (type != null) {
            // Authoritative path on essentially all real devices.
            return type == TYPE_EXTERNAL || type == TYPE_OVERLAY
        }

        // Reflection unavailable (unexpected future Android): fall back to a
        // name heuristic. Reject anything that looks app-created/virtual; accept
        // otherwise (a real display we couldn't type-check shouldn't be dropped).
        val n = d.name.lowercase()
        val looksVirtual = n.contains("ghost") ||
            n.contains("virtual") ||
            n.contains("projection") ||
            n.contains("componentinfo") ||
            n.contains("scrcpy") ||
            n.contains("simulate") ||
            n.contains("overlay#")
        return !looksVirtual
    }
}
