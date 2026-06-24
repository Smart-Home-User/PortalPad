package com.portalpad.app.ui.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.portalpad.app.PortalPadApp
import com.portalpad.app.applyForcedOrientation
import com.portalpad.app.lockOrientationDefault
import com.portalpad.app.data.RunningTask
import com.portalpad.app.ui.dock.AppIcon
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbDanger
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimaryBright
import com.portalpad.app.ui.theme.AbSurfaceElevated
import com.portalpad.app.ui.theme.PortalPadTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown ON THE PHONE when a window launch is blocked by the cap in desktop mode.
 * Lists the open external-display windows with a Close (✕) button on each, so the
 * user can free a slot, then look back at the glasses and re-tap the app.
 *
 * This is a real Activity (not a WindowManager overlay on the virtual display):
 * a heavy Compose overlay added to the VD reliably hung/killed the foreground
 * service process on-device, so the list lives on the phone where activities
 * render reliably and have real touch input. A brief toast on the glasses tells
 * the user to look at the phone.
 */
class WindowLimitActivity : com.portalpad.app.PinnedDensityActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finishWhenExternalDisplayGone()
        lockOrientationDefault()
        applyForcedOrientation()
        val max = intent.getIntExtra("max", 5)
        // mode: "manage" = top-bar ✕ → Manage on phone; "restore" = dock chip →
        // bring a minimized window back; default "limit" = window cap was hit.
        val mode = intent.getStringExtra("mode")
        val manageMode = mode == "manage"
        val restoreMode = mode == "restore"
        val title = when {
            restoreMode -> "Minimized windows"
            manageMode -> "Manage windows"
            else -> "Window limit reached ($max)"
        }
        val subtitle = when {
            restoreMode -> "Tap a window to bring it back to your display."
            manageMode -> "Close any windows you don't need. They'll close on your display."
            else -> "Close a window here, then look back at your display and tap the app you wanted to open."
        }
        setContent {
            PortalPadTheme {
                WindowLimitScreen(
                    title = title,
                    subtitle = subtitle,
                    restoreMode = restoreMode,
                    onClose = { finish() },
                )
            }
        }
    }

    @Composable
    private fun WindowLimitScreen(
        title: String,
        subtitle: String,
        restoreMode: Boolean,
        onClose: () -> Unit,
    ) {
        val app = PortalPadApp.instance
        val displayId = app.externalDisplayId.value ?: -1
        var tasks by remember { mutableStateOf<List<RunningTask>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }

        // Display pixel size for restore (un-park) bounds.
        fun canvasSize(): Pair<Int, Int> {
            val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
            val disp = dm?.getDisplay(displayId)
            return if (disp != null) {
                val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") disp.getRealMetrics(it) }
                m.widthPixels.coerceAtLeast(1) to m.heightPixels.coerceAtLeast(1)
            } else 1920 to 1080
        }

        suspend fun reload() {
            tasks = withContext(Dispatchers.IO) {
                val all = runCatching { app.freeform.listExternalTasks() }.getOrDefault(emptyList())
                    .filter { rt ->
                        !rt.packageName.contains("launcher", ignoreCase = true) &&
                            !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                            rt.packageName != "com.portalpad.app"
                    }
                // Restore mode lists ONLY the windows the user minimized.
                if (restoreMode) {
                    // Close-minimize model: these are closed windows held in our
                    // registry (package + bounds), not live tasks — so list the
                    // registry directly instead of intersecting with live tasks.
                    app.freeform.closedMinimizedList().map { w ->
                        com.portalpad.app.data.RunningTask(
                            taskId = w.id,
                            packageName = w.packageName,
                            displayId = displayId,
                            bounds = w.bounds,
                        )
                    }
                } else all
            }
            loading = false
        }

        LaunchedEffect(Unit) { reload() }

        Box(
            Modifier
                .fillMaxSize()
                .background(AbBackground)
                .padding(20.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    title,
                    color = AbOnSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(20.dp))

                if (loading) {
                    Text(
                        "Loading open windows\u2026",
                        color = AbOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        textAlign = TextAlign.Center,
                    )
                } else if (tasks.isEmpty()) {
                    Text(
                        if (restoreMode) "No minimized windows." else "No open windows found.",
                        color = AbOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(tasks, key = { it.taskId }) { task ->
                            WindowRow(
                                task = task,
                                label = appLabel(task.packageName),
                                restoreMode = restoreMode,
                                onAction = {
                                    // Optimistically drop the row, then act off-thread.
                                    tasks = tasks.filterNot { it.taskId == task.taskId }
                                    lifecycleScope.launch {
                                        withContext(Dispatchers.IO) {
                                            if (restoreMode) {
                                                val (w, h) = canvasSize()
                                                // Relaunch the closed window at its
                                                // saved bounds (fresh start).
                                                runCatching { app.freeform.restoreClosed(task.taskId, displayId, w, h) }
                                            } else {
                                                runCatching { app.freeform.close(task.taskId) }
                                            }
                                        }
                                        // Restore brings the window back to the display;
                                        // close it on the phone list. In restore mode,
                                        // finish once nothing's left to restore.
                                        if (restoreMode) finish() else reload()
                                    }
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AbSurfaceElevated)
                        .clickable { onClose() }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Done", color = AbPrimaryBright, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    @Composable
    private fun WindowRow(task: RunningTask, label: String, restoreMode: Boolean, onAction: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AbSurfaceElevated)
                // In restore mode the whole row is tappable (bring the window back);
                // in close mode only the trailing ✕ acts.
                .then(if (restoreMode) Modifier.clickable { onAction() } else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = task.packageName, sizeDp = 40)
            Spacer(Modifier.width(14.dp))
            Text(
                label,
                color = AbOnSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(14.dp))
            if (restoreMode) {
                Text("Restore", color = AbPrimaryBright, fontWeight = FontWeight.SemiBold)
            } else {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(AbDanger.copy(alpha = 0.18f))
                        .clickable { onAction() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close $label",
                        tint = AbDanger,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
