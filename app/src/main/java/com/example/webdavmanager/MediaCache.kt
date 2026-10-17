package com.example.webdavmanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.toBitmap
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.SuccessResult
import coil.request.CachePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@OptIn(coil.annotation.ExperimentalCoilApi::class)
internal object ThumbnailCache {
    private var cache: DiskCache? = null
    private val permits = Semaphore(3)
    private val stripes = Array(32) { Mutex() }

    @Synchronized
    private fun disk(context: Context): DiskCache = cache ?: DiskCache.Builder()
        .directory(File(context.applicationContext.cacheDir, "thumbnails"))
        .maxSizeBytes(100L * 1024 * 1024).build().also { cache = it }

    suspend fun load(context: Context, client: WebDavClient, file: FileEntry): Bitmap = withContext(Dispatchers.IO) {
        val key = "thumb:${imageCacheKey(client.connection.id, file)}"
        val memory = context.imageLoader.memoryCache
        memory?.get(MemoryCache.Key(key))?.bitmap?.let { return@withContext it }
        stripes[(key.hashCode() and Int.MAX_VALUE) % stripes.size].withLock {
            memory?.get(MemoryCache.Key(key))?.bitmap?.let { return@withLock it }
            permits.withPermit {
                disk(context).openSnapshot(key)?.use { snapshot ->
                    BitmapFactory.decodeFile(snapshot.data.toFile().path)?.let {
                        memory?.set(MemoryCache.Key(key), MemoryCache.Value(it))
                        return@withPermit it
                    }
                }
                val result = context.imageLoader.execute(client.imageRequest(context, file).newBuilder()
                    .size(320, 320).memoryCacheKey(key).diskCachePolicy(CachePolicy.DISABLED)
                    .decoderFactory(coil.decode.BitmapFactoryDecoder.Factory()).build())
                val decoded = (result as? SuccessResult)?.drawable?.toBitmap()
                    ?: throw IOException("Image thumbnail could not be loaded")
                val ratio = 320f / maxOf(decoded.width, decoded.height)
                val bitmap = if (ratio < 1f) Bitmap.createScaledBitmap(decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1), (decoded.height * ratio).toInt().coerceAtLeast(1), true) else decoded
                val editor = disk(context).openEditor(key)
                if (editor != null) {
                    try {
                        editor.data.toFile().outputStream().use { output ->
                            val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                            if (!bitmap.compress(format, 82, output)) throw IOException("Thumbnail encoding failed")
                        }
                        editor.commit()
                    } catch (e: Exception) {
                        editor.abort()
                        throw e
                    }
                }
                memory?.set(MemoryCache.Key(key), MemoryCache.Value(bitmap))
                bitmap
            }
        }
    }

    suspend fun evict(context: Context, serverId: String, file: FileEntry) = withContext(Dispatchers.IO) {
        val base = imageCacheKey(serverId, file)
        val key = "thumb:$base"
        stripes[(key.hashCode() and Int.MAX_VALUE) % stripes.size].withLock {
            disk(context).remove(key)
            context.imageLoader.memoryCache?.remove(MemoryCache.Key(key))
            context.imageLoader.memoryCache?.remove(MemoryCache.Key("full:$base"))
            context.imageLoader.diskCache?.remove("full:$base")
        }
    }
}

