package com.portalpad.app.data

import android.app.PendingIntent
import android.graphics.drawable.Drawable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One tappable notification action (e.g. "Mark as read", "Archive"). Reply /
 *  RemoteInput actions are excluded upstream since they need a text-input flow. */
data class NotificationAction(
    val title: String,
    val intent: PendingIntent,
)

/**
 * A flattened, UI-ready snapshot of one active status-bar notification. Built by
 * [com.portalpad.app.service.PortalPadNotificationListenerService] from a
 * StatusBarNotification and consumed by the on-glasses notification panel.
 *
 * We deliberately keep this simple (icon / app / title / text / time + a content
 * intent + dismissibility) — group summaries, big-text, custom RemoteViews,
 * inline actions and reply are out of scope for v1.
 */
data class NotificationItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTime: Long,
    /** Small icon as a Drawable (already loaded), if resolvable. */
    val icon: Drawable?,
    /** Tapping fires this (the notification's contentIntent), if present. */
    val contentIntent: PendingIntent?,
    /** Non-reply action buttons the notification offers. */
    val actions: List<NotificationAction>,
    /** False for ongoing / non-clearable notifications (no dismiss affordance). */
    val dismissable: Boolean,
)

/**
 * Process-wide live store of the active notifications the listener currently
 * sees. The panel observes [items]; the listener service writes to it.
 */
object NotificationStore {
    private val _items = MutableStateFlow<List<NotificationItem>>(emptyList())
    val items: StateFlow<List<NotificationItem>> = _items

    fun setItems(list: List<NotificationItem>) {
        _items.value = list.sortedByDescending { it.postTime }
    }
}
