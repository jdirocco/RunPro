import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

class RobolectricOfflineArguments(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val sdkDir: Provider<Directory>
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf(
        "-Drobolectric.offline=true",
        "-Drobolectric.dependency.dir=${sdkDir.get().asFile.absolutePath}"
    )
}

// Separate configurations avoid Gradle version conflict resolution across API levels
// that share the org.robolectric:android-all-instrumented coordinates.
val robolectricSdk30 = configurations.create("robolectricSdk30") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val robolectricSdk34 = configurations.create("robolectricSdk34") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val prepareRobolectricSdks = tasks.register<Sync>("prepareRobolectricSdks") {
    from(robolectricSdk30)
    from(robolectricSdk34)
    into(layout.buildDirectory.dir("robolectric-sdks"))
}

val robolectricSdksDir = layout.dir(prepareRobolectricSdks.map { it.destinationDir })

android {
    namespace = "com.runpro.wear"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.runpro.wear"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.dependsOn(prepareRobolectricSdks)
                test.jvmArgumentProviders.add(RobolectricOfflineArguments(robolectricSdksDir))
            }
        }
    }
    lint {
        warningsAsErrors = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core)
    implementation(libs.androidx.wear.ongoing)
    implementation(project(":core:recording"))
    implementation(project(":core:sync"))
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.health.services)
    constraints {
        // Play services brings Fragment 1.0; registerForActivityResult needs 1.3+ (lint, ADR 0010).
        implementation(libs.androidx.fragment)
    }
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    robolectricSdk30(libs.robolectric.android.all.api30)
    robolectricSdk34(libs.robolectric.android.all.api34)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
