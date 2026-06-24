package com.portalpad.app.presentation

import android.app.PendingIntent
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.portalpad.app.data.NotificationItem
import com.portalpad.app.data.NotificationStore
import com.portalpad.app.service.PortalPadNotificationListenerService
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimaryBright
import com.portalpad.app.ui.theme.AbWarning
import com.portalpad.app.ui.theme.PortalPadTheme

/**
 * A full-screen notification panel on the external display, modeled on
 * [SearchOverlay]. Shows the active notifications captured by
 * [PortalPadNotificationListenerService] into [NotificationStore]; the user
 * navigates it with the trackpad cursor.
 *
 * This exists because the SYSTEM notification shade can't be moved onto a
 * secondary display (SystemUI hosts it on display 0). This is our own panel,
 * opened by three-finger-down and dismissed by three-finger-up (or backdrop tap).
 *
 * v1: a scrollable list (icon / app / title / text), tap-to-open, per-item
 * dismiss, and clear-all. Reply/actions/expansion/grouping are out of scope.
 */
class NotificationPanelOverlay(
    serviceContext: Context,
    val display: Display,
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val overlayHost = OverlayHost.forDisplay(display, serviceContext)
    private val displayContext = overlayHost.context
    private val windowManager = overlayHost.windowManager

    val displayId: Int get() = display.displayId

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val _viewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    private var view: View? = null

    var isShowing: Boolean = false
        private set

    fun show() {
        if (isShowing) return
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val composeView = ComposeView(displayContext).apply {
            setViewTreeLifecycleOwner(this@NotificationPanelOverlay)
            setViewTreeSavedStateRegistryOwner(this@NotificationPanelOverlay)
            setViewTreeViewModelStoreOwner(this@NotificationPanelOverlay)
            setContent { PortalPadTheme { PanelContent() } }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHost.windowType,
            // NOT_FOCUSABLE so the panel persists on the external display after
            // the three-finger gesture ends (a focusable overlay opened mid-
            // gesture loses focus on finger-lift and gets torn down). Like the
            // dock overlay, taps arrive via the injected cursor — the panel needs
            // no IME, so it has no reason to grab focus. DIM_BEHIND still darkens
            // the app behind so it reads as a modal sheet.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            dimAmount = 0.55f
        }

        runCatching { windowManager.addView(composeView, params) }
            .onFailure { Log.e(TAG, "addView failed", it); return }
        view = composeView
        isShowing = true
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        com.portalpad.app.service.PortalPadForegroundService.raiseCursor()
        Log.d(TAG, "Notification panel added to display $displayId")
    }

    fun dismiss() {
        if (!isShowing) return
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        _viewModelStore.clear()
        isShowing = false
    }

    /** Open the app on the EXTERNAL display by resolving its launcher component
     *  and using the same reliable path the dock uses (shell / freeform). We
     *  launch the package rather than the notification's contentIntent because a
     *  PendingIntent can't be reliably retargeted to a secondary display. The
     *  tradeoff: it opens the app's main screen, not the notification's deep link. */
    private fun openAppOnGlasses(packageName: String) {
        val app = com.portalpad.app.PortalPadApp.instance
        val pm = displayContext.packageManager
        val component = runCatching {
            pm.getLaunchIntentForPackage(packageName)?.component?.flattenToShortString()
        }.getOrNull()
        if (component != null) {
            app.launchAppOnExternal(component)
        } else {
            Log.w(TAG, "no launcher component for $packageName")
        }
    }

    /** Fire a notification action's PendingIntent. Best-effort: background actions
     *  (mark-read, archive) work cleanly; UI-opening ones may surface on the phone
     *  on some OEMs since a PendingIntent can't be retargeted to a display. */
    private fun fireActionIntent(pi: PendingIntent) {
        runCatching { pi.send() }.onFailure { Log.w(TAG, "action send failed", it) }
    }

    // ─── UI ─────────────────────────────────────────────────────────────

    @Composable
    private fun PanelContent() {
        val items by NotificationStore.items.collectAsState()
        // Match the dock theme: paint the panel background with the same gradient
        // the dock band uses (read live from prefs), so the notification panel and
        // the quick-settings flyout — both dropping from the dock — look unified.
        // The notification CARDS use a translucent frosted fill so they blend
        // with the gradient instead of reading as flat opaque blocks, while still
        // delineating each card. Text stays the light on-surface tones (legible on
        // the default dark theme).
        val app = com.portalpad.app.PortalPadApp.instance
        val dockConfig by app.prefs.dockConfig.collectAsState(
            initial = com.portalpad.app.data.DockConfig(),
        )
        val colorA = Color(dockConfig.style.colorAInt())
        val colorB = Color(dockConfig.style.colorBInt())
        val panelBrush = com.portalpad.app.ui.common.toComposeBrush(dockConfig.style)
        // Adapt all panel content to the (dock-derived) background luminance, the
        // same way the dock status cluster does: light text/frost on a dark panel,
        // dark text/frost on a light one. Accent colors (avatar, CTA) stay as-is.
        val panelDark = run {
            fun l(c: Color) = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
            (l(colorA) + l(colorB)) / 2f < 0.5f
        }
        val onStrong = if (panelDark) Color.White else Color(0xFF15131A)
        val onMuted = if (panelDark) AbOnSurfaceMuted else Color(0xFF15131A).copy(alpha = 0.55f)
        val frost = if (panelDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
        // Whether PortalPad has notification-listener access. Read when the panel
        // opens; after enabling on the phone, the user reopens the panel.
        val listenerEnabled = remember {
            runCatching {
                android.provider.Settings.Secure.getString(
                    displayContext.contentResolver, "enabled_notification_listeners",
                )?.contains(displayContext.packageName) == true
            }.getOrDefault(false)
        }
        var openedSettings by remember { mutableStateOf(false) }
        // Backdrop: tapping outside the sheet dismisses.
        Box(
            Modifier
                .fillMaxSize()
                .clickable { dismiss() },
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .clip(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp))
                    .background(panelBrush)
                    // Consume clicks so taps inside the sheet don't dismiss.
                    .clickable(enabled = false) {},
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            tint = onStrong,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Notifications",
                            color = onStrong,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (listenerEnabled && items.isNotEmpty()) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(frost)
                                    .clickable {
                                        PortalPadNotificationListenerService.instance?.clearAll()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text("Clear all", color = onStrong, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    when {
                        !listenerEnabled -> AccessOffContent(openedSettings, onStrong, onMuted, frost) {
                            openNotificationSettings()
                            openedSettings = true
                        }
                        items.isEmpty() -> Text(
                            "No notifications",
                            color = onMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(),
                        )
                        else -> LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(items, key = { it.key }) { item ->
                                NotificationRow(item, onStrong, onMuted, frost)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Shown when PortalPad lacks notification-listener access. Explains why the
     *  panel is empty and offers a button that opens the system settings on the
     *  PHONE (system Settings can't be placed on the external display). */
    @Composable
    private fun AccessOffContent(
        opened: Boolean,
        onStrong: Color,
        onMuted: Color,
        frost: Color,
        onEnable: () -> Unit,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(
                "Notification access is off",
                color = onStrong,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "PortalPad needs notification access to show your notifications here.",
                color = onMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(frost)
                    .clickable { onEnable() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Enable on phone",
                    color = AbPrimaryBright,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (opened) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Check your phone — enable PortalPad in the list, then reopen this panel.",
                    color = AbWarning,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    /** Open the notification-listener settings on the PHONE (deep-link to
     *  PortalPad's toggle, falling back to the general list). System Settings
     *  can't be reliably placed on the external display, so the panel directs the
     *  user to their phone. */
    private fun openNotificationSettings() {
        val app = com.portalpad.app.PortalPadApp.instance
        val component = "${app.packageName}/${app.packageName}.service.PortalPadNotificationListenerService"
        // Dismiss the panel first so it isn't left on the glasses while the user
        // is on their phone enabling access.
        dismiss()
        runCatching {
            app.startActivity(
                android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                    .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", component)
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    ),
            )
        }.onFailure {
            runCatching {
                app.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
                        ),
                )
            }.onFailure { e -> Log.w(TAG, "open notification settings failed", e) }
        }
    }

    @Composable
    private fun NotificationRow(
        item: NotificationItem,
        onStrong: Color,
        onMuted: Color,
        frost: Color,
    ) {
        var menuOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(frost)
                .clickable {
                    openAppOnGlasses(item.packageName)
                    dismiss()
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // App icon (drawable → bitmap). Falls back to a dot if unavailable.
            val bmp = rememberDrawableBitmap(item)
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp).clip(CircleShape),
                )
            } else {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(AbPrimaryBright),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.appLabel,
                    color = onMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.title.isNotBlank()) {
                    Text(
                        item.title,
                        color = onStrong,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.text.isNotBlank()) {
                    Text(
                        item.text,
                        color = onStrong,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Overflow menu: Open app (on glasses), each non-reply action, Dismiss.
            Spacer(Modifier.width(8.dp))
            Box {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .clickable { menuOpen = true }
                        .padding(6.dp),
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Actions",
                        tint = onMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(frost),
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Open app", color = onStrong) },
                        onClick = {
                            menuOpen = false
                            openAppOnGlasses(item.packageName)
                            dismiss()
                        },
                    )
                    item.actions.forEach { action ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(action.title, color = onStrong) },
                            onClick = {
                                menuOpen = false
                                fireActionIntent(action.intent)
                            },
                        )
                    }
                    if (item.dismissable) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Dismiss", color = AbPrimaryBright) },
                            onClick = {
                                menuOpen = false
                                PortalPadNotificationListenerService.instance?.dismiss(item.key)
                            },
                        )
                    }
                }
            }
        }
    }

    /** Render the notification's Drawable icon into a Compose ImageBitmap. */
    @Composable
    private fun rememberDrawableBitmap(item: NotificationItem): androidx.compose.ui.graphics.ImageBitmap? {
        val d = item.icon ?: return null
        return androidx.compose.runtime.remember(item.key) {
            runCatching {
                val w = (if (d.intrinsicWidth > 0) d.intrinsicWidth else 96).coerceAtMost(192)
                val h = (if (d.intrinsicHeight > 0) d.intrinsicHeight else 96).coerceAtMost(192)
                val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                d.setBounds(0, 0, w, h)
                d.draw(canvas)
                bitmap.asImageBitmap()
            }.getOrNull()
        }
    }

    companion object {
        private const val TAG = "NotifPanel"
    }
}
