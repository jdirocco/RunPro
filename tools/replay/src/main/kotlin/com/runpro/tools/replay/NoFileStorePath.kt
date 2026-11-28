package com.runpro.tools.replay

import java.io.IOException
import java.net.URI
import java.nio.file.AccessMode
import java.nio.file.CopyOption
import java.nio.file.DirectoryStream
import java.nio.file.FileStore
import java.nio.file.FileSystem
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.spi.FileSystemProvider

/**
 * [real] on a file system whose `getFileStore` fails as it does on the watch: Android's
 * `/data/user/0/...` has no `/proc/mounts` entry (`IOException: Mount point not found`, #12) or the
 * lookup is denied (`SecurityException: getFileStore`, #105). Every other operation, `toFile()`
 * included, reaches the real file, so code that asks `File.usableSpace` (statvfs) keeps working.
 */
fun noFileStorePath(
    real: Path,
    failure: () -> Exception = { IOException("Mount point not found") }
): Path = NoFileStoreFileSystem(real.fileSystem, failure).wrap(real)

// Every function is an abstract member of FileSystem.
@Suppress("TooManyFunctions")
private class NoFileStoreFileSystem(
    private val base: FileSystem,
    private val failure: () -> Exception
) : FileSystem() {
    private val provider = NoFileStoreProvider(base.provider(), this, failure)

    fun wrap(target: Path): Path = WrappedPath(target, this)

    override fun provider(): FileSystemProvider = provider
    override fun close() = base.close()
    override fun isOpen() = base.isOpen
    override fun isReadOnly() = base.isReadOnly
    override fun getSeparator(): String = base.separator
    override fun getRootDirectories() = base.rootDirectories.map(::wrap)
    override fun getFileStores(): Iterable<FileStore> = throw failure()
    override fun supportedFileAttributeViews() = base.supportedFileAttributeViews()
    override fun getPath(first: String, vararg more: String): Path =
        wrap(base.getPath(first, *more))
    override fun getPathMatcher(syntaxAndPattern: String) = base.getPathMatcher(syntaxAndPattern)
    override fun getUserPrincipalLookupService() = base.userPrincipalLookupService
    override fun newWatchService() = base.newWatchService()
}

// Every function is an abstract member of FileSystemProvider.
@Suppress("TooManyFunctions")
private class NoFileStoreProvider(
    private val base: FileSystemProvider,
    private val fs: NoFileStoreFileSystem,
    private val failure: () -> Exception
) : FileSystemProvider() {
    private fun unwrap(p: Path): Path = (p as? WrappedPath)?.delegate ?: p

    override fun getScheme(): String = base.scheme
    override fun newFileSystem(uri: URI, env: Map<String, *>) = base.newFileSystem(uri, env)
    override fun getFileSystem(uri: URI): FileSystem = fs
    override fun getPath(uri: URI): Path = fs.wrap(base.getPath(uri))
    override fun newByteChannel(
        path: Path,
        options: Set<OpenOption>,
        vararg attrs: FileAttribute<*>
    ) = base.newByteChannel(unwrap(path), options, *attrs)
    override fun newFileChannel(
        path: Path,
        options: Set<OpenOption>,
        vararg attrs: FileAttribute<*>
    ) = base.newFileChannel(unwrap(path), options, *attrs)
    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>) =
        base.newDirectoryStream(unwrap(dir), filter)
    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) =
        base.createDirectory(unwrap(dir), *attrs)
    override fun delete(path: Path) = base.delete(unwrap(path))
    override fun copy(source: Path, target: Path, vararg options: CopyOption) =
        base.copy(unwrap(source), unwrap(target), *options)
    override fun move(source: Path, target: Path, vararg options: CopyOption) =
        base.move(unwrap(source), unwrap(target), *options)
    override fun isSameFile(path: Path, path2: Path) = base.isSameFile(unwrap(path), unwrap(path2))
    override fun isHidden(path: Path) = base.isHidden(unwrap(path))
    override fun getFileStore(path: Path): FileStore = throw failure()
    override fun checkAccess(path: Path, vararg modes: AccessMode) =
        base.checkAccess(unwrap(path), *modes)
    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ) = base.getFileAttributeView(unwrap(path), type, *options)
    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ) = base.readAttributes(unwrap(path), type, *options)
    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption) =
        base.readAttributes(unwrap(path), attributes, *options)
    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any?,
        vararg options: LinkOption
    ) = base.setAttribute(unwrap(path), attribute, value, *options)
}

private class WrappedPath(val delegate: Path, private val fs: NoFileStoreFileSystem) :
    Path by delegate {
    override fun getFileSystem(): FileSystem = fs
    override fun getParent(): Path? = delegate.parent?.let(fs::wrap)
    override fun toAbsolutePath(): Path = fs.wrap(delegate.toAbsolutePath())
    override fun resolve(other: String): Path = fs.wrap(delegate.resolve(other))
    override fun resolve(other: Path): Path =
        fs.wrap(delegate.resolve((other as? WrappedPath)?.delegate ?: other))
    override fun resolveSibling(other: String): Path = fs.wrap(delegate.resolveSibling(other))
    override fun toFile() = delegate.toFile()
}
