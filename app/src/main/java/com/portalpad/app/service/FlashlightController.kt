package com.portalpad.app.service

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Flashlight (torch) controller. Toggles the LED via Camera2 setTorchMode AND
 * listens for external state changes (Quick Settings, other apps) via a
 * registered TorchCallback — so the UI stays in sync regardless of how the
 * torch was turned on or off.
 *
 * Why register a TorchCallback? Without it, the UI only knows about state
 * changes WE initiate. If the user pulls down Quick Settings and toggles
 * the flashlight tile, our [isOn] would be stale until the next call. The
 * callback fires on every state transition system-wide, keeping us live.
 *
 * Requires CAMERA permission at runtime (caller's responsibility). If
 * permission is missing, setTorchMode throws SecurityException which we
 * catch and report not-on.
 */
class FlashlightController(context: Context) {

    private val cameraManager: CameraManager =
        context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** Resolved at construction. Null if no flash-capable camera found. */
    private val flashCameraId: String? = run {
        try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to enumerate cameras", t)
            null
        }
    }

    /** True if this device has a controllable flash unit. */
    val available: Boolean get() = flashCameraId != null

    private val _isOn = MutableStateFlow(false)
    /**
     * Live torch state. Updates from BOTH our own calls AND external state
     * changes (Quick Settings, other apps) via the registered TorchCallback.
     */
    val isOnFlow: StateFlow<Boolean> = _isOn.asStateFlow()

    /** Snapshot accessor for non-Compose callers. */
    val isOn: Boolean get() = _isOn.value

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            // We only care about OUR flash camera. Some devices report on
            // multiple camera IDs (front-facing screen flash, etc.) and we
            // want to ignore those — only the rear flash matters here.
            if (cameraId == flashCameraId) _isOn.value = enabled
        }
        override fun onTorchModeUnavailable(cameraId: String) {
            // Torch became unavailable (e.g., camera grabbed by another
            // app). Treat as off so our UI doesn't lie.
            if (cameraId == flashCameraId) _isOn.value = false
        }
    }

    init {
        if (flashCameraId != null) {
            // Register on the main looper so callbacks marshal to a known
            // thread. The Camera2 implementation already does this safely,
            // but being explicit keeps any future StateFlow consumers happy.
            try {
                cameraManager.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
            } catch (t: Throwable) {
                Log.w(TAG, "Could not register TorchCallback", t)
            }
        }
    }

    /** Free the callback. Safe to call multiple times; idempotent. */
    fun close() {
        try { cameraManager.unregisterTorchCallback(torchCallback) }
        catch (_: Throwable) { /* idempotent */ }
    }

    /**
     * Turn flashlight on or off. No-op if no flash-capable camera. Returns
     * true if the call succeeded; the actual state-of-record update lands
     * via the TorchCallback (so isOnFlow is always source-of-truth).
     */
    fun setOn(on: Boolean): Boolean {
        val id = flashCameraId ?: return false
        return try {
            cameraManager.setTorchMode(id, on)
            true
        } catch (sec: SecurityException) {
            Log.w(TAG, "setTorchMode SecurityException (CAMERA permission missing?)", sec)
            false
        } catch (t: Throwable) {
            Log.w(TAG, "setTorchMode failed", t)
            false
        }
    }

    /** Toggle the flashlight. Returns the requested state (callback may update later). */
    fun toggle(): Boolean {
        val newState = !_isOn.value
        setOn(newState)
        return newState
    }

    companion object { private const val TAG = "FlashlightController" }
}
