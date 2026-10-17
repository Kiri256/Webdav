package com.example.webdavmanager

import android.content.ClipData
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun ExternalOpenUi(model: BrowserViewModel) {
    val context = LocalContext.current
    val file = model.externalTarget
    val ready = model.externalReady
    LaunchedEffect(ready) {
        if (ready != null && file != null) {
            val mime = file.mimeType?.takeUnless { it == "application/octet-stream" }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.name.substringAfterLast('.', "").lowercase()) ?: "*/*"
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(ready, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(file.name, ready) }
            try {
                context.startActivity(Intent.createChooser(view, "Open with other app").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                model.externalOpened()
            } catch (_: Exception) { model.externalOpenFailed() }
        }
    }
    if (file != null) AlertDialog(onDismissRequest = model::cancelExternalOpen,
        title = { Text("Open with other app?") },
        text = { Column {
            Text(file.name)
            Text("A read-only copy will be provided to the app you choose. Files up to 256 MiB are supported.")
            if (model.externalBusy) CircularProgressIndicator()
            model.externalError?.let { Text(it) }
        } }, confirmButton = { TextButton(enabled = !model.externalBusy, onClick = model::prepareExternalOpen) { Text("Choose app") } },
        dismissButton = { TextButton(onClick = model::cancelExternalOpen) { Text("Cancel") } })
}
