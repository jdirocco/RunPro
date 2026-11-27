plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Traces, sources and listeners are expressed in model types.
    api(project(":core:model"))
    // ImuReport reads the IMU streams copied from the watch (ADR 0012).
    implementation(project(":core:journal"))
    testImplementation(project(":core:recording"))
}

val traces = rootProject.layout.projectDirectory.dir("testdata/traces")

tasks.withType<Test>().configureEach {
    inputs.dir(traces).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("runpro.traces", traces.asFile.absolutePath)
}

tasks.register<JavaExec>("generateSyntheticTraces") {
    group = "replay"
    description = "Regenerates the synthetic traces in testdata/traces from SyntheticTraces."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.runpro.tools.replay.GenerateSyntheticTracesKt")
    args(traces.asFile.absolutePath)
}

tasks.register<JavaExec>("imuReport") {
    group = "replay"
    description =
        "Summarises IMU streams from the watch: --args=\"<file or dir>... [--csv <dir>]\"."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.runpro.tools.replay.ImuReportKt")
    workingDir = rootProject.projectDir
}
