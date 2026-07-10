package com.portalpad.app.presentation

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.portalpad.app.data.RunningTask
import com.portalpad.app.data.toGradientDrawableType
import com.portalpad.app.data.WindowBounds
import com.portalpad.app.service.FreeformManager
import com.portalpad.app.service.InputInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * DeX-style window controls for desktop mode (phase 2). Two parts:
 *
 *  1. A top-edge control bar (revealed when the cursor reaches the top of the
 *     display) with: Move, Resize, Tile-left, Tile-right, Maximize, Close —
 *     all acting on the frontmost task.
 *
 *  2. A click-to-grab interaction for free Move / Resize. Because injected
 *     taps don't stream MOVE events to overlay windows the way a physical
 *     finger does, we can't use a normal touch-drag. Instead:
 *        - tap "Move"  -> the window then follows the CURSOR; a translucent
 *          proxy outline tracks it; the next left-click commits the window's
 *          new top-left there (size preserved).
 *        - tap "Resize" -> the proxy's bottom-right corner follows the cursor;
 *          the next left-click commits the new size (top-left preserved).
 *     The cursor position comes from [InputInjector.cursorPosition] and the
 *     commit click from [InputInjector.clickEvents] -- the same injected-cursor
 *     plumbing the rest of the app uses.
 *
 * Only constructed by PortalPadForegroundService when DESKTOP_MODE_ENABLED.
 */
class TopWindowBarOverlay(
    serviceContext: Context,
    private val display: Display,
    private val freeform: FreeformManager,
    private val injector: InputInjector,
    private val isDesktopMode: Boolean,
) {
    private val overlayHost = OverlayHost.forDisplay(display, serviceContext)
    private val displayContext = overlayHost.context
    private val windowManager = overlayHost.windowManager

    private var container: LinearLayout? = null
    private var barParams: WindowManager.LayoutParams? = null
    // Intrinsic min width of the assembled control row (buttons + padding), so the
    // bar window is never set narrower than its controls (which would clip them).
    private var barContentMinW: Int = 0
    private var appIconView: android.widget.ImageView? = null
    private var appTitleView: TextView? = null
    /** The window-control buttons, kept so the cursor-click flow can hit-test
     *  them by on-screen bounds (robust against injected taps not reaching a
     *  TextView inside a top-edge accessibility overlay). */
    private var controlButtons: List<TextView> = emptyList()

    // Proxy outline shown during a Move/Resize grab.
    private var proxy: View? = null
    private var proxyParams: WindowManager.LayoutParams? = null

    private var scope: CoroutineScope? = null
    private var revealJob: Job? = null
    private var autoHideJob: Job? = null
    // Persistent observer (separate from [scope], which is recreated on show/
    // dismiss) that watches the top-bar config and restyles the live bar when the
    // user changes settings in Workspace — so changes preview live on the glasses.
    private var configObserverScope: CoroutineScope? = null
    // Translucent overlay rectangle showing the detection zone while the user is
    // tuning it on the Top Bar customization sub-page. Preview-only.
    private var zoneHighlightView: View? = null
    private var zoneHighlightParams: WindowManager.LayoutParams? = null
    @Volatile private var zonePreviewActive = false
    // After a *timed* auto-hide, suppress re-revealing until the cursor has left
    // the reveal band at least once — otherwise a lingering cursor would
    // instantly re-show the bar and it would never stay hidden.
    @Volatile private var suppressRevealUntilExit = false
    private var cursorJob: Job? = null
    private var clickJob: Job? = null
    private var hoverJob: Job? = null
    // Tier-2 availability greying: a poll (while the bar is shown) dims each
    // control whose action doesn't currently apply. controlAvailability pairs each
    // desktop button with its precondition over the live window state.
    private var availabilityPollJob: Job? = null
    private data class WinState(val count: Int, val hasMaximized: Boolean, val hasSession: Boolean)
    private var lastWinState: WinState? = null
    private var controlAvailability: List<Pair<TextView, (WinState) -> Boolean>> = emptyList()
    private var hoveredButton: View? = null

    // Hover tooltip: a small pill shown below the hovered control button (mirrors
    // the dock's mic/search tooltip, but View-based since this bar isn't Compose).
    private var tooltipView: TextView? = null
    private var tooltipParams: WindowManager.LayoutParams? = null

    // Close-all confirmation card (cursor-clickable, hit-tested like the bar
    // buttons). Shown when ✕ is tapped; "Close all" closes every app on the
    // external display, "Cancel" dismisses.
    private var confirmView: View? = null
    private var confirmParams: WindowManager.LayoutParams? = null
    private var confirmButtons: List<TextView> = emptyList()

    @Volatile private var shown = false
    @Volatile private var displayW = 0
    @Volatile private var displayH = 0
    @Volatile private var density = 1f
    @Volatile private var topBarCfg = com.portalpad.app.data.TopBarConfig()

    // Coalesces top-bar "Bar size" slider ticks into a single rebuild after the
    // drag settles (a size change alters `density`, which is baked into the views,
    // so it needs a full rebuild rather than a live restyle).
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSizeRecreate: Runnable? = null

    private enum class Mode { IDLE, MOVE, RESIZE }
    @Volatile private var mode = Mode.IDLE

    // The task currently being manipulated, and a working copy of its bounds.
    @Volatile private var grabTask: RunningTask? = null
    @Volatile private var workBounds: WindowBounds? = null
    @Volatile private var lastPreview: WindowBounds? = null

    fun show() {
        if (container != null) return
        // Read the user's top-bar config (position / width / duration / reveal
        // zone) up front, same pattern as the dock overlay.
        topBarCfg = runCatching {
            kotlinx.coroutines.runBlocking {
                com.portalpad.app.PortalPadApp.instance.prefs.topBarConfig
                    .first()
            }
        }.getOrDefault(com.portalpad.app.data.TopBarConfig())
        // Pin the bar's sizing to the 360-dpi look regardless of the glasses'
        // actual VD density. At 360 dpi this equals the real density (no change);
        // at other DPIs it keeps the bar from overflowing/overlapping by holding
        // the 360 physical size. 360/160 = 2.25.
        //
        // Then apply the user's uniform size scale on top: because EVERY dimension
        // in this bar (icons, text, padding, and thus the content-driven height) is
        // derived from `density`, multiplying it here scales the whole bar together
        // — taller bar ⇒ proportionally bigger icons. Clamped 60–160% so icons can
        // never become untappable-small or absurdly large.
        val sizeScale = topBarCfg.sizePct.coerceIn(60, 160) / 100f
        density = (360f / 160f) * sizeScale

        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        displayW = metrics.widthPixels
        displayH = metrics.heightPixels

        val bar = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Mirror the dock's gradient (same slate-blue colors + transparency)
            // but in the OPPOSITE direction: the dock fades light→dark top→bottom,
            // so the top bar fades dark→light top→bottom. Rounded on all corners
            // to match the dock's pill shape.
            background = GradientDrawable(
                topBarCfg.style.drawableOrientation(),
                topBarCfg.style.drawableColors(),
            ).apply {
                gradientType = topBarCfg.style.gradientType.toGradientDrawableType()
                if (gradientType != GradientDrawable.LINEAR_GRADIENT) {
                    setGradientCenter(topBarCfg.style.centerXFrac(), topBarCfg.style.centerYFrac())
                    gradientRadius = topBarCfg.style.radiusFrac() * displayW
                }
                // Biased balance → custom stop offsets (API 29+; minSdk is 30).
                topBarCfg.style.drawableOffsets()?.let { setColors(topBarCfg.style.drawableColors(), it) }
                cornerRadius = topBarCfg.style.cornerRadiusDp * density   // rounded on ALL corners
                setStroke((1 * density).toInt(), "#26FFFFFF".toColorInt())
            }
            elevation = 16f * density
            val padH = (14 * density).toInt()
            val padV = (3 * density).toInt()   // shorter bar; icon size unchanged
            setPadding(padH, padV, padH, padV)
            visibility = View.GONE
        }

        // Left slot is intentionally empty for now — reserved for the status
        // bar (clock / battery / wifi / notifications) added in a later build.
        // A flexible spacer pushes the controls to the right edge of the wide bar.
        bar.addView(View(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        // Controls depend on mode. Desktop: window controls (minimize / ❐ /
        // close). Non-desktop (single fullscreen app): Back / Home / Close.
        // Kept as references so the click flow can hit-test them by on-screen
        // bounds (injected taps don't reliably reach a TextView inside a
        // top-edge accessibility overlay on all OEMs).
        // Controls, grouped so a hairline separator can sit between categories:
        //   desktop  → single-window ops | layout-all | session | close
        //   standard → navigation | close
        // Each control's onClick is guarded by its precondition (window count /
        // maximized / saved session) so it never opens empty UI — e.g. the
        // on-phone arrange/maximize/minimize pickers with no windows to act on.
        // controlButtons stays the FLAT list (the click hit-test walks it by
        // on-screen bounds); the bar gets buttons + separators between groups.
        val controlGroups: List<List<TextView>> = if (isDesktopMode) {
            val btnMinimize = controlButton("\u2212", "Minimize window\u2026") {   // − — one window: minimize it; several: pick on phone
                onSingleOrPick(
                    emptyMsg = "No windows to minimize",
                    single = { task ->
                        withContext(Dispatchers.IO) {
                            runCatching { freeform.minimizeByClose(task.taskId, task.packageName, task.bounds) }
                        }
                        maybeShowWallpaperIfEmpty()
                    },
                    multi = { launchMinimizePickerOnPhone() },
                )
            }
            val btnMaximize = controlButton("\u26F6", "Maximize window\u2026") {   // ⛶ — one window: maximize it; several: pick on phone
                onSingleOrPick(
                    emptyMsg = "No windows to maximize",
                    single = { task ->
                        val m = DisplayMetrics().also { @Suppress("DEPRECATION") display.getRealMetrics(it) }
                        withContext(Dispatchers.IO) {
                            runCatching {
                                freeform.maximizeByResize(
                                    taskId = task.taskId,
                                    component = task.topActivity,
                                    packageName = task.packageName,
                                    current = task.bounds,
                                    displayId = display.displayId,
                                    displayW = m.widthPixels.coerceAtLeast(1),
                                    displayH = m.heightPixels.coerceAtLeast(1),
                                )
                            }
                        }
                    },
                    multi = { launchMaximizePickerOnPhone() },
                )
            }
            val btnRestoreFreeform = controlButton("\u2750", "Restore to freeform") {  // ❐ — demote the maximized window back to a floating window
                restoreFreeform()  // self-guards: toasts when there's no maximized window
            }
            val btnArrange = controlButton("\u2637", "Arrange evenly") { // ☷ (even columns)
                requireWindows(2, "Need at least 2 windows to arrange") { arrangeEvenly() }
            }
            val btnArrangeOrder = controlButton("\u229E", "Arrange in order\u2026") { // ⊞ — order then tile (on phone)
                requireWindows(2, "Need at least 2 windows to arrange") { launchArrangeOnPhone() }
            }
            val btnStack = controlButton("\u29C9", "Stack windows") {     // ⧉ — gather all windows into a cascade
                requireWindows(2, "Need at least 2 windows to stack") { stackWindows() }
            }
            val btnRestore = controlButton("\u21BB", "Restore last session") {     // ↻ — relaunch the last saved session
                restoreLastSession()  // self-guards: toasts when there's no saved session
            }
            val btnClose = controlButton("\u2715", "Close all") {         // ✕
                requireWindows(1, "No windows to close") { showCloseAllConfirm() }
            }
            controlAvailability = listOf(
                btnMinimize to { st: WinState -> st.count >= 1 },
                btnMaximize to { st: WinState -> st.count >= 1 },
                btnRestoreFreeform to { st: WinState -> st.hasMaximized },
                btnArrange to { st: WinState -> st.count >= 2 },
                btnArrangeOrder to { st: WinState -> st.count >= 2 },
                btnStack to { st: WinState -> st.count >= 2 },
                btnRestore to { st: WinState -> st.hasSession },
                btnClose to { st: WinState -> st.count >= 1 },
            )
            listOf(
                listOf(btnMinimize, btnMaximize, btnRestoreFreeform),
                listOf(btnArrange, btnArrangeOrder, btnStack),
                listOf(btnRestore),
                listOf(btnClose),
            )
        } else {
            val btnBack = controlButton("\u2190", "Back") {           // ←
                injector.backGuarded()
            }
            val btnHome = controlButton("\u2302", "Home") {           // ⌂
                // Just lay the PortalPad wallpaper backdrop on the external display —
                // NOT the trackpad's goHome() (which targets the phone launcher).
                com.portalpad.app.service.PortalPadForegroundService.showWallpaper()
            }
            val btnClose = controlButton("\u2715", "Close all") {         // ✕
                showCloseAllConfirm()
            }
            controlAvailability = emptyList()
            listOf(
                listOf(btnBack, btnHome),
                listOf(btnClose),
            )
        }
        controlButtons = controlGroups.flatten()
        controlGroups.forEachIndexed { gi, group ->
            if (gi > 0) bar.addView(controlSeparator())
            group.forEach { bar.addView(it) }
        }

        // Balancing spacer on the right, equal weight to the left spacer, so the
        // control-button group sits CENTERED in the bar (equal flexible space on
        // both sides) rather than pushed to the right edge.
        bar.addView(View(displayContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        // Floor the bar width to its content so controls never clip. Measure the
        // assembled bar: the weighted spacers collapse to 0 under UNSPECIFIED, so
        // this is the intrinsic width of the button group + padding. The user's
        // widthPct can still make the bar WIDER, but never narrower than the
        // controls need — otherwise the rightmost icon (e.g. ✕) gets clipped when
        // more buttons exist than fit the configured width.
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        barContentMinW = bar.measuredWidth
        val widthCap = displayW.coerceAtMost(1600)
        val barWidth = (displayW * (topBarCfg.widthPct.coerceIn(30, 100) / 100f)).toInt()
            .coerceAtLeast(barContentMinW)
            .coerceAtMost(widthCap)

        val lp = WindowManager.LayoutParams(
            // Width is a user-configurable percent of the display, capped so it
            // doesn't stretch the whole super-ultrawide canvas, and floored to the
            // control row's intrinsic width so icons never clip. Centered/aligned
            // via gravity below per the configured position.
            barWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or when (topBarCfg.position) {
                com.portalpad.app.data.TopBarPosition.LEFT -> Gravity.START
                com.portalpad.app.data.TopBarPosition.RIGHT -> Gravity.END
                else -> Gravity.CENTER_HORIZONTAL
            }
            x = 0
            y = 0   // flush to the very top edge
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        try {
            windowManager.addView(bar, lp)
            container = bar
            barParams = lp
            Log.d(TAG, "Window control bar attached on display ${display.displayId}")
        } catch (t: Throwable) {
            Log.e(TAG, "Could not attach window control bar", t)
            return
        }

        // Always-visible mode (autoHideAfterSec <= 0): pin the bar shown right after
        // attach, so it's visible without waiting for a first cursor event to fire
        // the reveal logic. (Timed-hide mode starts hidden and reveals on top-edge.)
        if (topBarCfg.autoHideAfterSec <= 0) setShown(true)

        val s = CoroutineScope(Dispatchers.Main)
        scope = s

        // Tier-2 availability greying: poll the live window state and dim controls
        // whose action doesn't currently apply. Desktop mode only.
        startAvailabilityPolling()

        // Observe top-bar config changes and restyle the live bar in place, so
        // edits in Workspace (gradient / opacity / corners / width / position)
        // preview live on the glasses without needing to recreate the bar.
        configObserverScope?.cancel()
        configObserverScope = CoroutineScope(Dispatchers.Main).also { obs ->
            obs.launch {
                com.portalpad.app.PortalPadApp.instance.prefs.topBarConfig.collect { cfg ->
                    // `sizePct` feeds `density`, which is baked into every view's
                    // dimensions at build — so a size change needs a real rebuild,
                    // not a live restyle. Coalesce rapid slider ticks into one
                    // rebuild ~150ms after the drag settles so dragging doesn't
                    // thrash the window. Everything else restyles in place.
                    val sizeChanged = cfg.sizePct != topBarCfg.sizePct
                    topBarCfg = cfg
                    // If the user switches to always-visible (autoHideAfterSec <= 0)
                    // while the bar is attached, pin it shown now — don't wait for a
                    // cursor event. (No-op if a size rebuild is about to run below.)
                    if (cfg.autoHideAfterSec <= 0 && !shown && container != null && !sizeChanged) {
                        setShown(true)
                    }
                    if (sizeChanged && container != null) {
                        pendingSizeRecreate?.let { uiHandler.removeCallbacks(it) }
                        val r = Runnable { dismiss(); show() }
                        pendingSizeRecreate = r
                        uiHandler.postDelayed(r, 150L)
                    } else {
                        restyleInPlace()
                        updateZoneHighlight()
                    }
                }
            }
            obs.launch {
                com.portalpad.app.PortalPadApp.instance.zonePreviewActive.collect { active ->
                    zonePreviewActive = active
                    updateZoneHighlight()
                    // While previewing on the customization page, PIN the actual bar
                    // shown too — the user is on the phone and can't drive the glasses
                    // cursor into the top zone to reveal it, so otherwise only the
                    // zone highlight appeared. On preview end, hide it again.
                    if (active) setShown(true) else setShown(false)
                }
            }
        }

        // Reveal bar at the top edge, then KEEP it shown while the cursor is
        // anywhere over the bar (so you can move down onto a button and click
        // it). Previously the bar hid as soon as the cursor left an 8px strip —
        // below the buttons — so clicks never landed. We hide only once the
        // cursor moves well below the bar's actual height. While a grab is in
        // progress we keep the bar shown.
        revealJob = s.launch {
            injector.cursorPosition.collect { (x, y) ->
                if (mode != Mode.IDLE) { if (!shown) setShown(true); return@collect }
                // autoHideAfterSec == 0 = always visible (truly pinned, matching the
                // dock): keep it shown and skip all reveal/hide logic.
                if (topBarCfg.autoHideAfterSec <= 0) { if (!shown) setShown(true); return@collect }
                // Reveal zone: a band of the configured width, positioned to match
                // the bar (left/center/right), and a configured top-edge height.
                val zoneH = topBarCfg.revealZoneHeightPx.coerceIn(2, 200).toFloat()
                val band = displayW * (topBarCfg.revealZoneWidthPct.coerceIn(10, 100) / 100f)
                val xLo = when (topBarCfg.position) {
                    com.portalpad.app.data.TopBarPosition.LEFT -> 0f
                    com.portalpad.app.data.TopBarPosition.RIGHT -> displayW - band
                    else -> (displayW - band) / 2f
                }
                val inBand = x >= xLo && x <= xLo + band
                // Clear the post-timed-hide suppression once the cursor leaves
                // the reveal zone in EITHER direction — horizontally out of the
                // band, or vertically below the top strip. Previously this only
                // cleared on a horizontal exit, so re-approaching the top edge
                // vertically (the natural gesture) left the latch stuck and the
                // bar refused to reveal until the cursor happened to drift
                // sideways out of the band.
                if (!inBand || y > zoneH) suppressRevealUntilExit = false
                // While previewing on the customization page, the bar is pinned
                // shown — don't let the cursor-below rule hide it.
                if (zonePreviewActive) { if (!shown) setShown(true); return@collect }
                if (y <= zoneH && inBand && !shown && !suppressRevealUntilExit) setShown(true)
                // Timed hide (>0) is handled by scheduleAutoHide(); the
                // reveal-on-top-edge above brings a timed-hidden bar back.
            }
        }

        // While in MOVE/RESIZE, the proxy outline follows the cursor.
        cursorJob = s.launch {
            injector.cursorPosition.collect { (x, y) ->
                if (mode == Mode.IDLE) return@collect
                updateProxy(x, y)
            }
        }

        // Cursor clicks: in MOVE/RESIZE commit the grab; otherwise, when the
        // bar is shown, hit-test the control buttons by on-screen bounds and
        // fire the one under the cursor. This is the reliable click path — it
        // doesn't depend on the injected touch event reaching a TextView inside
        // the top-edge overlay (which it didn't on-device).
        clickJob = s.launch {
            injector.clickEvents.collect { (x, y) ->
                if (mode != Mode.IDLE) { commitGrab(); return@collect }
                // If the close-all confirmation is showing, its buttons take
                // priority over the bar buttons.
                if (confirmView != null && confirmButtons.isNotEmpty()) {
                    val cloc = IntArray(2)
                    var chit = false
                    for (b in confirmButtons) {
                        b.getLocationOnScreen(cloc)
                        val l = cloc[0]; val t = cloc[1]
                        if (x >= l && x <= l + b.width && y >= t && y <= t + b.height) {
                            flashButton(b); b.performClick(); chit = true; break
                        }
                    }
                    // Tap anywhere outside the card's buttons dismisses (treat the
                    // whole confirmation as modal). Always consume the click.
                    if (!chit) dismissConfirm()
                    return@collect
                }
                if (!shown) { Log.d(TAG, "CLICK ($x,$y) ignored — bar not shown"); return@collect }
                val loc = IntArray(2)
                var hit = false
                for (b in controlButtons) {
                    b.getLocationOnScreen(loc)
                    val left = loc[0]; val top = loc[1]
                    val right = left + b.width; val bottom = top + b.height
                    Log.d(TAG, "CLICK ($x,$y) test '${b.contentDescription}' bounds=($left,$top,$right,$bottom)")
                    if (x >= left && x <= right && y >= top && y <= bottom) {
                        Log.d(TAG, "CLICK HIT '${b.contentDescription}'")
                        flashButton(b)
                        b.performClick()
                        hit = true
                        break
                    }
                }
                if (!hit) Log.d(TAG, "CLICK ($x,$y) no button hit")
            }
        }

        // Hover highlight: as the cursor moves, tint whichever control button it
        // is over so the user gets feedback that the button is live and
        // clickable (matching how a mouse pointer highlights buttons on a PC).
        hoverJob = s.launch {
            injector.cursorPosition.collect { (x, y) ->
                if (!shown) { setHovered(null); return@collect }
                val loc = IntArray(2)
                var over: View? = null
                for (b in controlButtons) {
                    b.getLocationOnScreen(loc)
                    if (x >= loc[0] && x <= loc[0] + b.width &&
                        y >= loc[1] && y <= loc[1] + b.height
                    ) { over = b; break }
                }
                setHovered(over)
            }
        }
    }

    /** Show a centered confirmation card before closing all external-display
     *  apps (the ✕ is destructive). Buttons are cursor-clickable, hit-tested in
     *  the click flow like the bar's control buttons. */
    private fun showCloseAllConfirm() {
        container?.post {
            if (confirmView != null) return@post
            val pad = (16 * density).toInt()
            val card = LinearLayout(displayContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    cornerRadius = 18f * density
                    setColor("#F21A1226".toColorInt())
                }
            }
            val msg = TextView(displayContext).apply {
                text = if (isDesktopMode) "Close windows?" else "Close all apps?"
                setTextColor("#F2F2F5".toColorInt())
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 18f * density)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (14 * density).toInt())
            }
            card.addView(msg)
            fun pill(label: String, textColor: Int, fill: Int, onTap: () -> Unit): TextView =
                TextView(displayContext).apply {
                    text = label
                    contentDescription = label
                    setTextColor(textColor)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16f * density)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    val ph = (20 * density).toInt(); val pv = (10 * density).toInt()
                    setPadding(ph, pv, ph, pv)
                    isClickable = true
                    background = GradientDrawable().apply {
                        cornerRadius = 12f * density
                        setColor(fill)
                    }
                    setOnClickListener { onTap() }
                }
            fun hgap() = TextView(displayContext).apply { width = (12 * density).toInt() }
            val btnCancel = pill("Cancel", "#F2F2F5".toColorInt(), "#332A2140".toColorInt()) {
                dismissConfirm()
            }
            // Destructive action — kept red, but sized to its text (not a full-width
            // bar) and set apart on its own centered row below the safe actions.
            val btnCloseAll = pill("Close all windows", "#FFFFFFFF".toColorInt(), "#CCFF6B6B".toColorInt()) {
                dismissConfirm()
                closeAllExternalApps()
            }
            if (isDesktopMode) {
                // Row 1: safe actions side by side.
                val btnManage = pill("Manage Windows", "#FFC8A6FF".toColorInt(), "#332A2140".toColorInt()) {
                    dismissConfirm()
                    launchManageOnPhone()
                }
                val row1 = LinearLayout(displayContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                row1.addView(btnCancel); row1.addView(hgap()); row1.addView(btnManage)
                card.addView(row1)
                // Vertical gap, then the destructive action centered on its own row.
                card.addView(TextView(displayContext).apply { height = (12 * density).toInt() })
                val row2 = LinearLayout(displayContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                row2.addView(btnCloseAll)
                card.addView(row2)
                confirmButtons = listOf(btnCancel, btnManage, btnCloseAll)
            } else {
                // Non-desktop: simple Cancel / Close all on one row.
                val row = LinearLayout(displayContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                row.addView(btnCancel); row.addView(hgap()); row.addView(btnCloseAll)
                card.addView(row)
                confirmButtons = listOf(btnCancel, btnCloseAll)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayHost.windowType,
                // Not focusable; clicks come via the injected-cursor click flow.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.CENTER }
            runCatching { windowManager.addView(card, params) }
                .onSuccess { confirmView = card; confirmParams = params }
            com.portalpad.app.service.PortalPadForegroundService.raiseCursor()
        }
    }

    private fun dismissConfirm() {
        container?.post {
            confirmView?.let { runCatching { windowManager.removeView(it) } }
            confirmView = null; confirmParams = null
            confirmButtons = emptyList()
        }
    }

    /** Close every real app on the external display (both modes). */
    private fun closeAllExternalApps() {
        val s = scope ?: return
        s.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val tasks = freeform.listExternalTasks()
                        .filter { rt ->
                            !rt.packageName.contains("launcher", ignoreCase = true) &&
                                !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                                rt.packageName != "com.portalpad.app"
                        }
                    Log.d(TAG, "CLOSE ALL: ${tasks.size} task(s): " +
                        tasks.joinToString { "${it.packageName}#${it.taskId}" })
                    for (t in tasks) runCatching { freeform.close(t.taskId) }
                }
            }
            maybeShowWallpaperIfEmpty()
        }
    }

    /** Open the per-window manager on the phone (reuses WindowLimitActivity) so
     *  the user can close windows individually. Mirrors the cap flow's launch:
     *  a brief toast on the external display + a real phone activity (its own
     *  task affinity, no CLEAR_TOP, so it can't disturb the wallpaper backdrop). */
    private fun launchManageOnPhone() {
        val app = com.portalpad.app.PortalPadApp.instance
        runCatching {
            com.portalpad.app.presentation.GlassesToast.show(
                app, display, "Manage windows on your phone", 3500L,
            )
        }
        runCatching {
            val opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
            app.startActivity(
                android.content.Intent(app, com.portalpad.app.ui.settings.WindowLimitActivity::class.java)
                    .putExtra("mode", "manage")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                opts.toBundle(),
            )
            Log.d(TAG, "top-bar: launched WindowLimitActivity (manage) on phone")
        }.onFailure { Log.e(TAG, "manage-on-phone launch failed", it) }
    }

    /** Open the window-ordering screen on the phone (ArrangeWindowsActivity).
     *  The user orders the open windows, then they tile into even columns in
     *  that order — reuses the same resize/evenColumn path as "Arrange evenly". */
    private fun launchArrangeOnPhone() {
        val app = com.portalpad.app.PortalPadApp.instance
        runCatching {
            com.portalpad.app.presentation.GlassesToast.show(
                app, display, "Order your windows on your phone", 3500L,
            )
        }
        runCatching {
            val opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
            app.startActivity(
                android.content.Intent(app, com.portalpad.app.ui.settings.ArrangeWindowsActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                opts.toBundle(),
            )
            Log.d(TAG, "top-bar: launched ArrangeWindowsActivity on phone")
        }.onFailure { Log.e(TAG, "arrange-on-phone launch failed", it) }
    }

    /** Open the maximize-window picker on the phone (MaximizeWindowActivity):
     *  a list of the open external-display windows; tapping one brings it to
     *  full view. Replaces the old "maximize the front window" button so the
     *  user can jump straight to any window, not just the frontmost. */
    private fun launchMaximizePickerOnPhone() {
        val app = com.portalpad.app.PortalPadApp.instance
        runCatching {
            com.portalpad.app.presentation.GlassesToast.show(
                app, display, "Choose a window on your phone", 3500L,
            )
        }
        runCatching {
            val opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
            app.startActivity(
                android.content.Intent(app, com.portalpad.app.ui.settings.MaximizeWindowActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                opts.toBundle(),
            )
            Log.d(TAG, "top-bar: launched MaximizeWindowActivity on phone")
        }.onFailure { Log.e(TAG, "maximize-picker launch failed", it) }
    }

    /** Open the minimize-window picker on the phone (MinimizeWindowActivity):
     *  a list of the open external-display windows; tapping one minimizes it.
     *  Replaces the old "minimize the highlighted window" button so the user can
     *  minimize any window directly. */
    private fun launchMinimizePickerOnPhone() {
        val app = com.portalpad.app.PortalPadApp.instance
        runCatching {
            com.portalpad.app.presentation.GlassesToast.show(
                app, display, "Choose a window on your phone", 3500L,
            )
        }
        runCatching {
            val opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
            app.startActivity(
                android.content.Intent(app, com.portalpad.app.ui.settings.MinimizeWindowActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                opts.toBundle(),
            )
            Log.d(TAG, "top-bar: launched MinimizeWindowActivity on phone")
        }.onFailure { Log.e(TAG, "minimize-picker launch failed", it) }
    }

    /** Restore the last saved session (relaunch + retile the windows that were
     *  open when the session was last captured). Delegates to the foreground
     *  service, which owns the saved-session restore path used by Settings and
     *  the reconnect dialog. */
    private fun restoreLastSession() {
        val app = com.portalpad.app.PortalPadApp.instance
        val svc = com.portalpad.app.service.PortalPadForegroundService.instance
        if (svc == null) {
            runCatching {
                com.portalpad.app.presentation.GlassesToast.show(
                    app, display, "Restore unavailable right now", 3500L,
                )
            }
            return
        }
        val s = scope ?: return
        s.launch {
            val hasSession = withContext(Dispatchers.IO) {
                runCatching { app.prefs.lastSession.first().windows.isNotEmpty() }.getOrDefault(false)
            }
            if (!hasSession) {
                runCatching {
                    com.portalpad.app.presentation.GlassesToast.show(
                        app, display, "No saved session to restore", 2500L,
                    )
                }
                return@launch
            }
            runCatching {
                com.portalpad.app.presentation.GlassesToast.show(
                    app, display, "Restoring your last session\u2026", 3500L,
                )
            }
            runCatching { svc.restoreSavedSession() }
                .onFailure { Log.e(TAG, "restore-last-session failed", it) }
        }
    }

    /** After closing an app from the top bar, check whether the external display
     *  still has any real app on it. If it's now empty (only launcher/PortalPad,
     *  or nothing), show the PortalPad home-backdrop so the user never lands on a
     *  blank display. Best-effort: closing is async, so we poll briefly. */
    private fun maybeShowWallpaperIfEmpty() {
        val s = scope ?: return
        s.launch {
            // Give the close a moment to actually remove the task.
            kotlinx.coroutines.delay(450)
            val empty = withContext(Dispatchers.IO) {
                runCatching {
                    val tasks = freeform.listExternalTasks()
                    // "Real app" = not a launcher and not PortalPad itself.
                    tasks.none { rt ->
                        !rt.packageName.contains("launcher", ignoreCase = true) &&
                            !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                            rt.packageName != "com.portalpad.app"
                    }
                }.getOrDefault(false)
            }
            if (empty) {
                com.portalpad.app.service.PortalPadForegroundService.showWallpaper()
            }
        }
    }

    /** Show/update/hide the translucent detection-zone highlight on the glasses.
     *  Shown only while [zonePreviewActive]; sized to the configured zone
     *  (top-edge height × band width, positioned per the bar's L/C/R). */
    private fun updateZoneHighlight() {
        if (!zonePreviewActive) {
            zoneHighlightView?.let { runCatching { windowManager.removeView(it) } }
            zoneHighlightView = null
            zoneHighlightParams = null
            return
        }
        val zoneH = topBarCfg.revealZoneHeightPx.coerceIn(2, 200)
        val bandW = (displayW * (topBarCfg.revealZoneWidthPct.coerceIn(10, 100) / 100f)).toInt()
        val gravityX = when (topBarCfg.position) {
            com.portalpad.app.data.TopBarPosition.LEFT -> Gravity.START
            com.portalpad.app.data.TopBarPosition.RIGHT -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        runCatching {
            val existing = zoneHighlightView
            if (existing == null) {
                val v = View(displayContext).apply {
                    setBackgroundColor(topBarCfg.zoneHighlightColor.toInt())
                }
                val lp = WindowManager.LayoutParams(
                    bandW.coerceAtLeast(1),
                    zoneH,
                    overlayHost.windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or gravityX
                    x = 0; y = 0
                }
                windowManager.addView(v, lp)
                zoneHighlightView = v
                zoneHighlightParams = lp
            } else {
                existing.setBackgroundColor(topBarCfg.zoneHighlightColor.toInt())
                zoneHighlightParams?.let { p ->
                    p.width = bandW.coerceAtLeast(1)
                    p.height = zoneH
                    p.gravity = Gravity.TOP or gravityX
                    windowManager.updateViewLayout(existing, p)
                }
            }
        }.onFailure { Log.w(TAG, "updateZoneHighlight failed", it) }
    }

    /** Re-apply the user's style + size/position to the already-attached bar
     *  view, so Workspace edits show live. No teardown — just updates the
     *  background drawable and the window LayoutParams. */
    private fun restyleInPlace() {
        val bar = container ?: return
        runCatching {
            bar.background = android.graphics.drawable.GradientDrawable(
                topBarCfg.style.drawableOrientation(),
                topBarCfg.style.drawableColors(),
            ).apply {
                gradientType = topBarCfg.style.gradientType.toGradientDrawableType()
                if (gradientType != android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT) {
                    setGradientCenter(topBarCfg.style.centerXFrac(), topBarCfg.style.centerYFrac())
                    gradientRadius = topBarCfg.style.radiusFrac() * displayW
                }
                topBarCfg.style.drawableOffsets()?.let { setColors(topBarCfg.style.drawableColors(), it) }
                cornerRadius = topBarCfg.style.cornerRadiusDp * density
                setStroke((1 * density).toInt(), "#26FFFFFF".toColorInt())
            }
            // Keep the control glyphs + title legible when the theme luminance
            // flips (e.g. user drags to a light gradient).
            val gc = barGlyphColor()
            for (b in controlButtons) (b as? TextView)?.setTextColor(gc)
            appTitleView?.setTextColor(gc)
            // The loop above re-colored every control to full glyph color; restore
            // the availability dimming on top so a theme change doesn't un-grey
            // controls that still don't apply.
            lastWinState?.let { applyAvailability(it) }
            barParams?.let { p ->
                p.width = (displayW * (topBarCfg.widthPct.coerceIn(30, 100) / 100f))
                    .toInt()
                    .coerceAtLeast(barContentMinW)
                    .coerceAtMost(displayW.coerceAtMost(1600))
                p.gravity = Gravity.TOP or when (topBarCfg.position) {
                    com.portalpad.app.data.TopBarPosition.LEFT -> Gravity.START
                    com.portalpad.app.data.TopBarPosition.RIGHT -> Gravity.END
                    else -> Gravity.CENTER_HORIZONTAL
                }
                runCatching { windowManager.updateViewLayout(bar, p) }
            }
        }.onFailure { Log.w(TAG, "restyleInPlace failed", it) }
    }

    private fun setShown(visible: Boolean) {
        shown = visible
        container?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            val c = container
            val loc = IntArray(2)
            c?.getLocationOnScreen(loc)
            Log.d(TAG, "BAR shown=true mode=${if (isDesktopMode) "desktop" else "standard"} onScreen=(${loc[0]},${loc[1]}) size=(${c?.width}x${c?.height}) buttons=${controlButtons.size}")
            // Duration auto-hide (mirrors the dock): if configured (>0), hide the
            // bar after N seconds. Re-showing restarts the timer. 0 = never
            // auto-hide on a timer (the cursor-below rule handles hiding instead).
            scheduleAutoHide()
        } else {
            autoHideJob?.cancel(); autoHideJob = null
            Log.d(TAG, "BAR shown=false")
        }
    }

    private fun scheduleAutoHide() {
        autoHideJob?.cancel(); autoHideJob = null
        // Pinned shown during customization preview — never schedule a hide.
        if (zonePreviewActive) return
        val secs = topBarCfg.autoHideAfterSec
        if (secs <= 0) return
        val s = scope ?: return
        autoHideJob = s.launch {
            while (true) {
                kotlinx.coroutines.delay(secs * 1000L)
                if (!shown) return@launch
                // HOVER HOLD: a cursor parked anywhere over the bar (or a grab
                // in progress) must not have the bar hide under it — re-arm a
                // full window and check again, so the bar hides only a full
                // duration after the cursor LEAVES.
                if (mode != Mode.IDLE || cursorOverBar()) continue
                suppressRevealUntilExit = true
                setShown(false)
                return@launch
            }
        }
    }

    /** Whether the injected cursor currently sits over the visible bar's
     *  on-screen rect (small grace margin so the very edge still counts). */
    private fun cursorOverBar(): Boolean = runCatching {
        val c = container ?: return@runCatching false
        if (c.visibility != View.VISIBLE || c.width == 0) return@runCatching false
        val loc = IntArray(2)
        c.getLocationOnScreen(loc)
        val (x, y) = injector.cursorPosition.value
        val pad = 12f
        x >= loc[0] - pad && x <= loc[0] + c.width + pad &&
            y >= loc[1] - pad && y <= loc[1] + c.height + pad
    }.getOrDefault(false)

    /** Update the proxy outline as the cursor moves. MOVE translates the whole
     *  rect so its top-left follows the cursor; RESIZE holds the top-left and
     *  moves the bottom-right corner to the cursor. */
    private fun updateProxy(cx: Float, cy: Float) {
        val base = workBounds ?: return
        val next = when (mode) {
            Mode.MOVE -> {
                val w = base.width; val h = base.height
                val left = cx.toInt().coerceIn(0, (displayW - w).coerceAtLeast(0))
                val top = cy.toInt().coerceIn(0, (displayH - h).coerceAtLeast(0))
                WindowBounds(left, top, left + w, top + h)
            }
            Mode.RESIZE -> {
                val left = base.left; val top = base.top
                val right = cx.toInt().coerceAtLeast(left + MIN_WINDOW_PX).coerceAtMost(displayW)
                val bottom = cy.toInt().coerceAtLeast(top + MIN_WINDOW_PX).coerceAtMost(displayH)
                WindowBounds(left, top, right, bottom)
            }
            Mode.IDLE -> return
        }
        proxyParams?.let { p ->
            p.x = next.left; p.y = next.top
            p.width = next.width; p.height = next.height
            proxy?.let { runCatching { windowManager.updateViewLayout(it, p) } }
        }
        lastPreview = next
    }

    /** Commit the grab on click: apply the previewed bounds to the real window. */
    private fun commitGrab() {
        val task = grabTask
        val bounds = lastPreview ?: workBounds
        mode = Mode.IDLE
        hideProxy()
        grabTask = null
        workBounds = null
        lastPreview = null
        if (task != null && bounds != null) {
            scope?.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        freeform.setWindowingModeFreeform(task.taskId)
                        freeform.resize(task.taskId, bounds)
                    }
                }
            }
        }
    }

    private fun showProxy(b: WindowBounds) {
        hideProxy()
        val v = View(displayContext).apply {
            background = GradientDrawable().apply {
                setColor("#3327E0C0".toColorInt())   // translucent teal fill
                setStroke((2 * density).toInt(), "#CC27E0C0".toColorInt())
                cornerRadius = 10f * density
            }
        }
        val lp = WindowManager.LayoutParams(
            b.width, b.height,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = b.left; y = b.top
        }
        try {
            windowManager.addView(v, lp)
            proxy = v
            proxyParams = lp
        } catch (t: Throwable) {
            Log.e(TAG, "Could not show window proxy", t)
        }
    }

    private fun hideProxy() {
        proxy?.let { runCatching { windowManager.removeView(it) } }
        proxy = null
        proxyParams = null
    }

    private fun applyToFront(action: (taskId: Int) -> Unit) {
        applyToFrontTask { action(it.taskId) }
    }

    /** Like [applyToFront] but hands the resolved [RunningTask] to [action] (so
     *  callers can read its bounds, e.g. minimize parks it off-screen). */
    private fun applyToFrontTask(action: (task: RunningTask) -> Unit) {
        val s = scope ?: return
        s.launch {
            val front = withContext(Dispatchers.IO) {
                runCatching {
                    // The click arrives on this overlay's display id (the VD),
                    // but the app's task may be registered under a different
                    // display id (physical vs VD wrapper). Try this display
                    // first, then the injector's, then ANY non-launcher task —
                    // otherwise the control "hits" but finds nothing to act on.
                    val ids = listOf(display.displayId, injector.displayId).distinct()
                    var t: RunningTask? = null
                    for (id in ids) {
                        t = freeform.listTasks(id).firstOrNull()
                        if (t != null) break
                    }
                    t ?: freeform.listTasks(-1).firstOrNull { rt ->
                        !rt.packageName.contains("launcher", ignoreCase = true) &&
                            !rt.packageName.contains("nexuslauncher", ignoreCase = true)
                    }
                }.getOrNull()
            } ?: return@launch
            withContext(Dispatchers.IO) { action(front) }
        }
    }

    /**
     * "Arrange evenly" — lay every open freeform window out side by side in
     * equal-width columns spanning the display (2 -> halves, 3 -> thirds, ...
     * up to 6). On the super-ultrawide canvas this makes real multi-column
     * layouts; on a normal display it's the usual halves/thirds. Windows are
     * ordered left-to-right by their current horizontal position so the result
     * is predictable and minimally disruptive. Resizes are issued sequentially
     * with a small gap to avoid hitching the shell with a burst of commands.
     */
    private fun arrangeEvenly() {
        val s = scope ?: return
        // Shared with the trackpad's arrange button. Pass our cached width as the
        // floor (the ultrawide stale-metrics guard) and our display as primary.
        WindowArranger.arrangeEvenly(s, display.displayId, floorW = displayW)
    }

    /** "Restore to freeform": demote the maximized window back to a floating
     *  freeform window. Prefers the window PortalPad tracked as maximized; if that
     *  can't be resolved (e.g. several windows open, or it was maximized outside
     *  our tracking), falls back to the window whose bounds fill ~the whole display
     *  — the one that visually IS maximized. No picker. Logs its decision under
     *  "PortalPadRestore" so a logcat shows what it acted on. */
    private fun restoreFreeform() {
        val s = scope ?: return
        s.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val ids = listOf(display.displayId, injector.displayId).distinct()
                    val tracked = freeform.currentlyMaximizedTaskId()
                    var id = display.displayId
                    var task: RunningTask? = null

                    // 1) Try the tracked maximized task across candidate displays.
                    if (tracked != null) {
                        for (cand in ids) {
                            val t = freeform.listTasks(cand).firstOrNull { it.taskId == tracked }
                            if (t != null) { id = cand; task = t; break }
                        }
                    }

                    // 2) Fallback: the largest non-launcher window that fills most
                    //    of the display (≈ visually maximized). Robust with several
                    //    windows open and regardless of how it got maximized.
                    if (task == null) {
                        for (cand in ids) {
                            val tasks = freeform.listTasks(cand).filter { rt ->
                                !rt.packageName.contains("launcher", ignoreCase = true) &&
                                    !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                                    rt.packageName != "com.portalpad.app"
                            }
                            val big = tasks.maxByOrNull { rt ->
                                val b = rt.bounds ?: return@maxByOrNull 0L
                                (b.right - b.left).toLong() * (b.bottom - b.top).toLong()
                            }
                            val b = big?.bounds
                            if (big != null && b != null && displayW > 0 && displayH > 0) {
                                val area = (b.right - b.left).toLong() * (b.bottom - b.top).toLong()
                                val full = displayW.toLong() * displayH.toLong()
                                if (area >= full * 70 / 100) { id = cand; task = big; break }
                            }
                        }
                    }

                    if (task == null) {
                        Log.d("PortalPadRestore", "no maximized/large window to restore (tracked=$tracked)")
                        runCatching {
                            com.portalpad.app.presentation.GlassesToast.show(
                                com.portalpad.app.PortalPadApp.instance, display, "No maximized window", 2500L,
                            )
                        }
                        return@runCatching
                    }
                    Log.d(
                        "PortalPadRestore",
                        "restoring task=${task.taskId} pkg=${task.packageName} bounds=${task.bounds} " +
                            "display=$id tracked=$tracked",
                    )
                    freeform.restoreToFreeform(
                        taskId = task.taskId,
                        component = task.topActivity,
                        packageName = task.packageName,
                        displayId = id,
                        displayW = displayW,
                        displayH = displayH,
                    )
                }.onFailure { Log.e(TAG, "restoreFreeform failed", it) }
            }
        }
    }

    /** "Stack windows": cascade every open window into one spot (slight offset
     *  so each peeks out). Resolves the right display id the same way the other
     *  window actions do (VD id can mismatch where tasks are registered). */
    private fun stackWindows() {
        val s = scope ?: return
        s.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    // Prefer the display that actually has windows on it.
                    val ids = listOf(display.displayId, injector.displayId).distinct()
                    val id = ids.firstOrNull { freeform.listTasks(it).any { t ->
                        !t.packageName.contains("launcher", ignoreCase = true) &&
                            t.packageName != "com.portalpad.app"
                    } } ?: display.displayId
                    freeform.cascadeWindows(id, displayW, displayH)
                }.onFailure { Log.e(TAG, "stackWindows failed", it) }
            }
        }
    }

    /** Tint the button under the cursor (hover feedback) and clear the rest.
     *  Runs on the UI thread; safe to call from the cursor-position collector. */
    private fun setHovered(button: View?) {
        if (button === hoveredButton) return
        val prev = hoveredButton
        hoveredButton = button
        container?.post {
            // No background highlight tint — instead scale the button up while
            // hovered (matching the dock's voice/search hover), keeping the
            // background transparent.
            (prev?.background as? GradientDrawable)?.setColor(Color.TRANSPARENT)
            (button?.background as? GradientDrawable)?.setColor(Color.TRANSPARENT)
            prev?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
            button?.animate()?.scaleX(1.18f)?.scaleY(1.18f)?.setDuration(120)?.start()
            // Tooltip: show the button's description below it (gap above), or hide.
            val tv = button as? TextView
            if (tv != null) showTooltip(tv, tv.contentDescription?.toString() ?: "")
            else hideTooltip()
        }
    }

    /** Show a small pill tooltip centered below [anchor] with a gap, matching the
     *  dock's hover tooltip style (View-based since this bar isn't Compose). */
    private fun showTooltip(anchor: View, text: String) {
        if (text.isBlank()) { hideTooltip(); return }
        hideTooltip()
        val tip = TextView(displayContext).apply {
            this.text = text
            setTextColor("#F2F2F5".toColorInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13f * density)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            gravity = Gravity.CENTER
            val padH = (10 * density).toInt()
            val padV = (5 * density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor("#E61A1226".toColorInt())   // dark, slightly translucent
            }
        }
        // Measure to size the window and center it under the anchor.
        tip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val tipW = tip.measuredWidth
        val tipH = tip.measuredHeight
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val gap = (8 * density).toInt()   // gap above tooltip so it doesn't touch the icon
        val x = loc[0] + anchor.width / 2 - tipW / 2
        val y = loc[1] + anchor.height + gap
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayHost.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceAtLeast(0)
            this.y = y
        }
        runCatching { windowManager.addView(tip, params) }
            .onSuccess { tooltipView = tip; tooltipParams = params }
    }

    private fun hideTooltip() {
        tooltipView?.let { runCatching { windowManager.removeView(it) } }
        tooltipView = null
        tooltipParams = null
    }

    /** Click "pulse" to confirm a click registered — quick scale-down then back
     *  to the resting scale (mirrors the dock voice/search click pulse). */
    private fun flashButton(button: View) {
        container?.post {
            val resting = if (button === hoveredButton) 1.18f else 1f
            button.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80).withEndAction {
                button.animate().scaleX(resting).scaleY(resting).setDuration(120).start()
            }.start()
        }
    }

    private fun controlButton(
        glyph: String,
        description: String,
        onClick: () -> Unit,
    ): TextView = TextView(displayContext).apply {
        text = glyph
        contentDescription = description
        setTextColor(barGlyphColor())   // off-white on dark bars, dark slate on light
        // Use a PIXEL size computed from the PINNED density, not `textSize = 26f`
        // (which is sp and scales with the live display DPI — the cause of the
        // top-bar icons resizing when the external-display DPI slider moves).
        // 26 * pinned-density keeps the glyph a fixed size at any DPI.
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26f * density)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        val padH = (16 * density).toInt()
        val padV = (4 * density).toInt()        // trimmed further; glyph size unchanged
        setPadding(padH, padV, padH, padV)
        isClickable = true
        isFocusable = false
        background = GradientDrawable().apply {
            cornerRadius = 14f * density
            setColor(Color.TRANSPARENT)
        }
        setOnClickListener { onClick() }
    }

    /** A hairline separator between control-button groups: a thin vertical rule in
     *  the bar's glyph color at low alpha. NOT a member of [controlButtons] — it's
     *  non-interactive and never hit-tested. */
    private fun controlSeparator(): View = View(displayContext).apply {
        val w = (1.5f * density).toInt().coerceAtLeast(1)
        val h = (18 * density).toInt()
        val m = (7 * density).toInt()
        layoutParams = LinearLayout.LayoutParams(w, h).also {
            it.leftMargin = m
            it.rightMargin = m
            it.gravity = Gravity.CENTER_VERTICAL
        }
        setBackgroundColor((barGlyphColor() and 0x00FFFFFF) or 0x33000000)
    }

    /** VISIBLE user windows on the external display (excludes launchers and
     *  PortalPad itself) — the basis for the top bar's availability guards and the
     *  single-vs-picker branch. Runs a shell enumeration, so call it OFF the main
     *  thread. */
    private fun isUserWindow(rt: RunningTask): Boolean =
        rt.visible &&
            !rt.packageName.contains("launcher", ignoreCase = true) &&
            !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
            rt.packageName != "com.portalpad.app"

    private fun userWindows(): List<RunningTask> = runCatching {
        freeform.listExternalTasks().filter { isUserWindow(it) }
    }.getOrDefault(emptyList())

    private fun userWindowCount(): Int = userWindows().size

    /** Window controls that act on ONE window directly but defer to a phone picker
     *  when there are several: enumerate, then 0 → [emptyMsg] toast, exactly 1 →
     *  [single] on that window (no picker), 2+ → [multi] (the picker). Enumeration
     *  is async; [single] runs in the same coroutine so it can suspend for its
     *  own shell work. */
    private fun onSingleOrPick(
        emptyMsg: String,
        single: suspend (RunningTask) -> Unit,
        multi: () -> Unit,
    ) {
        val s = scope ?: return
        s.launch {
            val users = withContext(Dispatchers.IO) { userWindows() }
            when {
                users.isEmpty() -> runCatching {
                    com.portalpad.app.presentation.GlassesToast.show(
                        com.portalpad.app.PortalPadApp.instance, display, emptyMsg, 2500L,
                    )
                }
                users.size == 1 -> single(users[0])
                else -> multi()
            }
        }
    }

    /** Run [action] only when at least [min] user windows are open; otherwise
     *  surface [emptyMsg] on the glasses and do nothing, so a control never opens
     *  empty UI (e.g. the on-phone arrange picker with no windows to arrange).
     *  Enumeration is async, so [action] runs just after the tap, on the main thread. */
    private fun requireWindows(min: Int, emptyMsg: String, action: () -> Unit) {
        val s = scope ?: return
        s.launch {
            val n = withContext(Dispatchers.IO) { userWindowCount() }
            if (n >= min) {
                action()
            } else {
                runCatching {
                    com.portalpad.app.presentation.GlassesToast.show(
                        com.portalpad.app.PortalPadApp.instance, display, emptyMsg, 2500L,
                    )
                }
            }
        }
    }

    /**
     * Run a window action by id, from another surface (the trackpad radial menu),
     * using the SAME delegates as the top-bar buttons. Each delegate self-guards
     * (enumerates windows, toasts on the glasses when not applicable) and runs on
     * [scope], so callers don't pre-check. [closeAll] runs the close directly: the
     * radial does its own two-tap confirm, so the on-glasses confirm is skipped.
     * Unknown ids no-op. Mirrors the handlers at [controlGroups] verbatim.
     */
    fun runAction(action: String) {
        when (action) {
            "minimize" -> onSingleOrPick(
                emptyMsg = "No windows to minimize",
                single = { task ->
                    withContext(Dispatchers.IO) {
                        runCatching { freeform.minimizeByClose(task.taskId, task.packageName, task.bounds) }
                    }
                    maybeShowWallpaperIfEmpty()
                },
                multi = { launchMinimizePickerOnPhone() },
            )
            "maximize" -> onSingleOrPick(
                emptyMsg = "No windows to maximize",
                single = { task ->
                    val m = DisplayMetrics().also { @Suppress("DEPRECATION") display.getRealMetrics(it) }
                    withContext(Dispatchers.IO) {
                        runCatching {
                            freeform.maximizeByResize(
                                taskId = task.taskId,
                                component = task.topActivity,
                                packageName = task.packageName,
                                current = task.bounds,
                                displayId = display.displayId,
                                displayW = m.widthPixels.coerceAtLeast(1),
                                displayH = m.heightPixels.coerceAtLeast(1),
                            )
                        }
                    }
                },
                multi = { launchMaximizePickerOnPhone() },
            )
            "restoreFreeform" -> restoreFreeform()
            "arrangeEvenly" -> requireWindows(2, "Need at least 2 windows to arrange") { arrangeEvenly() }
            "arrangeInOrder" -> requireWindows(2, "Need at least 2 windows to arrange") { launchArrangeOnPhone() }
            "stack" -> requireWindows(2, "Need at least 2 windows to stack") { stackWindows() }
            "restoreSession" -> restoreLastSession()
            "closeAll" -> closeAllExternalApps()
        }
    }

    /** Start the availability poll (desktop mode only): every ~2s recompute the
     *  window state and, when it changes, dim/undim each control. Lives in [scope],
     *  so it's cancelled in dismiss(). */
    private fun startAvailabilityPolling() {
        availabilityPollJob?.cancel()
        if (!isDesktopMode) { availabilityPollJob = null; return }
        val s = scope ?: return
        availabilityPollJob = s.launch {
            var lastSig: String? = null
            // Derive availability from the shared WindowMonitor snapshot (one poll
            // for the whole app) combined with the saved-session flow, instead of
            // running our own ~2s shell enumeration. count/hasMaximized refresh on
            // each snapshot; hasSession now updates instantly on save, not on poll.
            kotlinx.coroutines.flow.combine(
                com.portalpad.app.PortalPadApp.instance.windowMonitor.snapshot,
                com.portalpad.app.PortalPadApp.instance.prefs.lastSession,
            ) { snap, session ->
                WinState(
                    snap.tasks.count { isUserWindow(it) },
                    snap.maximizedId != null,
                    session.windows.isNotEmpty(),
                )
            }.collect { state ->
                val sig = "${state.count}|${state.hasMaximized}|${state.hasSession}"
                if (sig != lastSig) {
                    lastSig = sig
                    lastWinState = state
                    applyAvailability(state)
                }
            }
        }
    }

    /** Dim controls whose action doesn't currently apply (full glyph color when
     *  applicable, ~30% alpha otherwise). Purely visual — the tap-time guards still
     *  do the real refusing; this just surfaces availability at a glance. */
    private fun applyAvailability(state: WinState) {
        val on = barGlyphColor()
        val off = (on and 0x00FFFFFF) or 0x4D000000
        controlAvailability.forEach { (btn, applies) ->
            btn.setTextColor(if (applies(state)) on else off)
        }
    }

    /** Glyph/title color for the bar, chosen from the bar gradient's luminance so
     *  the window controls + title stay legible whatever theme the user picks:
     *  off-white on a dark bar, dark slate on a light bar. */
    private fun barGlyphColor(): Int {
        val lum = (android.graphics.Color.luminance(topBarCfg.style.colorAInt()) +
            android.graphics.Color.luminance(topBarCfg.style.colorBInt())) / 2f
        return if (lum < 0.5f) "#F2F2F5".toColorInt() else "#15131A".toColorInt()
    }

    fun dismiss() {
        pendingSizeRecreate?.let { uiHandler.removeCallbacks(it) }; pendingSizeRecreate = null
        revealJob?.cancel(); revealJob = null
        autoHideJob?.cancel(); autoHideJob = null
        configObserverScope?.cancel(); configObserverScope = null
        zoneHighlightView?.let { runCatching { windowManager.removeView(it) } }
        zoneHighlightView = null
        zoneHighlightParams = null
        cursorJob?.cancel(); cursorJob = null
        clickJob?.cancel(); clickJob = null
        hoverJob?.cancel(); hoverJob = null
        availabilityPollJob?.cancel(); availabilityPollJob = null
        lastWinState = null
        controlAvailability = emptyList()
        hoveredButton = null
        hideTooltip()
        confirmView?.let { runCatching { windowManager.removeView(it) } }
        confirmView = null; confirmParams = null; confirmButtons = emptyList()
        scope = null
        mode = Mode.IDLE
        shown = false
        hideProxy()
        container?.let { runCatching { windowManager.removeView(it) } }
        container = null
        barParams = null
        grabTask = null
        workBounds = null
        lastPreview = null
    }

    companion object {
        private const val TAG = "WindowControlBar"
        private const val MIN_WINDOW_PX = 320
    }
}
