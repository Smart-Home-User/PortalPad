package com.portalpad.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import com.portalpad.app.ui.common.VerticalScrollbar
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalpad.app.data.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.core.graphics.drawable.toBitmap
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimary
import com.portalpad.app.ui.theme.AbPrimaryBright
import com.portalpad.app.ui.theme.AbSurfaceElevated
import com.portalpad.app.ui.theme.PortalPadTheme

/**
 * Phone-side app search + voice launcher. Runs on the PHONE screen (reliable
 * keyboard, microphone, and runtime-permission prompts — none of which behave
 * well in a secondary-display overlay). The chosen app launches on the EXTERNAL
 * display via [PortalPadApp.launchAppOnExternal], honoring desktop-window vs
 * fullscreen mode just like a dock tap.
 *
 * Launched from the dock's Search / Mic tiles. Pass EXTRA_START_VOICE=true to
 * begin listening immediately (the Mic tile).
 */
class SearchActivity : PinnedDensityActivity() {

    private var speech: SpeechRecognizer? = null

    /** True when this activity was opened via the dock Mic tile (voice-only
     *  entry) — drives the "drop to home, keep listening, finish when done"
     *  behavior so the user isn't left staring at this screen. */
    private var voiceLaunchEntry: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockOrientationDefault()
        applyForcedOrientation()
        val startVoice = intent?.getBooleanExtra(EXTRA_START_VOICE, false) ?: false
        voiceLaunchEntry = startVoice
        // For the voice entry point, only play the listening cue immediately if
        // mic permission is ALREADY granted (so we're truly about to listen). If
        // permission isn't granted yet, we must ask first — and we don't want a
        // misleading "listening" beep before that's resolved. The grant flow
        // (onRequestPermissionsResult) plays the cue + starts voice after accept.
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (startVoice && micGranted) playMicCue()
        // If voice was requested without permission, ask for it up front so the
        // permission dialog is the first thing the user sees (not competing with
        // the app list). Voice auto-starts on grant via onRequestPermissionsResult.
        if (startVoice && !micGranted) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        setContent { PortalPadTheme { SearchUi(startVoice) } }
        finishWhenExternalDisplayGone()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { speech?.destroy() }; speech = null
    }

    // Set by the composable so the activity can start voice after a permission
    // grant. Invoked from onRequestPermissionsResult.
    private var pendingVoiceStart: (() -> Unit)? = null

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                // Permission accepted — now play the cue and start listening.
                pendingVoiceStart?.invoke()
            }
            // If denied, do nothing special: the normal search UI (app list +
            // keyboard) is already there to fall back on.
        }
    }

    private fun launch(entry: AppEntry) {
        val component = if (entry.isActivity) "${entry.packageName}/${entry.componentName}"
        else packageManager.getLaunchIntentForPackage(entry.packageName)?.component?.flattenToString()
        if (component != null) {
            (applicationContext as PortalPadApp).launchAppOnExternal(component)
        }
        finish()
    }

    private fun startVoice(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            onError("Grant microphone permission, then tap the mic again")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onError("Voice recognition unavailable on this device"); return
        }
        runCatching { speech?.destroy() }
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        speech = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { onError("Didn't catch that — try again") }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                onResult(text)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Give the user a comfortable window to start/finish speaking. These
            // are HINTS — many recognizers honor them only partially — but they
            // cost nothing and help on devices that respect them.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L,
            )
        }
        runCatching { sr.startListening(intent) }.onFailure { onError("Couldn't start the microphone") }
    }

    /** Audible beep + short haptic so the user knows the mic is now listening. */
    private fun playMicCue() {
        runCatching {
            val tone = android.media.ToneGenerator(
                android.media.AudioManager.STREAM_MUSIC, 80,
            )
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
            // Release after the tone finishes so we don't leak the generator.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { runCatching { tone.release() } }, 250,
            )
        }
        performTapHaptic()
    }

    /** A short tactile tick for taps on the phone (mic start, app launch). */
    private fun performTapHaptic() {
        runCatching {
            // Honor the global vibration-strength pref (0 = off).
            val ms = runCatching {
                kotlinx.coroutines.runBlocking {
                    (applicationContext as PortalPadApp).prefs.vibrationMs
                        .first()
                }
            }.getOrDefault(25)
            if (ms <= 0) return
            val vib = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (!vib.hasVibrator()) return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vib.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        ms.toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ms.toLong())
            }
        }
    }

    @Composable
    private fun SearchUi(startVoice: Boolean) {
        val ctx = LocalContext.current
        val pm = remember { ctx.packageManager }
        // App list loads OFF the main thread, so the search field + keyboard show
        // up instantly and you can start typing/talking immediately; the list
        // fills in a beat later. (Previously loadApps + loadIcons ran
        // synchronously inside remember{} during first composition — loadIcons
        // decoded an icon drawable for EVERY installed app up front and blocked
        // the first frame. That was the search/mic launch delay.)
        var allApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
        var appsLoaded by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            allApps = withContext(Dispatchers.Default) { loadApps(pm) }
            appsLoaded = true
        }
        var query by remember { mutableStateOf("") }
        var voiceMsg by remember { mutableStateOf<String?>(null) }
        var listening by remember { mutableStateOf(false) }
        val focusRequester = remember { FocusRequester() }

        val filtered = remember(query, allApps) {
            if (query.isBlank()) allApps
            else allApps.filter { it.label.contains(query.trim(), ignoreCase = true) }
        }

        fun beginVoice(playCue: Boolean = true) {
            listening = true; voiceMsg = "Listening…"
            if (playCue) playMicCue()
            // Dictate-only: the mic transcribes what you say into the search box
            // and the list filters to it — you then TAP the app you want. We do
            // NOT auto-launch a "best match" (that false-fired, e.g. "3D" →
            // 3DMark) and we never auto-exit (an unmatched name just leaves an
            // empty/filtered list to retry or type into, not an exit).
            startVoice(
                onResult = { spoken ->
                    listening = false; query = spoken; voiceMsg = null
                    // Transcription only — the filtered list does the rest.
                },
                onError = { _ ->
                    // Don't exit on a recognizer error — keep the screen so the
                    // user can retry the mic or type. Show a gentle hint instead.
                    listening = false
                    voiceMsg = "Didn't catch that — tap the mic to retry or type"
                },
            )
        }

        LaunchedEffect(Unit) {
            if (startVoice) {
                val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) {
                    // Cue already played in onCreate; start listening now.
                    beginVoice(playCue = false)
                } else {
                    // onCreate already kicked off the permission request. Register
                    // what should happen once the user accepts: play the cue and
                    // begin listening. Until then, the app list/keyboard is shown
                    // so a denial still leaves a usable search screen.
                    pendingVoiceStart = { beginVoice(playCue = true) }
                    runCatching { focusRequester.requestFocus() }
                }
            } else {
                runCatching { focusRequester.requestFocus() }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(AbBackground)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = AbOnSurfaceMuted) },
                    placeholder = { Text("Search apps", color = AbOnSurfaceMuted) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = AbSurfaceElevated,
                        unfocusedContainerColor = AbSurfaceElevated,
                        focusedTextColor = AbOnSurface,
                        unfocusedTextColor = AbOnSurface,
                        cursorColor = AbPrimaryBright,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (listening) AbPrimary else AbSurfaceElevated)
                        .clickable {
                            if (!listening) {
                                // If a permission request is triggered, resume
                                // voice automatically once granted.
                                pendingVoiceStart = { beginVoice(playCue = true) }
                                beginVoice()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Mic, "Voice search", tint = if (listening) Color.White else AbPrimaryBright)
                }
            }
            voiceMsg?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filtered, key = { it.packageName }) { entry ->
                        Column {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        performTapHaptic()
                                        launch(entry)
                                    }
                                    .padding(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Icon decodes lazily, off the main thread, only
                                // for rows actually on screen (cached so scrolling
                                // back doesn't re-decode or flicker). The 40dp slot
                                // is always reserved so text doesn't shift when the
                                // bitmap lands.
                                AppRowIcon(pm, entry.packageName)
                                Text(entry.label, color = AbOnSurface, fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                            // Thin separator between rows (inset, clears scrollbar).
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
                VerticalScrollbar(
                    listState = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(4.dp),
                )
                // Brief placeholder while the list loads off-thread, so the area
                // isn't an unexplained blank for the moment before apps arrive.
                if (!appsLoaded && allApps.isEmpty()) {
                    Text(
                        "Loading apps…",
                        color = AbOnSurfaceMuted,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            // Return to the trackpad without launching anything.
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AbSurfaceElevated)
                    .clickable {
                        performTapHaptic()
                        finish()
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = null,
                        tint = AbOnSurface,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Back to trackpad",
                        color = AbOnSurface,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }

    /**
     * One app-row icon, decoded lazily off the main thread and cached so the
     * list scrolls without re-decoding or flicker. The 40dp box is always laid
     * out (icon or not) so rows don't reflow when the bitmap arrives. Seeds its
     * initial value from the cache, so a cached icon shows on the first frame.
     */
    @Composable
    private fun AppRowIcon(pm: PackageManager, packageName: String) {
        val bmp by produceState<ImageBitmap?>(SearchIconCache.get(packageName), packageName) {
            if (value != null) return@produceState
            val loaded = withContext(Dispatchers.Default) {
                runCatching {
                    pm.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
                }.getOrNull()
            }
            if (loaded != null) {
                SearchIconCache.put(packageName, loaded)
                value = loaded
            }
        }
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            bmp?.let {
                Image(
                    painter = androidx.compose.ui.graphics.painter.BitmapPainter(it),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
    }

    /** Process-wide LRU of decoded search-row icons (see [AppRowIcon]). */
    private object SearchIconCache {
        private const val MAX = 256
        private val map = object :
            LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ImageBitmap>,
            ): Boolean = size > MAX
        }

        @Synchronized fun get(key: String): ImageBitmap? = map[key]

        @Synchronized fun put(key: String, value: ImageBitmap) {
            map[key] = value
        }
    }

    companion object {
        const val EXTRA_START_VOICE = "start_voice"
        private const val REQ_MIC = 7322

        private fun loadApps(pm: PackageManager): List<AppEntry> {
            val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(launchIntent, 0)
            return resolved.map { it.activityInfo.packageName }.distinct().mapNotNull { pkg ->
                val main = resolved.firstOrNull { it.activityInfo.packageName == pkg } ?: return@mapNotNull null
                AppEntry(pkg, null, main.loadLabel(pm).toString())
            }.sortedBy { it.label.lowercase() }
        }

    }
}
