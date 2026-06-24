package com.portalpad.app.service

import android.hardware.display.IVirtualDisplayCallback
import android.os.IBinder
import android.util.Log
import android.view.Surface
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * EXPERIMENTAL: a TRUSTED VirtualDisplay that is owned by PortalPad's OWN
 * process, so it survives Shizuku being stopped.
 *
 * The trick — the "right code" we were missing — is the Shizuku usage mode:
 *
 *   - PortalPad's normal path binds a Shizuku UserService (a shell-uid
 *     process) and creates the VD *inside that process*. The display's owning
 *     process is the shell process, so killing Shizuku reaps the display.
 *
 *   - Here we instead wrap the display service's binder with
 *     [ShizukuBinderWrapper] and call IDisplayManager.createVirtualDisplay
 *     directly from THIS process. The transaction executes under Shizuku's
 *     shell identity, so the ADD_TRUSTED_DISPLAY check for the TRUSTED flag
 *     passes — but the [IVirtualDisplayCallback] token we pass in is hosted
 *     here, in PortalPad's process. DisplayManagerService ties the display's
 *     lifetime to that callback binder, so the display stays alive as long as
 *     THIS process does, regardless of Shizuku. Shizuku is only needed at
 *     creation (and for surface/resize/release reconfig, which only happen
 *     while it's running anyway).
 *
 * All of this is hidden-API reflection (IDisplayManager + VirtualDisplayConfig)
 * whose exact signatures vary by Android version. Every call logs what it found
 * so a device run can pin down the real One UI 16 shapes if anything is off.
 */
class AppOwnedTrustedDisplay {

    /** Wrapped IDisplayManager interface (calls run with shell identity). */
    private var idm: Any? = null

    /** Ownership token — hosted in THIS process. Keeping a strong ref alive is
     *  what keeps the display alive after Shizuku is gone. */
    private var callback: IVirtualDisplayCallback? = null

    var displayId: Int = -1
        private set

    /**
     * Create the trusted, app-owned VirtualDisplay. [flags] should be the same
     * trusted flag set the shell path uses. Returns the new display id, or -1.
     */
    fun create(name: String, w: Int, h: Int, dpi: Int, surface: Surface, flags: Int): Int {
        return try {
            if (!shizukuUsable()) {
                Log.e(TAG, "create: Shizuku not usable (binder/permission); aborting")
                return -1
            }

            val rawBinder = SystemServiceHelper.getSystemService("display")
                ?: run { Log.e(TAG, "create: no display service binder"); return -1 }
            val wrapped: IBinder = ShizukuBinderWrapper(rawBinder)
            val stubCls = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            val idmObj = stubCls.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
                ?: run { Log.e(TAG, "create: IDisplayManager.asInterface returned null"); return -1 }
            idm = idmObj

            val config = buildConfig(name, w, h, dpi, surface, flags)
                ?: run { Log.e(TAG, "create: VirtualDisplayConfig build failed"); return -1 }

            // Ownership token, hosted HERE.
            val cb = object : IVirtualDisplayCallback.Stub() {
                override fun onPaused() {}
                override fun onResumed() {}
                override fun onStopped() { Log.d(TAG, "VD callback onStopped (id=$displayId)") }
            }
            callback = cb

            // Long-stable signature: createVirtualDisplay(config, callback,
            // projectionToken, packageName). Log alternatives if not found so a
            // device run reveals the One UI 16 shape.
            val createM = idmObj.javaClass.methods.firstOrNull {
                it.name == "createVirtualDisplay" && it.parameterTypes.size == 4
            } ?: run {
                Log.e(TAG, "create: no 4-arg createVirtualDisplay. Found: " + signaturesOf(idmObj, "createVirtualDisplay"))
                return -1
            }
            // packageName must belong to the calling (shell) uid → com.android.shell.
            val id = createM.invoke(idmObj, config, cb, null, "com.android.shell") as? Int ?: -1
            displayId = id
            Log.d(TAG, "app-owned TRUSTED VD created id=$id flags=0x${flags.toString(16)} (via Shizuku wrapper)")
            id
        } catch (t: Throwable) {
            Log.e(TAG, "create failed", t)
            release()
            -1
        }
    }

    /** Redirect the display's output to [surface] (e.g. the glasses mirror). */
    fun setSurface(surface: Surface?): Boolean = invokeOnIdm(
        "setVirtualDisplaySurface", 2, arrayOf(callback, surface),
    )

    fun resize(w: Int, h: Int, dpi: Int): Boolean = invokeOnIdm(
        "resizeVirtualDisplay", 4, arrayOf(callback, w, h, dpi),
    )

    fun release() {
        runCatching { invokeOnIdm("releaseVirtualDisplay", 1, arrayOf(callback)) }
        idm = null
        callback = null
        displayId = -1
    }

    // ─── internals ──────────────────────────────────────────────────────

    private fun buildConfig(name: String, w: Int, h: Int, dpi: Int, surface: Surface, flags: Int): Any? = try {
        val builderCls = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder = builderCls.getConstructor(
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).newInstance(name, w, h, dpi)
        builderCls.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
        builderCls.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
        builderCls.getMethod("build").invoke(builder)
    } catch (t: Throwable) {
        Log.e(TAG, "buildConfig failed", t); null
    }

    private fun invokeOnIdm(method: String, paramCount: Int, args: Array<Any?>): Boolean {
        val idmObj = idm ?: return false
        if (callback == null) return false
        return try {
            val m = idmObj.javaClass.methods.firstOrNull {
                it.name == method && it.parameterTypes.size == paramCount
            } ?: run {
                Log.w(TAG, "$method: no $paramCount-arg overload. Found: " + signaturesOf(idmObj, method))
                return false
            }
            m.invoke(idmObj, *args)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "$method failed", t); false
        }
    }

    private fun signaturesOf(obj: Any, name: String): String =
        obj.javaClass.methods.filter { it.name == name }.joinToString(" | ") { m ->
            m.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }
        }

    private fun shizukuUsable(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    companion object {
        private const val TAG = "AppOwnedTrustedVD"
    }
}
