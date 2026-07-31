plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.miklergm.witscompanion"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.miklergm.witscompanion"
        // The head unit runs Android 13 (SDK 33). minSdk 29 keeps the app testable
        // on ordinary phones/tablets and emulators for Simulation mode.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0-signal-explorer"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "ru")
    }

    // Optional platform signing. The AOSP platform test key is public and matches this
    // head unit's framework certificate (verified on-device with the privilege probe), so
    // a build signed with it receives signature-level permissions — MANAGE_ACTIVITY_TASKS
    // above all — with a plain adb install and no flashing.
    //
    // The keystore is not in the repo; point to it with -PplatformKeystore=... or the
    // WITS_PLATFORM_KEYSTORE env var. Without it, the `platform` build type falls back to
    // the debug signature and the app simply runs unprivileged.
    val platformKeystore = (findProperty("platformKeystore") as String?)
        ?: System.getenv("WITS_PLATFORM_KEYSTORE")

    signingConfigs {
        if (platformKeystore != null && file(platformKeystore).exists()) {
            create("platform") {
                storeFile = file(platformKeystore)
                storePassword = (findProperty("platformStorePassword") as String?)
                    ?: System.getenv("WITS_PLATFORM_STORE_PASSWORD") ?: "android"
                keyAlias = (findProperty("platformKeyAlias") as String?)
                    ?: System.getenv("WITS_PLATFORM_KEY_ALIAS") ?: "platform"
                keyPassword = (findProperty("platformKeyPassword") as String?)
                    ?: System.getenv("WITS_PLATFORM_KEY_PASSWORD") ?: "android"
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Same code as release, but signed with the platform key when it is available.
        // The privileged window path activates at runtime only if the permission is
        // actually granted, so this build is safe to run even if signing fell back.
        create("platform") {
            initWith(getByName("release"))
            signingConfigs.findByName("platform")?.let { signingConfig = it }
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
