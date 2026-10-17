package com.example.webdavmanager

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class FileTransferNetworkTest {
    @Test fun depthZeroStatUsesHrefNameAndConditionalDeleteRejectsReplacement() = runBlocking {
        MockWebServer().use { server ->
            val dav = client(server)
            server.enqueue(MockResponse().setResponseCode(207).setBody("""
                <d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/files/a%20+%20%E4%B8%AD%E6%96%87.bin</d:href>
                <d:propstat><d:prop><d:displayname>A title / not a filename</d:displayname><d:getcontentlength>3</d:getcontentlength>
                <d:getetag>"v1"</d:getetag><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                </d:response></d:multistatus>
            """))
            val file = dav.stat("/files/a + 中文.bin")
            assertEquals("a + 中文.bin", file.name)
            assertEquals("A title / not a filename", file.displayName)
            val statRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("0", statRequest.getHeader("Depth"))
            assertFalse(statRequest.path!!.endsWith("/"))
            server.enqueue(MockResponse().setResponseCode(412))
            try { dav.deleteVerified(file); fail("Replacement must be retained") } catch (_: IOException) { }
            assertEquals("\"v1\"", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("If-Match"))
            try { dav.deleteVerified(file.copy(etag = null)); fail("No conditional version") } catch (_: IOException) { }
            assertEquals(0, dav.http.dispatcher.runningCallsCount())
        }
    }

    @Test fun realUploadLargerThan512MiBStreamsWithoutBufferingWholeFile() = runBlocking {
        MockWebServer().use { server ->
            server.bodyLimit = 0
            val source = File.createTempFile("large-transfer", ".bin")
            val bytes = 513L * 1024 * 1024
            try {
                java.io.RandomAccessFile(source, "rw").use { it.setLength(bytes) }
                server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"large\""))
                assertEquals("\"large\"", client(server).upload("/files/large.bin", source, null))
                val request = server.takeRequest(10, TimeUnit.SECONDS)!!
                assertEquals(bytes, request.bodySize)
                assertEquals(0L, request.body.size)
            } finally { source.delete() }
        }
    }
    private fun client(server: MockWebServer) = WebDavClient(WebDavConnection("test", "test", server.url("/dav/").toString(), "user", "pass", "/files"))
    private val entry = FileEntry("空格 + 中文.bin", "/files/空格 + 中文.bin", false, 3, null, "\"v1\"", "application/octet-stream")

    @Test fun uploadDownloadAndFolderCreationUseValidatedConditionalRequests() = runBlocking {
        MockWebServer().use { server ->
            val dav = client(server)
            val temporary = File.createTempFile("transfer", ".bin")
            try {
                temporary.writeBytes(byteArrayOf(1, 2, 3))
                server.enqueue(MockResponse().setResponseCode(201))
                dav.upload(entry.path, temporary, entry.mimeType)
                val upload = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("PUT", upload.method)
                assertEquals("*", upload.getHeader("If-None-Match"))
                assertEquals("/dav/files/%E7%A9%BA%E6%A0%BC%20+%20%E4%B8%AD%E6%96%87.bin", upload.path)
                assertArrayEquals(byteArrayOf(1, 2, 3), upload.body.readByteArray())
                assertNotNull(upload.getHeader("Authorization"))
                server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(4, 5, 6))))
                dav.download(entry, temporary, 3)
                assertArrayEquals(byteArrayOf(4, 5, 6), temporary.readBytes())
                val download = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("\"v1\"", download.getHeader("If-Match"))
                assertEquals("identity", download.getHeader("Accept-Encoding"))
                server.enqueue(MockResponse().setResponseCode(201))
                dav.createDirectory("/files/目录 +")
                val folder = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("MKCOL", folder.method)
                assertTrue(folder.path!!.endsWith("/"))
                assertEquals("*", folder.getHeader("If-None-Match"))
                server.enqueue(MockResponse().setResponseCode(204))
                dav.delete(entry.path, entry.etag)
                assertEquals("\"v1\"", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("If-Match"))
            } finally { temporary.delete() }
        }
    }

    @Test fun transfersRejectConflictsPartialResponsesAndCapacityOverflow() = runBlocking {
        MockWebServer().use { server ->
            val dav = client(server)
            val target = File.createTempFile("transfer", ".bin")
            try {
                for (response in listOf(MockResponse().setResponseCode(412), MockResponse().setResponseCode(206).setBody("abc"),
                    MockResponse().setBody("abcd"), MockResponse().setBody("ab"))) {
                    server.enqueue(response)
                    try { dav.download(entry, target, 3); fail("Unsafe download") } catch (_: IOException) { }
                    assertFalse(target.exists())
                }
                target.writeText("abc")
                for (code in listOf(412, 200, 207)) {
                    server.enqueue(MockResponse().setResponseCode(code))
                    try { dav.upload(entry.path, target, null); fail("Unconfirmed upload") } catch (_: IOException) { }
                }
                server.enqueue(MockResponse().setResponseCode(405))
                try { dav.createDirectory("/files/existing"); fail("Existing folder") } catch (_: IOException) { }
                try { dav.upload("/files-other/outside", target, null); fail("Root boundary") } catch (_: IllegalArgumentException) { }
            } finally { target.delete() }
        }
    }

    @Test fun cancelledDownloadClosesNetworkCallAndRemovesPartialFile() = runBlocking {
        MockWebServer().use { server ->
            val dav = client(server)
            val target = File.createTempFile("transfer", ".bin")
            try {
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val job = async { dav.download(entry, target, 3) }
                withTimeout(5000) {
                    while (dav.http.dispatcher.runningCallsCount() == 0) delay(10)
                    job.cancelAndJoin()
                    while (dav.http.dispatcher.runningCallsCount() != 0) delay(10)
                }
                assertTrue(job.isCancelled)
                assertFalse(target.exists())
            } finally { target.delete() }
        }
    }
}
