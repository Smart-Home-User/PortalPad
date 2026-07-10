package com.portalpad.app.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.portalpad.app.presentation.OverlayHost
import androidx.core.graphics.toColorInt
import com.portalpad.app.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.portalpad.app.PortalPadApp
import com.portalpad.app.R
import com.portalpad.app.TrackpadActivity
import kotlin.math.abs

/**
 * Manages the floating bubble + its long-press menu.
 *
 * Tap the bubble → open trackpad.
 * Drag → move (snaps to nearest screen edge on release).
 * Long-press → open a small action menu (Trackpad / Media / Settings / Hide).
 *
 * "Hide" hides the bubble for the current background session only — it'll come
 * back next time the user backgrounds the app. To permanently disable, the user
 * toggles the "Show floating bubble" preference in Settings.
 */
class FloatingBubbleManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // The bubble + its menu live on the phone (DEFAULT_DISPLAY). They are hosted
    // as TYPE_ACCESSIBILITY_OVERLAY (2032) via the bound AccessibilityService when
    // it's available, falling back to TYPE_APPLICATION_OVERLAY (2038) otherwise —
    // the same OverlayHost path the cursor/dock use. 2032 matters here because a
    // 2038 overlay is force-hidden by HIDE_NON_SYSTEM_OVERLAY_WINDOWS while a
    // permission dialog is up (confirmed via dumpsys: the bubble window carried
    // mIsForceHiddenNonSystemOverlayWindow=true during a GrantPermissions dialog),
    // which is why the bubble vanished through Kindle's permission flow. The WM
    // that ACTUALLY added each view is stored so hide()/drag/dismiss remove or
    // update via the SAME one — it can differ from the plain field when 2032 is in
    // use. Default to the plain WM (the 2038 fallback path) until show() resolves.
    private var bubbleWm: WindowManager = windowManager
    private var menuWm: WindowManager = windowManager

    private val handler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null

    /** True when the user has dismissed the bubble for this session via the menu. */
    @Volatile var sessionHidden: Boolean = false
        private set

    val isShown: Boolean get() = bubbleView != null

    /** True only when the bubble view exists AND is still attached to a window.
     *  [isShown] tracks only the reference, which can go stale if the OS tears
     *  the window down (a display flap, or a security overlay-hide) without
     *  hide() running — leaving callers believing the bubble is up when it's
     *  gone. The watchdog / post-settle re-assert check THIS so they can heal
     *  that desync. (addView attaches on the next traversal, so this reads false
     *  for a frame right after show(); the callers that use it run seconds later,
     *  so that transient doesn't matter.) */
    val isActuallyShown: Boolean get() = bubbleView?.isAttachedToWindow == true

    /** Force a clean re-add. show() early-returns while bubbleView != null, so a
     *  stale-but-detached reference cannot be healed by show() alone — this drops
     *  it first. hide() safely catches an already-gone window. Respects
     *  session-hide via show()'s own guard. */
    fun reshow() {
        hide()
        show()
    }

    fun show() {
        if (bubbleView != null) {
            Log.d(TAG, "show(): already shown — no-op")
            return
        }
        if (sessionHidden) {
            Log.d(TAG, "show(): suppressed — session-hidden (user tapped Hide until next time)")
            return
        }
        if (!canDrawOverlays()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — bubble suppressed")
            return
        }

        // Resolve the overlay host (2032 via a11y when available, else 2038) and
        // build the view from ITS context so the accessibility window token
        // attaches — building from the plain service context strips the token and
        // the 2032 add throws BadToken. bubbleWm is the WM that owns this window;
        // hide()/drag must use the same one.
        val host = resolveHost()
        bubbleWm = host.windowManager
        val ctx = host.context

        val sizePx = (BUBBLE_SIZE_DP * ctx.resources.displayMetrics.density).toInt()
        val container = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor("#9B5BFF".toColorInt())
                setStroke(
                    (2 * ctx.resources.displayMetrics.density).toInt(),
                    "#C8A6FF".toColorInt(),
                )
            }
            elevation = 12f * ctx.resources.displayMetrics.density
            isClickable = true
            isFocusable = false
        }
        val icon = ImageView(ctx).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            val padPx = (10 * ctx.resources.displayMetrics.density).toInt()
            setPadding(padPx, padPx, padPx, padPx)
        }
        container.addView(icon, FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER))

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            host.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ctx.resources.displayMetrics.widthPixels - sizePx - 32
            y = (ctx.resources.displayMetrics.heightPixels * 0.35f).toInt()
        }

        attachInputHandling(container, params)
        try {
            bubbleWm.addView(container, params)
            bubbleView = container
            bubbleParams = params
            Log.d(
                TAG,
                "show(): bubble added (type=${host.windowType} a11y=${host.isAccessibilityOverlay})",
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Could not add bubble view", t)
        }
    }

    /** Resolve an overlay host for the phone (DEFAULT_DISPLAY): 2032 via the bound
     *  AccessibilityService when available, else 2038 via the service context.
     *  Views added through the returned WM MUST be built from the returned context
     *  (see show()/showMenu()) or the 2032 window token won't attach. */
    private fun resolveHost(): OverlayHost {
        val display = runCatching {
            (context.getSystemService(Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        }.getOrNull()
        return if (display != null) OverlayHost.forDisplay(display, context)
        else OverlayHost(context, windowManager, overlayType())
    }

    fun hide() {
        dismissMenu()
        bubbleView?.let {
            runCatching { bubbleWm.removeView(it) }
                .onFailure { Log.w(TAG, "removeView failed", it) }
        }
        bubbleView = null
        bubbleParams = null
    }

    /** Called by app lifecycle when user foregrounds the app — resets session-hide. */
    fun clearSessionHide() {
        sessionHidden = false
    }

    private fun attachInputHandling(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var moved = false
        var longPressFired = false
        val touchSlopPx = 24f
        val longPressMs = 500L

        val longPressRunnable = Runnable {
            if (!moved) {
                longPressFired = true
                runCatching { PortalPadApp.instance.injector.buzz(longPress = true) }
                showMenu(view, params)
            }
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    moved = false
                    longPressFired = false
                    handler.postDelayed(longPressRunnable, longPressMs)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!moved && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        runCatching { bubbleWm.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressFired) {
                        // simple tap → resume where the user left off (see launchFromBubbleTap)
                        runCatching { PortalPadApp.instance.injector.buzz(longPress = false) }
                        launchFromBubbleTap()
                    } else if (moved) {
                        snapToNearestEdge(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToNearestEdge(view: View, params: WindowManager.LayoutParams) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val viewWidth = view.width
        val centerX = params.x + viewWidth / 2
        params.x = if (centerX < screenWidth / 2) 16
        else screenWidth - viewWidth - 16
        runCatching { bubbleWm.updateViewLayout(view, params) }
    }

    private fun showMenu(anchor: View, bubble: WindowManager.LayoutParams) {
        dismissMenu()

        // Host the menu the same way as the bubble (2032 when a11y is bound) so it
        // isn't force-hidden during a permission dialog either. The container added
        // to WM must be built from the host context for the token to attach.
        val host = resolveHost()
        menuWm = host.windowManager
        val menuCtx = host.context

        val density = menuCtx.resources.displayMetrics.density
        val menuWidthPx = (230 * density).toInt()
        val padPx = (12 * density).toInt()

        val container = LinearLayout(menuCtx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor("#1A1326".toColorInt())          // AbSurface
                setStroke((1 * density).toInt(), "#5B3CAA".toColorInt())  // AbPrimaryDim
            }
            elevation = 16f * density
            isClickable = true
            setPadding(padPx, padPx, padPx, padPx)
        }
        // Title so users know which app this menu belongs to.
        container.addView(TextView(context).apply {
            text = "PortalPad - Floating Bubble"
            setTextColor("#A89BB8".toColorInt())          // AbOnSurfaceMuted
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 13f
            setPadding(
                (12 * density).toInt(), (6 * density).toInt(),
                (12 * density).toInt(), (10 * density).toInt(),
            )
        })
        container.addView(menuRow("Open trackpad") {
            launchTrackpad(TrackpadActivity.MODE_TRACKPAD); dismissMenu()
        })
        container.addView(menuRow("Open media controls") {
            launchTrackpad(TrackpadActivity.MODE_MEDIA); dismissMenu()
        })
        container.addView(menuRow("Open PortalPad settings") {
            launchSettings(); dismissMenu()
        })
        container.addView(menuRow("Hide bubble until next time") {
            sessionHidden = true
            hide()
        })

        // Position the menu just to the left or right of the bubble, depending
        // on which edge it's snapped to.
        val screenWidth = menuCtx.resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            menuWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            host.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (bubble.x < screenWidth / 2) bubble.x + anchor.width + 12
            else bubble.x - menuWidthPx - 12
            y = bubble.y
        }

        // Tap-outside dismissal: a second invisible full-screen view watches
        // for touches outside the menu and closes it.
        container.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                dismissMenu()
                true
            } else false
        }

        try {
            menuWm.addView(container, params)
            menuView = container
        } catch (t: Throwable) {
            Log.e(TAG, "Could not show bubble menu", t)
        }
    }

    private fun menuRow(label: String, onClick: () -> Unit): View {
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            setTextColor("#C8A6FF".toColorInt())          // AbPrimaryBright
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = 15f
            setPadding(
                (16 * density).toInt(), (14 * density).toInt(),
                (16 * density).toInt(), (14 * density).toInt(),
            )
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor("#2A2140".toColorInt())          // AbSurfaceElevated
            }
            // Small gap between rows, matching the Compose menu's spacing.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (6 * density).toInt() }
            setOnClickListener {
                runCatching { PortalPadApp.instance.injector.buzz(longPress = false) }
                onClick()
            }
        }
    }

    private fun dismissMenu() {
        menuView?.let {
            runCatching { menuWm.removeView(it) }
        }
        menuView = null
    }

    /**
     * Tapping the bubble resumes where the user left off, context-aware:
     *  - No external display attached → open the app's home screen (MainActivity).
     *    A trackpad/air-mouse/remote surface would control nothing without the
     *    display, and launching the trackpad on the phone just flashes the
     *    "Restore last session?" prompt before the activity self-closes.
     *  - External display attached → reopen the LAST interface the user was in
     *    (Remote if they were last in Remote, otherwise Trackpad — which itself
     *    restores the last Trackpad/Air-Mouse tab), so they resume immediately.
     */
    private fun launchFromBubbleTap() {
        val app = PortalPadApp.instance
        val resolved = runCatching { app.resolveLiveExternalDisplayId() }.getOrNull()
        val hasDisplay = resolved != null
        android.util.Log.d(
            "PortalPadBubble",
            "tap: resolvedExternalDisplay=$resolved cached=${app.externalDisplayId.value} " +
                "→ ${if (hasDisplay) "resume-interface" else "open-home"}",
        )
        if (!hasDisplay) {
            launchSettings() // MainActivity = app home / connect screen
            return
        }
        val wasRemote = runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.bool(
                    com.portalpad.app.data.PreferencesRepository.Keys.LAST_MODE_WAS_REMOTE,
                    default = false,
                ).first()
            }
        }.getOrDefault(false)
        // Rebuild the external session before launching — the service may have
        // outlived the last activity (recents-swipe) and left it half-wired, which
        // makes a freshly launched interface flash on the glasses and die.
        runCatching { PortalPadForegroundService.instance?.reconcileExternalForBubbleLaunch() }
        launchTrackpad(if (wasRemote) TrackpadActivity.MODE_MEDIA else TrackpadActivity.MODE_TRACKPAD)
    }

    private fun launchTrackpad(mode: String) {
        val intent = Intent(context, TrackpadActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(TrackpadActivity.EXTRA_MODE, mode)
        // Force the trackpad interface onto the PHONE (display 0). Without an
        // explicit target, a singleTask TrackpadActivity whose task got stranded
        // on the external display (after a recents-clear + replug) resumes THERE —
        // so the trackpad UI (and any popup it shows on start) flashes on the
        // glasses instead of returning to the phone.
        val opts = android.app.ActivityOptions.makeBasic()
            .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
        runCatching { context.startActivity(intent, opts.toBundle()) }
            .onFailure { context.startActivity(intent) }
    }

    private fun launchSettings() {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Settings is a phone UI too — pin it to display 0 for the same reason.
        val opts = android.app.ActivityOptions.makeBasic()
            .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
        runCatching { context.startActivity(intent, opts.toBundle()) }
            .onFailure { context.startActivity(intent) }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
        else true

    companion object {
        private const val TAG = "FloatingBubble"
        private const val BUBBLE_SIZE_DP = 56
    }
}
