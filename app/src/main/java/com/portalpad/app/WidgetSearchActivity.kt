package com.portalpad.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalpad.app.ui.common.VerticalScrollbar
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimaryBright
import com.portalpad.app.ui.theme.AbSurfaceElevated

/**
 * Phone-side text entry for the WIDGET PICKER on the external display. Typed
 * text streams live into [PortalPadApp.widgetPickerQuery]; the picker filters
 * as you type. Pattern follows [SearchActivity] (dock app search): all input
 * and IME stay on the phone, sidestepping cross-display IME focus entirely.
 * The activity is pinned to display 0 by its launcher (the picker's search
 * row) for the same reason SearchActivity is.
 */
class WidgetSearchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as PortalPadApp
        setContent {
            var text by remember { mutableStateOf(app.widgetPickerQuery.value) }
            // Confirmation dialog: state + dialog HOISTED to the top of
            // setContent, outside every list/group scope — the in-list version
            // exhibited a stuck dialog (Cancel/back wouldn't dismiss) and
            // spontaneous opens; hoisting removes the stale-scope failure
            // modes. Dark container colors: the default M3 light dialog made
            // our light-on-dark name text white-on-white (the "missing" name).
            var pendingConfirm by remember {
                mutableStateOf<com.portalpad.app.service.WidgetHostManager.ProviderEntry?>(null)
            }
            var lastGroupToggleAt by remember { mutableStateOf(0L) }
            androidx.activity.compose.BackHandler(enabled = pendingConfirm != null) {
                android.util.Log.d("PortalPadWidget", "phone-pick dialog dismissed (BackHandler)")
                pendingConfirm = null
            }
            pendingConfirm?.let { entry ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {
                        android.util.Log.d("PortalPadWidget", "phone-pick dialog dismissed (outside/back)")
                        pendingConfirm = null
                    },
                    properties = androidx.compose.ui.window.DialogProperties(
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true,
                    ),
                    containerColor = AbSurfaceElevated,
                    titleContentColor = AbOnSurface,
                    textContentColor = AbOnSurfaceMuted,
                    title = { Text("Add to Widget Overlay?") },
                    text = {
                        val dims = "${com.portalpad.app.service.WidgetHostManager.defaultSpan(entry.info.minWidth)}×" +
                            "${com.portalpad.app.service.WidgetHostManager.defaultSpan(entry.info.minHeight)} cells"
                        com.portalpad.app.ui.common.WidgetPreviewCard(
                            info = entry.info,
                            name = entry.label,
                            subtitle = "$dims · ${entry.appLabel}",
                            nameColor = AbOnSurface,
                            subtitleColor = AbOnSurfaceMuted,
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            android.util.Log.d("PortalPadWidget",
                                "phone-pick dialog CONFIRM ${entry.label}")
                            pendingConfirm = null
                            app.emitWidgetPick(entry.info.provider.flattenToString())
                            android.widget.Toast.makeText(
                                this@WidgetSearchActivity,
                                "Adding “${entry.label}” on the external display…",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            finish()
                        }) { Text("Add") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            android.util.Log.d("PortalPadWidget", "phone-pick dialog CANCEL")
                            pendingConfirm = null
                        }) { Text("Cancel") }
                    },
                )
            }
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            Column(
                Modifier
                    .fillMaxSize()
                    .background(AbBackground)
                    .padding(20.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                Text(
                    "Search widgets",
                    color = AbOnSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Results filter live on the external display.",
                    color = AbOnSurfaceMuted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height (14.dp))
                TextField(
                    value = text,
                    onValueChange = {
                        text = it
                        app.setWidgetPickerQuery(it)
                    },
                    placeholder = { Text("Widget or app name…", color = AbOnSurfaceMuted) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = AbSurfaceElevated,
                        unfocusedContainerColor = AbSurfaceElevated,
                        focusedTextColor = AbOnSurface,
                        unfocusedTextColor = AbOnSurface,
                        cursorColor = AbPrimaryBright,
                    ),
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            text = ""
                            app.setWidgetPickerQuery("")
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AbSurfaceElevated,
                            contentColor = AbOnSurface,
                        ),
                    ) { Text("Clear") }
                    Button(
                        onClick = { finish() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AbPrimaryBright,
                            contentColor = Color.White,
                        ),
                    ) { Text("Done") }
                }
                Spacer(Modifier.height(14.dp))
                // Matching widgets, tappable — picking here adds to the layer on
                // the external display through the same path as the on-display
                // picker (its collector consumes the pick flow). Same profile-
                // aware catalog as the display side.
                val providers = remember {
                    com.portalpad.app.service.WidgetHostManager.allProviders(this@WidgetSearchActivity)
                }
                val shown = remember(providers, text) {
                    if (text.isBlank()) providers
                    else providers.filter {
                        it.label.contains(text, ignoreCase = true) ||
                            it.appLabel.contains(text, ignoreCase = true)
                    }
                }
                // Same grouped model as the on-display picker: one collapsible
                // section per app, search auto-expands matching groups.
                val groups = remember(shown) {
                    shown.groupBy { it.appLabel }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                }
                var expandedApps by remember { mutableStateOf(setOf<String>()) }
                val searching = text.isNotBlank()
                val listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = listState) {
                        groups.entries.forEachIndexed { gi, (appName, entries) ->
                            val expanded = searching || appName in expandedApps
                            if (gi > 0) {
                                item(key = "div:$appName") {
                                    Box(
                                        Modifier.fillMaxWidth().height(1.dp)
                                            .background(AbOnSurfaceMuted.copy(alpha = 0.16f)),
                                    )
                                }
                            }
                            item(key = "hdr:$appName") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !searching) {
                                            lastGroupToggleAt = android.os.SystemClock.uptimeMillis()
                                            expandedApps =
                                                if (appName in expandedApps) expandedApps - appName
                                                else expandedApps + appName
                                        }
                                        .padding(vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val icon = remember(appName) {
                                        runCatching {
                                            packageManager.getApplicationIcon(
                                                entries.first().info.provider.packageName,
                                            ).toBitmap().asImageBitmap()
                                        }.getOrNull()
                                    }
                                    if (icon != null) {
                                        Image(icon, contentDescription = null,
                                            modifier = Modifier.size(30.dp))
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    Text(appName, color = AbOnSurface, fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                                        modifier = Modifier.weight(1f))
                                    Text("${entries.size}",
                                        color = AbOnSurfaceMuted, fontSize = 13.sp)
                                    Spacer(Modifier.width(4.dp))
                                    val chevRot by androidx.compose.animation.core.animateFloatAsState(
                                        targetValue = if (expanded) 0f else -90f,
                                        label = "phoneChevron",
                                    )
                                    Icon(
                                        Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = AbOnSurfaceMuted,
                                        modifier = Modifier.size(22.dp).rotate(chevRot),
                                    )
                                }
                            }
                            if (expanded) {
                                items(entries.size, key = { "w:$appName:$it" }) { i ->
                                    val entry = entries[i]
                                    val dims = "${com.portalpad.app.service.WidgetHostManager.defaultSpan(entry.info.minWidth)}×" +
                                        "${com.portalpad.app.service.WidgetHostManager.defaultSpan(entry.info.minHeight)} cells"
                                    val setup = if (entry.info.configure == null) "no setup needed" else "needs setup"
                                    // Whole-card clickable was the collision
                                    // surface for scroll-turned-into-click
                                    // (field x2 despite debounce): the tap
                                    // target is now ONLY this small explicit
                                    // button — a scroll landing anywhere else
                                    // on the card does nothing.
                                    com.portalpad.app.ui.common.WidgetPreviewCard(
                                        info = entry.info,
                                        name = entry.label,
                                        subtitle = "$dims · $setup",
                                        nameColor = AbOnSurface,
                                        subtitleColor = AbOnSurfaceMuted,
                                        trailing = {
                                            Button(onClick = {
                                                val now = android.os.SystemClock.uptimeMillis()
                                                if (now - lastGroupToggleAt > 400L) {
                                                    android.util.Log.d("PortalPadWidget",
                                                        "phone-pick dialog OPEN ${entry.label}")
                                                    pendingConfirm = entry
                                                } else {
                                                    android.util.Log.d("PortalPadWidget",
                                                        "phone-pick tap debounced (${now - lastGroupToggleAt}ms after expand)")
                                                }
                                            }) { Text("Add…") }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    VerticalScrollbar(
                        listState = listState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(4.dp),
                    )
                }
            }
        }
    }
}
