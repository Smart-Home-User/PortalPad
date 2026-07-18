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

    /**
     * Overlay host for the mirror window, resolved fresh on every [show] attempt.
     *
     * This window is the ONLY thing that puts the VirtualDisplay's content on the
     * physical panel. As TYPE_APPLICATION_OVERLAY (2038) it is a NON-system overlay,
     * so any permission dialog raises HIDE_NON_SYSTEM_OVERLAY_WINDOWS and the system
     * force-hides it — the panel goes black while the VD behind it stays perfectly
     * healthy (dock/cursor/backdrop all present and fine). Confirmed via dumpsys:
     * `mPolicyVisibility=false`, `mIsForceHiddenNonSystemOverlayWindow=true`,
     * `Surface: shown=false mDrawState=READY_TO_SHOW`.
     *
     * The old `setSystemApplicationOverlay(true)` reflection was meant to exempt it,
     * but that only takes effect with INTERNAL_SYSTEM_WINDOW (a signature permission
     * we don't hold) — the reflection "succeeds" and the flag is silently ignored.
     *
     * TYPE_ACCESSIBILITY_OVERLAY (2032), hosted by the bound AccessibilityService,
     * IS exempt from that hide — the same fix already proven for the cursor, dock,
     * and floating bubble. Resolved per-attempt (not cached in a field) so a retry
     * after the a11y service binds can upgrade 2038 → 2032. Falls back to 2038 when
     * a11y isn't bound, which is exactly today's behaviour — never worse. The attach
     * ladder in [show] also flips [forceOverlay2038] on after 2032 token-nulls twice
     * on a reconnected physical display, escalating the remaining retries to 2038.
     */
    private fun resolveHost(): OverlayHost =
        OverlayHost.forDisplay(display, serviceContext, force2038 = forceOverlay2038)

    // Set true by [show]'s catch after the 2032 attach token-nulls repeatedly on a
    // fresh physical display (BadTokenException at addView). Escalates the remaining
    // retries to 2038, whose token is valid the moment the display exists. Per-
    // instance: a new mirror (fresh connect) starts back at the preferred 2032.
    @Volatile private var forceOverlay2038: Boolean = false

    // Fallback context/WM for the 2038 path and for callers that need a display
    // context outside show(). The host resolved in show() is authoritative for the
    // overlay window itself: views added through a WindowManager MUST be built from
    // that host's context or the 2032 add throws BadToken (the a11y window token
    // lives on the host context, not on this one).
    private val displayContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        serviceContext.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else {
        serviceContext.createDisplayContext(display)
    }
    private val windowManager =
        displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** The WindowManager that actually added [hostLayout]; removal must use the same one. */
    private var attachedWm: WindowManager = windowManager

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

    /** Bounded retry for the mirror-overlay attach — see the catch block in [show]. */
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var attachRetries: Int = 0
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

    /** True once a real VD frame has flowed through the GL renderer (mirror is
     *  pumping). False on a bound-but-stuck mirror sitting on its black init
     *  frame — the service polls this after attach to auto-kick a composition. */
    val hasPresentedContentFrame: Boolean get() = glRenderer?.hasPresentedContentFrame ?: false

    val isAttached: Boolean get() = attached

    /** True when the mirror is attached on the degraded 2038 fallback (the 2032
     *  a11y overlay token wasn't ready at attach — e.g. accessibility not yet bound
     *  on a fresh install). 2038 renders but is force-hidden by the system, so the
     *  panel looks black. The service re-attaches (upgrading to a visible 2032) once
     *  accessibility connects. */
    val isOnFallbackOverlay: Boolean get() = attached && forceOverlay2038
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

        // DIAG (Xiaomi blur + input): compare the physical panel's native pixels
        // and density against the SurfaceView we're rendering the VD into. If the
        // surface is smaller than the panel the VD image is upscaled (blur), and
        // if the VD was created at a different size than this surface the injected
        // tap coordinate space won't line up (taps miss inside apps). Pair this
        // with the "Starting AirGlassesSession WxH dpi" line (captured across a
        // reconnect) to see the full VD → surface → panel scaling chain.
        val sf = holder.surfaceFrame
        Log.d(
            TAG,
            "DIAG-VDSIZE panel=${w}x$h densityDpi=${metrics.densityDpi} disp=${display.displayId} " +
                "surfaceFrame=${sf.width()}x${sf.height()}",
        )

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
        // Retire any stale image before the new VD's first frame arrives. The panel
        // holds its last composited frame until something swaps a new one; a fresh
        // mirror over a freshly-created VD produces no frame until that VD's content
        // changes, so the old picture lingered (and only a nudge — DPI slider, replug
        // — forced a repaint). Black now; real content lands on the first frame.
        runCatching { glRenderer?.presentBlackFrame() }
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
        // NOTE: painting black belongs in dismiss(), BEFORE the window is removed —
        // see presentBlackAndSettle(). By the time releaseGl() runs on a surfaceDestroyed
        // callback the surface is already gone, and a swap here would be a no-op.
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
        // Resolve per-attempt: 2032 via the bound a11y service when available (exempt
        // from HIDE_NON_SYSTEM_OVERLAY_WINDOWS), else 2038. The SurfaceView and its
        // FrameLayout wrapper MUST be constructed from the host's context so the
        // window token matches the type being requested.
        val oh = resolveHost()
        val ctx = oh.context
        val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
        val sv = SurfaceView(ctx).apply {
            holder.addCallback(surfaceCallback)
            holder.setFormat(PixelFormat.OPAQUE)
        }
        // Wrap the SurfaceView in a FrameLayout. The window manager hands the
        // overlay a real ViewParent (the FrameLayout) instead of the raw
        // ViewRootImpl, so SurfaceView's parent.requestTransparentRegion(this)
        // call in onAttachedToWindow doesn't crash with NPE.
        val host = android.widget.FrameLayout(ctx).apply {
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
            oh.windowType,
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
        // EXPERIMENT (permfix): mark this overlay as a system application
        // overlay so HIDE_NON_SYSTEM_OVERLAY_WINDOWS (the security hide that
        // blanks the glasses during USB/permission dialogs) skips it. Only
        // takes effect if the app actually holds SYSTEM_APPLICATION_OVERLAY (or
        // INTERNAL_SYSTEM_WINDOW) — granted (attempted) at session start.
        // Silently ignored if the permission isn't held, so this is safe to set
        // unconditionally on API 31+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                WindowManager.LayoutParams::class.java
                    .getMethod("setSystemApplicationOverlay", Boolean::class.javaPrimitiveType)
                    .invoke(lp, true)
                Log.d(TAG, "setSystemApplicationOverlay(true) applied to mirror overlay")
            }.onFailure { Log.w(TAG, "setSystemApplicationOverlay failed: ${it.message}") }
        }
        try {
            oh.windowManager.addView(host, lp)
            attachedWm = oh.windowManager
            hostLayout = host
            surfaceView = sv
            attached = true
            attachRetries = 0
            hud = PerformanceHud(host, display).also { it.start() }
            Log.d(
                TAG,
                "VD mirror overlay added on display ${display.displayId} " +
                    "(${metrics.widthPixels}x${metrics.heightPixels}) " +
                    "type=${oh.windowType} a11y=${oh.isAccessibilityOverlay}" +
                    if (!oh.isAccessibilityOverlay) " — WARNING: 2038 is force-hidden by permission dialogs" else "",
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to add VD mirror overlay on display ${display.displayId}", t)
            // CRITICAL: this window is the ONLY thing that puts the VirtualDisplay's
            // content on the physical panel. Everything else (dock, cursor, backdrop)
            // lives INSIDE the VD and keeps working, so a silent failure here leaves
            // the panel BLACK with no other symptom — and previously nothing retried,
            // so it stayed black until the user stopped and restarted the service.
            // A 2038 overlay add can fail transiently (appop/token timing right after
            // an install). Retry with backoff; show() is idempotent (`if (attached)
            // return`) and dismiss() resets state, so a late success is harmless and a
            // retry after a real attach is a no-op.
            if (attachRetries < MAX_ATTACH_RETRIES) {
                val n = ++attachRetries
                // Attach ladder: 2032 ×2 (attempts 1–2), then 2038 ×3 (attempts
                // 3–5). The log shows 2032 token-nulling five times straight on a
                // reconnected physical display (fresh id, a11y window token not
                // registered yet), never self-healing → Android left the panel
                // mirroring the phone. Two 2032 tries distinguish "just the
                // synchronous onDisplayAdded attempt was too early" (retry #2,
                // deferred ~250ms, would then succeed) from "2032 is unattachable
                // on this id" — and if the deferred one still fails, escalate to
                // 2038, whose token is valid the instant the display exists.
                // Attach ladder: 2032 ×3 (attempts 1–3), then 2038 (attempts 4+).
                // The log shows 2032 token-nulling on a fresh physical display /
                // fresh install (a11y window token not registered yet), never self-
                // healing → Android left the panel mirroring the phone. A few 2032
                // tries give the accessibility service a moment to bind (its token
                // becoming valid is what makes 2032 succeed); if it still fails,
                // escalate to 2038 so SOMETHING shows — and the service re-attempts
                // 2032 once a11y connects (see onAccessibilityConnected).
                if (n >= 3 && !forceOverlay2038) {
                    forceOverlay2038 = true
                    Log.w(TAG, "DIAG-OVL escalating mirror overlay 2032 → 2038 on display " +
                        "${display.displayId} (2032 token-null after $n attempts)")
                }
                val delayMs = 250L * n
                val nextType = if (forceOverlay2038) 2038 else 2032
                Log.w(TAG, "Retrying VD mirror overlay attach in ${delayMs}ms " +
                    "(attempt $n/$MAX_ATTACH_RETRIES, next type=$nextType)")
                runCatching { oh.windowManager.removeView(host) }
                retryHandler.postDelayed({ if (!attached) show() }, delayMs)
            } else {
                Log.e(
                    TAG,
                    "VD mirror overlay attach FAILED after $MAX_ATTACH_RETRIES attempts on display " +
                        "${display.displayId} (tried 2032 then 2038) — the external display will stay " +
                        "black. Check the 'Display over other apps' permission.",
                )
            }
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

    /** Suppress/restore the parented performance HUD while the widget overlay
     *  is shown (the overlay renders above it). Passes through to the HUD; no-op
     *  when the HUD isn't attached (mirror mode uses the standalone HUD instead). */
    fun setHudWidgetHidden(hidden: Boolean) {
        hud?.setWidgetHidden(hidden)
    }

    fun dismiss() {
        // Cancel any pending attach retry FIRST — this must run before the
        // `hostLayout ?: return` below, because a failed attach leaves hostLayout
        // null, which is precisely the state where a retry is in flight. Otherwise
        // a retry could re-add the mirror onto a display we're tearing down.
        retryHandler.removeCallbacksAndMessages(null)
        attachRetries = 0
        val host = hostLayout ?: return
        val sv = surfaceView
        attached = false
        hud?.stop(); hud = null
        runCatching { sv?.holder?.removeCallback(surfaceCallback) }
        // Paint black and let it actually REACH the panel before the layer goes away.
        // eglSwapBuffers only QUEUES a buffer; releasing GL and removing the window in
        // the same breath destroys the layer before SurfaceFlinger ever composites it,
        // so nothing new is scanned out and the glasses keep their last image (a ghost
        // of windows that no longer exist). This is why presenting black inside
        // releaseGl() alone did nothing.
        presentBlackAndSettle(sv)
        releaseGl()
        runCatching { attachedWm.removeView(host) }
        hostLayout = null
        surfaceView = null
        Log.d(TAG, "VD mirror overlay dismissed on display ${display.displayId}")
    }

    /**
     * Push one opaque black frame to the panel and wait long enough for it to be
     * composited and scanned out. Called with the mirror window STILL attached.
     *
     * Falls back to a Canvas fill when the GL pipeline isn't active (direct-surface
     * path), and logs which route was taken — if neither works the panel will keep
     * its stale frame, and the log will say so instead of failing silently.
     */
    private fun presentBlackAndSettle(sv: SurfaceView?) {
        var painted = runCatching { glRenderer?.presentBlackFrame() }.getOrNull() ?: false
        if (!painted) {
            painted = runCatching {
                val h = sv?.holder ?: return@runCatching false
                val c = h.lockCanvas() ?: return@runCatching false
                c.drawColor(android.graphics.Color.BLACK)
                h.unlockCanvasAndPost(c)
                true
            }.getOrDefault(false)
            Log.d(TAG, "dismiss: black frame via Canvas fallback (ok=$painted)")
        } else {
            Log.d(TAG, "dismiss: black frame via GL")
        }
        if (!painted) {
            Log.w(TAG, "dismiss: could NOT paint black — panel may retain its last frame")
            return
        }
        runCatching { Thread.sleep(BLACK_SETTLE_MS) }
    }

    companion object {
        private const val TAG = "VDMirror"
        /** Attach attempts before we declare the panel unrecoverable and say why. */
        private const val MAX_ATTACH_RETRIES = 6
        /** ~5 vsyncs at 60Hz. Long enough for SurfaceFlinger to composite and scan out
         *  the black frame queued by eglSwapBuffers before we destroy the layer. */
        private const val BLACK_SETTLE_MS = 80L
    }
}
