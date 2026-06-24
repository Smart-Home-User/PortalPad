# ---- Shizuku ----
-keep class moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }

# ---- libsu (RootService bootstrap reflects into these across processes) ----
-keep class com.topjohnwu.superuser.** { *; }
-keep class com.portalpad.app.service.RootShellService { *; }
-keep class com.portalpad.app.service.ShellUserService { *; }
-keep interface com.portalpad.app.service.IShellService { *; }
-keep class com.portalpad.app.service.IShellService$* { *; }

# ---- Conservative whole-app keep ----
# PortalPad loads service/AIDL classes across the Shizuku/root binder and leans on
# hidden framework APIs. Framework classes (android.*, com.android.internal.*) are not
# in this APK, so R8 cannot touch them; but to remove ALL risk of R8 renaming/stripping
# our own reflectively-reached code, we keep the whole app package verbatim. This does
# NOT block the main win: the unused material icons (androidx.compose.material.icons.**)
# live outside this package and are still tree-shaken down to the ~139 we reference.
# Once a minified release is verified stable on-device, these keeps can be narrowed to
# recover a little more app-code optimization.
-keep class com.portalpad.app.** { *; }

# ---- Framework-shaped AIDL we declare to talk to the hidden VirtualDisplay API ----
# Generated into our dex and passed across a binder to createVirtualDisplay, so the
# interface class/descriptor must survive verbatim. The existing rules cover
# IShellService but not this one.
-keep class android.hardware.display.IVirtualDisplayCallback { *; }
-keep class android.hardware.display.IVirtualDisplayCallback$* { *; }

# ---- kotlinx.serialization ----
# The serialization gradle plugin adds its own consumer rules, and the whole-app keep
# above already covers our @Serializable classes + generated $$serializer companions.
# Kept explicitly as a belt-and-suspenders guard.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable *;
}
-keepclassmembers class **$$serializer { *; }

# ---- ML Kit digital-ink (ships its own consumer rules; silence optional refs) ----
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
