package com.portalpad.app.presentation

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * Hardware H.264 encoder fed by a GL input surface, muxed straight to an MP4.
 *
 * [GlColorRenderer] draws each VirtualDisplay frame into [inputSurface] (a 2nd
 * EGL target alongside the glasses), so this records exactly what the glasses
 * show — no `screenrecord`, no Shizuku. `screenrecord` can only target the
 * device's primary panel on this hardware; capturing our own VD output is the
 * only way to record the glasses.
 *
 * Video only: the VirtualDisplay carries no audio track. One instance per
 * recording — create, [start], hand [inputSurface] to the renderer, then [stop].
 */
class VdRecorder(
    private val width: Int,
    private val height: Int,
    private val outputFile: File,
) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurfaceInternal: Surface? = null

    /** The encoder's input surface — render frames here. Null until [start]. */
    val inputSurface: Surface? get() = inputSurfaceInternal

    private var trackIndex = -1
    private var muxerStarted = false
    private var drainThread: Thread? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    /** Configure + start the encoder and the drain thread. Returns true on success. */
    fun start(): Boolean = runCatching {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            // ~12 Mbps at 1080p; clamped so odd sizes stay sane.
            val bitRate = (width.toLong() * height.toLong() * 6L)
                .coerceIn(4_000_000L, 20_000_000L).toInt()
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val c = MediaCodec.createEncoderByType(MIME)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurfaceInternal = c.createInputSurface()
        c.start()
        codec = c
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        drainThread = Thread({ drainLoop() }, "VdRecorderDrain").also { it.start() }
        true
    }.getOrElse {
        Log.e(TAG, "start failed", it)
        releaseQuietly()
        false
    }

    /**
     * Pull encoded buffers into the muxer until end-of-stream. The encoder emits
     * a format-change first (→ add the track + start the muxer), then encoded
     * samples, then an EOS flag once [stop] signals the input. Runs on its own
     * thread so it never stalls the GL render thread.
     */
    private fun drainLoop() {
        val c = codec ?: return
        try {
            while (true) {
                val idx = c.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // No output ready yet — keep polling (EOS will break us out).
                    continue
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        trackIndex = muxer!!.addTrack(c.outputFormat)
                        muxer!!.start()
                        muxerStarted = true
                    }
                } else if (idx >= 0) {
                    val buf = c.getOutputBuffer(idx)
                    // Codec-config bytes (SPS/PPS) are folded into the track format
                    // by the muxer; don't write them as a sample.
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted && buf != null) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer!!.writeSampleData(trackIndex, buf, bufferInfo)
                    }
                    c.releaseOutputBuffer(idx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "drainLoop error", t)
        }
    }

    /**
     * Signal end-of-stream, wait for the drain to flush, finalize the MP4, and
     * release everything. Returns true if a non-empty, muxed file was written.
     * The caller MUST stop rendering to [inputSurface] before calling this.
     */
    fun stop(): Boolean {
        val c = codec ?: run { releaseQuietly(); return false }
        runCatching { c.signalEndOfInputStream() }
        runCatching { drainThread?.join(2500) }
        val wrote = muxerStarted
        runCatching { if (muxerStarted) muxer?.stop() }
        releaseQuietly()
        return wrote && outputFile.exists() && outputFile.length() > 0L
    }

    private fun releaseQuietly() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { muxer?.release() }
        runCatching { inputSurfaceInternal?.release() }
        codec = null
        muxer = null
        inputSurfaceInternal = null
        muxerStarted = false
    }

    companion object {
        private const val TAG = "VdRecorder"
        private const val MIME = "video/avc"
        private const val TIMEOUT_US = 10_000L
    }
}
