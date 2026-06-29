package com.portalpad.app.data

/**
 * A single running task on the external (desktop-windows) display, parsed from
 * `dumpsys activity` / `am stack list` output by
 * [com.portalpad.app.service.FreeformManager].
 *
 * Used by the taskbar and the top window-bar overlays to show what's running
 * and to drive focus / move / resize / close operations.
 *
 * @param taskId     the system task id (passed to `am task` commands)
 * @param packageName the owning package (for icon + label lookup)
 * @param topActivity the flattened component currently on top of the task,
 *                    if known (e.g. "com.android.chrome/.Main"). Null when we
 *                    could only parse the package.
 * @param displayId  the display the task is currently on
 * @param bounds     current window bounds in display pixels, or null if the
 *                    task is fullscreen / bounds were not reported
 * @param visible    whether the task is currently visible (vs. minimized to
 *                    the taskbar via move-to-back)
 */
data class RunningTask(
    val taskId: Int,
    val packageName: String,
    val topActivity: String? = null,
    val displayId: Int = -1,
    val bounds: WindowBounds? = null,
    val visible: Boolean = true,
) {
    /** A stable key for Compose/diffing — taskId is unique per task. */
    val key: String get() = "task_$taskId"
}

/** Window rectangle in display pixels. */
data class WindowBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    companion object {
        /**
         * A cascade position for the Nth freeform window opened on a display of
         * the given size. Each new window is offset down-right from the last so
         * they don't stack exactly on top of each other (DeX-like behavior).
         */
        fun cascade(index: Int, displayW: Int, displayH: Int): WindowBounds {
            // Window is ~62% of the display, offset by a step per index, clamped
            // so it never runs off the bottom-right.
            val w = (displayW * 0.62f).toInt().coerceAtLeast(480)
            val h = (displayH * 0.62f).toInt().coerceAtLeast(360)
            val step = (displayW * 0.04f).toInt().coerceAtLeast(32)
            val maxLeft = (displayW - w).coerceAtLeast(0)
            val maxTop = (displayH - h).coerceAtLeast(0)
            val left = ((index % 5) * step).coerceIn(0, maxLeft)
            val top = ((index % 5) * step).coerceIn(0, maxTop)
            return WindowBounds(left, top, left + w, top + h)
        }

        /** Maximize to (nearly) the full display. */
        fun maximized(displayW: Int, displayH: Int) =
            WindowBounds(0, 0, displayW, displayH)

        /**
         * The [index]-th of [count] equal-width columns spanning the display —
         * used by the top-bar "Arrange evenly" action to lay out all open
         * windows side by side (2 -> halves, 3 -> thirds, ... up to 6). Integer
         * math distributes any leftover pixels so the columns tile gap-free
         * across the full width.
         */
        fun evenColumn(index: Int, count: Int, displayW: Int, displayH: Int): WindowBounds {
            val n = count.coerceIn(1, 8)
            val i = index.coerceIn(0, n - 1)
            val left = (displayW.toLong() * i / n).toInt()
            val right = (displayW.toLong() * (i + 1) / n).toInt()
            return WindowBounds(left, 0, right, displayH)
        }

        /**
         * "Restore" — a sensible centered floating window (~62% of the display),
         * the un-maximized / un-tiled default. Mirrors the cascade base size but
         * centered rather than offset, so a maximized or half-tiled window snaps
         * back to a normal windowed size in the middle of the screen.
         */
        fun restored(displayW: Int, displayH: Int): WindowBounds {
            val w = (displayW * 0.62f).toInt().coerceAtLeast(480)
            val h = (displayH * 0.62f).toInt().coerceAtLeast(360)
            val left = ((displayW - w) / 2).coerceAtLeast(0)
            val top = ((displayH - h) / 2).coerceAtLeast(0)
            return WindowBounds(left, top, left + w, top + h)
        }
    }
}

/**
 * A single window remembered in a saved session: which app (flattened component
 * for relaunch, plus package for icon/label) and where it sat (display pixels).
 */
@kotlinx.serialization.Serializable
data class SavedWindow(
    val packageName: String,
    val component: String? = null,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val bounds: WindowBounds get() = WindowBounds(left, top, right, bottom)
}

/**
 * A snapshot of the apps open on the external display, so the user can resume
 * their workspace after an accidental disconnect or disabling the trackpad.
 * Captures apps + positions only — Android destroys the activities when the VD
 * is released, so in-app state can't be preserved (relaunch + retile is the
 * best achievable). [savedAtMillis] lets the UI show "X minutes ago".
 */
@kotlinx.serialization.Serializable
data class SavedSession(
    val windows: List<SavedWindow> = emptyList(),
    val savedAtMillis: Long = 0L,
    // Canvas size (px) the windows were captured at. 0 = unknown (older saves).
    // Restore compares width to the live canvas: if they differ, the saved
    // absolute bounds won't fit, so the windows are rescaled proportionally.
    val canvasWidth: Int = 0,
    val canvasHeight: Int = 0,
)

/**
 * Per-resolution layout memory: one [SavedSession] kept per canvas width, so each
 * display size (standard 1920, ultrawide 3840, 21:9 2520, …) remembers its own
 * window layout independently — like a desktop restoring each monitor's windows.
 * On landing at a width with a remembered layout we restore it exactly; a width
 * never seen before is seeded by scaling the most-recent layout.
 */
@kotlinx.serialization.Serializable
data class SavedSessions(
    val byWidth: Map<Int, SavedSession> = emptyMap(),
)
