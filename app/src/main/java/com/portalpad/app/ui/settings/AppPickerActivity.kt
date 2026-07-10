package com.portalpad.app.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.portalpad.app.PortalPadApp
import com.portalpad.app.applyForcedOrientation
import com.portalpad.app.lockOrientationDefault
import com.portalpad.app.data.AppEntry
import com.portalpad.app.data.BackAction
import com.portalpad.app.data.DockConfig
import com.portalpad.app.data.DockItem
import com.portalpad.app.data.MAX_DOCK_ITEMS
import com.portalpad.app.ui.dock.AppIcon
import com.portalpad.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppPickerActivity : com.portalpad.app.PinnedDensityActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockOrientationDefault()
        applyForcedOrientation()
        val target = intent.getStringExtra("target") ?: "home"
        setContent {
            PortalPadTheme {
                AppPickerScreen(
                    target = target,
                    onPickedSingle = { entry ->
                        lifecycleScope.launch { applySingle(target, entry); finish() }
                    },
                    onPickedMulti = { entries ->
                        lifecycleScope.launch { applyMulti(entries); finish() }
                    },
                    onReset = {
                        lifecycleScope.launch { applyReset(target); finish() }
                    },
                    onCreateFolder = { name ->
                        lifecycleScope.launch { applyCreateFolder(name) }
                    },
                    onPickAutoLaunchSpecial = { value ->
                        lifecycleScope.launch {
                            // Save and stay open so the dropdown reflects the new
                            // selection live (Wallpaper / Last app). Only picking a
                            // specific app from the list commits-and-closes.
                            PortalPadApp.instance.prefs.setAutoLaunchOnStart(value)
                        }
                    },
                    onPickAutoLaunchInterface = { value ->
                        lifecycleScope.launch {
                            PortalPadApp.instance.prefs.setAutoLaunchInterface(value)
                        }
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    private suspend fun applySingle(target: String, entry: AppEntry) {
        val app = PortalPadApp.instance
        when {
            // Nav buttons default NEW assignments to FULLSCREEN (freeform=false):
            // the primary use case is Android-TV-style launchers that should own
            // the panel. The "Launch app in full screen" toggle under Current
            // Action opts an assignment back into a window. Other targets (wheel,
            // gestures, prog keys) keep AppEntry's default (freeform=true).
            target == "home" -> app.prefs.setHomeAction(entry.copy(freeform = false))
            target == "back" -> app.prefs.setBackAction(BackAction.Launch(entry.copy(freeform = false)))
            target == "gesture_up" -> app.prefs.setGestureAppUp(entry)
            target == "gesture_down" -> app.prefs.setGestureAppDown(entry)
            target == "gesture_left" -> app.prefs.setGestureAppLeft(entry)
            target == "gesture_right" -> app.prefs.setGestureAppRight(entry)
            target == "prog_red" -> app.prefs.setProgAction(com.portalpad.app.data.PreferencesRepository.Keys.PROG_KEY_ACTION_RED, entry)
            target == "prog_green" -> app.prefs.setProgAction(com.portalpad.app.data.PreferencesRepository.Keys.PROG_KEY_ACTION_GREEN, entry)
            target == "prog_yellow" -> app.prefs.setProgAction(com.portalpad.app.data.PreferencesRepository.Keys.PROG_KEY_ACTION_YELLOW, entry)
            target == "prog_blue" -> app.prefs.setProgAction(com.portalpad.app.data.PreferencesRepository.Keys.PROG_KEY_ACTION_BLUE, entry)
            target.startsWith("wheel:") ->
                target.removePrefix("wheel:").toIntOrNull()?.let { app.prefs.setWheelSlot(it, entry) }
            target == "autolaunch" ->
                app.prefs.setAutoLaunchOnStart(com.portalpad.app.data.AutoLaunch.Launch(entry))
            target.startsWith("folder:") -> {
                // Apps added into a folder are nested and don't count against
                // the top-level dock cap.
                val folderId = target.removePrefix("folder:")
                val cfg: DockConfig = app.prefs.dockConfig.first()
                val sc = toShortcut(entry)
                app.prefs.setDockConfig(
                    cfg.addShortcut(sc).moveIntoFolder(sc.id, folderId)
                )
            }
            else -> { // "dock"
                val cfg: DockConfig = app.prefs.dockConfig.first()
                if (cfg.isFull) { warnDockFull(); return }
                app.prefs.setDockConfig(cfg.addShortcut(toShortcut(entry)))
            }
        }
    }

    private suspend fun applyMulti(entries: List<AppEntry>) {
        val app = PortalPadApp.instance
        val target = intent.getStringExtra("target") ?: "dock"
        var cfg: DockConfig = app.prefs.dockConfig.first()
        val folderId = target.takeIf { it.startsWith("folder:") }?.removePrefix("folder:")

        fun keyOf(pkg: String, comp: String?) = pkg + "|" + (comp ?: "")

        // Folder target stays ADD-ONLY (folder contents aren't managed here).
        if (folderId != null) {
            var hitCap = false
            for (entry in entries) {
                val sc = toShortcut(entry)
                cfg = cfg.addShortcut(sc).moveIntoFolder(sc.id, folderId)
            }
            app.prefs.setDockConfig(cfg)
            if (hitCap) warnDockFull()
            return
        }

        // Dock target: MANAGER MODEL. `entries` is the desired top-level
        // membership. Diff against the current top-level shortcuts:
        //   - remove docked shortcuts whose app is no longer selected
        //   - add selected apps that aren't already docked
        // Folders and nested items are left untouched.
        val desiredKeys = entries.map { keyOf(it.packageName, it.componentName) }.toSet()
        val currentShortcutKeyToId = HashMap<String, String>()
        cfg.items.forEach { di ->
            if (di is DockItem.Shortcut) {
                currentShortcutKeyToId[keyOf(di.app.packageName, di.app.componentName)] = di.id
            }
        }
        // Removals: docked top-level shortcut not in the desired set.
        currentShortcutKeyToId.forEach { (key, id) ->
            if (key !in desiredKeys) cfg = cfg.removeItem(id)
        }
        // Additions: desired app not currently docked (respect the cap).
        var hitCap = false
        for (entry in entries) {
            val key = keyOf(entry.packageName, entry.componentName)
            if (key in currentShortcutKeyToId) continue   // already docked
            if (cfg.isFull) { hitCap = true; break }
            cfg = cfg.addShortcut(toShortcut(entry))
        }
        app.prefs.setDockConfig(cfg)
        if (hitCap) warnDockFull()
    }

    private suspend fun applyReset(target: String) {
        val app = PortalPadApp.instance
        when {
            target == "home" -> app.prefs.setHomeAction(null)             // → "Not set (System Home)"
            target == "back" -> app.prefs.setBackAction(BackAction.System) // → "Back action"
            target.startsWith("wheel:") ->
                target.removePrefix("wheel:").toIntOrNull()?.let { app.prefs.setWheelSlot(it, null) }
        }
    }

    /** Create a new empty folder in the dock (from the picker's New folder
     *  action), then toast confirmation. Stays in the picker so the user can
     *  keep managing. */
    private suspend fun applyCreateFolder(name: String) {
        val app = PortalPadApp.instance
        val label = name.trim().ifBlank { "Folder" }
        val cfg = app.prefs.dockConfig.first()
        app.prefs.setDockConfig(cfg.addFolder(label))
        android.widget.Toast.makeText(
            this, "Folder \"$label\" created", android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    private fun warnDockFull() {
        android.widget.Toast.makeText(
            this,
            "Dock is full ($MAX_DOCK_ITEMS items). Remove an item or use a folder to add more.",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    private fun toShortcut(entry: AppEntry) = DockItem.Shortcut(
        id = "sc_${System.currentTimeMillis()}_${entry.packageName.hashCode()}",
        label = entry.label,
        app = entry,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerScreen(
    target: String,
    onPickedSingle: (AppEntry) -> Unit,
    onPickedMulti: (List<AppEntry>) -> Unit,
    onReset: () -> Unit,
    onCreateFolder: (String) -> Unit = {},
    onPickAutoLaunchSpecial: (com.portalpad.app.data.AutoLaunch) -> Unit = {},
    onPickAutoLaunchInterface: (com.portalpad.app.data.AutoLaunchInterface) -> Unit = {},
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf(TextFieldValue("")) }
    // Tabs replace the old "Show specific activities" toggle. 0 = Apps, 1 =
    // Activities. (A Shortcuts tab may be added later, pending a feasibility
    // spike on enumerating app shortcuts via the privileged layer.)
    var selectedTab by remember { mutableStateOf(0) }
    val showActivities = selectedTab == 1
    var apps by remember { mutableStateOf<List<AppListing>>(emptyList()) }
    val isFolderTarget = target.startsWith("folder:")
    // Multi-select for the dock (manager model) AND folder targets (add-only).
    val isMultiSelect = target == "dock" || isFolderTarget
    val selected = remember { mutableStateListOf<AppEntry>() }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    // ── Auto Launch (target == "autolaunch") ──
    // Two dropdowns: "Launch on start" (wallpaper / last app / specific app) and
    // "Input interface" (air mouse / trackpad / remote). The Apps/Activities
    // picker below only appears when "Specific app" is chosen.
    val autoLaunchVal by if (target == "autolaunch")
        PortalPadApp.instance.prefs.autoLaunchOnStart.collectAsState(initial = com.portalpad.app.data.AutoLaunch.Wallpaper)
    else remember { mutableStateOf(com.portalpad.app.data.AutoLaunch.Wallpaper) }
    val autoLaunchIface by if (target == "autolaunch")
        PortalPadApp.instance.prefs.autoLaunchInterface.collectAsState(initial = com.portalpad.app.data.AutoLaunchInterface.TRACKPAD)
    else remember { mutableStateOf(com.portalpad.app.data.AutoLaunchInterface.TRACKPAD) }
    // True while the user is actively choosing a specific app (revealed by the
    // "Specific app" dropdown option).
    var autoLaunchPickingApp by remember { mutableStateOf(false) }
    // "Launch triggers" group is collapsible (expanded by default).
    var triggersExpanded by remember { mutableStateOf(true) }
    // True while the app-search field has focus (keyboard up). When picking a
    // specific app and searching, we hide the Launch target / triggers sections
    // so the app list fills the space above the keyboard.
    var searchFocused by remember { mutableStateOf(false) }
    // ── Manager model (dock target only) ──
    // The picker doubles as a dock manager: already-docked apps appear checked,
    // and unchecking one removes it from the dock on Done. We load the current
    // dock's top-level shortcuts and build a key set to (a) pre-check them and
    // (b) detect removals on commit. Folders and nested items are NOT managed
    // here (only top-level shortcuts), so folder contents are left untouched.
    val appForManager = PortalPadApp.instance
    val dockCfgState by appForManager.prefs.dockConfig.collectAsState(initial = null)
    // Stable key for matching an AppEntry to a docked Shortcut.
    fun entryKey(pkg: String, comp: String?) = pkg + "|" + (comp ?: "")
    // Map of entryKey -> docked shortcut id, for top-level shortcuts only.
    val dockedShortcutIds = remember(dockCfgState) {
        val m = HashMap<String, String>()
        dockCfgState?.items?.forEach { di ->
            if (di is DockItem.Shortcut) {
                m[entryKey(di.app.packageName, di.app.componentName)] = di.id
            }
        }
        m
    }
    // The set of docked apps that existed when the picker opened — the baseline
    // we diff against on Done. Captured once so mid-session toggles don't move it.
    val initialDockedKeys = remember(dockCfgState != null) {
        dockedShortcutIds.keys.toSet()
    }
    // Reset-to-default is meaningful for Home/Back and for clearing a wheel slot.
    val canReset = target == "home" || target == "back" || target.startsWith("wheel:")
    // Whether the target is ALREADY at its default (Home unset / Back = System).
    // Drives the reset row's greyed, non-clickable state.
    val app = PortalPadApp.instance
    val homeActionState by app.prefs.homeAction.collectAsState(initial = null)
    val backActionState by app.prefs.backAction.collectAsState(initial = BackAction.System)
    val isAlreadyDefault = when (target) {
        "home" -> homeActionState == null
        "back" -> backActionState == BackAction.System
        else -> false
    }

    LaunchedEffect(showActivities) {
        apps = withContext(Dispatchers.IO) { loadAppList(context.packageManager, showActivities) }
    }

    // Launch-triggers default expansion follows the app-picking state: collapsed
    // while picking a specific app (so the app list keeps its space), expanded
    // otherwise. The user can still toggle it manually after this default fires.
    LaunchedEffect(autoLaunchPickingApp) {
        triggersExpanded = !autoLaunchPickingApp
    }

    // Pre-check already-docked apps once the list (and dock config) are loaded,
    // so the manager opens reflecting current dock membership. Only for the dock
    // target, and only on the Apps tab (activities tab lists components, not the
    // package-level shortcuts the dock stores).
    LaunchedEffect(apps, dockCfgState, isMultiSelect, showActivities) {
        if (!isMultiSelect || isFolderTarget || showActivities || apps.isEmpty() || dockCfgState == null) return@LaunchedEffect
        apps.forEach { listing ->
            val key = entryKey(listing.entry.packageName, listing.entry.componentName)
            if (key in dockedShortcutIds && selected.none {
                    entryKey(it.packageName, it.componentName) == key
                }) {
                selected.add(listing.entry)
            }
        }
    }

    val filtered = remember(query, apps) {
        if (query.text.isBlank()) apps
        else apps.filter {
            it.label.contains(query.text, ignoreCase = true) ||
                it.packageName.contains(query.text, ignoreCase = true)
        }
    }

    // Activities-tab grouping: group activities under their app with an
    // expandable header so the list isn't a giant flat scroll. Tracks which app
    // packages are expanded. When searching, we skip grouping and show flat
    // matches (so a search finds activities across all apps directly).
    val expandedPkgs = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val grouped = remember(filtered, showActivities, query) {
        if (!showActivities || query.text.isNotBlank()) emptyList()
        else filtered.groupBy { it.packageName }.entries
            .sortedBy { it.value.firstOrNull()?.appLabel?.lowercase() ?: it.key }
            .map { (pkg, rows) -> pkg to rows }
    }

    val title = when {
        target == "home" -> "Pick Home action"
        target == "back" -> "Pick Back action"
        target.startsWith("prog_") -> "Pick Button Action"
        target.startsWith("gesture_") -> "Pick swipe action"
        target.startsWith("wheel:") -> "Pick app for wheel slot"
        target == "autolaunch" -> "Auto Launch on Start"
        isFolderTarget -> "Add apps (${selected.size})"
        else -> "Manage dock (${selected.size}/$MAX_DOCK_ITEMS)"
    }

    // Has the selection changed vs. what was docked when we opened? Done is
    // enabled on any change — including removing items down to zero (valid in
    // the manager model), which the old "non-empty" rule wrongly disallowed.
    // For folder targets (add-only) Done is enabled whenever ≥1 app is picked.
    val selectedKeys = selected.map { entryKey(it.packageName, it.componentName) }.toSet()
    val hasChanges = if (isFolderTarget) selected.isNotEmpty()
        else isMultiSelect && selectedKeys != initialDockedKeys

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    Text(
                        "Cancel", color = AbPrimaryBright,
                        modifier = Modifier.clickable { onCancel() }.padding(12.dp),
                    )
                },
                actions = {
                    if (target == "dock") {
                        Text(
                            "New folder",
                            color = AbPrimaryBright,
                            modifier = Modifier
                                .clickable { showNewFolderDialog = true }
                                .padding(12.dp),
                        )
                    }
                    if (isMultiSelect) {
                        Text(
                            "Done",
                            color = if (hasChanges) AbAccent else AbOnSurfaceDim,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable(enabled = hasChanges) {
                                    onPickedMulti(selected.toList())
                                }
                                .padding(12.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AbBackground),
            )
        },
        bottomBar = {
            // Pinned bottom back button, matching the controls subpage style
            // (full width, amber border, taller). Returns to the previous screen.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(AbBackground)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AbSurfaceElevated)
                    .border(1.dp, AbAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .clickable { onCancel() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "\u2190 Back",
                    color = AbOnSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        containerColor = AbBackground,
    ) { padding ->
        Column(Modifier.padding(padding).imePadding().padding(horizontal = 12.dp)) {
            // Title on its own centered line, below the Cancel / New folder / Done
            // row (it used to sit cramped inline between them in the top bar).
            Text(
                title,
                color = AbOnSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
            )
            // Quick reset to the default action, separate from the app list.
            if (canReset) {
                val resetLabel = when {
                    target == "home" -> "Use System Home (default)"
                    target.startsWith("wheel:") -> "Clear this slot"
                    else -> "Use Back action (default)"
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .then(
                            // Active (assignable) → elevated background + clickable.
                            // Already default → blends into the screen, not clickable.
                            if (isAlreadyDefault) Modifier
                            else Modifier
                                .background(AbSurfaceElevated)
                                .clickable { onReset() },
                        )
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = if (isAlreadyDefault) AbOnSurfaceDim else AbPrimaryBright,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        if (isAlreadyDefault) "$resetLabel — current" else resetLabel,
                        color = if (isAlreadyDefault) AbOnSurfaceDim else AbOnSurface,
                        fontWeight = FontWeight.Medium,
                    )
                }
                // Current assignment (Home/Back only — wheel slots just clear).
                if (target == "home" || target == "back") {
                Spacer(Modifier.size(14.dp))
                val currentActionValue = when (target) {
                    "home" -> homeActionState?.label ?: "Not set (System Home)"
                    "back" -> when (val b = backActionState) {
                        is BackAction.Launch -> b.entry.label
                        else -> "Back action"
                    }
                    else -> ""
                }
                val currentHeading = if (target == "home") "Current Home Action:" else "Current Back Action:"
                // Is something actually assigned? (Drives whether the value is
                // emphasized in accent, or muted because it's "Not set".)
                val hasAssignment = when (target) {
                    "home" -> homeActionState != null
                    "back" -> backActionState is BackAction.Launch
                    else -> false
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = currentHeading,
                        color = Color(0xFFB9A7E6),  // faint purple heading (muted)
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        text = currentActionValue,
                        color = if (hasAssignment) AbPrimaryBright else AbOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (hasAssignment) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Per-assignment launch mode — ALWAYS visible for the Home/Back
                // pickers so the option and the fullscreen default are
                // discoverable before anything is assigned. Live and editable
                // only for a real app/activity assignment; greyed (with an
                // explanatory subtitle) when nothing is assigned or the
                // assignment is a shortcut (those fire on the phone) / System.
                // Toggle semantics: ON = full screen = freeform=false. New
                // assignments are written freeform=false (ON); entries saved
                // before the flag existed stay freeform (OFF) via the field's
                // serialization default — no silent behavior change.
                run {
                    val currentEntry: AppEntry? = when (target) {
                        "home" -> homeActionState
                        "back" -> (backActionState as? BackAction.Launch)?.entry
                        else -> null
                    }
                    val isShortcutEntry = currentEntry?.isShortcut == true
                    val editable = currentEntry != null && !isShortcutEntry
                    val ffScope = rememberCoroutineScope()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                "Launch app in full screen",
                                color = if (editable) AbOnSurface else AbOnSurfaceDim,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                when {
                                    isShortcutEntry ->
                                        "Not applicable to shortcuts — they run on the phone."
                                    currentEntry == null ->
                                        "Assign an app or activity to use this. New assignments launch full screen."
                                    else ->
                                        "Best for TV-style launchers. Turn off to launch as a freeform window. Applies on the app's next launch."
                                },
                                color = if (editable) AbOnSurfaceMuted else AbOnSurfaceDim,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = if (editable) !(currentEntry!!.freeform) else true,
                            enabled = editable,
                            onCheckedChange = { on ->
                                val entry = currentEntry ?: return@Switch
                                ffScope.launch {
                                    val updated = entry.copy(freeform = !on)
                                    when (target) {
                                        "home" -> app.prefs.setHomeAction(updated)
                                        "back" -> app.prefs.setBackAction(BackAction.Launch(updated))
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = AbPrimary,
                                checkedThumbColor = AbPrimaryBright,
                                uncheckedTrackColor = AbSurfaceElevated,
                                uncheckedThumbColor = AbOnSurfaceDim,
                            ),
                        )
                    }
                }
                }
            }
            // Auto Launch: two labeled dropdowns. The app picker below appears
            // only when "Specific app" is selected in the launch dropdown. While
            // the search field is focused (keyboard up), the whole Launch
            // target / triggers section hides so the app list fills the screen.
            if (target == "autolaunch" && !searchFocused) {
                // ── Group 1: Launch target (what opens) ──
                Text(
                    "Launch target",
                    color = AbOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
                )
                val launchLabel = when (val a = autoLaunchVal) {
                    com.portalpad.app.data.AutoLaunch.Wallpaper ->
                        if (autoLaunchPickingApp) "Specific app" else "Wallpaper (default)"
                    com.portalpad.app.data.AutoLaunch.LastApp ->
                        if (autoLaunchPickingApp) "Specific app" else "Last app"
                    is com.portalpad.app.data.AutoLaunch.Launch -> a.entry.label
                }
                Text(
                    "Launch on start",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                )
                Box(Modifier.padding(horizontal = 16.dp)) {
                    PickerDropdown(
                        current = launchLabel,
                        options = listOf("Wallpaper (default)", "Last app", "Specific app"),
                        onSelect = { choice ->
                            when (choice) {
                                "Wallpaper (default)" -> { autoLaunchPickingApp = false; onPickAutoLaunchSpecial(com.portalpad.app.data.AutoLaunch.Wallpaper) }
                                "Last app" -> { autoLaunchPickingApp = false; onPickAutoLaunchSpecial(com.portalpad.app.data.AutoLaunch.LastApp) }
                                else -> { autoLaunchPickingApp = true; selectedTab = 0 }
                            }
                        },
                    )
                }
                Text(
                    "Input interface",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                Box(Modifier.padding(horizontal = 16.dp)) {
                    PickerDropdown(
                        current = when (autoLaunchIface) {
                            com.portalpad.app.data.AutoLaunchInterface.AIR_MOUSE -> "Air Mouse"
                            com.portalpad.app.data.AutoLaunchInterface.TRACKPAD -> "Trackpad (Default)"
                            com.portalpad.app.data.AutoLaunchInterface.REMOTE -> "Remote"
                        },
                        options = listOf("Air Mouse", "Trackpad (Default)", "Remote"),
                        onSelect = { choice ->
                            val v = when (choice) {
                                "Air Mouse" -> com.portalpad.app.data.AutoLaunchInterface.AIR_MOUSE
                                "Remote" -> com.portalpad.app.data.AutoLaunchInterface.REMOTE
                                else -> com.portalpad.app.data.AutoLaunchInterface.TRACKPAD
                            }
                            onPickAutoLaunchInterface(v)
                        },
                    )
                }
                if (autoLaunchPickingApp) {
                    androidx.compose.material3.HorizontalDivider(
                        color = AbSurfaceElevated, thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                // ── Group 2: Launch triggers (when the chosen interface opens) ──
                // Always present, directly below Input interface. Collapsible:
                // expanded by default normally, but collapsed by default while
                // picking a specific app so the app list keeps its space. When
                // the user expands it during app-picking, the Apps/Activities
                // list hides (below) to make room.
                run {
                    Text(
                        "Launch triggers",
                        color = AbOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AbSurfaceElevated)
                                .border(1.dp, AbPrimaryBright.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .clickable { triggersExpanded = !triggersExpanded }
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "When to auto-open the interface",
                                color = AbOnSurface,
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                if (triggersExpanded) Icons.Default.KeyboardArrowUp
                                else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (triggersExpanded) "Collapse" else "Expand",
                                tint = AbOnSurfaceMuted,
                            )
                        }
                    }
                    if (triggersExpanded) {
                        AutoLaunchToggleRow(
                            label = "When you tap Start Service",
                            desc = "When you tap Start Service on the Start tab and an external display is connected, automatically open your chosen input interface.",
                            prefKey = com.portalpad.app.data.PreferencesRepository.Keys.AUTO_LAUNCH_WHEN_ENABLED,
                            default = true,
                        )
                        AutoLaunchToggleRow(
                            label = "On display connect",
                            desc = "Open your chosen input interface automatically the moment an external display is detected (if the service is running).",
                            prefKey = com.portalpad.app.data.PreferencesRepository.Keys.AUTO_ACTIVATE_EXTERNAL,
                            default = true,
                        )
                        AutoLaunchToggleRow(
                            label = "After opening an app from the dock",
                            desc = "After you open an app from the dock onto the external display, automatically return to your chosen input interface on your phone.",
                            prefKey = com.portalpad.app.data.PreferencesRepository.Keys.AUTO_LAUNCH_AFTER_MOVE,
                            default = false,
                        )
                    }
                }
            }
            // Apps / Activities tabs — shown for non-autolaunch targets, or while
            // picking a specific app for autolaunch UNLESS the user has expanded
            // the Launch-triggers group (which reclaims the space). When the
            // search field is focused, always show the list (top section hides).
            if (target != "autolaunch" || searchFocused || (autoLaunchPickingApp && !triggersExpanded)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = AbBackground,
                contentColor = AbPrimaryBright,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Apps") },
                    selectedContentColor = AbPrimaryBright,
                    unselectedContentColor = AbOnSurfaceMuted,
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Activities") },
                    selectedContentColor = AbPrimaryBright,
                    unselectedContentColor = AbOnSurfaceMuted,
                )
                // Shortcuts tab — only for Home/Back actions (not the dock
                // multi-select manager). Launches app/Tasker shortcuts on the
                // PHONE. This is a feasibility spike: confirm ACTION_CREATE_SHORTCUT
                // surfaces Tasker/app shortcuts on this device before building the
                // full assign/store/launch path.
                if (!isMultiSelect && target != "autolaunch") {
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Shortcuts") },
                        selectedContentColor = AbPrimaryBright,
                        unselectedContentColor = AbOnSurfaceMuted,
                    )
                }
            }
            if (selectedTab != 2) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search apps", color = AbOnSurfaceMuted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AbOnSurfaceMuted) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AbOnSurface, unfocusedTextColor = AbOnSurface,
                    focusedBorderColor = AbPrimary, unfocusedBorderColor = AbSurfaceElevated,
                    cursorColor = AbPrimaryBright,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .onFocusChanged { searchFocused = it.isFocused },
            )
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    if (grouped.isNotEmpty()) {
                        // Grouped activities view: app header (tap to expand) +
                        // indented activity rows when expanded.
                        grouped.forEach { (pkg, rows) ->
                            val appLabel = rows.firstOrNull()?.appLabel ?: pkg
                            item(key = "hdr_$pkg") {
                                val expanded = expandedPkgs[pkg] == true
                                AppGroupHeader(
                                    packageName = pkg,
                                    appLabel = appLabel,
                                    activityCount = rows.size,
                                    expanded = expanded,
                                    onToggle = { expandedPkgs[pkg] = !expanded },
                                )
                            }
                            if (expandedPkgs[pkg] == true) {
                                items(rows, key = { "act_" + it.entry.packageName + (it.entry.componentName ?: "") }) { item ->
                                    ActivityChildRow(
                                        label = item.activityLabel ?: item.label,
                                        packageName = item.entry.packageName,
                                        appLabel = item.appLabel ?: item.entry.packageName,
                                        onClick = { onPickedSingle(item.entry) },
                                    )
                                }
                            }
                        }
                    } else {
                    items(filtered, key = { it.entry.packageName + (it.entry.componentName ?: "") }) { item ->
                        val isSel = selected.contains(item.entry)
                        // In manager mode, `selected` represents the FUTURE dock
                        // membership, so the cap is hit when it reaches the max.
                        // Non-selected rows grey out then; selected rows stay
                        // interactive so they can be unchecked to free a slot.
                        val atCap = isMultiSelect && selected.size >= MAX_DOCK_ITEMS
                        AppRow(
                            listing = item,
                            isSelected = isSel,
                            showCheckbox = isMultiSelect,
                            disabled = atCap && !isSel,
                            onClick = {
                                if (isMultiSelect) {
                                    if (isSel) selected.remove(item.entry)
                                    else selected.add(item.entry)
                                } else if (target == "autolaunch") {
                                    // Save the chosen app and return to the Auto
                                    // Launch main view (dropdowns now showing the
                                    // app) — do NOT finish the activity.
                                    onPickAutoLaunchSpecial(
                                        com.portalpad.app.data.AutoLaunch.Launch(item.entry),
                                    )
                                    query = TextFieldValue("")
                                    searchFocused = false
                                    focusManager.clearFocus()
                                    autoLaunchPickingApp = false
                                } else onPickedSingle(item.entry)
                            },
                        )
                    }
                    }
                }
                com.portalpad.app.ui.common.VerticalScrollbar(
                    listState = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(4.dp),
                )
            }
            } else {
                // The currently-assigned action, but only if it's a phone shortcut
                // (so the tab echoes what's set and offers a Clear).
                val assignedShortcut: AppEntry? = when (target) {
                    "home" -> homeActionState?.takeIf { it.isShortcut }
                    "back" -> (backActionState as? BackAction.Launch)?.entry?.takeIf { it.isShortcut }
                    else -> null
                }
                ShortcutsSpikeTab(
                    onPickedSingle = onPickedSingle,
                    assigned = assignedShortcut,
                    onClear = onReset,
                )
            }
            } // end picker-visibility wrapper (autolaunch hides this unless picking an app)
        }
    }

    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf(TextFieldValue("Folder")) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("New folder", color = AbOnSurface) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AbOnSurface, unfocusedTextColor = AbOnSurface,
                        focusedBorderColor = AbAccent, unfocusedBorderColor = AbSurfaceElevated,
                        focusedLabelColor = AbAccent, unfocusedLabelColor = AbOnSurfaceMuted,
                        cursorColor = AbAccent,
                    ),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showNewFolderDialog = false
                    onCreateFolder(folderName.text)
                }) { Text("Create", color = AbAccent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("Cancel", color = AbOnSurface)
                }
            },
            containerColor = AbSurface,
        )
    }
}

/**
 * FEASIBILITY SPIKE — Shortcuts tab.
 *
 * Goal: confirm that Android's ACTION_CREATE_SHORTCUT flow surfaces app/Tasker
 * shortcuts on THIS device, and returns something launchable, BEFORE building the
 * full assign/store/phone-launch path. The chosen shortcut here just launches
 * immediately on the PHONE so we can see whether the mechanism works. The full
 * feature (storing the picked shortcut as the Home/Back action that fires on the
 * phone) comes next, only if this proves out.
 *
 * NOTE: shortcuts assigned here are intended to launch on the PHONE SCREEN, never
 * the external display.
 */
@Composable
private fun ShortcutsSpikeTab(
    onPickedSingle: (AppEntry) -> Unit,
    assigned: AppEntry? = null,
    onClear: () -> Unit = {},
) {
    var error by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // Step 2 launcher: the user picks the actual shortcut from the chosen app's
    // creator activity; result is a shortcut Intent (+ name). We SAVE it as the
    // button's action (it will fire on the phone when the button is tapped) — we
    // do NOT launch it here.
    val createLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data
        if (data == null) { error = "No shortcut chosen."; return@rememberLauncherForActivityResult }
        val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
            ?: data.getStringExtra("android.intent.extra.shortcut.NAME")
        @Suppress("DEPRECATION")
        val shortcutIntent = data.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)
        if (shortcutIntent != null) {
            val uri = shortcutIntent.toUri(0)
            val pkg = shortcutIntent.`package`
                ?: shortcutIntent.component?.packageName
                ?: "shortcut"
            // Some shortcut-CREATOR apps (Tasker, AutoInput, etc.) run/preview the
            // target as a side effect of ACTION_CREATE_SHORTCUT — happening in
            // THEIR process before this callback fires, so we can't prevent it.
            // Best-effort recovery: show a brief PortalPad cover and bring the
            // trackpad back to the front so the user lands back on their last-used
            // interface (Trackpad/AirMouse/Remote, restored from prefs) instead of
            // being left in whatever the creator launched. The cover smooths the
            // RETURN transition; it can't hide the creator's initial flash (that
            // already happened in the creator's process).
            runCatching {
                com.portalpad.app.presentation.DisableTransitionOverlay.show(
                    ctx.applicationContext, durationMs = 1100L, status = "Returning to PortalPad…",
                )
            }
            runCatching {
                ctx.startActivity(
                    android.content.Intent(ctx, com.portalpad.app.TrackpadActivity::class.java)
                        .addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                        )
                )
            }
            // Build a phone-shortcut AppEntry and return it as the chosen action.
            onPickedSingle(
                AppEntry(
                    packageName = pkg,
                    componentName = null,
                    label = name ?: "Shortcut",
                    shortcutUri = uri,
                )
            )
        } else {
            error = "That shortcut didn't return a usable action. Try another."
        }
    }

    // Step 1 launcher: pick WHICH app's shortcut creator to use.
    val pickAppLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data ?: run { error = "No app chosen."; return@rememberLauncherForActivityResult }
        runCatching { createLauncher.launch(data) }
            .onFailure { error = "Could not open shortcut creator: ${it.message}" }
    }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Shortcuts assigned here launch on the PHONE screen — not the external display.",
            color = AbDanger,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Pick an app, then choose one of its shortcuts (e.g. a Tasker task). It becomes this button's action and fires on the phone when you tap the button.",
            color = AbOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AbPrimary.copy(alpha = 0.18f))
                .clickable {
                    error = null
                    val pick = Intent(Intent.ACTION_PICK_ACTIVITY).apply {
                        putExtra(Intent.EXTRA_INTENT, Intent(Intent.ACTION_CREATE_SHORTCUT))
                        putExtra(Intent.EXTRA_TITLE, "Pick a shortcut source")
                    }
                    runCatching { pickAppLauncher.launch(pick) }
                        .onFailure { error = "Couldn't open the shortcut picker: ${it.message}" }
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text("Pick a shortcut…", color = AbOnSurface, fontWeight = FontWeight.SemiBold)
        }
        if (error != null) {
            Text(error!!, color = AbDanger, style = MaterialTheme.typography.bodySmall)
        }
        // Echo the currently-assigned shortcut (if any) with a Clear action.
        if (assigned != null) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(color = AbOnSurfaceMuted)) {
                            append("Currently assigned: ")
                        }
                        withStyle(
                            androidx.compose.ui.text.SpanStyle(
                                color = AbPrimaryBright,
                                fontWeight = FontWeight.SemiBold,
                            )
                        ) {
                            append(assigned.label)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    "Clear",
                    color = AbOnSurfaceMuted,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onClear() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PickerDropdown(current: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { anchorWidthPx = it.width }
                .clip(RoundedCornerShape(12.dp))
                .background(AbSurfaceElevated)
                .border(1.dp, AbPrimaryBright.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                current,
                color = AbOnSurface,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose", tint = AbOnSurfaceMuted)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(AbSurfaceElevated)
                .width(with(density) { anchorWidthPx.toDp() }),
        ) {
            options.forEach { opt ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            opt,
                            color = if (opt == current) AbAccent else AbOnSurface,
                            fontWeight = if (opt == current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = { expanded = false; onSelect(opt) },
                )
            }
        }
    }
}

/**
 * A labelled on/off row for the Auto Launch page's "Launch triggers" group.
 * Mirrors the Controls/Display ToggleRowWithDesc style (label + description +
 * Switch), reading/writing the given boolean pref. Local to this screen so the
 * Auto Launch page can host these toggles without depending on SettingsScreen's
 * private helper.
 */
@Composable
private fun AutoLaunchToggleRow(
    label: String,
    desc: String,
    prefKey: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
    default: Boolean,
) {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val value by app.prefs.bool(prefKey, default).collectAsState(initial = default)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = AbOnSurface, style = MaterialTheme.typography.bodyLarge)
            Text(desc, color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = value,
            onCheckedChange = { scope.launch { app.prefs.setBool(prefKey, it) } },
            colors = SwitchDefaults.colors(
                checkedTrackColor = AbPrimary,
                checkedThumbColor = AbPrimaryBright,
                uncheckedTrackColor = AbSurfaceElevated,
                uncheckedThumbColor = AbOnSurfaceDim,
            ),
        )
    }
}

@Composable
private fun AppGroupHeader(
    packageName: String,
    appLabel: String,
    activityCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = packageName, sizeDp = 40)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(appLabel, color = AbOnSurface, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "$activityCount " + if (activityCount == 1) "activity" else "activities",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = AbOnSurfaceMuted,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 56.dp)
                .height(1.dp)
                .background(AbOnSurfaceMuted.copy(alpha = 0.12f)),
        )
    }
}

@Composable
private fun ActivityChildRow(
    label: String,
    packageName: String,
    appLabel: String,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.portalpad.app.ui.dock.AppIcon(packageName = packageName, sizeDp = 28)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    appLabel,
                    color = AbOnSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    label,
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp)
                .height(1.dp)
                .background(AbOnSurfaceMuted.copy(alpha = 0.08f)),
        )
    }
}

@Composable
private fun AppRow(
    listing: AppListing,
    isSelected: Boolean,
    showCheckbox: Boolean,
    disabled: Boolean = false,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    // Highlight only applies in multi-select (dock) mode; single
                    // pick (Home/Back) uses the plain separator-list style.
                    if (isSelected) Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(AbPrimaryDim.copy(alpha = 0.6f))
                    else Modifier,
                )
                .then(if (disabled) Modifier.alpha(0.38f) else Modifier)
                .clickable(enabled = !disabled) { onClick() }
                .padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = listing.packageName, sizeDp = 40)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(listing.label, color = AbOnSurface, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = listing.entry.componentName ?: listing.packageName,
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (showCheckbox) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) AbAccent else AbSurfaceElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = AbBackground,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        // Thin separator (inset, clears the scrollbar).
        Box(
            Modifier
                .fillMaxWidth()
                .padding(end = 12.dp)
                .height(1.dp)
                .background(androidx.compose.ui.graphics.Color(0x14FFFFFF)),
        )
    }
}

private data class AppListing(
    val label: String,
    val packageName: String,
    val entry: AppEntry,
    // For grouped (activities) display: the app's name and this row's activity
    // name, kept separately so a header can show the app and children the
    // activities. For app-level rows these are the app label / null.
    val appLabel: String = label,
    val activityLabel: String? = null,
)

private fun loadAppList(pm: PackageManager, withActivities: Boolean): List<AppListing> {
    val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved: List<ResolveInfo> = pm.queryIntentActivities(launchIntent, 0)
    val byPackage = resolved.groupBy { it.activityInfo.packageName }
    val results = mutableListOf<AppListing>()
    byPackage.forEach { (pkg, infos) ->
        val main = infos.first()
        val appLabel = main.loadLabel(pm).toString()
        if (!withActivities) {
            results += AppListing(appLabel, pkg, AppEntry(pkg, null, appLabel))
        } else {
            val activityInfos: Array<android.content.pm.ActivityInfo> = runCatching {
                pm.getPackageInfo(
                    pkg,
                    PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS
                ).activities
            }.getOrNull() ?: emptyArray()
            activityInfos.filter { it.exported }
                .forEach { act ->
                    // Most activities don't declare their own android:label, so
                    // loadLabel() returns the APP's label — making every activity
                    // row read identically as the app name. When the loaded label
                    // is blank OR just the app label, fall back to the activity's
                    // class name (the meaningful distinguishing identifier).
                    val loaded = act.loadLabel(pm).toString()
                    val shortClass = act.name.substringAfterLast('.')
                    val componentLabel = if (loaded.isBlank() || loaded == appLabel) shortClass else loaded
                    results += AppListing(
                        label = "$appLabel — $componentLabel",
                        packageName = pkg,
                        entry = AppEntry(pkg, act.name, "$appLabel · $componentLabel"),
                        appLabel = appLabel,
                        activityLabel = componentLabel,
                    )
                }
        }
    }
    return results.sortedBy { it.label.lowercase() }
}
