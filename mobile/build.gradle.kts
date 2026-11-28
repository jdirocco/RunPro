import java.util.Properties
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.android.application)
}

class MobileRobolectricOfflineArguments(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val sdkDir: Provider<Directory>
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf(
        "-Drobolectric.offline=true",
        "-Drobolectric.dependency.dir=${sdkDir.get().asFile.absolutePath}"
    )
}

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

// Strava credentials for the private owner-only build (ADR 0015): read from git-ignored local.properties
// (the root file, then mobile/local.properties overriding it). Absent keys leave Strava disabled, so CI and
// ./rp check build without secrets. Never print these values.
val stravaProperties = Properties().apply {
    listOf(rootProject.file("local.properties"), file("local.properties"))
        .filter { it.isFile }
        .forEach { source -> source.inputStream().use { load(it) } }
}

fun stravaProperty(key: String, default: String = ""): String =
    stravaProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: default

fun javaStringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val stravaCallbackHost = stravaProperty("strava.callbackHost", "localhost")

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

        buildConfigField(
            "String",
            "STRAVA_CLIENT_ID",
            javaStringLiteral(stravaProperty("strava.clientId"))
        )
        buildConfigField(
            "String",
            "STRAVA_CLIENT_SECRET",
            javaStringLiteral(stravaProperty("strava.clientSecret"))
        )
        buildConfigField("String", "STRAVA_CALLBACK_HOST", javaStringLiteral(stravaCallbackHost))
        manifestPlaceholders["stravaCallbackHost"] = stravaCallbackHost
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.dependsOn(prepareRobolectricSdks)
                test.jvmArgumentProviders.add(MobileRobolectricOfflineArguments(robolectricSdksDir))
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
    implementation(project(":core:sync"))
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core)
    implementation(libs.androidx.health.connect)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.wearable)
    constraints {
        // Play services brings Fragment 1.0; registerForActivityResult needs 1.3+ (lint, ADR 0010).
        implementation(libs.androidx.fragment)
    }
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    robolectricSdk30(libs.robolectric.android.all.api30)
    robolectricSdk34(libs.robolectric.android.all.api34)
}
