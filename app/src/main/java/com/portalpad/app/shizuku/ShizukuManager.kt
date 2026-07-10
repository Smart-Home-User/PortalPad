package com.portalpad.app.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.portalpad.app.service.ElevatedAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

/**
 * Shizuku-based elevated access. Implements [ElevatedAccess] so the rest of the
 * app can switch between Shizuku and Root via a single interface.
 *
 * Shizuku gives non-root apps a way to call into a privileged ADB-launched daemon
 * (or root daemon) over Binder. That lets us inject input, start activities on
 * arbitrary displays, and resize tasks for multi-window.
 */
class ShizukuManager(private val context: Context) : ElevatedAccess {

    override val mode = ElevatedAccess.Mode.SHIZUKU

    enum class Status { UNKNOWN, NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY }

    private val _status = MutableStateFlow(Status.UNKNOWN)
    val status: StateFlow<Status> = _status

    private val permListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == PERM_REQUEST_CODE) refresh()
    }
    private val binderListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refresh() }

    fun init() {
        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderReceivedListener(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        refresh()
    }

    fun refresh() {
        _status.value = computeStatus()
    }

    /** Compute and return the current status synchronously (also updates the
     *  flow). Use when a caller needs an up-to-date status right now rather
     *  than waiting for the collected StateFlow to recompose — e.g. deciding
     *  which help dialog to show the instant the user selects Shizuku. */
    fun freshStatus(): Status {
        val s = computeStatus()
        _status.value = s
        return s
    }

    private fun computeStatus(): Status {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            if (!Shizuku.pingBinder()) return Status.NOT_RUNNING
            if (Shizuku.isPreV11()) return Status.NEEDS_PERMISSION
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) Status.READY
            else Status.NEEDS_PERMISSION
        } catch (_: PackageManager.NameNotFoundException) {
            Status.NOT_INSTALLED
        } catch (t: Throwable) {
            Log.w(TAG, "status check failed", t)
            Status.UNKNOWN
        }
    }

    fun requestPermission() {
        if (Shizuku.shouldShowRequestPermissionRationale()) return
        Shizuku.requestPermission(PERM_REQUEST_CODE)
    }

    override val isReady: Boolean get() = _status.value == Status.READY

    /** Shizuku grants INJECT_EVENTS to the calling app, so in-process reflection works. */
    override val canInjectInProcess: Boolean get() = isReady

    override fun execShell(command: String): String {
        if (!isReady) throw IllegalStateException("Shizuku not ready")
        val process = newProcessReflect(arrayOf("sh", "-c", command), null, null)
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        process.waitFor()
        return if (err.isBlank()) out else "$out\n$err"
    }

    /**
     * `Shizuku.newProcess` is marked @RestrictTo in API 13.x, so we call it via
     * reflection. The method still exists at runtime; only compile-time access
     * is gated. Long-term proper solution: bind a UserService.
     */
    private fun newProcessReflect(cmd: Array<String>, env: Array<String>?, dir: String?): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, cmd, env, dir) as Process
    }

    override fun startActivityOnDisplay(componentName: String, displayId: Int): Boolean {
        return try {
            // Include action+category so apps that gate their MAIN activity on
            // them (most do) actually accept the launch. Otherwise `am start -n`
            // alone fails silently with some apps.
            val output = execShell(
                "am start --display $displayId " +
                "-a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER " +
                "-n '$componentName'"
            )
            Log.d(TAG, "am start --display $displayId -n $componentName → $output")
            // `am start` prints "Error:" on failure; treat presence of that as failure
            !output.contains("Error:", ignoreCase = true)
        } catch (t: Throwable) {
            Log.e(TAG, "startActivityOnDisplay failed", t)
            false
        }
    }

    override fun resizeTask(taskId: Int, left: Int, top: Int, right: Int, bottom: Int) {
        execShell("am task resize $taskId $left $top $right $bottom")
    }

    companion object {
        private const val TAG = "ShizukuManager"
        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        const val PERM_REQUEST_CODE = 9981
    }
}
