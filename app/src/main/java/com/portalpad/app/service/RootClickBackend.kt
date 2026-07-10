package com.portalpad.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Root equivalent of [ShizukuClickBackend]. Binds [RootShellService] via libsu,
 * which launches [ShellUserService] in a root (uid 0) process and hands back
 * its binder. Exposes the same [BoundShellBackend] surface so the rest of the
 * app is source-agnostic.
 *
 * Graceful degradation: if the bound service can't start (SELinux denial, no
 * root grant, etc.), [isReady] stays false and the app falls back to the
 * existing shell-based root path — a rooted device is never worse off than it
 * was before this backend existed.
 */
class RootClickBackend(private val context: Context) : BoundShellBackend {

    @Volatile private var service: IShellService? = null

    private val _readyFlow = MutableStateFlow(false)
    override val readyFlow: StateFlow<Boolean> = _readyFlow.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                Log.w(TAG, "onServiceConnected with dead binder"); return
            }
            service = IShellService.Stub.asInterface(binder)
            val pong = runCatching { service?.ping("hello-root") }.getOrNull()
            Log.d(TAG, "Root user service connected: $pong")
            _readyFlow.value = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Root user service disconnected"); service = null
            _readyFlow.value = false
        }
    }

    override val isReady: Boolean
        get() = service != null && runCatching {
            service?.asBinder()?.pingBinder() == true
        }.getOrDefault(false)

    override fun bind() {
        // libsu's RootService.bind() must be invoked on the main thread. Callers
        // come from both the main thread (Settings) and background coroutines
        // (the app-scope access-mode watcher), so we normalize here.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            bindInternal()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post { bindInternal() }
        }
    }

    private fun bindInternal() {
        if (isReady) return
        // Only attempt if root is actually available — Shell.isAppGrantedRoot()
        // is libsu's check (true once the user grants su to PortalPad).
        val granted = runCatching { Shell.isAppGrantedRoot() }.getOrNull()
        if (granted == false) {
            Log.w(TAG, "bind: root not granted yet"); return
        }
        try {
            val intent = Intent(context, RootShellService::class.java)
            RootService.bind(intent, connection)
            Log.d(TAG, "RootService.bind requested")
        } catch (t: Throwable) {
            Log.e(TAG, "RootService.bind failed", t)
        }
    }

    override fun unbind() {
        val doUnbind = {
            runCatching { RootService.unbind(connection) }
            service = null
            _readyFlow.value = false
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            doUnbind()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post { doUnbind() }
        }
    }

    // ─── input ──────────────────────────────────────────────────────
    override fun tap(displayId: Int, x: Float, y: Float): Boolean = svcCall { it.injectTap(displayId, x, y); true }
    override fun longPress(displayId: Int, x: Float, y: Float, durationMs: Long): Boolean =
        svcCall { it.injectLongPress(displayId, x, y, durationMs); true }
    override fun swipe(displayId: Int, sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long): Boolean =
        svcCall { it.injectSwipe(displayId, sx, sy, ex, ey, durationMs); true }
    override fun key(displayId: Int, keyCode: Int): Boolean = svcCall { it.injectKeyPress(keyCode, displayId); true }
    override fun injectKey(keyCode: Int, action: Int, metaState: Int, displayId: Int): Boolean =
        svcCall { it.injectKey(keyCode, action, metaState, displayId, -1); true }
    override fun gamepadKey(displayId: Int, keyCode: Int): Boolean = svcCall { it.injectGamepadKeyPress(keyCode, displayId); true }
    override fun pointer(action: Int, x: Float, y: Float, displayId: Int, source: Int, buttonState: Int, downTime: Long): Boolean =
        svcCall { it.injectPointer(action, x, y, displayId, source, buttonState, downTime); true }
    override fun setImePolicy(displayId: Int, policy: Int): Boolean = svcCall { it.setDisplayImePolicy(displayId, policy) }
    override fun showImeOnFocusedEditor(): Boolean = svcCall { it.showImeOnFocusedEditor() }
    override fun setShouldShowSystemDecors(displayId: Int, enabled: Boolean): Boolean =
        svcCall { it.setShouldShowSystemDecors(displayId, enabled) }

    // ─── virtual display + mirror ───────────────────────────────────
    override fun createVirtualDisplay(name: String, w: Int, h: Int, dpi: Int, flags: Int, surface: Surface): Int =
        svcCall(-1) { it.createVirtualDisplay(name, w, h, dpi, flags, surface) }
    override fun resizeVirtualDisplay(vdId: Int, w: Int, h: Int, dpi: Int): Boolean =
        svcCall { it.resizeVirtualDisplay(vdId, w, h, dpi) }
    override fun setVirtualDisplaySurface(vdId: Int, surface: Surface): Boolean =
        svcCall { it.setVirtualDisplaySurface(vdId, surface) }
    override fun releaseVirtualDisplay(vdId: Int): Boolean = svcCall { it.releaseVirtualDisplay(vdId) }
    override fun startSurfaceMirror(glassesId: Int, surface: Surface): Boolean =
        svcCall { it.startSurfaceMirror(glassesId, surface) }
    override fun stopSurfaceMirror(glassesId: Int) { svcCall { it.stopSurfaceMirror(glassesId); true } }

    // ─── task / shell ───────────────────────────────────────────────
    override fun moveFocusedTaskToDisplay(displayId: Int): Boolean = svcCall { it.moveFocusedTaskToDisplay(displayId) }
    override fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean = svcCall { it.moveTaskToDisplay(taskId, displayId) }
    override fun refocusTopTaskOnDisplay(displayId: Int): Boolean = svcCall { it.refocusTopTaskOnDisplay(displayId) }
    override fun getFocusedTaskDisplayId(): Int = svcCall(-1) { it.getFocusedTaskDisplayId() }
    override fun runCommand(cmd: String): String = svcCall("") { it.runCommand(cmd) }
    override fun amStart(component: String, displayId: Int): String = svcCall("") { it.amStart(component, displayId) }
    override fun dumpDisplays(): String = svcCall("<not bound>") { it.dumpDisplays() }
    override fun probeOverlayWindow(displayId: Int, windowType: Int, holdMs: Long): String =
        svcCall("<not bound>") { it.probeOverlayWindow(displayId, windowType, holdMs) }
    override fun setDisplayColorTransform(displayId: Int, matrix: FloatArray): String =
        svcCall("<not bound>") { it.setDisplayColorTransform(displayId, matrix) }
    override fun getDisplayColorModes(displayId: Int): String =
        svcCall("<not bound>") { it.getDisplayColorModes(displayId) }
    override fun setDisplayColorMode(displayId: Int, colorMode: Int): String =
        svcCall("<not bound>") { it.setDisplayColorMode(displayId, colorMode) }
    override fun setLayerColorTransform(layer: android.view.SurfaceControl, matrix12: FloatArray): String =
        svcCall("<not bound>") { it.setLayerColorTransform(layer, matrix12) }
    override fun probeMirrorCapability(physicalDisplayHint: Int): String =
        svcCall("<not bound>") { it.probeMirrorCapability(physicalDisplayHint) }
    override fun startLayerStackMirror(glassesDisplayId: Int, vdDisplayId: Int): String =
        svcCall("<not bound>") { it.startLayerStackMirror(glassesDisplayId, vdDisplayId) }
    override fun stopLayerStackMirror(glassesDisplayId: Int): String =
        svcCall("<not bound>") { it.stopLayerStackMirror(glassesDisplayId) }
    override fun sampleDisplayFps(displayId: Int): Float =
        svcCall(-1f) { it.sampleDisplayFps(displayId) }

    // ─── diagnostics ────────────────────────────────────────────────
    override fun streamLogcat(filter: String?): android.os.ParcelFileDescriptor? =
        svcCall<android.os.ParcelFileDescriptor?>(null) { it.streamLogcat(filter) }
    override fun stopLogcatStream() { svcCall { it.stopLogcatStream(); true } }

    override fun startMouseCapture(grab: Boolean, nativeLibDir: String?, writeEnd: android.os.ParcelFileDescriptor): String =
        svcCall("ERR not-bound") { it.startMouseCapture(grab, nativeLibDir, writeEnd) }
    override fun stopMouseCapture() { svcCall { it.stopMouseCapture(); true } }

    override fun injectScroll(x: Float, y: Float, vScroll: Float, hScroll: Float, displayId: Int) {
        svcCall { it.injectScroll(x, y, vScroll, hScroll, displayId); true }
    }

    // ─── helpers ────────────────────────────────────────────────────
    private inline fun <T> svcCall(default: T, block: (IShellService) -> T): T {
        val s = service ?: return default
        return try { block(s) } catch (t: Throwable) {
            Log.w(TAG, "svcCall threw", t); default
        }
    }
    private inline fun svcCall(block: (IShellService) -> Boolean): Boolean = svcCall(false, block)

    companion object {
        private const val TAG = "RootClickBackend"
    }
}
