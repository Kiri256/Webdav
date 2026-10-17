package com.example.webdavmanager

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import `is`.xyz.mpv.MPV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun VideoPlayerScreen(file: FileEntry, client: WebDavClient?, model: BrowserViewModel, documentUri: Uri? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val activity = remember(context) {
        var current: android.content.Context = context
        while (current is ContextWrapper && current !is Activity) current = current.baseContext
        current as? Activity
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var contentDescriptor by remember(documentUri) { mutableStateOf<ParcelFileDescriptor?>(null) }
    DisposableEffect(documentUri) { onDispose { contentDescriptor?.close() } }
    LaunchedEffect(documentUri) {
        if (documentUri?.scheme == "content") {
            var opened: ParcelFileDescriptor? = null
            var delivered = false
            try {
                contentDescriptor = withContext(Dispatchers.IO) {
                    context.contentResolver.openFileDescriptor(documentUri, "r").also { opened = it }
                }
                delivered = true
                if (contentDescriptor == null) model.reportMediaError("Could not open local media")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                model.reportMediaError(error.message ?: "Could not open local media")
            } finally { if (!delivered) opened?.close() }
        }
    }
    val localVideo = model.localVideo
    val webDavProxy = remember(client, file.path, documentUri, localVideo) {
        if (documentUri == null && localVideo == null) client?.let { MpvWebDavProxy(it, file) } else null
    }
    val source = contentDescriptor?.let { "fd://${it.fd}" }
        ?: webDavProxy?.uri
        ?: documentUri?.takeUnless { it.scheme == "content" }?.toString()
        ?: localVideo?.let { Uri.fromFile(it).toString() }
        ?: client?.fileUri(file.path)?.toString()
    if (source == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                model.mediaError?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color.White) }
            }
        }
        return
    }
    var controls by rememberSaveable { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    val session = remember(source, file.path) {
        MpvPlaybackSession(context, source.toString(), file.isAudio(), model.playbackPosition,
            lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            wantsPlayback = { model.playbackWanted && !model.downloading },
            savePosition = { if ((model.videoPath ?: model.audioPath) == file.path) model.playbackPosition = it },
            onError = {
                failed = true
                model.reportMediaError("Playback failed. Try another decoder or download for local playback.")
            })
    }
    val player = session.player
    var position by remember(player) { mutableFloatStateOf(model.playbackPosition / 1000f) }
    val duration = session.duration.toFloat()
    val speed = session.speed.toFloat()
    var decoder by remember(player) { mutableStateOf("auto-safe") }
    val playing = session.loaded && !session.paused && !session.idle
    val buffering = !failed && (!session.loaded || (!session.paused && session.pausedForCache))
    var dragging by remember(player) { mutableStateOf(false) }
    var scrubbing by remember(player) { mutableStateOf(false) }
    val currentControls by androidx.compose.runtime.rememberUpdatedState(controls)
    val playerView = remember(player) { mutableStateOf<MpvTextureView?>(null) }
    val subtitles = model.subtitles
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !session.closed) subtitles.loadLocal(uri)
    }
    LaunchedEffect(session, session.loaded) {
        if (session.loaded) subtitles.bind(session, file, model.currentFiles,
            if (documentUri != null) model.localStorage else null, client)
    }

    DisposableEffect(session, lifecycle) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    playerView.value?.keepScreenOn = false
                    session.updateForeground(false)
                }
                Lifecycle.Event.ON_START -> session.updateForeground(true)
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(lifecycleObserver)
        val window = activity?.window
        val insetsController = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (!file.isAudio()) {
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
            insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            lifecycle.lifecycle.removeObserver(lifecycleObserver)
            playerView.value?.keepScreenOn = false
            playerView.value?.releaseSurface()
            session.close()
            webDavProxy?.close()
            subtitles.release(session)
            if (!file.isAudio()) {
                insetsController?.show(WindowInsetsCompat.Type.systemBars())
            }
            if (model.videoPath != file.path && !file.isAudio()) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(session, model.downloading) { session.applyPlaybackState() }

    LaunchedEffect(session, session.foreground, session.loaded, playing, controls, dragging, scrubbing, session.positionRevision) {
        if (!session.foreground || !session.loaded) return@LaunchedEffect
        do {
            session.syncIdleFromNative()
            val time = session.positionMillis()
            if (!dragging && !scrubbing && time != null) position = time / 1000f
            if (!dragging && !scrubbing && time != null) model.playbackPosition = time
            if (playing) delay(if (controls) 300 else 1000)
        } while (playing)
    }
    LaunchedEffect(controls, menu, dialog, playing, scrubbing) {
        if (controls && !menu && dialog == null && playing && !scrubbing && !file.isAudio()) {
            delay(3500)
            controls = false
        }
    }

    val tracks = remember(player, dialog, session.trackVersion, session.loaded) {
        if (dialog != "tracks") emptyList() else player.getPropertyNode("track-list")?.asArray().orEmpty().mapNotNull { node ->
        val fields = node.asMap() ?: return@mapNotNull null
        if (fields["type"]?.asString() != "audio") return@mapNotNull null
        val id = fields["id"]?.asInt()?.toInt() ?: return@mapNotNull null
        val name = fields["title"]?.asString()?.ifBlank { null }
            ?: fields["lang"]?.asString()?.ifBlank { null } ?: "Audio track"
        Triple(id, name, fields["selected"]?.asBoolean() == true)
    } }

    Box(Modifier.fillMaxSize().testTag("media-gestures").semantics {
        customActions = listOf(
            CustomAccessibilityAction("Show playback controls") { controls = true; true },
            CustomAccessibilityAction("Hide playback controls") { controls = false; true },
            CustomAccessibilityAction("Seek forward 10 seconds") { player.command("seek", "10", "relative"); true },
            CustomAccessibilityAction("Seek backward 10 seconds") { player.command("seek", "-10", "relative"); true })
    }.background(Color.Black).pointerInput(player) {
        detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { point ->
            player.command("seek", if (point.x < size.width / 2) "-10" else "10", "relative")
            controls = true
        })
    }.pointerInput(player, duration, model.rangeSupported, localVideo, documentUri) {
        var accepted = false
        var distance = 0f
        var startPosition = 0f
        detectHorizontalDragGestures(
            onDragStart = { point ->
                val seekable = duration > 0f && (model.rangeSupported != false || localVideo != null || documentUri != null)
                val controlsEdge = currentControls && (point.y < 72.dp.toPx() || point.y > size.height - 120.dp.toPx())
                accepted = !file.isAudio() && seekable && !controlsEdge
                if (accepted) {
                    distance = 0f
                    startPosition = player.getPropertyDouble("time-pos")?.toFloat() ?: position
                    position = startPosition
                    scrubbing = true
                    controls = true
                }
            },
            onHorizontalDrag = { change, amount ->
                if (accepted) {
                    distance += amount
                    val delta = distance / size.width.coerceAtLeast(1) * duration
                    position = (startPosition + delta).coerceIn(0f, duration)
                    change.consume()
                }
            },
            onDragEnd = {
                if (accepted) {
                    player.setPropertyDouble("time-pos", position.toDouble())
                    model.playbackPosition = (position * 1000).toLong()
                    scrubbing = false
                }
                accepted = false
            },
            onDragCancel = { if (accepted) scrubbing = false; accepted = false },
        )
    }) {
        key(session) {
        AndroidView(factory = { viewContext ->
            MpvTextureView(viewContext, session).also { playerView.value = it }
        }, modifier = if (file.isAudio()) Modifier.size(1.dp).alpha(0f) else Modifier.fillMaxSize(), update = {
            playerView.value = it
            it.keepScreenOn = !file.isAudio() && playing && session.foreground
        }, onRelease = {
            it.releaseSurface()
            if (playerView.value === it) playerView.value = null
        })
        }
        if (file.isAudio()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.PlayArrow, null, tint = Color.White, modifier = Modifier.size(72.dp))
            Text(file.name, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (buffering && !failed && !model.downloading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        if (controls) Column(Modifier.fillMaxSize().safeDrawingPadding().background(Color.Transparent), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .68f)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = model::closeMedia) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White) }
                Text(file.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!file.isAudio()) IconButton(onClick = {
                    activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }) { Icon(Icons.Outlined.ScreenRotation, "Rotate screen", tint = Color.White) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Audio track") }, onClick = { menu = false; dialog = "tracks" })
                        DropdownMenuItem(text = { Text("Subtitles") }, onClick = { menu = false; dialog = "subtitles" })
                        DropdownMenuItem(text = { Text("Playback speed") }, onClick = { menu = false; dialog = "speed" })
                        DropdownMenuItem(text = { Text("Video decoder") }, onClick = { menu = false; dialog = "decoder" })
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .76f)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (documentUri == null && model.rangeSupported == false) Text(
                    "This server does not support seeking.", color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp))
                model.mediaError?.takeIf { it.isNotBlank() }?.let { message -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = Color.White, modifier = Modifier.weight(1f).padding(bottom = 4.dp))
                    if (failed) TextButton(onClick = {
                        failed = false
                        model.playbackPosition = (position * 1000).toLong()
                        model.reportMediaError("")
                        session.reload(model.playbackPosition)
                    }) { Text("Retry") }
                } }
                if (documentUri == null && localVideo == null && (model.rangeSupported == false || failed)) {
                    if (model.downloading) {
                        Text("Downloading: ${model.downloadBytes / (1024 * 1024)} MiB", color = Color.White)
                        TextButton(onClick = model::cancelDownload) { Text("Cancel download") }
                    } else TextButton(onClick = { model.downloadVideo(file) }) { Text("Download for local playback (up to 1 GiB)") }
                }
                Slider(modifier = Modifier.testTag("media-seek"), value = position.coerceIn(0f, duration.coerceAtLeast(0.01f)),
                    onValueChange = { dragging = true; position = it }, valueRange = 0f..duration.coerceAtLeast(1f),
                    enabled = duration > 0 && (model.rangeSupported != false || documentUri != null || localVideo != null),
                    onValueChangeFinished = {
                        model.playbackPosition = (position * 1000).toLong()
                        player.setPropertyDouble("time-pos", position.toDouble()); dragging = false
                    })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(mediaTime(position.toLong()), color = Color.White)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { player.command("seek", "-10", "relative") }) { Icon(Icons.Outlined.Replay10, "Back 10 seconds", tint = Color.White) }
                    IconButton(onClick = {
                        model.playbackWanted = !playing
                        if (!playing && player.getPropertyBoolean("idle-active") == true) session.reload(0)
                        else session.applyPlaybackState()
                    }) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(36.dp)) }
                    IconButton(onClick = { player.command("seek", "10", "relative") }) { Icon(Icons.Outlined.Forward10, "Forward 10 seconds", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    Text(mediaTime(duration.toLong()), color = Color.White)
                }
            }
        }
        if (scrubbing) Surface(Modifier.align(Alignment.Center),
            color = Color.Black.copy(alpha = .76f), shape = RoundedCornerShape(6.dp)) {
            Text(mediaTime(position.toLong()), color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }

    if (dialog == "subtitles") SubtitlePanel(subtitles, session, onPickLocal = {
        subtitlePicker.launch(arrayOf("*/*"))
    }, onClose = { dialog = null })
    if (dialog == "tracks") AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Audio track") },
        text = { Column {
            tracks.forEach { (id, title, selected) -> TextButton(onClick = {
                player.setPropertyInt("aid", id)
                dialog = null
            }, modifier = Modifier.fillMaxWidth()) { Text((if (selected) "✓  " else "") + title) } }
            if (tracks.isEmpty()) Text("No audio tracks")
        } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } })
    if (dialog == "speed") AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Playback speed") },
        text = { Column { listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { value ->
            TextButton(onClick = { player.setPropertyDouble("speed", value.toDouble()); dialog = null }, modifier = Modifier.fillMaxWidth()) {
                Text((if (speed == value) "✓  " else "") + "${value}x")
            }
        } } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } })
    if (dialog == "decoder") AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Video decoder") },
        text = { Column { listOf("auto-safe" to "Automatic", "mediacodec-copy" to "Hardware", "no" to "Software").forEach { (value, label) ->
            TextButton(onClick = {
                decoder = value
                player.setPropertyString("hwdec", value)
                val resumeAt = position.toDouble()
                model.playbackPosition = (resumeAt * 1000).toLong()
                session.reload(model.playbackPosition)
                dialog = null
            }, modifier = Modifier.fillMaxWidth()) { Text((if (decoder == value) "✓  " else "") + label) }
        } } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } })
}

private fun mediaTime(seconds: Long): String {
    val value = seconds.coerceAtLeast(0)
    return if (value >= 3600) "%d:%02d:%02d".format(value / 3600, value / 60 % 60, value % 60)
    else "%d:%02d".format(value / 60, value % 60)
}

internal class MpvTextureView(context: android.content.Context, private val session: MpvPlaybackSession) : TextureView(context),
    TextureView.SurfaceTextureListener {
    val mpv: MPV? get() = session.player.takeUnless { session.closed }
    private var videoSurface: Surface? = null

    init {
        surfaceTextureListener = this
        if (isAvailable) surfaceTexture?.let(::attach)
    }

    override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) = attach(surface)

    override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
        mpv?.setPropertyString("android-surface-size", "${width}x${height}")
    }

    override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
        releaseSurface()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit

    fun releaseSurface() {
        videoSurface?.let {
            session.detach()
            it.release()
        }
        videoSurface = null
    }

    private fun attach(texture: android.graphics.SurfaceTexture) {
        if (videoSurface != null) return
        if (session.closed) return
        val surface = Surface(texture)
        videoSurface = surface
        session.attach(surface, width, height)
    }
}
