package com.example.webdavmanager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextEditorScreen(name: String, model: BrowserViewModel) {
    var pending by rememberSaveable(model.textPath) { mutableStateOf<String?>(null) }
    val back = { if (!model.textBusy) { if (model.textDirty) pending = "close" else model.closeMedia() } }
    BackHandler { back() }
    if (pending != null) AlertDialog(
        onDismissRequest = { pending = null },
        title = { Text("Unsaved changes") },
        text = { Text("Discard your changes? Reloading reads the current remote version.") },
        confirmButton = { TextButton(onClick = {
            val action = pending
            pending = null
            if (action == "close") model.closeMedia() else model.loadText()
        }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { pending = null }) { Text("Keep editing") } },
    )
    Scaffold(topBar = {
        TopAppBar(title = { Text(name) }, navigationIcon = {
            IconButton(onClick = back, enabled = !model.textBusy) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
            }
        }, actions = {
            TextButton(enabled = !model.textBusy, onClick = {
                if (model.textDirty) pending = "reload" else model.loadText()
            }) { Text("Reload") }
            TextButton(enabled = !model.textBusy && model.textDirty && model.textWritable,
                onClick = model::saveText) { Text("Save") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("UTF-8 · 64 KiB maximum · " + if (model.textDirty) "Unsaved" else "No unsaved changes")
            if (model.textBusy) CircularProgressIndicator()
            model.textError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (model.textOriginal != null && !model.textWritable) Text(
                "No remote version validator. Reload to obtain a version; saving is disabled to prevent overwriting changes.")
            OutlinedTextField(value = model.textDraft, onValueChange = model::updateText,
                enabled = model.textOriginal != null && !model.textBusy,
                label = { Text("Text content") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}
