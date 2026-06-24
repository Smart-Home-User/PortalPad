package com.portalpad.app.service

import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService

/**
 * A libsu [RootService] that runs in a separate process AS ROOT (uid 0) and
 * hands back the SAME [ShellUserService] binder we use over Shizuku. libsu
 * (topjohnwu / Magisk author) handles the app_process bootstrap + binder
 * handoff that would otherwise be fragile to hand-roll.
 *
 * Because [ShellUserService] is a plain [IShellService.Stub] that only needs a
 * Context (which a RootService provides) and otherwise works by reflection, the
 * exact same privileged implementation runs here — just at uid 0 instead of
 * shell uid 2000. Root passes Android's permission checks (ROOT_UID is treated
 * as granted), so input injection, trusted VirtualDisplay creation, IME policy,
 * and surface mirroring all work the same as the Shizuku path.
 *
 * Must be declared in AndroidManifest.xml as a <service>. libsu launches it in
 * the root process when [RootService.bind] is called.
 */
class RootShellService : RootService() {
    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "RootShellService.onBind — constructing ShellUserService as root")
        // `this` (the RootService) is a Context running as root.
        return ShellUserService(this)
    }

    companion object {
        private const val TAG = "RootShellService"
    }
}
