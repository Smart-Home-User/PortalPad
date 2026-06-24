package com.portalpad.app.ui.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.portalpad.app.data.AppEntry
import com.portalpad.app.data.DockConfig
import com.portalpad.app.data.DockItem
import com.portalpad.app.data.DockPosition
import com.portalpad.app.data.DockSort
import com.portalpad.app.data.RunningTask
import com.portalpad.app.data.FolderAppearance
import com.portalpad.app.data.findFolder
import com.portalpad.app.data.allFoldersFlat
import com.portalpad.app.ui.theme.AbAccent
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimary
import com.portalpad.app.ui.theme.AbPrimaryBright
import com.portalpad.app.ui.theme.AbSurface
import com.portalpad.app.ui.theme.AbSurfaceElevated
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Density baseline used to make the dock's on-screen size independent of the
 * external Display DPI. 213 dpi ≈ density 1.33 is the XREAL-native reference
 * the dock sizes were tuned against; the renderer scales by
 * BASELINE_DENSITY / actualDisplayDensity so a given Dock-settings value looks
 * the same regardless of the Display DPI the user picks.
 */
private const val BASELINE_DENSITY = 1.33f

/** Pixels scrolled per chevron tap on the dock. */
private const val SCROLL_STEP_PX = 360f

/**
 * Dock rendered on the external display. Supports BOTTOM / TOP / LEFT / RIGHT
 * positions. If [DockConfig.autoHideAfterSec] > 0, the dock fades out after that
 * many seconds of no interaction; any touch on the visible dock brings it back.
 */
@Composable
fun DockBar(
    dockFlow: Flow<DockConfig>,
    onLaunchEntry: (AppEntry) -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenVoiceSearch: () -> Unit = {},
    onModalOpen: (Boolean) -> Unit = {},
    // Live running/minimized windows on the external display (desktop mode
    // only; null/empty otherwise). Rendered as a trailing section so the user
    // can tap to restore a minimized window — including apps not pinned to the
    // dock. Deduping against pinned items is intentional-light: a running app
    // also pinned still shows in BOTH places (pinned = launch, running = the
    // live window), which matches how a taskbar + launcher coexist.
    runningTasksFlow: Flow<List<RunningTask>>? = null,
    onRestoreTask: (RunningTask) -> Unit = {},
    onCloseTask: (RunningTask) -> Unit = {},
    // Live OPEN windows on the external display (desktop mode). For now shown in the
    // SAME single bar slot as the minimized bar, but only when nothing is minimized
    // (minimized takes the slot when present). Tap a row to focus (bring to front),
    // with per-row minimize + close. Stacking both bars at once needs a taller
    // popoutHeadroom and is a deliberate follow-up step.
    openWindowsFlow: Flow<List<RunningTask>>? = null,
    // Emits whether the external display is showing wallpaper (no visible user app).
    // Drives "always show dock on wallpaper" — works in both desktop and extend mode.
    wallpaperShowingFlow: Flow<Boolean>? = null,
    onFocusTask: (RunningTask) -> Unit = {},
    onMinimizeTask: (RunningTask) -> Unit = {},
    onCloseOpenTask: (RunningTask) -> Unit = {},
    onArrangeWindows: () -> Unit = {},
    onArrangeBlocked: () -> Unit = {},
) {
    val rawConfig = dockFlow.collectAsState(initial = DockConfig()).value
    // BOTTOM is now the only supported position. TOP collided with the top bar;
    // LEFT/RIGHT were a second-class layout (no pop-out / magnify / pinned
    // widgets / hover) and have been retired. Any value persisted from an older
    // version (TOP/LEFT/RIGHT) falls back to BOTTOM, so existing users migrate
    // automatically and the vertical layout branch below is never reached.
    val config = if (rawConfig.position != DockPosition.BOTTOM)
        rawConfig.copy(position = DockPosition.BOTTOM) else rawConfig
    // Cap running-app tiles so the dock's scroll list stays manageable (pinned
    // items are already capped at MAX_DOCK_ITEMS). Most-recent first.
    val runningTasks = (runningTasksFlow?.collectAsState(initial = emptyList())?.value ?: emptyList())
        .take(8)
    val openTasks = (openWindowsFlow?.collectAsState(initial = emptyList())?.value ?: emptyList())
        .take(12)
    var openFolderStack by remember { mutableStateOf<List<String>>(emptyList()) }
    // Tracks the last managed-folder id we opened (so closing targets the right one).
    var managedFolderIdPrev by remember { mutableStateOf<String?>(null) }
    var editingItem by remember { mutableStateOf<ItemEditTarget?>(null) }
    var closingTask by remember { mutableStateOf<RunningTask?>(null) }
    // Reorder ("wiggle") mode: when active, all top-level dock icons wiggle and a
    // tap picks an icon up (pickedUpId); the next tap on another icon drops it
    // there (reusing config.moveItem). Tapping empty space or Done exits.
    // State lives in the injector (not remember{}) so it survives the dock's
    // remount when the config refreshes after a drop — otherwise the mode reset
    // to false after the first move.
    var reorderMode by com.portalpad.app.PortalPadApp.instance.injector.dockReorderMode
    var pickedUpId by com.portalpad.app.PortalPadApp.instance.injector.dockReorderPickedUpId
    // Remove ("wiggle") mode: tap an icon to remove it; loops until Done.
    var removeMode by com.portalpad.app.PortalPadApp.instance.injector.dockRemoveMode
    // The open-windows / minimized dropdowns are Compose Popups hosted in this
    // dock window. While the window is a WRAP_CONTENT bottom strip the popup has
    // no vertical room above the bar, so its position clamps to the strip top and
    // the gap can't lift it. Track each dropdown's open state and fold it into
    // modalOpen below so the host expands the window to full-screen while a menu
    // is open — same path the long-press/folder modals already use — giving the
    // popup real space to float up above the bar.
    var openMenuExpanded by remember { mutableStateOf(false) }
    var minMenuExpanded by remember { mutableStateOf(false) }
    // Tell the host (DockOverlay) to expand its window to full-screen while any
    // modal is open — the menu/folder/rename use fillMaxSize and need room.
    val modalOpen = editingItem != null || openFolderStack.isNotEmpty() || reorderMode ||
        removeMode || openMenuExpanded || minMenuExpanded
    androidx.compose.runtime.LaunchedEffect(modalOpen) { onModalOpen(modalOpen) }
    // Folder Window Customization preview: while active, open a folder on the
    // external display so its live style is visible. Prefer the user's first real
    // top-level folder; if they have NONE, fall back to a temporary in-memory
    // "Preview" sample so the window can still be styled (chicken-and-egg fix —
    // you couldn't preview a folder window without first creating a folder). The
    // sample is never persisted. Clear when preview ends.
    val folderPreviewActive = com.portalpad.app.PortalPadApp.instance
        .folderWindowPreviewActive.collectAsState().value
    // Folder-window config in this scope, used to pad the preview grid (below).
    val fwCfgForPreview by com.portalpad.app.PortalPadApp.instance.prefs.folderWindowConfig
        .collectAsState(initial = com.portalpad.app.data.FolderWindowConfig())
    val realFirstFolderId = config.items.filterIsInstance<DockItem.Folder>().firstOrNull()?.id
    // Stable temporary sample folder (only used when there's no real folder).
    val previewSampleFolder = remember {
        DockItem.Folder(
            id = "__folder_preview_sample__",
            label = "Preview",
            contents = listOf(
                DockItem.Shortcut(
                    id = "__sample_app_1__", label = "Photos",
                    app = AppEntry(packageName = "com.android.gallery3d", label = "Photos"),
                ),
                DockItem.Shortcut(
                    id = "__sample_app_2__", label = "Browser",
                    app = AppEntry(packageName = "com.android.chrome", label = "Browser"),
                ),
                DockItem.Shortcut(
                    id = "__sample_app_3__", label = "Settings",
                    app = AppEntry(packageName = "com.android.settings", label = "Settings"),
                ),
            ),
        )
    }
    // The id we open for preview: the real folder if present, else the sample.
    val previewFolderId = realFirstFolderId ?: previewSampleFolder.id
    androidx.compose.runtime.LaunchedEffect(folderPreviewActive, previewFolderId) {
        android.util.Log.d("FolderPreview", "effect: active=$folderPreviewActive realFirst=$realFirstFolderId sampleUsed=${realFirstFolderId == null} stack=$openFolderStack")
        if (folderPreviewActive) {
            openFolderStack = listOf(previewFolderId)
        } else if (!folderPreviewActive && openFolderStack == listOf(previewFolderId)) {
            // Preview ended — close only the folder WE opened for preview.
            openFolderStack = emptyList()
        }
    }
    // Manage Folders (settings) opens the ACTUAL folder being edited on the
    // external display — real contents, live — so reordering/editing is visible
    // as you do it. Distinct from the style-preview above (no placeholders).
    val managedFolderId = com.portalpad.app.PortalPadApp.instance
        .managedFolderId.collectAsState().value
    androidx.compose.runtime.LaunchedEffect(managedFolderId) {
        if (managedFolderId != null) {
            openFolderStack = listOf(managedFolderId)
        } else if (openFolderStack.size == 1 && openFolderStack[0] == managedFolderIdPrev) {
            openFolderStack = emptyList()
        }
        managedFolderIdPrev = managedFolderId
    }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val app = com.portalpad.app.PortalPadApp.instance

    // Open the app picker (multi-select add-to-dock flow) on the phone.
    // The dock lives on the glasses; this launches the phone-side picker
    // activity, the established cross-surface pattern for editing the dock.
    val openAddApps: () -> Unit = {
        com.portalpad.app.ui.dock.launchDockPickerOnPhone(ctx, "dock")
    }

    // Track last interaction time for auto-hide
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var visible by remember { mutableStateOf(true) }

    // Preview isolation. While the Dock Customization page is open, keep the dock
    // pinned visible so the user can see their edits live (no auto-hide). While
    // the Top Bar Customization page is open (zonePreviewActive), HIDE the dock so
    // it doesn't overtake the top-bar zone-highlight preview on the glasses.
    val dockCustomizing = com.portalpad.app.PortalPadApp.instance
        .dockCustomizationActive.collectAsState().value
    val topBarPreviewing = com.portalpad.app.PortalPadApp.instance
        .zonePreviewActive.collectAsState().value

    // "Always show dock on wallpaper": pin the dock visible (past the auto-hide
    // timer) whenever the external display is showing wallpaper — i.e. there is no
    // VISIBLE user app on it. Works in BOTH desktop and single-app extend mode, and
    // a fullscreen app (extend mode) or any open/visible window (desktop) is a
    // visible user app → not wallpaper → normal auto-hide. Minimized windows report
    // visible=false, so they count as wallpaper. Replaces the earlier desktop-mode
    // gate, which wrongly disabled the pin in extend mode.
    val alwaysShowOnWallpaper by app.prefs
        .bool(
            com.portalpad.app.data.PreferencesRepository.Keys.ALWAYS_SHOW_DOCK_ON_WALLPAPER,
            default = true,
        ).collectAsState(initial = true)
    val wallpaperShowing = wallpaperShowingFlow?.collectAsState(initial = false)?.value ?: false
    val pinnedToWallpaper = alwaysShowOnWallpaper && wallpaperShowing

    // Reset timer on any config change (e.g. new item added) so the user can see
    // it — UNLESS the top-bar customization page is suppressing the dock, in which
    // case forcing it visible would make it pop up over the top-bar preview.
    LaunchedEffect(config) {
        if (topBarPreviewing) return@LaunchedEffect
        lastInteraction = System.currentTimeMillis()
        visible = true
    }

    // Auto-hide loop: every second, check if we've been idle past the threshold
    LaunchedEffect(config.autoHideAfterSec, dockCustomizing, topBarPreviewing, pinnedToWallpaper) {
        android.util.Log.d(
            "DockPin",
            "effect: always=$alwaysShowOnWallpaper wallpaper=$wallpaperShowing " +
                "pinned=$pinnedToWallpaper autoHide=${config.autoHideAfterSec} " +
                "dockCust=$dockCustomizing topPrev=$topBarPreviewing",
        )
        // Top-bar preview wins: dock stays hidden so the zone preview is clean.
        if (topBarPreviewing) {
            visible = false
            return@LaunchedEffect
        }
        // Dock customization, no auto-hide, OR pinned-to-wallpaper keeps it visible.
        if (dockCustomizing || config.autoHideAfterSec <= 0 || pinnedToWallpaper) {
            visible = true
            return@LaunchedEffect
        }
        while (true) {
            delay(500L)
            val idleSec = (System.currentTimeMillis() - lastInteraction) / 1000
            // Never auto-hide while reordering or removing (the wiggle/Done UI must
            // stay up) or while the quick-settings flyout is open (it's anchored to
            // the dock — hiding the dock out from under it looks broken).
            val newVisible = pinnedToWallpaper || reorderMode || removeMode ||
                com.portalpad.app.PortalPadApp.instance.quickSettingsOpen.value ||
                idleSec < config.autoHideAfterSec
            if (visible && !newVisible) {
                android.util.Log.d(
                    "DockPin",
                    "auto-hiding: idle=${idleSec}s pinned=$pinnedToWallpaper " +
                        "always=$alwaysShowOnWallpaper wallpaper=$wallpaperShowing",
                )
            }
            visible = newVisible
        }
    }

    // When the dock goes away (auto-hide), close the quick-settings flyout with
    // it — otherwise the separate menu window lingers on screen after the dock
    // has slid out, which looks broken. (Full dock teardown is also covered in
    // DockOverlay.dismiss().)
    LaunchedEffect(visible) {
        if (!visible) {
            com.portalpad.app.service.PortalPadForegroundService.dismissQuickSettingsPanel()
        }
    }

    // Cursor-edge reveal: watch the shared injector cursor and reveal dock when
    // cursor enters the edge region for the dock's position. Replaces the old
    // visible trigger strip.
    val externalDisplayId by app.externalDisplayId.collectAsState()
    // Display metrics are FIXED for a given display, but cursor mapping, band
    // detection, icon sizing, and the reveal effect each used to call
    // getRealMetrics() — a binder call + allocation — on EVERY recomposition,
    // i.e. on every cursor sample (up to ~120/s). Fetch once and reuse; only
    // re-fetch if the external display itself changes. (Big win on weak SoCs:
    // removes ~4 IPC calls + DisplayMetrics allocations per cursor frame.)
    val displayMetrics: android.util.DisplayMetrics? = remember(externalDisplayId) {
        val id = externalDisplayId ?: return@remember null
        val dm = app.getSystemService(android.content.Context.DISPLAY_SERVICE)
            as? android.hardware.display.DisplayManager ?: return@remember null
        val display = dm.getDisplay(id) ?: return@remember null
        android.util.DisplayMetrics().also { display.getRealMetrics(it) }
    }
    // Cursor position, GATED + frame-capped BEFORE it reaches composition — this
    // is the main perf lever, since the dock lives on a software-rendered overlay
    // and every recomposition is CPU. A cold flow (built below) collapses the
    // cursor to one OFF sentinel whenever it's far from the dock's edge, so the
    // continuous stream of cursor moves out in the app area no longer recomposes
    // the whole dock. Real positions still flow when the cursor is near the dock
    // band (hover/magnify) or in the reveal strip (auto-hide summon) — the strip
    // covers the full dock thickness + reveal zone, so nothing that used to react
    // stops reacting. Repeats are dropped manually and conflate caps it to ~one
    // update per recomposition.
    val gateDensity = androidx.compose.ui.platform.LocalDensity.current.density
    val cursorPos by remember(
        displayMetrics, config.position, config.iconSizeDp,
        config.labelSizeSp, config.revealZoneHeightPx, gateDensity,
    ) {
        val m = displayMetrics
        if (m == null) {
            app.injector.cursorPosition
        } else {
            val dockThicknessPx = (config.iconSizeDp * 2.2f + config.labelSizeSp + 21f) * gateDensity
            val revealPx = config.revealZoneHeightPx.coerceIn(2, 200).toFloat()
            val stripPx = maxOf(dockThicknessPx, revealPx) + 8f * gateDensity
            val w = m.widthPixels.toFloat()
            val h = m.heightPixels.toFloat()
            val off = Pair(-1_000_000f, -1_000_000f)
            // Gate inside a plain cold flow with MANUAL dedup (rather than
            // distinctUntilChanged, which is an error-level deprecation when applied
            // to a StateFlow): emit the real position only while the cursor is near
            // the dock's edge; everywhere else collapse to a single OFF value and
            // skip repeats, so far-away cursor moves don't recompose the dock.
            // conflate caps it to ~one update per recomposition.
            kotlinx.coroutines.flow.flow {
                var last: Pair<Float, Float>? = null
                app.injector.cursorPosition.collect { p ->
                    val near = when (config.position) {
                        DockPosition.BOTTOM -> p.second >= h - stripPx
                        DockPosition.TOP -> p.second <= stripPx
                        DockPosition.LEFT -> p.first <= stripPx
                        DockPosition.RIGHT -> p.first >= w - stripPx
                    }
                    val gated = if (near) p else off
                    if (gated != last) {
                        last = gated
                        emit(gated)
                    }
                }
            }.conflate()
        }
    }.collectAsState(initial = app.injector.cursorPosition.value)
    // Narrow, INVISIBLE edge-reveal zone: the cursor must reach very close to
    // the screen edge AND be within a centered band along that edge to summon
    // the auto-hidden dock — so it doesn't pop up from drifting near the edge,
    // and only the configured centered band (not the corners) triggers. Nothing
    // is drawn here; it's a pure cursor-position threshold. Thickness + span are
    // now user-configurable (mirroring the top bar's detection zone).
    val edgeThresholdPx = config.revealZoneHeightPx.coerceIn(2, 200)
    val triggerBandFraction = config.revealZoneWidthPct.coerceIn(10, 100) / 100f

    LaunchedEffect(cursorPos, config.position, externalDisplayId, edgeThresholdPx, triggerBandFraction) {
        if (config.autoHideAfterSec <= 0) return@LaunchedEffect
        // While the top-bar page is previewing, the dock stays hidden — don't let
        // an edge-hover summon it back over the zone preview.
        if (topBarPreviewing) return@LaunchedEffect
        val id = externalDisplayId ?: return@LaunchedEffect
        val metrics = displayMetrics ?: return@LaunchedEffect
        val (x, y) = cursorPos
        // Centered band bounds along each axis.
        val wBand = metrics.widthPixels * triggerBandFraction
        val hBand = metrics.heightPixels * triggerBandFraction
        val xLo = (metrics.widthPixels - wBand) / 2f
        val xHi = xLo + wBand
        val yLo = (metrics.heightPixels - hBand) / 2f
        val yHi = yLo + hBand
        val inXBand = x in xLo..xHi
        val inYBand = y in yLo..yHi
        val atEdge = when (config.position) {
            DockPosition.BOTTOM -> y >= metrics.heightPixels - edgeThresholdPx && inXBand
            DockPosition.TOP -> y <= edgeThresholdPx && inXBand
            DockPosition.LEFT -> x <= edgeThresholdPx && inYBand
            DockPosition.RIGHT -> x >= metrics.widthPixels - edgeThresholdPx && inYBand
        }
        if (atEdge) {
            visible = true
            lastInteraction = System.currentTimeMillis()
        }
    }

    val align = when (config.position) {
        DockPosition.BOTTOM -> Alignment.BottomCenter
        DockPosition.TOP -> Alignment.TopCenter
        DockPosition.LEFT -> Alignment.CenterStart
        DockPosition.RIGHT -> Alignment.CenterEnd
    }

    // ── Magnification cursor mapping ──
    // The injected cursor position is in PHYSICAL display pixels; dock icons are
    // laid out in window coordinates. The dock window spans dockWidthPct% of the
    // display, centered, so window-X = displayX - (displayW - windowW)/2. Each
    // magnifiable tile captures its own center via onGloballyPositioned (root
    // coords) and compares against this value to compute its scale. For a
    // vertical (LEFT/RIGHT) dock we map Y instead. The raw float is exposed; a
    // tile with no position yet simply renders at scale 1.
    val isHorizontalDock = config.position == DockPosition.BOTTOM || config.position == DockPosition.TOP
    // Estimated rendered dock thickness (cross-axis), used to gate magnification
    // so it only fires when the cursor is actually over the bar — tightened to
    // hug the icon row closely so hovering just above/below no longer magnifies.
    val dockBandPx: Float = run {
        val displayDensity2 = androidx.compose.ui.platform.LocalDensity.current.density
        // The cursor "on dock band" zone must cover the FULL dock window height,
        // because some controls (the status icons: VPN/BT/Wi-Fi/battery) are pinned
        // to the TOP of the glass band while others (+/search/mic) sit at the
        // bottom. The window's content height is rowMaxH = tileBandDp +
        // popoutHeadroom = iconSize*1.35 + label + 21 + iconSize*0.85. Sizing the
        // band shorter (the old iconSize*1.05) left the top-pinned status icons
        // ABOVE the zone, so hovering on them didn't register (hover/tooltip only
        // fired a little BELOW them). Match the full height; the intentional bottom
        // label-strip exclusion is handled separately by labelZonePx.
        val approxThicknessDp = config.iconSizeDp * 2.2f + config.labelSizeSp + 21f
        approxThicknessDp * displayDensity2
    }
    // Asymmetric bottom exclusion (BOTTOM dock): the lower part of the bar — the
    // label strip down to the screen edge — should NOT magnify, so hovering
    // "under" the icons (around/below the label) doesn't trigger it. We exclude a
    // band of ~label height up from the screen bottom; the TOP boundary is left
    // as-is (top behavior was fine). Mirror for TOP dock (exclude the label strip
    // on its inner side).
    val labelZonePx: Float = run {
        val displayDensity2 = androidx.compose.ui.platform.LocalDensity.current.density
        (config.labelSizeSp + 14f) * displayDensity2
    }
    // TIGHTER band for the bottom controls (+/search/mic). The full dockBandPx is
    // deliberately tall to catch the TOP-pinned status icons, but the controls sit
    // at the very bottom — so on that wide band their tooltips fired from way above
    // the icons. This covers ~one control-tile height above the label strip, so
    // their tooltip/magnify only triggers when the cursor is close to them.
    // TUNABLE: raise the 1.2f to extend the controls' trigger zone higher.
    val controlsBandPx: Float = run {
        val displayDensity2 = androidx.compose.ui.platform.LocalDensity.current.density
        labelZonePx + config.iconSizeDp * 1.2f * displayDensity2
    }
    // Dedicated, TIGHTER band used only to gate ICON MAGNIFY. The full dockBandPx
    // reaches up to the top of the headroom — where the minimized-windows bar now
    // floats — so hovering that bar magnified the app icon beneath it (looked like
    // you were selecting the icon when you meant the bar). Magnify only needs to
    // cover the bottom-anchored icon row, so this caps the zone well below the bar.
    // TUNABLE: the trailing constant extends the magnify zone higher (toward the
    // bar). +5dp over the bare band height so hovering the icon's very TOP still
    // magnifies (the band edge used to land exactly at the icon top, so the top
    // pixel row fell just outside and you had to nudge the cursor down).
    val magnifyBandPx: Float = run {
        val displayDensity2 = androidx.compose.ui.platform.LocalDensity.current.density
        // = tileBandDp (the glass band's visual height) + a 5dp reach so the icon's
        // top edge is inside the zone. Covers the FULL icon row — including the room
        // a magnified (1.35×) icon grows into — and stops just shy of the headroom
        // where the minimized bar floats.
        (config.iconSizeDp * 1.35f + config.labelSizeSp + 21f + 5f) * displayDensity2
    }
    // Dock window height in px, captured from the root Box's layout below. Used to
    // map the cursor's vertical DISPLAY position into the icons' root coordinate
    // space for 2D-proximity hover on the stacked right-side controls. 0 until the
    // first layout pass; 2D hover simply reports no-hover until it's known.
    var rootHeightPx by remember { mutableStateOf(0) }
    // Window (overlay) width in px — used to cap the app lane so the wrapped,
    // centered dock can't grow past the window edge (then the lane scrolls).
    var rootWidthPx by remember { mutableStateOf(0) }
    var cursorOnDockBand = false
    // Same as cursorOnDockBand but using the tighter controlsBandPx (BOTTOM/TOP);
    // falls back to the full band on LEFT/RIGHT docks.
    var cursorOnControlsBand = false
    // Tighter still (magnifyBandPx) — gates app-icon magnify so the minimized bar
    // in the headroom doesn't magnify the icon below it.
    var cursorOnMagnifyBand = false
    val cursorAxisInWindowPx: Float = run {
        val metrics = displayMetrics ?: return@run Float.NaN
        val widthPct = (config.dockWidthPct / 100f)
        if (isHorizontalDock) {
            // Cross-axis (vertical) band check for BOTTOM/TOP docks.
            cursorOnDockBand = when (config.position) {
                DockPosition.BOTTOM ->
                    cursorPos.second >= metrics.heightPixels - dockBandPx &&
                        cursorPos.second <= metrics.heightPixels - labelZonePx
                else /* TOP */ ->
                    cursorPos.second <= dockBandPx &&
                        cursorPos.second >= labelZonePx
            }
            // Tighter band for the bottom controls (close to them, not the whole bar).
            cursorOnControlsBand = when (config.position) {
                DockPosition.BOTTOM ->
                    cursorPos.second >= metrics.heightPixels - controlsBandPx &&
                        cursorPos.second <= metrics.heightPixels - labelZonePx
                else /* TOP */ ->
                    cursorPos.second <= controlsBandPx &&
                        cursorPos.second >= labelZonePx
            }
            // Magnify gate — tighter than the dock band so the cursor must be near
            // the icon row, not up in the headroom where the minimized bar floats.
            cursorOnMagnifyBand = when (config.position) {
                DockPosition.BOTTOM ->
                    cursorPos.second >= metrics.heightPixels - magnifyBandPx &&
                        cursorPos.second <= metrics.heightPixels - labelZonePx
                else /* TOP */ ->
                    cursorPos.second <= magnifyBandPx &&
                        cursorPos.second >= labelZonePx
            }
            // When a modal/reorder is open the host window is expanded to
            // full-width (gravity TOP|START), so there's NO centering inset — the
            // window origin is the display origin. Using the dock-sized inset here
            // shifts the cursor mapping and makes magnify/hit-testing match the
            // wrong tile. Use 0 when expanded.
            val windowW = metrics.widthPixels * widthPct
            val inset = if (modalOpen) 0f else (metrics.widthPixels - windowW) / 2f
            cursorPos.first - inset
        } else {
            // Cross-axis (horizontal) band check for LEFT/RIGHT docks.
            cursorOnDockBand = when (config.position) {
                DockPosition.LEFT -> cursorPos.first <= dockBandPx
                else /* RIGHT */ -> cursorPos.first >= metrics.widthPixels - dockBandPx
            }
            // LEFT/RIGHT controls hover is along a different axis; keep the full band.
            cursorOnControlsBand = cursorOnDockBand
            cursorOnMagnifyBand = cursorOnDockBand
            val windowH = metrics.heightPixels * widthPct
            val inset = if (modalOpen) 0f else (metrics.heightPixels - windowH) / 2f
            cursorPos.second - inset
        }
    }

    // Cross-axis cursor position mapped into the dock window's ROOT coordinate
    // space — the perpendicular partner of cursorAxisInWindowPx, used only by
    // 2D-proximity controls (status icons + bell). For a BOTTOM dock the window
    // bottom coincides with the display bottom, so the window's top in display px
    // is (heightPixels - rootHeightPx); subtracting it converts the cursor's
    // display-Y into the same root-Y space as the icons' positionInRoot().y.
    val cursorCrossInWindowPx: Float = run {
        if (rootHeightPx <= 0 || !isHorizontalDock) return@run Float.NaN
        val metrics = displayMetrics ?: return@run Float.NaN
        when (config.position) {
            DockPosition.BOTTOM ->
                cursorPos.second.toFloat() - (metrics.heightPixels - rootHeightPx).toFloat()
            else /* TOP: window top == display top */ -> cursorPos.second.toFloat()
        }
    }

    // Tell the injector when the cursor is over the VISIBLE dock band, so a
    // right-click is handled in-process (item menu) instead of being injected as
    // a launch tap. Reset to false when the dock isn't visible / on dispose.
    LaunchedEffect(cursorOnDockBand, visible) {
        app.injector.cursorOverDock = cursorOnDockBand && visible
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { app.injector.cursorOverDock = false }
    }
    // DPI-INDEPENDENT SIZING.
    // The dock renders inside a ComposeView on the external display, whose
    // `density` follows the Display DPI setting. A raw .dp value would
    // therefore render bigger at 360 dpi than at 213 dpi — i.e. Display DPI
    // would change the dock size, which we don't want. We compensate by
    // dividing out the display's density against a fixed baseline, so the
    // user's Dock-settings values map to a consistent on-screen size no matter
    // what Display DPI is set to. Dock size is controlled ONLY here.
    val displayDensity = androidx.compose.ui.platform.LocalDensity.current.density
    val dpiComp = (BASELINE_DENSITY / displayDensity).coerceIn(0.4f, 2.5f)
    // "Overall scale" was removed (it only multiplied icon + label size, which have
    // their own sliders). Fixed at 1.0 so those sliders apply directly.
    val scale = 1f
    val labelSizeSp = (config.labelSizeSp * dpiComp * scale)
    // SAFETY CLAMP: no slider combination should ever crowd out the app icons.
    // The dock window is dockWidthPct of the display; the pinned regions (search +
    // mic on the left, connectivity + battery + clock on the right) take a chunk
    // that grows with icon AND label size. We cap the EFFECTIVE icon size so at
    // least ~3 app slots always fit in whatever width remains — otherwise large
    // icon×scale on a narrow dock left no room and showed "just the dock".
    val iconSizeDp = run {
        val raw = (config.iconSizeDp * dpiComp * scale).coerceIn(36f, 220f)
        val dockWindowDp = run {
            val metrics = displayMetrics ?: return@run Float.NaN
            // Convert px → dp using THIS dock's pinned baseline density (the dock's
            // own coordinate space), matching how iconSizeDp is interpreted.
            val widthPx = metrics.widthPixels * (config.dockWidthPct.coerceIn(40, 100) / 100f)
            widthPx / BASELINE_DENSITY
        }
        if (dockWindowDp.isNaN()) raw
        else {
            // Pinned-region budget (dp), in EFFECTIVE icon units: left search+mic
            // (~2.5·icon) + right status (1.7·icon + 84 + 3.2·label) + padding.
            // Reserve room for MIN_APP_SLOTS app icons in the remainder.
            val minAppSlots = 3f
            // Solve: minAppSlots*icon + 2.5*icon + 1.7*icon + 84 + 3.2*label + 40 <= dockWindowDp
            val fixed = 84f + 3.2f * labelSizeSp + 40f
            val perIconUnits = minAppSlots + 2.5f + 1.7f
            val maxIcon = ((dockWindowDp - fixed) / perIconUnits).coerceAtLeast(36f)
            raw.coerceAtMost(maxIcon)
        }
    }

    // macOS-style glassy look:
    //   - Deeply translucent dark fill (lets the app behind subtly show through)
    //   - Hairline white border for the rim-light effect
    //   - Soft drop shadow for depth
    //   - Rounded 32dp pill silhouette
    //   True backdrop frosted-glass blur is handled by DockOverlay's window
    //   params (setBlurBehindRadius on API 31+ when supported by the OEM).
    val dockShape = RoundedCornerShape(config.style.cornerRadiusDp.dp)

    // Custom two-point gradient (colorA → colorB) with user-chosen geometry,
    // type, angle, and balance, from BarStyle (Workspace → Dock Customization).
    val dockGradient = remember(config.style) {
        com.portalpad.app.ui.common.toComposeBrush(config.style)
    }
    // Adaptive status-icon colors: derive light-on-dark or dark-on-light from the
    // dock background's luminance, so the wifi/BT/VPN glyphs, battery, clock, and
    // mic/search stay legible whatever background color the user picks (instead of
    // being hardcoded off-white, which washes out on a light dock).
    val dockBgDark = remember(config.style) {
        val lum = (luminanceOf(Color(config.style.colorAInt())) +
            luminanceOf(Color(config.style.colorBInt()))) / 2f
        lum < 0.5f
    }
    val statusStrong = if (dockBgDark) Color.White else Color(0xFF15131A)
    val statusMuted = if (dockBgDark) AbOnSurfaceMuted else Color(0xFF15131A).copy(alpha = 0.55f)
    // Average dock background color — used by the notification badge to detect a
    // red-ish dock and switch to a high-contrast badge color.
    val dockAvgColor = remember(config.style) {
        val cA = Color(config.style.colorAInt()); val cB = Color(config.style.colorBInt())
        Color((cA.red + cB.red) / 2f, (cA.green + cB.green) / 2f, (cA.blue + cB.blue) / 2f)
    }
    // Theme for the long-press edit popup so it MATCHES the dock instead of the
    // fixed app theme. Background is an OPAQUE copy of the dock gradient (opaque so
    // content behind the menu can't bleed through even if the dock itself is
    // translucent); text/danger colors flip light↔dark from the dock's luminance
    // so they stay legible whatever colors the user picks.
    val dockMenuTheme = remember(config.style) {
        val brush = com.portalpad.app.ui.common.toComposeBrush(config.style, opaque = true)
        DockMenuTheme(
            brush = brush,
            solid = dockAvgColor.copy(alpha = 1f),
            content = if (dockBgDark) Color(0xFFF3F1F7) else Color(0xFF15131A),
            danger = if (dockBgDark) Color(0xFFFF6B6B) else Color(0xFFC62828),
        )
    }
    // ONE dock-wide "active tooltip" holder shared by ALL tooltip-bearing
    // controls. Stores (id, hoverValue) so the CLOSEST control wins — a control
    // claims the slot only if its hover is at least as high as the current
    // holder's. This prevents a neighbor with a wide hover radius (e.g. Wi-Fi)
    // from stealing the tooltip when the cursor is actually on the bell. Cleared
    // on hover-away. Only one tooltip ever shows.
    val dockTooltip = remember { mutableStateOf<Pair<String, Float>?>(null) }
    val bellTooltip = dockTooltip

    val isHorizontal = config.position == DockPosition.BOTTOM || config.position == DockPosition.TOP

    // Fill the wide overlay window along the dock's main axis so the bar reads
    // as a near-full-width (or full-height) desktop-style strip rather than a
    // content-hugging pill. Items inside stay centered. When a modal is open
    // the host window is expanded to full-screen, so fill the whole window then
    // (the menu/folder/rename use fillMaxSize and need the full height).
    val outerModifier = when {
        modalOpen -> Modifier.fillMaxSize()
        isHorizontal -> Modifier.fillMaxWidth()
        else -> Modifier.fillMaxHeight()
    }
    // At rest the bar WRAPS its content and the parent's BottomCenter centers it,
    // so it reads as a trimmed, centered pill (no dead space past the status). The
    // modal overlays (edit popup, reorder/remove banners, folder window) are
    // SIBLINGS at the root box — which goes fullMaxSize on modal — so they get
    // their full-screen space from there. The bar itself must therefore KEEP its
    // normal wrapped width in every state; previously it was forced to fillMaxWidth
    // on modal, which stretched the visible glass to the whole (expanded) window.
    val surfaceSpanModifier = when {
        !isHorizontal -> Modifier.fillMaxHeight()
        else -> Modifier.wrapContentWidth()
    }

    // Pop-out headroom (horizontal dock only): transparent space ABOVE the slim
    // glass bar that a magnified icon rises into. The glass is painted only on
    // the bottom band; the icon content is bottom-anchored so at rest it sits in
    // the glass (unchanged look) and when magnified it grows UP into the empty
    // headroom — the macOS pop-out effect. Vertical docks are unaffected.
    // Headroom = transparent space reserved ABOVE the glass band (for the magnify
    // pop-out and the minimized/open bar). When BOTH bars show, they stack and need
    // more room, so grow the headroom to fit the two-bar stack (2 bars + 6dp gap +
    // 4dp band gap); otherwise keep the original 0.85x so the single-bar/no-bar look
    // is unchanged. The band is bottom-anchored, so extra headroom grows upward.
    // Vertical gap (dp) between the bar stack and the dock band — reserved in the
    // headroom, applied in barTopY, and spanned by the connector stem.
    val barBandGap = 8f
    // --- Bar layout decision (shared by headroom, stack height, render, and stems) ---
    // Estimate each bar's width and place them side-by-side when both fit within the
    // dock band; otherwise stack (open above minimized). bandWidthDp is computed here,
    // not at the render site, because popoutHeadroom below depends on the outcome:
    // side-by-side reserves one bar's height, stacked reserves two.
    val barLayoutDensity = androidx.compose.ui.platform.LocalDensity.current
    val bandWidthDp = with(barLayoutDensity) {
        ((displayMetrics?.widthPixels ?: 1920) *
            (config.dockWidthPct.coerceIn(40, 100) / 100f)).toDp().value
    }
    val barMaxWindows by com.portalpad.app.PortalPadApp.instance.prefs
        .int(com.portalpad.app.data.PreferencesRepository.Keys.MAX_WINDOWS, default = 5)
        .collectAsState(initial = 5)
    val bothBars = openTasks.isNotEmpty() && runningTasks.isNotEmpty()
    val openBarEstDp = barEstWidthDp(
        "${openTasks.size} / $barMaxWindows " +
            if (openTasks.size == 1) "Window open" else "Windows open",
    )
    val minBarEstDp = barEstWidthDp(
        "${runningTasks.size} " +
            if (runningTasks.size == 1) "Window Minimized" else "Windows Minimized",
    )
    // 4dp between the two bars; 24dp total inset keeps the pair off the dock edges.
    val barsSideBySide = bothBars &&
        (openBarEstDp + 4f + minBarEstDp) <= (bandWidthDp - 24f)
    // Two-bar stacked column (open above minimized) only when both show AND they
    // don't fit side-by-side. Drives the extra headroom + stack height below.
    val barsStacked2 = bothBars && !barsSideBySide
    val popoutHeadroom = if (isHorizontal) {
        val oneBarDp = iconSizeDp * 0.50f
        val stackNeededDp = (if (barsStacked2) oneBarDp * 2f + 6f else oneBarDp) + barBandGap
        maxOf(iconSizeDp * 0.85f, stackNeededDp).dp
    } else {
        0.dp
    }
    // View handle so the glass bar can publish its REAL on-screen rect (window
    // origin + bar offset within the window), used to anchor the quick-settings
    // flyout to the visible bar — not the taller overlay window that includes the
    // transparent pop-out headroom above the bar.
    val dockHostView = androidx.compose.ui.platform.LocalView.current

    Box(
        outerModifier.onGloballyPositioned { rootHeightPx = it.size.height; rootWidthPx = it.size.width },
        contentAlignment = align,
    ) {
        if (visible) {
            // The dock "container": for a horizontal dock it's taller than the
            // glass (glass on the bottom band, transparent headroom on top). The
            // glass visuals (gradient + border + shadow + shape) are drawn by a
            // bottom-anchored Box so the visible bar stays slim; the content is
            // NOT clipped to the glass, so magnified icons overflow up.
            Box(
                modifier = Modifier
                    .then(surfaceSpanModifier)
                    .padding(10.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                // Glass bar — bottom-anchored, only as tall as the content band
                // (the container's height minus the headroom on top).
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(top = popoutHeadroom)
                        .onGloballyPositioned { c ->
                            // Publish the glass bar's true screen rect (window
                            // origin + bar offset within the window) so the quick-
                            // settings flyout anchors to the visible bar, not the
                            // taller window that includes the headroom above.
                            runCatching {
                                val pos = c.positionInWindow()
                                val loc = IntArray(2).also { dockHostView.getLocationOnScreen(it) }
                                val left = loc[0] + pos.x.toInt()
                                val top = loc[1] + pos.y.toInt()
                                app.setDockBandBounds(
                                    intArrayOf(left, top, left + c.size.width, top + c.size.height),
                                )
                            }
                        }
                        .shadow(
                            elevation = 16.dp,
                            shape = dockShape,
                            ambientColor = Color.Black.copy(alpha = 0.6f),
                            spotColor = Color.Black.copy(alpha = 0.6f),
                        )
                        .background(dockGradient, dockShape)
                        .border(
                            width = 0.5.dp,
                            color = Color.White.copy(alpha = 0.18f),
                            shape = dockShape,
                        )
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent()
                                    lastInteraction = System.currentTimeMillis()
                                }
                            }
                        },
                )
                // Content layer — fills the full container. The row inside is
                // sized tall enough to hold the pop-out headroom INTERNALLY (with
                // icons bottom-anchored), so a magnified icon grows up into space
                // that's inside the row's own bounds — and therefore inside the
                // fade-mask layer that would otherwise clip it.
                Box(Modifier.then(surfaceSpanModifier)) {
                if (config.position == DockPosition.BOTTOM || config.position == DockPosition.TOP) {
                    val listState = rememberLazyListState()
                    val scrollScope = rememberCoroutineScope()
                    // Asymmetric scroll affordance: a left fade+chevron appears
                    // only when items are scrolled off the left, a right one only
                    // when items remain off the right. canScrollBackward/Forward
                    // are false at the respective ends.
                    val canLeft = listState.canScrollBackward
                    val canRight = listState.canScrollForward
                    // Bound the row height to exactly what one tile needs (icon +
                    // its short reflection + label + vertical padding). Without a
                    // cap, an unexpectedly tall child could let the WRAP_CONTENT
                    // overlay window measure tall, leaving empty space under the
                    // icons. This pins the dock to its natural bar height.
                    // Pinned end regions get a DEFINED width that spans from the
                    // dock edge to the separator, with their content centered
                    // inside — so search/mic (left) and "+" (right) float centered
                    // in those gaps instead of hugging the dock's outer edges.
                    // (Fixed padding alone didn't span the gap, so it never
                    // actually centered.) Width = the pair/tile plus balanced
                    // space on each side.
                    // The glass-band tile height (icon + gap + reflection + label
                    // + padding) — this is the visible bar's content height.
                    // Trimmed from the original 28, then nudged back up slightly.
                    val tileBandDp = iconSizeDp * 1.35f + labelSizeSp + 21f
                    // Pop-out headroom carried INSIDE the row, above the icons, so
                    // a magnified icon grows up into in-bounds space (not clipped
                    // by the fade-mask layer). popoutHeadroom is the same value the
                    // glass uses for its top transparent band, so the glass paints
                    // the bottom (tile) portion and this headroom sits above it.
                    val rowMaxH = (tileBandDp + popoutHeadroom.value).dp
                    // leftPadW: search + mic moved to the right of the "+" button, so
                    // the left region is now just a small pad keeping the left
                    // chevron/separator off the dock edge (not flush). Starting
                    // estimate — tune if the chevron sits too close to / too far from
                    // the edge.
                    val leftPadW = 10.dp
                    // addRegionW = the status region width. It must be WIDE ENOUGH to
                    // contain the status content (cluster + %), so the content sits
                    // within its bounds — that keeps each status icon's drawn position,
                    // touch target, and tooltip anchor all in the same place (clickable
                    // + aligned). Shrinking it below the content width forces the
                    // content to overflow, which decouples draw from hit/anchor and
                    // breaks clicking — so do NOT shrink it. (The earlier "slide mic
                    // under VPN" attempt shrank this and broke status-icon clicks; the
                    // +cluster now sits cleanly to the LEFT of the status instead.)
                    //
                    // Coefficient bumped (1.82→2.34, +22→+28) to fit the added CELLULAR
                    // glyph in the cluster and to give the battery "%" headroom — at the
                    // old width the last glyph (the % sign) was being clipped by the
                    // region's right edge at the 1.66× battery text size. On-device:
                    // if the % still clips or the cluster looks cramped, raise these.
                    //
                    // Text term: the battery "%" is drawn at 1.66× labelSizeSp and is
                    // the LAST child of the bounded status Row, so it only gets the
                    // leftover width after the connectivity cluster. A 2-digit "NN%"
                    // fit, but "100%" is one glyph wider and the leftover fell short,
                    // clipping the %. An earlier 3.2→4.3 bump wasn't enough on-device;
                    // raised to 6.0 (+ fixed 28→34) for clear 3-digit headroom in all
                    // connectivity states. Scales with the font. On-device: if the %
                    // ever clips again or the cluster looks too wide, adjust here.
                    val addRegionW = (iconSizeDp * 2.34f + 34f + labelSizeSp * 6.0f).dp
                    // Gap from the region's (= dock's) right edge for the cluster (%),
                    // the time, and the date.
                    val statusEndPad = 6.dp
                    // Small leftward shift of the whole status group (icons + time +
                    // bell + date move together as a rigid unit). Uses offset(), which
                    // moves layout placement AND hit-testing together, so it does not
                    // break clicking. Right-edge gap = statusEndPad + |this|.
                    // Old layout nudged the whole status group left off the dock's
                    // right edge. Under the wrapped/centered bar the right gap is set
                    // by statusEndPad (layout), so this offset is 0 — a non-zero
                    // offset() shifts only drawing, not layout, and would desync the
                    // status from the wrapped bar's right edge.
                    val statusGroupShiftX = 0.dp
                    // Glyph-only items (search/mic/+/separator/chevron) are centered
                    // within the GLASS BAND height (not the app-icon row), so they
                    // sit in the vertical middle of the bar. Tying their height to
                    // tileBandDp means they stay centered if the band is trimmed.
                    val bandH = tileBandDp.dp
                    // Right-edge gap: mirror the LEFT edge→separator gap so the
                    // %/time/date sit the same distance from the dock border as the
                    // left separator does. That left gap is:
                    //   leftPadW(10) + rowSpacing(8)  =  18.dp  (to the separator line;
                    //   the first app sits further in, behind the lane's magnify-room).
                    // The status already carries statusEndPad on its right, so the extra
                    // padding added here is (18.dp - statusEndPad) = 12.dp, giving an
                    // 18.dp right gap. Applied in BOTH states so the empty mini dock gets
                    // the same right gap (its left controls get the matching gap via the
                    // leading spacer below). TUNABLE: bump leftPadW+8 if the right edge
                    // reads tighter/looser than the left separator on device.
                    val statusRightPad = (leftPadW + 8.dp - statusEndPad).coerceAtLeast(0.dp)
                    Row(
                        Modifier
                            .height(rowMaxH)
                            .padding(end = statusRightPad),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // The app lane and its left/right boundary slots (separators
                        // or scroll chevrons) only exist when apps are pinned — an
                        // empty dock is just the compact pill: no separators, no lane,
                        // no leftover spacing.
                        if (config.items.isNotEmpty()) {
                        // ── Pinned LEFT: a small spacer so the left chevron/separator
                        // isn't flush against the dock edge. (Search + mic moved to
                        // the right of the "+" button.) Kept just wide enough to give
                        // the chevron breathing room without wasting left width.
                        androidx.compose.foundation.layout.Spacer(
                            Modifier.width(leftPadW),
                        )
                        // Left boundary slot: a back-chevron when there's content
                        // off-screen to the left, otherwise a plain separator line.
                        // Centered in the glass band height.
                        Box(
                            Modifier.height(bandH),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (canLeft) {
                                ScrollEdge(
                                    modifier = Modifier,
                                    left = true,
                                    onClick = {
                                        lastInteraction = System.currentTimeMillis()
                                        scrollScope.launch { listState.animateScrollBy(-SCROLL_STEP_PX) }
                                    },
                                )
                            } else {
                                DockSeparator(iconSizeDp)
                            }
                        }

                        // ── Scrolling MIDDLE: app tiles + running tasks ──
                        var scrollBoxLeftPx by remember { mutableStateOf(Float.NaN) }
                        var scrollBoxWidthPx by remember { mutableStateOf(0f) }
                        // Lane width: the bar now WRAPS content (no weight-fill), so the
                        // lane sizes to its apps — ~0 when empty (compact pill), growing
                        // as apps are pinned — but never past maxLaneW, after which the
                        // LazyRow scrolls instead of the dock overflowing the window.
                        // perItemW is an estimate (icon + label + gap); if the few-apps
                        // spacing looks loose/tight on-device, nudge the 1.5f. maxLaneW
                        // leaves room for chevrons + controls + the status cluster.
                        val laneDensity = androidx.compose.ui.platform.LocalDensity.current
                        val perItemW = (iconSizeDp * 1.5f).dp
                        val laneHPopout = (iconSizeDp * 0.6f).dp
                        val maxLaneW = (with(laneDensity) { rootWidthPx.toDp() } -
                            addRegionW - (iconSizeDp * 4.6f).dp - 70.dp)
                            .coerceAtLeast(perItemW)
                        val naturalLaneW = if (config.items.isEmpty()) 0.dp
                            else perItemW * config.items.size.toFloat() + laneHPopout * 2f
                        val laneW = naturalLaneW.coerceAtMost(maxLaneW)
                        Box(
                            Modifier
                                .width(laneW)
                                .onGloballyPositioned { c ->
                                    scrollBoxLeftPx = c.positionInRoot().x
                                    scrollBoxWidthPx = c.size.width.toFloat()
                                },
                        ) {
                            // Fade the row content itself to transparent at the
                            // edges that have off-screen items, so icons dissolve
                            // into the background instead of hard-clipping. This
                            // masks the CONTENT (no colored overlay), so it blends
                            // regardless of the dock gradient behind it.
                            val fadePx = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
                            // Horizontal grow-space at the row's start/end so the
                            // first/last icons can magnify (pop out) sideways into
                            // transparent space instead of being clipped at the
                            // dock edge — the horizontal analogue of popoutHeadroom.
                            val hPopout = (iconSizeDp * 0.6f).dp
                            // A magnified edge icon would be hard-clipped by the
                            // alpha-compositing layer the fade requires. So: (a) only
                            // composite (and thus clip) when actually fading, and
                            // (b) suppress magnification for the cursor when it's in a
                            // fade zone, so no icon tries to grow into the clip / pop
                            // out from behind the blur.
                            val fadeActive = canLeft || canRight
                            val cursorInFadeZone = run {
                                if (cursorAxisInWindowPx.isNaN() || scrollBoxLeftPx.isNaN()) false
                                else {
                                    val rel = cursorAxisInWindowPx - scrollBoxLeftPx
                                    (canLeft && rel < fadePx) ||
                                        (canRight && rel > scrollBoxWidthPx - fadePx)
                                }
                            }
                            val tileMagnifyOnBand = cursorOnMagnifyBand && !cursorInFadeZone
                            LazyRow(
                                state = listState,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = hPopout,
                                ),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (fadeActive) Modifier.graphicsLayer { alpha = 0.99f }
                                        else Modifier,
                                    )
                                    .drawWithContent {
                                        drawContent()
                                        if (canLeft) {
                                            drawRect(
                                                brush = Brush.horizontalGradient(
                                                    colors = listOf(Color.Transparent, Color.Black),
                                                    startX = 0f, endX = fadePx,
                                                ),
                                                blendMode = BlendMode.DstIn,
                                            )
                                        }
                                        if (canRight) {
                                            drawRect(
                                                brush = Brush.horizontalGradient(
                                                    colors = listOf(Color.Black, Color.Transparent),
                                                    startX = size.width - fadePx, endX = size.width,
                                                ),
                                                blendMode = BlendMode.DstIn,
                                            )
                                        }
                                    }
                                    .padding(bottom = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                itemsIndexed(config.items, key = { _, it -> it.id }) { idx, item ->
                                    DockTile(
                                        item = item,
                                        iconSizeDp = iconSizeDp,
                                        labelSizeSp = labelSizeSp,
                                        onClick = {
                                            lastInteraction = System.currentTimeMillis()
                                            if (reorderMode) {
                                                val picked = pickedUpId
                                                if (picked == null) {
                                                    // Pick this icon up.
                                                    pickedUpId = item.id
                                                } else if (picked == item.id) {
                                                    // Tapped the held icon again → put it back down.
                                                    pickedUpId = null
                                                } else {
                                                    // Drop the held icon at this icon's position.
                                                    val from = config.items.indexOfFirst { it.id == picked }
                                                    val to = config.items.indexOfFirst { it.id == item.id }
                                                    if (from >= 0 && to >= 0) {
                                                        scope.launch { app.prefs.setDockConfig(config.moveItem(from, to)) }
                                                    }
                                                    pickedUpId = null
                                                }
                                            } else if (removeMode) {
                                                // Remove this item and STAY in remove mode (loop).
                                                scope.launch { app.prefs.setDockConfig(config.removeItem(item.id)) }
                                            } else {
                                                when (item) {
                                                    is DockItem.Shortcut -> onLaunchEntry(item.app)
                                                    is DockItem.Folder -> openFolderStack = listOf(item.id)
                                                }
                                            }
                                        },
                                        onLongPress = { if (!reorderMode && !removeMode) editingItem = ItemEditTarget(item, idx) },
                                        magnifyCursorPx = cursorAxisInWindowPx,
                                        magnifyOnBand = tileMagnifyOnBand,
                                        magnifyHorizontal = isHorizontalDock,
                                        wiggling = reorderMode || removeMode,
                                        pickedUp = reorderMode && pickedUpId == item.id,
                                        labelColor = statusStrong,
                                    )
                                }
                                // (Minimized windows are no longer shown as tiles
                                //  here — a compact chip above the right chevron
                                //  opens the phone restore launcher instead.)
                            }
                        }

                        // Right boundary slot: a forward-chevron when there's content
                        // off-screen to the right, otherwise a separator.
                        Box(
                            Modifier.height(bandH),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (canRight) {
                                ScrollEdge(
                                    modifier = Modifier,
                                    left = false,
                                    onClick = {
                                        lastInteraction = System.currentTimeMillis()
                                        scrollScope.launch { listState.animateScrollBy(SCROLL_STEP_PX) }
                                    },
                                )
                            } else {
                                DockSeparator(iconSizeDp)
                            }
                        }
                        } // end: app lane + boundary slots (only when apps exist)
                        // Controls + status share horizontal space: the status group is
                        // shifted RIGHT so its BLUETOOTH icon (2nd connectivity glyph)
                        // centers over the MIC button. They live in different vertical
                        // bands (controls sit at the bottom, the status connectivity row
                        // at the top), so they overlap without colliding; the bell/date
                        // land to the right of the mic. statusOverlapShift = the status
                        // group's left edge (= the key icon). Derivation, in icon units:
                        //   mic center   = 2.2*icon + 16   (3 tiles @0.88*icon, 8dp gaps)
                        //   bt center    = shift + 0.63*icon + 2   (key box 0.42*icon,
                        //                  2dp gap, half a bt box)
                        //   bt == mic    →  shift = 1.57*icon + 14
                        // We deliberately use 1.95 (> 1.57) to push the whole status
                        // cluster RIGHT of the mic, so the connectivity/VPN glyphs sit
                        // clear of the mic button below. The bar wraps content and is
                        // centered, so it stays centered with symmetric edge gaps.
                        // TUNABLE: raise 1.95f to move the cluster further RIGHT,
                        // lower it (toward 1.57) to move it back over the mic.
                        // Leading spacer before the controls+status overlap. When apps
                        // are pinned the lane group already provides the left edge gap, so
                        // this is just a hair of internal separation (1dp). When the dock
                        // is EMPTY (mini dock) there's no lane, so this spacer becomes the
                        // left edge gap: leftPadW(10) + rowSpacing(8) = 18.dp to the "+"
                        // button — matching the 18.dp right gap (statusRightPad) so the
                        // mini dock is symmetric.
                        androidx.compose.foundation.layout.Spacer(
                            Modifier.width(if (config.items.isNotEmpty()) 1.dp else leftPadW)
                        )
                        val statusOverlapShift = (iconSizeDp * 1.95f + 14f).dp
                        Box(Modifier.height(rowMaxH)) {
                        Box(
                            Modifier.align(Alignment.BottomStart).height(rowMaxH),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            // "+" plus the search/mic actions, all matched to the "+"
                            // tile size (iconSizeDp * 0.88) with even 8dp gaps.
                            androidx.compose.foundation.layout.Row(
                                Modifier.align(Alignment.BottomCenter).height(bandH),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AddTile(
                                    iconSizeDp = iconSizeDp * 0.88f, labelSizeSp = labelSizeSp, onClick = openAddApps,
                                    hoverCursorPx = cursorAxisInWindowPx,
                                    hoverOnBand = cursorOnControlsBand,
                                    hoverHorizontal = isHorizontalDock,
                                    tooltipHolder = dockTooltip,
                                )
                                SearchTile(
                                    iconSizeDp * 0.88f, Icons.Default.Search, "Search apps",
                                    onClick = { lastInteraction = System.currentTimeMillis(); onOpenSearch() },
                                    hoverCursorPx = cursorAxisInWindowPx,
                                    hoverOnBand = cursorOnControlsBand,
                                    hoverHorizontal = isHorizontalDock,
                                    tooltip = "Search apps",
                                    tintColor = statusStrong,
                                    tooltipHolder = dockTooltip,
                                    tooltipId = "search",
                                )
                                SearchTile(
                                    iconSizeDp * 0.88f, Icons.Default.Mic, "Voice search",
                                    onClick = { lastInteraction = System.currentTimeMillis(); onOpenVoiceSearch() },
                                    hoverCursorPx = cursorAxisInWindowPx,
                                    hoverOnBand = cursorOnControlsBand,
                                    hoverHorizontal = isHorizontalDock,
                                    tooltip = "Voice search",
                                    tintColor = statusStrong,
                                    tooltipHolder = dockTooltip,
                                    tooltipId = "mic",
                                )
                            }
                            // (Date + bell moved to the right status region, stacked
                            //  under the time — see below. The minimized-windows bar is
                            //  rendered at the dock-content level instead of here, so it
                            //  centers to the DOCK rather than these left controls.)
                        }
                        // ── Status group, pulled LEFT to overlap the controls ──
                        androidx.compose.foundation.layout.Column(
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(start = statusOverlapShift)
                                .wrapContentWidth()
                                .height(rowMaxH),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (popoutHeadroom > 0.dp) {
                                androidx.compose.foundation.layout.Spacer(
                                    Modifier.height(popoutHeadroom),
                                )
                            }
                            Box(
                                Modifier.wrapContentWidth().height(bandH),
                            ) {
                                // Status cluster, right-anchored. The column WRAPS to its
                                // widest row (the connectivity + battery cluster), so it
                                // sits snug after the controls with no empty slot; the
                                // time/date/bell right-align to that same edge. This box
                                // wraps its content (no fillMaxWidth) so the connectivity
                                // Row's true width sets the cluster width — which also
                                // means the battery "%" measures unbounded and can't be
                                // clipped by a fixed region budget.
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 6.dp, end = statusEndPad),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    androidx.compose.foundation.layout.Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        // Gap between the connectivity cluster and the battery.
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        DockConnectivity(
                                            iconSizeDp = iconSizeDp,
                                            strongColor = statusStrong,
                                            mutedColor = statusMuted,
                                            hoverCursorPx = cursorAxisInWindowPx,
                                            hoverOnBand = cursorOnDockBand,
                                            hoverHorizontal = isHorizontalDock,
                                            crossAxisCursorPx = cursorCrossInWindowPx,
                                        )
                                        DockBattery(
                                            labelSizeSp = labelSizeSp,
                                            iconSizeDp = iconSizeDp,
                                            strongColor = statusStrong,
                                            mutedColor = statusMuted,
                                            hoverCursorPx = cursorAxisInWindowPx,
                                            hoverOnBand = cursorOnDockBand,
                                            hoverHorizontal = isHorizontalDock,
                                            crossAxisCursorPx = cursorCrossInWindowPx,
                                            onClick = {
                                                lastInteraction = System.currentTimeMillis()
                                                launchSystemComponentOnPhone(
                                                    ctx,
                                                    "com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity",
                                                    "com.samsung.android.lool",
                                                    android.content.Intent.ACTION_POWER_USAGE_SUMMARY,
                                                    "Opened battery settings on your phone…",
                                                )
                                            },
                                        )
                                    }
                                }
                                // Time — vertically CENTERED in the band, right-aligned
                                // to the same statusEndPad edge as everything else, so
                                // its right edge (the "PM") sits directly above the
                                // date's last digit below.
                                Box(
                                    Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(end = statusEndPad),
                                    contentAlignment = Alignment.CenterEnd,
                                ) { DockClock(labelSizeSp = labelSizeSp, part = ClockPart.TIME, textColor = statusStrong, timeFormat = config.clockTimeFormat, dateFormat = config.clockDateFormat) }
                                // Date — pinned to the BOTTOM, right-aligned under the
                                // time's "PM" / the % above.
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(bottom = 5.dp, end = statusEndPad),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    DockClock(
                                        labelSizeSp = labelSizeSp,
                                        part = ClockPart.DATE,
                                        textColor = statusStrong,
                                        timeFormat = config.clockTimeFormat,
                                        dateFormat = config.clockDateFormat,
                                    )
                                }
                                // Notification bell + badge + highlight, moved RIGHT to
                                // sit just LEFT of the time/date column with a small gap
                                // so the highlight nearly touches it without overlapping.
                                // The end pad ≈ the clock column width + gap, estimated
                                // from label size. Tunable: smaller = further right
                                // (closer to the clock), larger = further left.
                                val bellNearClockPad = statusEndPad +
                                    (labelSizeSp * 4.5f).dp + 22.dp
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(bottom = 5.dp, end = bellNearClockPad),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    DockNotificationBell(
                                        iconSizeDp = iconSizeDp,
                                        strongColor = statusStrong,
                                        mutedColor = statusMuted,
                                        dockBgColor = dockAvgColor,
                                        hoverCursorPx = cursorAxisInWindowPx,
                                        hoverOnBand = cursorOnDockBand,
                                        hoverHorizontal = isHorizontalDock,
                                        crossAxisCursorPx = cursorCrossInWindowPx,
                                        activeTooltip = bellTooltip,
                                    )
                                }
                            }
                        }
                        } // end: controls + status overlap container
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(vertical = 12.dp, horizontal = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        item(key = "__search__") {
                            SearchTile(
                                iconSizeDp, Icons.Default.Search, "Search apps",
                                onClick = { lastInteraction = System.currentTimeMillis(); onOpenSearch() },
                                tintColor = statusStrong,
                            )
                        }
                        item(key = "__mic__") {
                            SearchTile(
                                iconSizeDp, Icons.Default.Mic, "Voice search",
                                onClick = { lastInteraction = System.currentTimeMillis(); onOpenVoiceSearch() },
                                tintColor = statusStrong,
                            )
                        }
                        item(key = "__search_sep__") { DockSeparator(iconSizeDp, vertical = true) }
                        itemsIndexed(config.items, key = { _, it -> it.id }) { idx, item ->
                            DockTile(
                                item = item,
                                iconSizeDp = iconSizeDp,
                                labelSizeSp = labelSizeSp,
                                onClick = {
                                    lastInteraction = System.currentTimeMillis()
                                    if (reorderMode) {
                                        val picked = pickedUpId
                                        if (picked == null) {
                                            pickedUpId = item.id
                                        } else if (picked == item.id) {
                                            pickedUpId = null
                                        } else {
                                            val from = config.items.indexOfFirst { it.id == picked }
                                            val to = config.items.indexOfFirst { it.id == item.id }
                                            if (from >= 0 && to >= 0) {
                                                scope.launch { app.prefs.setDockConfig(config.moveItem(from, to)) }
                                            }
                                            pickedUpId = null
                                        }
                                    } else if (removeMode) {
                                        scope.launch { app.prefs.setDockConfig(config.removeItem(item.id)) }
                                    } else {
                                        when (item) {
                                            is DockItem.Shortcut -> onLaunchEntry(item.app)
                                            is DockItem.Folder -> openFolderStack = listOf(item.id)
                                        }
                                    }
                                },
                                onLongPress = { if (!reorderMode && !removeMode) editingItem = ItemEditTarget(item, idx) },
                                wiggling = reorderMode || removeMode,
                                pickedUp = reorderMode && pickedUpId == item.id,
                                labelColor = statusStrong,
                            )
                        }
                        item(key = "__add_tile__") {
                            AddTile(iconSizeDp = iconSizeDp, labelSizeSp = labelSizeSp, onClick = openAddApps)
                        }
                        // (Minimized-window tiles removed — see the chip above the
                        //  chevron in the bottom-dock layout. This vertical branch
                        //  is retired/unreached.)
                    }
                }
                if ((runningTasks.isNotEmpty() || openTasks.isNotEmpty()) && isHorizontal) {
                    // Minimized-windows BAR — overlaid on the dock content via
                    // matchParentSize (so it never widens the dock), horizontally
                    // CENTERED to the dock, floating 4dp above the glass band. Tapping
                    // expands a dock-themed dropdown Popup. Restore/close act on the
                    // glasses via the close-registry.
                    // 0.50 (was 0.44) tracks the slightly-larger bar (13sp text +
                    // 0.26x glyph). barTopY pins the bar's BOTTOM 4dp above the band and
                    // derives the top from this height, so a correct barApproxH keeps the
                    // gap below fixed while the extra height grows up into the headroom.
                    val barApproxH = iconSizeDp * 0.50f
                    // Stack height: two bars + 6dp when stacked; a single bar otherwise
                    // (side-by-side, or only one bar present). barTopY pins the stack's
                    // BOTTOM barBandGap above the band, so the dock-to-bars gap is the
                    // same in both layouts and the stack grows upward into the headroom.
                    val stackH = if (barsStacked2) barApproxH * 2f + 6f else barApproxH
                    val barTopY = (popoutHeadroom.value - barBandGap - stackH).coerceAtLeast(0f).dp
                    // zIndex(1f): keep the bar ABOVE the icon content so it always
                    // wins hit-testing. Previously it sat BELOW (zIndex -1) to let a
                    // magnified icon draw over it, but that left the bar's .clickable
                    // intercepted by the band content / a magnified icon — the bar was
                    // untappable. Trade-off: a magnified icon growing directly under the
                    // centered bar now draws behind it, which is rare and minor vs. the
                    // bar not working at all.
                    Box(Modifier.matchParentSize().zIndex(1f)) {
                        // Measured bounds of the bar assembly in the overlay window, so the
                        // dropdowns anchor to the bars' real position (centered, a real gap
                        // above) instead of the unreliable zero-size Popup placeholder.
                        var barAnchorTopPx by remember { mutableStateOf<Float?>(null) }
                        var barAnchorCenterXPx by remember { mutableStateOf<Float?>(null) }
                        // Bar slots as local @Composable functions, placed in a Row
                        // (side-by-side) or Column (stacked) so the heavy parameter lists
                        // aren't duplicated across the two layouts.
                        @Composable
                        fun OpenBar() {
                            OpenWindowsBar(
                                tasks = openTasks,
                                iconSizeDp = iconSizeDp,
                                dockStyle = config.style,
                                maxBarWidthDp = bandWidthDp,
                                contentColor = statusStrong,
                                mutedColor = statusMuted,
                                onFocus = { t -> lastInteraction = System.currentTimeMillis(); onFocusTask(t) },
                                onMinimize = { t -> lastInteraction = System.currentTimeMillis(); onMinimizeTask(t) },
                                onClose = { t -> lastInteraction = System.currentTimeMillis(); onCloseOpenTask(t) },
                                onArrange = { lastInteraction = System.currentTimeMillis(); onArrangeWindows() },
                                onArrangeBlocked = { lastInteraction = System.currentTimeMillis(); onArrangeBlocked() },
                                onInteract = { lastInteraction = System.currentTimeMillis() },
                                anchorTopPx = barAnchorTopPx,
                                anchorCenterXPx = barAnchorCenterXPx,
                                onMenuExpandedChange = { openMenuExpanded = it },
                            )
                        }
                        @Composable
                        fun MinBar(lift: Float) {
                            MinimizedWindowsBar(
                                tasks = runningTasks,
                                iconSizeDp = iconSizeDp,
                                dockStyle = config.style,
                                maxBarWidthDp = bandWidthDp,
                                contentColor = statusStrong,
                                mutedColor = statusMuted,
                                liftAboveDp = lift,
                                onRestore = { t -> lastInteraction = System.currentTimeMillis(); onRestoreTask(t) },
                                onClose = { t -> lastInteraction = System.currentTimeMillis(); onCloseTask(t) },
                                onInteract = { lastInteraction = System.currentTimeMillis() },
                                anchorTopPx = barAnchorTopPx,
                                anchorCenterXPx = barAnchorCenterXPx,
                                onMenuExpandedChange = { minMenuExpanded = it },
                            )
                        }
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = barTopY)
                                .onGloballyPositioned { c ->
                                    val b = c.boundsInWindow()
                                    barAnchorTopPx = b.top
                                    barAnchorCenterXPx = (b.left + b.right) / 2f
                                }
                                .wrapContentWidth(unbounded = true)
                                .wrapContentHeight(unbounded = true),
                        ) {
                            if (barsSideBySide) {
                                // Both present (barsSideBySide implies both bars). Nothing
                                // sits above the minimized bar here, so its dropdown lift = 0.
                                Row(
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    OpenBar()
                                    MinBar(0f)
                                }
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    // OPEN on top, minimized below; each renders only when
                                    // its list is non-empty, so the Column collapses to one.
                                    if (openTasks.isNotEmpty()) OpenBar()
                                    if (runningTasks.isNotEmpty()) {
                                        MinBar(if (openTasks.isNotEmpty()) barApproxH + 6f else 0f)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        } // visible
        // (Removed: the thin colored edge strip that used to show when the
        // dock auto-hid. Per design we now hide the dock completely with no
        // visible edge line. The dock still returns via the cursor-edge reveal
        // LaunchedEffect above — moving the cursor to the dock's edge brings
        // it back — and the auto-hide timer is unchanged.)

        editingItem?.let { target ->
            ItemEditMenu(
                item = target.item,
                index = target.index,
                config = config,
                onDismiss = { editingItem = null },
                onMoveLeft = {
                    if (target.index > 0) {
                        scope.launch { app.prefs.setDockConfig(config.moveItem(target.index, target.index - 1)) }
                    }
                    editingItem = null
                },
                onMoveRight = {
                    if (target.index < config.items.size - 1) {
                        scope.launch { app.prefs.setDockConfig(config.moveItem(target.index, target.index + 1)) }
                    }
                    editingItem = null
                },
                onRemove = {
                    scope.launch { app.prefs.setDockConfig(config.removeItem(target.item.id)) }
                    editingItem = null
                },
                onMoveIntoFolder = { folderId ->
                    scope.launch { app.prefs.setDockConfig(config.moveIntoFolder(target.item.id, folderId)) }
                    editingItem = null
                },
                onRename = {
                    launchRenameOnPhone(ctx, target.item.id, target.item.label)
                    editingItem = null
                },
                onNewFolderWith = {
                    scope.launch { app.prefs.setDockConfig(config.newFolderWithItem(target.item.id)) }
                    editingItem = null
                },
                onReorder = {
                    reorderMode = true
                    pickedUpId = null
                    editingItem = null
                },
                onRemoveMode = {
                    removeMode = true
                    editingItem = null
                },
                theme = dockMenuTheme,
            )
        }

        // Reorder ("drag mode") banner — shows how to use it + a Done button to
        // exit. For a bottom dock, float it just ABOVE the dock band so it doesn't
        // overlap the icons; for other positions, anchor at the top.
        if (reorderMode) {
            val bannerAlign = if (config.position == DockPosition.BOTTOM)
                Alignment.BottomCenter else Alignment.TopCenter
            val bannerPad = with(androidx.compose.ui.platform.LocalDensity.current) {
                (dockBandPx).toDp() + 24.dp
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(bannerAlign)
                    .padding(
                        bottom = if (config.position == DockPosition.BOTTOM) bannerPad else 0.dp,
                        top = if (config.position == DockPosition.BOTTOM) 0.dp else 24.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = AbSurface,
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            if (pickedUpId == null)
                                "Reorder mode — tap an app to pick it up"
                            else
                                "Tap a spot to drop it",
                            color = AbOnSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Surface(
                            color = AbPrimaryBright,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.clickable {
                                reorderMode = false
                                pickedUpId = null
                            },
                        ) {
                            Text(
                                "Done",
                                color = Color.Black,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        // Remove ("remove mode") banner — same layout as the reorder banner but
        // RED-tinted to signal it's destructive. Tap an app to remove it; the mode
        // loops until Done.
        if (removeMode) {
            val bannerAlign = if (config.position == DockPosition.BOTTOM)
                Alignment.BottomCenter else Alignment.TopCenter
            val bannerPad = with(androidx.compose.ui.platform.LocalDensity.current) {
                (dockBandPx).toDp() + 24.dp
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(bannerAlign)
                    .padding(
                        bottom = if (config.position == DockPosition.BOTTOM) bannerPad else 0.dp,
                        top = if (config.position == DockPosition.BOTTOM) 0.dp else 24.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = AbSurface,
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, com.portalpad.app.ui.theme.AbDanger.copy(alpha = 0.6f),
                    ),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            "Remove mode — tap an app to remove it",
                            color = com.portalpad.app.ui.theme.AbDanger,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Surface(
                            color = com.portalpad.app.ui.theme.AbDanger,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.clickable { removeMode = false },
                        ) {
                            Text(
                                "Done",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        // Close-window confirm for a long-pressed running/pinned app tile.
        closingTask?.let { task ->
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val appLabel = remember(task.packageName) {
                runCatching {
                    val pm = ctx.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(task.packageName, 0)).toString()
                }.getOrDefault(task.packageName.substringAfterLast('.'))
            }
            androidx.compose.ui.window.Dialog(onDismissRequest = { closingTask = null }) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(com.portalpad.app.ui.theme.AbSurface)
                        .border(1.dp, com.portalpad.app.ui.theme.AbPrimaryDim, RoundedCornerShape(18.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Close $appLabel?",
                        color = AbOnSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "This closes the app's window on the external display.",
                        color = AbOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AbSurfaceElevated)
                                .clickable { closingTask = null }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Cancel", color = AbOnSurface, fontWeight = FontWeight.SemiBold)
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(com.portalpad.app.ui.theme.AbDanger.copy(alpha = 0.85f))
                                .clickable {
                                    onCloseTask(task)
                                    closingTask = null
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Close", color = AbOnSurface, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Folder popup — resolves the current folder from config by the id at
        // the top of the navigation stack, so edits (add/remove/appearance/
        // nesting) reflect live and back-navigation works for nested folders.
        // Falls back to the temporary preview SAMPLE folder when that id is open
        // (used only when the user has no real folder yet — see preview effect).
        val topId = openFolderStack.lastOrNull()
        val currentFolder = topId?.let { config.items.findFolder(it) }
            ?: topId?.let { if (it == previewSampleFolder.id) previewSampleFolder else null }
        val isSamplePreview = currentFolder?.id == previewSampleFolder.id
        // While the customization page is previewing on the external display, the
        // folder shows a clean, uniform grid of PLACEHOLDER squares (not the real
        // apps) titled "Preview" — so it reads clearly as a style preview rather
        // than a half-real/half-blank folder. The squares fill columns × rows so
        // the layout (count, size, spacing, alignment, labels) previews fully. The
        // squares are drawn contrast-adaptively in FolderPopup so they stay visible
        // on any background color. Never persisted.
        val previewMode = folderPreviewActive && currentFolder != null
        val previewFolder = if (previewMode && currentFolder != null) {
            val count = (fwCfgForPreview.columns.coerceIn(1, 12) * fwCfgForPreview.maxRows.coerceIn(1, 12))
            val fillers = (1..count).map { n ->
                DockItem.Shortcut(
                    id = "__folder_preview_filler_${n}__",
                    label = "App $n",
                    app = AppEntry(packageName = "__preview__${n}", label = "App $n"),
                )
            }
            currentFolder.copy(label = "Preview", contents = fillers)
        } else currentFolder
        if (openFolderStack.isNotEmpty() && previewFolder != null) {
            FolderPopup(
                folder = previewFolder,
                config = config,
                depth = openFolderStack.size,
                previewMode = previewMode,
                onDismiss = { openFolderStack = emptyList() },
                onBack = { openFolderStack = openFolderStack.dropLast(1) },
                onOpenSubFolder = { id -> openFolderStack = openFolderStack + id },
                onLaunch = { entry -> onLaunchEntry(entry); openFolderStack = emptyList() },
                // Sample preview is a throwaway — never persist edits to it. Real
                // folders persist as normal.
                onConfigChange = { newCfg -> if (!isSamplePreview) scope.launch { app.prefs.setDockConfig(newCfg) } },
                recentMillis = { pkg ->
                    runCatching {
                        ctx.packageManager.getPackageInfo(pkg, 0).firstInstallTime
                    }.getOrDefault(0L)
                },
            )
        } else if (openFolderStack.isNotEmpty()) {
            // Folder vanished (e.g. dissolved/deleted) — close.
            openFolderStack = emptyList()
        }
    }
}

/**
 * An edge scroll affordance for the horizontal dock: a translucent gradient
 * fade (so it looks like content continues off-screen) with a tappable chevron
 * the cursor can click to scroll. Shown only on the side that actually has
 * hidden content (see canScrollBackward/Forward at the call site). The caller
 * supplies a [modifier] that aligns this to the correct edge.
 */
@Composable
private fun ScrollEdge(
    modifier: Modifier,
    left: Boolean,
    onClick: () -> Unit,
) {
    // Clean, minimal affordance: a small chevron in a subtle dark circular chip.
    // No wide gradient fade — a horizontal fade can't blend cleanly into the
    // dock's vertical gradient + rounded ends, so we drop it and just show the
    // chevron the cursor can click to scroll.
    Box(
        modifier
            .padding(2.dp)
            .size(28.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (left) Icons.Default.KeyboardArrowLeft else Icons.Default.KeyboardArrowRight,
            contentDescription = if (left) "Scroll left" else "Scroll right",
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(20.dp),
            )
        }
}

/** A circular dock action tile (Search / Mic) shown at the far start of the
 *  dock, ahead of the app tiles. Matches the AddTile sizing language. */
@Composable
private fun SearchTile(
    iconSizeDp: Float,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    hoverCursorPx: Float = Float.NaN,
    hoverOnBand: Boolean = false,
    hoverHorizontal: Boolean = true,
    tooltip: String? = null,
    tintColor: Color = Color.White,
    tooltipHolder: androidx.compose.runtime.MutableState<Pair<String, Float>?>? = null,
    tooltipId: String? = null,
) {
    // Flat utility glyph — no plate or colored circle — so it sits in the dock
    // as a clean member of the icon row (matching the macOS-style flat app
    // icons) rather than a mismatched purple button.
    val (pulseScale, pulseClick) = rememberClickPulse(onClick)
    val (hover, hoverMod) = rememberDockHoverFraction(
        cursorAxisInWindowPx = hoverCursorPx,
        cursorOnBand = hoverOnBand,
        iconSizeDp = iconSizeDp,
        horizontal = hoverHorizontal,
    )
    val hoverScale = 1f + 0.18f * hover
    val iconAlpha = 0.92f + 0.08f * hover
    Box(
        Modifier
            .size(iconSizeDp.dp)
            .then(hoverMod)
            .graphicsLayer { scaleX = pulseScale * hoverScale; scaleY = pulseScale * hoverScale }
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { pulseClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tintColor.copy(alpha = iconAlpha),
            modifier = Modifier.size((iconSizeDp * 0.6f).dp),
        )
        if (tooltip != null) {
            val showTip = rememberTooltipSlot(tooltipHolder, tooltipId ?: tooltip, hover)
            DockTooltip(
                tooltip, visible = showTip, labelSizeSp = 11f,
                // The "+" pill is shorter than this icon tile, so its tooltip sits
                // higher. Pull this tooltip up a little to match the "Add apps"
                // spacing — but only modestly, so it keeps a visible gap below the
                // icon (a larger raise put it touching the icon's bottom edge).
                gapDp = 7f - (iconSizeDp * 0.20f),
            )
        }
    }
}

/** Thin divider separating the Search/Mic actions from the app tiles. Renders
 *  as a short vertical line in the horizontal dock, or a short horizontal line
 *  in the vertical (LEFT/RIGHT) dock. */
@Composable
private fun DockSeparator(iconSizeDp: Float, vertical: Boolean = false) {
    val len = (iconSizeDp * 0.6f).dp
    Box(
        Modifier
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .then(
                if (vertical) Modifier.size(width = len, height = 1.dp)
                else Modifier.size(width = 1.dp, height = len),
            )
            .background(Color.White.copy(alpha = 0.20f)),
    )
}

/** A "+" tile shown at the end of the dock for adding apps. macOS-style
 *  squircle icon with an "Add" label underneath, matching the other tiles. */
@Composable
private fun AddTile(
    iconSizeDp: Float,
    labelSizeSp: Float,
    onClick: () -> Unit,
    hoverCursorPx: Float = Float.NaN,
    hoverOnBand: Boolean = false,
    hoverHorizontal: Boolean = true,
    tooltipHolder: androidx.compose.runtime.MutableState<Pair<String, Float>?>? = null,
) {
    // Pill shape: keep the width but shrink the HEIGHT, and round the ends fully
    // so the short sides become semicircles → a horizontal pill rather than a
    // square tile.
    val pillW = iconSizeDp.dp
    val pillH = (iconSizeDp * 0.62f).dp
    val corner = pillH / 2f
    val (pulseScale, pulseClick) = rememberClickPulse(onClick)
    val (hover, hoverMod) = rememberDockHoverFraction(
        cursorAxisInWindowPx = hoverCursorPx,
        cursorOnBand = hoverOnBand,
        iconSizeDp = iconSizeDp,
        horizontal = hoverHorizontal,
    )
    val hoverScale = 1f + 0.18f * hover
    val borderAlpha = 0.30f + 0.45f * hover   // brighten the outline on hover
    val glyphAlpha = 0.8f + 0.2f * hover
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(pillW)
                .height(pillH)
                .then(hoverMod)
                .graphicsLayer { scaleX = pulseScale * hoverScale; scaleY = pulseScale * hoverScale }
                .clip(RoundedCornerShape(corner))
                .border(
                    width = 1.5.dp,
                    color = Color.White.copy(alpha = borderAlpha),
                    shape = RoundedCornerShape(corner),
                )
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { pulseClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add apps or folders",
                tint = Color.White.copy(alpha = glyphAlpha),
                modifier = Modifier.size((iconSizeDp * 0.42f).dp),
            )
            val showAddTip = rememberTooltipSlot(tooltipHolder, "add", hover)
            DockTooltip("Add apps or folders", visible = showAddTip, labelSizeSp = 11f)
        }
    }
}

/**
 * Shared single-tooltip arbitration with "closest wins". Given the dock-wide
 * [holder] (id + that control's hover), a unique [id], and this control's
 * [hover], this control claims the slot only when its hover is high AND at least
 * as high as the current holder's — so the glyph actually under the cursor wins
 * over a neighbor whose wide hover radius also reads moderately high. Releases
 * on hover-away. Returns whether THIS control's tooltip should show. When
 * [holder] is null, falls back to a plain hover check.
 */
@Composable
private fun rememberTooltipSlot(
    holder: androidx.compose.runtime.MutableState<Pair<String, Float>?>?,
    id: String,
    hover: Float,
    showThreshold: Float = 0.55f,
    releaseThreshold: Float = 0.4f,
    // A challenger can only STEAL the slot from a different holder if its hover
    // beats the holder's by at least this margin. Makes the held tooltip sticky
    // so near-ties don't flip (e.g. the bell sits directly under Wi-Fi and their
    // horizontal hover values track each other closely; without a margin, a hair
    // off the bell let Wi-Fi tie-steal the slot). 0 = pure closest-wins.
    stealMargin: Float = 0.12f,
): Boolean {
    if (holder == null) return hover > showThreshold
    // Raw-hover trace (throttled to a meaningful band) so we can see what EACH
    // control's hover reads at a given cursor position — including ones not high
    // enough to claim (e.g. wifi reaching over onto the bell).
    androidx.compose.runtime.LaunchedEffect(hover) {
        if (com.portalpad.app.PortalPadApp.verboseHoverLog && hover > 0.25f) {
            android.util.Log.d(
                "PortalPadTooltipHover",
                "id=$id hover=${"%.2f".format(hover)}",
            )
        }
    }
    androidx.compose.runtime.LaunchedEffect(hover) {
        val cur = holder.value
        if (hover < releaseThreshold && cur?.first == id) {
            if (com.portalpad.app.PortalPadApp.verboseHoverLog) android.util.Log.d(
                "PortalPadTooltip",
                "RELEASE id=$id hover=${"%.2f".format(hover)} (release=$releaseThreshold)",
            )
            holder.value = null
        } else if (hover > showThreshold) {
            when {
                // Claim an empty slot, or refresh our own current hold.
                cur == null || cur.first == id -> {
                    holder.value = id to hover
                }
                // Steal from a DIFFERENT holder only if we clearly beat them.
                hover >= cur.second + stealMargin -> {
                    if (com.portalpad.app.PortalPadApp.verboseHoverLog) android.util.Log.d(
                        "PortalPadTooltip",
                        "STEAL id=$id hover=${"%.2f".format(hover)} " +
                            "from=${cur.first}/${"%.2f".format(cur.second)} (margin=$stealMargin)",
                    )
                    holder.value = id to hover
                }
                // Hovered enough but blocked by a stickier holder.
                else -> {
                    if (com.portalpad.app.PortalPadApp.verboseHoverLog) android.util.Log.d(
                        "PortalPadTooltip",
                        "BLOCKED id=$id hover=${"%.2f".format(hover)} " +
                            "by=${cur.first}/${"%.2f".format(cur.second)}",
                    )
                }
            }
        }
    }
    return holder.value?.first == id
}

/**
 * Tactile click feedback for the fixed dock controls (search / mic / +), which
 * don't get cursor-proximity magnification. Returns a scale state plus an
 * onClick wrapper: each click fires a quick one-shot "pulse" (scale down then
 * spring back). We use a click-triggered pulse rather than a press-state squish
 * because the injected cursor click is near-instant — a press/release squish
 * would be too brief to see, whereas a one-shot pulse always plays a visible
 * ~180ms animation on every click. GPU-only (graphicsLayer scale).
 */
@Composable
private fun rememberClickPulse(onClick: () -> Unit): Pair<Float, () -> Unit> {
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    val scope = rememberCoroutineScope()
    val trigger: () -> Unit = {
        scope.launch {
            scale.snapTo(0.84f)
            scale.animateTo(
                1f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.45f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                ),
            )
        }
        onClick()
    }
    return scale.value to trigger
}

/**
 * Cursor-proximity magnification for a dock tile (macOS-style). The tile
 * captures its own center along the dock's main axis (root coords) and scales
 * up as [cursorAxisInWindowPx] approaches it, easing back to 1.0 with distance.
 * Scale is applied via graphicsLayer (GPU only — no layout reflow), so it's
 * cheap and never reflows the row. Each tile reads only its own distance, so
 * the first/last tiles magnify exactly like the middle ones (no edge cutoff).
 *
 * @param horizontal true for BOTTOM/TOP docks (compare X), false for LEFT/RIGHT (compare Y)
 * @param iconSizeDp used to size the falloff radius and as the scale pivot
 */
@Composable
private fun Modifier.dockMagnify(
    cursorAxisInWindowPx: Float,
    cursorOnBand: Boolean,
    iconSizeDp: Float,
    horizontal: Boolean,
): Modifier {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var centerPx by remember { mutableStateOf(Float.NaN) }
    // Falloff radius: kept close to the tile spacing (row spacedBy 10dp, tiles
    // ~iconSize wide) so the cursor strongly magnifies essentially the nearest
    // tile and lets neighbors fade fast, instead of 3–4 tiles all growing at
    // once. The old 1.6 reached well past neighbors, so two adjacent tiles grew
    // toward each other and overlapped (their combined growth exceeded the 10dp
    // gap). Paired with a slightly lower maxScale, 1.1 keeps a gentle gradient
    // while the per-tile growth stays inside the gap. (Pass B — tunable.)
    val radiusPx = with(density) { (iconSizeDp * 1.1f).dp.toPx() }
    val maxScale = 1.25f

    // Only magnify when the cursor is actually over the bar (cursorOnBand) — not
    // hovering above/below it, where the icon isn't clickable. Otherwise the
    // icons would appear interactive in a zone where taps don't land.
    val target = if (!cursorOnBand || cursorAxisInWindowPx.isNaN() || centerPx.isNaN()) {
        1f
    } else {
        val d = kotlin.math.abs(cursorAxisInWindowPx - centerPx)
        if (d >= radiusPx) 1f
        else {
            val t = 1f - (d / radiusPx)                  // 0..1, 1 at cursor
            val eased = t * t * (3f - 2f * t)            // smoothstep
            1f + (maxScale - 1f) * eased
        }
    }
    val animScale by animateFloatAsState(targetValue = target, label = "dockMagnify")

    return this
        .onGloballyPositioned { coords ->
            val pos = coords.positionInRoot()
            val size = coords.size
            centerPx = if (horizontal) pos.x + size.width / 2f
            else pos.y + size.height / 2f
        }
        .graphicsLayer {
            scaleX = animScale
            scaleY = animScale
            // Bottom pivot for a horizontal dock so the icon grows UPWARD, rising
            // out of the glass into the transparent headroom (macOS pop-out).
            // Center pivot for vertical docks.
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, if (horizontal) 1f else 0.5f)
        }
}

/**
 * 2D cursor-proximity magnify for folder-window tiles — the macOS-dock pop, but
 * ELLIPTICAL (here equal radii) for a grid rather than the dock's 1D horizontal
 * falloff. The folder window only shows while a modal is open, which expands the
 * host window to full-display, so the injected cursor's DISPLAY px and the tile's
 * positionInRoot() share one coordinate space — no inset conversion needed. Pass
 * NaN cursor coords (e.g. in preview) to disable. Center pivot so the tile grows
 * in place; maxScale is modest so growth stays within the cell gap + row spacing.
 */
@Composable
private fun Modifier.folderTileMagnify(
    cursorXPx: Float,
    cursorYPx: Float,
    tileSizeDp: Float,
): Modifier {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var cxPx by remember { mutableStateOf(Float.NaN) }
    var cyPx by remember { mutableStateOf(Float.NaN) }
    val radiusPx = with(density) { (tileSizeDp * 1.0f).dp.toPx() }
    val maxScale = 1.10f
    val target = if (cursorXPx.isNaN() || cursorYPx.isNaN() || cxPx.isNaN() || cyPx.isNaN()) {
        1f
    } else {
        val nx = (cursorXPx - cxPx) / radiusPx
        val ny = (cursorYPx - cyPx) / radiusPx
        val d = kotlin.math.sqrt(nx * nx + ny * ny)
        if (d >= 1f) {
            1f
        } else {
            val t = 1f - d
            val eased = t * t * (3f - 2f * t) // smoothstep
            1f + (maxScale - 1f) * eased
        }
    }
    val animScale by animateFloatAsState(targetValue = target, label = "folderTileMagnify")
    return this
        .onGloballyPositioned { coords ->
            val pos = coords.positionInRoot()
            val size = coords.size
            cxPx = pos.x + size.width / 2f
            cyPx = pos.y + size.height / 2f
        }
        .graphicsLayer {
            scaleX = animScale
            scaleY = animScale
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
        }
}

/**
 * Opens this tile's menu ([onMenu]) when an in-process dock right-click tick
 * fires AND the cursor is over THIS tile. Uses the same cursor-axis math as
 * dockMagnify (reliable with the injected cursor). This is how dock right-click
 * works without injection: rightClick() emits the tick + skips injection when
 * the cursor is over the dock; the tile under the cursor opens its own menu.
 */
@Composable
private fun Modifier.dockRightClickTarget(
    cursorAxisInWindowPx: Float,
    cursorOnBand: Boolean,
    iconSizeDp: Float,
    horizontal: Boolean,
    onMenu: () -> Unit,
): Modifier {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var centerPx by remember { mutableStateOf(Float.NaN) }
    var halfPx by remember { mutableStateOf(with(density) { (iconSizeDp / 2f).dp.toPx() }) }
    val tick = com.portalpad.app.PortalPadApp.instance.injector.dockRightClickTick
    LaunchedEffect(tick, cursorOnBand, cursorAxisInWindowPx, centerPx, halfPx) {
        tick.collect {
            if (cursorOnBand && !cursorAxisInWindowPx.isNaN() && !centerPx.isNaN() &&
                kotlin.math.abs(cursorAxisInWindowPx - centerPx) <= halfPx
            ) {
                onMenu()
            }
        }
    }
    return this.onGloballyPositioned { coords ->
        val pos = coords.positionInRoot()
        val size = coords.size
        centerPx = if (horizontal) pos.x + size.width / 2f else pos.y + size.height / 2f
        halfPx = (if (horizontal) size.width else size.height) / 2f
    }
}


/**
 * Cursor-proximity HOVER fraction for the pinned controls (search / mic / +),
 * which don't magnify like app tiles but should still react when the injected
 * cursor is over them — so the user can tell they're interactive. Returns a
 * 0..1 fraction (1 = cursor centered on the tile) plus a Modifier that records
 * the tile's center. Uses the SAME cursor-axis math as dockMagnify (reliable
 * with the injected cursor, unlike Compose hover which may not fire on the
 * overlay). Callers map the fraction to a subtle scale + brightening.
 */
@Composable
private fun rememberDockHoverFraction(
    cursorAxisInWindowPx: Float,
    cursorOnBand: Boolean,
    iconSizeDp: Float,
    horizontal: Boolean,
    // 2D MODE (opt-in): for the vertically-stacked right-side controls (status
    // icons on top, bell/time/date below in the SAME column), a horizontal-only
    // proximity can't tell them apart — every icon in the column reads ~1.0 at the
    // same x. When [twoDimensional] is true we also measure the CROSS axis (the
    // cursor's perpendicular position, [crossAxisCursorPx]) and use an ELLIPTICAL
    // falloff: wide along the dock's main axis (unchanged feel) but tight on the
    // cross axis, so each control is hot only near its own row. 2D mode does NOT
    // use [cursorOnBand] — the cross-axis falloff does the vertical gating itself.
    crossAxisCursorPx: Float = Float.NaN,
    twoDimensional: Boolean = false,
): Pair<Float, Modifier> {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var centerPx by remember { mutableStateOf(Float.NaN) }        // main-axis center
    var centerCrossPx by remember { mutableStateOf(Float.NaN) }   // cross-axis center
    val radiusPx = with(density) { (iconSizeDp * 1.2f).dp.toPx() }
    // 2D horizontal radius: much tighter than the 1D radius because the status
    // glyphs are packed ~2dp apart AND the battery sits ~6dp to the right of
    // Wi-Fi at the same height — a wide horizontal reach made Wi-Fi's tooltip
    // fire while hovering the battery. The cross-axis radius stays a touch
    // larger so the glyph is still easy to land on vertically.
    val twoDHRadiusPx = with(density) { (iconSizeDp * 0.6f).dp.toPx() }
    // Cross-axis radius for 2D: tighter than the main-axis radius so the trigger
    // zone hugs the icon's row and doesn't bleed into the control above/below it.
    val crossRadiusPx = with(density) { (iconSizeDp * 0.75f).dp.toPx() }
    val target = if (twoDimensional) {
        if (cursorAxisInWindowPx.isNaN() || centerPx.isNaN() ||
            crossAxisCursorPx.isNaN() || centerCrossPx.isNaN()
        ) {
            0f
        } else {
            // Normalized elliptical distance: 1.0 == on the ellipse edge.
            val nx = (cursorAxisInWindowPx - centerPx) / twoDHRadiusPx
            val ny = (crossAxisCursorPx - centerCrossPx) / crossRadiusPx
            val d = kotlin.math.sqrt(nx * nx + ny * ny)
            if (d >= 1f) 0f
            else {
                val t = 1f - d
                t * t * (3f - 2f * t)   // smoothstep
            }
        }
    } else if (!cursorOnBand || cursorAxisInWindowPx.isNaN() || centerPx.isNaN()) {
        0f
    } else {
        val d = kotlin.math.abs(cursorAxisInWindowPx - centerPx)
        if (d >= radiusPx) 0f
        else {
            val t = 1f - (d / radiusPx)
            t * t * (3f - 2f * t)   // smoothstep
        }
    }
    val frac by animateFloatAsState(targetValue = target, label = "dockHover")
    val mod = Modifier.onGloballyPositioned { coords ->
        val pos = coords.positionInRoot()
        val size = coords.size
        if (horizontal) {
            centerPx = pos.x + size.width / 2f
            centerCrossPx = pos.y + size.height / 2f
        } else {
            centerPx = pos.y + size.height / 2f
            centerCrossPx = pos.x + size.width / 2f
        }
    }
    return frac to mod
}

/**
 * A small tooltip label rendered ABOVE a dock control when hovered, blended into
 * the dock aesthetic (translucent dark pill, muted label-style text). Designed to
 * be placed inside the control's Box with Alignment.TopCenter; it offsets itself
 * upward so it floats above the glyph. Uses zero-height layout tricks so it
 * doesn't push the control. [visible] is driven by the hover fraction.
 */
/** Subtle dark shadow applied to white/muted dock text (clock, battery %, app
 *  labels) so it stays readable over arbitrary wallpapers. A soft offset shadow
 *  rather than a hard outline — escalate to a true outline later if needed. */
private fun dockTextShadow(): androidx.compose.ui.graphics.Shadow =
    androidx.compose.ui.graphics.Shadow(
        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.85f),
        offset = androidx.compose.ui.geometry.Offset(0f, 1f),
        blurRadius = 3f,
    )

/**
 * Convert a desired size (expressed as the same numeric value we used to pass as
 * sp) into a TextUnit that renders at a FIXED physical size regardless of the
 * user's Android font-size setting. We treat the number as dp and use toSp(),
 * which divides out the current fontScale — so dock/top-bar/tooltip text no
 * longer inflates when the user raises their system font size. This is the safe
 * way to pin dock text size: it touches only the text unit, never the overlay
 * context (unlike the earlier context-pin that broke overlay attachment).
 */
@Composable
private fun nonScalingSp(value: Float): androidx.compose.ui.unit.TextUnit =
    with(androidx.compose.ui.platform.LocalDensity.current) { value.dp.toSp() }

@Composable
private fun DockTooltip(text: String, visible: Boolean, labelSizeSp: Float, gapDp: Float = 7f, anchorWidthDp: Float = 0f, above: Boolean = false) {
    if (!visible) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gapPx = with(density) { gapDp.dp.roundToPx() }
    // The Popup placeholder is a zero-size node, so its anchorBounds collapses to
    // the parent's top-start point (zero width) rather than the glyph's full box.
    // Centering on a zero-width anchor shifts the tooltip LEFT of the glyph by
    // half its width. Add half the real glyph width back so it centers on the
    // glyph itself.
    val anchorHalfPx = with(density) { (anchorWidthDp / 2f).dp.roundToPx() }
    // Custom positioner: center the tooltip horizontally on the anchor and place
    // its TOP just below the anchor's BOTTOM edge + a small gap. Deterministic —
    // uses the real anchor bounds and popup size rather than a guessed offset.
    val positioner = remember(gapPx, anchorHalfPx, above) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ): androidx.compose.ui.unit.IntOffset {
                val anchorCenterX = anchorBounds.left + anchorBounds.width / 2 + anchorHalfPx
                val x = anchorCenterX - popupContentSize.width / 2
                // Below the anchor by default; ABOVE it when there's no room below
                // (e.g. the bell, pinned to the band bottom near the display edge,
                // where a below-tooltip would clamp against the screen and lose its
                // gap). Above places the tooltip's BOTTOM a gap above the anchor TOP.
                val y = if (above) anchorBounds.top - gapPx - popupContentSize.height
                else anchorBounds.bottom + gapPx
                return androidx.compose.ui.unit.IntOffset(x, y)
            }
        }
    }
    androidx.compose.ui.window.Popup(popupPositionProvider = positioner) {
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = text,
                color = AbOnSurfaceMuted,
                fontSize = nonScalingSp(labelSizeSp * 0.95f),
                maxLines = 1,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DockTile(
    item: DockItem,
    iconSizeDp: Float,
    labelSizeSp: Float,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    magnifyCursorPx: Float = Float.NaN,
    magnifyOnBand: Boolean = false,
    magnifyHorizontal: Boolean = true,
    wiggling: Boolean = false,
    pickedUp: Boolean = false,
    labelColor: Color = Color.White,
) {
    // macOS-style icons (full-size launcher icon sitting directly on the glass
    // bar, no plate; folders get a translucent squircle) with the app label
    // kept underneath each tile.
    // Reorder "wiggle": a small continuous rotation oscillation when in reorder
    // mode (iOS jiggle). A per-item phase offset desyncs the icons so they don't
    // wiggle in lockstep. A picked-up icon lifts (scales up) instead.
    val wigglePhase = remember(item.id) { (item.id.hashCode() % 360).toFloat() }
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "wiggle")
    val wiggleAngle by infinite.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = 180,
                easing = androidx.compose.animation.core.LinearEasing,
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            initialStartOffset = androidx.compose.animation.core.StartOffset((wigglePhase * 2).toInt()),
        ),
        label = "wiggleAngle",
    )
    val pickupScale by animateFloatAsState(if (pickedUp) 1.25f else 1f, label = "pickup")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(iconSizeDp.dp)
                .wrapContentHeight()
                .dockMagnify(magnifyCursorPx, magnifyOnBand, iconSizeDp, magnifyHorizontal)
                .dockRightClickTarget(magnifyCursorPx, magnifyOnBand, iconSizeDp, magnifyHorizontal) { onLongPress() }
                .combinedClickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        onClick()
                    },
                    onLongClick = {
                        onLongPress()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Wiggle/pickup transform lives on the INNER content only — never on
            // the Box that dockMagnify measures — so it can't shift the measured
            // center (which was offsetting magnification onto the neighbor tile).
            val wiggleMod = if (wiggling) {
                Modifier.graphicsLayer {
                    rotationZ = if (pickedUp) 0f else wiggleAngle
                    scaleX = pickupScale
                    scaleY = pickupScale
                }
            } else {
                Modifier
            }
            Box(wiggleMod, contentAlignment = Alignment.Center) {
                when (item) {
                    is DockItem.Shortcut ->
                        AppIcon(packageName = item.app.packageName, sizeDp = iconSizeDp.toInt(), reflect = true)
                    is DockItem.Folder ->
                        FolderIcon(folder = item, sizeDp = iconSizeDp.toInt(), reflect = true)
                }
            }
        }
        if (labelSizeSp >= 1f) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.label,
                color = labelColor,
                fontSize = nonScalingSp(labelSizeSp),
                maxLines = 1,
                // Cap the label to the icon width and ellipsize — otherwise a long
                // app name widens the whole tile (the Column sizes to its widest
                // child), making the gaps between icons uneven. Capping keeps every
                // tile exactly icon-width so spacing is uniform.
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(iconSizeDp.dp),
                style = androidx.compose.ui.text.TextStyle(shadow = dockTextShadow()),
            )
        }
    }
}

/**
 * The minimized-windows BAR shown above the dock. A slim, dock-themed pill
 * (glyph + count + "Windows Minimized" + an up-chevron) that floats above the
 * band, centered to the dock. Tapping it expands [MinimizedWindowsDropdown] — a
 * Popup, so the list isn't limited by the dock window's short headroom.
 *
 * Width-responsive: collapses to glyph + count + chevron when the full label
 * can't fit [maxBarWidthDp] (e.g. a narrow dock).
 *
 * Mutual exclusion with the status (quick-settings) menu: opening this dismisses
 * the status + notification panels; the status menu opening (observed via
 * [PortalPadApp.quickSettingsOpen]) collapses this.
 */
// Estimated rendered width (dp) of a windows-bar from its label: glyph + count +
// label + chevron + paddings, ~6.2dp/char (constant 60 = 18 glyph + 8 + 12 + 22).
// Shared by the bars (compact decision) and the parent layout (side-by-side fit-
// check) so the two never disagree about whether both bars fit.
internal fun barEstWidthDp(label: String): Float = 60f + label.length * 6.2f

@Composable
private fun MinimizedWindowsBar(
    tasks: List<RunningTask>,
    iconSizeDp: Float,
    dockStyle: com.portalpad.app.data.BarStyle,
    maxBarWidthDp: Float,
    contentColor: Color,
    mutedColor: Color,
    onRestore: (RunningTask) -> Unit,
    onClose: (RunningTask) -> Unit,
    onInteract: () -> Unit = {},
    // When this bar sits BELOW another bar in the stack (the open-windows bar above
    // it), the dropdown lifts by this much so it clears the bar(s) above and floats
    // over the whole stack instead of covering them. 0 when this is the only bar.
    liftAboveDp: Float = 0f,
    anchorTopPx: Float? = null,
    anchorCenterXPx: Float? = null,
    onMenuExpandedChange: (Boolean) -> Unit = {},
) {
    val count = tasks.size
    if (count == 0) return
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { onMenuExpandedChange(expanded) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onMenuExpandedChange(false) }
    }

    // Reverse mutual exclusion: collapse when the status menu opens.
    val qsOpen by com.portalpad.app.PortalPadApp.instance.quickSettingsOpen.collectAsState()
    LaunchedEffect(qsOpen) { if (qsOpen) expanded = false }

    val word = if (count == 1) "Window Minimized" else "Windows Minimized"
    // Collapse to glyph+count when the full label won't fit the dock band
    // (~6.2dp/char at this size, plus glyph + count + chevron + padding).
    val estWidthDp = barEstWidthDp("$count $word")
    val compact = estWidthDp > (maxBarWidthDp - 8f)

    val barBrush = remember(dockStyle) {
        val dockAlpha = androidx.compose.ui.graphics.Color(dockStyle.colorAInt()).alpha
        // Bars read as the dock's lighter secondary tier: same style, fill alpha
        // dropped ~0.15 below the dock's (floored at 0.55 for legibility, and never
        // exceeding the dock). Border + text stay opaque so shape/labels stay crisp.
        val barAlpha = (dockAlpha - 0.15f).coerceAtLeast(0.55f).coerceAtMost(dockAlpha)
        com.portalpad.app.ui.common.toComposeBrush(dockStyle, fillAlpha = barAlpha)
    }
    // Match the dock's corner radius so the bars read as the same material cut,
    // not free-floating pills.
    val barShape = RoundedCornerShape(dockStyle.cornerRadiusDp.dp)
    val glyphSize = iconSizeDp * 0.26f

    Box {
        Row(
            Modifier
                .clip(barShape)
                .background(barBrush)
                .background(if (expanded) AbPrimaryBright.copy(alpha = 0.12f) else Color.Transparent)
                .border(
                    if (expanded) 1.5.dp else 1.dp,
                    // Non-active edge matches the dock band's own border tint, so the
                    // bar edges + dock edge read as one material (the "shared edge").
                    if (expanded) AbPrimaryBright else Color.White.copy(alpha = 0.18f),
                    barShape,
                )
                .clickable {
                    onInteract()
                    if (!expanded) {
                        // Forward mutual exclusion: close the status + notification
                        // menus before expanding this one.
                        com.portalpad.app.service.PortalPadForegroundService.dismissQuickSettingsPanel()
                        com.portalpad.app.service.PortalPadForegroundService.dismissNotificationPanel()
                    }
                    expanded = !expanded
                }
                .padding(horizontal = 11.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            MinimizedGlyph(sizeDp = glyphSize, color = contentColor)
            Text(
                "$count",
                color = AbPrimaryBright,
                fontSize = nonScalingSp(13f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            if (!compact) {
                Text(
                    word,
                    color = contentColor,
                    fontSize = nonScalingSp(13f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                if (expanded) "▾" else "▴",
                color = mutedColor,
                fontSize = nonScalingSp(10f),
                maxLines = 1,
            )
        }
        if (expanded) {
            MinimizedWindowsDropdown(
                tasks = tasks,
                liftAboveDp = liftAboveDp,
                anchorTopPx = anchorTopPx,
                anchorCenterXPx = anchorCenterXPx,
                dockStyle = dockStyle,
                contentColor = contentColor,
                mutedColor = mutedColor,
                onRestore = { t -> expanded = false; onRestore(t) },
                onClose = onClose,
                onDismiss = { expanded = false },
            )
        }
    }
}

/** Stacked-squares "windows" glyph (Canvas, no icon import) for the bar. */
@Composable
private fun MinimizedGlyph(sizeDp: Float, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(sizeDp.dp)) {
        val s = this.size.minDimension
        val r = s * 0.12f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = s * 0.10f)
        val side = s * 0.62f
        drawRoundRect(
            color = color.copy(alpha = 0.55f),
            topLeft = androidx.compose.ui.geometry.Offset(s - side, 0f),
            size = androidx.compose.ui.geometry.Size(side, side),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            style = stroke,
        )
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(0f, s - side),
            size = androidx.compose.ui.geometry.Size(side, side),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            style = stroke,
        )
    }
}

/**
 * The dropdown listing minimized windows, opened ABOVE the bar as a Popup (its
 * own window, so it can be taller than the dock's headroom). Dock-themed.
 * Row tap restores (relaunches) the window; the ✕ closes/forgets it.
 */
@Composable
private fun MinimizedWindowsDropdown(
    tasks: List<RunningTask>,
    liftAboveDp: Float = 0f,
    anchorTopPx: Float? = null,
    anchorCenterXPx: Float? = null,
    dockStyle: com.portalpad.app.data.BarStyle,
    contentColor: Color,
    mutedColor: Color,
    onRestore: (RunningTask) -> Unit,
    onClose: (RunningTask) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Fold the lift-above-stack distance into the gap, so the menu's bottom clears
    // any bar stacked above this one and sits a gap above the TOP of the stack.
    // Keyed into remember below so it updates if the stack composition changes.
    val gapPx = with(density) { (40.dp + liftAboveDp.dp).roundToPx() }
    val positioner = remember(gapPx, anchorTopPx, anchorCenterXPx) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ): androidx.compose.ui.unit.IntOffset {
                // Prefer the parent-measured assembly center (centers on the bars);
                // fall back to the placeholder bounds.
                val cxBase = anchorCenterXPx?.toInt()
                    ?: (anchorBounds.left + anchorBounds.width / 2)
                val x = (cxBase - popupContentSize.width / 2)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                // Open ABOVE the bar: place the menu's BOTTOM a gap above the anchor top.
                val topBase = anchorTopPx?.toInt() ?: anchorBounds.top
                val y = (topBase - gapPx - popupContentSize.height).coerceAtLeast(0)
                return androidx.compose.ui.unit.IntOffset(x, y)
            }
        }
    }
    val brush = remember(dockStyle) {
        com.portalpad.app.ui.common.toComposeBrush(dockStyle, opaque = true)
    }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = positioner,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        val listScroll = rememberScrollState()
        var menuHeightPx by remember { mutableStateOf(0) }
        val sbDensity = androidx.compose.ui.platform.LocalDensity.current
        Box(
            Modifier
                .widthIn(min = 230.dp, max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(brush)
                .border(1.dp, contentColor.copy(alpha = 0.12f), RoundedCornerShape(16.dp)),
        ) {
            Column(
                Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(listScroll)
                    .padding(7.dp)
                    .onSizeChanged { menuHeightPx = it.height },
            ) {
                Text(
                    "MINIMIZED WINDOWS",
                    color = mutedColor,
                    fontSize = nonScalingSp(9f),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 6.dp),
                )
                tasks.forEach { task ->
                    MinimizedWindowRow(
                        task = task,
                        contentColor = contentColor,
                        mutedColor = mutedColor,
                        onRestore = { onRestore(task) },
                        onClose = { onClose(task) },
                    )
                }
            }
            // Visible scrollbar — drawn from the menu's MEASURED (finite) height, so it
            // never touches the Popup's possibly-unbounded height constraint (which made
            // BoxWithConstraints/fillMaxHeight throw and tear down the display). Shows
            // only when the list overflows the 280dp cap; the open-windows menu can't
            // overflow, so it has none.
            if (listScroll.maxValue > 0 && menuHeightPx > 0) {
                val menuH = with(sbDensity) { menuHeightPx.toDp() }
                val trackH = (menuH - 14.dp).coerceAtLeast(0.dp)
                val thumbFrac = (menuHeightPx.toFloat() /
                    (menuHeightPx + listScroll.maxValue)).coerceIn(0.12f, 1f)
                val posFrac = listScroll.value.toFloat() / listScroll.maxValue
                val thumbH = trackH * thumbFrac
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .height(menuH)
                        .padding(vertical = 7.dp)
                        .width(4.dp),
                ) {
                    Box(
                        Modifier
                            .offset(y = (trackH - thumbH) * posFrac)
                            .width(4.dp)
                            .height(thumbH)
                            .clip(RoundedCornerShape(2.dp))
                            .background(contentColor.copy(alpha = 0.30f)),
                    )
                }
            }
        }
    }
}

/** One row in [MinimizedWindowsDropdown]: app icon + label (tap to restore) + ✕. */
@Composable
private fun MinimizedWindowRow(
    task: RunningTask,
    contentColor: Color,
    mutedColor: Color,
    onRestore: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val label = remember(task.packageName) {
        runCatching {
            ctx.packageManager.getApplicationLabel(
                ctx.packageManager.getApplicationInfo(task.packageName, 0),
            ).toString()
        }.getOrDefault(task.packageName)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .clickable { onRestore() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        AppIcon(packageName = task.packageName, sizeDp = 34, reflect = false)
        Text(
            label,
            color = contentColor,
            fontSize = nonScalingSp(13f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = mutedColor, fontSize = nonScalingSp(13f))
        }
    }
}

/**
 * The open-windows BAR — same dock-themed pill as [MinimizedWindowsBar] but for
 * LIVE windows on the external display. Tapping expands [OpenWindowsDropdown]; a
 * row tap focuses (brings to front) the window, with per-row minimize + close.
 *
 * For now this renders ONLY in the single bar slot when nothing is minimized
 * (the minimized bar takes that slot when present). Showing both bars stacked at
 * once needs a taller popoutHeadroom and is a separate, on-device-verified step.
 */
@Composable
private fun OpenWindowsBar(
    tasks: List<RunningTask>,
    iconSizeDp: Float,
    dockStyle: com.portalpad.app.data.BarStyle,
    maxBarWidthDp: Float,
    contentColor: Color,
    mutedColor: Color,
    onFocus: (RunningTask) -> Unit,
    onMinimize: (RunningTask) -> Unit,
    onClose: (RunningTask) -> Unit,
    onArrange: () -> Unit,
    onArrangeBlocked: () -> Unit = {},
    onInteract: () -> Unit = {},
    anchorTopPx: Float? = null,
    anchorCenterXPx: Float? = null,
    onMenuExpandedChange: (Boolean) -> Unit = {},
) {
    val count = tasks.size
    if (count == 0) return
    val maxWindows by com.portalpad.app.PortalPadApp.instance.prefs
        .int(com.portalpad.app.data.PreferencesRepository.Keys.MAX_WINDOWS, default = 5)
        .collectAsState(initial = 5)
    var expanded by remember { mutableStateOf(false) }

    val qsOpen by com.portalpad.app.PortalPadApp.instance.quickSettingsOpen.collectAsState()
    LaunchedEffect(qsOpen) { if (qsOpen) expanded = false }
    LaunchedEffect(expanded) { onMenuExpandedChange(expanded) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onMenuExpandedChange(false) }
    }

    val word = if (count == 1) "Window open" else "Windows open"
    val estWidthDp = barEstWidthDp("$count / $maxWindows $word")
    val compact = estWidthDp > (maxBarWidthDp - 8f)

    val barBrush = remember(dockStyle) {
        val dockAlpha = androidx.compose.ui.graphics.Color(dockStyle.colorAInt()).alpha
        // Bars read as the dock's lighter secondary tier: same style, fill alpha
        // dropped ~0.15 below the dock's (floored at 0.55 for legibility, and never
        // exceeding the dock). Border + text stay opaque so shape/labels stay crisp.
        val barAlpha = (dockAlpha - 0.15f).coerceAtLeast(0.55f).coerceAtMost(dockAlpha)
        com.portalpad.app.ui.common.toComposeBrush(dockStyle, fillAlpha = barAlpha)
    }
    // Match the dock's corner radius so the bars read as the same material cut,
    // not free-floating pills.
    val barShape = RoundedCornerShape(dockStyle.cornerRadiusDp.dp)
    val glyphSize = iconSizeDp * 0.26f

    Box {
        Row(
            Modifier
                .clip(barShape)
                .background(barBrush)
                .background(if (expanded) AbPrimaryBright.copy(alpha = 0.12f) else Color.Transparent)
                .border(
                    if (expanded) 1.5.dp else 1.dp,
                    // Non-active edge matches the dock band's own border tint, so the
                    // bar edges + dock edge read as one material (the "shared edge").
                    if (expanded) AbPrimaryBright else Color.White.copy(alpha = 0.18f),
                    barShape,
                )
                .clickable {
                    onInteract()
                    if (!expanded) {
                        com.portalpad.app.service.PortalPadForegroundService.dismissQuickSettingsPanel()
                        com.portalpad.app.service.PortalPadForegroundService.dismissNotificationPanel()
                    }
                    expanded = !expanded
                }
                .padding(horizontal = 11.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            OpenWindowsGlyph(sizeDp = glyphSize, color = contentColor)
            Text(
                "$count / $maxWindows",
                color = if (count >= maxWindows) Color(0xFFE6B800) else AbPrimaryBright,
                fontSize = nonScalingSp(13f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            if (!compact) {
                Text(
                    word,
                    color = contentColor,
                    fontSize = nonScalingSp(13f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                if (expanded) "▾" else "▴",
                color = mutedColor,
                fontSize = nonScalingSp(10f),
                maxLines = 1,
            )
        }
        if (expanded) {
            OpenWindowsDropdown(
                tasks = tasks,
                anchorTopPx = anchorTopPx,
                anchorCenterXPx = anchorCenterXPx,
                maxWindows = maxWindows,
                dockStyle = dockStyle,
                contentColor = contentColor,
                mutedColor = mutedColor,
                onFocus = { t -> expanded = false; onFocus(t) },
                onMinimize = onMinimize,
                onClose = onClose,
                onArrange = { expanded = false; onArrange() },
                onArrangeBlocked = { expanded = false; onArrangeBlocked() },
                onDismiss = { expanded = false },
            )
        }
    }
}

/** Side-by-side "open windows" glyph (Canvas) — two windows next to each other,
 *  distinct from MinimizedGlyph's stacked/offset squares so the two bars read
 *  differently at a glance. */
@Composable
private fun OpenWindowsGlyph(sizeDp: Float, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(sizeDp.dp)) {
        val s = this.size.minDimension
        val r = s * 0.12f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = s * 0.10f)
        val w = s * 0.42f
        val h = s * 0.66f
        val top = (s - h) / 2f
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(0f, top),
            size = androidx.compose.ui.geometry.Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            style = stroke,
        )
        drawRoundRect(
            color = color.copy(alpha = 0.55f),
            topLeft = androidx.compose.ui.geometry.Offset(s - w, top),
            size = androidx.compose.ui.geometry.Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            style = stroke,
        )
    }
}

/**
 * Dropdown listing the open windows, opened ABOVE the bar as a Popup. Row tap
 * focuses (brings to front); the − minimizes (into the minimized list), the ✕
 * closes. Mirrors [MinimizedWindowsDropdown].
 */
@Composable
private fun OpenWindowsDropdown(
    tasks: List<RunningTask>,
    anchorTopPx: Float? = null,
    anchorCenterXPx: Float? = null,
    maxWindows: Int,
    dockStyle: com.portalpad.app.data.BarStyle,
    contentColor: Color,
    mutedColor: Color,
    onFocus: (RunningTask) -> Unit,
    onMinimize: (RunningTask) -> Unit,
    onClose: (RunningTask) -> Unit,
    onArrange: () -> Unit,
    onArrangeBlocked: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gapPx = with(density) { 40.dp.roundToPx() }
    val positioner = remember(gapPx, anchorTopPx, anchorCenterXPx) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ): androidx.compose.ui.unit.IntOffset {
                // Prefer the parent-measured assembly center (centers on the bars);
                // fall back to the placeholder bounds.
                val cxBase = anchorCenterXPx?.toInt()
                    ?: (anchorBounds.left + anchorBounds.width / 2)
                val x = (cxBase - popupContentSize.width / 2)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val topBase = anchorTopPx?.toInt() ?: anchorBounds.top
                val y = (topBase - gapPx - popupContentSize.height).coerceAtLeast(0)
                return androidx.compose.ui.unit.IntOffset(x, y)
            }
        }
    }
    val brush = remember(dockStyle) {
        com.portalpad.app.ui.common.toComposeBrush(dockStyle, opaque = true)
    }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = positioner,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Box(
            Modifier
                .widthIn(min = 230.dp, max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(brush)
                .border(1.dp, contentColor.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .padding(7.dp),
        ) {
            Column(
                Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val headerCount = tasks.size
                val atCap = headerCount >= maxWindows
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "OPEN WINDOWS",
                        color = mutedColor,
                        fontSize = nonScalingSp(9f),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$headerCount / $maxWindows",
                        color = if (atCap) Color(0xFFE6B800) else mutedColor,
                        fontSize = nonScalingSp(9f),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    // Mirror the glasses top bar's "Arrange in order": needs 2+
                    // windows. Below that, grey it and toast on tap instead of
                    // opening a pointless ordering screen for a single window.
                    val canArrange = tasks.size >= 2
                    val arrangeColor = if (canArrange) contentColor else contentColor.copy(alpha = 0.4f)
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { if (canArrange) onArrange() else onArrangeBlocked() }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("⊞", color = arrangeColor, fontSize = nonScalingSp(13f))
                        Text(
                            "Arrange",
                            color = arrangeColor,
                            fontSize = nonScalingSp(10f),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                tasks.forEach { task ->
                    OpenWindowRow(
                        task = task,
                        contentColor = contentColor,
                        mutedColor = mutedColor,
                        onFocus = { onFocus(task) },
                        onMinimize = { onMinimize(task) },
                        onClose = { onClose(task) },
                    )
                }
            }
        }
    }
}

/** One row in [OpenWindowsDropdown]: icon + label (tap to focus) + − + ✕. */
@Composable
private fun OpenWindowRow(
    task: RunningTask,
    contentColor: Color,
    mutedColor: Color,
    onFocus: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val label = remember(task.packageName) {
        runCatching {
            ctx.packageManager.getApplicationLabel(
                ctx.packageManager.getApplicationInfo(task.packageName, 0),
            ).toString()
        }.getOrDefault(task.packageName)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .clickable { onFocus() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppIcon(packageName = task.packageName, sizeDp = 34, reflect = false)
        Text(
            label,
            color = contentColor,
            fontSize = nonScalingSp(13f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onMinimize() },
            contentAlignment = Alignment.Center,
        ) {
            Text("−", color = mutedColor, fontSize = nonScalingSp(15f))
        }
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = mutedColor, fontSize = nonScalingSp(13f))
        }
    }
}

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
private fun FolderPopup(
    folder: DockItem.Folder,
    config: DockConfig,
    depth: Int,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onOpenSubFolder: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onConfigChange: (DockConfig) -> Unit,
    recentMillis: (String) -> Long,
    previewMode: Boolean = false,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val openPickerForFolder: (String) -> Unit = { folderId ->
        com.portalpad.app.ui.dock.launchDockPickerOnPhone(ctx, "folder:$folderId")
    }
    var editAppearance by remember { mutableStateOf(false) }
    var manageItem by remember { mutableStateOf<DockItem?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Global folder-window style (gradient background + app-label color).
    val fwConfig by com.portalpad.app.PortalPadApp.instance.prefs.folderWindowConfig
        .collectAsState(initial = com.portalpad.app.data.FolderWindowConfig())
    // Live injected-cursor position (display px) driving 2D-proximity tile magnify.
    // NaN in preview so placeholder tiles don't react. cursorPosition is a
    // StateFlow, which already conflates and only emits distinct values, so
    // collectAsState is inherently frame-capped + deduped — no operators needed.
    val cursorPos by com.portalpad.app.PortalPadApp.instance.injector
        .cursorPosition.collectAsState()
    val magnifyCursorX = if (previewMode) Float.NaN else cursorPos.first
    val magnifyCursorY = if (previewMode) Float.NaN else cursorPos.second
    val fwStyle = fwConfig.background
    val fwBrush = remember(fwStyle) { com.portalpad.app.ui.common.toComposeBrush(fwStyle) }
    val fwLabelColor = Color(fwConfig.labelColor.toInt())
    // Adaptive colors for the folder controls (title, Sort by, toolbar buttons),
    // derived from the background so they stay legible on any chosen color.
    val fcColors = folderControlColors(Color(fwStyle.colorAInt()), Color(fwStyle.colorBInt()))
    // Contrast-adaptive color for preview placeholder squares: pick light-on-dark
    // or dark-on-light from the average luminance of the background gradient, so
    // the squares stay visible whatever background color the user sets.
    val fwPreviewSquareColor = run {
        val ca = fwStyle.colorAInt(); val cb = fwStyle.colorBInt()
        fun lum(c: Int): Double {
            val r = ((c shr 16) and 0xFF) / 255.0
            val g = ((c shr 8) and 0xFF) / 255.0
            val bl = (c and 0xFF) / 255.0
            return 0.299 * r + 0.587 * g + 0.114 * bl
        }
        val avg = (lum(ca) + lum(cb)) / 2.0
        if (avg < 0.5) Color.White.copy(alpha = 0.30f) else Color.Black.copy(alpha = 0.28f)
    }
    // Grid sizing from config. Fixed columns (1–12) guarantee no overlap; the
    // window width follows from the column count + tile size, capped to the
    // display so it never runs off-screen. The header sits in the same-width
    // Column, so it lines up with the grid automatically.
    val fwCols = fwConfig.columns.coerceIn(1, 12)
    val fwRows = fwConfig.maxRows.coerceIn(1, 12)
    val fwTile = fwConfig.tileSizeDp.coerceIn(40, 120)
    val tileCellDp = fwTile + 22          // icon + a little label width (labels can
    val gridSpacingDp = 14
    val labelBandDp = if (fwConfig.showLabels) 22 else 4   // label height or just a small gap
    // Width needed for the requested columns, capped to a sensible max so the
    // popup stays on-screen. A minimum keeps the header/toolbar usable.
    val gridContentDp = fwCols * tileCellDp + (fwCols - 1) * gridSpacingDp
    val popupContentDp = gridContentDp.coerceIn(300, 1100)
    val gridMaxHeightDp = (fwRows * (fwTile + labelBandDp) + (fwRows - 1) * gridSpacingDp)
        .coerceAtMost(560)
    // Where the window sits on the display — horizontal × vertical. Both axes
    // user-selectable; the 9 combinations map to the named 2D alignments.
    val fwWindowAlign = when (fwConfig.vPosition) {
        com.portalpad.app.data.FolderWindowVPosition.TOP -> when (fwConfig.position) {
            com.portalpad.app.data.FolderWindowPosition.LEFT -> Alignment.TopStart
            com.portalpad.app.data.FolderWindowPosition.CENTER -> Alignment.TopCenter
            com.portalpad.app.data.FolderWindowPosition.RIGHT -> Alignment.TopEnd
        }
        com.portalpad.app.data.FolderWindowVPosition.CENTER -> when (fwConfig.position) {
            com.portalpad.app.data.FolderWindowPosition.LEFT -> Alignment.CenterStart
            com.portalpad.app.data.FolderWindowPosition.CENTER -> Alignment.Center
            com.portalpad.app.data.FolderWindowPosition.RIGHT -> Alignment.CenterEnd
        }
        com.portalpad.app.data.FolderWindowVPosition.BOTTOM -> when (fwConfig.position) {
            com.portalpad.app.data.FolderWindowPosition.LEFT -> Alignment.BottomStart
            com.portalpad.app.data.FolderWindowPosition.CENTER -> Alignment.BottomCenter
            com.portalpad.app.data.FolderWindowPosition.RIGHT -> Alignment.BottomEnd
        }
    }
    val fwGridHAlign = when (fwConfig.gridAlign) {
        com.portalpad.app.data.FolderGridAlign.LEFT -> Alignment.Start
        com.portalpad.app.data.FolderGridAlign.CENTER -> Alignment.CenterHorizontally
    }
    val fwShowLabels = fwConfig.showLabels
    val fwLabelSize = fwConfig.labelSizeSp.coerceIn(8, 20)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable { onDismiss() },
        contentAlignment = fwWindowAlign,
    ) {
        // Inner Surface swallows clicks so tapping inside doesn't dismiss.
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(fwStyle.cornerRadiusDp.dp),
            modifier = Modifier
                .padding(24.dp)
                .clip(RoundedCornerShape(fwStyle.cornerRadiusDp.dp))
                .background(fwBrush)
                .clickable(enabled = false) {},
        ) {
            Column(
                Modifier.padding(20.dp).width(popupContentDp.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Header: back (when nested) + title + toolbar.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (depth > 1) {
                        Icon(
                            Icons.Default.KeyboardArrowLeft,
                            contentDescription = "Back",
                            tint = AbOnSurface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onBack() }
                                .padding(4.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        folder.label,
                        color = fcColors.content,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    SortControl(
                        sort = folder.sort,
                        ascending = folder.sortAscending,
                        colors = fcColors,
                        onChange = { mode, asc ->
                            onConfigChange(config.sortFolder(folder.id, mode, asc, recentMillis))
                        },
                    )
                }
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FolderToolbarButton("Icon", fcColors) { editAppearance = true }
                    FolderToolbarButton("Rename", fcColors) { launchRenameOnPhone(ctx, folder.id, folder.label) }
                    FolderToolbarButton("New folder", fcColors) {
                        onConfigChange(config.addSubFolder(folder.id, "Folder"))
                    }
                    FolderToolbarButton("Add apps", fcColors) { openPickerForFolder(folder.id) }
                    FolderToolbarButton("Delete", fcColors, danger = true) { confirmDelete = true }
                }

                if (folder.contents.isEmpty()) {
                    Text(
                        "Empty folder. Use \"Add apps\" or long-press a dock app and move it in.",
                        color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    // Wrapping grid of contents (apps + sub-folders). Constrained
                    // in height + scrollable so a full folder doesn't overflow the
                    // popup off-screen; a visible scrollbar appears when it does.
                    val gridScroll = rememberScrollState()
                    Box {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(gridSpacingDp.dp, fwGridHAlign),
                            verticalArrangement = Arrangement.spacedBy(gridSpacingDp.dp),
                            maxItemsInEachRow = fwCols,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = gridMaxHeightDp.dp)
                                .verticalScroll(gridScroll)
                                // Top inset = magnify head-room. The TOP row scales up
                                // ~18% on hover (centered), so it grows ~9% past the
                                // scroll's top edge and was clipped against the toolbar
                                // above; this leading space gives it room to pop.
                                .padding(top = (fwTile * 0.14f).dp, end = 10.dp),
                        ) {
                        folder.contents.forEach { child ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(tileCellDp.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(fwTile.dp)
                                        .folderTileMagnify(
                                            magnifyCursorX, magnifyCursorY, fwTile.toFloat(),
                                        )
                                        .combinedClickable(
                                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                            indication = null,
                                            onClick = {
                                                when (child) {
                                                    is DockItem.Shortcut -> onLaunch(child.app)
                                                    is DockItem.Folder -> onOpenSubFolder(child.id)
                                                }
                                            },
                                            onLongClick = { manageItem = child },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    when {
                                        previewMode ->
                                            // Uniform placeholder square (contrast-
                                            // adaptive so it shows on any background).
                                            Box(
                                                Modifier
                                                    .size(fwTile.dp)
                                                    .clip(RoundedCornerShape((fwTile * 0.18f).dp))
                                                    .background(fwPreviewSquareColor),
                                            )
                                        child is DockItem.Shortcut ->
                                            AppIcon(packageName = child.app.packageName, sizeDp = fwTile)
                                        child is DockItem.Folder ->
                                            FolderIcon(folder = child, sizeDp = fwTile)
                                    }
                                }
                                if (fwShowLabels) {
                                    // Gap scales with icon size so the magnified icon
                                    // (now 1.10×, growing ~5% downward) never reaches
                                    // the label at any folder icon size.
                                    Spacer(Modifier.height((fwTile * 0.08f).coerceAtLeast(4f).dp))
                                    Text(
                                        child.label,
                                        color = fwLabelColor,
                                        fontSize = fwLabelSize.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                        }
                        // Visible scrollbar thumb (only when content overflows).
                        if (gridScroll.maxValue > 0) {
                            val frac = gridScroll.value.toFloat() / gridScroll.maxValue.toFloat()
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .fillMaxHeight()
                                    .width(4.dp),
                            ) {
                                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                    val viewH = size.height
                                    val thumbH = (viewH * 0.25f).coerceAtLeast(40f)
                                    val y = (viewH - thumbH) * frac
                                    drawRoundRect(
                                        color = AbOnSurfaceMuted.copy(alpha = 0.5f),
                                        topLeft = androidx.compose.ui.geometry.Offset(0f, y),
                                        size = androidx.compose.ui.geometry.Size(size.width, thumbH),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Per-item management (long-press inside the folder).
    manageItem?.let { child ->
        val idx = folder.contents.indexOfFirst { it.id == child.id }
        // Other folders this item could move into (exclude the current folder and,
        // if the item is itself a folder, its own descendants — moveIntoFolder also
        // guards this, but filtering keeps the menu clean).
        val otherFolders = config.items.allFoldersFlat().filter { f ->
            f.id != folder.id && f.id != child.id &&
                !(child is DockItem.Folder && child.contents.findFolder(f.id) != null)
        }
        FolderItemMenu(
            item = child,
            canMoveUp = idx > 0,
            canMoveDown = idx in 0 until (folder.contents.size - 1),
            moveTargets = otherFolders,
            onMoveUp = {
                if (idx > 0) onConfigChange(config.moveItemInFolder(folder.id, idx, idx - 1))
                manageItem = null
            },
            onMoveDown = {
                if (idx in 0 until (folder.contents.size - 1))
                    onConfigChange(config.moveItemInFolder(folder.id, idx, idx + 1))
                manageItem = null
            },
            onMoveToFolder = { targetId ->
                onConfigChange(config.moveIntoFolder(child.id, targetId)); manageItem = null
            },
            onDismiss = { manageItem = null },
            onMoveOut = {
                onConfigChange(config.extractToTopLevel(child.id)); manageItem = null
            },
            onRemove = {
                onConfigChange(config.deleteItem(child.id)); manageItem = null
            },
        )
    }

    if (editAppearance) {
        FolderAppearanceEditor(
            folder = folder,
            onDismiss = { editAppearance = false },
            onApply = { color, appearance, presetKey, symbol, iconPackPackage, iconPackDrawable ->
                onConfigChange(
                    config.setFolderAppearance(folder.id, color, appearance, presetKey, symbol, iconPackPackage, iconPackDrawable)
                )
                editAppearance = false
            },
        )
    }

    if (confirmDelete) {
        DeleteFolderDialog(
            folderLabel = folder.label,
            hasContents = folder.contents.isNotEmpty(),
            onDismiss = { confirmDelete = false },
            onDeleteKeepApps = {
                onConfigChange(config.dissolveFolder(folder.id)); confirmDelete = false; onBack()
            },
            onDeleteAll = {
                onConfigChange(config.deleteItem(folder.id)); confirmDelete = false; onBack()
            },
        )
    }
}

/**
 * "Sort by" dropdown + direction toggle, shared by the folder window and the
 * workspace folder editor. Dropdown picks A–Z or Recently Installed; the arrow
 * flips direction (A→Z / Z→A, or Newest / Oldest). [sort] == NONE shows "Manual".
 */
@Composable
internal fun SortControl(
    sort: DockSort,
    ascending: Boolean,
    colors: FolderControlColors,
    onChange: (DockSort, Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val sortLabel = when (sort) {
        DockSort.NONE -> "Manual"
        DockSort.ALPHA -> "A–Z"
        DockSort.RECENT -> "Recently Installed"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sort by", color = colors.content.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.chip)
                    .border(1.dp, colors.chipBorder, RoundedCornerShape(12.dp))
                    .clickable { menuOpen = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(sortLabel, color = colors.content, style = MaterialTheme.typography.bodyMedium)
                Text("\u25BE", color = colors.content.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium) // ▾
            }
            androidx.compose.material3.DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                // Match the folder window's theme: paint with the folder's own
                // gradient (opaque) when available, else the flat adaptive surface
                // (e.g. the Settings editor). A subtle border keeps the panel
                // readable as a distinct surface now that it shares the bg colors.
                modifier = Modifier
                    .background(
                        colors.surfaceBrush
                            ?: androidx.compose.ui.graphics.SolidColor(colors.surface),
                    )
                    .border(
                        1.dp,
                        colors.content.copy(alpha = 0.18f),
                        RoundedCornerShape(4.dp),
                    ),
            ) {
                SortMenuItem("A–Z", colors) { menuOpen = false; onChange(DockSort.ALPHA, ascending) }
                SortMenuItem("Recently Installed", colors) { menuOpen = false; onChange(DockSort.RECENT, ascending) }
            }
        }
        // Direction toggle — only meaningful once a sort mode is chosen.
        if (sort != DockSort.NONE) {
            val arrow = if (ascending) "\u2191" else "\u2193" // ↑ / ↓
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.chip)
                    .border(1.dp, colors.chipBorder, RoundedCornerShape(12.dp))
                    .clickable { onChange(sort, !ascending) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(arrow, color = AbPrimaryBright, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * A single Sort-dropdown row with an inset rounded highlight. The highlight sits
 * INSIDE the panel with a small margin (so it never touches the panel edges) and
 * appears on press/hover. Hover may not fire from the injected external-display
 * cursor, so the highlight is driven by press too, guaranteeing it shows on tap.
 */
@Composable
private fun SortMenuItem(
    label: String,
    colors: FolderControlColors,
    onClick: () -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val active = pressed || hovered
    Box(
        Modifier
            .fillMaxWidth()
            // Inset margin so the rounded highlight floats within the panel.
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) colors.content.copy(alpha = 0.15f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(label, color = colors.content, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Colors for the folder-window controls (toolbar buttons, Sort-by row),
 * DERIVED from the chosen folder-window background so they stay legible on any
 * background instead of using fixed theme colors (which could wash out or
 * vanish on a similar-colored background).
 */
/**
 * Theme for the dock long-press edit popup, derived from the dock's own gradient
 * so the menu matches the dock. [content]/[danger] adapt to the dock luminance so
 * text stays legible on any chosen colors. [solid] is an opaque fallback fill.
 */
internal data class DockMenuTheme(
    val brush: androidx.compose.ui.graphics.Brush,
    val solid: Color,
    val content: Color,
    val danger: Color,
)

internal data class FolderControlColors(
    val content: Color,   // text / glyph color (flips white↔near-black for contrast)
    val chip: Color,      // translucent button fill — always a step above the bg
    val chipBorder: Color,
    val surface: Color,   // OPAQUE panel fill for popups (dropdown menu), so text behind doesn't bleed through
    // Optional gradient that MATCHES the folder window's own background, so the
    // Sort dropdown reads as part of the folder theme instead of a flat block.
    // Null → use the flat [surface] (e.g. the Settings editor on the app bg).
    val surfaceBrush: androidx.compose.ui.graphics.Brush? = null,
)

/** Standard theme-colored controls — used where the controls sit on the normal
 *  app background (e.g. the Settings folder editor), not a custom folder bg. */
internal fun themeFolderControlColors() = FolderControlColors(
    content = AbOnSurface,
    chip = AbSurfaceElevated,
    chipBorder = Color.Transparent,
    surface = AbSurfaceElevated,
    surfaceBrush = null,
)

/** Perceived luminance (0=black, 1=white) of [c], via the standard sRGB weights. */
private fun luminanceOf(c: Color): Float =
    0.299f * c.red + 0.587f * c.green + 0.114f * c.blue

/**
 * Derive legible control colors from the folder background gradient ([a], [b]).
 * Uses the average of the two stops; on a dark background the content goes
 * white and the chip is a translucent white overlay, on a light background the
 * content goes near-black and the chip a translucent black overlay — so the
 * buttons always read as raised and the text is never invisible.
 */
private fun folderControlColors(a: Color, b: Color): FolderControlColors {
    val lum = (luminanceOf(a) + luminanceOf(b)) / 2f
    // Opaque copy of the folder's two stops as a vertical gradient — used to paint
    // the Sort dropdown so it matches the folder window's theme (opaque so text
    // behind the menu can't bleed through).
    val matchBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
        listOf(a.copy(alpha = 1f), b.copy(alpha = 1f)),
    )
    return if (lum < 0.5f) {
        // Dark background → light controls.
        FolderControlColors(
            content = Color(0xFFF3F1F7),
            chip = Color.White.copy(alpha = 0.14f),
            chipBorder = Color.White.copy(alpha = 0.22f),
            surface = Color(0xFF221C33),
            surfaceBrush = matchBrush,
        )
    } else {
        // Light background → dark controls.
        FolderControlColors(
            content = Color(0xFF15131A),
            chip = Color.Black.copy(alpha = 0.10f),
            chipBorder = Color.Black.copy(alpha = 0.18f),
            surface = Color(0xFFF3F1F7),
            surfaceBrush = matchBrush,
        )
    }
}

@Composable
private fun FolderToolbarButton(
    label: String,
    colors: FolderControlColors,
    danger: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AbPrimary else colors.chip)
            .border(
                1.dp,
                if (selected) AbPrimaryBright else colors.chipBorder,
                RoundedCornerShape(12.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) AbPrimaryBright else if (danger) AbAccent else colors.content,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Long-press menu for an item INSIDE a folder. */
@Composable
private fun FolderItemMenu(
    item: DockItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    moveTargets: List<DockItem.Folder>,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveToFolder: (String) -> Unit,
    onDismiss: () -> Unit,
    onMoveOut: () -> Unit,
    onRemove: () -> Unit,
) {
    var pickFolder by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = AbSurface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.padding(24.dp).clickable(enabled = false) {},
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.label, color = AbOnSurface,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (!pickFolder) {
                    // Reorder within this folder.
                    if (canMoveUp) MenuRow("Move up", onClick = onMoveUp)
                    if (canMoveDown) MenuRow("Move down", onClick = onMoveDown)
                    // Move into another folder (only if there's somewhere to go).
                    if (moveTargets.isNotEmpty()) MenuRow("Move to folder…", onClick = { pickFolder = true })
                    MenuRow("Move out to dock", onClick = onMoveOut)
                    MenuRow(
                        if (item is DockItem.Folder) "Delete folder & apps" else "Remove",
                        danger = true, onClick = onRemove,
                    )
                } else {
                    // Sub-menu: choose the destination folder.
                    Text(
                        "Move to…", color = AbOnSurfaceMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    moveTargets.forEach { f ->
                        MenuRow(f.label, onClick = { onMoveToFolder(f.id) })
                    }
                    MenuRow("‹ Back", onClick = { pickFolder = false })
                }
            }
        }
    }
}

/** Two-mode delete confirmation for a folder. */
@Composable
private fun DeleteFolderDialog(
    folderLabel: String,
    hasContents: Boolean,
    onDismiss: () -> Unit,
    onDeleteKeepApps: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = AbSurface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.padding(24.dp).clickable(enabled = false) {},
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Delete \"$folderLabel\"?",
                    color = AbOnSurface, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (hasContents) {
                    MenuRow("Delete folder, keep apps in dock", onClick = onDeleteKeepApps)
                    MenuRow("Delete folder and its apps", danger = true, onClick = onDeleteAll)
                } else {
                    MenuRow("Delete folder", danger = true, onClick = onDeleteAll)
                }
            }
        }
    }
}

/** Appearance editor: color swatches + custom hue, and Auto/Preset/Symbol. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun FolderAppearanceEditor(
    folder: DockItem.Folder,
    onDismiss: () -> Unit,
    onApply: (
        color: String?, appearance: FolderAppearance, presetKey: String?, symbol: String?,
        iconPackPackage: String?, iconPackDrawable: String?,
    ) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var color by remember { mutableStateOf(parseFolderColor(folder.color)) }
    var appearance by remember { mutableStateOf(folder.appearance) }
    var presetKey by remember { mutableStateOf(folder.presetKey ?: FolderPreset.GENERIC.key) }
    var symbol by remember {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(folder.symbol ?: ""))
    }
    var hue by remember { mutableStateOf(0f) }
    // Custom icon-pack state.
    var packPkg by remember { mutableStateOf(folder.iconPackPackage) }
    var packDrawable by remember { mutableStateOf(folder.iconPackDrawable) }

    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = AbSurface,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.padding(24.dp).clickable(enabled = false) {},
        ) {
            Column(
                Modifier.padding(20.dp).widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Folder icon", color = AbOnSurface, style = MaterialTheme.typography.titleMedium)

                // Live preview.
                Box(Modifier.padding(vertical = 4.dp)) {
                    FolderIcon(
                        folder = folder.copy(
                            color = color.toHex(),
                            appearance = appearance,
                            presetKey = presetKey,
                            symbol = symbol.text.ifBlank { null },
                            iconPackPackage = packPkg,
                            iconPackDrawable = packDrawable,
                        ),
                        sizeDp = 72,
                    )
                }

                // Appearance mode segmented row.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("Auto", appearance == FolderAppearance.AUTO) {
                        appearance = FolderAppearance.AUTO
                    }
                    ModeChip("Preset", appearance == FolderAppearance.PRESET) {
                        appearance = FolderAppearance.PRESET
                    }
                    ModeChip("Symbol", appearance == FolderAppearance.SYMBOL) {
                        appearance = FolderAppearance.SYMBOL
                    }
                    ModeChip("Custom", appearance == FolderAppearance.CUSTOM) {
                        appearance = FolderAppearance.CUSTOM
                    }
                }

                // Mode-specific controls.
                when (appearance) {
                    FolderAppearance.PRESET -> {
                        Text("Preset", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FolderPreset.entries.forEach { p ->
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (p.key == presetKey) AbPrimary else AbSurfaceElevated
                                        )
                                        .clickable { presetKey = p.key },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(p.emblem, contentDescription = p.key, tint = Color.White,
                                        modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                    FolderAppearance.SYMBOL -> {
                        androidx.compose.material3.OutlinedTextField(
                            value = symbol,
                            onValueChange = {
                                // Keep just the first glyph/emoji.
                                symbol = it.copy(text = it.text.take(2))
                            },
                            singleLine = true,
                            label = { Text("Emoji / symbol", color = AbOnSurfaceMuted) },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = AbOnSurface, unfocusedTextColor = AbOnSurface,
                                focusedBorderColor = AbPrimaryBright, unfocusedBorderColor = AbSurfaceElevated,
                                cursorColor = AbPrimaryBright,
                            ),
                        )
                    }
                    FolderAppearance.AUTO -> {
                        Text(
                            "Shows a 2×2 preview of the first apps in the folder.",
                            color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    FolderAppearance.CUSTOM -> {
                        IconPackPicker(
                            context = context,
                            selectedPack = packPkg,
                            selectedDrawable = packDrawable,
                            onPick = { pkg, drawable ->
                                packPkg = pkg
                                packDrawable = drawable
                            },
                        )
                    }
                }

                // Color swatches.
                Text("Color", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FolderColorSwatches.forEach { sw ->
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(sw)
                                .clickable { color = sw }
                                .padding(2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (sw.toHex() == color.toHex()) {
                                Icon(Icons.Default.Check, contentDescription = null,
                                    tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                // Custom hue slider (tap/drag friendly; full saturation/value).
                Text("Custom color", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = hue,
                    onValueChange = {
                        hue = it
                        color = Color.hsv(it * 360f, 0.6f, 0.55f)
                    },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = AbPrimaryBright,
                        activeTrackColor = AbPrimary,
                        inactiveTrackColor = AbSurfaceElevated,
                    ),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text("Cancel", color = AbOnSurfaceMuted)
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        onApply(
                            color.toHex(),
                            appearance,
                            if (appearance == FolderAppearance.PRESET) presetKey else folder.presetKey,
                            if (appearance == FolderAppearance.SYMBOL) symbol.text.ifBlank { null } else folder.symbol,
                            if (appearance == FolderAppearance.CUSTOM) packPkg else folder.iconPackPackage,
                            if (appearance == FolderAppearance.CUSTOM) packDrawable else folder.iconPackDrawable,
                        )
                    }) { Text("Apply", color = AbAccent) }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AbPrimary else AbSurfaceElevated)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) AbOnSurface else AbOnSurfaceMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Custom icon-pack picker: choose an installed icon pack, then pick a drawable
 * from it. Pack enumeration + drawable-name parsing run off the main thread
 * (packs can list thousands of icons). The icon grid is lazy and capped to keep
 * it responsive; a search box filters by drawable name.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun IconPackPicker(
    context: android.content.Context,
    selectedPack: String?,
    selectedDrawable: String?,
    onPick: (pack: String?, drawable: String?) -> Unit,
) {
    var packs by remember { mutableStateOf<List<com.portalpad.app.data.IconPackManager.IconPack>>(emptyList()) }
    var loadingPacks by remember { mutableStateOf(true) }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingNames by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        packs = withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.portalpad.app.data.IconPackManager.installedPacks(context)
        }
        loadingPacks = false
    }
    // Load drawable names whenever the selected pack changes.
    LaunchedEffect(selectedPack) {
        val pkg = selectedPack
        if (pkg.isNullOrBlank()) { names = emptyList(); return@LaunchedEffect }
        loadingNames = true
        names = withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.portalpad.app.data.IconPackManager.drawableNames(context, pkg)
        }
        loadingNames = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            loadingPacks -> Text("Finding icon packs…", color = AbOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall)
            packs.isEmpty() -> Text(
                "No icon packs found. Install one (e.g. from an icon-pack app) to use this.",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
            else -> {
                Text("Icon pack", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                val selectedLabel = packs.firstOrNull { it.packageName == selectedPack }?.label
                    ?: "Choose a pack"
                var packMenuOpen by remember { mutableStateOf(false) }
                var packAnchorWidthPx by remember { mutableIntStateOf(0) }
                val packDensity = androidx.compose.ui.platform.LocalDensity.current
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { packAnchorWidthPx = it.width }
                            .clip(RoundedCornerShape(10.dp))
                            .background(AbSurfaceElevated)
                            .clickable { packMenuOpen = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            selectedLabel,
                            color = if (selectedPack != null) AbOnSurface else AbOnSurfaceMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Choose icon pack",
                            tint = AbOnSurfaceMuted,
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = packMenuOpen,
                        onDismissRequest = { packMenuOpen = false },
                        modifier = Modifier
                            .background(AbSurfaceElevated)
                            .width(with(packDensity) { packAnchorWidthPx.toDp() })
                            .heightIn(max = 280.dp),
                    ) {
                        packs.forEach { p ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        p.label,
                                        color = if (p.packageName == selectedPack) AbPrimaryBright else AbOnSurface,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                },
                                onClick = { packMenuOpen = false; onPick(p.packageName, null) },
                            )
                        }
                    }
                }
            }
        }

        if (!selectedPack.isNullOrBlank()) {
            if (loadingNames) {
                Text("Loading icons…", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            } else if (names.isNotEmpty()) {
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search icons", color = AbOnSurfaceMuted) },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AbOnSurface, unfocusedTextColor = AbOnSurface,
                        focusedBorderColor = AbPrimaryBright, unfocusedBorderColor = AbSurfaceElevated,
                        cursorColor = AbPrimaryBright,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                val filtered = remember(names, query) {
                    (if (query.isBlank()) names else names.filter { it.contains(query, ignoreCase = true) })
                        .take(300) // cap for responsiveness
                }
                val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                val gridThumb = AbOnSurfaceMuted.copy(alpha = 0.6f)
                Box(Modifier.fillMaxWidth().height(220.dp)) {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        state = gridState,
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(52.dp),
                        modifier = Modifier.fillMaxSize().padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filtered.size) { i ->
                            val name = filtered[i]
                            val sel = name == selectedDrawable
                            Box(
                                Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) AbPrimary else AbSurfaceElevated)
                                    .clickable { onPick(selectedPack, name) }
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                PackIconThumb(context, selectedPack, name)
                            }
                        }
                    }
                    // Always-visible scrollbar thumb on the right edge, sized to
                    // the visible fraction. Makes it clear the grid scrolls
                    // (otherwise the bottom rows look cut off).
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(4.dp)
                            .drawWithContent {
                                drawContent()
                                val info = gridState.layoutInfo
                                val total = info.totalItemsCount
                                if (total > 0 && info.visibleItemsInfo.isNotEmpty()) {
                                    val visible = info.visibleItemsInfo.size.toFloat()
                                    val frac = (visible / total).coerceIn(0.1f, 1f)
                                    if (frac < 1f) {
                                        val firstIdx = info.visibleItemsInfo.first().index.toFloat()
                                        val progress = (firstIdx / (total - visible)).coerceIn(0f, 1f)
                                        val thumbH = size.height * frac
                                        val thumbY = (size.height - thumbH) * progress
                                        drawRoundRect(
                                            color = gridThumb,
                                            topLeft = androidx.compose.ui.geometry.Offset(0f, thumbY),
                                            size = androidx.compose.ui.geometry.Size(size.width, thumbH),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2),
                                        )
                                    }
                                }
                            },
                    )
                }
            } else {
                Text("This pack exposed no icons we could read.",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Small async thumbnail of one icon-pack drawable. */
@Composable
private fun PackIconThumb(context: android.content.Context, pack: String, name: String) {
    var bmp by remember(pack, name) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(pack, name) {
        bmp = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.portalpad.app.data.IconPackManager.loadIcon(context, pack, name)
                    ?.let { d -> d.toBitmap(96, 96).asImageBitmap() }
            }.getOrNull()
        }
    }
    bmp?.let {
        androidx.compose.foundation.Image(bitmap = it, contentDescription = name,
            modifier = Modifier.size(40.dp))
    }
}

/**
 * Identifies a dock item being edited via long-press. Carries the item and its
 * current index so the menu can offer move-left/move-right operations.
 */
private data class ItemEditTarget(val item: DockItem, val index: Int)

/**
 * Modal action sheet shown when a dock item is long-pressed. Lets the user
 * reorder, drop into a folder, or remove the item — the dock's equivalent of
 * a desktop right-click menu, given that drag-and-drop on overlay windows is
 * unreliable across OEMs.
 */
@Composable
private fun ItemEditMenu(
    item: DockItem,
    index: Int,
    config: DockConfig,
    onDismiss: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onRemove: () -> Unit,
    onMoveIntoFolder: (folderId: String) -> Unit,
    onRename: () -> Unit,
    onNewFolderWith: () -> Unit,
    onReorder: () -> Unit,
    onRemoveMode: () -> Unit,
    theme: DockMenuTheme,
) {
    val folders = config.items.filterIsInstance<DockItem.Folder>().filter { it.id != item.id }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = theme.solid,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 8.dp,
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 360.dp),
        ) {
          Box(Modifier.background(theme.brush)) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    item.label,
                    color = theme.content, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                MenuRow("Rename", onClick = onRename, contentColor = theme.content, dangerColor = theme.danger)
                MenuRow("Reorder apps (drag mode)", onClick = onReorder, contentColor = theme.content, dangerColor = theme.danger)
                MenuRow("Remove apps (remove mode)", danger = true, onClick = onRemoveMode, contentColor = theme.content, dangerColor = theme.danger)
                MenuRow("Move ←", enabled = index > 0, onClick = onMoveLeft, contentColor = theme.content, dangerColor = theme.danger)
                MenuRow("Move →", enabled = index < config.items.size - 1, onClick = onMoveRight, contentColor = theme.content, dangerColor = theme.danger)
                if (item is DockItem.Shortcut && folders.isNotEmpty()) {
                    folders.forEach { folder ->
                        MenuRow("Move into \"${folder.label}\"", contentColor = theme.content, dangerColor = theme.danger) { onMoveIntoFolder(folder.id) }
                    }
                }
                if (item is DockItem.Shortcut) {
                    MenuRow("New folder with this app", contentColor = theme.content, dangerColor = theme.danger) { onNewFolderWith() }
                }
                MenuRow(
                    if (item is DockItem.Folder) "Delete folder" else "Remove",
                    danger = true,
                    onClick = onRemove,
                    contentColor = theme.content,
                    dangerColor = theme.danger,
                )
            }
          }
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    contentColor: Color = AbOnSurface,
    dangerColor: Color = AbAccent,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            color = when {
                !enabled -> contentColor.copy(alpha = 0.35f)
                danger -> dangerColor
                else -> contentColor
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** Return a copy of [config] with the item identified by [id] relabeled. Recurses
 *  into folder contents so nested items rename too. */
internal fun renameDockItem(config: DockConfig, id: String, newLabel: String): DockConfig {
    fun renameIn(items: List<DockItem>): List<DockItem> = items.map { item ->
        when (item) {
            is DockItem.Shortcut -> if (item.id == id) item.copy(label = newLabel) else item
            is DockItem.Folder ->
                if (item.id == id) item.copy(label = newLabel)
                else item.copy(contents = renameIn(item.contents))
        }
    }
    return config.copy(items = renameIn(config.items))
}

/**
 * Launch the dock app-picker (AppPickerActivity) on the PHONE display, not the
 * external display. The dock renders inside the external display's context, so
 * a plain ctx.startActivity inherits that display and the picker appears on the
 * glasses — which feels awkward (the keyboard/touch are on the phone). Forcing
 * setLaunchDisplayId(DEFAULT_DISPLAY) sends it to the phone. A brief glasses
 * message explains where it went, mirroring the search/mic pivot.
 */
internal fun launchDockPickerOnPhone(ctx: android.content.Context, target: String) {
    val app = com.portalpad.app.PortalPadApp.instance
    // Brief glasses message (same pattern as search/mic launches).
    runCatching {
        val extId = app.externalDisplayId.value
        if (extId != null) {
            val dm = ctx.getSystemService(android.content.Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            val disp = dm.getDisplay(extId)
            if (disp != null) {
                com.portalpad.app.presentation.GlassesToast.show(
                    app, disp, "Opening app picker on your phone…", 3500L,
                )
            }
        }
    }
    runCatching {
        val intent = android.content.Intent(
            app, com.portalpad.app.ui.settings.AppPickerActivity::class.java,
        )
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("target", target)
        // Force the phone (default) display regardless of the dock's context.
        val opts = android.app.ActivityOptions.makeBasic()
            .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
        app.startActivity(intent, opts.toBundle())
    }
}

/**
 * Open the rename screen on the PHONE (display 0) for a dock item. Text input on
 * the external-display overlay was unstable (IME → black wallpaper → bounce), so
 * rename runs as a phone activity like the other launchers.
 */
internal fun launchRenameOnPhone(ctx: android.content.Context, itemId: String, currentLabel: String) {
    val app = com.portalpad.app.PortalPadApp.instance
    runCatching {
        val extId = app.externalDisplayId.value
        if (extId != null) {
            val dm = ctx.getSystemService(android.content.Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            val disp = dm.getDisplay(extId)
            if (disp != null) {
                com.portalpad.app.presentation.GlassesToast.show(
                    app, disp, "Rename on your phone…", 3500L,
                )
            }
        }
    }
    runCatching {
        val intent = android.content.Intent(
            app, com.portalpad.app.ui.settings.RenameActivity::class.java,
        )
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("itemId", itemId)
            .putExtra("current", currentLabel)
        val opts = android.app.ActivityOptions.makeBasic()
            .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
        app.startActivity(intent, opts.toBundle())
    }
}

/**
 * Open a SYSTEM setting screen fullscreen on the PHONE (display 0) with a glasses
 * cue and the red "Tap here to exit" bands — the same pattern the quick-settings
 * tiles use. These system screens can't move to the VD, so without the cue + bands
 * a tap on the glasses would look like a no-op. Used by the dock battery to open
 * battery management. [action] is an intent action string (e.g. POWER_USAGE_SUMMARY).
 */
internal fun launchSystemSettingOnPhone(
    ctx: android.content.Context,
    action: String,
    glassesMessage: String,
) {
    val app = com.portalpad.app.PortalPadApp.instance
    // Glasses cue.
    runCatching {
        val extId = app.externalDisplayId.value
        if (extId != null) {
            val dm = ctx.getSystemService(android.content.Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            dm.getDisplay(extId)?.let {
                com.portalpad.app.presentation.GlassesToast.show(app, it, glassesMessage, 3500L)
            }
        }
    }
    var launched = false
    if (app.access.isReady) {
        // Don't let the launch's window-change storm re-front the trackpad.
        com.portalpad.app.service.PortalPadForegroundService.suppressDisplayChanges(3000)
        launched = runCatching {
            val out = app.access.execShell("am start --display 0 -a $action")
            !out.contains("Error:", ignoreCase = true) &&
                !out.contains("Exception", ignoreCase = true)
        }.getOrDefault(false)
        if (launched) {
            // Frame the phone window with the "Tap here to exit" bands.
            com.portalpad.app.service.PortalPadForegroundService.attachPhoneExitBands(action)
        }
    }
    if (!launched) runCatching {
        app.startActivity(
            android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Like [launchSystemSettingOnPhone] but targets an explicit OEM component
 * (am start -n pkg/activity) — used where the page we want has no clean public
 * action (e.g. Samsung's battery page com.samsung.android.lool/…BatteryActivity).
 * The exit bands track [pkg] directly. If the component launch fails (e.g. on a
 * non-Samsung device where it doesn't exist), we fall back to [fallbackAction]
 * so the button still opens *a* battery screen with working bands.
 */
internal fun launchSystemComponentOnPhone(
    ctx: android.content.Context,
    component: String,
    pkg: String,
    fallbackAction: String,
    glassesMessage: String,
) {
    val app = com.portalpad.app.PortalPadApp.instance
    // Glasses cue.
    runCatching {
        val extId = app.externalDisplayId.value
        if (extId != null) {
            val dm = ctx.getSystemService(android.content.Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            dm.getDisplay(extId)?.let {
                com.portalpad.app.presentation.GlassesToast.show(app, it, glassesMessage, 3500L)
            }
        }
    }
    if (app.access.isReady) {
        // Don't let the launch's window-change storm re-front the trackpad.
        com.portalpad.app.service.PortalPadForegroundService.suppressDisplayChanges(3000)
        val compOk = runCatching {
            val out = app.access.execShell("am start --display 0 -n $component")
            !out.contains("Error:", ignoreCase = true) &&
                !out.contains("Exception", ignoreCase = true)
        }.getOrDefault(false)
        if (compOk) {
            com.portalpad.app.service.PortalPadForegroundService.attachPhoneExitBandsForPackage(pkg)
            return
        }
        // Component not present (non-Samsung / renamed) — fall back to the action.
        val actOk = runCatching {
            val out = app.access.execShell("am start --display 0 -a $fallbackAction")
            !out.contains("Error:", ignoreCase = true) &&
                !out.contains("Exception", ignoreCase = true)
        }.getOrDefault(false)
        if (actOk) {
            com.portalpad.app.service.PortalPadForegroundService.attachPhoneExitBands(fallbackAction)
            return
        }
    }
    // No elevated access or both shell launches failed — best-effort plain launch.
    runCatching {
        app.startActivity(
            android.content.Intent(fallbackAction).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Which part of the clock to render — time and date are shown separately on
 *  the dock now (time bottom-right under the battery, date up on the status row). */
private enum class ClockPart { TIME, DATE }

@Composable
private fun DockClock(
    labelSizeSp: Float,
    part: ClockPart = ClockPart.TIME,
    textColor: Color = Color.White,
    timeFormat: com.portalpad.app.data.ClockTimeFormat = com.portalpad.app.data.ClockTimeFormat.AUTO,
    dateFormat: com.portalpad.app.data.ClockDateFormat = com.portalpad.app.data.ClockDateFormat.AUTO,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // Tick every minute (aligned to the next minute boundary).
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            val msToNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
            kotlinx.coroutines.delay(msToNextMinute.coerceAtLeast(1_000L))
        }
    }
    val is24 = when (timeFormat) {
        com.portalpad.app.data.ClockTimeFormat.H24 -> true
        com.portalpad.app.data.ClockTimeFormat.H12 -> false
        com.portalpad.app.data.ClockTimeFormat.AUTO -> android.text.format.DateFormat.is24HourFormat(ctx)
    }
    val timeFmt = remember(is24) {
        java.text.SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", java.util.Locale.getDefault())
    }
    val dateFmt: java.text.DateFormat = remember(dateFormat) {
        when (dateFormat) {
            com.portalpad.app.data.ClockDateFormat.MDY ->
                java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.getDefault())
            com.portalpad.app.data.ClockDateFormat.DMY ->
                java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
            com.portalpad.app.data.ClockDateFormat.YMD ->
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            com.portalpad.app.data.ClockDateFormat.AUTO ->
                android.text.format.DateFormat.getDateFormat(ctx)
        }
    }
    val date = java.util.Date(now)
    Text(
        text = if (part == ClockPart.TIME) timeFmt.format(date) else dateFmt.format(date),
        color = textColor.copy(alpha = 0.80f),
        fontSize = nonScalingSp(labelSizeSp * 1.55f),
        maxLines = 1,
        style = androidx.compose.ui.text.TextStyle(shadow = dockTextShadow()),
    )
}

/**
 * Bluetooth (on/off) and Wi-Fi (connected/disconnected) status glyphs for the
 * dock, sized to sit alongside the battery as a set. Bluetooth shows enabled
 * state (no runtime permission needed); Wi-Fi shows whether there's an active
 * Wi-Fi connection. Both update live. Rendered in order BT | Wi-Fi, and the
 * caller places the battery to their right.
 */
@Composable
private fun DockConnectivity(
    iconSizeDp: Float,
    strongColor: Color = Color.White,
    mutedColor: Color = AbOnSurfaceMuted,
    hoverCursorPx: Float = Float.NaN,
    hoverOnBand: Boolean = false,
    hoverHorizontal: Boolean = true,
    crossAxisCursorPx: Float = Float.NaN,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // ── Bluetooth on/off (BluetoothAdapter.isEnabled; live via ACTION_STATE_CHANGED) ──
    var btOn by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        fun current(): Boolean = runCatching {
            val mgr = ctx.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager
            mgr?.adapter?.isEnabled == true
        }.getOrDefault(false)
        btOn = current()
        val filter = android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) { btOn = current() }
        }
        runCatching { ctx.registerReceiver(receiver, filter) }
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }

    // ── Connectivity + signal levels (centralized in SignalMonitor) ──
    val signal = remember(ctx) { (ctx.applicationContext as com.portalpad.app.PortalPadApp).signal }
    val wifiConnected by signal.wifiConnected.collectAsState()
    val vpnActive by signal.vpnActive.collectAsState()
    val cellularActive by signal.cellularActive.collectAsState()
    val wifiLevel by signal.wifiLevel.collectAsState()
    val cellLevel by signal.cellLevel.collectAsState()
    val cellType by signal.cellType.collectAsState()
    // Whether the device even has a cellular radio — Wi-Fi-only devices/tablets
    // hide the cellular glyph (and the menu's Mobile data tile) entirely.
    val hasCellular = remember {
        runCatching {
            ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)
        }.getOrDefault(false)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        // Connectivity/signal is now centralized in SignalMonitor (read above);
        // the per-render-site ConnectivityManager callback that used to live here
        // is gone. SignalMonitor is started/stopped by the foreground service.
        onDispose { }
    }

    // Airplane mode (free: Settings.Global + ACTION_AIRPLANE_MODE broadcast). When
    // on, the whole cluster collapses to a single airplane glyph (see render).
    fun airplaneNow(): Boolean = runCatching {
        android.provider.Settings.Global.getInt(
            ctx.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0,
        ) != 0
    }.getOrDefault(false)
    var airplaneOn by remember { mutableStateOf(airplaneNow()) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) { airplaneOn = airplaneNow() }
        }
        runCatching {
            ctx.registerReceiver(receiver, android.content.IntentFilter("android.intent.action.AIRPLANE_MODE"))
        }
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }

    // The whole connectivity cluster is ONE affordance now: hovering anywhere
    // over it highlights the row (signaling it's clickable), and a tap opens the
    // quick-settings flyout (QuickSettingsOverlay). The old per-icon hover
    // scale, click-pulse, tooltips, and individual taps are gone — the panel
    // carries the actions instead. Battery is a separate sibling, not part of
    // this row, so it's untouched.
    //
    // Row hover is computed from the injected cursor position against the row's
    // captured bounds (cursor px don't fire onGloballyPositioned, so we stash the
    // bounds in state and re-test each recomposition as the cursor moves).
    var rowX by remember { mutableStateOf(0f) }
    var rowY by remember { mutableStateOf(0f) }
    var rowW by remember { mutableStateOf(0) }
    var rowH by remember { mutableStateOf(0) }
    val rowHovered = if (rowW == 0 || hoverCursorPx.isNaN() || crossAxisCursorPx.isNaN()) {
        false
    } else {
        val margin = 10f
        val mainMin: Float; val mainMax: Float; val crossMin: Float; val crossMax: Float
        if (hoverHorizontal) {
            mainMin = rowX - margin; mainMax = rowX + rowW + margin
            crossMin = rowY - margin; crossMax = rowY + rowH + margin
        } else {
            mainMin = rowY - margin; mainMax = rowY + rowH + margin
            crossMin = rowX - margin; crossMax = rowX + rowW + margin
        }
        hoverCursorPx in mainMin..mainMax && crossAxisCursorPx in crossMin..crossMax
    }
    val highlight by animateFloatAsState(if (rowHovered) 1f else 0f, label = "connRowHover")
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .onGloballyPositioned { c ->
                val p = c.positionInRoot(); rowX = p.x; rowY = p.y
                rowW = c.size.width; rowH = c.size.height
            }
            .clip(RoundedCornerShape(10.dp))
            .background(strongColor.copy(alpha = 0.16f * highlight))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { com.portalpad.app.service.PortalPadForegroundService.toggleQuickSettingsPanel() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        // VPN↔BT↔Wi-Fi↔Cellular spacing.
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (airplaneOn) {
            // Airplane mode collapses the whole cluster to a single airplane glyph
            // where mobile sat (hard replace — the menu remains the source of truth
            // for per-radio state). The Row wraps this single icon, so the shared
            // hover-highlight auto-sizes down to a small icon-sized highlight.
            ConnIcon(
                Icons.Default.AirplanemodeActive,
                true, strongColor, mutedColor, iconSizeDp, "Airplane mode on",
            )
        } else {
            ConnIcon(Icons.Default.VpnKey, vpnActive, strongColor, mutedColor, iconSizeDp,
                if (vpnActive) "VPN connected" else "VPN off")
            ConnIcon(if (btOn) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                btOn, strongColor, mutedColor, iconSizeDp, if (btOn) "Bluetooth on" else "Bluetooth off")
            WifiSignalIcon(
                connected = wifiConnected, level = wifiLevel,
                strong = strongColor, muted = mutedColor, sizeDp = iconSizeDp,
            )
            // Cellular — after Wi-Fi, before the (separate) battery. Bars come from
            // SignalStrength.getLevel() (permission-free); the network-type label
            // (5G/LTE) is shown only when READ_PHONE_STATE was granted in setup
            // (cellType non-null). Greys out when there's no cellular data. Hidden
            // on Wi-Fi-only devices.
            if (hasCellular) {
                CellSignalIcon(
                    active = cellularActive, level = cellLevel, type = cellType,
                    showType = !wifiConnected,
                    strong = strongColor, muted = mutedColor, sizeDp = iconSizeDp,
                )
            }
        }
    }
}

/** Wi-Fi strength glyph: a fan (dot + 3 arcs) with `level` (0..4 → 0..3) arcs lit
 *  in the strong tint, the rest muted. level < 0 (unknown) shows full when connected;
 *  disconnected greys the whole fan. Matches the cluster's tint-based on/off style. */
@Composable
private fun WifiSignalIcon(
    connected: Boolean,
    level: Int,
    strong: Color,
    muted: Color,
    sizeDp: Float,
) {
    val mutedArc = muted.copy(alpha = 0.35f)
    val activeArcs = when {
        !connected -> 0
        level < 0 -> 3
        level <= 0 -> 0
        level == 1 -> 1
        level == 2 -> 2
        else -> 3
    }
    androidx.compose.foundation.Canvas(
        Modifier.padding(start = (sizeDp * 0.04f).dp, end = (sizeDp * 0.10f).dp).size((sizeDp * 0.34f).dp),
    ) {
        val w = size.width
        val cx = w / 2f
        val cy = size.height * 0.80f
        drawCircle(
            color = if (connected) strong else mutedArc,
            radius = w * 0.07f,
            center = androidx.compose.ui.geometry.Offset(cx, cy),
        )
        for (i in 0 until 3) {
            val r = w * (0.22f + 0.17f * i)
            drawArc(
                color = if (i < activeArcs) strong else mutedArc,
                startAngle = 215f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(cx - r, cy - r),
                size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = w * 0.085f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                ),
            )
        }
    }
}

/** Cellular strength glyph: 5 ascending bars. Connected fills `level+1` (so a top
 *  reading reads 5/5 and the weakest connected still shows 1 bar). Inactive greys all
 *  bars; active-but-unknown shows all muted (strength pending). A network-type label
 *  (5G/LTE) is drawn after the bars when READ_PHONE_STATE is granted AND Wi-Fi is off
 *  (Android hides the type while data rides Wi-Fi). */
@Composable
private fun CellSignalIcon(
    active: Boolean,
    level: Int,
    type: String?,
    showType: Boolean,
    strong: Color,
    muted: Color,
    sizeDp: Float,
) {
    val mutedBar = muted.copy(alpha = 0.4f)
    val filled = when {
        !active -> 0
        level < 0 -> 0          // connected but strength not yet known → all muted
        else -> level.coerceIn(0, 4) + 1
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (active && showType && !type.isNullOrBlank()) {
            Text(
                type,
                color = strong,
                fontSize = (sizeDp * 0.30f).sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = (sizeDp * 0.04f).dp),
            )
        }
        androidx.compose.foundation.Canvas(
            Modifier.padding(horizontal = (sizeDp * 0.04f).dp).size((sizeDp * 0.34f).dp),
        ) {
            val n = 5
            val gap = size.width * 0.11f
            val barW = (size.width - gap * (n - 1)) / n
            val maxH = size.height * 0.92f
            for (i in 0 until n) {
                val h = maxH * (0.32f + 0.68f * (i + 1) / n)
                drawRoundRect(
                    color = if (i < filled) strong else mutedBar,
                    topLeft = androidx.compose.ui.geometry.Offset(i * (barW + gap), size.height - h),
                    size = androidx.compose.ui.geometry.Size(barW, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.3f, barW * 0.3f),
                )
            }
        }
    }
}

/** A plain status glyph in the connectivity cluster — no hover/pulse/tooltip/
 *  click of its own (the whole row is the clickable unit). On/off shown via tint. */
@Composable
private fun ConnIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    on: Boolean,
    strongColor: Color,
    mutedColor: Color,
    iconSizeDp: Float,
    contentDescription: String,
) {
    Box(
        Modifier.size((iconSizeDp * 0.42f).dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (on) strongColor.copy(alpha = 0.85f) else mutedColor.copy(alpha = 0.55f),
            modifier = Modifier.size((iconSizeDp * 0.34f).dp),
        )
    }
}

/**
 * Proportional battery indicator: a battery outline whose inner bar fills to the
 * current level, plus the % text and a charging bolt when plugged in. Single
 * fill color, tinted red when very low (<= 15%). Observes battery changes live.
 */
@Composable
private fun DockNotificationBell(
    iconSizeDp: Float,
    strongColor: Color = Color.White,
    mutedColor: Color = AbOnSurfaceMuted,
    dockBgColor: Color = Color(0xFF15131A),
    hoverCursorPx: Float = Float.NaN,
    hoverOnBand: Boolean = false,
    hoverHorizontal: Boolean = true,
    crossAxisCursorPx: Float = Float.NaN,
    activeTooltip: androidx.compose.runtime.MutableState<Pair<String, Float>?>? = null,
) {
    // Live notification count from the same store that feeds the external-display
    // notification panel.
    val items by com.portalpad.app.data.NotificationStore.items.collectAsState()
    val count = items.size
    val on = count > 0

    // Badge color: default attention-red, but if the dock background is red-ish
    // (so red wouldn't stand out) fall back to a high-contrast blue. A thin ring
    // in the dock's strong color separates it from any background.
    val badgeColor = run {
        val red = Color(0xFFE5484D)
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (dockBgColor.red * 255).toInt(),
            (dockBgColor.green * 255).toInt(),
            (dockBgColor.blue * 255).toInt(),
            hsv,
        )
        val reddish = hsv[1] > 0.25f && (hsv[0] < 25f || hsv[0] > 335f)
        if (reddish) Color(0xFF2563EB) else red
    }

    val (hover, hoverMod) = rememberDockHoverFraction(
        cursorAxisInWindowPx = hoverCursorPx,
        cursorOnBand = hoverOnBand,
        iconSizeDp = iconSizeDp,
        horizontal = hoverHorizontal,
        crossAxisCursorPx = crossAxisCursorPx,
        twoDimensional = true,
    )
    // Calm highlight matching the connectivity status cluster: a rounded square
    // that fades in on hover (no scale-pop, no click-pulse, no tooltip). The badge
    // stays as the unread indicator. Click opens the notification panel.
    val hovered = hover > 0.45f
    val highlight by animateFloatAsState(if (hovered) 1f else 0f, label = "bellHover")
    val baseAlpha = if (on) 0.85f else 0.55f
    val iconAlpha = (baseAlpha + 0.10f * hover).coerceAtMost(1f)
    val tint = if (on) strongColor.copy(alpha = iconAlpha) else mutedColor.copy(alpha = iconAlpha)

    Box(
        Modifier
            .then(hoverMod)
            // NOTE: the hover highlight is NOT drawn here anymore (it used to be a
            // wide 0.92×0.80 rounded rect). It's now a tight rounded square hugging
            // the bell inside the cluster below. This Box keeps its size only as the
            // click target + layout footprint so neighbor spacing is unchanged.
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { com.portalpad.app.service.PortalPadForegroundService.toggleNotificationPanel() }
            .padding(5.dp)
            .width((iconSizeDp * 0.92f).dp)
            .height((iconSizeDp * 0.80f).dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Bell + badge as one cluster, sized exactly to the bell. The badge
        // anchors to THIS box's corner (the bell's real top-right), and every
        // badge dimension below is a FIXED FRACTION of the bell — no clamps, no
        // absolute dp — so the badge is a scaled copy on every device. It's
        // LEFT-anchored at the corner and grows rightward, so a 2-digit circle
        // and a "99+" pill overlap the bell identically; only the pill's right
        // side extends further (into empty space toward the clock). That keeps
        // the badge↔bell gap the same regardless of the count.
        Box(
            Modifier
                .offset(x = -(iconSizeDp * 0.04f).dp)   // small left nudge for cluster↔clock spacing
                .size((iconSizeDp * 0.40f).dp),
            contentAlignment = Alignment.Center,
        ) {
            // Bell geometry + badge anchor, hoisted to cluster level so the hover
            // highlight (drawn behind, regardless of count) can size itself to
            // enclose the badge too. All values are FIXED fractions of the bell /
            // a fixed badge dp, so they scale identically on every device.
            val bellDp = iconSizeDp * 0.40f
            // FIXED badge diameter (dp) — kept at the previous size so the badge and
            // its "99+" text read clearly. (Tried a bell-proportional badge; it came
            // out too small.) The hover-highlight off-centre issue was fixed
            // separately by centring the highlight on the bell, so the badge can stay
            // this size even though it's a bit larger than the bell. ← tune to resize.
            val badgeDia = 33f                             // circle diameter (dp)
            // Badge sits at the bell's top-right. anchorXf uses a SMALL overlap
            // factor so it sits a bit RIGHT of the corner; lower 0.38f to push it
            // further right, raise it to pull it left. Raw Float dp (not .dp) so
            // the highlight below can do arithmetic with it.
            val anchorXf = bellDp - badgeDia * 0.38f       // badge left  (cluster coords)
            val anchorYf = -(badgeDia * 0.45f)             // badge top   (negative = above)
            val hasBadge = count > 0

            // Hover highlight: a rounded square CENTERED on the bell (matching the
            // search / mic controls). The small corner badge overhangs the top-right,
            // which is the normal badge look. requiredSize lets it exceed the
            // bell-sized cluster; drawn first → sits behind the icon + badge.
            run {
                val pad = bellDp * 0.16f
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .requiredSize((bellDp + 2f * pad).dp)
                        .background(
                            strongColor.copy(alpha = 0.16f * highlight),
                            RoundedCornerShape(percent = 34),
                        ),
                )
            }
            Icon(
                Icons.Default.Notifications,
                contentDescription = if (count > 0) "Notifications ($count)" else "Notifications",
                tint = tint,
                modifier = Modifier.size((iconSizeDp * 0.40f).dp),
            )
            if (hasBadge) {
                val badgeRing = (badgeDia * 0.08f).dp          // ring thickness
                val badgeText = if (count > 99) "99+" else count.toString()
                // Text is a fraction of the diameter, kept well under the point
                // where it would touch the curve so there's breathing room: the
                // 3-char "99+" (widest) uses a smaller ratio than 1–2 digits.
                val badgeTextSp = badgeDia * (if (badgeText.length >= 3) 0.36f else 0.48f)
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = anchorXf.dp, y = anchorYf.dp)
                        // requiredSize (NOT size): the parent cluster box is only
                        // bell-sized, and plain size() would be clamped to it — so
                        // badgeDia had no effect past the bell size. requiredSize
                        // ignores the parent constraint and forces the true diameter
                        // (it overflows the cluster, which isn't clipped).
                        .requiredSize(badgeDia.dp)             // FIXED square → perfect circle
                        // Circle drawn via the background/border SHAPE (not .clip)
                        // so the centered number is never clipped by the curve.
                        .background(badgeColor, RoundedCornerShape(percent = 50))
                        // Ring in the dock background color so the badge reads as
                        // "cut into" the bell (system-badge look).
                        .border(badgeRing, dockBgColor, RoundedCornerShape(percent = 50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        badgeText,
                        color = Color.White,
                        fontSize = nonScalingSp(badgeTextSp),
                        maxLines = 1,
                        softWrap = false,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Proportional battery indicator: a battery outline whose inner bar fills to the
 * current level, plus the % text and a charging bolt when plugged in. Single
 * fill color, tinted red when very low (<= 15%). Observes battery changes live.
 */
@Composable
private fun DockBattery(
    labelSizeSp: Float,
    iconSizeDp: Float,
    strongColor: Color = Color.White,
    mutedColor: Color = AbOnSurfaceMuted,
    hoverCursorPx: Float = Float.NaN,
    hoverOnBand: Boolean = false,
    hoverHorizontal: Boolean = true,
    crossAxisCursorPx: Float = Float.NaN,
    onClick: () -> Unit = {},
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var level by remember { mutableStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                intent ?: return
                val l = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                if (l >= 0 && scale > 0) level = (l * 100 / scale)
                val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
            }
        }
        // Sticky broadcast — returns the current value immediately.
        val sticky = ctx.registerReceiver(receiver, filter)
        sticky?.let { receiver.onReceive(ctx, it) }
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    val pct = level.coerceIn(0, 100)
    val low = pct in 0..15
    // Charging state wins over low: if it's plugged in, the low condition is
    // already being resolved, so green ("charging, you're fine") is more accurate
    // and actionable than a red alarm. The % text still shows the real level.
    val chargeGreen = Color(0xFF2E9E5B)
    val fillColor = when {
        charging -> chargeGreen
        low -> Color(0xFFE5484D)
        else -> strongColor.copy(alpha = 0.85f)
    }
    val outlineColor = if (charging) chargeGreen else mutedColor
    val bodyW = (iconSizeDp * 0.56f).dp
    val bodyH = (iconSizeDp * 0.27f).dp
    // Hover highlight matching the status cluster: a rounded square that fades in
    // on cursor proximity (2D, so it only lights when the cursor is actually on the
    // battery's row — not above/below it). Click opens battery management on the
    // phone with the glasses cue + "Tap here to exit" bands.
    val (hover, hoverMod) = rememberDockHoverFraction(
        cursorAxisInWindowPx = hoverCursorPx,
        cursorOnBand = hoverOnBand,
        iconSizeDp = iconSizeDp,
        horizontal = hoverHorizontal,
        crossAxisCursorPx = crossAxisCursorPx,
        twoDimensional = true,
    )
    val hovered = hover > 0.45f
    val highlight by animateFloatAsState(if (hovered) 1f else 0f, label = "batteryHover")
    androidx.compose.foundation.layout.Row(
        modifier = hoverMod
            .background(
                strongColor.copy(alpha = 0.16f * highlight),
                RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier.width(bodyW).height(bodyH),
            ) {
                val nubW = size.width * 0.06f
                val bodyRight = size.width - nubW
                val stroke = size.height * 0.10f
                val r = size.height * 0.18f
                // Battery body outline.
                drawRoundRect(
                    color = outlineColor,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(bodyRight, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
                // Terminal nub.
                drawRoundRect(
                    color = outlineColor,
                    topLeft = androidx.compose.ui.geometry.Offset(bodyRight, size.height * 0.3f),
                    size = androidx.compose.ui.geometry.Size(nubW, size.height * 0.4f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(nubW / 2f, nubW / 2f),
                )
                // Proportional fill (inset from the outline).
                val pad = stroke * 1.6f
                val maxFillW = bodyRight - pad * 2f
                val fillW = (maxFillW * (pct / 100f)).coerceAtLeast(0f)
                drawRoundRect(
                    color = fillColor,
                    topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                    size = androidx.compose.ui.geometry.Size(fillW, size.height - pad * 2f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.6f, r * 0.6f),
                )
            }
            if (charging) {
                // White bolt with a subtle dark shadow behind it, so it reads on
                // ANY background (green fill, or the dark dock when the fill is
                // short at low %). Black-only was invisible on dark backgrounds.
                val boltSize = bodyH * 0.8f
                val shadowOffset = bodyH * 0.04f
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = null,
                    tint = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier
                        .size(boltSize)
                        .offset(x = shadowOffset, y = shadowOffset),
                )
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = "Charging",
                    tint = Color.White,
                    modifier = Modifier.size(boltSize),
                )
            }
        }
        Text(
            text = if (level < 0) "--%" else "$pct%",
            color = strongColor,
            fontSize = nonScalingSp(labelSizeSp * 1.66f),
            maxLines = 1,
            style = androidx.compose.ui.text.TextStyle(shadow = dockTextShadow()),
        )
    }
}
