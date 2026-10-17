package com.example.webdavmanager

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A bounded, explicit download fallback, separate from mpv's in-memory read-ahead. */
internal object OfflinePlayback {
    const val LIMIT = 1024L * 1024 * 1024
    suspend fun download(context: Context, client: WebDavClient, file: FileEntry, progress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        require((file.size ?: 0) <= LIMIT) { "Local playback download is limited to 1 GiB" }
        val directory = File(context.cacheDir, "offline_video").apply { mkdirs() }
        directory.listFiles().orEmpty().forEach { check(it.delete()) { "Could not clear old playback download" } }
        requireTransferSpace(directory.usableSpace, file.size ?: LIMIT)
        val pending = File(directory, "${UUID.randomUUID()}.part")
        val target = File(directory, "${UUID.randomUUID()}.media")
        try {
            client.download(file, pending, LIMIT, progress)
            check(pending.length() > 0 && pending.renameTo(target)) { "Could not publish playback download" }
            target
        } finally { pending.delete() }
    }
}
