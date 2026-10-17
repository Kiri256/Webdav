package com.example.webdavmanager

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class TransferSpaceTest {
    @Test fun filesLargerThan512MiBAreAllowedWhenSpaceIsAvailable() = runBlocking {
        val size = 513L * 1024 * 1024
        requireTransferSpace(2L * 1024 * 1024 * 1024, size, 3)
        var remaining = size
        var written = 0L
        var reported = 0L
        val input = object : InputStream() {
            override fun read() = if (remaining-- > 0) 0 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                val count = minOf(remaining, length.toLong()).toInt()
                remaining -= count
                return count
            }
        }
        val output = object : OutputStream() {
            override fun write(value: Int) { written++ }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { written += length }
        }
        copyBounded(input, output, Long.MAX_VALUE, onProgress = { reported = it })
        assertEquals(size, written)
        assertEquals(size, reported)
    }

    @Test fun spaceChecksReserveVerificationRoomAndHandleLargeSizesWithoutOverflow() {
        for ((available, bytes, copies) in listOf(Triple(TRANSFER_SPACE_RESERVE - 1, 0L, 1),
            Triple(1024L * 1024 * 1024, 600L * 1024 * 1024, 2), Triple(Long.MAX_VALUE, Long.MAX_VALUE, 3))) {
            try { requireTransferSpace(available, bytes, copies); fail("Insufficient space") }
            catch (error: IOException) { assertTrue(error.message!!.contains("free space")) }
        }
        requireTransferSpace(TRANSFER_SPACE_RESERVE + 9, 3, 3)
    }
}
