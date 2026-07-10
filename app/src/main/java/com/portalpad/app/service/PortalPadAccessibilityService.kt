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
    @Volatile private var lastPermRecoverAt = 0L
    // Generation counter for the launch-armed permission-dialog poll: each new
    // freeform launch re-arms with a fresh generation; older polls see the bump
    // and stop, so at most one watch runs.
    @Volatile private var permWatchGen = 0

    /** DEPRECATED / no-op. Permission-dialog handling now lives entirely in
     *  FreeformManager's shell watch, which shows PortalPad's Allow/Deny consent
     *  popup on the phone and pm-grants via Shizuku/root — no window is moved.
     *  This a11y-side path (which used to move the task to the phone) is kept as
     *  a no-op to avoid double-handling; the move primitive it relied on was
     *  proven unreliable on Samsung One UI across six logs. */
    private fun recoverDialogToPhone(pkg: String, extId: Int, via: String) {
        Log.d(TAG, "a11y perm-dialog seen (pkg=$pkg via=$via) — handled by FreeformManager consent popup")
    }

    /** Launch-armed permission-dialog watch. kindle_black3 showed the a11y event
     *  stream can be completely DEAD around a freeform launch (last event 6s
     *  before Kindle even started; zero events while GrantPermissionsActivity
     *  froze the display) — so the event watcher above never runs. This polls
     *  [windowsOnAllDisplays] directly for ~8s after each freeform launch; it
     *  needs no events and also retries past the event-before-window-registered
     *  race. Poll set is the permission controllers ONLY (not "android"): the
     *  event path reacts to an "android" window APPEARING, but a poll tests
     *  PRESENCE, and a long-lived system window with root pkg "android" on the
     *  VD would otherwise yank a task to the phone on every launch. */
    fun armPermissionDialogWatch(displayId: Int) {
        val gen = ++permWatchGen
        Log.d(TAG, "perm-dialog watch ARMED disp=$displayId gen=$gen")
        fun tick(n: Int) {
            if (gen != permWatchGen || n >= PERM_WATCH_TICKS) return
            val hit = runCatching {
                windowsOnAllDisplays.get(displayId)
                    ?.mapNotNull { it.root?.packageName?.toString() }
                    ?.firstOrNull { it in POLL_DIALOG_PKGS }
            }.getOrNull()
            if (hit != null) {
                Log.i(TAG, "perm-dialog watch HIT pkg=$hit disp=$displayId tick=$n")
                recoverDialogToPhone(hit, displayId, via = "poll")
                return
            }
            mainHandler.postDelayed({ tick(n + 1) }, PERM_WATCH_INTERVAL_MS)
        }
        mainHandler.post { tick(0) }
    }
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
            // ── Permission-dialog-on-external recovery ──────────────────────
            // A runtime permission prompt (or similar system dialog) launched
            // from a freeform app INHERITS the app's display — so it lands on
            // the external display as a TRANSPARENT activity and freezes it
            // black (no cursor: the transparent dialog holds display focus with
            // nothing drawn behind). Android binds these to the launching task's
            // display and won't let us redirect them, but we CAN automate the
            // manual fix the user found: move the app's task to the PHONE, where
            // the dialog becomes visible and tappable. Re-launching to the glass
            // then succeeds (no pending dialog). Guarded so it fires once per
            // appearance and only while an external display is active.
            if (wp != null && wp in SYSTEM_DIALOG_PKGS) {
                runCatching {
                    val app = applicationContext as? PortalPadApp
                    val extId = app?.externalDisplayId?.value
                    if (extId != null) {
                        val onExternal = windowsOnAllDisplays.get(extId)
                            ?.any { it.root?.packageName == wp } == true
                        // Unconditional instrumentation: kindle_black3 proved the
                        // match branch can stay silent for THREE different reasons
                        // (no event / windows-check false / guard). This line makes
                        // the next capture decisive on its own.
                        Log.i(TAG, "perm-dialog event pkg=$wp ext=$extId onExternal=$onExternal")
                        if (onExternal) recoverDialogToPhone(wp, extId, via = "event")
                    }
                }
            }
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
    /**
     * The field the relay should type into RIGHT NOW. The tracked [targetNode]
     * is only set when a field TAP is detected — apps whose search fields
     * don't emit those events (Compose/custom fields) leave it pointing at the
     * LAST detected app, and in freeform multi-window that made SET_TEXT type
     * into a background app's field (log-proven 2026-07-09: target pinned to
     * Prime Video across four other apps; Prime's dropdown showed the user's
     * queries). Rule: trust the tracked node only while it belongs to the app
     * holding WINDOW FOCUS on the external display (the VD runs OWN_FOCUS, so
     * the focused window is the one the user last tapped into); on mismatch,
     * adopt the focused window's own input-focused editable field. When
     * packages match — or focus is indeterminate — behavior is identical to
     * the old raw targetNode, so only the already-broken case changes.
     */
    private fun liveTargetField(): android.view.accessibility.AccessibilityNodeInfo? {
        val tracked = targetNode?.takeIf { runCatching { it.refresh() }.getOrDefault(false) }
        val extId = (applicationContext as? PortalPadApp)?.externalDisplayId?.value
            ?: return tracked
        val focusedRoot = runCatching {
            windowsOnAllDisplays.get(extId)?.firstOrNull { it.isFocused }?.root
        }.getOrNull() ?: return tracked
        val focusedPkg = focusedRoot.packageName?.toString() ?: return tracked
        if (tracked != null && tracked.packageName?.toString() == focusedPkg) return tracked
        val f = focusedRoot.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { runCatching { it.isEditable }.getOrDefault(false) }
        if (f != null) {
            Log.w(
                TAG,
                "RETARGET typing field: tracked=${tracked?.packageName} → focused=$focusedPkg (multi-window stale target)",
            )
            targetNode?.let { old -> if (old !== f) runCatching { old.recycle() } }
            targetNode = f
            return f
        }
        return tracked
    }

    fun setFieldText(text: String): Boolean {
        val n = liveTargetField() ?: return false
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
        val n = liveTargetField() ?: return false
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
    // ── Suggestion mirror ──────────────────────────────────────────────
    // Reads the omnibox/type-ahead suggestion list of the app on the given
    // display straight from the accessibility node tree, so the relay can
    // show the suggestions as its OWN tappable rows and click the chosen one
    // via ACTION_CLICK on the node. Node clicks bypass everything that made
    // the on-display dropdown unusable from the phone: no focus events, no
    // key injection, no dependence on the dropdown's visual selection state
    // (Chrome's fill-in/rebuild loop clears DPAD selections instantly).
    // FRAGILITY, by design: the view ids below are Chrome's and can shift
    // across versions — every layer degrades to "no suggestions readable"
    // (relay simply shows nothing) rather than misbehaving.

    /** One mirrored omnibox suggestion. [title] is line_1 (the query or page
     *  title); [url] is line_2 when it plausibly holds a NAVIGABLE address
     *  (history/bookmark/typed-URL rows) — null for plain search suggestions,
     *  whose line_2 is absent or descriptive text. */
    data class MirrorSuggestion(val title: String, val url: String?)

    /** Poster-tile a11y descriptions comma-join METADATA after the title —
     *  "New Girl,on Fox Television Classics,Comedy Series" (field report
     *  2026-07-09; tapping submitted the whole string). Discriminator: those
     *  metadata commas have NO space after them, while commas inside real
     *  titles ("New York, New York") conventionally do — so cut at the first
     *  space-less comma. Applied to the GENERIC harvest only; Chrome's
     *  suggestions can legitimately contain commas and stay raw. */
    private fun cleanGenericTitle(raw: String): String {
        val cut = raw.split(COMMA_NO_SPACE).firstOrNull()?.trim() ?: raw
        return cut.ifBlank { raw }
    }
    private val COMMA_NO_SPACE = Regex(",(?=\\S)")

    /** Package of the window that served the most recent non-empty
     *  [readSuggestions] result — lets the relay keep BROWSER suggestions in
     *  their native (engine-ranked) order while ABC-ranking everything else. */
    @Volatile var lastSuggestionSourcePkg: String? = null
        private set

    fun readSuggestions(displayId: Int, limit: Int = 8, query: String = ""): List<MirrorSuggestion> {
        lastSuggestionSourcePkg = null
        return runCatching {
            // The FOCUSED window's package: the generic harvest below is
            // restricted to it, so a background app's still-populated dropdown
            // (Prime's, in the field report) can never masquerade as the
            // typed-into app's suggestions. Null (no focused window) keeps the
            // old any-root behavior.
            val focusedPkg = runCatching {
                windowsOnAllDisplays.get(displayId)
                    ?.firstOrNull { it.isFocused }?.root?.packageName?.toString()
            }.getOrNull()
            for (root in rootsOnDisplay(displayId)) {
                val rows = suggestionRows(root, query, focusedPkg)
                if (rows.isNotEmpty()) {
                    val srcPkg = root.packageName?.toString()
                    lastSuggestionSourcePkg = srcPkg
                    val isChrome = srcPkg == "com.android.chrome"
                    return@runCatching rows.mapNotNull { row ->
                        rowText(row)?.takeIf { it.isNotBlank() }
                            ?.let { raw ->
                                val title = if (isChrome) raw else cleanGenericTitle(raw)
                                MirrorSuggestion(title, rowUrl(row))
                            }
                    }
                        .distinctBy { it.title }
                        .take(limit)
                }
            }
            emptyList()
        }.getOrDefault(emptyList())
    }

    /** line_2 of a suggestion row, kept only when it plausibly holds a
     *  navigable address. Chrome ELIDES long URLs with a literal ellipsis in
     *  the displayed TEXT — submitting an elided URL navigates somewhere
     *  wrong — so anything elided (or space-containing descriptive text)
     *  returns null and the pick falls back to searching the title. */
    private fun rowUrl(row: android.view.accessibility.AccessibilityNodeInfo): String? =
        runCatching {
            row.findAccessibilityNodeInfosByViewId("com.android.chrome:id/line_2")
                .firstOrNull()?.text?.toString()?.trim()
                ?.takeIf { looksNavigable(it) }
        }.getOrNull()

    private fun looksNavigable(s: String): Boolean =
        s.isNotBlank() && ' ' !in s && '\u2026' !in s && !s.contains("...") && '.' in s

    /** Click suggestion by TEXT (re-queried live at click time — never a stale
     *  node), index as tiebreak for duplicate texts. */
    fun clickSuggestion(displayId: Int, expectedText: String, index: Int): Boolean {
        return runCatching {
            for (root in rootsOnDisplay(displayId)) {
                // Pass the picked title as the query: grid containers require a
                // non-empty query to pass the gates, and the title is by
                // definition present in its own row — so the row that was shown
                // can always be re-found here for a direct click.
                val rows = suggestionRows(root, expectedText)
                if (rows.isEmpty()) continue
                // A generic chip's title was CLEANED (metadata cut at the first
                // space-less comma) — match either form against the live rows.
                val byText = rows.filter {
                    val t = rowText(it)
                    t == expectedText || (t != null && cleanGenericTitle(t) == expectedText)
                }
                val target = byText.getOrNull(0)
                    ?: rows.getOrNull(index)
                    ?: continue
                val clicked = target.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                )
                Log.d(TAG, "suggestion click '$expectedText' idx=$index clicked=$clicked disp=$displayId")
                return@runCatching clicked
            }
            false
        }.getOrDefault(false)
    }

    @Volatile private var lastMirrorLogAt = 0L

    private fun rootsOnDisplay(displayId: Int): List<android.view.accessibility.AccessibilityNodeInfo> {
        // windowsOnAllDisplays, NOT `windows`: the plain `windows` property
        // returns the DEFAULT display's windows only, so filtering it by a
        // virtual/external display id is empty on every device (measured:
        // mirror showed nothing anywhere). API 30+ (our minSdk).
        val wins = runCatching { windowsOnAllDisplays.get(displayId) }.getOrNull() ?: emptyList()
        // FOCUSED window first: in freeform multi-resume several windows can
        // each hold a focused field, and readSuggestions takes the first root
        // that yields rows — without this ordering a background app's open
        // dropdown could win over the app actually being typed into.
        val roots = wins.sortedByDescending { it.isFocused }.mapNotNull { it.root }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastMirrorLogAt > 1500) {
            lastMirrorLogAt = now
            Log.d(TAG, "mirror: disp=$displayId windows=${wins.size} roots=${roots.size}")
        }
        return roots
    }

    private fun suggestionRows(
        root: android.view.accessibility.AccessibilityNodeInfo,
        query: String = "",
        focusedPkg: String? = null,
    ): List<android.view.accessibility.AccessibilityNodeInfo> {
        // Per-app dispatch. Chrome keeps its precise view-id path (below,
        // untouched); every other app goes through the GENERIC heuristic —
        // a list container anchored just under the field being typed into.
        // Future per-app specializations slot in here exactly like Chrome's.
        return when (root.packageName?.toString()) {
            "com.android.chrome" -> chromeSuggestionRows(root)
            else -> genericSuggestionRows(root, query, focusedPkg)
        }
    }

    private fun chromeSuggestionRows(
        root: android.view.accessibility.AccessibilityNodeInfo,
    ): List<android.view.accessibility.AccessibilityNodeInfo> {
        val rows = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        // Layer 1: Chrome's dropdown container → its clickable children.
        root.findAccessibilityNodeInfosByViewId(
            "com.android.chrome:id/omnibox_suggestions_dropdown",
        ).firstOrNull()?.let { dropdown ->
            for (i in 0 until dropdown.childCount) {
                dropdown.getChild(i)?.let { child ->
                    (if (child.isClickable) child else clickableDescendant(child))
                        ?.let(rows::add)
                }
            }
        }
        // Layer 2: suggestion line texts → their clickable ancestors.
        if (rows.isEmpty()) {
            root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/line_1")
                .forEach { l -> clickableAncestor(l)?.let(rows::add) }
        }
        return rows
    }

    /**
     * GENERIC suggestion harvest for any app (reference target: Play Store).
     * No view ids to lean on, so it anchors on structure. Tuned against the
     * 2026-07-09 field report (Disney+ worked; Hulu/Paramount+/Peacock showed
     * category CHIP ROWS as "suggestions"; Play Store showed nothing):
     *  - only the window of the app actually being typed into is inspected;
     *  - list detection covers collectionInfo (Compose lists), the classic
     *    list classNames, and scrollable containers;
     *  - the container must be anchored below the typed field (within ~800px
     *    — search screens often put a chip row between field and results);
     *  - GEOMETRY GATES kill chip/tab rows: rows must stack VERTICALLY
     *    (≥2 distinct row tops) and be WIDE (≥60% of container width) —
     *    "All shows"/"For you"/"Replay" style horizontal chips fail both;
     *  - RELEVANCE GATE (query length ≥2): at least one row's text must
     *    contain the typed query, else the list is content/navigation, not
     *    suggestions. Skipped at 0-1 chars and at click time (query="") so a
     *    previously shown row can always be re-found for clicking.
     * Everything degrades to an EMPTY list — the relay shows nothing rather
     * than garbage. Apps whose suggestion UIs expose nothing to accessibility
     * stay blank; that's the ceiling of this approach.
     */
    // ── DIAG-SUGGEST (temporary calibration instrumentation, 2026-07-09) ──
    // Dumps every candidate container the generic harvest considers — class,
    // bounds, rows (text/clickable/bounds) — and the exact gate verdict, so
    // the geometry/relevance gates can be tuned from ground truth per app
    // instead of blind guesses (round 1 showed chip rows, round 2 killed
    // Disney+). One multi-line log entry per scan, throttled per package.
    // STRIP once calibrated.
    private val lastSuggestDiagAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun genericSuggestionRows(
        root: android.view.accessibility.AccessibilityNodeInfo,
        query: String = "",
        focusedPkg: String? = null,
    ): List<android.view.accessibility.AccessibilityNodeInfo> {
        val pkg = root.packageName?.toString() ?: return emptyList()
        if (pkg == "com.portalpad.app") return emptyList()
        // FOCUSED-WINDOW restriction: when the display has a focused window,
        // only ITS app may serve generic suggestions — a background app's
        // still-open dropdown is never the answer to the current typing.
        if (focusedPkg != null && pkg != focusedPkg) return emptyList()
        val now = android.os.SystemClock.uptimeMillis()
        val diagOn = now - (lastSuggestDiagAt[pkg] ?: 0L) > 2000
        if (diagOn) lastSuggestDiagAt[pkg] = now
        val diag = if (diagOn) StringBuilder("DIAG-SUGGEST pkg=$pkg q='$query'\n") else null
        // Anchor on THIS root's own input-focused field FIRST. The tracked
        // targetNode can be STALE in multi-window: background windows keep
        // firing focus events and re-steal it (log-proven 2026-07-09: it sat
        // pinned to Prime Video for minutes while four other apps were typed
        // into — every scan died at the package check below before any
        // harvesting). The tracked node is only a fallback, and only when it
        // belongs to this root's own app.
        val field = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            ?: targetNode?.takeIf {
                runCatching { it.refresh() }.getOrDefault(false) &&
                    it.packageName?.toString() == pkg
            }
        if (field == null) {
            diag?.append("  NO FIELD (no input focus in this root; tracked node absent/stale/other-app)")
                ?.let { Log.w(TAG, it.toString()) }
            return emptyList()
        }
        val fieldPkg = field.packageName?.toString()
        if (fieldPkg != null && fieldPkg != pkg) {
            diag?.append("  FIELD PKG MISMATCH field=$fieldPkg root=$pkg")
                ?.let { Log.w(TAG, it.toString()) }
            return emptyList()
        }
        val fb = android.graphics.Rect().also { field.getBoundsInScreen(it) }
        diag?.append("  field=$fb\n")

        var candIdx = 0
        // BEST-container selection, RELEVANCE-weighted: among containers that
        // pass the gates, prefer the one with the most rows MATCHING the
        // query, then the most rows overall. Weighting matters because a tall
        // Live TV rail now legitimately passes the gates (it IS tiled
        // content) — at q='b' Paramount's camera rail matches 0 rows while
        // the results rail matches 6, so results win even before the 2-char
        // relevance gate arms. Section wrappers lose on both counts.
        var best: List<android.view.accessibility.AccessibilityNodeInfo>? = null
        var bestScore = -1 to -1
        fun matchCount(rows: List<android.view.accessibility.AccessibilityNodeInfo>): Int {
            if (query.isEmpty()) return 0
            val q = query.lowercase()
            return rows.count { rowText(it)?.lowercase()?.contains(q) == true }
        }
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < 1500) {
            val n = queue.removeFirst(); visited++
            val cls = n.className?.toString() ?: ""
            val isList = n.collectionInfo != null || n.isScrollable ||
                cls.contains("RecyclerView") || cls.contains("ListView") || cls.contains("GridView")
            if (isList) {
                val cb = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                val anchored = cb.top >= fb.top && cb.top <= fb.bottom + 800
                if (diag != null && candIdx < 8) {
                    candIdx++
                    diag.append("  cand#$candIdx cls=${cls.substringAfterLast('.')} b=$cb kids=${n.childCount} anchored=$anchored\n")
                }
                if (anchored && cb.width() > 0) {
                    val rows = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { child ->
                            // Clickable row preferred; poster tiles that expose
                            // text/description but NO clickable node (Hulu's
                            // grid: 20 kids, 0 clickable — DIAG-proven) are
                            // accepted as-is. A failed ACTION_CLICK at pick
                            // time falls back to SET_TEXT + Enter anyway.
                            val row = if (child.isClickable) child
                            else clickableDescendant(child) ?: child
                            if (!rowText(row).isNullOrBlank()) rows.add(row)
                        }
                    }
                    if (diag != null && candIdx <= 8) {
                        rows.take(8).forEachIndexed { i, r ->
                            val rb = android.graphics.Rect().also { r.getBoundsInScreen(it) }
                            diag.append("    row$i '${rowText(r)?.take(40)}' b=$rb\n")
                        }
                    }
                    if (rows.isNotEmpty()) {
                        val verdict = dropdownGateVerdict(rows, cb, query)
                        val score = matchCount(rows) to rows.size
                        diag?.append("    verdict=${verdict ?: "ACCEPT (match=${score.first} rows=${score.second})"}\n")
                        if (verdict == null &&
                            (score.first > bestScore.first ||
                                (score.first == bestScore.first && score.second > bestScore.second))
                        ) {
                            best = rows
                            bestScore = score
                        }
                    } else {
                        diag?.append("    verdict=REJECT rows<2 (${rows.size})\n")
                    }
                    // Whatever the verdict, keep scanning: a richer container
                    // (the real results grid) may be deeper in the tree.
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        best?.let {
            diag?.append("  result=BEST (${it.size} rows) visited=$visited")
                ?.let { _ -> Log.w(TAG, diag.toString().trimEnd()) }
            return it.take(10)
        }
        diag?.append("  result=EMPTY visited=$visited")?.let { Log.w(TAG, it.toString()) }
        return emptyList()
    }

    /** Geometry + relevance gates separating a real suggestion DROPDOWN (or a
     *  results GRID / tile column — Hulu and Paramount+ render in-page poster
     *  tiles) from chip rows / nav tabs / content lists. Returns null when the
     *  container PASSES, else a human-readable rejection reason (DIAG). */
    private fun dropdownGateVerdict(
        rows: List<android.view.accessibility.AccessibilityNodeInfo>,
        container: android.graphics.Rect,
        query: String,
    ): String? {
        val bounds = rows.map { r -> android.graphics.Rect().also { r.getBoundsInScreen(it) } }
        // SINGLE ROW: geometry can't classify one row, so admit it only on a
        // relevant 2+ char query — a real search legitimately returns one
        // result (Paramount field case: one tile → chips went dark).
        if (rows.size == 1) {
            val q = query.lowercase()
            return if (query.length >= 2 &&
                rowText(rows[0])?.lowercase()?.contains(q) == true
            ) null else "REJECT single row w/o relevant 2+ char query"
        }
        // VERTICAL/TILED classification — tallness is computed FIRST: TALL
        // rows (≥96px = poster/image tiles) are tiled CONTENT in ANY
        // arrangement, including a single horizontal RAIL, which is how
        // Paramount+ renders its actual RESULTS at freeform widths (DIAG
        // 2026-07-09: 6 result tiles at one top edge, h=135, killed by the
        // old horizontal-first gate while the useless 2-row section wrapper
        // won and put 'Camera 1' in the chips). Short horizontal rows remain
        // chip strips / nav tabs and die below.
        val distinctTops = bounds.map { it.top }.toSortedSet().let { tops ->
            tops.zipWithNext().count { (a, b) -> b - a > 8 } + 1
        }
        val distinctLefts = bounds.map { it.left }.toSortedSet().let { ls ->
            ls.zipWithNext().count { (a, b) -> b - a > 8 } + 1
        }
        val medianHeight = bounds.map { it.height() }.sorted()[bounds.size / 2]
        val isTiled = medianHeight >= 96 || (distinctLefts >= 2 && distinctTops >= 2)
        if (isTiled) {
            if (query.isEmpty()) return "REJECT tiled w/o query (browse/trending, not results)"
        } else {
            if (distinctTops < 2) {
                return "REJECT horizontal (distinctTops=$distinctTops medianH=$medianHeight)"
            }
            // WIDE (single-column stacks of SHORT rows only): suggestion rows
            // span (most of) the container; a narrow vertical nav menu doesn't.
            val wide = bounds.count { it.width() >= container.width() * 0.6f }
            if (wide < 2) {
                return "REJECT narrow (wide=$wide widths=${bounds.map { it.width() }} container=${container.width()})"
            }
        }
        // RELEVANT (only meaningful from 2 typed chars): real suggestions echo
        // the query. Skipped when query is short/empty (first keystroke, and
        // click-time re-query, must not be gated away).
        if (query.length >= 2) {
            val q = query.lowercase()
            if (rows.none { rowText(it)?.lowercase()?.contains(q) == true }) {
                return "REJECT irrelevant (q='$q' not in any row)"
            }
        }
        return null
    }

    private fun clickableAncestor(
        node: android.view.accessibility.AccessibilityNodeInfo,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        var n: android.view.accessibility.AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable) return n
            n = n.parent
            hops++
        }
        return null
    }

    private fun clickableDescendant(
        node: android.view.accessibility.AccessibilityNodeInfo,
        depth: Int = 0,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (depth > 4) return null
        if (node.isClickable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { c ->
                clickableDescendant(c, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** First non-blank text among the row and its descendants. */
    private fun rowText(
        node: android.view.accessibility.AccessibilityNodeInfo,
        depth: Int = 0,
    ): String? {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        // Poster/tile UIs (Hulu's results grid, DIAG-proven) expose the title
        // via contentDescription with no text node anywhere in the subtree.
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (depth > 4) return null
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { c -> rowText(c, depth + 1)?.let { return it } }
        }
        return null
    }

    fun dispatchTap(displayId: Int, x: Float, y: Float): Boolean {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        // 60ms, not 1ms: a one-millisecond synthetic touch lives inside a
        // fraction of one display frame, and whether the target app's input
        // pipeline SAMPLES it is frame-phase roulette — the leading suspect
        // for the Sony reporter's "input ready" effect (dispatches completed
        // 203/203 while registrations lagged badly). Real fingers touch for
        // 50–100ms; 60ms matches the double-tap's strokes and stays snappy.
        return dispatchPath(displayId, path, 60L, "tap")
    }

    /** Double-tap as ONE two-stroke gesture (strokes offset in time) so both
     *  taps land atomically. Two sequential dispatchTap calls risk the second
     *  gesture cancelling the first mid-flight — the classic "double tap read
     *  as single tap" failure on a11y-input devices. */
    fun dispatchDoubleTap(displayId: Int, x: Float, y: Float): Boolean {
        return try {
            val p1 = android.graphics.Path().apply { moveTo(x, y) }
            val p2 = android.graphics.Path().apply { moveTo(x, y) }
            val builder = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(p1, 0L, 60L))
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(p2, 160L, 60L))
            if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { builder.setDisplayId(displayId) }
            val dispatched = dispatchGesture(builder.build(), gestureCallback("double-tap", displayId), null)
            Log.d(TAG, "a11y dispatch double-tap disp=$displayId (${x.toInt()},${y.toInt()}) dispatched=$dispatched")
            dispatched
        } catch (t: Throwable) {
            Log.e(TAG, "a11y dispatch double-tap failed disp=$displayId", t); false
        }
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
            // Coordinates logged so captures can correlate WHERE a tap landed
            // with whether anything registered (a11y click events only fire
            // for some targets; position is the missing variable).
            val b = android.graphics.RectF().also { path.computeBounds(it, true) }
            Log.d(
                TAG,
                "a11y dispatch $label disp=$displayId dur=${durationMs}ms " +
                    "at=(${b.left.toInt()},${b.top.toInt()}) dispatched=$dispatched",
            )
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

        /** System UI packages whose dialogs must be usable on the PHONE, not
         *  the external display (they launch transparent and freeze the glass).
         *  Runtime permission prompts are the common case; the others are the
         *  same class of phone-bound system dialog. */
        private val SYSTEM_DIALOG_PKGS = setOf(
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "android",
        )
        /** Presence-poll subset of [SYSTEM_DIALOG_PKGS] — see
         *  [armPermissionDialogWatch] for why "android" is excluded here. */
        private val POLL_DIALOG_PKGS = setOf(
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
        )
        // ~8s coverage: dialog appeared 1.1s after launch in kindle_black3.
        private const val PERM_WATCH_TICKS = 16
        private const val PERM_WATCH_INTERVAL_MS = 500L
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
        /** Browsers detected from the SYSTEM's own signal: apps registered to
         *  VIEW http(s) links — the same query the default-browser picker uses
         *  — which catches Chromium/Firefox forks and renamed browsers the
         *  curated set can't know (an app that handles web links IS a browser
         *  by its own declaration). Queried once and cached (PackageManager
         *  IPC; browsers don't get installed mid-session). Deliberately NOT
         *  `lazy`: if the first call raced service startup (no context yet) a
         *  lazy would freeze the empty result forever — this retries until a
         *  context exists, then caches. */
        @Volatile private var queriedBrowserPkgs: Set<String>? = null
        private fun queriedBrowsers(): Set<String> {
            queriedBrowserPkgs?.let { return it }
            val ctx = instance?.applicationContext ?: return emptySet()
            val set = runCatching {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("http://example.com"),
                ).addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                ctx.packageManager.queryIntentActivities(
                    intent,
                    android.content.pm.PackageManager.MATCH_ALL,
                ).mapNotNull { it.activityInfo?.packageName }.toSet()
            }.getOrDefault(emptySet())
            queriedBrowserPkgs = set
            return set
        }

        /** True when [pkg] is a known browser — shared with the relay, which
         *  keeps browser suggestions in NATIVE (engine-ranked) order instead
         *  of ABC-ranking them. Curated set first (free), then the system's
         *  own web-link-handler registry for unknown forks. */
        fun isBrowserPkg(pkg: String?): Boolean =
            pkg != null && (pkg in BROWSER_PKGS || pkg in queriedBrowsers())
        // Live service instance so the relay can call setFieldText for SET_TEXT
        // deletes. Null when the service isn't connected → relay falls back to DELs.
        @Volatile var instance: PortalPadAccessibilityService? = null
            private set
    }
}
