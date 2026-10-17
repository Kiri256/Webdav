package com.example.webdavmanager

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class StorageOperationsTest {
    @Test fun browserAndEngineShareOneRecoveryJournal() {
        TransferStore().use { store ->
            connect(store)
            val local = selectLocal()
            runBlocking { local.create(local.root.toString(), "journal.txt", false) }
            rule.runOnIdle { model.refreshLocal() }
            rule.waitUntil(10000) { !model.localLoading && model.localEntries.size == 1 }
            val source = model.localEntries.single()
            rule.runOnIdle { model.stageFiles(false, listOf(source)); model.selectTab(2) }
            rule.waitUntil(10000) { !model.loading }
            store.failUpload.set(true)
            rule.runOnIdle { model.pasteFiles() }
            rule.waitUntil(15000) { !model.operationBusy && !model.loading }
            val records = runBlocking { TransferJournal.recover(context) }.filter { it.file.path == source.path }
            assertEquals(1, records.size)
            assertTrue(records.single().detail.contains("interrupted or failed"))
            rule.runOnIdle { model.dismissOperationResults() }
            rule.waitUntil(10000) { !model.operationBusy }
            rule.runOnIdle { model.pasteFiles() }
            rule.waitUntil(15000) { !model.operationBusy && !model.loading }
            assertEquals("Copied", model.operationResults.single().status)
            assertTrue(runBlocking { TransferJournal.recover(context) }.none { it.file.path == source.path })
        }
    }

    @Test fun directoryMoveKeepsChildrenAddedAfterPlanning() = runBlocking {
        TransferStore().use { store ->
            val root = File(context.cacheDir.canonicalFile, "changed-${UUID.randomUUID()}").apply { mkdir() }
            try {
                val source = File(root, "source").apply { mkdir() }
                File(source, "old.txt").writeText("planned")
                val local = LocalStorage(context, Uri.fromFile(root))
                store.onGet = { path -> if (path.contains(".webdav-transfer-")) File(source, "new.txt").writeText("new content") }
                val result = CrossStorageTransfer(context, local, null, null, WebDavClient(store.connection))
                    .transfer(local.list(local.root.toString()).single(), local.root.toString(), "/files", "moved-folder", true)
                assertFalse(result.sourceDeleted)
                assertEquals("new content", File(source, "new.txt").readText())
                assertFalse(File(source, "old.txt").exists())
                assertEquals("planned", store.files["/files/moved-folder/old.txt"]!!.toString(Charsets.UTF_8))
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun failedTransferNeverRecursivelyCleansExternallyAddedDestinationFiles() = runBlocking {
        TransferStore().use { store ->
            val root = File(context.cacheDir.canonicalFile, "rollback-${UUID.randomUUID()}").apply { mkdir() }
            try {
                File(root, "folder").mkdir()
                File(root, "folder/leaf.txt").writeText("source")
                val local = LocalStorage(context, Uri.fromFile(root))
                store.onPut = { path -> store.files[parentPath(path) + "/external.txt"] = "other client".toByteArray() }
                store.failUpload.set(true)
                try {
                    CrossStorageTransfer(context, local, null, null, WebDavClient(store.connection))
                        .transfer(local.list(local.root.toString()).single(), local.root.toString(), "/files", "failed-copy", false)
                    fail("Upload failure")
                } catch (_: java.io.IOException) { }
                assertEquals("other client", store.files.entries.single { it.key.endsWith("/external.txt") }.value.toString(Charsets.UTF_8))
                assertEquals("source", File(root, "folder/leaf.txt").readText())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun successfulCreationWithLostResponseIsRecordedAndSourceRetained() = runBlocking {
        TransferStore().use { store ->
            val root = File(context.cacheDir.canonicalFile, "lost-${UUID.randomUUID()}").apply { mkdir() }
            try {
                File(root, "source.txt").writeText("source")
                val local = LocalStorage(context, Uri.fromFile(root))
                store.loseUploadResponse.set(true)
                val entry = local.list(local.root.toString()).single()
                try {
                    CrossStorageTransfer(context, local, null, null, WebDavClient(store.connection))
                        .transfer(entry, local.root.toString(), "/files", "target.txt", true)
                    fail("Unknown outcome must be reported")
                } catch (_: java.io.IOException) { }
                assertEquals("source", File(root, "source.txt").readText())
                assertTrue(store.files.keys.any { it.contains(".webdav-transfer-") })
                assertTrue(TransferJournal.recover(context).any { it.file.path == entry.path && it.status == "Needs review" })
            } finally { root.deleteRecursively() }
        }
    }
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(rule.activity)[BrowserViewModel::class.java]
    private val context get() = rule.activity.applicationContext

    private fun connect(store: TransferStore) {
        rule.waitUntil(10000) { !model.starting }
        rule.runOnIdle { model.editConnection(); model.connect(store.connection) }
        rule.waitUntil(15000) { !model.connecting && !model.loading }
        assertNull(model.connectionError)
    }

    private fun selectLocal(): LocalStorage {
        val id = "fixtures/${UUID.randomUUID()}"
        val tree = DocumentsContract.buildTreeDocumentUri("com.example.webdavmanager.test.documents", id)
        context.startActivity(Intent().setClassName("com.example.webdavmanager.test", LocalTestDocumentsProvider.GrantActivity::class.java.name)
            .putExtra("root", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        rule.waitUntil(10000) { context.checkUriPermission(tree, android.os.Process.myPid(), android.os.Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        rule.runOnIdle { model.selectTab(0); model.selectLocalFolder(tree) }
        rule.waitUntil(10000) { !model.localBusy && !model.localLoading }
        assertNull(model.localError)
        return model.localStorage!!
    }

    @Test fun multiSelectCopiesAcrossStorageInBothDirectionsAndDeletes() {
        TransferStore().use { store ->
            connect(store)
            val local = selectLocal()
            val root = local.root.toString()
            runBlocking {
                local.create(root, "本地 + folder", true)
                val folder = local.list(root).single()
                local.create(folder.path, "a + 中文.txt", false)
                val text = local.list(folder.path).single()
                local.saveText(text.path, "cross-storage UTF-8", local.readText(text.path))
                local.create(root, "second.txt", false)
            }
            rule.runOnIdle { model.refreshLocal() }
            rule.waitUntil(10000) { !model.localLoading && model.localEntries.size == 2 }
            rule.onNodeWithText("本地 + folder").performTouchInput { longClick() }
            rule.onNodeWithText("Select shown").performClick()
            rule.onNodeWithText("Copy").performScrollTo().performClick()
            rule.onNodeWithText("WebDAV 1").performClick()
            rule.runOnIdle { model.navigate("/files/target") }
            rule.waitUntil(10000) { !model.loading }
            assertTrue(model.canPaste)
            rule.onNodeWithText("Paste").performClick()
            rule.onNodeWithText("Confirm paste").performClick()
            rule.waitUntil(20000) { !model.operationBusy && !model.loading }
            assertEquals(listOf("Copied", "Copied"), model.operationResults.map { it.status })
            assertEquals("cross-storage UTF-8", store.files["/files/target/本地 + folder/a + 中文.txt"]!!.toString(Charsets.UTF_8))
            val remoteFolder = model.entries.first { it.isDirectory }
            rule.runOnIdle { model.stageFiles(false, model.entries); model.selectTab(1) }
            rule.waitUntil(10000) { !model.localLoading }
            rule.runOnIdle { model.createLocal("return-copy", true) }
            rule.waitUntil(10000) { !model.localBusy && !model.localLoading && model.localEntries.any { it.name == "return-copy" } }
            rule.runOnIdle { model.navigateLocal(model.localEntries.first { it.name == "return-copy" }.path) }
            rule.waitUntil(10000) { !model.localLoading }
            rule.runOnIdle { model.pasteFiles() }
            rule.waitUntil(20000) { !model.operationBusy && !model.localLoading }
            assertEquals(setOf("Copied"), model.operationResults.map { it.status }.toSet())
            assertEquals(2, model.localEntries.size)
            val copiedFolder = model.localEntries.first { it.name == remoteFolder.name }
            assertEquals("cross-storage UTF-8", runBlocking { local.readText(local.list(copiedFolder.path).single().path) }.content)
            rule.runOnIdle { model.selectVisible(); model.requestDelete() }
            rule.onNodeWithText("Confirm delete").performClick()
            rule.waitUntil(20000) { !model.operationBusy && !model.localLoading }
            assertEquals(2, model.operationResults.count { it.status == "Deleted" })
            assertTrue(model.localEntries.isEmpty())
            rule.runOnIdle { model.selectTab(2) }
        }
    }

    @Test fun crossMovesAndFailuresPreserveSourceAndCleanPartialFolder() = runBlocking {
        TransferStore().use { store ->
            val directory = File(context.cacheDir.canonicalFile, "cross-${UUID.randomUUID()}").apply { mkdir() }
            val local = LocalStorage(context, Uri.fromFile(directory))
            val dav = WebDavClient(store.connection)
            val root = local.root.toString()
            try {
                val source = dav.list("/files").first { it.name == "remote.xyz" }
                CrossStorageTransfer(context, null, dav, local, null).transfer(source, "/files", root, source.name, true)
                assertFalse(store.files.containsKey(source.path))
                assertEquals("remote bytes", File(directory, source.name).readText())
                val localFile = local.list(root).single()
                CrossStorageTransfer(context, local, null, null, dav).transfer(localFile, root, "/files", "back + 中文.xyz", true)
                assertTrue(local.list(root).isEmpty())
                assertEquals("remote bytes", store.files["/files/back + 中文.xyz"]!!.toString(Charsets.UTF_8))
                local.create(root, "folder", true)
                val folder = local.list(root).single()
                local.create(folder.path, "keep.txt", false)
                store.failUpload.set(true)
                try {
                    CrossStorageTransfer(context, local, null, null, dav).transfer(folder, root, "/files", "failed-folder", true)
                    fail("Upload failure expected")
                } catch (_: java.io.IOException) { }
                assertTrue(local.list(folder.path).isNotEmpty())
                assertFalse(store.directories.contains("/files/failed-folder"))
                assertTrue(File(context.cacheDir, "file_transfers").listFiles().orEmpty().isEmpty())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun crossTransfersWorkWhenServerRejectsDepthZero() = runBlocking {
        TransferStore().use { store ->
            store.rejectDepthZero.set(true)
            val directory = File(context.cacheDir.canonicalFile, "depth-fallback-${UUID.randomUUID()}").apply { mkdir() }
            val local = LocalStorage(context, Uri.fromFile(directory))
            val dav = WebDavClient(store.connection)
            try {
                val source = dav.list("/files").first { !it.isDirectory }
                CrossStorageTransfer(context, null, dav, local, null)
                    .transfer(source, "/files", local.root.toString(), source.name, false)
                assertEquals("remote bytes", File(directory, source.name).readText())
                val copied = local.list(local.root.toString()).single()
                CrossStorageTransfer(context, local, null, null, dav)
                    .transfer(copied, local.root.toString(), "/files/target", "空格 + %2F.xyz", false)
                assertEquals("remote bytes", store.files["/files/target/空格 + %2F.xyz"]!!.toString(Charsets.UTF_8))
                assertFalse(store.files.keys.any { it.contains(".webdav-transfer-") })
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun remoteBatchDeleteKeepsFailuresAndUnknownFilePromptsExternalApp() {
        TransferStore().use { store ->
            connect(store)
            val unknown = model.entries.first { it.name == "remote.xyz" }
            rule.runOnIdle { model.open(unknown) }
            rule.onNodeWithText("Open with other app?").assertExists()
            assertEquals(unknown.path, model.externalTarget!!.path)
            rule.onNodeWithText("Cancel").performClick()
            store.files["/files/keep.xyz"] = "keep".toByteArray()
            store.failedDelete = "/files/keep.xyz"
            rule.runOnIdle { model.refresh() }
            rule.waitUntil(10000) { !model.loading }
            rule.runOnIdle { model.requestDelete(model.entries.filter { !it.isDirectory }) }
            rule.onNodeWithText("Confirm delete").performClick()
            rule.waitUntil(15000) { !model.operationBusy && !model.loading }
            assertEquals(setOf("Deleted", "Failed"), model.operationResults.map { it.status }.toSet())
            assertFalse(store.files.containsKey(unknown.path))
            assertTrue(store.files.containsKey("/files/keep.xyz"))
        }
    }

    @Test fun localUnknownFileProducesReadOnlyContentUri() {
        rule.waitUntil(10000) { !model.starting }
        val local = selectLocal()
        val uri = DocumentsContract.createDocument(context.contentResolver, local.root, "application/octet-stream", "file.unknown")!!
        context.contentResolver.openOutputStream(uri, "rwt")!!.use { it.write("private bytes".toByteArray()) }
        rule.runOnIdle { model.refreshLocal() }
        rule.waitUntil(10000) { !model.localLoading && model.localEntries.size == 1 }
        rule.runOnIdle { model.open(model.localEntries.single()) }
        rule.onNodeWithText("Open with other app?").assertExists()
        // Inspect the prepared URI without starting another app during instrumentation.
        rule.runOnIdle { rule.activity.setContent { androidx.compose.material3.Text("External file fixture") } }
        rule.waitForIdle()
        rule.runOnIdle { model.prepareExternalOpen() }
        rule.waitUntil(10000) { model.externalReady != null || model.externalError != null }
        assertNull(model.externalError)
        val ready = model.externalReady!!
        assertEquals("content", ready.scheme)
        assertEquals("${context.packageName}.files", ready.authority)
        assertEquals("private bytes", context.contentResolver.openInputStream(ready)!!.bufferedReader().use { it.readText() })
        rule.runOnIdle { model.cancelExternalOpen(); model.selectTab(2) }
    }
}

private class TransferStore : AutoCloseable {
    val server = MockWebServer()
    val files = ConcurrentHashMap<String, ByteArray>().apply { put("/files/remote.xyz", "remote bytes".toByteArray()) }
    val directories = ConcurrentHashMap.newKeySet<String>().apply { addAll(listOf("/files", "/files/target")) }
    val failUpload = AtomicBoolean(false)
    val rejectDepthZero = AtomicBoolean(false)
    val loseUploadResponse = AtomicBoolean(false)
    @Volatile var onGet: ((String) -> Unit)? = null
    @Volatile var onPut: ((String) -> Unit)? = null
    @Volatile var failedDelete: String? = null
    val connection: WebDavConnection
    init {
        server.start()
        connection = WebDavConnection(UUID.randomUUID().toString(), "Transfer test", server.url("/dav/").toString(), "user", "pass", "/files")
        val paths = DavPaths(connection.baseUrl, connection.rootPath)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Basic dXNlcjpwYXNz") return MockResponse().setResponseCode(401)
                val path = normalizePath("/" + request.requestUrl!!.pathSegments.drop(1).filter { it.isNotEmpty() }.joinToString("/"))
                return when (request.method) {
                    "PROPFIND" -> {
                        if (rejectDepthZero.get() && request.getHeader("Depth") == "0") return MockResponse().setResponseCode(405)
                        if (!directories.contains(path) && !files.containsKey(path)) return MockResponse().setResponseCode(404)
                        val children = if (request.getHeader("Depth") == "0") listOf(path) else
                            (directories.filter { it != path && parentPath(it) == path } + files.keys.filter { parentPath(it) == path }).sorted()
                        val xml = children.joinToString("") { child ->
                            val folder = directories.contains(child)
                            val name = child.substringAfterLast('/').replace("&", "&amp;").replace("<", "&lt;")
                            """<d:response><d:href>${paths.url(child, folder).encodedPath}</d:href><d:propstat><d:prop><d:displayname>$name</d:displayname>
                            <d:resourcetype>${if (folder) "<d:collection/>" else ""}</d:resourcetype><d:getcontentlength>${files[child]?.size ?: 0}</d:getcontentlength>
                            <d:getetag>"v1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
                        }
                        MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">$xml</d:multistatus>")
                    }
                    "GET" -> files[path]?.let { bytes -> onGet?.invoke(path); MockResponse().setBody(Buffer().write(bytes)).setHeader("ETag", "\"v1\"") } ?: MockResponse().setResponseCode(404)
                    "MKCOL" -> {
                        if (directories.contains(path) || files.containsKey(path)) MockResponse().setResponseCode(405)
                        else { directories.add(path); MockResponse().setResponseCode(201) }
                    }
                    "PUT" -> {
                        onPut?.invoke(path)
                        if (failUpload.getAndSet(false)) return MockResponse().setResponseCode(403)
                        if (request.getHeader("If-None-Match") != "*" || files.containsKey(path)) MockResponse().setResponseCode(412)
                        else { files[path] = request.body.readByteArray()
                            if (loseUploadResponse.getAndSet(false)) MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                            else MockResponse().setResponseCode(201).setHeader("ETag", "\"v1\"") }
                    }
                    "DELETE" -> {
                        if (path == failedDelete) return MockResponse().setResponseCode(403)
                        val exists = files.containsKey(path) || directories.contains(path)
                        files.keys.filter { it == path || it.startsWith("$path/") }.forEach { files.remove(it) }
                        directories.filter { it == path || it.startsWith("$path/") }.forEach { directories.remove(it) }
                        MockResponse().setResponseCode(if (exists) 204 else 404)
                    }
                    "MOVE" -> {
                        val to = paths.relative(request.getHeader("Destination")!!, parentPath(path))
                        if (files.containsKey(to) || directories.contains(to)) return MockResponse().setResponseCode(412)
                        if (!files.containsKey(path) && !directories.contains(path)) return MockResponse().setResponseCode(404)
                        files.keys.filter { it == path || it.startsWith("$path/") }.toList().forEach { old -> files[to + old.removePrefix(path)] = files.remove(old)!! }
                        directories.filter { it == path || it.startsWith("$path/") }.toList().forEach { old -> directories.remove(old); directories.add(to + old.removePrefix(path)) }
                        MockResponse().setResponseCode(201)
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
    }
    override fun close() { server.close() }
}
