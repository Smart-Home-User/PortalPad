package com.portalpad.app.service

import com.portalpad.app.data.PreferencesRepository
import com.portalpad.app.data.RunningTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * Single shared poll of the external-display window state, so the dock and the
 * glasses top bar stop each hitting Shizuku/root binder on their own ~2s timers.
 *
 * One enumeration per tick — listExternalTasks() + minimizedTaskIds() +
 * currentlyMaximizedTaskId() — and every consumer derives its own filtered view
 * from the emitted [Snapshot] with NO additional binder calls. The four old polls
 * (3 in DockOverlay, 1 in TopWindowBarOverlay) collapse to one.
 *
 * Polls only while something is subscribed ([SharingStarted.WhileSubscribed]).
 * Cadence is a flat 2s — the dock's wallpaper-showing check wants that
 * responsiveness in both desktop and extend mode — but outside desktop mode only
 * the cheap task enumeration runs (minimized/maximized are skipped). The
 * enumeration runs off the main thread ([Dispatchers.Default]); [RunningTask] is a
 * data class, so identical snapshots dedup at the StateFlow and downstream
 * `.map { }`s don't re-run on no-op ticks.
 */
class WindowMonitor(
    private val freeform: FreeformManager,
    private val prefs: PreferencesRepository,
    scope: CoroutineScope,
) {
    data class Snapshot(
        val tasks: List<RunningTask>,
        val minimizedIds: Set<Int>,
        val maximizedId: Int?,
        // True when ANY user window is currently maximized/display-filling — tracked
        // taskId set OR a package flagged maximized (covers windows maximized via the
        // top bar AND via Android's caption "expand" → convert path, which only flags
        // the package). Drives the Unmaximize enable-gate so it lights whenever a
        // restore would actually do something, not only for top-bar-maximized windows.
        val hasMaximized: Boolean,
        val desktop: Boolean,
    ) {
        companion object {
            val EMPTY = Snapshot(emptyList(), emptySet(), null, false, false)
        }
    }

    val snapshot: StateFlow<Snapshot> = flow {
        while (true) {
            val desktop = runCatching {
                prefs.bool(PreferencesRepository.Keys.DESKTOP_MODE_ENABLED, default = false).first()
            }.getOrDefault(false)
            // tasks are enumerated in BOTH modes — the dock's wallpaper-showing check
            // needs live tasks even outside desktop mode. minimized/maximized only
            // matter to the desktop window controls, so skip those binder calls when
            // desktop is off (matches the old per-flow gating).
            val tasks = runCatching { freeform.listExternalTasks() }.getOrDefault(emptyList())
            val minimized = if (desktop) {
                runCatching { freeform.minimizedTaskIds() }.getOrDefault(emptySet())
            } else emptySet()
            val maximized = if (desktop) {
                runCatching { freeform.currentlyMaximizedTaskId() }.getOrNull()
            } else null
            val hasMax = maximized != null ||
                tasks.any { freeform.isMaximizedPackage(it.packageName) }
            emit(Snapshot(tasks, minimized, maximized, hasMax, desktop))
            delay(2000L)
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), Snapshot.EMPTY)
}
