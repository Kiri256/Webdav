package com.example.webdavmanager

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

class MpvWebDavProxyTest {
    @Test fun forwardsRangeAndKeepsWebDavAuthorizationBehindLoopbackProxy() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setBody("cde")
                .setHeader("Content-Range", "bytes 2-4/6")
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Type", "video/mp4"))
            val client = WebDavClient(WebDavConnection(
                id = "test", name = "test", baseUrl = server.url("/dav/").toString(),
                username = "user", password = "pass", rootPath = "/files",
            ))
            val file = FileEntry("clip.mp4", "/files/clip.mp4", false, 6, null, null, "video/mp4")
            MpvWebDavProxy(client, file).use { proxy ->
                val request = URL(proxy.uri).openConnection() as HttpURLConnection
                request.setRequestProperty("Range", "bytes=2-4")
                assertEquals(206, request.responseCode)
                assertEquals("bytes 2-4/6", request.getHeaderField("Content-Range"))
                assertEquals("cde", request.inputStream.bufferedReader().use { it.readText() })

                val upstream = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("GET", upstream.method)
                assertEquals("bytes=2-4", upstream.getHeader("Range"))
                assertTrue("Upstream authentication missing", upstream.getHeader("Authorization") == "Basic dXNlcjpwYXNz")
                assertTrue(proxy.uri.startsWith("http://127.0.0.1:"))
            }
        }
    }

    private fun client(server: MockWebServer) = WebDavClient(WebDavConnection(
        "proxy", "proxy", server.url("/dav/").toString(), "", "", "/files"))
    private val file = FileEntry("clip.mp4", "/files/clip.mp4", false, 6, null, null, "video/mp4")

    @Test fun headPreservesUpstreamLengthWithoutReadingBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Length", "12345"))
            MpvWebDavProxy(client(server), file).use { proxy ->
                val request = URL(proxy.uri).openConnection() as HttpURLConnection
                request.requestMethod = "HEAD"
                request.readTimeout = 3000
                try {
                    assertEquals(200, request.responseCode)
                    assertEquals(12345L, request.contentLengthLong)
                    assertEquals(-1, request.inputStream.read())
                    assertEquals("HEAD", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
                } finally { request.disconnect() }
            }
        }
    }

    @Test fun closeCancelsUpstreamAndClosesIncompleteAndQueuedLocalRequests() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = client(server)
            val proxy = MpvWebDavProxy(client, file)
            val uri = URI(proxy.uri)
            val sockets = (0 until 10).map { Socket(uri.host, uri.port).apply { soTimeout = 3000 } }
            try {
                sockets.first().getOutputStream().apply {
                    write("GET ${uri.rawPath} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray()); flush()
                }
                assertTrue(server.takeRequest(3, TimeUnit.SECONDS) != null)
                sockets.drop(1).forEach { it.getOutputStream().apply { write("GET ".toByteArray()); flush() } }
                proxy.close()
                proxy.close()
                sockets.forEach { socket ->
                    try { assertEquals(-1, socket.getInputStream().read()) }
                    catch (_: java.net.SocketException) { /* A reset also closes the connection. */ }
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (client.http.dispatcher.runningCallsCount() > 0 && System.nanoTime() < deadline) Thread.yield()
                assertEquals(0, client.http.dispatcher.runningCallsCount())
            } finally { proxy.close(); sockets.forEach { it.close() } }
        }
    }

    @Test fun truncatedBodyDoesNotAppendAnotherHttpResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdefghij").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            MpvWebDavProxy(client(server), file).use { proxy ->
                val uri = URI(proxy.uri)
                Socket(uri.host, uri.port).use { socket ->
                    socket.soTimeout = 3000
                    socket.getOutputStream().apply {
                        write("GET ${uri.rawPath} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray()); flush()
                    }
                    val response = socket.getInputStream().bufferedReader().readText()
                    assertTrue(response.startsWith("HTTP/1.1 200"))
                    assertEquals(1, Regex("HTTP/1.1").findAll(response).count())
                    assertTrue(!response.contains("WebDAV request failed"))
                }
            }
        }
    }
}
