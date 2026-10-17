package com.example.webdavmanager

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.io.Reader
import java.net.URI
import java.util.Locale

data class WebDavConnection(
    val id: String,
    val name: String,
    val baseUrl: String,
    val username: String,
    val password: String,
    val rootPath: String,
)

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long?,
    val lastModified: String?,
    val etag: String?,
    val mimeType: String?,
    val displayName: String = name,
) {
    fun isImage(): Boolean = !isDirectory && (mimeType.orEmpty().startsWith("image/") ||
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
        setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"))

    fun isVideo(): Boolean = !isDirectory && (mimeType.orEmpty().startsWith("video/") ||
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
        setOf("mp4", "webm", "mkv", "mov", "m4v", "avi"))

    fun isAudio(): Boolean = !isDirectory && (mimeType.orEmpty().startsWith("audio/") ||
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
        setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac"))

    fun isText(): Boolean = !isDirectory && (mimeType.orEmpty().startsWith("text/") ||
        mimeType?.substringBefore(';') in setOf("application/json", "application/xml", "application/javascript") ||
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
        setOf("txt", "md", "csv", "log", "json", "xml", "yaml", "yml", "ini", "conf", "html", "css", "js", "kt", "java", "py", "sh"))
}

internal fun normalizePath(path: String): String {
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    require(parts.none { it == "." || it == ".." || '\\' in it || it.any { c -> c.code < 32 } }) {
        "Invalid file path"
    }
    return "/" + parts.joinToString("/")
}

internal fun parentPath(path: String): String =
    normalizePath(path).substringBeforeLast('/', "").ifEmpty { "/" }

internal class DavPaths(address: String, root: String = "/") {
    val base: HttpUrl
    val rootPath = normalizePath(root)
    private val baseSegments: List<String>

    init {
        require(address == address.trim() && address.none { it.isWhitespace() }) { "Invalid server URL" }
        val parsed = address.toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid HTTPS server URL")
        require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null) {
            "Use the username and password fields; URL query and fragment are not supported"
        }
        require(parsed.scheme == "https" || parsed.scheme == "http") { "Only HTTP and HTTPS are supported" }
        base = parsed.newBuilder().encodedPath(parsed.encodedPath.trimEnd('/') + "/").build()
        baseSegments = base.pathSegments.dropLast(1)
        require(baseSegments.none { '/' in it || '\\' in it || it == "." || it == ".." }) { "Invalid server path" }
    }

    fun contains(path: String): Boolean {
        val normalized = normalizePath(path)
        return rootPath == "/" || normalized == rootPath || normalized.startsWith("$rootPath/")
    }

    fun url(path: String, directory: Boolean = false): HttpUrl {
        val normalized = normalizePath(path)
        require(contains(normalized)) { "Path is outside the configured root" }
        val builder = base.newBuilder()
        normalized.trim('/').split('/').filter { it.isNotEmpty() }.forEach(builder::addPathSegment)
        if (directory && normalized != "/") builder.addPathSegment("")
        return builder.build()
    }

    fun child(directory: String, name: String): String {
        validateLocalName(name)
        val child = normalizePath(directory).trimEnd('/') + "/" + name
        url(child)
        return child
    }

    fun validateTransfer(source: String, destination: String, directory: Boolean) {
        url(source)
        url(destination)
        val from = normalizePath(source)
        val to = normalizePath(destination)
        require(from != rootPath) { "Cannot transfer the configured root directory" }
        require(from != to && (!directory || !to.startsWith("$from/"))) { "Cannot transfer a folder into itself" }
    }

    fun relative(href: String, requested: String): String {
        require(href.isNotBlank()) { "Missing WebDAV href" }
        // Reject traversal before HttpUrl normalizes dot segments.
        val raw = URI(href)
        val rawPath = raw.rawPath ?: throw IllegalArgumentException("Missing href path")
        val probe = ("https://path.invalid/" + rawPath.trimStart('/')).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid href")
        require(rawPath.split('/').none {
            it.replace("%2e", ".", ignoreCase = true) in setOf(".", "..")
        }) { "Traversal in href" }
        require(probe.pathSegments.none { '/' in it || '\\' in it }) { "Encoded separator in href" }
        val resolved = url(requested, directory = true).resolve(href)
            ?: throw IllegalArgumentException("Invalid href")
        require(resolved.scheme == base.scheme && resolved.host == base.host && resolved.port == base.port &&
            resolved.username == base.username && resolved.password == base.password &&
            resolved.query == null && resolved.fragment == null) { "Foreign WebDAV href" }
        val segments = resolved.pathSegments.let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        require(segments.take(baseSegments.size) == baseSegments) { "Href is outside server directory" }
        val path = normalizePath("/" + segments.drop(baseSegments.size).joinToString("/"))
        require(contains(path)) { "Href is outside configured root" }
        return path
    }
}

internal data class DavElement(
    val name: String,
    val namespace: String,
    val children: MutableList<DavElement> = mutableListOf(),
    val text: StringBuilder = StringBuilder(),
) {
    fun children(name: String) = children.filter { it.name == name && it.namespace == "DAV:" }
    fun child(name: String) = children(name).firstOrNull()
    fun value(name: String) = child(name)?.text?.toString()
}

internal fun parseDavXml(xml: String): DavElement {
    require(xml.length <= 8 * 1024 * 1024) { "WebDAV response is too large" }
    val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
    parser.setInput(StringReader(xml))
    val stack = ArrayDeque<DavElement>()
    var root: DavElement? = null
    while (true) {
        when (parser.nextToken()) {
            XmlPullParser.START_TAG -> {
                require(stack.size < 32) { "WebDAV XML is too deeply nested" }
                val node = DavElement(parser.name, parser.namespace.orEmpty())
                stack.lastOrNull()?.children?.add(node)
                if (root == null) root = node
                stack.addLast(node)
            }
            XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.IGNORABLE_WHITESPACE ->
                stack.lastOrNull()?.text?.append(parser.text)
            XmlPullParser.ENTITY_REF -> {
                if (parser.name !in setOf("amp", "lt", "gt", "apos", "quot") && !parser.name.startsWith("#")) {
                    throw IOException("Unsupported XML entity")
                }
                stack.lastOrNull()?.text?.append(parser.text ?: throw IOException("Unresolved XML entity"))
            }
            XmlPullParser.DOCDECL -> throw IOException("Unsupported XML declaration")
            XmlPullParser.END_TAG -> stack.removeLast()
            XmlPullParser.END_DOCUMENT -> break
        }
    }
    return root?.takeIf { it.name == "multistatus" && it.namespace == "DAV:" }
        ?: throw IOException("Expected a WebDAV multistatus response")
}

internal fun statusCode(status: String?): Int? = status?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()

internal fun parseListing(xml: String, paths: DavPaths, requested: String, includeRequested: Boolean = false): List<FileEntry> {
    require(xml.length <= 8 * 1024 * 1024) { "WebDAV response is too large" }
    return parseListing(StringReader(xml), paths, requested, includeRequested)
}

/** Retains at most one response subtree; DELETE/COPY/MOVE keep their full validation parser. */
internal fun parseListing(reader: Reader, paths: DavPaths, requested: String, includeRequested: Boolean = false,
    checkCancelled: () -> Unit = {}): List<FileEntry> {
    val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
    parser.setInput(reader)
    val stack = ArrayDeque<DavElement>()
    val result = mutableListOf<FileEntry>()
    val seen = hashSetOf<String>()
    var depth = 0
    var rootSeen = false
    while (true) {
        checkCancelled()
        when (parser.nextToken()) {
            XmlPullParser.START_TAG -> {
                require(depth < 32) { "WebDAV XML is too deeply nested" }
                depth++
                if (depth == 1) {
                    if (rootSeen || parser.name != "multistatus" || parser.namespace != "DAV:")
                        throw IOException("Expected a WebDAV multistatus response")
                    rootSeen = true
                }
                if (stack.isNotEmpty() || (depth == 2 && parser.name == "response" && parser.namespace == "DAV:")) {
                    val node = DavElement(parser.name, parser.namespace.orEmpty())
                    stack.lastOrNull()?.children?.add(node)
                    stack.addLast(node)
                }
            }
            XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.IGNORABLE_WHITESPACE -> stack.lastOrNull()?.text?.append(parser.text)
            XmlPullParser.ENTITY_REF -> {
                if (parser.name !in setOf("amp", "lt", "gt", "apos", "quot") && !parser.name.startsWith("#"))
                    throw IOException("Unsupported XML entity")
                stack.lastOrNull()?.text?.append(parser.text ?: throw IOException("Unresolved XML entity"))
            }
            XmlPullParser.DOCDECL -> throw IOException("Unsupported XML declaration")
            XmlPullParser.END_TAG -> {
                if (stack.isNotEmpty()) {
                    val node = stack.removeLast()
                    if (depth == 2) listingEntry(node, paths, requested, includeRequested)?.let {
                        if (seen.add(it.path)) result.add(it)
                    }
                }
                depth--
            }
            XmlPullParser.END_DOCUMENT -> break
        }
    }
    if (!rootSeen || depth != 0) throw IOException("Incomplete WebDAV response")
    return sortFiles(result, FileSortMode.NAME, checkCancelled)
}

private fun listingEntry(response: DavElement, paths: DavPaths, requested: String, includeRequested: Boolean): FileEntry? {
        val responseStatus = statusCode(response.value("status"))
        if (responseStatus != null && responseStatus !in 200..299) return null
        val path = paths.relative(response.value("href")?.trim().orEmpty(), requested)
        if (path == normalizePath(requested)) {
            if (!includeRequested) return null
        } else if (parentPath(path) != normalizePath(requested)) return null
        val properties = response.children("propstat")
            .filter { statusCode(it.value("status")) in 200..299 }
            .mapNotNull { it.child("prop") }
        if (properties.isEmpty()) return null
        fun value(name: String) = properties.firstNotNullOfOrNull { it.value(name) }
        return FileEntry(
            name = path.substringAfterLast('/'),
            path = path,
            isDirectory = properties.any { it.child("resourcetype")?.child("collection") != null },
            size = value("getcontentlength")?.trim()?.toLongOrNull(),
            lastModified = value("getlastmodified")?.trim(),
            etag = value("getetag")?.trim(),
            mimeType = value("getcontenttype")?.trim(),
            displayName = value("displayname")?.takeIf { it.isNotEmpty() } ?: path.substringAfterLast('/'),
        )
}

internal fun verifyDeleteMultistatus(xml: String, paths: DavPaths, requested: String) {
    val responses = parseDavXml(xml).children("response")
    if (responses.isEmpty()) throw IOException("Empty DELETE multistatus")
    var matched = false
    responses.forEach { response ->
        val path = paths.relative(response.value("href")?.trim().orEmpty(), parentPath(requested))
        if (path == requested) matched = true
        val statuses = listOfNotNull(statusCode(response.value("status"))) +
            response.children("propstat").mapNotNull { statusCode(it.value("status")) }
        if (statuses.isEmpty() || statuses.any { it !in 200..299 }) {
            throw IOException("DELETE failed for $path: ${statuses.joinToString()}")
        }
    }
    if (!matched) throw IOException("DELETE did not confirm the requested file")
}

internal fun verifyTransferMultistatus(xml: String, paths: DavPaths, source: String, destination: String) {
    val responses = parseDavXml(xml).children("response")
    if (responses.isEmpty()) throw IOException("Empty transfer multistatus; check source and destination")
    responses.forEach { response ->
        val path = paths.relative(response.value("href")?.trim().orEmpty(), parentPath(source))
        if (path != source && path != destination && !path.startsWith("$source/") && !path.startsWith("$destination/"))
            throw IOException("Transfer response refers to an unrelated file")
        val statuses = listOfNotNull(statusCode(response.value("status"))) +
            response.children("propstat").mapNotNull { statusCode(it.value("status")) }
        if (statuses.isEmpty() || statuses.any { it !in 200..299 })
            throw IOException("Transfer partially failed; check source and destination")
    }
    // RFC 4918 uses 207 for incomplete COPY/MOVE. A child status alone cannot confirm the whole operation.
    throw IOException("Transfer returned multi-status; check source and destination before retrying")
}

internal fun imageCacheKey(serverId: String, file: FileEntry): String =
    listOf(serverId, file.path, file.etag.orEmpty(), file.lastModified.orEmpty(), file.size?.toString().orEmpty())
        .joinToString("") { "${it.length}:$it" }

internal fun nextImageAfterDelete(files: List<FileEntry>, selected: String?, deleted: String): String? {
    if (selected != deleted) return selected
    val images = files.filter { it.isImage() }
    val index = images.indexOfFirst { it.path == deleted }
    val remaining = images.filterNot { it.path == deleted }
    return remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))?.path
}
