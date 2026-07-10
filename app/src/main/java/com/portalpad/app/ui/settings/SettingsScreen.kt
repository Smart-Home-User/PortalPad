package com.portalpad.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.portalpad.app.PortalPadApp
import com.portalpad.app.OrientationLock
import com.portalpad.app.data.AccessMode
import com.portalpad.app.data.BackAction
import com.portalpad.app.data.DockConfig
import com.portalpad.app.data.findFolder
import com.portalpad.app.data.allFoldersFlat
import com.portalpad.app.data.removeRecursive
import androidx.datastore.preferences.core.edit
import com.portalpad.app.data.InputMode
import com.portalpad.app.data.PreferencesRepository
import com.portalpad.app.shizuku.ShizukuManager
import com.portalpad.app.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * Five-tab settings screen — feels nothing like a vertical scroll list. Distinctive
 * violet+amber palette. Each tab focuses on one part of the system.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onEnableClick: () -> Unit,
    onPickHome: () -> Unit,
    onPickBack: () -> Unit,
    onPickDock: () -> Unit,
    onAuthorizeShizuku: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onPickBackupFolder: () -> Unit,
    onBackupNow: () -> Unit,
    onRestoreFromFile: () -> Unit,
    onScheduleBackup: (dayOfWeek: Int, hour: Int) -> Unit,
    openToPrivilege: Boolean = false,
    openToSetup: Boolean = false,
    onLaunchTrackpad: (() -> Unit)? = null,
) {
    val tabs = remember {
        listOf(
            TabSpec("Start", Icons.Default.PlayArrow),
            TabSpec("Workspace", Icons.Default.GridView),
            TabSpec("Controls", Icons.Default.Tune),
        )
    }
    var selectedTab by remember { mutableIntStateOf(0) }
    val pickerCtx = androidx.compose.ui.platform.LocalContext.current
    // Top-level section switch shown as a small edge-to-edge strip above the
    // tab row: "Settings" (the Start/Workspace/Controls tabbed UI) vs
    // "Permissions" (a transparency page listing every Android permission).
    // Permissions sub-page. Reached only via the 3-dot menu → Privilege Source,
    // then the "Permissions" link on the strip. The Settings|Permissions strip
    // only appears in this 3-dot sub-area (Privilege Source page + Permissions),
    // never on the main Start/Workspace/Controls tabs.
    var showPermissions by remember { mutableStateOf(false) }
    // Bumped when the user taps an already-active nav item (a main tab, or a
    // strip section they're already on) to signal that page to scroll to top.
    var scrollToTopTick by remember { mutableIntStateOf(0) }
    // Resources sub-page (reached from a note in the System tab). When true it
    // replaces the tab content with a full Resources screen + back arrow.
    var showResources by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showSystem by remember { mutableStateOf(openToPrivilege) }
    var showDisplay by remember { mutableStateOf(false) }
    var showWallpaperPicker by remember { mutableStateOf(false) }
    var showFolderManager by remember { mutableStateOf(false) }
    var showDockCustomization by remember { mutableStateOf(false) }
    var showTopBarCustomization by remember { mutableStateOf(false) }
    var showFolderWindowCustomization by remember { mutableStateOf(false) }
    // Button-actions page (the same ControlsSubpage the trackpad/remote/air-mouse
    // interfaces open) launched from the Start tab's Home/Back buttons. "Back"
    // returns to Start.
    var showControlsSubpage by remember { mutableStateOf(false) }
    // Start-setup subpage: a focused checklist (overlay, privilege, and an
    // optional notification-access question) gating a Continue button that
    // actually starts the service. Reached when the user taps Start Service while
    // requirements aren't met.
    var showStartSetup by remember { mutableStateOf(openToSetup) }
    val tabScroll = rememberScrollState()
    val tabScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            BrandedTopBar(
                onSettingsPage = showSystem || showPermissions || showResources,
                onOpenSettings = {
                    showResources = false; showDiagnostics = false; showSystem = true
                },
            )
        },
        containerColor = AbBackground,
    ) { pad ->
        if (showControlsSubpage) {
            // Full-screen button-actions page launched from the Start tab's
            // Home/Back buttons. Same page the trackpad/remote opens; "← Back"
            // returns to Start (Main) here.
            Box(Modifier.padding(pad)) {
                ControlsSubpage(
                    isAirMouse = false,
                    onBack = { showControlsSubpage = false },
                )
            }
        } else {
        Column(Modifier.padding(pad).fillMaxSize()) {
            ModeDebugStrip(
                onDebugPage = showDiagnostics,
                onOpenDebug = { showResources = false; showSystem = false; showDiagnostics = true },
                onFixPrivilege = { showResources = false; showDiagnostics = false; showSystem = false; showStartSetup = true },
            )
            // ── Main nav (Start | Workspace | Controls) — ALWAYS visible across
            // all Settings views, including every sub-page. Only the trackpad /
            // air-mouse / remote control surfaces omit it. No tab is highlighted
            // while on a sub-page (you're in a sub-area, not "on" a tab).
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val onSubPage = showResources || showDiagnostics || showSystem || showDisplay || showWallpaperPicker || showFolderManager || showPermissions || showDockCustomization || showTopBarCustomization || showFolderWindowCustomization || showStartSetup
                    tabs.forEachIndexed { i, spec ->
                        if (i > 0) {
                            VerticalDivider(
                                color = AbSurfaceElevated,
                                modifier = Modifier.height(20.dp),
                            )
                        }
                        // Start (tab 0) stays highlighted on the setup sub-page too,
                        // so it reads as part of the Start flow (you got there from
                        // Start Service). Other sub-pages highlight nothing.
                        val active = (selectedTab == i && !onSubPage) || (i == 0 && showStartSetup)
                        Row(
                            Modifier
                                .padding(horizontal = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    // Tapping a main tab leaves any sub-page and
                                    // shows that tab's real content. If already on
                                    // this tab (and not on a sub-page), scroll its
                                    // content back to the top.
                                    if (selectedTab == i && !onSubPage) {
                                        scrollToTopTick++
                                    }
                                    showResources = false
                                    showDiagnostics = false
                                    showSystem = false
                                    showDisplay = false
                                    showWallpaperPicker = false
                                    showFolderManager = false
                                    showDockCustomization = false
                                    showTopBarCustomization = false
                                    showFolderWindowCustomization = false
                                    showPermissions = false
                                    showStartSetup = false
                                    selectedTab = i
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                spec.icon, contentDescription = null,
                                tint = if (active) AbPrimary else AbOnSurfaceMuted,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                spec.label,
                                color = if (active) AbPrimary else AbOnSurfaceMuted,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
            // ── Settings | Permissions | Resources strip — BELOW the main nav,
            // ONLY on these three peer pages. Hidden everywhere else.
            if (showSystem || showPermissions || showResources) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(AbSurfaceElevated)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val sectionLabel: @Composable (String, Boolean, () -> Unit) -> Unit =
                        { label, sel, onTap ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onTap() }
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    label,
                                    color = if (sel) AbPrimaryBright else AbOnSurfaceMuted,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    sectionLabel("Settings", showSystem && !showPermissions) {
                        if (showSystem && !showPermissions) scrollToTopTick++
                        showPermissions = false
                        showResources = false; showDiagnostics = false
                        showDisplay = false; showWallpaperPicker = false; showFolderManager = false
                        showDockCustomization = false; showTopBarCustomization = false; showFolderWindowCustomization = false
                        showSystem = true
                    }
                    VerticalDivider(color = AbSurface, modifier = Modifier.height(16.dp))
                    sectionLabel("Permissions", showPermissions) {
                        if (showPermissions) scrollToTopTick++
                        showSystem = false; showResources = false; showDiagnostics = false
                        showDisplay = false; showWallpaperPicker = false; showFolderManager = false
                        showDockCustomization = false; showTopBarCustomization = false; showFolderWindowCustomization = false
                        showPermissions = true
                    }
                    VerticalDivider(color = AbSurface, modifier = Modifier.height(16.dp))
                    sectionLabel("Resources", showResources) {
                        if (showResources) scrollToTopTick++
                        showSystem = false; showPermissions = false; showDiagnostics = false
                        showDisplay = false; showWallpaperPicker = false; showFolderManager = false
                        showDockCustomization = false; showTopBarCustomization = false; showFolderWindowCustomization = false
                        showResources = true
                    }
                }
            }
            HorizontalDivider(color = AbSurface, thickness = 1.dp)

            // The tab / sub-page content gets the column's REMAINING height as a
            // bounded box. Without this it was measured with unbounded height, so
            // the Start tab's ScaleToFit couldn't shrink-to-fit (it overflowed once
            // content grew past the screen) and scrolling sub-pages couldn't scroll.
            Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showPermissions) {
                PermissionsPage(scrollToTopSignal = scrollToTopTick)
            } else {

            if (showResources) {
                ResourcesPage()
            } else if (showDiagnostics) {
                DiagnosticsPage(onBack = { showDiagnostics = false })
            } else if (showSystem) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Settings",
                            color = AbOnSurface,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    SystemTab(
                        onAuthorizeShizuku = onAuthorizeShizuku,
                        onOpenOverlaySettings = onOpenOverlaySettings,
                        onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                        onPickBackupFolder = onPickBackupFolder,
                        onBackupNow = onBackupNow,
                        onRestoreFromFile = onRestoreFromFile,
                        onScheduleBackup = onScheduleBackup,
                    )
                }
            } else if (showDisplay) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Display",
                            color = AbOnSurface,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    DisplayTab()
                }
            } else if (showWallpaperPicker) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Home backdrop",
                            color = AbOnSurface,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    WallpaperPickerPage()
                }
            } else if (showFolderManager) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Manage folders",
                            color = AbOnSurface,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    FolderManagerPage(onBack = { showFolderManager = false })
                }
            } else if (showDockCustomization) {
                DockCustomizationPage(onBack = { showDockCustomization = false })
            } else if (showTopBarCustomization) {
                TopBarCustomizationPage(onBack = { showTopBarCustomization = false })
            } else if (showFolderWindowCustomization) {
                FolderWindowCustomizationPage(onBack = { showFolderWindowCustomization = false })
            } else if (showStartSetup) {
                StartSetupPage(
                    onBack = { showStartSetup = false },
                    onGoToSystemTab = { showStartSetup = false; showSystem = true },
                    onContinue = {
                        showStartSetup = false
                        onEnableClick()
                    },
                )
            } else when (selectedTab) {
                0 -> StartTab(
                    onEnableClick = {
                        if (startRequirementsMet(pickerCtx)) onEnableClick()
                        else showStartSetup = true
                    },
                    onGoToSystemTab = { showSystem = true },
                    onGoToControls = { selectedTab = 2 },
                    onGoToDisplay = { showDisplay = true },
                    onLaunchTrackpad = onLaunchTrackpad,
                    onOpenButtonActions = { showControlsSubpage = true },
                    onOpenAutoLaunch = { com.portalpad.app.ui.dock.launchDockPickerOnPhone(pickerCtx, "autolaunch") },
                    onPickHome = onPickHome,
                    onPickBack = onPickBack,
                    onOpenStartSetup = { showStartSetup = true },
                )
                1 -> WorkspaceTab(
                    onPickDock,
                    onOpenWallpaperPicker = { showWallpaperPicker = true },
                    onOpenFolderManager = { showFolderManager = true },
                    onOpenDockCustomization = { showDockCustomization = true },
                    onOpenTopBarCustomization = { showTopBarCustomization = true },
                    onOpenFolderWindowCustomization = { showFolderWindowCustomization = true },
                    scrollToTopSignal = scrollToTopTick,
                )
                2 -> ButtonsTab(onPickHome, onPickBack, scrollToTopSignal = scrollToTopTick)
                else -> StartTab(
                    onEnableClick = {
                        if (startRequirementsMet(pickerCtx)) onEnableClick()
                        else showStartSetup = true
                    },
                    onGoToSystemTab = { showSystem = true },
                    onGoToControls = { selectedTab = 2 },
                    onGoToDisplay = { showDisplay = true },
                    onLaunchTrackpad = onLaunchTrackpad,
                    onOpenButtonActions = { showControlsSubpage = true },
                    onOpenAutoLaunch = { com.portalpad.app.ui.dock.launchDockPickerOnPhone(pickerCtx, "autolaunch") },
                    onPickHome = onPickHome,
                    onPickBack = onPickBack,
                    onOpenStartSetup = { showStartSetup = true },
                )
            }
            }
            }
        }
        }
    }
}

private data class TabSpec(val label: String, val icon: ImageVector)

/**
 * Home-backdrop picker (separate Workspace sub-page). Lets the user choose the
 * external-display wallpaper from a set of PortalPad-owned images, plus a
 * "None (black)" option. Selection persists to prefs; the backdrop itself shows
 * on the external display via WallpaperActivity. Never touches Android's
 * system wallpaper.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WallpaperPickerPage() {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Canvas-aware: classify the connected external display so the picker can open
    // on the matching tab and highlight the canvas-appropriate default (ultrawide →
    // Cosmic Genesis) when the user hasn't picked anything yet.
    val canvasUltrawide = remember {
        runCatching {
            val dm = app.getSystemService(android.hardware.display.DisplayManager::class.java)
            val ext = dm?.displays?.firstOrNull { com.portalpad.app.DisplayUtil.isRealExternalDisplay(it) }
            if (ext != null) {
                val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") ext.getRealMetrics(it) }
                com.portalpad.app.data.Wallpaper.isUltrawideCanvas(m.widthPixels, m.heightPixels)
            } else false
        }.getOrDefault(false)
    }
    var selectedCategory by remember {
        mutableStateOf(
            if (canvasUltrawide) com.portalpad.app.data.WallpaperCategory.ULTRAWIDE
            else com.portalpad.app.data.WallpaperCategory.STANDARD
        )
    }
    val ultra = selectedCategory == com.portalpad.app.data.WallpaperCategory.ULTRAWIDE
    // Per-canvas selection: each tab remembers its own pick; the live external
    // canvas decides which one actually renders on the glasses.
    val stdRaw by app.prefs.homeWallpaperRaw(false).collectAsState(initial = null)
    val uwRaw by app.prefs.homeWallpaperRaw(true).collectAsState(initial = null)
    val current = (if (ultra) uwRaw else stdRaw) ?: com.portalpad.app.data.Wallpaper.effectiveDefault(ultra)
    val customPath by app.prefs.customWallpaperPath.collectAsState(initial = null)

    // Photo picker — copies the chosen image into app-private storage (robust
    // against the gallery URI later becoming invalid), saves the path, and
    // selects it as the custom backdrop.
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val dir = java.io.File(ctx.filesDir, "wallpapers").apply { mkdirs() }
                        val out = java.io.File(dir, "custom.jpg")
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                        out.absolutePath
                    }.getOrNull()
                }
                if (saved != null) {
                    app.prefs.setCustomWallpaperPath(saved)
                    app.prefs.setHomeWallpaper(
                        selectedCategory == com.portalpad.app.data.WallpaperCategory.ULTRAWIDE,
                        com.portalpad.app.data.Wallpaper.CUSTOM,
                    )
                    app.injector.buzz(longPress = false)
                }
            }
        }
    }

    TabBody {
        Text(
            "Shown on the external display when no home app is set, or after you close all apps. This is PortalPad's own backdrop — it does not change your phone's wallpaper.",
            color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                append("Note: ")
                pop()
                append("The preset images below were AI-generated using Google Gemini.")
            },
            color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
        )
        // Per-canvas tip (amber = informational, like the SBS note below).
        Spacer(Modifier.height(2.dp))
        Text(
            text = androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                append("Tip: ")
                pop()
                append("Standard and ultrawide canvases each keep their own wallpaper — the matching one is selected automatically for the current resolution.")
            },
            color = AbWarning, style = MaterialTheme.typography.bodySmall,
        )
        // Category switch: Standard (16:9 / 1920x1080) vs Ultrawide (21:9 & 32:9).
        // Filters the grid below; opens on the tab matching the connected canvas.
        Spacer(Modifier.height(2.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AbSurface)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                com.portalpad.app.data.WallpaperCategory.STANDARD to "Standard",
                com.portalpad.app.data.WallpaperCategory.ULTRAWIDE to "Ultrawide",
            ).forEach { (cat, label) ->
                val sel = selectedCategory == cat
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (sel) AbPrimary else Color.Transparent)
                        .clickable {
                            app.injector.buzz(longPress = false)
                            selectedCategory = cat
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (sel) AbOnSurface else AbOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        val caption = if (ultra) "21:9 & 32:9 — Ultrawide displays" else "1920\u00D71080 — Standard displays"
        Text(
            caption,
            color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                append("Note: ")
                pop()
                append(
                    if (ultra)
                        "These also appear in Full 3D SBS (3840\u00D71080). Half 3D SBS is 1920\u00D71080 — a Standard wallpaper."
                    else
                        "Half 3D SBS (1920\u00D71080) also uses these. Full 3D SBS is 3840\u00D71080 — an Ultrawide wallpaper."
                )
            },
            color = AbWarning, style = MaterialTheme.typography.bodySmall,
        )
        // Use-your-own-image button at the top.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AbAccent.copy(alpha = 0.14f))
                .border(1.dp, AbAccent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                .clickable {
                    pickImage.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = AbAccent)
            Spacer(Modifier.width(12.dp))
            Text(
                "Use my own image",
                color = AbOnSurface, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            com.portalpad.app.data.Wallpaper.entries.forEach { wp ->
                // The CUSTOM tile only appears once the user has actually picked
                // an image (choice (a) — hidden until picked).
                if (wp == com.portalpad.app.data.Wallpaper.CUSTOM && customPath == null) return@forEach
                // Category filter: show this tab's wallpapers plus the universal
                // ones (None, Custom) which fit any canvas.
                if (wp.category != com.portalpad.app.data.WallpaperCategory.UNIVERSAL &&
                    wp.category != selectedCategory) return@forEach
                val selected = wp == current
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(150.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                            .then(
                                if (selected) Modifier.border(2.5.dp, AbPrimaryBright, RoundedCornerShape(12.dp))
                                else Modifier.border(1.dp, AbSurfaceElevated, RoundedCornerShape(12.dp))
                            )
                            .clickable {
                                app.injector.buzz(longPress = false)
                                scope.launch { app.prefs.setHomeWallpaper(ultra, wp) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val res = wp.drawableRes
                        when {
                            wp == com.portalpad.app.data.Wallpaper.CUSTOM && customPath != null -> {
                                val bmp = remember(customPath) {
                                    runCatching { android.graphics.BitmapFactory.decodeFile(customPath) }.getOrNull()
                                }
                                if (bmp != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = wp.label,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    )
                                }
                                // Overlaid "Custom Image" bar at the bottom of the tile.
                                Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .padding(vertical = 3.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("Custom Image", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            res != null -> {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(res),
                                    contentDescription = wp.label,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            }
                            else -> {
                                Text("None", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (selected) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(AbPrimaryBright),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Check, contentDescription = "Selected",
                                    tint = Color.Black, modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // The custom tile shows its label ON the image; built-ins show it below.
                    if (wp != com.portalpad.app.data.Wallpaper.CUSTOM) {
                        Text(
                            wp.label,
                            color = if (selected) AbPrimaryBright else AbOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Phase A folder manager (a Workspace sub-page). Consolidates folder editing that was
 * previously only reachable by long-pressing dock items: browse folders, drill
 * into a folder (and subfolders), reorder its contents (up/down), remove items,
 * and sort. Rename and icon customization remain available via the dock's own
 * long-press. Edits write straight to the dock config, so the dock on the
 * external display updates as the config changes.
 */
@Composable
private fun FolderManagerPage(onBack: () -> Unit = {}) {
    val app = PortalPadApp.instance
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by app.prefs.dockConfig.collectAsState(initial = com.portalpad.app.data.DockConfig())
    var stack by remember { mutableStateOf<List<String>>(emptyList()) }
    // Which folder's appearance editor is open (by id), if any.
    var appearanceFolderId by remember { mutableStateOf<String?>(null) }
    // Bulk-select mode: when on, rows show checkboxes and a bulk action bar
    // appears. Selection is scoped to the current view and cleared on navigation.
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Move-to-folder picker (bulk) open?
    var bulkPickFolder by remember { mutableStateOf(false) }

    fun recentMillis(pkg: String): Long = runCatching {
        app.packageManager.getPackageInfo(pkg, 0).firstInstallTime
    }.getOrDefault(0L)

    val currentFolder = stack.lastOrNull()?.let { cfg.items.findFolder(it) }
    // Mirror the folder being managed onto the external display (real contents,
    // live) so reordering / edits are visible there as you make them. Clears when
    // you leave the folder or close the screen.
    androidx.compose.runtime.DisposableEffect(currentFolder?.id) {
        app.setManagedFolderId(currentFolder?.id)
        // Navigating to a different folder view: reset any bulk selection so we
        // never act on items that aren't visible here.
        selectMode = false
        selectedIds = emptySet()
        bulkPickFolder = false
        onDispose { app.setManagedFolderId(null) }
    }
    val shown: List<com.portalpad.app.data.DockItem> =
        if (currentFolder == null) cfg.items.filterIsInstance<com.portalpad.app.data.DockItem.Folder>()
        else currentFolder.contents

    val externalDisplayId by app.externalDisplayId.collectAsState()

    Box(Modifier.fillMaxSize()) {
    TabBody {
        // Heads-up when there's no external display: folder edits save to the
        // dock config and will show once a display is connected, but you won't
        // see them reflected live right now.
        if (externalDisplayId == null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF8A7A4D), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFFB8A66A),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "No display connected. Changes are saved and will appear on your external display once connected — you just won't see them live right now.",
                    color = Color(0xFFB8A66A),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        if (stack.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .clickable { stack = stack.dropLast(1) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                ) {
                    Text("‹ Up", color = AbAccent, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    currentFolder?.label ?: "",
                    color = AbOnSurface, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                )
            }
        } else {
            Text(
                "Top-level folders. Tap a folder to manage its contents.",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
        }

        currentFolder?.let { folder ->
            SectionCard(title = "Sort contents") {
                com.portalpad.app.ui.dock.SortControl(
                    sort = folder.sort,
                    ascending = folder.sortAscending,
                    colors = com.portalpad.app.ui.dock.themeFolderControlColors(),
                    onChange = { mode, asc ->
                        scope.launch {
                            app.prefs.setDockConfig(cfg.sortFolder(folder.id, mode, asc, ::recentMillis))
                        }
                    },
                )
            }
        }

        SectionCard(title = if (currentFolder == null) "Folders" else "Contents") {
            // Add a new top-level folder (only at the top level, not inside a folder).
            if (currentFolder == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AbSurfaceElevated)
                        .clickable {
                            scope.launch {
                                if (cfg.isFull) {
                                    android.widget.Toast.makeText(
                                        app, "Dock is full (${com.portalpad.app.data.MAX_DOCK_ITEMS} items). Remove an item first.",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    app.prefs.setDockConfig(
                                        cfg.addFolder("Folder ${cfg.items.count { it is com.portalpad.app.data.DockItem.Folder } + 1}")
                                    )
                                }
                            }
                        }
                        .padding(14.dp),
                ) {
                    Text(
                        "+  Add a folder",
                        color = AbAccent,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(Modifier.height(8.dp))
            } else {
                // Inside a folder: add apps directly to it. Reuses the same
                // phone app-picker the dock uses, targeted at this folder, so
                // picked apps land straight inside it.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AbSurfaceElevated)
                        .clickable {
                            com.portalpad.app.ui.dock.launchDockPickerOnPhone(
                                ctx, "folder:${currentFolder.id}",
                            )
                        }
                        .padding(14.dp),
                ) {
                    Text(
                        "+  Add apps to this folder",
                        color = AbAccent,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            // Bulk-select controls. Only meaningful when there are items to act on.
            if (shown.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selectMode = !selectMode
                                if (!selectMode) { selectedIds = emptySet(); bulkPickFolder = false }
                            }
                            .padding(vertical = 6.dp, horizontal = 8.dp),
                    ) {
                        Text(
                            if (selectMode) "Done" else "Select",
                            color = AbAccent, style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (selectMode) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${selectedIds.size} selected",
                            color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.weight(1f))
                        // Select-all / none toggle.
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    selectedIds = if (selectedIds.size == shown.size) emptySet()
                                    else shown.map { it.id }.toSet()
                                }
                                .padding(vertical = 6.dp, horizontal = 8.dp),
                        ) {
                            Text(
                                if (selectedIds.size == shown.size) "None" else "All",
                                color = AbAccent, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                // Bulk action bar — visible once something is selected.
                if (selectMode && selectedIds.isNotEmpty() && !bulkPickFolder) {
                    val moveTargets = cfg.items.allFoldersFlat().filter { f ->
                        f.id != currentFolder?.id && f.id !in selectedIds
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (moveTargets.isNotEmpty()) {
                            BulkActionChip("Move to folder…") { bulkPickFolder = true }
                        }
                        // "Move out to dock" only matters inside a folder.
                        if (currentFolder != null) {
                            BulkActionChip("Move out to dock") {
                                scope.launch {
                                    var c = cfg
                                    selectedIds.forEach { id -> c = c.extractToTopLevel(id) }
                                    app.prefs.setDockConfig(c)
                                    selectMode = false; selectedIds = emptySet()
                                }
                            }
                        }
                        BulkActionChip("Remove", danger = true) {
                            scope.launch {
                                var c = cfg
                                selectedIds.forEach { id -> c = c.copy(items = c.items.removeRecursive(id)) }
                                app.prefs.setDockConfig(c)
                                selectMode = false; selectedIds = emptySet()
                            }
                        }
                    }
                }
                // Bulk move-to-folder picker.
                if (selectMode && bulkPickFolder) {
                    val moveTargets = cfg.items.allFoldersFlat().filter { f ->
                        f.id != currentFolder?.id && f.id !in selectedIds
                    }
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Move ${selectedIds.size} to…", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                        moveTargets.forEach { f ->
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(AbSurfaceElevated)
                                    .clickable {
                                        scope.launch {
                                            var c = cfg
                                            selectedIds.forEach { id -> c = c.moveIntoFolder(id, f.id) }
                                            app.prefs.setDockConfig(c)
                                            selectMode = false; selectedIds = emptySet(); bulkPickFolder = false
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) { Text(f.label, color = AbOnSurface, style = MaterialTheme.typography.bodyMedium) }
                        }
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp)).clickable { bulkPickFolder = false }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) { Text("‹ Back", color = AbAccent, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            if (shown.isEmpty()) {
                Text(
                    if (currentFolder == null) "No folders yet. Tap “Add a folder” above."
                    else "This folder is empty.",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
            }
            shown.forEachIndexed { index, item ->
                val isFolder = item is com.portalpad.app.data.DockItem.Folder
                val checked = item.id in selectedIds
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .then(
                            if (selectMode) Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                selectedIds = if (checked) selectedIds - item.id else selectedIds + item.id
                            } else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectMode) {
                        Text(
                            if (checked) "\u2611" else "\u2610", // ☑ / ☐
                            color = if (checked) AbAccent else AbOnSurfaceMuted,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                    Column(
                        Modifier.weight(1f)
                            .then(
                                if (isFolder && !selectMode) Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { stack = stack + item.id }
                                else Modifier
                            )
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            (if (isFolder) "📁  " else "") + item.label,
                            color = AbOnSurface, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                        )
                        if (isFolder) {
                            val f = item as com.portalpad.app.data.DockItem.Folder
                            Text(
                                "${f.contents.size} items · tap to open",
                                color = AbAccent, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    // Per-row controls hidden in select mode (bulk bar handles actions).
                    if (!selectMode) {
                        if (isFolder) {
                            IconTextButton("🎨") { appearanceFolderId = item.id }
                        }
                        IconTextButton("↑", enabled = index > 0) {
                            scope.launch {
                                val newCfg = if (currentFolder == null)
                                    cfg.copy(items = cfg.items.moveFolderListItem(shown, index, index - 1))
                                else cfg.moveItemInFolder(currentFolder.id, index, index - 1)
                                app.prefs.setDockConfig(newCfg)
                            }
                        }
                        IconTextButton("↓", enabled = index < shown.size - 1) {
                            scope.launch {
                                val newCfg = if (currentFolder == null)
                                    cfg.copy(items = cfg.items.moveFolderListItem(shown, index, index + 1))
                                else cfg.moveItemInFolder(currentFolder.id, index, index + 1)
                                app.prefs.setDockConfig(newCfg)
                            }
                        }
                        IconTextButton("✕", danger = true) {
                            scope.launch { app.prefs.setDockConfig(cfg.copy(items = cfg.items.removeRecursive(item.id))) }
                        }
                    }
                }
            }
        }
        // Clear space so the pinned Back button doesn't cover the last row.
        Spacer(Modifier.height(72.dp))
    }

    // Per-folder appearance editor (reuses the dock's editor). Opens when a
    // folder's palette button is tapped; persists via setFolderAppearance.
    appearanceFolderId?.let { fid ->
        val folder = cfg.items.findFolder(fid)
        if (folder != null) {
            com.portalpad.app.ui.dock.FolderAppearanceEditor(
                folder = folder,
                onDismiss = { appearanceFolderId = null },
                onApply = { color, appearance, presetKey, symbol, iconPackPackage, iconPackDrawable ->
                    scope.launch {
                        app.prefs.setDockConfig(
                            cfg.setFolderAppearance(fid, color, appearance, presetKey, symbol, iconPackPackage, iconPackDrawable)
                        )
                    }
                    appearanceFolderId = null
                },
            )
        } else {
            appearanceFolderId = null
        }
    }

    // Pinned bottom Back. Inside a folder it goes up a level; at the top level
    // it exits the Manage Folders page (matches the other sub-pages' pattern).
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AbSurfaceElevated)
            .border(1.dp, AbAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable { if (stack.isNotEmpty()) stack = stack.dropLast(1) else onBack() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (stack.isNotEmpty()) "\u2190 Up" else "\u2190 Back",
            color = AbOnSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        )
    }
    }
}

/** Move within the filtered top-level folder list, mapping back to full items. */
private fun List<com.portalpad.app.data.DockItem>.moveFolderListItem(
    shown: List<com.portalpad.app.data.DockItem>,
    from: Int, to: Int,
): List<com.portalpad.app.data.DockItem> {
    if (from !in shown.indices || to !in shown.indices) return this
    val fromId = shown[from].id
    val toId = shown[to].id
    val absFrom = indexOfFirst { it.id == fromId }
    val absTo = indexOfFirst { it.id == toId }
    if (absFrom < 0 || absTo < 0) return this
    val m = toMutableList()
    val moved = m.removeAt(absFrom)
    m.add(absTo, moved)
    return m
}

/** Small text "icon" button used by the folder manager rows. */
@Composable
private fun BulkActionChip(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AbSurfaceElevated)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            color = if (danger) AbAccent else AbOnSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun IconTextButton(symbol: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AbSurfaceElevated)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            symbol,
            color = when {
                !enabled -> AbOnSurfaceDim
                danger -> AbAccent
                else -> AbOnSurface
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * Permissions transparency page (the "Permissions" top-section). Lists every
 * Android permission PortalPad declares, grouped Required / Optional / System,
 * each with a plain-language reason. Where a permission is user-toggleable, an
 * "Open settings" button deep-links to the most specific Android screen we can
 * reach; privileged (Shizuku/Root) and auto-granted permissions are shown for
 * transparency but are informational (no per-permission toggle exists).
 */
@Composable
private fun PermissionsPage(scrollToTopSignal: Int = 0) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Action kinds: which Android settings screen (if any) a row links to.
    fun openAppDetails() = runCatching {
        context.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // Overlay settings: pass the package URI so most devices open straight to
    // PortalPad's own "display over other apps" toggle instead of the full app
    // list. Falls back to the bare action (general list) if the OEM rejects the
    // URI, then to app details as a last resort.
    fun openOverlaySettings() = runCatching {
        context.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.recoverCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { openAppDetails() }

    // Notification-listener settings: on Android 11+ try the per-app detail
    // screen (jumps straight to PortalPad's listener toggle); fall back to the
    // general listener list on older versions / OEMs that don't support it.
    fun openNotificationListenerSettings() = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val cn = android.content.ComponentName(
                context, com.portalpad.app.service.PortalPadNotificationListenerService::class.java,
            )
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            throw IllegalStateException("pre-30")
        }
    }.recoverCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { openAppDetails() }
    fun openIntent(action: String) = runCatching {
        context.startActivity(
            android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { openAppDetails() }

    // Recompute statuses whenever we resume (e.g. returning from an Android
    // settings screen where the user just toggled something).
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun hasRuntime(perm: String): PermStatus {
        refreshTick // read so this re-evaluates on resume-driven recomposition
        return if (androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) PermStatus.GRANTED else PermStatus.DENIED
    }
    val overlayStatus = remember(refreshTick) {
        if (android.provider.Settings.canDrawOverlays(context)) PermStatus.GRANTED else PermStatus.DENIED
    }
    val notifListenerStatus = remember(refreshTick) {
        val enabled = runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners",
            )?.contains(context.packageName) == true
        }.getOrDefault(false)
        if (enabled) PermStatus.GRANTED else PermStatus.DENIED
    }
    val accessibilityStatus = remember(refreshTick) {
        val enabled = runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )?.contains(context.packageName) == true
        }.getOrDefault(false)
        if (enabled) PermStatus.GRANTED else PermStatus.DENIED
    }
    val batteryStatus = remember(refreshTick) {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) PermStatus.GRANTED else PermStatus.DENIED
    }
    TabBody(scrollToTopSignal = scrollToTopSignal) {
        Text(
            "Every permission PortalPad uses and why. Privileged items are granted through your access source (Shizuku/Root); some are granted automatically and have nothing to toggle.",
            color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
        )

        SectionCard(title = "Required") {
            PermissionRow("Display over other apps",
                "Draws the cursor, dock, and top bar on top of apps on the external display.",
                status = overlayStatus,
                onOpen = { openOverlaySettings() },
                isFirst = true)
            PermissionRow("Inject input events",
                "Sends taps, clicks, and key presses to the external display so the trackpad and buttons work. Granted via Shizuku/Root.",
                status = PermStatus.PRIVILEGED)
            PermissionRow("Manage system windows",
                "Lets the cursor and overlays sit above system UI on the external display. Granted via Shizuku/Root.",
                status = PermStatus.PRIVILEGED)
            PermissionRow("Manage activity tasks",
                "Launches, arranges, and resizes app windows on the external display (freeform, Arrange). Granted via Shizuku/Root.",
                status = PermStatus.PRIVILEGED)
            PermissionRow("Write secure settings",
                "Lets PortalPad adjust protected system settings needed for input injection and display setup. Granted via Shizuku/ADB.",
                status = PermStatus.PRIVILEGED)
            PermissionRow("Foreground service",
                "Keeps PortalPad running reliably while it drives the external display. Granted automatically.",
                status = PermStatus.AUTO)
            PermissionRow("See installed apps",
                "Lists your installed apps so you can add them to the dock and launch them. Granted automatically.",
                status = PermStatus.AUTO)
        }

        SectionCard(title = "Optional") {
            PermissionRow("Notification access",
                "Powers media controls (play/pause, track info) and the volume readout in the remote interface.",
                status = notifListenerStatus,
                onOpen = { openNotificationListenerSettings() },
                isFirst = true)
            PermissionRow("Accessibility service",
                "Required for auto-open: detects when you tap a text field on the glasses and opens the typing page automatically, showing the field's current text to edit. Without it, the typing page won't open on field taps.",
                status = accessibilityStatus,
                onOpen = { openIntent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS) })
            PermissionRow("Microphone",
                "Voice search from the dock.",
                status = hasRuntime(android.Manifest.permission.RECORD_AUDIO),
                onOpen = { openAppDetails() })
            PermissionRow("Camera",
                "Toggles the phone's flashlight/torch from the remote interface. Android controls the torch through the camera, so this is needed even though no photos are taken.",
                status = hasRuntime(android.Manifest.permission.CAMERA),
                onOpen = { openAppDetails() })
            PermissionRow("Notifications",
                "Shows the ongoing service notification and any alerts.",
                status = if (android.os.Build.VERSION.SDK_INT >= 33)
                    hasRuntime("android.permission.POST_NOTIFICATIONS") else PermStatus.AUTO,
                onOpen = { openAppDetails() })
            PermissionRow("Ignore battery optimization",
                "Stops Android from killing the service in the background so the external display doesn't drop.",
                status = batteryStatus,
                onOpen = { openIntent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) })
            PermissionRow("Phone state",
                "Powers the mobile network-type label (5G/LTE/4G) on the status icon. Signal bars don't need it. Granted via Shizuku/Root when you opt in during setup.",
                status = hasRuntime(android.Manifest.permission.READ_PHONE_STATE),
                onOpen = { openAppDetails() })
            PermissionRow("Bluetooth devices",
                "Counts the connected Bluetooth devices shown next to the Bluetooth tile in the status menu. Granted via Shizuku/Root when you opt in during setup.",
                status = hasRuntime(android.Manifest.permission.BLUETOOTH_CONNECT),
                onOpen = { openAppDetails() })
            PermissionRow("Start on boot",
                "Auto-starts the service after you restart your phone. Granted automatically.",
                status = PermStatus.AUTO)
        }

        SectionCard(title = "System") {
            PermissionRow("Vibrate",
                "Haptic feedback on taps and button presses. Granted automatically.",
                status = PermStatus.AUTO, isFirst = true)
            PermissionRow("Keep awake",
                "Keeps the connection alive while driving the external display. Granted automatically.",
                status = PermStatus.AUTO)
            PermissionRow("Network state",
                "Reads connectivity state for the Wi-Fi/mobile status indicators in the remote. Granted automatically.",
                status = PermStatus.AUTO)
        }
    }
}

/** Status shown as a badge on a permission row. GRANTED/DENIED are checkable;
 *  PRIVILEGED = granted via Shizuku/Root (a runtime check would falsely say
 *  denied); AUTO = normal permission granted automatically at install. */
private enum class PermStatus { GRANTED, DENIED, PRIVILEGED, AUTO }

/** One permission entry: name + reason + a status badge, with an optional
 *  "Open settings" deep-link. When onOpen is null the row is informational. */
@Composable
private fun PermissionRow(
    name: String,
    reason: String,
    status: PermStatus,
    onOpen: (() -> Unit)? = null,
    isFirst: Boolean = false,
) {
    Column {
        if (!isFirst) {
            HorizontalDivider(
                color = AbSurfaceElevated,
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = AbOnSurface, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(8.dp))
                    PermBadge(status)
                }
                Text(reason, color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            }
            if (onOpen != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AbSurfaceElevated)
                        .clickable { onOpen() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("Open", color = AbAccent, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Colored status pill for a permission row. */
@Composable
private fun PermBadge(status: PermStatus) {
    val (label, color) = when (status) {
        PermStatus.GRANTED -> "Granted" to androidx.compose.ui.graphics.Color(0xFF4CAF50)
        PermStatus.DENIED -> "Denied" to AbAccent
        PermStatus.PRIVILEGED -> "Shizuku/Root" to AbPrimaryBright
        PermStatus.AUTO -> "Auto" to AbOnSurfaceMuted
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Full-screen Resources sub-page (companion apps + community guides). Reached
 * from the note at the top of the System tab. Kept off the tab bar since it's
 * setup-time reference content, not something you return to constantly.
 */
@Composable
private fun ResourcesPage() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        // Header — no back arrow: Resources is a top-strip peer of
        // Settings/Permissions now (was a Settings sub-page with a box).
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Resources",
                color = AbOnSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        TabBody {
            SectionCard(title = "Guides", tightSpacing = true, titleDivider = true) {
                val guideUrl = "https://xrealguide.wixsite.com/unofficial"
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(guideUrl),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        .padding(bottom = 6.dp),
                ) {
                    Text(
                        "XREAL Unofficial Guide",
                        color = AbOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "https://xrealguide.wixsite.com/unofficial",
                        color = AbPrimaryBright,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            SectionCard(title = "Companion apps", tightSpacing = true, titleDivider = true) {
                Text(
                    "Shizuku",
                    color = AbOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Lets normal apps use system APIs at ADB level — or root level on rooted devices. PortalPad uses it to drive the external display, IME policy, and launching apps.",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Recommended build — adds start-on-boot, a watchdog (auto-restarts if it crashes), and TCP mode so Shizuku stays running reliably.",
                    color = AbOnSurfaceDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.gitHubDest { openUrl(ctx, "https://github.com/thedjchi/Shizuku") },
                    ),
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    "Extinguish (screen power)",
                    color = AbOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Powers the Remote tab's red power button. Tap = screen off; long-press = open Extinguish.",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.playStoreDest { openStore(ctx, "own.moderpach.extinguish") },
                        com.portalpad.app.ui.common.gitHubDest { openUrl(ctx, "https://github.com/Moderpach/Extinguish") },
                    ),
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append("Komi Store")
                        }
                        withStyle(
                            androidx.compose.ui.text.SpanStyle(
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                fontWeight = FontWeight.Normal,
                            ),
                        ) {
                            append(" (formerly GitHub Store)")
                        }
                    },
                    color = AbOnSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Komi Store is an open \"app store\" for GitHub, Codeberg, Forgejo, and Gitea releases. Discover repositories that ship real installers and keep them updated \u2014 no scraping, no waiting on stores. Browse Trending, Hot Releases, Most Popular, and topic feeds (Privacy, Media, Productivity, Networking, Dev Tools). Search across GitHub or any forge host you trust. Tap an APK and install it \u2014 the app handles the rest.",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.gitHubDest {
                            openUrl(ctx, "https://github.com/kurikomi-labs/komi-store")
                        },
                        com.portalpad.app.ui.common.fDroidDest {
                            openUrl(ctx, "https://f-droid.org/en/packages/zed.rainxch.githubstore/")
                        },
                    ),
                )
            }

            SectionCard(title = "XR community apps", tightSpacing = true, titleDivider = true) {
                Text(
                    "DroidOS Launcher",
                    color = AbOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "DroidOS Launcher is an app launcher and multi-window controller for Android devices, including cover screens and external displays. It is designed to work with Shizuku to provide advanced launch and window management actions without requiring root.",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.gitHubDest {
                            openUrl(ctx, "https://github.com/Katsuyamaki/DroidOS")
                        },
                        com.portalpad.app.ui.common.fDroidDest {
                            openUrl(ctx, "https://f-droid.org/en/packages/com.katsuyamaki.DroidOSFOSSLauncher/")
                        },
                    ),
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    "ScreenLab XR: External Display",
                    color = AbOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Visual effects for your external display / XR glasses over USB-C: artificial HDR, HUD, split/portrait views, head-controlled cursor, screen casting, and more.",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                ResourceLinkButton(
                    iconRes = com.portalpad.app.R.drawable.ic_playstore,
                    label = "Open in Play Store",
                    onClick = { openStore(ctx, "com.northnroro.nroro_shader") },
                )
            }

            SectionCard(title = "Other apps", tightSpacing = true, titleDivider = true) {
                Text(
                    "Win-X Launcher",
                    color = AbOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Brings a Windows 10/11 look and feel to Android phones and tablets: customizable Start panel and taskbar, pinned apps, widgets, file explorer, media player, notification panel, gestures, and daily Bing wallpapers. Highly configurable, no ads.",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Ultimate version unlocks the full premium experience with a single upfront purchase.",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.playStoreDest(sublabel = "(Standard)") {
                            openStore(ctx, "com.InternityLabs.Launcher.WinX")
                        },
                        com.portalpad.app.ui.common.playStoreDest(sublabel = "(Ultimate)") {
                            openStore(ctx, "com.InternityLabs.Launcher.WinX.Ultimate")
                        },
                    ),
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    "Projectivy Launcher",
                    color = AbOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "A clean, ad-free, highly customizable launcher for Android TV, projectors, and set-top boxes: custom layouts, animated wallpapers, icon packs, parental controls, backups, and direct input-source shortcuts. Some features need a premium upgrade.",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.playStoreDest { openStore(ctx, "com.spocky.projengmenu") },
                        com.portalpad.app.ui.common.gitHubDest { openUrl(ctx, "https://github.com/spocky/miproja1") },
                    ),
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    "Kodi",
                    color = AbOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Free, open-source media center for the living room: browse and play your own videos, music, and photos from local storage, your network, or the internet, with a remote-friendly 10-foot interface. Ships with no content — you supply your own.",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Recommended for 3D SBS content — navigation, subtitles, and the video itself all display properly in 3D.",
                    color = AbOnSurfaceDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                com.portalpad.app.ui.common.OpenInRow(
                    destinations = listOf(
                        com.portalpad.app.ui.common.playStoreDest { openStore(ctx, "org.xbmc.kodi") },
                        com.portalpad.app.ui.common.OpenInDest(
                            iconRes = com.portalpad.app.R.drawable.ic_kodi,
                            label = "Kodi.tv",
                            onClick = { openUrl(ctx, "https://kodi.tv/download/android/") },
                        ),
                    ),
                )
            }
        }
    }
}

/**
 * Diagnostics sub-page (opened by "Debug" on the mode strip). Hosts the
 * diagnostic toggles that used to live in the Controls tab, plus a button to
 * open the logcat viewer (which used to be the top-right "Logs" button). One
 * page, one entry point.
 */
@Composable
private fun DiagnosticsPage(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onBack() }
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Back",
                    tint = AbOnSurface,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Diagnostics",
                color = AbOnSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        TabBody {
            SectionCard(title = "Logs") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            ctx.startActivity(
                                android.content.Intent(ctx, com.portalpad.app.diag.LogcatActivity::class.java),
                            )
                        }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = AbOnSurface,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "View logcat",
                        color = AbOnSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = AbOnSurfaceMuted,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrandedTopBar(onSettingsPage: Boolean, onOpenSettings: () -> Unit) {
    val app = PortalPadApp.instance
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val accessMode by app.prefs.accessMode.collectAsState(initial = AccessMode.NONE)
    val shizukuStatus by app.shizuku.status.collectAsState()
    val shizukuReady = shizukuStatus == ShizukuManager.Status.READY
    val rootReady = app.root.isReady
    // Settings needs attention when no full-feature source is set — mirror the
    // strip's warning condition so the gear nudges first-run users into setup.
    val needsSetup = when (accessMode) {
        AccessMode.SHIZUKU -> !shizukuReady
        AccessMode.ROOT -> !rootReady
        AccessMode.NONE -> true
    }
    // Violet when EITHER we're on the Settings page (active highlight) OR setup
    // is needed (attention). Uses AbPrimary to match the nav tabs' and Debug
    // affordance's active color. The dot is shown ONLY for setup-needed.
    val gearTint = if (onSettingsPage) AbPrimary else AbOnSurfaceMuted
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // App icon foreground glyph — small touch of brand presence
                // beside the title. Foreground only (no rounded background
                // mask) for visual lightness; the icon's own portal/pad
                // composition reads cleanly at 24dp on the dark app bar.
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        com.portalpad.app.R.drawable.ic_launcher_foreground,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "PortalPad",
                    color = AbOnSurface, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onLongPress = {
                            // Long-press the title → PortalPad's App info page (handy
                            // for the restricted-settings flow + permissions/force-stop).
                            // Always opens it, so always give the long-press haptic.
                            app.injector.buzz(longPress = true)
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.fromParts("package", ctx.packageName, null),
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        })
                    },
                )
                Spacer(Modifier.width(8.dp))
                // Version number — makes it easy to confirm which build is
                // actually installed without digging into system settings.
                // Amber when the last update check says this binary is NOT the
                // published latest (ahead of GitHub, or same tag but different
                // bytes); the update page explains why.
                Text(
                    "v${com.portalpad.app.BuildConfig.VERSION_NAME}",
                    color = if (com.portalpad.app.service.UpdateChecker.versionTintAmber.value) {
                        com.portalpad.app.ui.theme.AbWarning
                    } else {
                        AbOnSurfaceMuted
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(6.dp))
                // Check for updates — sits right after the version so "your build"
                // and "is there a newer one?" read together. Opens the in-app
                // update page (v1.3+); the page itself links out to GitHub.
                Icon(
                    Icons.Default.Sync,
                    contentDescription = "Check for updates",
                    tint = AbOnSurfaceMuted,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            ctx.startActivity(
                                android.content.Intent(
                                    ctx,
                                    com.portalpad.app.ui.settings.UpdateActivity::class.java,
                                ),
                            )
                        },
                )
                // "« New" hint — visible only when the last update check found a
                // release newer than this build (and it wasn't skipped). State is
                // owned by UpdateChecker; refreshed on launch and on page checks.
                if (com.portalpad.app.service.UpdateChecker.badgeVisible.value) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "\u00ab New",
                        color = AbAccent,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        actions = {
            // Always-available Settings (formerly the System tab). Gear-first +
            // label + chevron, matching the Debug/Logs affordance style. The
            // gear tints to the accent color (with a dot) when setup is needed.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Open settings",
                        tint = gearTint,
                        modifier = Modifier.size(22.dp),
                    )
                    if (needsSetup) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFFE6B800)),
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AbBackground),
    )
}

/**
 * Thin edge-to-edge strip under the title bar: the active privilege source on
 * the far left ("Mode: Shizuku" / Root / None) and a tappable
 * "Debug" affordance on the far right that opens the Diagnostics page.
 *
 * When the active source isn't giving full features (None, or a selected
 * source that isn't ready), a yellow warning glyph appears next to the mode and
 * taps through to the System tab so the user can pick Shizuku/Root.
 */
@Composable
private fun ModeDebugStrip(
    onDebugPage: Boolean,
    onOpenDebug: () -> Unit,
    onFixPrivilege: () -> Unit,
) {
    val app = PortalPadApp.instance
    val accessMode by app.prefs.accessMode.collectAsState(initial = AccessMode.NONE)
    val shizukuStatus by app.shizuku.status.collectAsState()
    val shizukuReady = shizukuStatus == ShizukuManager.Status.READY
    val rootReady = app.root.isReady
    val serviceRunning by app.serviceRunning.collectAsState()

    // (Just the value here; the "Mode:" prefix is rendered bold separately.)
    val modeValue = when (accessMode) {
        AccessMode.SHIZUKU -> "Shizuku"
        AccessMode.ROOT -> "Root"
        AccessMode.NONE -> "None"
    }
    val fullFeatures = when (accessMode) {
        AccessMode.SHIZUKU -> shizukuReady
        AccessMode.ROOT -> rootReady
        AccessMode.NONE -> false
    }
    val modeColor = if (fullFeatures) AbOnSurface else Color(0xFFE6B800)
    // Discoverability hint shown only in the (tappable) warning state. None routes to
    // Start > Setup; Shizuku/Root run their in-place fix (popup / root grant).
    val warnSuffix = if (accessMode == AccessMode.NONE) "· Tap to set up" else "· Tap to fix"

    // Long-press the "Mode: Shizuku" text to open the Shizuku start/stop control,
    // matching the trackpad/remote interface. Gated to Shizuku mode.
    var showShizukuDialog by remember { mutableStateOf(false) }
    if (showShizukuDialog) {
        com.portalpad.app.ui.common.ShizukuControlDialog(
            onDismiss = { showShizukuDialog = false },
        )
    }

    val warnScope = rememberCoroutineScope()
    // Warning action by mode (mirrors ModeStatusLabel on the trackpad/remote):
    //   Shizuku → in-app Shizuku control popup.
    //   Root    → request root (`su` off-thread to trigger the grant prompt, then bind).
    //   None    → jump to the System tab privilege picker (onFixPrivilege).
    val onModeWarning: () -> Unit = {
        when (accessMode) {
            AccessMode.SHIZUKU -> showShizukuDialog = true
            AccessMode.ROOT -> {
                warnScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    app.root.refresh()
                    if (app.root.isReady) {
                        app.root.grantInjectEvents()
                        app.rootClickBackend.bind()
                    }
                }
            }
            AccessMode.NONE -> onFixPrivilege()
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(AbSurface)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Far-left service indicator: red/green dot + "Service" (colour conveys
        // running/stopped — no value text), followed by a divider, then Mode.
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (serviceRunning) AbSuccess else AbDanger),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "Service",
            color = if (serviceRunning) AbOnSurface else AbOnSurfaceMuted,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
        )
        androidx.compose.material3.VerticalDivider(
            modifier = Modifier
                .height(16.dp)
                .padding(horizontal = 10.dp),
            thickness = 1.dp,
            color = AbOnSurfaceDim.copy(alpha = 0.5f),
        )
        Text(
            text = androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) {
                    append("Mode: ")
                }
                append(modeValue)
            },
            color = modeColor,
            fontWeight = FontWeight.Normal,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.pointerInput(accessMode) {
                detectTapGestures(
                    onTap = {
                        // Healthy state does nothing on tap (no action → no haptic).
                        // Only the warning state has a tap action, so buzz only then.
                        if (!fullFeatures) {
                            app.injector.buzz(longPress = false)
                            onModeWarning()
                        }
                    },
                    onLongPress = {
                        if (accessMode == AccessMode.SHIZUKU) {
                            app.injector.buzz(longPress = true)
                            showShizukuDialog = true
                        }
                    },
                )
            },
        )
        if (!fullFeatures) {
            Spacer(Modifier.width(6.dp))
            // Yellow tappable warning → mode-appropriate fix (Shizuku popup /
            // request root / privilege picker).
            Icon(
                Icons.Default.Warning,
                contentDescription = "Limited features — tap to fix",
                tint = Color(0xFFE6B800),
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { app.injector.buzz(); onModeWarning() },
            )
            Spacer(Modifier.width(6.dp))
            Text(
                warnSuffix,
                color = Color(0xFFE6B800),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { app.injector.buzz(); onModeWarning() },
            )
        } else {
            // Green check when the active mode (Shizuku or Root) is properly
            // connected — the at-a-glance "you're good to go" signal. Themed
            // (AbSuccess) to match the strip rather than a system emoji.
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "$modeValue connected",
                tint = AbSuccess,
                modifier = Modifier.size(16.dp),
            )
        }
        // (Accessibility companion indicator moved to a standalone button below
        // the Status card on the Start tab.)
        Spacer(Modifier.weight(1f))
        val debugTint = if (onDebugPage) AbPrimary else AbOnSurfaceMuted
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { onOpenDebug() }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                "Debug",
                color = debugTint,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(5.dp))
            Icon(
                Icons.Default.BugReport,
                contentDescription = null,
                tint = debugTint,
                modifier = Modifier.size(16.dp),
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Open diagnostics",
                tint = debugTint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    HorizontalDivider(color = AbSurface, thickness = 1.dp)
}

// ─────────────────────────── Tab 1: Start ───────────────────────────

/**
 * Whether the service can be started without the setup page: overlay granted
 * AND a working privilege source (Shizuku / Root / accessibility) AND the
 * notification-access question resolved (answered "no", or "yes" with the
 * notification listener actually enabled). Read synchronously for the gate at
 * the tap site.
 */
private fun startRequirementsMet(context: android.content.Context): Boolean {
    val app = PortalPadApp.instance
    val overlay = android.provider.Settings.canDrawOverlays(context)
    if (!overlay) return false
    val mode = kotlinx.coroutines.runBlocking { app.prefs.accessMode.first() }
    val privilege = when (mode) {
        AccessMode.SHIZUKU -> app.shizuku.isReady
        AccessMode.ROOT -> app.root.isReady
        AccessMode.NONE -> false
    }
    if (!privilege) return false
    val keys = com.portalpad.app.data.PreferencesRepository.Keys
    // Read every setup answer once. Each requirement is "resolved" when the user
    // said "no", OR said "yes" and the underlying permission/state is currently
    // satisfied. Unanswered ("") is NOT resolved. This MUST mirror canContinue in
    // StartSetupPage so that disabling something you opted into (e.g. turning off
    // the accessibility service after enabling it to pass setup) re-routes you to
    // the setup page on the next start instead of silently starting anyway.
    val answers = kotlinx.coroutines.runBlocking {
        mapOf(
            "notif" to app.prefs.string(keys.SETUP_WANTS_NOTIF_ACCESS).first(),
            "voice" to app.prefs.string(keys.SETUP_WANTS_VOICE_SEARCH).first(),
            "battery" to app.prefs.string(keys.SETUP_WANTS_BACKGROUND).first(),
            "phone" to app.prefs.string(keys.SETUP_WANTS_PHONE_STATE).first(),
            "a11y" to app.prefs.string(keys.SETUP_WANTS_ACCESSIBILITY).first(),
        )
    }
    fun granted(perm: String) =
        context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val notifEnabled = runCatching {
        android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners",
        )?.contains(context.packageName) == true
    }.getOrDefault(false)
    val batteryExempt = runCatching {
        (context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)
    val a11yEnabled = runCatching {
        android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )?.contains(context.packageName) == true
    }.getOrDefault(false)
    fun resolved(answer: String?, met: Boolean) = when (answer) {
        "no" -> true
        "yes" -> met
        else -> false
    }
    return resolved(answers["notif"], notifEnabled) &&
        resolved(answers["voice"], granted(android.Manifest.permission.RECORD_AUDIO)) &&
        resolved(answers["battery"], batteryExempt) &&
        resolved(
            answers["phone"],
            granted(android.Manifest.permission.READ_PHONE_STATE) &&
                granted(android.Manifest.permission.BLUETOOTH_CONNECT),
        ) &&
        resolved(answers["a11y"], a11yEnabled)
}

/**
 * Focused setup checklist gating the service start. Native-scrolling page (via
 * TabBody), reached when the user taps Start Service while requirements aren't
 * met. Lists overlay + privilege status, asks whether the user wants media /
 * notification features on the glasses (which need notification-listener
 * access), and enables Continue only when everything required is satisfied.
 */
@Composable
private fun StartSetupPage(
    onBack: () -> Unit,
    onGoToSystemTab: () -> Unit,
    onContinue: () -> Unit,
) {
    val app = PortalPadApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Re-check Android-settings-backed statuses on resume (user may have just
    // granted overlay / toggled the notification listener and returned).
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val accessMode by app.prefs.accessMode.collectAsState(initial = AccessMode.NONE)
    val shizukuStatus by app.shizuku.status.collectAsState()
    val notifAnswer by app.prefs.string(
        com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_NOTIF_ACCESS,
    ).collectAsState(initial = "")
    val voiceAnswer by app.prefs.string(
        com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_VOICE_SEARCH,
    ).collectAsState(initial = "")
    val batteryAnswer by app.prefs.string(
        com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_BACKGROUND,
    ).collectAsState(initial = "")
    val phoneStateAnswer by app.prefs.string(
        com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_PHONE_STATE,
    ).collectAsState(initial = "")
    val accessibilityAnswer by app.prefs.string(
        com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_ACCESSIBILITY,
    ).collectAsState(initial = "")

    val overlayGranted = remember(refreshTick) {
        android.provider.Settings.canDrawOverlays(context)
    }
    val notifEnabled = remember(refreshTick) {
        runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners",
            )?.contains(context.packageName) == true
        }.getOrDefault(false)
    }
    val micGranted = remember(refreshTick) {
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val batteryExempt = remember(refreshTick) {
        runCatching {
            context.getSystemService(android.os.PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)
    }
    val phoneStateGranted = remember(refreshTick) {
        context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val accessibilityEnabledNow = remember(refreshTick) {
        runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )?.contains(context.packageName) == true
        }.getOrDefault(false)
    }
    val privilegeReady = when (accessMode) {
        AccessMode.SHIZUKU -> shizukuStatus == ShizukuManager.Status.READY
        AccessMode.ROOT -> app.root.isReady
        AccessMode.NONE -> false // must pick Shizuku/Root; accessibility is optional, doesn't gate
    }
    // One-tap privileged grant: runs each shell command off the main thread, then
    // polls `readBack` up to 6×400ms (bumping refreshTick each tick so the row's
    // remember(refreshTick) state re-reads and the UI flips to met), and falls back
    // to the system screen if the OS never honors it. Mirrors the proven
    // accessibility "Tap to enable" path. Only call when privilegeReady.
    val grantViaShell: (List<String>, () -> Boolean, () -> Unit) -> Unit =
        { commands, readBack, fallback ->
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    commands.forEach { c -> runCatching { app.access.execShell(c) } }
                }
                var ok = false
                for (i in 0 until 6) {
                    kotlinx.coroutines.delay(400)
                    refreshTick++
                    ok = runCatching { readBack() }.getOrDefault(false)
                    if (ok) break
                }
                if (!ok) fallback()
            }
        }
    // "Allow screen overlays on Settings" (Settings.Secure secure_overlay_settings).
    // Readable only via the elevated shell, so resolved async: null = checking,
    // true/false = state. Re-read whenever privilege or the refresh tick changes.
    var secureOverlayOk by remember(refreshTick) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(refreshTick, privilegeReady) {
        secureOverlayOk = if (privilegeReady) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    app.access.execShell("settings get secure secure_overlay_settings")
                        .trim().startsWith("1")
                }.getOrDefault(false)
            }
        } else {
            false
        }
    }
    val notifResolved = when (notifAnswer) {
        "no" -> true
        "yes" -> notifEnabled
        else -> false
    }
    val voiceResolved = when (voiceAnswer) {
        "no" -> true
        "yes" -> micGranted
        else -> false
    }
    val batteryResolved = when (batteryAnswer) {
        "no" -> true
        "yes" -> batteryExempt
        else -> false
    }
    val phoneStateResolved = when (phoneStateAnswer) {
        "no" -> true
        "yes" -> phoneStateGranted
        else -> false
    }
    val accessibilityResolved = when (accessibilityAnswer) {
        "no" -> true
        "yes" -> accessibilityEnabledNow
        else -> false
    }
    // External mouse — a deliberate Yes/No the user must answer in setup. It grabs
    // their physical mouse exclusively, so it shouldn't switch on silently. Unset
    // ("") until they choose, so it blocks completion like the permission items; the
    // answer drives EXT_MOUSE_ENABLED (the same pref the Controls toggle uses).
    val mouseAnswer by app.prefs
        .string(com.portalpad.app.data.PreferencesRepository.Keys.SETUP_MOUSE_ANSWER, "")
        .collectAsState(initial = "")
    val mouseResolved = mouseAnswer == "yes" || mouseAnswer == "no"
    val canContinue = overlayGranted && privilegeReady && secureOverlayOk == true &&
        notifResolved && voiceResolved && batteryResolved && phoneStateResolved &&
        accessibilityResolved

    // Settings jumps that LEAVE PortalPad (overlay, notification, Shizuku) get a
    // long toast reminding the user to return and finish setup. In-place dialogs
    // (battery exemption, the mic runtime prompt) don't call this.
    fun showReturnToast() = runCatching {
        android.widget.Toast.makeText(
            context,
            "Come back to PortalPad's setup to finish the remaining permissions.",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    fun openIntent(action: String) = runCatching {
        showReturnToast()
        // For the overlay-permission action, attach the package URI so most
        // devices open straight to PortalPad's own toggle (falls back to the
        // bare action below if the OEM rejects the URI).
        if (action == android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION) {
            context.startActivity(
                android.content.Intent(action, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else if (action == android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS &&
            android.os.Build.VERSION.SDK_INT >= 30) {
            // Android 11+: jump straight to PortalPad's listener toggle.
            val cn = android.content.ComponentName(
                context, com.portalpad.app.service.PortalPadNotificationListenerService::class.java,
            )
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else if (action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
            // App-details needs the package URI to land on PortalPad's own page
            // (where the Permissions → Microphone toggle lives). Without the URI
            // most devices open the generic app list instead.
            context.startActivity(
                android.content.Intent(action, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            context.startActivity(
                android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }.recoverCatching {
        context.startActivity(
            android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    // Microphone runtime-permission request. Tapping "Enable microphone" shows
    // the real system dialog (one tap). Android only shows that dialog while the
    // permission hasn't been permanently denied; once it has, the launcher
    // returns immediately with no UI, so we fall back to PortalPad's app-details
    // page (where the user can flip the toggle manually).
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshTick++ // re-evaluate micGranted on return
        if (!granted) {
            val activity = context as? android.app.Activity
            val canAskAgain = activity?.shouldShowRequestPermissionRationale(
                android.Manifest.permission.RECORD_AUDIO,
            ) ?: false
            // Denied AND the system won't show the dialog again → open settings.
            if (!canAskAgain) {
                openIntent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            }
        }
    }
    fun requestMic() {
        if (micGranted) return
        runCatching { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
            .onFailure { openIntent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
    }

    Column(Modifier.fillMaxSize()) {
        // Thin accent line at the very top — green to rhyme with the Continue /
        // Start Service button below, signalling this page is the lit path to
        // starting the service.
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Color(0xFF49C77E)),
        )
        // Back header (matches other subpages).
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onBack() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Back",
                tint = AbOnSurface,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Setup", color = AbOnSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Required before PortalPad can start",
                    color = AbPrimaryBright,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Setup progress — fills as each of the gating items is satisfied. An item
        // counts as done when granted, OR (for optional items) opted out with "No".
        // Selecting "Yes" without enabling does NOT advance it: the underlying
        // *Resolved flags stay false until the permission is actually granted.
        run {
            val doneCount = listOf(
                overlayGranted,
                privilegeReady,
                secureOverlayOk == true,
                accessibilityResolved,
                notifResolved,
                voiceResolved,
                batteryResolved,
                phoneStateResolved,
                mouseResolved,
            ).count { it }
            val total = 9
            val target = doneCount / total.toFloat()
            val animated by androidx.compose.animation.core.animateFloatAsState(
                targetValue = target,
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 450),
                label = "setupProgress",
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AbSuccess.copy(alpha = 0.14f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(animated.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(AbSuccess),
                    )
                }
                Spacer(Modifier.height(5.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "$doneCount of $total complete",
                        color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${(target * 100).toInt()}%",
                        color = AbSuccess, style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        val bodyScroll = rememberScrollState()
        val thumbColor = AbOnSurfaceMuted.copy(alpha = 0.5f)
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(bodyScroll)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                "A couple of things need to be ready before PortalPad can drive your external display.",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Continue turns green once the required items are granted and every optional question below has a Yes or No — granting the optional ones is up to you.",
                color = AbOnSurfaceDim, style = MaterialTheme.typography.bodySmall,
            )

            SectionCard(title = "Required") {
                // Privilege source — chosen right here via the same dropdown used
                // elsewhere, so the user never leaves this page. Below it, the
                // status + fix action for the chosen mode's ACTUAL current state.
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "Privilege source",
                        color = AbOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Required to create the external display and send taps, clicks, and keys to it. Choose Shizuku or Root.",
                        color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                AccessModeSelector(
                    accessMode = accessMode,
                    shizukuReady = shizukuStatus == ShizukuManager.Status.READY,
                    rootReady = app.root.isReady,
                    onSelect = { mode ->
                        scope.launch { app.prefs.setAccessMode(mode) }
                        if (mode == AccessMode.SHIZUKU) {
                            app.shizuku.refresh()
                            if (app.shizuku.isReady) app.shizukuClickBackend.bind()
                        }
                    },
                )
                // One-tap hint: with a source selected, the privileged permissions
                // below grant via shell in a single tap (matches the amber Tip style
                // used elsewhere — bold prefix, no box).
                Spacer(Modifier.height(4.dp))
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                        append("Tip: ")
                        pop()
                        append("With a privilege source selected, most permissions below can be granted in one tap — no need to dig through Android settings.")
                    },
                    color = AbWarning, style = MaterialTheme.typography.bodySmall,
                )
                // Status + fix for the chosen mode, reflecting its real state.
                if (accessMode != AccessMode.NONE) {
                    Spacer(Modifier.height(3.dp))
                    // Title / detail / action / handler computed from the live state.
                    data class PrivState(val title: String, val detail: String, val action: String, val onAct: () -> Unit)
                    val openShizuku: () -> Unit = {
                        runCatching {
                            context.packageManager
                                .getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let {
                                    showReturnToast()
                                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(it)
                                }
                        }
                    }
                    val ps = when (accessMode) {
                        AccessMode.SHIZUKU -> when (shizukuStatus) {
                            ShizukuManager.Status.READY -> PrivState(
                                "Shizuku connected", "Authorized and ready to drive the external display.", "", {})
                            ShizukuManager.Status.NEEDS_PERMISSION -> PrivState(
                                "Shizuku needs authorization",
                                "Shizuku is running but hasn't been authorized for PortalPad yet.",
                                "Authorize Shizuku", { app.shizuku.requestPermission() })
                            ShizukuManager.Status.NOT_RUNNING -> PrivState(
                                "Shizuku not running",
                                "Shizuku is installed but its service isn't started. Open Shizuku and start it (via wireless debugging or ADB).",
                                "Open Shizuku", openShizuku)
                            ShizukuManager.Status.NOT_INSTALLED -> PrivState(
                                "Shizuku not installed",
                                "Shizuku grants the privileges PortalPad needs. The GitHub build is recommended (start-on-boot + watchdog). Install it, then start its service.",
                                "", {})
                            else -> PrivState(
                                "Shizuku status unknown",
                                "Couldn't read Shizuku's state. Open Shizuku, then return.",
                                "Open Shizuku", openShizuku)
                        }
                        AccessMode.ROOT -> if (app.root.isReady) PrivState(
                            "Root active", "Superuser granted and ready to drive the external display.", "", {})
                        else PrivState(
                            "Root not granted",
                            "PortalPad couldn't get superuser access. Grant it in your root manager (e.g. Magisk → Superuser), then re-check.",
                            "Re-check root", { app.root.refresh() })
                        else -> PrivState("", "", "", {})
                    }
                    SetupRequirementRow(
                        met = privilegeReady,
                        title = ps.title,
                        detail = ps.detail,
                        actionLabel = ps.action,
                        onAction = ps.onAct,
                        extra = if (accessMode == AccessMode.SHIZUKU &&
                            shizukuStatus == ShizukuManager.Status.NOT_INSTALLED) {
                            {
                                com.portalpad.app.ui.common.OpenInRow(
                                    destinations = listOf(
                                        com.portalpad.app.ui.common.gitHubDest { openUrl(context, "https://github.com/thedjchi/Shizuku") },
                                    ),
                                )
                            }
                        } else null,
                    )
                }
                Spacer(Modifier.height(6.dp))
                SetupRequirementRow(
                    met = overlayGranted,
                    title = "Display overlay",
                    detail = "Lets PortalPad draw the cursor, dock, and controls on top of apps on your external display.",
                    actionLabel = "Grant overlay permission",
                    onAction = {
                        if (privilegeReady) {
                            grantViaShell(
                                listOf("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"),
                                { android.provider.Settings.canDrawOverlays(context) },
                                { openIntent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION) },
                            )
                        } else {
                            openIntent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                val secOn = secureOverlayOk == true
                SetupRequirementRow(
                    met = secOn,
                    title = "Allow screen overlays on Settings",
                    note = "This is a different permission from the Display overlay above. " +
                        "That one lets PortalPad's cursor, dock, and controls appear over " +
                        "normal apps; this one re-allows them over Android's own Settings app, " +
                        "which Android blocks by default for security.",
                    detail = "Without it, opening Settings on the external display would leave " +
                        "it with no dock or cursor to drive it. Granting it keeps the dock, " +
                        "cursor, and top bar usable over Settings screens.",
                    actionLabel = if (secOn) "Tap to disable" else "Tap to enable",
                    actionEnabled = privilegeReady,
                    actionWhenMet = true,
                    onAction = {
                        if (privilegeReady) {
                            val target = if (secOn) "0" else "1"
                            scope.launch {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching {
                                        app.access.execShell("settings put secure secure_overlay_settings $target")
                                    }
                                }
                                refreshTick++
                            }
                        }
                    },
                    extra = if (!privilegeReady) {
                        {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    "Requires Root or Shizuku to quickly tap " +
                                        (if (secOn) "disable" else "enable") +
                                        ". Otherwise, manually " +
                                        (if (secOn) "disable" else "enable") +
                                        " the setting from Android developer settings.",
                                    color = AbOnSurfaceMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Open developer settings",
                                    color = AbPrimaryBright,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            openIntent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                        }
                                        .padding(vertical = 2.dp),
                                )
                            }
                        }
                    } else null,
                )
            }

            SectionCard(title = "Media & notifications") {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SetupAnswerIcon(notifAnswer, notifAnswer == "yes" && notifEnabled)
                    Text(
                        "Will you use media controls or the notification panel on your external display?",
                        color = AbOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "The remote's media slider (play/pause, track info) and viewing notifications on the external display need notification access.",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SetupChoiceChip("Yes", selected = notifAnswer == "yes") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_NOTIF_ACCESS, "yes",
                            )
                        }
                    }
                    SetupChoiceChip("No", selected = notifAnswer == "no") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_NOTIF_ACCESS, "no",
                            )
                        }
                    }
                }
                if (notifAnswer == "yes") {
                    Spacer(Modifier.height(5.dp))
                    SetupRequirementRow(
                        met = notifEnabled,
                        title = "Notification access",
                        detail = "Powers media controls and the notification panel on the external display.",
                        actionLabel = "Enable notification access",
                        onAction = {
                            if (privilegeReady) {
                                val comp = "${context.packageName}/com.portalpad.app.service.PortalPadNotificationListenerService"
                                grantViaShell(
                                    // allow_listener is additive — it won't clobber other
                                    // enabled listeners the way a raw settings-put would.
                                    listOf("cmd notification allow_listener $comp"),
                                    {
                                        runCatching {
                                            android.provider.Settings.Secure.getString(
                                                context.contentResolver, "enabled_notification_listeners",
                                            )?.contains(context.packageName) == true
                                        }.getOrDefault(false)
                                    },
                                    { openIntent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
                                )
                            } else {
                                openIntent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            }
                        },
                    )
                }
            }

            SectionCard(title = "Voice search") {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SetupAnswerIcon(voiceAnswer, voiceAnswer == "yes" && micGranted)
                    Text(
                        "Will you use voice search?",
                        color = AbOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "The app drawer can search by voice. Enabling mic access now means it just works later — no interruption when you first reach for it. You can also turn this on anytime.",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SetupChoiceChip("Yes", selected = voiceAnswer == "yes") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_VOICE_SEARCH, "yes",
                            )
                        }
                    }
                    SetupChoiceChip("Not now", selected = voiceAnswer == "no") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_VOICE_SEARCH, "no",
                            )
                        }
                    }
                }
                if (voiceAnswer == "yes") {
                    Spacer(Modifier.height(5.dp))
                    SetupRequirementRow(
                        met = micGranted,
                        title = "Microphone access",
                        detail = "Lets you search the app drawer by voice. Tap to allow microphone access.",
                        actionLabel = "Enable microphone",
                        onAction = {
                            if (privilegeReady) {
                                grantViaShell(
                                    listOf("pm grant ${context.packageName} android.permission.RECORD_AUDIO"),
                                    {
                                        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                                            android.content.pm.PackageManager.PERMISSION_GRANTED
                                    },
                                    { requestMic() },
                                )
                            } else {
                                requestMic()
                            }
                        },
                    )
                }
            }

            SectionCard(title = "Connectivity details") {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SetupAnswerIcon(phoneStateAnswer, phoneStateAnswer == "yes" && phoneStateGranted)
                    Text(
                        "Show mobile network type and Bluetooth device count in the status menu?",
                        color = AbOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Optional. Unlocks two extras: the mobile network-type label (5G/LTE/4G) on " +
                        "the cellular icon, and the count of connected Bluetooth devices in the menu. " +
                        "Signal bars work without this. Granted through your access source " +
                        "(Shizuku/Root) — no system prompt, and nothing leaves your device.",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SetupChoiceChip("Yes", selected = phoneStateAnswer == "yes") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_PHONE_STATE, "yes",
                            )
                        }
                    }
                    SetupChoiceChip("No", selected = phoneStateAnswer == "no") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_PHONE_STATE, "no",
                            )
                        }
                    }
                }
                if (phoneStateAnswer == "yes") {
                    Spacer(Modifier.height(5.dp))
                    SetupRequirementRow(
                        met = phoneStateGranted,
                        title = "Connectivity details access",
                        detail = "Grants phone-state (network type) and Bluetooth (device count) access. " +
                            "Requires your access source (Shizuku/Root) to be active.",
                        actionLabel = "Grant access",
                        onAction = {
                            if (!privilegeReady) {
                                runCatching {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Set up your access source (Shizuku or Root) first.",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                            } else {
                                scope.launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching {
                                            app.access.execShell(
                                                "pm grant ${context.packageName} android.permission.READ_PHONE_STATE",
                                            )
                                        }
                                        runCatching {
                                            app.access.execShell(
                                                "pm grant ${context.packageName} android.permission.BLUETOOTH_CONNECT",
                                            )
                                        }
                                    }
                                    refreshTick++
                                }
                            }
                        },
                    )
                }
            }

            SectionCard(title = "Auto-open typing page") {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SetupAnswerIcon(accessibilityAnswer, accessibilityAnswer == "yes" && accessibilityEnabledNow)
                    Text(
                        "Open the typing page automatically when you tap a text field on the glasses?",
                        color = AbOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Required for auto-open. The PortalPad accessibility service " +
                        "detects when you tap or open a text field on the glasses, so the " +
                        "\"Type to external display\" page opens by itself — showing the " +
                        "field's current text to edit, no keyboard button needed. Choose " +
                        "No to skip auto-open; the typing page is still available manually " +
                        "via the keyboard button.",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SetupChoiceChip("Yes", selected = accessibilityAnswer == "yes") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_ACCESSIBILITY, "yes",
                            )
                        }
                    }
                    SetupChoiceChip("No", selected = accessibilityAnswer == "no") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_ACCESSIBILITY, "no",
                            )
                        }
                    }
                }
                if (accessibilityAnswer == "yes") {
                    Spacer(Modifier.height(5.dp))
                    SetupRequirementRow(
                        met = accessibilityEnabledNow,
                        title = "Enable PortalPad accessibility",
                        detail = if (accessibilityEnabledNow) "Status: Enabled" else "Status: Not enabled",
                        actionLabel = if (privilegeReady) "Tap to enable" else "Open Accessibility",
                        onAction = {
                            if (privilegeReady) {
                                // Root/Shizuku present: enable the service directly via a
                                // read-modify-write of the secure list (append our component,
                                // never overwrite other services) + accessibility_enabled.
                                scope.launch {
                                    val comp =
                                        "com.portalpad.app/com.portalpad.app.service.PortalPadAccessibilityService"
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        val cur = runCatching {
                                            app.access.execShell(
                                                "settings get secure enabled_accessibility_services",
                                            ).trim()
                                        }.getOrDefault("")
                                        val list = when {
                                            cur.isEmpty() || cur == "null" -> comp
                                            cur.split(":").contains(comp) -> cur
                                            else -> "$cur:$comp"
                                        }
                                        runCatching {
                                            app.access.execShell(
                                                "settings put secure enabled_accessibility_services $list",
                                            )
                                        }
                                        runCatching {
                                            app.access.execShell("settings put secure accessibility_enabled 1")
                                        }
                                    }
                                    // Poll for the OS to honor it (Samsung's restricted-
                                    // settings gate can silently reject a shell write);
                                    // refresh the row each tick, and fall back to the
                                    // Accessibility settings screen if it never takes.
                                    var enabled = false
                                    for (i in 0 until 6) {
                                        kotlinx.coroutines.delay(400)
                                        refreshTick++
                                        enabled = runCatching {
                                            android.provider.Settings.Secure.getString(
                                                context.contentResolver,
                                                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                            )?.contains(context.packageName) == true
                                        }.getOrDefault(false)
                                        if (enabled) break
                                    }
                                    if (!enabled) {
                                        openIntent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    }
                                }
                            } else {
                                openIntent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            }
                        },
                        extra = if (!accessibilityEnabledNow && !privilegeReady) {
                            {
                                Text(
                                    text = androidx.compose.ui.text.buildAnnotatedString {
                                        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("Note: ") }
                                        append(
                                            "On some devices the switch is greyed out. Tap it once — " +
                                                "Android then surfaces a 3-dot menu on PortalPad's App info page. " +
                                                "Open that menu, choose \"Allow restricted settings,\" then return " +
                                                "and enable PortalPad.",
                                        )
                                    },
                                    color = AbDanger.copy(alpha = 0.9f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "App info",
                                    color = AbPrimaryBright,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .clickable { openIntent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS) },
                                )
                            }
                        } else null,
                    )
                }
            }

            SectionCard(title = "External mouse") {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SetupAnswerIcon(mouseAnswer, mouseAnswer == "yes")
                    Text(
                        "Do you use a physical mouse with your external display?",
                        color = AbOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Lets a Bluetooth or USB mouse drive the cursor on your external display, " +
                        "even with the phone screen blanked. You can change this any time under " +
                        "Controls. Pick No if you don't use one.",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SetupChoiceChip("Yes", selected = mouseAnswer == "yes") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_MOUSE_ANSWER, "yes",
                            )
                            app.prefs.setBool(
                                com.portalpad.app.data.PreferencesRepository.Keys.EXT_MOUSE_ENABLED, true,
                            )
                        }
                    }
                    SetupChoiceChip("No", selected = mouseAnswer == "no") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_MOUSE_ANSWER, "no",
                            )
                            app.prefs.setBool(
                                com.portalpad.app.data.PreferencesRepository.Keys.EXT_MOUSE_ENABLED, false,
                            )
                        }
                    }
                }
            }

            SectionCard(title = "Background") {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SetupAnswerIcon(batteryAnswer, batteryAnswer == "yes" && batteryExempt)
                    Text(
                        "Do you want PortalPad to keep running in the background?",
                        color = AbOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Recommended. Keeps PortalPad alive in the background so it can detect your " +
                        "glasses connecting and launch automatically, and keeps the remote and " +
                        "input working while your screen is off. Without it, Android may sleep " +
                        "or kill PortalPad during a session.",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SetupChoiceChip("Yes", selected = batteryAnswer == "yes") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_BACKGROUND, "yes",
                            )
                        }
                    }
                    SetupChoiceChip("No", selected = batteryAnswer == "no") {
                        scope.launch {
                            app.prefs.setString(
                                com.portalpad.app.data.PreferencesRepository.Keys.SETUP_WANTS_BACKGROUND, "no",
                            )
                        }
                    }
                }
                if (batteryAnswer == "yes") {
                    Spacer(Modifier.height(5.dp))
                    SetupRequirementRow(
                        met = batteryExempt,
                        title = "Ignore battery optimization",
                        detail = "Lets PortalPad keep running so it can detect your glasses and " +
                            "keep the remote and input working while the screen is off.",
                        actionLabel = "Allow background running",
                        onAction = {
                            val openBatteryScreen = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            android.net.Uri.parse("package:${context.packageName}"),
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                                Unit
                            }
                            if (privilegeReady) {
                                grantViaShell(
                                    listOf(
                                        "cmd deviceidle whitelist +${context.packageName}",
                                        "dumpsys deviceidle whitelist +${context.packageName}",
                                    ),
                                    {
                                        context.getSystemService(android.os.PowerManager::class.java)
                                            ?.isIgnoringBatteryOptimizations(context.packageName) == true
                                    },
                                    openBatteryScreen,
                                )
                            } else {
                                openBatteryScreen()
                            }
                        },
                    )
                }
            }

            // Accessibility section hidden — service de-registered; PortalPad uses
            // Shizuku/Root for everything, including dock-menu long-press (handled
            // in-process now). Kept commented for easy restoration.
            // SectionCard(title = "Accessibility") { … "Accessibility companion" … }
        }
        // Visible scrollbar — only paints a thumb when content overflows
        // (maxValue > 0), so short screens that fit show nothing. Same look as
        // the other settings subpages (TabBody).
        Box(
            Modifier
                .matchParentSize()
                .drawWithContent {
                    drawContent()
                    val max = bodyScroll.maxValue
                    if (max > 0) {
                        val trackTop = 8.dp.toPx()
                        val trackBottom = size.height - 8.dp.toPx()
                        val trackH = (trackBottom - trackTop).coerceAtLeast(1f)
                        val viewport = size.height
                        val total = viewport + max
                        val thumbH = (trackH * (viewport / total)).coerceIn(28.dp.toPx(), trackH)
                        val frac = bodyScroll.value.toFloat() / max.toFloat()
                        val thumbY = trackTop + (trackH - thumbH) * frac
                        val w = 4.dp.toPx()
                        val x = size.width - w - 3.dp.toPx()
                        drawRoundRect(
                            color = thumbColor,
                            topLeft = androidx.compose.ui.geometry.Offset(x, thumbY),
                            size = androidx.compose.ui.geometry.Size(w, thumbH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f),
                        )
                    }
                },
        )
        }

        // Continue — gated. Greyed/disabled until all requirements are met.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .then(
                        if (canContinue)
                            Modifier.background(Color(0xFF2E9E5B))
                                .border(1.5.dp, Color(0xFF49C77E), RoundedCornerShape(16.dp))
                        else
                            Modifier.background(AbSurface)
                                .border(1.5.dp, AbOnSurfaceDim.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    )
                    .then(if (canContinue) Modifier.clickable { onContinue() } else Modifier)
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            ) {
                Text(
                    "Continue",
                    color = if (canContinue) AbOnSurface else AbOnSurfaceMuted,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** Side Mode corner glyph: a screen outline with a shrunk filled quadrant in the
 *  matching corner — i.e. the mini-display parked where Side Mode would dock it. */
@Composable
private fun SideCornerGlyph(corner: com.portalpad.app.data.SideCorner, selected: Boolean) {
    val c = if (selected) AbAccent else AbOnSurfaceMuted
    androidx.compose.foundation.Canvas(Modifier.size(width = 34.dp, height = 23.dp)) {
        val w = size.width; val h = size.height
        val sw = h * 0.09f
        drawRoundRect(
            color = c.copy(alpha = if (selected) 0.55f else 0.45f),
            topLeft = androidx.compose.ui.geometry.Offset(sw / 2f, sw / 2f),
            size = androidx.compose.ui.geometry.Size(w - sw, h - sw),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.22f, h * 0.22f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw),
        )
        val bw = w * 0.40f; val bh = h * 0.42f
        val pad = w * 0.12f
        val left = when (corner) {
            com.portalpad.app.data.SideCorner.TL, com.portalpad.app.data.SideCorner.BL -> pad
            else -> w - pad - bw
        }
        val top = when (corner) {
            com.portalpad.app.data.SideCorner.TL, com.portalpad.app.data.SideCorner.TR -> pad * 0.7f
            else -> h - pad * 0.7f - bh
        }
        drawRoundRect(
            color = c,
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(bw, bh),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.12f, h * 0.12f),
        )
    }
}

/** One checklist row: status icon + title + explanation + (when unmet) action. */
@Composable
private fun SetupRequirementRow(
    met: Boolean,
    title: String,
    detail: String,
    note: String? = null,
    actionLabel: String,
    onAction: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
    actionEnabled: Boolean = true,
    actionWhenMet: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!met) Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE6B800).copy(alpha = 0.12f))
                    .border(1.dp, Color(0xFFE6B800).copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(9.dp)
                else Modifier
            ),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (met) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = if (met) "$title ready" else "$title needed",
            tint = if (met) AbSuccess else Color(0xFFE6B800),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = AbOnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            if (note != null) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("Note: ") }
                        append(note)
                    },
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(detail, color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            if ((!met || actionWhenMet) && actionLabel.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    actionLabel,
                    color = if (actionEnabled) AbPrimaryBright else AbOnSurfaceMuted.copy(alpha = 0.5f),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (actionEnabled) Modifier.clickable { onAction() } else Modifier)
                        .padding(vertical = 2.dp),
                )
            }
            if (extra != null) {
                Spacer(Modifier.height(2.dp))
                extra()
            }
        }
    }
}

/**
 * Leading status marker for an optional setup question.
 *  - Unanswered ("") → a soft amber ring: a pending choice that still blocks
 *    Continue, but calmer than the required rows' warning triangle.
 *  - Answered + feature actually on (active) → green check.
 *  - Answered otherwise (declined, or "yes" but not yet granted) → muted check;
 *    when "yes"-but-ungranted, the amber lives on the grant sub-row instead.
 */
@Composable
private fun SetupAnswerIcon(answer: String, active: Boolean) {
    if (answer.isEmpty()) {
        Box(
            Modifier
                .size(18.dp)
                .border(2.dp, Color(0xFFE6B800), androidx.compose.foundation.shape.CircleShape),
        )
    } else {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = if (active) "enabled" else "answered",
            tint = if (active) AbSuccess else AbOnSurfaceMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Yes/No pill for the setup question. */
@Composable
private fun SetupChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) AbPrimaryBright.copy(alpha = 0.25f) else AbSurfaceElevated)
            .border(
                1.dp,
                if (selected) AbPrimaryBright else AbOnSurfaceDim.copy(alpha = 0.4f),
                RoundedCornerShape(20.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) AbPrimaryBright else AbOnSurface,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StartTab(onEnableClick: () -> Unit, onGoToSystemTab: () -> Unit, onGoToControls: () -> Unit, onGoToDisplay: () -> Unit = {}, onLaunchTrackpad: (() -> Unit)? = null, onOpenButtonActions: () -> Unit = {}, onOpenAutoLaunch: () -> Unit = {}, onPickHome: () -> Unit = {}, onPickBack: () -> Unit = {}, onOpenStartSetup: () -> Unit = {}) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val shizukuStatus by app.shizuku.status.collectAsState()
    val accessMode by prefs.accessMode.collectAsState(initial = AccessMode.NONE)
    val foreground by app.appInForeground.collectAsState()
    val serviceRunning by app.serviceRunning.collectAsState()
    val externalDisplayId by app.externalDisplayId.collectAsState()
    val physicalExternalDisplayId by app.physicalExternalDisplayId.collectAsState()
    val context = LocalContext.current

    // Live external-display detection (independent of the service) so the Display
    // button can show the connected display's name the moment glasses are plugged
    // in, even before the service starts. Null name when none connected.
    var detectedDisplayName by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
            as android.hardware.display.DisplayManager
        fun looksExternal(d: android.view.Display): Boolean {
            if (d.displayId == android.view.Display.DEFAULT_DISPLAY) return false
            if (d.name == "PortalPad Session") return false
            val type = runCatching {
                android.view.Display::class.java.getMethod("getType").invoke(d) as? Int
            }.getOrNull()
            if (type != null) return type == 2 || type == 4 // EXTERNAL or OVERLAY
            val n = d.name.lowercase()
            return !(n.contains("ghost") || n.contains("virtual") || n.contains("projection") ||
                n.contains("componentinfo") || n.contains("scrcpy"))
        }
        fun rescan() {
            val ext = runCatching { dm.displays.firstOrNull { looksExternal(it) } }.getOrNull()
            detectedDisplayName = ext?.name?.takeIf { it.isNotBlank() }
        }
        rescan()
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = rescan()
            override fun onDisplayRemoved(displayId: Int) = rescan()
            override fun onDisplayChanged(displayId: Int) = rescan()
        }
        dm.registerDisplayListener(listener, null)
        onDispose { dm.unregisterDisplayListener(listener) }
    }

    val homeAction by prefs.homeAction.collectAsState(initial = null)
    val backAction by prefs.backAction.collectAsState(initial = com.portalpad.app.data.BackAction.System)
    val autoLaunch by prefs.autoLaunchOnStart.collectAsState(initial = com.portalpad.app.data.AutoLaunch.Wallpaper)

    // Last-used interface, for the Launch button label. Mode-switches persist
    // these; a display connect / fresh start resets them (applyAutoLaunchInterface),
    // so the label defaults back to "Launch trackpad" on reconnect.
    val lastWasRemote by prefs.bool(
        com.portalpad.app.data.PreferencesRepository.Keys.LAST_MODE_WAS_REMOTE,
        default = false,
    ).collectAsState(initial = false)
    val storedInputMode by prefs.inputMode.collectAsState(
        initial = com.portalpad.app.data.InputMode.TRACKPAD,
    )
    val launchLabel = when {
        lastWasRemote -> "Launch remote"
        storedInputMode == com.portalpad.app.data.InputMode.AIR_MOUSE -> "Launch air mouse"
        else -> "Launch trackpad"
    }

    // Saved Shizuku auth key enables one-tap Start from the launch prompt below.
    val savedShizukuKey by prefs.shizukuAuthKey.collectAsState(initial = "")

    // Shown when the user taps Launch Trackpad while relying on Shizuku and its
    // service is down — launching would land them in a frozen trackpad. If an auth
    // key is saved we offer one-tap Start (broadcast); otherwise we redirect them
    // to open Shizuku. Never relevant to root users.
    var showShizukuLaunchPrompt by remember { mutableStateOf(false) }
    var startingShizuku by remember { mutableStateOf(false) }
    // When the user has no saved auth key, the launch prompt routes here instead of
    // duplicating the key-entry UI — setup lives in one place (the shared dialog).
    var showShizukuSetupDialog by remember { mutableStateOf(false) }
    if (showShizukuSetupDialog) {
        com.portalpad.app.ui.common.ShizukuControlDialog(
            onDismiss = { showShizukuSetupDialog = false },
        )
    }

    // Single launch path reused by the button and the prompt's auto-launch.
    val launchTrackpadNow: () -> Unit = {
        val cb = onLaunchTrackpad
        if (cb != null) {
            cb()
        } else {
            context.startActivity(
                android.content.Intent(context, com.portalpad.app.TrackpadActivity::class.java)
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    )
                    .putExtra(com.portalpad.app.TrackpadActivity.EXTRA_KEEP_CURRENT, true),
            )
        }
    }

    // On "Start Shizuku": send the START broadcast, then poll live status up to ~3s.
    // If it comes up, dismiss and launch automatically; otherwise leave the prompt
    // open for the Open-Shizuku fallback. Depends on the broadcast actually reviving
    // a stopped Shizuku.
    LaunchedEffect(startingShizuku) {
        if (!startingShizuku) return@LaunchedEffect
        com.portalpad.app.ui.common.sendShizukuControl(context, start = true, auth = savedShizukuKey)
        var waited = 0
        // Wait for the backend BINDER (what actually drives the trusted VD wrap),
        // not just app-permission status — the binder connects ~100-200ms later and
        // is the signal the service uses to recreate the display + re-lay wallpaper.
        while (waited < 3000 && app.activeBoundBackend?.isReady != true) {
            kotlinx.coroutines.delay(300)
            waited += 300
        }
        val ready = app.activeBoundBackend?.isReady == true
        startingShizuku = false
        if (ready) {
            showShizukuLaunchPrompt = false
            launchTrackpadNow()
        }
    }

    if (showShizukuLaunchPrompt) {
        val openShizuku: () -> Unit = {
            val pm = context.packageManager
            val launch = pm.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (launch != null) {
                runCatching {
                    context.startActivity(
                        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!startingShizuku) showShizukuLaunchPrompt = false },
            title = { Text(if (startingShizuku) "Starting Shizuku…" else "Shizuku is not running") },
            text = {
                Text(
                    when {
                        startingShizuku ->
                            "Asked Shizuku to start. Waiting for it to come up, then " +
                                "launching the trackpad…"
                        savedShizukuKey.isNotBlank() ->
                            "PortalPad needs Shizuku to control the trackpad. Tap Start " +
                                "Shizuku to bring it up, or open Shizuku to start it manually."
                        else ->
                            "PortalPad needs Shizuku to control the trackpad. Tap Set up " +
                                "Shizuku to save an auth key (for one-tap Start) and start " +
                                "the service, then return and launch."
                    },
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!startingShizuku) {
                        if (savedShizukuKey.isNotBlank()) {
                            androidx.compose.material3.TextButton(onClick = { startingShizuku = true }) {
                                Text("Start Shizuku")
                            }
                            androidx.compose.material3.TextButton(onClick = {
                                showShizukuLaunchPrompt = false
                                openShizuku()
                            }) { Text("Open Shizuku") }
                        } else {
                            // No saved key → one-tap Start can't work yet. Send them to
                            // the setup dialog (auth key + Start/Stop + Open Shizuku),
                            // rather than duplicating the key field here.
                            androidx.compose.material3.TextButton(onClick = {
                                showShizukuLaunchPrompt = false
                                showShizukuSetupDialog = true
                            }) { Text("Set up Shizuku") }
                        }
                    }
                }
            },
            dismissButton = {
                if (!startingShizuku) {
                    androidx.compose.material3.TextButton(onClick = { showShizukuLaunchPrompt = false }) {
                        Text("Cancel")
                    }
                }
            },
        )
    }
    ScaleToFit(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        // DRM / HDCP warning — muted amber border (no background), above the
        // primary actions so it's seen before launching anything. Calm "caution"
        // styling rather than alarming red: it's a known limitation, not an error.
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF8A7A4D), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column {
                Text(
                    "DRM-protected video may not play on the external display.\n" +
                        "In streaming apps you may see a black screen, an HDCP error, or a copyright-protection notice.",
                    color = Color(0xFFB8A66A),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Turning on System mirror (Display settings) may fix this for some DRM-protected streaming apps.",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        // Two primary actions side by side: Start/Stop Service (left) and Launch
        // trackpad (right). Equal size, shorter height, each with a two-line label
        // (action on top, hint underneath). The "No click path ready" banner was
        // removed — the top strip already shows access state and the first-run
        // popup covers setup, so it only added clutter/scroll here.
        val canLaunchTrackpad = serviceRunning && externalDisplayId != null
        val greenStart = Color(0xFF2E9E5B)        // calm "go" green (not alarm-bright)
        val greenBorder = Color(0xFF49C77E)       // brighter green so the border reads on green
        val roseStop = Color(0xFFB05566)          // muted rose — "heads up, ends your session"
        val roseBorder = Color(0xFFD07686)        // brighter rose so the border reads on rose
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Start / Stop service.
            Box(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .then(
                        if (serviceRunning)
                            Modifier
                                .background(roseStop)
                                .border(1.5.dp, roseBorder, RoundedCornerShape(18.dp))
                        else
                            Modifier
                                .background(greenStart)
                                .border(1.5.dp, greenBorder, RoundedCornerShape(18.dp))
                    )
                    .pointerInput(serviceRunning) {
                        detectTapGestures(
                            onTap = {
                                app.injector.buzz(longPress = false)
                                if (serviceRunning) {
                                    com.portalpad.app.service.PortalPadForegroundService.stop(context)
                                } else {
                                    onEnableClick()
                                }
                            },
                            onLongPress = {
                                // Long-press → jump straight to Start > Setup (power-user
                                // shortcut), regardless of running state. Firmer buzz.
                                app.injector.buzz(longPress = true)
                                onOpenStartSetup()
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (serviceRunning) "Stop Service" else "Start Service",
                        color = AbOnSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (serviceRunning) "(stops the external display)" else "(required before launching)",
                        color = AbOnSurface.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            // Launch trackpad — greyed/non-tappable until the service is running
            // (and a display connected).
            Box(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (canLaunchTrackpad) greenStart else AbSurface)
                    .then(
                        if (canLaunchTrackpad)
                            Modifier.border(1.5.dp, greenBorder, RoundedCornerShape(18.dp))
                        else
                            Modifier  // greyed/disabled → no border
                    )
                    .clickable(enabled = canLaunchTrackpad) {
                        // If the user is on Shizuku and it's not ready, launching
                        // gives a frozen trackpad — redirect them to open Shizuku
                        // first. Root users are never gated here.
                        if (accessMode == AccessMode.SHIZUKU &&
                            shizukuStatus != ShizukuManager.Status.READY
                        ) {
                            showShizukuLaunchPrompt = true
                        } else {
                            launchTrackpadNow()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        launchLabel,
                        color = if (canLaunchTrackpad) AbOnSurface else AbOnSurfaceMuted,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            !serviceRunning -> "(start service first)"
                            externalDisplayId == null -> "(connect a display)"
                            else -> "(opens on the display)"
                        },
                        color = if (canLaunchTrackpad) AbOnSurface.copy(alpha = 0.85f) else AbOnSurfaceMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Display settings — moved off the top nav. Full-width, distinct violet
        // (accent) tonal/outlined treatment so it reads as a "go to a section"
        // destination rather than a primary action button.
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AbAccent.copy(alpha = 0.14f))
                .border(1.dp, AbAccent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                .clickable { onGoToDisplay() }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Icon + label centered in the button…
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 28.dp),
            ) {
                Icon(Icons.Default.Devices, contentDescription = null, tint = AbAccent)
                Spacer(Modifier.width(12.dp))
                // Resolve a connected display's name: prefer the service's
                // physical/external id, fall back to live-detected. When present,
                // the label reads "Display – <name>".
                val serviceDisplayName = remember(externalDisplayId, physicalExternalDisplayId) {
                    val id = physicalExternalDisplayId ?: externalDisplayId
                    id?.let {
                        val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
                            as android.hardware.display.DisplayManager
                        runCatching { dm.getDisplay(it)?.name?.takeIf { n -> n.isNotBlank() } }.getOrNull()
                    }
                }
                val shownName = serviceDisplayName ?: detectedDisplayName
                Text(
                    if (shownName != null) "Display \u2013 $shownName" else "Display",
                    color = AbOnSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            // …chevron pinned to the right edge.
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Open Display settings",
                tint = AbAccent,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        // Home / Back buttons — same centered "Buttons" card layout these had
        // when they lived on the Controls page: two columns (Home | Back) with a
        // divider, the action label above and the assigned action name in
        // smaller text below. Both open the button-actions page (returns here).
        // The AppEntry behind each button (null = not an app/activity action).
        val homeEntry: com.portalpad.app.data.AppEntry? = homeAction
        val backEntry: com.portalpad.app.data.AppEntry? =
            (backAction as? com.portalpad.app.data.BackAction.Launch)?.entry
        val homeLabel = homeAction?.label ?: "Not set (System Home)"
        val backLabel = when (val b = backAction) {
            is com.portalpad.app.data.BackAction.System -> "Back action"
            is com.portalpad.app.data.BackAction.Launch -> b.entry.label
        }
        val buttonActionColumn: @Composable RowScope.(String, String, com.portalpad.app.data.AppEntry?, () -> Unit) -> Unit = { title, value, entry, onClick ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onClick() }
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title, color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                )
                // For an app/activity action, show icon + app label (+ activity
                // name) so it's clear which app it is. Otherwise plain value text.
                if (entry != null) {
                    com.portalpad.app.ui.common.AssignedActionLabel(
                        entry = entry, fallback = value, iconSizeDp = 28,
                    )
                } else {
                    Text(
                        value, color = AbAccent,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
        SectionCard(title = "", centeredTitleNoDot = true) {
            // Auto Launch on Start — what appears on the glasses when a session
            // starts (independent of the Home button). Yellow value, truncates.
            val autoLaunchEntry = (autoLaunch as? com.portalpad.app.data.AutoLaunch.Launch)?.entry
            val autoLaunchLabel = when (val a = autoLaunch) {
                com.portalpad.app.data.AutoLaunch.Wallpaper -> "Wallpaper (default)"
                com.portalpad.app.data.AutoLaunch.LastApp -> "Last app"
                is com.portalpad.app.data.AutoLaunch.Launch -> a.entry.label
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenAutoLaunch() }
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "Auto Launch on Start", color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                )
                if (autoLaunchEntry != null) {
                    com.portalpad.app.ui.common.AssignedActionLabel(
                        entry = autoLaunchEntry, fallback = autoLaunchLabel, iconSizeDp = 28,
                    )
                } else {
                    Text(
                        autoLaunchLabel, color = AbAccent,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(vertical = 3.dp),
                thickness = 1.dp,
                color = AbOnSurfaceDim.copy(alpha = 0.5f),
            )
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                buttonActionColumn("Home button", homeLabel, homeEntry, onPickHome)
                androidx.compose.material3.VerticalDivider(
                    modifier = Modifier.fillMaxHeight().padding(horizontal = 4.dp),
                    thickness = 1.dp,
                    color = AbOnSurfaceDim.copy(alpha = 0.5f),
                )
                buttonActionColumn("Back button", backLabel, backEntry, onPickBack)
            }
        }

        DeviceTakeoverNote()

        // (Status card removed — Service state now shows in the top strip, and
        // Shizuku/Root readiness is shown live by the strip's Mode indicator.)
        // Accessibility companion button hidden — service de-registered; the dock
        // long-press menu now works in-process via Shizuku, so accessibility is no
        // longer needed. (Block removed from UI; restore from git if re-enabling.)
      }
    }
}

@Composable
private fun DeviceTakeoverNote() {
    val app = PortalPadApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accessMode by app.prefs.accessMode.collectAsState(initial = AccessMode.NONE)
    val shizukuStatus by app.shizuku.status.collectAsState()

    val isSamsung = android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    val hasXrealGlasses = remember {
        context.packageManager.getLaunchIntentForPackage("com.xreal.evapro.nebula") != null
    }
    val hasMotoConnect = remember {
        context.packageManager.getLaunchIntentForPackage("com.motorola.mobiledesktop") != null
    }
    if (!isSamsung && !hasXrealGlasses && !hasMotoConnect) return

    // Pick the privilege backend by ACTUAL readiness, not the mode label, so a
    // bound Shizuku still drives DeX even when the access-mode pref is NONE
    // (mirrors app.access's NONE->shizuku fallback). Root is only consulted when
    // the user is in root mode or has explicitly tapped "Request Root", so we
    // never fire an `su` probe (Magisk prompt) behind their back.
    var rootRequested by remember { mutableStateOf(false) }
    var dexBackend by remember { mutableStateOf<com.portalpad.app.service.ElevatedAccess?>(null) }
    var privReady by remember { mutableStateOf(false) }
    var dexOn by remember { mutableStateOf<Boolean?>(null) }
    var dexRefresh by remember { mutableStateOf(0) }
    var showEnableDexConfirm by remember { mutableStateOf(false) }
    // Hoisted above any conditional UI below so the hook count stays stable
    // (inline collectAsState inside a new conditional would crash the slot
    // table). Samsung-only behavior; the toggle that reads it is gated to
    // isSamsung && privReady further down.
    val stopDexOnStart by app.prefs.bool(
        PreferencesRepository.Keys.STOP_DEX_ON_START, default = true,
    ).collectAsState(initial = true)
    LaunchedEffect(accessMode, shizukuStatus, dexRefresh, rootRequested) {
        val backend: com.portalpad.app.service.ElevatedAccess? =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val rootReady = (accessMode == AccessMode.ROOT || rootRequested) &&
                    runCatching { app.root.isReady }.getOrDefault(false)
                val shizukuReady = runCatching { app.shizuku.isReady }.getOrDefault(false)
                when {
                    rootReady -> app.root
                    shizukuReady -> app.shizuku
                    else -> null
                }
            }
        dexBackend = backend
        privReady = backend != null
        dexOn = if (isSamsung && backend != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    backend.execShell("settings get system dex_on_external_display").trim() == "1"
                }.getOrNull()
            }
        } else {
            null
        }
    }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) dexRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF8A4D4D), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column {
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("\u26A0 Note: ")
                    }
                    append(
                        "Some devices auto-launch their own display experience " +
                            "(e.g. Samsung DeX, Motorola Smart Connect, or nebulaOS on the " +
                            "XREAL Beam Pro) that takes over instead of PortalPad.",
                    )
                },
                color = Color(0xFFD98A8A),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Adjust these settings first:",
                color = Color(0xFFD98A8A),
                style = MaterialTheme.typography.bodySmall,
            )

            if (isSamsung) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Samsung DeX:",
                    color = Color(0xFFD98A8A),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "How to disable DeX (video)",
                    color = AbPrimaryBright,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openUrl(context, "https://www.youtube.com/watch?v=l1CEsDTjDMo") }
                        .padding(vertical = 4.dp),
                )

                val canDisable = dexOn == true
                val statusText = when (dexOn) {
                    true -> "DeX: On"
                    false -> "DeX: Off"
                    null -> "DeX: Unknown"
                }
                val statusColor = if (dexOn == true) Color(0xFFD98A8A) else AbOnSurfaceMuted
                Spacer(Modifier.height(2.dp))
                Text(
                    statusText,
                    color = statusColor,
                    style = MaterialTheme.typography.labelSmall,
                )

                Spacer(Modifier.height(5.dp))
                val disableTint = if (canDisable) AbPrimaryBright else AbOnSurfaceDim.copy(alpha = 0.4f)
                // Blind toggle of the Samsung DeX quick-settings tile. Tap disables
                // (only when DeX is on); long-press asks before ENABLING, since
                // turning DeX on switches to desktop mode and takes over the screen.
                val toggleDexTile: () -> Unit = {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                dexBackend?.execShell(
                                    "cmd statusbar click-tile com.sec.android.app.launcher/com.honeyspace.dexservice.DesktopModeTile",
                                )
                            }
                        }
                        kotlinx.coroutines.delay(700)
                        dexRefresh++
                    }
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, disableTint, RoundedCornerShape(8.dp))
                        .pointerInput(canDisable) {
                            detectTapGestures(
                                onTap = { if (canDisable) toggleDexTile() },
                                onLongPress = { showEnableDexConfirm = true },
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        "Disable DeX",
                        color = disableTint,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "Long-press the button above to enable DeX.",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.labelSmall,
                )

                // Samsung-only: standing preference to turn DeX off whenever the
                // PortalPad service starts, so DeX and our VirtualDisplay don't
                // fight over the external display. Gated to isSamsung && privReady
                // (without privilege we couldn't act on DeX anyway). Visible
                // regardless of DeX's current on/off state — it governs FUTURE
                // starts. The "only if currently on" guard lives at execution
                // time in the foreground service, not here.
                if (isSamsung && privReady) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Stop DeX when PortalPad starts",
                            color = AbOnSurface,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = stopDexOnStart,
                            onCheckedChange = { v ->
                                scope.launch {
                                    app.prefs.setBool(
                                        PreferencesRepository.Keys.STOP_DEX_ON_START, v,
                                    )
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

                if (showEnableDexConfirm) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showEnableDexConfirm = false },
                        title = { Text("Enable DeX?") },
                        text = {
                            Text(
                                "This turns Samsung DeX on, which switches the phone into " +
                                    "desktop mode and takes over the screen. Enabling DeX will " +
                                    "also stop the PortalPad service. Continue?",
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    showEnableDexConfirm = false
                                    // DeX takes over the external display, which fights
                                    // our VirtualDisplay — stop PortalPad's service first
                                    // so they don't conflict, then toggle DeX on.
                                    com.portalpad.app.service.PortalPadForegroundService.stop(context)
                                    toggleDexTile()
                                },
                            ) { Text("Enable DeX") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { showEnableDexConfirm = false },
                            ) { Text("Cancel") }
                        },
                    )
                }

                if (!privReady) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Needs Shizuku or root to read or disable DeX.",
                        color = AbOnSurfaceMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(5.dp))
                    when (accessMode) {
                        AccessMode.SHIZUKU -> {
                            if (shizukuStatus == ShizukuManager.Status.NEEDS_PERMISSION) {
                                DexSetupButton("Authorize Shizuku") {
                                    app.shizuku.requestPermission()
                                    scope.launch {
                                        kotlinx.coroutines.delay(800)
                                        app.shizuku.refresh()
                                        dexRefresh++
                                    }
                                }
                            } else {
                                DexSetupButton("Open Shizuku") { openShizukuApp(context) }
                            }
                        }
                        AccessMode.ROOT -> {
                            DexSetupButton("Request Root") {
                                rootRequested = true
                                scope.launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching { app.root.refresh() }
                                    }
                                    dexRefresh++
                                }
                            }
                        }
                        else -> {
                            val shizukuAuth = shizukuStatus == ShizukuManager.Status.NEEDS_PERMISSION
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                DexSetupButton(
                                    if (shizukuAuth) "Authorize Shizuku" else "Open Shizuku",
                                    Modifier.weight(1f),
                                ) {
                                    if (shizukuAuth) {
                                        app.shizuku.requestPermission()
                                        scope.launch {
                                            kotlinx.coroutines.delay(800)
                                            app.shizuku.refresh()
                                            dexRefresh++
                                        }
                                    } else {
                                        openShizukuApp(context)
                                    }
                                }
                                DexSetupButton("Request Root", Modifier.weight(1f)) {
                                    rootRequested = true
                                    scope.launch {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            runCatching { app.root.refresh() }
                                        }
                                        dexRefresh++
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (hasXrealGlasses) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "XREAL Beam Pro:",
                    color = Color(0xFFD98A8A),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "My Glasses app \u2192 enable Default Air Casting \u2192 unplug and replug glasses if necessary.",
                    color = Color(0xFFD98A8A),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                DeviceLaunchButton("Open My Glasses", "com.xreal.evapro.nebula", context)
            }

            if (hasMotoConnect) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Motorola Smart Connect:",
                    color = Color(0xFFD98A8A),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "How to disable Smart Connect (video)",
                    color = AbPrimaryBright,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openUrl(context, "https://www.youtube.com/watch?v=yIoSGFFuih4") }
                        .padding(vertical = 4.dp),
                )
                Spacer(Modifier.height(4.dp))
                DeviceLaunchButton("Open Smart Connect", "com.motorola.mobiledesktop", context)
            }
        }
    }
}

@Composable
private fun DeviceLaunchButton(label: String, pkg: String, context: android.content.Context) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, AbPrimaryBright, RoundedCornerShape(8.dp))
            .clickable {
                runCatching {
                    context.packageManager.getLaunchIntentForPackage(pkg)?.let {
                        it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        // Pin to the PHONE (display 0) so the app — and the centered
                        // "Tap to exit" pill — land on the phone regardless of where
                        // this settings screen is showing.
                        val opts = android.app.ActivityOptions.makeBasic()
                            .setLaunchDisplayId(0)
                            .toBundle()
                        context.startActivity(it, opts)
                        // Centered "⠿ Tap to exit" pill, shown service-INDEPENDENTLY:
                        // these buttons are typically used during setup, before the
                        // foreground service is running (which is why the pill never
                        // appeared before). Tapping returns to PortalPad WITHOUT closing
                        // the device app, since the user may be mid-configuration.
                        val appCtx = context.applicationContext
                        com.portalpad.app.presentation.PhoneExitBandsOverlay.showStandalone(
                            appCtx,
                            onExit = {
                                runCatching {
                                    val back = android.content.Intent(
                                        appCtx, com.portalpad.app.MainActivity::class.java,
                                    ).addFlags(
                                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                            android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                                    )
                                    val backOpts = android.app.ActivityOptions.makeBasic()
                                        .setLaunchDisplayId(0).toBundle()
                                    appCtx.startActivity(back, backOpts)
                                }
                            },
                        )
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = AbPrimaryBright,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DexSetupButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, AbPrimaryBright, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = AbPrimaryBright,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun openShizukuApp(context: android.content.Context) {
    val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    if (launch != null) {
        runCatching {
            context.startActivity(
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean, compact: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = if (compact) 5.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(if (compact) 9.dp else 10.dp).clip(CircleShape)
                .background(if (ok) AbSuccess else AbDanger),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = AbOnSurface,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value,
            color = AbOnSurfaceMuted,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ─────────────────────────── Tab 2: Buttons ───────────────────────────

@Composable
private fun ButtonsTab(onPickHome: () -> Unit, onPickBack: () -> Unit, scrollToTopSignal: Int = 0) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val homeAction by prefs.homeAction.collectAsState(initial = null)
    val backAction by prefs.backAction.collectAsState(initial = BackAction.System)

    TabBody(scrollToTopSignal = scrollToTopSignal) {
        SectionCard(title = "Controls") {
            // Two parallel collapsible dropdowns, both collapsed by default to
            // keep the card compact:
            //   "Trackpad & Air Mouse" — surface settings shared by both modes.
            //   "Air Mouse Only"       — tilt-specific settings.
            val collapsible: @Composable (String, @Composable () -> Unit) -> Unit =
                { title, content ->
                    var open by remember { mutableStateOf(false) }
                    val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { open = !open }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            color = AbAccent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = if (open) "Collapse" else "Expand",
                            tint = AbOnSurfaceMuted,
                            modifier = Modifier.rotate(rot),
                        )
                    }
                    if (open) {
                        Column(Modifier.padding(start = 4.dp, bottom = 4.dp)) { content() }
                    }
                    HorizontalDivider(color = AbSurfaceElevated, thickness = 1.dp)
                }

            collapsible("Trackpad & Air Mouse") {
                TrackpadSurfaceControls()
            }
            collapsible("Air Mouse Only") {
                SpeedSlider(
                    label = "Sensitivity",
                    desc = "How far the cursor moves per degree you tilt the glasses. Higher = faster.",
                    key = PreferencesRepository.Keys.AIR_MOUSE_SENSITIVITY,
                    default = 20f,
                    range = 2f..20f,
                )
                SpeedSlider(
                    label = "Smoothing",
                    desc = "Filters sensor jitter for steadier motion. Higher = smoother but slightly laggier; lower = snappier but jitterier.",
                    key = PreferencesRepository.Keys.AIR_MOUSE_SMOOTHING,
                    default = 0.3f,
                    range = 0f..0.9f,
                )
                SpeedSlider(
                    label = "Acceleration",
                    desc = "How much the cursor speeds up when you sweep the glasses faster — slow aim stays precise, quick sweeps cover more screen. Set to 0 for a constant speed.",
                    key = PreferencesRepository.Keys.AIR_MOUSE_ACCEL,
                    default = 1.0f,
                    range = 0f..2f,
                )
                ToggleRowWithDesc(
                    label = "Invert horizontal direction",
                    desc = "Flip left/right if swinging the glasses moves the cursor the wrong way.",
                    key = PreferencesRepository.Keys.AIR_MOUSE_INVERT_X,
                    default = false,
                )
                ToggleRowWithDesc(
                    label = "Invert vertical direction",
                    desc = "Flip up/down if tilting the glasses moves the cursor the wrong way.",
                    key = PreferencesRepository.Keys.AIR_MOUSE_INVERT_Y,
                    default = false,
                )
            }
            collapsible("External mouse (Beta)") {
                ExternalMouseControls()
            }
        }
        SectionCard(title = "Three-Finger Swipes") {
            Text(
                "Assign an action to each 3-finger swipe direction on the trackpad / air-mouse surface. Swipe with three fingers to trigger it.",
                color = AbOnSurfaceMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            val gUp by prefs.gestureSwipeUp.collectAsState(initial = com.portalpad.app.data.GestureAction.NOTIF_CLOSE)
            val gDown by prefs.gestureSwipeDown.collectAsState(initial = com.portalpad.app.data.GestureAction.NOTIF_OPEN)
            val gLeft by prefs.gestureSwipeLeft.collectAsState(initial = com.portalpad.app.data.GestureAction.HOME)
            val gRight by prefs.gestureSwipeRight.collectAsState(initial = com.portalpad.app.data.GestureAction.BACK)
            val gUpApp by prefs.gestureAppUp.collectAsState(initial = null)
            val gDownApp by prefs.gestureAppDown.collectAsState(initial = null)
            val gLeftApp by prefs.gestureAppLeft.collectAsState(initial = null)
            val gRightApp by prefs.gestureAppRight.collectAsState(initial = null)
            GestureActionRow("Swipe up", gUp, "gesture_up", gUpApp) { scope.launch { prefs.setGestureSwipeUp(it) } }
            GestureActionRow("Swipe down", gDown, "gesture_down", gDownApp) { scope.launch { prefs.setGestureSwipeDown(it) } }
            GestureActionRow("Swipe left", gLeft, "gesture_left", gLeftApp) { scope.launch { prefs.setGestureSwipeLeft(it) } }
            GestureActionRow("Swipe right", gRight, "gesture_right", gRightApp) { scope.launch { prefs.setGestureSwipeRight(it) } }
        }
        SectionCard(title = "Vibration") {
            Text(
                "Haptic feedback strength for taps — trackpad clicks and on-screen buttons. Affects the whole app. Some devices only support on/off regardless of level.",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            // Presets → milliseconds. 0 = Off.
            val vibOptions = listOf(
                "Off" to 0,
                "Light" to 10,
                "Medium" to 25,
                "Strong" to 40,
            )
            val vibMs by prefs.vibrationMs.collectAsState(initial = 25)
            val vibLabel = vibOptions.firstOrNull { it.second == vibMs }?.first ?: "Medium"
            var vibExpanded by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AbSurfaceElevated)
                        .clickable { vibExpanded = true }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        vibLabel,
                        color = AbOnSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = "Open vibration menu",
                        tint = AbOnSurfaceMuted,
                    )
                }
                DropdownMenu(
                    expanded = vibExpanded,
                    onDismissRequest = { vibExpanded = false },
                    modifier = Modifier.background(AbSurfaceElevated),
                ) {
                    vibOptions.forEach { (label, ms) ->
                        DropdownMenuItem(
                            text = { Text(label, color = AbOnSurface) },
                            onClick = {
                                vibExpanded = false
                                scope.launch { prefs.setVibrationMs(ms) }
                            },
                        )
                    }
                }
            }
        }
        SectionCard(title = "Cursor") {
            ToggleRowWithDesc(
                label = "Custom cursor on external display",
                desc = "Render an amber cursor on the external display at the current pointer location. Useful when the OEM doesn't render a system cursor for injected mouse events.",
                key = PreferencesRepository.Keys.ENABLE_MOUSE_HOVER,
                default = true,
            )
            MediaCursorHideSection()
        }
        SectionCard(title = "Keyboard & navigation") {
            // 1) Whether the keyboard pill appears in the trackpad bottom bar.
            ToggleRowWithDesc(
                label = "Show keyboard button",
                desc = "Add a keyboard button to the trackpad bottom bar. Tapping it brings up the keyboard on whichever display you choose below.",
                key = PreferencesRepository.Keys.SHOW_KEYBOARD,
                default = true,
            )
            // 2) WHERE the keyboard lives — the single either/or choice (drives
            //    IME_ON_EXTERNAL). Replaces the old standalone on/off toggle.
            KeyboardDisplaySelector()

            // 3) Auto-open the typing page when you tap a text field on the
            //    glasses (phone-keyboard mode only). Detection uses Shizuku:
            //    ActivityTaskManager gives the focused app's display reliably, so
            //    this only fires for glasses fields — not the phone's own search
            //    boxes (the flaw that made the earlier version unreliable).
            val keyboardOnGlasses by app.prefs
                .bool(PreferencesRepository.Keys.IME_ON_EXTERNAL, default = false)
                .collectAsState(initial = false)
            ToggleRowWithDesc(
                label = "Auto-open typing page on field tap",
                desc = "When the keyboard is set to the phone, tapping a text field on the glasses — or tapping something that opens one, like a search icon — automatically opens the \"Type to external display\" page on the phone, no need to press the keyboard button. Works for any field, including ones that won't pop the native keyboard (Chrome address bar, etc.). Requires the PortalPad accessibility service (below).",
                requirement = Requirement.ROOT_OR_SHIZUKU,
                key = PreferencesRepository.Keys.AUTO_OPEN_RELAY_ON_FIELD,
                default = true,
                enabled = !keyboardOnGlasses,
                disabledNote = "Set the keyboard to the phone (above) to use this.",
            )
            // Accessibility is the detector for auto-open: it receives real focus/tap
            // events with the field's display id, and for fields that focus silently it
            // probes the focused node directly. When it's off, auto-open is unavailable
            // (there's no fallback). Hidden in glasses mode (the toggle doesn't apply
            // there). The status refreshes when the user returns from the settings screen.
            if (!keyboardOnGlasses) {
                val a11yLifecycleOwner = LocalLifecycleOwner.current
                var a11yTick by remember { mutableIntStateOf(0) }
                DisposableEffect(a11yLifecycleOwner) {
                    val obs = LifecycleEventObserver { _, e ->
                        if (e == Lifecycle.Event.ON_RESUME) a11yTick++
                    }
                    a11yLifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { a11yLifecycleOwner.lifecycle.removeObserver(obs) }
                }
                val a11yEnabled = remember(a11yTick) {
                    runCatching {
                        android.provider.Settings.Secure.getString(
                            app.contentResolver,
                            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        )?.contains(app.packageName) == true
                    }.getOrDefault(false)
                }
                Text(
                    "Required for auto-open: Enable the PortalPad accessibility service to detect when you tap or open a text field on the glasses. It also shows the field's current text in the typing page so you can edit it. Without it, the typing page won't open automatically.",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                )
                // Restricted-settings workaround — only while not yet enabled (once
                // it's on, the workaround is moot). Worded as "on some devices" since
                // the exact steps vary by OEM (Samsung/Pixel both use this flow).
                if (!a11yEnabled) {
                    Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("Note: ") }
                            append("On some devices the PortalPad switch in Accessibility is greyed out. Tap it once — Android then surfaces a 3-dot menu on PortalPad's App info page. Open that menu, choose \"Allow restricted settings,\" then return to Accessibility and enable PortalPad.")
                        },
                        color = AbDanger.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
                    )
                    Text(
                        "App info",
                        color = AbPrimaryBright,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = 4.dp, start = 4.dp)
                            .clickable {
                                runCatching {
                                    app.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            android.net.Uri.fromParts("package", app.packageName, null),
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                    )
                }
                // Status line carries the enabled/disabled state.
                Text(
                    if (a11yEnabled) "Status: Accessibility enabled ✓" else "Status: Not enabled",
                    color = if (a11yEnabled) AbSuccess else AbOnSurfaceMuted,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
                // Button stays action-focused ("Open Accessibility") and stays
                // tappable even when enabled (so you can go turn it off) — just
                // muted/secondary when already on, rather than a dead disabled look.
                OutlinedButton(
                    onClick = {
                        runCatching {
                            app.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (a11yEnabled) AbOnSurfaceMuted else AbPrimaryBright,
                    ),
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                ) {
                    Text("Open Accessibility")
                }
            }
        }
        var showResetConfirm by remember { mutableStateOf(false) }
        ResetToDefaultsButton("Reset Controls to Defaults") { showResetConfirm = true }
        if (showResetConfirm) {
            ResetConfirmDialog(
                title = "Reset Controls to defaults?",
                message = "This resets trackpad, scrolling, cursor, vibration, and air-mouse settings to their defaults. Your Home/Back button assignments are kept.",
                onConfirm = {
                    showResetConfirm = false
                    scope.launch {
                        prefs.setBool(PreferencesRepository.Keys.TAP_TO_CLICK, true)
                        prefs.setBool(PreferencesRepository.Keys.EDGE_SCROLL, true)
                        prefs.setString(PreferencesRepository.Keys.EDGE_SCROLL_SIDE, "right")
                        prefs.setFloat(PreferencesRepository.Keys.CURSOR_SPEED, 1.0f)
                        prefs.setFloat(PreferencesRepository.Keys.SCROLL_SPEED, 1.0f)
                        prefs.setBool(PreferencesRepository.Keys.INVERT_SCROLL, false)
                        prefs.setBool(PreferencesRepository.Keys.SHOW_FINGER_DOTS, false)
                        prefs.setVibrationMs(25)
                        prefs.setBool(PreferencesRepository.Keys.ENABLE_MOUSE_HOVER, true)
                        prefs.setFloat(PreferencesRepository.Keys.AIR_MOUSE_SENSITIVITY, 20f)
                        prefs.setFloat(PreferencesRepository.Keys.AIR_MOUSE_SMOOTHING, 0.3f)
                        prefs.setBool(PreferencesRepository.Keys.AIR_MOUSE_INVERT_X, false)
                        prefs.setBool(PreferencesRepository.Keys.AIR_MOUSE_INVERT_Y, false)
                        prefs.setBool(PreferencesRepository.Keys.SHOW_KEYBOARD, true)
                        prefs.setBool(PreferencesRepository.Keys.IME_ON_EXTERNAL, false)
                        prefs.setBool(PreferencesRepository.Keys.AUTO_OPEN_RELAY_ON_FIELD, true)
                    }
                },
                onCancel = { showResetConfirm = false },
            )
        }
    }
}

/** Toggle row with multi-line description under the label. Used to explain what each toggle does. */
/**
 * Privilege requirement for a setting, shown as a small badge so the user knows
 * which access mode it needs. SHIZUKU_ONLY = needs the bound Shizuku UserService
 * (trusted virtual display / privileged reflection / IME policy) which root does
 * not yet provide. ROOT_OR_SHIZUKU = works via plain elevated shell under either.
 */
private enum class Requirement { NONE, SHIZUKU_ONLY, ROOT_OR_SHIZUKU }

@Composable
private fun RequirementBadge(req: Requirement) {
    if (req == Requirement.NONE) return
    val (text, fg, bg) = when (req) {
        Requirement.SHIZUKU_ONLY -> Triple(
            "Requires Shizuku", AbPrimaryBright, AbPrimary.copy(alpha = 0.18f),
        )
        Requirement.ROOT_OR_SHIZUKU -> Triple(
            "Root or Shizuku", AbOnSurface, AbSurfaceElevated,
        )
        Requirement.NONE -> return
    }
    Box(
        Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * "Auto-hide cursor during media playback" — a toggle plus a conditional seconds
 * slider. Detecting playback uses MediaSessionManager.getActiveSessions, which
 * requires the notification listener; if it isn't enabled we show a tap row that
 * deep-links to Android's notification-access settings (no shell-grant — that path
 * is unreliable for listeners on modern Android, matching the rest of the app).
 * The actual hide/show logic lives in PortalPadForegroundService; this is just the
 * controls.
 */
@Composable
private fun ExternalMouseControls() {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val keys = PreferencesRepository.Keys

    Text(
        "Use a physical mouse \u2014 Bluetooth or USB \u2014 to drive the cursor on your " +
            "external display. Move, click, right-click, scroll, and drag to move or " +
            "select. Works with the screen blanked via Extinguish.",
        color = AbOnSurfaceMuted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )

    // Enable + status. The toggle reflects the persisted opt-in (which also
    // drives auto-resume on display/Bluetooth connect); enable()/disable() apply
    // it immediately. Initial mirrors live state so an already-armed session reads
    // correctly on open.
    val mouseOn by app.prefs.bool(keys.EXT_MOUSE_ENABLED, false)
        .collectAsState(initial = app.btMouse.running)
    var status by remember { mutableStateOf(app.btMouse.lastStatus) }
    val grab by app.prefs.bool(keys.EXT_MOUSE_GRAB, true).collectAsState(initial = true)

    val statusLabel = when {
        !mouseOn -> "Off"
        status.startsWith("OK") ->
            if (status.contains("grab=OK")) "Connected \u2014 pointer grabbed"
            else "Connected \u2014 shared (grab off)"
        else -> status
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Enable", color = AbOnSurface, style = MaterialTheme.typography.bodyLarge)
            Text(statusLabel, color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = mouseOn,
            onCheckedChange = { on ->
                scope.launch { app.prefs.setBool(keys.EXT_MOUSE_ENABLED, on) }
                status = if (on) app.btMouse.enable(grab) else { app.btMouse.disable(); "stopped" }
            },
        )
    }

    // Sensitivity
    val sens by app.prefs.float(keys.EXT_MOUSE_SENSITIVITY, 2.5f).collectAsState(initial = 2.5f)
    Text(
        "Mouse sensitivity: ${"%.1f".format(sens)}\u00d7",
        color = AbOnSurfaceMuted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
    Slider(
        value = sens,
        onValueChange = { v -> scope.launch { app.prefs.setFloat(keys.EXT_MOUSE_SENSITIVITY, v) } },
        valueRange = 0.5f..6f,
        colors = sliderColors(),
        modifier = Modifier.padding(horizontal = 8.dp),
    )

    // Scroll speed
    val scrollSpd by app.prefs.float(keys.EXT_MOUSE_SCROLL_SPEED, 1.0f).collectAsState(initial = 1.0f)
    Text(
        "Scroll speed: ${"%.2f".format(scrollSpd)}\u00d7",
        color = AbOnSurfaceMuted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
    Slider(
        value = scrollSpd,
        onValueChange = { v -> scope.launch { app.prefs.setFloat(keys.EXT_MOUSE_SCROLL_SPEED, v) } },
        valueRange = 0.25f..4f,
        colors = sliderColors(),
        modifier = Modifier.padding(horizontal = 8.dp),
    )

    // Reverse scroll direction
    ToggleRowWithDesc(
        label = "Reverse scroll direction",
        desc = "Flip the wheel direction if your mouse scrolls the opposite way.",
        key = keys.EXT_MOUSE_NATURAL_SCROLL,
        default = false,
    )

    // Exclusive grab — re-applies immediately if the mouse is on.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Exclusive grab", color = AbOnSurface, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Capture the mouse so only the external cursor moves. Turn off if your " +
                    "device won't grab, or to keep using the mouse on the phone too.",
                color = AbOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = grab,
            onCheckedChange = { on ->
                scope.launch { app.prefs.setBool(keys.EXT_MOUSE_GRAB, on) }
                if (mouseOn) status = app.btMouse.enable(on)
            },
        )
    }

    // Reconnect — re-runs discovery (the device's event node can change).
    androidx.compose.material3.TextButton(
        onClick = { if (mouseOn) status = app.btMouse.enable(grab) },
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        Text(if (mouseOn) "Reconnect" else "Reconnect (enable first)")
    }
}

@Composable
private fun MediaCursorHideSection() {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Re-check listener status when returning from Android settings.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var refreshTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val notifEnabled = remember(refreshTick) {
        runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners",
            )?.contains(context.packageName) == true
        }.getOrDefault(false)
    }

    val enabled by prefs.bool(PreferencesRepository.Keys.MEDIA_AUTOHIDE_CURSOR, default = false)
        .collectAsState(initial = false)
    val secs by prefs.int(PreferencesRepository.Keys.MEDIA_AUTOHIDE_CURSOR_SEC, default = 5)
        .collectAsState(initial = 5)

    fun openNotifSettings() = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val cn = android.content.ComponentName(
                context, com.portalpad.app.service.PortalPadNotificationListenerService::class.java,
            )
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            throw IllegalStateException("pre-30")
        }
    }.recoverCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    ToggleRowWithDesc(
        label = "Auto-hide cursor during media playback",
        desc = "When fullscreen media is playing on the external display, hide the cursor after a few idle seconds. It reappears the moment you use the trackpad again. (Stays visible for media in a freeform window.)",
        key = PreferencesRepository.Keys.MEDIA_AUTOHIDE_CURSOR,
        default = false,
    )
    if (enabled && !notifEnabled) {
        // Listener required for playback detection — offer to grant it.
        Spacer(Modifier.height(4.dp))
        Text(
            text = androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                append("Needs notification access. ")
                pop()
                append("Tap to enable it so PortalPad can detect when media is playing.")
            },
            color = AbWarning,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { app.injector.buzz(); openNotifSettings() }
                .padding(vertical = 4.dp),
        )
    } else if (enabled) {
        // Listener present — reveal the idle-delay slider.
        Spacer(Modifier.height(4.dp))
        Text(
            "Hide after: ${secs}s of no trackpad movement",
            color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = secs.toFloat(),
            onValueChange = { n ->
                scope.launch {
                    prefs.setInt(PreferencesRepository.Keys.MEDIA_AUTOHIDE_CURSOR_SEC, n.toInt())
                }
            },
            valueRange = 2f..20f, steps = 17, colors = sliderColors(),
        )
    }
}

@Composable
private fun ToggleRowWithDesc(
    label: String,
    desc: String,
    key: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
    default: Boolean = false,
    requirement: Requirement = Requirement.NONE,
    // When set, toggling shows a confirm dialog with this message; the pref is only
    // written if the user confirms. Used by the desktop-windows toggle, which
    // closes all external windows when it flips.
    confirmMessage: String? = null,
    // When false, the row is greyed out and the switch is non-interactive. Used to
    // show a toggle whose effect is moot under the current configuration (e.g.
    // auto-open-relay when the keyboard is set to the glasses). Optional note
    // explains WHY it's disabled.
    enabled: Boolean = true,
    disabledNote: String? = null,
) {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val value by app.prefs.bool(key, default).collectAsState(initial = default)
    var pendingValue by remember { mutableStateOf<Boolean?>(null) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                label,
                color = if (enabled) AbOnSurface else AbOnSurfaceDim,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                desc,
                color = if (enabled) AbOnSurfaceMuted else AbOnSurfaceDim,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!enabled && disabledNote != null) {
                Text(
                    disabledNote,
                    color = AbAccent,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Default: ${if (default) "On" else "Off"}",
                color = AbOnSurfaceDim,
                style = MaterialTheme.typography.bodySmall,
            )
            RequirementBadge(requirement)
        }
        Switch(
            checked = value,
            enabled = enabled,
            onCheckedChange = { newVal ->
                if (confirmMessage != null) pendingValue = newVal
                else scope.launch { app.prefs.setBool(key, newVal) }
            },
            colors = SwitchDefaults.colors(
                checkedTrackColor = AbPrimary,
                checkedThumbColor = AbPrimaryBright,
                uncheckedTrackColor = AbSurfaceElevated,
                uncheckedThumbColor = AbOnSurfaceDim,
            ),
        )
    }
    // Confirm dialog (only used when confirmMessage != null). The Switch stays at
    // the old value until confirmed, so cancelling leaves everything unchanged.
    if (confirmMessage != null) {
        pendingValue?.let { pv ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingValue = null },
                title = { Text(if (pv) "Turn on desktop windows?" else "Turn off desktop windows?") },
                text = { Text(confirmMessage, color = AbOnSurfaceMuted) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        scope.launch { app.prefs.setBool(key, pv) }
                        pendingValue = null
                    }) { Text("Continue") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { pendingValue = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

/**
 * Segmented selector for WHERE the keyboard appears — the single either/or choice
 * that the two booleans (pill on/off lives elsewhere; this drives IME_ON_EXTERNAL)
 * used to express awkwardly. true = glasses (LOCAL IME on the external display),
 * false = phone (the relay page). Shows a heading so the user knows it's a choice
 * with more than one option, plus an explanation of the selected mode.
 */
@Composable
private fun KeyboardDisplaySelector() {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val onExternal by app.prefs.bool(PreferencesRepository.Keys.IME_ON_EXTERNAL, default = false)
        .collectAsState(initial = false)
    val selectedIndex = if (onExternal) 0 else 1
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "Choose your preferred keyboard display",
            color = AbOnSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Where the keyboard appears when you tap a text field or the keyboard button.",
            color = AbOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.size(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(AbSurfaceElevated),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            listOf("On the glasses", "On the phone").forEachIndexed { idx, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (idx == selectedIndex) AbPrimary else Color.Transparent)
                        .clickable {
                            scope.launch {
                                app.prefs.setBool(
                                    PreferencesRepository.Keys.IME_ON_EXTERNAL,
                                    idx == 0,
                                )
                            }
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (idx == selectedIndex) AbOnSurface else AbOnSurfaceMuted,
                        fontWeight = if (idx == selectedIndex) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            if (onExternal)
                "Glasses (recommended): the keyboard appears on the external display next to the focused app — tap a text field there and type directly. Cross-display dropdowns stay open."
            else
                "Phone: tapping the keyboard button (or focusing a field, if auto-open is on) opens a text box on the phone that you type into; what you type is forwarded to the focused app on the glasses.",
            color = AbOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        RequirementBadge(Requirement.ROOT_OR_SHIZUKU)
    }
}

// ─────────────────────────── Tab 3: Dock ───────────────────────────

/**
 * A focused, mode-specific control subpage opened from the gear on the
 * trackpad / air-mouse surface. Surfaces the SAME Controls-tab settings (same
 * pref keys — one source of truth) filtered to the active mode, plus a back
 * button that returns to the interface the user came from.
 */
@Composable
fun ControlsSubpage(
    isAirMouse: Boolean,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val prefs = PortalPadApp.instance.prefs
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxSize()
            .background(AbBackground),
    ) {
        // Scrollable content. Bottom padding leaves room for the pinned Back
        // button so the last setting isn't hidden behind it. A thin scrollbar
        // is painted on the right while there's overflow.
        Column(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    val max = scrollState.maxValue
                    if (max > 0) {
                        val viewH = size.height
                        val total = viewH + max
                        val thumbH = (viewH * (viewH / total)).coerceAtLeast(40f)
                        val track = viewH - thumbH
                        val y = track * (scrollState.value.toFloat() / max)
                        drawRoundRect(
                            color = AbOnSurfaceMuted.copy(alpha = 0.5f),
                            topLeft = androidx.compose.ui.geometry.Offset(size.width - 6f, y),
                            size = androidx.compose.ui.geometry.Size(4f, thumbH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
                        )
                    }
                }
                .verticalScroll(scrollState)
                .padding(16.dp)
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (isAirMouse) "Air Mouse controls" else "Trackpad controls",
                color = AbAccent,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            if (isAirMouse) {
                SectionCard(title = "Air Mouse") {
                    SpeedSlider(
                        label = "Sensitivity",
                        desc = "How far the cursor moves per degree you tilt the glasses. Higher = faster.",
                        key = PreferencesRepository.Keys.AIR_MOUSE_SENSITIVITY,
                        default = 20f,
                        range = 2f..20f,
                    )
                    SpeedSlider(
                        label = "Smoothing",
                        desc = "Filters sensor jitter for steadier motion. Higher = smoother but slightly laggier; lower = snappier but jitterier.",
                        key = PreferencesRepository.Keys.AIR_MOUSE_SMOOTHING,
                        default = 0.3f,
                        range = 0f..0.9f,
                    )
                    SpeedSlider(
                        label = "Acceleration",
                        desc = "How much the cursor speeds up when you sweep the glasses faster — slow aim stays precise, quick sweeps cover more screen. Set to 0 for a constant speed.",
                        key = PreferencesRepository.Keys.AIR_MOUSE_ACCEL,
                        default = 1.0f,
                        range = 0f..2f,
                    )
                    ToggleRowWithDesc(
                        label = "Invert horizontal direction",
                        desc = "Flip left/right if swinging the glasses moves the cursor the wrong way.",
                        key = PreferencesRepository.Keys.AIR_MOUSE_INVERT_X,
                        default = false,
                    )
                    ToggleRowWithDesc(
                        label = "Invert vertical direction",
                        desc = "Flip up/down if tilting the glasses moves the cursor the wrong way.",
                        key = PreferencesRepository.Keys.AIR_MOUSE_INVERT_Y,
                        default = false,
                    )
                }
                // Air Mouse still uses the trackpad surface for taps/scroll, so
                // it gets the same surface controls (same order as Trackpad).
                SectionCard(title = "Trackpad surface") {
                    TrackpadSurfaceControls()
                }
            } else {
                SectionCard(title = "Trackpad") {
                    TrackpadSurfaceControls()
                }
            }

        // 3-finger swipe gestures — apply to both trackpad and air mouse.
        SectionCard(title = "Three-Finger Swipes") {
            Text(
                "Assign an action to each 3-finger swipe direction. Swipe with three fingers on the surface to trigger it.",
                color = AbOnSurfaceMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            val gUp by prefs.gestureSwipeUp.collectAsState(initial = com.portalpad.app.data.GestureAction.NOTIF_CLOSE)
            val gDown by prefs.gestureSwipeDown.collectAsState(initial = com.portalpad.app.data.GestureAction.NOTIF_OPEN)
            val gLeft by prefs.gestureSwipeLeft.collectAsState(initial = com.portalpad.app.data.GestureAction.HOME)
            val gRight by prefs.gestureSwipeRight.collectAsState(initial = com.portalpad.app.data.GestureAction.BACK)
            val gUpApp by prefs.gestureAppUp.collectAsState(initial = null)
            val gDownApp by prefs.gestureAppDown.collectAsState(initial = null)
            val gLeftApp by prefs.gestureAppLeft.collectAsState(initial = null)
            val gRightApp by prefs.gestureAppRight.collectAsState(initial = null)
            GestureActionRow("Swipe up", gUp, "gesture_up", gUpApp) { scope.launch { prefs.setGestureSwipeUp(it) } }
            GestureActionRow("Swipe down", gDown, "gesture_down", gDownApp) { scope.launch { prefs.setGestureSwipeDown(it) } }
            GestureActionRow("Swipe left", gLeft, "gesture_left", gLeftApp) { scope.launch { prefs.setGestureSwipeLeft(it) } }
            GestureActionRow("Swipe right", gRight, "gesture_right", gRightApp) { scope.launch { prefs.setGestureSwipeRight(it) } }
        }

        // Cursor settings apply to both modes (the cursor shows in both).
        SectionCard(title = "Cursor") {
            ToggleRowWithDesc(
                label = "Custom cursor on external display",
                desc = "Render an amber cursor on the external display at the current pointer location. Useful when the OEM doesn't render a system cursor for injected mouse events.",
                key = PreferencesRepository.Keys.ENABLE_MOUSE_HOVER,
                default = true,
            )
            MediaCursorHideSection()
        }
        }

        // Pinned Back button — always visible at the bottom (with a small gap
        // above the edge), full width of the cards, taller tap target. Returns
        // to the interface the user came from.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AbSurfaceElevated)
                .border(1.dp, AbAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .clickable { onBack() }
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
    }
}

@Composable
private fun CompactDpiField(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = if (enabled) AbOnSurface else AbOnSurfaceMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(AbAccent),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        ),
        modifier = Modifier.width(64.dp),
        decorationBox = { inner ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        1.dp,
                        AbOnSurfaceMuted.copy(alpha = if (enabled) 0.5f else 0.25f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) { inner() }
        },
    )
}

/**
 * Display subpage — opened from the display-info pill on the trackpad / remote /
 * air-mouse interface. Holds external-display tuning: aspect ratio now, with
 * color tuning to follow. Full-screen overlay with a pinned "← Back" at the
 * bottom (same position/style as the button-actions page's Back).
 */
@Composable
fun DisplaySubpage(onBack: () -> Unit) {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val aspect by app.prefs.aspectRatio.collectAsState(initial = "AUTO")
    val dpi by app.prefs.displayDpi.collectAsState(initial = 0)
    val screenSizePct by app.prefs.screenSizePct.collectAsState(initial = 100)
    val context = androidx.compose.ui.platform.LocalContext.current
    val externalDisplayId by app.externalDisplayId.collectAsState()
    val physicalExternalDisplayId by app.physicalExternalDisplayId.collectAsState()
    // Detected external-display width (for the desktop/mobile DPI threshold hint).
    val externalWidthPx = remember(externalDisplayId, physicalExternalDisplayId) {
        val id = physicalExternalDisplayId ?: externalDisplayId
        id?.let {
            val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            dm.getDisplay(it)?.let { d ->
                android.util.DisplayMetrics().also { m -> d.getRealMetrics(m) }.widthPixels
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(AbBackground),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Display",
                color = AbAccent,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            SectionCard(title = "Display pipeline") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("System mirror (experimental)", color = AbOnSurface)
                        Text(
                            "Carries the external display's picture through the system compositor instead of an app-overlay window, so it no longer goes black when a USB or permission dialog appears, and some DRM video plays that otherwise wouldn't (like Netflix or Prime Video, though other streaming apps may still not work).",
                            color = AbOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Trade-off: color tuning, screen size, Side Mode and the performance overlay's frame-rate stats aren't available in this mode.\nTurn OFF for full color and all display controls.",
                            color = AbOnSurfaceDim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = app.prefs.panelSystemMirror.collectAsState(initial = false).value,
                        onCheckedChange = { scope.launch { app.prefs.setPanelSystemMirror(it) } },
                    )
                }
            }
            SectionCard(title = "Display DPI") {
                // In an app-owned session with Shizuku stopped, DPI can't be
                // reconfigured (reconfigure needs the shell identity), so flag it
                // rather than let the slider look silently broken. Screen Size
                // still works without Shizuku (it's app-side GL scaling).
                val appOwnedSession by app.sessionAppOwned.collectAsState(initial = false)
                val shizukuStatus by app.shizuku.status.collectAsState(
                    initial = com.portalpad.app.shizuku.ShizukuManager.Status.NOT_RUNNING,
                )
                if (appOwnedSession &&
                    shizukuStatus == com.portalpad.app.shizuku.ShizukuManager.Status.NOT_RUNNING
                ) {
                    Text(
                        "DPI changes need Shizuku connected — restart Shizuku to adjust it. " +
                            "Screen Size below works without it.",
                        color = AbAccent,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                val connected = externalDisplayId != null || physicalExternalDisplayId != null
                val dpiAlpha = if (connected) 1f else 0.4f
                // Detected density of the external display — the Auto value and the
                // manual-field starting value. 213 fallback if implausible.
                val detectedDpi = remember(externalDisplayId, physicalExternalDisplayId) {
                    runCatching {
                        val id = physicalExternalDisplayId ?: externalDisplayId ?: return@runCatching 213
                        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                        val d = dm.getDisplay(id) ?: return@runCatching 213
                        val m = android.util.DisplayMetrics().also { d.getRealMetrics(it) }
                        m.densityDpi.takeIf { it in 120..480 } ?: 213
                    }.getOrDefault(213)
                }
                val sliderValue = (if (dpi == 0) detectedDpi else dpi).toFloat().coerceIn(100f, 640f)

                var dpiText by remember(dpi, detectedDpi, connected) {
                    mutableStateOf(
                        when {
                            dpi != 0 -> dpi.toString()
                            connected -> detectedDpi.toString()
                            else -> "Auto"
                        }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (dpi == 0) "Display DPI (Auto):" else "Display DPI:",
                        color = AbOnSurfaceMuted.copy(alpha = dpiAlpha),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    CompactDpiField(value = dpiText, enabled = connected) { raw ->
                        val digits = raw.filter { it.isDigit() }.take(3)
                        dpiText = digits
                        digits.toIntOrNull()?.let { v ->
                            if (v in 80..640) scope.launch { app.prefs.setDisplayDpi(v) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Reset to Auto",
                        color = AbAccent.copy(alpha = dpiAlpha),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = connected) { scope.launch { app.prefs.setDisplayDpi(0) } }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Text(
                    if (connected) "Auto uses the display's detected density ($detectedDpi)."
                    else "Connect a display to adjust.",
                    color = AbOnSurfaceMuted.copy(alpha = dpiAlpha),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = sliderValue,
                    onValueChange = { scope.launch { app.prefs.setDisplayDpi(it.toInt()) } },
                    valueRange = 100f..640f,
                    enabled = connected,
                    colors = sliderColors(),
                )
                val widthForCalc = externalWidthPx ?: 1920
                val thresholdDpi = ((widthForCalc / 1280f) * 160f).toInt().coerceIn(161, 639)
                Text(
                    "Controls UI scale on the external display (not the dock or top bar).\n" +
                        "Adjusts live as you change it — you may need to reopen an app to see it fully update. Saves automatically.\n" +
                        "\n" +
                        "• Lower DPI (100 - $thresholdDpi) = bigger desktop-style layouts (Chrome shows a tab strip).\n" +
                        "• Higher DPI (${thresholdDpi + 1} - 640) = mobile-style apps with smaller UI.\n",
                    color = AbOnSurfaceMuted.copy(alpha = dpiAlpha),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard(title = "Aspect ratio") {
                Text(
                    "Sets the shape of the external display. Auto uses the external display's native resolution. Other ratios fit inside the panel — the unused area shows as black bars.",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                val options = listOf(
                    "AUTO" to "Auto (Detected)",
                    "4:3" to "4:3",
                    "16:9" to "16:9",
                    "16:10" to "16:10",
                    "21:9" to "21:9 (ultrawide)",
                    "21:10" to "21:10 (ultrawide)",
                    "32:9" to "32:9 (super ultrawide)",
                    "32:10" to "32:10 (super ultrawide)",
                )
                val currentValue = if (aspect.isBlank()) "AUTO" else aspect
                val currentLabel = options.firstOrNull { it.first == currentValue }?.second
                    ?: "Auto (Detected)"
                var expanded by remember { mutableStateOf(false) }
                var anchorWidthPx by remember { mutableIntStateOf(0) }
                val density = androidx.compose.ui.platform.LocalDensity.current
                // Plain Row + DropdownMenu (the codebase's robust pattern; the
                // ExposedDropdownMenu* APIs are fiddly across material3 versions).
                // The menu is given the anchor's measured width so it lines up
                // under the box instead of opening as a narrow detached popup.
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { anchorWidthPx = it.width }
                            .clip(RoundedCornerShape(12.dp))
                            .background(AbSurfaceElevated)
                            .border(1.dp, AbOnSurfaceDim.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .clickable { expanded = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            currentLabel,
                            color = AbOnSurface,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Choose aspect ratio",
                            tint = AbOnSurfaceMuted,
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier
                            .background(AbSurfaceElevated)
                            .width(with(density) { anchorWidthPx.toDp() }),
                    ) {
                        options.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        label,
                                        color = if (value == currentValue) AbAccent else AbOnSurface,
                                        fontWeight = if (value == currentValue) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    scope.launch { app.prefs.setAspectRatio(value) }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Changes apply right away on the connected external display \u2014 no need to reconnect.",
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard(title = "Screen size", disabled = app.prefs.panelSystemMirror.collectAsState(initial = false).value) {
                val connected = externalDisplayId != null || physicalExternalDisplayId != null
                val sizeAlpha = if (connected) 1f else 0.4f
                val sizeValue = screenSizePct.toFloat().coerceIn(50f, 100f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Screen size: $screenSizePct%",
                        color = AbOnSurfaceMuted.copy(alpha = sizeAlpha),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Reset to 100%",
                        color = AbAccent.copy(alpha = sizeAlpha),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = connected) { scope.launch { app.prefs.setScreenSizePct(100) } }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Drives the pref directly (like the DPI slider); the service
                // debounces the actual VD resize so dragging doesn't thrash it.
                Slider(
                    value = sizeValue,
                    onValueChange = { scope.launch { app.prefs.setScreenSizePct(it.toInt()) } },
                    valueRange = 50f..100f,
                    enabled = connected,
                    colors = sliderColors(),
                )
                Text(
                    if (connected)
                        "Shrinks the whole virtual screen within the panel \u2014 the unused area shows as black bars, " +
                            "so the screen gets smaller in view while the layout and sharpness stay the same. " +
                            "100% = full panel. Adjusts live; saves automatically."
                    else "Connect a display to adjust.",
                    color = AbOnSurfaceMuted.copy(alpha = sizeAlpha),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Side Mode — per-interface corner dock. Auto-scoped to the live input
            // interface (Air Mouse / Trackpad / Remote): the toggle, chosen corner,
            // and each corner's size are saved separately for whichever interface is
            // active. Screen size above stays the (centered) control; Side Mode owns
            // the corner sizes.
            run {
                val sideCfg by app.prefs.sideModeConfig
                    .collectAsState(initial = com.portalpad.app.data.SideModeConfig())
                val activeIface by app.prefs.activeSideInterface
                    .collectAsState(initial = com.portalpad.app.data.SideInterface.TRACKPAD)
                val si = sideCfg.forInterface(activeIface)
                val connected = externalDisplayId != null || physicalExternalDisplayId != null
                val ifaceName = when (activeIface) {
                    com.portalpad.app.data.SideInterface.AIR_MOUSE -> "Air Mouse"
                    com.portalpad.app.data.SideInterface.TRACKPAD -> "Trackpad"
                    com.portalpad.app.data.SideInterface.REMOTE -> "Remote"
                }
                val cornerName = when (si.corner) {
                    com.portalpad.app.data.SideCorner.TL -> "top-left"
                    com.portalpad.app.data.SideCorner.TR -> "top-right"
                    com.portalpad.app.data.SideCorner.BL -> "bottom-left"
                    com.portalpad.app.data.SideCorner.BR -> "bottom-right"
                }
                SectionCard(title = "Side Mode", disabled = app.prefs.panelSystemMirror.collectAsState(initial = false).value) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Dock to a corner — $ifaceName",
                                color = AbOnSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Saved separately for each input interface.",
                                color = AbOnSurfaceMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = si.enabled,
                            enabled = connected,
                            onCheckedChange = { on ->
                                scope.launch {
                                    app.prefs.setSideModeConfig(
                                        sideCfg.withInterface(activeIface, si.copy(enabled = on)),
                                    )
                                }
                            },
                        )
                    }
                    if (si.enabled && connected) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Corner",
                            color = AbOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                com.portalpad.app.data.SideCorner.TL,
                                com.portalpad.app.data.SideCorner.TR,
                                com.portalpad.app.data.SideCorner.BL,
                                com.portalpad.app.data.SideCorner.BR,
                            ).forEach { corner ->
                                val selected = si.corner == corner
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (selected) AbAccent.copy(alpha = 0.22f)
                                            else Color.White.copy(alpha = 0.06f),
                                        )
                                        .border(
                                            1.dp,
                                            if (selected) AbAccent else AbOnSurfaceMuted.copy(alpha = 0.3f),
                                            RoundedCornerShape(10.dp),
                                        )
                                        .clickable {
                                            scope.launch {
                                                app.prefs.setSideModeConfig(
                                                    sideCfg.withInterface(activeIface, si.copy(corner = corner)),
                                                )
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    SideCornerGlyph(corner = corner, selected = selected)
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        val cornerSize = si.sizeFor(si.corner)
                        Text(
                            "Corner size: $cornerSize%",
                            color = AbOnSurfaceMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = cornerSize.toFloat().coerceIn(40f, 90f),
                            onValueChange = { v ->
                                scope.launch {
                                    app.prefs.setSideModeConfig(
                                        sideCfg.withInterface(activeIface, si.withSize(si.corner, v.toInt())),
                                    )
                                }
                            },
                            valueRange = 40f..90f,
                            colors = sliderColors(),
                        )
                        Text(
                            "Docks $ifaceName's screen into the $cornerName corner at this size. " +
                                "Switching interface restores that interface\u2019s own corner and size. " +
                                "Adjusts live; saves automatically.",
                            color = AbOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (connected)
                                "Off \u2014 screen stays centered at the Screen size above. Turn on to dock it into a corner."
                            else "Connect a display to use Side Mode.",
                            color = AbOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Color tuning — full control set grouped into collapsible sections,
            // applied to the glasses via the GPU pipeline. Values persist and are
            // re-applied on reconnect. Greyed/disabled when the pipeline is off.
            run {
                val gpuOn by app.prefs.gpuColorPipeline.collectAsState(initial = true)
                val savedTuning by app.prefs.colorTuning.collectAsState(initial = "")
                val initial = remember(savedTuning) { com.portalpad.app.presentation.GlColorRenderer.parseValues(savedTuning) }
                // 14 values: 0 brightness,1 contrast,2 saturation,3 temp,4 tint,
                // 5 gamma,6 blackLevel,7 whitePoint,8 rGain,9 gGain,10 bGain,
                // 11 rOffset,12 gOffset,13 bOffset.
                val vals = remember(savedTuning) {
                    mutableStateListOf<Float>().apply { initial.forEach { add(it) } }
                }
                var live by remember { mutableStateOf(true) }
                var status by remember { mutableStateOf("Adjust to tune the external display. Settings are saved and re-applied on reconnect.") }

                fun matrix() = com.portalpad.app.presentation.GlColorRenderer.matrixFromValues(vals.toFloatArray())
                fun gamma() = vals[5]
                fun persist() {
                    scope.launch { app.prefs.setColorTuning(vals.joinToString(",")) }
                }
                fun apply() {
                    scope.launch {
                        val mtx = matrix(); val g = gamma()
                        val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val svc = com.portalpad.app.service.PortalPadForegroundService.instance
                            if (svc == null) "FAIL: service not running."
                            else runCatching { svc.applyGlassesColorMatrix(mtx, g) }.getOrElse { "call failed: ${it.message}" }
                        }
                        status = out
                    }
                }
                fun maybeLive() { if (live && gpuOn) { apply(); persist() } }

                SectionCard(title = "Color tuning", disabled = app.prefs.panelSystemMirror.collectAsState(initial = false).value) {
                    Text(
                        "Tunes the external display color (not the phone), via a GPU color pass. Settings are saved and re-applied automatically when you reconnect the external display.",
                        color = AbOnSurfaceMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("GPU color pipeline", color = AbOnSurface)
                            Text(
                                "Required for color tuning. Turn off if the display stutters on slower devices (disables color). Applies on reconnect.",
                                color = AbOnSurfaceMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = gpuOn,
                            onCheckedChange = { scope.launch { app.prefs.setGpuColorPipeline(it) } },
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    val dim = if (gpuOn) 1f else 0.4f

                    // Live preview — placed right under the GPU pipeline toggle (it
                    // depends on it: disabled when the pipeline is off) so the user
                    // sets how changes apply before touching the sliders below.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Live preview", color = AbOnSurface.copy(alpha = dim))
                            Text(
                                "Preview changes on the external display as you adjust. Tap Apply (near the bottom) to save them.",
                                color = AbOnSurfaceMuted.copy(alpha = dim),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = live, enabled = gpuOn,
                            onCheckedChange = { live = it },
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    // One slider bound to vals[index].
                    val tuneSlider: @Composable (String, Int, ClosedFloatingPointRange<Float>) -> Unit =
                        { label, index, range ->
                            Text(
                                label,
                                color = AbOnSurface.copy(alpha = dim),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            androidx.compose.material3.Slider(
                                value = vals[index].coerceIn(range.start, range.endInclusive),
                                onValueChange = { vals[index] = it; maybeLive() },
                                valueRange = range,
                                enabled = gpuOn,
                                colors = sliderColors(),
                            )
                        }

                    // Collapsible section header with a chevron that flips up when
                    // expanded, down when collapsed. Tap toggles. Defaults: Basic
                    // open, the rest collapsed.
                    val section: @Composable (String, Boolean, @Composable () -> Unit) -> Unit =
                        { title, defaultOpen, content ->
                            var open by remember { mutableStateOf(defaultOpen) }
                            val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { open = !open }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    title,
                                    color = AbAccent.copy(alpha = dim),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.Default.ExpandMore,
                                    contentDescription = if (open) "Collapse" else "Expand",
                                    tint = AbOnSurfaceMuted.copy(alpha = dim),
                                    modifier = Modifier.rotate(rot),
                                )
                            }
                            if (open) {
                                Column(Modifier.padding(start = 4.dp, bottom = 4.dp)) { content() }
                            }
                            HorizontalDivider(color = AbSurfaceElevated, thickness = 1.dp)
                        }

                    section("Basic", true) {
                        tuneSlider("Brightness", 0, 0.5f..1.5f)
                        tuneSlider("Contrast", 1, 0.5f..1.5f)
                        tuneSlider("Saturation", 2, 0f..2f)
                    }
                    section("White balance", false) {
                        tuneSlider("Temperature (warm ↔ cool)", 3, -0.3f..0.3f)
                        tuneSlider("Tint (green ↔ magenta)", 4, -0.3f..0.3f)
                    }
                    section("Tone", false) {
                        tuneSlider("Gamma", 5, 0.5f..2.2f)
                        tuneSlider("Black level", 6, -0.2f..0.2f)
                        tuneSlider("White point", 7, 0.8f..1.2f)
                    }
                    section("Channels", false) {
                        tuneSlider("Red gain", 8, 0.5f..1.5f)
                        tuneSlider("Green gain", 9, 0.5f..1.5f)
                        tuneSlider("Blue gain", 10, 0.5f..1.5f)
                        tuneSlider("Red offset", 11, -0.2f..0.2f)
                        tuneSlider("Green offset", 12, -0.2f..0.2f)
                        tuneSlider("Blue offset", 13, -0.2f..0.2f)
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val btn: @Composable RowScope.(String, () -> Unit) -> Unit = { label, onTap ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AbSurfaceElevated.copy(alpha = dim))
                                    .border(1.dp, AbAccent.copy(alpha = 0.45f * dim), RoundedCornerShape(12.dp))
                                    .clickable(enabled = gpuOn) { onTap() }
                                    .padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(label, color = AbOnSurface.copy(alpha = dim), fontWeight = FontWeight.SemiBold) }
                        }
                        btn("Apply") { apply(); persist() }
                        btn("Reset") {
                            val d = com.portalpad.app.presentation.GlColorRenderer.DEFAULTS
                            for (i in vals.indices) vals[i] = d[i]
                            apply()
                            scope.launch { app.prefs.setColorTuning("") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            PerformanceOverlaySection(app, scope, mirrorOn = app.prefs.panelSystemMirror.collectAsState(initial = false).value)

            // Page-wide reset: return all Display *appearance* settings to their
            // defaults in one tap — DPI (Auto), aspect (AUTO), screen size (100%),
            // Side Mode, color tuning + GPU pipeline. Intentionally does NOT touch
            // the System-mirror mode toggle or the Performance-overlay rows (those
            // are modes/diagnostics, not appearance).
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AbSurfaceElevated)
                    .border(1.dp, AbAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .clickable {
                        scope.launch {
                            app.prefs.setDisplayDpi(0)
                            app.prefs.setAspectRatio("AUTO")
                            app.prefs.setScreenSizePct(100)
                            app.prefs.setSideModeConfig(com.portalpad.app.data.SideModeConfig())
                            app.prefs.setColorTuning("")
                            app.prefs.setGpuColorPipeline(true)
                        }
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Reset all display settings",
                    color = AbOnSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Pinned "← Back" — same position/style as the button-actions page.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AbSurfaceElevated)
                .border(1.dp, AbAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .clickable { onBack() }
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
    }
}

@Composable
private fun PerformanceOverlaySection(
    app: PortalPadApp,
    scope: kotlinx.coroutines.CoroutineScope,
    mirrorOn: Boolean = false,
) {
    val enabled by app.prefs.bool(PreferencesRepository.Keys.HUD_ENABLED, false)
        .collectAsState(initial = false)
    val corner by app.prefs.string(PreferencesRepository.Keys.HUD_CORNER, "top_right")
        .collectAsState(initial = "top_right")
    val showFps by app.prefs.bool(PreferencesRepository.Keys.HUD_SHOW_FPS, true)
        .collectAsState(initial = true)
    val showFrameTime by app.prefs.bool(PreferencesRepository.Keys.HUD_SHOW_FRAMETIME, true)
        .collectAsState(initial = true)
    val showMode by app.prefs.bool(PreferencesRepository.Keys.HUD_SHOW_MODE, true)
        .collectAsState(initial = true)
    val showDropped by app.prefs.bool(PreferencesRepository.Keys.HUD_SHOW_DROPPED, true)
        .collectAsState(initial = true)

    SectionCard(title = "Performance overlay") {
        Text(
            "A small FPS / frame-time readout drawn on the external display, for spotting " +
                "stutter. Delivered-fps is measured from the GL pipeline; it reads 0 if the " +
                "GPU color pipeline is off.",
            color = AbOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (mirrorOn) {
            Text(
                "System mirror is on: the overlay shows the resolution and refresh rate. " +
                    "Frame-rate stats (fps, draw time, frames behind) need overlay mode.",
                color = AbOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Show overlay",
                color = AbOnSurface,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = { v ->
                    scope.launch { app.prefs.setBool(PreferencesRepository.Keys.HUD_ENABLED, v) }
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = AbPrimary,
                    checkedThumbColor = AbPrimaryBright,
                    uncheckedTrackColor = AbSurfaceElevated,
                    uncheckedThumbColor = AbOnSurfaceDim,
                ),
            )
        }
        if (enabled) {
            Text("Position", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            val corners = listOf(
                "top_left" to "Top-left",
                "top_right" to "Top-right",
                "bottom_left" to "Bottom-left",
                "bottom_right" to "Bottom-right",
            )
            val currentCornerLabel = corners.firstOrNull { it.first == corner }?.second ?: "Top-right"
            var cornerMenuOpen by remember { mutableStateOf(false) }
            var cornerAnchorWidthPx by remember { mutableIntStateOf(0) }
            val cornerDensity = androidx.compose.ui.platform.LocalDensity.current
            // Plain Row + DropdownMenu (the codebase's robust pattern; see the aspect-
            // ratio selector above). Menu uses the anchor's measured width so it lines up.
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { cornerAnchorWidthPx = it.width }
                        .clip(RoundedCornerShape(12.dp))
                        .background(AbSurfaceElevated)
                        .border(1.dp, AbOnSurfaceDim.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .clickable { cornerMenuOpen = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        currentCornerLabel,
                        color = AbOnSurface,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = "Choose overlay position",
                        tint = AbOnSurfaceMuted,
                    )
                }
                DropdownMenu(
                    expanded = cornerMenuOpen,
                    onDismissRequest = { cornerMenuOpen = false },
                    modifier = Modifier
                        .background(AbSurfaceElevated)
                        .width(with(cornerDensity) { cornerAnchorWidthPx.toDp() }),
                ) {
                    corners.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    color = if (value == corner) AbPrimaryBright else AbOnSurface,
                                    fontWeight = if (value == corner) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                cornerMenuOpen = false
                                scope.launch { app.prefs.setString(PreferencesRepository.Keys.HUD_CORNER, value) }
                            },
                        )
                    }
                }
            }
            Text("Show", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            HudShowRow("Active mode (resolution · Hz)", showMode) { v ->
                scope.launch { app.prefs.setBool(PreferencesRepository.Keys.HUD_SHOW_MODE, v) }
            }
            HudShowRow("FPS (delivered)", showFps) { v ->
                scope.launch { app.prefs.setBool(PreferencesRepository.Keys.HUD_SHOW_FPS, v) }
            }
            HudShowRow("Frame time (GL draw+swap)", showFrameTime) { v ->
                scope.launch { app.prefs.setBool(PreferencesRepository.Keys.HUD_SHOW_FRAMETIME, v) }
            }
            HudShowRow("Dropped / behind vsync", showDropped) { v ->
                scope.launch { app.prefs.setBool(PreferencesRepository.Keys.HUD_SHOW_DROPPED, v) }
            }
        }
    }
}

@Composable
private fun HudShowRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = AbOnSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = AbPrimary,
                checkedThumbColor = AbPrimaryBright,
                uncheckedTrackColor = AbSurfaceElevated,
                uncheckedThumbColor = AbOnSurfaceDim,
            ),
        )
    }
}

/**
 * A labelled slider for a Float preference. Drags update local state for
 * smooth feedback; the value is committed to prefs on release
 * (onValueChangeFinished) so we don't write to DataStore on every pixel.
 */
@Composable
private fun SpeedSlider(
    label: String,
    desc: String,
    key: androidx.datastore.preferences.core.Preferences.Key<Float>,
    default: Float,
    range: ClosedFloatingPointRange<Float>,
) {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val stored by app.prefs.float(key, default).collectAsState(initial = default)
    var local by remember(stored) { mutableFloatStateOf(stored) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(label, color = AbOnSurface, style = MaterialTheme.typography.bodyLarge)
                Text(desc, color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                Text(
                    String.format("Default: %.1f×", default),
                    color = AbOnSurfaceDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                String.format("%.1f×", local),
                color = AbPrimaryBright,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { scope.launch { app.prefs.setFloat(key, local) } },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = AbPrimaryBright,
                activeTrackColor = AbPrimary,
                inactiveTrackColor = AbSurfaceElevated,
            ),
        )
    }
}

@Composable
private fun WorkspaceTab(
    onPickDock: () -> Unit,
    onOpenWallpaperPicker: () -> Unit = {},
    onOpenFolderManager: () -> Unit = {},
    onOpenDockCustomization: () -> Unit = {},
    onOpenTopBarCustomization: () -> Unit = {},
    onOpenFolderWindowCustomization: () -> Unit = {},
    scrollToTopSignal: Int = 0,
) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val dockCfg by prefs.dockConfig.collectAsState(initial = DockConfig())
    val topBarCfg by prefs.topBarConfig.collectAsState(initial = com.portalpad.app.data.TopBarConfig())
    val dockEnabled by prefs.bool(PreferencesRepository.Keys.DOCK_ENABLED, default = true)
        .collectAsState(initial = true)
    val wallpaperCanvasUltrawide = remember {
        runCatching {
            val dm = app.getSystemService(android.hardware.display.DisplayManager::class.java)
            val ext = dm?.displays?.firstOrNull { com.portalpad.app.DisplayUtil.isRealExternalDisplay(it) }
            if (ext != null) {
                val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") ext.getRealMetrics(it) }
                com.portalpad.app.data.Wallpaper.isUltrawideCanvas(m.widthPixels, m.heightPixels)
            } else false
        }.getOrDefault(false)
    }
    val currentWallpaper by prefs.homeWallpaperFor(wallpaperCanvasUltrawide)
        .collectAsState(initial = com.portalpad.app.data.Wallpaper.effectiveDefault(wallpaperCanvasUltrawide))

    TabBody(scrollToTopSignal = scrollToTopSignal) {
        // Home backdrop — opens a separate picker page (kept off this page so it
        // doesn't crowd the Workspace). Shows the current selection as a subtitle.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, AbSurfaceElevated, RoundedCornerShape(16.dp))
                .clickable { onOpenWallpaperPicker() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Wallpaper, contentDescription = null, tint = AbAccent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Home backdrop", color = AbOnSurface, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "External display wallpaper — ${currentWallpaper.label}",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Open home backdrop picker", tint = AbOnSurfaceMuted)
        }

        SectionCard(title = "Desktop windows (beta)", tightSpacing = true) {
            Text(
                "Open apps in resizable, movable windows on the external display, with a taskbar at the bottom and window controls at the top edge — a Samsung DeX-style desktop. Requires Shizuku or Root. Experimental: some apps may not behave well in windowed mode.",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            ToggleRowWithDesc(
                label = "Enable desktop windows",
                desc = "Turns on freeform windowing, the bottom taskbar, and the top window-control bar (minimize, arrange evenly, maximize, restore, close). Move the cursor to the top edge to reveal window controls.",
                key = PreferencesRepository.Keys.DESKTOP_MODE_ENABLED,
                default = false,
                requirement = Requirement.ROOT_OR_SHIZUKU,
                confirmMessage = "This closes any open windows on the external display. " +
                    "Reopen apps from the dock afterward — they'll open as windows when " +
                    "desktop windows is on.",
            )
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("Note:") }
                    append(" Each floating desktop window has a system tab bar at the top, but its split button opens the split on the phone, not the external display (an Android limitation). Instead, hover to the top of the display — top-center by default, or wherever you've set the top bar — to reveal PortalPad's top bar, then tap the grid icon to tile all windows evenly across the external display.")
                },
                color = AbDanger,
                style = MaterialTheme.typography.bodySmall,
            )
            ToggleRowWithDesc(
                label = "Open dock apps in a window",
                desc = "When desktop windows is on, tapping a dock app opens it as a freeform window instead of fullscreen.",
                key = PreferencesRepository.Keys.DOCK_OPENS_FREEFORM,
                default = true,
            )
            ToggleRowWithDesc(
                label = "Handle permission prompts on the phone",
                desc = "When an app opened on the external display asks for a permission (notifications, camera, etc.), Android shows the prompt there as an unusable black window. With this on, PortalPad closes the app, shows an Allow/Deny popup on the trackpad screen, applies your choices via Shizuku or Root, and reopens the app on the external display. Turn off to leave the system prompt as-is.",
                key = PreferencesRepository.Keys.PERM_DIALOG_KEEP_ON_EXTERNAL,
                default = true,
                requirement = Requirement.ROOT_OR_SHIZUKU,
            )
            val maxWin by prefs.int(PreferencesRepository.Keys.MAX_WINDOWS, default = 5)
                .collectAsState(initial = 5)
            Text(
                "Maximum open windows: $maxWin",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Limits how many windows can be open at once for performance. At the limit, you'll be asked to close one before opening another.",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = maxWin.toFloat(),
                onValueChange = { n ->
                    scope.launch { prefs.setInt(PreferencesRepository.Keys.MAX_WINDOWS, n.toInt()) }
                },
                valueRange = 3f..6f,
                steps = 2,
                colors = sliderColors(),
            )
        }
        SectionCard(title = "Dock") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Show dock on external display",
                    color = AbOnSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = dockEnabled,
                    onCheckedChange = {
                        scope.launch {
                            prefs.setBool(PreferencesRepository.Keys.DOCK_ENABLED, it)
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
        SectionCard(title = "Dock contents") {
            ActionRow(
                label = "Manage dock apps (multi-select)",
                value = "${dockCfg.items.size} items",
                onClick = onPickDock,
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                label = "Manage dock folders",
                value = "${dockCfg.items.count { it is com.portalpad.app.data.DockItem.Folder }} folders",
                onClick = onOpenFolderManager,
            )
        }
        SectionCard(title = "External Display Appearance") {
            Text(
                "Customize the look and behavior of the dock, the top window bar, and folder windows shown on the external display.",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                label = "Dock Customization",
                value = "Layout & style",
                onClick = onOpenDockCustomization,
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                label = "Top Bar Customization",
                value = "Position & style",
                onClick = onOpenTopBarCustomization,
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                label = "Folder Window Customization",
                value = "Background & labels",
                onClick = onOpenFolderWindowCustomization,
            )
        }
        SectionCard(title = "App search") {
            ToggleRowWithDesc(
                label = "Use phone keyboard for dock search",
                desc = "When on, the dock app-search opens the phone keyboard relay so you type on your phone (reliable). When off, you type directly on the external-display search field. Voice search works either way.",
                key = PreferencesRepository.Keys.SEARCH_KEYBOARD_ON_PHONE,
                default = true,
            )
        }
        var showResetConfirm by remember { mutableStateOf(false) }
        ResetToDefaultsButton("Reset Workspace to Defaults") { showResetConfirm = true }
        if (showResetConfirm) {
            ResetConfirmDialog(
                title = "Reset Workspace to defaults?",
                message = "This resets the dock layout & style, top bar, folder windows, app-search, and wallpaper to their defaults. Your dock apps and folders are kept.",
                onConfirm = {
                    showResetConfirm = false
                    scope.launch {
                        // Reset dock appearance/layout/behavior but PRESERVE items + folders.
                        val keptItems = dockCfg.items
                        prefs.setDockConfig(DockConfig().copy(items = keptItems))
                        prefs.setTopBarConfig(com.portalpad.app.data.TopBarConfig())
                        prefs.setFolderWindowConfig(com.portalpad.app.data.FolderWindowConfig())
                        prefs.setBool(PreferencesRepository.Keys.DOCK_ENABLED, true)
                        prefs.setBool(PreferencesRepository.Keys.SEARCH_KEYBOARD_ON_PHONE, true)
                        prefs.resetHomeWallpapers()
                    }
                },
                onCancel = { showResetConfirm = false },
            )
        }
    }
}

/** Dock Customization sub-page: layout + style, with a pinned bottom Back. */
@Composable
private fun DockCustomizationPage(onBack: () -> Unit) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val dockCfg by prefs.dockConfig.collectAsState(initial = DockConfig())
    val externalDisplayId by app.externalDisplayId.collectAsState()

    // Preview isolation: pin the dock visible on the external display while this page is
    // open (and the top bar's zone highlight stays off), so edits preview cleanly.
    androidx.compose.runtime.DisposableEffect(Unit) {
        app.setDockCustomizationActive(true)
        onDispose { app.setDockCustomizationActive(false) }
    }

    Box(Modifier.fillMaxSize().background(AbBackground)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Dock Customization",
                color = AbOnSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            // External-display preview status hint (mirrors the folder-window page).
            Text(
                if (externalDisplayId == null)
                    "Connect your external display to preview the dock there."
                else
                    "Previewing your dock on the external display — adjust the style below to see it update live.",
                color = if (externalDisplayId != null) AbOnSurfaceMuted else AbWarning,
                style = MaterialTheme.typography.bodySmall,
            )
            SectionCard(title = "Visibility") {
                Text(
                    if (dockCfg.autoHideAfterSec == 0) "Always visible" else "Auto-hide after: ${dockCfg.autoHideAfterSec}s",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = dockCfg.autoHideAfterSec.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setDockConfig(dockCfg.copy(autoHideAfterSec = n.toInt())) } },
                    valueRange = 0f..30f, steps = 29, colors = sliderColors(),
                )
                ToggleRowWithDesc(
                    label = "Always show dock on wallpaper",
                    desc = "When desktop windows is on and nothing is open (wallpaper " +
                        "showing), keep the dock pinned instead of auto-hiding — so you " +
                        "don't have to summon it to launch your first app. The moment a " +
                        "window opens, the dock returns to the auto-hide timer above. " +
                        "Minimized windows still count as wallpaper. Only applies in " +
                        "desktop windows mode.",
                    key = PreferencesRepository.Keys.ALWAYS_SHOW_DOCK_ON_WALLPAPER,
                    default = true,
                )
            }
            SectionCard(title = "Reveal zone") {
                Text(
                    "When the dock is auto-hidden, move the cursor into this band at the dock's edge to bring it back.",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Text("Detection zone thickness: ${dockCfg.revealZoneHeightPx}px", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = dockCfg.revealZoneHeightPx.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setDockConfig(dockCfg.copy(revealZoneHeightPx = n.toInt())) } },
                    valueRange = 1f..120f, steps = 0, colors = sliderColors(),
                )
                Text("Detection zone width: ${dockCfg.revealZoneWidthPct}%", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = dockCfg.revealZoneWidthPct.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setDockConfig(dockCfg.copy(revealZoneWidthPct = n.toInt())) } },
                    valueRange = 10f..100f, steps = 89, colors = sliderColors(),
                )
                Text(
                    "The detection zone is highlighted on the external display while this page is open, so you can see what you're adjusting. It disappears when you go back.",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Text("Highlight color", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                ColorPointEditor("Zone highlight", dockCfg.zoneHighlightColor) { c ->
                    scope.launch { prefs.setDockConfig(dockCfg.copy(zoneHighlightColor = c)) }
                }
            }
            CollapsibleCard(title = "Dock layout") {
                Text(
                    "Dock size is controlled only here — it is independent of the Display DPI setting.",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text("Icon size: ${dockCfg.iconSizeDp} dp", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = dockCfg.iconSizeDp.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setDockConfig(dockCfg.copy(iconSizeDp = n.toInt())) } },
                    valueRange = 48f..120f, colors = sliderColors(),
                )
                Text(
                    if (dockCfg.labelSizeSp == 0) "Labels: hidden" else "Label text size: ${dockCfg.labelSizeSp} sp",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = dockCfg.labelSizeSp.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setDockConfig(dockCfg.copy(labelSizeSp = n.toInt())) } },
                    valueRange = 0f..18f, colors = sliderColors(),
                )
                Text("Dock width: ${dockCfg.dockWidthPct}% of display", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = dockCfg.dockWidthPct.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setDockConfig(dockCfg.copy(dockWidthPct = n.toInt())) } },
                    valueRange = 40f..100f, colors = sliderColors(),
                )
                // Reset to defaults across all dock customization sections above
                // (visibility, reveal zone, and layout) — keeps style, clock, apps.
                androidx.compose.material3.TextButton(
                    onClick = {
                        scope.launch {
                            prefs.setDockConfig(
                                dockCfg.copy(
                                    iconSizeDp = 60,
                                    labelSizeSp = 13,
                                    dockWidthPct = 92,
                                    autoHideAfterSec = 7,
                                    revealZoneHeightPx = 3,
                                    revealZoneWidthPct = 50,
                                    zoneHighlightColor = 0x66FFC83A,
                                ),
                            )
                        }
                    },
                ) {
                    Text("Reset to defaults")
                }
            }
            SectionCard(title = "Dock style") {
                BarStyleControls(
                    style = dockCfg.style,
                    onChange = { s -> scope.launch { prefs.setDockConfig(dockCfg.copy(style = s)) } },
                )
                MatchAllSurfacesButton(sourceLabel = "dock", sourceStyle = dockCfg.style)
            }
            SectionCard(title = "Clock") {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                // Live sample of the current format selection.
                val sample = remember(dockCfg.clockTimeFormat, dockCfg.clockDateFormat) {
                    val is24 = when (dockCfg.clockTimeFormat) {
                        com.portalpad.app.data.ClockTimeFormat.H24 -> true
                        com.portalpad.app.data.ClockTimeFormat.H12 -> false
                        com.portalpad.app.data.ClockTimeFormat.AUTO -> android.text.format.DateFormat.is24HourFormat(ctx)
                    }
                    val tf = java.text.SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", java.util.Locale.getDefault())
                    val df: java.text.DateFormat = when (dockCfg.clockDateFormat) {
                        com.portalpad.app.data.ClockDateFormat.MDY -> java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.getDefault())
                        com.portalpad.app.data.ClockDateFormat.DMY -> java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                        com.portalpad.app.data.ClockDateFormat.YMD -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        com.portalpad.app.data.ClockDateFormat.AUTO -> android.text.format.DateFormat.getDateFormat(ctx)
                    }
                    val d = java.util.Date()
                    "${tf.format(d)}  |  ${df.format(d)}"
                }
                Text("Preview: $sample", color = AbOnSurface, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("Time format", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                LabeledDropdown(
                    current = dockCfg.clockTimeFormat,
                    options = listOf(
                        com.portalpad.app.data.ClockTimeFormat.AUTO to "Auto (follow system)",
                        com.portalpad.app.data.ClockTimeFormat.H24 to "24-hour (13:30)",
                        com.portalpad.app.data.ClockTimeFormat.H12 to "12-hour (1:30 PM)",
                    ),
                    onSelect = { v -> scope.launch { prefs.setDockConfig(dockCfg.copy(clockTimeFormat = v)) } },
                    contentDescription = "Choose time format",
                )
                Spacer(Modifier.height(8.dp))
                Text("Date format", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                LabeledDropdown(
                    current = dockCfg.clockDateFormat,
                    options = listOf(
                        com.portalpad.app.data.ClockDateFormat.AUTO to "Auto (follow system)",
                        com.portalpad.app.data.ClockDateFormat.MDY to "MM/DD/YYYY",
                        com.portalpad.app.data.ClockDateFormat.DMY to "DD/MM/YYYY",
                        com.portalpad.app.data.ClockDateFormat.YMD to "YYYY-MM-DD",
                    ),
                    onSelect = { v -> scope.launch { prefs.setDockConfig(dockCfg.copy(clockDateFormat = v)) } },
                    contentDescription = "Choose date format",
                )
            }
            // Main reset for the whole Dock Customization page — restores dock
            // layout, style, and clock to defaults, while KEEPING your dock apps
            // and folders.
            var showDockResetConfirm by remember { mutableStateOf(false) }
            ResetToDefaultsButton("Reset Dock Customization to Defaults") { showDockResetConfirm = true }
            if (showDockResetConfirm) {
                ResetConfirmDialog(
                    title = "Reset dock customization to defaults?",
                    message = "This resets the dock layout, style, and clock to their defaults. Your dock apps and folders are kept.",
                    onConfirm = {
                        showDockResetConfirm = false
                        scope.launch {
                            val keptItems = dockCfg.items
                            prefs.setDockConfig(com.portalpad.app.data.DockConfig().copy(items = keptItems))
                        }
                    },
                    onCancel = { showDockResetConfirm = false },
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AbSurfaceElevated)
                .border(1.dp, AbAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .clickable { onBack() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("\u2190 Back", color = AbOnSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Top Bar Customization sub-page: position/width/zone/duration + style, with a pinned bottom Back. */
@Composable
private fun TopBarCustomizationPage(onBack: () -> Unit) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val topBarCfg by prefs.topBarConfig.collectAsState(initial = com.portalpad.app.data.TopBarConfig())
    val externalDisplayId by app.externalDisplayId.collectAsState()

    // Show the detection-zone highlight on the external display while this page is open;
    // hide it on leave. Preview-only.
    androidx.compose.runtime.DisposableEffect(Unit) {
        app.setZonePreviewActive(true)
        onDispose { app.setZonePreviewActive(false) }
    }

    Box(Modifier.fillMaxSize().background(AbBackground)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Top Bar Customization",
                color = AbOnSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            // External-display preview status hint (mirrors the folder-window page).
            Text(
                if (externalDisplayId == null)
                    "Connect your external display to preview the top bar there."
                else
                    "Previewing the top bar on the external display — adjust the style below to see it update live.",
                color = if (externalDisplayId != null) AbOnSurfaceMuted else AbWarning,
                style = MaterialTheme.typography.bodySmall,
            )
            SectionCard(title = "Visibility") {
                Text(
                    if (topBarCfg.autoHideAfterSec == 0) "Always visible" else "Auto-hide after: ${topBarCfg.autoHideAfterSec}s",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = topBarCfg.autoHideAfterSec.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setTopBarConfig(topBarCfg.copy(autoHideAfterSec = n.toInt())) } },
                    valueRange = 0f..30f, steps = 29, colors = sliderColors(),
                )
            }
            SectionCard(title = "Reveal zone") {
                Text(
                    "When the top bar is auto-hidden, move the cursor to the top edge of the display to bring it back.",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Text("Detection zone height: ${topBarCfg.revealZoneHeightPx}px", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = topBarCfg.revealZoneHeightPx.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setTopBarConfig(topBarCfg.copy(revealZoneHeightPx = n.toInt())) } },
                    valueRange = 1f..120f, steps = 0, colors = sliderColors(),
                )
                Text("Detection zone width: ${topBarCfg.revealZoneWidthPct}%", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = topBarCfg.revealZoneWidthPct.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setTopBarConfig(topBarCfg.copy(revealZoneWidthPct = n.toInt())) } },
                    valueRange = 10f..100f, steps = 89, colors = sliderColors(),
                )
                Text(
                    "The detection zone is highlighted on the external display while this page is open, so you can see what you're adjusting. It disappears when you go back.",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )
                Text("Highlight color", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                ColorPointEditor("Zone highlight", topBarCfg.zoneHighlightColor) { c ->
                    scope.launch { prefs.setTopBarConfig(topBarCfg.copy(zoneHighlightColor = c)) }
                }
            }
            CollapsibleCard(title = "Position & size") {
                Text("Position", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val positions = listOf(
                        com.portalpad.app.data.TopBarPosition.LEFT to "Left",
                        com.portalpad.app.data.TopBarPosition.CENTER to "Center",
                        com.portalpad.app.data.TopBarPosition.RIGHT to "Right",
                    )
                    positions.forEach { (pos, label) ->
                        val selected = topBarCfg.position == pos
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) AbAccent else AbSurfaceElevated)
                                .clickable { scope.launch { prefs.setTopBarConfig(topBarCfg.copy(position = pos)) } }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = if (selected) AbBackground else AbOnSurface, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text("Width: ${topBarCfg.widthPct}% of display", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = topBarCfg.widthPct.toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setTopBarConfig(topBarCfg.copy(widthPct = n.toInt())) } },
                    valueRange = 30f..100f, steps = 69, colors = sliderColors(),
                )
                Text("Bar size: ${topBarCfg.sizePct}% (icons + height)", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = topBarCfg.sizePct.coerceIn(60, 160).toFloat(),
                    onValueChange = { n -> scope.launch { prefs.setTopBarConfig(topBarCfg.copy(sizePct = n.toInt())) } },
                    valueRange = 60f..160f, steps = 99, colors = sliderColors(),
                )
                // Reset to defaults across all top-bar customization sections above
                // (visibility, reveal zone, position & size) — keeps the top-bar style.
                androidx.compose.material3.TextButton(
                    onClick = {
                        scope.launch {
                            prefs.setTopBarConfig(
                                topBarCfg.copy(
                                    position = com.portalpad.app.data.TopBarPosition.CENTER,
                                    widthPct = 70,
                                    revealZoneHeightPx = 3,
                                    revealZoneWidthPct = 50,
                                    zoneHighlightColor = 0x66FFC83A,
                                    autoHideAfterSec = 0,
                                ),
                            )
                        }
                    },
                ) {
                    Text("Reset to defaults")
                }
            }
            CollapsibleCard(title = "Top bar style") {
                BarStyleControls(
                    style = topBarCfg.style,
                    onChange = { s -> scope.launch { prefs.setTopBarConfig(topBarCfg.copy(style = s)) } },
                )
                MatchAllSurfacesButton(sourceLabel = "top bar", sourceStyle = topBarCfg.style)
            }
            // Main reset for the whole Top Bar Customization page — restores
            // position/size, detection zone, and top-bar style to defaults.
            var showTopBarResetConfirm by remember { mutableStateOf(false) }
            ResetToDefaultsButton("Reset Top Bar Customization to Defaults") { showTopBarResetConfirm = true }
            if (showTopBarResetConfirm) {
                ResetConfirmDialog(
                    title = "Reset top bar customization to defaults?",
                    message = "This resets the top bar position, size, detection zone, and style to their defaults.",
                    onConfirm = {
                        showTopBarResetConfirm = false
                        scope.launch {
                            prefs.setTopBarConfig(com.portalpad.app.data.TopBarConfig())
                        }
                    },
                    onCancel = { showTopBarResetConfirm = false },
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AbSurfaceElevated)
                .border(1.dp, AbAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .clickable { onBack() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("\u2190 Back", color = AbOnSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Folder Window Customization sub-page (global): background gradient + app-label
 *  color for all folder windows. Pinned bottom Back. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FolderWindowCustomizationPage(onBack: () -> Unit) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val fwCfg by prefs.folderWindowConfig.collectAsState(initial = com.portalpad.app.data.FolderWindowConfig())
    val dockCfg by prefs.dockConfig.collectAsState(initial = com.portalpad.app.data.DockConfig())
    val hasFolder = dockCfg.items.any { it is com.portalpad.app.data.DockItem.Folder }
    val externalDisplayId by app.externalDisplayId.collectAsState()

    // Preview the live folder-window style on the external display while this page is open
    // (opens the first folder there). Cleared on leave.
    androidx.compose.runtime.DisposableEffect(Unit) {
        app.setFolderWindowPreviewActive(true)
        onDispose { app.setFolderWindowPreviewActive(false) }
    }

    Box(Modifier.fillMaxSize().background(AbBackground)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
                .padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Folder Window Customization",
                color = AbOnSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Applies to all folder windows (the popup shown when you open a dock folder).",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
            // External-display preview status hint.
            val previewHint = when {
                externalDisplayId == null -> "Connect your external display to preview the folder window there."
                !hasFolder -> "Create a folder first to preview the folder window on your external display."
                else -> "Previewing your first folder on the external display — adjust the style below to see it update live."
            }
            Text(
                previewHint,
                color = if (hasFolder && externalDisplayId != null) AbOnSurfaceMuted else AbWarning,
                style = MaterialTheme.typography.bodySmall,
            )
            SectionCard(title = "Background gradient") {
                BarStyleControls(
                    style = fwCfg.background,
                    onChange = { s -> scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(background = s)) } },
                )
                MatchAllSurfacesButton(sourceLabel = "folder window", sourceStyle = fwCfg.background)
            }
            SectionCard(title = "App label color") {
                ColorPointEditor("Label color", fwCfg.labelColor) { c ->
                    scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(labelColor = c)) }
                }
            }
            CollapsibleCard(title = "Folder window layout") {
                // Lightweight sub-collapsibles so the (long) folder card breaks into
                // Grid / Window position / Labels instead of one tall scroll.
                val sub: @Composable (String, @Composable () -> Unit) -> Unit = { subTitle, subContent ->
                    var open by remember { mutableStateOf(false) }
                    val rot by animateFloatAsState(if (open) 180f else 0f, label = "fwSub")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { open = !open }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(subTitle, color = AbAccent, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = if (open) "Collapse" else "Expand",
                            tint = AbOnSurfaceMuted,
                            modifier = Modifier.rotate(rot),
                        )
                    }
                    if (open) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { subContent() }
                    }
                    HorizontalDivider(color = AbSurfaceElevated, thickness = 1.dp)
                }
                sub("Grid") {
                Text(
                    "Apps per row: ${fwCfg.columns}",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = fwCfg.columns.coerceIn(1, 12).toFloat(),
                    onValueChange = { v -> scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(columns = v.toInt().coerceIn(1, 12))) } },
                    valueRange = 1f..12f,
                    steps = 10,
                    colors = sliderColors(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Rows before scrolling: ${fwCfg.maxRows}",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = fwCfg.maxRows.coerceIn(1, 12).toFloat(),
                    onValueChange = { v -> scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(maxRows = v.toInt().coerceIn(1, 12))) } },
                    valueRange = 1f..12f,
                    steps = 10,
                    colors = sliderColors(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Icon size: ${fwCfg.tileSizeDp}dp",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = fwCfg.tileSizeDp.coerceIn(40, 120).toFloat(),
                    onValueChange = { v -> scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(tileSizeDp = v.toInt().coerceIn(40, 120))) } },
                    valueRange = 40f..120f,
                    colors = sliderColors(),
                )
                Text(
                    "More columns / larger icons widen the window; it's capped to fit the display. Rows set how many show before the grid scrolls.",
                    color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(14.dp))
                // Icon alignment within the window (matters for a partial last row).
                Text("Icon alignment", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        com.portalpad.app.data.FolderGridAlign.LEFT to "Left",
                        com.portalpad.app.data.FolderGridAlign.CENTER to "Center",
                    ).forEach { (a, label) ->
                        val sel = fwCfg.gridAlign == a
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (sel) AbAccent else AbSurfaceElevated)
                                .clickable { scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(gridAlign = a)) } }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (sel) AbBackground else AbOnSurface, style = MaterialTheme.typography.bodyMedium) }
                    }
                }

                }
                sub("Window position") {
                // Horizontal then vertical placement on the display.
                Text("Window position", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        com.portalpad.app.data.FolderWindowPosition.LEFT to "Left",
                        com.portalpad.app.data.FolderWindowPosition.CENTER to "Center",
                        com.portalpad.app.data.FolderWindowPosition.RIGHT to "Right",
                    ).forEach { (p, label) ->
                        val sel = fwCfg.position == p
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (sel) AbAccent else AbSurfaceElevated)
                                .clickable { scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(position = p)) } }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (sel) AbBackground else AbOnSurface, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        com.portalpad.app.data.FolderWindowVPosition.TOP to "Top",
                        com.portalpad.app.data.FolderWindowVPosition.CENTER to "Center",
                        com.portalpad.app.data.FolderWindowVPosition.BOTTOM to "Bottom",
                    ).forEach { (p, label) ->
                        val sel = fwCfg.vPosition == p
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (sel) AbAccent else AbSurfaceElevated)
                                .clickable { scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(vPosition = p)) } }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (sel) AbBackground else AbOnSurface, style = MaterialTheme.typography.bodyMedium) }
                    }
                }

                }
                sub("Labels") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Show app names", color = AbOnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = fwCfg.showLabels,
                        onCheckedChange = { on -> scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(showLabels = on)) } },
                    )
                }
                if (fwCfg.showLabels) {
                    Text("Label size: ${fwCfg.labelSizeSp}sp", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = fwCfg.labelSizeSp.coerceIn(8, 20).toFloat(),
                        onValueChange = { v -> scope.launch { prefs.setFolderWindowConfig(fwCfg.copy(labelSizeSp = v.toInt().coerceIn(8, 20))) } },
                        valueRange = 8f..20f,
                        colors = sliderColors(),
                    )
                }

                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.TextButton(
                    onClick = {
                        scope.launch {
                            val d = com.portalpad.app.data.FolderWindowConfig()
                            // Reset only the grid/layout fields; keep background + label color.
                            prefs.setFolderWindowConfig(
                                fwCfg.copy(
                                    columns = d.columns, maxRows = d.maxRows, tileSizeDp = d.tileSizeDp,
                                    gridAlign = d.gridAlign, showLabels = d.showLabels,
                                    labelSizeSp = d.labelSizeSp, position = d.position,
                                ),
                            )
                        }
                    },
                ) { Text("Reset grid layout", color = AbPrimaryBright) }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AbSurfaceElevated)
                .border(1.dp, AbAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .clickable { onBack() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("\u2190 Back", color = AbOnSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ─────────────────────────── Tab 4: Display ───────────────────────────

@Composable
private fun DisplayTab() {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val dpi by prefs.displayDpi.collectAsState(initial = 0)
    val externalDisplayId by app.externalDisplayId.collectAsState()
    val vdId by app.virtualDisplayIdFlow.collectAsState()
    val shizukuStatus by app.shizuku.status.collectAsState()
    val shizukuUserServiceReady by app.shizukuClickBackend.readyFlow.collectAsState()
    val shizukuReady = shizukuStatus == ShizukuManager.Status.READY
    val rootReady = app.root.isReady

    // Auto-bind UserService if Shizuku is ready but not yet bound — so the
    // trusted-VD wrap kicks in without the user having to visit System tab.
    androidx.compose.runtime.LaunchedEffect(shizukuReady) {
        if (shizukuReady && !app.shizukuClickBackend.isReady) {
            app.shizukuClickBackend.bind()
        }
    }

    // Proactively re-check Shizuku permission state whenever this tab is
    // shown. On a fresh install the manager can sit in UNKNOWN / stale state
    // until something calls checkSelfPermission — which made the access chip
    // show an unhelpful "View in App" (Shizuku's own stale-state text)
    // instead of a proper Authorize prompt. Refreshing on entry forces a
    // correct READY / NEEDS_PERMISSION / NOT_RUNNING reading up front.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        app.shizuku.refresh()
        app.root.refresh()
    }

    // Resolve a friendly label for the current display, recomputed when id changes.
    // Prefer the PHYSICAL external display id over the VD wrapper id — when we
    // wrap the glasses in a trusted Virtual Display, externalDisplayId points
    // to the VD (named "PortalPad Session"), which isn't useful to show. The
    // physical display has the real hardware name (e.g. "XREAL One Pro").
    val context = LocalContext.current
    val physicalExternalDisplayId by app.physicalExternalDisplayId.collectAsState()

    // Live external-display detection independent of the service, so Target
    // display reflects plugged-in glasses even before the service starts (mirrors
    // the Start tab's Status row). Holds the detected physical display's id.
    var detectedDisplayId by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) {
        val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
            as android.hardware.display.DisplayManager
        fun looksExternal(d: android.view.Display): Boolean {
            if (d.displayId == android.view.Display.DEFAULT_DISPLAY) return false
            if (d.name == "PortalPad Session") return false
            val type = runCatching {
                android.view.Display::class.java.getMethod("getType").invoke(d) as? Int
            }.getOrNull()
            if (type != null) return type == 2 || type == 4 // EXTERNAL or OVERLAY
            val n = d.name.lowercase()
            return !(n.contains("ghost") || n.contains("virtual") || n.contains("projection") ||
                n.contains("componentinfo") || n.contains("scrcpy"))
        }
        fun rescan() {
            detectedDisplayId = runCatching {
                dm.displays.firstOrNull { looksExternal(it) }?.displayId
            }.getOrNull()
        }
        rescan()
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = rescan()
            override fun onDisplayRemoved(displayId: Int) = rescan()
            override fun onDisplayChanged(displayId: Int) = rescan()
        }
        dm.registerDisplayListener(listener, null)
        onDispose { dm.unregisterDisplayListener(listener) }
    }
    // Prefer the service's physical/external id; fall back to the live-detected
    // display so the card populates pre-service.
    val labelDisplayId = physicalExternalDisplayId ?: externalDisplayId ?: detectedDisplayId
    val displayLabel = remember(labelDisplayId) {
        labelDisplayId?.let { id ->
            val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            val display = dm.getDisplay(id)
            if (display != null) {
                val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
                "${display.name.ifBlank { "External" }} (${metrics.widthPixels}×${metrics.heightPixels})"
            } else null
        }
    }
    // Detected external-display width in px, used to compute where the
    // desktop/mobile DPI boundary falls (it depends on resolution: the same
    // DPI yields twice the dp-width on a 3840 ultrawide as on 1920). Null when
    // no display is connected → we assume 1920 for the hint text.
    val externalWidthPx = remember(labelDisplayId) {
        labelDisplayId?.let { id ->
            val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager
            dm.getDisplay(id)?.let { d ->
                android.util.DisplayMetrics().also { d.getRealMetrics(it) }.widthPixels
            }
        }
    }

    TabBody {
        SectionCard(title = "Display pipeline") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("System mirror (experimental)", color = AbOnSurface)
                    Text(
                        "Carries the external display's picture through the system compositor instead of an app-overlay window, so it no longer goes black when a USB or permission dialog appears, and some DRM video plays that otherwise wouldn't (like Netflix or Prime Video, though other streaming apps may still not work).",
                        color = AbOnSurfaceMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Trade-off: color tuning, screen size, Side Mode and the performance overlay's frame-rate stats aren't available in this mode.\nTurn OFF for full color and all display controls.",
                        color = AbOnSurfaceDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = prefs.panelSystemMirror.collectAsState(initial = false).value,
                    onCheckedChange = { scope.launch { prefs.setPanelSystemMirror(it) } },
                )
            }
        }
        SectionCard(title = "Target display") {
            OutlinedCard(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, AbOnSurfaceDim),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (displayLabel != null) "Connected" else "Status",
                        color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        displayLabel ?: "No external display detected",
                        color = if (displayLabel != null) AbSuccess else AbOnSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (displayLabel != null) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    // Wrap status — was previously in a separate "External
                    // display" card under System tab; consolidated here so
                    // all display info lives in one place.
                    val wrapStatus = when {
                        externalDisplayId == null -> null
                        !shizukuReady && !rootReady -> "No trusted-VD wrap — needs Root or Shizuku"
                        !shizukuUserServiceReady && !app.rootClickBackend.isReady -> "Connecting to privileged service…"
                        vdId != null -> "Wrapped in trusted virtual display id=$vdId"
                        else -> "Wrap pending — VD not yet created"
                    }
                    wrapStatus?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            color = if (vdId != null) AbSuccess else AbOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            // Detected density of the external display (Auto value + manual-field
            // starting value); 213 fallback if implausible.
            val detectedDpi = remember(labelDisplayId) {
                runCatching {
                    val id = labelDisplayId ?: return@runCatching 213
                    val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                    val d = dm.getDisplay(id) ?: return@runCatching 213
                    val m = android.util.DisplayMetrics().also { d.getRealMetrics(it) }
                    m.densityDpi.takeIf { it in 120..480 } ?: 213
                }.getOrDefault(213)
            }
            val dpiConnected = externalDisplayId != null || physicalExternalDisplayId != null || vdId != null
            val dpiAlpha = if (dpiConnected) 1f else 0.4f
            val sliderValue = (if (dpi == 0) detectedDpi else dpi).toFloat().coerceIn(100f, 640f)
            var dpiText by remember(dpi, detectedDpi, dpiConnected) {
                mutableStateOf(
                    when {
                        dpi != 0 -> dpi.toString()
                        dpiConnected -> detectedDpi.toString()
                        else -> "Auto"
                    }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (dpi == 0) "Display DPI (Auto):" else "Display DPI:",
                    color = AbOnSurfaceMuted.copy(alpha = dpiAlpha),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(8.dp))
                CompactDpiField(value = dpiText, enabled = dpiConnected) { raw ->
                    val digits = raw.filter { it.isDigit() }.take(3)
                    dpiText = digits
                    digits.toIntOrNull()?.let { v ->
                        if (v in 80..640) scope.launch { prefs.setDisplayDpi(v) }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Reset to Auto",
                    color = AbAccent.copy(alpha = dpiAlpha),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = dpiConnected) { scope.launch { prefs.setDisplayDpi(0) } }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                if (dpiConnected) "Auto uses the display's detected density ($detectedDpi)."
                else "Connect a display to adjust.",
                color = AbOnSurfaceMuted.copy(alpha = dpiAlpha),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Slider(
                value = sliderValue,
                onValueChange = { scope.launch { prefs.setDisplayDpi(it.toInt()) } },
                valueRange = 100f..640f,
                enabled = dpiConnected,
                colors = sliderColors(),
            )
            val widthForCalc = externalWidthPx ?: 1920
            val thresholdDpi = ((widthForCalc / 1280f) * 160f).toInt().coerceIn(161, 639)
            Text(
                "Controls UI scale on the external display (not the dock or top bar).\n" +
                    "Adjusts live as you change it — you may need to reopen an app to see it fully update. Saves automatically.\n" +
                    "\n" +
                    "• Lower DPI (100 - $thresholdDpi) = bigger desktop-style layouts (Chrome shows a tab strip).\n" +
                    "• Higher DPI (${thresholdDpi + 1} - 640) = mobile-style apps with smaller UI.\n",
                color = AbOnSurfaceMuted.copy(alpha = dpiAlpha),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SectionCard(title = "Lock screen") {
            Text(
                "Control whether PortalPad's interface can appear over your phone's lock screen.",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
            ToggleRowWithDesc(
                label = "Show controls over the lock screen",
                desc = "Let the PortalPad controls (trackpad / remote / air mouse) open over your phone's lock screen, so you can drive the external display without unlocking. Leaving PortalPad to other apps still requires unlocking.",
                key = PreferencesRepository.Keys.ALLOW_LOCKSCREEN,
                default = true,
            )
        }
        SectionCard(title = "App orientation") {
            Text(
                "Locks PortalPad's phone screens to a fixed orientation so they don't auto-rotate. Affects only PortalPad — never apps shown on the external display. Pick Auto-rotate if you use a tablet.",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            val orientationFlow = remember {
                prefs.string(PreferencesRepository.Keys.FORCE_ORIENTATION, OrientationLock.DEFAULT)
            }
            val orientation by orientationFlow.collectAsState(initial = OrientationLock.DEFAULT)
            var expanded by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AbSurfaceElevated)
                        .clickable { expanded = true }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        OrientationLock.labelFor(orientation),
                        color = AbOnSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = "Open orientation menu",
                        tint = AbOnSurfaceMuted,
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(AbSurfaceElevated),
                ) {
                    OrientationLock.OPTIONS.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label, color = AbOnSurface) },
                            onClick = {
                                expanded = false
                                scope.launch { prefs.setString(PreferencesRepository.Keys.FORCE_ORIENTATION, value) }
                            },
                        )
                    }
                }
            }
            Text(
                "Changes apply when you return to a PortalPad screen.",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ─────────────────────────── Tab 5: System ───────────────────────────

@Composable
private fun SystemTab(
    onAuthorizeShizuku: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onPickBackupFolder: () -> Unit,
    onBackupNow: () -> Unit,
    onRestoreFromFile: () -> Unit,
    onScheduleBackup: (dayOfWeek: Int, hour: Int) -> Unit,
) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val shizukuStatus by app.shizuku.status.collectAsState()
    val accessMode by prefs.accessMode.collectAsState(initial = AccessMode.NONE)

    val overlayGranted = rememberOnResume { android.provider.Settings.canDrawOverlays(it) }
    val notifAccess = rememberOnResume { app.media.hasNotificationAccess }

    val shizukuReady = shizukuStatus == ShizukuManager.Status.READY
    val rootReady = app.root.isReady
    val shizukuUserServiceReady by app.shizukuClickBackend.readyFlow.collectAsState()
    val rootUserServiceReady by app.rootClickBackend.readyFlow.collectAsState()

    // Popup state — which chip the user just tapped that isn't ready
    var notReadyPopup by remember { mutableStateOf<AccessMode?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    TabBody {
        SectionCard(title = "Privilege source") {
            Text(
                "How PortalPad performs elevated operations like launching apps on external displays, external-display / AR-glasses click routing, IME policy, and granting Android permissions.",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("Note: ") }
                    append("Root runs a privileged service (via libsu) with full parity to Shizuku — trusted virtual display, keyboard-on-external, IME policy, and in-process input injection. This path is confirmed working. Shizuku is the most broadly tested option, so it remains a reliable alternative.")
                },
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
            )
            AccessModeSelector(
                accessMode = accessMode,
                shizukuReady = shizukuReady,
                rootReady = rootReady,
                onSelect = { mode ->
                    // Always save the preference. Help popup only appears when the
                    // mode isn't ready yet — even then it's just guidance, the
                    // selection is already persisted.
                    scope.launch { prefs.setAccessMode(mode) }
                    when (mode) {
                        AccessMode.NONE -> { /* nothing to set up */ }
                        AccessMode.SHIZUKU -> {
                            // Bind the UserService if Shizuku is ready. If not,
                            // show the helper popup tailored to the current state.
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
                            // Always show the Root popup: when root isn't granted
                            // it guides Magisk/Shizuku; when it IS granted it notes
                            // that some features may still want Shizuku and offers
                            // to authorize it too.
                            notReadyPopup = mode
                        }
                    }
                },
            )
            val statusValue = when (accessMode) {
                AccessMode.NONE -> "None — select Shizuku or Root above"
                AccessMode.SHIZUKU -> when {
                    shizukuUserServiceReady -> "Shizuku — Full features available"
                    shizukuReady -> "Shizuku — Binding service…"
                    else -> "Shizuku — Not authorized"
                }
                AccessMode.ROOT -> when {
                    rootUserServiceReady -> "Root — Full features available"
                    rootReady -> "Root — Starting service…"
                    else -> "Root — Not granted"
                }
            }
            val statusOk = when (accessMode) {
                AccessMode.NONE -> false
                AccessMode.SHIZUKU -> shizukuReady
                AccessMode.ROOT -> rootReady
            }
            StatusRow(label = "Status", value = statusValue, ok = statusOk)
            if (accessMode == AccessMode.ROOT) {
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append("Note: ") }
                        append("Root is confirmed working — the privileged service, virtual display, and input all run with full parity to Shizuku. Shizuku remains the most broadly tested path, so you can switch to it anytime if you prefer.")
                    },
                    color = AbOnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, start = 20.dp),
                )
            }
        }

        SectionCard(
            title = "Floating bubble",
            pill = { PermissionPill("Overlay") },
        ) {
            Text(
                "Show a draggable bubble when you leave the app, so you can return to the trackpad with one tap.",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            ToggleRow(label = "Show floating bubble when backgrounded", key = PreferencesRepository.Keys.FLOATING_BUBBLE, default = true)
            FixRow(
                label = "Overlay permission",
                value = if (overlayGranted.value) "Granted" else "Tap to grant",
                onClick = onOpenOverlaySettings,
                needsFix = !overlayGranted.value,
            )
        }

        SectionCard(
            title = "Media controls",
            pill = { PermissionPill("Notification access") },
        ) {
            Text(
                "Play/pause, next, previous and FF/rew. Volume buttons need no permission. The rest works best when PortalPad has Notification Access — that's how it talks to the active media session.",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            FixRow(
                label = "Notification access",
                value = if (notifAccess.value) "Granted" else "Tap to grant",
                onClick = onOpenNotificationListenerSettings,
                needsFix = !notifAccess.value,
            )
        }

        // (Duplicate "Accessibility service" card that used to live here has
        // been removed — the canonical Accessibility card above already
        // exposes the same toggle and now also holds the "Keep accessibility
        // service alive" pref + auto-disable timer.)

        SectionCard(title = "Startup & timeout") {
            ToggleRowWithDesc(
                label = "Start PortalPad on boot",
                desc = "Auto-start the foreground service when your phone finishes booting. Notification permission may be required on Android 13+.",
                key = PreferencesRepository.Keys.START_ON_BOOT,
                default = false,
            )
            AutoDisableRow()
            RestoreLastSessionRow()
            ToggleRowWithDesc(
                label = "Offer to restore on reconnect",
                desc = "When you open the trackpad and there's a saved session, ask whether to restore it. Turn off to never be prompted — the \"Restore session\" button above still works manually.",
                key = PreferencesRepository.Keys.OFFER_RESTORE_ON_RECONNECT,
                default = true,
            )
        }

        // (Diagnostics crash-summary card removed — same information is
        // available through the Logs button at the top right, which
        // shows full logcat including any crash stack traces.)

        BackupRestoreSection(
            onPickBackupFolder = onPickBackupFolder,
            onBackupNow = onBackupNow,
            onRestoreFromFile = onRestoreFromFile,
            onScheduleBackup = onScheduleBackup,
        )

        FactoryResetRow()
    }

    // ─── Popup: chosen privilege source isn't ready ────────────────────
    // Tailored to the current sub-state so the primary action is always useful.
    notReadyPopup?.let { mode ->
        // Read Shizuku status FRESHLY when the popup opens, so on first selection
        // we land on the correct branch (e.g. "Authorize") instead of a stale
        // UNKNOWN that wrongly showed the "Open Shizuku" path until reopened.
        val freshShizukuStatus = remember(mode) {
            if (mode == AccessMode.SHIZUKU) app.shizuku.freshStatus() else shizukuStatus
        }
        val (title, msg, primaryLabel, primaryAction) = when (mode) {
            AccessMode.SHIZUKU -> when (freshShizukuStatus) {
                ShizukuManager.Status.NOT_INSTALLED -> Quad(
                    "Shizuku not installed",
                    "Shizuku is a separate app that grants PortalPad the privileges needed for external displays / AR glasses, IME policy, and launching apps on external displays. Install the recommended GitHub build (adds start-on-boot and a watchdog so it keeps running), then come back.",
                    "Open in GitHub",
                    {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/thedjchi/Shizuku"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { ctx.startActivity(intent) }
                        notReadyPopup = null
                    },
                )
                ShizukuManager.Status.NOT_RUNNING -> Quad(
                    "Shizuku not running",
                    "Shizuku is installed but its service isn't running. Open Shizuku, then start the service via ADB (instructions inside the app) or with wireless debugging. PortalPad will pick it up automatically when you return.",
                    "Open Shizuku",
                    {
                        val pm = ctx.packageManager
                        val launchIntent = pm.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        launchIntent?.let {
                            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { ctx.startActivity(it) }
                        }
                        notReadyPopup = null
                    },
                )
                ShizukuManager.Status.NEEDS_PERMISSION -> Quad(
                    "Authorize PortalPad",
                    "Shizuku is running. Tap Authorize to bring up the Android permission dialog so PortalPad can use it.",
                    "Authorize",
                    {
                        app.shizuku.requestPermission()
                        notReadyPopup = null
                    },
                )
                else -> Quad(
                    "Shizuku status unknown",
                    "Couldn't determine Shizuku's state. Try reopening Shizuku, then come back.",
                    "OK",
                    { notReadyPopup = null },
                )
            }
            AccessMode.ROOT -> if (rootReady) Quad(
                "Root active",
                "PortalPad is using root, with full parity to Shizuku — confirmed working. " +
                    "Shizuku is the most broadly tested path, so if you'd like you can also " +
                    "authorize it and PortalPad will use whichever you prefer.",
                "OK",
                { notReadyPopup = null },
            ) else Quad(
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
            AccessMode.NONE -> Quad("", "", "", { notReadyPopup = null })
        }
        if (title.isNotEmpty()) {
            // For ROOT, offer a secondary "Authorize Shizuku" action so the user
            // can fall back to (or supplement with) Shizuku directly from here —
            // whether root is unavailable or just doesn't cover a feature.
            val authorizeShizuku: (() -> Unit)? = if (mode == AccessMode.ROOT) {
                {
                    // Use the SYNCHRONOUS status read — refresh() is async, so
                    // reading status.value on the next line was stale (UNKNOWN),
                    // which made this button appear to do nothing. freshStatus()
                    // returns the current state immediately so we branch right.
                    when (app.shizuku.freshStatus()) {
                        ShizukuManager.Status.NOT_INSTALLED -> {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/thedjchi/Shizuku"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { ctx.startActivity(intent) }
                        }
                        ShizukuManager.Status.READY -> {
                            // Already authorized — bind and switch the privilege
                            // source to Shizuku (flip the dropdown), since the
                            // user chose to use it.
                            app.shizukuClickBackend.bind()
                            scope.launch { prefs.setAccessMode(AccessMode.SHIZUKU) }
                        }
                        ShizukuManager.Status.NOT_RUNNING, ShizukuManager.Status.UNKNOWN -> {
                            val pm = ctx.packageManager
                            pm.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let {
                                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { ctx.startActivity(it) }
                            }
                        }
                        else -> {
                            app.shizuku.requestPermission()
                        }
                    }
                    notReadyPopup = null
                }
            } else null

            androidx.compose.material3.AlertDialog(
                onDismissRequest = { notReadyPopup = null },
                title = { Text(title) },
                text = { Text(msg) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = primaryAction) {
                        Text(primaryLabel)
                    }
                },
                dismissButton = {
                    androidx.compose.foundation.layout.Row {
                        if (authorizeShizuku != null) {
                            val shizukuLabel = if (app.shizuku.freshStatus() == ShizukuManager.Status.READY)
                                "Enable Shizuku" else "Authorize Shizuku"
                            androidx.compose.material3.TextButton(onClick = authorizeShizuku) {
                                Text(shizukuLabel)
                            }
                        }
                        androidx.compose.material3.TextButton(onClick = { notReadyPopup = null }) {
                            Text("Cancel")
                        }
                    }
                },
            )
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/**
 * Backup & Restore card. Lets the user pick a folder (any folder — Drive-synced,
 * Dropbox-synced, local, SD card), run a manual backup, restore from a JSON file
 * picked anywhere on the device, and schedule periodic backups.
 */
@Composable
private fun BackupRestoreSection(
    onPickBackupFolder: () -> Unit,
    onBackupNow: () -> Unit,
    onRestoreFromFile: () -> Unit,
    onScheduleBackup: (dayOfWeek: Int, hour: Int) -> Unit,
) {
    val app = PortalPadApp.instance
    val folderUri by app.prefs.rawDataStore.data.collectAsState(initial = null)
    val folderUriStr = folderUri?.get(PreferencesRepository.Keys.BACKUP_FOLDER_URI)
    val lastBackupMs = folderUri?.get(PreferencesRepository.Keys.BACKUP_LAST_SUCCESS_MS) ?: 0L
    val frequency = folderUri?.get(PreferencesRepository.Keys.BACKUP_FREQUENCY) ?: "off"
    val backupDay = folderUri?.get(PreferencesRepository.Keys.BACKUP_DAY) ?: 0
    val backupHour = folderUri?.get(PreferencesRepository.Keys.BACKUP_HOUR) ?: 3

    SectionCard(title = "Backup & restore") {
        Text(
            "Saves every PortalPad setting and dock layout as a JSON file. If you haven't picked a folder, backups land in Documents/PortalPad/ — visible in any file manager and synced by the Google Drive app.",
            color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
        )

        val folderLabel = if (folderUriStr != null) {
            val decoded = runCatching { android.net.Uri.decode(folderUriStr) }.getOrDefault(folderUriStr)
            decoded.substringAfterLast("/")
        } else "Documents/PortalPad (default)"
        FixRow(
            label = "Backup folder",
            value = folderLabel,
            onClick = onPickBackupFolder,
            needsFix = false,  // Default folder works fine; tap to override
        )

        if (lastBackupMs > 0) {
            StatusRow(label = "Last backup", value = formatTimeAgo(System.currentTimeMillis() - lastBackupMs), ok = true)
        } else {
            StatusRow(label = "Last backup", value = "Never", ok = false)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .weight(1f).height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AbPrimary)
                    .clickable { onBackupNow() },
                contentAlignment = Alignment.Center,
            ) {
                Text("Back up now", color = AbOnSurface, fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier
                    .weight(1f).height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AbSurfaceElevated)
                    .clickable { onRestoreFromFile() },
                contentAlignment = Alignment.Center,
            ) {
                Text("Restore", color = AbOnSurface, fontWeight = FontWeight.SemiBold)
            }
        }

        // Schedule on/off + day + hour
        Text(
            "Scheduled backups",
            color = AbOnSurface, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )

        val scheduleOn = frequency == "scheduled"
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (scheduleOn) "Scheduled" else "Off",
                color = AbOnSurface, modifier = Modifier.weight(1f),
            )
            Switch(
                checked = scheduleOn,
                onCheckedChange = { newOn ->
                    if (newOn) onScheduleBackup(backupDay, backupHour)
                    else onScheduleBackup(-1, 0)   // -1 = off
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = AbPrimary,
                    checkedThumbColor = AbPrimaryBright,
                    uncheckedTrackColor = AbSurfaceElevated,
                    uncheckedThumbColor = AbOnSurfaceDim,
                ),
            )
        }

        if (scheduleOn) {
            // Day-of-week selector
            Text(
                "Day",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            DaySelector(
                selected = backupDay,
                onSelect = { onScheduleBackup(it, backupHour) },
            )

            // Hour-of-day selector
            Text(
                "Time: ${formatHour(backupHour)}",
                color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = backupHour.toFloat(),
                onValueChange = { onScheduleBackup(backupDay, it.toInt()) },
                valueRange = 0f..23f,
                steps = 22,
                colors = sliderColors(),
            )
        }
    }
}

/** Day-of-week chips: Every day / Mon / Tue / Wed / Thu / Fri / Sat / Sun. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DaySelector(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(
        0 to "Every",
        1 to "Mon",
        2 to "Tue",
        3 to "Wed",
        4 to "Thu",
        5 to "Fri",
        6 to "Sat",
        7 to "Sun",
    )
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEach { (id, lbl) ->
            val isSel = selected == id
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSel) AbPrimary else AbSurfaceElevated)
                    .clickable { onSelect(id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    lbl,
                    color = if (isSel) AbOnSurface else AbOnSurfaceMuted,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun formatHour(h: Int): String {
    val twelve = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    val ampm = if (h < 12) "AM" else "PM"
    return "${twelve}:00 $ampm"
}

private fun formatTimeAgo(deltaMs: Long): String {
    val seconds = deltaMs / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "$seconds seconds ago"
        minutes < 60 -> "$minutes minute${if (minutes == 1L) "" else "s"} ago"
        hours < 24 -> "$hours hour${if (hours == 1L) "" else "s"} ago"
        days < 30 -> "$days day${if (days == 1L) "" else "s"} ago"
        else -> "More than a month ago"
    }
}

/**
 * Actionable "fix this permission" row. When [needsFix] is true the row pulses
 * amber and is clickable; when granted, it's a quiet green status.
 */
@Composable
private fun FixRow(label: String, value: String, onClick: () -> Unit, needsFix: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (needsFix) AbAccent.copy(alpha = 0.12f) else AbSurfaceElevated.copy(alpha = 0.5f))
            .clickable(enabled = needsFix) { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (needsFix) AbAccent else AbSuccess),
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = AbOnSurface, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                color = if (needsFix) AbAccent else AbOnSurfaceMuted,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (needsFix) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        if (needsFix) {
            Text("Fix", color = AbAccent, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Small pill rendering the permission requirement next to a section title. */
@Composable
private fun PermissionPill(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AbAccent.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label, color = AbAccent,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Reads a value once and again every time the host activity resumes. Lets a
 * Compose UI react when the user comes back from a system Settings screen
 * (overlay permission, accessibility) where they may have changed things.
 */
@Composable
private fun <T> rememberOnResume(read: (android.content.Context) -> T): State<T> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(read(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = read(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

// ─────────────────────────── Shared composables ───────────────────────────

/**
 * True scale-to-fit container: measures [content] at its natural size, then
 * uniformly scales it down (never up past 1x) so the whole thing fits the
 * available height without scrolling or clipping. Used by the Start page, which
 * should size to the display rather than scroll. Content is given the full width
 * at its natural height first, then scaled around the top-center so it stays put.
 *
 * Tradeoff: on very small screens or large font scales, tall content shrinks
 * (text included) rather than scrolling. That's the intended "fit" behavior.
 */
@Composable
private fun ScaleToFit(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.SubcomposeLayout(modifier.fillMaxSize()) { constraints ->
        val availH = constraints.maxHeight
        val availW = constraints.maxWidth
        val widthBounded = availW != Constraints.Infinity

        // Pass 1: measure at the available width to learn the natural height.
        val firstConstraints = Constraints(
            minWidth = 0,
            maxWidth = if (widthBounded) availW else Constraints.Infinity,
            minHeight = 0,
            maxHeight = Constraints.Infinity,
        )
        val firstPass = subcompose("content", content).map { it.measure(firstConstraints) }
        val contentW = firstPass.maxOfOrNull { it.width } ?: 0
        val naturalH = firstPass.maxOfOrNull { it.height } ?: 0

        // Scale factor: shrink only if taller than the available space; never enlarge.
        val scale = if (naturalH > 0 && availH != Constraints.Infinity)
            minOf(1f, availH.toFloat() / naturalH.toFloat()) else 1f

        val layoutW = if (widthBounded) availW else contentW
        val layoutH = if (availH != Constraints.Infinity) availH else naturalH

        // When we have to shrink, a UNIFORM down-scale would also shrink the width,
        // leaving side gaps. To keep the content edge-to-edge, re-measure it at a
        // width inflated by 1/scale, then place it scaled from the TOP-LEFT — so the
        // scaled-down result spans the full available width. (A pure visual column;
        // re-composing it for measurement has no side effects.)
        val placeables = if (scale < 1f && widthBounded) {
            val wideW = kotlin.math.ceil(availW / scale).toInt().coerceAtLeast(availW)
            val wideConstraints = Constraints(
                minWidth = 0, maxWidth = wideW, minHeight = 0, maxHeight = Constraints.Infinity,
            )
            subcompose("content-wide", content).map { it.measure(wideConstraints) }
        } else {
            firstPass
        }

        layout(layoutW, layoutH) {
            placeables.forEach { placeable ->
                placeable.placeRelativeWithLayer(0, 0) {
                    scaleX = scale
                    scaleY = scale
                    // Top-left origin: the (over-wide) content scales down to fill
                    // from the left edge to the right edge, anchored at the top.
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }
            }
        }
    }
}

@Composable
private fun TabBody(
    spacing: androidx.compose.ui.unit.Dp = 14.dp,
    scrollToTopSignal: Int = 0,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal > 0) scrollState.animateScrollTo(0)
    }
    val thumbColor = AbOnSurfaceMuted.copy(alpha = 0.5f)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        // Subtle always-visible scrollbar on the right edge. Only paints a thumb
        // when the content actually overflows (maxValue > 0) — short screens that
        // fit show nothing. Tells the user "there's more below" without the
        // confusion of an invisible scroll region. NOT used on the Remote /
        // Trackpad / Air Mouse control surfaces (those don't use TabBody).
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    val max = scrollState.maxValue
                    if (max > 0) {
                        val trackTop = 8.dp.toPx()
                        val trackBottom = size.height - 8.dp.toPx()
                        val trackH = (trackBottom - trackTop).coerceAtLeast(1f)
                        val viewport = size.height
                        // Thumb height proportional to visible/total; clamped to a
                        // usable minimum so it's always grabbable-looking.
                        val total = viewport + max
                        val thumbH = (trackH * (viewport / total)).coerceIn(28.dp.toPx(), trackH)
                        val frac = scrollState.value.toFloat() / max.toFloat()
                        val thumbY = trackTop + (trackH - thumbH) * frac
                        val w = 4.dp.toPx()
                        val x = size.width - w - 3.dp.toPx()
                        drawRoundRect(
                            color = thumbColor,
                            topLeft = androidx.compose.ui.geometry.Offset(x, thumbY),
                            size = androidx.compose.ui.geometry.Size(w, thumbH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f),
                        )
                    }
                },
        )
    }
}

/**
 * Copies the [sourceStyle]'s gradient COLORS (colorA, colorB, gradientType) to
 * the other two customizable surfaces (dock / top bar / folder window), so they
 * all match in one tap. Each surface keeps its OWN corner radius (their shapes
 * differ), so only the colors + gradient type are propagated. Confirms first.
 */
@Composable
private fun MatchAllSurfacesButton(
    sourceLabel: String,
    sourceStyle: com.portalpad.app.data.BarStyle,
) {
    val app = PortalPadApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    androidx.compose.material3.TextButton(onClick = { confirm = true }) {
        Text("Match all surfaces to this")
    }
    if (confirm) {
        ResetConfirmDialog(
            title = "Match all surfaces to the $sourceLabel?",
            message = "This copies the $sourceLabel's gradient colors and type to the dock, top bar, and folder window so they all match. Each keeps its own corner radius. You can still adjust each one afterward.",
            onConfirm = {
                confirm = false
                scope.launch {
                    fun apply(s: com.portalpad.app.data.BarStyle) = s.copy(
                        colorA = sourceStyle.colorA,
                        colorB = sourceStyle.colorB,
                        gradientType = sourceStyle.gradientType,
                    )
                    val dock = prefs.dockConfig.first()
                    prefs.setDockConfig(dock.copy(style = apply(dock.style)))
                    val top = prefs.topBarConfig.first()
                    prefs.setTopBarConfig(top.copy(style = apply(top.style)))
                    val fw = prefs.folderWindowConfig.first()
                    prefs.setFolderWindowConfig(fw.copy(background = apply(fw.background)))
                }
            },
            onCancel = { confirm = false },
        )
    }
}

@Composable
private fun BarStyleControls(
    style: com.portalpad.app.data.BarStyle,
    onChange: (com.portalpad.app.data.BarStyle) -> Unit,
) {
    var expanded by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    // Collapsible header so each customization page stays scannable: a tap target
    // with the section name + a live swatch (when collapsed) that expands the full
    // editor. The swatch promotes to the large preview once open.
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Background gradient",
            color = AbAccent,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (!expanded) {
            Box(
                Modifier
                    .width(56.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(com.portalpad.app.ui.common.toComposeBrush(style))
                    .border(1.dp, AbSurfaceElevated, RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            if (expanded) "▾" else "▸",
            color = AbAccent,
            style = MaterialTheme.typography.titleMedium,
        )
    }

    if (expanded) {
        // Large live preview — honors type, geometry, and balance via the same
        // builder every surface uses, so this is exactly what they render.
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(style.cornerRadiusDp.dp))
                .background(com.portalpad.app.ui.common.toComposeBrush(style))
                .border(1.dp, AbSurfaceElevated, RoundedCornerShape(style.cornerRadiusDp.dp)),
        )

        // Gradient type selector.
        Text("Gradient type", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.portalpad.app.data.BarGradientType.values().forEach { t ->
                val selected = style.gradientType == t
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) AbAccent else AbSurfaceElevated)
                        .clickable { onChange(style.copy(gradientType = t)) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        t.name.lowercase().replaceFirstChar { it.uppercase() },
                        color = if (selected) AbBackground else AbOnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // Per-type geometry, revealed contextually.
        when (style.gradientType) {
            com.portalpad.app.data.BarGradientType.LINEAR -> {
                // 8 compass steps (45°) — matched by both the Compose surfaces and
                // the GradientDrawable top bar (it only supports 8 orientations).
                val deg = (((style.angleDeg % 360) + 360) % 360)
                val arrow = when (deg / 45) {
                    0 -> "↓"; 1 -> "↘"; 2 -> "→"; 3 -> "↗"; 4 -> "↑"; 5 -> "↖"; 6 -> "←"; else -> "↙"
                }
                Text("Direction: $arrow  $deg°", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = (deg / 45).toFloat(),
                    onValueChange = { n -> onChange(style.copy(angleDeg = ((n + 0.5f).toInt() % 8) * 45)) },
                    valueRange = 0f..7f,
                    steps = 6,
                    colors = sliderColors(),
                )
            }
            com.portalpad.app.data.BarGradientType.RADIAL -> {
                Text("Center X: ${style.centerXPct}%", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = style.centerXPct.coerceIn(0, 100).toFloat(),
                    onValueChange = { n -> onChange(style.copy(centerXPct = n.toInt().coerceIn(0, 100))) },
                    valueRange = 0f..100f, steps = 0, colors = sliderColors(),
                )
                Text("Center Y: ${style.centerYPct}%", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = style.centerYPct.coerceIn(0, 100).toFloat(),
                    onValueChange = { n -> onChange(style.copy(centerYPct = n.toInt().coerceIn(0, 100))) },
                    valueRange = 0f..100f, steps = 0, colors = sliderColors(),
                )
                Text("Spread: ${style.radiusPct}%  (50 = original)", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = style.radiusPct.coerceIn(10, 150).toFloat(),
                    onValueChange = { n -> onChange(style.copy(radiusPct = n.toInt().coerceIn(10, 150))) },
                    valueRange = 10f..150f, steps = 0, colors = sliderColors(),
                )
            }
            com.portalpad.app.data.BarGradientType.CONICAL -> {
                Text("Center X: ${style.centerXPct}%", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = style.centerXPct.coerceIn(0, 100).toFloat(),
                    onValueChange = { n -> onChange(style.copy(centerXPct = n.toInt().coerceIn(0, 100))) },
                    valueRange = 0f..100f, steps = 0, colors = sliderColors(),
                )
                Text("Center Y: ${style.centerYPct}%", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = style.centerYPct.coerceIn(0, 100).toFloat(),
                    onValueChange = { n -> onChange(style.copy(centerYPct = n.toInt().coerceIn(0, 100))) },
                    valueRange = 0f..100f, steps = 0, colors = sliderColors(),
                )
            }
        }

        // The two color points.
        ColorPointEditor("Color point A", style.colorA) { onChange(style.copy(colorA = it)) }
        ColorPointEditor("Color point B", style.colorB) { onChange(style.copy(colorB = it)) }

        // Flip A/B — swap the two gradient stops (reverses the gradient direction).
        androidx.compose.material3.TextButton(
            onClick = { onChange(style.copy(colorA = style.colorB, colorB = style.colorA)) },
        ) {
            Text("Flip color points (A ⇄ B)")
        }

        // Color balance — where the A↔B transition sits (50% = even). All types.
        Text(
            "Color balance: ${style.midpointPct}%  (50 = even)",
            color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = style.midpointPct.coerceIn(5, 95).toFloat(),
            onValueChange = { n -> onChange(style.copy(midpointPct = n.toInt().coerceIn(5, 95))) },
            valueRange = 5f..95f,
            steps = 0,
            colors = sliderColors(),
        )

        Text(
            "Corner radius: ${style.cornerRadiusDp}dp",
            color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = style.cornerRadiusDp.toFloat(),
            onValueChange = { n -> onChange(style.copy(cornerRadiusDp = n.toInt())) },
            valueRange = 0f..40f,
            steps = 0,
            colors = sliderColors(),
        )
        androidx.compose.material3.TextButton(
            onClick = { onChange(com.portalpad.app.data.BarStyle()) },
        ) {
            Text("Reset to defaults")
        }
    }
}

/**
 * Edits a single ARGB color: a tappable swatch that expands a 2D color picker
 * (saturation/value square + hue strip) plus an opacity slider. [argb] is
 * 0xAARRGGBB as a Long; [onChange] returns the new Long.
 */
@Composable
private fun ColorPointEditor(label: String, argb: Long, onChange: (Long) -> Unit) {
    val a = ((argb shr 24) and 0xFF).toInt()
    val r = ((argb shr 16) and 0xFF).toInt()
    val g = ((argb shr 8) and 0xFF).toInt()
    val b = (argb and 0xFF).toInt()
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(r, g, b, hsv)
    var showDialog by androidx.compose.runtime.remember { mutableStateOf(false) }

    fun rebuild(h: Float, s: Float, v: Float, alpha: Int): Long {
        val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
        val rr = (rgb shr 16) and 0xFF
        val gg = (rgb shr 8) and 0xFF
        val bb = rgb and 0xFF
        return ((alpha.toLong() and 0xFF) shl 24) or
            ((rr.toLong()) shl 16) or ((gg.toLong()) shl 8) or (bb.toLong())
    }

    // Compact page row: swatch + label + opacity %, tap to open the picker popup.
    // The full editor (SV square, hue, hex/RGB, opacity) lives in the dialog so it
    // doesn't eat vertical space on the settings page.
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { showDialog = true }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(argb.toInt()))
                .border(1.dp, AbSurfaceElevated, RoundedCornerShape(7.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = AbOnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${(a * 100 / 255)}%", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        Text("\u203A", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodyLarge)
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.94f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(AbBackground)
                    .border(1.dp, AbSurfaceElevated, RoundedCornerShape(18.dp)),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    // Header: live swatch + label + Done.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color(argb.toInt()))
                                .border(1.dp, AbSurfaceElevated, RoundedCornerShape(7.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            label,
                            color = AbOnSurface,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "Done",
                            color = AbAccent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showDialog = false }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    // Saturation/Value square for the current hue.
                    SVPicker(
                        hue = hsv[0], sat = hsv[1], value = hsv[2],
                        onChange = { s, v -> onChange(rebuild(hsv[0], s, v, a)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    // Hue strip.
                    HueStrip(hue = hsv[0], onChange = { h -> onChange(rebuild(h, hsv[1], hsv[2], a)) })
                    Spacer(Modifier.height(10.dp))
                    // Manual entry: hex (#RRGGBB) + R/G/B. Two-way synced with the picker;
                    // alpha stays controlled by the opacity slider below.
                    ColorCodeFields(
                        r = r, g = g, b = b,
                        onRgb = { nr, ng, nb ->
                            onChange(((a.toLong() and 0xFF) shl 24) or (nr.toLong() shl 16) or (ng.toLong() shl 8) or nb.toLong())
                        },
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("Opacity: ${(a * 100 / 255)}%", color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = a.toFloat(), onValueChange = { onChange(rebuild(hsv[0], hsv[1], hsv[2], it.toInt())) },
                        valueRange = 0f..255f, colors = sliderColors(),
                    )
                }
            }
        }
    }
}

/**
 * Hex (#RRGGBB) + R/G/B numeric entry, synced to the current color. Each field
 * shows the current value; valid edits call [onRgb] with the new 0–255 channels.
 * Invalid/partial input doesn't apply (the field just holds what you typed until
 * it parses), so typing never makes the picker jump.
 */
@Composable
private fun ColorCodeFields(r: Int, g: Int, b: Int, onRgb: (Int, Int, Int) -> Unit) {
    val hexNow = String.format("#%02X%02X%02X", r, g, b)
    // Local editable text seeded from the current color; resynced when the color
    // changes from elsewhere (picker drag) via the key on the current value.
    var hexText by androidx.compose.runtime.remember(hexNow) { mutableStateOf(hexNow) }

    androidx.compose.material3.OutlinedTextField(
        value = hexText,
        onValueChange = { txt ->
            hexText = txt
            val cleaned = txt.trim().removePrefix("#")
            if (cleaned.length == 6 && cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                val v = cleaned.toLong(16)
                onRgb(((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt())
            }
        },
        label = { Text("Hex") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = AbOnSurface, unfocusedTextColor = AbOnSurface,
            focusedBorderColor = AbAccent, unfocusedBorderColor = AbSurfaceElevated,
            focusedLabelColor = AbAccent, unfocusedLabelColor = AbOnSurfaceMuted,
            cursorColor = AbAccent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val chan: @Composable (String, Int, (Int) -> Unit) -> Unit = { lbl, cur, set ->
            var t by androidx.compose.runtime.remember(cur) { mutableStateOf(cur.toString()) }
            androidx.compose.material3.OutlinedTextField(
                value = t,
                onValueChange = { txt ->
                    t = txt.filter { it.isDigit() }.take(3)
                    val n = t.toIntOrNull()
                    if (n != null && n in 0..255) set(n)
                },
                label = { Text(lbl) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AbOnSurface, unfocusedTextColor = AbOnSurface,
                    focusedBorderColor = AbAccent, unfocusedBorderColor = AbSurfaceElevated,
                    focusedLabelColor = AbAccent, unfocusedLabelColor = AbOnSurfaceMuted,
                    cursorColor = AbAccent,
                ),
                modifier = Modifier.weight(1f),
            )
        }
        chan("R", r) { onRgb(it, g, b) }
        chan("G", g) { onRgb(r, it, b) }
        chan("B", b) { onRgb(r, g, it) }
    }
}

/** Saturation (x) / Value (y) square for a fixed [hue]; reports new s,v on drag. */
@Composable
private fun SVPicker(hue: Float, sat: Float, value: Float, onChange: (Float, Float) -> Unit) {
    var sizePx by androidx.compose.runtime.remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, AbSurfaceElevated, RoundedCornerShape(10.dp))
            .onGloballyPositioned { sizePx = androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()) }
            .pointerInput(hue) {
                detectDragGestures { change, _ ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val s = (change.position.x / w).coerceIn(0f, 1f)
                    val v = (1f - change.position.y / h).coerceIn(0f, 1f)
                    onChange(s, v)
                }
            }
            .pointerInput(hue) {
                detectTapGestures { pos ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    onChange((pos.x / w).coerceIn(0f, 1f), (1f - pos.y / h).coerceIn(0f, 1f))
                }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            // White → hue across X.
            drawRect(brush = Brush.horizontalGradient(listOf(Color.White, hueColor)))
            // Transparent → black down Y.
            drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            // Selection handle.
            val hx = sat * size.width
            val hy = (1f - value) * size.height
            drawCircle(Color.White, radius = 9f, center = androidx.compose.ui.geometry.Offset(hx, hy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
            drawCircle(Color.Black, radius = 12f, center = androidx.compose.ui.geometry.Offset(hx, hy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
        }
    }
}

/** Horizontal hue strip (0..360); reports new hue on tap/drag. */
@Composable
private fun HueStrip(hue: Float, onChange: (Float) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, AbSurfaceElevated, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    onChange((change.position.x / w).coerceIn(0f, 1f) * 360f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    onChange((pos.x / w).coerceIn(0f, 1f) * 360f)
                }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val hueColors = (0..360 step 30).map {
                Color(android.graphics.Color.HSVToColor(floatArrayOf(it.toFloat(), 1f, 1f)))
            }
            drawRect(brush = Brush.horizontalGradient(hueColors))
            // Position marker.
            val mx = (hue / 360f) * size.width
            drawLine(Color.White, start = androidx.compose.ui.geometry.Offset(mx, 0f), end = androidx.compose.ui.geometry.Offset(mx, size.height), strokeWidth = 3f)
            drawLine(Color.Black, start = androidx.compose.ui.geometry.Offset(mx, 0f), end = androidx.compose.ui.geometry.Offset(mx, size.height), strokeWidth = 1f)
        }
    }
}

@Composable
private fun ResetToDefaultsButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AbSurfaceElevated)
            .border(1.dp, AbDanger.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = AbDanger, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ResetConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title, color = AbOnSurface) },
        text = { Text(message, color = AbOnSurfaceMuted) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text("Reset", color = AbDanger)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text("Cancel", color = AbOnSurface)
            }
        },
        containerColor = AbSurface,
    )
}

@Composable
private fun <T> LabeledDropdown(
    current: T,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    contentDescription: String = "Choose",
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val currentLabel = options.firstOrNull { it.first == current }?.second ?: ""
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { anchorWidthPx = it.width }
                .clip(RoundedCornerShape(12.dp))
                .background(AbSurfaceElevated)
                .border(1.dp, AbOnSurfaceDim.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                currentLabel,
                color = AbOnSurface,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = contentDescription, tint = AbOnSurfaceMuted)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(AbSurfaceElevated)
                .width(with(density) { anchorWidthPx.toDp() }),
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = if (value == current) AbAccent else AbOnSurface,
                            fontWeight = if (value == current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = { expanded = false; onSelect(value) },
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    pill: @Composable (() -> Unit)? = null,
    centeredTitleNoDot: Boolean = false,
    tightSpacing: Boolean = false,
    titleDivider: Boolean = false,
    disabled: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Box {
    Card(
        colors = CardDefaults.cardColors(containerColor = AbSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
            .then(if (disabled) Modifier.alpha(0.4f) else Modifier),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(if (tightSpacing) 5.dp else 10.dp)) {
            if (title.isNotEmpty()) {
                if (centeredTitleNoDot) {
                    Text(
                        title, color = AbOnSurface, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title, color = AbOnSurface, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        pill?.invoke()
                    }
                }
            }
            if (titleDivider && title.isNotEmpty()) {
                HorizontalDivider(color = AbSurfaceElevated, thickness = 1.dp)
            }
            content()
        }
    }
        // When disabled, overlay a transparent catcher that consumes all touch
        // so the (dimmed) controls can't be interacted with. Saved values are
        // untouched — flipping back re-enables them exactly as they were.
        if (disabled) {
            androidx.compose.foundation.layout.Box(
                Modifier.matchParentSize().clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) {},
            )
        }
    }
}

/**
 * Like [SectionCard] but the title row is a tap target that expands/collapses the
 * body, with a chevron that flips when open. Default-collapsed so the heavy
 * customization cards (dock layout, top-bar position/size/style, folder grid) stay
 * scannable until the user drills in. Same [ColumnScope] content slot as
 * [SectionCard], so swapping one for the other is mechanical.
 */
@Composable
private fun CollapsibleCard(
    title: String,
    defaultExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    val rot by animateFloatAsState(if (expanded) 180f else 0f, label = "cardChev")
    Card(
        colors = CardDefaults.cardColors(containerColor = AbSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    color = AbAccent,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = AbAccent,
                    modifier = Modifier.rotate(rot),
                )
            }
            if (expanded) { content() }
        }
    }
}

@Composable
private fun ActionRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = AbOnSurface, style = MaterialTheme.typography.bodyLarge)
            Text(value, color = AbAccent, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** One labeled row with a dropdown to pick the [GestureAction] for a swipe
 *  direction. */
@Composable
private fun GestureActionRow(
    label: String,
    selected: com.portalpad.app.data.GestureAction,
    target: String,
    appEntry: com.portalpad.app.data.AppEntry? = null,
    onPick: (com.portalpad.app.data.GestureAction) -> Unit,
) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val launchesApp = selected == com.portalpad.app.data.GestureAction.LAUNCH_APP && appEntry != null
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = AbOnSurface,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.width(110.dp),
        )
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AbSurfaceElevated)
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // When this direction launches an app/activity, show the app icon
                // + app label (+ activity name) so it's clear which app it is.
                if (launchesApp) {
                    com.portalpad.app.ui.common.AssignedActionLabel(
                        entry = appEntry, fallback = selected.label,
                        iconSizeDp = 28, modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        selected.label,
                        color = AbOnSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "Choose action",
                    tint = AbOnSurfaceMuted,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(AbSurfaceElevated),
            ) {
                com.portalpad.app.data.GestureAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                action.label,
                                color = if (action == selected) AbAccent else AbOnSurface,
                                fontWeight = if (action == selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            expanded = false
                            if (action == com.portalpad.app.data.GestureAction.LAUNCH_APP) {
                                // Open the app picker; it writes the gesture app
                                // pref (and sets this direction to LAUNCH_APP).
                                com.portalpad.app.ui.dock.launchDockPickerOnPhone(ctx, target)
                            } else {
                                onPick(action)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The trackpad-surface controls, shared by the Trackpad interface and the Air
 *  Mouse interface (which also uses the surface for taps/scroll). Order: tap,
 *  finger dots, speeds, invert scroll, edge-scroll strip + side. */
@Composable
private fun TrackpadSurfaceControls() {
    ToggleRowWithDesc(
        label = "Tap to click",
        desc = "ON: a quick tap on the trackpad surface fires a left click. OFF: trackpad surface is motion-only — use the dedicated Left / Right click buttons. Turn OFF if you frequently get accidental clicks while positioning the cursor.",
        key = PreferencesRepository.Keys.TAP_TO_CLICK,
        default = true,
    )
    ToggleRowWithDesc(
        label = "Show finger dots",
        desc = "Render a visible dot under each finger touching the trackpad surface.",
        key = PreferencesRepository.Keys.SHOW_FINGER_DOTS,
        default = false,
    )
    SpeedSlider(
        label = "Cursor speed",
        desc = "How fast the cursor moves relative to your finger.",
        key = PreferencesRepository.Keys.CURSOR_SPEED,
        default = 1.0f,
        range = 0.4f..2.5f,
    )
    SpeedSlider(
        label = "Cursor smoothing",
        desc = "Eases the cursor toward your finger to glide over occasional dropped frames (most noticeable on the wide ultrawide canvas). Off by default — no added latency. Higher = smoother but slightly laggier.",
        key = PreferencesRepository.Keys.CURSOR_SMOOTHING,
        default = 0f,
        range = 0f..0.9f,
    )
    SpeedSlider(
        label = "Pointer acceleration",
        desc = "How much the cursor speeds up when you move your finger faster, like a real mouse — slow moves stay precise, quick flicks cover more screen. Set to 0 for a constant speed.",
        key = PreferencesRepository.Keys.POINTER_ACCEL,
        default = 1.0f,
        range = 0f..2f,
    )
    SpeedSlider(
        label = "Scroll speed",
        desc = "How far content scrolls per swipe on the trackpad or edge strip.",
        key = PreferencesRepository.Keys.SCROLL_SPEED,
        default = 1.0f,
        range = 0.4f..3.0f,
    )
    ToggleRowWithDesc(
        label = "Invert scroll direction",
        desc = "Reverse the scroll direction (natural vs. traditional).",
        key = PreferencesRepository.Keys.INVERT_SCROLL,
        default = false,
    )
    ToggleRowWithDesc(
        label = "Edge scroll strip",
        desc = "Show a scroll strip (marked with horizontal lines) along one edge of the trackpad. Drag up/down on it to scroll, without needing the two-finger scroll gesture. Two-finger scroll still works everywhere else on the trackpad.",
        key = PreferencesRepository.Keys.EDGE_SCROLL,
        default = true,
    )
    EdgeScrollSideRadio()
}

/** Radio Left/Right chooser for which side the edge-scroll strip sits on.
 *  Shown under the "Edge scroll strip" toggle; only meaningful when it's on. */
@Composable
private fun EdgeScrollSideRadio() {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val side by app.prefs.string(PreferencesRepository.Keys.EDGE_SCROLL_SIDE, "right")
        .collectAsState(initial = "right")

    @Composable
    fun option(label: String, value: String) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { scope.launch { app.prefs.setString(PreferencesRepository.Keys.EDGE_SCROLL_SIDE, value) } }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.RadioButton(
                selected = side == value,
                onClick = { scope.launch { app.prefs.setString(PreferencesRepository.Keys.EDGE_SCROLL_SIDE, value) } },
                colors = androidx.compose.material3.RadioButtonDefaults.colors(
                    selectedColor = AbPrimary,
                    unselectedColor = AbOnSurfaceMuted,
                ),
            )
            Spacer(Modifier.width(4.dp))
            Text(label, color = AbOnSurface, style = MaterialTheme.typography.bodyMedium)
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Strip side:", color = AbOnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(end = 4.dp),
        )
        option("Left", "left")
        option("Right", "right")
    }
}

/** A small pill button with a leading store/source logo (GitHub, Play, F-Droid)
 *  and a label, used in the Resources section. The logo is a vector drawable;
 *  Play Store keeps its own colors, so the icon is drawn untinted. */
@Composable
private fun ResourceLinkButton(iconRes: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AbSurfaceElevated)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = AbOnSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Open a web URL in the browser. */
private fun openUrl(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Open an app's Play Store page, falling back to the web URL if the Play
 *  app isn't present. */
private fun openStore(ctx: android.content.Context, packageName: String) {
    val market = android.net.Uri.parse("market://details?id=$packageName")
    val web = android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, market)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(intent) }
    catch (_: android.content.ActivityNotFoundException) {
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, web)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    key: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
    default: Boolean = false,
) {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val value by app.prefs.bool(key, default).collectAsState(initial = default)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = AbOnSurface, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = value,
            onCheckedChange = { scope.launch { app.prefs.setBool(key, it) } },
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
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = AbPrimaryBright,
    activeTrackColor = AbPrimary,
    inactiveTrackColor = AbSurfaceElevated,
)

/**
 * Auto-disable timeout selector. 0 = never. Built as labeled chips so the user
 * can see the options at a glance — slider would be ambiguous for a setting
 * where "1 minute" and "5 minutes" matter precisely.
 */
@Composable
private fun RestoreLastSessionRow() {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val session by app.prefs.lastSession.collectAsState(initial = com.portalpad.app.data.SavedSession())
    val externalDisplayId by app.externalDisplayId.collectAsState()
    // Observe Shizuku status so the button reacts live when privilege drops/returns.
    val shizukuStatus by app.shizuku.status.collectAsState()
    // Privileged launches need Shizuku OR Root ready (matches hasLaunchPrivilege).
    val accessReady = shizukuStatus == com.portalpad.app.shizuku.ShizukuManager.Status.READY || app.root.isReady
    val count = session.windows.size

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "Restore last session",
            color = AbOnSurface, style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Reopens the apps that were on your external display, arranged where they were. Works in desktop-windows mode (it remembers your window layout); in single-app mode there's no multi-window layout to save. App contents aren't restored (the system closes apps when the display disconnects) — this brings back your layout.",
            color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        val canRestore = count > 0 && externalDisplayId != null && accessReady
        val subtitle = when {
            count == 0 -> "No saved session yet."
            !accessReady -> "$count app${if (count == 1) "" else "s"} saved — connect Shizuku or Root to restore."
            externalDisplayId == null -> "$count app${if (count == 1) "" else "s"} saved — connect a display to restore."
            else -> "$count app${if (count == 1) "" else "s"} saved."
        }
        Text(subtitle, color = AbAccent, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (canRestore) AbPrimary else AbSurfaceElevated)
                .then(
                    if (canRestore) Modifier.clickable {
                        val displayId = externalDisplayId ?: return@clickable
                        android.widget.Toast.makeText(ctx, "Restoring $count app${if (count == 1) "" else "s"}…", android.widget.Toast.LENGTH_SHORT).show()
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
                                val disp = dm?.getDisplay(displayId)
                                val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") disp?.getRealMetrics(it) }
                                val w = m.widthPixels.coerceAtLeast(1).let { if (it <= 1) 1920 else it }
                                val h = m.heightPixels.coerceAtLeast(1).let { if (it <= 1) 1080 else it }
                                runCatching { app.freeform.restoreSession(session, displayId, w, h) }
                            }
                        }
                    } else Modifier
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                "Restore session",
                color = if (canRestore) AbPrimaryBright else AbOnSurfaceDim,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AutoDisableRow() {
    val app = PortalPadApp.instance
    val scope = rememberCoroutineScope()
    val raw by app.prefs.rawDataStore.data.collectAsState(initial = null)
    val current = raw?.get(PreferencesRepository.Keys.AUTO_DISABLE_AFTER_MIN) ?: 0

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "Auto-disable after no display",
            color = AbOnSurface, style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Stops the service automatically if no external display has been connected for the chosen duration. Saves battery if you forget to disable manually.",
            color = AbOnSurfaceMuted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        val options = listOf(0 to "Never", 5 to "5 min", 15 to "15 min", 60 to "1 hour")
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (mins, label) ->
                val sel = current == mins
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (sel) AbPrimary else AbSurfaceElevated)
                        .clickable {
                            scope.launch {
                                app.prefs.rawDataStore.edit {
                                    it[PreferencesRepository.Keys.AUTO_DISABLE_AFTER_MIN] = mins
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        label,
                        color = if (sel) AbOnSurface else AbOnSurfaceMuted,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessModeSelector(
    accessMode: AccessMode,
    shizukuReady: Boolean,
    rootReady: Boolean,
    onSelect: (AccessMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val label = when (accessMode) {
        AccessMode.NONE -> "None"
        AccessMode.SHIZUKU -> "Shizuku"
        AccessMode.ROOT -> "Root"
    }
    Box(Modifier.fillMaxWidth()) {
        // Tappable selector row styled like a dropdown trigger. We avoid
        // ExposedDropdownMenuBox / ExposedDropdownMenu because availability of
        // those across material3 versions is fiddly; a plain Row + DropdownMenu
        // is robust and looks the same. The menu is given the anchor's measured
        // width so it lines up under the button instead of opening narrow.
        Row(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { anchorWidthPx = it.width }
                .clip(RoundedCornerShape(10.dp))
                .background(AbSurfaceElevated)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = AbOnSurface,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Open privilege source menu",
                tint = AbOnSurfaceMuted,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(AbSurfaceElevated)
                .width(with(density) { anchorWidthPx.toDp() }),
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("None", color = AbOnSurface)
                        Text(
                            "No privileged access. Driving an external display needs Shizuku or Root.",
                            color = AbOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                onClick = { expanded = false; onSelect(AccessMode.NONE) },
            )
            HorizontalDivider(
                color = AbOnSurfaceDim.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 3.dp),
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Shizuku", color = AbOnSurface)
                        Text(
                            "No root needed. Requires the Shizuku app installed and authorized, then it creates the display and injects input.",
                            color = AbOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                onClick = { expanded = false; onSelect(AccessMode.SHIZUKU) },
            )
            HorizontalDivider(
                color = AbOnSurfaceDim.copy(alpha = 0.3f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 3.dp),
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Root", color = AbOnSurface)
                        Text(
                            "For rooted devices. Runs a privileged service (su) that creates the display and injects input — full parity with Shizuku.",
                            color = AbOnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                onClick = { expanded = false; onSelect(AccessMode.ROOT) },
            )
        }
    }
}

/**
 * "Reset to factory defaults" row + confirmation dialog. Clears the entire
 * DataStore (every pref, dock config, all backed-up state) AND wipes on-disk
 * state (custom wallpapers, screenshots, saved logs), stops the foreground
 * service, then schedules an automatic relaunch and kills the process so it
 * restarts clean with defaults everywhere. While the reset runs, a spinning
 * PortalPad logo overlay is shown so the user sees progress instead of being
 * dropped to the home screen.
 */
@Composable
private fun FactoryResetRow() {
    val scope = rememberCoroutineScope()
    var showConfirm by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    // 0..1 progress + a label for the current step, driven through the reset.
    var resetProgress by remember { mutableFloatStateOf(0f) }
    var resetStep by remember { mutableStateOf("Starting…") }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AbAccent.copy(alpha = 0.12f))
            .clickable { showConfirm = true }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Reset to factory defaults", color = AbAccent, fontWeight = FontWeight.SemiBold)
            Text(
                "Clears everything — dock items, folders, all toggles, custom wallpapers, screenshots and logs. The app restarts fresh.",
                color = AbOnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = AbSurfaceElevated,
            title = { Text("Reset everything?", color = AbOnSurface) },
            text = {
                Text(
                    "This clears every PortalPad preference, the dock and all folders, plus custom wallpapers, screenshots and saved logs. The app will restart automatically. This can't be undone.",
                    color = AbOnSurfaceMuted,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showConfirm = false
                        resetting = true
                        scope.launch {
                            val app = PortalPadApp.instance
                            // 1) Clear DataStore preferences (every key).
                            resetStep = "Clearing settings…"; resetProgress = 0.15f
                            runCatching { app.prefs.clearAllForFactoryReset() }
                            kotlinx.coroutines.delay(250)
                            // 2) Wipe on-disk state DataStore doesn't cover:
                            //    custom wallpapers, screenshots, saved logs.
                            resetStep = "Removing wallpapers, screenshots & logs…"; resetProgress = 0.45f
                            runCatching {
                                java.io.File(app.filesDir, "wallpapers").deleteRecursively()
                                app.getExternalFilesDir(null)?.let { ext ->
                                    java.io.File(ext, "screenshots").deleteRecursively()
                                    java.io.File(ext, "logs").deleteRecursively()
                                }
                            }
                            kotlinx.coroutines.delay(250)
                            // 3) Stop the foreground service so it doesn't
                            //    re-create state from the now-cleared prefs.
                            resetStep = "Stopping services…"; resetProgress = 0.7f
                            runCatching {
                                app.stopService(
                                    android.content.Intent(
                                        app, com.portalpad.app.service.PortalPadForegroundService::class.java,
                                    ),
                                )
                            }
                            // 4) Unbind privileged backends cleanly.
                            runCatching { app.shizukuClickBackend.unbind() }
                            runCatching { app.rootClickBackend.unbind() }
                            kotlinx.coroutines.delay(250)
                            // 5) Schedule an automatic relaunch of the launcher
                            //    activity ~250ms out and kill the process. The
                            //    alarm brings the app back so the user lands in a
                            //    fresh PortalPad, not the home screen.
                            resetStep = "Restarting PortalPad…"; resetProgress = 1f
                            kotlinx.coroutines.delay(500)
                            runCatching {
                                val launch = app.packageManager
                                    .getLaunchIntentForPackage(app.packageName)
                                    ?.addFlags(
                                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                                    )
                                if (launch != null) {
                                    val pi = android.app.PendingIntent.getActivity(
                                        app, 0, launch,
                                        android.app.PendingIntent.FLAG_CANCEL_CURRENT or
                                            android.app.PendingIntent.FLAG_IMMUTABLE,
                                    )
                                    val am = app.getSystemService(android.content.Context.ALARM_SERVICE)
                                        as android.app.AlarmManager
                                    am.set(
                                        android.app.AlarmManager.RTC,
                                        System.currentTimeMillis() + 250,
                                        pi,
                                    )
                                }
                            }
                            kotlinx.coroutines.delay(150)
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    },
                ) { Text("Reset", color = AbAccent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel", color = AbOnSurfaceMuted)
                }
            },
        )
    }

    // Full-screen spinning-logo overlay shown while the reset runs (until the
    // process is killed). Covers the work so the user sees progress.
    if (resetting) {
        val transition = rememberInfiniteTransition(label = "reset-spin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(
                    durationMillis = 900,
                    easing = androidx.compose.animation.core.LinearEasing,
                ),
            ),
            label = "reset-angle",
        )
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(AbBackground.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 40.dp),
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(
                            com.portalpad.app.R.drawable.ic_launcher_foreground,
                        ),
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .graphicsLayer { rotationZ = angle },
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "Resetting PortalPad",
                        color = AbOnSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    // Live step description — says exactly what's happening.
                    Text(
                        resetStep,
                        color = AbOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(20.dp))
                    // Thin determinate bar that fills through the real steps.
                    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = resetProgress,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
                        label = "reset-progress",
                    )
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = AbPrimaryBright,
                        trackColor = AbSurfaceElevated,
                    )
                }
            }
        }
    }
}
