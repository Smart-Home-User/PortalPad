package com.portalpad.app.presentation

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.WindowManager

/**
 * Picks the right Context + window type for adding overlays.
 *
 * Background: there are two ways to add a system overlay on Android.
 *
 *  - TYPE_APPLICATION_OVERLAY (2038): the "alert window" type any app
 *    with the SYSTEM_ALERT_WINDOW permission can use. The system
 *    treats it as a "third-party app" overlay. Critically, Samsung's
 *    input dispatcher on One UI / Android 16 reacts to touchscreen
 *    events on the phone display (display 0) by canceling hover or
 *    focus state on OTHER displays where TYPE_APPLICATION_OVERLAY
 *    windows are active. This is what's causing Chrome's address-bar
 *    suggestion dropdown (a PopupWindow on the trusted VD) to dismiss
 *    the moment the user touches the trackpad on the phone screen,
 *    even when our overlays don't render anything new.
 *
 *  - TYPE_ACCESSIBILITY_OVERLAY (2032): a special type only addable
 *    from a bound AccessibilityService. The system treats these as
 *    part of the accessibility layer and they do NOT propagate
 *    cross-display dismiss signals. This is the type the reference implementation uses
 *    for its cursor and dock overlays (confirmed via adb logcat:
 *    `a system overlay window (type 2032)`). the reference implementation
 *    works for Chrome's address-bar dropdown specifically because
 *    they use 2032 — the cross-display interference doesn't happen.
 *
 * PortalPad's AccessibilityService was removed, so this helper always
 * builds a TYPE_APPLICATION_OVERLAY hosted by the service context. The
 * [TYPE_ACCESSIBILITY_OVERLAY] constant and [isAccessibilityOverlay] flag
 * are kept only for log/debug references (the flag is now always false).
 */
data class OverlayHost(
    val context: Context,
    val windowManager: WindowManager,
    val windowType: Int,
) {
    val isAccessibilityOverlay: Boolean
        get() = windowType == TYPE_ACCESSIBILITY_OVERLAY

    companion object {
        /** TYPE_ACCESSIBILITY_OVERLAY (hidden constant on some Android versions). */
        const val TYPE_ACCESSIBILITY_OVERLAY: Int = 2032

        /**
         * Build an overlay host for the given [display] using
         * TYPE_APPLICATION_OVERLAY, hosted by [serviceContext] (a Service
         * context holding SYSTEM_ALERT_WINDOW).
         */
        fun forDisplay(
            display: Display,
            serviceContext: Context,
        ): OverlayHost {
            val ctx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                serviceContext.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else {
                serviceContext.createDisplayContext(display)
            }
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return OverlayHost(ctx, wm, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
    }
}
