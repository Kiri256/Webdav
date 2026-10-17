package com.example.webdavmanager

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import okhttp3.Call
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class MpvWebDavProxy(client: WebDavClient, file: FileEntry) : Closeable {
    private val token = UUID.randomUUID().toString()
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8))
    private val closed = AtomicBoolean(false)
    private val resourceLock = Any()
    private val sockets = mutableSetOf<Socket>()
    private val calls = mutableSetOf<Call>()
    val uri = "http://127.0.0.1:${server.localPort}/$token/media"
    private val acceptThread = Thread {
        while (!closed.get()) {
            try {
                val socket = server.accept()
                synchronized(resourceLock) {
                    if (closed.get()) socket.close()
                    else {
                        sockets.add(socket)
                        try { workers.execute {
                            try { serve(socket, client, file) }
                            catch (_: IOException) { /* Peer disconnected or proxy closed. */ }
                            finally {
                                runCatching { socket.close() }
                                synchronized(resourceLock) { sockets.remove(socket) }
                            }
                        } } catch (_: RejectedExecutionException) {
                            sockets.remove(socket)
                            socket.close()
                        }
                    }
                }
            } catch (_: IOException) {
                if (!closed.get()) close()
            }
        }
    }.apply { name = "mpv-webdav-proxy"; isDaemon = true; start() }

    private fun serve(socket: Socket, client: WebDavClient, file: FileEntry) {
        socket.use { connection ->
            connection.soTimeout = 30_000
            val input = BufferedInputStream(connection.getInputStream())
            val output = BufferedOutputStream(connection.getOutputStream())
            val requestLine = input.readAsciiLine(4096) ?: return
            val requestParts = requestLine.split(' ', limit = 3)
            if (requestParts.size != 3 || requestParts[2] != "HTTP/1.1") return
            val method = requestParts[0]
            if (method != "GET" && method != "HEAD") return
            if (requestParts[1] != "/$token/media") {
                output.writeResponse(404, "Not Found", "Not found")
                return
            }
            val headers = linkedMapOf<String, String>()
            var headerBytes = 0
            while (true) {
                val line = input.readAsciiLine(8192) ?: return
                headerBytes += line.length + 2
                if (headerBytes > 32 * 1024) throw IOException("Local media headers are too long")
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator <= 0) continue
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
            val range = headers["range"]
            if (range != null && !range.matches(Regex("bytes=\\d*-\\d*(,\\d*-\\d*)*"))) {
                output.writeResponse(416, "Range Not Satisfiable", "Invalid range")
                return
            }
            val request = client.request(file.path).header("Accept-Encoding", "identity").apply {
                if (range != null) header("Range", range)
                headers["if-range"]?.let { header("If-Range", it) }
            }
            if (method == "HEAD") request.head() else request.get()
            val call = client.http.newCall(request.build())
            synchronized(resourceLock) {
                if (closed.get()) return
                calls.add(call)
            }
            var committed = false
            try {
                call.execute().use { response ->
                    val status = response.code
                    val reason = when (status) {
                        200 -> "OK"
                        206 -> "Partial Content"
                        401 -> "Unauthorized"
                        403 -> "Forbidden"
                        404 -> "Not Found"
                        416 -> "Range Not Satisfiable"
                        else -> "Upstream Error"
                    }
                    val body = response.body
                    val contentLength = if (method == "HEAD") response.header("Content-Length")?.toLongOrNull() ?: -1L
                        else body?.contentLength() ?: 0L
                    committed = true
                    output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.write("Connection: close\r\n".toByteArray(StandardCharsets.US_ASCII))
                    response.header("Accept-Ranges")?.let { output.write("Accept-Ranges: $it\r\n".toByteArray(StandardCharsets.US_ASCII)) }
                    response.header("Content-Type")?.let { output.write("Content-Type: $it\r\n".toByteArray(StandardCharsets.ISO_8859_1)) }
                    response.header("Content-Range")?.let { output.write("Content-Range: $it\r\n".toByteArray(StandardCharsets.US_ASCII)) }
                    if (contentLength >= 0) output.write("Content-Length: $contentLength\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                    if (method != "HEAD") body?.byteStream()?.use { it.copyTo(output, 64 * 1024) }
                    output.flush()
                }
            } catch (_: IOException) {
                if (!committed && !closed.get()) runCatching { output.writeResponse(502, "Bad Gateway", "WebDAV request failed") }
            } finally {
                call.cancel()
                synchronized(resourceLock) { calls.remove(call) }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { server.close() }
            synchronized(resourceLock) {
                calls.forEach { it.cancel() }
                sockets.forEach { runCatching { it.close() } }
                calls.clear()
                sockets.clear()
            }
            workers.shutdownNow()
        }
    }

    private fun BufferedInputStream.readAsciiLine(limit: Int): String? {
        val bytes = ArrayList<Byte>(minOf(limit, 128))
        while (bytes.size < limit) {
            val next = read()
            if (next < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), StandardCharsets.US_ASCII)
            if (next == '\n'.code) break
            if (next != '\r'.code) bytes += next.toByte()
        }
        if (bytes.size >= limit) throw IOException("Local media request header is too long")
        return String(bytes.toByteArray(), StandardCharsets.US_ASCII)
    }

    private fun BufferedOutputStream.writeResponse(status: Int, reason: String, message: String) {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        write("HTTP/1.1 $status $reason\r\nConnection: close\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        write(body)
        flush()
    }
}
