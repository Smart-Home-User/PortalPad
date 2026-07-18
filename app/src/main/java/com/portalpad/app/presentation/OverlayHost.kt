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
 * PortalPad's AccessibilityService is used again (for the permission-dialog
 * watch and text relay), so this helper now hosts overlays as
 * TYPE_ACCESSIBILITY_OVERLAY (2032) via that service when it's bound, and only
 * falls back to TYPE_APPLICATION_OVERLAY (2038) via the service context when it
 * isn't. [isAccessibilityOverlay] reflects which type is in use. See
 * [forDisplay] for why 2032 matters (surviving HIDE_NON_SYSTEM_OVERLAY_WINDOWS
 * during permission dialogs, and the Chrome-dropdown behavior).
 */
data class OverlayHost(
    val context: Context,
    val windowManager: WindowManager,
    val windowType: Int,
) {
    val isAccessibilityOverlay: Boolean
        get() = windowType == TYPE_ACCESSIBILITY_OVERLAY

    companion object {
        private const val TAG = "OverlayHost"

        /** TYPE_ACCESSIBILITY_OVERLAY (hidden constant on some Android versions). */
        const val TYPE_ACCESSIBILITY_OVERLAY: Int = 2032

        /**
         * Build an overlay host for the given [display].
         *
         * Prefers **TYPE_ACCESSIBILITY_OVERLAY (2032)** hosted by PortalPad's
         * bound AccessibilityService: a11y overlays are EXEMPT from
         * `HIDE_NON_SYSTEM_OVERLAY_WINDOWS` (which Android raises during
         * permission / security dialogs), so the cursor + dock stay visible
         * through a permission prompt instead of vanishing — and on a mirrored VD
         * they land on that display's own a11y overlay layer, so they still show
         * through the mirror. (This is exactly how AirBeam keeps its cursor
         * visible during a permission prompt — confirmed in its SurfaceFlinger
         * dump: its overlays sit under a `WindowToken{type=2032}` on its
         * VD. 2032 was also PortalPad's original type, chosen because it fixed
         * Chrome's address-bar dropdown dying on trackpad touch.)
         *
         * Falls back to **TYPE_APPLICATION_OVERLAY (2038)** hosted by
         * [serviceContext] when the a11y service isn't enabled. In that mode the
         * cursor still hides during dialogs (unavoidable without a11y), but
         * everything else works as before.
         */
        fun forDisplay(
            display: Display,
            serviceContext: Context,
            // When true, skip the 2032 (a11y) path entirely and host as 2038.
            // Used by the mirror-overlay attach ladder: 2032's window token can
            // token-null at addView on a freshly-reconnected physical display
            // (the a11y service has no window-token registration for the new
            // display id yet), and that failure surfaces at addView — OUTSIDE
            // the runCatching below — so it can't fall through here. The caller
            // escalates to force2038 after 2032 fails, and 2038's token (the
            // app's SYSTEM_ALERT_WINDOW grant) is valid the moment the display
            // exists.
            force2038: Boolean = false,
        ): OverlayHost {
            // 2032 path: only a bound AccessibilityService may add
            // TYPE_ACCESSIBILITY_OVERLAY windows, so host them from its context.
            val a11y = if (force2038) null
                else com.portalpad.app.service.PortalPadAccessibilityService.instance
            if (a11y != null) {
                val host = runCatching {
                    val base = a11y.createDisplayContext(display)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Require a REAL 2032 window context. If this ROM rejects a
                        // window context for 2032 (some do — notably for a raw
                        // PHYSICAL external display in the no-VD fallback), do NOT
                        // host 2032 on a plain display context: its addView fails
                        // with "token null is not valid" and the cursor/control bar
                        // never attach (the Sony Xperia raw-fallback regression from
                        // the 2038→2032 migration). Let it throw and drop to 2038,
                        // which is what those overlays used before the migration and
                        // which attaches fine on a raw physical display.
                        val wctx = base.createWindowContext(TYPE_ACCESSIBILITY_OVERLAY, null)
                        val wm = wctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        android.util.Log.d(TAG, "DIAG-OVL disp=${display.displayId} host=2032(windowctx)")
                        OverlayHost(wctx, wm, TYPE_ACCESSIBILITY_OVERLAY)
                    } else {
                        val wm = base.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                        android.util.Log.d(TAG, "DIAG-OVL disp=${display.displayId} host=2032(base<S)")
                        OverlayHost(base, wm, TYPE_ACCESSIBILITY_OVERLAY)
                    }
                }.getOrNull()
                if (host != null) return host
                android.util.Log.w(
                    TAG,
                    "DIAG-OVL disp=${display.displayId} 2032 window context unavailable -> 2038 fallback",
                )
            }
            // 2038 fallback (original pre-migration behavior): hosted by the service context.
            val ctx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                serviceContext.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else {
                serviceContext.createDisplayContext(display)
            }
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            android.util.Log.d(TAG, "DIAG-OVL disp=${display.displayId} host=2038")
            return OverlayHost(ctx, wm, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
    }
}
