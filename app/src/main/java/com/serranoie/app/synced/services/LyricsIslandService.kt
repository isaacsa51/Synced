package com.serranoie.app.synced.services

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import com.sameerasw.island.sdk.HostInfo
import com.sameerasw.island.sdk.IslandHostHandle
import com.sameerasw.island.sdk.IslandPayload
import com.sameerasw.island.sdk.compose.ComposeIslandProviderService
import com.sameerasw.island.sdk.compose.islandSnapshot
import com.serranoie.app.synced.R
import com.serranoie.app.synced.SetupActivity
import com.serranoie.app.synced.island.IslandOption
import com.serranoie.app.synced.island.IslandOptions
import com.serranoie.app.synced.island.LYRICS_SURFACE
import com.serranoie.app.synced.island.accessPrompt
import com.serranoie.app.synced.island.lyricsAttachment
import com.serranoie.app.synced.lyrics.Beat
import com.serranoie.app.synced.lyrics.BeatEstimator
import com.serranoie.app.synced.lyrics.Lrclib
import com.serranoie.app.synced.lyrics.LyricLine
import com.serranoie.app.synced.media.NowPlaying
import com.serranoie.app.synced.media.art
import com.serranoie.app.synced.media.vividColor
import com.serranoie.app.synced.ui.AccessCard
import com.serranoie.app.synced.ui.LyricsCard
import java.util.concurrent.Executors

class LyricsIslandService : ComposeIslandProviderService() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val options by lazy { IslandOptions(this) }

    private val lines = mutableStateOf<List<LyricLine>>(emptyList())
    private val playback = mutableStateOf<PlaybackState?>(null)
    private val artColor = mutableStateOf<Int?>(null)
    private var beat: Beat? = null
    private var art: Bitmap? = null
    private var trackKey: String? = null
    private var title: String? = null
    private val needsAccess = mutableStateOf(false)

    private val nowPlaying by lazy {
        NowPlaying(
            context = this,
            listener = ComponentName(this, MediaAccessService::class.java),
            handler = main,
            onMetadata = ::load,
            onPlayback = { playback.value = it },
        )
    }

    override fun onAttached(host: IslandHostHandle, info: HostInfo) {
        MediaAccessService.onConnected = { main.post { start() } }
        main.post { start() }
    }

    override fun onInfoChanged(info: HostInfo) {
        main.post { publish() }
    }

    override fun onOptionChanged(key: String, checked: Boolean) {
        main.post { if (options.set(key, checked)) publish() }
    }

    override fun onHostDetached() {
        super.onHostDetached()
        MediaAccessService.onConnected = null
        main.post { stop() }
    }

    override fun onDestroy() {
        stop()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun hasExpandedContent(itemKey: String) = itemKey == LYRICS_SURFACE

    private fun start() {
        stop()
        needsAccess.value = false
        if (!SetupActivity.hasMediaAccess(this) || !nowPlaying.start()) {
            needsAccess.value = true
            show(emptyList())
        }
    }

    private fun stop() {
        nowPlaying.stop()
        trackKey = null
        title = null
        beat = null
        lines.value = emptyList()
        playback.value = null
        art = null
        artColor.value = null
    }

    private fun load(metadata: MediaMetadata?) {
        loadArtColor(metadata?.art)
        val track = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        if (track.isNullOrBlank() || artist.isNullOrBlank()) {
            trackKey = null
            show(emptyList())
            return
        }
        val key = "$artist\u0000$track"
        if (key == trackKey) return
        trackKey = key
        title = track
        show(emptyList())
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val durationSec = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000
        io.execute {
            val result = Lrclib.fetch(artist, track, album, durationSec)
            val grid = BeatEstimator.estimate(result)
            main.post { if (trackKey == key) show(result, grid) }
        }
    }

    private fun loadArtColor(bitmap: Bitmap?) {
        if (bitmap === art) return
        art = bitmap
        artColor.value = null
        bitmap ?: return
        io.execute {
            val color = vividColor(bitmap)
            main.post { if (art === bitmap) artColor.value = color }
        }
    }

    private fun show(result: List<LyricLine>, grid: Beat? = null) {
        lines.value = result
        beat = grid
        publish()
    }

    private fun publish() {
        val host = host ?: return
        val attachments = when {
            hostInfo?.supportsAttachments != true -> emptyList()
            needsAccess.value -> listOf(lyricsAttachment(title = null, beat = null))
            lines.value.isEmpty() -> emptyList()
            else -> listOf(lyricsAttachment(title, beat.takeIf { options[IslandOption.BEAT_PULSE] }))
        }
        val payload = IslandPayload(
            items = if (needsAccess.value) listOf(accessPrompt(this)) else emptyList(),
            options = options.payload(),
            attachments = attachments,
        )
        try {
            host.publish(payload)
        } catch (_: Exception) {
        }
    }

    @Composable
    override fun ExpandedContent(itemKey: String) {
        val info = hostInfoState.value
        val base = LocalDensity.current
        val accent = artColor.value ?: info?.accent ?: 0xFFFFFFFF.toInt()
        val onHeightChanged = { height: Int -> host?.resize(itemKey, height); Unit }
        val snapshot = Modifier.islandSnapshot { host?.snapshot(itemKey, it) }
        CompositionLocalProvider(LocalDensity provides Density(base.density, info?.fontScale ?: base.fontScale)) {
            if (needsAccess.value) {
                AccessCard(
                    text = stringResource(R.string.island_access_card),
                    onTap = { host?.openApp(itemKey) },
                    onHeightChanged = onHeightChanged,
                    modifier = snapshot,
                )
                return@CompositionLocalProvider
            }
            LyricsCard(
                lines = lines.value,
                playback = playback.value,
                powerSave = info?.powerSave == true,
                animate = options[IslandOption.LYRIC_ANIMATION],
                showPast = options[IslandOption.THREE_LINES],
                glowColor = Color(accent).takeIf { options[IslandOption.ACCENT_GLOW] },
                onHeightChanged = onHeightChanged,
                modifier = snapshot,
            )
        }
    }
}
