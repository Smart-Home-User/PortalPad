package com.portalpad.app.data

/**
 * An action assignable to a 3-finger swipe direction on the trackpad / air-mouse
 * surface. Stage 1: system actions only (Back / Home / the on-glasses
 * notification panel). "Launch a specific app" can be added later (it would reuse
 * the Home/Back app picker).
 *
 * Stored in prefs by [id]. [label] is the full settings-picker label; [short] is
 * the compact label used in the on-glasses gesture guide (single-line + ellipsis,
 * so it stays tidy).
 */
enum class GestureAction(val id: String, val label: String, val short: String) {
    NONE("none", "None", "—"),
    BACK("back", "Back", "Back"),
    HOME("home", "Home", "Home"),
    NOTIF_OPEN("notif_open", "Open notifications panel", "Notifications"),
    NOTIF_CLOSE("notif_close", "Close notifications panel", "Close panel"),
    LAUNCH_APP("launch_app", "Launch app…", "Open app"),
    ;

    companion object {
        fun fromId(id: String?): GestureAction =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}
