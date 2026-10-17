package com.example.webdavmanager

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Two rows keep every toolbar action reachable on narrow displays and with large text. */
@Composable
internal fun BrowserToolbar(model: BrowserViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.refreshLocal() }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refreshLocal() }
    var create by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.statusBarsPadding().fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = { if (model.localMode) model.localUp() else model.navigate(parentPath(model.path)) },
                enabled = !model.actionsBusy && if (model.localMode) model.localParents.isNotEmpty() else model.path != model.client?.paths?.rootPath) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
            }
            TextButton(onClick = model::openPathEditor, enabled = !model.actionsBusy, modifier = Modifier.weight(1f)) {
                Text(displayFileLocation(if (model.localMode) model.localDirectory.orEmpty() else model.path), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            IconButton(onClick = { name = ""; create = "folder" }, enabled = !model.actionsBusy) { Icon(Icons.Outlined.CreateNewFolder, "New folder") }
            IconButton(onClick = { name = "notes.txt"; create = "text" }, enabled = !model.actionsBusy) { Icon(Icons.Outlined.NoteAdd, "New text") }
            if (model.localMode) IconButton(onClick = {
                if (android.os.Build.VERSION.SDK_INT >= 30) permission.launch(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                else legacy.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }, enabled = !model.actionsBusy) { Icon(Icons.Outlined.FolderOpen, "Storage access") }
            BrowserSearchButton(model)
            BrowserSortButton(model)
            IconButton(onClick = { if (model.localMode) model.refreshLocal() else model.refresh() }, enabled = !model.actionsBusy) { Icon(Icons.Outlined.Refresh, "Refresh") }
            IconButton(onClick = model::toggleGrid, enabled = !model.actionsBusy) { Icon(if (model.grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, "Toggle view") }
            if (!model.localMode) IconButton(onClick = model::editConnection, enabled = !model.actionsBusy) { Icon(Icons.Outlined.Settings, "Edit connection") }
        }
    }
    if (create != null) AlertDialog(onDismissRequest = { create = null }, title = { Text(if (create == "folder") "New folder" else "New text file") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank() && !model.actionsBusy, onClick = { model.createCurrent(name, create == "folder"); create = null }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { create = null }) { Text("Cancel") } })
    if (model.pathEditorVisible) {
        val focus = remember { FocusRequester() }
        AlertDialog(onDismissRequest = model::dismissPathEditor, title = { Text("Go to folder") }, text = {
            Column {
                OutlinedTextField(model.pathInput, model::updatePathInput, label = { Text("Directory path") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus), enabled = !model.pathJumpBusy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { model.jumpToPath() }))
                model.pathJumpError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (model.pathJumpBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LaunchedEffect(model.pathJumpBusy) {
                    if (!model.pathJumpBusy) {
                        withFrameNanos { }
                        runCatching { focus.requestFocus() }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = model::jumpToPath, enabled = !model.pathJumpBusy && model.pathInput.isNotBlank()) { Text("Go") } },
            dismissButton = { TextButton(onClick = model::dismissPathEditor) { Text("Cancel") } })
    }
    model.renameTarget?.let { file ->
        var next by remember(file.path) { mutableStateOf(file.name) }
        AlertDialog(onDismissRequest = model::dismissRename, title = { Text("Rename") }, text = { OutlinedTextField(next, { next = it }, label = { Text("Name") }) },
            confirmButton = { TextButton(enabled = next.isNotBlank() && !model.actionsBusy, onClick = { model.renameCurrent(file, next) }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = model::dismissRename) { Text("Cancel") } })
    }
}

@Composable
internal fun BrowserEmptyState(model: BrowserViewModel) {
    Column {
        Text(if (model.searchQuery.isBlank()) "Empty folder" else "No matching files")
        if (model.searchQuery.isNotBlank()) TextButton(onClick = { model.setSearch("") }) { Text("Clear search") }
        else Text("Use New folder in the toolbar. Long press a file to select it.")
        TextButton(onClick = { if (model.localMode) model.localUp() else model.navigate(parentPath(model.path)) },
            enabled = if (model.localMode) model.localParents.isNotEmpty() else model.path != model.client?.paths?.rootPath) { Text("Back") }
    }
}
