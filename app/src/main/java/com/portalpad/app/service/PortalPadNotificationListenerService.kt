package com.portalpad.app.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.portalpad.app.data.NotificationItem
import com.portalpad.app.data.NotificationStore

/**
 * Notification listener. Originally a no-op stub kept only so that
 * [android.media.session.MediaSessionManager.getActiveSessions] works for the
 * [MediaController]. It now ALSO captures active notifications into
 * [NotificationStore] so the on-glasses notification panel can show them.
 *
 * The user enables it from Settings → Apps → Special access → Notification
 * access → PortalPad. The "Tap to fix" button in the System tab opens this
 * directly. (The permission is required for media keys regardless.)
 */
class PortalPadNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refresh()
    }

    /** Rebuild the active-notification snapshot from the current set. */
    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val pm = packageManager
        val items = active.mapNotNull { sbn -> toItem(sbn, pm) }
        NotificationStore.setItems(items)
    }

    private fun toItem(sbn: StatusBarNotification, pm: PackageManager): NotificationItem? {
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()

        // Skip group summaries and entries with no usable content — they read as
        // empty rows in the panel.
        val isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) return null
        if (title.isBlank() && text.isBlank()) return null

        val appLabel = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)

        val icon: android.graphics.drawable.Drawable? = runCatching {
            n.smallIcon?.loadDrawable(this)
        }.getOrNull() ?: runCatching {
            pm.getApplicationIcon(sbn.packageName)
        }.getOrNull()

        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val dismissable = sbn.isClearable && !ongoing

        // Capture action buttons, excluding reply / RemoteInput ones (they need a
        // text-input flow we don't support here) and any without an intent.
        val actions = n.actions?.mapNotNull { a ->
            val hasRemoteInput = a.remoteInputs?.isNotEmpty() == true
            val pi = a.actionIntent
            val title = a.title?.toString().orEmpty()
            if (hasRemoteInput || pi == null || title.isBlank()) null
            else com.portalpad.app.data.NotificationAction(title = title, intent = pi)
        }.orEmpty()

        return NotificationItem(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = text,
            postTime = sbn.postTime,
            icon = icon,
            contentIntent = n.contentIntent,
            actions = actions,
            dismissable = dismissable,
        )
    }

    /** Cancel one notification by key (called by the panel's dismiss action). */
    fun dismiss(key: String) {
        runCatching { cancelNotification(key) }
            .onFailure { Log.w(TAG, "cancelNotification failed", it) }
    }

    /** Clear all clearable notifications (the panel's "Clear all"). */
    fun clearAll() {
        runCatching { cancelAllNotifications() }
            .onFailure { Log.w(TAG, "cancelAllNotifications failed", it) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    companion object {
        private const val TAG = "NotifListener"
        /** Live reference so the panel can dismiss/clear via the connected service. */
        @Volatile
        var instance: PortalPadNotificationListenerService? = null
            private set
    }
}
