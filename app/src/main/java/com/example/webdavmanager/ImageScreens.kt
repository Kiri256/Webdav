package com.example.webdavmanager

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException

private data class ThumbnailState(val bitmap: Bitmap? = null, val error: Boolean = false)

@Composable
internal fun ThumbnailImage(client: WebDavClient, file: FileEntry, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    var retry by remember(file.path) { mutableIntStateOf(0) }
    val key = imageCacheKey(client.connection.id, file)
    var state by remember(key) { mutableStateOf(ThumbnailState()) }
    LaunchedEffect(key, retry) {
        state = ThumbnailState()
        try {
            state = ThumbnailState(bitmap = ThumbnailCache.load(context, client, file))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state = ThumbnailState(error = true)
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            state.bitmap != null -> AsyncImage(state.bitmap, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            state.error -> IconButton(onClick = { retry++ }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Retry thumbnail")
            }
            else -> CircularProgressIndicator(Modifier.size(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ImageViewerScreen(files: List<FileEntry>, current: FileEntry, client: WebDavClient?, model: BrowserViewModel) {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = (context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowInsetsControllerCompat(it, it.decorView) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (previous != null) controller?.isAppearanceLightStatusBars = previous }
    }
    val index = files.indexOfFirst { it.path == current.path }
    val busy = model.deleting || model.localBusy
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var scale by remember(current.path) { mutableFloatStateOf(1f) }
    var offset by remember(current.path) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var loading by remember(current.path) { mutableStateOf(true) }
    var failed by remember(current.path) { mutableStateOf(false) }
    var retry by remember(current.path) { mutableIntStateOf(0) }
    var controls by rememberSaveable { mutableStateOf(true) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        val maxX = viewport.width * (scale - 1f) / 2f
        val maxY = viewport.height * (scale - 1f) / 2f
        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }
            .imageSwipe(current.path, scale <= 1f && !busy,
                onPrevious = { files.getOrNull(index - 1)?.let(model::selectImage) },
                onNext = { files.getOrNull(index + 1)?.let(model::selectImage) })
            .transformable(transform, canPan = { scale > 1f })
            .pointerInput(current.path) {
                detectTapGestures(onTap = { controls = !controls }, onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2f
                    offset = Offset.Zero
                })
            }) {
            AsyncImage(
                model = remember(current, retry, client) { client?.imageRequest(context, current) ?:
                    coil.request.ImageRequest.Builder(context).data(Uri.parse(current.path))
                        .memoryCacheKey(localImageCacheKey(current, false))
                        .diskCachePolicy(coil.request.CachePolicy.DISABLED).build() },
                contentDescription = current.name,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                }, contentScale = ContentScale.Fit,
                onLoading = { loading = true; failed = false },
                onSuccess = { loading = false; failed = false },
                onError = { loading = false; failed = true },
            )
        }
        if (loading || busy) CircularProgressIndicator(color = Color.White)
        if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Image could not be loaded", color = Color.White)
            TextButton(onClick = { retry++; loading = true; failed = false }) { Text("Retry") }
        }
        if (controls && index > 0) IconButton(enabled = !busy,
            onClick = { model.selectImage(files[index - 1]) }, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.Outlined.ChevronLeft, "Previous", tint = Color.White, modifier = Modifier.size(40.dp))
        }
        if (controls && index >= 0 && index < files.lastIndex) IconButton(enabled = !busy,
            onClick = { model.selectImage(files[index + 1]) }, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(Icons.Outlined.ChevronRight, "Next", tint = Color.White, modifier = Modifier.size(40.dp))
        }
        (if (client == null) model.localError else model.mediaError)?.let { Text(it,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) }
        if (controls) TopAppBar(modifier = Modifier.align(Alignment.TopCenter),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = .65f),
                titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White),
            title = { Text(current.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(enabled = !busy, onClick = model::closeMedia) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
            } },
            actions = {
                TextButton(onClick = { controls = false }) { Text("Hide") }
                IconButton(enabled = !model.deleting && !model.localBusy, onClick = { deleteTarget = current }) {
                    Icon(Icons.Outlined.Delete, "Delete")
                }
            })
    }
    deleteTarget?.let { target -> AlertDialog(onDismissRequest = { deleteTarget = null },
        title = { Text("Delete image?") }, text = { Text(target.name) }, confirmButton = {
            TextButton(onClick = {
                deleteTarget = null
                if (client == null) model.deleteLocal(target) else model.deleteImage(target)
            }, enabled = !busy) { Text("Delete") }
        }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }) }
}

internal fun Modifier.imageSwipe(identity: String, enabled: Boolean, onPrevious: () -> Unit, onNext: () -> Unit): Modifier =
    pointerInput(identity, enabled) {
        if (enabled) {
            var distance = 0f
            val threshold = 64 * density
            detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f },
                onDragEnd = {
                    if (kotlin.math.abs(distance) >= threshold) {
                        if (distance < 0) onNext() else onPrevious()
                    }
                }, onHorizontalDrag = { change, delta -> change.consume(); distance += delta })
        }
    }
