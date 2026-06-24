package com.portalpad.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Single source of truth for the connectivity / signal state shown in the status
 * cluster (the dock's top-right icons AND the quick-settings panel). Registers ONE
 * connectivity callback and ONE telephony callback for the whole app, so the two
 * render sites read the same StateFlows instead of each spinning up their own
 * listeners.
 *
 * Levels are 0..4; -1 means "unknown / not applicable".
 *  - Wi-Fi level comes from NetworkCapabilities.getSignalStrength() (permission-free).
 *  - Cellular level comes from SignalStrength.getLevel() (permission-free).
 *  - cellType (5G/LTE/…) needs READ_PHONE_STATE — it stays null until that's granted.
 *
 * Owned by PortalPadForegroundService: start() on service create, stop() on destroy.
 */
class SignalMonitor(private val context: Context) {

    private val _wifiConnected = MutableStateFlow(false)
    val wifiConnected: StateFlow<Boolean> = _wifiConnected.asStateFlow()
    private val _cellularActive = MutableStateFlow(false)
    val cellularActive: StateFlow<Boolean> = _cellularActive.asStateFlow()
    private val _vpnActive = MutableStateFlow(false)
    val vpnActive: StateFlow<Boolean> = _vpnActive.asStateFlow()

    private val _wifiLevel = MutableStateFlow(-1)   // 0..4, -1 = unknown
    val wifiLevel: StateFlow<Int> = _wifiLevel.asStateFlow()
    private val _cellLevel = MutableStateFlow(-1)   // 0..4, -1 = unknown
    val cellLevel: StateFlow<Int> = _cellLevel.asStateFlow()
    private val _cellType = MutableStateFlow<String?>(null)  // "5G"/"LTE"/… or null
    val cellType: StateFlow<String?> = _cellType.asStateFlow()
    // Latest TelephonyDisplayInfo override — how the carrier presents the connection
    // (e.g. NR_NSA = 5G riding an LTE anchor). dataNetworkType reports LTE for 5G NSA,
    // so this override is what makes the label read "5G" the way the system bar does.
    @Volatile private var overrideNetworkType: Int = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE

    private val _btConnectedCount = MutableStateFlow(0)      // distinct connected BT devices
    val btConnectedCount: StateFlow<Int> = _btConnectedCount.asStateFlow()
    private val _wifiSsid = MutableStateFlow<String?>(null)  // connected SSID or null
    val wifiSsid: StateFlow<String?> = _wifiSsid.asStateFlow()

    private val cm get() =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val tm get() =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var telephonyCallback: Any? = null              // TelephonyCallback (API 31+)
    @Suppress("DEPRECATION")
    private var phoneListener: android.telephony.PhoneStateListener? = null
    private val btProxies = java.util.concurrent.ConcurrentHashMap<Int, android.bluetooth.BluetoothProfile>()
    private var btReceiver: android.content.BroadcastReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ssidJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        registerConnectivity()
        registerTelephony()
        registerBluetooth()
        refreshConnectivity()
        refreshCellType()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { netCallback?.let { cm?.unregisterNetworkCallback(it) } }
        netCallback = null
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                (telephonyCallback as? TelephonyCallback)?.let { tm?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                phoneListener?.let { tm?.listen(it, android.telephony.PhoneStateListener.LISTEN_NONE) }
            }
        }
        telephonyCallback = null
        phoneListener = null
        unregisterBluetooth()
        ssidJob?.cancel()
        _wifiLevel.value = -1; _cellLevel.value = -1; _cellType.value = null; _wifiSsid.value = null
        overrideNetworkType = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
    }

    // ── Connectivity: wifi/cell/vpn presence + wifi level ──
    private fun hasTransport(transport: Int): Boolean = runCatching {
        cm?.allNetworks?.any { n -> cm?.getNetworkCapabilities(n)?.hasTransport(transport) == true } == true
    }.getOrDefault(false)

    private fun refreshConnectivity() {
        val c = cm ?: return
        _wifiConnected.value = runCatching {
            val net = c.activeNetwork ?: return@runCatching false
            c.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)
        _vpnActive.value = hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        _cellularActive.value = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        _wifiLevel.value = if (_wifiConnected.value) {
            runCatching {
                val net = c.activeNetwork
                val caps = net?.let { c.getNetworkCapabilities(it) }
                wifiDbmToLevel(caps?.signalStrength)
            }.getOrDefault(-1)
        } else {
            -1
        }
        refreshWifiSsid()
    }

    /** Connected SSID. App-context WifiManager redacts the SSID to "&lt;unknown ssid&gt;"
     *  for non-foreground reads (we read from a service), so we read it through the
     *  elevated shell (the shell uid gets it unredacted). No app-held location
     *  permission is used; if the shell isn't available the SSID stays null and the
     *  tile shows "Connected". */
    private fun refreshWifiSsid() {
        ssidJob?.cancel()
        if (!_wifiConnected.value) { _wifiSsid.value = null; return }
        val app = context.applicationContext as? com.portalpad.app.PortalPadApp
        ssidJob = scope.launch {
            // Elevated shell only: the shell uid reads the SSID UNREDACTED whenever
            // Shizuku/Root is active. No location permission required.
            _wifiSsid.value = runCatching {
                if (app != null && app.access.isReady) parseSsid(app.access.execShell("cmd wifi status")) else null
            }.getOrNull()
        }
    }

    /** Pull the SSID out of `cmd wifi status` / dumpsys output. */
    private fun parseSsid(out: String?): String? {
        if (out.isNullOrBlank()) return null
        val m = Regex("SSID:\\s*\"([^\"]+)\"").find(out)
            ?: Regex("connected to \"([^\"]+)\"").find(out)
        return m?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && !it.startsWith("0x") }
    }

    private fun registerConnectivity() {
        val c = cm ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) = refreshConnectivity()
            override fun onLost(n: Network) = refreshConnectivity()
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) = refreshConnectivity()
        }
        netCallback = cb
        runCatching { c.registerDefaultNetworkCallback(cb) }
    }

    /** NetworkCapabilities.getSignalStrength() is RSSI in dBm (Int.MIN_VALUE = unspecified). */
    private fun wifiDbmToLevel(dbm: Int?): Int {
        if (dbm == null || dbm == Int.MIN_VALUE || dbm >= 0 || dbm < -120) return -1
        return when {
            dbm >= -55 -> 4
            dbm >= -66 -> 3
            dbm >= -77 -> 2
            dbm >= -88 -> 1
            else -> 0
        }
    }

    // ── Telephony: cell level + network type ──
    /** AOSP's SignalStrength.getLevel() (and per-cell getLevel) read conservatively
     *  vs OEM status bars — Samsung shows ~4 bars where AOSP reports 1. So we map the
     *  strongest cell's raw dBm with generous thresholds to better match what the user
     *  sees on their phone. Falls back to getLevel() if no dBm is available. */
    private fun cellLevelFrom(ss: SignalStrength?): Int {
        if (ss == null) return -1
        return runCatching {
            val cells = if (Build.VERSION.SDK_INT >= 30) ss.cellSignalStrengths else emptyList()
            val bestDbm = cells.mapNotNull { c ->
                runCatching { c.dbm }.getOrNull()?.takeIf { it < 0 && it > -200 }
            }.maxOrNull()
            val computed = when {
                bestDbm != null -> dbmToCellLevel(bestDbm)
                cells.isNotEmpty() -> cells.maxOf { it.level }
                else -> ss.level
            }
            // ── Calibration diagnostic (tag: PortalPadSignal) ───────────────
            // Logs the framework's own getLevel() — the value Samsung's status
            // bar draws — next to our computed level and the raw per-cell data,
            // so a dock-vs-status-bar mismatch can be calibrated (or we just
            // switch to the framework level if it matches). Fires only on actual
            // signal changes, so it's not a hot path.
            runCatching {
                val perCell = cells.joinToString("; ") { c ->
                    val t = c.javaClass.simpleName.removePrefix("CellSignalStrength")
                    "$t dbm=${runCatching { c.dbm }.getOrNull()} " +
                        "asu=${runCatching { c.asuLevel }.getOrNull()} " +
                        "level=${runCatching { c.level }.getOrNull()}"
                }
                android.util.Log.d(
                    "PortalPadSignal",
                    "STATUS-BAR(ss.level)=${ss.level}  ourComputed=$computed  " +
                        "bestDbm=$bestDbm→ladder=${bestDbm?.let { dbmToCellLevel(it) }}  " +
                        "cells=[$perCell]",
                )
            }
            computed
        }.getOrDefault(runCatching { ss.level }.getOrDefault(-1))
    }

    /** Generous dBm→0..4 mapping (RSRP-style) to track OEM bar displays. Samsung
     *  shows ~4 bars where stock AOSP reports fewer, so the mid thresholds are wide.
     *  TUNABLE: nudge the -105 (level-3) boundary if the dock reads one bar off the
     *  phone — that's the band most cell signals sit in. */
    private fun dbmToCellLevel(dbm: Int): Int = when {
        dbm >= -90 -> 4
        dbm >= -105 -> 3
        dbm >= -113 -> 2
        dbm >= -120 -> 1
        else -> 0
    }

    private fun registerTelephony() {
        val t = tm ?: return
        if (!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)) return
        // Seed immediately so the bars aren't "unknown" until the first callback.
        runCatching { _cellLevel.value = cellLevelFrom(t.signalStrength) }
        if (Build.VERSION.SDK_INT >= 31) {
            val cb = object : TelephonyCallback(),
                TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.DisplayInfoListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    _cellLevel.value = cellLevelFrom(signalStrength)
                    refreshCellType()
                }
                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    overrideNetworkType = telephonyDisplayInfo.overrideNetworkType
                    refreshCellType()
                }
            }
            telephonyCallback = cb
            runCatching { t.registerTelephonyCallback(context.mainExecutor, cb) }
        } else {
            @Suppress("DEPRECATION")
            val l = object : android.telephony.PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                    _cellLevel.value = cellLevelFrom(signalStrength)
                    refreshCellType()
                }
                @Deprecated("Deprecated in Java")
                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    overrideNetworkType = telephonyDisplayInfo.overrideNetworkType
                    refreshCellType()
                }
            }
            phoneListener = l
            @Suppress("DEPRECATION")
            runCatching {
                t.listen(
                    l,
                    android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                        android.telephony.PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED,
                )
            }
        }
    }

    /** Network-type label. Only readable with READ_PHONE_STATE; null otherwise. */
    private fun refreshCellType() {
        val granted = context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) { _cellType.value = null; return }
        val t = tm ?: return
        // 5G NSA reports dataNetworkType = LTE, so prefer the display-info override
        // (how the system status bar decides to show "5G"); fall back to the data type.
        val nr = overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
            overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE ||
            overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
        _cellType.value = if (nr) "5G"
            else runCatching { networkTypeLabel(t.dataNetworkType) }.getOrNull()
    }

    private fun networkTypeLabel(nt: Int): String? = when (nt) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> null
        else -> "4G"
    }

    // ── Bluetooth: distinct connected device count (menu only) ──
    private val btAdapter: android.bluetooth.BluetoothAdapter? get() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    private val btProfiles = intArrayOf(
        android.bluetooth.BluetoothProfile.HEADSET,
        android.bluetooth.BluetoothProfile.A2DP,
    )

    private fun registerBluetooth() {
        val adapter = btAdapter ?: return
        val listener = object : android.bluetooth.BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                btProxies[profile] = proxy
                recountBluetooth()
            }
            override fun onServiceDisconnected(profile: Int) {
                btProxies.remove(profile)
                recountBluetooth()
            }
        }
        for (p in btProfiles) runCatching { adapter.getProfileProxy(context, listener, p) }
        // Recount when devices connect/disconnect or the radio toggles (all system
        // broadcasts, so no exported-receiver flag needed — mirrors the dock's BT receiver).
        val filter = android.content.IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: android.content.Intent?) { recountBluetooth() }
        }
        btReceiver = r
        runCatching { context.registerReceiver(r, filter) }
        recountBluetooth()
    }

    /** Distinct connected devices across the audio profiles (one pair of earbuds
     *  often registers under both A2DP and HEADSET — union by address so it counts
     *  once). Needs BLUETOOTH_CONNECT; 0 when not granted or radio off. */
    private fun recountBluetooth() {
        val adapter = btAdapter
        if (adapter == null || !runCatching { adapter.isEnabled }.getOrDefault(false)) {
            _btConnectedCount.value = 0; return
        }
        val granted = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) { _btConnectedCount.value = 0; return }
        val addrs = HashSet<String>()
        for ((_, proxy) in btProxies) {
            runCatching { for (d in proxy.connectedDevices) runCatching { addrs.add(d.address) } }
        }
        _btConnectedCount.value = addrs.size
    }

    private fun unregisterBluetooth() {
        val adapter = btAdapter
        for ((p, proxy) in btProxies) runCatching { adapter?.closeProfileProxy(p, proxy) }
        btProxies.clear()
        runCatching { btReceiver?.let { context.unregisterReceiver(it) } }
        btReceiver = null
        _btConnectedCount.value = 0
    }
}
