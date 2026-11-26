plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Traces, sources and listeners are expressed in model types.
    api(project(":core:model"))
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
