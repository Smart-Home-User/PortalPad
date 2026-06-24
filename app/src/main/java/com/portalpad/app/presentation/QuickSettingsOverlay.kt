package com.portalpad.app.presentation

import android.content.Context
import android.graphics.PixelFormat
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.portalpad.app.ui.theme.PortalPadTheme

/**
 * A Windows-11-style "quick settings" flyout on the external display, opened by
 * tapping the dock's connectivity cluster (Wi-Fi / Bluetooth / VPN). It mirrors
 * [NotificationPanelOverlay]'s overlay plumbing (the system quick-settings panel
 * can't be placed on a secondary display, so this is our own).
 *
 * Three tiles — Wi-Fi, Bluetooth, VPN — each shows a status line and, when
 * tapped, opens that setting in a FREEFORM window on the PHONE (display 0) and
 * shows an "Opened on your phone…" toast on the glasses, exactly like the dock's
 * status icons did when they were individually tappable. Battery is intentionally
 * NOT part of this panel.
 *
 * The panel background tracks the DOCK COLOR (the same gradient the dock band
 * uses, read from prefs), and its text contrast flips with that color's
 * luminance so labels stay readable on any dock theme.
 */
class QuickSettingsOverlay(
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
            setViewTreeLifecycleOwner(this@QuickSettingsOverlay)
            setViewTreeSavedStateRegistryOwner(this@QuickSettingsOverlay)
            setViewTreeViewModelStoreOwner(this@QuickSettingsOverlay)
            setContent {
                // Compose at the display's REAL density (matching DockOverlay) so the
                // dock's published pixel rect converts to dp 1:1 and the panel lines
                // up edge-to-edge with the dock.
                val m = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(m.density, 1f),
                ) {
                    PortalPadTheme { PanelContent() }
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHost.windowType,
            // NOT_FOCUSABLE — taps arrive via the injected cursor; the panel needs
            // no IME and shouldn't steal focus (a focusable overlay opened via a
            // cursor tap can lose focus and tear down). DIM_BEHIND reads as modal.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            dimAmount = 0.45f
        }

        runCatching { windowManager.addView(composeView, params) }
            .onFailure { Log.e(TAG, "addView failed", it); return }
        view = composeView
        isShowing = true
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        com.portalpad.app.service.PortalPadForegroundService.raiseCursor()
        Log.d(TAG, "Quick-settings panel added to display $displayId")
    }

    fun dismiss() {
        if (!isShowing) return
        // Clear the dock's "menu open" signal on every dismiss path (backdrop tap,
        // tile-launch, service-driven), so the dock can resume auto-hiding.
        runCatching { com.portalpad.app.PortalPadApp.instance.setQuickSettingsOpen(false) }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        _viewModelStore.clear()
        isShowing = false
    }

    /**
     * Open a system setting in a FREEFORM window on the PHONE (display 0) and tell
     * the user — on the glasses — that it opened there. These are system screens
     * that can't move to the VD, so without the cue the tap looks like a no-op.
     * Identical behavior to the dock status icons' old per-icon tap. Dismisses the
     * panel afterward.
     */
    private fun launchSettingOnPhone(action: String) {
        val app = com.portalpad.app.PortalPadApp.instance
        // Cue on the glasses.
        runCatching {
            GlassesToast.show(app, display, "Opened on your phone…", 3500L)
        }
        var launched = false
        if (app.access.isReady) {
            // Don't let our launch's window-change storm re-front the trackpad.
            com.portalpad.app.service.PortalPadForegroundService.suppressDisplayChanges(3000)
            launched = runCatching {
                // Launch FULLSCREEN (default mode) on the phone — not freeform.
                // VPN settings already does this implicitly (it isn't freeform-
                // resizable), giving a clean full-portrait window; the resizable
                // settings opened tiny in freeform and Samsung re-snapped any
                // resize, so we drop --windowingMode 5 and let them all open
                // full-portrait. The "Tap here to exit" band overlays on top.
                val out = app.access.execShell(
                    "am start --display 0 -a $action",
                )
                !out.contains("Error:", ignoreCase = true) &&
                    !out.contains("Exception", ignoreCase = true)
            }.getOrDefault(false)
            if (launched) {
                // Frame the window with red "Tap here to exit" bands on the phone.
                com.portalpad.app.service.PortalPadForegroundService.attachPhoneExitBands(action)
            }
        }
        if (!launched) {
            launched = runCatching {
                app.startActivity(
                    android.content.Intent(action)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }.getOrDefault(false)
        }
        if (!launched) runCatching {
            app.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        dismiss()
    }

    // ─── UI ─────────────────────────────────────────────────────────────

    @Composable
    private fun PanelContent() {
        val app = com.portalpad.app.PortalPadApp.instance
        val ctx = displayContext

        // Dock color → panel background + readable text. Same gradient the dock
        // band paints, read live from prefs so the panel always matches the dock.
        val dockConfig by app.prefs.dockConfig.collectAsState(
            initial = com.portalpad.app.data.DockConfig(),
        )
        val colorA = Color(dockConfig.style.colorAInt())
        val colorB = Color(dockConfig.style.colorBInt())
        val brush = com.portalpad.app.ui.common.toComposeBrush(dockConfig.style)
        fun lum(c: Color) = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
        val bgDark = (lum(colorA) + lum(colorB)) / 2f < 0.5f
        val strong = if (bgDark) Color.White else Color(0xFF15131A)
        val muted = if (bgDark) strong.copy(alpha = 0.65f) else Color(0xFF15131A).copy(alpha = 0.55f)
        val panelRadius = dockConfig.style.cornerRadiusDp.dp

        // Live connectivity state (same detection as the dock's status icons).
        var btOn by remember { mutableStateOf(false) }
        DisposableEffect(Unit) {
            fun current(): Boolean = runCatching {
                val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE)
                    as? android.bluetooth.BluetoothManager
                mgr?.adapter?.isEnabled == true
            }.getOrDefault(false)
            btOn = current()
            val filter = android.content.IntentFilter(
                android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED,
            )
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: android.content.Intent?) { btOn = current() }
            }
            runCatching { ctx.registerReceiver(receiver, filter) }
            onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
        }
        // Connectivity + network type from the centralized SignalMonitor — same
        // source as the dock cluster, so no per-panel ConnectivityManager callback.
        val wifiConnected by app.signal.wifiConnected.collectAsState()
        val vpnActive by app.signal.vpnActive.collectAsState()
        val cellularActive by app.signal.cellularActive.collectAsState()
        val cellType by app.signal.cellType.collectAsState()
        val btCount by app.signal.btConnectedCount.collectAsState()
        val wifiSsid by app.signal.wifiSsid.collectAsState()
        // Cellular presence + carrier name (both permission-free). networkOperatorName
        // is the SPN/operator label; may be blank on some devices/roaming. We do NOT
        // read network type (5G/LTE) or signal level — those need READ_PHONE_STATE
        // (Tier A: permission-free).
        val hasCellular = remember {
            runCatching {
                ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)
            }.getOrDefault(false)
        }
        val carrierName = remember {
            runCatching {
                (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager)
                    ?.networkOperatorName?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        // Airplane mode (free: Settings.Global + ACTION_AIRPLANE_MODE broadcast).
        fun airplaneNow(): Boolean = runCatching {
            android.provider.Settings.Global.getInt(
                ctx.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0,
            ) != 0
        }.getOrDefault(false)
        var airplaneOn by remember { mutableStateOf(airplaneNow()) }
        DisposableEffect(Unit) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: android.content.Intent?) { airplaneOn = airplaneNow() }
            }
            runCatching {
                ctx.registerReceiver(receiver, android.content.IntentFilter("android.intent.action.AIRPLANE_MODE"))
            }
            onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
        }

        // Mobile hotspot (soft AP). No permission-free public API exists, so the
        // INITIAL state is read via the elevated shell (best-effort dumpsys parse)
        // and live updates come from the WIFI_AP_STATE_CHANGED broadcast (free to
        // receive). TODO(on-device): the dumpsys token varies by OEM — if the
        // initial state reads wrong, adjust the markers below.
        var hotspotOn by remember { mutableStateOf(false) }
        DisposableEffect(Unit) {
            val appRef = com.portalpad.app.PortalPadApp.instance
            Thread {
                val on = runCatching {
                    if (!appRef.access.isReady) return@runCatching false
                    val out = appRef.access.execShell("dumpsys wifi")
                    out.contains("TetheredState") || out.contains("WIFI_AP_STATE_ENABLED")
                }.getOrDefault(false)
                hotspotOn = on
            }.start()
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: android.content.Intent?) {
                    // 13 = WIFI_AP_STATE_ENABLED, 12 = enabling.
                    val st = i?.getIntExtra("wifi_state", 11) ?: 11
                    hotspotOn = st == 13 || st == 12
                }
            }
            runCatching {
                ctx.registerReceiver(receiver, android.content.IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"))
            }
            onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
        }

        // Sizing follows the DOCK so the menu stays integrated when the dock is
        // customized: tile squares, icons, text, and padding all scale from the
        // dock's icon/label sizes × overall scale. The dock's published rect is
        // used ONLY to anchor (right edge + gap), not to size the panel.
        val scale = dockConfig.scalePct.coerceIn(50, 160) / 100f
        val effIcon = dockConfig.iconSizeDp * scale
        val baseLabel = (if (dockConfig.labelSizeSp > 0) dockConfig.labelSizeSp else 13) * scale
        val tileWidthDp = (effIcon * 1.7f).dp
        val tileIconDp = (effIcon * 0.5f).dp
        val tilePadH = (effIcon * 0.16f).dp
        val tilePadV = (effIcon * 0.20f).dp
        val tileGap = (effIcon * 0.16f).dp
        val panelPad = (effIcon * 0.18f).dp
        val titleSp = baseLabel.sp
        val statusSp = (baseLabel * 0.82f).sp

        val dockBounds by app.dockBandBounds.collectAsState()
        val metrics = remember {
            android.util.DisplayMetrics().also { display.getRealMetrics(it) }
        }
        val density = LocalDensity.current
        val accent = com.portalpad.app.ui.theme.AbPrimaryBright

        // Compact menu, RIGHT-aligned just above the dock so it reads as integrated
        // at the dock's bottom-right. The gap between the menu's bottom and the
        // dock's top is a small fixed value so the menu sits snug above the dock
        // regardless of how far the dock floats off the screen bottom. Anchor from
        // the published rect: right edge → right alignment, top edge → the gap.
        Box(
            Modifier
                .fillMaxSize()
                .clickable { dismiss() },
            contentAlignment = Alignment.BottomEnd,
        ) {
            val b = dockBounds
            val anchorMod = if (b != null && b.size == 4) {
                val dockRight = b[2].toFloat()
                val dockTop = b[1].toFloat()
                // Small fixed gap above the dock (snug). Tunable.
                val menuGapPx = with(density) { 8.dp.toPx() }
                Modifier.padding(
                    end = with(density) { (metrics.widthPixels - dockRight).toDp() },
                    bottom = with(density) { (metrics.heightPixels - dockTop + menuGapPx).toDp() },
                )
            } else {
                // Fallback if the dock hasn't reported bounds yet.
                Modifier.padding(end = 16.dp, bottom = 72.dp)
            }
            Column(
                anchorMod
                    .clip(RoundedCornerShape(panelRadius))
                    .background(brush)
                    // Consume taps inside the panel so they don't hit the backdrop.
                    .clickable(enabled = false) {}
                    .padding(panelPad),
                verticalArrangement = Arrangement.spacedBy(tileGap),
                horizontalAlignment = Alignment.End,
            ) {
                // Row 1 — Wi-Fi | Mobile data | Bluetooth
                Row(horizontalArrangement = Arrangement.spacedBy(tileGap)) {
                    QuickTile(
                        tileWidth = tileWidthDp, iconSize = tileIconDp, titleSize = titleSp,
                        statusSize = statusSp, padH = tilePadH, padV = tilePadV,
                        icon = if (wifiConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                        title = "Wi-Fi", status = if (wifiConnected) (wifiSsid ?: "Connected") else "Off",
                        on = wifiConnected, accent = accent, strong = strong, muted = muted,
                        onClick = { launchSettingOnPhone(android.provider.Settings.ACTION_WIFI_SETTINGS) },
                    )
                    QuickTile(
                        tileWidth = tileWidthDp, iconSize = tileIconDp, titleSize = titleSp,
                        statusSize = statusSp, padH = tilePadH, padV = tilePadV,
                        icon = Icons.Default.SignalCellularAlt,
                        title = "Mobile data",
                        status = if (!hasCellular) "Unavailable"
                            else if (!cellularActive) "Off"
                            // Data rides Wi-Fi when it's connected, so the network type is
                            // hidden then (mirrors the dock glyph's showType = !wifiConnected)
                            // — show a plain "On" instead of a possibly-stale 4G/5G label.
                            else if (wifiConnected) "On"
                            else (cellType ?: carrierName ?: "Connected"),
                        on = cellularActive, accent = accent, strong = strong, muted = muted,
                        onClick = { launchSettingOnPhone(android.provider.Settings.ACTION_NETWORK_OPERATOR_SETTINGS) },
                    )
                    QuickTile(
                        tileWidth = tileWidthDp, iconSize = tileIconDp, titleSize = titleSp,
                        statusSize = statusSp, padH = tilePadH, padV = tilePadV,
                        icon = if (btOn) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                        title = if (btOn && btCount > 0) "Bluetooth ($btCount)" else "Bluetooth",
                        status = if (btOn) "On" else "Off",
                        on = btOn, accent = accent, strong = strong, muted = muted,
                        onClick = { launchSettingOnPhone(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS) },
                    )
                }
                // Row 2 — VPN | Mobile Hotspot | Airplane mode
                Row(horizontalArrangement = Arrangement.spacedBy(tileGap)) {
                    QuickTile(
                        tileWidth = tileWidthDp, iconSize = tileIconDp, titleSize = titleSp,
                        statusSize = statusSp, padH = tilePadH, padV = tilePadV,
                        icon = Icons.Default.VpnKey, title = "VPN",
                        status = if (vpnActive) "Connected" else "Off",
                        on = vpnActive, accent = accent, strong = strong, muted = muted,
                        onClick = { launchSettingOnPhone(android.provider.Settings.ACTION_VPN_SETTINGS) },
                    )
                    QuickTile(
                        tileWidth = tileWidthDp, iconSize = tileIconDp, titleSize = titleSp,
                        statusSize = statusSp, padH = tilePadH, padV = tilePadV,
                        icon = Icons.Default.WifiTethering, title = "Hotspot",
                        status = if (hotspotOn) "On" else "Off",
                        on = hotspotOn, accent = accent, strong = strong, muted = muted,
                        onClick = { launchSettingOnPhone("android.settings.TETHER_SETTINGS") },
                    )
                    QuickTile(
                        tileWidth = tileWidthDp, iconSize = tileIconDp, titleSize = titleSp,
                        statusSize = statusSp, padH = tilePadH, padV = tilePadV,
                        icon = Icons.Default.AirplanemodeActive, title = "Airplane",
                        status = if (airplaneOn) "On" else "Off",
                        on = airplaneOn, accent = accent, strong = strong, muted = muted,
                        // Tap toggles airplane mode in place (no dismiss) — the
                        // AIRPLANE_MODE broadcast flips the status here. Needs the
                        // elevated shell; without it, fall back to opening settings.
                        onClick = {
                            if (app.access.isReady) toggleAirplane(airplaneOn)
                            else launchSettingOnPhone(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                        },
                    )
                }
            }
        }
    }

    /** Toggle airplane mode via the elevated shell — no app API exists for this
     *  since Android 4.2. The AIRPLANE_MODE broadcast we listen for updates the
     *  tile afterward, so we do NOT dismiss; the user sees it flip in place. Runs
     *  off the main thread (execShell blocks). The `cmd connectivity` form is
     *  current; if an OEM/version rejects it, this is where to add a fallback. */
    private fun toggleAirplane(currentlyOn: Boolean) {
        val app = com.portalpad.app.PortalPadApp.instance
        Thread {
            runCatching {
                app.access.execShell(
                    "cmd connectivity airplane-mode " + if (currentlyOn) "disable" else "enable",
                )
            }
        }.start()
    }

    /** A single quick-settings tile: icon on top, title, status line beneath.
     *  Sizes are passed in (scaled from the dock config) so the menu tracks dock
     *  customization. ON shows the accent color; OFF is muted. */
    @Composable
    private fun QuickTile(
        tileWidth: androidx.compose.ui.unit.Dp,
        iconSize: androidx.compose.ui.unit.Dp,
        titleSize: androidx.compose.ui.unit.TextUnit,
        statusSize: androidx.compose.ui.unit.TextUnit,
        padH: androidx.compose.ui.unit.Dp,
        padV: androidx.compose.ui.unit.Dp,
        icon: ImageVector,
        title: String,
        status: String,
        on: Boolean,
        accent: Color,
        strong: Color,
        muted: Color,
        onClick: () -> Unit,
    ) {
        val bg = if (on) accent.copy(alpha = 0.22f) else strong.copy(alpha = 0.10f)
        Column(
            Modifier
                .width(tileWidth)
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .clickable { onClick() }
                .padding(horizontal = padH, vertical = padV),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = title,
                tint = if (on) accent else muted,
                modifier = Modifier.size(iconSize),
            )
            Spacer(Modifier.height(padV * 0.5f))
            Text(
                title,
                color = strong,
                fontSize = titleSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                status,
                color = if (on) accent else muted,
                fontSize = statusSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    companion object {
        private const val TAG = "QuickSettings"
    }
}
