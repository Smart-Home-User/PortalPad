package com.portalpad.app.ui.trackpad

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A phone-side consent request for an app on the external display whose
 * runtime-permission dialog rendered there as an unusable black transparent
 * window. Carried from FreeformManager (via the service) to the foreground
 * trackpad's Compose UI through [com.portalpad.app.TrackpadActivity.permConsentTrigger].
 *
 * WHY it's hosted inside TrackpadActivity's own composition: One UI buried
 * BOTH a service-owned TYPE_APPLICATION_OVERLAY window AND a separate
 * dialog-themed activity (own task) UNDER the foreground trackpad activity —
 * proven on hardware twice. A dialog composed by the resumed foreground
 * activity itself is the one thing guaranteed to be on top of it.
 *
 * @param groups permission-group label → the raw permission names in it,
 *   grouped the way Android's own App-info page does.
 * @param onDecision Allow/Deny-all pressed; receives the raw permissions of
 *   every CHECKED group (empty = deny all). Caller pm-grants and relaunches.
 * @param onDismissed back / tap-outside — "ask me later": no grants, no
 *   relaunch.
 */
data class PermConsentRequest(
    val appLabel: String,
    val groups: List<Pair<String, List<String>>>,
    val onDecision: (approved: List<String>) -> Unit,
    val onDismissed: () -> Unit,
)

/** The consent dialog itself: one checkbox per permission GROUP (all checked
 *  by default), Deny all / Allow. [onClose] clears the host's request state —
 *  always invoked exactly once, after the request callback. */
@Composable
fun PermissionConsentDialog(request: PermConsentRequest, onClose: () -> Unit) {
    val checked = remember(request) {
        mutableStateListOf<Boolean>().apply { repeat(request.groups.size) { add(true) } }
    }
    AlertDialog(
        onDismissRequest = {
            request.onDismissed()
            onClose()
        },
        title = { Text("${request.appLabel} would like access to:") },
        text = {
            Column {
                request.groups.forEachIndexed { i, (label, _) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { checked[i] = !checked[i] },
                    ) {
                        Checkbox(
                            checked = checked[i],
                            onCheckedChange = { checked[i] = it },
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Its permission prompt can't be used on the external display. " +
                        "Choices you allow are granted before the app reopens there.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val approved = request.groups
                    .filterIndexed { i, _ -> checked[i] }
                    .flatMap { it.second }
                request.onDecision(approved)
                onClose()
            }) { Text("Allow") }
        },
        dismissButton = {
            TextButton(onClick = {
                request.onDecision(emptyList())
                onClose()
            }) { Text("Deny all") }
        },
    )
}
