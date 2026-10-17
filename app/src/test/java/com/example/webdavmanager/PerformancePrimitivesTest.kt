package com.example.webdavmanager

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class PerformancePrimitivesTest {
    @Test fun ordinarySavesCoalesceButFlushWaitsForActualWrite() = runTest {
        val writes = mutableListOf<Int>()
        val release = CompletableDeferred<Unit>()
        val writer = CoalescingStateWriter<Int>(backgroundScope, { value ->
            release.await(); writes += value
        }, { throw it })
        repeat(30) { writer.submit(it) }
        advanceTimeBy(250); runCurrent()
        assertTrue(writes.isEmpty())
        val barrier = async { writer.flush(30) }
        runCurrent()
        writer.submit(31)
        assertFalse(barrier.isCompleted)
        release.complete(Unit)
        runCurrent()
        barrier.await()
        assertEquals(listOf(29, 31), writes)
    }

    @Test fun failedFlushReportsFailureAndNextSaveCanRecover() = runTest {
        val errors = mutableListOf<Exception>()
        val writes = mutableListOf<Int>()
        val writer = CoalescingStateWriter<Int>(backgroundScope, {
            if (it == 1) throw IOException("disk unavailable") else writes += it
        }, errors::add)
        val first = async { runCatching { writer.flush(1) } }
        runCurrent()
        assertTrue(first.await().exceptionOrNull() is IOException)
        val second = async { writer.flush(2) }
        runCurrent(); second.await()
        assertEquals(1, errors.size)
        assertEquals(listOf(2), writes)
    }

    @Test fun progressIsBoundedAndFinalValueCannotUpdateNextFile() = runTest {
        val events = mutableListOf<TransferProgress>()
        val publisher = TransferProgressPublisher(backgroundScope, { testScheduler.currentTime * 1_000_000 }, events::add)
        publisher.submit("first", "read", 1, 10000)
        runCurrent()
        repeat(10000) { publisher.submit("first", "read", it.toLong(), 10000) }
        runCurrent()
        assertEquals(1, events.size)
        advanceTimeBy(150); runCurrent()
        assertEquals(9999L, events.last().bytes)
        publisher.submit("first", "verify", 0, 10000)
        runCurrent()
        assertEquals("verify", events.last().phase)
        publisher.submit("first", "verify", 10000, 10000)
        publisher.finish()
        val size = events.size
        publisher.submit("late", "read", 42, 42)
        advanceTimeBy(1000); runCurrent()
        assertEquals(size, events.size)
        assertEquals(10000L, events.last().bytes)
    }

    @Test fun streamingDigestMatchesContentAndSpaceQueriesUseBoundedBudget() = runTest {
        val data = ByteArray(9 * 1024 * 1024) { (it % 251).toByte() }
        val result = copyBounded(ByteArrayInputStream(data), discardOutput, Long.MAX_VALUE)
        val expected = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        assertEquals(data.size.toLong(), result.bytes)
        assertEquals(expected, result.sha256)
        var queries = 0
        val space = TransferSpaceBudget { queries++; TRANSFER_SPACE_RESERVE + 100 * 1024 * 1024 }
        repeat(144) { space.beforeWrite(64 * 1024) }
        assertEquals(3, queries) // Previously 144 filesystem calls for these buffers.
        val full = TransferSpaceBudget { TRANSFER_SPACE_RESERVE }
        try { full.beforeWrite(1); fail("Keep the reserve") } catch (_: IOException) { }
        try { copyBounded(ByteArrayInputStream(data), discardOutput, 4); fail("Bounded copy") } catch (_: IOException) { }
    }

    @Test fun optimizedSortingPreservesNullInvalidDateAndStableTies() {
        val files = listOf(
            FileEntry("b", "/b", false, null, "invalid", null, null),
            FileEntry("A", "/a", false, 2, "Wed, 21 Oct 2015 07:28:00 GMT", null, "text/plain"),
            FileEntry("a", "/a2", false, 2, "1445412480000", null, "text/plain"),
            FileEntry("folder", "/f", true, null, null, null, null))
        for (mode in FileSortMode.entries) assertEquals("/f", sortFiles(files, mode).first().path)
        assertEquals(listOf("/f", "/a", "/a2", "/b"), sortFiles(files, FileSortMode.MODIFIED).map { it.path })
        var checks = 0
        try { sortFiles(files, FileSortMode.NAME) { if (++checks == 2) throw CancellationException() }; fail("Cancel sorting") }
        catch (_: CancellationException) { }
    }
}
