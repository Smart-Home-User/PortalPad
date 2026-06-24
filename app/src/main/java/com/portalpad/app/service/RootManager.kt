package com.portalpad.app.service

import android.content.Context
import android.util.Log
import java.io.IOException

/**
 * Elevated access via `su`. Requires a rooted device with a root-management app
 * (Magisk, KernelSU, etc.) that prompts the user to grant root to PortalPad on
 * first invocation.
 *
 * Compared to Shizuku:
 *  - Pros: doesn't require the user to start a daemon over ADB; works after reboot
 *  - Cons: only available on rooted devices; each shell call spawns a `su` process
 */
class RootManager(private val context: Context) : ElevatedAccess {

    override val mode = ElevatedAccess.Mode.ROOT

    /** Cached after first probe. Call [refresh] to re-probe (e.g. after user grants su). */
    @Volatile private var rootAvailable: Boolean = false
    @Volatile private var probed: Boolean = false

    private fun probe(): Boolean = try {
        val process = ProcessBuilder("su", "-c", "id")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor() == 0
        finished && output.contains("uid=0")
    } catch (_: IOException) {
        false
    } catch (t: Throwable) {
        Log.w(TAG, "root probe failed", t); false
    }

    /** Re-probe `su -c id`. Useful after user grants root externally. */
    fun refresh() {
        rootAvailable = probe()
        probed = true
        Log.d(TAG, "refresh: rootAvailable=$rootAvailable")
    }

    /** Set to true once we've successfully granted INJECT_EVENTS to ourselves via su. */
    @Volatile private var injectGranted = false

    override val isReady: Boolean
        get() {
            if (!probed) refresh()
            return rootAvailable
        }

    override val canInjectInProcess: Boolean get() = injectGranted

    /**
     * Grant ourselves INJECT_EVENTS so in-process reflection-based input injection
     * works (matching the Shizuku path). Without this, we fall back to the `input`
     * shell command per event — slower but functional.
     */
    fun grantInjectEvents() {
        if (!isReady || injectGranted) return
        try {
            execShell("pm grant ${context.packageName} android.permission.INJECT_EVENTS")
            injectGranted = true
        } catch (t: Throwable) {
            Log.w(TAG, "pm grant INJECT_EVENTS failed", t)
        }
    }

    override fun execShell(command: String): String {
        if (!isReady) throw IllegalStateException("Root not available")
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    override fun startActivityOnDisplay(componentName: String, displayId: Int): Boolean {
        return try {
            val output = execShell(
                "am start --display $displayId " +
                "-a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER " +
                "-n '$componentName'"
            )
            Log.d(TAG, "am start --display $displayId -n $componentName → $output")
            !output.contains("Error:", ignoreCase = true)
        } catch (t: Throwable) {
            Log.e(TAG, "startActivityOnDisplay failed", t)
            false
        }
    }

    override fun resizeTask(taskId: Int, left: Int, top: Int, right: Int, bottom: Int) {
        execShell("am task resize $taskId $left $top $right $bottom")
    }

    companion object { private const val TAG = "RootManager" }
}
