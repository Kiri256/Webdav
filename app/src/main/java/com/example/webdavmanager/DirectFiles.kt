package com.example.webdavmanager

import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal object DirectFiles {
    fun file(path: String): File {
        val uri = Uri.parse(path)
        require(uri.scheme == "file" && uri.authority.isNullOrEmpty() && uri.query == null && uri.fragment == null)
        val value = File(requireNotNull(uri.path))
        require(value.isAbsolute && value.toPath().normalize().toString() == value.path) { "Invalid local path" }
        return value
    }

    fun inside(root: String, path: String): File {
        val base = file(root)
        val target = file(path)
        require(target.toPath().startsWith(base.toPath()) &&
            target.canonicalFile.toPath().startsWith(base.canonicalFile.toPath())) { "Outside local storage root" }
        require(!Files.isSymbolicLink(target.toPath()) && target.canonicalFile == target.absoluteFile) {
            "Browsing through symbolic links is unavailable"
        }
        return target
    }

    fun entry(target: File): FileEntry {
        val attrs = Files.readAttributes(target.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        return FileEntry(target.name, Uri.fromFile(target).toString(), attrs.isDirectory,
            if (attrs.isDirectory) null else attrs.size(), target.lastModified().toString(),
            attrs.fileKey()?.toString(), MimeTypeMap.getSingleton().getMimeTypeFromExtension(target.extension.lowercase()))
    }

    suspend fun list(path: String): List<FileEntry> {
        val children = file(path).listFiles() ?: throw IOException("Access denied or directory unavailable")
        return children.filterNot { Files.isSymbolicLink(it.toPath()) }.map { coroutineContext.ensureActive(); entry(it) }
            .sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    private fun child(directory: String, name: String): File {
        validateLocalName(name)
        return File(file(directory), name)
    }

    private fun writableTarget(target: File) {
        require(target.path != "/" && target.parentFile != null) { "Cannot modify filesystem root" }
        require(!Files.isSymbolicLink(target.toPath()) && target.canonicalFile == target.absoluteFile) {
            "Operations through symbolic links are unavailable"
        }
    }

    fun checkWritable(path: String) = writableTarget(file(path))

    fun create(directory: String, name: String, folder: Boolean): FileEntry {
        val target = child(directory, name)
        writableTarget(target)
        if (!(if (folder) target.mkdir() else target.createNewFile())) throw IOException("Could not create: access denied or name already exists")
        return entry(target)
    }

    fun rename(entry: FileEntry, directory: String, name: String) {
        val source = file(entry.path)
        require(source.parentFile == file(directory))
        writableTarget(source)
        requireSameVersion(entry, this.entry(source))
        val destination = child(directory, name)
        writableTarget(destination)
        if (source != destination) Files.move(source.toPath(), destination.toPath())
    }

    suspend fun delete(entry: FileEntry) {
        val plan = mutableListOf<FileEntry>()
        suspend fun collect(current: FileEntry, depth: Int) {
            coroutineContext.ensureActive()
            checkWritable(current.path)
            if (depth > 64 || plan.size >= 10000) throw IOException("Deletion exceeds 10,000 entries or 64 levels")
            requireSameVersion(current, this.entry(file(current.path)))
            plan += current
            if (current.isDirectory) for (child in list(current.path)) collect(child, depth + 1)
        }
        collect(entry, 0)
        for (current in plan.asReversed()) {
            if (current.isDirectory) deleteEmpty(current) else deleteVerified(current)
        }
    }

    /** Detach first, verify the detached identity, and never unlink a replacement by path. */
    suspend fun deleteVerified(expected: FileEntry, digest: String? = null) {
        require(!expected.isDirectory)
        val source = file(expected.path)
        writableTarget(source)
        requireSameVersion(expected, entry(source))
        val detached = File(source.parentFile, ".webdav-delete-${UUID.randomUUID()}")
        Files.move(source.toPath(), detached.toPath())
        try {
            requireSameVersion(expected, entry(detached), comparePath = false)
            if (digest != null && fileDigest(detached) != digest) throw IOException("Source changed; file retained")
            coroutineContext.ensureActive()
            Files.delete(detached.toPath())
        } catch (error: Exception) {
            try { Files.move(detached.toPath(), source.toPath()) }
            catch (_: Exception) { throw IOException("File retained at ${detached.name}; check the source folder", error) }
            throw error
        }
    }

    fun deleteEmpty(expected: FileEntry) {
        val target = file(expected.path)
        writableTarget(target)
        val current = entry(target)
        if (!current.isDirectory || (expected.etag != null && current.etag != expected.etag))
            throw IOException("Folder replaced; folder retained")
        // rmdir fails if any new child exists. Never discover and delete additional children.
        Files.delete(target.toPath())
    }

    suspend fun saveText(path: String, bytes: ByteArray, originalDigest: String) {
        val target = file(path)
        writableTarget(target)
        val version = entry(target)
        val temporary = File(target.parentFile, ".webdav-save-${UUID.randomUUID()}")
        try {
            requireTransferSpace(requireNotNull(target.parentFile).usableSpace, bytes.size.toLong())
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            coroutineContext.ensureActive()
            requireSameVersion(version, entry(target))
            if (fileDigest(target) != originalDigest) throw IOException("Local file changed. Keep your draft or reload before saving.")
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    fun tryMove(entry: FileEntry, directory: String, name: String): Boolean {
        val source = file(entry.path)
        val destination = child(directory, name)
        writableTarget(source); writableTarget(destination)
        require(destination != source && !destination.toPath().startsWith(source.toPath())) { "Cannot move into itself" }
        requireSameVersion(entry, this.entry(source))
        val sameVolume = android.system.Os.stat(source.path).st_dev ==
            android.system.Os.stat(requireNotNull(destination.parentFile).path).st_dev
        if (!sameVolume) return false
        Files.move(source.toPath(), destination.toPath())
        return true
    }
}
