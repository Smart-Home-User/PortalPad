package com.portalpad.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.portalpad.app.PortalPadApp
import com.portalpad.app.ui.theme.AbAccent
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbDanger
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import kotlinx.coroutines.launch

/**
 * Shared color-picker building blocks used by both the nav-button customizer and
 * the program-key appearance editor. Pickers are parameterized purely by
 * [Color] in / out, so any caller can host them. [ColorPickerDialog] adds the
 * saved-presets UI (backed by app prefs) on top.
 */

/**
 * Color picker popup for one target: a live swatch header, the HSV picker, hex +
 * RGB entry, and saved presets (save / apply / remove). Opaque colors only.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ColorPickerDialog(
    title: String,
    color: Color,
    onColorChange: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    val app = PortalPadApp.instance
    val presets = app.prefs.savedColorPresets.collectAsState(initial = emptyList()).value
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(18.dp))
                .background(AbBackground),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(color).border(1.dp, Color(0x33FFFFFF), CircleShape))
                    Text(title, color = AbOnSurface, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        "Done",
                        color = AbAccent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDismiss() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                SimpleColorPicker(color = color, onColorChange = onColorChange)
                ColorHexRgbFields(color = color, onColorChange = onColorChange)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved colors", color = AbOnSurface, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (presets.isNotEmpty()) {
                        Text(
                            "Clear all",
                            color = AbDanger,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    app.injector.buzz(longPress = true)
                                    app.lifecycleScopeLaunch { app.prefs.clearColorPresets() }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF2A2533)).clickable {
                            app.injector.buzz(longPress = false)
                            app.lifecycleScopeLaunch { app.prefs.addColorPreset(color.toArgbInt()) }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Add, contentDescription = "Save color", tint = Color.White, modifier = Modifier.size(20.dp)) }
                    presets.forEach { argb ->
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(Color(argb))
                                .border(1.dp, Color(0x33FFFFFF), CircleShape)
                                .pointerInput(argb) {
                                    detectTapGestures(
                                        onTap = {
                                            app.injector.buzz(longPress = false)
                                            onColorChange(Color(argb))
                                        },
                                        onLongPress = {
                                            app.injector.buzz(longPress = true)
                                            app.lifecycleScopeLaunch { app.prefs.removeColorPreset(argb) }
                                        },
                                    )
                                },
                        )
                    }
                }
                if (presets.isNotEmpty()) {
                    Text("Long-press a color to remove it.", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Hex (#RRGGBB) + R/G/B numeric entry, two-way synced with the picker. */
@Composable
internal fun ColorHexRgbFields(color: Color, onColorChange: (Color) -> Unit) {
    val argb = color.toArgbInt()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val hexNow = String.format("#%02X%02X%02X", r, g, b)
    var hexText by remember(hexNow) { mutableStateOf(hexNow) }
    fun emit(nr: Int, ng: Int, nb: Int) =
        onColorChange(Color((0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = hexText,
            onValueChange = { txt ->
                hexText = txt
                val cleaned = txt.trim().removePrefix("#")
                if (cleaned.length == 6 && cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    val v = cleaned.toLong(16)
                    emit(((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt())
                }
            },
            label = { Text("Hex") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val chan: @Composable (String, Int, (Int) -> Unit) -> Unit = { lbl, cur, set ->
                var t by remember(cur) { mutableStateOf(cur.toString()) }
                OutlinedTextField(
                    value = t,
                    onValueChange = { txt ->
                        t = txt.filter { it.isDigit() }.take(3)
                        t.toIntOrNull()?.let { n -> if (n in 0..255) set(n) }
                    },
                    label = { Text(lbl) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            chan("R", r) { emit(it, g, b) }
            chan("G", g) { emit(r, it, b) }
            chan("B", b) { emit(r, g, it) }
        }
    }
}

/**
 * Minimal HSV color picker: a 2D saturation/value square plus a hue slider.
 * No external dependencies — uses pointer drag to set the color.
 */
@Composable
internal fun SimpleColorPicker(color: Color, onColorChange: (Color) -> Unit) {
    val hsv = remember(color) { color.toHsv() }
    var hue by remember(color) { mutableStateOf(hsv[0]) }
    var sat by remember(color) { mutableStateOf(hsv[1]) }
    var value by remember(color) { mutableStateOf(hsv[2]) }

    fun emit() { onColorChange(hsvToColor(hue, sat, value)) }

    // Saturation/Value square.
    Box(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(hsvToColor(hue, 1f, 1f))
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    sat = (off.x / size.width).coerceIn(0f, 1f)
                    value = (1f - off.y / size.height).coerceIn(0f, 1f)
                    emit()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    sat = (change.position.x / size.width).coerceIn(0f, 1f)
                    value = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                    emit()
                }
            },
    ) {
        // White (left) → transparent gradient for saturation.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color.White, Color.Transparent)),
            ),
        )
        // Transparent → black (bottom) gradient for value.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
            ),
        )
    }

    Spacer(Modifier.height(10.dp))

    // Hue slider.
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.horizontalGradient(
                    (0..360 step 60).map { hsvToColor(it.toFloat(), 1f, 1f) },
                ),
            )
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    hue = ((off.x / size.width) * 360f).coerceIn(0f, 360f)
                    emit()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    hue = ((change.position.x / size.width) * 360f).coerceIn(0f, 360f)
                    emit()
                }
            },
    )
}

// ─── Color helpers ──────────────────────────────────────────────────────────

internal fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

internal fun Color.toHsv(): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(), out,
    )
    return out
}

internal fun hsvToColor(h: Float, s: Float, v: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

// Convenience to avoid importing lifecycleScope everywhere; uses the app's scope.
internal fun PortalPadApp.lifecycleScopeLaunch(block: suspend () -> Unit) {
    this.appScope.launch { block() }
}
