package com.portalpad.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.portalpad.app.data.AccessMode
import com.portalpad.app.data.PreferencesRepository
import com.portalpad.app.service.ElevatedAccess
import com.portalpad.app.service.RootManager
import com.portalpad.app.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PortalPadApp : Application() {
    val prefs by lazy { PreferencesRepository(this) }
    val signal by lazy { com.portalpad.app.service.SignalMonitor(this) }
    val shizuku by lazy { ShizukuManager(this) }
    val root by lazy { RootManager(this) }
    val media by lazy { com.portalpad.app.service.MediaController(this) }

    /**
     * Desktop-windows (DeX-style) operations — freeform launch, task listing,
     * move/resize/minimize/close. Routes through whatever elevated backend is
     * active (same as [access]). Gated at runtime by the DESKTOP_MODE_ENABLED
     * pref; this object existing is harmless when the feature is off.
     */
    val freeform by lazy { com.portalpad.app.service.FreeformManager { access } }
    val windowMonitor by lazy { com.portalpad.app.service.WindowMonitor(freeform, prefs, appScope) }

    /**
     * Single app-wide InputInjector. Both [TrackpadActivity] (writes cursor moves)
     * and the foreground service's [com.portalpad.app.presentation.CursorOverlay]
     * (renders the cursor on the external display) reference this same instance,
     * so the on-screen cursor stays in sync with the actual injection state.
     */
    val injector: com.portalpad.app.service.InputInjector by lazy {
        com.portalpad.app.service.InputInjector({ access }, this)
    }

    /** Phase 1 physical-mouse capture controller (experimental). */
    val btMouse by lazy { com.portalpad.app.service.BluetoothMouseController(injector) }

    /**
     * Active elevated-access backend for non-click operations (launching apps,
     * sending keys to external display, granting permissions). The user picks
     * Shizuku or Root explicitly in Settings.
     *
     * Note: this is the privilege source. Click routing happens automatically
     * in [clickBackend] based on what's available.
     */
    val access: ElevatedAccess
        get() = when (currentAccessMode) {
            AccessMode.ROOT -> root
            AccessMode.SHIZUKU -> shizuku
            AccessMode.NONE -> shizuku   // fallback for "access" callers — they'll check isReady
        }

    /**
     * True only when the user has explicitly selected a privileged backend
     * (Shizuku or Root) AND that backend is actually ready. Selecting NONE means
     * "no privilege" even if Shizuku happens to be bound in the background — so
     * callers that gate external-display launches use THIS, not `access.isReady`
     * (which, via the NONE→shizuku fallback above, would wrongly report ready
     * whenever Shizuku is bound regardless of the user's choice).
     */
    val hasLaunchPrivilege: Boolean
        get() = when (currentAccessMode) {
            AccessMode.SHIZUKU -> shizuku.isReady
            AccessMode.ROOT -> root.isReady
            AccessMode.NONE -> false
        }

    @Volatile private var currentAccessMode: AccessMode = AccessMode.NONE

    /** Shared Shizuku UserService binder (lazy — only binds when needed). */
    val shizukuClickBackend: com.portalpad.app.service.ShizukuClickBackend by lazy {
        com.portalpad.app.service.ShizukuClickBackend(this)
    }

    /** Shared Root UserService binder via libsu (lazy — only binds when the
     *  user selects Root mode and root is granted). */
    val rootClickBackend: com.portalpad.app.service.RootClickBackend by lazy {
        com.portalpad.app.service.RootClickBackend(this)
    }

    /**
     * The bound privileged backend for the CURRENT access mode, or null if the
     * current mode has no bound backend ready. Used by AirGlassesSession and
     * the trusted-VD wrap gate so they work over root or Shizuku transparently.
     */
    val activeBoundBackend: com.portalpad.app.service.BoundShellBackend?
        get() = when (currentAccessMode) {
            AccessMode.SHIZUKU -> shizukuClickBackend
            AccessMode.ROOT -> rootClickBackend
            AccessMode.NONE -> null
        }

    /**
     * The trusted-VirtualDisplay session. Process-scoped (NOT per-service-instance)
     * so the single app-owned VD handle persists across foreground-service
     * recreation. If this lived on the service, a service restart while the
     * process survived would start a fresh session with no knowledge of the prior
     * app-owned VD — which keeps running unreleased and surfaces as a duplicate
     * "PortalPad Session". One owner = the start()/stop() guards always see the
     * live VD, so it's reused or torn down instead of orphaned.
     */
    val airGlassesSession: com.portalpad.app.service.AirGlassesSession by lazy {
        com.portalpad.app.service.AirGlassesSession { activeBoundBackend }
    }

    /** Logcat streamer for the in-app diagnostic viewer. */
    val logcat: com.portalpad.app.diag.LogcatStreamer by lazy {
        com.portalpad.app.diag.LogcatStreamer()
    }

    /**
     * Auto-routes clicks based on AccessMode:
     *
     *   AccessMode.SHIZUKU + UserService ready → ShizukuUserService (best path)
     *   AccessMode.SHIZUKU + UserService not ready, but Shizuku ready → Shell(shizuku)
     *   AccessMode.ROOT + Root ready → Shell(root)
     *   AccessMode.NONE → ShizukuUserService (no working path; fails loudly via not-ready)
     */
    val clickBackend: com.portalpad.app.service.ClickBackend
        get() {
            return when (currentAccessMode) {
                AccessMode.SHIZUKU -> {
                    when {
                        shizukuClickBackend.isReady ->
                            com.portalpad.app.service.ClickBackend.ShizukuUserService(shizukuClickBackend)
                        shizuku.isReady -> com.portalpad.app.service.ClickBackend.Shell(shizuku)
                        else -> com.portalpad.app.service.ClickBackend.ShizukuUserService(shizukuClickBackend)
                    }
                }
                AccessMode.ROOT -> {
                    when {
                        rootClickBackend.isReady ->
                            com.portalpad.app.service.ClickBackend.ShizukuUserService(rootClickBackend)
                        root.isReady -> com.portalpad.app.service.ClickBackend.Shell(root)
                        else -> com.portalpad.app.service.ClickBackend.Shell(root)
                    }
                }
                // Accessibility is de-registered (PortalPad requires Shizuku/Root).
                // NONE no longer has a working click path; default to the Shizuku
                // UserService backend so the app fails loudly via its not-ready
                // path rather than silently routing to a dead accessibility one.
                AccessMode.NONE -> com.portalpad.app.service.ClickBackend.ShizukuUserService(shizukuClickBackend)
            }
        }

    /**
     * Tracks whether any PortalPad activity is currently visible to the user.
     * The foreground service watches this flow to decide whether to show the
     * floating bubble.
     */
    private val _appInForeground = MutableStateFlow(false)
    val appInForeground: StateFlow<Boolean> = _appInForeground.asStateFlow()

    /** True while the active glasses session's VirtualDisplay is app-owned (the
     *  Shizuku-wrapper trusted display that survives Shizuku stopping), false for
     *  the shell-owned path (root, or fallback). Drives the soft Shizuku-off
     *  warning + the DPI-needs-Shizuku hint. */
    private val _sessionAppOwned = MutableStateFlow(false)
    val sessionAppOwned: StateFlow<Boolean> = _sessionAppOwned.asStateFlow()
    fun setSessionAppOwned(value: Boolean) { _sessionAppOwned.value = value }

    /**
     * Fires whenever the set/positions of external windows change via PortalPad's
     * controls (launch, move/resize, maximize, minimize, restore, close). The
     * session snapshotter listens (debounced) so it captures the workspace right
     * after a change settles — instead of blindly polling every few seconds.
     * extraBufferCapacity=1 so tryEmit works from any thread without suspending.
     */
    private val _windowsChanged = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val windowsChanged = _windowsChanged.asSharedFlow()
    fun signalWindowsChanged() { _windowsChanged.tryEmit(Unit) }

    /**
     * True while the user is on the Top Bar customization sub-page, so the top
     * bar overlay shows a translucent highlight of the detection zone (sized to
     * the live config) on the glasses. Set false on leaving the sub-page so the
     * highlight disappears. Preview-only — not persisted.
     */
    private val _zonePreviewActive = MutableStateFlow(false)
    val zonePreviewActive: StateFlow<Boolean> = _zonePreviewActive.asStateFlow()
    fun setZonePreviewActive(active: Boolean) { _zonePreviewActive.value = active }

    // When true, the window-limit manager popup is showing on the external
    // display. Adding/closing windows from it triggers display changes that would
    // otherwise recreate the VD and tear the popup down (the "trackpad → home"
    // flicker), so the display listener is suppressed while this is true.
    private val _windowLimitActive = MutableStateFlow(false)
    val windowLimitActive: StateFlow<Boolean> = _windowLimitActive.asStateFlow()
    fun setWindowLimitActive(active: Boolean) { _windowLimitActive.value = active }

    // When true, the dock overlay opens the first folder on the glasses so the
    // Folder Window Customization page can preview the live style there.
    private val _folderWindowPreviewActive = MutableStateFlow(false)
    val folderWindowPreviewActive: StateFlow<Boolean> = _folderWindowPreviewActive.asStateFlow()
    fun setFolderWindowPreviewActive(active: Boolean) { _folderWindowPreviewActive.value = active }

    /** True while the Settings "Widget Overlay" page is open — PFS summons the
     *  live layer on the external display so backdrop tweaks and widget removals
     *  are visible as they happen (same pattern as the folder-window preview). */
    private val _widgetOverlayPreviewActive = MutableStateFlow(false)
    val widgetOverlayPreviewActive: StateFlow<Boolean> = _widgetOverlayPreviewActive.asStateFlow()
    fun setWidgetOverlayPreviewActive(active: Boolean) { _widgetOverlayPreviewActive.value = active }

    /** True while the widget overlay layer is SHOWING on the external display.
     *  The layer is modal: dock and top-bar reveal are suppressed while it's up
     *  (they'd be unreachable under the full-screen scrim and their hover chrome
     *  read as broken behind it). One tap dismisses the layer and all chrome
     *  behavior returns. */
    private val _widgetOverlayOpen = MutableStateFlow(false)
    val widgetOverlayOpen: StateFlow<Boolean> = _widgetOverlayOpen.asStateFlow()
    fun setWidgetOverlayOpen(open: Boolean) { _widgetOverlayOpen.value = open }

    /** Live query typed in the phone-side widget-search box; the widget picker
     *  on the external display filters on it. The IME lives entirely on the
     *  phone, so this does NOT revive the cross-display IME-focus fight that
     *  killed the old on-display search relay. */
    private val _widgetPickerQuery = MutableStateFlow("")
    val widgetPickerQuery: StateFlow<String> = _widgetPickerQuery.asStateFlow()
    fun setWidgetPickerQuery(q: String) { _widgetPickerQuery.value = q }

    /** Phone-side widget pick (WidgetSearchActivity tap): the flattened
     *  provider ComponentName + the profile user id (profile-aware lookup on
     *  the consuming side). The display picker collects and adds via the same
     *  addWidget path as an on-display tap. */
    private val _widgetPickerPick =
        kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val widgetPickerPick: kotlinx.coroutines.flow.SharedFlow<String> =
        _widgetPickerPick.asSharedFlow()
    fun emitWidgetPick(providerFlat: String) { _widgetPickerPick.tryEmit(providerFlat) }

    /** One-shot "enter edit mode" request for the widget overlay (wheel chip
     *  long-press). The layer collects it while showing; show-path callers set
     *  it just before showing so a fresh layer opens editing. */
    private val _widgetOverlayEnterEdit =
        kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val widgetOverlayEnterEdit: kotlinx.coroutines.flow.SharedFlow<Unit> =
        _widgetOverlayEnterEdit.asSharedFlow()

    /** Consume-once pending flag alongside the tick: a SharedFlow without
     *  replay DROPS emissions made before a collector exists — so a long-press
     *  that OPENED the layer emitted into the void and the layer came up idle,
     *  while an already-open layer (live collector) entered edit fine (field
     *  report of exactly that asymmetry). The freshly-composed layer checks
     *  the flag at init; the flow covers the already-open case. */
    @Volatile private var pendingWidgetOverlayEdit = false
    fun requestWidgetOverlayEdit() {
        pendingWidgetOverlayEdit = true
        _widgetOverlayEnterEdit.tryEmit(Unit)
    }
    fun consumeWidgetOverlayEditRequest(): Boolean =
        pendingWidgetOverlayEdit.also { pendingWidgetOverlayEdit = false }

    /** When set (from the Manage Folders settings screen), the external display
     *  opens THIS real folder with its real contents, live, so reordering/editing
     *  is visible on the display as you do it. Null = not managing a folder. */
    private val _managedFolderId = MutableStateFlow<String?>(null)
    val managedFolderId: StateFlow<String?> = _managedFolderId.asStateFlow()
    fun setManagedFolderId(id: String?) { _managedFolderId.value = id }

    // Preview isolation: while a customization page is open we show ONLY the
    // overlay being edited so its preview is clean. Dock-customization keeps the
    // dock pinned-visible (and the top bar's zone highlight is already off, since
    // only the top-bar page sets zonePreviewActive). Top-bar customization sets
    // zonePreviewActive, which now ALSO suppresses the dock so the zone highlight
    // isn't overtaken by the dock on the glasses.
    private val _dockCustomizationActive = MutableStateFlow(false)
    val dockCustomizationActive: StateFlow<Boolean> = _dockCustomizationActive.asStateFlow()
    fun setDockCustomizationActive(active: Boolean) { _dockCustomizationActive.value = active }

    /**
     * Timestamp (ms) of the most recent app launch onto the external display.
     * refocusGlasses() uses this to skip its wallpaper fallback briefly after a
     * launch — otherwise a refocus that races the launch can wrongly conclude
     * "nothing is running" and slam the wallpaper over the just-opened app.
     */
    @Volatile
    var lastExternalLaunchAt: Long = 0L
    fun markExternalLaunch() { lastExternalLaunchAt = System.currentTimeMillis() }

    /**
     * Components launched onto the external display this session, most-recent
     * last (capped). Flap-recovery uses this to re-home apps the system evicts
     * to the phone when the VD is destroyed on screen-off: after the flap those
     * apps are NOT the focused task (the launcher/lockscreen is), so a bare
     * moveFocusedTaskToDisplay can't find them — we re-focus each recorded app
     * first, then move it.
     */
    val externalAppComponents: MutableSet<String> =
        java.util.Collections.synchronizedSet(LinkedHashSet())

    fun recordExternalApp(component: String) {
        synchronized(externalAppComponents) {
            externalAppComponents.remove(component) // re-insert at end = most recent
            externalAppComponents.add(component)
            while (externalAppComponents.size > 8) {
                val it = externalAppComponents.iterator()
                if (it.hasNext()) { it.next(); it.remove() } else break
            }
        }
    }

    /**
     * Largest plausible pixel height the Remote's volume column has measured.
     * The volume slider is a fillMaxHeight/weight element, so its length tracks
     * the live available height — which transiently drops on a screen-off→on
     * cycle (insets animating in), shrinking the slider to ~half then recovering.
     * Caching the max (survives recreation) lets us pin the column to a stable
     * height so the slider stays full-size through the transition.
     */
    @Volatile
    var remoteVolumeColHeightPx: Int = 0
    fun launchedRecently(windowMs: Long = 3000L): Boolean =
        System.currentTimeMillis() - lastExternalLaunchAt < windowMs

    /**
     * Live id of the currently-attached external display, or null when none.
     * Updated by [com.portalpad.app.service.PortalPadForegroundService] as
     * displays are added or removed.
     */
    private val _externalDisplayId = MutableStateFlow<Int?>(null)
    val externalDisplayId: StateFlow<Int?> = _externalDisplayId.asStateFlow()

    fun setExternalDisplayId(id: Int?) { _externalDisplayId.value = id }

    /** Bumped when the snapshotter detects a MASS window death (LMK reclaim /
     *  crash wave) and holds the previous snapshot instead of overwriting it —
     *  the trackpad's "Restore last session?" offer re-arms on each bump so
     *  the user gets a one-tap path back to the layout the system destroyed. */
    private val _restoreOfferNudge = MutableStateFlow(0)
    val restoreOfferNudge: StateFlow<Int> = _restoreOfferNudge.asStateFlow()
    fun bumpRestoreOfferNudge() { _restoreOfferNudge.value += 1 }

    // The dock's ACTUAL measured band rectangle on the external display, in real
    // display pixels: [left, top, right, bottom]. Published by DockOverlay after
    // each layout so the quick-settings flyout can mirror the dock's exact width
    // and sit just above it edge-to-edge — without re-deriving the dock geometry
    // (which would drift when icon size / labels / width% change). Null = no dock
    // laid out (the flyout then falls back to a default anchor).
    private val _dockBandBounds = MutableStateFlow<IntArray?>(null)
    val dockBandBounds: StateFlow<IntArray?> = _dockBandBounds.asStateFlow()

    fun setDockBandBounds(bounds: IntArray?) { _dockBandBounds.value = bounds }

    /** True while the quick-settings flyout is showing. The dock reads this so it
     *  won't auto-hide out from under an open menu (the menu is anchored to it). */
    private val _quickSettingsOpen = MutableStateFlow(false)
    val quickSettingsOpen: StateFlow<Boolean> = _quickSettingsOpen.asStateFlow()

    fun setQuickSettingsOpen(open: Boolean) { _quickSettingsOpen.value = open }

    /**
     * Authoritatively resolve the external display id that is *live right now*,
     * validating the cached [externalDisplayId] against the actual display list
     * and re-picking if it's gone stale — e.g. after a screen-off VirtualDisplay
     * flap recreates the display with a NEW id (14→15→16) while the cache still
     * holds the dead id or null. Refreshes the cache so every consumer
     * (including [launchAppOnExternal]) sees the fresh value. Returns null only
     * when no external (non-DEFAULT) display is genuinely present, in which case
     * callers must NOT fall back to the phone for an external-intended launch.
     *
     * This is the fix for "after sleep, dock/Home/Back launches land on the
     * phone, and only clearing recents fixes it": the stale cache made the good
     * cross-display path no-op, dropping launches onto the phone. Re-resolving
     * live removes the dependency on process-scoped cached state.
     */
    fun resolveLiveExternalDisplayId(): Int? {
        val dm = getSystemService(android.content.Context.DISPLAY_SERVICE)
            as? android.hardware.display.DisplayManager ?: return externalDisplayId.value
        val liveIds = runCatching { dm.displays.map { it.displayId } }.getOrDefault(emptyList())
        val def = android.view.Display.DEFAULT_DISPLAY
        val cached = externalDisplayId.value
        // 1) Cached id still present and non-default → trust it.
        if (cached != null && cached != def && cached in liveIds) return cached
        // 2) Our wrapped VirtualDisplay id, if it's live.
        val vd = virtualDisplayId
        if (vd != null && vd != def && vd in liveIds) {
            setExternalDisplayId(vd)
            android.util.Log.d("LaunchEntry", "resolveLive: cache stale ($cached) -> live VD $vd")
            return vd
        }
        // 3) Physical external id, if it's live.
        val phys = physicalExternalDisplayId.value
        if (phys != null && phys != def && phys in liveIds) {
            setExternalDisplayId(phys)
            android.util.Log.d("LaunchEntry", "resolveLive: cache stale ($cached) -> live physical $phys")
            return phys
        }
        // 4) Last resort: any live non-default display (typical single-glasses
        //    setups have exactly one). Logged so a mis-pick is visible.
        val anyExternal = liveIds.firstOrNull { it != def }
        if (anyExternal != null) {
            setExternalDisplayId(anyExternal)
            android.util.Log.d("LaunchEntry", "resolveLive: cache stale ($cached) -> any live external $anyExternal")
            return anyExternal
        }
        // 5) Genuinely no external display present.
        if (cached != null) setExternalDisplayId(null)
        return null
    }


    /**
     * Launch an app component on the external display, honoring desktop-windows
     * mode (open as a cascaded freeform window when DOCK_OPENS_FREEFORM) vs a
     * normal fullscreen launch. Shared by the dock and the phone-side app search
     * so both behave identically. Runs shell calls off the main thread.
     *
     * @param component "pkg/cls" or a package's launch component string.
     */
    fun launchAppOnExternal(component: String, forceFullscreen: Boolean = false) {
        val displayId = externalDisplayId.value ?: return
        if (!access.isReady) return
        markExternalLaunch()
        recordExternalApp(component)
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val desktop = runCatching {
                prefs.bool(com.portalpad.app.data.PreferencesRepository.Keys.DESKTOP_MODE_ENABLED, default = false).first()
            }.getOrDefault(false)
            // forceFullscreen: per-assignment override (Home/Back nav buttons
            // assigned to TV-launcher-style apps) — skip the freeform path and
            // take the plain startActivityOnDisplay branch below, which already
            // arms the permission-dialog watch for fullscreen launches.
            val asWindow = !forceFullscreen && desktop && runCatching {
                prefs.bool(com.portalpad.app.data.PreferencesRepository.Keys.DOCK_OPENS_FREEFORM, default = true).first()
            }.getOrDefault(true)
            if (asWindow) {
                val launched = runCatching {
                    val idx = freeform.nextLaunchIndex()
                    val bounds = com.portalpad.app.data.WindowBounds.cascade(idx, 1920, 1080)
                    freeform.launchFreeform(component, displayId, bounds)
                }.getOrDefault(false)
                if (!launched) {
                    // Likely the window cap was reached — surface a toast on the
                    // external display so it's not a silent no-op (matches the
                    // dock path's feedback).
                    val open = freeform.countExternalWindows()
                    val max = runCatching {
                        prefs.int(com.portalpad.app.data.PreferencesRepository.Keys.MAX_WINDOWS, 5).first()
                    }.getOrDefault(5)
                    if (open >= max) {
                        val disp = runCatching {
                            (getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
                                .getDisplay(displayId)
                        }.getOrNull()
                        if (disp != null) {
                            com.portalpad.app.presentation.GlassesToast.show(
                                this@PortalPadApp, disp,
                                "Window limit reached ($max) — close a window first",
                            )
                        }
                    } else {
                        runCatching { access.startActivityOnDisplay(component, displayId) }
                    }
                }
            } else {
                runCatching { access.startActivityOnDisplay(component, displayId) }
                // Extend/fullscreen launches skip launchFreeform, so arm the
                // permission-dialog watch here too (fullscreen mode) — otherwise a
                // first-run app's transparent GrantPermissionsActivity can black out
                // the external display undetected.
                runCatching { freeform.armDialogShellWatch(component, displayId, freeform = false) }
            }
        }
    }

    /**
     * Live id of the **physical** external display (HDMI / DisplayPort /
     * USB-C DP from AR glasses), or null when none attached.
     *
     * Important contrast with [externalDisplayId]: when we wrap a physical
     * display in a trusted Virtual Display, [externalDisplayId] holds the
     * VD's id (because that's what apps need to target with `am start
     * --display`). But for screencap, MediaProjection, and similar
     * SurfaceFlinger-level operations we need the underlying *physical*
     * display id — the VD doesn't appear in SurfaceFlinger's composition
     * graph, only the physical display we mirror to does.
     *
     * Set alongside the VD by PortalPadForegroundService.
     */
    private val _physicalExternalDisplayId = MutableStateFlow<Int?>(null)
    val physicalExternalDisplayId: StateFlow<Int?> = _physicalExternalDisplayId.asStateFlow()

    fun setPhysicalExternalDisplayId(id: Int?) { _physicalExternalDisplayId.value = id }

    /**
     * Id of the trusted VirtualDisplay we wrap the physical glasses display
     * in, when Display Glasses mode is on AND Shizuku UserService is bound.
     * Null when no VD is active. This is what `am start --display N` should
     * target so apps like Chrome run on our trusted display (where injected
     * mouse events are accepted and anchor popups don't dismiss on motion).
     *
     * The PortalPadForegroundService updates this as the VD lifecycle moves.
     * Pre-Step 1, this stayed null; with the VD architecture work, it tracks
     * the live [com.portalpad.app.service.AirGlassesSession.virtualDisplayId].
     */
    private val _virtualDisplayId = MutableStateFlow<Int?>(null)
    val virtualDisplayIdFlow: StateFlow<Int?> = _virtualDisplayId.asStateFlow()

    /** Snapshot accessor (Settings UI uses this for one-shot reads). */
    val virtualDisplayId: Int? get() = _virtualDisplayId.value

    fun setVirtualDisplayId(id: Int?) { _virtualDisplayId.value = id }

    // True while KeyboardRelayActivity is showing. The auto-open poller goes fully
    // dormant while the relay is up, so the relay's own keystroke injection (which
    // briefly bounces focus to the glasses) can't be misread as a fresh field focus
    // and re-trigger the relay — that loop was collapsing the keyboard to one letter
    // at a time. Set by the relay's lifecycle (onResume true / onDestroy false).
    @Volatile var relayOpen: Boolean = false
    /** When the relay last closed — refocusExternalDisplay skips briefly after
     *  (a launcher-intent refocus relaunches the top app; Chrome resets its
     *  omnibox and the suggestion dropdown dies unclickable). */
    // MONOTONIC (elapsedRealtime) — the skip-window math this feeds must
    // survive wall-clock changes like every other interval in the app.
    @Volatile var relayClosedAt = 0L

    // Timestamp (elapsedRealtime) of the last deliberate tap injected onto the
    // glasses via the trackpad. The accessibility detector requires a RECENT tap to
    // open the relay — because on the glasses the only way to focus a field is to
    // tap it. This distinguishes "you tapped a field" (open) from "an app
    // auto-focused a field at launch" (Chrome's omnibox — no tap, so no relay).
    @Volatile var lastGlassesTapAt: Long = 0L

    // Set by the foreground service when a flap auto-recovery kicks off (quick
    // unplug/replug). While this is in the future, the phone's "Restore last
    // session?" popup stays silent — auto-recovery is already bringing the
    // session back on the external display, so asking would offer a choice
    // that's already been made. A cold reconnect (no flap recovery) leaves this
    // stale, so the popup still fires there.
    // MONOTONIC (elapsedRealtime) deadline — wall clock froze this window open
    // for hours after a manual clock change backward (field bug: session
    // snapshots skipped as "recovery in progress" long after recovery ended,
    // even across a service restart, because this field lives on the App).
    @Volatile var flapRecoveryUntilMs: Long = 0L

    /** True while a flap auto-recovery is restoring (or just restored) the session. */
    fun isFlapRecovering(): Boolean = android.os.SystemClock.elapsedRealtime() < flapRecoveryUntilMs

    /** True when the foreground service is running. Used by Settings to show Enable/Disable toggle. */
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()
    fun setServiceRunning(running: Boolean) { _serviceRunning.value = running }

    /**
     * Transient "hide the bubble until the user opens the app again" toggle.
     * Set by the bubble's long-press menu when the user picks "Hide". Reset to
     * false the next time any activity becomes started.
     */
    private val _bubbleSuppressedThisSession = MutableStateFlow(false)
    val bubbleSuppressedThisSession: StateFlow<Boolean> = _bubbleSuppressedThisSession.asStateFlow()

    fun setBubbleSuppressedThisSession(suppressed: Boolean) {
        _bubbleSuppressedThisSession.value = suppressed
    }

    internal val appScope = CoroutineScope(SupervisorJob())

    /**
     * Bring PortalPad's MainActivity to the foreground after the given [delays]
     * (ms). Called from the foreground service's onDestroy on Disable: tearing
     * down the VD migrates whatever app was on it (e.g. Chrome) to display 0,
     * and that migration lands AFTER the service's synchronous bring-forward —
     * so Chrome wins. Running these reasserts from the application scope (which
     * outlives the dying service) lets PortalPad come back to front once the
     * migration has settled.
     */
    fun appScopeReassertMainActivity(delays: List<Long>) {
        appScope.launch {
            for (d in delays) {
                kotlinx.coroutines.delay(d)
                runCatching {
                    startActivity(
                        android.content.Intent(this@PortalPadApp, MainActivity::class.java).addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                        ),
                    )
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        shizuku.init()

        // First-run only: seed a default dock (Google folder + Files + a few hero
        // apps) so a fresh install isn't empty. No-op once any config is saved.
        appScope.launch { runCatching { prefs.seedDefaultDockIfNeeded() } }

        // Watch the access-mode preference so the active backend stays in sync.
        appScope.launch {
            prefs.accessMode.collectLatest { mode ->
                currentAccessMode = mode
                // Access sources are mutually exclusive — when one is selected,
                // tear down the other's bound service so it isn't left running
                // in the background fighting for the same trusted VD.
                when (mode) {
                    AccessMode.ROOT -> {
                        runCatching { shizukuClickBackend.unbind() }
                        if (root.isReady) {
                            root.grantInjectEvents()
                            // Bring up the bound RootUserService for VD/IME/
                            // injection parity with Shizuku. Safe no-op if it
                            // can't start — routing falls back to root shell.
                            rootClickBackend.bind()
                        }
                    }
                    AccessMode.SHIZUKU -> {
                        runCatching { rootClickBackend.unbind() }
                        if (shizuku.isReady) shizukuClickBackend.bind()
                    }
                    AccessMode.NONE -> {
                        runCatching { shizukuClickBackend.unbind() }
                        runCatching { rootClickBackend.unbind() }
                    }
                }
            }
        }

        // Mirror the global vibration-strength pref into the injector so its
        // click haptic can read it synchronously (0 = off).
        appScope.launch {
            prefs.vibrationMs.collectLatest { ms -> injector.vibrationMs = ms }
        }

        // Mirror the verbose-hover-log debug pref into the companion flag that
        // gates the per-frame tooltip/hover traces (off by default for perf).
        appScope.launch {
            prefs.bool(
                com.portalpad.app.data.PreferencesRepository.Keys.VERBOSE_HOVER_LOG,
                default = false,
            ).collectLatest { verboseHoverLog = it }
        }

        // Mirror the IME-on-external pref so repinImePolicy() (called after every
        // click and text key) reads a cached volatile instead of a blocking
        // DataStore read per call.
        appScope.launch {
            prefs.bool(
                com.portalpad.app.data.PreferencesRepository.Keys.IME_ON_EXTERNAL,
                default = false,
            ).collectLatest { injector.imeOnExternalEnabled = it }
        }

        // Mirror the auto-open-relay pref too. It feeds repinImePolicy's matrix;
        // since the FALLBACK change the external display's IME policy is
        // FALLBACK_DISPLAY (phone) in phone-keyboard mode regardless of the
        // relay pref — the old HIDE pin died with keystroke forwarding (it was
        // what hid the relay's own keyboard on every a11y write).
        appScope.launch {
            prefs.bool(
                com.portalpad.app.data.PreferencesRepository.Keys.AUTO_OPEN_RELAY_ON_FIELD,
                default = true,
            ).collectLatest { injector.autoOpenRelayEnabled = it }
        }

        // DeX-style close: closing a window also purges its Recents card.
        // Default ON (SH's requested default) — must match the Settings toggle.
        appScope.launch {
            prefs.bool(
                com.portalpad.app.data.PreferencesRepository.Keys.CLOSE_REMOVES_FROM_RECENTS,
                default = true,
            ).collectLatest { freeform.closeRemovesFromRecents = it }
        }

        // When Shizuku becomes ready, bind the UserService if user has chosen Shizuku
        appScope.launch {
            shizuku.status.collectLatest { status ->
                if (status == com.portalpad.app.shizuku.ShizukuManager.Status.READY
                    && currentAccessMode == AccessMode.SHIZUKU) {
                    shizukuClickBackend.bind()
                }
            }
        }

        registerActivityLifecycleCallbacks(LifecycleTracker())
    }

    /**
     * Tracks whether the user is looking at PortalPad ON THE PHONE. Only activities
     * on the DEFAULT (phone) display count toward foreground — an activity on the
     * external display (e.g. WallpaperActivity, the glasses backdrop) stays "started"
     * the whole session and must NOT pin foreground=true, or pressing Home from the
     * trackpad would never let the floating bubble appear (the bubble is a phone-screen
     * overlay shown when the user leaves the phone-side app while a display is connected).
     */
    private inner class LifecycleTracker : ActivityLifecycleCallbacks {
        // Track started PHONE-display activities by identity.
        private val startedOnPhone = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<Activity, Boolean>(),
        )
        private fun isOnPhoneDisplay(activity: Activity): Boolean = runCatching {
            (activity.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY) ==
                android.view.Display.DEFAULT_DISPLAY
        }.getOrDefault(true)

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {
            if (isOnPhoneDisplay(activity)) {
                startedOnPhone.add(activity)
                _appInForeground.value = true
                // User came back to the app on the phone — clear the transient hide flag.
                _bubbleSuppressedThisSession.value = false
            }
        }
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {
            startedOnPhone.remove(activity)
            _appInForeground.value = startedOnPhone.isNotEmpty()
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            // Defensive: ensure a destroyed activity never lingers in the set.
            startedOnPhone.remove(activity)
            _appInForeground.value = startedOnPhone.isNotEmpty()
        }
    }

    companion object {
        lateinit var instance: PortalPadApp
            private set

        /** Most recent uncaught exception, surfaced in Settings for diagnostics. */
        @Volatile var lastCrashSummary: String? = null
            private set

        /** Gates the per-frame tooltip/hover diagnostic logs (off by default — the
         *  string formatting + logcat I/O is real work during dock interaction).
         *  Flip on via the Diagnostics page only when chasing a hover/tooltip bug. */
        @Volatile var verboseHoverLog: Boolean = false
    }

    /**
     * Hooks uncaught exceptions so a crash on a background thread (e.g. Compose
     * recomposition, click handler) writes a structured log line AND a brief
     * summary the user can see in Settings → System → Diagnostics. The previous
     * default handler is still called afterward so the OS-level crash dialog +
     * Play Store reporting still work.
     */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                android.util.Log.e(
                    "PortalPadCrash",
                    "Uncaught on thread ${thread.name}",
                    throwable,
                )
                lastCrashSummary = buildString {
                    append("${throwable::class.simpleName}: ${throwable.message}\n")
                    throwable.stackTrace.take(6).forEach { frame ->
                        append("  at ${frame.className.substringAfterLast('.')}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})\n")
                    }
                }
            } catch (_: Throwable) { /* never let logging itself crash */ }
            prev?.uncaughtException(thread, throwable)
        }
    }
}
