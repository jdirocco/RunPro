plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Exposed through ModelJson and the @Serializable model types.
    api(libs.kotlinx.serialization.json)
}
