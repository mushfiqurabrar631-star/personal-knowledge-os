import java.util.Properties

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

val screenshotsDir: String = rootProject.layout.projectDirectory.dir("docs/screenshots/m1").asFile.absolutePath

// Debug signing key comes from local.properties (never committed). Without it, the standard Android debug key is used.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val pkosDebugKeystore: File? = localProps.getProperty("pkos.debugKeystore")?.let { file(it) }?.takeIf { it.exists() }

// Release signing (M8). The owner keeps the release keystore and passwords on their own computer only. Values come from
// environment variables (CI secrets) or local.properties — never from a committed file. Without them the release build
// is produced unsigned, exactly as before.
fun releaseProp(env: String, key: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(key)?.takeIf { it.isNotBlank() }
val pkosReleaseKeystore: File? = releaseProp("PKOS_RELEASE_STORE_FILE", "pkos.releaseKeystore")?.let { file(it) }?.takeIf { it.exists() }
val pkosReleaseStorePassword = releaseProp("PKOS_RELEASE_STORE_PASSWORD", "pkos.releaseStorePassword")
val pkosReleaseKeyAlias = releaseProp("PKOS_RELEASE_KEY_ALIAS", "pkos.releaseKeyAlias")
val pkosReleaseKeyPassword = releaseProp("PKOS_RELEASE_KEY_PASSWORD", "pkos.releaseKeyPassword") ?: pkosReleaseStorePassword
val releaseSigningReady = pkosReleaseKeystore != null && pkosReleaseStorePassword != null && pkosReleaseKeyAlias != null

// ABI splits make per-device APKs (arm64 etc.) much smaller than the universal APK. Off with -Ppkos.noAbiSplits=true.
// An app bundle (.aab) does its own ABI split on Google Play and cannot be built together with APK splits
// (issuetracker 402800800), so build `bundleRelease` in its own Gradle run.
val bundleRequested = gradle.startParameter.taskNames.any { it.substringAfterLast(':').startsWith("bundle") }
val abiSplits = (findProperty("pkos.noAbiSplits") as String?)?.toBoolean() != true && !bundleRequested

android {
    namespace = "com.pkos.app"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.pkos.app"
        minSdk = 26
        targetSdk = 37
        // 1.0.0 → 10000 (major*10000 + minor*100 + patch); higher than every earlier build (versionCode 1).
        versionCode = 10000
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (pkosDebugKeystore != null) {
            create("pkosDebug") {
                storeFile = pkosDebugKeystore
                storePassword = localProps.getProperty("pkos.debugKeystorePassword")
                keyAlias = localProps.getProperty("pkos.debugKeyAlias")
                keyPassword = localProps.getProperty("pkos.debugKeystorePassword")
            }
        }
        if (releaseSigningReady) {
            create("pkosRelease") {
                storeFile = pkosReleaseKeystore
                storePassword = pkosReleaseStorePassword
                keyAlias = pkosReleaseKeyAlias
                keyPassword = pkosReleaseKeyPassword
                enableV1Signing = false // minSdk 26: v2 + v3 signatures
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            if (pkosDebugKeystore != null) signingConfig = signingConfigs.getByName("pkosDebug")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 code shrinking + resource shrinking (M8). Keep rules for reflection/JNI users are in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("pkosRelease")
        }
        // CI-only check of the shrunk code at runtime: same R8 settings as release, debug-signed, separate app id, so
        // an emulator can install and launch it. Never distributed.
        create("r8check") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".r8check"
            versionNameSuffix = "-r8check"
            matchingFallbacks += listOf("release")
        }
    }

    splits {
        abi {
            isEnable = abiSplits
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true // one APK that runs everywhere, for simple sideloading
        }
    }

    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        // The app switches language inside Settings, so every language must be on the device.
        language { enableSplit = false }
    }

    packaging {
        resources {
            // Post-quantum parameter tables from BouncyCastle (pulled in by pdfbox-android); PKOS never uses them.
            excludes += listOf("org/bouncycastle/pqc/**/*.properties", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
        // App UI ships in Bengali (default) and English; drop library translations we don't support.
        localeFilters += listOf("bn", "en")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.systemProperty("pkos.screenshots.dir", screenshotsDir)
                it.maxHeapSize = "2g"
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.play.services.auth)
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.tess.two)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(testFixtures(project(":core:notion")))
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
