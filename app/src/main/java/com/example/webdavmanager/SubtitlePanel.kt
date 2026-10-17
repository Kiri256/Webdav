package com.example.webdavmanager

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SubtitlePanel(controller: SubtitleController, session: MpvPlaybackSession, onPickLocal: () -> Unit, onClose: () -> Unit) {
    val tracks = remember(session.trackVersion) { session.player.getPropertyNode("track-list")?.asArray().orEmpty().mapNotNull { it.asMap() }.filter { it["type"]?.asString() == "sub" } }
    AlertDialog(onDismissRequest = onClose, title = { Text("Subtitles") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            Text("Current: ${controller.currentName}")
            TextButton(onClick = { controller.selectTrack(null, "Off") }) { Text("Off") }
            tracks.forEach { track ->
                val id = track["id"]?.asInt()?.toInt()
                if (id != null) TextButton(onClick = { controller.selectTrack(id, track["title"]?.asString() ?: "Embedded subtitle $id") }) {
                    Text((if (track["selected"]?.asBoolean() == true) "✓ " else "") +
                        (track["title"]?.asString() ?: track["lang"]?.asString() ?: "Subtitle $id"))
                }
            }
            Text("Same folder", style = MaterialTheme.typography.titleSmall)
            controller.candidates.forEach { candidate -> TextButton(enabled = !controller.busy, onClick = { controller.load(candidate) }) { Text(candidate.name) } }
            if (controller.candidates.isEmpty()) Text("No matching subtitle files")
            TextButton(onClick = onPickLocal, enabled = !controller.busy) { Text("Choose local subtitle") }
            Row { Checkbox(controller.autoLoad, controller::setAutomatic); Text("Automatically load matching subtitles") }
            if (controller.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = controller::cancel) { Text("Cancel subtitle load") } }
            controller.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = controller::retry, enabled = !controller.busy) { Text("Retry subtitle") }
            }
            Text("Offset: %.1f s".format(controller.offset))
            Row(Modifier.horizontalScrollCompat()) {
                TextButton(onClick = { controller.updateOffset(controller.offset - .5) }) { Text("Earlier 0.5 s") }
                TextButton(onClick = { controller.updateOffset(controller.offset + .5) }) { Text("Later 0.5 s") }
                TextButton(onClick = { controller.updateOffset(0.0) }) { Text("Reset") }
            }
            Text("Encoding: ${controller.encoding}")
            Row(Modifier.horizontalScrollCompat()) {
                listOf("auto", "UTF-8", "GB18030", "Big5").forEach { value -> TextButton(enabled = !controller.busy, onClick = { controller.updateEncoding(value) }) { Text(value) } }
            }
            Text("Text size: %.0f%%".format(controller.scale * 100))
            Slider(value = controller.scale.toFloat(), onValueChange = { controller.updateScale(it.toDouble()) }, valueRange = .5f..2f)
            Text("ASS/SSA fonts and positioning may be controlled by the subtitle's own styles.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}

@Composable
private fun Modifier.horizontalScrollCompat() = this.then(Modifier.horizontalScroll(rememberScrollState()))
