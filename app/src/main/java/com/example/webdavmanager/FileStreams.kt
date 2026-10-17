package com.example.webdavmanager

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

internal const val TRANSFER_SPACE_RESERVE = 32L * 1024 * 1024
internal data class StreamDigest(val bytes: Long, val sha256: String)
internal val discardOutput = object : OutputStream() {
    override fun write(value: Int) = Unit
    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
}

/** Refresh on a bounded byte budget; keep the reserve and recheck earlier near exhaustion. */
internal class TransferSpaceBudget(private val available: () -> Long) {
    private var budget = 0L
    fun beforeWrite(bytes: Int) {
        if (budget < bytes) {
            val free = available()
            requireTransferSpace(free, bytes.toLong())
            budget = minOf(4L * 1024 * 1024, free - TRANSFER_SPACE_RESERVE)
        }
        budget -= bytes
    }
}

internal fun requireTransferSpace(available: Long, bytes: Long, copies: Int = 1) {
    require(bytes >= 0 && copies > 0)
    if (available < TRANSFER_SPACE_RESERVE || bytes > (available - TRANSFER_SPACE_RESERVE) / copies)
        throw IOException("Insufficient free space for transfer and verification. Free storage space and retry.")
}

internal suspend fun copyBounded(
    input: InputStream,
    output: OutputStream,
    limit: Long,
    storage: File? = null,
    onProgress: (Long) -> Unit = {},
    computeDigest: Boolean = true,
): StreamDigest {
    val buffer = ByteArray(65536)
    val digest = if (computeDigest) MessageDigest.getInstance("SHA-256") else null
    val space = storage?.let { TransferSpaceBudget { it.usableSpace } }
    var received = 0L
    var lastReported = 0L
    while (true) {
        coroutineContext.ensureActive()
        val bytes = input.read(buffer)
        if (bytes < 0) break
        received += bytes
        if (received > limit) throw IOException("File exceeds transfer capacity")
        space?.beforeWrite(bytes)
        output.write(buffer, 0, bytes)
        digest?.update(buffer, 0, bytes)
        if (received - lastReported >= 512L * 1024) {
            onProgress(received)
            lastReported = received
        }
    }
    if (received != lastReported) onProgress(received)
    return StreamDigest(received, digest?.digest()?.joinToString("") { "%02x".format(it) }.orEmpty())
}

internal suspend fun fileDigest(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
