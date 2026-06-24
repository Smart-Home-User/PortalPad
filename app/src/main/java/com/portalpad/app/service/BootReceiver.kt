package com.portalpad.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.portalpad.app.PortalPadApp
import com.portalpad.app.data.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Auto-starts the [PortalPadForegroundService] when the device finishes booting
 * — but only if the user has the "Start on boot" pref enabled.
 *
 * Registered for [Intent.ACTION_BOOT_COMPLETED] + [Intent.ACTION_LOCKED_BOOT_COMPLETED]
 * + [Intent.ACTION_MY_PACKAGE_REPLACED] (the last one re-launches us cleanly
 * after we ourselves get updated).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received $action")

        // Only act on the boot-class actions
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val app = context.applicationContext as? PortalPadApp ?: return
        val enabled = runCatching {
            runBlocking {
                app.prefs.bool(PreferencesRepository.Keys.START_ON_BOOT, default = false).first()
            }
        }.getOrDefault(false)

        if (!enabled) {
            Log.d(TAG, "Start-on-boot pref off, skipping")
            return
        }

        // Background-launching a foreground service has restrictions on Android 12+.
        // The BOOT_COMPLETED context grants us the start, but to be safe we use the
        // standard start helper.
        try {
            PortalPadForegroundService.start(context)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start service on boot", t)
        }
    }

    companion object { private const val TAG = "BootReceiver" }
}
