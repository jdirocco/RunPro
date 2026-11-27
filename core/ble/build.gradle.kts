plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // Parsed measurements become the model's validated samples (BleSamples).
    api(project(":core:model"))
}
