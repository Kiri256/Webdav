package com.example.webdavmanager

import org.junit.Assert.*
import org.junit.Test

class DavProtocolTest {
    private val paths = DavPaths("https://example.org/dav/", "/photos")
    private fun file(path: String) = FileEntry(path.substringAfterLast('/'), path, false, 12, null, "\"v1\"", "image/jpeg")
    private fun xml(responses: String) = "<d:multistatus xmlns:d=\"DAV:\">$responses</d:multistatus>"

    @Test fun transferChildNamesPreserveLiteralsAndRootBoundaries() {
        for (name in listOf("a b.txt", "a+b.txt", "中文.txt", "a%2Fb.txt")) {
            val child = paths.child("/photos/target", name)
            assertEquals("/photos/target/$name", child)
            assertEquals(child, paths.relative(paths.url(child).toString(), "/photos/target"))
        }
        for (name in listOf("..", "a/b", "a\\b")) {
            assertThrows(IllegalArgumentException::class.java) { paths.child("/photos", name) }
        }
        assertThrows(IllegalArgumentException::class.java) { paths.child("/photos-other", "a.txt") }
        assertThrows(IllegalArgumentException::class.java) { paths.validateTransfer("/photos", "/photos/target", true) }
        paths.validateTransfer("/photos/a", "/photos/ab/a", true)
    }

    @Test fun pathsRoundTripWithoutChangingNames() {
        listOf("a+b.jpg", "a b.jpg", "a%20b.jpg", "a&b.jpg", "photo#.jpg", " photo .jpg", "\u56fe\u7247.jpg").forEach {
            val path = "/photos/$it"
            val url = paths.url(path)
            assertEquals(path, paths.relative(url.toString(), "/photos"))
        }
        assertNotEquals(paths.url("/photos/a+b.jpg"), paths.url("/photos/a b.jpg"))
        assertEquals("/dav/photos/", paths.url("/photos", true).encodedPath)
    }

    @Test fun rejectsForeignPathsAndTraversal() {
        listOf("https://other.org/dav/photos/a.jpg", "/dav-other/photos/a.jpg", "/dav/private/a.jpg",
            "/dav/photos/%2e%2e/private/a.jpg", "/dav/photos/a%2Fb.jpg", "/dav/photos/a%5Cb.jpg",
            "/dav/photos/a.jpg?x=1").forEach { href ->
            assertThrows(IllegalArgumentException::class.java) { paths.relative(href, "/photos") }
        }
        assertThrows(IllegalArgumentException::class.java) { paths.url("/photos/../private/a.jpg") }
        assertThrows(IllegalArgumentException::class.java) { paths.url("/private/a.jpg") }
    }

    @Test fun validatesConnectionAddresses() {
        listOf("not a url", "ftp://example.org", "https://example.org/a b", "https://user:pass@example.org/",
            "https://example.org/?token=x", "https://example.org/#frag").forEach {
            assertThrows(IllegalArgumentException::class.java) { DavPaths(it) }
        }
    }

    @Test fun successfulPropertiesSurviveFailedPropstatAndEntities() {
        val result = parseListing(xml("""
            <d:response><d:href>/dav/photos/a+b.jpg</d:href>
              <d:propstat><d:prop><d:displayname>a&amp;b.jpg</d:displayname><d:getcontentlength>123</d:getcontentlength>
                <d:getetag>"v1"</d:getetag><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              <d:propstat><d:prop><d:getcontentlength/><d:getetag/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
            </d:response>
        """), paths, "/photos")
        assertEquals(1, result.size)
        assertEquals("/photos/a+b.jpg", result.single().path)
        assertEquals("a+b.jpg", result.single().name)
        assertEquals("a&b.jpg", result.single().displayName)
        assertEquals(123L, result.single().size)
        assertEquals("\"v1\"", result.single().etag)
    }

    @Test fun listingIncludesOnlyDirectChildrenAndDeduplicates() {
        fun response(href: String) = """
            <d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        """
        val result = parseListing(xml(response("/dav/photos/") + response("/dav/photos/sub/") +
            response("/dav/photos/sub/deep/") + response("/dav/photos/sub/")), paths, "/photos")
        assertEquals(listOf("/photos/sub"), result.map { it.path })
        assertTrue(result.single().isDirectory)
    }

    @Test fun deleteMultistatusMustConfirmSuccessForTarget() {
        fun response(path: String, code: Int) = "<d:response><d:href>$path</d:href><d:status>HTTP/1.1 $code Result</d:status></d:response>"
        verifyDeleteMultistatus(xml(response("/dav/photos/a+b.jpg", 204)), paths, "/photos/a+b.jpg")
        listOf(xml(response("/dav/photos/a+b.jpg", 403)), xml(response("/dav/photos/other.jpg", 204)), xml("")).forEach {
            assertThrows(java.io.IOException::class.java) { verifyDeleteMultistatus(it, paths, "/photos/a+b.jpg") }
        }
    }

    @Test fun rejectsDoctype() {
        assertThrows(java.io.IOException::class.java) {
            parseDavXml("<!DOCTYPE x [<!ENTITY leak SYSTEM 'file:///secret'>]>" + xml(""))
        }
    }

    @Test fun selectionAfterDeletionUsesIdentity() {
        val files = listOf(file("/photos/a.jpg"), file("/photos/b.jpg"), file("/photos/c.jpg"))
        assertEquals("/photos/b.jpg", nextImageAfterDelete(files, "/photos/a.jpg", "/photos/a.jpg"))
        assertEquals("/photos/b.jpg", nextImageAfterDelete(files, "/photos/c.jpg", "/photos/c.jpg"))
        assertEquals("/photos/c.jpg", nextImageAfterDelete(files, "/photos/c.jpg", "/photos/a.jpg"))
        assertNull(nextImageAfterDelete(listOf(files.first()), "/photos/a.jpg", "/photos/a.jpg"))
    }

    @Test fun cacheKeysCannotCollideOnSeparatorsAndChangeWithVersion() {
        val a = file("/photos/a.jpg")
        assertNotEquals(imageCacheKey("server", a), imageCacheKey("server", a.copy(etag = "\"v2\"")))
        assertNotEquals(imageCacheKey("server", a), imageCacheKey("other", a))
        assertNotEquals(imageCacheKey("x:y", a.copy(path = "/z")), imageCacheKey("x", a.copy(path = "y:/z")))
    }
}
