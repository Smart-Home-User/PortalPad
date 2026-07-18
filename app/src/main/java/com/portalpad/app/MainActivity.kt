package com.portalpad.app

import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.activity.result.contract.ActivityResultContracts
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import com.portalpad.app.data.BackupManager
import com.portalpad.app.data.BackupWorker
import com.portalpad.app.data.PreferencesRepository
import com.portalpad.app.service.PortalPadForegroundService
import com.portalpad.app.ui.settings.SettingsScreen
import com.portalpad.app.ui.theme.PortalPadTheme
import com.portalpad.app.data.AccessMode
import com.portalpad.app.shizuku.ShizukuManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : PinnedDensityActivity() {

    // Drives the readiness dialog (overlay / privilege / accessibility). Shown
    // by enable() OR launch-trackpad when a prerequisite is missing; the dialog
    // renders in setContent and lets the user fix each item inline. The action
    // to run once ready / on "Continue anyway" is stored here so the same dialog
    // serves both the Enable and Launch-trackpad paths.
    private val showReadinessDialog = mutableStateOf(false)
    private var readinessProceedAction: () -> Unit = {}

    // ─── Storage Access Framework launchers ─────────────────────────────────
    //
    // Folder picker — returns a persistable URI we save into prefs and use for
    // all future backup writes.
    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // Persist read+write access across reboots
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure { Log.w(TAG, "takePersistableUriPermission failed", it) }
        lifecycleScope.launch {
            (application as PortalPadApp).prefs.rawDataStore.edit {
                it[PreferencesRepository.Keys.BACKUP_FOLDER_URI] = uri.toString()
            }
        }
        toast("Backup folder set")
    }

    // File picker for restore — opens single JSON files
    private val restorePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = BackupManager(this@MainActivity).restoreFrom(uri)
            toast(if (ok) "Settings restored — restart app to fully apply"
                  else "Restore failed — file may be invalid or unreadable")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the system splash (androidx core-splashscreen) before super so
        // the branded animated-icon splash shows on launch, then hands off to the
        // app theme. Must precede super.onCreate().
        // Timed hold: WITHOUT a keep-condition the system dismisses the splash
        // at the app's first frame, cutting the icon animation short whenever
        // the activity wins the race (field report). Hold ≈ the AVD's ~880ms
        // run + a 1s rest so the animation always completes, then release.
        // Trade-off: every cold launch shows the splash this long.
        val splash = installSplashScreen()
        var keepSplash = true
        splash.setKeepOnScreenCondition { keepSplash }
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ keepSplash = false }, 1900L)
        super.onCreate(savedInstanceState)
        lockOrientationDefault()
        // Throttled update auto-check (v1.3+): at most once per 24h, only when
        // the user toggle is on and a network is up; silent on every failure.
        // Also refreshes the top-bar "« New" badge from the offline cache.
        com.portalpad.app.service.UpdateChecker.autoCheckOnLaunch(applicationContext)
        // Mirror TrackpadActivity: when the user has opted to drive PortalPad over
        // the lock screen (ALLOW_LOCKSCREEN), let the settings host show over the
        // keyguard too. Without this, tapping the gear from the over-lock trackpad
        // silently bounced to the lock screen, because MainActivity had no
        // showWhenLocked flag. Same consent toggle, so no new exposure beyond what
        // the user already accepted for the trackpad. NOTE: deeper SYSTEM screens
        // (e.g. Android permission settings) opened from here are separate
        // activities and still require unlocking — unavoidable, they're not ours.
        val showOverLock = runCatching {
            kotlinx.coroutines.runBlocking {
                PortalPadApp.instance.prefs.bool(
                    com.portalpad.app.data.PreferencesRepository.Keys.ALLOW_LOCKSCREEN,
                    true,
                ).first()
            }
        }.getOrDefault(true)
        // Only ride over the keyguard while an external display is actually
        // connected; the ALLOW_LOCKSCREEN pref remains the master switch.
        // BIRTH ATTRIBUTE first (unconditional, like the pre-gating build) —
        // WM reliably honors a window born with SHOW_WHEN_LOCKED, while a
        // runtime change isn't re-evaluated until a relayout (see
        // TrackpadActivity). The collector's immediate first emission applies
        // the display-gated value within the same frame.
        setShowWhenLocked(showOverLock)
        if (showOverLock) {
            lifecycleScope.launch {
                PortalPadApp.instance.physicalExternalDisplayId.collect { phys ->
                    applyShowWhenLocked(phys != null)
                }
            }
        }
        // If the glasses are plugged in while the phone is locked, the desktop
        // can't extend onto them (the system keyguard claims the display) — prompt
        // the unlock so the user isn't stuck staring at two lock screens.
        promptUnlockWhenDisplayAttachesLocked(enabled = showOverLock)
        setContent {
            // Apply the orientation preference live: when the user changes it in
            // Settings (which is hosted right here), this re-applies immediately
            // instead of waiting for the next onResume.
            val app = application as PortalPadApp
            val orientation by app.prefs
                .string(com.portalpad.app.data.PreferencesRepository.Keys.FORCE_ORIENTATION, OrientationLock.DEFAULT)
                .collectAsState(initial = OrientationLock.DEFAULT)
            LaunchedEffect(orientation) { OrientationLock.apply(this@MainActivity, orientation) }
            PortalPadTheme {
                val openPrivilege = intent?.getBooleanExtra(EXTRA_OPEN_PRIVILEGE, false) ?: false
                val openSetup = intent?.getBooleanExtra(EXTRA_OPEN_SETUP, false) ?: false
                SettingsScreen(
                    openToPrivilege = openPrivilege,
                    openToSetup = openSetup,
                    onLaunchTrackpad = ::launchTrackpad,
                    onEnableClick = ::enable,
                    onPickHome = { startPicker("home") },
                    onPickBack = { startPicker("back") },
                    onPickDock = { startPicker("dock") },
                    onAuthorizeShizuku = {
                        (application as PortalPadApp).shizuku.requestPermission()
                    },
                    onOpenOverlaySettings = {
                        runCatching {
                            startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    .setData(Uri.parse("package:$packageName"))
                            )
                        }.onFailure { toast("Couldn't open overlay settings: ${it.message}") }
                    },
                    onOpenNotificationListenerSettings = {
                        runCatching {
                            startActivity(
                                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                                    .putExtra(
                                        "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                                        "$packageName/${packageName}.service.PortalPadNotificationListenerService",
                                    )
                            )
                        }.recoverCatching {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }.onFailure {
                            toast("Couldn't open notification access settings: ${it.message}")
                        }
                    },
                    onPickBackupFolder = { folderPicker.launch(null) },
                    onBackupNow = {
                        lifecycleScope.launch {
                            val name = BackupManager(this@MainActivity).backupNow()
                            toast(
                                if (name != null) "Backup saved: $name"
                                else "Backup failed — pick a folder first?",
                            )
                        }
                    },
                    onRestoreFromFile = {
                        restorePicker.launch(arrayOf("application/json", "*/*"))
                    },
                    onScheduleBackup = { dayOfWeek, hour ->
                        lifecycleScope.launch {
                            (application as PortalPadApp).prefs.rawDataStore.edit {
                                if (dayOfWeek < 0) {
                                    // Special "off" sentinel
                                    it[PreferencesRepository.Keys.BACKUP_FREQUENCY] = "off"
                                } else {
                                    it[PreferencesRepository.Keys.BACKUP_FREQUENCY] = "scheduled"
                                    it[PreferencesRepository.Keys.BACKUP_DAY] = dayOfWeek
                                    it[PreferencesRepository.Keys.BACKUP_HOUR] = hour
                                }
                            }
                            if (dayOfWeek < 0) {
                                BackupWorker.cancel(this@MainActivity)
                                toast("Scheduled backups disabled")
                            } else {
                                BackupWorker.schedule(this@MainActivity, dayOfWeek, hour)
                                val dayLabel = when (dayOfWeek) {
                                    0 -> "every day"
                                    1 -> "every Monday"
                                    2 -> "every Tuesday"
                                    3 -> "every Wednesday"
                                    4 -> "every Thursday"
                                    5 -> "every Friday"
                                    6 -> "every Saturday"
                                    7 -> "every Sunday"
                                    else -> "scheduled"
                                }
                                toast("Backup will run $dayLabel at ${hour}:00")
                            }
                        }
                    },
                )

                if (showReadinessDialog.value) {
                    ReadinessDialog(
                        onDismiss = { showReadinessDialog.value = false },
                        onContinueAnyway = { proceedReadiness() },
                        onFixOverlay = {
                            runCatching {
                                startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                        .setData(Uri.parse("package:$packageName")),
                                )
                            }.onFailure { toast("Couldn't open overlay settings: ${it.message}") }
                        },
                        onFixPrivilege = {
                            // Jump to the Privilege Source page where Shizuku/Root
                            // is authorized (same flow as Settings → Privilege source).
                            startActivity(
                                Intent(this@MainActivity, MainActivity::class.java)
                                    .putExtra(EXTRA_OPEN_PRIVILEGE, true),
                            )
                        },
                    )
                }
            }
        }
    }

    /** setShowWhenLocked + a FORCED RELAYOUT so WindowManager re-evaluates
     *  keyguard occlusion NOW. A bare runtime setShowWhenLocked isn't picked
     *  up until the next incidental relayout (measured on-device: first
     *  screen-off after a runtime-only change showed the lock screen; the
     *  second worked only because an unrelated relayout had shipped the
     *  flag in between). Logged so captures show our side of any race.
     */
    private fun applyShowWhenLocked(show: Boolean) {
        runCatching {
            setShowWhenLocked(show)
            // Attribute self-assignment forces a relayout of this window,
            // pushing the updated flag to WM immediately.
            window.attributes = window.attributes
            Log.d(TAG, "applyShowWhenLocked($show) + relayout")
        }
    }

    override fun onResume() {
        super.onResume()
        applyForcedOrientation()
        // Re-run the (throttled) update check when the main screen comes back to
        // the foreground, so someone who keeps resuming the app still gets fresh
        // checks + the spinning Sync icon. Fire-and-forget and throttled, so it
        // dedupes with the onCreate call and never checks on the display-connect
        // auto-launch path (that starts the service, not this activity).
        com.portalpad.app.service.UpdateChecker.autoCheckOnLaunch(applicationContext)
        // Refresh access state — user may have just enabled Shizuku in another
        // app, or granted root externally. Without this the UI stays stale.
        val app = application as PortalPadApp
        app.shizuku.refresh()
        // Auto-bind UserService if user picked Shizuku and it's now ready
        kotlinx.coroutines.runBlocking {
            val mode = app.prefs.accessMode.first()
            // Strict opt-in: only probe root (which can trigger the su prompt)
            // when the user has actually selected Root. Avoids an unprompted
            // root-grant dialog on a rooted device for Shizuku/None users.
            if (mode == com.portalpad.app.data.AccessMode.ROOT) {
                runCatching { app.root.refresh() }
            }
            if (mode == com.portalpad.app.data.AccessMode.SHIZUKU && app.shizuku.isReady) {
                app.shizukuClickBackend.bind()
            }
        }
    }

    private fun enable() {
        // Gate on readiness, then start the service + maybe launch.
        gateOnReadiness { startServiceAndMaybeLaunch() }
    }

    /** Launch the trackpad onto the external display, gated by the same
     *  readiness check as Enable (the trackpad needs the same prerequisites). */
    private fun launchTrackpad() {
        gateOnReadiness {
            startActivity(
                Intent(this, TrackpadActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ).putExtra(TrackpadActivity.EXTRA_KEEP_CURRENT, true),
            )
        }
    }

    /** If all prerequisites are satisfied, run [action] now. Otherwise stash it
     *  and show the readiness dialog, which runs it on "Continue anyway" or once
     *  everything is granted. The launch prerequisites are overlay permission plus
     *  a working privilege mode (Shizuku or Root) — see isFullyReady(). Accessibility
     *  and the other setup items are optional and do NOT gate launching. */
    private fun gateOnReadiness(action: () -> Unit) {
        if (isFullyReady()) {
            action()
        } else {
            readinessProceedAction = action
            showReadinessDialog.value = true
        }
    }

    /** True when all prerequisites for the full external-display experience are
     *  satisfied: overlay permission and a privileged backend (Shizuku/Root). */
    private fun isFullyReady(): Boolean {
        val overlayOk = android.provider.Settings.canDrawOverlays(this)
        val privilegeOk = (application as PortalPadApp).hasLaunchPrivilege
        return overlayOk && privilegeOk
    }

    /** Called from the readiness dialog's "Continue anyway" / once everything is
     *  fixed, to run the stashed action (start service, or launch trackpad). */
    private fun proceedReadiness() {
        showReadinessDialog.value = false
        val action = readinessProceedAction
        readinessProceedAction = {}
        action()
    }

    private fun startServiceAndMaybeLaunch() {
        val app = application as PortalPadApp
        // Show the black "PortalPad" transition cover while enable brings the
        // session + trackpad up, so the user sees a clean spinner instead of the
        // intermediate flicker of the service starting and the trackpad activity
        // launching. Cleared once the trackpad activity is started below (or by
        // its own safety timeout if no display is present).
        val willLaunch = runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.bool(
                    PreferencesRepository.Keys.AUTO_LAUNCH_WHEN_ENABLED,
                    default = true,
                ).first()
            }
        }.getOrDefault(true) && hasExternalDisplay()
        if (willLaunch) {
            com.portalpad.app.presentation.DisableTransitionOverlay.showUntilDismissed(
                applicationContext, maxMs = 4000L, status = "Launching PortalPad…",
            )
        }

        try {
            PortalPadForegroundService.start(this)
        } catch (t: Throwable) {
            Log.e(TAG, "Service start failed", t)
            com.portalpad.app.presentation.DisableTransitionOverlay.dismiss()
            toast("Couldn't start service: ${t.message}")
            return
        }

        val autoLaunch = runCatching {
            kotlinx.coroutines.runBlocking {
                app.prefs.bool(
                    PreferencesRepository.Keys.AUTO_LAUNCH_WHEN_ENABLED,
                    default = true,
                ).first()
            }
        }.getOrDefault(true)

        if (hasExternalDisplay() && autoLaunch) {
            startActivity(Intent(this, TrackpadActivity::class.java))
            // Give the trackpad activity a moment to come to the foreground, then
            // lift the cover. (TrackpadActivity could also dismiss it on its own
            // first frame, but a short delay keeps this self-contained.)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { com.portalpad.app.presentation.DisableTransitionOverlay.dismiss() },
                1200L,
            )
        } else if (!hasExternalDisplay()) {
            com.portalpad.app.presentation.DisableTransitionOverlay.dismiss()
            toast("Service started — connect an external display to begin")
        }
    }

    private fun hasExternalDisplay(): Boolean {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        // Strict: only a REAL physical external display (HDMI/DP/USB-C/glasses)
        // counts — NOT a virtual display spawned by a USB-C PC link, scrcpy,
        // screen mirroring, or the "Simulate secondary displays" dev option.
        // (Was: any non-default display, which auto-launched the trackpad when
        // merely tethered to a PC.)
        return dm.displays.any { DisplayUtil.isRealExternalDisplay(it) }
    }

    private fun startPicker(target: String) {
        startActivity(
            Intent(this, com.portalpad.app.ui.settings.AppPickerActivity::class.java)
                .putExtra("target", target)
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object { private const val TAG = "MainActivity"
        const val EXTRA_OPEN_PRIVILEGE = "open_privilege"
        const val EXTRA_OPEN_SETUP = "open_setup"
    }
}

/**
 * Enable-time readiness dialog. Lists ONLY the prerequisites that are currently
 * missing — each with a short explanation and an inline action button to fix
 * that specific item. Re-reads permission state on each composition so items
 * disappear as the user grants them (e.g. returns from settings). When nothing
 * is missing the dialog auto-dismisses and enabling proceeds.
 *
 *  - Overlay ("Display over other apps"): required for the VD mirror; without
 *    it the glasses show nothing even though apps launch.
 *  - Privilege (Shizuku or Root): required to launch/control apps on the
 *    external display.
 */
@Composable
private fun ReadinessDialog(
    onDismiss: () -> Unit,
    onContinueAnyway: () -> Unit,
    onFixOverlay: () -> Unit,
    onFixPrivilege: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val app = ctx.applicationContext as PortalPadApp

    // Re-read on each recomposition + whenever the activity resumes (the user
    // comes back from a settings screen). A simple resume-keyed state refresh.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var refreshKey by androidx.compose.runtime.remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val overlayOk = androidx.compose.runtime.remember(refreshKey) {
        android.provider.Settings.canDrawOverlays(ctx)
    }
    val privilegeOk = androidx.compose.runtime.remember(refreshKey) { app.hasLaunchPrivilege }

    // Auto-proceed once the required items (overlay + privilege) are satisfied.
    androidx.compose.runtime.LaunchedEffect(overlayOk, privilegeOk) {
        if (overlayOk && privilegeOk) onContinueAnyway()
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onContinueAnyway) {
                androidx.compose.material3.Text("Continue anyway")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("Cancel")
            }
        },
        title = { androidx.compose.material3.Text("Set up PortalPad") },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
            ) {
                androidx.compose.material3.Text(
                    "To control apps on your external display, PortalPad needs the " +
                        "following. Tap each to set it up:",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )

                if (!overlayOk) {
                    ReadinessItem(
                        title = "Display over other apps",
                        description = "Required for PortalPad to overlay its controls " +
                            "(dock, cursor, and the floating bubble for quick return to " +
                            "the trackpad) on top of other apps and the external display.",
                        buttonLabel = "Enable overlay permission",
                        onClick = onFixOverlay,
                    )
                }
                if (!privilegeOk) {
                    PrivilegeSetupItem()
                }
            }
        },
    )
}

@Composable
private fun ReadinessItem(
    title: String,
    description: String,
    buttonLabel: String,
    onClick: () -> Unit,
    note: androidx.compose.ui.text.AnnotatedString? = null,
) {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.material3.Text(
            title,
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
        androidx.compose.material3.Text(
            description,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
        if (note != null) {
            androidx.compose.material3.Text(
                note,
                color = com.portalpad.app.ui.theme.AbDanger,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
        androidx.compose.material3.Button(
            onClick = onClick,
            modifier = androidx.compose.ui.Modifier.padding(top = 2.dp),
        ) {
            androidx.compose.material3.Text(buttonLabel)
        }
    }
}

/**
 * Privilege setup directly inside the "Set up PortalPad" dialog: a dropdown to
 * choose the source (None / Shizuku / Root) plus a status-aware action button,
 * so the user can authorize without first navigating to the Privilege Source
 * screen. The select + action + tailored "not ready" popups mirror the
 * Privilege Source behavior (duplicated here intentionally so this works
 * standalone).
 */
@Composable
private fun PrivilegeSetupItem() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val app = ctx.applicationContext as PortalPadApp
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val accessMode by app.prefs.accessMode.collectAsState(initial = AccessMode.NONE)
    var expanded by androidx.compose.runtime.remember { mutableStateOf(false) }
    // Tailored "not ready" popup, keyed by the mode whose guidance to show.
    var notReadyPopup by androidx.compose.runtime.remember {
        mutableStateOf<AccessMode?>(null)
    }

    // Mirror AccessModeSelector.onSelect: persist the choice, bind/grant if
    // ready, otherwise raise the tailored popup.
    fun selectMode(mode: AccessMode) {
        scope.launch { app.prefs.setAccessMode(mode) }
        when (mode) {
            AccessMode.NONE -> { /* nothing to set up */ }
            AccessMode.SHIZUKU -> {
                app.shizuku.refresh()
                if (app.shizuku.isReady) {
                    app.shizukuClickBackend.bind()
                } else {
                    notReadyPopup = mode
                }
            }
            AccessMode.ROOT -> {
                app.root.refresh()
                if (app.root.isReady) {
                    app.root.grantInjectEvents()
                    app.rootClickBackend.bind()
                }
                notReadyPopup = mode
            }
        }
    }

    androidx.compose.foundation.layout.Column(
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.material3.Text(
            "Shizuku or Root",
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
        androidx.compose.material3.Text(
            "Required to launch and control apps on the external display. " +
                "Choose a privilege source and authorize it to continue.",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )

        // Dropdown trigger — plain Row + DropdownMenu (matches the Privilege
        // Source selector; avoids fiddly ExposedDropdownMenu across versions).
        val dropdownLabel = when (accessMode) {
            AccessMode.NONE -> "None"
            AccessMode.SHIZUKU -> "Shizuku"
            AccessMode.ROOT -> "Root"
        }
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(com.portalpad.app.ui.theme.AbSurfaceElevated)
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Text(
                    dropdownLabel,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                    modifier = androidx.compose.ui.Modifier.weight(1f),
                )
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "Choose privilege source",
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { androidx.compose.material3.Text("None") },
                    onClick = { expanded = false; selectMode(AccessMode.NONE) },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { androidx.compose.material3.Text("Authorize Shizuku") },
                    onClick = { expanded = false; selectMode(AccessMode.SHIZUKU) },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { androidx.compose.material3.Text("Request Root") },
                    onClick = { expanded = false; selectMode(AccessMode.ROOT) },
                )
            }
        }

        // Status-aware action button. Its label adapts to the chosen source's
        // current state (Get/Open/Authorize Shizuku, or Request Root). Tapping
        // re-runs selectMode so the tailored popup appears for the live state.
        val actionLabel: String? = when (accessMode) {
            AccessMode.NONE -> null
            AccessMode.SHIZUKU -> when (app.shizuku.freshStatus()) {
                ShizukuManager.Status.NOT_INSTALLED -> "Get Shizuku"
                ShizukuManager.Status.NOT_RUNNING, ShizukuManager.Status.UNKNOWN -> "Open Shizuku"
                ShizukuManager.Status.NEEDS_PERMISSION -> "Authorize Shizuku"
                ShizukuManager.Status.READY -> null
            }
            AccessMode.ROOT -> if (app.root.isReady) null else "Request Root"
        }
        if (actionLabel != null) {
            androidx.compose.material3.Button(
                onClick = { selectMode(accessMode) },
                modifier = androidx.compose.ui.Modifier.padding(top = 4.dp),
            ) {
                androidx.compose.material3.Text(actionLabel)
            }
        }
    }

    // ─── Tailored "not ready" popup (mirrors Privilege Source) ───────────
    notReadyPopup?.let { mode ->
        val freshShizukuStatus = androidx.compose.runtime.remember(mode) {
            if (mode == AccessMode.SHIZUKU) app.shizuku.freshStatus() else app.shizuku.freshStatus()
        }
        data class Action(val title: String, val msg: String, val label: String, val act: () -> Unit)
        val a: Action = when (mode) {
            AccessMode.SHIZUKU -> when (freshShizukuStatus) {
                ShizukuManager.Status.NOT_INSTALLED -> Action(
                    "Shizuku not installed",
                    "Shizuku is a separate app that grants PortalPad the privileges needed for external displays / AR glasses, IME policy, and launching apps on external displays. Install the recommended GitHub build (adds start-on-boot and a watchdog so it keeps running), then come back.",
                    "Open in GitHub",
                    {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/thedjchi/Shizuku"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { ctx.startActivity(intent) }
                        notReadyPopup = null
                    },
                )
                ShizukuManager.Status.NOT_RUNNING -> Action(
                    "Shizuku not running",
                    "Shizuku is installed but its service isn't running. Open Shizuku, then start the service via ADB (instructions inside the app) or with wireless debugging. PortalPad will pick it up automatically when you return.",
                    "Open Shizuku",
                    {
                        val launchIntent = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        launchIntent?.let {
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { ctx.startActivity(it) }
                        }
                        notReadyPopup = null
                    },
                )
                ShizukuManager.Status.NEEDS_PERMISSION -> Action(
                    "Authorize PortalPad",
                    "Shizuku is running. Tap Authorize to bring up the Android permission dialog so PortalPad can use it.",
                    "Authorize",
                    {
                        app.shizuku.requestPermission()
                        notReadyPopup = null
                    },
                )
                else -> Action(
                    "Shizuku status unknown",
                    "Couldn't determine Shizuku's state. Try reopening Shizuku, then come back.",
                    "OK",
                    { notReadyPopup = null },
                )
            }
            AccessMode.ROOT -> if (app.root.isReady) Action(
                "Root active",
                "PortalPad is using root, with full parity to Shizuku — confirmed working. " +
                    "Shizuku is the most broadly tested path, so if you'd like you can also " +
                    "authorize it and PortalPad will use whichever you prefer.",
                "OK",
                { notReadyPopup = null },
            ) else Action(
                "Root not granted",
                "PortalPad couldn't get superuser access. In the Magisk app, make sure " +
                    "Superuser is enabled and that PortalPad is allowed (open Magisk → Superuser, " +
                    "grant PortalPad). Then tap Request. If you'd rather not use root, you can use " +
                    "Shizuku instead.",
                "Request",
                {
                    Thread {
                        runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() }
                        app.root.refresh()
                        if (app.root.isReady) {
                            app.root.grantInjectEvents()
                            app.rootClickBackend.bind()
                        }
                    }.start()
                    notReadyPopup = null
                },
            )
            AccessMode.NONE -> Action("", "", "", { notReadyPopup = null })
        }
        if (a.title.isNotEmpty()) {
            // For ROOT, offer a secondary Shizuku action (fall back to/supplement
            // with Shizuku directly), mirroring the Privilege Source popup.
            val authorizeShizuku: (() -> Unit)? = if (mode == AccessMode.ROOT) {
                {
                    when (app.shizuku.freshStatus()) {
                        ShizukuManager.Status.NOT_INSTALLED -> {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/thedjchi/Shizuku"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { ctx.startActivity(intent) }
                        }
                        ShizukuManager.Status.READY -> {
                            app.shizukuClickBackend.bind()
                            scope.launch { app.prefs.setAccessMode(AccessMode.SHIZUKU) }
                        }
                        ShizukuManager.Status.NOT_RUNNING, ShizukuManager.Status.UNKNOWN -> {
                            ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let {
                                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { ctx.startActivity(it) }
                            }
                        }
                        else -> app.shizuku.requestPermission()
                    }
                    notReadyPopup = null
                }
            } else null

            androidx.compose.material3.AlertDialog(
                onDismissRequest = { notReadyPopup = null },
                title = { androidx.compose.material3.Text(a.title) },
                text = { androidx.compose.material3.Text(a.msg) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = a.act) {
                        androidx.compose.material3.Text(a.label)
                    }
                },
                dismissButton = {
                    androidx.compose.foundation.layout.Row {
                        if (authorizeShizuku != null) {
                            val shizukuLabel = if (app.shizuku.freshStatus() == ShizukuManager.Status.READY)
                                "Enable Shizuku" else "Authorize Shizuku"
                            androidx.compose.material3.TextButton(onClick = authorizeShizuku) {
                                androidx.compose.material3.Text(shizukuLabel)
                            }
                        }
                        androidx.compose.material3.TextButton(onClick = { notReadyPopup = null }) {
                            androidx.compose.material3.Text("Cancel")
                        }
                    }
                },
            )
        }
    }
}
