package com.runpro.core.journal

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Physically written capacity beside the journal, released only to finish a run after ENOSPC. */
internal class StorageReserve private constructor(
    val file: Path,
    private val channel: FileChannel
) : Closeable {
    fun release() {
        channel.truncate(0)
        channel.force(true)
    }

    override fun close() {
        channel.close()
        Files.deleteIfExists(file)
    }

    companion object {
        private const val RESERVE_BYTES = 1024 * 1024
        private const val BLOCK_BYTES = 8192

        fun create(journal: Path): StorageReserve {
            val file = journal.resolveSibling("${journal.fileName}.reserve")
            val channel = FileChannel.open(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            )
            var opened = false
            try {
                // Write every block; extending/truncating a sparse file would reserve no disk blocks.
                val block = ByteBuffer.allocate(BLOCK_BYTES)
                repeat(RESERVE_BYTES / BLOCK_BYTES) {
                    block.clear()
                    while (block.hasRemaining()) channel.write(block)
                }
                channel.truncate(RESERVE_BYTES.toLong())
                channel.force(true)
                opened = true
                return StorageReserve(file, channel)
            } finally {
                if (!opened) {
                    channel.close()
                    Files.deleteIfExists(file)
                }
            }
        }
    }
}
