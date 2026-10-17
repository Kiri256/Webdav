package com.example.webdavmanager

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LocalBrowserScreen(model: BrowserViewModel) {
    val context = LocalContext.current
    val entries = model.localEntries
    val visibleFiles = model.visibleFiles
    val selected = entries.firstOrNull { it.path == (model.videoPath ?: model.audioPath) }
    BackHandler(enabled = model.textPath == null && (model.selectionMode || model.imagePath != null || model.videoPath != null || model.audioPath != null || model.localParents.isNotEmpty())) {
        if (!model.localBusy && !model.operationBusy) when {
            model.selectionMode -> model.clearSelection()
            model.imagePath != null || model.videoPath != null || model.audioPath != null -> model.closeMedia()
            model.localParents.isNotEmpty() -> model.localUp()
            else -> Unit
        }
    }
    model.textPath?.let { path ->
        TextEditorScreen(entries.firstOrNull { it.path == path }?.name ?: "Local text", model)
        return
    }
    if (selected != null) {
        VideoPlayerScreen(selected, null, model, Uri.parse(selected.path))
        return
    }
    model.imagePath?.let { path ->
        entries.firstOrNull { it.path == path }?.let {
            ImageViewerScreen(entries.filter { file -> file.isImage() }, it, null, model)
            return
        }
    }
    var operation by rememberSaveable { mutableStateOf<String?>(null) }
    var targetPath by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    val target = entries.firstOrNull { it.path == targetPath }
    if (operation != null) AlertDialog(onDismissRequest = { operation = null },
        title = { Text(when (operation) {
            "delete" -> "Delete permanently?"
            "rename" -> "Rename"
            else -> "Rename"
        }) },
        text = {
            if (operation == "delete") Text("${target?.name.orEmpty()}\n" +
                if (target?.isDirectory == true) "This deletes the folder and everything inside it." else "This file will be permanently deleted.")
            else OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
        }, confirmButton = { TextButton(enabled = !model.actionsBusy &&
            (operation == "delete" || name.isNotBlank()), onClick = {
            when (operation) {
                "delete" -> target?.let(model::deleteLocal)
                "rename" -> target?.let { model.renameLocal(it, name) }
            }
            operation = null
        }) { Text(if (operation == "delete") "Delete" else "Confirm") } },
        dismissButton = { TextButton(onClick = { operation = null }) { Text("Cancel") } })
    Scaffold(topBar = {
        BrowserToolbar(model)
    }, bottomBar = { Column { FileActionsBar(model); FileSelectionBar(model); StorageSwitcher(model) } }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp)) {
            if (model.localStorage == null) {
                Text("Loading filesystem…")
            }
            model.localError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Row { TextButton(onClick = model::refreshLocal) { Text("Retry") }; TextButton(onClick = model::openInternalStorage) { Text("Internal storage") } }
            }
            if (model.localLoading || model.localBusy) CircularProgressIndicator()
            if (!model.localLoading && visibleFiles.isEmpty() && model.localStorage != null)
                BrowserEmptyState(model)
            if (model.grid) LazyVerticalGrid(
                columns = GridCells.Adaptive(128.dp),
                modifier = Modifier.weight(1f),
                state = folderGridState(model, visibleFiles),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                gridItems(visibleFiles, key = { it.path }) { file ->
                    Card(
                        modifier = Modifier.fillMaxWidth().combinedClickable(enabled = !model.actionsBusy, onLongClickLabel = "Select file", onClickLabel = "Open file or toggle selection",
                            onLongClick = { model.toggleSelection(file) },
                            onClick = { if (model.selectionMode) model.toggleSelection(file) else model.open(file) }),
                        shape = MaterialTheme.shapes.small,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(Modifier.fillMaxWidth().size(110.dp), contentAlignment = Alignment.Center) {
                            if (file.isImage()) LocalImage(file, Modifier.fillMaxSize(), thumbnail = true)
                            else Icon(if (file.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                                null, Modifier.size(40.dp))
                            Box(Modifier.align(Alignment.TopStart)) { FileSelectionCheckbox(file, model) }
                            Box(Modifier.align(Alignment.TopEnd)) {
                                FileActionsMenu(file, model,
                                    onRename = { targetPath = file.path; name = file.name; operation = "rename" },
                                    onDelete = { targetPath = file.path; operation = "delete" })
                            }
                        }
                        Text(file.name, Modifier.padding(horizontal = 8.dp, vertical = 6.dp), maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else LazyColumn(Modifier.weight(1f), state = folderListState(model, visibleFiles, "list")) {
                items(visibleFiles, key = { it.path }) { file ->
                    Row(Modifier.fillMaxWidth().combinedClickable(enabled = !model.actionsBusy, onLongClickLabel = "Select file", onClickLabel = "Open file or toggle selection",
                        onLongClick = { model.toggleSelection(file) },
                        onClick = { if (model.selectionMode) model.toggleSelection(file) else model.open(file) })
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FileSelectionCheckbox(file, model)
                        if (file.isImage()) LocalImage(file, Modifier.size(52.dp), thumbnail = true)
                        else Icon(if (file.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                            null, Modifier.size(40.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(file.name)
                            Text(if (file.isDirectory) "Folder" else "${file.size ?: 0} bytes", style = MaterialTheme.typography.bodySmall)
                        }
                        FileActionsMenu(file, model,
                            onRename = { targetPath = file.path; name = file.name; operation = "rename" },
                            onDelete = { targetPath = file.path; operation = "delete" })
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalImage(file: FileEntry, modifier: Modifier, thumbnail: Boolean = false) {
    val context = LocalContext.current
    val request = remember(file.path, file.lastModified, file.size, thumbnail) {
        ImageRequest.Builder(context).data(Uri.parse(file.path))
            .memoryCacheKey(localImageCacheKey(file, thumbnail))
            .diskCachePolicy(coil.request.CachePolicy.DISABLED)
            .apply { if (thumbnail) { size(320, 320); decoderFactory(coil.decode.BitmapFactoryDecoder.Factory()) } }.build()
    }
    AsyncImage(request, file.name, modifier, contentScale = if (thumbnail) ContentScale.Crop else ContentScale.Fit)
}

