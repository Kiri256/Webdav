package com.example.webdavmanager

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

internal class BrowserViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val store = ConnectionStore(application)
    private val browserStore = BrowserStateStore(application)
    private val browserWrites = CoalescingStateWriter<BrowserState>(viewModelScope, browserStore::save) {
        connectionMessage = "Could not persist browser state; keep the app open before retrying."
    }
    var activeTab by mutableStateOf(saved.get<Int>("activeTab") ?: -1)
        private set
    var searchDialogVisible by mutableStateOf(saved.get<Boolean>("searchDialogVisible") ?: false)
        private set
    private val tabs = MutableList(4) { BrowserTabState() }
    fun openSearch() { searchDialogVisible = true; saved["searchDialogVisible"] = true }
    fun dismissSearch() { searchDialogVisible = false; saved["searchDialogVisible"] = false }
    private fun captureTab() {
        if (activeTab !in tabs.indices) return
        tabs[activeTab] = BrowserTabState(if (localMode) localDirectory else path,
            if (localMode) localParents else emptyList(), grid, searchQuery, sortMode.name)
    }
    private fun browserSnapshot(): BrowserState {
        // SAF selection is a test/compatibility entry; production cold starts retain the direct storage root.
        val direct = localStorage?.tree?.scheme != "content"
        captureTab()
        return BrowserState(localMode, connection?.id, path,
            if (direct) localDirectory else null, if (direct) localParents else emptyList(), grid, clipboard,
            activeTab.coerceIn(0, 3), tabs.map { if (!direct && it.directory?.startsWith("content:") == true) it.copy(directory = null, parents = emptyList()) else it })
    }
    private fun persistBrowser() { browserWrites.submit(browserSnapshot()) }
    private suspend fun persistBrowserNow() {
        browserWrites.flush(browserSnapshot())
    }
    fun flushBrowserState() { if (!starting) viewModelScope.launch { try { persistBrowserNow() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* The writer reports persistence failures. */ } } }
    private val positionsStore = FolderPositionsStore(application)
    private val folderPositions = linkedMapOf<String, FolderPosition>()
    private var positionSaveJob: Job? = null
    internal fun folderPositionKey(layout: String): String = listOf(
        activeTab.toString(), if (localMode) "local" else "dav", sourceKey.orEmpty(),
        if (localMode) localDirectory.orEmpty() else path, layout).joinToString("") { "${it.length}:$it" }
    internal fun folderPosition(key: String) = folderPositions[key] ?: FolderPosition(null, 0, 0)
    internal fun rememberFolderPosition(key: String, position: FolderPosition) {
        if (folderPositions[key] == position) return
        folderPositions.remove(key)
        folderPositions[key] = position
        while (folderPositions.size > 200) folderPositions.remove(folderPositions.keys.first())
        positionSaveJob?.cancel()
        val snapshot = folderPositions.toMap()
        positionSaveJob = viewModelScope.launch {
            try { positionsStore.save(snapshot) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* In-memory positions remain available if storage is unavailable. */ }
        }
    }
    var searchQuery by mutableStateOf(saved.get<String>("searchQuery") ?: "")
        private set
    var sortMode by mutableStateOf(saved.get<String>("sortMode")?.let {
        runCatching { FileSortMode.valueOf(it) }.getOrNull()
    } ?: FileSortMode.NAME)
        private set
    var selectionMode by mutableStateOf(saved.get<Boolean>("selectionMode") ?: false)
        private set
    var selectedPaths by mutableStateOf(saved.get<ArrayList<String>>("selectedPaths")?.toSet() ?: emptySet())
        private set
    var clipboard by mutableStateOf<FileClipboard?>(null)
        private set
    private var clipboardStorage: LocalStorage? = null
    private var clipboardClient: WebDavClient? = null
    var propertyFiles by mutableStateOf<List<FileEntry>>(emptyList())
        private set
    var operationBusy by mutableStateOf(false)
        private set
    var operationProgress by mutableStateOf(0)
        private set
    var operationTotal by mutableStateOf(0)
        private set
    var operationFile by mutableStateOf("")
        private set
    var operationPhase by mutableStateOf("")
        private set
    var operationBytes by mutableStateOf(0L)
        private set
    var operationBytesTotal by mutableStateOf<Long?>(null)
        private set
    private val resultItems = androidx.compose.runtime.mutableStateListOf<FileOperationResult>()
    var operationResults: List<FileOperationResult>
        get() = resultItems
        private set(value) { resultItems.clear(); resultItems.addAll(value) }
    private var transferJob: Job? = null
    val currentFiles get() = if (localMode) localEntries else entries
    private val visibleState by lazy { VisibleFilesState(viewModelScope) {
        VisibleFilesState.Input(currentFiles, searchQuery, sortMode, activeTab)
    } }
    val visibleFiles get() = visibleState.files
    val actionsBusy get() = operationBusy || localBusy || deleting || textBusy || loading || localLoading || externalBusy
    private val sourceKey get() = if (localMode) localStorage?.tree?.toString() else connection?.id
    val canPaste get() = clipboard?.let {
        if (it.local == localMode) it.sourceKey == sourceKey
        else if (it.local) clipboardStorage != null && client != null else clipboardClient != null && localStorage != null
    } == true && !actionsBusy
    var localMode by mutableStateOf(saved.get<Boolean>("localMode") ?: false)
        private set
    var localStorage by mutableStateOf<LocalStorage?>(null)
        private set
    var localDirectory by mutableStateOf(saved.get<String>("localDirectory"))
        private set
    var localParents by mutableStateOf(saved.get<ArrayList<String>>("localParents")?.toList() ?: emptyList())
        private set
    var localEntries by mutableStateOf<List<FileEntry>>(emptyList())
        private set
    var localLoading by mutableStateOf(false)
        private set
    var localBusy by mutableStateOf(false)
        private set
    var localError by mutableStateOf<String?>(null)
        private set
    private var localLoadJob: Job? = null
    private var localGeneration = 0L
    var connection by mutableStateOf<WebDavConnection?>(null)
        private set
    var draft by mutableStateOf<WebDavConnection?>(null)
        private set
    var client by mutableStateOf<WebDavClient?>(null)
        private set
    var starting by mutableStateOf(true)
        private set
    var editing by mutableStateOf(false)
        private set
    var connecting by mutableStateOf(false)
        private set
    var connectionError by mutableStateOf<String?>(null)
        private set
    var connectionMessage by mutableStateOf<String?>(null)
        private set
    var path by mutableStateOf(saved.get<String>("directory") ?: "/")
        private set
    var entries by mutableStateOf<List<FileEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var grid by mutableStateOf(saved.get<Boolean>("grid") ?: true)
        private set
    var imagePath by mutableStateOf(saved.get<String>("image"))
        private set
    var videoPath by mutableStateOf(saved.get<String>("video"))
        private set
    var audioPath by mutableStateOf(saved.get<String>("audio"))
        private set
    var textPath by mutableStateOf(saved.get<String>("text"))
        private set
    var textDraft by mutableStateOf(saved.get<String>("textDraft") ?: "")
        private set
    var textOriginal by mutableStateOf(saved.get<String>("textOriginal"))
        private set
    private var textVersion: RemoteText? = saved.get<String>("textOriginal")?.let {
        RemoteText(it, saved["textEtag"], saved["textModified"], saved["textBom"] ?: false)
    }
    var textBusy by mutableStateOf(false)
        private set
    var textError by mutableStateOf<String?>(null)
        private set
    val textDirty get() = textOriginal != null && textDraft != textOriginal
    val textWritable get() = textVersion?.let { it.etag != null || it.modified != null } == true
    private var textJob: Job? = null
    var deleting by mutableStateOf(false)
        private set
    var mediaError by mutableStateOf<String?>(null)
        private set
    var browserNotice by mutableStateOf<String?>(null)
        private set
    fun dismissBrowserNotice() { browserNotice = null }
    var rangeSupported by mutableStateOf<Boolean?>(null)
        private set
    val subtitles = SubtitleController(application, viewModelScope)
    var localVideo by mutableStateOf<File?>(null)
        private set
    var downloading by mutableStateOf(false)
        private set
    var downloadBytes by mutableStateOf(0L)
        private set
    private var downloadJob: Job? = null
    private var downloadGeneration = 0L
    fun cancelDownload() { downloadGeneration++; downloadJob?.cancel(); downloading = false }
    fun downloadVideo(file: FileEntry) {
        val dav = client ?: return
        if (localMode || downloading) return
        downloading = true; downloadBytes = 0; mediaError = null
        val attempt = ++downloadGeneration
        val selected = videoPath ?: audioPath
        downloadJob = viewModelScope.launch {
            val publisher = TransferProgressPublisher(this) { downloadBytes = it.bytes }
            var downloaded: File? = null
            try {
                downloaded = OfflinePlayback.download(getApplication(), dav, file) { publisher.submit(file.name, "Download", it, file.size) }
                if (attempt == downloadGeneration && (videoPath ?: audioPath) == selected) { localVideo = downloaded; downloaded = null; rangeSupported = true }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mediaError = "Could not download for local playback. Check free space and the 1 GiB limit, then retry." }
            finally { downloaded?.delete(); publisher.finish(); if (attempt == downloadGeneration) downloading = false }
        }
    }
    private var loadJob: Job? = null
    private var mediaJob: Job? = null
    private var generation = 0L
    var playbackPosition: Long
        get() = saved.get<Long>("position") ?: 0L
        set(value) { saved["position"] = value }
    var playbackWanted: Boolean
        get() = saved.get<Boolean>("playing") ?: true
        set(value) { saved["playing"] = value }


    init {
        viewModelScope.launch {
            try {
                folderPositions.putAll(positionsStore.load())
                val persisted = browserStore.load()
                if (persisted != null) {
                    persisted.tabs.forEachIndexed { index, state -> if (index < 4) tabs[index] = state }
                    if (activeTab !in 0..3) activeTab = if (saved.contains("localMode")) {
                        if (localMode) 0 else 2
                    } else persisted.activeTab
                    if (!saved.contains("localMode")) localMode = persisted.local
                    if (!saved.contains("directory")) { path = persisted.directory; saved["server"] = persisted.server }
                    if (!saved.contains("localDirectory")) localDirectory = persisted.localDirectory
                    if (!saved.contains("localParents")) localParents = persisted.localParents
                    if (!saved.contains("grid")) grid = persisted.grid
                    clipboard = persisted.clipboard
                }
                if (activeTab !in 0..3) activeTab = if (localMode) 0 else 2
                localMode = activeTab < 2
                val restoredTab = tabs[activeTab]
                if (persisted?.tabs?.isNotEmpty() == true) {
                    if (localMode) {
                        if (!saved.contains("localDirectory")) localDirectory = restoredTab.directory
                        if (!saved.contains("localParents")) localParents = restoredTab.parents
                    } else if (!saved.contains("directory")) path = restoredTab.directory ?: path
                    if (!saved.contains("grid")) grid = restoredTab.grid
                    if (!saved.contains("searchQuery")) searchQuery = restoredTab.search
                    if (!saved.contains("sortMode")) sortMode = runCatching { FileSortMode.valueOf(restoredTab.sort) }.getOrDefault(FileSortMode.NAME)
                }
                saved["activeTab"] = activeTab
                operationResults = TransferJournal.recover(application)
                CrossStorageTransfer.cleanOrphanSnapshots(application)
                val loaded = store.load()
                draft = loaded
                if (loaded != null) {
                    val nextClient = WebDavClient(loaded)
                    connection = loaded
                    client = nextClient
                    if (saved.get<String>("server") != loaded.id || !nextClient.paths.contains(path)) {
                        if (!localMode) resetNavigation(loaded)
                        else { path = nextClient.paths.rootPath; saved["directory"] = path; saved["server"] = loaded.id }
                    }
                    if (!localMode) refresh()
                } else editing = true
            } catch (e: ConnectionRecoveryException) {
                draft = e.connection
                connectionError = e.message
                editing = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connectionError = e.message ?: "Could not load saved connection"
                editing = true
            } finally {
                try {
                    localStorage = saved.get<String>("localTree")?.let { LocalStorage(application, Uri.parse(it)) }
                        ?: LocalStorage.load(application)
                    if (localDirectory?.startsWith("content:") == true && saved.get<String>("localTree") == null) {
                        localDirectory = null
                        localParents = emptyList()
                    }
                    if (localStorage?.tree?.scheme == "file") {
                        val root = DirectFiles.file(localStorage!!.root.toString()).toPath()
                        fun inside(value: String) = runCatching { DirectFiles.file(value).toPath().startsWith(root) }.getOrDefault(false)
                        if (localDirectory != null && !inside(localDirectory!!)) localDirectory = localStorage!!.root.toString()
                        localParents = localParents.filter(::inside)
                        saved["localDirectory"] = localDirectory
                        saved["localParents"] = ArrayList(localParents)
                    }
                    if (localMode) {
                        if (localDirectory == null) localDirectory = localStorage?.root?.toString()
                        refreshLocal()
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { localError = "Local folder access is unavailable. Select the folder again." }
                starting = false
                clipboard?.let { clip ->
                    if (clip.local) {
                        clipboardStorage = runCatching { LocalStorage(application, Uri.parse(clip.sourceKey)) }.getOrNull()
                    } else if (clip.sourceKey == connection?.id) clipboardClient = client
                    else clipboard = null
                }
                persistBrowser()
            }
        }
    }

    fun connect(candidate: WebDavConnection, testOnly: Boolean = false) {
        if (connecting || operationBusy) return
        connecting = true
        connectionError = null
        connectionMessage = null
        draft = candidate
        viewModelScope.launch {
            try {
                val next = WebDavClient(candidate)
                val files = next.list(next.paths.rootPath)
                if (testOnly) connectionMessage = "Connection successful"
                else {
                    val previous = connection
                    val sameServer = previous?.baseUrl == next.paths.base.toString() && previous.username == candidate.username &&
                        previous.password == candidate.password && previous.rootPath == candidate.rootPath
                    val normalized = candidate.copy(id = if (sameServer) previous!!.id else UUID.randomUUID().toString(),
                        baseUrl = next.paths.base.toString(), rootPath = next.paths.rootPath)
                    store.save(normalized)
                    if (activeTab < 2) { captureTab(); activeTab = 2; saved["activeTab"] = activeTab }
                    if (!sameServer) for (index in 2..3) tabs[index] = BrowserTabState()
                    cancelLoad()
                    closeMedia()
                    localMode = false
                    saved["localMode"] = false
                    connection = normalized
                    draft = normalized
                    client = WebDavClient(normalized)
                    resetNavigation(normalized)
                    entries = files
                    error = null
                    editing = false
                    persistBrowser()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connectionError = e.message ?: "Connection failed"
            } finally {
                connecting = false
            }
        }
    }

    fun updateDraft(next: WebDavConnection) {
        if (!connecting) { draft = next; connectionError = null; connectionMessage = null }
    }

    fun editConnection() {
        if (deleting || operationBusy) return
        resetFileActions()
        if (clipboard?.local == false) clearClipboard()
        cancelLoad()
        closeMedia()
        draft = connection
        connectionError = null
        connectionMessage = null
        editing = true
    }

    fun cancelEditing() {
        if (connection != null && !connecting) {
            editing = false
            refresh()
        }
    }

    private fun resetNavigation(next: WebDavConnection) {
        resetFileActions()
        path = normalizePath(next.rootPath)
        saved["directory"] = path
        saved["server"] = next.id
        imagePath = null
        videoPath = null
        saved["image"] = null
        saved["video"] = null
        audioPath = null
        saved["audio"] = null
        clearText()
    }

    private fun cancelLoad() {
        generation++
        loadJob?.cancel()
        loading = false
    }

    fun refresh() {
        if (deleting || editing || operationBusy) return
        val currentClient = client ?: return
        cancelLoad()
        val token = generation
        val requested = path
        loading = true
        error = null
        loadJob = viewModelScope.launch {
            try {
                val files = currentClient.list(requested)
                if (token != generation) return@launch
                entries = files
                reconcileSelection(files)
                imagePath?.let { if (files.none { file -> file.path == it && file.isImage() }) setImage(null) }
                videoPath?.let { selected ->
                    files.firstOrNull { it.path == selected && it.isVideo() }?.let { prepareVideo(it) } ?: closeMedia()
                }
                audioPath?.let { selected ->
                    files.firstOrNull { it.path == selected && it.isAudio() }?.let { prepareVideo(it) } ?: closeMedia()
                }
                textPath?.let { selected ->
                    if (files.none { it.path == selected && it.isText() }) closeMedia()
                    else if (textOriginal == null) loadText()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == generation) error = e.message ?: "Could not load directory"
            } finally {
                if (token == generation) loading = false
            }
        }
    }

    var pathEditorVisible by mutableStateOf(false)
        private set
    var pathInput by mutableStateOf("")
        private set
    var pathJumpError by mutableStateOf<String?>(null)
        private set
    var pathJumpBusy by mutableStateOf(false)
        private set
    private var pathJumpJob: Job? = null
    private var pathJumpGeneration = 0L
    fun openPathEditor() { pathInput = if (localMode && localStorage?.tree?.scheme != "file") localDirectory.orEmpty() else displayFileLocation(if (localMode) localDirectory.orEmpty() else path); pathJumpError = null; pathEditorVisible = true }
    fun updatePathInput(value: String) { pathInput = value; pathJumpError = null }
    fun dismissPathEditor() { pathJumpGeneration++; pathJumpJob?.cancel(); pathJumpBusy = false; pathEditorVisible = false }
    fun jumpToPath() {
        if (actionsBusy || pathJumpBusy) return
        val tab = activeTab
        val local = localMode
        val storage = localStorage
        val dav = client
        val input = pathInput
        val attempt = ++pathJumpGeneration
        pathJumpBusy = true
        pathJumpError = null
        pathJumpJob = viewModelScope.launch {
            try {
                val target: String
                val files: List<FileEntry>
                if (local) {
                    requireNotNull(storage)
                    if (storage.tree.scheme == "file") {
                        target = kotlinx.coroutines.withContext(Dispatchers.IO) { localJumpUri(storage.root.toString(), input) }
                    } else {
                        require(input.startsWith("content://")) { "For this document provider, enter an authorized directory URI instead of a filesystem path" }
                        target = input
                    }
                    require(storage.stat(target).isDirectory) { "The path points to a file" }
                    files = storage.list(target)
                } else {
                    requireNotNull(dav)
                    target = remoteJumpPath(dav.paths, input)
                    require(dav.stat(target, true).isDirectory) { "The path points to a file" }
                    files = dav.list(target)
                }
                if (attempt != pathJumpGeneration || tab != activeTab || local != localMode || storage !== localStorage || dav !== client) return@launch
                cancelLoad(); localLoadJob?.cancel(); localGeneration++; localLoading = false
                resetFileActions(); closeMedia()
                if (local) {
                    localDirectory = target
                    localParents = if (storage!!.tree.scheme == "file") localParentChain(storage.root.toString(), target) else listOf(storage.root.toString()).filter { it != target }
                    localEntries = files; localError = null
                    saved["localDirectory"] = target; saved["localParents"] = ArrayList(localParents)
                } else { path = target; entries = files; error = null; saved["directory"] = target }
                persistBrowser()
                pathEditorVisible = false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (attempt == pathJumpGeneration) pathJumpError = "Cannot open this folder. Check the path, directory boundary and access permission, then retry." }
            finally { if (attempt == pathJumpGeneration) pathJumpBusy = false }
        }
    }

    var renameTarget by mutableStateOf<FileEntry?>(null)
        private set
    fun requestRename(file: FileEntry) { if (!actionsBusy) renameTarget = file }
    fun dismissRename() { renameTarget = null }
    fun renameCurrent(file: FileEntry, name: String) {
        if (localMode) { renameLocal(file, name); renameTarget = null; return }
        val dav = client ?: return
        if (actionsBusy) return
        renameTarget = null
        operationBusy = true
        error = null
        viewModelScope.launch {
            try {
                require(currentFiles.any { it.path == file.path })
                val current = dav.inspect(file); requireSameVersion(file, current)
                dav.transfer(current, dav.paths.child(path, name), true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Rename failed; check both names before retrying" }
            finally { operationBusy = false; if (error == null) refresh() }
        }
    }
    fun createCurrent(name: String, folder: Boolean) {
        if (localMode) { createLocal(name, folder); return }
        val dav = client ?: return
        if (actionsBusy) return
        val directory = path
        operationBusy = true
        error = null
        viewModelScope.launch {
            var temp: File? = null
            try {
                val destination = dav.paths.child(directory, name)
                if (folder) dav.createDirectory(destination)
                else kotlinx.coroutines.withContext(Dispatchers.IO) {
                    temp = File.createTempFile("new-text-", ".txt", getApplication<Application>().cacheDir)
                    dav.upload(destination, temp!!, "text/plain")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Creation failed; check the folder before retrying" }
            finally { temp?.delete(); operationBusy = false; if (error == null) refresh() }
        }
    }

    fun navigate(next: String) {
        if (deleting || operationBusy || client?.paths?.contains(next) != true) return
        resetFileActions()
        closeMedia()
        path = normalizePath(next)
        saved["directory"] = path
        entries = emptyList()
        persistBrowser()
        refresh()
    }

    fun toggleGrid() { grid = !grid; saved["grid"] = grid; persistBrowser() }

    fun open(file: FileEntry) {
        if (textBusy || textDirty || deleting || operationBusy || localBusy) return
        closeMedia()
        when {
            file.isDirectory -> if (localMode) navigateLocal(file.path) else navigate(file.path)
            file.isImage() -> { setImage(file.path); mediaError = null }
            file.isVideo() -> {
                videoPath = file.path
                saved["video"] = file.path
                playbackPosition = 0
                playbackWanted = true
                prepareVideo(file)
            }
            file.isAudio() -> {
                audioPath = file.path
                saved["audio"] = file.path
                playbackPosition = 0
                playbackWanted = true
                prepareVideo(file)
            }
            file.isText() -> {
                textPath = file.path
                saved["text"] = file.path
                loadText()
            }
            else -> requestExternalOpen(file)
        }
    }

    private fun setImage(next: String?) { imagePath = next; saved["image"] = next }
    fun selectImage(file: FileEntry) { if (!deleting) { setImage(file.path); mediaError = null } }

    fun closeMedia() {
        if (deleting || textBusy) return
        clearText()
        setImage(null)
        audioPath = null
        saved["audio"] = null
        videoPath = null
        saved["video"] = null
        mediaJob?.cancel()
        cancelDownload()
        localVideo = null
        mediaError = null
    }

    private fun clearText() {
        textJob?.cancel()
        textPath = null
        textDraft = ""
        textOriginal = null
        textVersion = null
        textError = null
        listOf("text", "textDraft", "textOriginal", "textEtag", "textModified", "textBom").forEach { saved.remove<Any>(it) }
    }

    fun updateText(value: String) {
        if (textBusy) return
        if (value.toByteArray(Charsets.UTF_8).size > TEXT_LIMIT - 3) {
            textError = "Text editor supports files up to 64 KiB"
            return
        }
        textDraft = value
        saved["textDraft"] = value
    }

    private fun setTextVersion(value: RemoteText) {
        textVersion = value
        textOriginal = value.content
        saved["textOriginal"] = value.content
        saved["textEtag"] = value.etag
        saved["textModified"] = value.modified
        saved["textBom"] = value.bom
    }

    fun loadText() {
        if (textBusy) return
        val selected = textPath ?: return
        val dav = client
        val storage = if (localMode) localStorage else null
        if ((localMode && storage == null) || (!localMode && dav == null)) return
        textBusy = true
        textError = null
        textJob = viewModelScope.launch {
            try {
                val value = if (storage != null) storage.readText(selected) else dav!!.readText(selected)
                setTextVersion(value)
                textDraft = value.content
                saved["textDraft"] = value.content
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { textError = e.message ?: "Could not read text" }
            finally { textBusy = false }
        }
    }

    fun saveText() {
        if (textBusy || !textDirty) return
        val selected = textPath ?: return
        val dav = client
        val storage = if (localMode) localStorage else null
        if ((localMode && storage == null) || (!localMode && dav == null)) return
        val original = textVersion ?: return
        val content = textDraft
        textBusy = true
        textError = null
        textJob = viewModelScope.launch {
            try {
                val result = if (storage != null) storage.saveText(selected, content, original) else dav!!.saveText(selected, content, original)
                setTextVersion(result)
                if (storage != null) {
                    // Publish the new local identity before enabling actions; a background refresh can finish later.
                    val current = storage.stat(selected)
                    localEntries = localEntries.map { if (it.path == selected) current else it }
                    refreshLocal()
                } else {
                    // A PUT response may omit validators. Reload explicitly before another edit/save.
                    entries = entries.map { if (it.path == selected) it.copy(etag = result.etag,
                        lastModified = result.modified, size = ((if (result.bom) "\uFEFF" else "") + content).toByteArray(Charsets.UTF_8).size.toLong()) else it }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { textError = e.message ?: "Could not save text" }
            finally { textBusy = false }
        }
    }

    fun deleteImage(file: FileEntry) {
        if (deleting) return
        val currentClient = client ?: return
        deleting = true
        mediaError = null
        cancelLoad()
        viewModelScope.launch {
            try {
                val current = currentClient.inspect(file)
                requireSameVersion(file, current)
                currentClient.delete(current.path, current.etag)
                val nextImage = nextImageAfterDelete(entries, imagePath, file.path)
                if (nextImage == null) browserNotice = "No images remain in this folder."
                entries = entries.filterNot { it.path == file.path }
                setImage(nextImage)
                try {
                    ThumbnailCache.evict(getApplication(), currentClient.connection.id, file)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mediaError = "File deleted; cached image cleanup failed"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mediaError = e.message ?: "Delete failed"
            } finally {
                deleting = false
            }
        }
    }

    private fun prepareVideo(file: FileEntry) {
        if (localMode) {
            rangeSupported = true
            mediaError = null
            return
        }
        val currentClient = client ?: return
        mediaJob?.cancel()
        rangeSupported = null
        mediaError = null
        mediaJob = viewModelScope.launch {
            try {
                rangeSupported = currentClient.supportsRange(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mediaError = e.message ?: "Could not check video seeking"
            }
        }
    }

    fun reportMediaError(message: String) { mediaError = message }

    var externalTarget by mutableStateOf<FileEntry?>(null)
        private set
    var externalBusy by mutableStateOf(false)
        private set
    var externalError by mutableStateOf<String?>(null)
        private set
    var externalReady by mutableStateOf<Uri?>(null)
        private set
    private var externalJob: Job? = null
    private var externalGeneration = 0L
    fun requestExternalOpen(file: FileEntry) {
        if (actionsBusy || file.isDirectory || currentFiles.none { it.path == file.path }) return
        externalTarget = file
        externalError = null
    }
    fun cancelExternalOpen() {
        externalGeneration++
        externalJob?.cancel()
        externalTarget = null
        externalReady = null
        externalBusy = false
    }
    fun externalOpened() { externalReady = null; externalTarget = null; externalBusy = false }
    fun externalOpenFailed() { externalReady = null; externalError = "No application could open this file. Install a compatible app and retry." }
    fun prepareExternalOpen() {
        if (externalBusy) return
        val file = externalTarget ?: return
        val storage = if (localMode) localStorage else null
        val dav = if (localMode) null else client
        externalBusy = true
        externalError = null
        val token = ++externalGeneration
        externalJob = viewModelScope.launch {
            var folder: File? = null
            try {
                val cached = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val root = File(getApplication<Application>().cacheDir, "external_open").apply { mkdirs() }
                    val limit = 256L * 1024 * 1024
                    val existing = root.listFiles().orEmpty().sortedBy { it.lastModified() }
                    var used = existing.sumOf { directory -> directory.listFiles().orEmpty().sumOf { it.length() } }
                    for (old in existing) if (used + limit > 512L * 1024 * 1024 || System.currentTimeMillis() - old.lastModified() > 24L * 60 * 60 * 1000) {
                        val bytes = old.listFiles().orEmpty().sumOf { it.length() }
                        if (old.deleteRecursively()) used -= bytes
                    }
                    if (used + limit > 512L * 1024 * 1024) throw java.io.IOException("Open-file cache is full")
                    validateLocalName(file.name)
                    folder = File(root, UUID.randomUUID().toString()).apply { if (!mkdir()) throw java.io.IOException("Cannot create open-file cache") }
                    val target = File(folder, file.name)
                    if (storage != null) storage.exportFile(file, target, limit) else requireNotNull(dav).download(file, target, limit)
                    target
                }
                ensureActive()
                if (token == externalGeneration) externalReady = androidx.core.content.FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.files", cached)
            } catch (error: CancellationException) { folder?.deleteRecursively(); throw error }
            catch (error: Exception) { folder?.deleteRecursively(); if (token == externalGeneration) externalError = error.message ?: "Cannot prepare file for another app" }
            finally { if (token == externalGeneration) externalBusy = false }
        }
    }

    fun selectTab(index: Int) {
        dismissPathEditor()
        if (index !in 0..3 || index == activeTab || starting || textBusy || textDirty || deleting || localBusy || connecting || operationBusy || externalBusy) return
        captureTab()
        resetFileActions()
        closeMedia()
        cancelLoad()
        localLoadJob?.cancel()
        localGeneration++
        localLoading = false
        activeTab = index
        saved["activeTab"] = index
        localMode = index < 2
        saved["localMode"] = localMode
        val tab = tabs[index]
        grid = tab.grid
        saved["grid"] = grid
        searchQuery = tab.search
        saved["searchQuery"] = searchQuery
        sortMode = runCatching { FileSortMode.valueOf(tab.sort) }.getOrDefault(FileSortMode.NAME)
        saved["sortMode"] = sortMode.name
        editing = false
        if (localMode) {
            val root = localStorage?.root?.toString()
            fun valid(value: String) = runCatching {
                if (localStorage?.tree?.scheme == "file") DirectFiles.file(value).toPath().startsWith(DirectFiles.file(root!!).toPath())
                else value.startsWith("content:") && localStorage != null
            }.getOrDefault(false)
            localDirectory = tab.directory?.takeIf(::valid) ?: root
            localParents = tab.parents.filter(::valid)
            saved["localDirectory"] = localDirectory
            saved["localParents"] = ArrayList(localParents)
            localEntries = emptyList()
            refreshLocal()
        } else {
            path = tab.directory?.takeIf { client?.paths?.contains(it) == true } ?: client?.paths?.rootPath ?: "/"
            saved["directory"] = path
            entries = emptyList()
            if (connection == null) { editing = true; draft = null } else refresh()
        }
        persistBrowser()
    }

    fun openInternalStorage() {
        if (actionsBusy || textDirty || localStorage?.tree?.scheme != "file") return
        resetFileActions()
        closeMedia()
        localDirectory = Uri.fromFile(android.os.Environment.getExternalStorageDirectory()).toString()
        localParents = emptyList()
        saved["localDirectory"] = localDirectory
        saved["localParents"] = ArrayList(localParents)
        localEntries = emptyList()
        persistBrowser()
        refreshLocal()
    }

    fun selectLocalFolder(tree: Uri) {
        if (localBusy || textBusy || textDirty || operationBusy) return
        resetFileActions()
        closeMedia()
        localLoadJob?.cancel()
        localGeneration++
        localBusy = true
        localError = null
        viewModelScope.launch {
            try {
                localStorage = LocalStorage.openGrantedFolder(getApplication(), tree)
                saved["localTree"] = tree.toString()
                localDirectory = localStorage!!.root.toString()
                localParents = emptyList()
                saved["localDirectory"] = localDirectory
                saved["localParents"] = arrayListOf<String>()
                localMode = true
                saved["localMode"] = true
                localEntries = emptyList()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { localError = "Could not access this folder. Select a writable folder again." }
            finally { localBusy = false; if (localError == null) refreshLocal() }
        }
    }

    fun refreshLocal() {
        if (localBusy || !localMode || operationBusy) return
        val storage = localStorage ?: return
        val directory = localDirectory ?: storage.root.toString()
        localLoadJob?.cancel()
        val token = ++localGeneration
        localLoading = true
        localError = null
        localLoadJob = viewModelScope.launch {
            try {
                val files = storage.list(directory)
                if (token != localGeneration) return@launch
                localEntries = files
                reconcileSelection(files)
                textPath?.let { if (textOriginal == null && files.any { file -> file.path == it }) loadText() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == localGeneration) localError = "Cannot read directory. Check storage permission and retry. Protected system directories require root access."
            } finally { if (token == localGeneration) localLoading = false }
        }
    }

    fun navigateLocal(next: String) {
        if (localBusy || textBusy || textDirty || operationBusy || localEntries.none { it.path == next && it.isDirectory }) return
        resetFileActions()
        closeMedia()
        localDirectory?.let { localParents = localParents + it }
        localDirectory = next
        saved["localDirectory"] = next
        saved["localParents"] = ArrayList(localParents)
        localEntries = emptyList()
        persistBrowser()
        refreshLocal()
    }

    fun localUp() {
        if (localBusy || textBusy || textDirty || operationBusy || localParents.isEmpty()) return
        resetFileActions()
        closeMedia()
        localDirectory = localParents.last()
        localParents = localParents.dropLast(1)
        saved["localDirectory"] = localDirectory
        saved["localParents"] = ArrayList(localParents)
        localEntries = emptyList()
        persistBrowser()
        refreshLocal()
    }

    fun createLocal(name: String, folder: Boolean) = mutateLocal { storage ->
        storage.create(localDirectory ?: storage.root.toString(), name, folder)
    }

    fun renameLocal(file: FileEntry, name: String) = mutateLocal { storage ->
        require(localEntries.any { it.path == file.path }) { "File no longer exists in this folder" }
        storage.rename(file, localDirectory ?: storage.root.toString(), name)
    }

    fun deleteLocal(file: FileEntry) = mutateLocal { storage ->
        require(localEntries.any { it.path == file.path }) { "File no longer exists in this folder" }
        requireSameVersion(file, storage.stat(file.path))
        storage.delete(file)
        if (imagePath == file.path) setImage(nextImageAfterDelete(localEntries, imagePath, file.path))
        if (file.isImage() && imagePath == null && localEntries.none { it.path != file.path && it.isImage() }) browserNotice = "No images remain in this folder."
        localEntries = localEntries.filterNot { it.path == file.path }
    }

    private fun mutateLocal(action: suspend (LocalStorage) -> Unit) {
        if (localBusy || textBusy || textDirty || operationBusy) return
        val storage = localStorage ?: return
        localBusy = true
        localError = null
        localLoadJob?.cancel()
        localGeneration++
        viewModelScope.launch {
            try { action(storage) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { localError = e.message ?: "Local file operation failed" }
            finally {
                localBusy = false
                // Keep operation errors visible; a retry can refresh explicitly.
                if (localError == null) refreshLocal()
            }
        }
    }

    fun setSearch(query: String) { searchQuery = query; saved["searchQuery"] = query; persistBrowser() }
    fun updateSortMode(mode: FileSortMode) { sortMode = mode; saved["sortMode"] = mode.name; persistBrowser() }
    fun beginSelection() { if (!actionsBusy) { selectionMode = true; saved["selectionMode"] = true } }
    fun toggleSelection(file: FileEntry) {
        if (actionsBusy || currentFiles.none { it.path == file.path }) return
        beginSelection()
        selectedPaths = if (file.path in selectedPaths) selectedPaths - file.path else selectedPaths + file.path
        saved["selectedPaths"] = ArrayList(selectedPaths)
    }
    fun selectVisible() {
        if (actionsBusy) return
        beginSelection()
        selectedPaths = selectedPaths + visibleFiles.map { it.path }
        saved["selectedPaths"] = ArrayList(selectedPaths)
    }
    fun clearSelection() {
        selectionMode = false
        selectedPaths = emptySet()
        saved["selectionMode"] = false
        saved["selectedPaths"] = arrayListOf<String>()
    }
    private fun reconcileSelection(files: List<FileEntry>) {
        selectedPaths = selectedPaths.intersect(files.map { it.path }.toSet())
        saved["selectedPaths"] = ArrayList(selectedPaths)
    }
    private fun resetFileActions() { clearSelection(); searchQuery = ""; saved["searchQuery"] = ""; propertyFiles = emptyList(); deleteTargets = emptyList() }
    fun showProperties(files: List<FileEntry>) { propertyFiles = files }
    fun closeProperties() { propertyFiles = emptyList() }
    fun stageFiles(move: Boolean, files: List<FileEntry> = currentFiles.filter { it.path in selectedPaths }) {
        if (actionsBusy || files.isEmpty() || files.any { file -> currentFiles.none { it.path == file.path } }) return
        val key = sourceKey ?: return
        val directory = if (localMode) localDirectory ?: return else path
        clipboard = FileClipboard(files, directory, key, localMode, move)
        clipboardStorage = if (localMode) localStorage else null
        clipboardClient = if (localMode) null else client
        clearSelection()
        setSearch("")
        operationResults = emptyList()
        persistBrowser()
    }
    fun clearClipboard() { if (!operationBusy) { clipboard = null; clipboardStorage = null; clipboardClient = null; persistBrowser() } }
    fun dismissOperationResults() {
        if (operationBusy) return
        // Keep new mutations from creating journals until acknowledgement has finished.
        operationBusy = true
        viewModelScope.launch {
            try {
                TransferJournal.acknowledge(getApplication())
                operationResults = emptyList()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { browserNotice = "Could not dismiss task records. Retry when storage is available." }
            finally { operationBusy = false }
        }
    }
    fun cancelFileOperation() { transferJob?.cancel() }
    val retryableResults get() = operationResults.filter { it.status in setOf("Failed", "Cancelled", "Not processed", "Needs review") &&
        clipboard?.files?.any { file -> file.path == it.file.path } == true }
    private var resultDestination: String? = null
    private var resultTargetKey: String? = null
    val canRetryResults get() = canPaste && retryableResults.isNotEmpty() && resultDestination == (if (localMode) localDirectory else path) &&
        resultTargetKey == (if (localMode) localStorage?.tree?.toString() else connection?.id)
    fun retryResults() {
        if (!canRetryResults) return
        val paths = retryableResults.map { it.file.path }.toSet()
        clipboard = clipboard?.let { it.copy(files = it.files.filter { file -> file.path in paths }) }
        persistBrowser()
        pasteFiles()
    }

    var deleteTargets by mutableStateOf<List<FileEntry>>(emptyList())
        private set
    fun requestDelete(files: List<FileEntry> = currentFiles.filter { it.path in selectedPaths }) {
        if (!actionsBusy && files.isNotEmpty() && files.all { file -> currentFiles.any { it.path == file.path } }) deleteTargets = files
    }
    fun dismissDelete() { deleteTargets = emptyList() }
    fun confirmDelete() {
        if (actionsBusy || textDirty || deleteTargets.isEmpty()) return
        val targets = deleteTargets
        operationTotal = targets.size
        deleteTargets = emptyList()
        val storage = if (localMode) localStorage else null
        val dav = if (localMode) null else client
        val directory = if (localMode) localDirectory ?: return else path
        operationBusy = true
        operationProgress = 0
        operationPhase = ""
        operationResults = emptyList()
        cancelLoad()
        localLoadJob?.cancel()
        localGeneration++
        localLoading = false
        transferJob = viewModelScope.launch {
            try {
                for (file in targets) {
                    ensureActive()
                    operationFile = file.name
                    try {
                        val current = storage?.stat(file.path) ?: requireNotNull(dav).inspect(file)
                        if (current.isDirectory != file.isDirectory || current.size != file.size || current.lastModified != file.lastModified || current.etag != file.etag)
                            throw java.io.IOException("File changed; select it again")
                        if (storage != null) storage.delete(current) else requireNotNull(dav).delete(current.path, current.etag)
                        resultItems.add(FileOperationResult(file, "Deleted"))
                        if (storage == null && file.isImage()) {
                            try { ThumbnailCache.evict(getApplication(), requireNotNull(dav).connection.id, file) }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) { operationResults = operationResults.dropLast(1) + FileOperationResult(file, "Deleted", "Cache cleanup unavailable") }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { resultItems.add(FileOperationResult(file, "Failed", error.message ?: "Deletion incomplete; check directory")) }
                    operationProgress++
                }
            } catch (_: CancellationException) {
                val finished = operationResults.map { it.file.path }.toSet()
                resultItems.addAll(targets.filter { it.path !in finished }.map { FileOperationResult(it, "Cancelled", "Check directory before retrying") })
            } finally {
                operationBusy = false
                operationFile = ""
                clearSelection()
                if (localMode) refreshLocal() else refresh()
            }
        }
    }

    fun pasteFiles() {
        if (!canPaste || textDirty) return
        val staged = clipboard ?: return
        val remainingFiles = staged.files.associateByTo(linkedMapOf()) { it.path }
        operationTotal = staged.files.size
        val destination = if (localMode) localDirectory ?: return else path
        resultDestination = destination
        resultTargetKey = if (localMode) localStorage?.tree?.toString() else connection?.id
        val storage = if (localMode) localStorage else null
        val dav = client
        val cross = staged.local != localMode
        val crossTransfer = if (cross) CrossStorageTransfer(getApplication(), clipboardStorage, clipboardClient,
            storage, if (storage == null) dav else null) else null
        operationBusy = true
        operationProgress = 0
        operationBytes = 0
        operationBytesTotal = null
        operationPhase = "Preparing"
        operationResults = emptyList()
        cancelLoad()
        localLoadJob?.cancel()
        localGeneration++
        localLoading = false
        transferJob = viewModelScope.launch {
            var current: FileEntry? = null
            try {
                val destinationFiles = if (storage != null) storage.list(destination) else dav!!.list(destination)
                val occupied = destinationFiles.map { it.name }.toMutableSet()
                for (file in staged.files) {
                    ensureActive()
                    current = file
                    operationFile = file.name
                    operationPhase = "Preparing"
                    operationBytes = 0
                    operationBytesTotal = file.size
                    if (!cross && staged.move && destination == staged.directory) {
                        resultItems.add(FileOperationResult(file, "Skipped", "Already in this folder"))
                    } else if (file.name in occupied) {
                        resultItems.add(FileOperationResult(file, "Skipped", "Name already exists"))
                    } else {
                        try {
                            val name = file.name.also(::validateLocalName)
                            val journal = TransferJournal(getApplication(), file,
                                if (storage != null) "$destination / $name" else dav!!.paths.child(destination, name),
                                storage?.tree?.toString() ?: dav!!.connection.id, staged.move)
                            journal.record("transfer starting; outcome may be unknown")
                            val progress = TransferProgressPublisher(this) { value ->
                                operationFile = value.file
                                operationPhase = value.phase
                                operationBytes = value.bytes
                                operationBytesTotal = value.total
                            }
                            val outcome = try { if (crossTransfer != null) crossTransfer.transfer(
                                file, staged.directory, destination, name, staged.move, progress::submit, journal)
                            else if (storage != null) {
                                operationPhase = if (staged.move) "Moving" else "Copying"
                                storage.transfer(file, staged.directory, destination, name, staged.move, journal, progress::submit)
                            }
                            else {
                                operationPhase = "Waiting for server"
                                operationBytesTotal = null
                                dav!!.transferChecked(file, dav.paths.child(destination, name), staged.move)
                                TransferOutcome(staged.move)
                            } } finally { progress.finish() }
                            operationBytes = operationBytesTotal ?: file.size ?: 0L
                            operationBytesTotal = file.size
                            operationPhase = "Finishing"
                            occupied += name
                            resultItems.add(FileOperationResult(file, if (staged.move && outcome.sourceDeleted) "Moved" else "Copied",
                                if (outcome.detail.isEmpty()) name else "$name. ${outcome.detail}"))
                            // Persist each completed item so process death cannot offer to repeat a completed MOVE.
                            remainingFiles.remove(file.path)
                            clipboard = staged.copy(files = remainingFiles.values.toList()).takeIf { it.files.isNotEmpty() }
                            try {
                                persistBrowserNow()
                                if (!staged.move || outcome.sourceDeleted) journal.complete()
                            } catch (error: CancellationException) { current = null; throw error }
                            catch (_: Exception) {
                                try { journal.record("transfer completed; clipboard persistence unavailable") } catch (_: Exception) { }
                            }
                            if (staged.move && outcome.sourceDeleted && !staged.local && file.isImage()) {
                                try { ThumbnailCache.evict(getApplication(), clipboardClient!!.connection.id, file) }
                                catch (e: CancellationException) { current = null; throw e }
                                catch (_: Exception) {
                                    operationResults = operationResults.dropLast(1) + FileOperationResult(file, "Moved", "$name (cache cleanup unavailable)")
                                }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            resultItems.add(FileOperationResult(file, "Failed", e.message ?: "Transfer failed; check source and destination"))
                        }
                    }
                    operationProgress++
                    current = null
                }
            } catch (e: CancellationException) {
                val reported = operationResults.map { it.file.path }.toSet()
                resultItems.addAll(staged.files.filter { it.path !in reported }.map {
                    FileOperationResult(it, if (it.path == current?.path) "Cancelled" else "Not processed", "Check source and destination before retrying")
                })
            } catch (e: Exception) {
                val done = operationResults.map { it.file.path }.toSet()
                resultItems.addAll(staged.files.filter { it.path !in done }.map {
                    FileOperationResult(it, "Failed", e.message ?: "Could not read destination")
                })
            } finally {
                val remaining = remainingFiles.values.toList()
                clipboard = if (remaining.isNotEmpty()) staged.copy(files = remaining)
                    else if (staged.move) null else staged
                persistBrowser()
                operationBusy = false
                operationFile = ""
                operationPhase = ""
                operationBytes = 0
                operationBytesTotal = null
                clearSelection()
                if (localMode) refreshLocal() else refresh()
            }
        }
    }
}
