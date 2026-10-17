package com.example.webdavmanager

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Large listings never sort on the UI thread; a result belongs to its exact input. */
internal class VisibleFilesState(scope: CoroutineScope, input: () -> Input) {
    data class Input(val files: List<FileEntry>, val query: String, val sort: FileSortMode, val tab: Int)
    private val current by derivedStateOf(input)
    private val small by derivedStateOf {
        current.let { if (it.files.size <= 1000) sortFiles(filterFiles(it.files, it.query), it.sort) else emptyList() }
    }
    private var completed by mutableStateOf<Pair<Input, List<FileEntry>>?>(null)
    val files: List<FileEntry> get() {
        val value = current
        return if (value.files.size <= 1000) small
        else completed?.takeIf { it.first == value }?.second.orEmpty()
    }
    init {
        scope.launch {
            snapshotFlow { current }.collectLatest { value ->
                if (value.files.size > 1000) {
                    val result = withContext(Dispatchers.Default) {
                        val jobContext = coroutineContext
                        val filtered = if (value.query.isBlank()) value.files else value.files.filter {
                            jobContext.ensureActive(); it.name.contains(value.query, ignoreCase = true)
                        }
                        sortFiles(filtered, value.sort) { jobContext.ensureActive() }
                    }
                    completed = value to result
                } else completed = null
            }
        }
    }
}
