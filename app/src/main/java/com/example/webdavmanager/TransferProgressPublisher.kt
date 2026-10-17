package com.example.webdavmanager

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

internal data class TransferProgress(val file: String, val phase: String, val bytes: Long, val total: Long?)

/** One collector per file; producers never launch a coroutine for each buffer. */
internal class TransferProgressPublisher(scope: CoroutineScope,
    private val nowNanos: () -> Long = System::nanoTime,
    private val publish: (TransferProgress) -> Unit) {
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var latest: TransferProgress? = null
    private var closed = false
    private val job = scope.launch {
        var previous: TransferProgress? = null
        var due = 0L
        while (isActive) {
            if (signal.receiveCatching().isClosed) break
            var value = synchronized(lock) { latest } ?: continue
            while (previous?.let { it.file == value.file && it.phase == value.phase } == true) {
                val wait = due - nowNanos()
                if (wait <= 0) break
                val update = withTimeoutOrNull((wait / 1_000_000).coerceAtLeast(1)) { signal.receiveCatching() }
                if (update == null || update.isClosed) break
                value = synchronized(lock) { latest } ?: break
            }
            if (value != previous) publish(value)
            previous = value
            due = nowNanos() + 150_000_000L
        }
    }
    fun submit(file: String, phase: String, bytes: Long, total: Long?) = synchronized(lock) {
        if (!closed) {
            latest = TransferProgress(file, phase, bytes, total)
            signal.trySend(Unit)
        }
        Unit
    }
    /** Called on owner thread before advancing to the next file or clearing operation state. */
    fun finish() {
        val final = synchronized(lock) { closed = true; latest }
        job.cancel()
        signal.close()
        final?.let(publish)
    }
}
