plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * A throwaway probe, deliberately separate from the companion.
 *
 * It exists to answer one question: does an APK signed with the AOSP platform test key
 * actually receive signature-level permissions on this head unit? The companion itself
 * stays debug-signed and unprivileged until that is answered.
 *
 * The probe only reads. It never resizes a task, never moves a window, never writes a
 * setting. Signing happens after the build, with apksigner and the AOSP key — see
 * tools/build-probe.sh.
 */
android {
    namespace = "io.github.miklergm.privprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.miklergm.privprobe"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-probe"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
