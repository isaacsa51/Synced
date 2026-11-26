package com.serranoie.app.synced.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock

class NowPlaying(
    context: Context,
    private val listener: ComponentName,
    private val handler: Handler,
    private val onMetadata: (MediaMetadata?) -> Unit,
    private val onPlayback: (PlaybackState?) -> Unit,
) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private var controller: MediaController? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { bind(it) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = onMetadata(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) = onPlayback(state)
        override fun onSessionDestroyed() = refresh()
    }

    fun start(): Boolean {
        stop()
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler)
        } catch (_: SecurityException) {
            return false
        }
        refresh()
        return true
    }

    fun stop() {
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (_: Exception) {
        }
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun refresh() {
        val active = try {
            sessions.getActiveSessions(listener)
        } catch (_: SecurityException) {
            null
        }
        bind(active)
    }

    private fun bind(active: List<MediaController>?) {
        val next = active?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: active?.firstOrNull()
        if (next != null && next.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = next
        next?.registerCallback(callback, handler)
        onPlayback(next?.playbackState)
        onMetadata(next?.metadata)
    }
}

fun PlaybackState?.positionNow(): Long {
    this ?: return 0
    if (state != PlaybackState.STATE_PLAYING) return position
    return position + ((SystemClock.elapsedRealtime() - lastPositionUpdateTime) * playbackSpeed).toLong()
}

val MediaMetadata.art
    get() = getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: getBitmap(MediaMetadata.METADATA_KEY_ART)
