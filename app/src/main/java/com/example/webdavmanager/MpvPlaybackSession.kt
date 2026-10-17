package com.example.webdavmanager

import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode

/** Native events are marshalled to the UI thread; video never loads without an attached surface. */
internal class MpvPlaybackSession(
    context: android.content.Context,
    private val source: String,
    private val audio: Boolean,
    initialPosition: Long,
    foreground: Boolean,
    private val wantsPlayback: () -> Boolean,
    private val savePosition: (Long) -> Unit,
    private val onError: () -> Unit,
) : AutoCloseable {
    val player = MPV(context.applicationContext).apply {
        setPropertyString("vo", "null")
        setPropertyBoolean("pause", true)
        setPropertyString("hwdec", "auto-safe")
        setPropertyBoolean("cache", true)
        setPropertyInt("cache-secs", 30)
        setPropertyBoolean("osc", false)
        setPropertyBoolean("osd-bar", false)
    }
    var loaded by mutableStateOf(false)
        private set
    var foreground by mutableStateOf(foreground)
        private set
    var paused by mutableStateOf(true)
        private set
    var idle by mutableStateOf(true)
        private set
    var duration by mutableStateOf(0.0)
        private set
    var speed by mutableStateOf(1.0)
        private set
    var pausedForCache by mutableStateOf(false)
        private set
    var trackVersion by mutableStateOf(0)
        private set
    var positionRevision by mutableStateOf(0)
        private set
    var closed = false
        private set
    private var attached = false
    private var started = false
    private var requestedStart = initialPosition.coerceAtLeast(0)
    private val main = Handler(Looper.getMainLooper())
    private val observer = object : MPV.EventObserver {
        override fun eventProperty(property: String) {
            if (property == "track-list") main.post { if (!closed) trackVersion++ }
        }
        override fun eventProperty(property: String, value: Long) = Unit
        override fun eventProperty(property: String, value: Boolean) {
            main.post { if (!closed) when (property) {
                "pause" -> paused = value
                "idle-active" -> idle = value
                "paused-for-cache" -> pausedForCache = value
            } }
        }
        override fun eventProperty(property: String, value: String) = Unit
        override fun eventProperty(property: String, value: Double) {
            main.post { if (!closed && value.isFinite()) when (property) {
                "duration" -> duration = value.coerceAtLeast(0.0)
                "speed" -> speed = value
            } }
        }
        override fun eventProperty(property: String, value: MPVNode) = Unit
        override fun event(eventId: Int, data: MPVNode) {
            // MPVNode belongs to the native callback; extract values before posting.
            val error = eventId == MPV.mpvEvent.MPV_EVENT_END_FILE && data["reason"]?.asString() == "error"
            main.post {
                if (!closed) {
                    if (eventId == MPV.mpvEvent.MPV_EVENT_FILE_LOADED) {
                        loaded = true
                        applyPlaybackState()
                    }
                    if (eventId == MPV.mpvEvent.MPV_EVENT_PLAYBACK_RESTART) positionRevision++
                    // END_FILE can precede the final idle property notification.
                    // Refresh once on the UI thread so an ended file releases
                    // screen-on immediately, without a periodic idle poll.
                    if (eventId == MPV.mpvEvent.MPV_EVENT_END_FILE || eventId == MPV.mpvEvent.MPV_EVENT_IDLE) {
                        idle = player.getPropertyBoolean("idle-active") == true
                        // mpv may deliver the idle event before the FLAG observer;
                        // the event itself is authoritative at end-of-file.
                        if (eventId == MPV.mpvEvent.MPV_EVENT_IDLE || idle) {
                            idle = true
                            loaded = false
                        }
                    }
                    if (error) { loaded = false; onError() }
                }
            }
        }
    }

    init {
        player.addObserver(observer)
        listOf("pause", "idle-active", "paused-for-cache").forEach { player.observeProperty(it, MPV.mpvFormat.MPV_FORMAT_FLAG) }
        listOf("duration", "speed").forEach { player.observeProperty(it, MPV.mpvFormat.MPV_FORMAT_DOUBLE) }
        player.observeProperty("track-list", MPV.mpvFormat.MPV_FORMAT_NONE)
        loadWhenReady()
    }

    private fun loadWhenReady() {
        if (closed || started || (!audio && !attached)) return
        started = true
        loaded = false
        player.setPropertyBoolean("pause", true)
        // Supplying start with loadfile prevents a polling tick from erasing a pending seek.
        player.command("loadfile", source, "replace", "-1", "start=${requestedStart / 1000.0}")
    }

    fun reload(position: Long) {
        if (closed) return
        requestedStart = position.coerceAtLeast(0)
        started = false
        loadWhenReady()
    }

    fun attach(surface: Surface, width: Int, height: Int) {
        if (closed || audio) return
        player.attachSurface(surface)
        attached = true
        player.setPropertyString("android-surface-size", "${width}x${height}")
        player.setPropertyString("force-window", "yes")
        player.setPropertyString("vo", "gpu")
        loadWhenReady()
        applyPlaybackState()
    }

    fun detach() {
        if (closed || !attached) return
        if (foreground) positionMillis()?.let { requestedStart = it; savePosition(it) }
        player.setPropertyBoolean("pause", true)
        // Changing vo during playback rebuilds the hardware decoder. Stop while the
        // Surface is still valid, then release the output without recreating a codec.
        player.command("stop")
        started = false
        loaded = false
        player.setPropertyString("force-window", "no")
        player.setPropertyString("vo", "null")
        player.detachSurface()
        attached = false
    }

    fun updateForeground(value: Boolean) {
        if (closed) return
        foreground = value
        applyPlaybackState()
        if (!value) snapshotPosition()
    }

    fun applyPlaybackState() {
        if (!closed) player.setPropertyBoolean("pause", !loaded || !foreground || !wantsPlayback() || (!audio && !attached))
    }

    fun positionMillis(): Long? {
        if (closed || !loaded) return null
        val time = player.getPropertyDouble("time-pos") ?: return null
        if (!time.isFinite()) return null
        val value = (time * 1000).toLong().coerceAtLeast(0)
        // A loaded event can precede the first position at the requested start.
        if (requestedStart > 0 && value == 0L) return null
        requestedStart = 0
        return value
    }

    /** Called by the existing foreground position sampler to catch an EOF
     * notification from older mpv builds that omit the idle event callback. */
    fun syncIdleFromNative() {
        if (!closed && player.getPropertyBoolean("idle-active") == true) {
            idle = true
            loaded = false
        }
    }

    fun snapshotPosition() { positionMillis()?.let(savePosition) }

    override fun close() {
        if (closed) return
        if (foreground && !attached) snapshotPosition()
        detach()
        closed = true
        main.removeCallbacksAndMessages(null)
        player.removeObserver(observer)
        player.close()
    }
}
