package com.runpro.core.journal

import com.runpro.core.model.RunEvent
import com.runpro.core.model.StopReason
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.spi.FileSystemProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ReservedStorageTest {
    @Test
    fun `partial full write rolls back and uses reserve without duplicating bytes`(
        @TempDir dir: Path
    ) {
        val file = dir.resolve("run.journal")
        val reserve = StorageReserve.create(file)
        val channel = FileChannel.open(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        var full = false
        val output = FileJournalOutput(channel, reserve, writeChannel = { buffer ->
            if (full && Files.size(reserve.file) > 0) {
                channel.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
                throw IOException("No space left on device")
            }
            channel.write(buffer)
        })
        JournalWriter(output).use { writer ->
            writer.append(started())
            full = true
            writer.append(samples(hr(1, 150)))
            writer.append(event(RunEvent.Stopped(at(2), StopReason.STORAGE_FULL)))
            assertTrue(writer.storageFull)
        }
        val contents = JournalReader.read(file)
        assertEquals(JournalEnd.Clean, contents.end)
        assertEquals(3, contents.records.size)
        assertTrue(contents.isStopped)
        assertFalse(Files.exists(reserve.file))
    }

    @Test
    fun `full sync consumes the reserve and retries sync once`(@TempDir dir: Path) {
        val file = dir.resolve("sync.journal")
        val reserve = StorageReserve.create(file)
        val channel = FileChannel.open(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        var failSync = false
        var failedSyncs = 0
        var synced = byteArrayOf()
        val output = FileJournalOutput(channel, reserve, syncChannel = {
            if (failSync && Files.size(reserve.file) > 0) {
                failedSyncs++
                throw IOException("No space left on device")
            }
            channel.force(false)
            synced = Files.readAllBytes(file)
        })
        JournalWriter(output).use { writer ->
            writer.append(started())
            failSync = true
            writer.append(event(RunEvent.Stopped(at(2), StopReason.LOW_BATTERY)))
            assertTrue(writer.storageFull)
            assertEquals(1, failedSyncs)
            assertTrue(read(synced).isStopped)
            assertEquals(2, read(synced).records.size)
        }
    }

    @Test
    fun `ordinary IO errors do not consume the reserve`(@TempDir dir: Path) {
        val file = dir.resolve("run.journal")
        val reserve = StorageReserve.create(file)
        val channel = FileChannel.open(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        FileJournalOutput(channel, reserve, writeChannel = {
            throw IOException("device broken")
        }).use { output ->
            kotlin.test.assertFailsWith<IOException> { output.write(byteArrayOf(1), 0, 1) }
            assertFalse(output.storageFull)
            assertTrue(Files.size(reserve.file) > 0)
        }
    }

    @Test
    fun `availableBytes works when FileSystemProvider getFileStore throws as on Android`(
        @TempDir dir: Path
    ) {
        val file = noFileStorePath(dir.resolve("watch.journal"))
        JournalWriter.create(file).use { writer ->
            assertTrue(writer.availableBytes > 0L)
            writer.append(started())
            writer.append(event(RunEvent.Stopped(at(1), StopReason.USER)))
        }
        assertTrue(JournalReader.read(dir.resolve("watch.journal")).isStopped)
    }

    @Test
    fun `abort keeps the journal file if RunStarted failed during sync`(@TempDir dir: Path) {
        val file = dir.resolve("failed-sync.journal")
        val reserve = StorageReserve.create(file)
        val channel = FileChannel.open(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        val output = FileJournalOutput(
            channel,
            reserve,
            file = file,
            syncChannel = { throw IOException("sync failed") }
        )
        val writer = JournalWriter(output)
        kotlin.test.assertFailsWith<IOException> { writer.append(started()) }
        writer.abort()
        assertTrue(Files.exists(file))
        assertFalse(Files.exists(reserve.file))
        assertEquals(listOf(started()), JournalReader.read(file).records)
    }

    private fun noFileStorePath(real: Path): Path {
        val baseFs = real.fileSystem
        val baseProvider = baseFs.provider()
        lateinit var wrappedFs: FileSystem
        val provider = object : FileSystemProvider() {
            private fun unwrap(p: Path): Path = (p as? WrappedPath)?.delegate ?: p

            override fun getScheme(): String = baseProvider.scheme
            override fun newFileSystem(uri: java.net.URI, env: Map<String, *>) =
                baseProvider.newFileSystem(uri, env)
            override fun getFileSystem(uri: java.net.URI) = wrappedFs
            override fun getPath(uri: java.net.URI): Path = wrap(baseProvider.getPath(uri))
            override fun newByteChannel(
                path: Path,
                options: Set<java.nio.file.OpenOption>,
                vararg attrs: java.nio.file.attribute.FileAttribute<*>
            ) = baseProvider.newByteChannel(unwrap(path), options, *attrs)
            override fun newFileChannel(
                path: Path,
                options: Set<java.nio.file.OpenOption>,
                vararg attrs: java.nio.file.attribute.FileAttribute<*>
            ) = baseProvider.newFileChannel(unwrap(path), options, *attrs)
            override fun newDirectoryStream(
                dir: Path,
                filter: java.nio.file.DirectoryStream.Filter<in Path>
            ) = baseProvider.newDirectoryStream(unwrap(dir), filter)
            override fun createDirectory(
                dir: Path,
                vararg attrs: java.nio.file.attribute.FileAttribute<*>
            ) = baseProvider.createDirectory(unwrap(dir), *attrs)
            override fun delete(path: Path) = baseProvider.delete(unwrap(path))
            override fun copy(
                source: Path,
                target: Path,
                vararg options: java.nio.file.CopyOption
            ) = baseProvider.copy(unwrap(source), unwrap(target), *options)
            override fun move(
                source: Path,
                target: Path,
                vararg options: java.nio.file.CopyOption
            ) = baseProvider.move(unwrap(source), unwrap(target), *options)
            override fun isSameFile(path: Path, path2: Path) =
                baseProvider.isSameFile(unwrap(path), unwrap(path2))
            override fun isHidden(path: Path) = baseProvider.isHidden(unwrap(path))
            override fun getFileStore(path: Path): FileStore =
                throw IOException("Mount point not found")
            override fun checkAccess(path: Path, vararg modes: java.nio.file.AccessMode) =
                baseProvider.checkAccess(unwrap(path), *modes)
            override fun <V : java.nio.file.attribute.FileAttributeView> getFileAttributeView(
                path: Path,
                type: Class<V>,
                vararg options: java.nio.file.LinkOption
            ) = baseProvider.getFileAttributeView(unwrap(path), type, *options)
            override fun <A : java.nio.file.attribute.BasicFileAttributes> readAttributes(
                path: Path,
                type: Class<A>,
                vararg options: java.nio.file.LinkOption
            ) = baseProvider.readAttributes(unwrap(path), type, *options)
            override fun readAttributes(
                path: Path,
                attributes: String,
                vararg options: java.nio.file.LinkOption
            ) = baseProvider.readAttributes(unwrap(path), attributes, *options)
            override fun setAttribute(
                path: Path,
                attribute: String,
                value: Any?,
                vararg options: java.nio.file.LinkOption
            ) = baseProvider.setAttribute(unwrap(path), attribute, value, *options)

            fun wrap(target: Path): Path = WrappedPath(target, wrappedFs, ::wrap)
        }
        wrappedFs = object : FileSystem() {
            override fun provider(): FileSystemProvider = provider
            override fun close() = baseFs.close()
            override fun isOpen() = baseFs.isOpen
            override fun isReadOnly() = baseFs.isReadOnly
            override fun getSeparator(): String = baseFs.separator
            override fun getRootDirectories() = baseFs.rootDirectories.map(provider::wrap)
            override fun getFileStores(): Iterable<FileStore> =
                throw IOException("Mount point not found")
            override fun supportedFileAttributeViews() = baseFs.supportedFileAttributeViews()
            override fun getPath(first: String, vararg more: String): Path =
                provider.wrap(baseFs.getPath(first, *more))
            override fun getPathMatcher(syntaxAndPattern: String) =
                baseFs.getPathMatcher(syntaxAndPattern)
            override fun getUserPrincipalLookupService() = baseFs.userPrincipalLookupService
            override fun newWatchService() = baseFs.newWatchService()
        }
        return provider.wrap(real)
    }

    private class WrappedPath(
        val delegate: Path,
        private val fs: FileSystem,
        private val wrap: (Path) -> Path
    ) : Path by delegate {
        override fun getFileSystem(): FileSystem = fs
        override fun getParent(): Path? = delegate.parent?.let(wrap)
        override fun toAbsolutePath(): Path = wrap(delegate.toAbsolutePath())
        override fun resolve(other: String): Path = wrap(delegate.resolve(other))
        override fun resolve(other: Path): Path =
            wrap(delegate.resolve((other as? WrappedPath)?.delegate ?: other))
        override fun resolveSibling(other: String): Path = wrap(delegate.resolveSibling(other))
        override fun toFile() = delegate.toFile()
    }
}
