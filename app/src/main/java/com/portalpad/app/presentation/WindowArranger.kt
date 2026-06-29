package com.portalpad.app.presentation

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.RunningTask
import com.portalpad.app.data.WindowBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared "arrange windows evenly" used by BOTH the external-display top bar and
 * the trackpad's arrange button, so there is a single tested copy. Tiles every
 * open non-launcher freeform window into equal-width columns across the display.
 *
 * Display resolution tries [primaryDisplayId] first (the top bar passes its
 * overlay display; the trackpad passes the injector display), then the injector
 * display, then a cross-display fallback (handles the VD-vs-task display-id
 * mismatch the close/arrange paths already work around). Live metrics are read
 * fresh (a stale cached width left empty space on the right after switching into
 * ultrawide); [floorW] is a lower bound the caller can supply — the top bar
 * passes its cached width as a guard.
 */
object WindowArranger {
    private const val TAG = "WindowArranger"

    fun arrangeEvenly(scope: CoroutineScope, primaryDisplayId: Int, floorW: Int = 0, silent: Boolean = false) {
        val app = PortalPadApp.instance
        val freeform = app.freeform
        val injectorDisplayId = app.injector.displayId
        scope.launch {
            val windows = withContext(Dispatchers.IO) {
                runCatching {
                    fun isLauncher(t: RunningTask) =
                        t.packageName.contains("launcher", ignoreCase = true) ||
                            t.packageName.contains("nexuslauncher", ignoreCase = true)
                    val seen = LinkedHashMap<Int, RunningTask>()
                    // Try the requested display + the injector's display first.
                    val ids = listOf(primaryDisplayId, injectorDisplayId).distinct()
                    for (id in ids) {
                        for (t in freeform.listTasks(id)) if (!isLauncher(t)) seen[t.taskId] = t
                    }
                    // Fallback: per-display lookups didn't find ≥2 windows (VD vs
                    // task display-id mismatch) — enumerate ALL displays.
                    if (seen.size < 2) {
                        for (t in freeform.listTasks(-1)) if (!isLauncher(t)) seen[t.taskId] = t
                    }
                    Log.d(TAG, "arrangeEvenly: enumerated ${seen.size} candidate windows")
                    // Order left-to-right by current left edge (nulls last).
                    seen.values.sortedBy { it.bounds?.left ?: Int.MAX_VALUE }
                }.getOrDefault(emptyList())
            }
            // Resolve the target display once — used for both the empty-state
            // toast and the live metrics below.
            val disp = runCatching {
                val dm = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                dm.getDisplay(primaryDisplayId) ?: dm.getDisplay(injectorDisplayId)
            }.getOrNull()

            val count = windows.size
            if (count < 2) {
                Log.d(TAG, "arrangeEvenly: $count window(s) — nothing to arrange")
                // Feedback on the display being arranged (matches the top bar's
                // wording). The top bar's own requireWindows pre-check blocks
                // before reaching here, so the two never double-toast. Auto
                // callers (e.g. re-tile on a resolution change) pass silent=true
                // so a switch with 0–1 windows doesn't flash a toast.
                if (disp != null && !silent) {
                    runCatching {
                        GlassesToast.show(app, disp, "Need at least 2 windows to arrange")
                    }
                }
                return@launch
            }
            // Live display metrics (fresh read beats the cached width).
            var liveW = 0
            var liveH = 0
            if (disp != null) {
                runCatching {
                    val m = DisplayMetrics().also { disp.getRealMetrics(it) }
                    liveW = m.widthPixels
                    liveH = m.heightPixels
                }
            }
            val w = liveW.coerceAtLeast(floorW).coerceAtLeast(1)
            val h = liveH.coerceAtLeast(1)
            val n = count
            Log.d(TAG, "arrangeEvenly: arranging all $n windows into even columns on ${w}x$h")
            withContext(Dispatchers.IO) {
                windows.forEachIndexed { i, task ->
                    runCatching {
                        freeform.resizeToFreeform(task, WindowBounds.evenColumn(i, n, w, h), w, h)
                    }
                    // Small gap so a burst of resizes doesn't race the window manager.
                    Thread.sleep(60)
                }
            }
        }
    }

    /**
     * Proportionally remap windows from an old canvas size to a new one using
     * bounds CAPTURED BEFORE the resize, keyed by task id. Reading bounds after a
     * shrink is unsafe — the platform may already have clamped windows that no
     * longer fit — so the caller snapshots each window's good bounds first, resizes
     * the canvas, then calls this to apply (captured × ratio). Preserves a custom
     * layout instead of flattening to even columns. Runs synchronously; call from
     * an IO context. Used by the live aspect/resolution switch as the baseline
     * before any exact remembered-layout overlay.
     */
    fun scaleWindowsFromBounds(
        captured: Map<Int, WindowBounds>,
        oldW: Int,
        oldH: Int,
        newW: Int,
        newH: Int,
    ) {
        if (oldW <= 0 || oldH <= 0 || newW <= 0 || newH <= 0) return
        if (oldW == newW && oldH == newH) return
        if (captured.isEmpty()) return
        val freeform = PortalPadApp.instance.freeform
        val sx = newW.toFloat() / oldW
        val sy = newH.toFloat() / oldH
        Log.d(TAG, "scaleWindowsFromBounds: ${captured.size} window(s) ${oldW}x$oldH → ${newW}x$newH")
        for ((taskId, b) in captured) {
            val nl = (b.left * sx).toInt().coerceIn(0, (newW - 1).coerceAtLeast(0))
            val nt = (b.top * sy).toInt().coerceIn(0, (newH - 1).coerceAtLeast(0))
            val nr = (b.right * sx).toInt().coerceIn(nl + 1, newW)
            val nb = (b.bottom * sy).toInt().coerceIn(nt + 1, newH)
            runCatching { freeform.resize(taskId, WindowBounds(nl, nt, nr, nb)) }
            Thread.sleep(60)
        }
    }
}
