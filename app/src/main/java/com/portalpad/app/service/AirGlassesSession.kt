package com.portalpad.app.service

import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * App-side orchestrator for the AR-glasses virtual-display session.
 *
 * Architecture (modeled on a reference session + display-wrap flow):
 *
 *  1. Create a trusted VirtualDisplay (owned by shell uid via Shizuku
 *     UserService). Initially renders to a placeholder ImageReader surface
 *     so the VD has somewhere to draw before the visible mirror is hooked
 *     up.
 *  2. The trackpad app creates a fullscreen system-overlay window on the
 *     PHYSICAL glasses display that holds a SurfaceView. When that
 *     SurfaceView's surface becomes available, the caller invokes
 *     [bindMirrorSurface] which swaps the VD's output to render INTO the
 *     SurfaceView — so VD content appears on the user's glasses.
 *  3. Apps (Chrome, etc.) launched via `am start --display <vdId>` run on
 *     the trusted VD. Injected mouse events targeting the VD's displayId
 *     are accepted as trusted system input → anchor popups (Chrome's
 *     address-bar suggestions, etc.) do not dismiss on cursor motion the
 *     way they did when we injected to the raw untrusted glasses display.
 *
 * The ImageReader's onImageAvailable listener drains frames so the
 * underlying buffer queue doesn't stall when the VD is rendering to it
 * (before the visible mirror is bound).
 */
class AirGlassesSession(private val backendProvider: () -> BoundShellBackend?) {

    /** The currently-active bound backend (root or Shizuku), or null if none. */
    private val backend: BoundShellBackend? get() = backendProvider()

    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    /**
     * EXPERIMENTAL app-owned TRUSTED VirtualDisplay. When the experimental flag
     * is on, the VD is created here (in PortalPad's own process, via a
     * Shizuku-wrapped IDisplayManager) instead of inside the Shizuku shell
     * process — so it survives Shizuku being stopped while staying trusted.
     * Non-null only while an app-owned session is active; null for the normal
     * shell-owned path. Its resize/setSurface/release method shapes mirror the
     * shell path so the call sites below are identical.
     */
    private var appVd: AppOwnedTrustedDisplay? = null

    @Volatile var virtualDisplayId: Int = -1
        private set

    @Volatile var physicalGlassesId: Int = -1
        private set

    @Volatile var lastWidth: Int = 0
        private set
    @Volatile var lastHeight: Int = 0
        private set
    @Volatile var lastDpi: Int = 0
        private set

    /** True when the visible mirror is hooked up — VD output goes to the
     *  user's physical glasses display rather than the invisible
     *  ImageReader. */
    @Volatile var mirrorBound: Boolean = false
        private set

    val isActive: Boolean get() = virtualDisplayId >= 0

    /**
     * Start (or resize) the VD session for the given glasses display.
     * If already active for the same glasses id and same dimensions, no-op.
     */
    fun start(glassesId: Int, width: Int, height: Int, dpi: Int): Int {
        if (backend?.isReady != true) {
            Log.w(TAG, "start: bound backend not ready")
            return -1
        }
        val b = backend!!
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)

        // Already active for this glasses id — just resize if needed.
        if (virtualDisplayId >= 0 && physicalGlassesId == glassesId) {
            if (lastWidth != w || lastHeight != h || lastDpi != dpi) {
                Log.d(TAG, "resize VD=$virtualDisplayId to ${w}x$h dpi=$dpi")
                if (appVd != null) {
                    runCatching { appVd?.resize(w, h, dpi) }
                    if (!mirrorBound) {
                        runCatching { appVd?.setSurface(obtainPlaceholderSurface(w, h)) }
                    }
                } else {
                    b.resizeVirtualDisplay(virtualDisplayId, w, h, dpi)
                    // Replace placeholder Surface so the new size takes effect when
                    // the visible mirror isn't bound yet.
                    if (!mirrorBound) {
                        val newSurface = obtainPlaceholderSurface(w, h)
                        b.setVirtualDisplaySurface(virtualDisplayId, newSurface)
                    }
                }
                lastWidth = w; lastHeight = h; lastDpi = dpi
            }
            return virtualDisplayId
        }

        // Different glasses or first start: tear down then start fresh.
        if (virtualDisplayId >= 0) stop()

        val placeholder = obtainPlaceholderSurface(w, h)
        // Flag selection — based on debugging on Samsung One UI Android 16:
        //
        //  PUBLIC (1) — visible to other apps so `am start --display N`
        //    can target it.
        //  OWN_CONTENT_ONLY (8) — apps on this VD don't mirror back to
        //    other displays.
        //  TRUSTED (1024) — Without it Chrome's PopupWindows dismiss
        //    defensively on cursor motion.
        //  OWN_DISPLAY_GROUP (2048) — independent focus/IME state so the
        //    glasses VD doesn't blank the phone when typing.
        //
        // v0.17.42 added all of the reference implementation's flag set including PRESENTATION
        // and DEVICE_DISPLAY_GROUP. Those didn't fix the dropdown bug,
        // and screenshots showed the VD was rendering Chrome's DESKTOP
        // tab-strip mode (a tab bar at the top with closeable tabs +
        // separate omnibox below). Chrome enters desktop mode when the
        // display reports Configuration.uiMode containing UI_MODE_TYPE_DESK
        // OR when VirtualDisplay flags suggest presentation/external
        // monitor semantics. In desktop mode, Chrome's address-bar dropdown
        // has DIFFERENT dismiss behavior than mobile mode — it dismisses
        // on cursor-motion-outside whereas mobile waits for an actual tap.
        //
        // v0.17.53 — NEW dropdown theory from a breakthrough observation:
        // taking a SCREENSHOT (which injects ZERO input) ALSO dismisses the
        // Chrome address-bar dropdown, exactly like cursor motion does.
        // Since a screenshot touches no input pipeline, the dismiss can't
        // be caused by our injected events — it must be a FOCUS / window-
        // state re-evaluation on the display. Anything that "pokes" the
        // display (screencap, cursor overlay relayout, cursor motion)
        // triggers a focus recheck, and Chrome's popup dismisses on focus
        // change.
        //
        // OWN_FOCUS makes the VD maintain its own independent focus state.
        // On Samsung One UI, an independent-focus display may aggressively
        // re-evaluate its focus owner on any display event — dropping
        // Chrome's popup. We've tested WITH OWN_FOCUS many times (always
        // dismisses); we have NEVER tested WITHOUT it. Drop it now. (Do NOT
        // re-add OWN_FOCUS to fix unrelated issues — it regresses the popup.)
        //
        // NOTE: ALWAYS_UNLOCKED was tried for the "SmartTube ends up on the
        // phone after screen-off" bug and REVERTED — logcat showed the VD is
        // DESTROYED on screen-off (the Shizuku shell process that owns it is
        // reaped), not merely locked, so an unlock flag can't help. That bug is
        // handled by flap-recovery in PortalPadForegroundService instead.
        //
        // Match the reference implementation's VD flags byte-for-byte. Captured live from a working
        // the reference implementation session via `dumpsys display` on this exact hardware:
        //   mFlags = 56651 = 0xDD4B
        //   = PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SCALING_DISABLED
        //     | ROTATES_WITH_CONTENT | TRUSTED | OWN_DISPLAY_GROUP
        //     | ALWAYS_UNLOCKED | OWN_FOCUS | DEVICE_DISPLAY_GROUP
        //
        // The three survival bits are ALWAYS_UNLOCKED, OWN_FOCUS, and
        // DEVICE_DISPLAY_GROUP — together they keep the VD alive and
        // task-hosting through a phone screen-off (SmartTube keeps playing on
        // the glasses) instead of the system evacuating its tasks to display 0.
        // ALWAYS_UNLOCKED alone did nothing in an earlier attempt because it
        // needs OWN_FOCUS + DEVICE_DISPLAY_GROUP as a set. OWN_FOCUS was once
        // blamed for dismissing Chrome's address-bar popup and removed; the
        // the reference implementation capture shows OWN_FOCUS set WITH its Chrome popup working, so
        // that was a misattribution — keeping the full set here.
        val flags = VD_FLAG_PUBLIC or
            VD_FLAG_PRESENTATION or
            VD_FLAG_OWN_CONTENT_ONLY or
            VD_FLAG_SCALING_DISABLED or
            VD_FLAG_ROTATES_WITH_CONTENT or
            VD_FLAG_TRUSTED or
            VD_FLAG_OWN_DISPLAY_GROUP or
            VD_FLAG_ALWAYS_UNLOCKED or
            VD_FLAG_OWN_FOCUS or
            VD_FLAG_DEVICE_DISPLAY_GROUP   // = 0xDD4B = 56651, matches the reference implementation

        // Prefer an app-owned TRUSTED VD (survives Shizuku stopping). Falls back
        // to the shell-owned VD automatically when app-owned isn't available —
        // i.e. on root (Shizuku-wrapper unusable) or if the wrapper create fails
        // on some device/Android version. Root's shell path is already robust
        // (its libsu daemon isn't toggled mid-session like Shizuku).
        var vdId = createAppOwnedVd(w, h, dpi, placeholder, flags)
        if (vdId < 0) {
            Log.d(TAG, "app-owned VD unavailable — using shell-owned path")
            vdId = b.createVirtualDisplay("PortalPad Session", w, h, dpi, flags, placeholder)
        }
        com.portalpad.app.PortalPadApp.instance.setSessionAppOwned(appVd != null)
        if (vdId < 0) {
            Log.e(TAG, "createVirtualDisplay returned -1; aborting")
            releaseResources()
            return -1
        }

        virtualDisplayId = vdId
        physicalGlassesId = glassesId
        lastWidth = w; lastHeight = h; lastDpi = dpi
        mirrorBound = false

        Log.d(TAG, "AirGlassesSession started: vdId=$vdId glasses=$glassesId ${w}x$h flags=0x${flags.toString(16)}")
        return vdId
    }

    /**
     * Redirect the VD's output to the given visible [surface] (typically
     * from a SurfaceView attached to the physical glasses display). After
     * this, VD content appears on the user's glasses.
     */
    fun bindMirrorSurface(surface: Surface) {
        val vd = virtualDisplayId
        if (vd < 0) {
            Log.w(TAG, "bindMirrorSurface: no active VD; ignoring")
            return
        }
        val ok = if (appVd != null) {
            runCatching { appVd?.setSurface(surface) }.isSuccess
        } else {
            backend?.setVirtualDisplaySurface(vd, surface) ?: false
        }
        mirrorBound = ok
        Log.d(TAG, "bindMirrorSurface vd=$vd ok=$ok appOwned=${appVd != null}")
    }

    /**
     * Detach the visible mirror surface and return the VD to rendering
     * into the invisible placeholder ImageReader. Called when the
     * SurfaceView is destroyed (e.g. the mirror overlay was dismissed).
     */
    fun unbindMirrorSurface() {
        val vd = virtualDisplayId
        if (vd < 0) return
        val placeholder = obtainPlaceholderSurface(lastWidth, lastHeight)
        if (appVd != null) {
            runCatching { appVd?.setSurface(placeholder) }
        } else {
            backend?.setVirtualDisplaySurface(vd, placeholder)
        }
        mirrorBound = false
        Log.d(TAG, "unbindMirrorSurface vd=$vd → placeholder")
    }

    fun stop() {
        val vdId = virtualDisplayId
        val glassesId = physicalGlassesId
        if (vdId >= 0) {
            runCatching { backend?.stopSurfaceMirror(glassesId) }
            if (appVd != null) {
                Log.i(TAG, "stop(): releasing app-owned VD (vdId=$vdId)")
                runCatching { appVd?.release() }
            } else {
                Log.i(TAG, "stop(): releasing shell VD (vdId=$vdId)")
                runCatching { backend?.releaseVirtualDisplay(vdId) }
            }
        } else {
            Log.i(TAG, "stop(): no VD to release (vdId=$vdId)")
        }
        appVd = null
        virtualDisplayId = -1
        physicalGlassesId = -1
        lastWidth = 0; lastHeight = 0; lastDpi = 0
        mirrorBound = false
        releaseResources()
        com.portalpad.app.PortalPadApp.instance.setSessionAppOwned(false)
        Log.d(TAG, "AirGlassesSession stopped")
    }

    /**
     * Create the TRUSTED VirtualDisplay in PortalPad's OWN process via
     * [AppOwnedTrustedDisplay] (a Shizuku-wrapped IDisplayManager call with an
     * app-hosted ownership token). Uses the SAME [flags] as the shell path, so
     * it's trusted — apps launch onto it and the keyboard shows on it — but
     * ownership lives here, so it survives Shizuku stopping. Shizuku must be
     * running NOW (it does the privileged creation); afterwards the display
     * persists without it. Returns the new display id, or -1 (caller falls back
     * to the shell-owned path).
     */
    private fun createAppOwnedVd(w: Int, h: Int, dpi: Int, placeholder: Surface, flags: Int): Int {
        // Defensive: never overwrite a live app-owned VD handle. If we still hold
        // one here (e.g. an earlier start threw between creating the VD and
        // recording its id, leaving virtualDisplayId=-1 but appVd set), release it
        // first — otherwise that VD leaks for the life of the process with no
        // handle left to reclaim it, surfacing as a duplicate "PortalPad Session".
        appVd?.let {
            Log.w(TAG, "createAppOwnedVd: releasing a still-held app-owned VD before re-create")
            runCatching { it.release() }
            appVd = null
        }
        val helper = AppOwnedTrustedDisplay()
        val id = helper.create("PortalPad Session (app-owned)", w, h, dpi, placeholder, flags)
        if (id < 0) {
            Log.e(TAG, "app-owned trusted VD creation failed (id=$id)")
            return -1
        }
        appVd = helper
        return id
    }

    private fun obtainPlaceholderSurface(width: Int, height: Int): Surface {
        val w = width.coerceAtLeast(1); val h = height.coerceAtLeast(1)
        imageReader?.let { existing ->
            if (existing.width == w && existing.height == h) return existing.surface
            runCatching { existing.close() }
        }
        val ht = handlerThread ?: HandlerThread("PortalPad SessionPlaceholder").also {
            it.start(); handlerThread = it
        }
        val hh = handler ?: Handler(ht.looper).also { handler = it }
        val reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        // Drain frames so the buffer queue doesn't back up while the VD is
        // rendering to us instead of the visible mirror.
        reader.setOnImageAvailableListener({ ir ->
            runCatching { ir.acquireLatestImage()?.close() }
        }, hh)
        imageReader = reader
        return reader.surface
    }

    private fun releaseResources() {
        runCatching { imageReader?.close() }; imageReader = null
        runCatching { handlerThread?.quitSafely() }
        handlerThread = null; handler = null
    }

    companion object {
        private const val TAG = "AirGlassesSession"

        // VirtualDisplay flag bit positions.
        //
        // Updated v0.17.40 — comprehensive set after dump-comparison vs
        // a reference implementation, whose `ShellUserService.createVirtualDisplay`
        // assembles a flag word of (PUBLIC|PRESENTATION|OWN_CONTENT_ONLY|
        // SUPPORTS_TOUCH|DESTROY_CONTENT_ON_REMOVAL|TRUSTED|
        // OWN_DISPLAY_GROUP|OWN_FOCUS|DEVICE_DISPLAY_GROUP) on Android 14+
        // (derived reference behavior line 8866 onward). The two we'd been missing
        // and that seem most relevant to the Chrome dropdown bug are
        // OWN_FOCUS (decouples popup-focus from cross-display events) and
        // SUPPORTS_TOUCH (declares the VD as touch-input capable).
        // Canonical AOSP DisplayManager.VIRTUAL_DISPLAY_FLAG_* bit positions.
        // (Several of these were previously mislabeled; corrected against AOSP.)
        const val VD_FLAG_PUBLIC = 1 shl 0                          // 1
        const val VD_FLAG_PRESENTATION = 1 shl 1                    // 2
        const val VD_FLAG_SECURE = 1 shl 2                          // 4
        const val VD_FLAG_OWN_CONTENT_ONLY = 1 shl 3               // 8
        const val VD_FLAG_AUTO_MIRROR = 1 shl 4                     // 16
        const val VD_FLAG_SCALING_DISABLED = 1 shl 6               // 64
        const val VD_FLAG_SUPPORTS_TOUCH = 1 shl 7                 // 128
        const val VD_FLAG_ROTATES_WITH_CONTENT = 1 shl 8           // 256
        const val VD_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 9     // 512
        const val VD_FLAG_TRUSTED = 1 shl 10                        // 1024
        const val VD_FLAG_OWN_DISPLAY_GROUP = 1 shl 11             // 2048
        const val VD_FLAG_ALWAYS_UNLOCKED = 1 shl 12              // 4096
        const val VD_FLAG_OWN_FOCUS = 1 shl 14                      // 16384
        const val VD_FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15          // 32768
        const val VD_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 16 // 65536
    }
}
