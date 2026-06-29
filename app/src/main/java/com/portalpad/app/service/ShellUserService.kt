package com.portalpad.app.service

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Privileged helper running inside Shizuku's process (shell UID 2000).
 *
 * Architecture (reference-behavior analysis):
 *
 *   1. createVirtualDisplay(name, w, h, dpi, flags, surface) — VD backed by
 *      caller-provided Surface (caller owns the Surface, e.g. ImageReader).
 *      Returns a virtual display id which becomes a TRUSTED display.
 *   2. startSurfaceMirror(physicalGlassesId, surface) — mirrors the glasses'
 *      content INTO our Surface. Two paths:
 *      a) DisplayManager.createVirtualDisplay(name, w, h, glassesId, surface)
 *         — preferred — uses the glasses display id as input
 *      b) SurfaceControl reflection — fallback for OEMs where (a) fails
 *
 * Then clicks target the VD id (not glasses id). VD is trusted and we own it.
 *
 * Heavy reflection because SurfaceControl APIs are @hide.
 */
class ShellUserService : IShellService.Stub {

    private var context: Context? = null

    // Cached reflection — set up once
    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    private val injectModeAsync = 0
    private var displayManagerGlobal: Any? = null
    private var dmCreateMirrorMethod: Method? = null
    private var getDisplayInfoMethod: Method? = null
    private var scClass: Class<*>? = null
    private var scOpenTransaction: Method? = null
    private var scCloseTransaction: Method? = null
    private var scCreateDisplay: Method? = null
    private var scDestroyDisplay: Method? = null
    private var scSetDisplayLayerStack: Method? = null
    private var scSetDisplayProjection: Method? = null
    private var scSetDisplaySurface: Method? = null
    private var setMotionDisplayId: Method? = null
    private var setKeyDisplayId: Method? = null

    private var reflectionBroken = false
    private var lastMouseActionButton = 0

    private val virtualDisplays = ConcurrentHashMap<Int, VirtualDisplay>()
    private val mirrorVirtualDisplays = ConcurrentHashMap<Int, VirtualDisplay>()
    private val surfaceMirrorTokens = ConcurrentHashMap<Int, IBinder>()

    @Suppress("unused")
    constructor() {
        setupReflection()
        Log.d(TAG, "ShellUserService init (no-arg); uid=${Process.myUid()}")
    }

    @Suppress("unused")
    constructor(context: Context) {
        this.context = context
        setupReflection()
        Log.d(TAG, "ShellUserService init (Context); uid=${Process.myUid()}")
    }

    private fun setupReflection() {
        try {
            val cls = Class.forName("android.hardware.input.InputManager")
            inputManager = cls.getMethod("getInstance").invoke(null)
            injectInputEventMethod = cls.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType,
            )
            setMotionDisplayId = runCatching {
                MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            }.getOrNull()
            setKeyDisplayId = runCatching {
                KeyEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            }.getOrNull()
        } catch (t: Throwable) {
            Log.e(TAG, "Reflection setup failed", t)
            reflectionBroken = true
        }
    }

    // ─── Input injection ────────────────────────────────────────────────

    override fun injectPointer(
        action: Int, x: Float, y: Float, displayId: Int,
        source: Int, buttonState: Int, downTime: Long,
    ) {
        if (reflectionBroken) return
        injectInternal(action, x, y, displayId, downTime, SystemClock.uptimeMillis(), source, buttonState)
    }

    private fun injectInternal(
        action: Int, x: Float, y: Float, displayId: Int,
        downTime: Long, eventTime: Long, source: Int, buttonState: Int,
    ) {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = if (source == 8194) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            pressure = if (buttonState != 0 || action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) 1f else 0f
            size = 1f
        }
        try {
            var effectiveButtons = buttonState
            val event = MotionEvent.obtain(
                downTime, eventTime, action, 1, arrayOf(props), arrayOf(coords),
                0, effectiveButtons, 1f, 1f, 0, 0, source, 0,
            )
            if (source == 8194) {
                if (effectiveButtons != 0) {
                    lastMouseActionButton = effectiveButtons
                } else if (action == MotionEvent.ACTION_UP) {
                    effectiveButtons = lastMouseActionButton
                }
                if (effectiveButtons != 0) {
                    runCatching {
                        MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
                            .invoke(event, effectiveButtons)
                    }
                }
                if (action == MotionEvent.ACTION_UP) lastMouseActionButton = 0
            }
            setDisplayId(event, displayId)
            injectInputEventMethod?.invoke(inputManager, event, injectModeAsync)
            event.recycle()
        } catch (t: Throwable) {
            Log.e(TAG, "Inject failed", t)
        }
    }

    override fun injectScroll(x: Float, y: Float, vScroll: Float, hScroll: Float, displayId: Int) {
        if (reflectionBroken) return
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
            if (hScroll != 0f) setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
        }
        try {
            val event = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
            )
            setDisplayId(event, displayId)
            injectInputEventMethod?.invoke(inputManager, event, injectModeAsync)
            event.recycle()
        } catch (t: Throwable) {
            Log.e(TAG, "Scroll inject failed", t)
        }
    }

    override fun injectKey(keyCode: Int, action: Int, metaState: Int, displayId: Int, deviceId: Int) {
        if (reflectionBroken) return
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            now, now, action, keyCode, 0, metaState,
            if (deviceId >= 0) deviceId else 1, 0,
            KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD,
        )
        try {
            setDisplayId(event, displayId)
            injectInputEventMethod?.invoke(inputManager, event, injectModeAsync)
        } catch (t: Throwable) {
            Log.e(TAG, "Key inject failed", t)
        }
    }

    override fun injectKeyPress(keyCode: Int, displayId: Int) {
        injectKeyPressInternal(keyCode, displayId, InputDevice.SOURCE_KEYBOARD)
    }

    override fun injectGamepadKeyPress(keyCode: Int, displayId: Int) {
        // Tag as gamepad — for the program keys (BUTTON_1..4), this matches
        // what a real gamepad would send and gives key-mapping apps the best
        // chance of capturing the event via their gamepad input pipeline.
        injectKeyPressInternal(
            keyCode,
            displayId,
            InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK,
        )
    }

    private fun injectKeyPressInternal(keyCode: Int, displayId: Int, source: Int) {
        if (reflectionBroken) return
        // Real wall-clock gap (12ms) between DOWN and UP. This is the same
        // pattern as injectTap below and is necessary for InputDispatcher on
        // an external display to reliably accept the pair as one press.
        //
        // History: v0.17.71 removed the sleep to eliminate a "trail" bug
        // (rapid taps queued up). The sleep itself wasn't the trail cause —
        // a client-side blocking call was. Without ANY real gap, the
        // dispatcher silently drops occasional presses, especially under
        // rapid sequences. We're back on the SERVER side here, in a Shizuku
        // worker thread; sleeping here doesn't block our client keyExecutor
        // (it's a different process), so it costs nothing client-side. Net:
        // reliable delivery, no client thread blocked.
        val downTime = SystemClock.uptimeMillis()
        val down = KeyEvent(
            downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
            1, 0, KeyEvent.FLAG_FROM_SYSTEM, source,
        )
        try {
            setDisplayId(down, displayId)
            injectInputEventMethod?.invoke(inputManager, down, injectModeAsync)
        } catch (t: Throwable) {
            Log.e(TAG, "Key DOWN inject failed", t)
            return
        }
        try { Thread.sleep(12) } catch (_: InterruptedException) {}
        val upTime = SystemClock.uptimeMillis()
        val up = KeyEvent(
            downTime, upTime, KeyEvent.ACTION_UP, keyCode, 0, 0,
            1, 0, KeyEvent.FLAG_FROM_SYSTEM, source,
        )
        try {
            setDisplayId(up, displayId)
            injectInputEventMethod?.invoke(inputManager, up, injectModeAsync)
        } catch (t: Throwable) {
            Log.e(TAG, "Key UP inject failed", t)
        }
    }

    override fun injectTap(displayId: Int, x: Float, y: Float) {
        if (reflectionBroken) return
        // Important: real wall-clock delay between DOWN and UP. Without it,
        // both events arrive at InputDispatcher within microseconds and the
        // UP gets dropped because the DOWN session hasn't been registered yet.
        // (Right-click works because injectLongPress already has a 50ms+
        // MOVE-loop between DOWN and UP — that's why right-click reliably
        // landed where the old left-click didn't.)
        val now = SystemClock.uptimeMillis()
        injectInternal(MotionEvent.ACTION_DOWN, x, y, displayId, now, now, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { Thread.sleep(40) } catch (_: InterruptedException) {}
        val upTime = SystemClock.uptimeMillis()
        injectInternal(MotionEvent.ACTION_UP, x, y, displayId, now, upTime, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    override fun injectLongPress(displayId: Int, x: Float, y: Float, durationMs: Long) {
        if (reflectionBroken) return
        val now = SystemClock.uptimeMillis()
        injectInternal(MotionEvent.ACTION_DOWN, x, y, displayId, now, now, InputDevice.SOURCE_TOUCHSCREEN, 0)
        var elapsed = 0L
        while (elapsed < durationMs) {
            try { Thread.sleep(50); elapsed += 50 } catch (_: InterruptedException) { break }
            injectInternal(MotionEvent.ACTION_MOVE, x, y, displayId, now, now + elapsed, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        injectInternal(MotionEvent.ACTION_UP, x, y, displayId, now, now + durationMs, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    override fun injectSwipe(displayId: Int, sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long) {
        if (reflectionBroken) return
        val now = SystemClock.uptimeMillis()
        injectInternal(MotionEvent.ACTION_DOWN, sx, sy, displayId, now, now, InputDevice.SOURCE_TOUCHSCREEN, 0)
        val steps = (durationMs / 16L).coerceAtLeast(2L)
        for (i in 1..steps) {
            val t = i.toFloat() / steps.toFloat()
            val mx = sx + (ex - sx) * t; val my = sy + (ey - sy) * t
            val tEvt = now + (durationMs * i / steps)
            injectInternal(MotionEvent.ACTION_MOVE, mx, my, displayId, now, tEvt, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { Thread.sleep(durationMs / steps) } catch (_: InterruptedException) { break }
        }
        injectInternal(MotionEvent.ACTION_UP, ex, ey, displayId, now, now + durationMs, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun setDisplayId(event: InputEvent, displayId: Int) {
        if (displayId == 0) return
        try {
            when (event) {
                is MotionEvent -> setMotionDisplayId?.invoke(event, displayId)
                is KeyEvent -> setKeyDisplayId?.invoke(event, displayId)
            }
        } catch (_: Throwable) {}
    }

    // ─── IME policy ─────────────────────────────────────────────────────

    override fun setDisplayImePolicy(displayId: Int, policy: Int): Boolean {
        return try {
            val wm = getWindowManagerService() ?: return false
            val m = wm.javaClass.methods.firstOrNull {
                it.name == "setDisplayImePolicy" && it.parameterCount == 2
            }
            m?.invoke(wm, displayId, policy)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setDisplayImePolicy failed", t); false
        }
    }

    private fun getWindowManagerService(): Any? = try {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "window") as? IBinder
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        binder?.let { stub.getMethod("asInterface", IBinder::class.java).invoke(null, it) }
    } catch (t: Throwable) { null }

    private fun getInputMethodManagerService(): Any? = try {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java)
            .invoke(null, "input_method") as? IBinder
        val stub = Class.forName("com.android.internal.view.IInputMethodManager\$Stub")
        binder?.let { stub.getMethod("asInterface", IBinder::class.java).invoke(null, it) }
    } catch (t: Throwable) {
        Log.w(TAG, "getInputMethodManagerService failed", t); null
    }

    /**
     * Force the soft keyboard to show on the currently focused editor. Used in
     * phone-as-keyboard mode: routing now resolves the IME to display 0, but the
     * focused field may not auto-raise it (we saw STATE_ALWAYS_HIDDEN). This asks
     * the system to show it explicitly.
     *
     * Reachability is genuinely uncertain: showSoftInput is normally restricted to
     * the focused client/IME, and its signature varies a lot across Android
     * versions. We grab the focused window token from IWindowManager, then try the
     * known showSoftInput shapes on IInputMethodManager, logging the available
     * signatures so an on-device run reveals the exact one. Returns true only if a
     * call completed without throwing.
     */
    override fun showImeOnFocusedEditor(): Boolean {
        return try {
            val imm = getInputMethodManagerService() ?: run {
                Log.w(TAG, "showIme: no IInputMethodManager"); return false
            }
            // Best-effort: focused window token (some One UI builds expose this).
            val wm = getWindowManagerService()
            val windowToken: IBinder? = wm?.let { svc ->
                listOf("getFocusedWindowToken", "getFocusedWindowTokenFromWindow").firstNotNullOfOrNull { name ->
                    runCatching {
                        svc.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                            ?.invoke(svc) as? IBinder
                    }.getOrNull()
                }
            }
            Log.d(TAG, "showIme: windowToken=${if (windowToken != null) "present" else "null"}")

            val showMethods = imm.javaClass.methods.filter { it.name == "showSoftInput" }
            if (showMethods.isEmpty()) {
                Log.w(TAG, "showIme: no showSoftInput method on IInputMethodManager. Methods: " +
                    imm.javaClass.methods.filter { it.name.contains("show", true) || it.name.contains("Input") }
                        .joinToString { it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")" })
                return false
            }
            for (m in showMethods) {
                val types = m.parameterTypes
                Log.d(TAG, "showIme: trying showSoftInput(${types.joinToString { it.simpleName }})")
                val args = arrayOfNulls<Any?>(types.size)
                for (i in types.indices) {
                    val t = types[i]
                    args[i] = when {
                        t == IBinder::class.java -> windowToken          // windowToken slot(s)
                        t == Int::class.javaPrimitiveType -> 0           // flags / toolType / reason
                        else -> null                                     // client, statsToken, resultReceiver
                    }
                }
                val ok = runCatching { m.invoke(imm, *args) }.fold(
                    onSuccess = { true },
                    onFailure = { Log.w(TAG, "showIme: invoke failed", it); false },
                )
                if (ok) { Log.i(TAG, "showIme: showSoftInput call completed"); return true }
            }
            false
        } catch (t: Throwable) {
            Log.w(TAG, "showImeOnFocusedEditor failed", t); false
        }
    }

    // ─── Display system-decoration promotion (experimental) ─────────────

    /**
     * Enable system decorations (home/IME/wallpaper) + cross-app launching on
     * [displayId]. For the experimental app-owned VD path: the app creates a
     * plain (non-trusted) VirtualDisplay in its own process, then asks us —
     * the privileged process — to promote it once. Unlike a TRUSTED flag (set
     * only at creation, requires shell uid), system decorations can be toggled
     * on an existing display via IWindowManager.setShouldShowSystemDecors,
     * which needs INTERNAL_SYSTEM_WINDOW (i.e. this shell/system process).
     */
    override fun setShouldShowSystemDecors(displayId: Int, shouldShow: Boolean): Boolean {
        return try {
            val wm = getWindowManagerService() ?: return false
            val m = wm.javaClass.methods.firstOrNull {
                it.name == "setShouldShowSystemDecors" && it.parameterCount == 2
            }
            m?.invoke(wm, displayId, shouldShow)
            Log.d(TAG, "setShouldShowSystemDecors($displayId, $shouldShow) ok=${m != null}")
            m != null
        } catch (t: Throwable) {
            Log.w(TAG, "setShouldShowSystemDecors failed", t); false
        }
    }

    // ─── Virtual display lifecycle ──────────────────────────────────────

    /**
     * Lazily obtain a system-uid context using ActivityThread reflection.
     *
     * Background: when our user service calls
     * `DisplayManager.createVirtualDisplay()` using the "app" context
     * (`Service.getApplicationContext()`), the framework rejects it with
     *   `SecurityException: packageName must match the calling uid`
     * because the app context's packageName is "com.portalpad.app" (which
     * is owned by our app's uid), but the call arrives at the framework
     * from shell uid (2000). The framework's safety check fails the
     * mismatch.
     *
     * the reference implementation works around this by obtaining a **system context** through
     * ActivityThread reflection. The system context's packageName is
     * "android" which the framework accepts for any privileged uid (root,
     * shell, system). With it, createVirtualDisplay passes the uid check.
     *
     * Two ActivityThread methods can give us this:
     *   1. `ActivityThread.currentActivityThread()` — returns the cached
     *      thread instance for the current process. Cheap. Does NOT create
     *      any Handler internally, so it works from any thread, including
     *      Binder threads without a Looper. This is the path we prefer.
     *   2. `ActivityThread.systemMain()` — creates a new ActivityThread
     *      and a Handler bound to the current thread's Looper. If the
     *      thread has no Looper (most Binder threads), this throws
     *      `Can't create handler inside thread ... that has not called
     *      Looper.prepare()`. We only fall back to this if (1) fails,
     *      and we do it via the main looper.
     */
    @Volatile private var systemCtx: Context? = null
    private fun obtainSystemContext(): Context? {
        systemCtx?.let { return it }

        // Path 1: currentActivityThread — works on any thread, no Looper needed.
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val current = atClass.getMethod("currentActivityThread").invoke(null)
            if (current != null) {
                val ctx = atClass.getMethod("getSystemContext").invoke(current) as? Context
                if (ctx != null) {
                    systemCtx = ctx
                    Log.d(TAG, "obtained system context via ActivityThread.currentActivityThread()")
                    return ctx
                }
            }
        }.onFailure {
            Log.w(TAG, "currentActivityThread path failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // Path 2: systemMain — must be called on a thread with a Looper.
        // Use the main Looper. Block briefly to receive the result.
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val mainLooper = android.os.Looper.getMainLooper()
            val handler = android.os.Handler(mainLooper)
            val latch = java.util.concurrent.CountDownLatch(1)
            val result = AtomicReference<Context?>(null)
            val error = AtomicReference<Throwable?>(null)
            handler.post {
                try {
                    val thread = atClass.getMethod("systemMain").invoke(null)
                    if (thread != null) {
                        val ctx = atClass.getMethod("getSystemContext").invoke(thread) as? Context
                        result.set(ctx)
                    }
                } catch (t: Throwable) {
                    error.set(t)
                } finally {
                    latch.countDown()
                }
            }
            if (latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                error.get()?.let { throw it }
                result.get()?.let {
                    systemCtx = it
                    Log.d(TAG, "obtained system context via ActivityThread.systemMain() on main looper")
                    return it
                }
            } else {
                Log.w(TAG, "systemMain path: main-looper post timed out after 2s")
            }
        }.onFailure {
            Log.e(TAG, "systemMain path failed", it)
        }

        return null
    }

    // Experimental high-refresh VD toggle. true = try the VirtualDisplayConfig
    // path (which can request the panel's max refresh) FIRST; false = original
    // behaviour only. Flip to false to instantly revert if the trusted-VD wrap
    // regresses (IME on display, volume rocker, SmartTube, recording).
    private val REQUEST_HIGH_REFRESH_VD = false

    /**
     * EXPERIMENTAL high-refresh VD path. Same shell-uid context and same [flags] as
     * [tryCreateVirtualDisplaySystemContext], but constructs the VD via
     * VirtualDisplayConfig so it can call setRequestedRefreshRate(). The app-owned
     * VD is otherwise created fixed at 60 Hz (dumpsys: "PortalPad Session" fps=60.0,
     * supportedRefreshRates []), which caps the whole chain — the external panel can
     * only present what the 60 Hz VD produces, so no amount of panel-side coaxing
     * gets past 60. Requesting the panel's max rate (120 on the One Pro) lets the VD
     * feed 120 frames so the panel's 120 mode can actually engage.
     *
     * Returns -1 on ANY failure, OR when the display exposes no mode above 60 at this
     * resolution — so the caller falls through to the proven 6-arg path and existing
     * behaviour is the guaranteed fallback. The VirtualDisplayConfig path MAY treat
     * flags differently from the 6-arg API (the documented reason it isn't already
     * the default), so the wrap must be regression-tested before this is trusted.
     */
    private fun tryCreateVirtualDisplayHighRefresh(
        name: String, width: Int, height: Int, densityDpi: Int, flags: Int, surface: Surface?,
    ): Int {
        if (!REQUEST_HIGH_REFRESH_VD) return -1
        if (surface == null) return -1
        return try {
            val sysCtx = obtainSystemContext() ?: return -1
            val shellCtx = createContextForCallingUid(sysCtx) ?: return -1
            val dm = shellCtx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return -1
            // Highest refresh the external panel offers at THIS resolution. The
            // internal panel is a different resolution so it won't match; a 60-only
            // link (or SBS canvas with no >60 mode) yields nothing and we fall through.
            val target = dm.displays
                .flatMap { it.supportedModes.asList() }
                .filter { it.physicalWidth == width && it.physicalHeight == height }
                .maxOfOrNull { it.refreshRate } ?: 0f
            if (target <= 60.5f) {
                Log.d(TAG, "HighRefresh: no >60 mode at ${width}x$height; falling through to proven path")
                return -1
            }
            val config = android.hardware.display.VirtualDisplayConfig.Builder(name, width, height, densityDpi)
                .setFlags(flags)
                .setSurface(surface)
                .setRequestedRefreshRate(target)
                .build()
            val vd = dm.createVirtualDisplay(config) ?: run {
                Log.w(TAG, "HighRefresh: createVirtualDisplay(config) returned null; falling through")
                return -1
            }
            val vdId = vd.display.displayId
            virtualDisplays[vdId] = vd
            Log.d(TAG, "createVirtualDisplay [path=HighRefresh pkg=${shellCtx.opPackageName}] '$name' " +
                "id=$vdId flags=0x${flags.toString(16)} requestedRefreshRate=${"%.1f".format(target)}")
            vdId
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            Log.w(TAG, "HighRefresh threw (falling through to proven path): ${cause.javaClass.simpleName}: ${cause.message}")
            -1
        }
    }

    override fun createVirtualDisplay(
        name: String, width: Int, height: Int, densityDpi: Int, flags: Int, surface: Surface?,
    ): Int {
        val id = Binder.clearCallingIdentity()
        return try {
            // Multi-tier strategy. Order updated v0.17.42 — try the reference implementation's
            // exact path FIRST. Their reference behavior shows they:
            //
            //   1. Get systemContext via ActivityThread (we have helper)
            //   2. Call createContextForCallingUid(systemContext) — a
            //      private helper that does `context.createPackageContext(
            //      pm.getPackagesForUid(Process.myUid())[0], 0x2)` with
            //      0x2 = Context.CONTEXT_IGNORE_SECURITY. The resulting
            //      Context's getOpPackageName() reports "com.android.shell"
            //      authoritatively (not just a wrapper claiming it).
            //   3. Call ctx.getSystemService("display") on THAT context to
            //      get a DisplayManager bound to shell uid context.
            //   4. Call dm.createVirtualDisplay(name, w, h, dpi, surface,
            //      flags) — the PUBLIC 6-arg API. No reflection.
            //
            // This is simpler and might behave differently from the
            // DisplayManagerGlobal reflection path because the resulting
            // VD is registered through the standard public API path with
            // proper context binding — closer to how a real shell-uid
            // app would create a display.
            // Tier 0 (experimental, gated by REQUEST_HIGH_REFRESH_VD): build the VD
            // via VirtualDisplayConfig so we can request the panel's max refresh —
            // the app-owned VD is otherwise pinned at 60 Hz, capping the whole chain.
            // Returns -1 (falls through) on any failure or a 60-only display.
            val rHi = tryCreateVirtualDisplayHighRefresh(name, width, height, densityDpi, flags, surface)
            if (rHi >= 0) return rHi

            val r0 = tryCreateVirtualDisplaySystemContext(name, width, height, densityDpi, flags, surface)
            if (r0 >= 0) return r0

            // Try #1 & #2: hidden DMG paths with shell packageName
            val r1 = tryCreateVirtualDisplayViaDMG(name, width, height, densityDpi, flags, surface)
            if (r1 >= 0) return r1

            val sysCtx = obtainSystemContext()
            val appCtx = context

            // Try #3: public DisplayManager via system context
            sysCtx?.let { ctx ->
                val r = tryCreateVirtualDisplay(ctx, "system", name, width, height, densityDpi, flags, surface)
                if (r >= 0) return r
            }

            // Try #4: public DisplayManager via app context
            appCtx?.let { ctx ->
                val r = tryCreateVirtualDisplay(ctx, "app", name, width, height, densityDpi, flags, surface)
                if (r >= 0) return r
            }

            -1
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    /**
     * system-context direct VD creation. Derived from reference behavior
     * `ShellUserService.createVirtualDisplay` (line 8298 onward).
     *
     * Approach:
     *   1. systemCtx = ActivityThread.currentActivityThread().getSystemContext()
     *   2. shellCtx = createContextForCallingUid(systemCtx)
     *        — uses Context.createPackageContext with the FIRST package
     *          owned by Process.myUid() (shell uid 2000 → "com.android.shell"),
     *          flags = Context.CONTEXT_IGNORE_SECURITY (0x2).
     *   3. dm = shellCtx.getSystemService("display") as DisplayManager
     *   4. dm.createVirtualDisplay(name, w, h, dpi, surface, flags)
     *
     * Difference from `tryCreateVirtualDisplayViaDMG`: that method
     * reflects into DisplayManagerGlobal and constructs a VirtualDisplayConfig
     * with a packageName string. This method goes through the standard
     * PUBLIC API path with a context whose packageName resolves to
     * com.android.shell naturally — no manual packageName-passing. The
     * VirtualDisplayConfig path may silently transform flags or drop fields
     * the public API path preserves.
     */
    private fun tryCreateVirtualDisplaySystemContext(
        name: String, width: Int, height: Int, densityDpi: Int, flags: Int, surface: Surface?,
    ): Int {
        return try {
            val sysCtx = obtainSystemContext() ?: run {
                Log.d(TAG, "SystemContext: no system context")
                return -1
            }
            val shellCtx = createContextForCallingUid(sysCtx) ?: run {
                Log.d(TAG, "SystemContext: createContextForCallingUid returned null")
                return -1
            }
            val dm = shellCtx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: run {
                Log.d(TAG, "SystemContext: getSystemService(display) didn't return DisplayManager")
                return -1
            }
            val vd = dm.createVirtualDisplay(name, width, height, densityDpi, surface, flags)
                ?: run {
                    Log.w(TAG, "SystemContext: createVirtualDisplay returned null")
                    return -1
                }
            val vdId = vd.display.displayId
            virtualDisplays[vdId] = vd
            Log.d(TAG, "createVirtualDisplay [path=SystemContext pkg=${shellCtx.opPackageName}] '$name' id=$vdId flags=0x${flags.toString(16)}")
            vdId
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            Log.w(TAG, "SystemContext threw: ${cause.javaClass.simpleName}: ${cause.message}")
            -1
        }
    }

    /**
     * a system-context `createContextForCallingUid(Context)` approach in Kotlin.
     * Returns a context bound to the actual package(s) owned by
     * Process.myUid() — for shell uid (2000), that's `com.android.shell`.
     *
     *   pm.getPackagesForUid(Process.myUid()) → ["com.android.shell", ...]
     *   for pkg in packages:
     *     try return context.createPackageContext(pkg, CONTEXT_IGNORE_SECURITY)
     *   return null
     */
    private fun createContextForCallingUid(context: Context): Context? {
        return try {
            val pm = context.packageManager ?: return null
            val myUid = Process.myUid()
            val pkgs = pm.getPackagesForUid(myUid) ?: return null
            if (pkgs.isEmpty()) return null
            for (pkg in pkgs) {
                try {
                    return context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
                } catch (_: Throwable) {
                    // try next package
                }
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "createContextForCallingUid failed: ${t.message}")
            null
        }
    }

    /**
     * Reflectively call `DisplayManagerGlobal.createVirtualDisplay(...)`
     * with `packageName = "com.android.shell"`. This is the only way to
     * create a VirtualDisplay from shell uid that the system accepts —
     * passing the package the calling uid actually owns.
     *
     * Tries the Android 14+ signature first (Context, MediaProjection,
     * VirtualDisplayConfig, Callback, Handler), then falls back to older
     * signatures.
     *
     * Heavy reflection but matches scrcpy's well-tested approach.
     */
    private fun tryCreateVirtualDisplayViaDMG(
        name: String, width: Int, height: Int, densityDpi: Int, flags: Int, surface: Surface?,
    ): Int {
        val shellPkg = "com.android.shell"
        return try {
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmg = dmgClass.getMethod("getInstance").invoke(null)
                ?: run {
                    Log.w(TAG, "DMG getInstance returned null")
                    return -1
                }

            // Diagnostic: log every createVirtualDisplay method this DMG
            // class exposes so we can see the actual signature on this
            // device. Samsung One UI 7 / Android 16 may have a custom
            // overload that doesn't match any AOSP version we know about.
            dmgClass.declaredMethods
                .filter { it.name == "createVirtualDisplay" }
                .forEachIndexed { i, m ->
                    val params = m.parameterTypes.joinToString(",") { it.simpleName }
                    Log.d(TAG, "DMG.createVirtualDisplay[$i] signature: ($params) -> ${m.returnType.simpleName}")
                }

            // Build the VirtualDisplayConfig via its Builder (modern API).
            val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val builderCtor = builderClass.getConstructor(
                String::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            val builder = builderCtor.newInstance(name, width, height, densityDpi)
            builderClass.getMethod("setFlags", Int::class.javaPrimitiveType)
                .invoke(builder, flags)
            if (surface != null) {
                builderClass.getMethod("setSurface", Surface::class.java)
                    .invoke(builder, surface)
            }
            val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val config = builderClass.getMethod("build").invoke(builder)

            val ctx = obtainSystemContext() ?: context
            if (ctx == null) {
                Log.w(TAG, "DMG path: no context available")
                return -1
            }

            val callbackClass = Class.forName("android.hardware.display.VirtualDisplay\$Callback")
            val mediaProjectionClass = Class.forName("android.media.projection.MediaProjection")
            val handlerClass = Class.forName("android.os.Handler")

            // Generic adapter: find ANY createVirtualDisplay method that takes
            // VirtualDisplayConfig as one of its args, then build the arg list
            // dynamically based on its parameter types.
            //
            // Common parameter shapes we know how to satisfy:
            //   - Context → ctx (or shellCtx if it's the only string-relevant arg)
            //   - MediaProjection → null
            //   - VirtualDisplayConfig → config
            //   - VirtualDisplay.Callback → null
            //   - Handler → null
            //   - String (last position) → shellPkg
            //   - int (uid hint, rare) → Process.myUid()
            val shellCtx = runCatching { ctx.createPackageContext(shellPkg, 0) }
                .onFailure { Log.w(TAG, "createPackageContext('$shellPkg') failed: ${it.message}") }
                .getOrNull() ?: ctx

            val candidates = dmgClass.declaredMethods.filter {
                it.name == "createVirtualDisplay" &&
                    it.parameterTypes.any { p -> p == configClass }
            }
            for (m in candidates) {
                val args = m.parameterTypes.map { p ->
                    when (p) {
                        Context::class.java -> shellCtx
                        mediaProjectionClass -> null
                        configClass -> config
                        callbackClass -> null
                        handlerClass -> null
                        String::class.java -> shellPkg
                        Int::class.javaPrimitiveType -> Process.myUid()
                        else -> {
                            Log.w(TAG, "DMG: unknown param type ${p.simpleName} for createVirtualDisplay; passing null")
                            null
                        }
                    }
                }
                val sigStr = m.parameterTypes.joinToString(",") { it.simpleName }
                try {
                    m.isAccessible = true
                    val vd = m.invoke(dmg, *args.toTypedArray()) as? VirtualDisplay
                    if (vd != null) {
                        val vdId = vd.display.displayId
                        virtualDisplays[vdId] = vd
                        Log.d(TAG, "createVirtualDisplay [path=DMG sig=($sigStr) pkg=$shellPkg] '$name' id=$vdId")
                        return vdId
                    }
                    Log.w(TAG, "DMG sig=($sigStr) returned null for '$name'")
                } catch (t: Throwable) {
                    val cause = t.cause ?: t
                    Log.w(TAG, "DMG sig=($sigStr) threw: ${cause.javaClass.simpleName}: ${cause.message}")
                }
            }
            if (candidates.isEmpty()) {
                Log.w(TAG, "DMG: no createVirtualDisplay method takes VirtualDisplayConfig — check signature dump above")
            }

            -1
        } catch (t: Throwable) {
            Log.e(TAG, "tryCreateVirtualDisplayViaDMG threw at top level", t)
            -1
        }
    }

    private fun tryCreateVirtualDisplay(
        ctx: Context, ctxLabel: String,
        name: String, width: Int, height: Int, densityDpi: Int, flags: Int, surface: Surface?,
    ): Int {
        return try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val vd = dm.createVirtualDisplay(name, width, height, densityDpi, surface, flags)
            if (vd == null) {
                Log.w(TAG, "createVirtualDisplay [ctx=$ctxLabel] '$name' returned null (no exception)")
                return -1
            }
            val vdId = vd.display.displayId
            virtualDisplays[vdId] = vd
            Log.d(TAG, "createVirtualDisplay [ctx=$ctxLabel] '$name' id=$vdId ${width}x$height dpi=$densityDpi flags=0x${flags.toString(16)}")
            vdId
        } catch (t: Throwable) {
            Log.e(TAG, "createVirtualDisplay [ctx=$ctxLabel] '$name' threw: ${t.javaClass.simpleName}: ${t.message}", t)
            -1
        }
    }

    override fun resizeVirtualDisplay(virtualDisplayId: Int, width: Int, height: Int, densityDpi: Int): Boolean {
        val id = Binder.clearCallingIdentity()
        return try {
            val vd = virtualDisplays[virtualDisplayId] ?: return false
            vd.resize(width, height, densityDpi); true
        } catch (t: Throwable) {
            Log.e(TAG, "resizeVirtualDisplay failed: id=$virtualDisplayId", t); false
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    override fun setVirtualDisplaySurface(virtualDisplayId: Int, surface: Surface?): Boolean {
        val id = Binder.clearCallingIdentity()
        return try {
            val vd = virtualDisplays[virtualDisplayId] ?: return false
            vd.surface = surface; true
        } catch (t: Throwable) {
            Log.e(TAG, "setVirtualDisplaySurface failed: id=$virtualDisplayId", t); false
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    override fun releaseVirtualDisplay(virtualDisplayId: Int): Boolean {
        val id = Binder.clearCallingIdentity()
        return try {
            virtualDisplays.remove(virtualDisplayId)?.let { it.release(); return true }
            false
        } catch (t: Throwable) {
            Log.e(TAG, "releaseVirtualDisplay failed: id=$virtualDisplayId", t); false
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    // ─── EXPERIMENTAL: per-display color transform (color spike) ────────
    override fun setDisplayColorTransform(displayId: Int, matrix: FloatArray?): String {
        val id = Binder.clearCallingIdentity()
        return try {
            if (matrix == null || matrix.size != 16) {
                return "FAIL: matrix must be 16 floats (got ${matrix?.size})"
            }
            // Resolve the display's SurfaceControl token. SurfaceControl has a
            // hidden getPhysicalDisplayToken(long physicalDisplayId) and an
            // older getBuiltInDisplay/getInternalDisplayToken; physical display
            // ids map from the logical displayId via DisplayManager → but the
            // simplest reachable path is SurfaceControl.getPhysicalDisplayIds()
            // then getPhysicalDisplayToken(). We try a few signatures.
            val sc = Class.forName("android.view.SurfaceControl")
            val log = StringBuilder()

            // Find a display token.
            var token: android.os.IBinder? = null
            runCatching {
                val ids = sc.getMethod("getPhysicalDisplayIds").invoke(null) as? LongArray
                log.append("physicalIds=${ids?.toList()}; ")
                // Heuristic: the external (glasses) is usually the non-zero /
                // second id. Try each until one accepts the transform.
                if (ids != null && ids.isNotEmpty()) {
                    val getToken = sc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                    // Prefer the LAST id (external tends to enumerate after internal).
                    for (pid in ids.reversed()) {
                        val t = getToken.invoke(null, pid) as? android.os.IBinder
                        if (t != null) {
                            token = t
                            log.append("usingPid=$pid; ")
                            break
                        }
                    }
                }
            }.onFailure { log.append("tokenLookup:${it.javaClass.simpleName}; ") }

            if (token == null) {
                return "FAIL: no display token. [$log]"
            }

            // Apply the color matrix. The exact API varies by Android version,
            // so try several known shapes and, if none work, dump the available
            // SurfaceControl method names so we can see what this build exposes.
            var applied = false

            // Attempt 1: static SurfaceControl.setDisplayColorTransform(IBinder, float[])
            if (!applied) runCatching {
                sc.getMethod("setDisplayColorTransform", android.os.IBinder::class.java, FloatArray::class.java)
                    .invoke(null, token, matrix)
                applied = true; log.append("path=static-token; ")
            }.onFailure { log.append("a1:${it.javaClass.simpleName}; ") }

            // Attempt 2: SurfaceControl.Transaction().setDisplayColorTransform(IBinder, float[]).apply()
            if (!applied) runCatching {
                val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
                val tx = txClass.getConstructor().newInstance()
                val m = txClass.getMethod("setDisplayColorTransform", android.os.IBinder::class.java, FloatArray::class.java)
                m.invoke(tx, token, matrix)
                txClass.getMethod("apply").invoke(tx)
                applied = true; log.append("path=tx-token; ")
            }.onFailure { log.append("a2:${it.javaClass.simpleName}; ") }

            // Attempt 3: DisplayTransformManager.setColorMatrix(level, float[]) — GLOBAL.
            // Only used as a last resort; flagged clearly because it tints the
            // whole device, not just the glasses.
            if (!applied) runCatching {
                val dtmClass = Class.forName("com.android.server.display.color.DisplayTransformManager")
                // DTM is a system-server local service; not reachable from our
                // process. This will almost certainly fail — we attempt it only
                // to record the exception for the log.
                val inst = Class.forName("com.android.server.LocalServices")
                    .getMethod("getService", Class::class.java).invoke(null, dtmClass)
                if (inst != null) {
                    dtmClass.getMethod("setColorMatrix", Int::class.javaPrimitiveType, FloatArray::class.java)
                        .invoke(inst, 300, matrix)
                    applied = true; log.append("path=dtm-GLOBAL; ")
                } else {
                    log.append("a3:dtm-null; ")
                }
            }.onFailure { log.append("a3:${it.javaClass.simpleName}; ") }

            // If still nothing, dump the SurfaceControl method names that mention
            // "color" or "transform" so we can see what THIS build actually has.
            if (!applied) {
                val names = sc.declaredMethods
                    .map { it.name }
                    .filter { it.contains("olor", true) || it.contains("ransform", true) }
                    .distinct()
                log.append("scMethods=$names; ")
                val txNames = runCatching {
                    Class.forName("android.view.SurfaceControl\$Transaction").declaredMethods
                        .map { it.name }
                        .filter { it.contains("olor", true) || it.contains("ransform", true) }
                        .distinct()
                }.getOrDefault(emptyList())
                log.append("txMethods=$txNames; ")
            }

            if (applied) "OK: color transform applied. [$log]"
            else "FAIL: could not apply per-display transform. [$log]"
        } catch (t: Throwable) {
            "EXCEPTION: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    // ─── Color mode probe + setter (per-display, real panel modes) ──────
    override fun getDisplayColorModes(displayId: Int): String {
        val id = Binder.clearCallingIdentity()
        return try {
            val ctx = obtainSystemContext() ?: context
                ?: return "FAIL: no context"
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(displayId)
                ?: return "FAIL: no display id=$displayId"
            val log = StringBuilder()
            // Display.getSupportedColorModes(): int[] (public since API 26).
            val supported = runCatching {
                (display.javaClass.getMethod("getSupportedColorModes").invoke(display) as? IntArray)
            }.getOrNull()
            val active = runCatching {
                display.javaClass.getMethod("getColorMode").invoke(display) as? Int
            }.getOrNull()
            log.append("supported=${supported?.toList()}; active=$active; ")
            // Also dump physical display tokens so the setter can target the panel.
            val phys = runCatching {
                Class.forName("android.view.SurfaceControl")
                    .getMethod("getPhysicalDisplayIds").invoke(null) as? LongArray
            }.getOrNull()
            log.append("physicalIds=${phys?.toList()}; ")
            val count = supported?.size ?: 0
            if (count > 1) "OK: $count color modes available. [$log]"
            else "ONLY-ONE: display reports $count color mode(s) — no switchable color. [$log]"
        } catch (t: Throwable) {
            "EXCEPTION: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    override fun setDisplayColorMode(displayId: Int, colorMode: Int): String {
        val id = Binder.clearCallingIdentity()
        return try {
            val log = StringBuilder()
            var applied = false
            // Path 1: WindowManager hidden setActiveColorMode hooks are not in
            // our process; the reachable path is SurfaceControl static
            // setDisplayColorMode/setActiveColorMode(IBinder token, int mode).
            val sc = Class.forName("android.view.SurfaceControl")
            var token: android.os.IBinder? = null
            runCatching {
                val ids = sc.getMethod("getPhysicalDisplayIds").invoke(null) as? LongArray
                if (ids != null && ids.isNotEmpty()) {
                    val getToken = sc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                    for (pid in ids.reversed()) {
                        val t = getToken.invoke(null, pid) as? android.os.IBinder
                        if (t != null) { token = t; log.append("usingPid=$pid; "); break }
                    }
                }
            }
            if (token != null) {
                for (name in listOf("setDisplayColorMode", "setActiveColorMode")) {
                    if (applied) break
                    runCatching {
                        sc.getMethod(name, android.os.IBinder::class.java, Int::class.javaPrimitiveType)
                            .invoke(null, token, colorMode)
                        applied = true; log.append("path=$name; ")
                    }.onFailure { log.append("$name:${it.javaClass.simpleName}; ") }
                }
            } else {
                log.append("no-token; ")
            }
            if (applied) "OK: color mode set to $colorMode. [$log]"
            else "FAIL: could not set color mode. [$log]"
        } catch (t: Throwable) {
            "EXCEPTION: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    // ─── SPIKE #3: privileged-process layer color transform ─────────────
    override fun setLayerColorTransform(layer: android.view.SurfaceControl?, matrix12: FloatArray?): String {
        val id = Binder.clearCallingIdentity()
        return try {
            if (layer == null) return "FAIL: null layer (parcel didn't carry the SurfaceControl)"
            if (matrix12 == null || matrix12.size != 12) return "FAIL: matrix must be 12 floats (got ${matrix12?.size})"
            if (!layer.isValid) return "FAIL: layer invalid in privileged process (valid=${layer.isValid})"
            val m3 = floatArrayOf(
                matrix12[0], matrix12[1], matrix12[2],
                matrix12[3], matrix12[4], matrix12[5],
                matrix12[6], matrix12[7], matrix12[8],
            )
            val v3 = floatArrayOf(matrix12[9], matrix12[10], matrix12[11])
            val log = StringBuilder()
            log.append("valid=${layer.isValid}; ")
            val tx = android.view.SurfaceControl.Transaction()
            var applied = false
            runCatching {
                android.view.SurfaceControl.Transaction::class.java.getMethod(
                    "setColorTransform",
                    android.view.SurfaceControl::class.java,
                    FloatArray::class.java, FloatArray::class.java,
                ).invoke(tx, layer, m3, v3)
                applied = true; log.append("setColorTransform-ok; ")
            }.onFailure { log.append("setColorTransform:${it.javaClass.simpleName}; ") }
            if (applied) {
                tx.apply()
                "OK(priv): applied from privileged process. [$log]"
            } else {
                tx.close()
                "FAIL(priv): transform not applied. [$log]"
            }
        } catch (t: Throwable) {
            "EXCEPTION(priv): ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    // ─── Surface mirror: glasses → our Surface ──────────────────────────

    override fun startSurfaceMirror(physicalDisplayId: Int, surface: Surface?): Boolean {
        if (surface == null) return false
        val identity = Binder.clearCallingIdentity()
        try {
            val info = getDisplayInfo(physicalDisplayId) ?: return false
            val w = info.width
            val h = info.height
            releaseOtherSurfaceMirrors(physicalDisplayId)

            mirrorVirtualDisplays[physicalDisplayId]?.let { existing ->
                existing.surface = surface
                Log.d(TAG, "startSurfaceMirror: updated surface for displayId=$physicalDisplayId")
                return true
            }

            // Path A: DisplayManager.createVirtualDisplay with the physical
            // display id passed in — Android internally treats this as a
            // mirror. the reference's preferred path.
            val mirrorMethod = getDisplayManagerMirrorMethod()
            if (mirrorMethod != null) {
                try {
                    val result = mirrorMethod.invoke(
                        null, "PortalPadMirror", w, h, physicalDisplayId, surface,
                    ) as? VirtualDisplay
                    if (result != null) {
                        mirrorVirtualDisplays[physicalDisplayId] = result
                        surfaceMirrorTokens.remove(physicalDisplayId)?.let { destroySurfaceControlDisplay(it) }
                        Log.d(TAG, "startSurfaceMirror: DisplayManager path ok displayId=$physicalDisplayId")
                        return true
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "DisplayManager mirror threw, trying SurfaceControl", t)
                }
            }

            // Path B: SurfaceControl reflection fallback
            Log.w(TAG, "DisplayManager mirror not available; SurfaceControl fallback displayId=$physicalDisplayId")
            var token = surfaceMirrorTokens[physicalDisplayId]
            if (token == null) token = createSurfaceControlDisplay()
            if (token != null) {
                val srcRect = Rect(0, 0, w, h); val dstRect = Rect(0, 0, w, h)
                try {
                    openSurfaceControlTransaction()
                    try {
                        setSurfaceControlDisplaySurface(token, surface)
                        setSurfaceControlDisplayProjection(token, 0, srcRect, dstRect)
                        setSurfaceControlDisplayLayerStack(token, info.layerStack)
                    } finally { closeSurfaceControlTransaction() }
                    surfaceMirrorTokens[physicalDisplayId] = token
                    Log.d(TAG, "startSurfaceMirror: SurfaceControl path ok displayId=$physicalDisplayId")
                    return true
                } catch (t: Throwable) {
                    Log.w(TAG, "SurfaceControl apply failed displayId=$physicalDisplayId", t)
                    surfaceMirrorTokens.remove(physicalDisplayId)?.let { destroySurfaceControlDisplay(it) }
                }
            } else {
                Log.w(TAG, "SurfaceControl display not created; mirror failed displayId=$physicalDisplayId")
            }
            return false
        } catch (t: Throwable) {
            Log.e(TAG, "startSurfaceMirror failed: displayId=$physicalDisplayId", t)
            return false
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun stopSurfaceMirror(physicalDisplayId: Int) {
        val id = Binder.clearCallingIdentity()
        try {
            mirrorVirtualDisplays.remove(physicalDisplayId)?.runCatching { release() }
            stopSurfaceMirrorInternal(physicalDisplayId)
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    private fun stopSurfaceMirrorInternal(physicalDisplayId: Int) {
        surfaceMirrorTokens.remove(physicalDisplayId)?.let { token ->
            runCatching { destroySurfaceControlDisplay(token) }
        }
    }

    private fun releaseOtherSurfaceMirrors(keepId: Int) {
        val toRelease = mirrorVirtualDisplays.keys.filter { it != keepId }
        for (key in toRelease) mirrorVirtualDisplays.remove(key)?.runCatching { release() }
        val tokensToRelease = surfaceMirrorTokens.keys.filter { it != keepId }
        for (key in tokensToRelease) stopSurfaceMirrorInternal(key)
    }

    // ─── DisplayManager + SurfaceControl reflection ─────────────────────

    private fun getDisplayManagerMirrorMethod(): Method? {
        dmCreateMirrorMethod?.let { return it }
        return try {
            // 5-arg overload: (String, int, int, int, Surface) — name,w,h,sourceDisplayId,sinkSurface
            val m = DisplayManager::class.java.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java,
            )
            dmCreateMirrorMethod = m; m
        } catch (_: Throwable) { null }
    }

    private data class DisplayInfo(val width: Int, val height: Int, val rotation: Int, val layerStack: Int)

    private fun getDisplayInfo(displayId: Int): DisplayInfo? {
        return try {
            val dmg = getDisplayManagerGlobal() ?: return null
            val m = getDisplayInfoMethod
                ?: dmg.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                    .also { getDisplayInfoMethod = it }
            val info = m.invoke(dmg, displayId) ?: return null
            val cls = info.javaClass
            DisplayInfo(
                cls.getDeclaredField("logicalWidth").getInt(info),
                cls.getDeclaredField("logicalHeight").getInt(info),
                cls.getDeclaredField("rotation").getInt(info),
                cls.getDeclaredField("layerStack").getInt(info),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "getDisplayInfo failed: id=$displayId", t); null
        }
    }

    private fun getDisplayManagerGlobal(): Any? {
        displayManagerGlobal?.let { return it }
        return try {
            val c = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val obj = c.getDeclaredMethod("getInstance").invoke(null)
            displayManagerGlobal = obj; obj
        } catch (t: Throwable) {
            Log.e(TAG, "getDisplayManagerGlobal failed", t); null
        }
    }

    private fun getSurfaceControlClass(): Class<*>? {
        scClass?.let { return it }
        return try {
            val c = Class.forName("android.view.SurfaceControl")
            scClass = c; c
        } catch (t: Throwable) {
            Log.e(TAG, "SurfaceControl class not found", t); null
        }
    }

    private fun createSurfaceControlDisplay(): IBinder? {
        val sc = getSurfaceControlClass() ?: return null
        val secure = false
        val methods = (sc.methods.filter {
            it.name == "createDisplay" && it.parameterTypes.firstOrNull() == String::class.java
        } + sc.declaredMethods.filter {
            it.name == "createDisplay" && it.parameterTypes.firstOrNull() == String::class.java
        }).distinct()
        for (m in methods) {
            try { m.isAccessible = true } catch (_: Throwable) {}
            val args: Array<Any?> = buildArgsForCreateDisplay(m.parameterTypes, "PortalPadMirror", secure)
                ?: continue
            try {
                val result = m.invoke(null, *args) as? IBinder
                if (result != null) {
                    scCreateDisplay = m
                    Log.d(TAG, "SurfaceControl.createDisplay ok signature=${m.parameterTypes.joinToString(",") { it.simpleName }}")
                    return result
                }
            } catch (t: Throwable) {
                Log.d(TAG, "createDisplay overload threw: ${t.javaClass.simpleName}")
            }
        }
        Log.w(TAG, "SurfaceControl.createDisplay: no working overload")
        return null
    }

    private fun buildArgsForCreateDisplay(paramTypes: Array<Class<*>>, name: String, secure: Boolean): Array<Any?>? {
        val args = arrayOfNulls<Any>(paramTypes.size)
        var boolSet = false
        for (i in paramTypes.indices) {
            val pt = paramTypes[i]
            args[i] = when {
                pt == String::class.java -> name
                pt == Boolean::class.javaPrimitiveType || pt == Boolean::class.java -> {
                    if (!boolSet) { boolSet = true; secure } else false
                }
                pt == Int::class.javaPrimitiveType || pt == Int::class.java -> 0
                pt == Long::class.javaPrimitiveType || pt == Long::class.java -> 0L
                pt == Float::class.javaPrimitiveType || pt == Float::class.java -> 0f
                pt.isPrimitive -> return null
                else -> null
            }
        }
        return args
    }

    private fun openSurfaceControlTransaction() {
        val sc = getSurfaceControlClass() ?: return
        val m = scOpenTransaction ?: sc.getMethod("openTransaction").also { scOpenTransaction = it }
        m.invoke(null)
    }
    private fun closeSurfaceControlTransaction() {
        val sc = getSurfaceControlClass() ?: return
        val m = scCloseTransaction ?: sc.getMethod("closeTransaction").also { scCloseTransaction = it }
        m.invoke(null)
    }
    private fun destroySurfaceControlDisplay(token: IBinder) {
        val sc = getSurfaceControlClass() ?: return
        val m = scDestroyDisplay ?: sc.getMethod("destroyDisplay", IBinder::class.java).also { scDestroyDisplay = it }
        m.invoke(null, token)
    }
    private fun setSurfaceControlDisplaySurface(token: IBinder, surface: Surface) {
        val sc = getSurfaceControlClass() ?: return
        val m = scSetDisplaySurface ?: sc.getMethod("setDisplaySurface", IBinder::class.java, Surface::class.java).also { scSetDisplaySurface = it }
        m.invoke(null, token, surface)
    }
    private fun setSurfaceControlDisplayProjection(token: IBinder, orientation: Int, src: Rect, dst: Rect) {
        val sc = getSurfaceControlClass() ?: return
        val m = scSetDisplayProjection ?: sc.getMethod(
            "setDisplayProjection",
            IBinder::class.java, Int::class.javaPrimitiveType, Rect::class.java, Rect::class.java,
        ).also { scSetDisplayProjection = it }
        m.invoke(null, token, orientation, src, dst)
    }
    private fun setSurfaceControlDisplayLayerStack(token: IBinder, layerStack: Int) {
        val sc = getSurfaceControlClass() ?: return
        val m = scSetDisplayLayerStack ?: sc.getMethod(
            "setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType,
        ).also { scSetDisplayLayerStack = it }
        m.invoke(null, token, layerStack)
    }

    // ─── Task management ────────────────────────────────────────────────

    override fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean {
        val id = Binder.clearCallingIdentity()
        return try {
            val atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null) ?: return false
            for (methodName in listOf("moveRootTaskToDisplay", "moveStackToDisplay", "moveTaskToDisplay")) {
                runCatching {
                    val m = atm.javaClass.getMethod(methodName, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    m.invoke(atm, taskId, displayId)
                    Log.d(TAG, "moveTaskToDisplay: $methodName ok task=$taskId display=$displayId")
                    return@moveTaskToDisplay true
                }
            }
            Log.w(TAG, "moveTaskToDisplay: no working method for task=$taskId display=$displayId")
            false
        } catch (t: Throwable) {
            Log.e(TAG, "moveTaskToDisplay failed task=$taskId display=$displayId", t); false
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    override fun moveFocusedTaskToDisplay(displayId: Int): Boolean {
        val id = Binder.clearCallingIdentity()
        return try {
            val atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null) ?: return false
            val focusedTask = getFocusedTaskInfo(atm) ?: return false
            val taskId = readIntField(focusedTask, "rootTaskId")
                ?: readIntField(focusedTask, "taskId")
                ?: readIntField(focusedTask, "stackId")
                ?: return false

            for (methodName in listOf("moveRootTaskToDisplay", "moveStackToDisplay", "moveTaskToDisplay")) {
                runCatching {
                    val m = atm.javaClass.getMethod(methodName, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    m.invoke(atm, taskId, displayId)
                    Log.d(TAG, "moveFocusedTaskToDisplay: $methodName ok task=$taskId display=$displayId")
                    return@moveFocusedTaskToDisplay true
                }
            }
            false
        } catch (t: Throwable) {
            Log.e(TAG, "moveFocusedTaskToDisplay failed", t); false
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    private fun getFocusedTaskInfo(atm: Any): Any? = try {
        runCatching { atm.javaClass.getMethod("getFocusedRootTaskInfo").invoke(atm) }.getOrNull()
            ?: runCatching { atm.javaClass.getMethod("getFocusedStackInfo").invoke(atm) }.getOrNull()
    } catch (_: Throwable) { null }

    override fun getFocusedTaskDisplayId(): Int {
        val id = Binder.clearCallingIdentity()
        return try {
            val atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null) ?: return -1
            val focusedTask = getFocusedTaskInfo(atm) ?: return -1
            readIntField(focusedTask, "displayId") ?: -1
        } catch (t: Throwable) {
            Log.w(TAG, "getFocusedTaskDisplayId failed", t); -1
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    /**
     * Re-assert input focus on [displayId] by bringing its current top task to
     * front — without relaunching anything. Finds the task whose displayId
     * matches and calls a moveTask/RootTask-to-front ATM method. Returns false
     * if no task is found on that display (caller may then fall back to
     * launching a backdrop).
     */
    override fun refocusTopTaskOnDisplay(displayId: Int): Boolean {
        val id = Binder.clearCallingIdentity()
        return try {
            val atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null) ?: return false
            // Enumerate tasks; pick the first (top-most) whose displayId matches.
            val tasks: List<*> = runCatching {
                @Suppress("UNCHECKED_CAST")
                (atm.javaClass.getMethod("getAllRootTaskInfos").invoke(atm) as? List<*>)
            }.getOrNull()
                ?: runCatching {
                    @Suppress("UNCHECKED_CAST")
                    (atm.javaClass.getMethod("getAllStackInfos").invoke(atm) as? List<*>)
                }.getOrNull()
                ?: return false

            val match = tasks.firstOrNull { t ->
                t != null && readIntField(t, "displayId") == displayId
            } ?: return false

            val taskId = readIntField(match, "rootTaskId")
                ?: readIntField(match, "taskId")
                ?: readIntField(match, "stackId")
                ?: return false

            for (methodName in listOf("moveRootTaskToFront", "moveTaskToFront", "moveStackToFront")) {
                runCatching {
                    val m = atm.javaClass.getMethod(methodName, Int::class.javaPrimitiveType)
                    m.invoke(atm, taskId)
                    Log.d(TAG, "refocusTopTaskOnDisplay: $methodName ok task=$taskId display=$displayId")
                    return@refocusTopTaskOnDisplay true
                }
            }
            Log.w(TAG, "refocusTopTaskOnDisplay: found task=$taskId on display=$displayId but no move-to-front method worked")
            false
        } catch (t: Throwable) {
            Log.e(TAG, "refocusTopTaskOnDisplay failed", t); false
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    private fun readIntField(obj: Any, fieldName: String): Int? = try {
        val f = obj.javaClass.getDeclaredField(fieldName)
        f.isAccessible = true
        f.getInt(obj)
    } catch (_: Throwable) { null }

    // ─── runCommand ─────────────────────────────────────────────────────

    override fun runCommand(command: String): String {
        val id = Binder.clearCallingIdentity()
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) { sb.append(line).append('\n'); line = reader.readLine() }
            }
            sb.toString()
        } catch (t: Throwable) {
            Log.e(TAG, "runCommand failed: $command", t); ""
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    // ─── EXPERIMENTAL: privileged-process overlay probe ────────────────
    override fun probeOverlayWindow(displayId: Int, windowType: Int, holdMs: Long): String {
        val id = Binder.clearCallingIdentity()
        return try {
            val ctx = obtainSystemContext() ?: context
                ?: return "PROBE FAIL: no context in privileged process"

            val result = AtomicReference<String>("(no result)")
            val latch = java.util.concurrent.CountDownLatch(1)
            val touchSeen = java.util.concurrent.atomic.AtomicBoolean(false)
            val touchLog = StringBuilder()

            // Window + view ops must run on a thread with a Looper. Use a
            // dedicated HandlerThread so we don't depend on the main looper.
            val ht = android.os.HandlerThread("portalpad-probe").apply { start() }
            val handler = android.os.Handler(ht.looper)
            handler.post {
                var view: android.view.View? = null
                var wm: android.view.WindowManager? = null
                try {
                    // Target the requested display.
                    val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    val display = dm.getDisplay(displayId)
                        ?: run {
                            result.set("PROBE FAIL: no display id=$displayId")
                            latch.countDown(); return@post
                        }
                    val displayCtx = ctx.createDisplayContext(display)
                    wm = displayCtx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager

                    // A small, visible, TOUCHABLE box so you can see it and tap it.
                    view = android.view.View(displayCtx).apply {
                        setBackgroundColor(0xAAFF0000.toInt()) // semi-transparent red
                        setOnTouchListener { _, ev ->
                            runCatching {
                                touchSeen.set(true)
                                touchLog.append("touch action=${ev.actionMasked} @(${ev.x.toInt()},${ev.y.toInt()}); ")
                                Log.d(TAG, "PROBE touch: action=${ev.actionMasked} pos=(${ev.x},${ev.y})")
                            }
                            true
                        }
                    }

                    val lp = android.view.WindowManager.LayoutParams(
                        300, 300,
                        windowType,
                        // Focusable + touchable (NO not-touchable / not-focusable flags)
                        // so we can actually test whether touches land on it.
                        0,
                        android.graphics.PixelFormat.TRANSLUCENT,
                    ).apply {
                        gravity = android.view.Gravity.CENTER
                    }

                    wm.addView(view, lp)
                    Log.d(TAG, "PROBE: addView OK type=$windowType disp=$displayId")
                    result.set("PROBE ADD OK: type=$windowType disp=$displayId — box shown ${holdMs}ms; TAP IT NOW")

                    // Hold so the user can see + tap, then remove.
                    val capturedView = view
                    val capturedWm = wm
                    handler.postDelayed({
                        try {
                            runCatching { capturedWm?.removeView(capturedView) }
                            result.set(
                                "PROBE type=$windowType disp=$displayId: ADD=OK, " +
                                    "touchReceived=${touchSeen.get()} [${touchLog}]",
                            )
                        } catch (t: Throwable) {
                            result.set("PROBE type=$windowType: removal error ${t.javaClass.simpleName}: ${t.message}; touchReceived=${touchSeen.get()}")
                        } finally {
                            latch.countDown()
                        }
                    }, holdMs)
                } catch (t: Throwable) {
                    Log.e(TAG, "PROBE addView FAILED type=$windowType: ${t.javaClass.simpleName}: ${t.message}")
                    result.set("PROBE ADD FAIL: type=$windowType disp=$displayId — ${t.javaClass.simpleName}: ${t.message}")
                    runCatching { if (view != null) wm?.removeView(view) }
                    latch.countDown()
                }
            }

            // Wait for the hold + result (cap a bit beyond holdMs).
            latch.await(holdMs + 3000, java.util.concurrent.TimeUnit.MILLISECONDS)
            runCatching { ht.quitSafely() }
            result.get()
        } catch (t: Throwable) {
            "PROBE EXCEPTION: ${t.javaClass.simpleName}: ${t.message}"
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    override fun amStart(component: String, displayId: Int): String {
        // reference pattern (from derived ShellUserService.startFocusedActivityOnDisplay):
        //   First try `am start -n <component>/<activity> --display N`. If
        //   that fails (Error/Exception), fall back to launching by package:
        //   `am start -a MAIN -c LAUNCHER -p <package> --display N`. The
        //   first form preserves which activity opens; the fallback opens
        //   the package's default launcher activity.
        Log.d(TAG, "amStart component='$component' displayId=$displayId")
        val cmdPrimary = if ("/" in component) {
            "am start -n '$component' --display $displayId"
        } else {
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $component --display $displayId"
        }
        val out1 = runCommand(cmdPrimary).trim()
        Log.d(TAG, "amStart primary out: $out1")
        val failed = out1.contains("Error", ignoreCase = true) ||
            out1.contains("Exception", ignoreCase = true) ||
            out1.contains("does not exist", ignoreCase = true)
        if (!failed) return out1

        // Fallback: extract package (before '/') and try LAUNCHER form
        if ("/" in component) {
            val pkg = component.substringBefore('/')
            val cmdFallback =
                "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg --display $displayId"
            val out2 = runCommand(cmdFallback).trim()
            Log.d(TAG, "amStart fallback out: $out2")
            return "PRIMARY:\n$out1\n\nFALLBACK:\n$out2"
        }
        return out1
    }

    // ─── Diagnostics ────────────────────────────────────────────────────

    override fun dumpDisplays(): String {
        val ctx = context ?: return "<no context>"
        return try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val sb = StringBuilder()
            for (d in dm.displays) {
                val p = android.graphics.Point().also { d.getRealSize(it) }
                sb.append("id=${d.displayId} name='${d.name}' ${p.x}x${p.y} flags=0x${d.flags.toString(16)} state=${d.state}\n")
            }
            sb.toString()
        } catch (t: Throwable) { "dump failed: $t" }
    }

    override fun ping(message: String): String = "pong: $message; uid=${Process.myUid()}"

    override fun shutdownUserService() {
        Log.d(TAG, "shutdown: ${virtualDisplays.size} VDs, ${mirrorVirtualDisplays.size} mirrors")
        virtualDisplays.values.forEach { runCatching { it.release() } }
        mirrorVirtualDisplays.values.forEach { runCatching { it.release() } }
        surfaceMirrorTokens.values.forEach { runCatching { destroySurfaceControlDisplay(it) } }
        virtualDisplays.clear(); mirrorVirtualDisplays.clear(); surfaceMirrorTokens.clear()
        stopLogcatStream()
        runCatching { Process.killProcess(Process.myPid()) }
    }

    // ─── Logcat streaming ──────────────────────────────────────────────
    // Use fully-qualified java.lang.Process for the OS subprocess to avoid
    // colliding with android.os.Process (which is also imported in this
    // file and is what bare `Process` resolves to here).
    @Volatile private var logcatProcess: java.lang.Process? = null
    @Volatile private var logcatThread: Thread? = null

    /**
     * Pipes filtered logcat into a ParcelFileDescriptor. Runs in this
     * (shell-UID) process where logcat read permission is granted. The
     * caller reads from the returned FD until [stopLogcatStream] is
     * invoked (or the process exits).
     *
     * Filter spec defaults to a strict PortalPad-only allowlist so we
     * never leak other apps' logs to the user. The "*:S" suffix silences
     * everything else.
     */
    override fun streamLogcat(filterSpec: String?): android.os.ParcelFileDescriptor {
        stopLogcatStream()  // ensure no leak from a previous session
        val safe = filterSpec ?: DEFAULT_LOGCAT_FILTER
        val pipe = android.os.ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        // Build argv. Splitting on spaces is fine because the filter
        // tokens never contain spaces individually.
        val argv = mutableListOf("logcat", "-v", "threadtime")
        argv += safe.split(' ').filter { it.isNotBlank() }

        val proc = try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.e(TAG, "logcat spawn failed", t)
            try { writeSide.close() } catch (_: Throwable) {}
            return readSide
        }
        logcatProcess = proc

        // Pump stdout → FD write side on a worker thread.
        val outFd = android.os.ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
        logcatThread = Thread({
            try {
                val buf = ByteArray(4096)
                val inStream = proc.inputStream
                while (true) {
                    val n = inStream.read(buf)
                    if (n <= 0) break
                    try { outFd.write(buf, 0, n); outFd.flush() }
                    catch (_: Throwable) { break }   // reader closed; abort
                }
            } catch (t: Throwable) {
                Log.w(TAG, "logcat pump ended", t)
            } finally {
                runCatching { outFd.close() }
            }
        }, "TD-LogcatPump").apply { isDaemon = true; start() }

        return readSide
    }

    override fun stopLogcatStream() {
        runCatching { logcatProcess?.destroy() }
        logcatProcess = null
        runCatching { logcatThread?.interrupt() }
        logcatThread = null
    }

    // ─── Physical mouse capture (Phase 1) ───────────────────────────────
    @Volatile private var mouseFd: Int = -1
    @Volatile private var mouseWriteFd: Int = -1
    @Volatile private var mouseThread: Thread? = null

    override fun startMouseCapture(grab: Boolean, nativeLibDir: String?, writeEnd: ParcelFileDescriptor?): String {
        // One session at a time — tear down any prior capture first.
        stopMouseCapture()
        if (writeEnd == null) return "ERR no-pipe"
        if (!NativeMouse.ensureLoaded(nativeLibDir)) {
            runCatching { writeEnd.close() }
            return "ERR native-lib-not-loaded: ${NativeMouse.loadError}"
        }
        val path = findMouseEventPath()
        if (path == null) {
            runCatching { writeEnd.close() }
            return "ERR no-mouse-found"
        }
        val fd = NativeMouse.nativeOpen(path)
        if (fd < 0) {
            runCatching { writeEnd.close() }
            return "ERR open device=$path errno=${-fd}"
        }
        val grabResult = if (grab) NativeMouse.nativeGrab(fd) else 0
        // Take ownership of the write fd so it outlives this binder call.
        val wfd = writeEnd.detachFd()
        mouseFd = fd
        mouseWriteFd = wfd
        val t = Thread({
            val reason = runCatching { NativeMouse.nativeRunLoop(fd, wfd) }.getOrDefault(-1)
            Log.i(TAG, "mouse read loop ended reason=$reason")
            runCatching { NativeMouse.nativeUngrabClose(fd) }
            runCatching { android.os.ParcelFileDescriptor.adoptFd(wfd).close() }
            if (mouseFd == fd) { mouseFd = -1; mouseWriteFd = -1; mouseThread = null }
        }, "PortalPadMouseLoop")
        t.isDaemon = true
        mouseThread = t
        t.start()

        val grabStr = when {
            !grab -> "skipped"
            grabResult == 0 -> "OK"
            else -> "FAILED(errno=${-grabResult})"
        }
        val status = "OK device=$path grab=$grabStr"
        Log.i(TAG, "startMouseCapture → $status")
        return status
    }

    override fun stopMouseCapture() {
        if (mouseFd == -1 && mouseThread == null) return
        runCatching { NativeMouse.nativeStop() }
        runCatching { mouseThread?.join(500) }
        mouseThread = null
        mouseFd = -1
        mouseWriteFd = -1
    }

    /**
     * Find a connected physical pointer device.
     *
     * We parse `getevent -pl` rather than /proc/bus/input/devices because Samsung's
     * SELinux policy denies the shell domain read access to that proc file (it
     * returns EACCES), while `getevent` is permitted. A mouse advertises REL_X and
     * REL_Y; keyboards and touchpads don't, so requiring both cleanly selects the
     * pointer node of a combo keyboard+mouse device (leaving the keyboard node
     * ungrabbed). The eventN number isn't stable across reboots, so we always
     * resolve it fresh.
     *
     * `getevent -pl` output looks like:
     *   add device 1: /dev/input/event12
     *     name:     "X1 keyboard Mouse"
     *     events:
     *       KEY (0001): BTN_MOUSE  BTN_RIGHT  BTN_MIDDLE
     *       REL (0002): REL_X  REL_Y  REL_WHEEL  REL_WHEEL_HI_RES
     */
    private fun findMouseEventPath(): String? {
        val out = runCatching {
            val p = ProcessBuilder("getevent", "-pl")
                .redirectErrorStream(true)
                .start()
            val text = p.inputStream.bufferedReader().readText()
            runCatching { p.waitFor() }
            text
        }.getOrNull() ?: return null

        var curPath: String? = null
        var curHasRelX = false
        var curHasRelY = false

        fun matches(): String? =
            if (curPath != null && curHasRelX && curHasRelY) curPath else null

        for (raw in out.lineSequence()) {
            val line = raw.trim()
            val dev = Regex("add device \\d+:\\s*(/dev/input/event\\d+)").find(line)
            if (dev != null) {
                // New device block starts — check the one we just finished.
                matches()?.let { return it }
                curPath = dev.groupValues[1]
                curHasRelX = false
                curHasRelY = false
                continue
            }
            if (curPath != null && line.startsWith("REL")) {
                if (line.contains("REL_X")) curHasRelX = true
                if (line.contains("REL_Y")) curHasRelY = true
            }
        }
        return matches()
    }

    companion object {
        private const val TAG = "ShellUserService"
        /**
         * Strict allowlist — silences everything except PortalPad's own tags.
         * Built from the shared [com.portalpad.app.diag.LogTags.PORTALPAD_TAGS]
         * so it can't drift from the root-path filter; AndroidRuntime:E keeps
         * crash stacks, *:S silences all other apps' logs.
         */
        private val DEFAULT_LOGCAT_FILTER =
            com.portalpad.app.diag.LogTags.PORTALPAD_TAGS + "AndroidRuntime:E *:S"
    }
}
