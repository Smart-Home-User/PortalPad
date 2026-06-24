package com.portalpad.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Screen recording of the EXTERNAL display (the glasses) by capturing
 * PortalPad's OWN VirtualDisplay output — NOT `screenrecord`.
 *
 * `screenrecord` on this hardware can only target the primary panel: it rejects
 * the glasses' SurfaceFlinger token, logical id, and the VD id alike ("invalid
 * physical display id"). So instead we tee the VD's frames off the GL pipeline
 * already in the mirror path: [com.portalpad.app.presentation.GlColorRenderer]
 * draws each frame to the glasses AND to a [VdRecorder] MediaCodec input
 * surface, which muxes straight to an MP4. This records exactly what the glasses
 * show (post color-tuning) and needs no Shizuku — just the live VD mirror.
 *
 * The GL tee only exists when the GPU color pipeline is on, so a recording
 * forces it on for its duration (restoring the user's pref on stop).
 *
 * Notes / limitations:
 *  - VIDEO ONLY: the VirtualDisplay carries no audio track.
 *  - DRM/secure surfaces are black in the VD itself, same Widevine wall.
 *  - If the display drops mid-recording, the file is finalized with whatever
 *    was captured (not corrupted).
 *
 * State is a singleton (survives recomposition / tab switches) so the top-bar
 * icon and timer reflect the live recording wherever it's shown.
 */
object ScreenRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsedSec = MutableStateFlow(0)
    val elapsedSec: StateFlow<Int> = _elapsedSec.asStateFlow()

    private var timerJob: Job? = null

    // The live recording (null when idle). Touched from main + the interrupt
    // callback; @Volatile + null-swap guards the start/stop/interrupt race.
    @Volatile private var activeRecorder: com.portalpad.app.presentation.VdRecorder? = null
    @Volatile private var activeFile: java.io.File? = null

    /** mm:ss for the live timer readout. */
    fun formatElapsed(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

    fun toggle(app: PortalPadApp) {
        if (_isRecording.value) stop(app) else start(app)
    }

    fun start(app: PortalPadApp) {
        if (_isRecording.value) return
        val mirror = com.portalpad.app.service.PortalPadForegroundService.activeVdMirror
        if (mirror == null || app.externalDisplayId.value == null) {
            toast(app, "Recording needs the glasses display connected")
            return
        }
        _isRecording.value = true
        _elapsedSec.value = 0
        // GL force-on + surface attach rebinds the SurfaceView, so do it on the
        // main thread; the heavy encode/drain runs on its own threads.
        mainScope.launch {
            val ok = runCatching { beginRecording(app, mirror) }.getOrElse {
                android.util.Log.w("ScreenRecorder", "start failed", it); false
            }
            if (!ok) {
                _isRecording.value = false
                stopTimer()
            }
        }
    }

    /** Main-thread: force GL on, start the encoder, attach its surface to the
     *  live renderer. Returns true once frames are flowing into the recorder. */
    private fun beginRecording(
        app: PortalPadApp,
        mirror: com.portalpad.app.presentation.VirtualDisplayMirror,
    ): Boolean {
        if (!mirror.ensureGlForRecording()) {
            toast(app, "Couldn't start the GPU pipeline for recording")
            return false
        }
        val dims = mirror.recordDimensions()
        if (dims == null) {
            toast(app, "Recording: couldn't read the display size")
            mirror.restoreGlAfterRecording()
            return false
        }
        val (w, h) = dims
        val dir = java.io.File(app.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val file = java.io.File(dir, "portalpad_rec_${System.currentTimeMillis()}.mp4")
        val rec = com.portalpad.app.presentation.VdRecorder(w, h, file)
        if (!rec.start()) {
            toast(app, "Recording: encoder failed to start")
            mirror.restoreGlAfterRecording()
            return false
        }
        val surface = rec.inputSurface
        if (surface == null) {
            rec.stop(); mirror.restoreGlAfterRecording()
            toast(app, "Recording: no encoder surface")
            return false
        }
        val attached = mirror.attachRecordSurface(surface) { onRecordingInterrupted(app) }
        if (!attached) {
            rec.stop(); mirror.restoreGlAfterRecording()
            toast(app, "Recording: couldn't attach to the GPU pipeline")
            return false
        }
        activeRecorder = rec
        activeFile = file
        startTimer()
        toast(app, "Recording the glasses display")
        return true
    }

    fun stop(app: PortalPadApp) {
        if (!_isRecording.value) return
        _isRecording.value = false
        stopTimer()
        val mirror = com.portalpad.app.service.PortalPadForegroundService.activeVdMirror
        mainScope.launch {
            // Stop teeing to the encoder FIRST, then restore the GL pref.
            runCatching { mirror?.detachRecordSurface() }
            val rec = activeRecorder; val file = activeFile
            activeRecorder = null; activeFile = null
            runCatching { mirror?.restoreGlAfterRecording() }
            if (rec == null || file == null) return@launch
            // Signal EOS + flush + finalize off the main thread.
            scope.launch {
                val ok = runCatching { rec.stop() }.getOrDefault(false)
                finishSave(app, ok, file)
            }
        }
    }

    /** Pipeline torn down mid-recording (display dropped / VD recreated): the
     *  mirror already detached the tee, so just finalize whatever we captured. */
    private fun onRecordingInterrupted(app: PortalPadApp) {
        val rec = activeRecorder; val file = activeFile
        activeRecorder = null; activeFile = null
        _isRecording.value = false
        stopTimer()
        if (rec == null || file == null) return
        scope.launch {
            val ok = runCatching { rec.stop() }.getOrDefault(false)
            finishSave(app, ok, file)
        }
    }

    private fun finishSave(app: PortalPadApp, ok: Boolean, file: java.io.File) {
        if (ok && file.exists() && file.length() > 0L) {
            val uri = saveRecordingToMovies(app, file)
            toast(app, if (uri != null) "Recording saved to Movies/PortalPad" else "Recording saved")
        } else {
            toast(app, "Recording was empty")
        }
        runCatching { file.delete() }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (_isRecording.value) {
                delay(1000)
                _elapsedSec.value = _elapsedSec.value + 1
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun saveRecordingToMovies(
        ctx: android.content.Context,
        file: java.io.File,
    ): android.net.Uri? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    android.provider.MediaStore.Video.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_MOVIES + "/PortalPad",
                )
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values,
            ) ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: run { resolver.delete(uri, null, null); return@runCatching null }
            values.clear()
            values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MOVIES,
                ),
                "PortalPad",
            ).apply { mkdirs() }
            val dest = java.io.File(dir, file.name)
            file.copyTo(dest, overwrite = true)
            android.media.MediaScannerConnection.scanFile(
                ctx, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null,
            )
            androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", dest,
            )
        }
    }.getOrNull()

    private fun toast(app: PortalPadApp, msg: String, long: Boolean = false) {
        mainScope.launch {
            android.widget.Toast.makeText(
                app,
                msg,
                if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
