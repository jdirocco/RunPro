plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Journal records are expressed in model types.
    api(project(":core:model"))
    // Record payloads are protobuf messages private to the journal (ADR 0006).
    implementation(libs.kotlinx.serialization.protobuf)
}
