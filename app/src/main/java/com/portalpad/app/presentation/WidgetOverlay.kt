package com.portalpad.app.presentation

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.portalpad.app.data.MAX_OVERLAY_WIDGETS
import com.portalpad.app.data.OverlayWidget
import com.portalpad.app.data.WidgetOverlayConfig
import com.portalpad.app.service.WidgetHostManager
import com.portalpad.app.ui.theme.PortalPadTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The WIDGET OVERLAY: a summonable layer of Android app widgets on the external
 * display, toggled by assigning "Widget Overlay" to the Home or Back button
 * (AppEntry.isWidgetOverlay sentinel — dispatched in launchEntry / the PFS home
 * fallback, never launched as an app).
 *
 * Window plumbing mirrors [QuickSettingsOverlay]: a TYPE_ACCESSIBILITY_OVERLAY
 * (2032, via [OverlayHost]) MATCH_PARENT window on the VD, NOT_FOCUSABLE (taps
 * arrive via the injected cursor). Because ALL PortalPad chrome lives on the VD
 * and system-mirror mode retargets the external panel to the VD's layerStack,
 * this layer is visible in BOTH mirror modes for the same reason the dock is.
 *
 * Chrome (grid math, chips, placeholders) composes at the PINNED baseline
 * density like the dock/QS so the DPI slider never resizes it; the widget
 * CONTENT inside each AppWidgetHostView renders at the display context's own
 * density, like app content does.
 *
 * Layout is a fixed-cell grid (CELL_DP dp cells, GAP_DP gaps) anchored top-left
 * inside a SAFE AREA that reserves a bottom inset for the dock — so widgets
 * never sit under the dock band. Positions live in GRID COORDINATES in
 * [WidgetOverlayConfig] (one DataStore key → included in the app backup);
 * ultrawide simply exposes more columns and out-of-range widgets are skipped at
 * render time, never mutated.
 */
class WidgetOverlay(
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

    var isShowing: Boolean = false
        private set

    fun show() {
        if (isShowing) return
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val composeView = ComposeView(displayContext).apply {
            setViewTreeLifecycleOwner(this@WidgetOverlay)
            setViewTreeSavedStateRegistryOwner(this@WidgetOverlay)
            setViewTreeViewModelStoreOwner(this@WidgetOverlay)
            setContent {
                // Pinned baseline density — same rationale as DockOverlay/QS: the
                // DPI slider must not resize the layer's chrome.
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(BASELINE_DENSITY, 1f),
                ) {
                    PortalPadTheme { LayerContent() }
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHost.windowType,
            // NOT_FOCUSABLE — same as QS: cursor taps, no IME, no focus churn.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )

        runCatching { windowManager.addView(composeView, params) }
            .onFailure { Log.e(TAG, "addView failed", it); return }
        view = composeView
        isShowing = true
        PortalPadApp.instance.injector.cursorOverWidgetOverlay = true
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        com.portalpad.app.service.PortalPadForegroundService.raiseCursor()
        Log.d(TAG, "Widget overlay added to display $displayId")
    }

    fun dismiss() {
        if (!isShowing) return
        runCatching { PortalPadApp.instance.injector.widgetEditRects = emptyList() }
        runCatching { PortalPadApp.instance.injector.cursorOverWidgetOverlay = false }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        _viewModelStore.clear()
        isShowing = false
    }

    @Composable
    private fun LayerContent() {
        val app = PortalPadApp.instance
        val prefs = app.prefs
        val scope = rememberCoroutineScope()
        val cfg by prefs.widgetOverlayConfig.collectAsState(
            initial = WidgetOverlayConfig(),
        )
        var editMode by remember {
            // Consume a pending edit request (wheel-chip long-press that also
            // OPENED the layer) so it composes straight into edit mode.
            mutableStateOf(PortalPadApp.instance.consumeWidgetOverlayEditRequest())
        }
        var pickerOpen by remember { mutableStateOf(false) }
        // Placement intent from a tapped "+" free cell: the next added widget is
        // anchored here (falls back to first-fit if it doesn't fit). Null = the
        // toolbar/CTA add path (first-fit as before).
        var pendingAnchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        // Drag-in-progress (edit mode): which widget, move (0) or resize (1),
        // and the accumulated pointer delta in px.
        var dragId by remember { mutableStateOf<String?>(null) }
        var dragMode by remember { mutableStateOf(0) }
        var dragOffset by remember {
            mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
        }

        // Content color for OUR chrome (chips, CTA, handles, ghost), flipped
        // from the backdrop's average luminance so it stays legible on any
        // color the user picks — same approach the dock / top bar / QS panel
        // use. Widgets draw their own content and are never touched; the remove
        // badge stays semantic red (see EditableWidgetBox).
        val bgLum = remember(cfg.background.colorA, cfg.background.colorB) {
            (android.graphics.Color.luminance(cfg.background.colorAInt()) +
                android.graphics.Color.luminance(cfg.background.colorBInt())) / 2f
        }
        val onBg = if (bgLum > 0.5f) Color(0xFF14121A) else Color(0xFFF0EBF5)
        val onBgMuted = if (bgLum > 0.5f) Color(0xCC14121A) else Color(0xCCF0EBF5)
        val chipBg = if (bgLum > 0.5f) Color(0x22000000) else Color(0x33FFFFFF)
        // Edit-chrome accent (border + resize dots): darker violet on light
        // backdrops, the bright theme violet on dark ones.
        val editAccent = if (bgLum > 0.5f) Color(0xFF6B3FA0)
        else com.portalpad.app.ui.theme.AbPrimaryBright

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(com.portalpad.app.ui.common.toComposeBrush(cfg.background))
                .clickable(enabled = !editMode) {
                    // A plain cursor CLICK when IDLE dismisses the layer. In edit
                    // mode the root consumes nothing: exits are Done-only (per
                    // user decision — tap-to-exit caused accidental exits), and
                    // free-cell taps go to the "+" placement layer instead.
                    com.portalpad.app.service.PortalPadForegroundService
                        .dismissWidgetOverlayPanel()
                },
        ) {
            // Trackpad press-and-hold → injector.rightClick() → this tick.
            // ENTERS edit mode only (never exits — tap or Done exits, below), so
            // the hold gesture has one unambiguous job. Only when the layer has
            // widgets; an empty layer offers the CTA instead. Each entry bumps
            // the retire counter that stops the summon hint after 3 uses.
            val emptyNow = androidx.compose.runtime.rememberUpdatedState(cfg.widgets.isEmpty())
            val editingNow = androidx.compose.runtime.rememberUpdatedState(editMode)
            androidx.compose.runtime.LaunchedEffect(Unit) {
                PortalPadApp.instance.injector.widgetOverlayRightClickTick.collect {
                    if (!emptyNow.value && !editingNow.value) {
                        editMode = true
                        scope.launch {
                            val prefs = PortalPadApp.instance.prefs
                            val k = com.portalpad.app.data.PreferencesRepository.Keys.WIDGET_OVERLAY_EDIT_HINT_COUNT
                            val n = prefs.int(k).first()
                            if (n < 3) prefs.setInt(k, n + 1)
                        }
                    }
                }
            }
            val density = androidx.compose.ui.platform.LocalDensity.current
            // Safe area: symmetric margins. The dock WINDOW is dismissed while
            // the layer is up (PFS show/dismiss), so no dock reserve is needed —
            // the old 200dp DOCK_INSET_MAX left a dead band at the bottom and
            // made the top/bottom gaps visibly unequal. Bottom = GRID_MARGIN,
            // matching the top by construction, and widgets can fill the height.
            // Still a FIXED value: the row count never changes at runtime, so
            // the "resized dock, widgets vanished" bug class stays impossible.
            val bottomInset = GRID_MARGIN
            val safeW = maxWidth - GRID_MARGIN * 2
            val safeH = maxHeight - bottomInset - GRID_MARGIN
            val columns = ((safeW + GAP) / (CELL + GAP)).toInt().coerceAtLeast(1)
            val rows = ((safeH + GAP) / (CELL + GAP)).toInt().coerceAtLeast(1)

            val cellPx = with(density) { (CELL + GAP).toPx() }
            val latestCfg = androidx.compose.runtime.rememberUpdatedState(cfg)

            // Per-resolution placement: ONE widget set, per-resolution
            // arrangements keyed by the display's real pixel size. Everything
            // below operates on the RESOLVED list (this resolution's col/row/
            // span substituted in; no-fit widgets filtered) so the grid math
            // never sees out-of-range coordinates from another resolution —
            // the ultrawide→standard crash class (coerceIn(min>max) in the old
            // heal pass) is structurally gone.
            val resKey = remember {
                val m = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
                "${m.widthPixels}x${m.heightPixels}"
            }
            val rw = remember(cfg, resKey, columns, rows) {
                resolveWidgets(cfg, resKey, columns, rows)
            }

            // FULL-GRID centering — the origin is STATIC. Centering the
            // occupied block (previous model) meant col 0 was always wherever
            // the leftmost widget sat: no cells existed left of / above the
            // block, so a lone widget couldn't be moved freely, the W/N dots
            // could only shrink, and every commit re-centered the origin under
            // the user (field: "can't drag where I want", "not resizable from
            // all sides"). Centering the FIXED grid gives every cell a home,
            // symmetric resize at all four dots, free placement anywhere, and
            // the truncation remainder splits evenly so all four gaps match.
            val gridTop = remember(rows, maxHeight) {
                val blockH = CELL * rows + GAP * (rows - 1)
                ((maxHeight - blockH) / 2).coerceAtLeast(GRID_MARGIN)
            }
            val gridTopPx = with(density) { gridTop.toPx() }
            val gridLeft = remember(columns, maxWidth) {
                val blockW = CELL * columns + GAP * (columns - 1)
                ((maxWidth - blockW) / 2).coerceAtLeast(GRID_MARGIN)
            }
            val gridLeftPx = with(density) { gridLeft.toPx() }

            // Publish edit-mode widget rects (+ per-axis resizability) to the
            // injector so the cursor shows the same resize glyphs freeform
            // windows get. Cleared on edit-exit and on layer disposal.
            val awm = remember { AppWidgetManager.getInstance(displayContext) }
            androidx.compose.runtime.LaunchedEffect(editMode, cfg, columns, rows) {
                val injector = PortalPadApp.instance.injector
                injector.widgetEditRects = if (!editMode) emptyList() else {
                    rw.mapNotNull { w ->
                        if (w.col + w.spanW > columns || w.row + w.spanH > rows) return@mapNotNull null
                        val info = runCatching { awm.getAppWidgetInfo(w.appWidgetId) }.getOrNull()
                            ?: return@mapNotNull null
                        // DIAG-WIDGETRESIZE: provider resize metadata (kept for
                        // the shrink-bug hunt). Growth is now UNRESTRICTED on
                        // both axes (resizeMode gating dropped — Nova parity),
                        // so every widget publishes, flags always true.
                        android.util.Log.d(
                            "PortalPadWidget",
                            "DIAG-WIDGETRESIZE ${w.label} mode=${info.resizeMode} " +
                                "min=${info.minWidth}x${info.minHeight} " +
                                "minResize=${info.minResizeWidth}x${info.minResizeHeight} " +
                                "span=${w.spanW}x${w.spanH} cellDp=${CELL.value}",
                        )
                        // Rect MUST equal the rendered widget box exactly, or the
                        // injector's edge/interior cursor zones measure against a
                        // phantom rectangle (field: H cursor on the top circle, V
                        // on the left, resize glyphs on the widget face). The old
                        // rect inflated size by one GAP (used the grid STEP, not
                        // the box) and omitted the free-move offset AND the
                        // fractional resize extras — so any moved/resized widget's
                        // zones were in the wrong place, and even a fresh 1x1 read
                        // one gap too big. Same origin, size, offset, and extras
                        // as the render path above.
                        val boxWDp = CELL * w.spanW + GAP * (w.spanW - 1) + w.extraWDp.dp
                        val boxHDp = CELL * w.spanH + GAP * (w.spanH - 1) + w.extraHDp.dp
                        val l = (gridLeftPx + cellPx * w.col +
                            with(density) { w.offsetXDp.dp.toPx() }).toInt()
                        val t = (gridTopPx + cellPx * w.row +
                            with(density) { w.offsetYDp.dp.toPx() }).toInt()
                        val wd = with(density) { boxWDp.toPx() }.toInt()
                        val ht = with(density) { boxHDp.toPx() }.toInt()
                        android.util.Log.d(
                            "PortalPadWidget",
                            "DIAG-EDITRECT ${w.label} span=${w.spanW}x${w.spanH} " +
                                "off=${w.offsetXDp.toInt()},${w.offsetYDp.toInt()} " +
                                "extra=${w.extraWDp.toInt()}x${w.extraHDp.toInt()} " +
                                "rect=[$l,$t ${wd}x$ht]",
                        )
                        com.portalpad.app.service.InputInjector.WidgetEditRect(
                            android.graphics.Rect(l, t, l + wd, t + ht), true, true,
                        )
                    }
                }
            }
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose {
                    PortalPadApp.instance.injector.widgetEditRects = emptyList()
                }
            }

            // ── SEED + HEAL PASS (once per show, per resolution): every
            // widget missing a placement for THIS resolution gets one seeded
            // (base spot if free, else first-fit, else NO_FIT = hidden here
            // with a toast; other resolutions untouched). Under-provider-min
            // spans are healed in place when the cells are free (ignores
            // resizeMode — correcting to minimum is not a user resize). All
            // math is clamp-safe: out-of-grid coordinates can't throw (the
            // ultrawide→standard bugout was coerceIn(min>max) right here). ──
            androidx.compose.runtime.LaunchedEffect(resKey) {
                val app = PortalPadApp.instance
                val cur = app.prefs.widgetOverlayConfig.first()
                var noFit = 0
                var changed = false
                val placed = mutableListOf<OverlayWidget>()
                // DIAG-PLACE header: grid identity for this show. Strip with the
                // other DIAG blocks once the blocks-but-doesn't-draw report is
                // resolved. Tag PortalPadWidget.
                android.util.Log.d("PortalPadWidget",
                    "DIAG-PLACE show resKey=$resKey grid=${columns}x$rows widgets=${cur.widgets.size}")
                val updated = cur.widgets.map { w ->
                    val existing = w.placements[resKey]
                    // Provider minimums fetched FIRST so shrink-to-fit seeding
                    // never trims below what the provider can render.
                    val info = runCatching { awm.getAppWidgetInfo(w.appWidgetId) }.getOrNull()
                    val provMinW = info?.let { minSpan(it.minWidth) } ?: 1
                    val provMinH = info?.let { minSpan(it.minHeight) } ?: 1
                    // RE-VALIDATE every show (placements were previously written
                    // once and trusted forever): a stale entry — wrong-grid seed
                    // from a mid-transition frame, or any other corruption —
                    // could collide invisibly ("that spot is taken" over empty
                    // space, field report). Invalid or NO_FIT entries re-seed,
                    // trying their own stored spot first, shrinking to fit
                    // before giving up; the system self-heals instead of
                    // requiring the delete-on-other-resolution dance.
                    var p = when {
                        // User-hidden on this resolution: keep the entry
                        // verbatim — no re-seed, no heal, no no-fit counting.
                        existing?.hidden == true -> existing
                        existing == null -> seedPlacement(w, placed, columns, rows, provMinW, provMinH)
                        existing.col == com.portalpad.app.data.Placement.NO_FIT ->
                            seedPlacement(w, placed, columns, rows, provMinW, provMinH)
                        !placementValid(existing, placed, columns, rows) ->
                            seedPlacement(
                                w.copy(
                                    col = existing.col, row = existing.row,
                                    spanW = existing.spanW, spanH = existing.spanH,
                                ),
                                placed, columns, rows, provMinW, provMinH,
                            )
                        else -> existing
                    }
                    android.util.Log.d("PortalPadWidget",
                        "DIAG-PLACE ${w.label} base=${w.col},${w.row} ${w.spanW}x${w.spanH} " +
                            "stored=$existing resolved=$p maps=${w.placements}")
                    if (p.col != com.portalpad.app.data.Placement.NO_FIT && !p.hidden) {
                        // Heal-grow an under-min widget back to provider minimum
                        // when there's room — UNLESS the user manually sized it
                        // (they chose the smaller size; clipping is their call).
                        if (info != null && !p.userSized) {
                            val maxW = (columns - p.col).coerceAtLeast(1)
                            val maxH = (rows - p.row).coerceAtLeast(1)
                            val needW = maxOf(p.spanW, minOf(minSpan(info.minWidth), maxW))
                            val needH = maxOf(p.spanH, minOf(minSpan(info.minHeight), maxH))
                            if (needW != p.spanW || needH != p.spanH) {
                                val clash = placed.any { o ->
                                    placementsConflict(
                                        p.col, p.row, needW, needH, p.offsetXDp, p.offsetYDp,
                                        o.col, o.row, o.spanW, o.spanH, o.offsetXDp, o.offsetYDp,
                                        aExW = p.extraWDp, aExH = p.extraHDp,
                                        bExW = o.extraWDp, bExH = o.extraHDp,
                                    )
                                }
                                if (!clash) {
                                    android.util.Log.w("PortalPadWidget",
                                        "heal: ${w.label} grown ${p.spanW}x${p.spanH} → ${needW}x$needH (provider min)")
                                    p = p.copy(spanW = needW, spanH = needH)
                                } else {
                                    android.util.Log.w("PortalPadWidget",
                                        "heal: ${w.label} under-min but blocked by a neighbor — left as-is")
                                }
                            }
                        }
                        placed += w.copy(
                            col = p.col, row = p.row, spanW = p.spanW, spanH = p.spanH,
                            offsetXDp = p.offsetXDp, offsetYDp = p.offsetYDp,
                            extraWDp = p.extraWDp, extraHDp = p.extraHDp,
                        )
                    } else if (!p.hidden) {
                        noFit++ // NO_FIT only — user-hidden is not "doesn't fit"
                    }
                    if (w.placements[resKey] != p) changed = true
                    w.copy(placements = w.placements + (resKey to p))
                }
                if (changed) {
                    android.util.Log.d("PortalPadWidget",
                        "DIAG-PLACE write persist res=$resKey (seed/heal/re-validate)")
                    app.prefs.setWidgetOverlayConfig(cur.copy(widgets = updated))
                }
                val userHidden = updated.count { it.placements[resKey]?.hidden == true }
                val notShown = noFit + userHidden
                if (notShown > 0) {
                    toast(
                        "$notShown widget${if (notShown > 1) "s" else ""} not shown on this " +
                            "resolution — see Hidden in edit mode",
                    )
                }
            }

            // ── Hover add-cell (EDIT MODE): the static "+"-per-cell matrix
            // read as noise, so instead the FREE cell under the cursor lights
            // up (rounded outline + "+ Add widget"); clicking it opens the
            // picker anchored there. Driven by the injector's cursorPosition
            // (injected cursors produce no Compose hover events — same reason
            // the dock magnify tracks coordinates). Idle edit mode shows no
            // chrome at all. Colors ride the same adaptive accents as the rest.
            // Wheel-chip long-press (and future external callers): enter
            // edit mode on request. Buffered flow, so a request sent just
            // before show is consumed by the freshly-composed layer.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                PortalPadApp.instance.widgetOverlayEnterEdit.collect {
                    if (!editMode) editMode = true
                }
            }

            var hoverCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
            androidx.compose.runtime.LaunchedEffect(editMode, cfg, columns, rows, gridTopPx, gridLeftPx, pickerOpen) {
                // Suppressed when: not editing; the PICKER is up (it hovered the
                // backdrop behind the sheet); or the grid is EMPTY (the CTA is
                // the single affordance — hover + CTA double-chrome collided).
                if (!editMode || pickerOpen || cfg.widgets.isEmpty()) {
                    hoverCell = null; return@LaunchedEffect
                }
                PortalPadApp.instance.injector.cursorPosition.collect { (cx, cy) ->
                    // Guard BEFORE dividing: negative offsets truncate toward
                    // zero and would false-light cell (0,0) from the margins.
                    if (cx < gridLeftPx || cy < gridTopPx) {
                        if (hoverCell != null) hoverCell = null
                        return@collect
                    }
                    val c = ((cx - gridLeftPx) / cellPx).toInt()
                    val r = ((cy - gridTopPx) / cellPx).toInt()
                    // Rect rule, not cell membership: a free-moved neighbor's
                    // overhang (or its GAP margin) makes this cell un-addable,
                    // so don't light the "+" there and invite a doomed add.
                    val free = c in 0 until columns && r in 0 until rows &&
                        rw.none { o ->
                            placementsConflict(
                                c, r, 1, 1, 0f, 0f,
                                o.col, o.row, o.spanW, o.spanH, o.offsetXDp, o.offsetYDp,
                                bExW = o.extraWDp, bExH = o.extraHDp,
                            )
                        }
                    val next = if (free) Pair(c, r) else null
                    if (hoverCell != next) hoverCell = next
                }
            }
            hoverCell?.let { (hc, hr) ->
                Column(
                    Modifier
                        .offset(gridLeft + (CELL + GAP) * hc, gridTop + (CELL + GAP) * hr)
                        .size(CELL, CELL)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, editAccent, RoundedCornerShape(12.dp))
                        .background(editAccent.copy(alpha = 0.12f))
                        .clickable {
                            pendingAnchor = Pair(hc, hr)
                            pickerOpen = true
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Text("+", color = onBg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Add widget", color = onBgMuted, fontSize = 10.sp, maxLines = 1)
                }
            }

            // ── Widget cells ──
            // key(w.id) is LOAD-BEARING: without it Compose identifies these
            // children by POSITION, and AndroidView's factory (which binds the
            // AppWidgetHostView to an appWidgetId) runs once per instance.
            // Removing widgets shifted the survivors into slots still holding
            // the REMOVED widgets' host views — field bug: deleting two widgets
            // made the remaining entries render the deleted widgets' content.
            rw.forEach { w ->
                if (w.col + w.spanW > columns || w.row + w.spanH > rows) return@forEach
                androidx.compose.runtime.key(w.id) {
                EditableWidgetBox(
                    w = w,
                    editMode = editMode,
                    dragging = dragId == w.id,
                    dragMode = dragMode,
                    dragOffset = if (dragId == w.id) dragOffset else androidx.compose.ui.geometry.Offset.Zero,
                    columns = columns, rows = rows,
                    cellPx = cellPx,
                    gridTop = gridTop,
                    gridLeft = gridLeft,
                    frozen = pickerOpen,
                    handleFill = chipBg,
                    accent = editAccent,
                    onDragStart = { mode -> dragId = w.id; dragMode = mode; dragOffset = androidx.compose.ui.geometry.Offset.Zero },
                    onDragDelta = { d -> dragOffset += d },
                    onDragEnd = { commit ->
                        if (commit) {
                            val id = w.id; val mode = dragMode; val off = dragOffset
                            scope.launch(Dispatchers.IO) {
                                commitDrag(latestCfg.value, id, mode, off, cellPx, columns, rows, resKey)
                            }
                        }
                        dragId = null
                        dragOffset = androidx.compose.ui.geometry.Offset.Zero
                    },
                    onRemove = { scope.launch(Dispatchers.IO) { removeWidget(w) } },
                    onHide = {
                        scope.launch(Dispatchers.IO) { hideOnResolution(w, resKey) }
                    },
                )
                }
            }

            // ── Drop-target ghost: snapped outline while dragging (free-move:
            // dp-granular with gap/lattice magnets; resize: cell-snapped). ──
            val ghostW = dragId?.let { id -> rw.firstOrNull { it.id == id } }
            if (ghostW != null) {
                val target = dragTarget(rw, ghostW, dragMode, dragOffset, cellPx, columns, rows)
                if (target != null) {
                    val (tc, tr, tw, th) = target
                    val free = rw.none { o ->
                        o.id != ghostW.id &&
                            placementsConflict(
                                tc, tr, tw, th, target.offX, target.offY,
                                o.col, o.row, o.spanW, o.spanH, o.offsetXDp, o.offsetYDp,
                                aExW = target.extraW, aExH = target.extraH,
                                bExW = o.extraWDp, bExH = o.extraHDp,
                            )
                    }
                    Box(
                        Modifier
                            .offset(
                                gridLeft + (CELL + GAP) * tc + target.offX.dp,
                                gridTop + (CELL + GAP) * tr + target.offY.dp,
                            )
                            .size(
                                CELL * tw + GAP * (tw - 1) + target.extraW.dp,
                                CELL * th + GAP * (th - 1) + target.extraH.dp,
                            )
                            .border(
                                2.dp,
                                if (free) com.portalpad.app.ui.theme.AbPrimaryBright
                                else Color(0xFFD84A4A),
                                RoundedCornerShape(14.dp),
                            ),
                    )
                }
            }

            // ── Top-right toolbar — only while EDITING (idle layer with widgets
            // is chrome-free; hold empty space to enter edit). ──
            if (editMode) {
                val hiddenHere = cfg.widgets.filter { it.placements[resKey]?.hidden == true }
                val noFitHere = cfg.widgets.filter {
                    val pl = it.placements[resKey]
                    pl != null && !pl.hidden && pl.col == com.portalpad.app.data.Placement.NO_FIT
                }
                val notShownCount = hiddenHere.size + noFitHere.size
                var hiddenPopupOpen by remember { mutableStateOf(false) }
                // Two-step Remove-all confirm; disarmed by any other toolbar
                // action or by leaving edit mode (remember is keyed to this
                // composition, so overlay dismissal also resets it).
                var removeAllArmed by remember { mutableStateOf(false) }
                // Vertically centered in the band ABOVE the grid: symmetric
                // gaps to the top edge and the widgets below, on any
                // resolution (fixed 24dp padding hugged the widgets when
                // gridTop was large).
                val chipRowH = 36.dp
                Column(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(horizontal = GRID_MARGIN)
                        .offset(y = ((gridTop - chipRowH) / 2).coerceAtLeast(8.dp)),
                    horizontalAlignment = Alignment.End,
                ) {
                    Row {
                        if (notShownCount > 0) {
                            ToolChip(
                                label = "Hidden ($notShownCount)",
                                contentColor = onBg, bg = chipBg,
                            ) { hiddenPopupOpen = !hiddenPopupOpen }
                            Spacer(Modifier.width(10.dp))
                        }
                        // Destructive, so two-step: first tap arms ("Remove all?"),
                        // second tap within the armed state executes; tapping any
                        // other chip or leaving edit mode disarms. Same behavior
                        // as the phone Workspace page's Remove all: release every
                        // system binding, then clear the config.
                        if (cfg.widgets.isNotEmpty()) {
                            ToolChip(
                                label = if (removeAllArmed) "Tap again to confirm" else "Remove all",
                                contentColor = if (removeAllArmed) Color.White else onBg,
                                bg = if (removeAllArmed) Color(0xFFD84A4A) else chipBg,
                            ) {
                                if (!removeAllArmed) {
                                    removeAllArmed = true
                                } else {
                                    removeAllArmed = false
                                    scope.launch(Dispatchers.IO) {
                                        val app = PortalPadApp.instance
                                        val cur = app.prefs.widgetOverlayConfig.first()
                                        cur.widgets.forEach {
                                            WidgetHostManager.deleteId(displayContext, it.appWidgetId)
                                        }
                                        app.prefs.setWidgetOverlayConfig(cur.copy(widgets = emptyList()))
                                    }
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                        }
                        ToolChip(
                            label = "+ Add",
                            enabled = cfg.widgets.size < MAX_OVERLAY_WIDGETS,
                            contentColor = onBg, bg = chipBg,
                        ) { removeAllArmed = false; pickerOpen = true }
                        Spacer(Modifier.width(10.dp))
                        ToolChip(label = "Done", contentColor = onBg, bg = chipBg) {
                            removeAllArmed = false
                            editMode = false
                        }
                    }
                    // Unhide popup: on-display, same room as the hide action —
                    // no phone round-trip to undo a one-tap choice.
                    if (hiddenPopupOpen && notShownCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(chipBg)
                                .padding(10.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            // USER-hidden: restorable here.
                            if (hiddenHere.isNotEmpty()) {
                                Text("Hidden by you", color = onBgMuted, fontSize = 11.sp)
                                hiddenHere.forEach { hw ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            hw.label, color = onBg, fontSize = 13.sp,
                                            maxLines = 1,
                                            modifier = Modifier.widthIn(max = 220.dp),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        ToolChip(
                                            label = "Unhide",
                                            contentColor = editAccent, bg = Color.Transparent,
                                        ) {
                                            scope.launch(Dispatchers.IO) {
                                                unhideOnResolution(hw.id, resKey)
                                            }
                                        }
                                    }
                                }
                            }
                            // SYSTEM no-fit: info only — shrink-to-fit already
                            // retries every show; if room appears it returns
                            // automatically, so there's nothing manual to do.
                            if (noFitHere.isNotEmpty()) {
                                if (hiddenHere.isNotEmpty()) Spacer(Modifier.height(6.dp))
                                Text("Doesn't fit this resolution", color = onBgMuted, fontSize = 11.sp)
                                noFitHere.forEach { nf ->
                                    Text(
                                        nf.label, color = onBg, fontSize = 13.sp,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .widthIn(max = 220.dp)
                                            .padding(vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Empty-state CTA — centered in the SAFE area (optically above the
            // dock), not the raw display. Renders only while no widgets exist. ──
            if (cfg.widgets.isEmpty()) {
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = 0.dp, y = (maxHeight - bottomInset) / 2 - 48.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(chipBg)
                            .clickable { pickerOpen = true }
                            .padding(horizontal = 36.dp, vertical = 20.dp),
                    ) {
                        Text(
                            "Add your first widget  +",
                            color = onBg,
                            fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Widgets appear on this display",
                        color = onBgMuted,
                        fontSize = 13.sp,
                    )
                }
            }

            if (pickerOpen) {
                WidgetPickerSheet(
                    columns = columns, rows = rows,
                    resKey = resKey,
                    anchor = pendingAnchor,
                    sheetBg = if (bgLum > 0.5f) Color(0xF2EFEAF7) else Color(0xF2181226),
                    onBg = onBg, onBgMuted = onBgMuted, accent = editAccent,
                    onClose = { added ->
                        pickerOpen = false
                        pendingAnchor = null
                        // Land in edit mode after an add — keeps the user in the
                        // placement flow (position/resize/add more) instead of
                        // dropping them out, especially after the first widget
                        // added via the empty-state CTA.
                        if (added) editMode = true
                    },
                )
            }
        }
    }

    /** One live widget (AppWidgetHostView) or a placeholder when its binding /
     *  provider is gone. The host view is created against [displayContext] so
     *  widget CONTENT tracks the display density (like app content), while our
     *  chrome stays pinned. */
    @Composable
    private fun WidgetCell(w: OverlayWidget, wDp: Dp, hDp: Dp) {
        val awm = remember { AppWidgetManager.getInstance(displayContext) }
        val info = remember(w.appWidgetId) {
            runCatching { awm.getAppWidgetInfo(w.appWidgetId) }.getOrNull()
        }
        if (info == null) {
            // Dead binding or missing provider — visible placeholder so the user
            // sees what's missing instead of wondering where a widget went.
            Column(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x66201A30))
                    .border(1.dp, com.portalpad.app.ui.theme.AbSurfaceElevated,
                        RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(w.label, color = com.portalpad.app.ui.theme.AbOnSurface,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text("Unavailable — app missing or needs re-add",
                    color = com.portalpad.app.ui.theme.AbOnSurfaceMuted, fontSize = 11.sp)
            }
            return
        }
        val density = androidx.compose.ui.platform.LocalDensity.current
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { _ ->
                WidgetHostManager.host(displayContext)
                    .createView(displayContext, w.appWidgetId, info)
                    .apply {
                        // SMALL symmetric padding (4dp), replacing the fully-
                        // stripped default: zero padding put bottom-flush widget
                        // content (the "Ask Alexa" caption) right ON the border,
                        // while the system default (~16dp) was the ugly empty
                        // band an earlier pass removed. 4dp gives every edge the
                        // same small clearance. Visual-only: the size REPORTED
                        // to the provider is unchanged, so layouts don't re-pick.
                        val padPx = (4f * displayContext.resources.displayMetrics.density).toInt()
                        setPadding(padPx, padPx, padPx, padPx)
                    }
            },
            update = { view ->
                // Only re-inform the provider when the COMMITTED span actually
                // changed. The update block re-runs on EVERY recomposition —
                // captured at ~60Hz during drags — and hammering providers with
                // size callbacks per frame is the prime suspect for flaky
                // providers re-picking (smaller) layouts. Memoized on the view
                // tag: one call per real resize.
                val spanKey = "${w.spanW}x${w.spanH}+${w.extraWDp.toInt()}x${w.extraHDp.toInt()}"
                if (view.tag != spanKey) {
                    view.tag = spanKey
                    val ctxD = displayContext.resources.displayMetrics.density
                    // REAL committed size, fractional extras included, so a
                    // free-resized widget re-lays-out to its actual box instead
                    // of leaving dead margins (or cropping) at the span size.
                    val cw = CELL * w.spanW + GAP * (w.spanW - 1) + w.extraWDp.dp
                    val ch = CELL * w.spanH + GAP * (w.spanH - 1) + w.extraHDp.dp
                    val wPx = with(density) { cw.toPx() }
                    val hPx = with(density) { ch.toPx() }
                    // DIAG-WIDGETRESIZE: now logs only on REAL size reports.
                    android.util.Log.d(
                        "PortalPadWidget",
                        "DIAG-WIDGETRESIZE report ${w.label} id=${w.appWidgetId} " +
                            "span=${w.spanW}x${w.spanH} px=${wPx.toInt()}x${hPx.toInt()} " +
                            "ctxD=$ctxD reportedDp=${(wPx / ctxD).toInt()}x${(hPx / ctxD).toInt()}",
                    )
                    runCatching {
                        view.updateAppWidgetSize(
                            null,
                            (wPx / ctxD).toInt(), (hPx / ctxD).toInt(),
                            (wPx / ctxD).toInt(), (hPx / ctxD).toInt(),
                        )
                    }
                }
            },
        )
    }

    /** One grid widget with edit-mode chrome: the dock's iOS-style wiggle
     *  (±2.5°, 180ms reverse tween, per-item hash phase offset so cells don't
     *  wiggle in lockstep), a transparent GRAB LAYER so drags reach us instead
     *  of the widget's own buttons/lists, drag-to-move, a bottom-right resize
     *  handle honoring the provider's resizeMode, and the remove badge. */
    @Composable
    private fun EditableWidgetBox(
        w: OverlayWidget,
        editMode: Boolean,
        dragging: Boolean,
        dragMode: Int,
        dragOffset: androidx.compose.ui.geometry.Offset,
        columns: Int,
        rows: Int,
        cellPx: Float,
        gridTop: Dp,
        gridLeft: Dp,
        frozen: Boolean,
        handleFill: Color,
        accent: Color,
        onHide: () -> Unit,
        onDragStart: (Int) -> Unit,
        onDragDelta: (androidx.compose.ui.geometry.Offset) -> Unit,
        onDragEnd: (Boolean) -> Unit,
        onRemove: () -> Unit,
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val x = gridLeft + (CELL + GAP) * w.col + w.offsetXDp.dp
        val y = gridTop + (CELL + GAP) * w.row + w.offsetYDp.dp
        val baseW = CELL * w.spanW + GAP * (w.spanW - 1) + w.extraWDp.dp
        val baseH = CELL * w.spanH + GAP * (w.spanH - 1) + w.extraHDp.dp
        // Live drag visuals per mode (0=move, 1=E, 2=S, 3=W, 4=N): MOVE
        // translates; E/S grow the far edge; W/N translate AND counter-grow so
        // the far edge stays put. Cell-snapped ghost shows the real target.
        val dragDp = with(density) {
            androidx.compose.ui.unit.DpOffset(dragOffset.x.toDp(), dragOffset.y.toDp())
        }
        val movePx = when {
            dragging && dragMode == 0 -> dragOffset
            dragging && dragMode == 3 -> androidx.compose.ui.geometry.Offset(dragOffset.x, 0f)
            dragging && dragMode == 4 -> androidx.compose.ui.geometry.Offset(0f, dragOffset.y)
            else -> androidx.compose.ui.geometry.Offset.Zero
        }
        val liveW = when {
            dragging && dragMode == 1 -> (baseW + dragDp.x).coerceAtLeast(MIN_WIDGET_DP.dp)
            dragging && dragMode == 3 -> (baseW - dragDp.x).coerceAtLeast(MIN_WIDGET_DP.dp)
            else -> baseW
        }
        val liveH = when {
            dragging && dragMode == 2 -> (baseH + dragDp.y).coerceAtLeast(MIN_WIDGET_DP.dp)
            dragging && dragMode == 4 -> (baseH - dragDp.y).coerceAtLeast(MIN_WIDGET_DP.dp)
            else -> baseH
        }

        // Dock reorder wiggle pattern (amplitude reduced: ±1.2° — full ±2.5° is
        // violent on cells this large).
        val wigglePhase = remember(w.id) { (w.id.hashCode() % 360).toFloat() }
        val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "wWiggle")
        val wiggleAngle by infinite.animateFloat(
            initialValue = -1.2f,
            targetValue = 1.2f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(
                    durationMillis = 180,
                    easing = androidx.compose.animation.core.LinearEasing,
                ),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                initialStartOffset = androidx.compose.animation.core.StartOffset((wigglePhase * 2).toInt()),
            ),
            label = "wWiggleAngle",
        )

        Box(
            Modifier
                .offset(x, y)
                .size(liveW, liveH)
                // Dead-space click consumer: AppWidgetHostView only consumes
                // clicks on the RemoteViews' actual click targets; taps on a
                // widget's non-clickable body fell THROUGH to the root scrim
                // and dismissed the layer. Children (host view, grab layer)
                // still win their own touches; this fires only for unconsumed
                // taps within the widget's bounds — and does nothing, like a
                // launcher.
                .pointerInput(Unit) { detectTapGestures { } }
                .graphicsLayer {
                    translationX = movePx.x
                    translationY = movePx.y
                    rotationZ = if (editMode && !dragging && !frozen) wiggleAngle else 0f
                    val s = if (dragging && dragMode == 0) 1.06f else 1f
                    scaleX = s; scaleY = s
                },
        ) {
            // Widget renders at its TRUE committed size in BOTH modes. An
            // earlier edit-mode 12dp inset shrank the reported size, which made
            // size-adaptive providers (Alexa) DROP their caption to fit —
            // field: "Ask Alexa" label vanished in edit mode, and the extra
            // uniform padding stacked with the widget's own asymmetric margins
            // so internal gaps looked unequal. Label clearance for the bottom
            // dot is handled geometrically instead (S dot nudged lower on
            // 1-cell-tall widgets), not by squeezing content.
            WidgetCell(w, liveW, liveH)
            // Action-strip BACKGROUND: drawn here — above the widget, BELOW the
            // border/dots — so the resize border crosses visibly over the
            // strip's left/right ends (field request). The Hide/Remove BUTTONS
            // draw later, after the dots, staying topmost and tappable. Only the
            // large tier uses the strip; compact/mid tiers self-contain.
            // Strip background gated on baseW (the committed span width), the
            // SAME threshold the button tier uses below — so the background and
            // the buttons appear together for large-tier widgets only.
            if (editMode && baseW >= 160.dp) {
                Row(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .background(Color(0xCC10101C))
                        .drawBehind {
                            val rule = accent.copy(alpha = 0.55f)
                            val t = 1.dp.toPx()
                            drawLine(rule,
                                androidx.compose.ui.geometry.Offset(0f, t / 2),
                                androidx.compose.ui.geometry.Offset(size.width, t / 2), t)
                            drawLine(rule,
                                androidx.compose.ui.geometry.Offset(0f, size.height - t / 2),
                                androidx.compose.ui.geometry.Offset(size.width, size.height - t / 2), t)
                        }
                        // Height matches the buttons' band (button content + the
                        // Row's 7dp vertical padding) so the drawn strip sits
                        // exactly behind them.
                        .padding(vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Invisible spacer matching the button band height keeps this
                    // background Row the same height as the button Row below.
                    Spacer(Modifier.height(23.dp))
                }
            }
            if (editMode) {
                // Grab layer: widgets consume touches (buttons, lists), so edit
                // gestures need a surface ABOVE the host view. Also naturally
                // disables widget taps while editing.
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(w.id) {
                            detectDragGestures(
                                onDragStart = { onDragStart(0) },
                                onDrag = { change, amt -> change.consume(); onDragDelta(amt) },
                                onDragEnd = { onDragEnd(true) },
                                onDragCancel = { onDragEnd(false) },
                            )
                        },
                )
                // Nova-style resize chrome: rounded border + a drag dot at each
                // edge MIDPOINT (E/S/W/N → dragTarget modes 1/2/3/4). Colors are
                // the backdrop-adaptive accent so the chrome reads on any scrim.
                // Border THICKENS while the small-widget menu is open — the
                // ownership cue for a flyout that renders on this widget's face.
                var smallMenuOpen by remember(w.id) { mutableStateOf(false) }
                Box(
                    Modifier
                        .matchParentSize()
                        .border(if (smallMenuOpen) 4.dp else 2.dp, accent, RoundedCornerShape(16.dp)),
                )
                // Dots ride the border half-in/half-out (Nova-style) so adjacent
                // widgets' chrome never overlaps a neighbor. EXCEPTION: on a
                // 1-cell-TALL widget the half-in S dot lands on the widget's own
                // bottom-center caption ("Ask Alexa", field). There it's pushed
                // fully OUT (a whole DOT_SIZE) so the caption stays visible; taller
                // widgets keep the half-in dot (their caption sits well above the
                // bottom edge, no collision). A 1-tall widget has no vertical
                // neighbor closer than GAP anyway, so the outboard dot can't
                // overlap one below.
                // WHOLE-EDGE resize strips: invisible drag targets straddling each
                // border (10dp in / 10dp out), resizing exactly like the matching
                // dot — so the border-long resize cursor is a promise the touch
                // layer keeps (field: glyph appeared where nothing dragged). N/S
                // first, E/W after: later children win Compose touch order, so
                // corners resize horizontally, matching the cursor model. Dots
                // stay on top as the visible affordance.
                EdgeStrip(4, Alignment.TopCenter, liveW, 20.dp, 0.dp, (-10).dp, w.id, onDragStart, onDragDelta, onDragEnd)    // N
                EdgeStrip(2, Alignment.BottomCenter, liveW, 20.dp, 0.dp, 10.dp, w.id, onDragStart, onDragDelta, onDragEnd)    // S
                EdgeStrip(1, Alignment.CenterEnd, 20.dp, liveH, 10.dp, 0.dp, w.id, onDragStart, onDragDelta, onDragEnd)       // E
                EdgeStrip(3, Alignment.CenterStart, 20.dp, liveH, (-10).dp, 0.dp, w.id, onDragStart, onDragDelta, onDragEnd)  // W
                val sDotOff = if (w.spanH <= 1) DOT_SIZE else DOT_SIZE / 2
                EdgeDot(1, Alignment.CenterEnd, DOT_SIZE / 2, 0.dp, accent, handleFill, w.id, onDragStart, onDragDelta, onDragEnd)    // E
                EdgeDot(2, Alignment.BottomCenter, 0.dp, sDotOff, accent, handleFill, w.id, onDragStart, onDragDelta, onDragEnd)  // S
                EdgeDot(3, Alignment.CenterStart, -(DOT_SIZE / 2), 0.dp, accent, handleFill, w.id, onDragStart, onDragDelta, onDragEnd) // W
                EdgeDot(4, Alignment.TopCenter, 0.dp, -(DOT_SIZE / 2), accent, handleFill, w.id, onDragStart, onDragDelta, onDragEnd)   // N
                // Hide / Remove as LABELED chips, top-center ON the widget
                // body (the corner badges sat on the resize chrome and crowded
                // the grab areas; unlabeled icons under-communicated actions
                // this consequential). The grab layer already disables widget
                // interaction in edit mode, so covering a strip costs nothing.
                // Width-adaptive: labeled pills need ~160dp; smaller widgets
                // (a 1×1 is 70dp) get compact icon circles instead — same
                // actions, no overlap (field: Ask Alexa's chips spilled over
                // its own bounds and each other).
                // Three tiers: 1-cell widgets (<120dp) keep the ⋮ menu (pills
                // would bury the widget's identity); 2-cell widgets (120-160dp)
                // get ALWAYS-VISIBLE chips in the flyout's exact presentation —
                // stacked vertically, centered on the face (side-by-side
                // wrapped "Remov/e" at this width, field); ≥160dp keeps the
                // side-by-side row.
                val compactChips = baseW < 120.dp
                val midChips = baseW >= 120.dp && baseW < 160.dp
                // Small widgets (a 1×1 is 70dp) can't host two of ANYTHING on
                // their face — even compact circles collided (field x2). They
                // get ONE ⋮ button; its flyout with the full labeled chips
                // anchors OUTSIDE the widget bounds where there's room.
                if (compactChips) {
                    // ROUNDED-SQUARE menu button: a solid accent CIRCLE read
                    // as a fifth resize dot (field) — the shape alone now
                    // separates it from the EdgeDots. Glyph contrast computed
                    // from the accent so it adapts with the palette.
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(6.dp, (-6).dp)
                            .size(26.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFF181226))
                            .border(1.5.dp, accent, RoundedCornerShape(7.dp))
                            .clickable { smallMenuOpen = !smallMenuOpen },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Widget options",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    if (smallMenuOpen) {
                        // Centered ON the owning widget's face: possession is
                        // unambiguous (side-floating flyouts landed over
                        // NEIGHBOR widgets and cropped at screen edges), and
                        // the thickened border above marks the owner.
                        // Edge-clamped: still anchored to the owner's face,
                        // but a flyout WIDER than a 1×1 widget at the last
                        // column was overflowing offscreen ("Remov" cropped,
                        // field). Near the right edge it grows leftward; near
                        // the left edge, rightward; centered otherwise.
                        // UNBOUNDED measure is the real cure: rendered inside
                        // the widget's box, the flyout was measured with the
                        // widget's max width — 70dp on a 1×1 — so "✕ Remove"
                        // clipped at ANY font size ("Remov", field x2). The
                        // wrap alignment picks the growth direction: End at the
                        // right edge (grows left), Start at the left, centered
                        // otherwise.
                        val wrapAlign = when {
                            w.col + w.spanW >= columns - 1 -> Alignment.End
                            w.col <= 1 -> Alignment.Start
                            else -> Alignment.CenterHorizontally
                        }
                        Column(
                            Modifier
                                .align(Alignment.Center)
                                .wrapContentWidth(align = wrapAlign, unbounded = true),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF181226))
                                    .border(1.dp, accent, RoundedCornerShape(14.dp))
                                    .clickable { smallMenuOpen = false; onHide() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.VisibilityOff, contentDescription = null,
                                    tint = Color.White, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Hide", color = Color.White, fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1, softWrap = false)
                            }
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFFD84A4A))
                                    .clickable { smallMenuOpen = false; onRemove() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("✕", color = Color.White, fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(5.dp))
                                Text("Remove", color = Color.White, fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
                // Mid tier: the flyout's stacked layout, permanently visible.
                if (midChips) {
                    // Semi-transparent fills (~76%) at this tier ONLY: the
                    // stacked chips cover most of a small widget's face, so
                    // letting the icon read through keeps the widget
                    // identifiable while the labels stay legible (field
                    // request). Large tier stays opaque — plenty of face left.
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xC2181226))
                                .border(1.dp, accent, RoundedCornerShape(14.dp))
                                .clickable { onHide() }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.VisibilityOff, contentDescription = null,
                                tint = Color.White, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Hide", color = Color.White, fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1, softWrap = false)
                        }
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xC2D84A4A))
                                .clickable { onRemove() }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("✕", color = Color.White, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(5.dp))
                            Text("Remove", color = Color.White, fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1, softWrap = false)
                        }
                    }
                }
                // Action-strip BUTTONS only — the strip background + hairlines
                // were drawn earlier, beneath the border, so the border crosses
                // over the strip while these buttons stay topmost and tappable.
                if (!compactChips && !midChips) {
                    Row(
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Hide chip: fixed dark fill + accent border, legible on any widget face.
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF181226))
                                .border(1.dp, accent, RoundedCornerShape(14.dp))
                                .clickable { onHide() }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("Hide", color = Color.White, fontSize = 12.sp,
                                fontWeight = FontWeight.Medium)
                        }
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFFD84A4A))
                                .clickable { onRemove() }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("✕", color = Color.White, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(5.dp))
                            Text("Remove", color = Color.White, fontSize = 12.sp,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }

    /** One Nova-style edge-midpoint resize dot. BoxScope receiver so it can
     *  align inside the widget box; [mode] maps to dragTarget (1=E 2=S 3=W 4=N). */
    /** Invisible whole-edge resize drag target. Same drag wiring as [EdgeDot],
     *  strip-shaped and straddling the border, so the border-long resize cursor
     *  always has a real drag beneath it. */
    @Composable
    private fun androidx.compose.foundation.layout.BoxScope.EdgeStrip(
        mode: Int,
        alignTo: Alignment,
        w: Dp,
        h: Dp,
        ox: Dp,
        oy: Dp,
        widgetId: String,
        onDragStart: (Int) -> Unit,
        onDragDelta: (androidx.compose.ui.geometry.Offset) -> Unit,
        onDragEnd: (Boolean) -> Unit,
    ) {
        Box(
            Modifier
                .align(alignTo)
                .offset(ox, oy)
                .size(w, h)
                .pointerInput(widgetId, mode) {
                    detectDragGestures(
                        onDragStart = { onDragStart(mode) },
                        onDrag = { change, amt -> change.consume(); onDragDelta(amt) },
                        onDragEnd = { onDragEnd(true) },
                        onDragCancel = { onDragEnd(false) },
                    )
                },
        )
    }

    @Composable
    private fun androidx.compose.foundation.layout.BoxScope.EdgeDot(
        mode: Int,
        alignTo: Alignment,
        ox: Dp,
        oy: Dp,
        fill: Color,
        ring: Color,
        widgetId: String,
        onDragStart: (Int) -> Unit,
        onDragDelta: (androidx.compose.ui.geometry.Offset) -> Unit,
        onDragEnd: (Boolean) -> Unit,
    ) {
        Box(
            Modifier
                .align(alignTo)
                .offset(ox, oy)
                .size(DOT_SIZE)
                .clip(CircleShape)
                .background(fill)
                .border(2.dp, ring, CircleShape)
                .pointerInput(widgetId, mode) {
                    detectDragGestures(
                        onDragStart = { onDragStart(mode) },
                        onDrag = { change, amt -> change.consume(); onDragDelta(amt) },
                        onDragEnd = { onDragEnd(true) },
                        onDragCancel = { onDragEnd(false) },
                    )
                },
        )
    }

    /** Small rounded toolbar chip ("+ Add" / "Edit"). */
    @Composable
    private fun ToolChip(
        label: String,
        enabled: Boolean = true,
        contentColor: Color = com.portalpad.app.ui.theme.AbOnSurface,
        bg: Color = com.portalpad.app.ui.theme.AbSurfaceElevated,
        onClick: () -> Unit,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (enabled) bg else bg.copy(alpha = bg.alpha * 0.4f))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                label,
                color = if (enabled) contentColor else contentColor.copy(alpha = 0.45f),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }

    /** Remove [w]: release its system binding, then drop it from the config.
     *  BLOCKING datastore write — call on IO. */
    private suspend fun removeWidget(w: OverlayWidget) {
        val app = PortalPadApp.instance
        WidgetHostManager.deleteId(displayContext, w.appWidgetId)
        val cur = app.prefs.widgetOverlayConfig.first()
        app.prefs.setWidgetOverlayConfig(
            cur.copy(widgets = cur.widgets.filterNot { it.id == w.id }),
        )
    }

    /** Centered picker sheet listing every installed widget provider: a
     *  "Suggested" section first (widgets needing NO configuration — they work
     *  the instant they're tapped), then all providers grouped by app. Built
     *  from installedProviders — the system picker can't target this display. */
    @Composable
    private fun WidgetPickerSheet(
        columns: Int,
        rows: Int,
        resKey: String,
        anchor: Pair<Int, Int>?,
        sheetBg: Color,
        onBg: Color,
        onBgMuted: Color,
        accent: Color,
        onClose: (Boolean) -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        // Profile-aware shared catalog (work-profile/Secure Folder widgets were
        // invisible to plain installedProviders) — same list the phone-side
        // search shows.
        val providers = remember { WidgetHostManager.allProviders(displayContext) }
        // Live phone-typed query (see WidgetSearchActivity): filter by widget
        // label OR app name, case-insensitive. Query resets when the sheet
        // closes so a stale filter never greets the next open.
        val query by PortalPadApp.instance.widgetPickerQuery.collectAsState()
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose { PortalPadApp.instance.setWidgetPickerQuery("") }
        }
        val shown = remember(providers, query) {
            if (query.isBlank()) providers
            else providers.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.appLabel.contains(query, ignoreCase = true)
            }
        }
        // Launcher-style grouping: one collapsible section per app. Searching
        // auto-expands every matching group so results appear instantly. The
        // old flat "Suggested" section is retired — its useful bit ("no setup
        // needed") is now a per-row badge.
        val groups = remember(shown) { shown.groupBy { it.appLabel }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
        var expandedApps by remember { mutableStateOf(setOf<String>()) }
        var busy by remember { mutableStateOf(false) }
        // Phone-side picks (WidgetSearchActivity tap) add through the SAME path
        // as an on-display row tap — anchor-aware and all.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            PortalPadApp.instance.widgetPickerPick.collect { flat ->
                val entry = providers.firstOrNull { it.info.provider.flattenToString() == flat }
                    ?: return@collect
                if (busy) return@collect
                busy = true
                scope.launch(Dispatchers.IO) {
                    addWidget(entry.info, entry.label, columns, rows, resKey, anchor)
                    withContext(Dispatchers.Main) { busy = false; onClose(true) }
                }
            }
        }

        // Backdrop / Close = cancel (false); picking a provider = add (true).
        Box(
            Modifier.fillMaxSize().clickable { onClose(false) },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(640.dp)
                    .heightIn(max = 560.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(sheetBg)
                    .clickable { /* swallow */ }
                    .padding(18.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Add a widget", color = onBg,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    ToolChip(label = "Close", onClick = { onClose(false) })
                }
                Spacer(Modifier.height(10.dp))
                // Search row: the picker window is NOT_FOCUSABLE (cursor-driven,
                // no on-display IME), so tapping this opens the phone-side
                // WidgetSearchActivity — typed text filters this list live.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x22FFFFFF))
                        .clickable(enabled = !busy) { launchPhoneWidgetSearch() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (query.isBlank()) "🔍  Search widgets (types on your phone)"
                        else "🔍  “$query”",
                        color = if (query.isBlank()) onBgMuted else onBg,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (query.isNotBlank()) {
                        ToolChip(label = "Clear") {
                            PortalPadApp.instance.setWidgetPickerQuery("")
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                Box(Modifier.weight(1f, fill = false)) {
                    LazyColumn(state = listState) {
                        if (shown.isEmpty()) {
                            item {
                                Text(
                                    "No widgets match “$query”",
                                    color = onBgMuted,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(vertical = 16.dp),
                                )
                            }
                        }
                        val searching = query.isNotBlank()
                        // Nova-style grouped rows, restyled to our adaptive
                        // palette: app icon + name + count + chevron in a slim
                        // ~48dp row, a hairline divider between groups.
                        groups.entries.forEachIndexed { gi, (appName, entries) ->
                            val expanded = searching || appName in expandedApps
                            if (gi > 0) {
                                item(key = "div:$appName") {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(onBgMuted.copy(alpha = 0.16f)),
                                    )
                                }
                            }
                            item(key = "hdr:$appName") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(enabled = !searching) {
                                            expandedApps =
                                                if (appName in expandedApps) expandedApps - appName
                                                else expandedApps + appName
                                        }
                                        .padding(horizontal = 8.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AndroidView(
                                        modifier = Modifier.size(30.dp),
                                        factory = { ctx ->
                                            android.widget.ImageView(ctx).apply {
                                                scaleType =
                                                    android.widget.ImageView.ScaleType.FIT_CENTER
                                            }
                                        },
                                        update = { iv ->
                                            iv.setImageDrawable(
                                                runCatching {
                                                    iv.context.packageManager.getApplicationIcon(
                                                        entries.first().info.provider.packageName,
                                                    )
                                                }.getOrNull(),
                                            )
                                        },
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        appName,
                                        color = onBg,
                                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${entries.size}",
                                        color = onBgMuted,
                                        fontSize = 13.sp,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    val chevRot by androidx.compose.animation.core.animateFloatAsState(
                                        targetValue = if (expanded) 0f else -90f,
                                        label = "pickerChevron",
                                    )
                                    Icon(
                                        Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = onBgMuted,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .rotate(chevRot),
                                    )
                                }
                            }
                            if (expanded) {
                                items(entries.size, key = { "w:$appName:$it" }) { i ->
                                    val entry = entries[i]
                                    ProviderRow(entry.info, entry.label, entry.appLabel,
                                        busy, onBg, onBgMuted, accent) {
                                        busy = true
                                        scope.launch(Dispatchers.IO) {
                                            addWidget(entry.info, entry.label, columns, rows, resKey, anchor)
                                            withContext(Dispatchers.Main) { busy = false; onClose(true) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Visible scrollbar when the list overflows (shared component;
                    // same one SearchActivity uses).
                    com.portalpad.app.ui.common.VerticalScrollbar(
                        listState = listState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            // Without an explicit width the bar laid out 0dp
                            // wide — present but invisible (field report x2).
                            .width(4.dp),
                    )
                }
            }
        }
    }

    /** Launch the phone-side widget-search text entry, pinned to display 0
     *  (same pattern as the dock's phone search — see DockOverlay). */
    private fun launchPhoneWidgetSearch() {
        runCatching {
            com.portalpad.app.presentation.GlassesToast.show(
                PortalPadApp.instance, display, "Type on your phone…", 3000L,
            )
        }
        runCatching {
            val opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(0)
                .toBundle()
            PortalPadApp.instance.startActivity(
                android.content.Intent(
                    PortalPadApp.instance,
                    com.portalpad.app.WidgetSearchActivity::class.java,
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                opts,
            )
        }.onFailure {
            android.util.Log.e("PortalPadWidget", "widget search launch failed", it)
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(text, color = com.portalpad.app.ui.theme.AbPrimaryBright,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
    }

    /** One provider row: preview image (falls back to the app icon), widget
     *  label, app name. */
    @Composable
    private fun ProviderRow(
        info: AppWidgetProviderInfo,
        label: String,
        appLabel: String,
        busy: Boolean,
        onBg: Color,
        onBgMuted: Color,
        accent: Color,
        onPick: () -> Unit,
    ) {
        // Nova-style shared card: big centered natural-aspect preview, name +
        // dimensions centered beneath. Subtitle carries the default placement
        // span (what the widget will actually get) + the setup badge.
        val dims = "${WidgetHostManager.defaultSpan(info.minWidth)}×" +
            "${WidgetHostManager.defaultSpan(info.minHeight)} cells"
        val setup = if (info.configure == null) "no setup needed" else "needs setup"
        com.portalpad.app.ui.common.WidgetPreviewCard(
            info = info,
            name = label,
            subtitle = "$dims · $setup",
            nameColor = onBg,
            subtitleColor = onBgMuted,
            enabled = !busy,
            onClick = onPick,
        )
    }

    /** Bind + place [info]: allocate an id, bind (escalating through grantbind),
     *  compute the span from the provider's min sizes, auto-place in the first
     *  free cell, persist. Widgets that declare a config activity get it
     *  launched on the PHONE (display 0) — config screens can't be reliably
     *  retargeted to a secondary display, and adding a widget is a setup act
     *  with the phone in hand. BLOCKING — runs on IO. */
    private suspend fun addWidget(
        info: AppWidgetProviderInfo,
        label: String,
        columns: Int,
        rows: Int,
        resKey: String,
        anchor: Pair<Int, Int>? = null,
    ) {
        val app = PortalPadApp.instance
        val cfg = app.prefs.widgetOverlayConfig.first()
        if (cfg.widgets.size >= MAX_OVERLAY_WIDGETS) {
            toast("Widget limit reached ($MAX_OVERLAY_WIDGETS)")
            return
        }
        // Span from the provider's declared minimums via [minSpan] (treated as
        // dp — see its doc for the convicting capture), clamped to the grid.
        val spanW = minSpan(info.minWidth).coerceIn(1, columns)
        val spanH = minSpan(info.minHeight).coerceIn(1, rows)
        // Placement: honor a tapped "+" cell anchor when the widget FITS there
        // (grid-clamped origin, no collision); otherwise first-fit as before.
        val resolved = resolveWidgets(cfg, resKey, columns, rows)
        val anchored = anchor?.let { (ac, ar) ->
            val c = ac.coerceIn(0, (columns - spanW).coerceAtLeast(0))
            val r = ar.coerceIn(0, (rows - spanH).coerceAtLeast(0))
            val clash = resolved.any { o ->
                placementsConflict(
                    c, r, spanW, spanH, 0f, 0f,
                    o.col, o.row, o.spanW, o.spanH, o.offsetXDp, o.offsetYDp,
                    bExW = o.extraWDp, bExH = o.extraHDp,
                )
            }
            if (!clash) Pair(c, r) else null
        }
        val cell = anchored
            ?: firstFreeCell(resolved, columns, rows, spanW, spanH) ?: run {
            toast("No room on the grid — remove a widget first")
            return
        }
        val id = WidgetHostManager.allocateId(displayContext)
        if (!WidgetHostManager.bindOrGrant(displayContext, id, info.provider)) {
            WidgetHostManager.deleteId(displayContext, id)
            toast("Couldn't bind widget — Shizuku or root access is required")
            return
        }
        android.util.Log.d("PortalPadWidget", "DIAG-PLACE write add $label res=$resKey → ${cell.first},${cell.second} ${spanW}x$spanH")
        app.prefs.setWidgetOverlayConfig(
            cfg.copy(
                widgets = cfg.widgets + OverlayWidget(
                    id = java.util.UUID.randomUUID().toString(),
                    provider = info.provider.flattenToString(),
                    label = label,
                    appWidgetId = id,
                    // Base = seed for other resolutions; this resolution gets
                    // its own placement entry immediately. 1x1 adds get
                    // LABEL_HEADROOM_DP of extra height by DEFAULT: bottom-
                    // labeled 1x1 widgets ("Ask Alexa") sit exactly at the
                    // cropping margin at one cell and drop their caption
                    // (field: one manual downward resize revealed it). Icon-
                    // only widgets just get a little air, and it's only the
                    // add-time default — resize away freely.
                    col = cell.first, row = cell.second,
                    spanW = spanW, spanH = spanH,
                    extraHDp = if (spanW == 1 && spanH == 1) LABEL_HEADROOM_DP else 0f,
                    placements = mapOf(
                        resKey to com.portalpad.app.data.Placement(
                            cell.first, cell.second, spanW, spanH,
                            extraHDp = if (spanW == 1 && spanH == 1) LABEL_HEADROOM_DP else 0f,
                        ),
                    ),
                ),
            ),
        )
        if (info.configure != null) {
            val launched = runCatching {
                app.startActivity(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                        component = info.configure
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }.isSuccess
            toast(
                if (launched) "Set up \"$label\" on your phone…"
                else "Open the widget's app on your phone to configure it",
            )
        }
    }

    /** Cell rect on the grid (col/row origin + span). */
    private data class CellRect(
        val col: Int, val row: Int, val w: Int, val h: Int,
        // Free-move fine offsets (dp) from the cell anchor. Declared AFTER
        // col/row/w/h so existing 4-way destructuring keeps its meaning.
        val offX: Float = 0f, val offY: Float = 0f,
        // Free-resize fractional size (dp) beyond the whole-cell span.
        val extraW: Float = 0f, val extraH: Float = 0f,
    )

    /** Width/height in dp of a [span]-cell widget. */
    private fun spanDp(span: Int): Float = span * CELL.value + (span - 1) * GAP.value

    /** dp position of a cell anchor plus its fine offset — same formula for
     *  both axes (the grid step is CELL+GAP). */
    private fun axisDp(cell: Int, offset: Float): Float = cell * (CELL.value + GAP.value) + offset

    /** The free-move collision rule, replacing cell-overlap everywhere: two
     *  widget RECTS conflict when they sit closer than the standard GAP on
     *  both axes. Exactly-GAP is allowed — that's what lattice adjacency has
     *  always been, and what the magnetic gap-snap produces. All dp math, so
     *  verdicts are identical across resolutions (fixed-dp grid). Size extras
     *  default 0 (lattice candidates); pass them for real widgets. */
    private fun placementsConflict(
        aCol: Int, aRow: Int, aW: Int, aH: Int, aOx: Float, aOy: Float,
        bCol: Int, bRow: Int, bW: Int, bH: Int, bOx: Float, bOy: Float,
        aExW: Float = 0f, aExH: Float = 0f, bExW: Float = 0f, bExH: Float = 0f,
    ): Boolean {
        val eps = 0.5f
        val aL = axisDp(aCol, aOx); val aT = axisDp(aRow, aOy)
        val bL = axisDp(bCol, bOx); val bT = axisDp(bRow, bOy)
        val aWd = spanDp(aW) + aExW; val aHd = spanDp(aH) + aExH
        val bWd = spanDp(bW) + bExW; val bHd = spanDp(bH) + bExH
        return aL + aWd + GAP.value - eps > bL && bL + bWd + GAP.value - eps > aL &&
            aT + aHd + GAP.value - eps > bT && bT + bHd + GAP.value - eps > aT
    }

    /** In-grid test for a possibly-offset, possibly-fractionally-sized
     *  placement: the cell anchor must satisfy the classic span-in-grid
     *  invariant (render-skip and seeding rely on it) AND the real RECT,
     *  offsets and extras included, must fit the grid. */
    private fun rectFitsGrid(
        col: Int, row: Int, sw: Int, sh: Int, offX: Float, offY: Float,
        columns: Int, rows: Int,
        exW: Float = 0f, exH: Float = 0f,
    ): Boolean {
        if (col < 0 || row < 0 || sw < 1 || sh < 1 || offX < 0f || offY < 0f) return false
        if (col + sw > columns || row + sh > rows) return false
        if (spanDp(sw) + exW < MIN_WIDGET_DP - 0.5f || spanDp(sh) + exH < MIN_WIDGET_DP - 0.5f) return false
        return axisDp(col, offX) + spanDp(sw) + exW <= spanDp(columns) + 0.5f &&
            axisDp(row, offY) + spanDp(sh) + exH <= spanDp(rows) + 0.5f
    }

    /** Grid-clamped target for the in-flight drag.
     *  Modes: 0=MOVE, 1=E (right edge), 2=S (bottom), 3=W (left, shifts col),
     *  4=N (top, shifts row) — Nova-style edge dots. resizeMode gating dropped
     *  (growth allowed on both axes; only provider MAXIMUMS clamp growth).
     *  MOVE and RESIZE are both FREE (dp-granular) with the same two magnets
     *  applied to the moving edge: neighbor gap-snap first (edge lands exactly
     *  GAP from another widget's edge), then whole-cell snap (casual drags
     *  land on tidy cell sizes/positions; extras collapse to 0). SIZE floor is
     *  MIN_WIDGET_DP per axis — small enough that an icon shows slightly
     *  cropped, large enough that the widget and its dots stay grabbable.
     *  Provider MINIMUMS deliberately do NOT clamp manual resize (the user may
     *  shrink a widget even if it clips its own content — heal-grow skips
     *  userSized entries). [others] is the resolved world; [w] is filtered. */
    private fun dragTarget(
        others: List<OverlayWidget>,
        w: OverlayWidget,
        mode: Int,
        offset: androidx.compose.ui.geometry.Offset,
        cellPx: Float,
        columns: Int,
        rows: Int,
    ): CellRect? {
        val step = CELL.value + GAP.value
        val myW = spanDp(w.spanW) + w.extraWDp
        val myH = spanDp(w.spanH) + w.extraHDp
        val left = axisDp(w.col, w.offsetXDp)
        val top = axisDp(w.row, w.offsetYDp)
        val gridW = spanDp(columns)
        val gridH = spanDp(rows)
        // cellPx = px per grid step, so px to dp is offset / cellPx * step.
        val dxDp = offset.x / cellPx * step
        val dyDp = offset.y / cellPx * step
        val neighbors = others.filter { it.id != w.id }
        // Magnet for a moving POSITION (leading edge): neighbor-derived
        // candidates first, then the cell lattice.
        fun snapPos(raw: Float, edges: List<Float>): Float {
            var best = Float.MAX_VALUE
            var snapped = raw
            for (e in edges) {
                val d = kotlin.math.abs(raw - e)
                if (d <= NEIGHBOR_SNAP_DP && d < best) { best = d; snapped = e }
            }
            if (best != Float.MAX_VALUE) return snapped
            val lattice = kotlin.math.round(raw / step) * step
            return if (kotlin.math.abs(raw - lattice) <= LATTICE_SNAP_DP) lattice else raw
        }
        // Magnet for a moving SIZE: neighbor-derived widths first (my far edge
        // lands GAP before a neighbor's near edge), then whole-cell sizes.
        fun snapSize(raw: Float, candidates: List<Float>): Float {
            var best = Float.MAX_VALUE
            var snapped = raw
            for (cand in candidates) {
                val d = kotlin.math.abs(raw - cand)
                if (d <= NEIGHBOR_SNAP_DP && d < best) { best = d; snapped = cand }
            }
            if (best != Float.MAX_VALUE) return snapped
            val k = kotlin.math.round((raw + GAP.value) / step).toInt().coerceAtLeast(1)
            val cell = spanDp(k)
            return if (kotlin.math.abs(raw - cell) <= LATTICE_SNAP_DP) cell else raw
        }
        // Decompose a free size into whole-cell span + fractional extra. The
        // extra is negative only at span 1 (sub-cell sizes).
        fun sizeToSpan(size: Float): Pair<Int, Float> {
            var k = kotlin.math.floor((size + GAP.value) / step).toInt()
            if (k < 1) k = 1
            var e = size - spanDp(k)
            if (kotlin.math.abs(e) < 0.75f) e = 0f
            return k to e
        }
        fun posToCell(pos: Float, span: Int, maxCells: Int): Pair<Int, Float> {
            val cell = kotlin.math.floor(pos / step).toInt()
                .coerceIn(0, (maxCells - span).coerceAtLeast(0))
            var off = (pos - cell * step).coerceAtLeast(0f)
            if (off < 0.75f) off = 0f
            return cell to off
        }
        if (mode == 0) {
            var x = left + dxDp
            var y = top + dyDp
            val xEdges = neighbors.flatMap { o ->
                val oL = axisDp(o.col, o.offsetXDp)
                listOf(oL + spanDp(o.spanW) + o.extraWDp + GAP.value, oL - GAP.value - myW)
            }
            val yEdges = neighbors.flatMap { o ->
                val oT = axisDp(o.row, o.offsetYDp)
                listOf(oT + spanDp(o.spanH) + o.extraHDp + GAP.value, oT - GAP.value - myH)
            }
            x = snapPos(x, xEdges).coerceIn(0f, (gridW - myW).coerceAtLeast(0f))
            y = snapPos(y, yEdges).coerceIn(0f, (gridH - myH).coerceAtLeast(0f))
            val (col, offX) = posToCell(x, w.spanW, columns)
            val (row, offY) = posToCell(y, w.spanH, rows)
            return CellRect(col, row, w.spanW, w.spanH, offX, offY, w.extraWDp, w.extraHDp)
        }
        val awm = AppWidgetManager.getInstance(displayContext)
        val info = runCatching { awm.getAppWidgetInfo(w.appWidgetId) }.getOrNull() ?: return null
        // Provider-declared MAXIMUMS (API 31+, optional), read as dp — growth
        // stops where the widget stops rendering. Never below the CURRENT size
        // so an existing oversize widget doesn't get stuck.
        val provMaxWDp = if (android.os.Build.VERSION.SDK_INT >= 31 && info.maxResizeWidth > 0) {
            maxOf(info.maxResizeWidth.toFloat(), myW)
        } else Float.MAX_VALUE
        val provMaxHDp = if (android.os.Build.VERSION.SDK_INT >= 31 && info.maxResizeHeight > 0) {
            maxOf(info.maxResizeHeight.toFloat(), myH)
        } else Float.MAX_VALUE
        return when (mode) {
            1 -> { // E: right edge free — position fixed
                val widthCands = neighbors.map { o -> (axisDp(o.col, o.offsetXDp) - GAP.value) - left }
                val newW = snapSize(myW + dxDp, widthCands)
                    .coerceIn(MIN_WIDGET_DP, minOf(provMaxWDp, gridW - left))
                val (sw, exW) = sizeToSpan(newW)
                CellRect(w.col, w.row, sw, w.spanH, w.offsetXDp, w.offsetYDp, exW, w.extraHDp)
            }
            2 -> { // S: bottom edge free — position fixed
                val heightCands = neighbors.map { o -> (axisDp(o.row, o.offsetYDp) - GAP.value) - top }
                val newH = snapSize(myH + dyDp, heightCands)
                    .coerceIn(MIN_WIDGET_DP, minOf(provMaxHDp, gridH - top))
                val (sh, exH) = sizeToSpan(newH)
                CellRect(w.col, w.row, w.spanW, sh, w.offsetXDp, w.offsetYDp, w.extraWDp, exH)
            }
            3 -> { // W: left edge free — RIGHT edge stays planted
                val right = left + myW
                val leftEdges = neighbors.map { o ->
                    axisDp(o.col, o.offsetXDp) + spanDp(o.spanW) + o.extraWDp + GAP.value
                }
                val newLeft = snapPos(left + dxDp, leftEdges)
                    .coerceIn((right - provMaxWDp).coerceAtLeast(0f), right - MIN_WIDGET_DP)
                val newW = right - newLeft
                val (sw, exW) = sizeToSpan(newW)
                val (col, offX) = posToCell(newLeft, sw, columns)
                CellRect(col, w.row, sw, w.spanH, offX, w.offsetYDp, exW, w.extraHDp)
            }
            4 -> { // N: top edge free — BOTTOM edge stays planted
                val bottom = top + myH
                val topEdges = neighbors.map { o ->
                    axisDp(o.row, o.offsetYDp) + spanDp(o.spanH) + o.extraHDp + GAP.value
                }
                val newTop = snapPos(top + dyDp, topEdges)
                    .coerceIn((bottom - provMaxHDp).coerceAtLeast(0f), bottom - MIN_WIDGET_DP)
                val newH = bottom - newTop
                val (sh, exH) = sizeToSpan(newH)
                val (row, offY) = posToCell(newTop, sh, rows)
                CellRect(w.col, row, w.spanW, sh, w.offsetXDp, offY, w.extraWDp, exH)
            }
            else -> null
        }
    }

    /** Substitute THIS resolution's placement into each widget (transiently
     *  seeding widgets not yet placed here) and drop NO_FIT ones — downstream
     *  grid math only ever sees in-grid coordinates. */
    private fun resolveWidgets(
        cfg: WidgetOverlayConfig,
        resKey: String,
        columns: Int,
        rows: Int,
    ): List<OverlayWidget> {
        val placed = mutableListOf<OverlayWidget>()
        for (w in cfg.widgets) {
            val stored = w.placements[resKey]
            // Same trust rules as the persisted seed+heal pass: an out-of-grid
            // or colliding stored entry is re-seeded transiently rather than
            // rendered/collided against (the persist pass writes the durable fix).
            if (stored?.hidden == true) continue // user: "not on this resolution"
            val p = when {
                stored == null -> seedPlacement(w, placed, columns, rows)
                stored.col == com.portalpad.app.data.Placement.NO_FIT -> stored
                !placementValid(stored, placed, columns, rows) ->
                    seedPlacement(
                        w.copy(col = stored.col, row = stored.row,
                            spanW = stored.spanW, spanH = stored.spanH),
                        placed, columns, rows,
                    )
                else -> stored
            }
            if (p.col == com.portalpad.app.data.Placement.NO_FIT) continue
            placed += w.copy(
                col = p.col, row = p.row, spanW = p.spanW, spanH = p.spanH,
                offsetXDp = p.offsetXDp, offsetYDp = p.offsetYDp,
                extraWDp = p.extraWDp, extraHDp = p.extraHDp,
            )
        }
        return placed
    }

    /** A stored placement is only trusted if its rect (offsets included) is
     *  fully in-grid and doesn't sit closer than GAP to anything already
     *  placed this pass. RECT-based, not cell-based: cell-footprint overlap
     *  would flag a legitimately gap-snapped neighbor pair as colliding and
     *  the persist pass would re-seed it back to the lattice, silently
     *  undoing the user's free-move. */
    private fun placementValid(
        p: com.portalpad.app.data.Placement,
        placed: List<OverlayWidget>,
        columns: Int,
        rows: Int,
    ): Boolean =
        rectFitsGrid(p.col, p.row, p.spanW, p.spanH, p.offsetXDp, p.offsetYDp, columns, rows,
            exW = p.extraWDp, exH = p.extraHDp) &&
            placed.none { o ->
                placementsConflict(
                    p.col, p.row, p.spanW, p.spanH, p.offsetXDp, p.offsetYDp,
                    o.col, o.row, o.spanW, o.spanH, o.offsetXDp, o.offsetYDp,
                    aExW = p.extraWDp, aExH = p.extraHDp,
                    bExW = o.extraWDp, bExH = o.extraHDp,
                )
            }

    /** Arrangement of [w] on a resolution: preferred span at the base spot,
     *  else first-fit, else SHRINK-TO-FIT — progressively trim the larger
     *  dimension (never below the provider minimums [minW]/[minH]) until
     *  something fits; a smaller widget beats a hidden one (field case: a
     *  21-wide widget evicted from a grid with a 19-wide gap free). Only when
     *  even the minimum span has no home does it return NO_FIT — and that
     *  record keeps the ORIGINAL spans so a roomier grid can restore full
     *  size. All math clamp-safe by construction. */
    private fun seedPlacement(
        w: OverlayWidget,
        placed: List<OverlayWidget>,
        columns: Int,
        rows: Int,
        minW: Int = 1,
        minH: Int = 1,
    ): com.portalpad.app.data.Placement {
        val sw0 = w.spanW.coerceIn(1, columns)
        val sh0 = w.spanH.coerceIn(1, rows)
        val mnW = minW.coerceIn(1, columns)
        val mnH = minH.coerceIn(1, rows)
        var sw = sw0
        var sh = sh0
        while (true) {
            // Seeds land ON the lattice (offset 0/0); the collision world may
            // contain free-moved neighbors, so the check is the rect rule.
            // Seeds land ON the lattice (offset 0/0) but CARRY the base's
            // fractional size (e.g. the 1x1 label headroom), so a widget seeded
            // onto a new resolution keeps its intended box.
            val fitsAtBase = rectFitsGrid(w.col, w.row, sw, sh, 0f, 0f, columns, rows,
                exW = w.extraWDp, exH = w.extraHDp) &&
                placed.none { o ->
                    placementsConflict(
                        w.col, w.row, sw, sh, 0f, 0f,
                        o.col, o.row, o.spanW, o.spanH, o.offsetXDp, o.offsetYDp,
                        aExW = w.extraWDp, aExH = w.extraHDp,
                        bExW = o.extraWDp, bExH = o.extraHDp,
                    )
                }
            val spot = if (fitsAtBase) Pair(w.col, w.row)
            else firstFreeCell(placed, columns, rows, sw, sh)
            if (spot != null) {
                if (sw < sw0 || sh < sh0) {
                    android.util.Log.d("PortalPadWidget",
                        "DIAG-PLACE shrink-to-fit ${w.label} ${sw0}x$sh0 → ${sw}x$sh")
                }
                return com.portalpad.app.data.Placement(spot.first, spot.second, sw, sh,
                    extraWDp = w.extraWDp, extraHDp = w.extraHDp)
            }
            when {
                sw > mnW && (sw >= sh || sh <= mnH) -> sw--
                sh > mnH -> sh--
                else -> return com.portalpad.app.data.Placement(
                    com.portalpad.app.data.Placement.NO_FIT,
                    com.portalpad.app.data.Placement.NO_FIT, sw0, sh0,
                )
            }
        }
    }

    /** Provider min size → grid cells, treating the declared value as DP.
     *  The old phone-density division (min/​~3.0) under-computed spans — the
     *  convicting capture: AfterShip min=333x240, placed 8x2 = 147dp tall,
     *  rendered cropped; 240-as-dp → 4 cells (304dp) fits. If some provider's
     *  widget now places comically LARGE, that's the signal this interpretation
     *  needs per-device calibration — revisit with a capture. */
    private fun minSpan(minDeclared: Int): Int =
        kotlin.math.ceil(minDeclared.toFloat() / (CELL.value + GAP.value)).toInt()
            .coerceAtLeast(1)

    /** Persist the finished drag if its target is valid and free. Suspend —
     *  called on IO from the drag-end callback. */
    private suspend fun commitDrag(
        cfg: WidgetOverlayConfig,
        id: String,
        mode: Int,
        offset: androidx.compose.ui.geometry.Offset,
        cellPx: Float,
        columns: Int,
        rows: Int,
        resKey: String,
    ) {
        val resolved = resolveWidgets(cfg, resKey, columns, rows)
        val w = resolved.firstOrNull { it.id == id } ?: return
        val t = dragTarget(resolved, w, mode, offset, cellPx, columns, rows) ?: return
        if (t.col == w.col && t.row == w.row && t.w == w.spanW && t.h == w.spanH &&
            t.offX == w.offsetXDp && t.offY == w.offsetYDp &&
            t.extraW == w.extraWDp && t.extraH == w.extraHDp
        ) return
        val clash = resolved.any { o ->
            o.id != id &&
                placementsConflict(
                    t.col, t.row, t.w, t.h, t.offX, t.offY,
                    o.col, o.row, o.spanW, o.spanH, o.offsetXDp, o.offsetYDp,
                    aExW = t.extraW, aExH = t.extraH,
                    bExW = o.extraWDp, bExH = o.extraHDp,
                )
        }
        // DIAG-PLACE commit witness: the target, the WORLD this commit clash-
        // checked against, and the verdict — for the two wrong-verdict field
        // cases (an overlap that committed; a free-space move that blocked).
        // The next wrong verdict shows exactly what the commit believed.
        android.util.Log.d("PortalPadWidget",
            "DIAG-PLACE commit ${w.label} mode=$mode res=$resKey " +
                "from=${w.col},${w.row}+${w.offsetXDp.toInt()},${w.offsetYDp.toInt()} ${w.spanW}x${w.spanH} " +
                "to=${t.col},${t.row}+${t.offX.toInt()},${t.offY.toInt()} ${t.w}x${t.h} verdict=${if (clash) "BLOCK" else "OK"} " +
                "world=[" + resolved.joinToString { o ->
                    "${o.label}@${o.col},${o.row}+${o.offsetXDp.toInt()},${o.offsetYDp.toInt()} ${o.spanW}x${o.spanH}"
                } + "]")
        if (clash) {
            toast("That spot is taken")
            return
        }
        // Writes go to THIS resolution's placement entry only — base fields
        // stay as the seed for resolutions not yet visited.
        android.util.Log.d("PortalPadWidget",
            "DIAG-PLACE write commit ${w.label} res=$resKey → ${t.col},${t.row}+${t.offX.toInt()},${t.offY.toInt()} ${t.w}x${t.h}")
        val app = PortalPadApp.instance
        val cur = app.prefs.widgetOverlayConfig.first()
        app.prefs.setWidgetOverlayConfig(
            cur.copy(
                widgets = cur.widgets.map {
                    if (it.id == id) it.copy(
                        placements = it.placements +
                            (resKey to com.portalpad.app.data.Placement(
                                t.col, t.row, t.w, t.h,
                                offsetXDp = t.offX, offsetYDp = t.offY,
                                extraWDp = t.extraW, extraHDp = t.extraH,
                                // A RESIZE (mode 1-4) is a deliberate user size —
                                // latch it so heal-grow won't undo a shrink-below-min.
                                // A MOVE (mode 0) preserves whatever the prior
                                // resize set, so moving a shrunk widget keeps it shrunk.
                                userSized = mode != 0 ||
                                    (it.placements[resKey]?.userSized ?: false),
                            )),
                    )
                    else it
                },
            ),
        )
    }

    /** First free (col,row) where a spanW×spanH widget fits at least GAP away
     *  from every saved widget (rect rule — neighbors may be free-moved off
     *  the lattice). Row-major scan; candidates land ON the lattice. */
    private fun firstFreeCell(
        widgets: List<OverlayWidget>,
        columns: Int,
        rows: Int,
        spanW: Int,
        spanH: Int,
    ): Pair<Int, Int>? {
        for (r in 0..(rows - spanH)) {
            for (c in 0..(columns - spanW)) {
                val clash = widgets.any { w ->
                    placementsConflict(
                        c, r, spanW, spanH, 0f, 0f,
                        w.col, w.row, w.spanW, w.spanH, w.offsetXDp, w.offsetYDp,
                        bExW = w.extraWDp, bExH = w.extraHDp,
                    )
                }
                if (!clash) return Pair(c, r)
            }
        }
        return null
    }

    /** USER hide on THIS resolution: stamp the entry hidden=true, keeping its
     *  spot/size for exact restore. Falls back to the live resolved values if
     *  no entry was persisted yet. Other resolutions untouched. */
    private suspend fun hideOnResolution(w: OverlayWidget, resKey: String) {
        val app = PortalPadApp.instance
        val cur = app.prefs.widgetOverlayConfig.first()
        app.prefs.setWidgetOverlayConfig(
            cur.copy(
                widgets = cur.widgets.map {
                    if (it.id != w.id) it else {
                        val stored = it.placements[resKey]
                            ?: com.portalpad.app.data.Placement(
                                w.col, w.row, w.spanW, w.spanH,
                                offsetXDp = w.offsetXDp, offsetYDp = w.offsetYDp,
                                extraWDp = w.extraWDp, extraHDp = w.extraHDp,
                            )
                        android.util.Log.d("PortalPadWidget",
                            "DIAG-PLACE write hide ${w.label} res=$resKey")
                        it.copy(placements = it.placements + (resKey to stored.copy(hidden = true)))
                    }
                },
            ),
        )
        toast("Hidden on this resolution")
    }

    /** Clears the user-hidden flag; the normal re-validation path restores the
     *  widget at its stored spot (shrink-to-fit if the space got taken). */
    private suspend fun unhideOnResolution(id: String, resKey: String) {
        val app = PortalPadApp.instance
        val cur = app.prefs.widgetOverlayConfig.first()
        app.prefs.setWidgetOverlayConfig(
            cur.copy(
                widgets = cur.widgets.map {
                    val stored = it.placements[resKey]
                    if (it.id != id || stored == null) it else {
                        android.util.Log.d("PortalPadWidget",
                            "DIAG-PLACE write unhide ${it.label} res=$resKey")
                        it.copy(placements = it.placements + (resKey to stored.copy(hidden = false)))
                    }
                },
            ),
        )
    }

    private fun toast(msg: String) {
        runCatching {
            GlassesToast.show(PortalPadApp.instance, display, msg)
        }
    }

    companion object {
        private const val TAG = "WidgetOverlay"

        /** Pinned chrome density — keep in sync with DockBar.BASELINE_DENSITY. */
        private const val BASELINE_DENSITY = 1.33f

        /** Fixed grid cell size / gap / outer margin, in pinned dp. */
        private val CELL = 70.dp
        private val GAP = 8.dp
        private val GRID_MARGIN = 24.dp

        /** Diameter of the Nova-style edge-midpoint resize dots. */
        private val DOT_SIZE = 22.dp
        // Free-move magnet radii (dp). Neighbor gap-snap outranks the lattice
        // and is slightly stronger — the whole point of free-move is landing
        // exactly GAP from a neighbor; the lattice magnet keeps casual drops
        // tidy (offsets collapse to 0, so untouched layouts stay lattice-pure).
        private const val NEIGHBOR_SNAP_DP = 10f
        private const val LATTICE_SNAP_DP = 8f
        // Free-resize floor per axis: a standard widget icon is ~48dp, so 40dp
        // still shows it slightly cropped (the user's spec) while keeping the
        // widget and its resize dots comfortably grabbable. One-line tunable.
        private const val MIN_WIDGET_DP = 40f
        // Default extra height for 1x1 adds so bottom labels show (see addWidget).
        private const val LABEL_HEADROOM_DP = 16f


    }
}
