package com.portalpad.app.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.portalpad.app.PortalPadApp
import com.portalpad.app.ui.common.ColorPickerDialog
import com.portalpad.app.ui.common.lifecycleScopeLaunch
import com.portalpad.app.ui.common.toArgbInt
import com.portalpad.app.data.ButtonAppearance
import com.portalpad.app.data.ButtonIcons
import com.portalpad.app.data.DEFAULT_ICON_TINT
import com.portalpad.app.data.DEFAULT_NAV_BG
import com.portalpad.app.ui.theme.PortalPadTheme
import com.portalpad.app.ui.theme.AbDanger
import com.portalpad.app.ui.theme.AbAccent
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbSurfaceElevated
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbBackground
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Full-screen customization for a navigation button's appearance: pick an icon
 * (searchable), a background color, and an icon color. Background/icon colors
 * use a simple HSV picker; the user can save up to 10 custom color presets.
 *
 * Launched with an extra "target" = "back" | "home" | "appdrawer" | "keyboard".
 * Saves live to prefs so the
 * trackpad picks up changes immediately.
 */
class ButtonCustomizeActivity : com.portalpad.app.PinnedDensityActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent?.getStringExtra("target") ?: "back"
        setContent {
            PortalPadTheme {
                CustomizeScreen(target = target, onDone = { finish() })
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CustomizeScreen(target: String, onDone: () -> Unit) {
    val app = PortalPadApp.instance
    val defaultFallbackIcon = when (target) {
        "home" -> "home"
        "appdrawer" -> "auto_awesome"
        "keyboard" -> "keyboard"
        else -> "arrow_back"
    }

    val stored = when (target) {
        "home" -> app.prefs.homeButtonStyle
        "appdrawer" -> app.prefs.appDrawerButtonStyle
        "keyboard" -> app.prefs.keyboardButtonStyle
        else -> app.prefs.backButtonStyle
    }.collectAsState(initial = null).value

    // Per-target default colors must match each button's ACTUAL rendered default
    // (see TrackpadBottomBar). The keyboard pill is intentionally the darker
    // elevated-surface shade with a muted glyph so it reads as separate from the
    // purple Back/Home/App-drawer pills — seeding it with the generic nav purple
    // would make a save flatten it to look like the others.
    val defaultBg = if (target == "keyboard") AbSurfaceElevated.toArgbInt() else DEFAULT_NAV_BG
    val defaultIcon = if (target == "keyboard") AbOnSurfaceMuted.toArgbInt() else DEFAULT_ICON_TINT

    var iconId by remember(stored) { mutableStateOf(stored?.iconId ?: defaultFallbackIcon) }
    var bgColor by remember(stored) { mutableStateOf(Color(stored?.bgColor ?: defaultBg)) }
    var iconColor by remember(stored) { mutableStateOf(Color(stored?.iconColor ?: defaultIcon)) }
    // User-picked custom icon path (a pack icon baked to PNG; overrides the vector
    // icon and is drawn untinted).
    var customIconPath by remember(stored) { mutableStateOf(stored?.customIconPath) }
    // Which color the color dialog edits: true = background, false = icon glyph.
    var editingBg by remember { mutableStateOf(true) }
    // Popups: each heavy editor opens on demand so the page stays compact.
    var showIconPicker by remember { mutableStateOf(false) }   // icon-pack browser
    var showPresetIcons by remember { mutableStateOf(false) }  // built-in vector grid
    var showColorDialog by remember { mutableStateOf(false) }  // HSV + hex/rgb + presets

    fun persist() {
        app.lifecycleScopeLaunch {
            app.prefs.setButtonStyle(
                target,
                ButtonAppearance(
                    iconId = iconId,
                    bgColor = bgColor.toArgbInt(),
                    iconColor = iconColor.toArgbInt(),
                    customIconPath = customIconPath,
                ),
            )
        }
    }

    if (showIconPicker) {
        IconPackPickerDialog(
            target = target,
            onPicked = { path ->
                customIconPath = path
                editingBg = true // pack icons are untinted, so Icon color no longer applies
                persist()
                app.injector.buzz(longPress = false)
            },
            onDismiss = { showIconPicker = false },
        )
    }
    if (showPresetIcons) {
        PresetIconsDialog(
            selectedIconId = iconId,
            hasCustomIcon = customIconPath != null,
            bgColor = bgColor,
            iconColor = iconColor,
            onPick = { id ->
                iconId = id
                customIconPath = null
                persist()
                showPresetIcons = false
            },
            onDismiss = { showPresetIcons = false },
        )
    }
    if (showColorDialog) {
        ColorPickerDialog(
            title = if (editingBg) "Background color" else "Icon color",
            color = if (editingBg) bgColor else iconColor,
            onColorChange = {
                if (editingBg) bgColor = it else iconColor = it
                persist()
            },
            onDismiss = { showColorDialog = false },
        )
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            when (target) {
                "home" -> "Customize Home button"
                "appdrawer" -> "Customize App Drawer button"
                "keyboard" -> "Customize Keyboard button"
                else -> "Customize Back button"
            },
            style = MaterialTheme.typography.titleLarge,
        )

        // Live preview pill.
        Box(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(bgColor),
            contentAlignment = Alignment.Center,
        ) {
            com.portalpad.app.ui.common.ButtonGlyph(
                appearance = ButtonAppearance(
                    iconId = iconId,
                    bgColor = bgColor.toArgbInt(),
                    iconColor = iconColor.toArgbInt(),
                    customIconPath = customIconPath,
                ),
                fallback = ButtonIcons.ALL[defaultFallbackIcon]!!,
                tint = iconColor,
                contentDescription = null,
            )
        }

        // Color swatches — tap one to open its color picker popup.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorTargetChip("Background", swatch = bgColor) {
                editingBg = true; showColorDialog = true
            }
            ColorTargetChip("Icon", swatch = iconColor, enabled = customIconPath == null) {
                editingBg = false; showColorDialog = true
            }
        }
        if (customIconPath != null) {
            Text(
                "Icon color doesn't apply to a custom icon — it's shown in the pack's own colors.",
                color = AbOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Icon source — custom (from an installed icon-pack app) or the built-in
        // preset set. Each opens in a popup so this page stays short.
        // Hidden for the keyboard button: it intentionally has no icon picker
        // (custom or preset) to avoid confusion later — only background color and
        // icon color are customizable there.
        if (target != "keyboard") {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AbAccent.copy(alpha = 0.14f))
                .border(1.dp, AbAccent.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .clickable { showIconPicker = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = AbAccent, modifier = Modifier.size(20.dp))
            Text(
                if (customIconPath == null) "Choose a custom icon" else "Change custom icon",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (customIconPath != null) {
                Text(
                    "Remove",
                    color = AbDanger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { customIconPath = null; persist() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else {
                Text("\u203A", color = AbAccent)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF221E2B))
                .border(1.dp, AbOnSurfaceMuted.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .clickable { showPresetIcons = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Preset icons",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text("\u203A", color = AbOnSurfaceMuted)
        }
        } // end if (target != "keyboard") — no icon picker for the keyboard button

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    iconId = defaultFallbackIcon
                    bgColor = Color(DEFAULT_NAV_BG)
                    iconColor = Color(DEFAULT_ICON_TINT)
                    customIconPath = null
                    editingBg = true
                    app.lifecycleScopeLaunch { app.prefs.resetButtonStyle(target) }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Reset to default") }
            Button(onClick = { persist(); onDone() }, modifier = Modifier.weight(1f)) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun ColorTargetChip(
    label: String,
    swatch: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF221E2B))
            .border(1.dp, AbOnSurfaceMuted.copy(alpha = 0.35f * alpha), RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(swatch.copy(alpha = alpha)).border(1.dp, Color(0x33FFFFFF), CircleShape))
        Text(label, color = Color.White.copy(alpha = alpha), style = MaterialTheme.typography.bodyMedium)
        Text("\u203A", color = AbOnSurfaceMuted.copy(alpha = alpha)) // › tap to open
    }
}


/** Popup grid of the built-in vector icons, with search. Tapping returns the id. */
@Composable
private fun PresetIconsDialog(
    selectedIconId: String,
    hasCustomIcon: Boolean,
    bgColor: Color,
    iconColor: Color,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) ButtonIcons.ALL.keys.toList()
        else ButtonIcons.ALL.keys.filter { ButtonIcons.label(it).contains(query.trim(), ignoreCase = true) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(18.dp))
                .background(AbBackground),
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Preset icons", color = AbOnSurface, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        "\u2715",
                        color = AbOnSurfaceMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDismiss() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search icons") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(56.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered) { id ->
                        val selected = id == selectedIconId && !hasCustomIcon
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) bgColor else Color(0xFF221E2B))
                                .then(
                                    if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp))
                                    else Modifier
                                )
                                .clickable { onPick(id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                ButtonIcons.ALL[id]!!,
                                contentDescription = id,
                                tint = if (selected) iconColor else Color(0xFFB8B2C8),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

