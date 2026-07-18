import java.util.Properties
import java.io.FileInputStream

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.portalpad.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.portalpad.app"
        minSdk = 30
        targetSdk = 34
        versionCode = 140
        versionName = "1.4-beta"
        // Compile timestamp for the update checker's same-tag heuristic: when
        // the installed versionName equals GitHub's latest tag but the release
        // asset carries no sha256 digest, a release published AFTER this build
        // was compiled triggers a soft "reinstall to be sure" hint. Regenerated
        // every build (slightly weaker incremental caching — accepted).
        buildConfigField("long", "BUILD_TIME_MS", "${System.currentTimeMillis()}L")
        vectorDrawables { useSupportLibrary = true }
        // Ship only 64-bit ARM. Every phone that can drive XREAL glasses (USB-C
        // DisplayPort Alt Mode, and DeX/desktop mode on top of that) is arm64-v8a —
        // 32-bit-only devices were on microUSB and can't do DP Alt Mode at all. This
        // drops the 3 unused ABI copies of ML Kit's native libs (libdigitalink.so).
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // Keep minify OFF — R8/minify strips/renames the @hide APIs reached via
            // reflection, which breaks the privileged ShellUserService paths.
            isMinifyEnabled = false
        }
    }

    lint {
        // RootShellService extends libsu's RootService (a real android.app.Service),
        // but lint can't follow the inheritance through the library and false-flags
        // it as non-instantiatable. Suppress that one check.
        disable += "Instantiatable"
        // Don't let lint gate release builds for a sideloaded hobbyist app.
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    // ── Native build for the external-mouse evdev capture lib ──
    // Builds libportalpad_mouse.so from src/main/cpp via CMake. WITHOUT this block
    // the .so is never compiled/packaged and the mouse toggle reports
    // "native-lib-not-loaded". (No CMake version pin — uses whatever the SDK has.)
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Keep the native lib UNCOMPRESSED on disk (extracted) so the Shizuku
        // shell / root process — which loads PortalPad's classes via app_process —
        // can reliably dlopen it across the process boundary.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    // QR / barcode scanner (wheel chip): ZXing core is pure-Java decode (no
    // Google-services dependency, matching the app's self-contained ethos);
    // CameraX handles preview + analysis + lifecycle.
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Kotlinx serialization for dock model persistence
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // libsu (topjohnwu / Magisk author) — RootService gives a bound binder
    // running as root (uid 0), letting us run the SAME ShellUserService over
    // root that we run over Shizuku. Battle-tested on Magisk.
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    implementation("com.github.topjohnwu.libsu:service:5.2.2")

    // WorkManager — used to schedule periodic backups
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // DocumentFile — wraps SAF tree URIs for create/list/write
    implementation("androidx.documentfile:documentfile:1.0.1")

    // ML Kit Digital Ink Recognition — handwriting recognition for the relay's
    // gesture-input mode (on-device model, downloaded on first use).
    // 19.0.0 is the 16 KB-aligned release (libdigitalink.so aligned for Android 15
    // 16 KB-page devices). Built against compileSdk 35 / AGP 8.13.2 / Gradle 8.13.
    // Does NOT resolve in the CI sandbox (Google Maven off the network allowlist) —
    // build/verify on a real machine.
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
}
