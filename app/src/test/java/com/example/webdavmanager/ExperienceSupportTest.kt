package com.example.webdavmanager

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExperienceSupportTest {
    @Test fun subtitleTimeoutIsRecoverableAndExplicitCancellationStaysCancellation() = runTest {
        try {
            awaitSubtitleResult(5000) { awaitCancellation() }
            fail("Timeout must report a load failure")
        } catch (_: java.io.IOException) { }
        assertTrue(coroutineContext[kotlinx.coroutines.Job]!!.isActive)
        val loading = async { awaitSubtitleResult(5000) { awaitCancellation() } }
        testScheduler.runCurrent()
        loading.cancelAndJoin()
        assertTrue(loading.isCancelled)
        assertEquals(42, awaitSubtitleResult(5000) { 42 })
    }
    private fun entry(name: String) = FileEntry(name, "/root/$name", false, null, null, null, null)
    @Test fun matchingSubtitlesUsesLiteralStemAndRanksUnambiguousChineseNames() {
        val names = listOf("电影 + %2F.srt", "电影 + %2F.chs.ass", "电影 + %2F.en.vtt", "电影 + %2F2.srt", "other.zh.srt")
        val result = subtitleCandidates(entry("电影 + %2F.mkv"), names.map(::entry))
        assertEquals(listOf(names[1], names[2], names[0]), result.map { it.name })
        assertFalse(preferredChineseSubtitle("english.srt"))
        assertTrue(preferredChineseSubtitle("movie.zh-CN.srt"))
        assertTrue(subtitleCandidates(entry("a.mp4"), listOf(entry("a.srt").copy(isDirectory = true))).isEmpty())
    }
    @Test fun remoteJumpPathsKeepUserCharactersLiteralAndRejectOtherServersAndBoundaries() {
        val paths = DavPaths("https://example.test/dav/", "/root")
        for (name in listOf("a space", "a+b", "中文", "a%2Fb")) {
            val target = remoteJumpPath(paths, "/root/$name")
            assertEquals(name, paths.url(target, true).pathSegments.filter { it.isNotEmpty() }.last())
        }
        for (target in listOf("https://other.test/root", "/root-other", "/root/../escape", "/root/a\\b")) {
            try { remoteJumpPath(paths, target); fail("Unsafe path") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun remoteSubtitleVerifiesVersionUsesAuthenticatedConditionalGetAndCleansTruncation(): Unit = runBlocking {
        MockWebServer().use { server ->
            val dav = WebDavClient(WebDavConnection("subtitle", "test", server.url("/dav/").toString(), "user", "secret", "/root"))
            val file = entry("字幕 + %2F.srt").copy(size = 3, etag = "\"v1\"")
            val href = dav.paths.url(file.path).encodedPath
            fun properties(tag: String) = MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>$href</d:href><d:propstat><d:prop><d:getcontentlength>3</d:getcontentlength><d:getetag>$tag</d:getetag><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""")
            val target = File.createTempFile("subtitle-", ".srt")
            try {
                server.enqueue(properties("\"v1\"")); server.enqueue(MockResponse().setBody("abc"))
                dav.downloadSubtitle(file, target)
                assertEquals("abc", target.readText())
                val stat = server.takeRequest(); val get = server.takeRequest()
                assertEquals("0", stat.getHeader("Depth")); assertNotNull(get.getHeader("Authorization"))
                assertEquals("\"v1\"", get.getHeader("If-Match")); assertEquals(href, get.requestUrl!!.encodedPath)
                server.enqueue(properties("\"v2\""))
                try { dav.downloadSubtitle(file, target); fail("Version changed") } catch (_: java.io.IOException) { }
                server.takeRequest()
                server.enqueue(properties("\"v1\"")); server.enqueue(MockResponse().setBody("ab"))
                try { dav.downloadSubtitle(file, target); fail("Truncated subtitle") } catch (_: java.io.IOException) { }
                assertFalse(target.exists())
            } finally { target.delete() }
        }
    }
}
