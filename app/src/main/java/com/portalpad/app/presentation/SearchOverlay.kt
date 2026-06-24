package com.portalpad.app.presentation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
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
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.AppEntry
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimary
import com.portalpad.app.ui.theme.AbPrimaryBright
import com.portalpad.app.ui.theme.AbSurfaceElevated
import com.portalpad.app.ui.theme.PortalPadTheme

/**
 * A focusable, full-screen app-search overlay on the external display.
 *
 * Unlike [DockOverlay] (which is WRAP_CONTENT + NOT_FOCUSABLE so launched apps
 * stay clickable), this window is full-screen and FOCUSABLE so its text field
 * can receive keyboard input. It's shown on demand from the dock's Search/Mic
 * buttons and dismissed after a launch or when the user taps the backdrop.
 *
 * Two ways to find an app:
 *   1. Type — live-filters the launcher app list by label.
 *   2. Voice — [SpeechRecognizer] transcribes speech, which fills the query and
 *      auto-launches the single best label match. The voice path does NOT
 *      depend on the (finicky) on-display keyboard, so it works even where
 *      secondary-display IME is unreliable.
 *
 * Launching is delegated to [onLaunch], which the caller wires to the same path
 * dock taps use (so desktop-window vs fullscreen behavior is identical).
 */
class SearchOverlay(
    serviceContext: Context,
    val display: Display,
    private val onLaunch: (AppEntry) -> Unit,
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
    private var speech: SpeechRecognizer? = null

    var isShowing: Boolean = false
        private set

    /** @param startVoice if true, immediately begins listening on show. */
    fun show(startVoice: Boolean) {
        if (isShowing) return
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val composeView = ComposeView(displayContext).apply {
            setViewTreeLifecycleOwner(this@SearchOverlay)
            setViewTreeSavedStateRegistryOwner(this@SearchOverlay)
            setViewTreeViewModelStoreOwner(this@SearchOverlay)
            setContent { PortalPadTheme { SearchContent(startVoice) } }
        }

        // Full-screen + FOCUSABLE (so the TextField gets the IME). We do NOT
        // set NOT_FOCUSABLE here — that's the whole point vs the dock window.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHost.windowType,
            // Focusable (no NOT_FOCUSABLE flag). DIM_BEHIND darkens the app
            // behind so the panel reads as a modal sheet.
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            dimAmount = 0.55f
            // ADJUST_RESIZE so the field stays visible when the IME shows.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }

        runCatching { windowManager.addView(composeView, params) }
            .onFailure { Log.e(TAG, "addView failed", it); return }
        view = composeView
        isShowing = true
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        // Lift the cursor above this newly-added overlay so it stays visible.
        com.portalpad.app.service.PortalPadForegroundService.raiseCursor()
        Log.d(TAG, "Search overlay added to display $displayId (voice=$startVoice)")
    }

    fun dismiss() {
        if (!isShowing) return
        runCatching { speech?.destroy() }; speech = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        _viewModelStore.clear()
        isShowing = false
    }

    private fun launchAndClose(entry: AppEntry) {
        onLaunch(entry)
        dismiss()
    }

    // ─── Voice ──────────────────────────────────────────────────────────

    private fun startVoice(onResult: (String) -> Unit, onError: (String) -> Unit) {
        // Without RECORD_AUDIO, SpeechRecognizer silently fails. Launch the
        // phone-side permission activity to prompt, then ask the user to retry.
        if (displayContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                val i = Intent(displayContext, com.portalpad.app.MicPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                displayContext.startActivity(i)
            }
            onError("Grant microphone permission on your phone, then tap the mic again")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(displayContext)) {
            onError("Voice recognition unavailable on this device")
            return
        }
        runCatching { speech?.destroy() }
        val sr = SpeechRecognizer.createSpeechRecognizer(displayContext)
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
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                onResult(text)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { sr.startListening(intent) }
            .onFailure { onError("Couldn't start the microphone") }
    }

    // ─── UI ─────────────────────────────────────────────────────────────

    @Composable
    private fun SearchContent(startVoice: Boolean) {
        val pm = remember { displayContext.packageManager }
        val allApps = remember { loadApps(pm) }
        var query by remember { mutableStateOf("") }
        var voiceMsg by remember { mutableStateOf<String?>(null) }
        var listening by remember { mutableStateOf(false) }
        val focusRequester = remember { FocusRequester() }

        val filtered = remember(query, allApps) {
            if (query.isBlank()) allApps
            else allApps.filter { it.label.contains(query.trim(), ignoreCase = true) }
        }

        // ─── Relay bridge ───────────────────────────────────────────────────
        // The phone keyboard relay writes typed text to injector.searchQuery;
        // mirror it into our local query so the list filters and the field on
        // the glasses shows what was typed — without depending on injected keys
        // landing in this overlay's TextField (which fails across displays).
        val injector = remember { PortalPadApp.instance.injector }
        val relayedQuery by injector.searchQuery.collectAsState()
        LaunchedEffect(relayedQuery) {
            // Only adopt the relayed text when it differs, so local edits (voice)
            // aren't clobbered by a stale empty initial value.
            if (relayedQuery != query) query = relayedQuery
        }
        // Relayed Enter (Send Enter button on the phone) → launch top result.
        val submitTick by injector.searchSubmit.collectAsState()
        LaunchedEffect(submitTick) {
            if (submitTick > 0) {
                filtered.firstOrNull()?.let { launchAndClose(it) }
            }
        }

        fun beginVoice() {
            listening = true
            voiceMsg = "Listening…"
            startVoice(
                onResult = { spoken ->
                    listening = false
                    query = spoken
                    voiceMsg = null
                    // Auto-launch the best label match if there's a clear one.
                    val match = bestMatch(spoken, allApps)
                    if (match != null) launchAndClose(match)
                },
                onError = { msg -> listening = false; voiceMsg = msg },
            )
        }

        LaunchedEffect(Unit) {
            if (startVoice) beginVoice() else runCatching { focusRequester.requestFocus() }
        }

        // Backdrop — tap outside the panel to dismiss.
        Box(
            Modifier
                .fillMaxSize()
                .clickable { dismiss() },
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                color = AbBackground,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .padding(top = 60.dp)
                    .fillMaxWidth(0.7f)
                    // Consume clicks so taps inside the panel don't dismiss.
                    .clickable(enabled = false) {},
            ) {
                Column(Modifier.padding(16.dp)) {
                    // Search field + mic + close.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = AbOnSurfaceMuted)
                            },
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
                        // Mic button.
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (listening) AbPrimary else AbSurfaceElevated)
                                .clickable { if (!listening) beginVoice() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Voice search",
                                tint = if (listening) Color.White else AbPrimaryBright,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable { dismiss() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = AbOnSurfaceMuted)
                        }
                    }

                    voiceMsg?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(12.dp))
                    LazyColumn(Modifier.heightForResults()) {
                        items(filtered, key = { it.packageName }) { entry ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { launchAndClose(entry) }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                com.portalpad.app.ui.dock.AppIcon(packageName = entry.packageName, sizeDp = 32)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    entry.label,
                                    color = AbOnSurface,
                                    fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Cap the results list height so the panel doesn't grow unbounded.
    private fun Modifier.heightForResults(): Modifier = this.height(360.dp)

    companion object {
        private const val TAG = "SearchOverlay"

        /** Launcher apps as [AppEntry], sorted by label. */
        private fun loadApps(pm: PackageManager): List<AppEntry> {
            val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(launchIntent, 0)
            return resolved
                .map { it.activityInfo.packageName }
                .distinct()
                .mapNotNull { pkg ->
                    val main = resolved.firstOrNull { it.activityInfo.packageName == pkg }
                        ?: return@mapNotNull null
                    val label = main.loadLabel(pm).toString()
                    AppEntry(pkg, null, label)
                }
                .sortedBy { it.label.lowercase() }
        }

        /**
         * Fuzzy-match spoken text to an app label. Strips common filler ("open",
         * "launch", "start", "go to") then prefers exact, then prefix, then
         * contains. Returns null if nothing reasonable matches.
         */
        private fun bestMatch(spoken: String, apps: List<AppEntry>): AppEntry? {
            if (spoken.isBlank()) return null
            val q = spoken.trim().lowercase()
                .removePrefix("open ").removePrefix("launch ")
                .removePrefix("start ").removePrefix("go to ")
                .trim()
            if (q.isEmpty()) return null
            apps.firstOrNull { it.label.equals(q, ignoreCase = true) }?.let { return it }
            apps.firstOrNull { it.label.lowercase().startsWith(q) }?.let { return it }
            return apps.firstOrNull { it.label.contains(q, ignoreCase = true) }
        }
    }
}
