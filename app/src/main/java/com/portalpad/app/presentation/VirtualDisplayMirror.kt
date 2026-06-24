package com.portalpad.app.presentation

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import com.portalpad.app.service.ShizukuClickBackend
import kotlinx.coroutines.flow.first

/**
 * Renders the trusted VirtualDisplay's content onto the physical glasses
 * display. Without this, the VD renders to an invisible ImageReader and the
 * user sees nothing on their glasses.
 *
 * Architecture (modeled on a reference virtual-display wrapper):
 *
 *  - Adds a fullscreen system-overlay window to the PHYSICAL glasses
 *    display (e.g. id=109 or 113).
 *  - That overlay contains a single SurfaceView sized to the glasses.
 *  - When the SurfaceView's surface becomes available, we call
 *    [ShizukuClickBackend.setVirtualDisplaySurface] to redirect the VD's
 *    output INTO this SurfaceView. The VD's content then appears on the
 *    physical glasses display.
 *  - When the overlay is dismissed (or the surface is destroyed), we
 *    redirect the VD's output back to a placeholder Surface (the caller
 *    handles that).
 *
 * The overlay window is `TYPE_APPLICATION_OVERLAY` with NOT_TOUCHABLE +
 * NOT_FOCUSABLE flags — so it doesn't intercept any input from the user.
 * Input still goes to the VD via injected mouse events from elsewhere.
 *
 * @param onSurfaceReady invoked when the SurfaceView is ready and we
 *   should redirect the VD to it. Caller does the redirect.
 * @param onSurfaceGone invoked when the SurfaceView's surface is destroyed
 *   so the caller can swap the VD back to a fallback (ImageReader).
 */
class VirtualDisplayMirror(
    private val serviceContext: Context,
    private val display: Display,
    private val onSurfaceReady: (Surface) -> Unit,
    private val onSurfaceGone: () -> Unit,
) {

    private val displayContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        serviceContext.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else {
        serviceContext.createDisplayContext(display)
    }
    private val windowManager =
        displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * The host FrameLayout that wraps the SurfaceView. SurfaceView's
     * `onAttachedToWindow` calls `parent.requestTransparentRegion(this)`
     * — when SurfaceView is added directly as a system overlay window
     * (no parent ViewGroup), the parent IS the WindowManager root view
     * which on Samsung One UI / Android 16 returns null for some
     * operations, throwing NullPointerException at attach time.
     *
     * Wrapping in a FrameLayout (a proper ViewGroup) gives SurfaceView a
     * well-defined ViewParent that supports requestTransparentRegion,
     * fixing the crash entirely.
     */
    private var hostLayout: android.widget.FrameLayout? = null
    private var surfaceView: SurfaceView? = null
    @Volatile private var attached: Boolean = false
    // GPU color pipeline: the VD renders into the renderer's input SurfaceTexture,
    // which is sampled through a color-matrix shader and output to the glasses.
    // Null when GL setup failed (we then fall back to the direct surface path).
    private var glRenderer: GlColorRenderer? = null
    @Volatile private var lastMatrix: FloatArray? = null
    // Recording: force the GL pipeline on (even if the pref is off) so the VD
    // renders through GlColorRenderer — that's the only tee point for the
    // encoder. Cleared again when recording stops.
    @Volatile private var forceGl: Boolean = false
    // Fired if the GL pipeline is torn down (display dropped / VD recreated)
    // while a recording is attached, so the recorder can finalize its file.
    @Volatile private var onRecordingInterrupted: (() -> Unit)? = null

    // Refresh-rate lever: request the external display's highest mode at the current
    // resolution. Self-guarding (resolveTarget no-ops at ≤60Hz / on the SBS canvas) and
    // inert on the own-group VD, but correct on any future non-own-group display path.
    private var targetModeId: Int = 0
    private var targetRate: Float = 0f

    // On-glasses Performance overlay (diagnostic HUD). Created with the mirror window,
    // observes its own prefs live, torn down on dismiss.
    private var hud: PerformanceHud? = null

    val isGlActive: Boolean get() = glRenderer != null

    val isAttached: Boolean get() = attached
    val displayId: Int get() = display.displayId

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.d(TAG, "Mirror surface created on display ${display.displayId}")
            bindVdSurface(holder)
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.d(TAG, "Mirror surface changed ${width}x$height on display ${display.displayId}")
            bindVdSurface(holder)
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.d(TAG, "Mirror surface destroyed on display ${display.displayId}")
            releaseGl()
            try { onSurfaceGone() }
            catch (t: Throwable) { Log.e(TAG, "onSurfaceGone threw", t) }
        }
    }

    /**
     * Set up the GPU color pipeline: create a GlColorRenderer that outputs to
     * the glasses surface (holder.surface) and exposes an input surface the VD
     * renders into. If GL setup fails, fall back to the plain holder.surface
     * (the proven path) so the glasses never go black.
     */
    private fun bindVdSurface(holder: SurfaceHolder) {
        val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
        val w = metrics.widthPixels.coerceAtLeast(1)
        val h = metrics.heightPixels.coerceAtLeast(1)

        // Refresh-rate vote: request the panel's max rate. Self-guards to a no-op when
        // no higher mode exists; inert on the own-group VD but harmless and correct.
        resolveTarget()
        applyFrameRateVote()

        val gpuEnabled = forceGl || runCatching {
            kotlinx.coroutines.runBlocking {
                com.portalpad.app.PortalPadApp.instance.prefs.gpuColorPipeline.first()
            }
        }.getOrDefault(true)

        val glInput = if (!gpuEnabled) {
            Log.d(TAG, "GPU color pipeline disabled by setting — binding direct surface")
            null
        } else runCatching {
            if (glRenderer == null) {
                val r = GlColorRenderer(holder.surface, w, h)
                if (r.start()) {
                    // Re-apply persisted tuning (glasses don't store it; we do).
                    val saved = runCatching {
                        kotlinx.coroutines.runBlocking {
                            com.portalpad.app.PortalPadApp.instance.prefs.colorTuning.first()
                        }
                    }.getOrDefault("")
                    val v = GlColorRenderer.parseValues(saved)
                    if (!GlColorRenderer.isDefault(v)) {
                        r.setColorMatrix(
                            GlColorRenderer.matrixFromValues(v),
                            GlColorRenderer.gammaOf(v),
                        )
                    }
                    // Apply the persisted screen-size (centers the VD frame).
                    val pct = runCatching {
                        kotlinx.coroutines.runBlocking {
                            com.portalpad.app.PortalPadApp.instance.prefs.screenSizePct.first()
                        }
                    }.getOrDefault(100).coerceIn(50, 100)
                    r.setScreenScale(pct / 100f)
                    glRenderer = r
                    r.inputSurface
                } else {
                    r.release(); null
                }
            } else {
                glRenderer?.inputSurface
            }
        }.getOrNull()

        // VD renders into the GL input surface if available, else straight to
        // the glasses surface (no color, but guaranteed to show content).
        val target = glInput ?: holder.surface
        if (glInput == null) Log.w(TAG, "GL color pipeline unavailable — using direct surface")
        try { onSurfaceReady(target) }
        catch (t: Throwable) { Log.e(TAG, "onSurfaceReady threw", t) }
    }

    private fun releaseGl() {
        // If a recording is attached, stop teeing to the encoder and let the
        // recorder finalize its file BEFORE the renderer goes away.
        val cb = onRecordingInterrupted
        onRecordingInterrupted = null
        if (cb != null) {
            runCatching { glRenderer?.clearRecordSurface() }
            runCatching { cb() }
        }
        runCatching { glRenderer?.release() }
        glRenderer = null
        forceGl = false
    }

    /** The VD/glasses pixel size, used to size the encoder. */
    fun recordDimensions(): Pair<Int, Int>? = runCatching {
        val m = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
        m.widthPixels.coerceAtLeast(1) to m.heightPixels.coerceAtLeast(1)
    }.getOrNull()

    /**
     * Ensure the GL pipeline is active so there's a tee point for recording,
     * forcing it on (and rebinding the VD through GL) if the pref had it off.
     * Returns true if GL is active afterward. Call on the main thread.
     */
    fun ensureGlForRecording(): Boolean {
        if (glRenderer != null) return true
        forceGl = true
        val holder = surfaceView?.holder ?: run { forceGl = false; return false }
        runCatching { bindVdSurface(holder) }
        if (glRenderer == null) forceGl = false
        return glRenderer != null
    }

    /** Undo [ensureGlForRecording]: if GL was forced on, drop back to the user's
     *  real pref (rebinding direct-to-glasses when the pref is off). */
    fun restoreGlAfterRecording() {
        if (!forceGl) return
        forceGl = false
        val holder = surfaceView?.holder ?: return
        releaseGl()
        runCatching { bindVdSurface(holder) }
    }

    /** Attach a MediaCodec input surface as the recording tee. [onInterrupted]
     *  fires if the pipeline is torn down mid-recording. Returns true on success. */
    fun attachRecordSurface(surface: Surface, onInterrupted: () -> Unit): Boolean {
        val r = glRenderer ?: return false
        onRecordingInterrupted = onInterrupted
        val ok = r.setRecordSurface(surface)
        if (!ok) onRecordingInterrupted = null
        return ok
    }

    /** Detach the recording tee (normal stop). Safe if nothing is attached. */
    fun detachRecordSurface() {
        onRecordingInterrupted = null
        runCatching { glRenderer?.clearRecordSurface() }
    }

    fun show() {
        if (attached) return
        val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
        val sv = SurfaceView(displayContext).apply {
            holder.addCallback(surfaceCallback)
            holder.setFormat(PixelFormat.OPAQUE)
        }
        // Wrap the SurfaceView in a FrameLayout. The window manager hands the
        // overlay a real ViewParent (the FrameLayout) instead of the raw
        // ViewRootImpl, so SurfaceView's parent.requestTransparentRegion(this)
        // call in onAttachedToWindow doesn't crash with NPE.
        val host = android.widget.FrameLayout(displayContext).apply {
            addView(
                sv,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        val lp = WindowManager.LayoutParams(
            metrics.widthPixels, metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        // Refresh-rate hint: request the highest mode the external display offers at the
        // current resolution via this window's preferredDisplayModeId. Guarded — a 60-only
        // link / the 3840-wide SBS canvas (no >60 mode) leaves the default unchanged.
        // Inert on this device (the own-group VD pins render to 60); harmless and correct
        // on any future non-own-group display path.
        resolveTarget()
        if (targetModeId != 0) {
            lp.preferredDisplayModeId = targetModeId
            Log.d(TAG, "Refresh: requesting ${"%.1f".format(targetRate)}Hz modeId=$targetModeId " +
                "on display ${display.displayId}")
        }
        try {
            windowManager.addView(host, lp)
            hostLayout = host
            surfaceView = sv
            attached = true
            hud = PerformanceHud(host, display).also { it.start() }
            Log.d(TAG, "VD mirror overlay added on display ${display.displayId} (${metrics.widthPixels}x${metrics.heightPixels})")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to add VD mirror overlay on display ${display.displayId}", t)
        }
    }

    private fun resolveTarget() {
        runCatching {
            val cur = display.mode
            val t = display.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate }
            if (t != null && t.refreshRate > cur.refreshRate + 0.5f) {
                targetModeId = t.modeId; targetRate = t.refreshRate
            } else {
                targetModeId = 0; targetRate = 0f
            }
        }.onFailure { targetModeId = 0; targetRate = 0f }
    }

    /** Request the panel's max refresh via Surface.setFrameRate. No-op below 60Hz. */
    private fun applyFrameRateVote() {
        if (targetRate <= 60.5f) return
        val surface = surfaceView?.holder?.surface ?: return
        if (!surface.isValid) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(
                    targetRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else {
                @Suppress("DEPRECATION")
                surface.setFrameRate(targetRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
            Log.d(TAG, "Refresh: frame-rate vote ${"%.1f".format(targetRate)}Hz on display ${display.displayId}")
        }.onFailure { Log.w(TAG, "Refresh: setFrameRate failed", it) }
    }

    /**
     * Apply a 4x4 color matrix (+ gamma) to the glasses via the GPU pipeline.
     * No-op fallback message if the pipeline isn't active.
     */
    fun setColorMatrix(matrix4x4: FloatArray, gamma: Float = 1f): String {
        if (matrix4x4.size != 16) return "FAIL: matrix must be 16 floats"
        lastMatrix = matrix4x4
        val r = glRenderer
            ?: return "FAIL: GPU color pipeline not active (GL setup failed or disabled; color unavailable)."
        return try {
            r.setColorMatrix(matrix4x4, gamma)
            "OK: color matrix sent to GPU pipeline."
        } catch (t: Throwable) {
            "EXCEPTION: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** Live screen-size: scale in (0,1], 1 = full. No-op (returns false) when the
     *  GL pipeline isn't active — screen-size centering needs the GL renderer. */
    fun setScreenScale(scale: Float): Boolean {
        val r = glRenderer ?: return false
        return runCatching { r.setScreenScale(scale); true }.getOrDefault(false)
    }

    /** Scale + clip-space corner offset (Side Mode). Needs the GL pipeline active. */
    fun setScreenTransform(scale: Float, offsetX: Float, offsetY: Float): Boolean {
        val r = glRenderer ?: return false
        return runCatching { r.setScreenTransform(scale, offsetX, offsetY); true }.getOrDefault(false)
    }

    fun dismiss() {
        val host = hostLayout ?: return
        val sv = surfaceView
        attached = false
        hud?.stop(); hud = null
        runCatching { sv?.holder?.removeCallback(surfaceCallback) }
        releaseGl()
        runCatching { windowManager.removeView(host) }
        hostLayout = null
        surfaceView = null
        Log.d(TAG, "VD mirror overlay dismissed on display ${display.displayId}")
    }

    companion object { private const val TAG = "VDMirror" }
}
