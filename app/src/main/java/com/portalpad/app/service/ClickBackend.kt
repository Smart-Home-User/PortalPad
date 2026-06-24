package com.portalpad.app.service

/**
 * Represents which path PortalPad uses to deliver clicks to the external display.
 *
 * Two variants:
 *  - [ShizukuUserService]: binds [ShellUserService] via Shizuku and calls
 *    [InputManager.injectInputEvent] from inside the elevated process. Requires
 *    Shizuku running + permission granted.
 *  - [Shell]: runs `input -d N tap X Y` via Shizuku or Root shell. Older,
 *    less-reliable path; kept as a fallback option.
 */
sealed class ClickBackend {
    /** A bound, privileged [ShellUserService] reached via either Shizuku or
     *  root (libsu). Named ShizukuUserService for historical reasons; the
     *  backend is now any [BoundShellBackend]. */
    data class ShizukuUserService(val backend: BoundShellBackend) : ClickBackend()
    data class Shell(val access: ElevatedAccess) : ClickBackend()

    /** True if this backend can actually deliver a click right now. */
    val isReady: Boolean
        get() = when (this) {
            is ShizukuUserService -> backend.isReady
            is Shell -> access.isReady
        }

    val displayName: String
        get() = when (this) {
            is ShizukuUserService -> "Shizuku (UserService)"
            is Shell -> when (access) {
                is com.portalpad.app.shizuku.ShizukuManager -> "Shizuku (shell)"
                is RootManager -> "Root (shell)"
                else -> "Shell"
            }
        }
}
