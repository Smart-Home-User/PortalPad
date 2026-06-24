package com.portalpad.app.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.portalpad.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Binds [ShellUserService] inside Shizuku's process (uid 2000 = shell) and
 * provides Kotlin-friendly wrappers for the AIDL methods.
 *
 * Follows the reference implementation's pattern: rebind after binder-received, use a version
 * derived from the app's lastUpdateTime so reinstalls re-bind cleanly.
 *
 * Exposes [readyFlow] as a [StateFlow] so Compose UIs recompose as soon as the
 * bind completes — without this, [isReady] is a plain property and the UI only
 * reflects the bound state when the user reopens the app.
 */
class ShizukuClickBackend(private val context: Context) : BoundShellBackend {

    @Volatile private var service: IShellService? = null

    private val _readyFlow = MutableStateFlow(false)
    /** Hot, reactive form of [isReady]. Compose can `collectAsState()` this. */
    override val readyFlow: StateFlow<Boolean> = _readyFlow.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            service = IShellService.Stub.asInterface(binder)
            val pong = runCatching { service?.ping("hello") }.getOrNull()
            Log.d(TAG, "User service connected: $pong")
            _readyFlow.value = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "User service disconnected"); service = null
            _readyFlow.value = false
        }
    }

    override val isReady: Boolean
        get() = service != null && runCatching {
            service?.asBinder()?.pingBinder() == true
        }.getOrDefault(false)

    private fun userServiceVersion(): Int = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        ((pi.lastUpdateTime and 0x7FFFFFFFL).toInt()) + 93
    } catch (_: Throwable) { 100 }

    override fun bind() {
        if (isReady) return
        if (Shizuku.getVersion() < 10) return
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) return

        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("portalpad_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(userServiceVersion())
        try { Shizuku.bindUserService(args, connection) }
        catch (t: Throwable) { Log.e(TAG, "bindUserService failed", t) }
    }

    override fun unbind() {
        runCatching {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShellUserService::class.java.name),
            )
            Shizuku.unbindUserService(args, connection, true)
        }
        service = null
    }

    // ─── click API ──────────────────────────────────────────────────

    override fun tap(displayId: Int, x: Float, y: Float): Boolean = svcCall { it.injectTap(displayId, x, y); true }
    override fun longPress(displayId: Int, x: Float, y: Float, durationMs: Long): Boolean =
        svcCall { it.injectLongPress(displayId, x, y, durationMs); true }
    override fun swipe(displayId: Int, sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long): Boolean =
        svcCall { it.injectSwipe(displayId, sx, sy, ex, ey, durationMs); true }
    override fun key(displayId: Int, keyCode: Int): Boolean = svcCall {
        // Single server-side press (DOWN + gap + UP). Replaces the old
        // back-to-back DOWN/UP which dropped keys intermittently.
        it.injectKeyPress(keyCode, displayId)
        true
    }
    /** Press a key tagged as gamepad input (SOURCE_GAMEPAD | SOURCE_JOYSTICK). */
    override fun gamepadKey(displayId: Int, keyCode: Int): Boolean = svcCall {
        it.injectGamepadKeyPress(keyCode, displayId)
        true
    }
    override fun pointer(action: Int, x: Float, y: Float, displayId: Int, source: Int, buttonState: Int, downTime: Long): Boolean =
        svcCall { it.injectPointer(action, x, y, displayId, source, buttonState, downTime); true }
    override fun setImePolicy(displayId: Int, policy: Int): Boolean = svcCall { it.setDisplayImePolicy(displayId, policy) }
    override fun showImeOnFocusedEditor(): Boolean = svcCall { it.showImeOnFocusedEditor() }
    override fun setShouldShowSystemDecors(displayId: Int, enabled: Boolean): Boolean =
        svcCall { it.setShouldShowSystemDecors(displayId, enabled) }

    // ─── virtual display + mirror ──────────────────────────────────

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

    override fun moveFocusedTaskToDisplay(displayId: Int): Boolean = svcCall { it.moveFocusedTaskToDisplay(displayId) }
    override fun refocusTopTaskOnDisplay(displayId: Int): Boolean = svcCall { it.refocusTopTaskOnDisplay(displayId) }
    override fun getFocusedTaskDisplayId(): Int = svcCall(-1) { it.getFocusedTaskDisplayId() }
    override fun runCommand(cmd: String): String = svcCall("") { it.runCommand(cmd) }
    /** Launch an activity (component or package name) on the given display.
     *  Returns command output for diagnostic display; empty if the call failed. */
    override fun amStart(component: String, displayId: Int): String =
        svcCall("") { it.amStart(component, displayId) }
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

    /** Start streaming filtered logcat lines via the user service. Pass
     *  null for the default safe PortalPad-only filter. */
    override fun streamLogcat(filter: String?): android.os.ParcelFileDescriptor? =
        svcCall<android.os.ParcelFileDescriptor?>(null) { it.streamLogcat(filter) }
    override fun stopLogcatStream() { svcCall { it.stopLogcatStream(); true } }

    // ─── helpers ────────────────────────────────────────────────────

    private inline fun <T> svcCall(default: T, block: (IShellService) -> T): T {
        val s = service ?: return default
        return try { block(s) } catch (t: Throwable) {
            Log.w(TAG, "svcCall threw", t); default
        }
    }
    private inline fun svcCall(block: (IShellService) -> Boolean): Boolean = svcCall(false, block)

    companion object {
        private const val TAG = "ShizukuClickBackend"
    }
}
