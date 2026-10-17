package com.example.webdavmanager

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Written before every remote/local mutation; contains identities and paths, never credentials. */
internal class TransferJournal(context: Context, source: FileEntry, destination: String, targetKey: String, move: Boolean) {
    private val folder = File(context.filesDir, "transfer_journal")
    private val file = AtomicFile(File(folder, "${UUID.randomUUID()}.json"))
    private val state = JSONObject().put("source", fileEntryJson(source)).put("destination", destination)
        .put("targetKey", targetKey).put("move", move).put("phase", "planning")

    suspend fun record(phase: String, path: String = "") = withContext(Dispatchers.IO) {
        if (!folder.isDirectory && !folder.mkdirs()) throw java.io.IOException("Cannot create transfer journal")
        state.put("phase", phase).put("activePath", path)
        val stream = file.startWrite()
        try { stream.write(state.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    suspend fun complete() = withContext(Dispatchers.IO) { file.delete() }

    companion object {
        suspend fun recover(context: Context): List<FileOperationResult> = withContext(Dispatchers.IO) {
            File(context.filesDir, "transfer_journal").listFiles().orEmpty().filter { it.extension == "json" }.map { file ->
                try {
                    val data = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
                    FileOperationResult(fileEntryFromJson(data.getJSONObject("source")), "Needs review",
                        "Interrupted or incomplete task (${data.getString("phase")}). Destination: ${data.getString("destination")}; " +
                            "active item: ${data.optString("activePath")}. Check both folders before retrying; source deletion is never resumed automatically.")
                } catch (_: Exception) {
                    FileOperationResult(FileEntry(file.name, file.path, false, null, null, null, null), "Needs review",
                        "Task record could not be read. Check the source and destination before retrying.")
                }
            }
        }
        suspend fun acknowledge(context: Context) = withContext(Dispatchers.IO) {
            File(context.filesDir, "transfer_journal").listFiles().orEmpty().filter { it.extension == "json" }
                .forEach { AtomicFile(it).delete() }
        }
    }
}

internal fun fileEntryJson(file: FileEntry) = JSONObject().put("name", file.name).put("path", file.path)
    .put("directory", file.isDirectory).put("size", file.size).put("modified", file.lastModified)
    .put("etag", file.etag).put("mime", file.mimeType).put("display", file.displayName)

internal fun fileEntryFromJson(json: JSONObject) = FileEntry(json.getString("name"), json.getString("path"),
    json.getBoolean("directory"), if (json.isNull("size")) null else json.getLong("size"),
    json.optString("modified").takeIf { it.isNotEmpty() && it != "null" },
    json.optString("etag").takeIf { it.isNotEmpty() && it != "null" },
    json.optString("mime").takeIf { it.isNotEmpty() && it != "null" }, json.optString("display", json.getString("name")))
