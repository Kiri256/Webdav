package com.example.webdavmanager

import android.content.Context
import android.net.Uri
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume

internal class WebDavClient(val connection: WebDavConnection) {
    val paths = DavPaths(connection.baseUrl, connection.rootPath)
    internal val http = OkHttpClient.Builder().followRedirects(false).build()

    fun fileUri(path: String): Uri = Uri.parse(paths.url(path).toString())
    fun headers(): Map<String, String> = if (connection.username.isEmpty() && connection.password.isEmpty()) emptyMap()
        else mapOf("Authorization" to Credentials.basic(connection.username, connection.password))

    fun request(path: String): Request.Builder = Request.Builder().url(paths.url(path)).apply {
        headers().forEach { (name, value) -> header(name, value) }
    }

    fun imageRequest(context: Context, file: FileEntry): ImageRequest = ImageRequest.Builder(context)
        .data(fileUri(file.path).toString())
        .apply { this@WebDavClient.headers().forEach { (name, value) -> setHeader(name, value) } }
        .diskCacheKey("full:${imageCacheKey(connection.id, file)}")
        .memoryCacheKey("full:${imageCacheKey(connection.id, file)}")
        .crossfade(true)
        .build()

    suspend fun list(path: String): List<FileEntry> = properties(path, "1", false, true)

    suspend fun stat(path: String, directory: Boolean = false): FileEntry = properties(path, "0", true, directory).firstOrNull { it.path == normalizePath(path) }
        ?: throw IOException("Server did not confirm the requested resource")

    /** Some servers reject Depth=0; only those protocol responses permit a live parent-list fallback. */
    suspend fun inspect(file: FileEntry): FileEntry = inspect(file.path, file.isDirectory)

    suspend fun inspect(path: String, directory: Boolean = false): FileEntry = try { stat(path, directory) }
        catch (error: DavStatusException) {
            if (error.status !in setOf(400, 405, 501)) throw error
            list(parentPath(path)).firstOrNull { it.path == normalizePath(path) }
                ?: throw IOException("File no longer exists")
        }

    private suspend fun properties(path: String, depth: String, includeRequested: Boolean, directory: Boolean): List<FileEntry> = withContext(Dispatchers.IO) {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop>
              <d:displayname/><d:getcontentlength/><d:getlastmodified/>
              <d:getetag/><d:getcontenttype/><d:resourcetype/>
            </d:prop></d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = request(path).url(paths.url(path, directory = directory))
            .method("PROPFIND", body).header("Depth", depth).build()
        val callerContext = kotlin.coroutines.coroutineContext
        http.newCall(request).awaitBody { response ->
            requireSuccess(response, "PROPFIND")
            val bodyStream = response.body?.byteStream() ?: throw IOException("Empty WebDAV response")
            val bounded = LimitedInputStream(bodyStream, 8L * 1024 * 1024)
            parseListing(bounded.reader(Charsets.UTF_8), paths, path, includeRequested) { callerContext.ensureActive() }
        }
    }

    suspend fun delete(path: String, etag: String? = null) = withContext(Dispatchers.IO) {
        val builder = request(path).delete()
        etag?.takeUnless { it.startsWith("W/") }?.let { builder.header("If-Match", it) }
        http.newCall(builder.build()).awaitBody { response ->
            requireSuccess(response, "DELETE")
            if (response.code == 207) verifyDeleteMultistatus(response.xmlBody(), paths, path)
            else if (response.code !in setOf(200, 204)) throw IOException("DELETE not completed: HTTP ${response.code}")
        }
    }

    suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        http.newCall(request(path).url(paths.url(path, directory = true)).method("MKCOL", null)
            .header("If-None-Match", "*").build()).awaitBody { response ->
            requireSuccess(response, "Create folder")
            if (response.code != 201) throw IOException("Folder creation not confirmed")
        }
    }

    suspend fun upload(path: String, data: File, mime: String?, onProgress: (Long) -> Unit = {}): String? = withContext(Dispatchers.IO) {
        val type = runCatching { (mime ?: "application/octet-stream").toMediaType() }.getOrDefault("application/octet-stream".toMediaType())
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = type
            override fun contentLength() = data.length()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) {
                data.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    var reported = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        sent += count
                        if (sent - reported >= 512L * 1024) {
                            onProgress(sent)
                            reported = sent
                        }
                    }
                    if (sent != reported) onProgress(sent)
                }
            }
        }
        http.newCall(request(path).header("If-None-Match", "*").put(body).build()).awaitBody { response ->
            requireSuccess(response, "Upload")
            if (response.code != 201) throw IOException("Upload creation not confirmed; check destination before retrying")
            response.header("ETag")?.takeUnless { it.startsWith("W/") }
        }
    }

    suspend fun deleteVerified(file: FileEntry) {
        if (file.isDirectory) throw IOException("Source folder retained: WebDAV cannot atomically guarantee empty-folder deletion")
        val tag = file.etag?.takeUnless { it.startsWith("W/") }
            ?: throw IOException("Source retained: server provides no strong version for conditional deletion")
        delete(file.path, tag)
    }

    suspend fun download(file: FileEntry, target: File, limit: Long, onProgress: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        try { target.outputStream().use { readFile(file, it, limit, target.parentFile, onProgress) } }
        catch (error: Exception) { target.delete(); throw error }
    }

    suspend fun downloadSubtitle(file: FileEntry, target: File) {
        val current = inspect(file)
        requireSameVersion(file, current)
        download(current, target, SUBTITLE_LIMIT)
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    suspend fun readFile(file: FileEntry, output: java.io.OutputStream, limit: Long,
        storage: File? = null, onProgress: (Long) -> Unit = {}): StreamDigest = withContext(Dispatchers.IO) {
        require(!file.isDirectory)
        val builder = request(file.path).header("Accept-Encoding", "identity")
        file.etag?.takeUnless { it.startsWith("W/") }?.let { builder.header("If-Match", it) }
        val call = http.newCall(builder.build())
        val cancellation = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.awaitResponse().use { response ->
                requireSuccess(response, "Download")
                if (response.code != 200) throw IOException("Incomplete file response")
                val body = response.body ?: throw IOException("Empty file response")
                if (body.contentLength() > limit || (file.size ?: 0) > limit) throw IOException("File exceeds transfer capacity")
                val result = body.byteStream().use { input -> copyBounded(input, output, limit, storage, onProgress) }
                if (file.size != null && result.bytes != file.size) throw IOException("Source size changed")
                result
            }
        } catch (error: Exception) { kotlin.coroutines.coroutineContext.ensureActive(); throw error }
        finally { cancellation?.dispose() }
    }

    suspend fun transferChecked(file: FileEntry, destination: String, move: Boolean) {
        paths.validateTransfer(file.path, destination, file.isDirectory)
        val current = inspect(file)
        requireSameVersion(file, current)
        transfer(current, destination, move)
    }

    suspend fun transfer(file: FileEntry, destination: String, move: Boolean) = withContext(Dispatchers.IO) {
        paths.validateTransfer(file.path, destination, file.isDirectory)
        val operation = if (move) "MOVE" else "COPY"
        val builder = request(file.path).url(paths.url(file.path, file.isDirectory))
            .method(operation, null).header("Destination", paths.url(destination, file.isDirectory).toString())
            .header("Overwrite", "F").header("Depth", "infinity")
        file.etag?.takeUnless { it.startsWith("W/") }?.let { builder.header("If-Match", it) }
        http.newCall(builder.build()).awaitBody { response ->
            if (response.code == 412) throw IOException("Destination already exists or source changed; nothing was overwritten")
            requireSuccess(response, operation)
            if (response.code == 207) verifyTransferMultistatus(response.xmlBody(), paths, file.path, destination)
            else if (response.code !in setOf(201, 204)) throw IOException("$operation not confirmed; check source and destination")
        }
    }

    suspend fun supportsRange(file: FileEntry): Boolean = withContext(Dispatchers.IO) {
        http.newCall(request(file.path).header("Range", "bytes=0-0").header("Accept-Encoding", "identity").build())
            .awaitBody { response ->
                requireSuccess(response, "Video")
                response.code == 206 && response.header("Content-Range")?.startsWith("bytes 0-0/") == true
            }
    }

    suspend fun readText(path: String): RemoteText = withContext(Dispatchers.IO) {
        http.newCall(request(path).build()).awaitBody { response ->
            requireSuccess(response, "Read text")
            if (response.code != 200) throw IOException("Incomplete text response")
            val source = response.body?.source() ?: throw IOException("Empty text response")
            if (source.request(TEXT_LIMIT + 1L)) throw IOException("Text editor supports files up to 64 KiB")
            val bytes = source.readByteArray()
            val bom = bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()
            val content = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, if (bom) 3 else 0, bytes.size - if (bom) 3 else 0)).toString()
            } catch (_: Exception) { throw IOException("Only UTF-8 text is supported") }
            if ('\u0000' in content) throw IOException("This file is not UTF-8 text")
            RemoteText(content, response.header("ETag")?.takeUnless { it.startsWith("W/") },
                response.header("Last-Modified"), bom)
        }
    }

    suspend fun saveText(path: String, content: String, original: RemoteText) = withContext(Dispatchers.IO) {
        val bytes = ((if (original.bom) "\uFEFF" else "") + content).toByteArray(Charsets.UTF_8)
        if (bytes.size > TEXT_LIMIT) throw IOException("Text editor supports files up to 64 KiB")
        val builder = request(path)
        when {
            original.etag != null -> builder.header("If-Match", original.etag)
            original.modified != null -> builder.header("If-Unmodified-Since", original.modified)
            else -> throw IOException("Server provides no version validator; safe saving is unavailable")
        }
        http.newCall(builder.put(bytes.toRequestBody("text/plain; charset=utf-8".toMediaType())).build()).awaitBody { response ->
            if (response.code == 412) throw IOException("Remote file changed. Keep your draft or reload before saving.")
            requireSuccess(response, "Save text")
            if (response.code !in setOf(200, 201, 204)) throw IOException("Save not completed: HTTP ${response.code}")
            RemoteText(content, response.header("ETag")?.takeUnless { it.startsWith("W/") }, response.header("Last-Modified"), original.bom)
        }
    }

    private fun requireSuccess(response: Response, operation: String) {
        if (!response.isSuccessful) {
            val message = when (response.code) {
                401 -> "Incorrect username or password"
                403 -> "Permission denied"
                in 300..399 -> "Server redirected the request; use its final WebDAV URL"
                else -> "$operation failed: HTTP ${response.code}"
            }
            throw DavStatusException(response.code, message)
        }
    }

    private fun Response.xmlBody(): String {
        val source = body?.source() ?: throw IOException("Empty WebDAV response")
        val limit = 8L * 1024 * 1024
        if (source.request(limit + 1)) throw IOException("WebDAV response exceeds 8 MiB")
        return source.readUtf8()
    }
}

internal const val TEXT_LIMIT = 64 * 1024
internal class DavStatusException(val status: Int, message: String) : IOException(message)
internal class LimitedInputStream(input: java.io.InputStream, private val limit: Long) : java.io.FilterInputStream(input) {
    private var received = 0L
    private fun count(value: Int): Int {
        if (value > 0) received += value
        if (received > limit) throw IOException("WebDAV response exceeds 8 MiB")
        return value
    }
    override fun read(): Int = super.read().also { if (it >= 0) count(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = count(`in`.read(buffer, offset, length))
}
internal data class RemoteText(val content: String, val etag: String?, val modified: String?, val bom: Boolean = false)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { response.close() }
        }
    })
}

private suspend fun <T> Call.awaitBody(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use(read)
                if (!continuation.isCancelled) continuation.resume(value)
            } catch (e: Exception) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        }
    })
}
