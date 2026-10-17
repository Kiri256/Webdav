package com.example.webdavmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onStop() {
        androidx.lifecycle.ViewModelProvider(this)[BrowserViewModel::class.java].flushBrowserState()
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        setContent {
            MaterialTheme {
                WebDavApp()
            }
        }
    }

}

@Composable
private fun WebDavApp() {
    val model: BrowserViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            model.starting -> CenterBox { CircularProgressIndicator() }
            model.localMode -> LocalBrowserScreen(model)
            model.editing || model.connection == null -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { ConnectionScreen(model) }
                StorageSwitcher(model)
            }
            else -> BrowserScreen(model)
        }
        ExternalOpenUi(model)
    }
}

@Composable
private fun ConnectionScreen(model: BrowserViewModel) {
    val initial = remember { WebDavConnection(UUID.randomUUID().toString(), "WebDAV", "", "", "", "/") }
    val draft = model.draft ?: initial
    BackHandler(enabled = model.connection != null && !model.connecting) { model.cancelEditing() }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("WebDAV Manager", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(draft.name, { model.updateDraft(draft.copy(name = it)) },
            label = { Text("Name") }, enabled = !model.connecting, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.baseUrl, { model.updateDraft(draft.copy(baseUrl = it)) },
            label = { Text("Server URL") }, enabled = !model.connecting, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.username, { model.updateDraft(draft.copy(username = it)) },
            label = { Text("Username") }, enabled = !model.connecting, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.password, { model.updateDraft(draft.copy(password = it)) },
            label = { Text("Password") }, enabled = !model.connecting, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.rootPath, { model.updateDraft(draft.copy(rootPath = it)) },
            label = { Text("Root path") }, enabled = !model.connecting, singleLine = true, modifier = Modifier.fillMaxWidth())
        model.connectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        model.connectionMessage?.let { Text(it) }
        if (model.connecting) CircularProgressIndicator()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(enabled = !model.connecting && draft.baseUrl.isNotBlank(),
                onClick = { model.connect(draft.copy(baseUrl = draft.baseUrl.trim(), rootPath = draft.rootPath.trim()), testOnly = true) }) {
                Text("Test connection")
            }
            Button(enabled = !model.connecting && draft.baseUrl.isNotBlank(),
                onClick = { model.connect(draft.copy(baseUrl = draft.baseUrl.trim(), rootPath = draft.rootPath.trim())) }) {
                Text("Connect")
            }
        }
        if (model.connection != null) TextButton(enabled = !model.connecting, onClick = model::cancelEditing) { Text("Cancel") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(model: BrowserViewModel) {
    val connection = model.connection ?: return
    val client = model.client ?: return
    val entries = model.entries
    val visibleEntries = model.visibleFiles
    val imageEntries = entries.filter { it.isImage() }
    BackHandler(enabled = model.selectionMode || model.videoPath != null || model.audioPath != null || model.imagePath != null || model.path != normalizePath(connection.rootPath)) {
        when {
            model.operationBusy -> Unit
            model.selectionMode -> model.clearSelection()
            model.videoPath != null || model.audioPath != null || model.imagePath != null -> model.closeMedia()
            else -> model.navigate(parentPath(model.path))
        }
    }
    model.textPath?.let { selected ->
        TextEditorScreen(entries.firstOrNull { it.path == selected }?.name ?: selected.substringAfterLast('/'), model)
        return
    }
    model.audioPath?.let { selected ->
        entries.firstOrNull { it.path == selected }?.let {
            VideoPlayerScreen(it, client, model)
            return
        }
    }
    model.videoPath?.let { selected ->
        entries.firstOrNull { it.path == selected }?.let {
            VideoPlayerScreen(it, client, model)
            return
        }
    }
    model.imagePath?.let { selected ->
        imageEntries.firstOrNull { it.path == selected }?.let {
            ImageViewerScreen(imageEntries, it, client, model)
            return
        }
    }
    Scaffold(
        topBar = {
            BrowserToolbar(model)
        },
        bottomBar = { Column { FileActionsBar(model); FileSelectionBar(model); StorageSwitcher(model) } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (model.loading && entries.isNotEmpty()) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            if (model.error != null && entries.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = model::refresh) { Text("Retry") }
                TextButton(onClick = model::editConnection) { Text("Edit connection") }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                model.loading && entries.isEmpty() -> CenterBox { CircularProgressIndicator() }
                model.error != null && entries.isEmpty() -> CenterBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(model.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        Button(onClick = model::refresh) { Text("Retry") }
                        TextButton(onClick = model::editConnection) { Text("Edit connection") }
                        TextButton(onClick = { model.navigate(client.paths.rootPath) }) { Text("Server root") }
                    }
                }
                visibleEntries.isEmpty() -> CenterBox { BrowserEmptyState(model) }
                model.grid -> FileGrid(client, visibleEntries, model)
                else -> FileList(client, visibleEntries, model)
            }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FileGrid(client: WebDavClient, entries: List<FileEntry>, model: BrowserViewModel) {
    LazyVerticalGrid(
        state = folderGridState(model, entries),
        columns = GridCells.Adaptive(128.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(entries, key = { it.path }) { file ->
            Card(
                modifier = Modifier
                    .height(154.dp)
                    .combinedClickable(enabled = !model.actionsBusy, onLongClickLabel = "Select file", onClickLabel = "Open file or toggle selection", onLongClick = { model.toggleSelection(file) },
                        onClick = { if (model.selectionMode) model.toggleSelection(file) else model.open(file) }),
                shape = MaterialTheme.shapes.small,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        file.isDirectory -> Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(44.dp))
                        file.isImage() -> ThumbnailImage(client, file, Modifier.fillMaxSize())
                        file.isVideo() -> Icon(Icons.Outlined.Movie, contentDescription = null, modifier = Modifier.size(44.dp))
                        file.isAudio() -> Icon(Icons.Outlined.AudioFile, contentDescription = null, modifier = Modifier.size(44.dp))
                        file.isText() -> Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(44.dp))
                        else -> Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(44.dp))
                    }
                    Box(Modifier.align(Alignment.TopStart)) { FileSelectionCheckbox(file, model) }
                    Box(Modifier.align(Alignment.TopEnd)) { FileActionsMenu(file, model) }
                }
                Text(
                    text = file.name,
                    modifier = Modifier.padding(8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FileList(client: WebDavClient, entries: List<FileEntry>, model: BrowserViewModel) {
    LazyColumn(state = folderListState(model, entries, "list"), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(entries, key = { it.path }) { file ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(enabled = !model.actionsBusy, onLongClickLabel = "Select file", onClickLabel = "Open file or toggle selection", onLongClick = { model.toggleSelection(file) },
                        onClick = { if (model.selectionMode) model.toggleSelection(file) else model.open(file) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileSelectionCheckbox(file, model)
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        file.isDirectory -> Icon(Icons.Outlined.Folder, contentDescription = null)
                        file.isImage() -> ThumbnailImage(client, file, Modifier.fillMaxSize())
                        file.isVideo() -> Icon(Icons.Outlined.Movie, contentDescription = null)
                        file.isAudio() -> Icon(Icons.Outlined.AudioFile, contentDescription = null)
                        file.isText() -> Icon(Icons.Outlined.Description, contentDescription = null)
                        else -> Icon(Icons.Outlined.Image, contentDescription = null)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(file.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FileActionsMenu(file, model)
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content()
    }
}

private fun FileEntry.subtitle(): String {
    if (isDirectory) return "Folder"
    val sizeText = size?.let { formatSize(it) } ?: "Unknown size"
    return listOfNotNull(sizeText, lastModified?.let { simplifyDate(it) }).joinToString("  ")
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}

private fun simplifyDate(value: String): String {
    val input = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
    val output = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    return runCatching { output.format(input.parse(value) ?: Date()) }.getOrDefault(value)
}
