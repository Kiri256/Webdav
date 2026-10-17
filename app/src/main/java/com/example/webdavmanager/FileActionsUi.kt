package com.example.webdavmanager

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun FileActionsBar(model: BrowserViewModel) {
    var confirmPaste by rememberSaveable { mutableStateOf(false) }
    var showResults by rememberSaveable { mutableStateOf(false) }
    val clipboard = model.clipboard
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        model.browserNotice?.let { notice -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(notice, Modifier.weight(1f))
            TextButton(onClick = model::dismissBrowserNotice) { Text("Dismiss") }
        } }
        model.connectionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (model.searchQuery.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = model::openSearch, modifier = Modifier.weight(1f)) {
                    Text("Filter: ${model.searchQuery}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { model.setSearch("") }, enabled = !model.operationBusy) { Text("Clear") }
            }
        }
        if (clipboard != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${if (clipboard.move) "Move" else "Copy"} · ${clipboard.files.size} item(s)",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(enabled = model.canPaste, onClick = { confirmPaste = true }) { Text("Paste") }
            TextButton(enabled = !model.operationBusy, onClick = model::clearClipboard) { Text("Clear clipboard") }
        }
        if (model.operationBusy && model.operationPhase.isNotEmpty()) {
            val total = model.operationBytesTotal
            if (total != null && total > 0L) {
                LinearProgressIndicator(
                    progress = { (model.operationBytes.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                val bytes = if (total != null && total > 0L)
                    " · ${formatBytes(model.operationBytes)} / ${formatBytes(total)}"
                else if (model.operationBytes > 0L) " · ${formatBytes(model.operationBytes)}"
                else ""
                Text("${model.operationProgress}/${model.operationTotal} items · ${model.operationPhase} · ${model.operationFile}$bytes",
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = model::cancelFileOperation) { Text("Cancel transfer") }
            }
        }
        if (model.operationResults.isNotEmpty() && !model.operationBusy) {
            TextButton(onClick = { model.showProperties(emptyList()); showResults = true }) { Text("Transfer results (${model.operationResults.size})") }
        }
    }
    if (confirmPaste && clipboard != null) AlertDialog(onDismissRequest = { confirmPaste = false },
        title = { Text(if (clipboard.move) "Move here?" else "Copy here?") },
        text = {
            Column {
                Text("${clipboard.files.size} item(s) → ${displayFileLocation(if (model.localMode) model.localDirectory.orEmpty() else model.path)}")
                Text("Existing files will not be overwritten and will be skipped.")
                if (clipboard.move && !clipboard.local) Text("Cancelling stops waiting; the server may finish an active MOVE. Check both folders before retrying.")
            }
        }, confirmButton = { TextButton(enabled = model.canPaste, onClick = {
            confirmPaste = false
            model.pasteFiles()
        }) { Text("Confirm paste") } },
        dismissButton = { TextButton(onClick = { confirmPaste = false }) { Text("Cancel") } })
    FileDetailsDialogs(model, showResults) { showResults = it }
    if (model.deleteTargets.isNotEmpty()) AlertDialog(onDismissRequest = model::dismissDelete,
        title = { Text("Delete selected files permanently?") },
        text = { LazyColumn(Modifier.heightIn(max = 320.dp)) {
            item { Text("${model.deleteTargets.size} item(s). Selected folders and all their contents will be deleted.") }
            items(model.deleteTargets, key = { it.path }) { Text(it.name) }
        } }, confirmButton = { TextButton(enabled = !model.actionsBusy, onClick = model::confirmDelete) { Text("Confirm delete") } },
        dismissButton = { TextButton(onClick = model::dismissDelete) { Text("Cancel") } })
}

@Composable
internal fun StorageSwitcher(model: BrowserViewModel) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
        listOf("Local 1", "Local 2", "WebDAV 1", "WebDAV 2").forEachIndexed { index, label ->
            SegmentedButton(
                selected = model.activeTab == index,
                onClick = { model.selectTab(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = 4),
                enabled = !model.operationBusy && !model.connecting && !model.textBusy && !model.textDirty && !model.localBusy && !model.deleting && !model.externalBusy,
                icon = {},
            ) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
internal fun BrowserSearchButton(model: BrowserViewModel) {
    IconButton(onClick = model::openSearch, enabled = !model.operationBusy) {
        Icon(Icons.Outlined.Search, "Search")
    }
    if (model.searchDialogVisible) {
        val focus = remember { FocusRequester() }
        AlertDialog(onDismissRequest = model::dismissSearch,
            title = { Text("Find files") },
            text = {
                Column {
                    OutlinedTextField(model.searchQuery, model::setSearch, label = { Text("Search this folder") },
                        singleLine = true, enabled = !model.operationBusy,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { model.dismissSearch() }))
                    Text("Filter by name. Subfolders are not searched.", style = MaterialTheme.typography.bodySmall)
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            },
            confirmButton = { TextButton(onClick = model::dismissSearch) { Text("Done") } },
            dismissButton = { TextButton(onClick = { model.setSearch("") }, enabled = !model.operationBusy) { Text("Clear") } })
    }
}

@Composable
internal fun BrowserSortButton(model: BrowserViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = !model.actionsBusy) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort") }
        DropdownMenu(expanded, { expanded = false }) {
            FileSortMode.entries.forEach { mode ->
                DropdownMenuItem(text = { Text(mode.label()) }, onClick = {
                    model.updateSortMode(mode)
                    expanded = false
                })
            }
        }
    }
}

@Composable
internal fun FileSelectionBar(model: BrowserViewModel) {
    if (!model.selectionMode) return
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${model.selectedPaths.size} selected")
            TextButton(enabled = !model.actionsBusy, onClick = model::selectVisible) { Text("Select shown") }
            TextButton(enabled = model.selectedPaths.isNotEmpty() && !model.actionsBusy,
                onClick = { model.stageFiles(false) }) { Text("Copy") }
            TextButton(enabled = model.selectedPaths.isNotEmpty() && !model.actionsBusy,
                onClick = { model.stageFiles(true) }) { Text("Move") }
            TextButton(enabled = model.selectedPaths.isNotEmpty() && !model.actionsBusy,
                onClick = { model.requestDelete() }) { Text("Delete") }
            TextButton(enabled = model.selectedPaths.isNotEmpty(),
                onClick = { model.showProperties(model.currentFiles.filter { it.path in model.selectedPaths }) }) {
                Text("Properties")
            }
            TextButton(enabled = !model.actionsBusy, onClick = model::clearSelection) { Text("Done") }
        }
    }
}

private fun FileSortMode.label() = when (this) {
    FileSortMode.NAME -> "Name"
    FileSortMode.SIZE -> "Size"
    FileSortMode.MODIFIED -> "Modified"
    FileSortMode.TYPE -> "Type"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    for (unit in units) {
        value /= 1024.0
        if (value < 1024.0 || unit == units.last()) return "%.1f %s".format(Locale.ROOT, value, unit)
    }
    return "$bytes B"
}

@Composable
private fun FileDetailsDialogs(model: BrowserViewModel, showResults: Boolean, setShowResults: (Boolean) -> Unit) {
    var checkedDestinations by remember(showResults) { mutableStateOf(false) }
    if (model.propertyFiles.isNotEmpty()) AlertDialog(onDismissRequest = model::closeProperties,
        title = { Text("Properties · ${model.propertyFiles.size} item(s)") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    val files = model.propertyFiles.filterNot { it.isDirectory }
                    Text("${model.propertyFiles.count { it.isDirectory }} folder(s), ${files.size} file(s)\n" +
                        "Known file sizes: ${files.mapNotNull { it.size }.sum()} bytes (folder contents not included)")
                }
                items(model.propertyFiles, key = { it.path }) { file ->
                    Column {
                        Text(file.name, style = MaterialTheme.typography.titleSmall)
                        Text("Location: ${displayFileLocation(file.path)}")
                        Text("Type: ${if (file.isDirectory) "Folder" else file.mimeType ?: "Unknown"}")
                        Text("Size: ${if (file.isDirectory) "Not calculated" else file.size?.let { "$it bytes" } ?: "Unknown"}")
                        Text("Modified: ${displayModified(file.lastModified)}")
                        if (file.etag != null) Text("Version: ${file.etag}")
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = model::closeProperties) { Text("Close") } })
    if (showResults && !model.operationBusy) AlertDialog(onDismissRequest = { setShowResults(false) },
        title = { Text("Transfer results") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { Text(model.operationResults.groupingBy { it.status }.eachCount().entries.joinToString(" · ") { "${it.key}: ${it.value}" }) }
                itemsIndexed(model.operationResults, key = { index, result -> "$index:${result.file.path}" }) { _, it ->
                    Text("${it.file.name} · ${it.status}\n${it.detail}", Modifier.padding(vertical = 8.dp))
                }
                item { Text("Failed or skipped items stay on the clipboard. Check the destination and paste again to retry.") }
                if (model.retryableResults.isNotEmpty()) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checkedDestinations, { checkedDestinations = it })
                        Text("I checked the source and destination for incomplete operations.")
                    }
                    TextButton(enabled = checkedDestinations && model.canRetryResults, onClick = { setShowResults(false); model.retryResults() }) { Text("Retry failed and remaining") }
                    if (!model.canRetryResults) Text("Return to the original destination folder to retry, or check both folders and paste the remaining clipboard items.")
                }
            }
        }, confirmButton = { TextButton(onClick = { setShowResults(false) }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { setShowResults(false); model.dismissOperationResults() }) { Text("Dismiss results") } })
}

@Composable
internal fun FileSelectionCheckbox(file: FileEntry, model: BrowserViewModel) {
    if (model.selectionMode) Checkbox(file.path in model.selectedPaths, { model.toggleSelection(file) },
        enabled = !model.actionsBusy, modifier = Modifier.semantics { contentDescription = "Select ${file.name}" })
}

@Composable
internal fun FileActionsMenu(file: FileEntry, model: BrowserViewModel, onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null) {
    var expanded by remember(file.path) { mutableStateOf(false) }
    Box {
        IconButton(enabled = !model.actionsBusy, onClick = { expanded = true }) { Icon(Icons.Outlined.MoreVert, "Actions for ${file.name}") }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("Select") }, onClick = { expanded = false; model.toggleSelection(file) })
            DropdownMenuItem(text = { Text("Copy") }, onClick = { expanded = false; model.stageFiles(false, listOf(file)) })
            DropdownMenuItem(text = { Text("Move") }, onClick = { expanded = false; model.stageFiles(true, listOf(file)) })
            DropdownMenuItem(text = { Text("Properties") }, onClick = { expanded = false; model.showProperties(listOf(file)) })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { expanded = false; if (onRename != null) onRename() else model.requestRename(file) })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { expanded = false; if (onDelete != null) onDelete() else model.requestDelete(listOf(file)) })
            if (!file.isDirectory) DropdownMenuItem(text = { Text("Open with other app") }, onClick = { expanded = false; model.requestExternalOpen(file) })
        }
    }
}

internal fun displayFileLocation(path: String): String = if (path.startsWith("content://")) {
    runCatching { DocumentsContract.getDocumentId(Uri.parse(path)) }.getOrDefault(path)
} else if (path.startsWith("file:")) Uri.parse(path).path.orEmpty() else path

private fun displayModified(value: String?): String = value?.toLongOrNull()?.takeIf { it > 0 }?.let {
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it))
} ?: value ?: "Unknown"
