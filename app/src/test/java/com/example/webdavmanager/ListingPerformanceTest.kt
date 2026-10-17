package com.example.webdavmanager

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit

class ListingPerformanceTest {
    private fun client(server: MockWebServer) = WebDavClient(WebDavConnection(
        "listing", "listing", server.url("/dav/").toString(), "", "", "/files"))
    private fun response(href: String) = """<d:response><d:href>$href</d:href><d:propstat><d:prop>
        <d:resourcetype/><d:getcontentlength>3</d:getcontentlength><d:getetag>"v1"</d:getetag>
        </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
    private fun listing(items: String) = "<d:multistatus xmlns:d=\"DAV:\">$items</d:multistatus>"

    @Test fun inspectUsesSingleDepthZeroRequestAndOnlyFallsBackForUnsupportedStat() = runBlocking {
        MockWebServer().use { server ->
            val dav = client(server)
            val file = FileEntry("a + 中文.txt", "/files/a + 中文.txt", false, 3, null, "\"v1\"", null)
            val xml = listing(response(dav.paths.url(file.path).encodedPath))
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml))
            assertEquals(file.path, dav.inspect(file).path)
            assertEquals("0", server.takeRequest().getHeader("Depth"))
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(405))
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml))
            assertEquals(file.path, dav.inspect(file).path)
            assertEquals("0", server.takeRequest().getHeader("Depth"))
            assertEquals("1", server.takeRequest().getHeader("Depth"))
            server.enqueue(MockResponse().setResponseCode(403))
            try { dav.inspect(file); fail("Permission errors must not fall back") } catch (_: IOException) { }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun streamingListingDeduplicatesAndPreservesEncodingAndPropstat() = runBlocking {
        MockWebServer().use { server ->
            val dav = client(server)
            val names = listOf("a space.txt", "a+plus.txt", "中文.txt", "literal%2Fname.txt")
            val entries = names.joinToString("") { response(dav.paths.url("/files/$it").encodedPath) }
            server.enqueue(MockResponse().setResponseCode(207).setChunkedBody(listing(entries + entries), 31))
            val files = dav.list("/files")
            assertEquals(names.toSet(), files.map { it.name }.toSet())
            assertEquals(names.size, files.size)
            for (href in listOf("/dav/files-other/escape.txt", "/dav/files/a%2Fb.txt")) {
                server.enqueue(MockResponse().setResponseCode(207).setBody(listing(response(href))))
                try { dav.list("/files"); fail("Unsafe href") } catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun streamingListingKeepsResponseByteLimitAndRejectsDoctype() = runBlocking {
        MockWebServer().use { server ->
            val dav = client(server)
            server.enqueue(MockResponse().setResponseCode(207).setChunkedBody(listing(" ".repeat(8 * 1024 * 1024)), 8192))
            try { dav.list("/files"); fail("Byte limit") } catch (_: IOException) { }
            server.enqueue(MockResponse().setResponseCode(207).setBody("<!DOCTYPE x [<!ENTITY a 'x'>]>" + listing("&a;")))
            try { dav.list("/files"); fail("Entity declaration") } catch (_: IOException) { }
        }
    }

    @Test fun largeListingCanCancelDuringParsingBeforeReturningAnyResult() {
        val paths = DavPaths("https://example.test/dav/", "/files")
        val xml = listing((0 until 10000).joinToString("") { response("/dav/files/item$it") })
        var tokens = 0
        try {
            parseListing(StringReader(xml), paths, "/files") { if (++tokens > 1000) throw CancellationException() }
            fail("Cancelled parser")
        } catch (_: CancellationException) { }
        assertEquals(1001, tokens)
        assertEquals(10000, parseListing(xml, paths, "/files").size)
    }

    @Test fun streamedVerificationKeepsConditionalRequestAndDetectsTruncation() = runBlocking {
        MockWebServer().use { server ->
            val dav = client(server)
            val file = FileEntry("file", "/files/file", false, 3, null, "\"v1\"", null)
            server.enqueue(MockResponse().setBody("abc"))
            val digest = dav.readFile(file, discardOutput, Long.MAX_VALUE)
            assertEquals(3L, digest.bytes)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest.sha256)
            assertEquals("\"v1\"", server.takeRequest().getHeader("If-Match"))
            server.enqueue(MockResponse().setBody("ab"))
            try { dav.readFile(file, discardOutput, Long.MAX_VALUE); fail("Truncated verification") } catch (_: IOException) { }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = async { dav.readFile(file, discardOutput, Long.MAX_VALUE) }
            withTimeout(5000) {
                server.takeRequest(2, TimeUnit.SECONDS)
                while (dav.http.dispatcher.runningCallsCount() == 0) delay(10)
                job.cancelAndJoin()
                while (dav.http.dispatcher.runningCallsCount() != 0) delay(10)
            }
        }
    }
}
