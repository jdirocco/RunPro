package com.runpro.tools.replay

import java.nio.file.Files
import java.nio.file.Paths

/** Writes [SyntheticTraces] into the directory given as the only argument (`testdata/traces`). */
fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: GenerateSyntheticTraces <output directory>" }
    val dir = Files.createDirectories(Paths.get(args[0]))
    SyntheticTraces.all.forEach { (file, generate) ->
        val trace = generate()
        trace.writeTo(dir.resolve(file))
        println("$file: ${trace.records.size} records, ${trace.duration}")
    }
}
