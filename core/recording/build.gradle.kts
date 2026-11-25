plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // RunState exposes model types; RecordingEngine exposes StateFlow (ADR 0005).
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
}
