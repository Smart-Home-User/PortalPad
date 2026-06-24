package com.portalpad.app.presentation

import android.content.Context
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.portalpad.app.data.RunningTask
import com.portalpad.app.service.FreeformManager
import com.portalpad.app.ui.dock.AppIcon
import com.portalpad.app.ui.theme.AbPrimaryBright
import com.portalpad.app.ui.theme.AbSurfaceElevated
import com.portalpad.app.ui.theme.PortalPadTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DeX-style taskbar pinned to the bottom-center of the external display in
 * desktop-windows mode. Shows an icon per running task; tap focuses/restores
 * the window, long-press closes it. A "+" tile opens the dock's app picker.
 *
 * IMPORTANT — built EXACTLY like [DockOverlay] (a ComposeView in a WRAP_CONTENT
 * overlay window with the same lifecycle owners and the same window flags, and
 * crucially WITHOUT FLAG_LAYOUT_NO_LIMITS). The earlier plain-Android-View
 * version did not reliably receive the injected-cursor taps; the dock's Compose
 * hit-testing path is the one that works, so the taskbar now uses it too.
 */
class TaskbarOverlay(
    serviceContext: Context,
    val display: Display,
    private val freeform: FreeformManager,
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val overlayHost = OverlayHost.forDisplay(display, serviceContext)
    private val displayContext = overlayHost.context
    private val windowManager = overlayHost.windowManager

    val displayId: Int get() = display.displayId

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val _viewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    private var view: View? = null

    /** Scope for off-main-thread launch/close shell calls triggered from the
     *  Compose content. Cancelled in dismiss(). */
    private val scope: CoroutineScope = MainScope()

    var isShowing: Boolean = false
        private set

    fun show() {
        if (isShowing) return
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val composeView = ComposeView(displayContext).apply {
            setViewTreeLifecycleOwner(this@TaskbarOverlay)
            setViewTreeSavedStateRegistryOwner(this@TaskbarOverlay)
            setViewTreeViewModelStoreOwner(this@TaskbarOverlay)
            setContent { PortalPadTheme { TaskbarContent() } }
        }

        // Same window params as DockOverlay: WRAP_CONTENT, NOT_FOCUSABLE +
        // NOT_TOUCH_MODAL, and deliberately NO FLAG_LAYOUT_NO_LIMITS so the
        // touchable bounds match the laid-out content and every icon is
        // clickable by the injected cursor.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        try {
            windowManager.addView(composeView, params)
            view = composeView
            isShowing = true
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            Log.d(TAG, "Taskbar attached on display $displayId (type=${overlayHost.windowType})")
        } catch (t: Throwable) {
            Log.e(TAG, "Could not attach taskbar", t)
        }
    }

    fun dismiss() {
        if (!isShowing) return
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        runCatching { scope.cancel() }
        _viewModelStore.clear()
        isShowing = false
    }

    @Composable
    private fun TaskbarContent() {
        val app = com.portalpad.app.PortalPadApp.instance
        var tasks by remember { mutableStateOf<List<RunningTask>>(emptyList()) }
        val dockCfg = app.prefs.dockConfig
            .collectAsState(initial = com.portalpad.app.data.DockConfig()).value
        val maxWindows = app.prefs.int(
            com.portalpad.app.data.PreferencesRepository.Keys.MAX_WINDOWS, default = 5,
        ).collectAsState(initial = 5).value.coerceIn(1, 8)

        // "Close a window to open another" chooser. Non-null while shown; holds
        // the component the user wanted to open once they free a slot.
        var pendingLaunch by remember { mutableStateOf<String?>(null) }

        // Poll the running-task list off the main thread. Count windows the
        // robust way: tasks are often registered under a different display id
        // than the VD (display.displayId), so listTasks(display.displayId) can
        // come back empty even with windows open — which made the max-window
        // cap never trigger. Fall back to listTasks(-1) (all displays, minus
        // launchers/our own app) when the per-display query finds nothing.
        LaunchedEffect(Unit) {
            while (true) {
                val next = withContext(Dispatchers.IO) {
                    runCatching {
                        // Count tasks on the actual external display id(s) — the VD
                        // id and the physical external id (a task may report either
                        // depending on the wrap). Previously this fell back to
                        // listTasks(-1) minus launchers, which counted phone
                        // background/recents tasks → phantom taskbar entries and a
                        // false max-window cap with no windows on the glasses.
                        val physId = app.physicalExternalDisplayId.value
                        val ids = setOfNotNull(display.displayId, physId)
                        freeform.listTasks(-1).filter { rt ->
                            rt.displayId in ids &&
                                !rt.packageName.contains("launcher", ignoreCase = true) &&
                                !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                                !rt.packageName.startsWith("com.portalpad")
                        }
                    }.getOrDefault(emptyList())
                }
                if (next.map { it.taskId } != tasks.map { it.taskId }) tasks = next
                delay(POLL_INTERVAL_MS)
            }
        }

        // Pinned favorites flattened to launchable entries (folders excluded —
        // the taskbar is a flat launcher strip; folders live in the dock).
        val pinned = dockCfg.items.filterIsInstance<com.portalpad.app.data.DockItem.Shortcut>()

        val launch: (String) -> Unit = { component ->
            if (tasks.size >= maxWindows) {
                pendingLaunch = component        // at cap → ask user to close one
            } else {
                openWindow(component)
            }
        }

        val shape = RoundedCornerShape(24.dp)
        Surface(
            color = Color(0xCC1A1A20),
            shape = shape,
            tonalElevation = 0.dp,
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.18f)),
            modifier = Modifier.padding(10.dp),
        ) {
            LazyRow(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 1. Pinned favorites — tap to open as a window.
                items(pinned, key = { "pin_${it.id}" }) { sc ->
                    PinnedTile(
                        packageName = sc.app.packageName,
                        onClick = { launch(componentFor(sc.app)) },
                    )
                }
                // 2. Separator — only when BOTH groups are present, so it
                //    always sits between favorites and running windows.
                if (pinned.isNotEmpty() && tasks.isNotEmpty()) {
                    item(key = "__sep__") { TaskbarSeparator() }
                }
                // 3. Running windows (external display) — tap focus, long-press close.
                items(tasks, key = { "task_${it.taskId}" }) { task ->
                    TaskTile(
                        task = task,
                        onClick = {
                            // restoreFromMinimized un-parks + clears the minimized
                            // mark if the user had minimized it; otherwise it just
                            // focuses (no resize). Safe either way.
                            val m = DisplayMetrics().also { display.getRealMetrics(it) }
                            freeform.restoreFromMinimized(task.taskId, m.widthPixels, m.heightPixels)
                        },
                        onLongPress = { freeform.close(task.taskId) },
                    )
                }
                // 4. Add tile.
                item(key = "__add__") {
                    AddTaskTile(onClick = { openAppPicker() })
                }
            }
        }

        // Cap reached → chooser to close a running window, then proceed.
        pendingLaunch?.let { component ->
            CloseToOpenDialog(
                running = tasks,
                onPick = { taskId ->
                    freeform.close(taskId)
                    pendingLaunch = null
                    // Give the close a beat, then open the requested app.
                    scope.launch {
                        delay(450)
                        openWindow(component)
                    }
                },
                onDismiss = { pendingLaunch = null },
                maxWindows = maxWindows,
            )
        }
    }

    /** Open [component] as a freeform window on this display, cascaded. */
    private fun openWindow(component: String) {
        scope.launch {
            val metrics = android.util.DisplayMetrics().also {
                @Suppress("DEPRECATION") display.getRealMetrics(it)
            }
            val bounds = com.portalpad.app.data.WindowBounds.cascade(
                freeform.nextLaunchIndex(), metrics.widthPixels, metrics.heightPixels,
            )
            withContext(Dispatchers.IO) {
                runCatching { freeform.launchFreeform(component, display.displayId, bounds) }
            }
        }
    }

    private fun componentFor(entry: com.portalpad.app.data.AppEntry): String {
        return if (entry.isActivity) "${entry.packageName}/${entry.componentName}"
        else com.portalpad.app.PortalPadApp.instance.packageManager
            .getLaunchIntentForPackage(entry.packageName)
            ?.component?.flattenToString() ?: entry.packageName
    }

    @Composable
    private fun TaskbarSeparator() {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(width = 1.dp, height = (TASK_ICON_DP * 0.7f).dp)
                .background(Color.White.copy(alpha = 0.22f)),
        )
    }

    @Composable
    private fun PinnedTile(packageName: String, onClick: () -> Unit) {
        Box(
            Modifier
                .size(TASK_ICON_DP.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(packageName = packageName, sizeDp = TASK_ICON_DP - 8)
        }
    }

    @Composable
    private fun CloseToOpenDialog(
        running: List<RunningTask>,
        onPick: (taskId: Int) -> Unit,
        onDismiss: () -> Unit,
        maxWindows: Int,
    ) {
        val pm = com.portalpad.app.PortalPadApp.instance.packageManager
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF1A1A20),
            title = {
                androidx.compose.material3.Text(
                    "Window limit reached",
                    color = Color.White,
                )
            },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(
                        "You have $maxWindows windows open (the maximum). " +
                            "Close one to open another:",
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                    running.forEach { t ->
                        val label = runCatching {
                            pm.getApplicationLabel(pm.getApplicationInfo(t.packageName, 0)).toString()
                        }.getOrDefault(t.packageName)
                        androidx.compose.foundation.layout.Row(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(t.taskId) }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(packageName = t.packageName, sizeDp = 32)
                            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
                            androidx.compose.material3.Text(label, color = Color.White)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    androidx.compose.material3.Text("Cancel", color = AbPrimaryBright)
                }
            },
        )
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    private fun TaskTile(
        task: RunningTask,
        onClick: () -> Unit,
        onLongPress: () -> Unit,
    ) {
        Box(
            Modifier
                .size(TASK_ICON_DP.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.10f))
                .combinedClickable(onClick = onClick, onLongClick = onLongPress),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(packageName = task.packageName, sizeDp = TASK_ICON_DP - 12)
        }
    }

    @Composable
    private fun AddTaskTile(onClick: () -> Unit) {
        Box(
            Modifier
                .size(TASK_ICON_DP.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AbSurfaceElevated.copy(alpha = 0.45f))
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Open app",
                tint = AbPrimaryBright,
                modifier = Modifier.size((TASK_ICON_DP * 0.5f).dp),
            )
        }
    }

    private fun openAppPicker() {
        runCatching {
            val ctx = com.portalpad.app.PortalPadApp.instance
            ctx.startActivity(
                android.content.Intent(
                    ctx, com.portalpad.app.ui.settings.AppPickerActivity::class.java,
                )
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("target", "dock"),
            )
        }
    }

    companion object {
        private const val TAG = "TaskbarOverlay"
        private const val POLL_INTERVAL_MS = 1500L
        private const val TASK_ICON_DP = 60
    }
}
