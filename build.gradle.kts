import dev.detekt.gradle.extensions.DetektExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt) apply false
}

ktlint {
    version.set(libs.versions.ktlint.engine)
}

subprojects {
    // Parent grouping projects (:core and :tools) have no sources or build logic.
    if (buildFile.exists()) {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        apply(plugin = "dev.detekt")

        extensions.configure<KtlintExtension> {
            version.set(rootProject.libs.versions.ktlint.engine)
        }
        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        }

        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            extensions.configure<DetektExtension> {
                source.from(rootProject.file("build-support/src/test/kotlin"))
            }
            extensions.configure<KotlinJvmProjectExtension> {
                jvmToolchain(21)
                sourceSets.named("test") {
                    kotlin.srcDir(rootProject.file("build-support/src/test/kotlin"))
                }
            }
            dependencies {
                "testImplementation"(rootProject.libs.kotlin.test.junit5)
                "testRuntimeOnly"(rootProject.libs.junit.jupiter.engine)
                "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
            }
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
            }
        }
        val moduleCheck = "$path:check"
        rootProject.tasks.named("check") {
            dependsOn(moduleCheck)
        }
    }
}
