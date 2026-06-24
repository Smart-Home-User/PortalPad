package com.portalpad.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.AccessMode
import com.portalpad.app.shizuku.ShizukuManager
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbSuccess

/**
 * The access-mode status indicator, identical to the one on the main mode strip:
 * bold "Mode: " + the active source ("Shizuku" / "Root" / "Accessibility" /
 * "None"), coloured bright when the source is fully connected and muted when not,
 * followed by a green check (connected) or a yellow warning (limited).
 *
 * Extracted so the trackpad / air-mouse / remote interfaces show the SAME live
 * readout as the strip — when Shizuku drops, the indicator flips to warning right
 * on the surface you're looking at. Single source of truth, so all copies stay
 * consistent.
 *
 * In the warning state (Shizuku selected but not ready), a tap anywhere on the
 * strip opens the in-app [ShizukuControlDialog] (Start/Stop + Open Shizuku); a
 * long-press opens it in any state. In the healthy state the strip is a plain
 * label (the green check is never a separate tap target).
 */
@Composable
fun ModeStatusLabel(
    modifier: Modifier = Modifier,
) {
    val app = PortalPadApp.instance
    val accessMode by app.prefs.accessMode.collectAsState(initial = AccessMode.NONE)
    val shizukuStatus by app.shizuku.status.collectAsState()
    val shizukuReady = shizukuStatus == ShizukuManager.Status.READY
    val rootReady = app.root.isReady

    val modeValue = when (accessMode) {
        AccessMode.SHIZUKU -> "Shizuku"
        AccessMode.ROOT -> "Root"
        AccessMode.NONE -> "None"
    }
    val fullFeatures = when (accessMode) {
        AccessMode.SHIZUKU -> shizukuReady
        AccessMode.ROOT -> rootReady
        AccessMode.NONE -> false
    }
    // Healthy → same white as the "Service" label; warning → same yellow as the
    // warning icon, so text + icon read as one unit (and the now-tappable strip
    // clearly signals "fix me").
    val modeColor = if (fullFeatures) AbOnSurface else Color(0xFFE6B800)
    // Discoverability hint shown only in the (tappable) warning state. None routes to
    // Start > Setup; Shizuku/Root run their in-place fix (popup / root grant).
    val warnSuffix = if (accessMode == AccessMode.NONE) "· Tap to set up" else "· Tap to fix"

    var showShizukuDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Warning-state action, by mode:
    //   Shizuku → open the in-app Shizuku control popup.
    //   Root    → request root (runs `su` off-thread to trigger the grant prompt,
    //             then binds the root backend if granted).
    //   None    → open the main app's Start > Setup page (guided privilege setup).
    val onModeWarning: () -> Unit = {
        when (accessMode) {
            AccessMode.SHIZUKU -> showShizukuDialog = true
            AccessMode.ROOT -> {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    app.root.refresh()
                    if (app.root.isReady) {
                        app.root.grantInjectEvents()
                        app.rootClickBackend.bind()
                    }
                }
            }
            AccessMode.NONE -> context.startActivity(
                Intent(context, com.portalpad.app.MainActivity::class.java)
                    .putExtra(com.portalpad.app.MainActivity.EXTRA_OPEN_SETUP, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    Row(
        modifier.pointerInput(accessMode, fullFeatures) {
            detectTapGestures(
                onTap = {
                    // Warning state: a single tap runs the mode-appropriate fix and
                    // buzzes. Healthy state does nothing on tap (no action → no haptic).
                    if (!fullFeatures) {
                        app.injector.buzz(longPress = false)
                        onModeWarning()
                    }
                },
                onLongPress = {
                    // Long-press always opens it (e.g. to Stop Shizuku while healthy).
                    if (accessMode == AccessMode.SHIZUKU) {
                        app.injector.buzz(longPress = true)
                        showShizukuDialog = true
                    }
                },
            )
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append("Mode: ")
                }
                append(modeValue)
            },
            color = modeColor,
            fontWeight = FontWeight.Normal,
            style = MaterialTheme.typography.bodySmall,
        )
        if (!fullFeatures) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.Warning,
                contentDescription = "Limited features — tap the strip to fix",
                tint = Color(0xFFE6B800),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                warnSuffix,
                color = Color(0xFFE6B800),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "$modeValue connected",
                tint = AbSuccess,
                modifier = Modifier.size(16.dp),
            )
        }
    }

    if (showShizukuDialog) {
        ShizukuControlDialog(onDismiss = { showShizukuDialog = false })
    }
}

/**
 * Long-press "Mode: Shizuku" to open this. Lets you Start/Stop Shizuku from
 * inside PortalPad by broadcasting Shizuku's automation intents — the same
 * mechanism Tasker uses (no root needed to *send* the intent). You paste the
 * "auth" Extras value once (from Shizuku's "View intents"); it's saved and sent
 * with every Start/Stop. Buttons are disabled until a key is saved, and the
 * Shizuku status is re-checked shortly after each action so you see the result.
 */
@Composable
internal fun ShizukuControlDialog(onDismiss: () -> Unit) {
    val app = PortalPadApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedKey by app.prefs.shizukuAuthKey.collectAsState(initial = "")
    val status by app.shizuku.status.collectAsState()
    var keyField by remember(savedKey) { mutableStateOf(savedKey) }
    val hasKey = savedKey.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = {
                val pm = context.packageManager
                val launch = pm.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (launch != null) {
                    runCatching {
                        context.startActivity(
                            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }) { Text("Open Shizuku") }
        },
        title = { Text("Shizuku control") },
        text = {
            Column {
                Text(
                    "Status: ${status.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AbOnSurfaceMuted,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            sendShizukuControl(context, start = true, auth = savedKey)
                            scope.launch { delay(1200); app.shizuku.refresh() }
                        },
                        enabled = hasKey && status != ShizukuManager.Status.READY,
                        modifier = Modifier.weight(1f),
                    ) { Text("Start") }
                    Button(
                        onClick = {
                            sendShizukuControl(context, start = false, auth = savedKey)
                            scope.launch { delay(1200); app.shizuku.refresh() }
                        },
                        enabled = hasKey && (status == ShizukuManager.Status.READY ||
                            status == ShizukuManager.Status.NEEDS_PERMISSION),
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop") }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    app.shizukuClickBackend.unbind()
                                    delay(300)
                                    app.shizukuClickBackend.bind()
                                }
                            }
                            delay(1500)
                            app.shizuku.refresh()
                        }
                    },
                    // Rebinds our user-service binder — no auth key needed. ALWAYS
                    // enabled: its whole purpose is recovering a wedged/dead binder,
                    // which is exactly when status ISN'T READY — so gating it on READY
                    // defeated the point (and made it unavailable on the trackpad/remote
                    // mode popups whenever the binder dropped). The action is
                    // runCatching-wrapped, so it's a harmless no-op if Shizuku isn't
                    // installed/running.
                    enabled = true,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restart Shizuku service") }
                if (!hasKey) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Save your auth key below to enable Start / Stop.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE6B800),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text("Extras (auth key)", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = keyField,
                    onValueChange = { keyField = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Auth keys are stored locally on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AbOnSurfaceMuted,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { scope.launch { app.prefs.setShizukuAuthKey(keyField.trim()) } }) {
                        Text("Save")
                    }
                    Spacer(Modifier.width(10.dp))
                    if (hasKey) {
                        Text(
                            "Saved: $savedKey",
                            style = MaterialTheme.typography.bodySmall,
                            color = AbOnSurfaceMuted,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Open the Shizuku app \u2192 under \"Control Shizuku with automation apps\" tap " +
                        "\"View intents\" \u2192 copy the code for Extras (auth key) \u2192 come back here " +
                        "and paste it above, then Save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AbOnSurfaceMuted,
                )
            }
        },
    )
}

/** Broadcast Shizuku's automation Start/Stop intent with the saved auth Extras. */
internal fun sendShizukuControl(context: Context, start: Boolean, auth: String) {
    if (auth.isBlank()) return
    val action = if (start) "moe.shizuku.privileged.api.START" else "moe.shizuku.privileged.api.STOP"
    runCatching {
        val intent = Intent(action).apply {
            setPackage("moe.shizuku.privileged.api")
            putExtra("auth", auth)
        }
        context.sendBroadcast(intent)
    }
}
