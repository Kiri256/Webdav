package com.example.webdavmanager

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

internal data class FolderPosition(val path: String?, val index: Int, val offset: Int)
private val Context.folderPositionsStore by preferencesDataStore("folder_positions")
internal class FolderPositionsStore(private val context: Context) {
    private val key = stringPreferencesKey("positions")
    suspend fun load(): Map<String, FolderPosition> = runCatching {
        val json = JSONObject(context.folderPositionsStore.data.first()[key] ?: "{}")
        json.keys().asSequence().associateWith { name ->
            val value = json.getJSONObject(name)
            FolderPosition(value.optString("path").takeIf { it.isNotEmpty() }, value.optInt("index").coerceAtLeast(0), value.optInt("offset").coerceAtLeast(0))
        }
    }.getOrDefault(emptyMap())
    suspend fun save(positions: Map<String, FolderPosition>) {
        val json = JSONObject()
        positions.forEach { (name, value) -> json.put(name, JSONObject().put("path", value.path.orEmpty()).put("index", value.index).put("offset", value.offset)) }
        context.folderPositionsStore.edit { it[key] = json.toString() }
    }
}

private fun FolderPosition.index(files: List<FileEntry>) = files.indexOfFirst { it.path == path }
    .takeIf { it >= 0 } ?: index.coerceIn(0, (files.size - 1).coerceAtLeast(0))

@Composable
internal fun folderListState(model: BrowserViewModel, files: List<FileEntry>, layout: String): LazyListState {
    val state = remember(model.folderPositionKey(layout), model.searchQuery) { LazyListState() }
    rememberFolderScroll(model, files, layout, state,
        read = { if (state.layoutInfo.totalItemsCount > 0) FolderPosition(null, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) else null },
        scrolling = { state.isScrollInProgress },
        restore = { index, offset -> state.scrollToItem(index, offset) })
    return state
}

@Composable
internal fun folderGridState(model: BrowserViewModel, files: List<FileEntry>): LazyGridState {
    val state = remember(model.folderPositionKey("grid"), model.searchQuery) { LazyGridState() }
    rememberFolderScroll(model, files, "grid", state,
        read = { if (state.layoutInfo.totalItemsCount > 0) FolderPosition(null, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) else null },
        scrolling = { state.isScrollInProgress },
        restore = { index, offset -> state.scrollToItem(index, offset) })
    return state
}

@Composable
private fun rememberFolderScroll(
    model: BrowserViewModel,
    files: List<FileEntry>,
    layout: String,
    state: Any,
    read: () -> FolderPosition?,
    scrolling: () -> Boolean,
    restore: suspend (Int, Int) -> Unit,
) {
    val key = model.folderPositionKey(layout)
    val position = remember(key, model.searchQuery) { model.folderPosition(key) }
    val currentFiles by rememberUpdatedState(files)
    val searchable = model.searchQuery.isBlank()
    var restored by remember(key, model.searchQuery) { mutableStateOf(false) }
    val save = {
        read()?.let { value -> currentFiles.getOrNull(value.index)?.let {
            model.rememberFolderPosition(key, value.copy(path = it.path))
        } }
    }
    LaunchedEffect(key, state, files.isNotEmpty()) {
        if (!restored && files.isNotEmpty()) {
            restore(if (searchable) position.index(files) else 0, if (searchable) position.offset else 0)
            restored = true
        }
    }
    LaunchedEffect(key, state, restored, searchable) {
        if (restored && searchable) snapshotFlow { read() to scrolling() }
            .collect { (_, moving) -> if (!moving) save() }
    }
    DisposableEffect(key, state, restored, searchable) {
        onDispose { if (restored && searchable) save() }
    }
}
