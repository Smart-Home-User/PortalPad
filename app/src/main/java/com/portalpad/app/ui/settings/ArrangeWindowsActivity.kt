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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.portalpad.app.data.RunningTask
import com.portalpad.app.data.WindowBounds
import com.portalpad.app.lockOrientationDefault
import com.portalpad.app.ui.dock.AppIcon
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimaryBright
import com.portalpad.app.ui.theme.AbSurfaceElevated
import com.portalpad.app.ui.theme.PortalPadTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown ON THE PHONE (top-bar tiles icon, desktop mode) to choose the order in
 * which the open external-display windows tile, 1st → last, then arrange them
 * into even columns in that order.
 *
 * Reuses the same proven path as the top bar's "Arrange evenly" — freeform
 * resize + WindowBounds.evenColumn — but iterates the user's chosen order
 * instead of the raw task list. Like WindowLimitActivity, the list lives on the
 * phone (reliable rendering + real touch); a toast on the external display tells
 * the user to look at the phone.
 */
class ArrangeWindowsActivity : com.portalpad.app.PinnedDensityActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finishWhenExternalDisplayGone()
        lockOrientationDefault()
        applyForcedOrientation()
        setContent {
            PortalPadTheme {
                ArrangeScreen(onClose = { finish() })
            }
        }
    }

    @Composable
    private fun ArrangeScreen(onClose: () -> Unit) {
        val app = PortalPadApp.instance
        val displayId = app.externalDisplayId.value ?: -1
        var tasks by remember { mutableStateOf<List<RunningTask>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var arranging by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            tasks = withContext(Dispatchers.IO) {
                runCatching { app.freeform.listTasks(displayId) }.getOrDefault(emptyList())
                    .filter { rt ->
                        !rt.packageName.contains("launcher", ignoreCase = true) &&
                            !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                            rt.packageName != "com.portalpad.app"
                    }
                    // Match the top bar's "Arrange evenly" ordering: left-to-right by
                    // current left edge (nulls last). Without this, listTasks' raw
                    // task-stack order (recency/z-order) put windows in a different
                    // sequence than the auto-tile, so a window that tiled first showed
                    // up further down this list. Same sort key = same starting order.
                    .sortedBy { it.bounds?.left ?: Int.MAX_VALUE }
            }
            loading = false
        }

        fun move(index: Int, delta: Int) {
            val to = index + delta
            if (to < 0 || to >= tasks.size) return
            tasks = tasks.toMutableList().also { it.add(to, it.removeAt(index)) }
        }

        // Resolve the external display's pixel size for the tiling math.
        fun canvasSize(): Pair<Int, Int> {
            val dm = getSystemService(DisplayManager::class.java)
            val disp = dm?.getDisplay(displayId)
            return if (disp != null) {
                val m = DisplayMetrics().also { @Suppress("DEPRECATION") disp.getRealMetrics(it) }
                m.widthPixels.coerceAtLeast(1) to m.heightPixels.coerceAtLeast(1)
            } else 1920 to 1080
        }

        fun arrange() {
            if (tasks.isEmpty() || arranging) return
            arranging = true
            val ordered = tasks.toList()
            val (w, h) = canvasSize()
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val n = ordered.size
                    ordered.forEachIndexed { i, task ->
                        runCatching { app.freeform.resize(task.taskId, WindowBounds.evenColumn(i, n, w, h)) }
                        // Small gap so a burst of resizes doesn't race the shell.
                        Thread.sleep(60)
                    }
                }
                finish()
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
                    "Arrange windows",
                    color = AbOnSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Order the windows top-to-bottom here; they'll tile left-to-right across your display in that order.",
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
                        "No open windows to arrange.",
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
                            val index = tasks.indexOf(task)
                            ArrangeRow(
                                position = index + 1,
                                task = task,
                                label = appLabel(task.packageName),
                                canMoveUp = index > 0,
                                canMoveDown = index < tasks.size - 1,
                                onUp = { move(index, -1) },
                                onDown = { move(index, +1) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AbSurfaceElevated)
                            .clickable { onClose() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Cancel", color = AbOnSurface) }
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (tasks.isEmpty() || arranging) AbSurfaceElevated else AbPrimaryBright)
                            .clickable(enabled = tasks.isNotEmpty() && !arranging) { arrange() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (arranging) "Arranging\u2026" else "Arrange",
                            color = if (tasks.isEmpty() || arranging) AbOnSurfaceMuted else AbBackground,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ArrangeRow(
        position: Int,
        task: RunningTask,
        label: String,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        onUp: () -> Unit,
        onDown: () -> Unit,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AbSurfaceElevated)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$position",
                color = AbOnSurfaceMuted,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(24.dp),
            )
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
            Spacer(Modifier.width(8.dp))
            ArrowButton(Icons.Default.KeyboardArrowUp, "Move up", canMoveUp, onUp)
            Spacer(Modifier.width(8.dp))
            ArrowButton(Icons.Default.KeyboardArrowDown, "Move down", canMoveDown, onDown)
        }
    }

    @Composable
    private fun ArrowButton(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        desc: String,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (enabled) AbPrimaryBright.copy(alpha = 0.18f) else Color.Transparent)
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = desc,
                tint = if (enabled) AbPrimaryBright else AbOnSurfaceMuted.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp),
            )
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
