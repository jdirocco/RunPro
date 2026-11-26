plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // Export rebuilds totals and laps with the recording engine, so FIT and watch never disagree.
    api(project(":core:recording"))
    // FIT encoding and decoding (ADR 0009).
    implementation(libs.garmin.fit)
    testImplementation(project(":tools:replay"))
}

tasks.withType<Test>().configureEach {
    // ExportVerificationTest leaves FIT files here for the owner's validator and Strava check.
    systemProperty(
        "runpro.fitSamples",
        layout.buildDirectory.dir("fit-samples").get().asFile.absolutePath
    )
}
