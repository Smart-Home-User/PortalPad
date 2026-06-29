package com.portalpad.app.service

import android.util.Log

/**
 * JNI bridge to libportalpad_mouse, loaded inside the PRIVILEGED process
 * (Shizuku shell uid, or root) where [ShellUserService] runs and /dev/input can
 * be opened.
 *
 * Loading is deferred (not in an init block) and done by ABSOLUTE PATH. The
 * Shizuku/libsu user service is bootstrapped via app_process with a classloader
 * whose native-library search path does NOT include the app's lib dir, so a
 * plain System.loadLibrary("portalpad_mouse") fails with UnsatisfiedLinkError
 * even though the .so is on disk. We therefore System.load() the full path the
 * app passes us (its applicationInfo.nativeLibraryDir + the .so name), falling
 * back to loadLibrary by name in case the search path is fine.
 */
internal object NativeMouse {

    @Volatile var available: Boolean = false
        private set

    /** Human-readable reason the last [ensureLoaded] failed (for diagnostics). */
    @Volatile var loadError: String = ""
        private set

    private const val LIB_NAME = "portalpad_mouse"
    private const val SO_FILE = "libportalpad_mouse.so"
    private const val TAG = "NativeMouse"

    /**
     * Idempotently load the native lib. [nativeLibDir] is the app's
     * applicationInfo.nativeLibraryDir, readable by the privileged process.
     * Returns true if loaded (or already loaded).
     */
    @Synchronized
    fun ensureLoaded(nativeLibDir: String?): Boolean {
        if (available) return true
        val errors = StringBuilder()

        if (!nativeLibDir.isNullOrEmpty()) {
            val path = "$nativeLibDir/$SO_FILE"
            try {
                System.load(path)
                available = true
                loadError = ""
                Log.i(TAG, "loaded via absolute path $path")
                return true
            } catch (t: Throwable) {
                errors.append("load($path): ${t.message}; ")
            }
        } else {
            errors.append("no nativeLibDir; ")
        }

        // Fallback: by name (works if the classloader search path is correct).
        try {
            System.loadLibrary(LIB_NAME)
            available = true
            loadError = ""
            Log.i(TAG, "loaded via loadLibrary($LIB_NAME)")
            return true
        } catch (t: Throwable) {
            errors.append("loadLibrary($LIB_NAME): ${t.message}")
        }

        loadError = errors.toString()
        Log.w(TAG, "native load failed: $loadError")
        return false
    }

    /** Open the device read-write. Returns the fd (>= 0) or a negative errno. */
    external fun nativeOpen(path: String): Int

    /** EVIOCGRAB(1) the fd for exclusive access. Returns 0 or a negative errno. */
    external fun nativeGrab(fd: Int): Int

    /** EVIOCGRAB(0) + close. */
    external fun nativeUngrabClose(fd: Int)

    /**
     * Blocking read loop. Reads input_events from [fd], accumulates relative
     * motion, and on each SYN_REPORT writes a 16-byte little-endian record
     * {int32 dx, dy, buttons, wheel} to [writeFd]. Returns 0 on a requested
     * stop / reader-closed, or a negative errno if the device errored.
     */
    external fun nativeRunLoop(fd: Int, writeFd: Int): Int

    /** Signal [nativeRunLoop] to exit (checked ~5x/sec). */
    external fun nativeStop()
}
