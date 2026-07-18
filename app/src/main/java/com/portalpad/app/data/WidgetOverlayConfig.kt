package com.portalpad.app.data

import kotlinx.serialization.Serializable

/** Hard cap on widgets in the overlay — a RAM/process BACKSTOP, not the
 *  everyday limit. The grid itself is the real limit (a widget that won't fit is
 *  refused by firstFreeCell with "no room"); this only exists because each
 *  widget is a live AppWidgetHostView that can wake its provider's process, and
 *  the S24 is RAM-tight under multi-streaming (lmkd mass-reclaim, 2026-07). Set
 *  comfortably above any sane real layout so it rarely bites — a wide ultrawide
 *  grid could hold 20+ tiny cells, but binding that many live widgets is memory
 *  we don't want competing with a streaming session. */
const val MAX_OVERLAY_WIDGETS = 12

/**
 * One widget placed on the overlay grid.
 *
 * Positions are stored in GRID COORDINATES (cell col/row + span), never pixels,
 * so the same saved layout is valid in both standard and ultrawide resolutions:
 * the grid uses a FIXED cell size in dp anchored top-left, and ultrawide simply
 * exposes more columns. A widget whose cells fall outside the current grid
 * (placed in ultrawide, viewed in standard) is skipped at render time and
 * reappears when the wider grid returns — the saved config is never mutated by
 * a resolution switch.
 *
 * [appWidgetId] is the system binding for THIS install. It is serialized (and
 * therefore rides along in the app backup), but ids are NOT portable across
 * installs — the restore path validates each id via getAppWidgetInfo and
 * re-binds by [provider] when the id is dead. Per-widget configuration
 * (e.g. which city a weather widget shows) lives inside the provider app and
 * cannot be backed up or restored from here; a re-bound widget comes back
 * unconfigured. This is the same limitation every launcher has.
 */
/** One resolution's arrangement of a widget: cell origin + span. A col of
 *  [Placement.NO_FIT] marks "doesn't fit on this resolution" — the widget stays
 *  bound and keeps its other resolutions' placements, it just doesn't render
 *  here (seeding found no free room). Persisted so the no-fit decision isn't
 *  recomputed every show. */
@Serializable
data class Placement(
    val col: Int,
    val row: Int,
    val spanW: Int,
    val spanH: Int,
    /** USER choice: "not on this resolution" — distinct from NO_FIT (the
     *  SYSTEM's "no room", retried every show). Hidden entries are never
     *  rendered, never collide, never re-seeded or healed; position/size are
     *  kept so unhiding restores the widget exactly (re-validated on return).
     *  Other resolutions are untouched. Default keeps old configs decoding. */
    val hidden: Boolean = false,
    /** FREE-MOVE fine offsets, in dp, from the cell anchor (col/row). Always
     *  >= 0 and < one cell step; the anchor stays the coarse position so every
     *  cell-based consumer (render-skip, seeding, per-resolution mapping)
     *  keeps working unchanged. 0/0 = exactly on the lattice — the value every
     *  pre-free-move config decodes to, so old layouts are untouched. Spans
     *  stay whole cells; only POSITION is fine-grained. */
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 0f,
    /** FREE-RESIZE fractional size (dp) beyond the whole-cell span — size's
     *  twin of the position offsets above. The rect width is
     *  spanDp(spanW) + extraWDp; extras may be NEGATIVE only at span 1
     *  (sub-cell sizes, floored at MIN_WIDGET_DP by the drag logic). 0/0 =
     *  exactly whole cells — what every pre-free-resize config decodes to. */
    val extraWDp: Float = 0f,
    val extraHDp: Float = 0f,
    /** TRUE once the user has manually RESIZED this widget on this resolution.
     *  The seed/heal pass grows an under-provider-minimum widget back up when
     *  there's room — desirable for AUTO placement, but it would undo a
     *  deliberate shrink-below-min (the user explicitly chose the smaller size,
     *  even if the widget clips its own content). This flag makes heal-grow
     *  skip user-sized entries. Default false keeps auto-heal for everything
     *  placed by the system. */
    val userSized: Boolean = false,
) {
    companion object { const val NO_FIT = -1 }
}

@Serializable
data class OverlayWidget(
    /** Stable row identity for lists / removal. */
    val id: String,
    /** Flattened ComponentName of the AppWidgetProvider. */
    val provider: String,
    /** Human label captured at add time (shown in Settings and placeholders
     *  even when the provider app is gone). */
    val label: String,
    /** System widget binding id for this install. */
    val appWidgetId: Int,
    /** Base placement: the seed for resolutions not yet in [placements]
     *  (and the whole story for configs written before per-resolution
     *  placement existed — the default keeps old JSON decoding). */
    val col: Int,
    val row: Int,
    val spanW: Int,
    val spanH: Int,
    /** Per-resolution arrangements, keyed "WxH" from the display's real pixel
     *  size (e.g. "1920x1080", "3840x1080"). ONE widget set, MANY arrangements:
     *  the same bound widget sits wherever each resolution last put it. First
     *  visit to a resolution seeds an entry from the base placement (same
     *  span — cells are fixed dp, so physical size is resolution-independent;
     *  position clamped/re-placed to fit the new grid). Moves/resizes write
     *  ONLY the current resolution's entry. Default keeps old configs valid. */
    val placements: Map<String, Placement> = emptyMap(),
    /** Runtime carrier for the CURRENT resolution's free-move offsets: the
     *  resolve pass copies placements[resKey] into col/row/span AND these, so
     *  downstream layout/collision code reads one flat widget. As BASE fields
     *  they stay 0 — new-resolution seeding lands on the lattice. Serialized
     *  defaults keep old configs decoding. */
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 0f,
    /** Runtime carriers for the current resolution's fractional SIZE, same
     *  channel as the position offsets above. Base values stay 0. */
    val extraWDp: Float = 0f,
    val extraHDp: Float = 0f,
)

/**
 * The widget overlay: a summonable layer of Android app widgets on the
 * external display, toggled by assigning "Widget Overlay" to the Home or Back
 * button. Serialized as ONE DataStore key (like DockConfig / TopBarConfig /
 * FolderWindowConfig) so the whole feature state — widgets, placement, style —
 * is included in the app backup automatically.
 *
 * [background] is the full-screen backdrop SCRIM behind the grid; nothing is
 * painted behind individual widgets (they draw their own RemoteViews
 * backgrounds). Default is a dim ~40% wash — dimmer and more transparent than
 * the dock's bar default — so summoning the layer reads as a layer over the
 * running apps, not a mode switch that blacks them out. cornerRadiusDp is
 * meaningless for a full-screen scrim and the customization page hides that
 * control.
 */
@Serializable
data class WidgetOverlayConfig(
    val widgets: List<OverlayWidget> = emptyList(),
    val background: BarStyle = BarStyle(
        colorA = 0x59141A26,
        colorB = 0x730B0E16,
        cornerRadiusDp = 0,
    ),
)
