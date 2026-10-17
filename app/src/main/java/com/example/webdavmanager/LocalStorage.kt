package com.example.webdavmanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import coil.imageLoader
import coil.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import android.database.Cursor
import android.os.CancellationSignal

internal class LocalStorage(private val context: Context, val tree: Uri) {
    private val resolver get() = context.contentResolver
    val root: Uri = if (tree.scheme == "file") tree else
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private suspend fun uri(value: String): Uri {
        val parsed = Uri.parse(value)
        if (tree.scheme == "file") {
            DirectFiles.inside(root.toString(), value)
            return parsed
        }
        require(parsed.scheme == "content" && parsed.authority == tree.authority &&
            DocumentsContract.getTreeDocumentId(parsed) == DocumentsContract.getTreeDocumentId(tree)) { "Outside selected folder" }
        require(parsed == root || isInside(parsed)) { "Outside selected folder" }
        return parsed
    }

    private suspend fun isInside(document: Uri): Boolean {
        coroutineContext.ensureActive()
        if (Build.VERSION.SDK_INT >= 29) {
            try { return DocumentsContract.isChildDocument(resolver, root, document) }
            catch (_: UnsupportedOperationException) { /* Some providers omit the optional child check. */ }
        }
        // Older Android versions need a provider traversal: document IDs are opaque, not paths.
        val wanted = DocumentsContract.getDocumentId(document)
        val pending = java.util.ArrayDeque<String>()
        pending.add(DocumentsContract.getDocumentId(root))
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty() && seen.size < 10_000) {
            coroutineContext.ensureActive()
            val parent = pending.removeFirst()
            if (!seen.add(parent)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE)) { cursor ->
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val id = cursor.getString(0)
                    if (id == wanted) return true
                    if (cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) pending.add(id)
                }
            }
        }
        return false
    }

    suspend fun list(directory: String): List<FileEntry> = withContext(Dispatchers.IO) {
        if (tree.scheme == "file") { uri(directory); return@withContext DirectFiles.list(directory) }
        val parent = uri(directory)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val result = mutableListOf<FileEntry>()
        query(children, columns) { cursor ->
            while (cursor.moveToNext()) {
                coroutineContext.ensureActive()
                val id = cursor.getString(0)
                val mime = cursor.getString(2)
                result += FileEntry(cursor.getString(1) ?: "Unnamed", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                    mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (cursor.isNull(3)) null else cursor.getLong(3),
                    if (cursor.isNull(4)) null else cursor.getLong(4).toString(), null, mime)
            }
        } ?: throw IOException("Cannot read selected folder; select it again")
        result.sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun create(directory: String, name: String, folder: Boolean): Unit = withContext(Dispatchers.IO) {
        if (tree.scheme == "file") { uri(directory); DirectFiles.create(directory, name, folder); return@withContext }
        validateLocalName(name)
        val parent = uri(directory)
        if (list(directory).any { it.name == name }) throw IOException("A file with this name already exists")
        DocumentsContract.createDocument(resolver, parent,
            if (folder) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain", name)
            ?: throw IOException("Could not create file or folder")
        Unit
    }

    suspend fun rename(file: FileEntry, directory: String, name: String): Unit = withContext(Dispatchers.IO) {
        if (tree.scheme == "file") {
            uri(file.path); uri(directory)
            DirectFiles.rename(file, directory, name)
            evictImage(file)
            return@withContext
        }
        validateLocalName(name)
        requireSameVersion(file, stat(file.path))
        if (list(directory).any { it.name == name && it.path != file.path }) throw IOException("A file with this name already exists")
        if (DocumentsContract.renameDocument(resolver, uri(file.path), name) == null) throw IOException("Could not rename file")
        evictImage(file)
        Unit
    }

    suspend fun delete(file: FileEntry): Unit = withContext(Dispatchers.IO) {
        if (tree.scheme == "file") {
            uri(file.path)
            require(file.path != root.toString()) { "Cannot delete local storage root" }
            DirectFiles.delete(file)
            evictImage(file)
            return@withContext
        }
        val target = uri(file.path)
        require(target != root) { "Cannot delete selected root folder" }
        requireSameVersion(file, stat(file.path))
        if (!DocumentsContract.deleteDocument(resolver, target)) throw IOException("Could not delete file")
        evictImage(file)
    }

    private fun evictImage(file: FileEntry) {
        if (!file.isImage()) return
        val cache = context.imageLoader.memoryCache ?: return
        cache.remove(MemoryCache.Key(localImageCacheKey(file, true)))
        cache.remove(MemoryCache.Key(localImageCacheKey(file, false)))
    }

    suspend fun transfer(file: FileEntry, sourceDirectory: String, destinationDirectory: String,
        name: String, move: Boolean,
        journal: TransferJournal? = null,
        onProgress: (String, String, Long, Long?) -> Unit = { _, _, _, _ -> }) = withContext(Dispatchers.IO) {
        uri(file.path); uri(sourceDirectory); uri(destinationDirectory)
        require(file.path != root.toString()) { "Cannot transfer local storage root" }
        validateLocalName(name)
        if (tree.scheme == "file") {
            val source = DirectFiles.file(file.path)
            require(source.parentFile == DirectFiles.file(sourceDirectory))
            val destination = java.io.File(DirectFiles.file(destinationDirectory), name)
            require(destination != source && !destination.toPath().startsWith(source.toPath())) { "Cannot transfer into itself" }
            if (move && DirectFiles.tryMove(file, destinationDirectory, name)) {
                evictImage(file)
                return@withContext TransferOutcome(true)
            }
        } else require(file.path != destinationDirectory && (!file.isDirectory ||
            !isDescendant(uri(file.path), uri(destinationDirectory)))) { "Cannot transfer into itself" }
        val outcome = CrossStorageTransfer(context, this@LocalStorage, null, this@LocalStorage, null)
            .transfer(file, sourceDirectory, destinationDirectory, name, move, onProgress, journal)
        if (move) evictImage(file)
        outcome
    }

    suspend fun stat(path: String): FileEntry = withContext(Dispatchers.IO) {
        val target = uri(path)
        if (tree.scheme == "file") return@withContext DirectFiles.entry(DirectFiles.file(path))
        query(target, columns) { cursor ->
            if (!cursor.moveToFirst()) throw IOException("File no longer exists")
            val mime = cursor.getString(2)
            FileEntry(cursor.getString(1) ?: "Unnamed", target.toString(),
                mime == DocumentsContract.Document.MIME_TYPE_DIR,
                if (cursor.isNull(3)) null else cursor.getLong(3),
                if (cursor.isNull(4)) null else cursor.getLong(4).toString(), null, mime)
        } ?: throw IOException("Cannot inspect file")
    }

    /** Used for moves/rollback only. Providers cannot atomically guarantee an empty directory DELETE. */
    suspend fun deleteVerified(file: FileEntry, digest: String? = null) = withContext(Dispatchers.IO) {
        uri(file.path)
        require(file.path != root.toString()) { "Cannot delete local storage root" }
        if (tree.scheme == "file") {
            if (file.isDirectory) DirectFiles.deleteEmpty(file) else DirectFiles.deleteVerified(file, digest)
        } else {
            if (file.isDirectory) throw IOException("Copy verified; source folder retained. This provider cannot safely delete empty folders during a move.")
            requireSameVersion(file, stat(file.path))
            val target = uri(file.path)
            val detached = DocumentsContract.renameDocument(resolver, target, ".webdav-delete-${java.util.UUID.randomUUID()}")
                ?: throw IOException("Provider cannot safely detach the source; source retained")
            try {
                requireSameVersion(file, stat(detached.toString()), comparePath = false)
                if (digest != null && contentDigest(detached) != digest) throw IOException("Source changed; file retained")
                coroutineContext.ensureActive()
                if (!DocumentsContract.deleteDocument(resolver, detached)) throw IOException("Deletion not confirmed")
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    try { DocumentsContract.renameDocument(resolver, detached, file.name) } catch (_: Exception) { }
                }
                throw IOException("Deletion incomplete; check source and .webdav-delete files", error)
            }
        }
        evictImage(file)
    }

    suspend fun createExclusive(directory: String, name: String, folder: Boolean, mime: String? = null): FileEntry =
        withContext(Dispatchers.IO) {
            validateLocalName(name)
            val parent = uri(directory)
            if (tree.scheme == "file") return@withContext DirectFiles.create(directory, name, folder)
            val created = DocumentsContract.createDocument(resolver, parent,
                if (folder) DocumentsContract.Document.MIME_TYPE_DIR else mime ?: "application/octet-stream", name)
                ?: throw IOException("Creation outcome unknown; check destination")
            val entry = stat(created.toString())
            if (entry.name != name) throw IOException("Provider changed the destination name; check destination")
            entry
        }

    suspend fun availableSpace(directory: String): Long? {
        uri(directory)
        return if (tree.scheme == "file") DirectFiles.file(directory).usableSpace else null
    }

    suspend fun publish(file: FileEntry, parent: String, name: String): FileEntry = withContext(Dispatchers.IO) {
        uri(file.path); uri(parent); validateLocalName(name)
        if (list(parent).any { it.name == name }) throw IOException("Destination exists; temporary copy retained")
        if (tree.scheme == "file") {
            DirectFiles.rename(stat(file.path), parent, name)
            stat(Uri.fromFile(java.io.File(DirectFiles.file(parent), name)).toString())
        } else {
            val result = DocumentsContract.renameDocument(resolver, uri(file.path), name)
                ?: throw IOException("Rename outcome unknown; check destination")
            stat(result.toString()).also { if (it.name != name) throw IOException("Provider changed the name; check destination") }
        }
    }

    private suspend fun isDescendant(parent: Uri, child: Uri): Boolean {
        coroutineContext.ensureActive()
        if (Build.VERSION.SDK_INT >= 29) {
            try { return DocumentsContract.isChildDocument(resolver, parent, child) }
            catch (_: UnsupportedOperationException) { }
        }
        val wanted = DocumentsContract.getDocumentId(child)
        val pending = java.util.ArrayDeque<String>()
        pending.add(DocumentsContract.getDocumentId(parent))
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty() && seen.size < 10_000) {
            coroutineContext.ensureActive()
            val id = pending.removeFirst()
            if (!seen.add(id)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE)) { cursor ->
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val next = cursor.getString(0)
                    if (next == wanted) return true
                    if (cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) pending.add(next)
                }
            }
        }
        require(pending.isEmpty()) { "Folder is too large to validate safely" }
        return false
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend inline fun <T> query(target: Uri, projection: Array<String>, read: (Cursor) -> T): T? {
        coroutineContext.ensureActive()
        val signal = CancellationSignal()
        val cancellation = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion(
            onCancelling = true, invokeImmediately = true) { cause -> if (cause != null) signal.cancel() }
        try {
            return resolver.query(target, projection, null, null, null, signal)?.use(read)
        } catch (error: Exception) { coroutineContext.ensureActive(); throw error }
        finally { cancellation?.dispose() }
    }

    private fun directorySignature(files: List<FileEntry>): String = files.sortedBy { it.path }.joinToString("") {
        listOf(it.path, it.name, it.isDirectory.toString(), it.size.toString(), it.lastModified.orEmpty())
            .joinToString("") { value -> "${value.length}:$value" }
    }

    private suspend fun contentDigest(document: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(document)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } ?: throw IOException("Could not verify file content")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun readText(path: String): RemoteText = withContext(Dispatchers.IO) {
        uri(path)
        if (tree.scheme == "file") require(java.nio.file.Files.isRegularFile(DirectFiles.file(path).toPath())) { "Only regular text files can be read" }
        val bytes = resolver.openInputStream(uri(path))?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(chunk)
                if (count < 0) break
                if (output.size() + count > TEXT_LIMIT) throw IOException("Text editor supports files up to 64 KiB")
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException("Could not read text")
        val bom = bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
        val content = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, if (bom) 3 else 0, bytes.size - if (bom) 3 else 0)).toString()
        } catch (_: Exception) { throw IOException("Only UTF-8 text is supported") }
        if ('\u0000' in content) throw IOException("This file is not UTF-8 text")
        RemoteText(content, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, null, bom)
    }

    suspend fun exportFile(file: FileEntry, target: java.io.File, limit: Long,
        onProgress: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        target.outputStream().use { readFile(file, it, limit, target.parentFile, onProgress) }
    }

    suspend fun readFile(file: FileEntry, output: java.io.OutputStream, limit: Long,
        storage: java.io.File? = null, onProgress: (Long) -> Unit = {}): StreamDigest = withContext(Dispatchers.IO) {
        require(!file.isDirectory)
        if (tree.scheme == "file") {
            val source = DirectFiles.file(file.path)
            require(java.nio.file.Files.isRegularFile(source.toPath())) { "Only regular files can be opened" }
        }
        val result = resolver.openInputStream(uri(file.path))?.use { input ->
            copyBounded(input, output, limit, storage, onProgress)
        } ?: throw IOException("Cannot read local file")
        if (file.size != null && result.bytes != file.size) throw IOException("Source size changed")
        result
    }

    suspend fun importFile(directory: String, name: String, data: java.io.File, mime: String?,
        onProgress: (Long) -> Unit = {}): FileEntry = withContext(Dispatchers.IO) {
        val target = createExclusive(directory, name, false, mime)
        // Ownership is known before writing. Failed writes are retained; cleanup never blindly deletes a path.
        try {
            data.inputStream().use { input -> resolver.openOutputStream(uri(target.path), "rwt")?.use { output ->
                copyBounded(input, output, data.length(), if (tree.scheme == "file") DirectFiles.file(directory) else null, onProgress, computeDigest = false)
            } ?: throw IOException("Cannot write destination") }
            stat(target.path)
        } catch (error: Exception) { throw IOException("Write incomplete; source retained. Check destination: $name", error) }
    }

    suspend fun saveText(path: String, content: String, original: RemoteText): RemoteText = withContext(Dispatchers.IO) {
        uri(path)
        if (tree.scheme == "file") DirectFiles.checkWritable(path)
        val bytes = ((if (original.bom) "\uFEFF" else "") + content).toByteArray(Charsets.UTF_8)
        if (bytes.size > TEXT_LIMIT) throw IOException("Text editor supports files up to 64 KiB")
        if (readText(path).etag != original.etag) throw IOException("Local file changed. Keep your draft or reload before saving.")
        coroutineContext.ensureActive()
        if (tree.scheme == "file") {
            DirectFiles.saveText(path, bytes, requireNotNull(original.etag))
        } else {
            // A durable sibling backup exists before any truncate. It remains available after failure/process death.
            val current = stat(path)
            val parent = findParent(current)
            val backup = createExclusive(parent, ".webdav-backup-${java.util.UUID.randomUUID()}-${current.name}", false, current.mimeType)
            resolver.openInputStream(uri(path))!!.use { input ->
                resolver.openOutputStream(uri(backup.path), "rwt")!!.use { output -> copyBounded(input, output, TEXT_LIMIT.toLong()) }
            }
            if (contentDigest(uri(backup.path)) != original.etag) throw IOException("Local file changed; original retained")
            if (readText(path).etag != original.etag) throw IOException("Local file changed; original retained")
            try {
                resolver.openOutputStream(uri(path), "rwt")?.use { it.write(bytes) }
                    ?: throw IOException("Cannot write text")
                if (readText(path).content != content) throw IOException("Text save verification failed")
            } catch (error: Exception) {
                throw IOException("Save incomplete. Original retained as ${backup.name} in the same folder.", error)
            }
            // The verified backup can be removed by identity; failed cleanup leaves a recovery copy.
            try { deleteVerified(stat(backup.path), original.etag) } catch (_: Exception) { }
        }
        RemoteText(content, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, null, original.bom)
    }

    private suspend fun findParent(file: FileEntry): String {
        if (tree.scheme == "file") {
            return Uri.fromFile(DirectFiles.file(file.path).parentFile).toString()
        }
        val pending = java.util.ArrayDeque<String>()
        pending.add(root.toString())
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty() && seen.size < 10000) {
            val parent = pending.removeFirst().also { coroutineContext.ensureActive() }
            if (!seen.add(parent)) { continue }
            val children = list(parent)
            if (children.any { it.path == file.path }) { return parent }
            children.filter { it.isDirectory }.forEach { pending.add(it.path) }
        }
        throw IOException("Cannot locate parent for a safe backup")
    }

    companion object {
        private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)

        suspend fun load(context: Context): LocalStorage = LocalStorage(context, Uri.fromFile(android.os.Environment.getExternalStorageDirectory()))

        suspend fun openGrantedFolder(context: Context, tree: Uri): LocalStorage {
            context.contentResolver.takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val next = LocalStorage(context, tree)
            next.list(next.root.toString())
            return next
        }
    }
}

internal fun validateLocalName(name: String) {
    require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.code < 32 }) {
        "Enter a name without path separators or control characters"
    }
}

internal fun localImageCacheKey(file: FileEntry, thumbnail: Boolean): String =
    "local:${if (thumbnail) "thumb" else "full"}:${file.path}:${file.lastModified}:${file.size}"
