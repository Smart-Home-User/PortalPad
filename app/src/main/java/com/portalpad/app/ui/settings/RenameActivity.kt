package com.portalpad.app.ui.settings

import android.os.Bundle
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.portalpad.app.PortalPadApp
import com.portalpad.app.applyForcedOrientation
import com.portalpad.app.lockOrientationDefault
import com.portalpad.app.ui.dock.renameDockItem
import com.portalpad.app.ui.theme.AbAccent
import com.portalpad.app.ui.theme.AbBackground
import com.portalpad.app.ui.theme.AbOnSurface
import com.portalpad.app.ui.theme.AbOnSurfaceMuted
import com.portalpad.app.ui.theme.AbPrimaryBright
import com.portalpad.app.ui.theme.AbSurface
import com.portalpad.app.ui.theme.AbSurfaceElevated
import com.portalpad.app.ui.theme.PortalPadTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Rename a dock item (folder or shortcut) ON THE PHONE. The old rename dialog was
 * a Compose AlertDialog hosted on the external-display VD overlay; bringing up the
 * IME there destabilized the display (trackpad flicker → black wallpaper → phone
 * bounced to the start screen). Text input is reliable in a real phone activity,
 * so rename lives here, forced to the default display.
 */
class RenameActivity : com.portalpad.app.PinnedDensityActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockOrientationDefault()
        applyForcedOrientation()
        val itemId = intent.getStringExtra("itemId")
        if (itemId.isNullOrBlank()) { finish(); return }
        val current = intent.getStringExtra("current").orEmpty()
        setContent {
            PortalPadTheme {
                RenameScreen(
                    current = current,
                    onCancel = { finish() },
                    onSave = { newLabel ->
                        lifecycleScope.launch {
                            val cfg = PortalPadApp.instance.prefs.dockConfig.first()
                            PortalPadApp.instance.prefs.setDockConfig(
                                renameDockItem(cfg, itemId, newLabel),
                            )
                            finish()
                        }
                    },
                )
            }
        }
    }

    @Composable
    private fun RenameScreen(current: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
        var text by remember { mutableStateOf(TextFieldValue(current, androidx.compose.ui.text.TextRange(current.length))) }
        Box(
            Modifier.fillMaxSize().background(AbBackground).padding(20.dp),
        ) {
            Column(Modifier.fillMaxWidth().align(Alignment.TopStart)) {
                Text(
                    "Rename",
                    color = AbOnSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AbOnSurface, unfocusedTextColor = AbOnSurface,
                        focusedBorderColor = AbPrimaryBright, unfocusedBorderColor = AbSurface,
                        cursorColor = AbPrimaryBright,
                    ),
                )
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(AbSurfaceElevated).clickable { onCancel() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Cancel", color = AbOnSurfaceMuted) }
                    val canSave = text.text.isNotBlank()
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(if (canSave) AbPrimaryBright else AbSurfaceElevated)
                            .clickable(enabled = canSave) { onSave(text.text.trim()) }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Save",
                            color = if (canSave) AbBackground else AbOnSurfaceMuted,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
