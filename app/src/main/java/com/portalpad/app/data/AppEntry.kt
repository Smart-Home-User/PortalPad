package com.portalpad.app.data

import kotlinx.serialization.Serializable

/** Number of customizable slots on the Quick Wheel ring (center is the
 *  permanent app-drawer button and is not counted here). */
const val QUICK_WHEEL_SLOTS = 8

/**
 * A reference to one of:
 *  - an entire app (componentName=null, shortcutUri=null)
 *  - a specific activity within an app (componentName set)
 *  - a PHONE shortcut / Tasker task (shortcutUri set) — an Intent URI that is
 *    fired on the PHONE screen (not the external display) via a launch cascade.
 */
@Serializable
data class AppEntry(
    val packageName: String,
    val componentName: String? = null,
    val label: String,
    /** When set, this entry is a phone-side shortcut: the Intent (encoded via
     *  Intent.toUri) is fired on the phone instead of launching on the glasses.
     *  Null for normal app/activity entries. */
    val shortcutUri: String? = null,
    /** Launch mode on the external display (desktop mode only). True = freeform
     *  window; false = fullscreen (launched without --windowingMode 5) — for
     *  Android-TV-style launchers and other apps that should own the whole
     *  panel. Defaults TRUE so every entry saved before this field existed
     *  keeps its historical freeform behavior with zero migration; the
     *  Home/Back picker explicitly writes false on NEW assignments (fullscreen
     *  is the deliberate default for nav buttons). Meaningless for shortcuts.
     *  NOTE: this ROM cannot convert a running task between modes — a change
     *  applies on the app's next launch. */
    val freeform: Boolean = true,
) {
    val isActivity: Boolean get() = componentName != null
    /** True when this is a phone shortcut (Tasker task / app shortcut) that should
     *  fire on the phone screen rather than launch on the external display. */
    val isShortcut: Boolean get() = shortcutUri != null
}

