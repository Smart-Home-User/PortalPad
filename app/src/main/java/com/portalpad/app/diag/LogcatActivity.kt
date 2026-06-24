package com.portalpad.app.diag

import com.portalpad.app.applyForcedOrientation
import com.portalpad.app.lockOrientationDefault

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.PreferencesRepository
import com.portalpad.app.ui.theme.AbAccent
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimary
import com.portalpad.app.ui.theme.AbSurface
import com.portalpad.app.ui.theme.AbSurfaceElevated
import com.portalpad.app.ui.theme.PortalPadTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live logcat viewer + save-to-file. Routes via Shizuku UserService or
 * Root depending on which backend is ready. If neither is available, shows
 * a clear "needs Shizuku or Root" message.
 *
 * Privacy:
 *   - Default filter silences every tag except PortalPad's own.
 *   - We never log keystrokes, clipboard content, or text-field text.
 *   - First-time use shows a consent dialog explaining what's captured.
 *   - Files are saved to app-private storage (auto-deleted on uninstall).
 */
class LogcatActivity : com.portalpad.app.PinnedDensityActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockOrientationDefault()
        applyForcedOrientation()
        setContent { PortalPadTheme { LogcatScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun LogcatScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = PortalPadApp.instance
    val streamer = app.logcat
    val lines by streamer.lines.collectAsState()
    val state by streamer.state.collectAsState()
    val scope = rememberCoroutineScope()
    var paused by remember { mutableStateOf(false) }
    // When paused, freeze the view to a snapshot taken at the moment of pausing
    // so newly-streamed lines don't scroll the content out from under the user.
    // (The stream keeps filling the buffer in the background; we just stop
    // showing the new lines until Resume.) Without this, "Pause" only stopped
    // auto-scroll while lines kept appending — so it didn't feel paused.
    var frozenLines by remember { mutableStateOf<List<String>>(emptyList()) }
    val displayedLines = if (paused) frozenLines else lines
    var showConsent by remember { mutableStateOf<Boolean?>(null) }
    var showStopConfirm by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Show consent once on first open (per install).
    LaunchedEffect(Unit) {
        val consented = app.prefs.bool(
            PreferencesRepository.Keys.LOGCAT_CONSENT_SHOWN, default = false,
        ).first()
        if (!consented) showConsent = true else streamer.start()
    }

    // Auto-scroll to bottom when new lines arrive and not paused.
    LaunchedEffect(displayedLines.size, paused) {
        if (!paused && displayedLines.isNotEmpty()) {
            listState.scrollToItem(displayedLines.lastIndex)
        }
    }

    if (showConsent == true) {
        AlertDialog(
            onDismissRequest = { showConsent = false; onClose() },
            containerColor = AbSurfaceElevated,
            title = { Text("View internal logs?", color = AbOnSurface) },
            text = {
                Column {
                    Text(
                        "PortalPad's log captures internal activity for debugging:",
                        color = AbOnSurfaceMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("• Click coordinates (no info about what was clicked)", color = AbOnSurfaceMuted)
                    Text("• Key event codes (not actual characters typed)", color = AbOnSurfaceMuted)
                    Text("• Display ids, IME policy changes, gesture events", color = AbOnSurfaceMuted)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Only PortalPad's own tags are captured — other apps' logs are filtered out at the source. Review logs before sharing screenshots or files publicly.",
                        color = AbOnSurfaceMuted,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showConsent = false
                    scope.launch {
                        app.prefs.setBool(PreferencesRepository.Keys.LOGCAT_CONSENT_SHOWN, true)
                    }
                    streamer.start()
                }) { Text("Continue", color = AbPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { showConsent = false; onClose() }) {
                    Text("Cancel", color = AbOnSurfaceMuted)
                }
            },
        )
    }

    Surface(
        color = AbBackground,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            // Header row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Logcat",
                    color = AbOnSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text("Close", color = AbOnSurfaceMuted) }
            }

            Spacer(Modifier.height(4.dp))

            // State chip
            val (stateLabel, stateColor) = when (state) {
                LogcatStreamer.State.RUNNING -> "Streaming" to Color(0xFF4ADE80)
                LogcatStreamer.State.IDLE -> "Idle" to AbOnSurfaceMuted
                LogcatStreamer.State.NO_BACKEND ->
                    "Needs Shizuku or Root" to AbAccent
                LogcatStreamer.State.ERROR -> "Error" to AbAccent
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(stateColor.copy(alpha = 0.18f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(stateColor))
                Spacer(Modifier.width(8.dp))
                Text(stateLabel, color = stateColor, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Text("${displayedLines.size} lines", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))

            // Action row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!paused) {
                            // Pausing: snapshot the current lines so the view freezes.
                            frozenLines = lines
                            paused = true
                        } else {
                            paused = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AbSurfaceElevated, contentColor = AbOnSurface),
                ) { Text(if (paused) "Resume" else "Pause") }
                Button(
                    onClick = { streamer.clearBuffer() },
                    colors = ButtonDefaults.buttonColors(containerColor = AbSurfaceElevated, contentColor = AbOnSurface),
                ) { Text("Clear") }
                Button(
                    onClick = { showStopConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AbSurfaceElevated, contentColor = AbOnSurface),
                ) { Text("Stop") }
                Button(
                    onClick = {
                        scope.launch {
                            val file = saveLogToFile(ctx, streamer.snapshotText())
                            if (file != null) {
                                statusMsg = "Saved ${file.name}"
                                shareFile(ctx, file)
                            } else {
                                statusMsg = "Save failed"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AbPrimary, contentColor = AbOnSurface),
                ) { Text("Save & Share") }
            }

            if (showStopConfirm) {
                AlertDialog(
                    onDismissRequest = { showStopConfirm = false },
                    containerColor = AbSurfaceElevated,
                    title = { Text("Stop capture?", color = AbOnSurface) },
                    text = {
                        Text(
                            "This stops the log stream AND clears all captured lines. " +
                                "You'll need to tap Start again on the previous screen to capture more. " +
                                "Anything not Saved & Shared will be lost.",
                            color = AbOnSurfaceMuted,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showStopConfirm = false
                            paused = false
                            frozenLines = emptyList()
                            streamer.clearBuffer()
                            streamer.stop()
                            // Reset the consent flag so the next time the
                            // user opens Logs, the consent/start dialog
                            // appears again — making "start a fresh
                            // capture" an explicit user action rather than
                            // an implicit "open the screen and capture
                            // resumes."
                            scope.launch {
                                app.prefs.setBool(
                                    PreferencesRepository.Keys.LOGCAT_CONSENT_SHOWN, false,
                                )
                            }
                            statusMsg = "Stopped and cleared"
                            onClose()
                        }) { Text("Stop & clear", color = AbPrimary) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showStopConfirm = false }) {
                            Text("Cancel", color = AbOnSurfaceMuted)
                        }
                    },
                )
            }

            statusMsg?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))

            // Log lines
            Surface(
                color = AbSurface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (displayedLines.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when (state) {
                            LogcatStreamer.State.NO_BACKEND -> Text(
                                "Live logcat requires Shizuku or Root.\nPick a privilege source in System tab.",
                                color = AbOnSurfaceMuted,
                                modifier = Modifier.padding(24.dp),
                            )
                            LogcatStreamer.State.ERROR -> Text(
                                "Could not start logcat. Check Shizuku is bound.",
                                color = AbOnSurfaceMuted,
                                modifier = Modifier.padding(24.dp),
                            )
                            else -> Text(
                                "Waiting for log lines…",
                                color = AbOnSurfaceMuted,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    ) {
                        items(displayedLines) { line ->
                            Text(
                                line,
                                color = colorize(line),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 4,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Color-code by log level (E/W/I/D/V) for quick scanning. */
private fun colorize(line: String): Color {
    // logcat -v threadtime format: "MM-DD HH:MM:SS.mmm  pid  tid LEVEL TAG: msg"
    // We just look for the level letter.
    return when {
        line.contains(" E ") -> Color(0xFFEF4444)
        line.contains(" W ") -> Color(0xFFFBBF24)
        line.contains(" I ") -> Color(0xFF93C5FD)
        line.contains(" D ") -> AbOnSurface
        else -> AbOnSurfaceMuted
    }
}

private fun saveLogToFile(
    ctx: android.content.Context,
    contents: String,
): File? {
    return try {
        val dir = File(ctx.getExternalFilesDir(null), "logs")
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "portalpad-$ts.txt")
        file.writeText(buildString {
            appendLine("# PortalPad log capture")
            appendLine("# Timestamp: $ts")
            appendLine("# App version: ${com.portalpad.app.BuildConfig.VERSION_NAME} (${com.portalpad.app.BuildConfig.VERSION_CODE})")
            appendLine("# Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("# Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("# Filter: PortalPad-only (other tags silenced at the source)")
            appendLine()
            append(contents)
        })
        file
    } catch (t: Throwable) {
        android.util.Log.e("LogcatScreen", "save failed", t); null
    }
}

private fun shareFile(ctx: android.content.Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "PortalPad log: ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Share log").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (t: Throwable) {
        android.util.Log.e("LogcatScreen", "share failed", t)
    }
}
