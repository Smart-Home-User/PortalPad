package com.portalpad.app.ui.power

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.PreferencesRepository
import com.portalpad.app.ui.theme.AbDanger
import com.portalpad.app.ui.theme.AbOnSurface
import kotlinx.coroutines.launch

private const val EXTINGUISH_PACKAGE = "own.moderpach.extinguish"
private const val EXTINGUISH_SERVICE_CLASS = "own.moderpach.extinguish.service.ExtinguishService"
private const val TAG = "ExtinguishPowerButton"

/**
 * Red power button that turns off the phone screen via the Extinguish app
 * while leaving the external display active. Reusable across Remote / Air
 * Mouse / Trackpad tabs to keep the gesture consistent across the app.
 *
 * Behavior:
 *  - Tap: send screen-off command. The first time, an explainer dialog
 *    appears so the user understands what the button does and how to wake
 *    the phone back up. Subsequent taps fire without ceremony.
 *  - Long-press: open Extinguish for settings configuration.
 *  - If Extinguish isn't installed (checked via getPackageInfo before each
 *    attempt), an install dialog appears with a Play Store link.
 *
 * The "screen off" command itself fires as on→off (200ms gap) — Extinguish
 * tracks its own state, and that state can drift from reality when the
 * screen wakes via other means. Always cycling through "on" forces a known
 * starting state before the "off" command takes effect.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExtinguishPowerButton(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
) {
    val app = PortalPadApp.instance
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var extinguishMissing by remember { mutableStateOf(false) }
    var screenOffExplainer by remember { mutableStateOf(false) }
    var showPowerMenu by remember { mutableStateOf(false) }
    val explainerSeen = app.prefs
        .bool(PreferencesRepository.Keys.SCREEN_OFF_EXPLAINED, false)
        .collectAsState(initial = false).value

    fun extinguishInstalled(): Boolean = try {
        ctx.packageManager.getPackageInfo(EXTINGUISH_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    // Stop the Extinguish service via Shizuku shell. This is Extinguish's own
    // documented "stop" command (start the service with the stop=1 int extra).
    // Replaces the old "screen ON" step: stopping the service also clears the
    // IME wedge Extinguish leaves after a screen on/off cycle (which strands the
    // phone keyboard across Android apps). Requires the Shizuku backend.
    fun stopExtinguishService() {
        val cmd = "am startservice -n $EXTINGUISH_PACKAGE/.service.ExtinguishService --ei stop 1"
        val backend = PortalPadApp.instance.clickBackend
        val shizuku = (backend as? com.portalpad.app.service.ClickBackend.ShizukuUserService)?.backend
        if (shizuku == null || !shizuku.isReady) {
            Log.w(TAG, "stopExtinguishService SKIPPED: Shizuku not ready (backend=${backend?.javaClass?.simpleName})")
            return
        }
        val out = runCatching { shizuku.runCommand(cmd) }.getOrElse { "ERR:${it.message}" }
        Log.d(TAG, "stopExtinguishService: ran `$cmd` -> $out")
    }

    // Start the Extinguish service back up (no screen command) so the user can
    // still use Extinguish elsewhere after we stop it. Bare start — the service
    // just runs idle, available, without triggering any screen on/off.
    fun startExtinguishService() {
        val cmd = "am startservice -n $EXTINGUISH_PACKAGE/.service.ExtinguishService"
        val backend = PortalPadApp.instance.clickBackend
        val shizuku = (backend as? com.portalpad.app.service.ClickBackend.ShizukuUserService)?.backend
        if (shizuku == null || !shizuku.isReady) {
            Log.w(TAG, "startExtinguishService SKIPPED: Shizuku not ready")
            return
        }
        val out = runCatching { shizuku.runCommand(cmd) }.getOrElse { "ERR:${it.message}" }
        Log.d(TAG, "startExtinguishService: ran `$cmd` -> $out")
    }

    fun fireExtinguishIntent(screenState: Int) {
        val stateLabel = if (screenState == 0) "ON(0)" else "OFF(1)"
        Log.d(TAG, "fireExtinguishIntent: dispatching screen=$stateLabel at ${System.currentTimeMillis()}")
        val intent = Intent().apply {
            component = ComponentName(EXTINGUISH_PACKAGE, EXTINGUISH_SERVICE_CLASS)
            putExtra("screen", screenState)
        }
        // Use startForegroundService for cross-app service starts on O+
        // (background-start restrictions otherwise throw IllegalStateException).
        // Wrap in try/catch but DON'T set extinguishMissing here — startService
        // failure could be transient (background restriction, race during
        // wake state, etc.) and shouldn't be misreported as "app not installed."
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            Log.d(TAG, "fireExtinguishIntent: startService OK for screen=$stateLabel")
        } catch (t: Throwable) {
            Log.w(TAG, "fireExtinguishIntent: startService FAILED for screen=$stateLabel — ${t.javaClass.simpleName}: ${t.message}", t)
            // Show a brief toast so the user knows the tap didn't silently
            // vanish, but don't claim the app isn't installed.
            Toast.makeText(ctx, "Extinguish couldn't run — try again", Toast.LENGTH_SHORT).show()
        }
    }

    fun actuallySendScreenOff() {
        // Stop-service → off sequence. The stop (replacing the old "screen ON"
        // step) clears Extinguish's lingering IME-wedge state; then the OFF
        // intent turns the phone screen off (and restarts the service fresh).
        // 200ms lets the stop dispatch before the off.
        Log.d(TAG, "actuallySendScreenOff: stop-service → off sequence")
        stopExtinguishService()
        Handler(Looper.getMainLooper()).postDelayed({
            fireExtinguishIntent(1)
        }, 200)
    }

    fun sendScreenOff() {
        Log.d(TAG, "sendScreenOff: invoked (installed=${extinguishInstalled()}, explainerSeen=$explainerSeen)")
        if (!extinguishInstalled()) { extinguishMissing = true; return }
        if (!explainerSeen) {
            // First time ever — show the dialog. The off command fires only
            // after the user taps "Got it" (so the screen doesn't go dark
            // while they're reading the explainer).
            Log.d(TAG, "sendScreenOff: first-run, showing explainer (off fires on Got it)")
            screenOffExplainer = true
            return
        }
        actuallySendScreenOff()
    }

    fun openExtinguish() {
        if (!extinguishInstalled()) { extinguishMissing = true; return }
        val launch = ctx.packageManager.getLaunchIntentForPackage(EXTINGUISH_PACKAGE)
        if (launch != null) {
            // Fresh launch: CLEAR_TASK (+ NEW_TASK) resets Extinguish to its
            // start rather than resuming where it left off.
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )
            ctx.startActivity(launch)
        } else {
            extinguishMissing = true
        }
    }

    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(AbDanger)
            .border(1.dp, androidx.compose.ui.graphics.Color(0xFFEDEDE8), CircleShape)
            .combinedClickable(
                onClick = {
                    Log.d(TAG, "power button TAP")
                    com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                    sendScreenOff()
                },
                onLongClick = {
                    Log.d(TAG, "power button LONG-PRESS → menu")
                    com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = true)
                    showPowerMenu = true
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.PowerSettingsNew,
            contentDescription = "Turn off phone screen",
            tint = AbOnSurface,
            modifier = Modifier.size(size * 0.55f),
        )
    }

    // Install dialog — shown when Extinguish package isn't present.
    if (extinguishMissing) {
        AlertDialog(
            onDismissRequest = { extinguishMissing = false },
            title = { Text("Extinguish required") },
            text = {
                Column {
                    Text(
                        "Tap turns off the phone screen while keeping the external display on. " +
                            "Long-press opens Extinguish settings.\n\n" +
                            "Extinguish handles screen power for PortalPad. It isn't installed yet.",
                    )
                    Spacer(Modifier.size(16.dp))
                    com.portalpad.app.ui.common.OpenInRow(
                        centered = true,
                        destinations = listOf(
                            com.portalpad.app.ui.common.playStoreDest {
                                extinguishMissing = false
                                val market = Uri.parse("market://details?id=$EXTINGUISH_PACKAGE")
                                val web = Uri.parse("https://play.google.com/store/apps/details?id=$EXTINGUISH_PACKAGE")
                                val intent = Intent(Intent.ACTION_VIEW, market)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                try { ctx.startActivity(intent) }
                                catch (_: android.content.ActivityNotFoundException) {
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW, web).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                            com.portalpad.app.ui.common.gitHubDest {
                                extinguishMissing = false
                                runCatching {
                                    ctx.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://github.com/Moderpach/Extinguish"),
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        ),
                    )
                }
            },
            confirmButton = {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { extinguishMissing = false },
                        modifier = Modifier.align(Alignment.Center),
                    ) { Text("Cancel") }
                }
            },
        )
    }

    // First-time screen-off explainer. Shown once, then a pref flag
    // suppresses it forever. Styled to match the long-press power menu
    // (AbSurface + AbPrimaryDim border) rather than a stock AlertDialog.
    if (screenOffExplainer) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { screenOffExplainer = false },
        ) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(com.portalpad.app.ui.theme.AbSurface)
                    .border(1.dp, com.portalpad.app.ui.theme.AbPrimaryDim, RoundedCornerShape(18.dp))
                    .padding(20.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Turning off phone screen",
                    color = AbOnSurface,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                Text(
                    "The external display stays on. To wake your phone, you'll need a " +
                        "wake method configured in Extinguish (volume key, etc.).",
                    color = AbOnSurface,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                        append("Note: ")
                        pop()
                        append(
                            "In Extinguish → Volume key control → (gear icon) → " +
                                "\"Method of getting volume key event,\" make sure Shell is " +
                                "selected, not Android window. For example, if it's set to " +
                                "Android window, the volume keys are intercepted at the window " +
                                "layer — which can stop your keyboard from appearing in text " +
                                "fields, and may interfere with app focus and gesture navigation. " +
                                "Shell mode avoids that.",
                        )
                    },
                    color = AbDanger,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                ) {
                    TextButton(onClick = {
                        screenOffExplainer = false
                        scope.launch {
                            app.prefs.setBool(PreferencesRepository.Keys.SCREEN_OFF_EXPLAINED, true)
                        }
                        openExtinguish()
                    }) { Text("Configure Extinguish") }
                    TextButton(onClick = {
                        Log.d(TAG, "Got it tapped → firing screen-off")
                        screenOffExplainer = false
                        scope.launch {
                            app.prefs.setBool(PreferencesRepository.Keys.SCREEN_OFF_EXPLAINED, true)
                        }
                        actuallySendScreenOff()
                    }) { Text("Got it") }
                }
            }
        }
    }

    // Long-press power menu — styled like the trackpad's Home/Back menu,
    // anchored near the button (bottom-center, lifted up to finger reach).
    if (showPowerMenu) {
        androidx.compose.ui.window.Popup(
            alignment = Alignment.BottomCenter,
            offset = androidx.compose.ui.unit.IntOffset(0, -220),
            onDismissRequest = { showPowerMenu = false },
            properties = androidx.compose.ui.window.PopupProperties(focusable = true),
        ) {
            Column(
                Modifier
                    .widthIn(max = 340.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(com.portalpad.app.ui.theme.AbSurface)
                    .border(
                        1.dp,
                        com.portalpad.app.ui.theme.AbPrimaryDim,
                        RoundedCornerShape(18.dp),
                    )
                    .padding(8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Phone Screen",
                    color = com.portalpad.app.ui.theme.AbOnSurfaceMuted,
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                )
                PowerMenuRow("Stop Extinguish Service") {
                    showPowerMenu = false
                    com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                    stopExtinguishService()
                }
                PowerMenuRow("Turn Off Phone Screen, Keep External Display On") {
                    showPowerMenu = false
                    com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                    sendScreenOff()
                }
                PowerMenuRow("Open Extinguish App for More Settings") {
                    showPowerMenu = false
                    com.portalpad.app.PortalPadApp.instance.injector.buzz(longPress = false)
                    openExtinguish()
                }
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                        append("Note: ")
                        pop()
                        append(
                            "In Extinguish → Volume key control → (gear icon) → " +
                                "\"Method of getting volume key event,\" make sure Shell is " +
                                "selected, not Android window. For example, if it's set to " +
                                "Android window, the volume keys are intercepted at the window " +
                                "layer — which can stop your keyboard from appearing in text " +
                                "fields, and may interfere with app focus and gesture navigation. " +
                                "Shell mode avoids that.",
                        )
                    },
                    color = AbDanger,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PowerMenuRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = com.portalpad.app.ui.theme.AbPrimaryBright,
        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(com.portalpad.app.ui.theme.AbSurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}
