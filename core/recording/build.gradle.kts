plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // RunState exposes model types; RecordingEngine exposes StateFlow (ADR 0005).
    api(project(":core:model"))
    api(project(":core:journal"))
    api(libs.kotlinx.coroutines.core)
    // noFileStorePath reproduces the watch file system in IMU storage tests (#105).
    testImplementation(project(":tools:replay"))
    // A Stryd's GATT notifications drive a run end to end (#21).
    testImplementation(project(":core:ble"))
}
