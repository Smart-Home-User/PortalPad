package com.portalpad.app.presentation

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
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
import com.portalpad.app.data.DockPosition
import com.portalpad.app.data.DockConfig
import com.portalpad.app.ui.dock.DockBar
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.PortalPadTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Dock rendered as a system-level overlay on the external display.
 *
 * Unlike [DockPresentation], this stays on top of any activity launched on the
 * same display via `am start --display N` — because it's a TYPE_APPLICATION_OVERLAY
 * window pinned to the secondary display's WindowManager, not a Presentation that
 * lives at the activity layer.
 *
 * Requires SYSTEM_ALERT_WINDOW (which the app already declares + the user grants
 * from the Settings → System tab).
 *
 * OEM caveats: stock Android, Pixel, OnePlus = works. Samsung One UI = usually
 * works. Xiaomi MIUI = sometimes silently refuses to render on secondary displays
 * even with the permission. If the dock doesn't show, the user can switch to
 * Presentation mode in Settings → Dock.
 */
/**
 * 213 dpi ÷ 160 = 1.33 — the dock's fixed layout density. Must stay in sync with
 * DockBar.BASELINE_DENSITY (private there): DockBar computes
 * `dpiComp = BASELINE_DENSITY / LocalDensity.current.density`, so providing this
 * exact value makes dpiComp resolve to 1.0 and leaves every dp/sp — derived AND
 * raw — at a fixed on-screen size regardless of the external-display DPI slider.
 */
private const val DOCK_BASELINE_DENSITY = 1.33f

class DockOverlay(
    serviceContext: Context,
    val display: Display,
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
    /** Invoked once, when the dock window has genuinely attached — the hook
     *  the service uses to re-add the cursor overlay ABOVE the dock. */
    var onWindowAttached: (() -> Unit)? = null
    /** Live window params for the dock window, so we can expand it to
     *  full-screen while a long-press menu / folder popup is open (those modals
     *  use fillMaxSize and need room) and restore the bar size afterward. */
    private var liveParams: WindowManager.LayoutParams? = null
    private var sizeObserverScope: kotlinx.coroutines.CoroutineScope? = null
    private var barSpanW: Int = WindowManager.LayoutParams.WRAP_CONTENT
    private var barSpanH: Int = WindowManager.LayoutParams.WRAP_CONTENT
    private var barGravity: Int = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    // True while the window is expanded to full-screen for a modal (menu / folder
    // / rename / reorder). While expanded, config-driven resizes are deferred so
    // they don't shrink the window mid-modal (which broke the cursor→tile mapping
    // and offset dock magnification after a reorder drop).
    @Volatile private var modalExpanded: Boolean = false
    private var pendingResizeConfig: DockConfig? = null

    var isShowing: Boolean = false
        private set

    /**
     * Adds the overlay view to the secondary display's WindowManager.
     * @throws WindowManager.BadTokenException or SecurityException if the device
     * refuses the overlay (caller should fall back to [DockPresentation]).
     */
    fun show() {
        if (isShowing) return
        // Read the configured edge once, up front, so we can anchor the
        // window's gravity to it. Reconcile re-creates this overlay when the
        // position pref changes, so reading once at show() is sufficient.
        val dockConfig = runCatching {
            runBlocking { PortalPadApp.instance.prefs.dockConfig.first() }
        }.getOrNull()
        val dockPosition = dockConfig?.position ?: DockPosition.BOTTOM
        val widthPct = (dockConfig?.dockWidthPct ?: 92).coerceIn(40, 100) / 100f
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // PIN the dock's layout density to the 213-dpi baseline (1.33) — the same
        // constant DockBar.BASELINE_DENSITY uses, and the value this display already
        // reports at "Auto" (its physical 100 dpi falls outside the 120..480 accept
        // window, so Auto falls back to 213). Pinning here is what actually makes the
        // dock DPI-proof: the DPI slider drives `wm density` on the VD, and Compose
        // converts EVERY dp/sp with the density it's given. Compensating individual
        // values (dpiComp) only fixed the values it touched — the dock's many raw
        // constants (band thickness +21f, status glyph .size(28.dp), padding 10.dp,
        // the icon-width budget 84f/40f, chevron, badge, tooltip) still tripled at
        // 640 dpi, blowing up the layout. With the density pinned, dpiComp in DockBar
        // self-computes to 1.0 and raw + derived values alike render at a fixed size.
        //
        // NOTE the historical regression this must not reintroduce: content pinned to
        // a density that DISAGREES with the window's pixel scale laid out narrower
        // than the window and sat top-left. That pin was 2.25 (360dpi) against a real
        // 1.33 display. This pins to 1.33 — equal to the real density at Auto — so at
        // Auto the layout is pixel-identical to today, and off-Auto it simply holds
        // that same Auto layout instead of scaling. The window span itself is raw
        // pixels (widthPixels * pct), which the DPI slider never changes.
        val realMetrics = DisplayMetrics().also { display.getRealMetrics(it) }
        @Suppress("UNUSED_VARIABLE")
        val displayDensity = realMetrics.density.coerceAtLeast(0.5f)
        val pinnedDensity = DOCK_BASELINE_DENSITY
        val composeView = ComposeView(displayContext).apply {
            setViewTreeLifecycleOwner(this@DockOverlay)
            setViewTreeSavedStateRegistryOwner(this@DockOverlay)
            setViewTreeViewModelStoreOwner(this@DockOverlay)
            setContent {
                // Lay out at the PINNED baseline density (not the live VD density) so
                // the DPI slider can never resize the dock. fontScale pinned to 1f.
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(pinnedDensity, 1f),
                ) {
                    PortalPadTheme { OverlayContent() }
                }
            }
        }

        val metrics = realMetrics
        val horizontal = dockPosition == DockPosition.BOTTOM || dockPosition == DockPosition.TOP
        // Desktop-style wide bar: the dock window spans dockWidthPct of the
        // display along its main axis (width for BOTTOM/TOP, height for
        // LEFT/RIGHT), leaving a small gap at each edge. The other axis stays
        // WRAP_CONTENT so the bar is only as thick as the icon row. A fixed
        // span (vs WRAP_CONTENT) is what lets a sparse dock still read as a
        // full-width bar. Touches OUTSIDE this window still pass through
        // (FLAG_NOT_TOUCH_MODAL), so apps above the bar remain clickable.
        val spanW = if (horizontal) (metrics.widthPixels * widthPct).toInt()
            else WindowManager.LayoutParams.WRAP_CONTENT
        val spanH = if (!horizontal) (metrics.heightPixels * widthPct).toInt()
            else WindowManager.LayoutParams.WRAP_CONTENT
        val params = WindowManager.LayoutParams(
            spanW,
            spanH,
            overlayHost.windowType,
            // NOT_FOCUSABLE: don't grab IME focus.
            // NOT_TOUCH_MODAL: touches OUTSIDE the dock window pass through to
            //   the app behind (load-bearing — keeps launched apps clickable).
            //
            // We deliberately do NOT set FLAG_LAYOUT_NO_LIMITS here. With it,
            // the dock's content (icon row + its padding/shadow) could be laid
            // out partly OUTSIDE the WRAP_CONTENT window's touchable bounds —
            // so a tap on an icon near the dock's edge would fall outside the
            // window and hit the app behind instead of the icon. Without the
            // flag, the touchable window tightly matches the laid-out dock, so
            // every visible icon is clickable. (The dock has no off-screen
            // animation that would need NO_LIMITS.)
            //
            // NOTE: we do NOT set FLAG_HARDWARE_ACCELERATED here. The dock lives
            // on our app-created virtual display, whose overlay window can't be
            // hardware-accelerated regardless of the flag — so offscreen layers /
            // BlendMode.DstIn / RenderEffect don't work on this surface. Rather
            // than rely on them, the app-icon squircle and its reflection are
            // baked into the icon BITMAP (see AppIcon.bakeSquircle/bakeReflection)
            // and folders clip to SquircleShape — all surface-independent.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Anchor the dock window to the edge the user picked. The window
            // is WRAP_CONTENT (see the note above — a fullscreen overlay would
            // swallow every touch), so its on-screen placement comes entirely
            // from this gravity, NOT from any alignment inside the Compose
            // content. BOTTOM/TOP center horizontally; LEFT/RIGHT center
            // vertically and the DockBar renders a vertical (column) layout.
            // Use absolute LEFT/RIGHT (not START/END) so "left" is physically
            // left regardless of locale layout direction.
            gravity = when (dockPosition) {
                DockPosition.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                DockPosition.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                DockPosition.LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                DockPosition.RIGHT -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
            }
            x = 0
            y = 0
            // Match the reference implementation — ADJUST_PAN so this overlay doesn't trigger a
            // display-wide IME re-layout that would dismiss Chrome's
            // dropdown. (See CursorOverlay for the full explanation.)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            // Refresh-rate hint — matters on the RAW-FALLBACK path, where apps
            // render straight to the physical display and nothing else in the
            // app votes for a mode (VirtualDisplayMirror does on the VD path).
            // Some panels ship a >60Hz mode the system never selects by
            // default (seen in the wild: XBX A01+ with a 90Hz mode sitting
            // unused at 60). Request the highest mode at the CURRENT
            // resolution. Self-gating: an app-created VD reports exactly one
            // mode, so this is a no-op there; a request, not a command — the
            // OEM's mode policy has the final say.
            runCatching {
                val cur = display.mode
                val best = display.supportedModes
                    .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                    .maxByOrNull { it.refreshRate }
                if (best != null && best.refreshRate > cur.refreshRate + 0.5f) {
                    preferredDisplayModeId = best.modeId
                    Log.d(
                        TAG,
                        "Refresh: dock hint ${"%.1f".format(best.refreshRate)}Hz " +
                            "modeId=${best.modeId} on display ${display.displayId} " +
                            "(was ${"%.1f".format(cur.refreshRate)}Hz)",
                    )
                }
            }
        }

        // One-shot notification when this view is ACTUALLY attached to its
        // window. addView schedules the attach on a later traversal, so
        // "addView returned" ≠ "window exists" — the cursor z-order reassert
        // must key on the real attach (registered BEFORE addView so a
        // synchronous attach can't slip past; isAttachedToWindow re-checked
        // after as a belt).
        var attachFired = false
        fun fireAttached() {
            if (attachFired) return
            attachFired = true
            onWindowAttached?.invoke()
        }
        composeView.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    fireAttached()
                }
                override fun onViewDetachedFromWindow(v: View) {}
            },
        )
        windowManager.addView(composeView, params)
        if (composeView.isAttachedToWindow) fireAttached()
        liveParams = params
        barSpanW = spanW
        barSpanH = spanH
        barGravity = params.gravity
        // Backdrop blur for the macOS frosted-glass effect. Only available on
        // Android 12+ and only honored when the OEM ships with cross-window
        // blurs enabled (Pixel yes; some Samsung firmware no). Silently a
        // no-op elsewhere — the translucent fill alone still looks good.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { params.blurBehindRadius = 40 }
            runCatching {
                params.flags = params.flags or
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                windowManager.updateViewLayout(composeView, params)
            }
        }
        view = composeView
        isShowing = true
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        Log.d(TAG, "Dock overlay added to display $displayId (type=${overlayHost.windowType} a11y=${overlayHost.isAccessibilityOverlay})")

        // The dock's on-screen rect for the quick-settings flyout to anchor against
        // is now published by the glass bar itself (DockBar, via onGloballyPositioned),
        // so it reports the VISIBLE bar — not this overlay window, which is taller
        // than the bar because of the transparent pop-out headroom above it.

        // Live-update the dock WINDOW size + position when the user changes width
        // or position in Workspace. (Style/icon/label/scale are already live —
        // they're read inside the Compose content via collectAsState. Only the
        // window bounds — span + gravity — are set imperatively here, so they
        // need this observer to update without recreating the overlay.)
        sizeObserverScope?.cancel()
        sizeObserverScope = CoroutineScope(Dispatchers.Main).also { obs ->
            obs.launch {
                PortalPadApp.instance.prefs.dockConfig.collect { cfg ->
                    applyWindowSizeAndPosition(cfg)
                    // Keep the zone highlight (if showing) in sync with live edits.
                    dockZoneCfg = cfg
                    updateDockZoneHighlight()
                }
            }
            obs.launch {
                PortalPadApp.instance.dockCustomizationActive.collect { active ->
                    dockZonePreviewActive = active
                    updateDockZoneHighlight()
                }
            }
        }
    }

    // Detection-zone highlight shown on the external display while the Dock
    // Customization page is open (mirrors the top bar's zone preview). Drawn as a
    // thin colored band at the dock's edge, spanning revealZoneWidthPct of it.
    @Volatile private var dockZonePreviewActive = false
    private var dockZoneCfg: DockConfig? = null
    private var dockZoneHighlightView: android.view.View? = null
    private var dockZoneHighlightParams: WindowManager.LayoutParams? = null

    private fun updateDockZoneHighlight() {
        if (!dockZonePreviewActive) {
            dockZoneHighlightView?.let { v -> runCatching { windowManager.removeView(v) } }
            dockZoneHighlightView = null
            dockZoneHighlightParams = null
            return
        }
        val cfg = dockZoneCfg ?: runCatching {
            runBlocking { PortalPadApp.instance.prefs.dockConfig.first() }
        }.getOrNull() ?: return
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        val pos = cfg.position
        val horiz = pos == DockPosition.BOTTOM || pos == DockPosition.TOP
        val zoneThick = cfg.revealZoneHeightPx.coerceIn(2, 200)
        val spanPct = cfg.revealZoneWidthPct.coerceIn(10, 100) / 100f
        val bandLen = if (horiz) (metrics.widthPixels * spanPct).toInt()
            else (metrics.heightPixels * spanPct).toInt()
        val w = if (horiz) bandLen else zoneThick
        val h = if (horiz) zoneThick else bandLen
        val gravity = when (pos) {
            DockPosition.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            DockPosition.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            DockPosition.LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
            DockPosition.RIGHT -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }
        runCatching {
            val existing = dockZoneHighlightView
            if (existing == null) {
                val v = android.view.View(displayContext).apply {
                    setBackgroundColor(cfg.zoneHighlightColor.toInt())
                }
                val lp = WindowManager.LayoutParams(
                    w.coerceAtLeast(1), h.coerceAtLeast(1),
                    overlayHost.windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply { this.gravity = gravity; x = 0; y = 0 }
                windowManager.addView(v, lp)
                dockZoneHighlightView = v
                dockZoneHighlightParams = lp
            } else {
                existing.setBackgroundColor(cfg.zoneHighlightColor.toInt())
                dockZoneHighlightParams?.let { p ->
                    p.width = w.coerceAtLeast(1); p.height = h.coerceAtLeast(1)
                    p.gravity = gravity
                    windowManager.updateViewLayout(existing, p)
                }
            }
        }.onFailure { Log.w(TAG, "updateDockZoneHighlight failed", it) }
    }

    /** Recompute the dock window's span + gravity from [cfg] and apply live. */
    private fun applyWindowSizeAndPosition(cfg: DockConfig) {
        val composeView = view ?: return
        val p = liveParams ?: return
        // While a modal (incl. reorder) has the window expanded full-screen, don't
        // shrink it — that would desync the cursor→tile mapping (magnify offset).
        // Remember the latest config and apply it when the modal closes.
        if (modalExpanded) {
            pendingResizeConfig = cfg
            return
        }
        runCatching {
            val pos = if (cfg.position != DockPosition.BOTTOM) DockPosition.BOTTOM else cfg.position
            val pct = cfg.dockWidthPct.coerceIn(40, 100) / 100f
            val m = DisplayMetrics().also { display.getRealMetrics(it) }
            val horiz = pos == DockPosition.BOTTOM || pos == DockPosition.TOP
            val newW = if (horiz) (m.widthPixels * pct).toInt() else WindowManager.LayoutParams.WRAP_CONTENT
            val newH = if (!horiz) (m.heightPixels * pct).toInt() else WindowManager.LayoutParams.WRAP_CONTENT
            val newGravity = when (pos) {
                DockPosition.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                DockPosition.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                DockPosition.LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                DockPosition.RIGHT -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
            }
            if (p.width != newW || p.height != newH || p.gravity != newGravity) {
                p.width = newW; p.height = newH; p.gravity = newGravity
                barSpanW = newW; barSpanH = newH; barGravity = newGravity
                windowManager.updateViewLayout(composeView, p)
            }
        }.onFailure { Log.w(TAG, "applyWindowSizeAndPosition failed", it) }
    }

    fun dismiss() {
        if (!isShowing) return
        sizeObserverScope?.cancel(); sizeObserverScope = null
        dockZoneHighlightView?.let { v -> runCatching { windowManager.removeView(v) } }
        dockZoneHighlightView = null; dockZoneHighlightParams = null
        runCatching { searchOverlay?.dismiss() }; searchOverlay = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        _viewModelStore.clear()
        isShowing = false
        PortalPadApp.instance.setDockBandBounds(null)
        com.portalpad.app.service.PortalPadForegroundService.dismissQuickSettingsPanel()
    }

    @Composable
    private fun OverlayContent() {
        val app = PortalPadApp.instance
        val restoreScope = androidx.compose.runtime.rememberCoroutineScope()
        // Poll running windows on this display while in desktop mode, so the
        // dock can show minimized/running apps as restorable tiles. Off (empty)
        // when desktop mode is disabled.
        val runningTasksFlow = remember {
            // Derived from the shared WindowMonitor snapshot (one poll for the app)
            // instead of a private 2s enumeration. closedMinimizedList() is an
            // in-memory registry, not a binder call, so it's read per emission.
            app.windowMonitor.snapshot.map { snap ->
                if (!snap.desktop) {
                    emptyList()
                } else {
                    // Close-minimize model: windows the user minimized-by-closing
                    // (FreeformManager registry: package + bounds, surviving the
                    // task's death). Synthetic registry id as taskId; the restore/
                    // close callbacks branch on isClosedId.
                    val closed = app.freeform.closedMinimizedList().map { w ->
                        com.portalpad.app.data.RunningTask(
                            taskId = w.id,
                            packageName = w.packageName,
                            displayId = display.displayId,
                            bounds = w.bounds,
                        )
                    }
                    // Caption-bar minimize: the OS hides a freeform window
                    // (visible=false) but keeps the task alive — surface those live
                    // hidden windows so they're restorable. REAL task ids, so the
                    // restore/close handlers branch on isClosedId. Exclude launchers,
                    // self, PortalPad's park-minimized set; dedup by package against
                    // the close-model rows.
                    val closedPkgs = closed.map { it.packageName }.toSet()
                    val captionMinimized = snap.tasks.filter { rt ->
                        !rt.visible &&
                            !rt.packageName.contains("launcher", ignoreCase = true) &&
                            !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                            rt.packageName != "com.portalpad.app" &&
                            rt.taskId !in snap.minimizedIds &&
                            rt.packageName !in closedPkgs
                    }
                    closed + captionMinimized
                }
            }.distinctUntilChanged { a, b -> a.map { it.taskId } == b.map { it.taskId } }
        }
        // Live OPEN windows on the external display (desktop mode): live tasks from
        // listExternalTasks(), minus launchers, PortalPad itself, and any parked-
        // minimized tasks. Feeds the open-windows bar (single slot when nothing is
        // minimized). Emits only on task-set change; 2s poll in desktop, 5s off.
        val openWindowsFlow = remember {
            app.windowMonitor.snapshot.map { snap ->
                if (!snap.desktop) {
                    emptyList()
                } else {
                    snap.tasks.filter { rt ->
                        !rt.packageName.contains("launcher", ignoreCase = true) &&
                            !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                            rt.packageName != "com.portalpad.app" &&
                            rt.taskId !in snap.minimizedIds &&
                            rt.visible
                    }
                }
            }.distinctUntilChanged { a, b -> a.map { it.taskId } == b.map { it.taskId } }
        }
        // Whether the external display is showing wallpaper (no VISIBLE user app on
        // it). Unlike openWindowsFlow this is NOT gated on desktop mode and ignores
        // minimized (visible=false) windows, so it reads "wallpaper" correctly in
        // both desktop and single-app extend mode. Drives the dock's pin-on-wallpaper.
        val wallpaperShowingFlow = remember {
            // NOT desktop-gated: reads "wallpaper showing" in both desktop and
            // single-app extend mode, which is why WindowMonitor enumerates tasks
            // in both modes. Ignores minimized (visible=false) windows.
            app.windowMonitor.snapshot.map { snap ->
                snap.tasks.none { rt ->
                    rt.visible &&
                        !rt.packageName.contains("launcher", ignoreCase = true) &&
                        !rt.packageName.contains("nexuslauncher", ignoreCase = true) &&
                        rt.packageName != "com.portalpad.app"
                }
            }.distinctUntilChanged()
        }
        DockBar(
            dockFlow = app.prefs.dockConfig,
            onLaunchEntry = { entry ->
                val component = if (entry.isActivity) "${entry.packageName}/${entry.componentName}"
                else app.packageManager.getLaunchIntentForPackage(entry.packageName)
                    ?.component?.flattenToString() ?: return@DockBar
                if (app.access.isReady) {
                    // Launch Settings on the external display (glasses) like any other
                    // app. (Previously pinned to the phone as a freeform window for
                    // touch+IME; trying on-glasses per request — launchSettingsOnPhone
                    // is kept below as an easy fallback.)
                    // Record as the last foreground app (for the Auto Launch
                    // "Last app" restore). Skip phone shortcuts.
                    if (!entry.isShortcut) {
                        restoreScope.launch { runCatching { app.prefs.setLastForegroundApp(entry) } }
                    }
                    launchFromDock(app, component)
                }
            },
            onOpenSearch = { launchPhoneSearch(startVoice = false) },
            onOpenVoiceSearch = { launchPhoneSearch(startVoice = true) },
            onModalOpen = { open -> setModalExpanded(open) },
            runningTasksFlow = runningTasksFlow,
            onRestoreTask = { task ->
                restoreScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val m = DisplayMetrics().also { display.getRealMetrics(it) }
                    if (app.freeform.isClosedId(task.taskId)) {
                        // Close-minimize model: "restore" RELAUNCHES the app into a
                        // freeform window at its saved bounds (fresh start — in-app
                        // state isn't preserved). task.taskId is the registry id.
                        runCatching {
                            app.freeform.restoreClosed(task.taskId, display.displayId, m.widthPixels, m.heightPixels)
                        }
                    } else {
                        // Caption-bar-minimized LIVE window: bring it back to the front
                        // via the proven launcher-launch path, which un-hides it. NOTE:
                        // whether bring-to-front reliably un-minimizes an OS-minimized
                        // freeform window is OEM-dependent — verify on-device; if it
                        // doesn't, fall back to relaunching the package.
                        runCatching { app.freeform.bringToFront(task) }
                    }
                }
            },
            onCloseTask = { task ->
                restoreScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (app.freeform.isClosedId(task.taskId)) {
                        // Already closed; just forget the registry entry so it drops
                        // out of the menu (task.taskId is the registry id).
                        runCatching { app.freeform.forgetClosed(task.taskId) }
                    } else {
                        // Live caption-bar-minimized window: close the actual task.
                        runCatching { app.freeform.close(task.taskId) }
                    }
                }
            },
            openWindowsFlow = openWindowsFlow,
            wallpaperShowingFlow = wallpaperShowingFlow,
            onFocusTask = { task ->
                restoreScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    // Bring the live window to the front on the external display via the
                    // proven launcher-launch path (am task move-to-front doesn't work).
                    runCatching { app.freeform.bringToFront(task) }
                }
            },
            onMinimizeTask = { task ->
                restoreScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    // Same close-minimize model as the minimized bar, so the window
                    // drops into the minimized list (registry: package + bounds).
                    runCatching {
                        app.freeform.minimizeByClose(task.taskId, task.packageName, task.bounds)
                    }
                }
            },
            onCloseOpenTask = { task ->
                restoreScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { app.freeform.close(task.taskId) }
                }
            },
            onArrangeWindows = {
                // Glasses toast directs the user to the phone, then launch the window-
                // ordering screen (ArrangeWindowsActivity) on the phone — the same proven
                // path the top bar's "Arrange in order" uses.
                runCatching {
                    com.portalpad.app.presentation.GlassesToast.show(
                        app, display, "Order your windows on your phone", 3500L,
                    )
                }
                runCatching {
                    val opts = android.app.ActivityOptions.makeBasic()
                        .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
                    app.startActivity(
                        android.content.Intent(
                            app, com.portalpad.app.ui.settings.ArrangeWindowsActivity::class.java,
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        opts.toBundle(),
                    )
                }
            },
            onArrangeBlocked = {
                runCatching {
                    com.portalpad.app.presentation.GlassesToast.show(
                        app, display, "Need at least 2 windows to arrange", 3000L,
                    )
                }
            },
        )
    }

    /** The on-display app-search overlay, kept so we can dismiss a prior one
     *  before opening another and clean it up when the dock is dismissed. */
    private var searchOverlay: SearchOverlay? = null

    /** Interactive window-limit manager popup (replaces the old cap toast). */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Show the interactive window-limit popup on the external display: lists the
     * open external windows with a Close button each, so the user can free a slot
     * and re-open. Replaces any prior instance. Closing runs off the main thread.
     */
    private fun showWindowLimitManager(app: PortalPadApp, max: Int) {
        // A heavy Compose overlay added to the virtual display reliably hung/killed
        // the foreground-service process on-device (UI thread blocked → process
        // restart → "trackpad/black → home" flicker). So instead: show a brief
        // toast on the glasses telling the user to look at the phone, and launch a
        // real Activity on the PHONE that lists the open windows to close. Phone
        // activities render reliably and have real touch input.
        runCatching {
            com.portalpad.app.presentation.GlassesToast.show(
                app, display,
                "Window limit reached ($max) — manage windows on your phone",
                3500L,
            )
        }
        runCatching {
            // Force the activity onto the PHONE (default display 0). Without this,
            // it inherits the launch display from the caller context (DockOverlay
            // runs on the EXTERNAL display), so it opened ON the external display —
            // covering it and blacking out the wallpaper backdrop behind the
            // floating windows. setLaunchDisplayId(0) pins it to the phone.
            val opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
            app.startActivity(
                android.content.Intent(app, com.portalpad.app.ui.settings.WindowLimitActivity::class.java)
                    .putExtra("max", max)
                    // NEW_TASK only — with WindowLimitActivity's own taskAffinity it
                    // lands in an isolated task on the phone. We must NOT use
                    // CLEAR_TOP here: it was clearing PortalPad's WallpaperActivity
                    // (same default package affinity), blacking out the external
                    // display's backdrop while the floating windows stayed.
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                opts.toBundle(),
            )
            Log.d(TAG, "window-limit: toast on disp ${display.displayId} + launched WindowLimitActivity on phone")
        }.onFailure { Log.e(TAG, "window-limit activity launch failed", it) }
    }

    /** Dismiss the on-display app-search overlay if showing (used by Home). */
    fun dismissSearchOverlay() {
        runCatching { searchOverlay?.dismiss() }; searchOverlay = null
    }

    /**
     * Expand the dock window to full-screen (so the long-press menu / folder
     * popup, which use fillMaxSize, have room to render as a real modal), or
     * restore it to the bar size. While expanded we also drop NOT_TOUCH_MODAL
     * so the full-screen modal captures taps anywhere (its scrim dismisses on
     * outside tap); restored, NOT_TOUCH_MODAL returns so apps above the bar stay
     * clickable. Kept NOT_FOCUSABLE throughout (taps arrive via injected cursor
     * clicks; avoids reintroducing IME-on-display issues).
     */
    fun setModalExpanded(expanded: Boolean) {
        val v = view ?: return
        val p = liveParams ?: return
        modalExpanded = expanded
        if (expanded) {
            p.width = WindowManager.LayoutParams.MATCH_PARENT
            p.height = WindowManager.LayoutParams.MATCH_PARENT
            p.gravity = Gravity.TOP or Gravity.START
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        } else {
            p.width = barSpanW
            p.height = barSpanH
            p.gravity = barGravity
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        runCatching { windowManager.updateViewLayout(v, p) }
        // The cursor must stay above the now-fullscreen modal.
        if (expanded) com.portalpad.app.service.PortalPadForegroundService.raiseCursor()
        // Apply any config-driven resize that was deferred while expanded.
        if (!expanded) {
            pendingResizeConfig?.let { cfg ->
                pendingResizeConfig = null
                applyWindowSizeAndPosition(cfg)
            }
        }
    }

    /**
     * Launch the phone-side [SearchActivity]. Search input (keyboard, mic,
     * permission prompts) all happen on the phone where they work reliably; the
     * chosen app launches on the external display via the normal path. This
     * replaces the old on-display search overlay + keyboard relay, which fought
     * cross-display IME focus.
     */
    private fun launchPhoneSearch(startVoice: Boolean) {
        val app = PortalPadApp.instance
        // Tell the user on the glasses where the action went — tapping Search/Mic
        // launches a phone-side activity, which briefly perturbs the display
        // (cursor may stall for a moment). The message is shown BEFORE the launch
        // so it's drawn ahead of that churn. For voice, the wording depends on
        // whether mic permission still needs granting.
        runCatching {
            val msg = if (!startVoice) {
                "Opening search on your phone…"
            } else {
                val granted = app.checkSelfPermission(
                    android.Manifest.permission.RECORD_AUDIO,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) "Listening — check your phone…"
                else "Grant mic permission on your phone"
            }
            val dur = if (startVoice && app.checkSelfPermission(
                    android.Manifest.permission.RECORD_AUDIO,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) 4500L else 3000L
            com.portalpad.app.presentation.GlassesToast.show(app, display, msg, dur)
        }
        runCatching {
            // Pin the launch to the PHONE (display 0). Without an explicit launch
            // display the activity inherits PortalPad's external-display task and
            // lands on the glasses; the whole point here is to keep search input
            // on the phone (the glasses just get the toast above).
            val opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(0)
                .toBundle()
            app.startActivity(
                android.content.Intent(app, com.portalpad.app.SearchActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(com.portalpad.app.SearchActivity.EXTRA_START_VOICE, startVoice),
                opts,
            )
        }.onFailure { Log.e(TAG, "phone search launch failed", it) }
    }

    /** True if [pkg] is the Android Settings app (covers the common AOSP/OEM id;
     *  most OEMs, incl. Samsung, keep com.android.settings for the main app). */
    @Suppress("unused")
    private fun isSettingsPackage(pkg: String): Boolean =
        pkg == "com.android.settings"

    /**
     * Launch Settings as a FREEFORM (floating) window on the PHONE (display 0)
     * rather than on the external display. The remote lives on display 0 and the
     * glasses mirror it, so a fullscreen Settings would cover the remote; a
     * freeform window floats over it instead. Uses the same shell path + display-
     * change suppression the dock's connectivity icons use for on-phone settings.
     */
    @Suppress("unused")
    private fun launchSettingsOnPhone(app: PortalPadApp, component: String) {
        com.portalpad.app.service.PortalPadForegroundService.suppressDisplayChanges(3000)
        Thread {
            // Launch by ACTION, not component. Launching the Settings launcher
            // activity by component (-n com.android.settings/.Settings) opened a
            // small/compact freeform window on some OEMs; the action form (the
            // same one the Wi-Fi/Bluetooth icons use) resolves to the main entry
            // the system sizes as a normal freeform window.
            val ok = runCatching {
                val out = app.access.execShell(
                    "am start --display 0 --windowingMode 5 -a android.settings.SETTINGS",
                )
                !out.contains("Error:", ignoreCase = true) &&
                    !out.contains("Exception", ignoreCase = true)
            }.getOrDefault(false)
            if (!ok) {
                // Fallback: plain launch (lands fullscreen on the phone).
                runCatching {
                    app.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }.start()
    }

    /**
     * Launch a dock entry. In desktop-windows mode with "open in window" on,
     * this opens the app as a freeform window cascaded on the display; the
     * shell calls are run off the main thread. Otherwise it falls back to the
     * normal fullscreen launch on the display.
     */
    private fun launchFromDock(app: PortalPadApp, component: String) {
        app.markExternalLaunch()
        val desktop = runCatching {
            runBlocking {
                app.prefs.bool(
                    com.portalpad.app.data.PreferencesRepository.Keys.DESKTOP_MODE_ENABLED,
                    default = false,
                ).first()
            }
        }.getOrDefault(false)
        val asWindow = desktop && runCatching {
            runBlocking {
                app.prefs.bool(
                    com.portalpad.app.data.PreferencesRepository.Keys.DOCK_OPENS_FREEFORM,
                    default = true,
                ).first()
            }
        }.getOrDefault(true)

        if (asWindow) {
            Log.d(TAG, "launchFromDock: desktop=$desktop asWindow=true → freeform launch $component on disp ${display.displayId}")
            val metrics = android.util.DisplayMetrics().also {
                @Suppress("DEPRECATION") display.getRealMetrics(it)
            }
            val bounds = com.portalpad.app.data.WindowBounds.cascade(
                app.freeform.nextLaunchIndex(),
                metrics.widthPixels, metrics.heightPixels,
            )
            Thread {
                val ok = app.freeform.launchFreeform(component, display.displayId, bounds)
                if (!ok) {
                    // launchFreeform returns false when the window cap was hit (its
                    // single chokepoint). Show the glasses toast + phone manager.
                    val max = runCatching {
                        runBlocking {
                            app.prefs.int(
                                com.portalpad.app.data.PreferencesRepository.Keys.MAX_WINDOWS, 5,
                            ).first()
                        }
                    }.getOrDefault(5)
                    Log.d(TAG, "launchFromDock blocked (cap) → window-limit (toast + phone) for disp ${display.displayId}")
                    mainHandler.post { showWindowLimitManager(app, max) }
                }
            }.start()
        } else {
            Log.d(TAG, "launchFromDock: desktop=$desktop asWindow=false → startActivityOnDisplay $component on disp ${display.displayId}")
            app.access.startActivityOnDisplay(component, display.displayId)
            // Fullscreen/extend launches don't go through launchFreeform, so the
            // permission-dialog watch was never armed here — a first-run app (e.g.
            // Kindle) could spawn a transparent GrantPermissionsActivity on the
            // external display and black it out, undetected. Arm the shell watch
            // for this launch too, in fullscreen mode so any consent relaunch
            // comes back fullscreen (not as a freeform window).
            runCatching { app.freeform.armDialogShellWatch(component, display.displayId, freeform = false) }
        }
    }

    companion object { private const val TAG = "DockOverlay" }
}
