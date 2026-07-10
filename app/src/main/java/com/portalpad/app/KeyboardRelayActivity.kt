package com.portalpad.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.view.KeyEvent
import com.portalpad.app.service.InputInjector
import com.portalpad.app.service.PortalPadAccessibilityService
import com.portalpad.app.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keyboard relay screen.
 *
 * Why this exists: Android doesn't natively render an IME on a secondary display
 * for regular (non-platform-signed) apps. So we host the system keyboard on the
 * **phone** in a big text field, and forward what's typed to the external display
 * as KeyEvents in real time.
 *
 * Behavior:
 * - Auto-focuses on launch, opening the user's default keyboard immediately
 * - LIVE typing: each character is forwarded as a [KeyEvent.KEYCODE_*] to the
 *   external display as you type; backspace as KEYCODE_DEL
 * - Tradeoff (accepted): injecting keys at the external display can make Android
 *   steal focus there and collapse this phone keyboard; the field re-asserts
 *   focus to recover. This is the cost of live typing vs. buffer-and-send.
 * - Live search also rides [InputInjector.setSearchQuery] (a StateFlow side
 *   channel, no focus impact) for PortalPad's own on-display search overlay
 * - "Send Enter" submits the search / injects a real ENTER
 * - Close button (or Back) dismisses
 *
 * Limitations of the relay model (honestly stated):
 * - Predictions / autocorrect happen on the phone, then get forwarded — fine
 *   for most use but may double-apply if the target also corrects
 * - Emoji and characters outside US-ASCII don't map to KeyEvent codes; they
 *   won't transmit. The text field will accept them but the external display
 *   won't see them
 * - Swipe typing works on the phone — the resulting word gets streamed as
 *   individual key events
 */
class KeyboardRelayActivity : PinnedDensityActivity() {

    private val app get() = application as PortalPadApp
    private val injector get() = app.injector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockOrientationDefault()
        // Prefill text comes from the accessibility detector (it reads the tapped
        // field's current text off the node). Empty when opened via the Shizuku
        // poller, which has no node to read from.
        val prefill = intent?.getStringExtra(EXTRA_PREFILL).orEmpty()
        setContent { PortalPadTheme { RelayScreen(injector, prefill) { finish() } } }
        finishWhenExternalDisplayGone()
    }

    companion object {
        /** Optional launch extra: the tapped field's existing text, to prefill the
         *  relay box. Set only by the accessibility detector (never the poller). */
        const val EXTRA_PREFILL = "prefill_text"
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.w("DIAG-RELAY", "onResume")
        applyForcedOrientation()
        // Tell the auto-open poller the relay is up so it goes dormant and can't
        // re-trigger itself while we're typing.
        app.relayOpen = true
        // While the relay screen is up, the user types on the PHONE keyboard
        // and we forward keys to the external display. Enter relay IME mode:
        // this suppresses the per-key IME re-pin AND tells the VD to not show
        // an IME (policy=HIDE), which stops Chrome on the VD from requesting
        // IME placement that would otherwise collapse the phone keyboard
        // after each keystroke (HIDE_DISPLAY_IME_POLICY_HIDE in the trace).
        injector.setRelayImeMode(true)
    }

    override fun onPause() {
        // Stamp BEFORE super/anything else: TrackpadActivity's onResume (which
        // runs refocusExternalDisplay) fires while this activity is merely
        // PAUSED — onDestroy comes ~250ms later, AFTER the refocus already ran.
        // Stamping only in onDestroy left the skip-window unarmed at the exact
        // moment it existed for (measured: refocus at +265ms after onPause,
        // destroy at +513ms — dropdown relaunch-killed with the skip asleep).
        (application as? PortalPadApp)?.relayClosedAt = System.currentTimeMillis()
        super.onPause()
        android.util.Log.w("DIAG-RELAY", "onPause")
        injector.setRelayImeMode(false)
    }

    override fun onDestroy() {
        android.util.Log.w("DIAG-RELAY", "onDestroy")
        // Relay is closing (back button or the X). Re-enable the auto-open poller
        // so the next field tap can open it again.
        app.relayOpen = false
        app.relayClosedAt = System.currentTimeMillis()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelayScreen(injector: InputInjector, initialText: String = "", onClose: () -> Unit) {
    // Seed with the tapped field's existing text (prefill). Cursor at end → append
    // mode. Because onValueChange forwards only the DIFF vs. this baseline, the
    // prefilled text is NOT re-injected into the field — only new keystrokes are.
    // Showing the real text is what makes the keyboard's backspace work: there's
    // something in the box to delete. Empty fields open blank.
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(initialText, selection = TextRange(initialText.length)))
    }
    val focusRequester = remember { FocusRequester() }
    var reassertFocus by remember { mutableStateOf(false) }
    // Bumped after every forwarded keystroke to drive the proactive reassert below.
    var keystrokeNonce by remember { mutableIntStateOf(0) }
    @OptIn(ExperimentalComposeUiApi::class)
    val keyboardController = LocalSoftwareKeyboardController.current

    // Input mode. Custom = our own on-screen keyboard (drives the field via
    // accessibility SET_TEXT/SET_SELECTION — reliable backspace + caret, no system
    // IME to fight). Android = the system keyboard via keystroke forwarding (great
    // typing, autocorrect/swipe/emoji, but backspace-on-prefilled is limited).
    // Defaults to custom since reliable editing is the point of this page.
    var customKeyboard by remember { mutableStateOf(true) }

    // Re-open the phone keyboard whenever the user TAPS the box. The box keeps
    // focus the whole time the relay is up (we requested it on open and the
    // reasserts hold it), so tapping it again normally produces NO focus change —
    // which means Compose won't re-show a keyboard the user dismissed (back/swipe)
    // or that collapsed. Observing tap "release" interactions lets us force the
    // keyboard back up on every tap without consuming the touch (so cursor
    // placement still works). Typing then forwards to the external display as usual.
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.PressInteraction.Release) {
                runCatching { focusRequester.requestFocus() }
                runCatching { keyboardController?.show() }
            }
        }
    }

    // PROACTIVE per-key reassert. The reactive onFocusChanged path (below) only
    // fires after focus is already lost, which loses the race against a web input's
    // IME re-request. Here we fire immediately after each keystroke and again a
    // beat later, grabbing display-0 focus + re-showing the keyboard inside the
    // ~70ms window between the key targeting the VD and Samsung's HIDE cascade —
    // so the phone keyboard never goes down. Keyed on the nonce so it re-runs per
    // key; rapid keys cancel the prior run, which is fine (the latest key wins).
    LaunchedEffect(keystrokeNonce) {
        if (keystrokeNonce > 0) {
            kotlinx.coroutines.delay(20)
            runCatching { focusRequester.requestFocus() }
            runCatching { keyboardController?.show() }
            kotlinx.coroutines.delay(45)
            runCatching { focusRequester.requestFocus() }
            runCatching { keyboardController?.show() }
        }
    }

    // When the field reports it lost focus (external IME stealing it), re-grab
    // focus AND explicitly re-show the soft keyboard so typing isn't interrupted.
    // Re-requesting field focus alone recovers simple native fields (Chrome's
    // address bar), but a web input that pops suggestions (google.com search box)
    // collapses the phone keyboard harder — the field regains focus but the IME
    // stays down — so we force it back up too. Guarded by the flag so this only
    // runs on an actual loss, not every recomposition.
    LaunchedEffect(reassertFocus) {
        if (reassertFocus) {
            kotlinx.coroutines.delay(50)
            runCatching { focusRequester.requestFocus() }
            runCatching { keyboardController?.show() }
            reassertFocus = false
        }
    }

    LaunchedEffect(Unit) {
        // Pop the keyboard so the user can start typing. Wait a beat so the
        // text field node is actually attached/positioned before requesting
        // focus — requesting too early throws "FocusRequester is not
        // initialized", and because this runs on the main looper that
        // exception is FATAL and takes the whole PortalPad process down (which
        // is what made tapping Search drop the user back to the launcher).
        // The runCatching guarantees it can never crash the process again.
        kotlinx.coroutines.delay(120)
        runCatching { focusRequester.requestFocus() }
        // Start from a clean query each time the relay opens.
        injector.setSearchQuery("")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Type to external display", color = AbOnSurface) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = AbOnSurface)
                    }
                },
                actions = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Done", tint = AbAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AbBackground),
            )
        },
        containerColor = AbBackground,
    ) { padding ->
      var showSuggestions by remember { mutableStateOf(false) }
      var overlaySuggestions by remember {
          mutableStateOf(listOf<com.portalpad.app.service.PortalPadAccessibilityService.MirrorSuggestion>())
      }
      // Scope for the pick fallback (SET_TEXT → brief settle → Enter).
      val suggestionScope = rememberCoroutineScope()
      // When the popup closes, restore field focus (the reassert watcher stood
      // down while it was open). Guarded so it doesn't fire on first composition.
      var popupWasOpen by remember { mutableStateOf(false) }
      LaunchedEffect(showSuggestions) {
          if (popupWasOpen && !showSuggestions) {
              kotlinx.coroutines.delay(60)
              runCatching { focusRequester.requestFocus() }
          }
          popupWasOpen = showSuggestions
      }
      Box(Modifier.fillMaxSize().padding(padding)) {
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Same disconnect banner as the trackpad interface — the relay
            // covers TrackpadActivity, so its banner fires unseen underneath.
            // Keyed to the PHYSICAL id (instant at unplug); under the grace
            // model the app being typed into stays ALIVE, so this is
            // actionable ("replug and continue"), and the relay deliberately
            // does NOT auto-finish: the draft text survives the grace window.
            val physId by com.portalpad.app.PortalPadApp.instance
                .physicalExternalDisplayId.collectAsState()
            com.portalpad.app.ui.common.DisconnectBanner(externalDisplayId = physId)
            RelayDependencyChip(customKeyboard)

            // ── Suggestion mirror (overlay dropdown) ───────────────────
            // The on-display dropdown is UNSELECTABLE from the phone: any relay
            // touch kills it (system IME arbitration), and DPAD selection is
            // cleared by Chrome's fill-in/rebuild loop. So the accessibility
            // service READS the suggestions off the display's node tree; here
            // they surface as a compact trigger row that opens a floating,
            // scrollable popup OVER the keyboard (nothing below reflows). A tap
            // clicks the real suggestion via ACTION_CLICK on its node — no focus
            // events, no key injection. Invisible when nothing is readable
            // (fallback by design: Chrome's view ids can shift across versions).
            var suggestions by remember {
                mutableStateOf(listOf<com.portalpad.app.service.PortalPadAccessibilityService.MirrorSuggestion>())
            }
            LaunchedEffect(Unit) {
                // STICKY + BURST reliability (field report 2026-07-09: chips
                // appeared intermittently — a single empty read mid-dropdown-
                // rebuild blanked the list even when the next poll would have
                // refilled it, and the fixed 600ms cadence aliased against apps
                // that populate their dropdown a few hundred ms AFTER each
                // keystroke).
                //  - STICKY: a non-empty chip set is held through empty reads
                //    while the query is unchanged; it clears only after 4
                //    consecutive empties (the dropdown is genuinely gone).
                //    On a query change the old chips hold briefly too — the
                //    burst below replaces them within ~250ms, which reads as
                //    progressive refinement rather than flicker.
                //  - BURST: the first 5 reads after each query change run at
                //    250ms, then the loop settles back to 600ms.
                var lastQuery = ""
                var emptyStreak = 0
                var sinceChange = 0
                // Seed the shared query flow with THIS session's starting text:
                // it's a process-wide StateFlow, so without seeding, a stale
                // query from a previous relay session (another app entirely)
                // leaks into the first scans until the first keystroke.
                injector.setSearchQuery(fieldValue.text)
                while (true) {
                    // Query source follows the ACTIVE keyboard path. The old
                    // "fieldValue, else custom buffer" precedence served the
                    // PREFILL forever on the custom path whenever the tapped
                    // field opened with leftover text (DIAG 2026-07-09: every
                    // scan said q='goo' while the user typed 'boss bab' — the
                    // relevance gate then rejected perfectly good rows).
                    val typed = if (customKeyboard) {
                        injector.searchQuery.value.trim()
                    } else {
                        fieldValue.text.trim()
                    }
                    if (typed != lastQuery) {
                        lastQuery = typed
                        sinceChange = 0
                        emptyStreak = 0
                    } else {
                        sinceChange++
                    }
                    val raw = runCatching {
                        com.portalpad.app.service.PortalPadAccessibilityService.instance
                            ?.readSuggestions(injector.displayId, query = typed)
                    }.getOrNull() ?: emptyList()
                    // Filter Chrome's verbatim echo of the typed text (Send Enter
                    // already covers "search exactly this") and trim the trailing
                    // two-line separator dot that bleeds into line_1.
                    val processed = raw
                        .map { it.copy(title = it.title.trimEnd(' ', '\u00B7', '\u2013', '\u2014').trim()) }
                        .filter { it.title.isNotBlank() && !it.title.equals(typed, ignoreCase = true) }
                        .distinctBy { it.title }
                    // BROWSERS keep their NATIVE order (and full list): the
                    // engine's ranking encodes history/popularity/personal
                    // signals that ABC would destroy, and its list is already
                    // all-relevant by its own judgment so nothing is pruned.
                    // Everything else (streaming tiles, Play Store, unknown
                    // apps) gets word-prefix tiers + ABC within each tier, with
                    // non-matches dropped once 3+ real matches exist.
                    val srcPkg = com.portalpad.app.service.PortalPadAccessibilityService
                        .instance?.lastSuggestionSourcePkg
                    val isBrowser = com.portalpad.app.service.PortalPadAccessibilityService
                        .isBrowserPkg(srcPkg)
                    val ranked = if (isBrowser) processed
                    else com.portalpad.app.ui.common.SearchRank.rank(processed, typed) { it.title }
                    if (ranked.isNotEmpty()) {
                        emptyStreak = 0
                        suggestions = ranked
                    } else {
                        emptyStreak++
                        if (emptyStreak >= 4) suggestions = emptyList()
                    }
                    kotlinx.coroutines.delay(if (sinceChange < 5) 250L else 600L)
                }
            }
            // NOTE deliberately NO live `overlaySuggestions = suggestions` here.
            // The popup shows a SNAPSHOT taken when the trigger is tapped: the
            // trigger tap itself kills Chrome's real dropdown (IMMS arbitration —
            // the proven dropdown3.txt floor), so the next poll reads EMPTY and a
            // live-bound popup drains and vanishes within one tick ("list appears
            // and cancels quickly"). The snapshot keeps the popup stable; the live
            // list only controls the trigger row's visibility.

            Surface(
                color = AbSurface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Keystrokes appear on the external display as you type.",
                        color = AbAccent, style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                            append("Note: ")
                            pop()
                            append(
                                "Custom keyboard is the more reliable option for editing " +
                                    "existing text (backspace, cursor, selection). Switch to " +
                                    "Android for other features like autocorrect, swipe, and voice.",
                            )
                        },
                        color = AbDanger,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            // Keyboard-source toggle. Custom (reliable editing) vs Android (familiar
            // typing). Two segments; the selected one is filled.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AbSurface)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KbModeSegment(
                    label = "Custom keyboard",
                    selected = customKeyboard,
                    onClick = { injector.buzz(); customKeyboard = true },
                )
                KbModeSegment(
                    label = "Android keyboard",
                    selected = !customKeyboard,
                    onClick = { injector.buzz(); customKeyboard = false },
                )
            }

            // Suggestion trigger — directly above the input area, below the
            // keyboard-mode tabs. Opens the floating popup (declared later).
            if (suggestions.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            // Snapshot NOW — this very tap dismisses Chrome's real
                            // dropdown, so the poll goes empty right after. The
                            // popup must not depend on the live list.
                            overlaySuggestions = suggestions
                            showSuggestions = true
                        }
                        .background(AbSurfaceElevated)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("\u2605", color = AbPrimaryBright, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${suggestions.size} suggestion${if (suggestions.size == 1) "" else "s"}",
                        color = AbOnSurface, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("\u25BE", color = AbOnSurfaceMuted, fontSize = 12.sp)
                }
            }
            // No `else { showSuggestions = false }`: the live list going empty is
            // EXPECTED the instant the popup opens (trigger tap → Chrome dropdown
            // dies → poll drains). It hides the trigger row above, but the open
            // popup keeps its snapshot; only the scrim tap or a pick closes it.

            if (customKeyboard) {
                // Size the keyboard to the space left below the toggle so it fits
                // without scrolling on any screen. Keys and the mirror field shrink on
                // short screens and cap out on tall ones (where the bottom-anchored
                // arrangement leaves empty space above — thumbs stay low either way).
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val avail = maxHeight
                    val mirrorH = (avail * 0.22f).coerceIn(80.dp, 124.dp)
                    val keyH = ((avail - mirrorH - 166.dp) / 5).coerceIn(32.dp, 50.dp)
                    CompositionLocalProvider(LocalKbKeyHeight provides keyH) {
                        Column(Modifier.fillMaxSize()) {
                            CustomKeyboardSection(injector, initialText, mirrorH)
                        }
                    }
                }
            } else {

            OutlinedTextField(
                value = fieldValue,
                onValueChange = { newValue ->
                    // LIVE typing on the ANDROID-keyboard path: forward the diff
                    // between old and new text as key events to the external display.
                    // This path is the simple, known-good keystroke forwarder — it
                    // types reliably but can only reliably backspace at the END of a
                    // field (the keystroke DEL desyncs the IME on a prefilled field
                    // after one delete). For reliable mid-text editing and backspace,
                    // use the custom keyboard (toggle above), which drives the field
                    // via accessibility SET_TEXT instead and has no IME to desync.
                    val newText = newValue.text
                    val oldText = fieldValue.text
                    when {
                        newText.length == oldText.length + 1 && newText.startsWith(oldText) ->
                            forwardCharacter(newText.last(), injector)
                        newText.length == oldText.length - 1 && oldText.startsWith(newText) ->
                            injector.pressKey(KeyEvent.KEYCODE_DEL)
                        newText.length > oldText.length -> {
                            val commonPrefixLen = oldText.commonPrefixWith(newText).length
                            newText.substring(commonPrefixLen).forEach {
                                forwardCharacter(it, injector)
                            }
                        }
                        newText.length < oldText.length -> {
                            repeat(oldText.length - newText.length) {
                                injector.pressKey(KeyEvent.KEYCODE_DEL)
                            }
                        }
                    }
                    fieldValue = newValue
                    injector.setSearchQuery(newValue.text)
                    keystrokeNonce++
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        // The VD/external app can steal IME focus after a couple
                        // keystrokes, which silently stops typing until the user
                        // re-taps the field. If we lose focus while the relay is
                        // up, re-request it on the next frame so typing continues
                        // uninterrupted. Event-driven (not polling) so it only
                        // fires on actual focus loss.
                        // EXCEPTION: while the suggestion popup is open, the field
                        // SHOULD lose focus to it — reasserting here would blur the
                        // popup and dismiss it mid-tap (the "list flashes then
                        // cancels" bug). Stand down until the popup closes.
                        if (!state.isFocused && !showSuggestions) {
                            reassertFocus = true
                        }
                    },
                placeholder = { Text("Tap here and start typing…", color = AbOnSurfaceMuted) },
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.None),
                interactionSource = interactionSource,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AbOnSurface, unfocusedTextColor = AbOnSurface,
                    focusedBorderColor = AbPrimary, unfocusedBorderColor = AbSurfaceElevated,
                    cursorColor = AbPrimaryBright,
                ),
            )

            // Enter button — useful for forms that need a real Enter keypress.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        // Text was already forwarded live as you typed, so Send
                        // just notifies the on-display search overlay (launch top
                        // result) and injects a real ENTER for the focused field
                        // (posts the message / submits the address bar).
                        injector.submitSearch()
                        injector.pressKey(KeyEvent.KEYCODE_ENTER)
                    }
                    .background(AbPrimary)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "↵  Send Enter",
                    color = AbOnSurface,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            } // end Android-keyboard branch
        }

        // ── Floating suggestion popup (over the keyboard) ──────────────────
        if (showSuggestions && overlaySuggestions.isNotEmpty()) {
            // Scrim: tap anywhere outside dismisses without choosing.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    ) { showSuggestions = false },
            )
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AbSurface)
                    .border(1.dp, AbPrimary, RoundedCornerShape(14.dp)),
            ) {
                Text(
                    "Suggestions \u00B7 tap to open",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
                androidx.compose.foundation.layout.Box {
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    val suggThumb = AbOnSurfaceMuted.copy(alpha = 0.6f)
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        modifier = Modifier
                            // Content-sized up to this cap: 8 chips (some with
                            // 2-line URL rows) fit without clipping; beyond it
                            // the list scrolls, with the thumb below as the
                            // affordance (a clipped row used to read as broken
                            // — field report 2026-07-09).
                            .heightIn(max = 420.dp)
                            .fillMaxWidth()
                            // Scrollbar thumb (the dock list's pattern): drawn
                            // only while content overflows; sized to the
                            // visible fraction, tracks scroll position.
                            .drawWithContent {
                                drawContent()
                                val info = listState.layoutInfo
                                val total = info.totalItemsCount
                                if (total > 0 && info.visibleItemsInfo.isNotEmpty()) {
                                    val visible = info.visibleItemsInfo.size.toFloat()
                                    val frac = (visible / total).coerceIn(0.1f, 1f)
                                    if (frac < 1f) {
                                        val firstIdx = info.visibleItemsInfo.first().index.toFloat()
                                        val progress = (firstIdx / (total - visible)).coerceIn(0f, 1f)
                                        val thumbH = size.height * frac
                                        val thumbY = (size.height - thumbH) * progress
                                        val w = 4.dp.toPx()
                                        drawRoundRect(
                                            color = suggThumb,
                                            topLeft = androidx.compose.ui.geometry.Offset(size.width - w - 3.dp.toPx(), thumbY),
                                            size = androidx.compose.ui.geometry.Size(w, thumbH),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
                                        )
                                    }
                                }
                            },
                    ) {
                        itemsIndexed(overlaySuggestions) { i, s ->
                            androidx.compose.foundation.layout.Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showSuggestions = false
                                        suggestionScope.launch {
                                            val svc = com.portalpad.app.service
                                                .PortalPadAccessibilityService.instance
                                            // Tier 1: click the LIVE node — free win in
                                            // the rare case Chrome's dropdown outlived
                                            // the trigger tap. Preserves the row's exact
                                            // semantics, whatever they were.
                                            var ok = runCatching {
                                                svc?.clickSuggestion(injector.displayId, s.title, i)
                                            }.getOrNull() == true
                                            // Tier 2 (expected path): the real dropdown
                                            // died when the trigger was tapped, so the
                                            // node is gone. SET_TEXT into the retained
                                            // field node (same primitive the relay uses
                                            // for deletes) and submit with a real Enter.
                                            // URL rows submit their line_2 ADDRESS (the
                                            // omnibox navigates); search rows submit the
                                            // title (the omnibox searches) — same
                                            // outcome the real row would have had.
                                            if (!ok) {
                                                val submitText = s.url ?: s.title
                                                val set = runCatching { svc?.setFieldText(submitText) }
                                                    .getOrNull() == true
                                                if (set) {
                                                    injector.setSearchQuery(submitText)
                                                    // Let Chrome's editor ingest the
                                                    // SET_TEXT before the submit key.
                                                    kotlinx.coroutines.delay(120)
                                                    injector.submitSearch()
                                                    injector.pressKey(KeyEvent.KEYCODE_ENTER)
                                                    ok = true
                                                }
                                            }
                                            android.util.Log.d(
                                                "PortalPadRelay",
                                                "suggestion pick '${s.title}' idx=$i url=${s.url != null} ok=$ok",
                                            )
                                            if (ok) { injector.buzz(longPress = false); onClose() }
                                        }
                                    }
                                    .padding(start = 14.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
                            ) {
                                Text(
                                    s.title,
                                    color = AbOnSurface,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                // Dimmed address line — present only on rows whose
                                // pick will NAVIGATE rather than search.
                                if (s.url != null) {
                                    Text(
                                        s.url,
                                        color = AbOnSurfaceMuted,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                    // Persistent proportional scrollbar — visible ONLY when the
                    // list actually overflows (no false signal on short lists).
                    val info = listState.layoutInfo
                    val total = info.totalItemsCount
                    val visible = info.visibleItemsInfo.size
                    if (total > visible && total > 0) {
                        val frac = visible.toFloat() / total
                        val progress = if (total > visible)
                            listState.firstVisibleItemIndex.toFloat() / (total - visible) else 0f
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 4.dp, bottom = 4.dp, end = 3.dp)
                                .width(4.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(AbSurfaceElevated),
                        ) {
                            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxHeight()) {
                                val thumbH = maxHeight * frac
                                val room = maxHeight - thumbH
                                Box(
                                    Modifier
                                        .offset(y = room * progress)
                                        .width(4.dp)
                                        .height(thumbH)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(AbPrimary),
                                )
                            }
                        }
                    }
                }
            }
        }
      }
    }
}

/** Height of the multi-line mirror field (text wraps + scrolls vertically within). */
private val MIRROR_HEIGHT = 120.dp

/** Key height, set responsively per screen so the keyboard fits without scrolling. */
private val LocalKbKeyHeight = compositionLocalOf { 46.dp }

/**
 * Alternate-character layers reached via the "Swap layer" button. Layer 0 is normal;
 * layers 1..N apply these accent maps to the matching letters (unmapped keys stay as
 * their base letter). Cycle order: normal → grave → acute → circumflex → umlaut → normal.
 */
private val ACCENT_TIERS: List<Map<Char, Char>> = listOf(
    mapOf('a' to 'à', 'e' to 'è', 'i' to 'ì', 'o' to 'ò', 'u' to 'ù'),
    mapOf('a' to 'á', 'e' to 'é', 'i' to 'í', 'o' to 'ó', 'u' to 'ú', 'y' to 'ý'),
    mapOf('a' to 'â', 'e' to 'ê', 'i' to 'î', 'o' to 'ô', 'u' to 'û'),
    mapOf('a' to 'ä', 'e' to 'ë', 'i' to 'ï', 'o' to 'ö', 'u' to 'ü', 'y' to 'ÿ'),
)
private val ACCENT_NAMES = listOf("Normal", "Grave à", "Acute á", "Circumflex â", "Umlaut ä")

/**
 * Grapheme-cluster boundaries. Emoji are multiple UTF-16 units (surrogate pairs, and
 * ZWJ/flag/skin-tone sequences are several code points), so moving or deleting one
 * unit at a time would split them. BreakIterator gives whole-glyph boundaries.
 */
private fun prevGrapheme(s: String, index: Int): Int {
    if (index <= 0) return 0
    val bi = java.text.BreakIterator.getCharacterInstance()
    bi.setText(s)
    val b = bi.preceding(index.coerceAtMost(s.length))
    return if (b == java.text.BreakIterator.DONE) 0 else b
}
private fun nextGrapheme(s: String, index: Int): Int {
    if (index >= s.length) return s.length
    val bi = java.text.BreakIterator.getCharacterInstance()
    bi.setText(s)
    val b = bi.following(index.coerceAtLeast(0))
    return if (b == java.text.BreakIterator.DONE) s.length else b
}

/** Emoji picker categories. Tab glyphs for the row are in EMOJI_TABS (Recent first). */
private val EMOJI_TABS = listOf("\uD83D\uDD58", "\uD83D\uDE00", "\uD83D\uDC36", "\uD83C\uDF4E", "\u26BD", "\u2708\uFE0F", "\uD83D\uDCA1", "\uD83D\uDD23")
private val EMOJI_CATEGORIES: List<List<String>> = listOf(
    // Smileys & people
    "😀 😃 😄 😁 😆 😅 😂 🤣 🥲 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥳 😏 😒 😞 😔 😟 😕 🙁 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 🤥 😶 😐 😑 😬 🙄 😮 😲 🥱 😴 🤤 😪 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 👍 👎 👌 ✌️ 🤞 🙏 👏 🙌 💪 👋",
    // Animals & nature
    "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🐢 🐍 🦎 🦖 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🐘 🦏 🦛 🐪 🐫 🦒 🦘 🐎 🐖 🐏 🐑 🐐 🌵 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🍃 🌷 🌹 🌺 🌻 🌼 🌸 💐 🍄 🌰",
    // Food & drink
    "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥙 🥗 🍣 🍤 🍙 🍚 🍛 🍜 🍲 🍥 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 ☕ 🍵 🥤 🍶 🍺 🍷 🥂 🥃 🍸 🍹",
    // Activities
    "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🪀 🏓 🏸 🏒 🏑 🥍 🏏 🥅 ⛳ 🪁 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛼 🛷 ⛸️ 🥌 🎿 ⛷️ 🏂 🏋️ 🤼 🤸 ⛹️ 🤺 🏌️ 🏇 🧘 🏄 🏊 🚣 🧗 🚴 🚵 🏆 🏅 🥇 🥈 🥉 🎫 🎪 🤹 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩",
    // Travel & places
    "🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛴 🚲 🛵 🏍️ 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🚀 🛸 🚁 🛶 ⛵ 🚤 🛳️ ⛴️ 🚢 ⚓ ⛽ 🚧 🚦 🚥 🗺️ 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🏠 🏡 🏘️ 🏭 🏢 🏬 🏥 🏦 🏨 🏪 🏫 ⛪ 🕌",
    // Objects
    "⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 🕹️ 💽 💾 💿 📀 📷 📸 📹 🎥 📽️ 🎞️ 📞 ☎️ 📟 📠 📺 📻 🎙️ 🧭 ⏱️ ⏰ 🕰️ ⌛ ⏳ 📡 🔋 🔌 💡 🔦 🕯️ 🧯 💸 💵 💴 💶 💷 🪙 💰 💳 🧾 💎 ⚖️ 🔧 🔨 🛠️ ⛏️ 🔩 ⚙️ 🧲 🔫 💣 🧨 🪓 🔪 🗡️ ⚔️ 🛡️ 🚬 ⚰️ 🏺 🔮 📿 🧿 🔭 🔬 💊 💉 🩸 🧬 🦠 🧫 🧪 🌡️ 🧹 🧺 🧻 🚽 🚿 🛁 🧼 🧽 🔑 🗝️ 🚪 🛋️ 🛏️ 🧸 🖼️ 🛍️ 🛒 🎁 🎈 🎀 🎉 🎊 ✉️ 📩 📨 📧 💌 📦 🏷️ 📫 📮 📜 📄 📊 📈 📉 📆 📅 📇 📋 📁 📂 🗂️ 📰 📕 📗 📘 📙 📚 📖 🔖 📎 📐 📏 🧮 📌 📍 ✂️ 🖊️ 🖋️ ✒️ 🖌️ 🖍️ 📝 ✏️ 🔍 🔒 🔓",
    // Symbols
    "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☪️ 🕉️ ☸️ ✡️ 🔯 🕎 ☯️ ☦️ 🛐 ⛎ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ ⚛️ ☢️ ☣️ ✴️ 🆚 ❌ ⭕ 🛑 ⛔ 🚫 💯 💢 ♨️ 🔞 📵 🚭 ❗ ❓ ❔ ❕ ‼️ ⁉️ ⚠️ 🚸 🔱 ⚜️ 🔰 ♻️ ✅ 💹 ❇️ ✳️ ❎ 🌐 💠 🌀 🏧 🚾 ♿ 🅿️ 🚹 🚺 🚼 🚻 🚮 🔣 🔤 🔡 🔠 🆗 🆙 🆒 🆕 🆓 🔟 ➕ ➖ ➗ ✖️ 🟰 ♾️ 💲 💱 ©️ ®️ ™️ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟥 🟧 🟨 🟩 🟦 🟪 ⬛ ⬜",
).map { it.split(" ").filter { e -> e.isNotBlank() } }

/**
 * Press-and-hold key repeat. Fires [action] once on press, then (if still held after
 * a short delay) repeats it on an accelerating interval until release/cancel. Used
 * for backspace and the caret arrows. When [enabled] is false the modifier is inert.
 */
private fun Modifier.holdRepeat(
    scope: CoroutineScope,
    enabled: Boolean,
    action: () -> Unit,
): Modifier = if (!enabled) this else this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            action()
            val job = scope.launch {
                kotlinx.coroutines.delay(380)
                var d = 90L
                while (true) {
                    action()
                    kotlinx.coroutines.delay(d)
                    d = (d * 85 / 100).coerceAtLeast(35L)
                }
            }
            try {
                waitForUpOrCancellation()
            } finally {
                job.cancel()
            }
        }
    },
)

/** One clipboard action button (Select all / Cut / Copy / Paste / Mic / Clear). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RowScope.KbClipButton(
    label: String,
    active: Boolean = false,
    icon: ImageVector? = null,
    fixedWidth: Dp? = null,
    weight: Float = 1f,
    restingColor: Color? = null,
    iconTint: Color? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .then(if (fixedWidth != null) Modifier.width(fixedWidth) else Modifier.weight(weight))
            .height(40.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) AbPrimary else (restingColor ?: AbSurfaceElevated))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) AbOnSurface else (iconTint ?: AbOnSurface),
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(label, color = AbOnSurface, style = MaterialTheme.typography.labelMedium, fontSize = 12.sp)
        }
    }
}

/** One segment of the keyboard-source toggle (Custom | Android). */
@Composable
private fun RowScope.KbModeSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AbPrimary else AbSurfaceElevated)
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) AbOnSurface else AbOnSurfaceMuted,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** One key on the custom keyboard. If [repeatScope] is set, the key auto-repeats while held. */
@Composable
private fun RowScope.KbKey(
    label: String,
    weight: Float = 1f,
    bg: Color = AbSurfaceElevated,
    fg: Color = AbOnSurface,
    repeatScope: CoroutineScope? = null,
    preview: Boolean = false,
    onClick: () -> Unit,
) {
    val keyH = LocalKbKeyHeight.current
    // holdRepeat captures its action inside a pointerInput(Unit) coroutine that
    // OUTLIVES recomposition — so when ?123 swapped the rows, each positionally
    // reused key kept typing its FIRST label's character ("1" typed "q", "@"
    // typed "a", "?" typed "m": every symbol key sent the letter from the same
    // grid slot). Route the call through rememberUpdatedState so the long-lived
    // gesture always invokes the CURRENT key's action; letter mode never showed
    // it because the slot's k never changes there.
    val currentOnClick by rememberUpdatedState(onClick)
    // Press state, tracked on the Initial pass so it works for BOTH the clickable and
    // the hold-repeat paths (the character keys repeat) without consuming — so it can't
    // interfere with the actual click/repeat handling layered after it.
    var pressed by remember { mutableStateOf(false) }
    // Outer wrapper carries the row weight + key height as the layout footprint but does
    // NOT clip, so the press-preview bubble can render ABOVE the key. The clipped key
    // surface is a child; the bubble is a sibling drawn on top.
    Box(Modifier.weight(weight).height(keyH)) {
        val surface = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                    )
                    pressed = true
                    do {
                        val e = awaitPointerEvent(
                            androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                        )
                    } while (e.changes.any { it.pressed })
                    pressed = false
                }
            }
            .clip(RoundedCornerShape(7.dp))
            .background(if (pressed) androidx.compose.ui.graphics.lerp(bg, AbPrimary, 0.3f) else bg)
        val clickMod = if (repeatScope != null) {
            surface.holdRepeat(repeatScope, true) { currentOnClick() }
        } else {
            surface.clickable { currentOnClick() }
        }
        Box(clickMod, contentAlignment = Alignment.Center) {
            Text(label, color = fg, style = MaterialTheme.typography.titleMedium)
        }
        // Press preview: a bubble popped ABOVE the pressed key showing its character, so
        // it's visible above the finger. Character keys only (preview = true). Same
        // window (no Popup → no gesture cancel) and outside the key's rounded clip.
        if (preview && pressed) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, -(keyH.roundToPx() + 12.dp.roundToPx())) }
                    .size(48.dp, keyH + 8.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(AbSurfaceElevated)
                    .border(1.5.dp, AbPrimary, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = fg, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

/**
 * A draggable selection handle (one per selection end). The visual is a small circle
 * hanging just below the line; the touch target is larger. Dragging maps the finger
 * (tracked in text space via accumulated drag deltas, so vertical scroll doesn't
 * skew it) to a character offset and reports it live via [onMoveTo]; [onRelease]
 * fires on lift so the glasses field is only re-selected once, not every frame.
 */
@Composable
private fun SelectionHandle(
    cursorRect: androidx.compose.ui.geometry.Rect,
    scrollPx: Int,
    currentOffset: () -> Int,
    layout: () -> androidx.compose.ui.text.TextLayoutResult?,
    onGrab: () -> Unit,
    onMoveTo: (Int) -> Unit,
    onRelease: () -> Unit,
) {
    val half = with(LocalDensity.current) { 14.dp.toPx() }
    val xPx = cursorRect.left
    val yPx = cursorRect.bottom - scrollPx
    var dragPos by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    // Safety net: if this handle is removed while a drag is still in progress — e.g. a
    // selection collapses to zero width and its handle leaves the tree before onDragEnd
    // fires — release it anyway so the magnifier flag can't get stranded on.
    val latestRelease by rememberUpdatedState(onRelease)
    DisposableEffect(Unit) {
        onDispose { if (dragPos != null) latestRelease() }
    }
    Box(
        Modifier
            .offset { IntOffset((xPx - half).roundToInt(), yPx.roundToInt()) }
            .size(28.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        onGrab()
                        val r = layout()?.let { runCatching { it.getCursorRect(currentOffset()) }.getOrNull() }
                        dragPos = if (r != null) {
                            androidx.compose.ui.geometry.Offset(r.left, (r.top + r.bottom) / 2f)
                        } else {
                            null
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val l = layout()
                        val dp = dragPos
                        if (l != null && dp != null) {
                            val cur = dp + dragAmount
                            dragPos = cur
                            onMoveTo(l.getOffsetForPosition(cur))
                        }
                    },
                    onDragEnd = { dragPos = null; onRelease() },
                    onDragCancel = { dragPos = null; onRelease() },
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(AbPrimaryBright))
    }
}

/**
 * Custom on-screen keyboard. Unlike the Android-keyboard path, there is NO system
 * IME and NO focused proxy text field here — each key drives the glasses field
 * directly through the accessibility service (SET_TEXT for insert/delete, SET_SELECTION
 * for the caret). That removes the entire focus/IME-desync problem: the buttons don't
 * need IME focus, so SET_TEXT stealing focus to the external display can't collapse
 * anything, and backspace/caret are exact because we own the buffer. Falls back to
 * keystroke forwarding only if the accessibility service isn't connected.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.CustomKeyboardSection(
    injector: InputInjector,
    initialText: String,
    mirrorHeight: Dp = MIRROR_HEIGHT,
) {
    var buffer by remember { mutableStateOf(initialText) }
    var caret by remember { mutableIntStateOf(initialText.length) }
    var shift by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    // Accent swap layer: 0 = normal letters; 1..ACCENT_TIERS.size apply an accent map.
    // Cycled by the "Swap layer" button; only affects letter mode (ignored under ?123).
    var letterLayer by remember { mutableIntStateOf(0) }
    // Emoji picker: when on, the keyboard is replaced by the emoji grid. emojiCat is the
    // selected tab (0 = Recent, 1.. = EMOJI_CATEGORIES). Recent is persisted across opens.
    var emoji by remember { mutableStateOf(false) }
    var emojiCat by remember { mutableIntStateOf(1) }
    val ctx = LocalContext.current
    val prefs = remember {
        ctx.getSharedPreferences("portalpad_kb", android.content.Context.MODE_PRIVATE)
    }
    var recent by remember {
        mutableStateOf(
            prefs.getString("recent_emoji", "")
                ?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList(),
        )
    }
    // Mic search: listening drives the listening panel; voiceMsg surfaces errors /
    // permission prompts; speechRef holds the active recognizer so it can be torn down.
    var listening by remember { mutableStateOf(false) }
    var voiceMsg by remember { mutableStateOf<String?>(null) }
    // Continuous (latched) dictation: engaged by long-pressing the mic. Keeps re-arming
    // the recognizer after each phrase. lastResultAt drives a 60s silence auto-timeout.
    var continuousMic by remember { mutableStateOf(false) }
    val lastResultAt = remember { mutableStateOf(0L) }
    // Clear-all undo: holds (text, caret) from just before a clear so it can be restored.
    // Non-null = the "Field cleared · Undo" strip is showing in place of the action row.
    var clearedBackup by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val speechRef = remember { mutableStateOf<android.speech.SpeechRecognizer?>(null) }
    DisposableEffect(Unit) {
        onDispose { runCatching { speechRef.value?.destroy() } }
    }
    // Gesture (handwriting) mode: draw letters that ML Kit recognizes and types.
    // strokes = completed strokes; currentStroke = the one being drawn (for live ink).
    // gestureGen bumps to reset the pointer-capture after each committed letter.
    var gesture by remember { mutableStateOf(false) }
    var gestureReady by remember { mutableStateOf(false) }
    var gestureMsg by remember { mutableStateOf<String?>(null) }
    var gestureFailed by remember { mutableStateOf(false) }
    val gestureInk = remember { GestureInkRecognizer() }
    val strokes = remember { mutableStateListOf<List<InkPoint>>() }
    var currentStroke by remember { mutableStateOf<List<InkPoint>>(emptyList()) }
    var gestureCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastCommitLen by remember { mutableIntStateOf(0) }
    var gestureGen by remember { mutableIntStateOf(0) }
    // Sticky Caps Lock for gesture mode: forces committed letters to upper/lower case
    // deterministically (ML Kit's returned case is unreliable for single letters).
    var gestureCaps by remember { mutableStateOf(false) }
    val gestureCommitJob = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(Unit) {
        onDispose { gestureInk.close() }
    }
    // Prepares the handwriting model. Three outcomes drive the on-canvas message:
    //   • downloading  → "Downloading gesture kit…" (first run / not yet cached)
    //   • ready        → message cleared, canvas live
    //   • failed       → "tap to retry" (e.g. the USB-Ethernet/DNS case), retry re-runs this
    fun prepareGesture() {
        gestureFailed = false
        gestureMsg = "Preparing handwriting…"
        gestureInk.prepare(
            onReady = { gestureReady = true; gestureMsg = null; gestureFailed = false },
            onError = { gestureMsg = "Couldn't download gesture kit — tap to retry"; gestureFailed = true },
            onDownloading = { gestureMsg = "Downloading gesture kit…" },
        )
    }
    LaunchedEffect(gesture) {
        if (gesture && !gestureReady) {
            prepareGesture()
        }
    }
    // Caret blink: caretVisible toggles on a timer; blinkReset is bumped on any
    // caret activity so the caret snaps solid the instant you type/move it, then
    // resumes blinking (standard caret feel). caretLayout is the measured glyph
    // layout of the mirror text, used to draw the caret and map taps to a char index.
    var caretVisible by remember { mutableStateOf(true) }
    var blinkReset by remember { mutableIntStateOf(0) }
    var caretLayout by remember {
        mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null)
    }
    // Selection: an anchor (null = no selection). The selected span runs between the
    // anchor and the caret. Only "Select all" creates one; any edit/move/tap clears it.
    var selAnchor by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    fun selRange(): Pair<Int, Int>? {
        val a = selAnchor ?: return null
        val lo = minOf(a, caret).coerceIn(0, buffer.length)
        val hi = maxOf(a, caret).coerceIn(0, buffer.length)
        return if (lo == hi) null else lo to hi
    }

    fun pushText() {
        val svc = PortalPadAccessibilityService.instance
        val ok = runCatching { svc?.setFieldText(buffer) == true }.getOrDefault(false)
        if (ok) runCatching { svc?.setFieldSelection(caret, caret) }
        injector.setSearchQuery(buffer)
        android.util.Log.i(
            "PortalPadRelay",
            "customKb text: buffer='${buffer.take(40)}'(len=${buffer.length}) caret=$caret setText=$ok",
        )
        // No service / SET_TEXT failed → we can't mirror; nothing else to do (the
        // mirror bar still reflects the buffer so the user sees their edit).
    }
    fun pushCaret() {
        runCatching { PortalPadAccessibilityService.instance?.setFieldSelection(caret, caret) }
        android.util.Log.i("PortalPadRelay", "customKb caret=$caret")
    }
    fun insert(s: String) {
        injector.buzz()
        blinkReset++
        val sel = selRange()
        if (sel != null) {
            buffer = buffer.substring(0, sel.first) + s + buffer.substring(sel.second)
            caret = sel.first + s.length
            selAnchor = null
        } else {
            val c = caret.coerceIn(0, buffer.length)
            buffer = buffer.substring(0, c) + s + buffer.substring(c)
            caret = c + s.length
        }
        if (shift) shift = false
        pushText()
    }
    fun backspace() {
        val sel = selRange()
        if (sel != null) {
            injector.buzz()
            blinkReset++
            buffer = buffer.substring(0, sel.first) + buffer.substring(sel.second)
            caret = sel.first
            selAnchor = null
            pushText()
        } else {
            val c = caret.coerceIn(0, buffer.length)
            if (c > 0) {
                val start = prevGrapheme(buffer, c)
                injector.buzz()
                blinkReset++
                buffer = buffer.substring(0, start) + buffer.substring(c)
                caret = start
                pushText()
            }
        }
    }
    fun moveCaret(delta: Int) {
        if ((delta < 0 && caret <= 0) || (delta > 0 && caret >= buffer.length)) return
        injector.buzz()
        blinkReset++
        selAnchor = null
        caret = if (delta < 0) prevGrapheme(buffer, caret) else nextGrapheme(buffer, caret)
        pushCaret()
    }
    fun setCaret(pos: Int) {
        injector.buzz()
        blinkReset++
        selAnchor = null
        caret = pos.coerceIn(0, buffer.length)
        pushCaret()
    }
    // Long-press the field to select the word under the finger (the standard way to
    // start a selection without "Select all"). Whitespace/punctuation/empty → just
    // place the caret. Stronger haptic so the long-press registers tactilely.
    fun selectWordAt(off: Int) {
        injector.buzz(longPress = true)
        blinkReset++
        if (buffer.isEmpty()) {
            selAnchor = null
            caret = 0
            pushCaret()
            return
        }
        val o = off.coerceIn(0, buffer.length)
        val idx = when {
            o < buffer.length && buffer[o].isLetterOrDigit() -> o
            o > 0 && buffer[o - 1].isLetterOrDigit() -> o - 1
            else -> -1
        }
        if (idx < 0) {
            selAnchor = null
            caret = o
            pushCaret()
            return
        }
        var start = idx
        while (start > 0 && buffer[start - 1].isLetterOrDigit()) start--
        var end = idx + 1
        while (end < buffer.length && buffer[end].isLetterOrDigit()) end++
        selAnchor = start
        caret = end
        runCatching {
            PortalPadAccessibilityService.instance?.setFieldSelection(start, end)
        }
    }
    fun selectAll() {
        injector.buzz()
        if (buffer.isEmpty()) return
        selAnchor = 0
        caret = buffer.length
        runCatching {
            PortalPadAccessibilityService.instance?.setFieldSelection(0, buffer.length)
        }
    }
    fun copy() {
        injector.buzz()
        val sel = selRange()
        val text = if (sel != null) buffer.substring(sel.first, sel.second) else buffer
        if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
    }
    fun cut() {
        injector.buzz()
        blinkReset++
        val sel = selRange()
        val text = if (sel != null) buffer.substring(sel.first, sel.second) else buffer
        if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
        if (sel != null) {
            buffer = buffer.substring(0, sel.first) + buffer.substring(sel.second)
            caret = sel.first
        } else {
            buffer = ""
            caret = 0
        }
        selAnchor = null
        pushText()
    }
    fun paste() {
        injector.buzz()
        blinkReset++
        val clip = clipboard.getText()?.text ?: return
        if (clip.isEmpty()) return
        val sel = selRange()
        if (sel != null) {
            buffer = buffer.substring(0, sel.first) + clip + buffer.substring(sel.second)
            caret = sel.first + clip.length
        } else {
            val c = caret.coerceIn(0, buffer.length)
            buffer = buffer.substring(0, c) + clip + buffer.substring(c)
            caret = c + clip.length
        }
        selAnchor = null
        pushText()
    }
    fun clearAll() {
        if (buffer.isEmpty()) return
        injector.buzz()
        blinkReset++
        clearedBackup = buffer to caret
        buffer = ""
        caret = 0
        selAnchor = null
        pushText()
    }
    fun undoClear() {
        val b = clearedBackup ?: return
        injector.buzz()
        blinkReset++
        buffer = b.first
        caret = b.second.coerceIn(0, b.first.length)
        selAnchor = null
        clearedBackup = null
        pushText()
    }
    fun mirrorSelection() {
        val sel = selRange()
        runCatching {
            val svc = PortalPadAccessibilityService.instance
            if (sel != null) svc?.setFieldSelection(sel.first, sel.second)
            else svc?.setFieldSelection(caret, caret)
        }
    }

    // Blink loop. Restarts whenever blinkReset changes — that's what makes the caret
    // snap solid on a keypress before resuming the ~530ms blink.
    LaunchedEffect(blinkReset) {
        caretVisible = true
        while (true) {
            kotlinx.coroutines.delay(530)
            caretVisible = !caretVisible
        }
    }

    // Vertical scroll for the multi-line mirror, plus the viewport height in px so
    // we can keep the caret's line in view as you type / move it.
    val mirrorScroll = rememberScrollState()
    val mirrorViewportPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        mirrorHeight.toPx()
    }
    // True while the caret handle is being dragged, so the caret renders solid (not
    // blinking) and stays visible under your finger as you scrub it.
    var caretDragging by remember { mutableStateOf(false) }
    // Auto-fading scrollbar thumb: visible while scrolling/typing, fades after a beat.
    val scrollbarAlpha = remember { Animatable(0f) }
    LaunchedEffect(mirrorScroll.value, buffer) {
        if (mirrorScroll.maxValue > 0) {
            scrollbarAlpha.snapTo(1f)
            kotlinx.coroutines.delay(900)
            scrollbarAlpha.animateTo(0f, tween(400))
        }
    }
    // Undo strip lifetime: auto-dismiss after 4s, and dismiss the moment new text is
    // typed (so an accidental Undo can't clobber what you just started writing).
    LaunchedEffect(clearedBackup) {
        if (clearedBackup != null) {
            kotlinx.coroutines.delay(4000)
            clearedBackup = null
        }
    }
    LaunchedEffect(buffer) {
        if (clearedBackup != null && buffer.isNotEmpty()) clearedBackup = null
    }
    LaunchedEffect(caret, caretLayout, buffer) {
        val l = caretLayout ?: return@LaunchedEffect
        val safe = caret.coerceIn(0, buffer.length)
        val rect = runCatching { l.getCursorRect(safe) }.getOrNull() ?: return@LaunchedEffect
        val cur = mirrorScroll.value.toFloat()
        when {
            rect.top < cur ->
                runCatching { mirrorScroll.scrollTo(rect.top.toInt().coerceAtLeast(0)) }
            rect.bottom > cur + mirrorViewportPx ->
                runCatching { mirrorScroll.scrollTo((rect.bottom - mirrorViewportPx).toInt().coerceAtLeast(0)) }
        }
    }

    // Top slack: with the matching spacer below the clipboard row, this centers the
    // field + clipboard group in the space above the keyboard. Collapses to 0 on short
    // screens (keyboard stays bottom-anchored, group packs up against it).
    Spacer(Modifier.weight(1f))
    // The field, wrapped in a non-clipping Box so the magnifier bubble (added right after
    // the Surface) can render ABOVE the field — same window as the drag (so it doesn't
    // cancel the gesture the way a Popup did), but outside the field's rounded clip.
    Box(Modifier.fillMaxWidth()) {
    // Mirror bar: read-only multi-line view of the field's text with our caret +
    // move arrows. Wraps long text and scrolls vertically when it overflows.
    Surface(color = AbSurface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(mirrorHeight)
                    .pointerInput(buffer) {
                        // Tap anywhere in the field — including blank space past the
                        // text or below the last line — to place the caret. Map the
                        // touch into text space (add the vertical scroll offset);
                        // getOffsetForPosition picks the right line + char and clamps
                        // to the end when you tap past everything.
                        detectTapGestures(
                            onLongPress = { pos ->
                                val l = caretLayout
                                if (buffer.isNotEmpty() && l != null) {
                                    val ty = pos.y + mirrorScroll.value
                                    val off = l.getOffsetForPosition(
                                        androidx.compose.ui.geometry.Offset(pos.x, ty),
                                    )
                                    selectWordAt(off)
                                } else {
                                    injector.buzz(longPress = true)
                                }
                            },
                            onTap = { pos ->
                                val l = caretLayout
                                if (buffer.isNotEmpty() && l != null) {
                                    val ty = pos.y + mirrorScroll.value
                                    val off = l.getOffsetForPosition(
                                        androidx.compose.ui.geometry.Offset(pos.x, ty),
                                    )
                                    setCaret(off)
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.TopStart,
            ) {
                if (buffer.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(2.dp).height(22.dp).background(
                                if (caretVisible) AbAccent else Color.Transparent,
                            ),
                        )
                        Text(
                            "Type with the keyboard below…",
                            color = AbOnSurfaceMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Box(Modifier.verticalScroll(mirrorScroll).fillMaxWidth()) {
                        Text(
                            text = buffer,
                            color = AbOnSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            onTextLayout = { caretLayout = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawWithContent {
                                    val l = caretLayout
                                    // Highlight the selection (behind the text).
                                    val a = selAnchor
                                    val selLo = if (a != null) minOf(a, caret).coerceIn(0, buffer.length) else caret
                                    val selHi = if (a != null) maxOf(a, caret).coerceIn(0, buffer.length) else caret
                                    val hasSel = a != null && selLo != selHi
                                    if (l != null && hasSel) {
                                        drawPath(
                                            l.getPathForRange(selLo, selHi),
                                            color = AbPrimary.copy(alpha = 0.40f),
                                        )
                                    }
                                    drawContent()
                                    // Caret only when there is no active selection.
                                    if (l != null &&
                                        (caretVisible || caretDragging) &&
                                        !hasSel
                                    ) {
                                        val safe = caret.coerceIn(0, buffer.length)
                                        val rect = runCatching { l.getCursorRect(safe) }.getOrNull()
                                        if (rect != null) {
                                            drawRect(
                                                color = AbAccent,
                                                topLeft = androidx.compose.ui.geometry.Offset(rect.left, rect.top),
                                                size = androidx.compose.ui.geometry.Size(2.dp.toPx(), rect.height),
                                            )
                                        }
                                    }
                                },
                        )
                    }
                }
                // Draggable selection handles — one at each end of the highlight.
                // Mirror-on-release: the on-screen selection updates live as you drag,
                // and the glasses field is re-selected only when you lift (in onRelease).
                val hAnchor = selAnchor
                val hLo = if (hAnchor != null) minOf(hAnchor, caret).coerceIn(0, buffer.length) else caret
                val hHi = if (hAnchor != null) maxOf(hAnchor, caret).coerceIn(0, buffer.length) else caret
                val hLayout = caretLayout
                if (buffer.isNotEmpty() && hAnchor != null && hLo != hHi && hLayout != null) {
                    val loRect = runCatching { hLayout.getCursorRect(hLo) }.getOrNull()
                    val hiRect = runCatching { hLayout.getCursorRect(hHi) }.getOrNull()
                    if (loRect != null) {
                        SelectionHandle(
                            cursorRect = loRect,
                            scrollPx = mirrorScroll.value,
                            currentOffset = { selRange()?.first ?: caret },
                            layout = { caretLayout },
                            onGrab = {
                                injector.buzz()
                                selRange()?.let { selAnchor = it.second; caret = it.first }
                                caretDragging = true
                            },
                            onMoveTo = { off ->
                                caret = off.coerceIn(0, buffer.length)
                            },
                            onRelease = {
                                caretDragging = false
                                mirrorSelection()
                            },
                        )
                    }
                    if (hiRect != null) {
                        SelectionHandle(
                            cursorRect = hiRect,
                            scrollPx = mirrorScroll.value,
                            currentOffset = { selRange()?.second ?: caret },
                            layout = { caretLayout },
                            onGrab = {
                                injector.buzz()
                                selRange()?.let { selAnchor = it.first; caret = it.second }
                                caretDragging = true
                            },
                            onMoveTo = { off ->
                                caret = off.coerceIn(0, buffer.length)
                            },
                            onRelease = {
                                caretDragging = false
                                mirrorSelection()
                            },
                        )
                    }
                } else if (buffer.isNotEmpty() && hLayout != null) {
                    // No selection: a single draggable handle under the caret so it can be
                    // slid to an exact spot (taps still place it; this fine-tunes). The
                    // on-screen caret follows the finger live; the glasses-field cursor is
                    // synced once on release (pushCaret), matching the selection handles.
                    val caretRect = runCatching {
                        hLayout.getCursorRect(caret.coerceIn(0, buffer.length))
                    }.getOrNull()
                    if (caretRect != null) {
                        SelectionHandle(
                            cursorRect = caretRect,
                            scrollPx = mirrorScroll.value,
                            currentOffset = { caret },
                            layout = { caretLayout },
                            onGrab = { injector.buzz(); blinkReset++; caretDragging = true },
                            onMoveTo = { off ->
                                caret = off.coerceIn(0, buffer.length)
                                blinkReset++
                            },
                            onRelease = {
                                caretDragging = false
                                pushCaret()
                            },
                        )
                    }
                }
                // Auto-fading scrollbar thumb on the right edge — shown only when the
                // text overflows the field. Geometry from mirrorScroll; fades via alpha.
                if (mirrorScroll.maxValue > 0) {
                    val dens = LocalDensity.current
                    val trackPx = with(dens) { mirrorHeight.toPx() }
                    val minThumb = with(dens) { 18.dp.toPx() }
                    val thumbPx = (trackPx * trackPx / (trackPx + mirrorScroll.maxValue))
                        .coerceIn(minThumb, trackPx)
                    val span = (trackPx - thumbPx).coerceAtLeast(0f)
                    val topPx = (mirrorScroll.value.toFloat() / mirrorScroll.maxValue) * span
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset { IntOffset(0, topPx.roundToInt()) }
                            .width(3.dp)
                            .height(with(dens) { thumbPx.toDp() })
                            .clip(RoundedCornerShape(2.dp))
                            .background(AbOnSurfaceMuted.copy(alpha = 0.6f * scrollbarAlpha.value)),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            val canLeft = caret > 0
            val canRight = caret < buffer.length
            Column(
                Modifier.height(mirrorHeight),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.width(42.dp).weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (canLeft) AbSurfaceElevated else AbSurface)
                        .holdRepeat(scope, canLeft) { moveCaret(-1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "◄",
                        color = if (canLeft) AbPrimaryBright else AbOnSurfaceMuted,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Box(
                    Modifier.width(42.dp).weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (canRight) AbSurfaceElevated else AbSurface)
                        .holdRepeat(scope, canRight) { moveCaret(1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "►",
                        color = if (canRight) AbPrimaryBright else AbOnSurfaceMuted,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
    // In-window magnifier bubble: same window as the drag (no Popup → no gesture cancel),
    // sits just above the field via a negative offset into the slack above it. The bubble
    // stays centred over the field; the zoom transform centres the dragged caret offset
    // inside it, so it shows the exact spot regardless of the bubble's own position.
    val magLayout = caretLayout
    if (caretDragging && magLayout != null) {
        val cr = runCatching { magLayout.getCursorRect(caret.coerceIn(0, buffer.length)) }.getOrNull()
        if (cr != null) {
            val zoom = 1.8f
            val crCenterY = (cr.top + cr.bottom) / 2f
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, -(56.dp.roundToPx() + 10.dp.roundToPx())) }
                    .size(132.dp, 56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AbSurfaceElevated)
                    .border(1.dp, AbPrimary.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    translate(cx - zoom * cr.left, cy - zoom * crCenterY) {
                        scale(zoom, zoom, pivot = Offset.Zero) {
                            val a = selAnchor
                            val selLo = if (a != null) minOf(a, caret).coerceIn(0, buffer.length) else caret
                            val selHi = if (a != null) maxOf(a, caret).coerceIn(0, buffer.length) else caret
                            val hasSel = a != null && selLo != selHi
                            if (hasSel) {
                                drawPath(magLayout.getPathForRange(selLo, selHi), color = AbPrimary.copy(alpha = 0.40f))
                            }
                            drawText(magLayout)
                            if (!hasSel) {
                                drawRect(
                                    color = AbAccent,
                                    topLeft = Offset(cr.left, cr.top),
                                    size = androidx.compose.ui.geometry.Size(1.5.dp.toPx(), cr.height),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }

    // Voice dictation. Plays a short activation beep + haptic so it's clear the mic is
    // live, then starts the recognizer; the result is inserted at the caret like a paste.
    // Android's recognizer is single-utterance, so continuous mode (continuousMic) just
    // re-arms it after each phrase. playBeep is false on those re-arms so the tone/haptic
    // don't fire every phrase.
    fun startVoice(playBeep: Boolean = true) {
        val avail = android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)
        if (!avail) {
            voiceMsg = "Voice recognition unavailable on this device"
            listening = false
            continuousMic = false
            return
        }
        voiceMsg = null
        val tone = if (playBeep) {
            injector.buzz()
            runCatching {
                android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80)
            }.getOrNull().also { runCatching { it?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150) } }
        } else {
            null
        }
        runCatching { speechRef.value?.destroy() }
        val sr = android.speech.SpeechRecognizer.createSpeechRecognizer(ctx)
        speechRef.value = sr
        sr.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buf: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                // If the user already stopped (tapped the mic to cancel), this is just the
                // recognizer's parting no-match from stopListening() — not a real failure.
                // Ignore it so it doesn't flash a red "didn't catch that" after a clean stop.
                if (!listening) return
                // In continuous mode a silence error (no match / speech timeout) is normal
                // between phrases — re-arm rather than stop, unless we've been silent for a
                // minute (auto-timeout backstop). Any other error ends the session cleanly.
                val silence = error == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
                    error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                when {
                    continuousMic && silence &&
                        System.currentTimeMillis() - lastResultAt.value <= 60_000L -> {
                        startVoice(playBeep = false)
                    }
                    continuousMic && silence -> {
                        listening = false
                        continuousMic = false
                        voiceMsg = "Continuous mic stopped after a minute of silence"
                    }
                    else -> {
                        listening = false
                        continuousMic = false
                        voiceMsg = "Didn't catch that — tap the mic to try again (err $error)"
                    }
                }
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            override fun onResults(results: android.os.Bundle?) {
                // Drop a result that arrives after a clean stop (see onError note above).
                if (!listening) return
                val text = results
                    ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotEmpty()) {
                    lastResultAt.value = System.currentTimeMillis()
                    val prefix = if (buffer.isEmpty() || buffer.endsWith(" ")) "" else " "
                    insert(prefix + text)
                }
                if (continuousMic) {
                    startVoice(playBeep = false)
                } else {
                    listening = false
                }
            }
        })
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        listening = true
        // Start after the beep finishes (so the tone isn't transcribed); re-arms have no
        // beep so they only need a short gap to let the previous recognizer release.
        scope.launch {
            kotlinx.coroutines.delay(if (playBeep) 220 else 80)
            runCatching { tone?.release() }
            // If a stop landed during the gap, don't restart.
            if (!listening) return@launch
            runCatching { sr.startListening(intent) }
                .onFailure {
                    listening = false
                    continuousMic = false
                    voiceMsg = "Couldn't start the microphone"
                }
        }
    }
    fun stopVoice() {
        continuousMic = false
        runCatching { speechRef.value?.stopListening() }
        listening = false
    }
    // Mic permission helpers, shared by the tap and long-press handlers.
    fun micPermitted(): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    fun promptMicPermission() {
        runCatching {
            ctx.startActivity(
                android.content.Intent(ctx, MicPermissionActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        voiceMsg = "Grant microphone permission on your phone, then tap the mic again"
    }
    // Gesture mode helpers. commitGesture recognizes the drawn strokes and types the
    // best letter; scheduleCommit fires it after a short pause (so multi-stroke letters
    // like E/F/A finish first); chooseGestureCandidate swaps in an alternate guess.
    fun commitGesture() {
        if (strokes.isEmpty()) return
        val snapshot = strokes.map { it.toList() }
        gestureInk.recognize(snapshot) { cands ->
            // A–Z mode: prefer single-letter guesses; fall back to whatever came back.
            // Case is forced by the Caps toggle, not ML Kit's (unreliable) returned case.
            fun cased(s: String) = if (gestureCaps) s.uppercase() else s.lowercase()
            val letters = cands.filter { it.length == 1 && it[0].isLetter() }
            val pick = letters.firstOrNull() ?: cands.firstOrNull()
            if (pick != null && pick.isNotEmpty()) {
                insert(cased(pick))
                lastCommitLen = pick.length
                gestureCandidates = (if (letters.isNotEmpty()) letters else cands)
                    .take(6).map { cased(it) }
                gestureMsg = null
            } else if (gestureReady) {
                gestureMsg = "Didn't recognize that — try again"
            }
            strokes.clear()
            currentStroke = emptyList()
            gestureGen++
        }
    }
    fun scheduleCommit() {
        gestureCommitJob.value?.cancel()
        gestureCommitJob.value = scope.launch {
            kotlinx.coroutines.delay(750)
            commitGesture()
        }
    }
    fun cancelCommit() {
        gestureCommitJob.value?.cancel()
        gestureCommitJob.value = null
    }
    fun clearGesture() {
        cancelCommit()
        strokes.clear()
        currentStroke = emptyList()
        gestureCandidates = emptyList()
        gestureGen++
    }
    fun chooseGestureCandidate(c: String) {
        if (c.isEmpty()) return
        injector.buzz()
        blinkReset++
        val cur = caret.coerceIn(0, buffer.length)
        val start = (cur - lastCommitLen).coerceAtLeast(0)
        buffer = buffer.substring(0, start) + c + buffer.substring(cur)
        caret = start + c.length
        lastCommitLen = c.length
        selAnchor = null
        pushText()
    }

    Spacer(Modifier.height(12.dp))
    // Clipboard actions. Select all highlights the whole field (mirrored to the glasses
    // field); Cut/Copy act on the selection or, if none, the whole text; Paste drops the
    // clipboard at the caret; the trash icon clears the field. While an Undo is on offer
    // the strip replaces this row (so the icon buttons stay a fixed narrow width, the four
    // text labels keep their room).
    if (clearedBackup != null) {
        Row(
            Modifier.fillMaxWidth().height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(9.dp))
                    .background(AbSurfaceElevated),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "Field cleared",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
            Box(
                Modifier
                    .width(104.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(9.dp))
                    .background(AbPrimary)
                    .clickable { undoClear() },
                contentAlignment = Alignment.Center,
            ) {
                Text("Undo", color = AbOnSurface, style = MaterialTheme.typography.labelMedium)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KbClipButton("Select all", weight = 1.5f) { selectAll() }
            KbClipButton("Cut") { cut() }
            KbClipButton("Copy") { copy() }
            KbClipButton("Paste") { paste() }
            KbClipButton(
                "Mic",
                active = listening,
                icon = Icons.Filled.Mic,
                fixedWidth = 40.dp,
                restingColor = AbMicTint,
                iconTint = AbPrimaryBright,
                onLongClick = {
                    // Press-and-hold = latched continuous dictation.
                    if (!listening) {
                        if (micPermitted()) {
                            injector.buzz(longPress = true)
                            continuousMic = true
                            lastResultAt.value = System.currentTimeMillis()
                            startVoice()
                        } else {
                            promptMicPermission()
                        }
                    }
                },
            ) {
                // Tap = stop if live, else a single phrase that auto-stops.
                injector.buzz()
                when {
                    listening -> stopVoice()
                    micPermitted() -> {
                        continuousMic = false
                        startVoice()
                    }
                    else -> promptMicPermission()
                }
            }
            KbClipButton(
                "Clear all",
                icon = Icons.Filled.Delete,
                fixedWidth = 40.dp,
                restingColor = AbClearTint,
                iconTint = AbClearIcon,
            ) { clearAll() }
        }
    }
    if (voiceMsg != null) {
        Text(
            voiceMsg ?: "",
            color = AbDanger,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }

    // The keys.
    val letterRows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m"),
    )
    val symbolRows = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
        listOf("*", "\"", "'", ":", ";", "!", "?"),
    )
    val rows = if (symbols) symbolRows else letterRows

    // Label for a letter/number key: applies the active accent layer (letter mode
    // only) then shift. Numbers and unmapped letters fall through unchanged.
    fun keyLabel(k: String): String {
        if (symbols) return k
        val accented = if (letterLayer in 1..ACCENT_TIERS.size) {
            ACCENT_TIERS[letterLayer - 1][k.firstOrNull()]
        } else null
        val ch = accented?.toString() ?: k
        return if (shift) ch.uppercase() else ch
    }
    // True when this key's glyph is swapped by the active accent layer — used to tint
    // it (highlight A: glyph only). False on the normal layer and for unmapped keys.
    fun isSwapped(k: String): Boolean =
        !symbols && letterLayer in 1..ACCENT_TIERS.size &&
            ACCENT_TIERS[letterLayer - 1][k.firstOrNull()] != null
    // Record an emoji into the persisted recent list (most-recent-first, deduped, capped).
    fun recordRecent(e: String) {
        val updated = (listOf(e) + recent.filter { it != e }).take(32)
        recent = updated
        prefs.edit().putString("recent_emoji", updated.joinToString("\n")).apply()
    }

    val emojiScroll = rememberScrollState()
    // Bottom slack: mirrors the top spacer so the field + clipboard group sits centered
    // in the space above the keyboard, which stays pinned to the bottom. The extra fixed
    // 16dp matches the outer Column's spacedBy(16) gap that sits ABOVE this BoxWithConstraints
    // (between the keyboard-source toggle and the field) — without it the top gap would read
    // 16dp larger than the bottom gap and the group would look low rather than centered.
    Spacer(Modifier.weight(1f))
    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (listening) {
            // Listening panel replaces the keyboard while the mic is live.
            val inf = rememberInfiniteTransition(label = "mic")
            val pulse by inf.animateFloat(
                initialValue = 1f,
                targetValue = 1.12f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                label = "pulse",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(LocalKbKeyHeight.current * 5)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AbSurface),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(64.dp).scale(pulse).clip(CircleShape).background(AbPrimary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Microphone",
                            tint = AbOnSurface,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Text(
                        if (continuousMic) "Listening — continuous" else "Listening…",
                        color = AbOnSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (continuousMic) "Keep talking — tap the mic to stop" else "Tap the mic again to stop",
                        color = AbOnSurfaceMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        } else if (gesture) {
            // Candidate / status row.
            Row(
                Modifier.fillMaxWidth().height(34.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    gestureMsg != null -> Text(
                        gestureMsg ?: "",
                        color = if (gestureReady) AbOnSurfaceMuted else AbAccent,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    gestureCandidates.isNotEmpty() -> {
                        gestureCandidates.forEachIndexed { i, c ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (i == 0) AbPrimary else AbSurfaceElevated)
                                    .clickable { chooseGestureCandidate(c) }
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            ) {
                                Text(c, color = AbOnSurface, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    else -> Text(
                        "Draw a letter",
                        color = AbOnSurfaceMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            // Drawing canvas.
            val canvasH = LocalKbKeyHeight.current * 4
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(canvasH)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AbSurface)
                    .pointerInput(gestureGen) {
                        awaitEachGesture {
                            val first = awaitFirstDown()
                            cancelCommit()
                            gestureCandidates = emptyList()
                            val pts = ArrayList<InkPoint>()
                            val t0 = System.currentTimeMillis()
                            pts.add(InkPoint(first.position.x, first.position.y, 0L))
                            currentStroke = ArrayList(pts)
                            first.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull()
                                if (change == null || !change.pressed) break
                                pts.add(
                                    InkPoint(
                                        change.position.x,
                                        change.position.y,
                                        System.currentTimeMillis() - t0,
                                    ),
                                )
                                currentStroke = ArrayList(pts)
                                change.consume()
                            }
                            strokes.add(ArrayList(pts))
                            currentStroke = emptyList()
                            scheduleCommit()
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val allStrokes = strokes + listOf(currentStroke)
                    for (pts in allStrokes) {
                        if (pts.size < 2) continue
                        val path = Path()
                        path.moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                        drawPath(
                            path,
                            color = AbPrimaryBright,
                            style = Stroke(width = 9f, cap = StrokeCap.Round),
                        )
                    }
                }
                if (!gestureReady) {
                    Text(
                        gestureMsg ?: "Preparing handwriting…",
                        color = if (gestureFailed) AbAccent else AbOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp)
                            .then(
                                if (gestureFailed) {
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { prepareGesture() }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                } else {
                                    Modifier
                                }
                            ),
                    )
                } else if (strokes.isEmpty() && currentStroke.isEmpty()) {
                    Text(
                        "✍  draw here",
                        color = AbOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            // Gesture-mode bottom row.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                KbKey("ABC", weight = 2f, bg = AbSurface, fg = AbOnSurfaceMuted) {
                    injector.buzz()
                    clearGesture()
                    gesture = false
                }
                KbKey(
                    "Caps",
                    weight = 1.7f,
                    bg = if (gestureCaps) AbPrimary else AbSurface,
                    fg = if (gestureCaps) AbOnSurface else AbOnSurfaceMuted,
                ) {
                    injector.buzz()
                    gestureCaps = !gestureCaps
                }
                KbKey("clear", weight = 1.6f, bg = AbSurface, fg = AbOnSurfaceMuted) {
                    injector.buzz()
                    clearGesture()
                }
                KbKey("space", weight = 2.5f, fg = AbOnSurfaceMuted, repeatScope = scope) { insert(" ") }
                KbKey("⌫", weight = 1.6f, bg = AbSurface, fg = AbDanger, repeatScope = scope) { backspace() }
            }
        } else if (emoji) {
            // Emoji tabs (Recent first, then the fixed categories).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                EMOJI_TABS.forEachIndexed { i, glyph ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (i == emojiCat) AbPrimary else AbSurfaceElevated)
                            .clickable { injector.buzz(); emojiCat = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(glyph, fontSize = 17.sp)
                    }
                }
            }
            // Grid for the selected category (8 columns, scrolls vertically).
            val emojiList = if (emojiCat == 0) recent else EMOJI_CATEGORIES[emojiCat - 1]
            val gridH = LocalKbKeyHeight.current * 4
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(gridH)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AbSurface),
            ) {
                if (emojiList.isEmpty()) {
                    Text(
                        "No recent emoji yet",
                        color = AbOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(emojiScroll).padding(4.dp),
                    ) {
                        emojiList.chunked(8).forEach { rowItems ->
                            Row(Modifier.fillMaxWidth()) {
                                rowItems.forEach { e ->
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .height(LocalKbKeyHeight.current)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { insert(e); recordRecent(e) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(e, fontSize = 22.sp)
                                    }
                                }
                                repeat(8 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
            // Emoji-mode bottom row: back to letters, space, backspace.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                KbKey("ABC", weight = 2f, bg = AbSurface, fg = AbOnSurfaceMuted) {
                    injector.buzz()
                    emoji = false
                }
                KbKey("space", weight = 4f, fg = AbOnSurfaceMuted, repeatScope = scope) { insert(" ") }
                KbKey("⌫", weight = 2f, bg = AbSurface, fg = AbDanger, repeatScope = scope) { backspace() }
            }
        } else {
        // Swap-layer control (letter mode only): cycles the whole board through the
        // accent tiers. Dots show the current layer; the label names it.
        if (!symbols) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (letterLayer > 0) AbPrimary else AbSurfaceElevated)
                        .clickable {
                            injector.buzz()
                            letterLayer = (letterLayer + 1) % (ACCENT_TIERS.size + 1)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "⟳  Swap layer",
                        color = if (letterLayer > 0) AbOnSurface else AbOnSurfaceMuted,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 0..ACCENT_TIERS.size) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (i == letterLayer) AbPrimaryBright else AbSurface),
                        )
                    }
                }
                Text(
                    ACCENT_NAMES[letterLayer],
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Persistent number row in letter mode (symbol mode's first row is already
        // digits, so it isn't duplicated there).
        if (!symbols) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").forEach { d ->
                    KbKey(d, bg = AbSurface, fg = AbPrimaryBright, repeatScope = scope, preview = true) { insert(d) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            rows[0].forEach { k ->
                KbKey(
                    keyLabel(k),
                    fg = if (isSwapped(k)) AbAccent else AbOnSurface,
                    repeatScope = scope,
                    preview = true,
                ) { insert(keyLabel(k)) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (symbols) 0.dp else 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            rows[1].forEach { k ->
                KbKey(
                    keyLabel(k),
                    fg = if (isSwapped(k)) AbAccent else AbOnSurface,
                    repeatScope = scope,
                    preview = true,
                ) { insert(keyLabel(k)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (symbols) {
                Spacer(Modifier.weight(1.5f))
            } else {
                KbKey("⇧", weight = 1.5f, bg = AbSurface, fg = if (shift) AbAccent else AbPrimaryBright) {
                    injector.buzz()
                    shift = !shift
                }
            }
            rows[2].forEach { k ->
                KbKey(
                    keyLabel(k),
                    fg = if (isSwapped(k)) AbAccent else AbOnSurface,
                    repeatScope = scope,
                    preview = true,
                ) { insert(keyLabel(k)) }
            }
            KbKey("⌫", weight = 1.5f, bg = AbSurface, fg = AbDanger, repeatScope = scope) { backspace() }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            KbKey(if (symbols) "ABC" else "?123", weight = 1.5f, bg = AbSurface, fg = AbOnSurfaceMuted) {
                injector.buzz()
                symbols = !symbols
            }
            if (!symbols) {
                KbKey("🙂", weight = 1f, bg = AbSurface) {
                    android.util.Log.w("DIAG-RELAY", "emoji mode -> true")
                    injector.buzz()
                    emoji = true
                }
                KbKey("✍", weight = 1f, bg = AbSurface) {
                    injector.buzz()
                    gesture = true
                }
            }
            KbKey(",", weight = 1f, repeatScope = scope, preview = true) { insert(",") }
            KbKey("space", weight = 2.8f, fg = AbOnSurfaceMuted, repeatScope = scope) { insert(" ") }
            KbKey(".", weight = 1f, repeatScope = scope, preview = true) { insert(".") }
            KbKey("↵", weight = 1.6f, bg = AbAccent, fg = AbBackground) {
                injector.buzz(longPress = true)
                injector.submitSearch()
                injector.pressKey(KeyEvent.KEYCODE_ENTER)
            }
        }
        }
    }
}

/**
 * Map a Char to a KeyEvent and inject. Uses the system's VIRTUAL_KEYBOARD
 * [android.view.KeyCharacterMap] to resolve ANY mappable character to its key +
 * shift metaState, then presses it via [InputInjector.pressKeyWithMeta] (which
 * carries META_SHIFT_ON on the key events, the part text widgets actually read).
 *
 * History: the old hand-rolled table silently DROPPED every symbol not in it
 * (@ # $ _ & + ( ) * " : ! ? — the whole ?123 second/third rows), and typed
 * capitals as lowercase because it "held" shift as a separate press-RELEASE
 * that completed before the letter went down. Field report: "keys send the
 * wrong keys" in both ?123 and ABC mode.
 */
private val VIRTUAL_KEYMAP: android.view.KeyCharacterMap by lazy {
    android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
}

private fun forwardCharacter(ch: Char, injector: InputInjector) {
    // Fast common paths the keymap also covers, kept explicit for clarity.
    when (ch) {
        ' ' -> { injector.pressKeyWithMeta(KeyEvent.KEYCODE_SPACE, 0); return }
        '\n' -> { injector.pressKeyWithMeta(KeyEvent.KEYCODE_ENTER, 0); return }
    }
    val events = runCatching { VIRTUAL_KEYMAP.getEvents(charArrayOf(ch)) }.getOrNull()
    if (events == null) {
        android.util.Log.w(
            "PortalPadRelay",
            "forwardCharacter: no keymap events for '$ch' (U+${"%04X".format(ch.code)}) — dropped",
        )
        return
    }
    // The keymap returns a full physical sequence (SHIFT down, key down/up,
    // SHIFT up). The principal key's DOWN carries the metaState; pressing it
    // with that metaState is sufficient — text widgets resolve the character
    // from (keyCode, metaState), a physically held shift key isn't required.
    val principal = events.firstOrNull {
        it.action == KeyEvent.ACTION_DOWN &&
            it.keyCode != KeyEvent.KEYCODE_SHIFT_LEFT &&
            it.keyCode != KeyEvent.KEYCODE_SHIFT_RIGHT
    } ?: return
    injector.pressKeyWithMeta(principal.keyCode, principal.metaState)
}

/**
 * Contextual dependency warning — the first row of the relay. Each keyboard path has
 * ONE hard dependency, surfaced only for the SELECTED path so the two never stack:
 *   - Custom keyboard types via the accessibility service (SET_TEXT); if it isn't
 *     connected, typing silently goes nowhere. Tap shell-enables it (the user already
 *     has Shizuku/Root here), then falls back to the accessibility settings screen if
 *     the service still doesn't bind (e.g. an OEM restricted-settings gate).
 *   - Android keyboard forwards every key via Shizuku/Root (pressKey); if privilege
 *     isn't ready, keystrokes go nowhere. Tap opens the same Shizuku popup / root-grant
 *     the mode strip uses (None routes to Start > Setup).
 * Renders nothing once the selected path's dependency is satisfied.
 */
@Composable
private fun RelayDependencyChip(customKeyboard: Boolean) {
    val app = com.portalpad.app.PortalPadApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val accessMode by app.prefs.accessMode.collectAsState(
        initial = com.portalpad.app.data.AccessMode.NONE,
    )
    val shizukuStatus by app.shizuku.status.collectAsState()
    val privilegeReady = when (accessMode) {
        com.portalpad.app.data.AccessMode.SHIZUKU ->
            shizukuStatus == com.portalpad.app.shizuku.ShizukuManager.Status.READY
        com.portalpad.app.data.AccessMode.ROOT -> app.root.isReady
        com.portalpad.app.data.AccessMode.NONE -> false
    }

    // The accessibility service exposes a plain static `instance`, not a flow, so it
    // won't recompose on its own. Re-evaluate on every ON_RESUME (returning from the
    // settings screen) and whenever accessTick is bumped after a fix attempt.
    var accessTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) accessTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val accessibilityConnected = remember(accessTick) {
        PortalPadAccessibilityService.instance != null
    }

    var showShizukuDialog by remember { mutableStateOf(false) }

    var lead: String? = null
    var rest = ""
    var action = ""
    var onTap: () -> Unit = {}

    if (customKeyboard) {
        if (!accessibilityConnected) {
            lead = "Accessibility off"
            rest = " — the on-screen keyboard can't reach the field. "
            action = "· Tap to enable"
            onTap = {
                app.injector.buzz()
                scope.launch {
                    runCatching {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            enableAccessibilityViaShell(app)
                        }
                    }
                    // Poll for the service to actually bind; if it never does (privilege
                    // down, or an OEM restricted-settings gate), fall back to the
                    // accessibility settings screen so it can be flipped by hand.
                    var connected = false
                    var i = 0
                    while (i < 6 && !connected) {
                        kotlinx.coroutines.delay(400)
                        accessTick++
                        if (PortalPadAccessibilityService.instance != null) connected = true
                        i++
                    }
                    if (!connected) openAccessibilitySettings(context)
                }
            }
        }
    } else if (!privilegeReady) {
        rest = " — keystrokes won't reach the field. "
        when (accessMode) {
            com.portalpad.app.data.AccessMode.ROOT -> {
                lead = "Root not connected"
                action = "· Tap to fix"
                onTap = {
                    app.injector.buzz()
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        app.root.refresh()
                        if (app.root.isReady) {
                            app.root.grantInjectEvents()
                            app.rootClickBackend.bind()
                        }
                    }
                }
            }
            com.portalpad.app.data.AccessMode.NONE -> {
                lead = "No input source set"
                action = "· Tap to set up"
                onTap = {
                    app.injector.buzz()
                    runCatching {
                        context.startActivity(
                            android.content.Intent(context, com.portalpad.app.MainActivity::class.java)
                                .putExtra(com.portalpad.app.MainActivity.EXTRA_OPEN_SETUP, true)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            else -> {
                lead = "Shizuku not connected"
                action = "· Tap to fix"
                onTap = {
                    app.injector.buzz()
                    showShizukuDialog = true
                }
            }
        }
    }

    if (lead != null) {
        val warnYellow = Color(0xFFE6B800)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(warnYellow.copy(alpha = 0.10f))
                .border(1.dp, warnYellow.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .clickable { onTap() }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = androidx.compose.ui.Alignment.Top,
        ) {
            Text("⚠", color = warnYellow, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(9.dp))
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = warnYellow,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        ),
                    )
                    append(lead!!)
                    pop()
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = AbOnSurfaceMuted))
                    append(rest)
                    pop()
                    pushStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = warnYellow,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        ),
                    )
                    append(action)
                    pop()
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showShizukuDialog) {
        com.portalpad.app.ui.common.ShizukuControlDialog(onDismiss = { showShizukuDialog = false })
    }
}

private fun enableAccessibilityViaShell(app: com.portalpad.app.PortalPadApp) {
    val comp = "com.portalpad.app/com.portalpad.app.service.PortalPadAccessibilityService"
    val cur = runCatching {
        app.access.execShell("settings get secure enabled_accessibility_services").trim()
    }.getOrDefault("")
    val list = when {
        cur.isEmpty() || cur == "null" -> comp
        cur.split(":").contains(comp) -> cur
        else -> "$cur:$comp"
    }
    app.access.execShell("settings put secure enabled_accessibility_services $list")
    app.access.execShell("settings put secure accessibility_enabled 1")
}

private fun openAccessibilitySettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
