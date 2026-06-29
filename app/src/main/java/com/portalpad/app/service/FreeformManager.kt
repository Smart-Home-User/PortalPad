package com.portalpad.app.service

import android.util.Log
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.RunningTask
import com.portalpad.app.data.WindowBounds
import kotlinx.coroutines.flow.first

/**
 * Desktop-windows ("DeX-style") operations, all driven through the existing
 * elevated-shell channel ([ElevatedAccess.execShell]) — the same channel the
 * dock already uses to launch apps. Nothing here touches the trusted-VD / IME
 * pipeline; it only issues `settings`, `am`, and `wm` shell commands against
 * the display the caller passes in.
 *
 * PHASE 1 SCOPE (intentionally limited — see the desktop-windows plan):
 *   - enable / disable freeform support (a global setting)
 *   - launch an app into a freeform window with explicit launch bounds
 *   - list running tasks on a display
 *   - move / resize a task's window
 *   - minimize (move-to-back) and close (finish) a task
 *
 * What is deliberately NOT here yet (phase 2): live drag-to-move / drag-to-
 * resize chrome, per-app multi-instance, and persistence of window layout.
 *
 * Robustness notes:
 *   - `am` syntax has drifted across Android versions. We try the modern form
 *     first and fall back to older spellings, treating any non-"Error:" output
 *     as success (same heuristic ShizukuManager.startActivityOnDisplay uses).
 *   - Task-list parsing is best-effort: OEM dumpsys output varies, so we parse
 *     defensively and skip rows we can't understand rather than throwing.
 */
class FreeformManager(private val accessProvider: () -> ElevatedAccess) {

    private val access: ElevatedAccess get() = accessProvider()

    /** Monotonic counter for cascade placement of freeform windows. */
    @Volatile private var launchIndex = 0

    /**
     * Task ids the user minimized via PortalPad's window controls. We track
     * this ourselves because Android gives no reliable "is minimized" flag, and
     * on some OEMs we can't even filter tasks by display id (the reported id
     * doesn't match our virtual display). The dock shows restore tiles ONLY for
     * tasks in this set — so it shows exactly what the user minimized, not every
     * running/background app. Restoring or closing a task removes it.
     */
    private val minimizedTasks = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /** Pre-minimize window bounds, so restore returns a parked window exactly
     *  where it was. Keyed by taskId; populated on minimize, consumed on restore. */
    private val savedBounds = java.util.concurrent.ConcurrentHashMap<Int, WindowBounds>()

    /** Snapshot of currently-minimized task ids (set by PortalPad). */
    fun minimizedTaskIds(): Set<Int> = synchronized(minimizedTasks) { minimizedTasks.toSet() }

    fun markMinimized(taskId: Int) { minimizedTasks.add(taskId) }
    fun clearMinimized(taskId: Int) { minimizedTasks.remove(taskId) }

    /** Windows "minimized" via the CLOSE model: the task is removed from the
     *  display (no off-screen sliver) and we remember its package + bounds here
     *  so the dock can relaunch it on demand. Survives the task's death (unlike
     *  taskId-keyed state) — keyed by our own synthetic, stable id. */
    data class ClosedWindow(val id: Int, val packageName: String, val bounds: WindowBounds?)
    private val closedWindows = java.util.concurrent.ConcurrentHashMap<Int, ClosedWindow>()
    // Synthetic ids for close-minimized windows start at 1_000_000 so they never
    // collide with REAL Android task ids (which stay well below that). The dock's
    // restore/close handlers use isClosedId() to branch: a close-model id relaunches
    // / forgets, a real id (a caption-bar-minimized live window) brings-to-front /
    // closes the actual task. (closedWindows is in-memory, so changing the base is
    // safe — no persisted ids to migrate.)
    private val closedIdSeq = java.util.concurrent.atomic.AtomicInteger(1_000_000)

    /** True if [id] is a close-minimize registry id (vs. a real live task id). */
    fun isClosedId(id: Int): Boolean = id >= 1_000_000

    /** Snapshot of close-minimized windows, oldest first (for the dock menu). */
    fun closedMinimizedList(): List<ClosedWindow> = closedWindows.values.sortedBy { it.id }

    /** Drop a close-minimized entry without relaunching — the dock menu's ✕. The
     *  window is already closed, so this just forgets it. */
    fun forgetClosed(id: Int) { closedWindows.remove(id) }

    val isReady: Boolean get() = runCatching { access.isReady }.getOrDefault(false)

    /** Next cascade index (wraps via the modulo in WindowBounds.cascade). */
    fun nextLaunchIndex(): Int = launchIndex++

    /**
     * Turn on the platform's freeform/multi-window support. Requires
     * WRITE_SECURE_SETTINGS (already declared + granted via the elevated
     * backend). Safe to call repeatedly. Returns true if both writes appeared
     * to succeed.
     *
     * `enable_freeform_support` is the gate for freeform windowing mode;
     * `force_resizable_activities` makes apps that declare
     * resizeableActivity=false still open in a window (many do; without this
     * they'd silently launch fullscreen).
     */
    // The user's enable_freeform_support value captured BEFORE PortalPad first
    // turned it on this process — so we can restore their own choice (they may have
    // set it via Developer Options). Null = we haven't enabled freeform this process.
    // force_resizable_activities is deliberately NOT remembered: we own it outright,
    // since its only purpose is windowing and leaving it on system-wide crashes
    // SystemUI's split-screen coordinator on unrelated app launches.
    @Volatile private var priorEnableFreeform: String? = null

    fun enableFreeform(): Boolean = runCatching {
        if (!isReady) return false
        // Capture the user's prior enable_freeform_support exactly once, before we
        // change it, so disableFreeform can put it back to what they had.
        if (priorEnableFreeform == null) {
            priorEnableFreeform = runCatching {
                access.execShell("settings get global enable_freeform_support").trim()
            }.getOrDefault("0").let { if (it == "1") "1" else "0" }
        }
        val o1 = access.execShell("settings put global enable_freeform_support 1")
        val o2 = access.execShell("settings put global force_resizable_activities 1")
        // Some ROMs also gate on this development setting; harmless if absent.
        runCatching { access.execShell("settings put global force_resizable 1") }
        Log.d(TAG, "enableFreeform: o1=$o1 o2=$o2 prior=$priorEnableFreeform")
        !o1.containsError() && !o2.containsError()
    }.getOrElse {
        Log.e(TAG, "enableFreeform failed", it); false
    }

    /**
     * Revert [enableFreeform]. force_resizable_activities is forced OFF — PortalPad
     * owns it, and leaving it on globally crashes SystemUI's split-screen
     * coordinator on later app launches (random black-screen). enable_freeform_
     * support is restored to the user's prior value, but ONLY if we actually turned
     * it on this process; otherwise their setting (e.g. Developer Options) is left
     * untouched. Safe and idempotent.
     */
    fun disableFreeform() {
        if (!isReady) return
        runCatching {
            access.execShell("settings put global force_resizable_activities 0")
            runCatching { access.execShell("settings put global force_resizable 0") }
            val prior = priorEnableFreeform
            if (prior != null) {
                access.execShell("settings put global enable_freeform_support $prior")
                priorEnableFreeform = null
            }
            Log.d(TAG, "disableFreeform: force_resizable->0; enable_freeform=${prior ?: "untouched"}")
        }
    }

    /**
     * Launch [component] (a flattened "pkg/.Activity" string) into a FREEFORM
     * window with the given [bounds] on [displayId].
     *
     * `am start` exposes freeform via `--windowingMode 5` (WINDOWING_MODE_
     * FREEFORM) on modern Android. Combined with `am task resize` immediately
     * afterward we get a window placed at our chosen bounds. We can't always
     * pass launch bounds directly through `am start` across versions, so we
     * launch then resize the resulting task — the established two-step.
     */
    fun launchFreeform(
        component: String,
        displayId: Int,
        bounds: WindowBounds,
        forceNewTask: Boolean = false,
    ): Boolean = runCatching {
        if (!isReady) return false
        // Enforce the max-window cap at this single chokepoint so EVERY launch
        // path (taskbar, dock, app drawer, search) is covered — previously only
        // the taskbar checked, so dock/drawer launches sailed past the limit.
        // Count current non-launcher windows robustly (tasks are often under a
        // different display id than the VD, so a per-display count reads 0).
        val maxWindows = runCatching {
            kotlinx.coroutines.runBlocking {
                PortalPadApp.instance.prefs.int(
                    com.portalpad.app.data.PreferencesRepository.Keys.MAX_WINDOWS, 5,
                ).first()
            }
        }.getOrDefault(5).coerceIn(1, 8)
        val openCount = countExternalWindows()
        // A flap recovery is RE-opening windows the user already had — never block
        // it on the window cap. Without this, a transient extra during recovery
        // (e.g. a duplicate same-app window briefly in flight) could push the count
        // to the cap and knock out a DIFFERENT app's relaunch (observed: YouTube
        // dropped while a 3rd Chrome was momentarily live). The per-app cap-of-2
        // collector trims any genuine excess once recovery finishes.
        val recovering = runCatching { PortalPadApp.instance.isFlapRecovering() }.getOrDefault(false)
        Log.d(TAG, "launchFreeform: openCount=$openCount maxWindows=$maxWindows recovering=$recovering for $component")
        if (!recovering && openCount >= maxWindows) {
            Log.d(TAG, "launchFreeform BLOCKED: $openCount/$maxWindows windows open (cap reached) — $component")
            return false
        }
        // When restoring a SECOND window of an app that's already live, a plain
        // launch just refocuses the existing window. Setting FLAG_ACTIVITY_NEW_TASK
        // (0x10000000) | FLAG_ACTIVITY_MULTIPLE_TASK (0x08000000) = 0x18000000 forces
        // a distinct task so the duplicate window actually appears. NOTE: pass the
        // numeric `-f` flags — this platform's `am` rejects the named
        // `--activity-new-task` option. (This is the same flag combo Android itself
        // uses when you open a 2nd window normally.) The recreated window is blank —
        // the original was destroyed by the display re-enumeration and can't be
        // recovered — but the layout slot is filled instead of left empty.
        val newTaskFlags = if (forceNewTask) " -f 0x18000000" else ""
        // WINDOWING_MODE_FREEFORM = 5.
        val out = access.execShell(
            "am start --display $displayId --windowingMode 5$newTaskFlags " +
                "-a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER " +
                "-n '$component'"
        )
        Log.d(TAG, "launchFreeform $component disp=$displayId forceNewTask=$forceNewTask → $out")
        if (out.containsError()) {
            if (forceNewTask) {
                // A forced-new-task launch that errors must NOT silently fall back to
                // a plain launch — that would just refocus the existing window and
                // mask the failure (the duplicate we wanted never appears). Surface it.
                Log.w(TAG, "launchFreeform forceNewTask FAILED for $component (no flagless fallback) → $out")
                return false
            }
            // Fallback: plain launch (lands fullscreen), then move into freeform
            // by task id below. Better a fullscreen window than nothing.
            access.startActivityOnDisplay(component, displayId)
        }
        // Give the task a beat to appear, then place it at our bounds.
        Thread.sleep(LAUNCH_SETTLE_MS)
        val task = listTasks(displayId).firstOrNull { it.packageName == packageOf(component) }
        if (task != null) {
            setWindowingModeFreeform(task.taskId)
            resize(task.taskId, bounds)
        }
        PortalPadApp.instance.signalWindowsChanged()
        true
    }.getOrElse {
        Log.e(TAG, "launchFreeform failed", it); false
    }

    /** Force a task into freeform windowing mode (mode id 5). */
    fun setWindowingModeFreeform(taskId: Int) {
        runCatching {
            // Newer: `am task ... `; widely available: move task to a freeform
            // stack. Try the modern spelling first.
            val out = access.execShell("am task $taskId set-windowing-mode 5")
            if (out.containsError() || out.contains("Unknown", ignoreCase = true)) {
                // Older devices: move the task into the freeform stack id.
                access.execShell("am stack move-task $taskId 2 true")
            }
        }
    }

    /** Switch a task to TRUE fullscreen windowing mode (mode 1). Unlike a
     *  freeform window resized to fill the screen, a fullscreen task is a
     *  different window layer the system draws OVER freeform windows — so it
     *  cleanly occludes other windows' caption bars (which Samsung otherwise
     *  keeps on-screen). This is the basis of the "maximize" action. */
    fun setWindowingModeFullscreen(taskId: Int) {
        runCatching {
            val out = access.execShell("am task $taskId set-windowing-mode 1")
            if (out.containsError() || out.contains("Unknown", ignoreCase = true)) {
                // Older devices: fullscreen stack id is 1.
                access.execShell("am stack move-task $taskId 1 true")
            }
        }
    }

    // ── Exclusive-maximize state ──────────────────────────────────────────
    // Which task is currently maximized (fullscreen), and the freeform bounds
    // it had before — so the NEXT maximize can demote it back to a normal
    // freeform window at its old size/position. Only one window is ever
    // fullscreen at a time; everything else stays freeform and re-pickable.
    @Volatile private var maximizedTaskId: Int? = null
    private val preMaximizeBounds = java.util.concurrent.ConcurrentHashMap<Int, WindowBounds>()

    fun currentlyMaximizedTaskId(): Int? = maximizedTaskId

    /** Maximize (keeps the freeform caption bar): front the chosen window via
     *  am start + resize it to fill the display. This is the reliable, only
     *  maximize path now that the experimental bar-free variant was removed. */
    fun maximizeByResize(
        taskId: Int,
        component: String?,
        packageName: String,
        current: WindowBounds?,
        displayId: Int,
        displayW: Int,
        displayH: Int,
    ) {
        if (!isReady) return
        // 1) Shrink the previously-maximized window back to its old bounds, so we
        //    don't leave two full-size windows stacked.
        val prev = maximizedTaskId
        if (prev != null && prev != taskId) {
            preMaximizeBounds[prev]?.let { runCatching { resize(prev, it) } }
            preMaximizeBounds.remove(prev)
        }
        // 2) Remember the chosen window's bounds for its own future restore.
        if (current != null) preMaximizeBounds[taskId] = current
        // 3) Front the chosen window via am start — the one fronting primitive
        //    proven on-device. Brings the existing task above the others.
        val started = runCatching {
            if (component != null) {
                val out = access.execShell(
                    "am start --display $displayId " +
                        "-a android.intent.action.MAIN " +
                        "-c android.intent.category.LAUNCHER " +
                        "-n '$component'"
                )
                !out.containsError()
            } else false
        }.getOrDefault(false)
        if (!started) runCatching { access.startActivityOnDisplay(packageName, displayId) }
        // 4) Resize it to fill the display (proven). Now it's on top AND full.
        if (displayW > 0 && displayH > 0) {
            Thread.sleep(120) // let the front settle before resizing
            runCatching { resize(taskId, WindowBounds.maximized(displayW, displayH)) }
        }
        maximizedTaskId = taskId
        clearMinimized(taskId)
        PortalPadApp.instance.signalWindowsChanged()
    }

    fun resize(taskId: Int, bounds: WindowBounds) {
        runCatching {
            access.resizeTask(taskId, bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
        PortalPadApp.instance.signalWindowsChanged()
    }

    /**
     * Like [resize], but first forces the task into freeform windowing mode. A
     * window maximized through Android's caption bar (a system action PortalPad
     * doesn't track) is in FULLSCREEN mode, and a plain `am task resize` on a
     * fullscreen-mode task is ignored — the window stays maximized. Demoting it
     * to freeform first makes the resize apply. Used by the arrange/stack actions
     * so a caption-bar-maximized window participates instead of sitting maximized
     * in the background. No-op cost on a window that's already freeform (the
     * set-windowing-mode call just confirms the current mode).
     */
    /**
     * Resize a window into [bounds] as part of an arrange/stack, handling a
     * window that was maximized through Android's caption bar.
     *
     * Such a window is in a system fullscreen state that silently ignores
     * `am task resize` (confirmed on-device: the bounds don't change). The in-
     * place windowing-mode commands don't work on every device either. The one
     * primitive that reliably produces a freeform window here is the launch path
     * (`am start --windowingMode 5`), so when a window resists the resize we
     * relaunch it into freeform and resize again.
     *
     * To keep the common case fast and side-effect-free, we only take that path
     * for a window that ENTERED the arrange already filling the display (the only
     * kind that resists) AND only after a plain resize provably failed — so a
     * normal freeform window is never relaunched, and we never relaunch when the
     * intended result is itself full-screen.
     */
    fun resizeToFreeform(task: RunningTask, bounds: WindowBounds, displayW: Int, displayH: Int) {
        // Plain resize first — works for every normal freeform window.
        resize(task.taskId, bounds)

        if (displayW <= 0 || displayH <= 0) return
        fun fills(b: WindowBounds?) =
            b != null && b.width >= displayW * 0.92f && b.height >= displayH * 0.92f
        // Only a window that started maximized can resist; and never relaunch if
        // the target bounds are themselves full-screen (nothing to un-maximize).
        if (!fills(task.bounds) || fills(bounds)) return

        Thread.sleep(80) // let the resize settle before we re-read
        if (!fills(taskBounds(task.taskId))) return // resize took — done

        // Resize was ignored: the window is still maximized. Relaunch it into a
        // freeform window (proven path), then resize into its slot.
        val displayId = task.displayId.takeIf { it >= 0 }
            ?: PortalPadApp.instance.externalDisplayId.value ?: return
        val component = task.topActivity
        runCatching {
            if (component != null) {
                val out = access.execShell(
                    "am start --display $displayId --windowingMode 5 " +
                        "-a android.intent.action.MAIN " +
                        "-c android.intent.category.LAUNCHER " +
                        "-n '$component'"
                )
                if (out.containsError()) access.startActivityOnDisplay(task.packageName, displayId)
            } else {
                access.startActivityOnDisplay(task.packageName, displayId)
            }
        }
        Thread.sleep(LAUNCH_SETTLE_MS)
        // The task id can change after a relaunch — re-find by package.
        val newId = runCatching {
            listTasks(displayId).firstOrNull { it.packageName == task.packageName }?.taskId
        }.getOrNull() ?: task.taskId
        resize(newId, bounds)
    }

    /** Current bounds of [taskId] from a live task enumeration, or null. */
    private fun taskBounds(taskId: Int): WindowBounds? = runCatching {
        listTasks(-1).firstOrNull { it.taskId == taskId }?.bounds
    }.getOrNull()


    /** "Stack windows": gather every open window into a tidy cascade in one
     *  spot — same size, each offset slightly down-right from the last so every
     *  window's edge/caption peeks out (you can see and tap any of them). Uses
     *  only resize (proven on-device); no z-order tricks needed because the
     *  offsets keep each window partly visible regardless of stacking order. */
    fun cascadeWindows(displayId: Int, displayW: Int, displayH: Int) {
        if (!isReady || displayW <= 0 || displayH <= 0) return
        val windows = runCatching { listTasks(displayId) }.getOrDefault(emptyList())
            .filter { rt ->
                !rt.packageName.contains("launcher", ignoreCase = true) &&
                    !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                    rt.packageName != "com.portalpad.app"
            }
        if (windows.isEmpty()) return
        // Each window is ~64% of the display; cascade step ~4% of width.
        val winW = (displayW * 0.64f).toInt().coerceAtLeast(320)
        val winH = (displayH * 0.64f).toInt().coerceAtLeast(240)
        val step = (displayW * 0.04f).toInt().coerceIn(32, 72)
        val spread = step * (windows.size - 1)
        // Center the whole cascade block on the display.
        val startX = ((displayW - winW - spread) / 2).coerceAtLeast(0)
        val startY = ((displayH - winH - spread) / 2).coerceAtLeast(0)
        windows.forEachIndexed { i, w ->
            val left = (startX + step * i).coerceAtMost(displayW - winW)
            val top = (startY + step * i).coerceAtMost(displayH - winH)
            runCatching { resizeToFreeform(w, WindowBounds(left, top, left + winW, top + winH), displayW, displayH) }
            Thread.sleep(40)
        }
        // The maximize state is no longer meaningful once windows are cascaded.
        maximizedTaskId = null
        PortalPadApp.instance.signalWindowsChanged()
    }

    /** Demote the currently-maximized window back to a normal FREEFORM window —
     *  the inverse of the maximize actions. Two cases:
     *   • Bar-keeping maximize (freeform still ON): the window never left freeform,
     *     so just resize it back to its pre-maximize bounds (or a sensible default)
     *     and focus it. Clean, no relaunch.
     *   • If freeform support is somehow OFF (defensive — no normal path turns it
     *     off now that bar-free maximize was removed, but a stray state is
     *     possible): this device IGNORES live windowing-mode changes, so we
     *     re-enable freeform and RELAUNCH the app into a freeform window at the
     *     restored bounds. That can re-run the app's launch screen. The relaunch is
     *     inlined (not via launchFreeform) so the max-window cap doesn't block
     *     restoring an already-open window.
     *  No-op if nothing is maximized (caller passes the maximized task). */
    fun restoreToFreeform(
        taskId: Int,
        component: String?,
        packageName: String,
        displayId: Int,
        displayW: Int,
        displayH: Int,
    ) {
        if (!isReady) return
        val target = preMaximizeBounds[taskId]
            ?: if (displayW > 0 && displayH > 0) WindowBounds.restored(displayW, displayH) else null
        val freeformOn = runCatching {
            access.execShell("settings get global enable_freeform_support").trim().startsWith("1")
        }.getOrDefault(false)
        if (!freeformOn) {
            // Freeform was off: re-enable it, then relaunch into a freeform window.
            runCatching { enableFreeform() }
            Thread.sleep(120)
            if (component != null) {
                runCatching {
                    val out = access.execShell(
                        "am start --display $displayId --windowingMode 5 " +
                            "-a android.intent.action.MAIN " +
                            "-c android.intent.category.LAUNCHER " +
                            "-n '$component'"
                    )
                    if (out.containsError()) access.startActivityOnDisplay(packageName, displayId)
                }
                Thread.sleep(LAUNCH_SETTLE_MS)
                // The task id can change after a relaunch — re-find by package.
                val t = runCatching {
                    listTasks(displayId).firstOrNull { it.packageName == packageName }
                }.getOrNull()
                if (t != null) {
                    setWindowingModeFreeform(t.taskId)
                    if (target != null) resize(t.taskId, target)
                }
            } else if (packageName.isNotEmpty()) {
                runCatching { access.startActivityOnDisplay(packageName, displayId) }
            }
        } else {
            // Already freeform (bar-keeping maximize): resize back down + focus.
            setWindowingModeFreeform(taskId)
            if (target != null) resize(taskId, target)
            focus(taskId)
        }
        preMaximizeBounds.remove(taskId)
        if (maximizedTaskId == taskId) maximizedTaskId = null
        PortalPadApp.instance.signalWindowsChanged()
    }

    /** Bring a (possibly minimized) task to the front / give it focus. */
    fun focus(taskId: Int) {
        clearMinimized(taskId)
        runCatching {
            val out = access.execShell("am task $taskId move-to-front")
            Log.d(TAG, "focus($taskId) move-to-front out='${out.trim().take(180)}'")
            if (out.containsError() || out.contains("Unknown", ignoreCase = true)) {
                // Fallback spelling used on some builds.
                val fb = access.execShell("am stack move-task $taskId 1 true")
                Log.d(TAG, "focus($taskId) fallback move-task out='${fb.trim().take(180)}'")
            }
        }.onFailure { Log.e(TAG, "focus($taskId) failed", it) }
    }

    /**
     * Bring an OPEN window on the external display to the front. There is no
     * proven z-order primitive on Shizuku/Samsung — `am task move-to-front`
     * isn't a valid verb, and `am stack move-task` fails for external-display
     * tasks (and would yank the window onto the phone display anyway). Instead
     * we re-launch the app's LAUNCHER component on the SAME display, which makes
     * the system reorder the already-running task to the front (like tapping its
     * icon) at its current freeform bounds — so the window keeps its position.
     */
    fun bringToFront(task: RunningTask) {
        clearMinimized(task.taskId)
        val launchComp = runCatching {
            PortalPadApp.instance.packageManager
                .getLaunchIntentForPackage(task.packageName)?.component?.flattenToShortString()
        }.getOrNull()
        val disp = if (task.displayId >= 0) {
            task.displayId
        } else {
            PortalPadApp.instance.externalDisplayId.value ?: -1
        }
        if (launchComp == null || disp < 0) {
            Log.d(TAG, "bringToFront(${task.taskId}) skip comp=$launchComp disp=$disp")
            return
        }
        val ok = runCatching { access.startActivityOnDisplay(launchComp, disp) }
            .getOrDefault(false)
        Log.d(TAG, "bringToFront(${task.taskId}) $launchComp disp=$disp ok=$ok")
        PortalPadApp.instance.signalWindowsChanged()
    }

    /** Restack a task BEHIND the others without moving or resizing it. Used when
     *  maximizing one window: the others are pushed back so the maximized window
     *  isn't left overlapped by a freeform window that was on top. This only
     *  changes z-order; the window keeps its bounds and stays open. */
    fun moveToBack(taskId: Int) {
        runCatching { access.execShell("am task $taskId move-to-back") }
            .onFailure { Log.e(TAG, "moveToBack($taskId) failed", it) }
    }

    /** Minimize a task by PARKING it off-screen (below the display) and marking
     *  it minimized so the dock lists it for restore. There's no reliable
     *  freeform "hide" primitive on Shizuku/Samsung — `move-to-back` only
     *  restacks (the window stays visible). resize() IS proven on-device, so we
     *  park the window entirely below the bottom edge to make it visually vanish,
     *  remembering its bounds so [restoreFromMinimized] returns it exactly.
     *
     *  [current] is the window's current bounds (from listTasks) if known;
     *  [displayW]/[displayH] are the external display's pixel size. */
    fun minimize(taskId: Int, current: WindowBounds? = null, displayW: Int = 0, displayH: Int = 0) {
        markMinimized(taskId)
        if (current != null) savedBounds[taskId] = current
        runCatching {
            if (displayH > 0) {
                // Preserve size/left where we can; park fully below the bottom edge.
                val w = current?.let { (it.right - it.left).coerceAtLeast(1) } ?: (displayW / 2).coerceAtLeast(1)
                val h = current?.let { (it.bottom - it.top).coerceAtLeast(1) } ?: (displayH / 2).coerceAtLeast(1)
                val left = current?.left ?: ((displayW - w) / 2).coerceAtLeast(0)
                resize(taskId, WindowBounds(left, displayH, left + w, displayH + h))
            }
            // Also nudge to back — harmless, and helps if the OEM clamps the park
            // bounds back on-screen (at least it won't sit on top).
            access.execShell("am task $taskId move-to-back")
        }.onFailure { Log.e(TAG, "minimize($taskId) failed", it) }
    }

    /** Restore a window the user minimized: move it back to its saved bounds (or
     *  a sensible default), clear the minimized mark, and bring it to front. Safe
     *  to call on a non-minimized task — it then just focuses (no resize). */
    fun restoreFromMinimized(taskId: Int, displayW: Int = 0, displayH: Int = 0) {
        val saved = savedBounds.remove(taskId)
        clearMinimized(taskId)
        runCatching {
            val target = saved ?: if (displayH > 0) WindowBounds.restored(displayW, displayH) else null
            if (target != null) resize(taskId, target)
            focus(taskId)
        }.onFailure { Log.e(TAG, "restoreFromMinimized($taskId) failed", it) }
    }

    /** Minimize by CLOSING the window (no off-screen sliver): remember the
     *  package + bounds, then remove the task from the display. [restoreClosed]
     *  later relaunches the app into a freeform window at the saved bounds — a
     *  FRESH start, so in-app state (scroll, navigation, unsaved input) isn't
     *  preserved. That's the deliberate trade vs. the park model: a clean,
     *  sliver-free minimize and freed resources, in exchange for a relaunch
     *  rather than a live restore. */
    fun minimizeByClose(taskId: Int, packageName: String, bounds: WindowBounds?) {
        val id = closedIdSeq.getAndIncrement()
        closedWindows[id] = ClosedWindow(id, packageName, bounds)
        close(taskId)
    }

    /** Relaunch a close-minimized window into freeform at its saved bounds on
     *  [displayId]. Returns false if the entry is gone or the app exposes no
     *  launcher entry. Removes the entry whether or not the launch succeeds (a
     *  failed relaunch shouldn't leave a ghost row in the menu). */
    fun restoreClosed(id: Int, displayId: Int, displayW: Int = 0, displayH: Int = 0): Boolean {
        val e = closedWindows.remove(id) ?: return false
        val component = runCatching {
            PortalPadApp.instance.packageManager
                .getLaunchIntentForPackage(e.packageName)?.component?.flattenToShortString()
        }.getOrNull() ?: return false
        val target = e.bounds ?: if (displayH > 0) WindowBounds.restored(displayW, displayH) else null
        if (target == null) return false
        return launchFreeform(component, displayId, target)
    }

    /**
     * DIAGNOSTIC: dump the raw output of the commands we'd parse window positions
     * from, so we can see this device's actual format and write an accurate bounds
     * parser. Logged under tag "PortalPadWinDiag" — capture with logcat. Open a few
     * freeform windows on the external display, position them, THEN trigger this.
     */
    fun dumpWindowDiagnostics(displayId: Int) {
        val tag = "PortalPadWinDiag"
        fun chunked(label: String, body: String) {
            Log.d(tag, "===== BEGIN $label =====")
            // Logcat truncates long lines; emit line-by-line so nothing is lost.
            body.split("\n").forEach { Log.d(tag, "[$label] $it") }
            Log.d(tag, "===== END $label =====")
        }
        runCatching {
            Log.d(tag, "displayId(requested)=$displayId")
            chunked("am_stack_list", access.execShell("am stack list"))
            chunked("dumpsys_window_displays", access.execShell("dumpsys window displays"))
            chunked("dumpsys_window_windows", access.execShell("dumpsys window windows"))
            // Per-task bounds sometimes live here on Samsung.
            chunked("dumpsys_activity_lru", access.execShell("dumpsys activity lru"))
            chunked("dumpsys_activity_activities", access.execShell("dumpsys activity activities"))
        }.onFailure { Log.e(tag, "dumpWindowDiagnostics failed", it) }
        Log.d(tag, "===== DIAGNOSTIC COMPLETE =====")
    }

    /** Close (finish) a task entirely. Tries the spellings that vary by OEM. */
    fun close(taskId: Int) {
        clearMinimized(taskId)
        runCatching {
            val out = access.execShell("am task $taskId remove")
            if (out.containsError() || out.contains("Unknown", ignoreCase = true) ||
                out.contains("Error", ignoreCase = true)
            ) {
                Log.d(TAG, "close: 'am task remove' spelling failed, trying alternates")
                // Alternate spellings seen across builds.
                val alt = access.execShell("am stack remove $taskId")
                if (alt.containsError() || alt.contains("Unknown", ignoreCase = true)) {
                    access.execShell("am force-stop-task $taskId")
                }
            }
        }.onFailure { Log.e(TAG, "close($taskId) failed", it) }
        PortalPadApp.instance.signalWindowsChanged()
    }

    /**
     * Snapshot the REAL apps currently open on [displayId] (their relaunch
     * component + window bounds), for session save. Filters out launcher/home and
     * PortalPad's own backdrop. Returns null if nothing real is open (so callers
     * can avoid overwriting a good session with an empty one).
     */
    fun snapshotSession(displayId: Int): com.portalpad.app.data.SavedSession? {
        val windows = runCatching {
            val raw = listTasks(displayId).filter { rt ->
                !rt.packageName.contains("launcher", ignoreCase = true) &&
                    !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                    rt.packageName != "com.portalpad.app"
            }
            Log.d(TAG, "snapshotSession: raw tasks=${raw.size} [${raw.groupingBy { it.packageName }.eachCount()}]")
            raw.mapNotNull { rt ->
                val b = rt.bounds
                // Skip windows whose bounds are null or degenerate (a window caught
                // mid-transition). Saving 0,0,0,0 here would later be restored as a
                // full-canvas rectangle — a window stuck MAXIMIZED behind the tiled
                // ones. The debounced snapshotter re-captures a clean frame shortly
                // after, so dropping a transient now is harmless.
                if (b == null || b.right <= b.left || b.bottom <= b.top) {
                    Log.d(TAG, "snapshotSession: SKIP ${rt.packageName} task=${rt.taskId} bad-bounds=$b")
                    return@mapNotNull null
                }
                com.portalpad.app.data.SavedWindow(
                    packageName = rt.packageName,
                    component = rt.topActivity,
                    left = b.left, top = b.top,
                    right = b.right, bottom = b.bottom,
                )
            }
        }.getOrDefault(emptyList())
        if (windows.isEmpty()) return null
        Log.d(TAG, "snapshotSession: saved ${windows.size} windows [${windows.groupingBy { it.packageName }.eachCount()}]")
        // Record the canvas width these bounds were captured against, so restore
        // can detect a resolution change (e.g. ultrawide → standard on replug) and
        // re-tile instead of replaying bounds that no longer fit.
        val canvasWidth = runCatching {
            val dm = PortalPadApp.instance
                .getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val disp = dm.getDisplay(displayId)
            val m = android.util.DisplayMetrics().also { disp?.getRealMetrics(it) }
            m.widthPixels
        }.getOrDefault(0)
        val canvasHeight = runCatching {
            val dm = PortalPadApp.instance
                .getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val disp = dm.getDisplay(displayId)
            val m = android.util.DisplayMetrics().also { disp?.getRealMetrics(it) }
            m.heightPixels
        }.getOrDefault(0)
        return com.portalpad.app.data.SavedSession(windows, System.currentTimeMillis(), canvasWidth, canvasHeight)
    }

    /**
     * Relaunch a saved [session]'s apps onto [displayId] and retile them to their
     * saved bounds. Apps with a known component launch precisely; those without
     * fall back to a launch-intent by package. Best-effort per app — one failure
     * doesn't abort the rest. Returns the count successfully launched.
     */
    fun restoreSession(
        session: com.portalpad.app.data.SavedSession,
        displayId: Int,
        displayW: Int,
        displayH: Int,
    ): Int {
        var launched = 0
        // Don't relaunch apps already live on the display. On a physical replug the
        // platform re-homes surviving apps itself; relaunching them here spawns a
        // duplicate (e.g. a 2nd Chrome window behind the arranged ones). Only
        // genuinely-missing apps get (re)launched; survivors are left for the
        // caller to re-tile in place.
        // Count how many instances of each package are already live on the
        // display. A surviving window only "covers" ONE saved window of that
        // package — so with two saved Chrome windows and one live Chrome, the
        // second is still relaunched instead of being skipped as a duplicate.
        // (The old set-of-package-names dedup collapsed same-package windows,
        // silently dropping the 2nd Chrome on a resolution flap.)
        val liveCounts = runCatching { listExternalTasks() }
            .getOrDefault(emptyList())
            .groupingBy { it.packageName }.eachCount().toMutableMap()
        // Track how many windows of each package will exist as we go (survivors +
        // what we relaunch). When this is already >0 for an app, a plain launch
        // would refocus the existing window instead of making a new one, so we
        // force a fresh task to actually recreate the duplicate window.
        val established = liveCounts.toMutableMap()
        // `session.windows` is stored in `am stack list` order, which is
        // front-to-back (most-recently-focused first). Each launch comes up on
        // top, so to reproduce the original stacking we launch BACK-to-front:
        // reversed() puts the frontmost app last, leaving it on top.
        for (w in session.windows.reversed()) {
            val survivors = liveCounts[w.packageName] ?: 0
            if (survivors > 0) {
                // A live instance already satisfies this saved window — leave it
                // for the caller to re-tile in place; don't spawn a duplicate.
                liveCounts[w.packageName] = survivors - 1
                continue
            }
            val bounds = if (w.right > w.left && w.bottom > w.top) w.bounds
            else WindowBounds.restored(displayW, displayH)
            val comp = w.component
            // If this app already has a window (a survivor, or one relaunched
            // earlier in this pass), force a new task so the duplicate appears
            // instead of refocusing the existing one.
            val needNewTask = (established[w.packageName] ?: 0) > 0
            val ok = runCatching {
                if (!comp.isNullOrBlank()) {
                    launchFreeform(comp, displayId, bounds, forceNewTask = needNewTask)
                } else {
                    // No component recorded — resolve the package's launcher activity.
                    val pm = PortalPadApp.instance.packageManager
                    val intentComp = pm.getLaunchIntentForPackage(w.packageName)?.component?.flattenToShortString()
                    if (intentComp != null) launchFreeform(intentComp, displayId, bounds, forceNewTask = needNewTask) else false
                }
            }.getOrDefault(false)
            if (ok) {
                launched++
                established[w.packageName] = (established[w.packageName] ?: 0) + 1
            }
            // Small gap so each launch settles before the next (matches arrange).
            runCatching { Thread.sleep(120) }
        }
        return launched
    }

    /**
     * Re-apply a saved session's window bounds to the LIVE tasks already on
     * [displayId] — WITHOUT relaunching anything (so a playing video keeps
     * playing). Used after a flap-recovery's move-first re-homes the apps: that
     * preserves the live tasks but drops them in a default/minimized state, so
     * this re-tiles them to the arrangement the user had before the disconnect.
     *
     * Matches each saved window to a running task by component (preferred) or
     * package, greedily, so duplicate packages don't double-map. Returns the
     * number of windows re-tiled.
     */
    fun retileToSaved(
        session: com.portalpad.app.data.SavedSession,
        displayId: Int,
        displayW: Int,
        displayH: Int,
        // When true, a survivor stuck in FULLSCREEN mode is CLOSED and relaunched
        // into freeform at its saved bounds — the only thing that reliably tiles it
        // on this device (live resize/mode-change is ignored), at the cost of the
        // app's live state. The move-first path passes false so a preserved live
        // task (e.g. a playing video) is never killed.
        relaunchFullscreen: Boolean = false,
        // Scale factor applied to each saved bound before tiling. Used when
        // restoring onto a canvas of a different size than the session was saved
        // on (e.g. ultrawide → standard): scaling the SAVED bounds keeps targets
        // inside the new canvas, so the platform never clamps an oversized target
        // and we never have to re-read mangled bounds. 1f = no change.
        scaleX: Float = 1f,
        scaleY: Float = 1f,
    ): Int {
        if (!isReady) return 0
        val live = runCatching { listExternalTasks() }.getOrDefault(emptyList())
        if (live.isEmpty()) return 0
        val used = HashSet<Int>()
        var applied = 0
        // Process BACK-to-front (reversed): session.windows is `am stack list`
        // order = front-to-back, and every step here fronts the window it touches
        // (a relaunch comes up on top; an in-place focus moves-to-front). Iterating
        // back-to-front means the FRONTMOST window is handled last and ends on top,
        // reproducing the original stacking — matching restoreSession's order.
        // (Forward iteration fronted the backmost window last, inverting the stack.)
        for (w in session.windows.reversed()) {
            val task = live.firstOrNull { t ->
                t.taskId !in used && (
                    (!w.component.isNullOrBlank() && t.topActivity?.equals(w.component, ignoreCase = true) == true) ||
                        t.packageName.equals(w.packageName, ignoreCase = true)
                )
            } ?: continue
            used += task.taskId
            val rawBounds = if (w.right > w.left && w.bottom > w.top) w.bounds
            else WindowBounds.restored(displayW, displayH)
            // Scale the saved bound to the live canvas when requested, clamped so it
            // always stays a valid in-bounds rect (keeps a different-size restore
            // from producing off-screen targets the platform would then clamp).
            val bounds = if (scaleX != 1f || scaleY != 1f) {
                val nl = (rawBounds.left * scaleX).toInt().coerceIn(0, (displayW - 1).coerceAtLeast(0))
                val nt = (rawBounds.top * scaleY).toInt().coerceIn(0, (displayH - 1).coerceAtLeast(0))
                val nr = (rawBounds.right * scaleX).toInt().coerceIn(nl + 1, displayW)
                val nb = (rawBounds.bottom * scaleY).toInt().coerceIn(nt + 1, displayH)
                WindowBounds(nl, nt, nr, nb)
            } else rawBounds
            val cur = task.bounds
            // A survivor the platform re-homed in FULLSCREEN windowing mode fills
            // the display and (proven on-device) ignores live resize + windowing-
            // mode changes — it can't be tiled in place. Detect by full-display
            // bounds (or unknown bounds).
            val isFullscreen = cur == null || (
                cur.left <= 2 && cur.top <= 2 &&
                    cur.right >= displayW - 2 && cur.bottom >= displayH - 2
            )
            android.util.Log.d(
                "PortalPadSleep",
                "RECOVERY retile pkg=${task.packageName} task=${task.taskId} " +
                    "cur=${cur?.let { "[${it.left},${it.top}][${it.right},${it.bottom}]" } ?: "null"} " +
                    "target=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}] " +
                    "fullscreen=$isFullscreen relaunch=${isFullscreen && relaunchFullscreen}",
            )
            if (isFullscreen && relaunchFullscreen) {
                // Close + relaunch fresh into freeform. A brand-new --windowingMode 5
                // task comes up freeform reliably (that's how every normal launch
                // works here), so the resize inside launchFreeform actually sticks.
                // (Tested alternatives — in-place resize, and a move-to-phone-and-back
                // bounce — both FAILED to un-stick a re-homed fullscreen window on this
                // device, and the bounce additionally spawned duplicate tasks. So the
                // close+relaunch is the only reliable tiling path here. Its cost is that
                // the relaunched window loses its in-app state, e.g. Chrome's page.)
                val comp = w.component?.takeIf { it.isNotBlank() }
                    ?: runCatching {
                        PortalPadApp.instance.packageManager
                            .getLaunchIntentForPackage(w.packageName)?.component?.flattenToShortString()
                    }.getOrNull()
                if (comp != null) {
                    close(task.taskId)
                    // Let the removal settle — also frees the window-cap slot so the
                    // relaunch below isn't blocked by the max-windows check.
                    runCatching { Thread.sleep(350) }
                    runCatching { launchFreeform(comp, displayId, bounds) }
                    applied++
                    runCatching { Thread.sleep(120) }
                    continue
                }
                // No launchable component — fall through to a best-effort in-place
                // attempt (better than leaving it fullscreen, even if it won't stick).
            }
            // Already-freeform window (or in-place fallback): resize in place,
            // preserving the app's live state.
            setWindowingModeFreeform(task.taskId)
            runCatching { Thread.sleep(120) }
            runCatching { resize(task.taskId, bounds) }
            clearMinimized(task.taskId)
            focus(task.taskId)
            applied++
            runCatching { Thread.sleep(120) }
        }
        if (applied > 0) PortalPadApp.instance.signalWindowsChanged()
        return applied
    }


    /**
     * List running tasks, optionally filtered to [displayId] (pass -1 for all).
     * Parses `am stack list`, which prints one line per task with the task id,
     * the top component, bounds, and display id. Output format varies by OEM,
     * so this is intentionally forgiving — unparseable lines are skipped.
     *
     * Example line shapes we handle:
     *   taskId=42: com.android.chrome/org.chromium.chrome.browser.ChromeTabbedActivity bounds=[0,0][960,1080] displayId=7
     *   Task id #42 ... A=com.android.chrome U=0 ...
     */
    /**
     * List running tasks, filtered to [displayId] (pass -1 for all).
     *
     * When a specific [displayId] is given, this is STRICT: only tasks the
     * platform reports as being on that display are returned. Tasks whose
     * display we couldn't parse are excluded — better to occasionally miss a
     * window than to show phone-display apps the user can't manage from the
     * external taskbar. (Some OEM `am stack list` output omits displayId; on
     * those devices the external taskbar may under-report, which is the safe
     * direction to fail.)
     */
    fun listTasks(displayId: Int = -1): List<RunningTask> {
        if (!isReady) return emptyList()
        var raw = runCatching { access.execShell("am stack list") }.getOrDefault("")
        // am stack list is deprecated on newer Android and can come back blank
        // or as an error; fall back to the cmd activity equivalent.
        if (raw.isBlank() || raw.contains("Unknown", ignoreCase = true) ||
            raw.contains("Error", ignoreCase = true)
        ) {
            raw = runCatching { access.execShell("cmd activity stack list") }.getOrDefault("")
        }
        val tasks = mutableListOf<RunningTask>()
        var parsedAny = 0
        // The display id often lives on a header/context line (e.g.
        // "RootTask … displayId=N") while the component/taskId is on a following
        // line — so per-line parsing saw displayId=-1 for every task. Track the
        // most recent display id seen on ANY line and apply it to tasks parsed
        // under it. Format-agnostic so it survives OEM/Android output variations.
        var currentHeaderDisplay = -1
        for (line in raw.lineSequence()) {
            ANY_DISPLAY.find(line)?.let {
                currentHeaderDisplay = it.groupValues[1].toIntOrNull() ?: currentHeaderDisplay
            }
            val t = parseTaskLine(line) ?: continue
            parsedAny++
            // Prefer a displayId parsed from the task's own line; fall back to the
            // most recent display id seen above it.
            val effective = if (t.displayId != -1) t.displayId else currentHeaderDisplay
            val resolved = t.copy(displayId = effective)
            val keep = if (displayId == -1) true else resolved.displayId == displayId
            if (keep) tasks += resolved
        }
        android.util.Log.d(
            "ListTasks",
            "req disp=$displayId rawLen=${raw.length} parsed=$parsedAny kept=${tasks.size} " +
                "firstLines=${raw.lineSequence().take(3).joinToString(" | ").take(300)}",
        )
        return tasks.distinctBy { it.taskId }
    }

    /**
     * List tasks on the external display, resilient to which display id the
     * platform currently attributes them to.
     *
     * Why this exists: the trusted VD is recreated with a FRESH id whenever
     * [com.portalpad.app.service.PortalPadForegroundService.refreshExternalDisplay]
     * runs (dock toggle, DPI change, a brief display blip). A window opened
     * under the old id stays put while [PortalPadApp.externalDisplayId] moves to
     * the new one — so a single-id `listTasks(externalDisplayId)` can come back
     * empty while the window is plainly still open. That's the "Close all does
     * nothing / Manage windows shows zero" bug.
     *
     * The fix: query every id that could be the external surface — the live
     * external/VD id, the underlying PHYSICAL id (stable across VD churn, so it
     * anchors us even after the VD id changes), and the VD snapshot — and merge
     * the results, deduped by taskId.
     *
     * We deliberately do NOT fall back to `listTasks(-1)` here: that would risk
     * surfacing phone-display apps the user can't (and shouldn't) manage from
     * the external bar.
     */
    fun listExternalTasks(): List<RunningTask> {
        if (!isReady) return emptyList()
        val app = com.portalpad.app.PortalPadApp.instance
        val ids = listOfNotNull(
            app.externalDisplayId.value,
            app.physicalExternalDisplayId.value,
            app.virtualDisplayId,
        ).filter { it >= 0 }.distinct()
        if (ids.isEmpty()) return emptyList()
        val merged = ids.flatMap { id ->
            runCatching { listTasks(id) }.getOrDefault(emptyList())
        }.distinctBy { it.taskId }
        android.util.Log.d(
            "ListTasks", "listExternalTasks ids=$ids merged=${merged.size}",
        )
        return merged
    }

    /**
     * Count the windows that actually live on the EXTERNAL display (the ones
     * PortalPad manages), for cap enforcement. The naive "all non-launcher
     * tasks" count over-counts on some devices: their `am stack list` surfaces
     * the phone's own background/recents tasks (on displayId 0, fullscreen
     * phone bounds), which made the cap trip with nothing actually open on the
     * glasses. PortalPad launches windows onto the external display (a non-zero
     * display id), so we exclude tasks on display 0 and tasks whose bounds are
     * the full phone screen. Launchers and our own app are excluded too.
     */
    fun countExternalWindows(): Int {
        // Count windows on the ACTUAL external display id, not "everything that
        // isn't obviously the phone." The old approach used listTasks(-1) and kept
        // tasks with an unresolved displayId (== -1), which over-counted phone
        // background/recents tasks → the cap warning fired with few or no windows
        // actually on the glasses. Resolve the external/VD id and count tasks
        // whose displayId matches it, excluding the launcher and PortalPad itself.
        val app = PortalPadApp.instance
        val extId = app.externalDisplayId.value ?: return 0
        val physId = app.physicalExternalDisplayId.value
        // A freeform task may report either the VD id or the physical display id
        // depending on the wrap, so accept both. Counting on a specific display
        // (not "everything non-phone") is what fixes the phantom-count warning.
        val ids = setOfNotNull(extId, physId)
        val all = listTasks(-1)
        fun isUserApp(rt: RunningTask): Boolean {
            val isLauncher = rt.packageName.contains("launcher", ignoreCase = true) ||
                rt.packageName.contains("nexuslauncher", ignoreCase = true)
            val isSelf = rt.packageName.startsWith("com.portalpad")
            return !isLauncher && !isSelf
        }
        val onExternal = all.filter { it.displayId in ids && isUserApp(it) }
        // Robustness fallback: if display-id parsing failed for everything (no
        // task resolved to our display id, yet there ARE user-app tasks with an
        // unresolved/unknown display), count those so the cap still enforces SOME
        // limit rather than silently allowing unlimited windows. On the phone's
        // own display (0) apps are excluded either way.
        val counted = if (onExternal.isEmpty()) {
            all.filter { it.displayId != 0 && it.displayId !in ids && isUserApp(it) }
                .ifEmpty { onExternal }
        } else {
            onExternal
        }
        return counted.size
    }

    // ---- parsing helpers -------------------------------------------------

    private fun parseTaskLine(line: String): RunningTask? {
        val taskId = TASK_ID.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val component = COMPONENT.find(line)?.value
        val pkg = component?.substringBefore('/')
            ?: PKG_ONLY.find(line)?.groupValues?.get(1)
            ?: return null
        val disp = DISPLAY_ID.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val bounds = BOUNDS.find(line)?.let { m ->
            val (l, t, r, b) = m.destructured
            WindowBounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }
        // Skip our own app's tasks and the system launcher — they aren't user
        // windows the taskbar should manage.
        if (pkg.startsWith("com.portalpad")) return null
        // visible=true|false on the task line distinguishes an on-screen window from
        // one minimized to the taskbar (system caption-bar minimize, or move-to-back).
        // Absent on some OEM builds → default true (assume visible).
        val isVisible = VISIBLE.find(line)?.groupValues?.get(1) != "false"
        return RunningTask(
            taskId = taskId,
            packageName = pkg,
            topActivity = component,
            displayId = disp,
            bounds = bounds,
            visible = isVisible,
        )
    }

    private fun packageOf(component: String): String = component.substringBefore('/')

    private fun String.containsError(): Boolean =
        contains("Error:", ignoreCase = true) || contains("Exception", ignoreCase = true)

    companion object {
        private const val TAG = "FreeformManager"
        private const val LAUNCH_SETTLE_MS = 350L

        // Forgiving regexes for `am stack list` / dumpsys lines.
        private val TASK_ID = Regex("""(?:taskId=|Task id #|task #)\s*(\d+)""")
        private val COMPONENT = Regex("""[a-zA-Z][\w.]+/[\w.${'$'}]+""")
        private val PKG_ONLY = Regex("""A=(?:\d+:)?([\w.]+)""")
        // Match displayId on a task's own line. Handles "displayId=N" and the
        // "mDisplayId=N" spelling some Android builds use.
        private val DISPLAY_ID = Regex("""m?[Dd]isplayId=(\d+)""")
        // Per-task visibility flag from `am stack list` / dumpsys (visible=true|false).
        // A caption-bar minimize or move-to-back reports visible=false.
        private val VISIBLE = Regex("""visible=(true|false)""")
        // Any line carrying a display id is treated as context for the tasks that
        // follow (headers like "RootTask … displayId=N", "Display #N", "Stack …
        // mDisplayId=N", etc.) — kept format-agnostic so it survives OEM/Android
        // variations rather than assuming the exact "RootTask" wording.
        private val ANY_DISPLAY = Regex("""(?:m?[Dd]isplayId=|Display #)(\d+)""")
        private val BOUNDS = Regex("""bounds=\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
    }
}
