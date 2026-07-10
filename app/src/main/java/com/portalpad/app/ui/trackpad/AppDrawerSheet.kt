package com.portalpad.app.ui.trackpad

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.AppEntry
import com.portalpad.app.data.DockItem
import com.portalpad.app.ui.dock.AppIcon
import com.portalpad.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Searchable app drawer shown as a modal bottom sheet on the trackpad
 * (phone) side. Lists all launchable apps with a search box. Tapping an app
 * presents a small "Launch / Add to dock" choice:
 *   • Launch  → launches the app on the external display (via [onLaunch])
 *   • Add     → appends a shortcut to the dock config
 *
 * Reachable from the bottom-bar App Drawer button, the three-finger swipe,
 * and the dock's "+" tile (all open this same sheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawerSheet(
    onLaunch: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var apps by remember { mutableStateOf<List<DrawerApp>>(emptyList()) }
    var chosen by remember { mutableStateOf<DrawerApp?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadDrawerApps(context.packageManager) }
    }

    val filtered = remember(query, apps) {
        if (query.text.isBlank()) apps
        else com.portalpad.app.ui.common.SearchRank
            .filterApps(apps, query.text) { it.label }
            .ifEmpty {
                // Legacy fallback: package-name substring (lets power users
                // find apps by package when the label doesn't match at all).
                apps.filter { it.packageName.contains(query.text, ignoreCase = true) }
            }
    }

    // ─── In-sheet voice dictation ───────────────────────────────────────────
    // Tapping the mic dictates into THIS sheet's search box (no separate page).
    // Dictate-only: fills `query`, filters the list, the user taps a result.
    var listening by remember { mutableStateOf(false) }
    var voiceMsg by remember { mutableStateOf<String?>(null) }
    val speechRef = remember { mutableStateOf<android.speech.SpeechRecognizer?>(null) }
    DisposableEffect(Unit) {
        onDispose { runCatching { speechRef.value?.destroy() }; speechRef.value = null }
    }

    fun beginDrawerVoice() {
        if (listening) return
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
            voiceMsg = "Voice recognition unavailable on this device"; return
        }
        listening = true; voiceMsg = "Listening…"
        runCatching { speechRef.value?.destroy() }
        val sr = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
        speechRef.value = sr
        sr.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                listening = false
                voiceMsg = "Didn't catch that — tap the mic to retry or type"
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            override fun onResults(results: android.os.Bundle?) {
                listening = false; voiceMsg = null
                val text = results?.getStringArrayList(
                    android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                )?.firstOrNull().orEmpty()
                if (text.isNotBlank()) query = TextFieldValue(text)
            }
        })
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(
                android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L,
            )
            putExtra(
                android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                2000L,
            )
        }
        runCatching { sr.startListening(intent) }
            .onFailure { listening = false; voiceMsg = "Couldn't start the microphone" }
    }

    // Mic permission: request via the host Activity; start voice once granted.
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) beginDrawerVoice()
        else voiceMsg = "Microphone permission needed for voice search"
    }
    fun onMicTap() {
        val granted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) beginDrawerVoice() else micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AbBackground,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 360.dp, max = 560.dp)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "Apps",
                color = AbOnSurface,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search apps", color = AbOnSurfaceMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AbOnSurfaceMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AbOnSurface, unfocusedTextColor = AbOnSurface,
                        focusedBorderColor = AbPrimary, unfocusedBorderColor = AbSurfaceElevated,
                        cursorColor = AbPrimaryBright,
                    ),
                    modifier = Modifier.weight(1f).padding(bottom = 8.dp),
                )
                Spacer(Modifier.size(8.dp))
                // Mic → dictates into THIS search box (in-sheet, no new page).
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (listening) AbPrimary else AbSurfaceElevated)
                        .clickable { onMicTap() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Voice search",
                        tint = if (listening) Color.White else AbPrimaryBright,
                    )
                }
            }
            voiceMsg?.let {
                Text(
                    it,
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filtered, key = { it.packageName + (it.componentName ?: "") }) { item ->
                        Column {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { chosen = item }
                                    .padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(packageName = item.packageName, sizeDp = 40)
                                Spacer(Modifier.size(12.dp))
                                Text(
                                    item.label,
                                    color = AbOnSurface,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(end = 12.dp)
                                    .height(1.dp)
                                    .background(Color(0x14FFFFFF)),
                            )
                        }
                    }
                }
                com.portalpad.app.ui.common.VerticalScrollbar(
                    listState = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(4.dp),
                )
            }
        }
    }

    // Per-app action chooser: Launch now, or add to the dock.
    chosen?.let { item ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            containerColor = AbSurfaceElevated,
            title = { Text(item.label, color = AbOnSurface) },
            text = {
                Text(
                    "Launch this app on the external display, or add it to your dock?",
                    color = AbOnSurfaceMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onLaunch(item.entry)
                    chosen = null
                    onDismiss()
                }) { Text("Launch", color = AbAccent) }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch {
                        val app = PortalPadApp.instance
                        val cfg = app.prefs.dockConfig.first()
                        val shortcut = DockItem.Shortcut(
                            id = "sc_${System.currentTimeMillis()}_${item.packageName.hashCode()}",
                            label = item.entry.label,
                            app = item.entry,
                        )
                        app.prefs.setDockConfig(cfg.addShortcut(shortcut))
                    }
                    chosen = null
                }) { Text("Add to dock", color = AbPrimaryBright) }
            },
        )
    }
}

private data class DrawerApp(
    val label: String,
    val packageName: String,
    val componentName: String?,
    val entry: AppEntry,
)

private fun loadDrawerApps(pm: PackageManager): List<DrawerApp> {
    val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = pm.queryIntentActivities(launchIntent, 0)
    return resolved
        .map { it.activityInfo.packageName }
        .distinct()
        .mapNotNull { pkg ->
            val main = resolved.firstOrNull { it.activityInfo.packageName == pkg } ?: return@mapNotNull null
            val label = main.loadLabel(pm).toString()
            DrawerApp(label, pkg, null, AppEntry(pkg, null, label))
        }
        .sortedBy { it.label.lowercase() }
}
