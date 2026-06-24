package com.portalpad.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * Tiny, no-UI activity whose only job is to request the RECORD_AUDIO runtime
 * permission. The voice-search mic lives in an overlay/service process, which
 * can't itself prompt for a runtime permission — so when the mic is tapped
 * without the permission, we launch this on the phone to show the system
 * dialog. After the user responds we just finish; they tap mic again to use it.
 */
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockOrientationDefault()
        applyForcedOrientation()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Toast.makeText(
            this,
            if (granted) "Microphone enabled — tap the mic again to search by voice"
            else "Microphone permission denied",
            Toast.LENGTH_LONG,
        ).show()
        finish()
    }

    companion object {
        private const val REQ = 7321
    }
}
