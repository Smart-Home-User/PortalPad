package com.portalpad.app.diag

import android.util.Log
import com.portalpad.app.PortalPadApp
import com.portalpad.app.service.ClickBackend
import com.portalpad.app.service.RootManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.FileInputStream

/**
 * Streams filtered logcat lines into an in-memory ring buffer that Compose
 * can observe. Two backends supported:
 *
 * - Shizuku UserService: the user service (running as shell UID) spawns
 *   logcat and pipes stdout back via [android.os.ParcelFileDescriptor].
 *   This is the normal path and works without root.
 *
 * - Root: we `Runtime.exec("su -c logcat …")` directly from the app
 *   process. Requires the device to actually be rooted and granted su.
 *
 * When neither is available, [start] is a no-op and the buffer stays
 * empty. The viewer UI surfaces this state.
 *
 * Privacy: the default filter spec silences every tag except PortalPad's
 * own (see ShellUserService.DEFAULT_LOGCAT_FILTER). We never log
 * keystrokes, clipboard contents, or text-field content from our side,
 * so the visible stream is metadata-only.
 */
class LogcatStreamer {

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    enum class State { IDLE, RUNNING, NO_BACKEND, ERROR }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    /** The `su -c logcat` process when streaming via the root-shell fallback. */
    @Volatile private var rootProcess: Process? = null

    /** Max lines retained. Older lines drop off the front. */
    private val maxLines = 2000

    fun start(filter: String? = null) {
        if (_state.value == State.RUNNING) return
        val app = PortalPadApp.instance
        val backend = app.clickBackend
        when {
            // Bound UserService (Shizuku OR root via libsu) — the service spawns
            // logcat and pipes it back over a ParcelFileDescriptor.
            backend is ClickBackend.ShizukuUserService && backend.backend.isReady ->
                startViaBoundService(backend, filter)
            // Plain root shell — exec `su -c logcat` directly from the app
            // process. Used when root is granted but the bound RootUserService
            // isn't up (e.g. it failed to start, or while it's connecting).
            backend is ClickBackend.Shell && backend.access is RootManager && backend.access.isReady ->
                startViaRootShell(filter)
            else ->
                _state.value = State.NO_BACKEND
        }
    }

    private fun startViaBoundService(
        backend: ClickBackend.ShizukuUserService,
        filter: String?,
    ) {
        val pfd = runCatching { backend.backend.streamLogcat(filter) }.getOrNull()
        if (pfd == null) {
            _state.value = State.ERROR
            return
        }
        _state.value = State.RUNNING
        readerJob = scope.launch {
            try {
                FileInputStream(pfd.fileDescriptor).bufferedReader().use { br ->
                    pumpLines(br)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "bound-service logcat read failed", t)
                _state.value = State.ERROR
            } finally {
                runCatching { pfd.close() }
                if (_state.value == State.RUNNING) _state.value = State.IDLE
            }
        }
    }

    /**
     * Root fallback: spawn `su -c logcat` from the app process and read its
     * stdout. Unlike the bound-service path this also surfaces SELinux denials
     * and other system tags (we widen the filter to include avc denials), which
     * is exactly what's useful when diagnosing why the root service won't bind.
     */
    private fun startViaRootShell(filter: String?) {
        val spec = filter ?: ROOT_FILTER
        _state.value = State.RUNNING
        readerJob = scope.launch {
            var process: Process? = null
            try {
                // -v brief keeps lines compact; the filter spec selects tags.
                process = ProcessBuilder("su", "-c", "logcat -v brief $spec")
                    .redirectErrorStream(true)
                    .start()
                rootProcess = process
                process.inputStream.bufferedReader().use { br ->
                    pumpLines(br)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "root logcat read failed", t)
                _state.value = State.ERROR
            } finally {
                runCatching { process?.destroy() }
                rootProcess = null
                if (_state.value == State.RUNNING) _state.value = State.IDLE
            }
        }
    }

    private suspend fun pumpLines(br: BufferedReader) {
        // Stream line-by-line; append to the ring buffer.
        while (true) {
            val line = br.readLine() ?: break
            _lines.update { current ->
                if (current.size >= maxLines) {
                    current.drop(current.size - maxLines + 1) + line
                } else current + line
            }
        }
    }

    fun stop() {
        readerJob?.cancel()
        readerJob = null
        // Kill the root-shell logcat subprocess if we spawned one.
        runCatching { rootProcess?.destroy() }
        rootProcess = null
        // Also tell a bound user service to kill its subprocess.
        val app = PortalPadApp.instance
        val backend = app.clickBackend as? ClickBackend.ShizukuUserService
        runCatching { backend?.backend?.stopLogcatStream() }
        _state.value = State.IDLE
    }

    /** Clear the in-memory buffer (does NOT clear actual logcat). */
    fun clearBuffer() { _lines.value = emptyList() }

    /** Snapshot of the buffer at this moment. */
    fun snapshotText(): String = _lines.value.joinToString("\n")

    companion object {
        private const val TAG = "LogcatStreamer"
        // Root-shell fallback filter. Same PortalPad tags as the Shizuku path
        // (shared source of truth in LogTags), but we DON'T silence everything
        // (*:S) — instead we keep SELinux denials (avc/auditd/SELinux), libsu
        // errors, and warnings-and-above globally (*:W) so a failed root-service
        // bind is visible. Those denials are what explain such failures.
        private val ROOT_FILTER =
            LogTags.PORTALPAD_TAGS +
                "libsu:V avc:V auditd:V SELinux:V AndroidRuntime:E *:W"
    }
}
