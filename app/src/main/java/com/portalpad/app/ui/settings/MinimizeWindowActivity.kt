package com.portalpad.app.ui.settings

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.DisplayMetrics
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.portalpad.app.PortalPadApp
import com.portalpad.app.applyForcedOrientation
import com.portalpad.app.data.RunningTask
import com.portalpad.app.lockOrientationDefault
import com.portalpad.app.ui.dock.AppIcon
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbSurfaceElevated
import com.portalpad.app.ui.theme.PortalPadTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown ON THE PHONE (top-bar "Minimize" icon, desktop mode) to pick which open
 * external-display window to minimize. Tapping a row minimizes that specific
 * window (parks it off-screen) and closes this screen.
 *
 * Mirrors MaximizeWindowActivity: the list lives on the phone (reliable
 * rendering + real touch) while a toast on the external display tells the user
 * to look at the phone. Previously the top-bar Minimize acted only on the
 * "highlighted" window; this lets the user minimize ANY window directly.
 */
class MinimizeWindowActivity : com.portalpad.app.PinnedDensityActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finishWhenExternalDisplayGone()
        lockOrientationDefault()
        applyForcedOrientation()
        setContent {
            PortalPadTheme {
                MinimizeScreen(onClose = { finish() })
            }
        }
    }

    @Composable
    private fun MinimizeScreen(onClose: () -> Unit) {
        val app = PortalPadApp.instance
        val displayId = app.externalDisplayId.value ?: -1
        var tasks by remember { mutableStateOf<List<RunningTask>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var working by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            tasks = withContext(Dispatchers.IO) {
                runCatching { app.freeform.listExternalTasks() }.getOrDefault(emptyList())
                    .filter { rt ->
                        !rt.packageName.contains("launcher", ignoreCase = true) &&
                            !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                            rt.packageName != "com.portalpad.app"
                    }
            }
            loading = false
        }

        fun minimize(task: RunningTask) {
            if (working) return
            working = true
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    // Close-minimize model: remove the window from the display (no
                    // off-screen sliver) and register its package + bounds so the
                    // dock can relaunch it later.
                    runCatching { app.freeform.minimizeByClose(task.taskId, task.packageName, task.bounds) }
                }
                // Keep the list open for minimizing more — just drop the row we
                // just minimized. Auto-close once nothing's left to minimize.
                tasks = tasks.filter { it.taskId != task.taskId }
                working = false
                if (tasks.isEmpty()) finish()
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(AbBackground)
                .padding(20.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Minimize a window",
                    color = AbOnSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap windows to minimize them, then tap Done.",
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
                        "No open windows to minimize.",
                        color = AbOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    val pkgCounts = tasks.groupingBy { it.packageName }.eachCount()
                    val currentMax = app.freeform.currentlyMaximizedTaskId()
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(tasks, key = { it.taskId }) { task ->
                            val hint = if ((pkgCounts[task.packageName] ?: 0) > 1) positionHint(task) else null
                            val isCurrent = task.taskId == currentMax
                            MinimizeRow(
                                task = task,
                                label = appLabel(task.packageName),
                                subtitle = when {
                                    isCurrent && hint != null -> "Currently maximized \u00B7 $hint"
                                    isCurrent -> "Currently maximized"
                                    else -> hint
                                },
                                enabled = !working,
                                onClick = { minimize(task) },
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
                ) { Text("Done", color = AbOnSurface) }
            }
        }
    }

    @Composable
    private fun MinimizeRow(
        task: RunningTask,
        label: String,
        subtitle: String?,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AbSurfaceElevated)
                .clickable(enabled = enabled) { onClick() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = task.packageName, sizeDp = 40)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    color = AbOnSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = AbOnSurfaceMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    /** A rough where-is-it hint for duplicate-app windows, from their bounds. */
    private fun positionHint(task: RunningTask): String {
        val b = task.bounds ?: return "Window"
        val cx = (b.left + b.right) / 2
        val dm = getSystemService(DisplayManager::class.java)
        val disp = dm?.getDisplay(PortalPadApp.instance.externalDisplayId.value ?: -1)
        val w = if (disp != null) {
            val m = DisplayMetrics().also { @Suppress("DEPRECATION") disp.getRealMetrics(it) }
            m.widthPixels.coerceAtLeast(1)
        } else 1920
        return when {
            cx < w / 3 -> "Left"
            cx > w * 2 / 3 -> "Right"
            else -> "Center"
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
