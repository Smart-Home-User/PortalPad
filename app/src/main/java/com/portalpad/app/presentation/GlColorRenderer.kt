package com.portalpad.app.presentation

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU color pipeline (spike #2). The compositor refused to honor a color
 * transform on our layers, so we do the color math ourselves:
 *
 *   VD  ──renders into──▶  SurfaceTexture (external GL texture)
 *                              │  onFrameAvailable
 *                              ▼
 *   GL thread: sample texture through a fragment shader that applies a 4x4
 *   color matrix, draw a fullscreen quad, eglSwapBuffers ──▶ glasses surface
 *
 * [inputSurface] is what the VD must render into (caller redirects the VD to
 * it). [outputSurface] is the glasses SurfaceView's surface (the EGL window).
 *
 * Everything runs on a dedicated render thread. If GL setup fails, [start]
 * returns false and the caller falls back to the plain direct-surface path so
 * the glasses never go black.
 */
class GlColorRenderer(
    private val outputSurface: Surface,
    private val width: Int,
    private val height: Int,
) {
    private val thread = HandlerThread("GlColorRenderer").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    // Chosen EGL config (kept for creating the recording window surface).
    private var eglConfig: EGLConfig? = null
    // Recording tee: a 2nd EGL window surface over a MediaCodec input surface.
    // All access is on the GL thread (drawFrame + set/clearRecordSurface), so
    // no locking is needed. EGL_NO_SURFACE when not recording.
    private var recordEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    @Volatile private var recordActive = false

    // Set true the first time a real VD frame flows through drawFrame (onFrameAvailable
    // fired → updateTexImage succeeded). This is the definitive "mirror is pumping" signal:
    // a bound-but-stuck mirror sits on its black init frame and NEVER runs drawFrame, so
    // the service can poll this shortly after attach and auto-kick a composition if it's
    // still false (black-panel-on-plug guard).
    @Volatile private var contentFramePresented = false
    val hasPresentedContentFrame: Boolean get() = contentFramePresented

    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var uStMatrix = 0
    private var uColorMatrix = 0
    private var uGamma = 0
    private var uTexture = 0

    @Volatile private var surfaceTexture: SurfaceTexture? = null
    @Volatile var inputSurface: Surface? = null
        private set

    @Volatile private var released = false
    // Row-major 4x4 color matrix; identity by default (no change).
    @Volatile private var colorMatrix = identity()
    @Volatile private var gamma = 1f
    private val stMatrix = FloatArray(16)


    /** Full-screen quad: pos.xy + tex.uv (interleaved). */
    private val quad = floatArrayOf(
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f,
    )
    private lateinit var quadBuf: FloatBuffer
    // Screen-size: shrink the output quad symmetrically in clip space so the
    // (full-resolution) VD frame is drawn smaller and CENTERED on the panel, with
    // black bars filling the rest. 1f = full panel. Position-only scale (UV stays
    // 0..1), so it can never anchor to a corner. Applied on the GL thread.
    @Volatile private var screenScale = 1f
    private var scaleInBuf = 1f
    // Clip-space offset of the quad's center (Side Mode corner-dock). 0,0 = centered.
    // x: -1 = left edge .. +1 = right edge. y: +1 = top .. -1 = bottom (GL NDC).
    @Volatile private var offX = 0f
    @Volatile private var offY = 0f
    private var offXInBuf = 0f
    private var offYInBuf = 0f

    /** Set up EGL + GL on the render thread. Returns true on success. */
    fun start(): Boolean {
        val ok = runOnGlBlocking {
            try {
                initEgl()
                initGl()
                val st = SurfaceTexture(texId).apply {
                    setDefaultBufferSize(width, height)
                    setOnFrameAvailableListener({ requestDraw() }, handler)
                }
                surfaceTexture = st
                inputSurface = Surface(st)
                true
            } catch (t: Throwable) {
                Log.e(TAG, "GL init failed", t)
                false
            }
        }
        if (!ok) release()
        return ok
    }

    fun setColorMatrix(matrix4x4: FloatArray, gammaValue: Float = 1f) {
        if (matrix4x4.size == 16) {
            colorMatrix = matrix4x4.copyOf()
            gamma = if (gammaValue > 0.05f) gammaValue else 1f
            requestDraw()
        }
    }

    /**
     * Screen-size scale in (0,1]. 1 = full panel; lower draws the VD frame smaller
     * and centered (black bars around). The quad buffer is rebuilt lazily on the
     * GL thread inside [drawQuad], so this is safe to call from any thread.
     */
    fun setScreenScale(scale: Float) = setScreenTransform(scale, 0f, 0f)

    /**
     * Screen-size scale in (0,1] plus a clip-space center offset for Side Mode.
     * scale 1 + offset 0,0 = full centered panel. A smaller scale with a corner
     * offset of (±(1−scale), ±(1−scale)) docks the (still full-resolution) frame
     * into that corner. The quad buffer is rebuilt lazily on the GL thread inside
     * [drawQuad], so this is safe to call from any thread.
     */
    fun setScreenTransform(scale: Float, offsetX: Float, offsetY: Float) {
        val c = scale.coerceIn(0.1f, 1f)
        val ox = offsetX.coerceIn(-1f, 1f)
        val oy = offsetY.coerceIn(-1f, 1f)
        if (c != screenScale || ox != offX || oy != offY) {
            screenScale = c
            offX = ox
            offY = oy
            requestDraw()
        }
    }

    private fun requestDraw() {
        if (released) return
        handler.post { drawFrame() }
    }

    // ── Frame-time diagnostic (tag PortalPadDisplayDiag-adjacent): when on, logs
    // effective fps + per-frame draw+swap time ~once/sec WHILE frames flow (i.e.
    // during cursor/content motion). Tells us whether the 3840-wide mirror holds
    // 60fps or drops — the question behind ultrawide cursor stutter. ──

    private fun drawFrame() {
        if (released) return
        val st = surfaceTexture ?: return
        val drawStartNs = System.nanoTime()
        try {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
        } catch (t: Throwable) {
            Log.w(TAG, "updateTexImage failed", t); return
        }
        val frameTsNs = st.timestamp
        if (!contentFramePresented) {
            contentFramePresented = true
            Log.d(TAG, "first content frame presented (mirror live)")
        }

        // Primary output: the glasses.
        drawQuad()
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        // Recording tee: redraw the SAME frame (already in texId) to the encoder
        // input surface, stamped with the frame's presentation time, then restore
        // the glasses surface as current for the next frame. Records exactly what
        // the glasses show (post color-tuning).
        if (recordActive && recordEglSurface != EGL14.EGL_NO_SURFACE) {
            runCatching {
                if (EGL14.eglMakeCurrent(eglDisplay, recordEglSurface, recordEglSurface, eglContext)) {
                    drawQuad()
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, recordEglSurface, frameTsNs)
                    EGL14.eglSwapBuffers(eglDisplay, recordEglSurface)
                }
            }
            // Always restore the glasses surface, even if the tee draw failed.
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        // Render telemetry for the on-glasses Performance overlay (HUD). Cheap and
        // always-on (one nanoTime diff + a counter bump per frame); the HUD derives
        // delivered fps from frameCount deltas on its own ~1s tick.
        lastDrawNanos = System.nanoTime() - drawStartNs
        frameCount++
    }


    /** Rebuild the quad's clip-space positions when [screenScale] changed. Runs
     *  on the GL thread (called from drawQuad). UV (offsets 2,3) stays 0..1 so the
     *  full VD frame is sampled; only the position shrinks toward the center. */
    private fun ensureQuadScale() {
        if (scaleInBuf == screenScale && offXInBuf == offX && offYInBuf == offY) return
        val s = screenScale
        val ox = offX
        val oy = offY
        val scaled = floatArrayOf(
            -1f * s + ox, -1f * s + oy, 0f, 0f,
            1f * s + ox, -1f * s + oy, 1f, 0f,
            -1f * s + ox, 1f * s + oy, 0f, 1f,
            1f * s + ox, 1f * s + oy, 1f, 1f,
        )
        quadBuf.clear()
        quadBuf.put(scaled)
        quadBuf.position(0)
        scaleInBuf = s
        offXInBuf = ox
        offYInBuf = oy
    }

    /** Issue the GL draw calls for the full-screen color-corrected quad into
     *  whichever EGL surface is currently bound. */
    private fun drawQuad() {
        ensureQuadScale()
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        quadBuf.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quadBuf)
        quadBuf.position(2)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quadBuf)

        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
        // GLES requires transpose=false, so upload the column-major (transposed)
        // form of our row-major color matrix.
        GLES20.glUniformMatrix4fv(uColorMatrix, 1, false, transpose(colorMatrix), 0)
        GLES20.glUniform1f(uGamma, gamma)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uTexture, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    /**
     * Attach a MediaCodec input surface as a 2nd render target (recording). A
     * RECORDABLE EGL config is chosen for it so the encoder accepts the frames;
     * the glasses surface keeps its own (proven) config untouched. Runs on the
     * GL thread. Returns true on success.
     */
    fun setRecordSurface(surface: Surface): Boolean = runOnGlBlocking {
        if (released) return@runOnGlBlocking false
        runCatching {
            val cfg = chooseRecordableConfig() ?: eglConfig
            if (cfg == null) {
                Log.e(TAG, "no EGL config for recording"); return@runCatching false
            }
            val s = EGL14.eglCreateWindowSurface(
                eglDisplay, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0,
            )
            if (s == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "record eglCreateWindowSurface failed"); false
            } else {
                recordEglSurface = s
                recordActive = true
                true
            }
        }.getOrDefault(false)
    }

    /** Detach + destroy the recording surface. Runs on the GL thread. */
    fun clearRecordSurface() {
        runOnGlBlocking {
            recordActive = false
            if (recordEglSurface != EGL14.EGL_NO_SURFACE) {
                runCatching {
                    // Don't destroy the surface while it's current.
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                    EGL14.eglDestroySurface(eglDisplay, recordEglSurface)
                }
                recordEglSurface = EGL14.EGL_NO_SURFACE
            }
            true
        }
    }

    private fun chooseRecordableConfig(): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        return if (EGL14.eglChooseConfig(eglDisplay, attrs, 0, cfgs, 0, 1, num, 0) && num[0] > 0) {
            cfgs[0]
        } else {
            null
        }
    }

    /**
     * Clear the glasses surface to opaque black and PRESENT it.
     *
     * The panel only changes when a frame is swapped to it. [drawFrame] runs solely
     * on `onFrameAvailable` from the VirtualDisplay's SurfaceTexture, so when the VD
     * is destroyed (service stopped) or replaced (transient-mirror revert, replug),
     * no frame is produced and the display keeps compositing its LAST image — the
     * user sees a ghost of whatever was on screen (e.g. a Kindle window that no
     * longer exists) until something forces a repaint, like nudging the DPI slider.
     *
     * Pushing one black frame gives the panel something new to show. Safe to call
     * after the VD is gone: it touches only the EGL output surface, never the
     * SurfaceTexture. No-op once [release] has run.
     */
    fun presentBlackFrame(): Boolean = runOnGlBlocking {
        if (released) {
            Log.w(TAG, "presentBlackFrame: renderer already released — panel may keep last frame")
            return@runOnGlBlocking false
        }
        val ok = runCatching {
            if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) {
                return@runCatching false
            }
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            true
        }.getOrDefault(false)
        Log.d(TAG, "presentBlackFrame: ok=$ok")
        ok
    }

    fun release() {
        if (released) return
        released = true
        runOnGlBlocking {
            recordActive = false
            runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
            runCatching { inputSurface?.release() }
            runCatching { surfaceTexture?.release() }
            runCatching { if (program != 0) GLES20.glDeleteProgram(program) }
            runCatching { if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0) }
            runCatching {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (recordEglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, recordEglSurface)
                        recordEglSurface = EGL14.EGL_NO_SURFACE
                    }
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            }
            true
        }
        runCatching { thread.quitSafely() }
    }

    // ── GL/EGL setup ──
    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) error("no EGL display")
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) error("eglInitialize failed")
        val cfgAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, cfgs, 0, 1, num, 0) || num[0] <= 0) {
            error("eglChooseConfig failed")
        }
        val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglConfig = cfgs[0]
        eglContext = EGL14.eglCreateContext(eglDisplay, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) error("eglCreateContext failed")
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfgs[0], outputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) error("eglCreateWindowSurface failed")
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) error("eglMakeCurrent failed")
    }

    private fun initGl() {
        quadBuf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(quad); position(0) }
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        uColorMatrix = GLES20.glGetUniformLocation(program, "uColorMatrix")
        uGamma = GLES20.glGetUniformLocation(program, "uGamma")
        uTexture = GLES20.glGetUniformLocation(program, "sTexture")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            error("link failed: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            error("shader compile failed: $log")
        }
        return s
    }

    private fun <T> runOnGlBlocking(block: () -> T): T {
        var result: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try { result = block() } finally { latch.countDown() }
        }
        latch.await()
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    companion object {
        // Render telemetry for the on-glasses Performance overlay (HUD), updated each
        // frame by the active renderer. frameCount monotonic; HUD derives fps from
        // deltas. lastDrawNanos = most recent GL draw+swap time (GPU headroom).
        @Volatile var frameCount: Long = 0L
        @Volatile var lastDrawNanos: Long = 0L
        private const val TAG = "GlColorRenderer"
        // EGL_RECORDABLE_ANDROID (not in EGL14): marks a config whose surfaces
        // can be consumed by a MediaCodec video encoder.
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        // Tuning value order (14): 0 brightness, 1 contrast, 2 saturation,
        // 3 temp, 4 tint, 5 gamma, 6 blackLevel, 7 whitePoint, 8 rGain,
        // 9 gGain, 10 bGain, 11 rOffset, 12 gOffset, 13 bOffset.
        val DEFAULTS = floatArrayOf(1f, 1f, 1f, 0f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f)

        /** Build a row-major 4x4 color matrix from the tuning values (all the
         *  LINEAR ops). Gamma (index 5) is non-linear and applied in the shader
         *  separately, so it's not folded in here. Shared by UI + re-apply. */
        fun matrixFromValues(v: FloatArray): FloatArray {
            val vv = if (v.size == 14) v else DEFAULTS
            val brightness = vv[0]; val contrast = vv[1]; val saturation = vv[2]
            val temp = vv[3]; val tint = vv[4]
            val blackLevel = vv[6]; val whitePoint = vv[7]
            val rGain = vv[8]; val gGain = vv[9]; val bGain = vv[10]
            val rOff = vv[11]; val gOff = vv[12]; val bOff = vv[13]

            val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
            val s = saturation
            // Per-channel gains combine brightness, temp (blue↔orange), tint
            // (green↔magenta), and the per-channel gain sliders.
            val rg = brightness * (1f + temp) * (1f - tint * 0.5f) * rGain
            val gg = brightness * (1f + tint) * gGain
            val bg = brightness * (1f - temp) * (1f - tint * 0.5f) * bGain
            val c = contrast
            // Black level / white point remap: out = (in - bl)/(wp - bl).
            val span = (whitePoint - blackLevel).let { if (kotlin.math.abs(it) < 0.01f) 0.01f else it }
            val levelGain = 1f / span
            val levelOff = -blackLevel / span
            // Contrast pivots around 0.5.
            val contrastOff = 0.5f * (1f - c)
            fun sat(diag: Float, ch: Float) = (1f - s) * ch + s * diag
            // Combined per-row gain = saturation*channelGain*contrast*levelGain.
            fun row(diagR: Float, diagG: Float, diagB: Float, g: Float, off: Float): FloatArray {
                val k = g * c * levelGain
                return floatArrayOf(
                    sat(diagR, lr) * k, sat(diagG, lg) * k, sat(diagB, lb) * k,
                    contrastOff + levelOff * g + off,
                )
            }
            val r = row(1f, 0f, 0f, rg, rOff)
            val gr = row(0f, 1f, 0f, gg, gOff)
            val b = row(0f, 0f, 1f, bg, bOff)
            return floatArrayOf(
                r[0], r[1], r[2], r[3],
                gr[0], gr[1], gr[2], gr[3],
                b[0], b[1], b[2], b[3],
                0f, 0f, 0f, 1f,
            )
        }

        fun gammaOf(v: FloatArray): Float = if (v.size == 14) v[5] else 1f

        /** Parse comma-separated tuning values; defaults (padded) if blank/short. */
        fun parseValues(s: String): FloatArray {
            if (s.isBlank()) return DEFAULTS.copyOf()
            val parts = s.split(",").mapNotNull { it.trim().toFloatOrNull() }
            // Migrate older 7-value saves by padding with the new defaults.
            val out = DEFAULTS.copyOf()
            // Old order was: brightness,contrast,saturation,temp,rGain,gGain,bGain.
            if (parts.size == 7) {
                out[0] = parts[0]; out[1] = parts[1]; out[2] = parts[2]; out[3] = parts[3]
                out[8] = parts[4]; out[9] = parts[5]; out[10] = parts[6]
                return out
            }
            if (parts.size == 14) return parts.toFloatArray()
            return DEFAULTS.copyOf()
        }

        /** True when all values are defaults (identity color). */
        fun isDefault(v: FloatArray): Boolean = v.size == 14 && v.indices.all { v[it] == DEFAULTS[it] }

        private fun identity() = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )

        private fun transpose(m: FloatArray): FloatArray {
            val t = FloatArray(16)
            for (r in 0..3) for (c in 0..3) t[c * 4 + r] = m[r * 4 + c]
            return t
        }

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uStMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uStMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform mat4 uColorMatrix;
            uniform float uGamma;
            void main() {
                vec4 c = texture2D(sTexture, vTexCoord);
                vec3 tuned = (uColorMatrix * vec4(c.rgb, 1.0)).rgb;
                tuned = clamp(tuned, 0.0, 1.0);
                // Non-linear gamma applied after the linear matrix.
                tuned = pow(tuned, vec3(1.0 / uGamma));
                gl_FragColor = vec4(clamp(tuned, 0.0, 1.0), c.a);
            }
        """
    }
}
