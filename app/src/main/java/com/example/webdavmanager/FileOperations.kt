package com.example.webdavmanager

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal enum class FileSortMode { NAME, SIZE, MODIFIED, TYPE }
internal data class FileClipboard(val files: List<FileEntry>, val directory: String, val sourceKey: String,
    val local: Boolean, val move: Boolean)
internal data class FileOperationResult(val file: FileEntry, val status: String, val detail: String = "")
internal data class TransferOutcome(val sourceDeleted: Boolean, val detail: String = "")

internal fun requireSameVersion(expected: FileEntry, current: FileEntry, comparePath: Boolean = true) {
    if ((comparePath && expected.path != current.path) || expected.isDirectory != current.isDirectory ||
        expected.size != current.size || expected.lastModified != current.lastModified ||
        (expected.etag != null && expected.etag != current.etag))
        throw java.io.IOException("File changed; select it again")
}

internal fun filterFiles(files: List<FileEntry>, query: String): List<FileEntry> =
    if (query.isBlank()) files else files.filter { it.name.contains(query, ignoreCase = true) }

internal fun sortFiles(files: List<FileEntry>, mode: FileSortMode, checkCancelled: () -> Unit = {}): List<FileEntry> {
    data class Key(val file: FileEntry, val name: String, val modified: Long?)
    val keys = files.map {
        checkCancelled()
        Key(it, it.name.lowercase(), if (mode == FileSortMode.MODIFIED) modifiedMillis(it.lastModified) else null)
    }
    val comparator = when (mode) {
        FileSortMode.NAME -> compareBy<Key> { it.name }
        FileSortMode.SIZE -> compareBy<Key> { it.file.size == null }
            .thenBy { it.file.size ?: 0L }.thenBy { it.name }
        FileSortMode.MODIFIED -> compareBy<Key> { it.modified == null }
            .thenBy { it.modified ?: 0L }.thenBy { it.name }
        FileSortMode.TYPE -> compareBy<Key> { if (it.file.isDirectory) "" else it.file.mimeType.orEmpty() }
            .thenBy { it.name }
    }
    val order = compareBy<Key> { !it.file.isDirectory }.then(comparator)
    return keys.sortedWith { a, b -> checkCancelled(); order.compare(a, b) }.map { it.file }
}

private fun modifiedMillis(value: String?): Long? = value?.toLongOrNull() ?: runCatching {
    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
}.getOrNull()
