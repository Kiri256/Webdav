package com.example.webdavmanager

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.system.measureNanoTime

/** Opt-in probes report measurements, never use timing as a functional assertion. */
@RunWith(AndroidJUnit4::class)
class PerformanceProbeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("performance") == "true")
    private fun report(label: String, values: List<Long>) {
        val sorted = values.sorted()
        Log.i("PerformanceProbe", "$label n=${values.size} median_ms=${sorted[sorted.size / 2] / 1e6} p95_ms=${sorted[(sorted.size * .95).toInt().coerceAtMost(sorted.lastIndex)] / 1e6}")
    }
    private fun legacySort(files: List<FileEntry>): List<FileEntry> {
        fun modified(value: String?) = value?.toLongOrNull() ?: runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
        return files.sortedWith(compareBy<FileEntry> { !it.isDirectory }
            .thenBy { modified(it.lastModified) == null }.thenBy { modified(it.lastModified) ?: 0 }
            .thenBy { it.name.lowercase() })
    }
    @Test fun compareLegacyAndOptimizedDateSorting() {
        enabled()
        for (size in listOf(1000, 10000)) {
            val files = (0 until size).map {
                FileEntry("item-$it", "/$it", it % 10 == 0, it.toLong(),
                    "Wed, 21 Oct 2015 07:${(it % 60).toString().padStart(2, '0')}:00 GMT", null, null)
            }.shuffled(kotlin.random.Random(42))
            repeat(5) { legacySort(files); sortFiles(files, FileSortMode.MODIFIED) }
            assertEquals(legacySort(files), sortFiles(files, FileSortMode.MODIFIED))
            val old = mutableListOf<Long>(); val optimized = mutableListOf<Long>()
            repeat(20) { iteration ->
                if (iteration % 2 == 0) {
                    old += measureNanoTime { legacySort(files) }
                    optimized += measureNanoTime { sortFiles(files, FileSortMode.MODIFIED) }
                } else {
                    optimized += measureNanoTime { sortFiles(files, FileSortMode.MODIFIED) }
                    old += measureNanoTime { legacySort(files) }
                }
            }
            report("date_sort_${size}_legacy", old)
            report("date_sort_${size}_optimized", optimized)
        }
    }

    @Test fun twoGiBLocalCopyVerifiesWithSingleStagingFile(): Unit = runBlocking {
        enabled()
        val root = File(context.cacheDir, "performance-${UUID.randomUUID()}").apply { mkdir() }
        try {
            val bytes = 2L * 1024 * 1024 * 1024
            assumeTrue(root.usableSpace > bytes * 2 + TRANSFER_SPACE_RESERVE * 2)
            val canonicalRoot = root.canonicalFile
            val source = File(canonicalRoot, "source.bin")
            java.io.RandomAccessFile(source, "rw").use { it.setLength(bytes); it.seek(bytes - 3); it.write(byteArrayOf(7, 8, 9)) }
            // DirectFiles deliberately rejects symlink traversal; use the
            // canonical cache path in the probe just as production does.
            val storage = LocalStorage(context, Uri.fromFile(canonicalRoot))
            var maxStageFiles = 0
            val elapsed = measureNanoTime {
                storage.transfer(DirectFiles.entry(source), storage.root.toString(), storage.root.toString(), "copy.bin", false) { _, _, _, _ ->
                    val stages = File(context.cacheDir, "file_transfers").listFiles().orEmpty()
                    maxStageFiles = maxOf(maxStageFiles, stages.sumOf { it.listFiles().orEmpty().size })
                }
            }
            assertEquals(bytes, File(root, "copy.bin").length())
            assertEquals(fileDigest(source), fileDigest(File(root, "copy.bin")))
            assertEquals(1, maxStageFiles)
            assertTrue(File(context.cacheDir, "file_transfers").listFiles().orEmpty().isEmpty())
            Log.i("PerformanceProbe", "local_copy_2GiB elapsed_ms=${elapsed / 1e6} max_staging_files=$maxStageFiles")
        } finally { root.deleteRecursively() }
    }
}
