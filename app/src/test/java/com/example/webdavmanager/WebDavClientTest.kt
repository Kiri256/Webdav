package com.example.webdavmanager

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class WebDavClientTest {
    @Test fun checkedTransfersRejectChangedSourcesWithoutStrongEtagsAndUseDepthFallback() = runBlocking {
        MockWebServer().use { server ->
            val dav = client(server)
            val file = FileEntry("a+b 中文.txt", "/photos/a+b 中文.txt", false, 3, "old", null, null)
            fun properties(size: Long, modified: String, tag: String? = null) = MockResponse().setResponseCode(207).setBody("""
                <d:multistatus xmlns:d="DAV:"><d:response><d:href>${dav.paths.url(file.path).encodedPath}</d:href>
                <d:propstat><d:prop><d:getcontentlength>$size</d:getcontentlength><d:getlastmodified>$modified</d:getlastmodified>
                ${tag?.let { "<d:getetag>$it</d:getetag>" }.orEmpty()}<d:resourcetype/></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
            """.trimIndent())
            for (move in listOf(false, true)) {
                server.enqueue(properties(4, "new"))
                try { dav.transferChecked(file, "/photos/target/a.txt", move); fail("Changed source") }
                catch (_: IOException) { }
                assertEquals("PROPFIND", server.takeRequest().method)
            }
            assertEquals(2, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(405))
            server.enqueue(properties(3, "old", "\"fresh\""))
            server.enqueue(MockResponse().setResponseCode(201))
            dav.transferChecked(file, "/photos/target/a.txt", true)
            assertEquals("0", server.takeRequest().getHeader("Depth"))
            assertEquals("1", server.takeRequest().getHeader("Depth"))
            val moved = server.takeRequest()
            assertEquals("MOVE", moved.method)
            assertEquals("\"fresh\"", moved.getHeader("If-Match"))
            assertEquals("F", moved.getHeader("Overwrite"))
        }
    }

    @Test fun copyAndMoveUseValidatedDestinationsAndNeverOverwrite() = runBlocking {
        MockWebServer().use { server ->
            val dav = client(server)
            val file = FileEntry("a+b 中文.txt", "/photos/a+b 中文.txt", false, 3, null, "\"v1\"", "text/plain")
            val destination = dav.paths.child("/photos/target", file.name)
            repeat(2) { index ->
                server.enqueue(MockResponse().setResponseCode(if (index == 0) 201 else 204))
                dav.transfer(file, destination, index == 1)
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals(if (index == 0) "COPY" else "MOVE", request.method)
                assertEquals("/dav/photos/a+b%20%E4%B8%AD%E6%96%87.txt", request.path)
                assertEquals(server.url("/dav/photos/target/a+b%20%E4%B8%AD%E6%96%87.txt").toString(), request.getHeader("Destination"))
                assertEquals("F", request.getHeader("Overwrite"))
                assertEquals("infinity", request.getHeader("Depth"))
                assertEquals("\"v1\"", request.getHeader("If-Match"))
                assertNotNull(request.getHeader("Authorization"))
            }
        }
    }

    @Test fun transferRejectsUnconfirmedResponsesAndUnsafeTargets() = runBlocking {
        MockWebServer().use { server ->
            val dav = client(server)
            val folder = FileEntry("album", "/photos/album", true, null, null, null, null)
            for (target in listOf("/private/album", "/photos/album", "/photos/album/child")) {
                try { dav.transfer(folder, target, true); fail("Unsafe target") }
                catch (_: IllegalArgumentException) { }
            }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(412))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setResponseCode(207).setBody("""
                <d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/photos/target/child.txt</d:href>
                <d:status>HTTP/1.1 403 Forbidden</d:status></d:response></d:multistatus>
            """))
            repeat(3) {
                try { dav.transfer(folder, "/photos/target", false); fail("Unconfirmed transfer") }
                catch (_: IOException) { }
            }
        }
    }

    @Test fun cancelledMoveCancelsClientRequest() = runBlocking {
        MockWebServer().use { server ->
            val dav = client(server)
            val file = FileEntry("a.txt", "/photos/a.txt", false, 3, null, null, "text/plain")
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = async { dav.transfer(file, "/photos/target/a.txt", true) }
            withTimeout(3000) {
                while (dav.http.dispatcher.runningCallsCount() == 0) kotlinx.coroutines.delay(10)
                job.cancelAndJoin()
                while (dav.http.dispatcher.runningCallsCount() != 0) kotlinx.coroutines.delay(10)
            }
            assertTrue(job.isCancelled)
        }
    }

    @Test fun textReadAndConditionalSavePreserveUtf8AndPaths() = runBlocking {
        MockWebServer().use { server ->
            val dav = client(server)
            server.enqueue(MockResponse().setBody("\uFEFF你好\r\na+b").setHeader("ETag", "\"v1\""))
            val original = dav.readText("/photos/中文 a+b.txt")
            assertEquals("你好\r\na+b", original.content)
            assertTrue(original.bom)
            val get = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", get.method)
            assertEquals("/dav/photos/%E4%B8%AD%E6%96%87%20a+b.txt", get.path)
            assertNotNull(get.getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"v2\""))
            val result = dav.saveText("/photos/中文 a+b.txt", "修改\r\n", original)
            assertEquals("\"v2\"", result.etag)
            val put = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("PUT", put.method)
            assertEquals(get.path, put.path)
            assertEquals("\"v1\"", put.getHeader("If-Match"))
            assertEquals("\uFEFF修改\r\n", put.body.readUtf8())
        }
    }

    @Test fun textConflictsAndMissingValidatorsDoNotOverwrite() = runBlocking {
        MockWebServer().use { server ->
            val dav = client(server)
            server.enqueue(MockResponse().setResponseCode(412))
            try { dav.saveText("/photos/a.txt", "draft", RemoteText("old", "\"v1\"", null)); fail("Conflict expected") }
            catch (e: IOException) { assertTrue(e.message!!.contains("changed")) }
            server.takeRequest(2, TimeUnit.SECONDS)
            try { dav.saveText("/photos/a.txt", "draft", RemoteText("old", null, null)); fail("Validator required") }
            catch (_: IOException) { }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun textReadRejectsBinaryAndOversizedBodies() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(TEXT_LIMIT + 1)))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(0xff.toByte()))))
            server.enqueue(MockResponse().setBody("binary\u0000data"))
            repeat(3) {
                try { client(server).readText("/photos/a.txt"); fail("Invalid text expected") }
                catch (_: IOException) { }
            }
        }
    }

    @Test fun textSaveUsesModifiedDateAndRejectsAcceptedResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202))
            val date = "Wed, 21 Oct 2015 07:28:00 GMT"
            try { client(server).saveText("/photos/a.txt", "draft", RemoteText("old", null, date)); fail("Not completed") }
            catch (_: IOException) { }
            assertEquals(date, server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("If-Unmodified-Since"))
        }
    }

    @Test fun audioRangeProbeIsAuthenticated() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/100").setBody("a"))
            val file = FileEntry("song.mp3", "/photos/song.mp3", false, 100, null, null, "audio/mpeg")
            assertTrue(file.isAudio())
            assertTrue(client(server).supportsRange(file))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("bytes=0-0", request.getHeader("Range"))
            assertNotNull(request.getHeader("Authorization"))
        }
    }

    private fun client(server: MockWebServer) = WebDavClient(WebDavConnection(
        "test", "Test", server.url("/dav/").toString(), "user", "pass", "/photos"))

    @Test fun deletionPreservesPlusAndSendsAuthentication() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            client(server).delete("/photos/a+b.jpg")
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("DELETE", request.method)
            assertEquals("/dav/photos/a+b.jpg", request.path)
            assertEquals("Basic dXNlcjpwYXNz", request.getHeader("Authorization"))
        }
    }

    @Test fun failedMultistatusAndAcceptedDeleteAreNotSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(207).setBody("""
                <d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/photos/a.jpg</d:href>
                <d:status>HTTP/1.1 403 Forbidden</d:status></d:response></d:multistatus>
            """))
            server.enqueue(MockResponse().setResponseCode(202))
            repeat(2) {
                try { client(server).delete("/photos/a.jpg"); fail("Deletion should fail") }
                catch (_: IOException) { }
            }
        }
    }

    @Test fun propfindUsesDirectoryUrlAndDepth() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"/>"))
            assertTrue(client(server).list("/photos").isEmpty())
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("PROPFIND", request.method)
            assertEquals("/dav/photos/", request.path)
            assertEquals("1", request.getHeader("Depth"))
        }
    }

    @Test fun cancelledRequestClosesTheNetworkCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val dav = client(server)
            val job = async { dav.list("/photos") }
            withTimeout(3000) {
                while (dav.http.dispatcher.runningCallsCount() == 0) kotlinx.coroutines.delay(10)
                job.cancelAndJoin()
                while (dav.http.dispatcher.runningCallsCount() != 0) kotlinx.coroutines.delay(10)
            }
            assertTrue(job.isCancelled)
        }
    }
}
