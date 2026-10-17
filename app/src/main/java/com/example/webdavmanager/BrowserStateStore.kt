package com.example.webdavmanager

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.browserStateDataStore by preferencesDataStore("browser_state")

internal data class BrowserState(val local: Boolean, val server: String?, val directory: String,
    val localDirectory: String?, val localParents: List<String>, val grid: Boolean, val clipboard: FileClipboard?,
    val activeTab: Int = if (local) 0 else 2, val tabs: List<BrowserTabState> = emptyList())

internal data class BrowserTabState(val directory: String? = null, val parents: List<String> = emptyList(),
    val grid: Boolean = true, val search: String = "", val sort: String = "NAME")

internal class BrowserStateStore(private val context: Context) {
    private val key = stringPreferencesKey("state")
    suspend fun clear() { context.browserStateDataStore.edit { it.remove(key) } }
    suspend fun load(): BrowserState? = withContext(Dispatchers.IO) {
        val raw = context.browserStateDataStore.data.first()[key] ?: return@withContext null
        try {
            val json = JSONObject(raw)
            val clip = json.optJSONObject("clipboard")?.let { value ->
                val files = value.getJSONArray("files")
                FileClipboard((0 until files.length()).map { fileEntryFromJson(files.getJSONObject(it)) },
                    value.getString("directory"), value.getString("source"), value.getBoolean("local"), value.getBoolean("move"))
            }
            val parents = json.getJSONArray("parents")
            BrowserState(json.getBoolean("local"), json.optString("server").takeIf { it.isNotEmpty() },
                json.getString("directory"), json.optString("localDirectory").takeIf { it.isNotEmpty() },
                (0 until parents.length()).map { parents.getString(it) }, json.optBoolean("grid", true), clip,
                json.optInt("activeTab", if (json.getBoolean("local")) 0 else 2).coerceIn(0, 3),
                json.optJSONArray("tabs")?.let { tabs -> (0 until minOf(tabs.length(), 4)).map { index ->
                    val tab = tabs.getJSONObject(index)
                    val ancestors = tab.optJSONArray("parents") ?: JSONArray()
                    BrowserTabState(tab.optString("directory").takeIf { it.isNotEmpty() },
                        (0 until ancestors.length()).map { ancestors.getString(it) }, tab.optBoolean("grid", true),
                        tab.optString("search"), tab.optString("sort", "NAME"))
                } } ?: emptyList())
        } catch (_: Exception) { null }
    }
    suspend fun save(state: BrowserState) = withContext(Dispatchers.IO) {
        val json = JSONObject().put("local", state.local).put("server", state.server.orEmpty())
            .put("directory", state.directory).put("localDirectory", state.localDirectory.orEmpty())
            .put("parents", JSONArray(state.localParents)).put("grid", state.grid)
            .put("activeTab", state.activeTab).put("tabs", JSONArray().apply {
                state.tabs.forEach { tab -> put(JSONObject().put("directory", tab.directory.orEmpty())
                    .put("parents", JSONArray(tab.parents)).put("grid", tab.grid)
                    .put("search", tab.search).put("sort", tab.sort)) }
            })
        state.clipboard?.let { clip -> json.put("clipboard", JSONObject().put("directory", clip.directory)
            .put("source", clip.sourceKey).put("local", clip.local).put("move", clip.move)
            .put("files", JSONArray().apply { clip.files.forEach { put(fileEntryJson(it)) } })) }
        context.browserStateDataStore.edit { it[key] = json.toString() }
        Unit
    }
}
