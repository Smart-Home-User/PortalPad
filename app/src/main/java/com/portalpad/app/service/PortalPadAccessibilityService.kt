package com.portalpad.app.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.portalpad.app.KeyboardRelayActivity
import com.portalpad.app.PortalPadApp

/**
 * Detects when an editable text field gains focus (or is tapped) on the external
 * display and opens the relay ("Type to external display") on the phone so the
 * user can type — automatically, without reaching for the keyboard button.
 *
 * WHY ACCESSIBILITY (not the old poller): the previous auto-open scraped
 * `dumpsys input_method` a few times a second and guessed, which couldn't tell
 * which display held the *focused* editor — so it false-fired on the phone's own
 * search fields and was flaky. An accessibility service instead receives real
 * focus/click events with the source node, and the node's window carries a
 * display id — so we can gate precisely on "editable field on the glasses VD".
 * This is the same mechanism comparable trackpad apps use.
 *
 * Listening to BOTH focus and click matters for re-entry: when you leave a field
 * and come back, tapping it fires TYPE_VIEW_CLICKED even though the system thinks
 * focus never changed — so the relay still opens on return.
 *
 * The relay is a foreground activity on display 0: when it opens, its own text
 * box takes focus and gets the phone keyboard natively, and each keystroke is
 * forwarded to the focused glasses field. So it works for ANY field, including
 * the ones the native keyboard refuses (Chrome address bar, etc.).
 *
 * Enabling/disabling this service in Android Settings → Accessibility is itself
 * the on/off switch; there's no separate in-app toggle.
 */
class PortalPadAccessibilityService : AccessibilityService() {

    @Volatile private var lastLaunchAt = 0L
    // When a non-PortalPad window last appeared (WINDOW_STATE_CHANGED). Used to tell
    // a genuine field tap from an app-launch auto-focus: if a window appeared AFTER
    // your most recent glasses tap, a following FOCUS is auto-focus, not your tap.
    @Volatile private var lastWindowStateAt = 0L
    // The foreground app's package + when it last SWITCHED to a different app. This is
    // a finer signal than lastWindowStateAt: it separates a within-app window change
    // (Play Store opening its search activity: vending→vending) from a real app launch
    // (Chrome cold-start: launcher→chrome). The auto-focus guard suppresses a field
    // focus only when the app SWITCHED after your tap, so in-app "open search" flows
    // are allowed while cold-launch auto-focus stays suppressed.
    @Volatile private var lastForegroundPkg: String? = null
    @Volatile private var lastAppSwitchAt = 0L
    // Package + time of the last real CLICK on any view (not just editable fields —
    // e.g. YouTube's search icon). A FOCUS that follows a click in the SAME app is a
    // deliberate in-app action ("open search"), which the auto-focus guard allows
    // even across a window change. Cold app-launch has no same-app click (the launch
    // came from PortalPad's dock), so its auto-focus stays suppressed.
    @Volatile private var lastClickPkg: String? = null
    @Volatile private var lastClickAt = 0L
    // Click-triggered field probe: a non-editable click (e.g. a search icon) may open a
    // field that auto-focuses with NO accessibility event (YouTube's search box). We
    // post a short burst of checks that query the focused node directly.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val probeToken = Any()
    // Live reference to the last tapped editable field on the glasses. The relay
    // uses it to DELETE via ACTION_SET_TEXT (no DEL key → no focus bounce → no IME
    // cursor desync, which is why keystroke-DEL could only delete once on a field
    // that already had text). Set when the relay opens; refreshed on each use.
    @Volatile private var targetNode: android.view.accessibility.AccessibilityNodeInfo? = null

    private fun tn(t: Int) = when (t) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICK"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "FOCUS"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "SELCHG"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "A11YFOCUS"
        else -> "type$t"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val type = event.eventType
        // Track window transitions: a WINDOW_STATE_CHANGED means an app/window just
        // appeared. We use that below to separate a genuine field tap from an
        // app-launch auto-focus. Ignore our OWN windows (the relay opening is itself
        // a window change, and would otherwise poison the discriminator).
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val wp = event.packageName?.toString()
            if (wp != null && wp != packageName) {
                lastWindowStateAt = SystemClock.elapsedRealtime()
                // Only count it as an app SWITCH when the package actually changed.
                // A within-app navigation (Play Store home → its search activity) keeps
                // the same package and must NOT be treated as a launch.
                if (wp != lastForegroundPkg) {
                    lastAppSwitchAt = SystemClock.elapsedRealtime()
                    lastForegroundPkg = wp
                }
            }
            return
        }
        // Record the package of the last real CLICK (any clickable view — e.g.
        // YouTube's search icon — not only editable fields). The FOCUSED guard below
        // uses this to allow a deliberate in-app "open search" that auto-focuses its
        // field across a window change.
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val cp = event.packageName?.toString()
            val clickEditable = runCatching { event.source?.isEditable == true }.getOrDefault(false)
            Log.i(TAG, "DIAG click pkg=$cp editable=$clickEditable")
            if (cp != null && cp != packageName) {
                lastClickPkg = cp
                lastClickAt = SystemClock.elapsedRealtime()
            }
        }
        // Open on a real field CLICK or FOCUS. CLICKED alone missed fields that only
        // FOCUS on the first tap — WebView inputs (e.g. google.com's search box) and
        // some app compose boxes (Discord) fire VIEW_FOCUSED first and only emit
        // VIEW_CLICKED on a SECOND tap, which is why those needed a double-tap.
        // FOCUSED is re-admitted here, but gated below (the auto-focus guard) so an
        // app auto-focusing a field on launch / navigation still can't pop the relay
        // unprompted — the original reason this was CLICKED-only.
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) return

        val app = applicationContext as? PortalPadApp ?: return
        // Respect the in-app master toggle: "Auto-open typing page on field tap".
        // Even when this service is enabled in Android Settings, the toggle is the
        // real on/off — turning it off stops auto-open without disabling the service.
        if (!app.injector.autoOpenRelayEnabled) return
        // Only act in phone-as-keyboard mode (glasses mode keeps the keyboard on
        // the VD), and only when an external display is actually present.
        if (app.injector.imeOnExternalEnabled) return
        if (app.externalDisplayId.value == null) return
        // With a physical keyboard connected, keystrokes route straight to the
        // focused field on the glasses — so the phone relay page is redundant
        // (and can't track your typing, since none of it reaches the page). Skip
        // the auto-popup. The manual keyboard button still opens it on demand.
        if (hasHardwareKeyboard()) { Log.i(TAG, "DIAG skip: hardware keyboard present"); return }
        // Don't re-trigger while the relay is already showing. Selection-change events
        // fire as we inject text into the glasses field during typing, and without
        // this each keystroke's SELCHG would try to relaunch the relay.
        if (app.relayOpen) { Log.i(TAG, "DIAG skip: relay already open"); return }

        val node = event.source ?: run { Log.i(TAG, "DIAG ${tn(type)} null source"); return }
        try {
            val pkg = node.packageName?.toString()
            Log.i(
                TAG,
                "DIAG ${tn(type)} pkg=$pkg editable=${node.isEditable} " +
                    "cls=${node.className} vid=${runCatching { node.viewIdResourceName }.getOrNull()} " +
                    "lastClick=$lastClickPkg (${SystemClock.elapsedRealtime() - lastClickAt}ms ago)",
            )
            // Ignore PortalPad's own UI (e.g. the on-glasses search overlay) so we
            // don't open the relay for our own fields.
            if (pkg == packageName) return
            // Browser field auto-open: only a deliberate CLICK on an editable node
            // (you tapping the address bar) should open the relay. Everything else in a
            // browser — FOCUS / A11YFOCUS / the probe — is Chrome auto-focusing its
            // ever-present omnibox on new-tab / navigation, which popped the relay
            // unprompted. CLICK-on-editable is the one signal a real tap produces that
            // auto-focus does not. (In-page web fields don't emit a clean editable
            // CLICK, so they stay manual — use the keyboard button there.)
            if (pkg != null && pkg in BROWSER_PKGS) {
                val deliberateFieldTap =
                    type == AccessibilityEvent.TYPE_VIEW_CLICKED && node.isEditable == true
                if (!deliberateFieldTap) {
                    Log.i(TAG, "DIAG skip: browser pkg=$pkg evt=${tn(type)} editable=${node.isEditable} — auto-open suppressed")
                    return
                }
                Log.i(TAG, "DIAG browser pkg=$pkg editable CLICK → allow")
            }
            val editable = node.isEditable == true
            // The display the focused field lives on. getWindow() requires
            // canRetrieveWindowContent + flagRetrieveInteractiveWindows (set in
            // the config xml); getDisplayId() is available from API 30.
            //
            // Gate on the VIRTUAL display, NOT the physical glasses id. Apps and
            // their fields render on the VirtualDisplay (app.virtualDisplayId), not
            // the physical external display id (externalDisplayId.value).
            //
            // If the display id RESOLVES, enforce it: must be a non-phone display,
            // and the VD if we know it. If it's NULL, do NOT bail — on the FIRST
            // focus event the field's window often isn't attached yet (this is what
            // made the search icon not open the relay, and made some fields need a
            // second tap). Fall through to the recent-glasses-tap gate below, which
            // is itself proof the interaction is on the external display.
            val displayId = try { node.window?.displayId } catch (t: Throwable) { null }
            if (displayId != null) {
                if (displayId == 0) { Log.i(TAG, "DIAG reject: phone display 0"); return }
                val vd = app.virtualDisplayId
                if (vd != null && displayId != vd) {
                    Log.i(TAG, "DIAG reject: disp=$displayId vd=$vd"); return
                }
            } else {
                Log.i(TAG, "DIAG field display unresolved (evt=${tn(type)}) — using tap gate")
            }

            val now = SystemClock.elapsedRealtime()
            // Require a recent glasses TAP, same signal the poller uses. Without
            // this, an app that auto-focuses a field on launch (Chrome cold-start
            // focusing the omnibox / a page input) fires TYPE_VIEW_FOCUSED with no
            // user intent, and the relay pops unprompted. Gating on a real tap means
            // we only open when you actually tapped a field — first entry or
            // re-entry — and ignore auto-focus. tapWindowMs mirrors the poller (2s).
            if (now - app.lastGlassesTapAt > TAP_WINDOW_MS) {
                Log.i(TAG, "DIAG reject: no recent tap (${now - app.lastGlassesTapAt}ms)")
                return
            }
            // Auto-focus guard (app-SWITCH based): applies to every NON-CLICK trigger
            // (focus / caret / a11y-focus). If the foreground APP switched AFTER your
            // most recent glasses tap, this is app-launch auto-focus (Chrome cold-start
            // focusing the omnibox) — not a field you opened — so skip it. A WITHIN-app
            // window change (Play Store opening its search activity: vending→vending) is
            // NOT an app switch, so it's allowed — that's what made Play Store fail with
            // the older "any window changed" guard. EXCEPTION: a recent click in THIS
            // SAME app (YouTube's search icon) is always a deliberate action → allow.
            if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
                lastAppSwitchAt > app.lastGlassesTapAt) {
                val sameAppClick = pkg != null && pkg == lastClickPkg &&
                    now - lastClickAt < TAP_WINDOW_MS
                Log.i(
                    TAG,
                    "DIAG focus-guard: app switched after tap (fg=$lastForegroundPkg); " +
                        "sameAppClick=$sameAppClick (lastClick=$lastClickPkg fieldPkg=$pkg)",
                )
                if (!sameAppClick) return
            }
            if (now - lastLaunchAt < 2000) {
                Log.i(TAG, "DIAG reject: cooldown (${now - lastLaunchAt}ms)")
                return
            }
            if (editable) {
                // The tapped/focused node IS the field — open directly.
                openRelayForField(node, displayId, tn(type))
            } else {
                // A non-editable trigger that passed every gate: the user tapped or
                // focused something (a search icon, Play Store's search bar) that may
                // open a field which auto-focuses with no further event we receive.
                // Probe the VD for that focused field. pkg is non-null (own-UI checked).
                Log.i(TAG, "DIAG non-editable trigger passed gates → probe (pkg=$pkg)")
                pkg?.let { scheduleFieldProbe(it, app.virtualDisplayId) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onAccessibilityEvent failed", t)
        } finally {
            // Don't recycle the node we just retained as targetNode (the relay needs
            // it). Recycle only nodes from early-return / non-open paths.
            if (node !== targetNode) { try { node.recycle() } catch (_: Throwable) {} }
        }
    }

    /** True when a real (non-virtual) alphabetic keyboard is enumerated — a
     *  connected Bluetooth/USB keyboard. The ALPHABETIC + non-virtual checks
     *  exclude the soft-IME virtual device and plain mice (which report
     *  KEYBOARD_TYPE_NONE), so this fires only for an actual hardware keyboard. */
    private fun hasHardwareKeyboard(): Boolean = runCatching {
        val im = getSystemService(android.content.Context.INPUT_SERVICE)
            as? android.hardware.input.InputManager ?: return false
        im.inputDeviceIds.any { id ->
            val d = im.getInputDevice(id) ?: return@any false
            !d.isVirtual &&
                d.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                (d.sources and android.view.InputDevice.SOURCE_KEYBOARD) ==
                android.view.InputDevice.SOURCE_KEYBOARD
        }
    }.getOrDefault(false)

    /** Opens the relay for [node] (a focused editable field on the glasses). Shared by
     *  the event path and the click-triggered probe. Sets the launch timestamp and
     *  retains the node so the relay can delete via ACTION_SET_TEXT. */
    private fun openRelayForField(
        node: android.view.accessibility.AccessibilityNodeInfo,
        displayId: Int?,
        evt: String,
    ) {
        lastLaunchAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "DIAG opening relay (evt=$evt pkg=${node.packageName} disp=$displayId)")
        // Prefill: read the field's current text so the relay box opens showing it (lets
        // backspace delete the existing text). Skip password fields, and skip PLACEHOLDER
        // text — some fields (YouTube/Chrome search) report their HINT as the text when
        // empty, so text == hint means it's really empty and we open blank.
        val rawText = node.text?.toString()
        val rawHint = try { node.hintText?.toString() } catch (t: Throwable) { null }
        val isPlaceholder = rawText != null && rawText == rawHint
        val prefill = if (node.isPassword || isPlaceholder) null
            else rawText?.takeIf { it.isNotEmpty() }
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()
        targetNode?.let { old -> if (old !== node) runCatching { old.recycle() } }
        targetNode = node
        runCatching {
            startActivity(
                android.content.Intent(applicationContext, KeyboardRelayActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    .apply { if (prefill != null) putExtra(KeyboardRelayActivity.EXTRA_PREFILL, prefill) },
                opts,
            )
        }
    }

    /** After a non-editable trigger (a search-icon click, or Play Store's search-bar
     *  focus), poll a few times for a focused editable field that appeared in [pkg] on
     *  the VD (a field that auto-focused without firing an event we receive). Stops as
     *  soon as one is found / the relay opens. */
    private fun scheduleFieldProbe(pkg: String, vd: Int?) {
        Log.i(TAG, "DIAG probe scheduled (pkg=$pkg vd=$vd)")
        mainHandler.removeCallbacksAndMessages(probeToken)
        for (d in longArrayOf(150, 350, 600, 900)) {
            mainHandler.postAtTime(
                { probeForFocusedField(pkg, vd) },
                probeToken,
                android.os.SystemClock.uptimeMillis() + d,
            )
        }
    }

    private fun probeForFocusedField(pkg: String, vd: Int?) {
        runCatching {
            val app = applicationContext as? PortalPadApp ?: return
            if (!app.injector.autoOpenRelayEnabled || app.injector.imeOnExternalEnabled) return
            if (app.externalDisplayId.value == null || app.relayOpen) return
            if (hasHardwareKeyboard()) return
            if (SystemClock.elapsedRealtime() - lastLaunchAt < 2000) return
            val node = findFocusedEditableOnVd(vd, pkg) ?: return
            Log.i(TAG, "DIAG probe FOUND editable (pkg=$pkg) → opening relay")
            mainHandler.removeCallbacksAndMessages(probeToken)   // stop remaining probes
            openRelayForField(node, vd, "PROBE")
        }
    }

    /** Scans the interactive windows for a focused editable node on the VD belonging to
     *  [pkg]. Queries the live tree (no event needed), which is how we catch fields that
     *  auto-focus silently. */
    private fun findFocusedEditableOnVd(
        vd: Int?,
        pkg: String,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        // getWindows() returns DEFAULT-display windows only; app fields live on the VD,
        // so we must use getWindowsOnAllDisplays() (a SparseArray keyed by display id).
        val byDisplay = runCatching { windowsOnAllDisplays }.getOrNull()
        if (byDisplay == null) { Log.i(TAG, "DIAG probe: windowsOnAllDisplays null"); return null }
        val displays = (0 until byDisplay.size()).map { byDisplay.keyAt(it) }
        Log.i(TAG, "DIAG probe scan displays=$displays vd=$vd")
        for (i in 0 until byDisplay.size()) {
            val disp = byDisplay.keyAt(i)
            if (disp == 0) continue                    // skip the phone
            if (vd != null && disp != vd) continue     // enforce the VD when known
            val wins = byDisplay.valueAt(i) ?: continue
            for (w in wins) {
                val root = runCatching { w.root }.getOrNull() ?: continue
                // 1. The input-focused node. For most apps this IS the editable field.
                val f = runCatching {
                    root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                }.getOrNull()
                if (f != null) {
                    Log.i(
                        TAG,
                        "DIAG probe focus disp=$disp editable=${f.isEditable} " +
                            "cls=${f.className} pkg=${f.packageName}",
                    )
                    if (f.isEditable == true && f.packageName?.toString() == pkg) return f
                }
                // 2. The focused node wasn't an editable match (Play Store's search bar
                //    focuses a non-editable wrapper). Search the window's tree for the
                //    real editable field. Log the first one's class so we can confirm.
                val editable = firstEditableInTree(root, pkg, 0)
                if (editable != null) {
                    Log.i(TAG, "DIAG probe tree-editable disp=$disp cls=${editable.className}")
                    return editable
                }
                Log.i(TAG, "DIAG probe no editable in window disp=$disp")
            }
        }
        return null
    }

    /** Depth-first search for the first editable node in [pkg] under [root]. Catches
     *  fields whose window holds an EditText that isn't the input-focused node. Bounded
     *  depth so a pathological tree can't spin. */
    private fun firstEditableInTree(
        root: android.view.accessibility.AccessibilityNodeInfo?,
        pkg: String,
        depth: Int,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        root ?: return null
        if (depth > 40) return null
        if (root.isEditable == true && root.packageName?.toString() == pkg) return root
        val n = runCatching { root.childCount }.getOrDefault(0)
        for (i in 0 until n) {
            val c = runCatching { root.getChild(i) }.getOrNull() ?: continue
            val found = firstEditableInTree(c, pkg, depth + 1)
            if (found != null) return found
        }
        return null
    }

    /**
     * Set the tapped field's text directly via accessibility (used by the relay for
     * DELETES). No DEL key is sent, so the external display doesn't get a key event,
     * focus doesn't bounce, and the phone keyboard's IME cursor cache stays in sync —
     * which is what lets backspace work repeatedly on a field that already had text.
     * Returns false if there's no live field (relay falls back to forwarding DELs).
     */
    fun setFieldText(text: String): Boolean {
        val n = targetNode ?: return false
        return try {
            // refresh() re-reads the node's current state; false/throw means it's
            // stale (window changed) → let the caller fall back.
            if (!n.refresh()) return false
            val args = android.os.Bundle().apply {
                putCharSequence(
                    android.view.accessibility.AccessibilityNodeInfo
                        .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
            n.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                args,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "setFieldText failed", t)
            false
        }
    }

    /**
     * Move the tapped field's caret/selection via accessibility (used by the custom
     * keyboard to mirror the on-screen caret onto the glasses field). start==end is a
     * plain caret position. Returns false if there's no live field.
     */
    fun setFieldSelection(start: Int, end: Int): Boolean {
        val n = targetNode ?: return false
        return try {
            if (!n.refresh()) return false
            val args = android.os.Bundle().apply {
                putInt(
                    android.view.accessibility.AccessibilityNodeInfo
                        .ACTION_ARGUMENT_SELECTION_START_INT,
                    start,
                )
                putInt(
                    android.view.accessibility.AccessibilityNodeInfo
                        .ACTION_ARGUMENT_SELECTION_END_INT,
                    end,
                )
            }
            n.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION,
                args,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "setFieldSelection failed", t)
            false
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "PortalPad accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        targetNode?.let { runCatching { it.recycle() } }
        targetNode = null
        return super.onUnbind(intent)
    }

    /**
     * Inject a tap on [displayId] at ([x],[y]) through the accessibility
     * framework. Unlike Shizuku injection (which the system gates on display
     * trust), an a11y-dispatched gesture is system-sourced, so foreign apps honor
     * it on a NON-trusted display — the fallback for devices where the trusted VD
     * can't be created. Returns whether the gesture was accepted for dispatch
     * (not whether it landed; the app's reaction is observed by the user).
     */
    fun dispatchTap(displayId: Int, x: Float, y: Float): Boolean {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        return dispatchPath(displayId, path, 1L, "tap")
    }

    /** Long-press (right-click / select) via the a11y framework. */
    fun dispatchLongPress(displayId: Int, x: Float, y: Float, durationMs: Long): Boolean {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        return dispatchPath(displayId, path, durationMs.coerceAtLeast(1L), "long-press")
    }

    /** A straight-line swipe — used for scroll (fast) and drag (slow). */
    fun dispatchSwipe(
        displayId: Int, sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long, label: String = "swipe",
    ): Boolean {
        val path = android.graphics.Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        return dispatchPath(displayId, path, durationMs.coerceAtLeast(1L), label)
    }

    /** Two-finger pinch/zoom centered at (cx,cy): both fingers animate from
     *  [startSpan] to [endSpan] apart. a11y supports multi-stroke gestures. */
    fun dispatchPinch(
        displayId: Int, cx: Float, cy: Float, startSpan: Float, endSpan: Float, durationMs: Long,
    ): Boolean {
        return try {
            val sh = startSpan / 2f
            val eh = endSpan / 2f
            val left = android.graphics.Path().apply { moveTo(cx - sh, cy); lineTo(cx - eh, cy) }
            val right = android.graphics.Path().apply { moveTo(cx + sh, cy); lineTo(cx + eh, cy) }
            val dur = durationMs.coerceAtLeast(1L)
            val builder = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(left, 0L, dur))
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(right, 0L, dur))
            if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { builder.setDisplayId(displayId) }
            val dispatched = dispatchGesture(builder.build(), gestureCallback("pinch", displayId), null)
            Log.d(TAG, "a11y dispatch pinch disp=$displayId span $startSpan→$endSpan dispatched=$dispatched")
            dispatched
        } catch (t: Throwable) {
            Log.e(TAG, "a11y dispatch pinch failed disp=$displayId", t); false
        }
    }

    private fun dispatchPath(
        displayId: Int, path: android.graphics.Path, durationMs: Long, label: String,
    ): Boolean {
        return try {
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, durationMs)
            val builder = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke)
            // setDisplayId targets the external display (API 30+, our minSdk).
            if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { builder.setDisplayId(displayId) }
            val dispatched = dispatchGesture(builder.build(), gestureCallback(label, displayId), null)
            Log.d(TAG, "a11y dispatch $label disp=$displayId dur=${durationMs}ms dispatched=$dispatched")
            dispatched
        } catch (t: Throwable) {
            Log.e(TAG, "a11y dispatch $label failed disp=$displayId", t); false
        }
    }

    private fun gestureCallback(label: String, displayId: Int) = object : GestureResultCallback() {
        override fun onCompleted(d: android.accessibilityservice.GestureDescription?) {
            Log.d(TAG, "a11y $label COMPLETED disp=$displayId")
        }
        override fun onCancelled(d: android.accessibilityservice.GestureDescription?) {
            Log.w(TAG, "a11y $label CANCELLED disp=$displayId")
        }
    }

    // ─── Coalesced, completion-paced scroll ──────────────────────────────────
    // a11y runs one gesture at a time, so firing a swipe per scroll tick makes
    // each new dispatch cancel the one still in flight — nothing ever scrolls.
    // Instead we bank incoming scroll vectors and dispatch the next swipe only
    // once the current one finishes, so exactly one scroll gesture is ever live.
    private val scrollLock = Any()
    private var scrollVecX = 0f
    private var scrollVecY = 0f
    private var scrollAnchorX = 0f
    private var scrollAnchorY = 0f
    private var scrollDispId = 0
    private var scrollDispW = 0
    private var scrollDispH = 0
    private var scrollInFlight = false

    /** Feed one scroll tick's swipe vector (already scaled + inverted by the
     *  caller). Accumulates and paces so only one swipe runs at a time. */
    fun scrollGesture(
        displayId: Int, anchorX: Float, anchorY: Float, vx: Float, vy: Float, dispW: Int, dispH: Int,
    ) {
        synchronized(scrollLock) {
            scrollVecX += vx
            scrollVecY += vy
            scrollAnchorX = anchorX
            scrollAnchorY = anchorY
            scrollDispId = displayId
            scrollDispW = dispW
            scrollDispH = dispH
            if (!scrollInFlight) dispatchScrollLocked()
        }
    }

    private fun dispatchScrollLocked() {
        val vx = scrollVecX
        val vy = scrollVecY
        // Nothing banked up → the chain ends until the next tick arrives.
        if (kotlin.math.abs(vx) < 4f && kotlin.math.abs(vy) < 4f) {
            scrollVecX = 0f; scrollVecY = 0f; scrollInFlight = false
            return
        }
        scrollVecX = 0f; scrollVecY = 0f
        scrollInFlight = true
        val sx = scrollAnchorX
        val sy = scrollAnchorY
        val ex = (sx + vx).coerceIn(4f, (scrollDispW - 4).coerceAtLeast(8).toFloat())
        val ey = (sy + vy).coerceIn(4f, (scrollDispH - 4).coerceAtLeast(8).toFloat())
        val dist = kotlin.math.hypot((ex - sx).toDouble(), (ey - sy).toDouble()).toFloat()
        // ~1ms/px, floored so it reads as a controlled scroll rather than a flick.
        val dur = dist.toLong().coerceIn(120L, 350L)
        try {
            val path = android.graphics.Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, dur)
            val builder = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke)
            if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { builder.setDisplayId(scrollDispId) }
            val cb = object : GestureResultCallback() {
                override fun onCompleted(d: android.accessibilityservice.GestureDescription?) {
                    synchronized(scrollLock) { dispatchScrollLocked() }
                }
                override fun onCancelled(d: android.accessibilityservice.GestureDescription?) {
                    synchronized(scrollLock) { dispatchScrollLocked() }
                }
            }
            val ok = dispatchGesture(builder.build(), cb, null)
            Log.d(TAG, "a11y scroll swipe disp=$scrollDispId ($sx,$sy)->($ex,$ey) ${dur}ms ok=$ok")
            if (!ok) scrollInFlight = false
        } catch (t: Throwable) {
            Log.e(TAG, "a11y scroll dispatch failed", t)
            scrollInFlight = false
        }
    }

    companion object {
        private const val TAG = "PortalPadA11y"
        // Mirror the poller's tap window: a field tap counts as "recent" for 2s.
        private const val TAP_WINDOW_MS = 2000L
        // Browsers keep an always-present, self-focusing URL bar (Chrome's omnibox is
        // an EditText that's in the a11y tree even when unfocused, and Chrome focuses
        // it on new-tab). That trips both the editable-focus path and the probe's
        // tree-walk on tab/link/navigation, popping the relay unprompted. Field
        // auto-open is fundamentally unreliable in browsers, so we suppress it there;
        // the manual keyboard button still works for typing a URL or web field.
        private val BROWSER_PKGS = setOf(
            "com.android.chrome",
            "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "com.sec.android.app.sbrowser",   // Samsung Internet (S24 default browser)
            "org.mozilla.firefox", "org.mozilla.fenix",
            "com.brave.browser",
            "com.microsoft.emmx",             // Edge
            "com.opera.browser", "com.opera.mini.native",
            "com.duckduckgo.mobile.android",
            "com.kiwibrowser.browser",
            "com.vivaldi.browser",
        )
        // Live service instance so the relay can call setFieldText for SET_TEXT
        // deletes. Null when the service isn't connected → relay falls back to DELs.
        @Volatile var instance: PortalPadAccessibilityService? = null
            private set
    }
}
