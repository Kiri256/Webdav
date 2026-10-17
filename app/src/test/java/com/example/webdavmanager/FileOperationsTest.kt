package com.example.webdavmanager

import org.junit.Assert.*
import org.junit.Test

class FileOperationsTest {
    @Test fun searchUsesLiteralNamesAndIgnoresCase() {
        val files = listOf("PHOTO.JPG", "中文+图.jpg", "[notes].txt").map {
            FileEntry(it, "/$it", false, null, null, null, null)
        }
        assertEquals(listOf(files[0]), filterFiles(files, "photo"))
        assertEquals(listOf(files[1]), filterFiles(files, "中文+"))
        assertEquals(listOf(files[2]), filterFiles(files, "["))
        assertEquals(files, filterFiles(files, ""))
    }

    @Test fun sortingKeepsFoldersFirstAndOrdersBySelectedField() {
        val files = listOf(
            FileEntry("large.txt", "/large.txt", false, 20, "200", null, "text/plain"),
            FileEntry("folder", "/folder", true, null, null, null, null),
            FileEntry("small.txt", "/small.txt", false, 2, "100", null, "text/plain"),
        )
        assertEquals(listOf("folder", "small.txt", "large.txt"), sortFiles(files, FileSortMode.SIZE).map { it.name })
        assertEquals(listOf("folder", "small.txt", "large.txt"), sortFiles(files, FileSortMode.MODIFIED).map { it.name })
    }
}
