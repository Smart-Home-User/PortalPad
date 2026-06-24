package com.portalpad.app.service

/**
 * Common interface for elevated-access backends. The app currently has two:
 *  - [com.portalpad.app.shizuku.ShizukuManager] — uses Shizuku's ADB-launched daemon
 *  - [RootManager] — uses `su` if the device is rooted
 *
 * Both expose the same shell-execution + activity-launch surface.
 */
interface ElevatedAccess {
    enum class Mode { SHIZUKU, ROOT }

    val mode: Mode

    /** True if this backend can actually run elevated commands right now. */
    val isReady: Boolean

    /**
     * True if this backend grants the calling app INJECT_EVENTS, so in-process
     * `InputManager.injectInputEvent` reflection works. Shizuku → true. Root → true
     * if we ran `pm grant INJECT_EVENTS` on startup; otherwise false (we fall back
     * to `input` shell commands).
     */
    val canInjectInProcess: Boolean

    /** Runs a shell command. Returns combined stdout/stderr. */
    fun execShell(command: String): String

    /** Launches an activity on a specific display via `am start --display`. */
    fun startActivityOnDisplay(componentName: String, displayId: Int): Boolean

    /** Resizes a task — used for multi-window. */
    fun resizeTask(taskId: Int, left: Int, top: Int, right: Int, bottom: Int)
}
