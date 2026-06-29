package com.portalpad.app.presentation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt

/**
 * A full-screen BLACK cover shown ON the external display (glasses) while a
 * session restore is in progress — whether triggered by a flap auto-recovery
 * (quick unplug/replug) or by the phone's "Restore last session?" popup.
 *
 * Its job is to HIDE the brief shuffle the user would otherwise see: on a replug
 * the platform re-homes the surviving apps FULLSCREEN before PortalPad can tile
 * them, so for ~1s you'd see a maximized window before it snaps into the saved
 * split. The cover goes up at restore start (before the apps re-home), shows a
 * centered "Restoring your windows…" label with a progress bar that fills as the
 * work proceeds, then on completion snaps to 100%, swaps to "Windows restored",
 * and fades away to reveal the tiled layout underneath.
 *
 * Renders on the glasses via the same [OverlayHost] plumbing the dock/cursor use
 * (a phone-side window would land on the wrong display). All sizing is derived
 * from the physical display width, so it's correct in standard 16:9 AND
 * ultrawide. A safety timer removes it if the "done" signal never arrives, so a
 * stalled restore can't leave the display black.
 */
object RestoreCover {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var current: Pair<WindowManager, View>? = null
    private var label: TextView? = null
    private var fill: View? = null
    private var trackW: Int = 0
    private var fillAnim: ValueAnimator? = null
    private var safety: Runnable? = null

    private const val DONE_COLOR = "#3DC9A5"
    private const val SAFETY_MS = 12_000L

    /**
     * Bring the cover up (black), slide the centered card in, and start the
     * progress bar filling toward ~92% over a few seconds. If it's already up,
     * just updates the label. Stays until [showDone] or [dismiss].
     */
    fun showRestoring(
        serviceContext: Context,
        display: Display,
        message: String = "Restoring your windows…",
    ) {
        mainHandler.post {
            if (current != null) {
                label?.text = message
                armSafety()
                return@post
            }
            runCatching {
                val host = OverlayHost.forDisplay(display, serviceContext)
                val ctx = host.context
                val density = ctx.resources.displayMetrics.density
                @Suppress("DEPRECATION")
                val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
                val w = metrics.widthPixels.coerceAtLeast(640)
                val h = metrics.heightPixels.coerceAtLeast(360)
                // Size the label/bar off a 16:9-EQUIVALENT width, not the raw
                // physical width. On ultrawide (e.g. 3840x1080) the raw width is
                // ~2x a 16:9 panel of the same height, which would inflate the
                // font to ~92px and clip the label. Cap the base at ~16:9 so the
                // pill looks identical in both aspect ratios, just centered on a
                // wider black field.
                val baseW = w.coerceAtMost((h * 16f / 9f).toInt())
                val textPx = baseW * 0.024f
                val barH = (6 * density).toInt().coerceAtLeast(4)
                trackW = (baseW * 0.5f).toInt()
                    .coerceAtMost((520 * density).toInt())
                    .coerceAtLeast(240)

                val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }

                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    alpha = 0f
                    translationY = -22f * density
                }
                val tv = TextView(ctx).apply {
                    text = message
                    setTextColor("#F2F2F5".toColorInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    val padH = (baseW * 0.04f).toInt()
                    setPadding(padH, 0, padH, 0)
                }
                val track = FrameLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(trackW, barH).apply {
                        topMargin = (14 * density).toInt()
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = barH / 2f
                        setColor("#29FFFFFF".toColorInt())
                    }
                }
                val fillV = View(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(0, barH)
                    background = GradientDrawable().apply {
                        cornerRadius = barH / 2f
                        setColor(DONE_COLOR.toColorInt())
                    }
                }
                track.addView(fillV)
                col.addView(tv)
                col.addView(track)
                root.addView(
                    col,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { gravity = Gravity.CENTER },
                )

                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    host.windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    // Translucent (not opaque) so the fade-out alpha animation
                    // works; the solid black bg makes it visually opaque anyway.
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.CENTER }

                host.windowManager.addView(root, lp)
                current = host.windowManager to root
                label = tv
                fill = fillV

                col.animate().alpha(1f).translationY(0f).setDuration(260).start()
                startFill((trackW * 0.92f).toInt(), 3400L)
                armSafety()
            }.onFailure { Log.w(TAG, "RestoreCover show failed", it) }
        }
    }

    /**
     * Snap the bar to 100%, swap to a "done" message, then fade the cover away
     * after a brief linger — revealing the now-tiled windows. If the cover
     * wasn't up (restore finished very fast), shows it briefly first.
     */
    fun showDone(
        serviceContext: Context,
        display: Display,
        message: String = "Windows restored",
        lingerMs: Long = 900L,
    ) {
        mainHandler.post {
            if (current == null) {
                showRestoring(serviceContext, display, message)
                mainHandler.postDelayed({ finishDone(message, lingerMs) }, 140L)
            } else {
                finishDone(message, lingerMs)
            }
        }
    }

    private fun finishDone(message: String, lingerMs: Long) {
        label?.text = message
        label?.setTextColor(DONE_COLOR.toColorInt())
        if (trackW > 0) startFill(trackW, 250L)
        cancelSafety()
        val r = Runnable { dismiss() }
        safety = r
        mainHandler.postDelayed(r, lingerMs)
    }

    /** Fade the cover out and remove it. Safe to call when nothing is showing. */
    fun dismiss() {
        mainHandler.post {
            cancelSafety()
            fillAnim?.cancel()
            fillAnim = null
            val cur = current ?: return@post
            current = null
            label = null
            fill = null
            val (wm, v) = cur
            runCatching {
                v.animate().alpha(0f).setDuration(280)
                    .withEndAction { runCatching { wm.removeView(v) } }.start()
            }.onFailure { runCatching { wm.removeView(v) } }
        }
    }

    private fun startFill(targetPx: Int, duration: Long) {
        val fillV = fill ?: return
        fillAnim?.cancel()
        val start = fillV.layoutParams?.width ?: 0
        val anim = ValueAnimator.ofInt(start, targetPx).apply {
            this.duration = duration
            addUpdateListener { a ->
                val lp = fillV.layoutParams ?: return@addUpdateListener
                lp.width = a.animatedValue as Int
                fillV.layoutParams = lp
            }
        }
        fillAnim = anim
        anim.start()
    }

    private fun armSafety() {
        cancelSafety()
        val r = Runnable { dismiss() }
        safety = r
        mainHandler.postDelayed(r, SAFETY_MS)
    }

    private fun cancelSafety() {
        safety?.let { mainHandler.removeCallbacks(it) }
        safety = null
    }

    private const val TAG = "RestoreCover"
}
