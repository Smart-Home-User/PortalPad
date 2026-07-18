package com.portalpad.app.diag

/**
 * Single source of truth for the in-app logcat capture's tag allowlist.
 *
 * The in-app debug page captures ONLY the tags named here (logcat matches tags
 * exactly — there is no prefix matching, so "PortalPad" does NOT pull in
 * "PortalPadFgService"). Both capture paths build their filterspec from
 * [PORTALPAD_TAGS]:
 *   - Shizuku/bound-service path: ShellUserService.DEFAULT_LOGCAT_FILTER
 *   - Root fallback path:         LogcatStreamer.ROOT_FILTER
 *
 * This list is generated from every tag PortalPad logs under (TAG constants +
 * inline Log.x("tag", ...) literals). If you add a NEW logging tag anywhere in
 * the app, add it here too or it won't appear in user-submitted in-app logs.
 * (We deliberately do NOT filter by process PID: the privileged helper runs in
 * the shell process under a different PID, so a PID filter would drop the
 * ShellUserService / RootShellService / input-injection logs that explain most
 * privilege failures.)
 *
 * Privacy note: this is strictly PortalPad's own tags — no other app's logs are
 * captured. PortalPad's own lines can still include launched package names,
 * display ids, and window titles, which is why the debug page tells users to
 * review a log before sharing it publicly.
 */
internal object LogTags {
    /** Space-separated `Tag:V` allowlist for every PortalPad tag (trailing space). */
    const val PORTALPAD_TAGS: String =
        "AirGlassesSession:V AirMouseController:V AppOwnedTrustedVD:V " +
            "BackupManager:V BackupWorker:V BootReceiver:V BtMouseController:V " +
            "BubbleActivate:V CursorOverlay:V DIAG-KEYGUARD:V DIAG-RELAY:V " +
            "DisableTransition:V DockOverlay:V EdgeStrip:V ExtinguishPowerButton:V " +
            "FlashlightController:V FloatingBubble:V FloatingKeyButton:V FolderPreview:V " +
            "FreeformManager:V Gesture:V GestureInk:V GlColorRenderer:V GlassesToast:V " +
            "IconPackManager:V InputInjector:V LaunchEntry:V LogcatScreen:V " +
            "LogcatStreamer:V MainActivity:V MediaController:V NativeMouse:V " +
            "PhoneCurtain:V " +
            "NavButtonsOverlay:V NotifListener:V NotifPanel:V PhoneExitBands:V " +
            "OverlayHost:V QuickWheel:V " +
            "PortalPadA11y:V PortalPadDisplayDiag:V PortalPadFgService:V " +
            "PortalPadRelay:V PortalPadRestore:V PortalPadSleep:V QuickSettings:V " +
            "RestoreCover:V RootClickBackend:V RootManager:V RootShellService:V " +
            "ScreenRecorder:V Screenshot:V SearchOverlay:V ShellUserService:V " +
            "ShizukuClickBackend:V PortalPadTorch:V " +
            "ShizukuManager:V ShizukuWarning:V TaskbarOverlay:V TrackpadActivity:V " +
            "TrackpadSurface:V UpdateChecker:V VDMirror:V VdRecorder:V WindowArranger:V " +
            "WindowControlBar:V "
}
