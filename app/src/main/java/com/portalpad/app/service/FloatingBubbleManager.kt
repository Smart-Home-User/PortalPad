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
import androidx.core.graphics.toColorInt
import com.portalpad.app.MainActivity
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
    private val handler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null

    /** True when the user has dismissed the bubble for this session via the menu. */
    @Volatile var sessionHidden: Boolean = false
        private set

    val isShown: Boolean get() = bubbleView != null

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

        val sizePx = (BUBBLE_SIZE_DP * context.resources.displayMetrics.density).toInt()
        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor("#9B5BFF".toColorInt())
                setStroke(
                    (2 * context.resources.displayMetrics.density).toInt(),
                    "#C8A6FF".toColorInt(),
                )
            }
            elevation = 12f * context.resources.displayMetrics.density
            isClickable = true
            isFocusable = false
        }
        val icon = ImageView(context).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            val padPx = (10 * context.resources.displayMetrics.density).toInt()
            setPadding(padPx, padPx, padPx, padPx)
        }
        container.addView(icon, FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER))

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = context.resources.displayMetrics.widthPixels - sizePx - 32
            y = (context.resources.displayMetrics.heightPixels * 0.35f).toInt()
        }

        attachInputHandling(container, params)
        try {
            windowManager.addView(container, params)
            bubbleView = container
            bubbleParams = params
            Log.d(TAG, "show(): bubble added successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Could not add bubble view", t)
        }
    }

    fun hide() {
        dismissMenu()
        bubbleView?.let {
            runCatching { windowManager.removeView(it) }
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
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressFired) {
                        // simple tap → open trackpad
                        runCatching { PortalPadApp.instance.injector.buzz(longPress = false) }
                        launchTrackpad(TrackpadActivity.MODE_TRACKPAD)
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
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun showMenu(anchor: View, bubble: WindowManager.LayoutParams) {
        dismissMenu()

        val density = context.resources.displayMetrics.density
        val menuWidthPx = (230 * density).toInt()
        val padPx = (12 * density).toInt()

        val container = LinearLayout(context).apply {
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
        val screenWidth = context.resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            menuWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
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
            windowManager.addView(container, params)
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
            runCatching { windowManager.removeView(it) }
        }
        menuView = null
    }

    private fun launchTrackpad(mode: String) {
        val intent = Intent(context, TrackpadActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(TrackpadActivity.EXTRA_MODE, mode)
        context.startActivity(intent)
    }

    private fun launchSettings() {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
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
