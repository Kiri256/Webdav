package com.example.webdavmanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

internal const val SUBTITLE_LIMIT = 20L * 1024 * 1024
internal val subtitleExtensions = setOf("srt", "ass", "ssa", "vtt", "sub", "sup")
private data class LoadedSubtitle(val name: String, val version: String, val persistent: Boolean)

internal suspend fun <T : Any> awaitSubtitleResult(timeoutMillis: Long, block: suspend CoroutineScope.() -> T): T =
    withTimeoutOrNull(timeoutMillis, block) ?: throw IOException("Subtitle track did not become ready in time")

internal fun subtitleCandidates(video: FileEntry, files: List<FileEntry>): List<FileEntry> {
    val stem = video.name.substringBeforeLast('.').lowercase(java.util.Locale.ROOT)
    return files.filter {
        val name = it.name.lowercase(java.util.Locale.ROOT)
        val base = name.substringBeforeLast('.')
        !it.isDirectory && name.substringAfterLast('.') in subtitleExtensions &&
            (base == stem || base.startsWith("$stem."))
    }.sortedWith(compareByDescending<FileEntry> { preferredChineseSubtitle(it.name) }.thenBy { it.name })
}

internal fun preferredChineseSubtitle(name: String): Boolean =
    Regex("(^|[._ -])(zh([_-](cn|hans))?|chs|简体)([._ -]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name)

/** Owned by the ViewModel; only the bound playback session may receive a completed load. */
internal class SubtitleController(private val context: Context, private val scope: CoroutineScope) {
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var currentName by mutableStateOf("Embedded / none"); private set
    var encoding by mutableStateOf("auto"); private set
    var offset by mutableStateOf(0.0); private set
    var scale by mutableStateOf(1.0); private set
    var autoLoad by mutableStateOf(true); private set
    var candidates by mutableStateOf<List<FileEntry>>(emptyList()); private set
    private var session: MpvPlaybackSession? = null
    private var storage: LocalStorage? = null
    private var dav: WebDavClient? = null
    private var key = ""
    private var token = 0L
    private var job: Job? = null
    private var restoreSelection: (() -> Unit)? = null
    private var selected: String? = null
    private var selectionPersistent = true
    private var lastAttempt: (() -> Unit)? = null
    private var loadedEncoding = "auto"
    private val temporary = mutableSetOf<File>()
    private val mounted = mutableMapOf<String, Pair<Int, File>>()
    private val prefs = context.getSharedPreferences("subtitle_choices", Context.MODE_PRIVATE)

    fun bind(next: MpvPlaybackSession, video: FileEntry, files: List<FileEntry>, local: LocalStorage?, client: WebDavClient?) {
        release(session)
        session = next; storage = local; dav = client
        key = imageCacheKey(local?.tree?.toString() ?: client!!.connection.id, video)
        candidates = subtitleCandidates(video, files)
        autoLoad = prefs.getBoolean("automatic", true)
        currentName = "Embedded / none"; encoding = "auto"; offset = 0.0; scale = 1.0; selected = null; selectionPersistent = true
        val saved = runCatching { JSONObject(prefs.getString(key, "{}")!!) }.getOrDefault(JSONObject())
        encoding = saved.optString("encoding", "auto").takeIf { it in listOf("auto", "UTF-8", "GB18030", "Big5") } ?: "auto"
        loadedEncoding = encoding
        offset = saved.optDouble("offset", 0.0).takeIf { it.isFinite() }?.coerceIn(-600.0, 600.0) ?: 0.0
        scale = saved.optDouble("scale", 1.0).takeIf { it.isFinite() }?.coerceIn(.5, 2.0) ?: 1.0
        next.player.setPropertyDouble("sub-delay", offset); next.player.setPropertyDouble("sub-scale", scale)
        val source = saved.optString("source")
        when {
            source == "off" -> selectTrack(null, "Off")
            source.startsWith("track:") -> source.substringAfter(':').toIntOrNull()?.let { selectTrack(it, saved.optString("name", "Embedded")) }
            source.startsWith("local:") -> loadLocal(Uri.parse(source.removePrefix("local:")), saved.optString("version"))
            source.isNotEmpty() -> candidates.firstOrNull { it.path == source && subtitleVersion(it) == saved.optString("version") }?.let(::load)
                ?: run { error = "Saved subtitle changed or is unavailable. Select it again." }
            autoLoad -> {
                val preferred = candidates.filter { preferredChineseSubtitle(it.name) }
                (candidates.singleOrNull() ?: preferred.singleOrNull())?.let(::load)
            }
        }
    }

    fun setAutomatic(value: Boolean) { autoLoad = value; prefs.edit().putBoolean("automatic", value).apply() }
    fun updateOffset(value: Double) { offset = value.coerceIn(-600.0, 600.0); session?.player?.setPropertyDouble("sub-delay", offset); rememberChoice() }
    fun updateScale(value: Double) { scale = value.coerceIn(.5, 2.0); session?.player?.setPropertyDouble("sub-scale", scale); rememberChoice() }
    fun updateEncoding(value: String) {
        if (!busy && value in listOf("auto", "UTF-8", "GB18030", "Big5")) {
            encoding = value; lastAttempt?.invoke() ?: rememberChoice()
        }
    }
    fun cancel() {
        restoreSelection?.invoke()
        restoreSelection = null
        token++; job?.cancel(); job = null; busy = false
    }
    fun retry() { lastAttempt?.invoke() }
    fun selectTrack(id: Int?, name: String) {
        cancel()
        session?.player?.let { player ->
            player.setPropertyString("sid", id?.toString() ?: "no")
            if (id != null) player.setPropertyBoolean("sub-visibility", true)
        }
        currentName = name
        selected = mounted.entries.firstOrNull { it.value.first == id }?.key ?: id?.let { "track:$it" } ?: "off"
        selectionPersistent = selected?.startsWith("local:") != true || context.contentResolver.persistedUriPermissions.any { "local:${it.uri}" == selected && it.isReadPermission }
        error = null
        if (selected?.startsWith("track:") == true || id == null) lastAttempt = null
        rememberChoice()
    }
    fun load(file: FileEntry) {
        lastAttempt = { load(file) }
        val local = storage
        val client = dav
        start(file.path, file.name) { target ->
            if (local != null) {
                val current = local.stat(file.path); requireSameVersion(file, current)
                local.exportFile(current, target, SUBTITLE_LIMIT)
            } else requireNotNull(client).downloadSubtitle(file, target)
            LoadedSubtitle(file.name, subtitleVersion(file), true)
        }
    }
    fun loadLocal(uri: Uri, expectedVersion: String? = null) {
        lastAttempt = { loadLocal(uri, expectedVersion) }
        start("local:$uri", "Local subtitle") { target ->
            var persist = false
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                persist = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            } catch (_: Exception) { }
            currentCoroutineContext().ensureActive()
            val displayName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Local subtitle"
            currentCoroutineContext().ensureActive()
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output ->
                copyBounded(input, output, SUBTITLE_LIMIT, target.parentFile)
            } } ?: error("Subtitle is no longer readable; choose it again")
            val digest = fileDigest(target)
            require(expectedVersion.isNullOrEmpty() || expectedVersion == digest) { "Saved subtitle changed; select it again" }
            LoadedSubtitle(displayName, digest, persist)
        }
    }
    private fun start(
        source: String,
        name: String,
        copy: suspend (File) -> LoadedSubtitle,
    ) {
        val bound = session ?: return
        if (bound.closed) return
        cancel(); val generation = token
        val previous = bound.player.getPropertyString("sid") ?: "no"
        restoreSelection = { if (session === bound && !bound.closed) bound.player.setPropertyString("sid", previous) }
        busy = true; error = null
        job = scope.launch {
            var pending: File? = null
            var newTrack: Int? = null
            try {
                mounted[source]?.takeIf { selected == source && encoding == loadedEncoding }?.let {
                    bound.player.setPropertyInt("sid", it.first); busy = false; return@launch
                }
                val folder = File(context.cacheDir, "subtitles").apply { mkdirs() }
                val ext = name.substringAfterLast('.', "").lowercase().takeIf { it in subtitleExtensions } ?: "srt"
                var target = File(folder, "${UUID.randomUUID()}.$ext"); pending = target
                val metadata = withContext(Dispatchers.IO) { val metadata = copy(target); require(target.length() > 0) { "Subtitle is empty" }; metadata }
                ensureActive()
                if (generation != token || session !== bound || bound.closed) return@launch
                val actualExtension = metadata.name.substringAfterLast('.', "").lowercase().takeIf { it in subtitleExtensions } ?: ext
                if (actualExtension != ext) {
                    val renamed = File(folder, "${UUID.randomUUID()}.$actualExtension")
                    if (!target.renameTo(renamed)) throw IOException("Could not prepare subtitle")
                    target = renamed; pending = renamed
                }
                bound.player.setPropertyString("sub-codepage", encoding)
                val oldIds = tracks(bound).filter { it["type"]?.asString() == "sub" }.mapNotNull { it["id"]?.asInt()?.toInt() }.toSet()
                bound.player.command("sub-add", target.path, "auto")
                newTrack = awaitSubtitleResult(5000) {
                    var id: Int? = null
                    while (id == null) {
                        ensureActive()
                        id = tracks(bound).firstOrNull { it["type"]?.asString() == "sub" && it["id"]?.asInt()?.toInt() !in oldIds }
                            ?.get("id")?.asInt()?.toInt()
                        if (id == null) delay(50)
                    }
                    requireNotNull(id)
                }
                ensureActive()
                if (generation != token || session !== bound || bound.closed) return@launch
                bound.player.setPropertyBoolean("sub-visibility", true)
                bound.player.setPropertyInt("sid", newTrack!!)
                awaitSubtitleResult(2000) {
                    while (tracks(bound).none { it["type"]?.asString() == "sub" && it["id"]?.asInt()?.toInt() == newTrack && it["selected"]?.asBoolean() == true }) {
                        ensureActive()
                        if (generation != token || session !== bound || bound.closed) throw CancellationException()
                        delay(50)
                    }
                    true
                }
                mounted.values.forEach { (id, old) -> bound.player.command("sub-remove", id.toString()); temporary.remove(old); old.delete() }
                mounted.clear()
                mounted[source] = newTrack!! to target; temporary += target; pending = null
                selected = source; currentName = metadata.name
                selectionPersistent = metadata.persistent
                if (metadata.persistent) rememberChoice(metadata.version) else {
                    prefs.edit().remove(key).apply()
                    error = "Loaded for this session; this provider did not grant persistent access."
                }
                loadedEncoding = encoding
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { encoding = loadedEncoding; error = "Could not load subtitle. Check format, encoding, permission and the 20 MiB limit, then retry." }
            finally {
                if (pending != null && !bound.closed) {
                    val pendingPath = pending?.path
                    val pendingId = newTrack ?: tracks(bound).firstOrNull { it["external-filename"]?.asString() == pendingPath }?.get("id")?.asInt()?.toInt()
                    pendingId?.let { bound.player.command("sub-remove", it.toString()) }
                    if (session === bound && generation == token) bound.player.setPropertyString("sid", previous)
                }
                pending?.delete()
                if (generation == token) { restoreSelection = null; busy = false }
            }
        }
    }
    private fun tracks(bound: MpvPlaybackSession) = bound.player.getPropertyNode("track-list")?.asArray().orEmpty().mapNotNull { it.asMap() }
    private fun rememberChoice(version: String? = null) {
        if (key.isEmpty()) return
        if (!selectionPersistent) { prefs.edit().remove(key).apply(); return }
        val old = runCatching { JSONObject(prefs.getString(key, "{}")!!) }.getOrDefault(JSONObject())
        val value = JSONObject().put("source", selected ?: old.optString("source")).put("name", currentName)
            .put("version", version ?: old.optString("version")).put("encoding", encoding).put("offset", offset).put("scale", scale)
        prefs.edit().putString(key, value.toString()).apply()
    }
    fun release(bound: MpvPlaybackSession?) {
        if (session !== bound) return
        cancel(); session = null; mounted.clear(); temporary.forEach { it.delete() }; temporary.clear()
        lastAttempt = null; loadedEncoding = "auto"; error = null
        storage = null; dav = null; key = ""
    }
}

private fun subtitleVersion(file: FileEntry) = listOf(file.size, file.lastModified, file.etag).joinToString(":")
