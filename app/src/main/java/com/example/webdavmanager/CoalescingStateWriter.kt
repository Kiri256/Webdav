package com.example.webdavmanager

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Accessed on the owner thread. Flush waiters are never conflated with ordinary saves. */
internal class CoalescingStateWriter<T>(
    scope: CoroutineScope,
    private val save: suspend (T) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var revision = 0L
    private var pending: Pair<Long, T>? = null
    private val waiters = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
    init {
        scope.launch {
            try {
                for (ignored in signal) {
                    if (waiters.isEmpty()) delay(200)
                    val writing = pending ?: continue
                    pending = null
                    try {
                        save(writing.second)
                        finish(writing.first, null)
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { finish(writing.first, error); onError(error) }
                }
            } finally {
                waiters.forEach { it.second.cancel() }
                waiters.clear()
                signal.close()
            }
        }
    }
    fun submit(value: T) {
        pending = ++revision to value
        signal.trySend(Unit)
    }
    suspend fun flush(value: T) {
        submit(value)
        val done = CompletableDeferred<Unit>()
        waiters += revision to done
        try { done.await() } finally { waiters.removeAll { it.second === done } }
    }
    private fun finish(version: Long, error: Exception?) {
        val ready = waiters.filter { it.first <= version }
        waiters.removeAll { it.first <= version }
        ready.forEach { if (error == null) it.second.complete(Unit) else it.second.completeExceptionally(error) }
    }
}
