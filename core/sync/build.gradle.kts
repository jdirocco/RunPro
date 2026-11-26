plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // The watch outbox sends only verified exports and deletes a journal through them (R3, ADR 0010).
    api(project(":core:fit"))
    testImplementation(project(":tools:replay"))
}
