package com.portalpad.app

import android.app.Activity
import android.content.pm.ActivityInfo
import com.portalpad.app.data.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Locks PortalPad's PHONE-side UI to a chosen orientation. Applies ONLY to
 * PortalPad's own activities — it never affects apps launched on the external
 * display (those are separate apps the system orients independently).
 *
 * Default is portrait, since the phone is the input surface and is meant to be
 * held upright; users on a tablet (or who want free rotation) can pick another
 * mode — including "auto" to follow the system.
 *
 * Usage in each phone-side Activity:
 *   override fun onCreate(...) { super.onCreate(...); lockOrientationDefault() ... }
 *   override fun onResume()   { super.onResume(); applyForcedOrientation() }
 *
 * onCreate sets the default immediately (no async wait → no first-frame
 * rotation flicker); onResume reconciles with the actual saved preference so a
 * change in Settings takes effect when you return to the screen.
 */
object OrientationLock {

    const val PORTRAIT = "portrait"
    const val PORTRAIT_REVERSE = "portrait_reverse"
    const val LANDSCAPE = "landscape"
    const val LANDSCAPE_REVERSE = "landscape_reverse"
    const val AUTO = "auto"

    const val DEFAULT = PORTRAIT

    /** Human labels for the settings picker, in display order. */
    val OPTIONS: List<Pair<String, String>> = listOf(
        PORTRAIT to "Portrait",
        PORTRAIT_REVERSE to "Portrait (reverse)",
        LANDSCAPE to "Landscape",
        LANDSCAPE_REVERSE to "Landscape (reverse)",
        AUTO to "Auto-rotate (follow system)",
    )

    fun labelFor(mode: String): String =
        OPTIONS.firstOrNull { it.first == mode }?.second ?: "Portrait"

    private fun toActivityInfo(mode: String): Int = when (mode) {
        PORTRAIT_REVERSE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        LANDSCAPE_REVERSE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    fun apply(activity: Activity, mode: String) {
        runCatching { activity.requestedOrientation = toActivityInfo(mode) }
    }
}

/** Set the default orientation (portrait) instantly in onCreate to avoid a
 *  first-frame rotation flicker before the saved preference resolves. */
fun Activity.lockOrientationDefault() {
    OrientationLock.apply(this, OrientationLock.DEFAULT)
}

/** Reconcile with the saved orientation preference (call from onResume). */
fun Activity.applyForcedOrientation() {
    val app = applicationContext as? PortalPadApp ?: return
    val mode = runCatching {
        runBlocking { app.prefs.string(PreferencesRepository.Keys.FORCE_ORIENTATION, OrientationLock.DEFAULT).first() }
    }.getOrDefault(OrientationLock.DEFAULT)
    OrientationLock.apply(this, mode)
}
