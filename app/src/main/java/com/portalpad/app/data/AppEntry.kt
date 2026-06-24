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
) {
    val isActivity: Boolean get() = componentName != null
    /** True when this is a phone shortcut (Tasker task / app shortcut) that should
     *  fire on the phone screen rather than launch on the external display. */
    val isShortcut: Boolean get() = shortcutUri != null
}

