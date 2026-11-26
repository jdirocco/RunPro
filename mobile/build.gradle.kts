plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.runpro.mobile"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        // The Data Layer pairs apps by application id and signing key: the phone must use the watch's id
        // (ADR 0010). The namespace stays com.runpro.mobile.
        applicationId = "com.runpro.wear"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    lint {
        warningsAsErrors = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:sync"))
    implementation(libs.play.services.wearable)
}
