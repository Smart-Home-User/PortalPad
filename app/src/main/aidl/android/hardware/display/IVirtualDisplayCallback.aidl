package android.hardware.display;

/**
 * Re-declaration of the framework's @hide IVirtualDisplayCallback so the build
 * generates a Stub we can subclass in PortalPad's OWN process. The display's
 * lifetime in DisplayManagerService is tied (via death recipient) to this
 * callback binder — so hosting it here, instead of in the Shizuku shell
 * process, is what makes the trusted VirtualDisplay app-owned and survive
 * Shizuku being stopped. The interface descriptor + transaction order match the
 * framework's, so the cross-process binder contract is compatible.
 *
 * @hide
 */
oneway interface IVirtualDisplayCallback {
    void onPaused();
    void onResumed();
    void onStopped();
}
