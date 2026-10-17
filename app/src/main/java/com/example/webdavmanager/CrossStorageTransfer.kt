package com.example.webdavmanager

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Plan once, stage one file at a time, verify, publish, then conditionally remove only planned sources. */
internal class CrossStorageTransfer(
    private val context: Context,
    private val sourceLocal: LocalStorage?, private val sourceDav: WebDavClient?,
    private val targetLocal: LocalStorage?, private val targetDav: WebDavClient?,
) {
    companion object {
        private val activeStages = mutableSetOf<String>()
        private fun newStaging(context: Context): File = synchronized(activeStages) {
            val parent = File(context.cacheDir, "file_transfers").apply { mkdirs() }
            File(parent, UUID.randomUUID().toString()).also {
                if (!it.mkdir()) throw IOException("Cannot create transfer cache")
                activeStages += it.absolutePath
            }
        }
        suspend fun cleanOrphanSnapshots(context: Context) = withContext(Dispatchers.IO) {
            synchronized(activeStages) {
                File(context.cacheDir, "file_transfers").listFiles().orEmpty()
                    .filter { it.absolutePath !in activeStages }.forEach { it.deleteRecursively() }
            }
        }
    }
    private data class Plan(val entry: FileEntry, val children: List<Plan>)
    private data class Created(val entry: FileEntry, val digest: String)

    suspend fun transfer(
        file: FileEntry,
        sourceDirectory: String,
        destination: String,
        name: String,
        move: Boolean,
        onProgress: (String, String, Long, Long?) -> Unit = { _, _, _, _ -> },
        taskJournal: TransferJournal? = null,
    ): TransferOutcome =
        withContext(Dispatchers.IO) {
            validateLocalName(name)
            val finalPath = targetDav?.paths?.child(destination, name) ?: "$destination / $name"
            val journal = taskJournal ?: TransferJournal(context, file, finalPath, targetLocal?.tree?.toString() ?: targetDav!!.connection.id, move)
            journal.record("planning")
            val staging = newStaging(context)
            val temporaryName = ".webdav-transfer-${UUID.randomUUID()}"
            val copied = mutableListOf<Pair<Plan, String>>()
            val verifiedTargets = mutableListOf<Created>()
            val directories = mutableListOf<FileEntry>()
            val expectedChildren = mutableMapOf<String, MutableSet<String>>()
            var published = false
            var pendingRemote: String? = null
            var pendingDirectory = false
            var count = 0
            suspend fun listSource(path: String) = sourceLocal?.list(path) ?: requireNotNull(sourceDav).list(path)
            suspend fun statSource(entry: FileEntry) = sourceLocal?.stat(entry.path) ?: sourceDav!!.inspect(entry)
            suspend fun statTarget(path: String, directory: Boolean = false) = targetLocal?.stat(path) ?: targetDav!!.inspect(path, directory)
            suspend fun read(entry: FileEntry, target: File?, source: Boolean, phase: String): StreamDigest {
                if (target != null) requireTransferSpace(staging.usableSpace, (entry.size ?: 0).coerceAtLeast(0))
                val local = if (source) sourceLocal else targetLocal
                val dav = if (source) sourceDav else targetDav
                val report: (Long) -> Unit = { bytes -> onProgress(entry.name, phase, bytes, entry.size) }
                suspend fun consume(output: java.io.OutputStream) = if (local != null)
                    local.readFile(entry, output, Long.MAX_VALUE, target?.parentFile, report)
                else dav!!.readFile(entry, output, Long.MAX_VALUE, target?.parentFile, report)
                return if (target == null) consume(discardOutput) else target.outputStream().use { consume(it) }
            }
            suspend fun plan(entry: FileEntry, depth: Int): Plan {
                coroutineContext.ensureActive()
                validateLocalName(entry.name)
                if (++count > 10000 || depth > 64) throw IOException("Transfer exceeds 10,000 entries or 64 levels")
                if (sourceLocal?.tree?.scheme == "file") DirectFiles.checkWritable(entry.path)
                return Plan(entry, if (entry.isDirectory) listSource(entry.path).map { plan(it, depth + 1) } else emptyList())
            }
            fun all(plan: Plan): List<Plan> = buildList {
                fun append(node: Plan) { add(node); node.children.forEach(::append) }
                append(plan)
            }
            suspend fun create(plan: Plan, parentPath: String, childName: String): FileEntry {
                coroutineContext.ensureActive()
                val entry = if (plan.entry.isDirectory) {
                    val path = targetDav?.paths?.child(parentPath, childName) ?: "$parentPath / $childName"
                    journal.record("creating folder; outcome may be unknown", path)
                    if (targetDav != null) { pendingRemote = path; pendingDirectory = true }
                    val created = if (targetLocal != null) targetLocal.createExclusive(parentPath, childName, true)
                    else {
                        targetDav!!.createDirectory(path)
                        // Record confirmed creation before making another request.
                        journal.record("folder created", path)
                        targetDav.inspect(path, true)
                    }
                    directories += created
                    expectedChildren.getOrPut(parentPath) { mutableSetOf() } += created.path
                    for (child in plan.children) create(child, created.path, child.entry.name)
                    created
                } else {
                    val data = File(staging, "data")
                    try {
                        journal.record("reading source", plan.entry.path)
                        val current = statSource(plan.entry)
                        requireSameVersion(plan.entry, current)
                        val digest = read(current, data, true, "Reading source").sha256
                        val path = targetDav?.paths?.child(parentPath, childName) ?: "$parentPath / $childName"
                        journal.record("creating file; outcome may be unknown", path)
                        if (targetDav != null) { pendingRemote = path; pendingDirectory = false }
                        val created = if (targetLocal != null) targetLocal.importFile(parentPath, childName, data, plan.entry.mimeType) {
                            onProgress(plan.entry.name, "Writing destination", it, plan.entry.size)
                        }
                        else {
                            val tag = targetDav!!.upload(path, data, plan.entry.mimeType) {
                                onProgress(plan.entry.name, "Writing destination", it, plan.entry.size)
                            }
                            journal.record("file created", path)
                            val currentTarget = targetDav.inspect(path)
                            if (tag != null && tag != currentTarget.etag) throw IOException("Destination replaced; destination retained")
                            currentTarget
                        }
                        journal.record("verifying destination", created.path)
                        if (read(created, null, false, "Verifying destination").sha256 != digest)
                            throw IOException("Destination verification failed; destination retained")
                        verifiedTargets += Created(created, digest)
                        expectedChildren.getOrPut(parentPath) { mutableSetOf() } += created.path
                        copied += plan to digest
                        created
                    } finally { data.delete() }
                }
                return entry
            }
            try {
                val current = if (sourceLocal?.tree?.scheme == "content") {
                    listSource(sourceDirectory).firstOrNull { it.path == file.path }
                        ?: throw IOException("Source no longer exists")
                } else sourceLocal?.stat(file.path) ?: sourceDav!!.inspect(file)
                if (sourceLocal?.tree?.scheme == "file")
                    require(DirectFiles.file(file.path).parentFile == DirectFiles.file(sourceDirectory))
                if (sourceDav != null) require(parentPath(file.path) == normalizePath(sourceDirectory))
                requireSameVersion(file, current)
                val plan = plan(current, 0)
                val plannedItems = all(plan)
                val files = plannedItems.filterNot { it.entry.isDirectory }
                val knownSizes = files.mapNotNull { it.entry.size?.takeIf { size -> size >= 0 } }
                var total = 0L
                for (size in knownSizes) total = Math.addExact(total, size)
                val largest = knownSizes.maxOrNull() ?: 0L
                requireTransferSpace(staging.usableSpace, largest)
                targetLocal?.availableSpace(destination)?.let { available ->
                    // Conservatively include destination bytes and the one per-file staging copy.
                    requireTransferSpace(available, Math.addExact(total, largest))
                }
                val occupied = (targetLocal?.list(destination) ?: targetDav!!.list(destination)).map { it.name }.toSet()
                if (name in occupied) throw IOException("Destination exists; nothing overwritten")
                val root = create(plan, destination, temporaryName)
                // One membership check per folder; newly-added content is never published or removed as ours.
                for (directory in directories) {
                    val actual = targetLocal?.list(directory.path) ?: targetDav!!.list(directory.path)
                    val expected = expectedChildren[directory.path].orEmpty()
                    if (actual.map { it.path }.toSet() != expected) throw IOException("Destination folder changed; temporary destination retained")
                }
                for (created in verifiedTargets) requireSameVersion(created.entry, statTarget(created.entry.path))
                journal.record("publishing; outcome may be unknown", root.path)
                if (targetDav != null) { pendingRemote = finalPath; pendingDirectory = root.isDirectory }
                if (targetLocal != null) targetLocal.publish(root, destination, name)
                else targetDav!!.transfer(statTarget(root.path, root.isDirectory), targetDav.paths.child(destination, name), true)
                published = true
                journal.record("copy verified and published", finalPath)
                var retained = 0
                var firstReason = ""
                if (move) {
                    // Check every original file again immediately before a guarded delete.
                    for ((source, digest) in copied) {
                        coroutineContext.ensureActive()
                        try {
                            journal.record("deleting verified source", source.entry.path)
                            val now = statSource(source.entry)
                            requireSameVersion(source.entry, now)
                            if (read(now, null, true, "Checking source").sha256 != digest) throw IOException("Source changed; source retained")
                            if (sourceLocal != null) sourceLocal.deleteVerified(now, digest) else sourceDav!!.deleteVerified(now)
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { retained++; if (firstReason.isEmpty()) firstReason = error.message.orEmpty() }
                    }
                    for (directory in plannedItems.filter { it.entry.isDirectory }.asReversed()) {
                        coroutineContext.ensureActive()
                        try {
                            journal.record("removing empty source folder", directory.entry.path)
                            if (sourceLocal != null) sourceLocal.deleteVerified(directory.entry) else sourceDav!!.deleteVerified(directory.entry)
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { retained++; if (firstReason.isEmpty()) firstReason = error.message.orEmpty() }
                    }
                }
                if (retained > 0) {
                    journal.record("copy complete; $retained source items retained", finalPath)
                    TransferOutcome(false, "Copy verified. $retained source item(s) retained: $firstReason")
                } else {
                    if (taskJournal == null) journal.complete()
                    TransferOutcome(move)
                }
            } catch (error: Exception) {
                var outcome = ""
                if (error !is CancellationException && pendingRemote != null) {
                    // Reconcile the observed server state without assuming ownership of an unconfirmed creation.
                    outcome = try {
                        targetDav!!.inspect(pendingRemote!!, pendingDirectory)
                        "Destination resource exists and is retained for review: ${pendingRemote!!}"
                    } catch (_: Exception) { "Destination outcome could not be confirmed: ${pendingRemote!!}" }
                }
                // Cleanup only verified files with unchanged identity/version; never recursively DELETE a directory.
                if (!published) withContext(NonCancellable) {
                    for (created in verifiedTargets.asReversed()) try {
                        val current = statTarget(created.entry.path)
                        requireSameVersion(created.entry, current)
                        if (targetLocal != null) targetLocal.deleteVerified(current, created.digest) else targetDav!!.deleteVerified(current)
                    } catch (_: Exception) { }
                    if (targetLocal?.tree?.scheme == "file") for (directory in directories.asReversed()) try {
                        targetLocal.deleteVerified(directory)
                    } catch (_: Exception) { }
                    journal.record("interrupted or failed; $outcome; inspect temporary destination", temporaryName)
                }
                if (error is CancellationException) throw error
                throw IOException("${error.message ?: "Transfer failed"}. $outcome. Source deletion is not resumed automatically; check $name and $temporaryName.", error)
            } finally {
                staging.deleteRecursively()
                synchronized(activeStages) { activeStages.remove(staging.absolutePath) }
            }
        }
}
