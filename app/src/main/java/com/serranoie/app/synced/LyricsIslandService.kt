package com.serranoie.app.synced

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sameerasw.island.sdk.HostInfo
import com.sameerasw.island.sdk.IslandAction
import com.sameerasw.island.sdk.IslandActionType
import com.sameerasw.island.sdk.IslandCellPayload
import com.sameerasw.island.sdk.IslandInteractionsPayload
import com.sameerasw.island.sdk.IslandItemPayload
import com.sameerasw.island.sdk.IslandLinePayload
import com.sameerasw.island.sdk.IslandTone
import com.sameerasw.island.sdk.IslandAnchor
import com.sameerasw.island.sdk.IslandAttachmentPayload
import com.sameerasw.island.sdk.IslandHostHandle
import com.sameerasw.island.sdk.IslandPayload
import com.sameerasw.island.sdk.IslandSurfaceKey
import com.sameerasw.island.sdk.compose.ComposeIslandProviderService
import com.sameerasw.island.sdk.compose.islandSnapshot
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

class LyricsIslandService : ComposeIslandProviderService() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val lines = mutableStateOf<List<LyricLine>>(emptyList())
    private var beat: Beat? = null
    private val playback = mutableStateOf<PlaybackState?>(null)

    private val sessions by lazy { getSystemService(MediaSessionManager::class.java) }
    private val listener by lazy { ComponentName(this, MediaAccessService::class.java) }

    private var controller: MediaController? = null
    private var trackKey: String? = null
    private var title: String? = null
    private var needsAccess = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { bind(it) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = load(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            playback.value = state
        }

        override fun onSessionDestroyed() = refresh()
    }

    override fun onAttached(host: IslandHostHandle, info: HostInfo) {
        MediaAccessService.onConnected = { main.post { start() } }
        main.post { start() }
    }

    override fun onInfoChanged(info: HostInfo) {
        main.post { publish() }
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

    override fun hasExpandedContent(itemKey: String) = itemKey == SURFACE

    private fun start() {
        stop()
        if (!SetupActivity.hasMediaAccess(this)) {
            needsAccess = true
            show(emptyList())
            return
        }
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
        } catch (_: SecurityException) {
            needsAccess = true
            show(emptyList())
            return
        }
        needsAccess = false
        refresh()
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
        next?.registerCallback(callback, main)
        playback.value = next?.playbackState
        trackKey = null
        load(next?.metadata)
    }

    private fun stop() {
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (_: Exception) {
        }
        controller?.unregisterCallback(callback)
        controller = null
        trackKey = null
        title = null
        beat = null
        lines.value = emptyList()
    }

    private fun load(metadata: MediaMetadata?) {
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

    private fun show(result: List<LyricLine>, grid: Beat? = null) {
        lines.value = result
        beat = grid
        publish()
    }

    private fun publish() {
        val host = host ?: return
        val items = if (needsAccess) listOf(accessPrompt()) else emptyList()
        val attachments =
            if (needsAccess || hostInfo?.supportsAttachments != true || lines.value.isEmpty()) emptyList()
            else listOf(
                IslandAttachmentPayload(
                    key = ATTACHMENT,
                    anchor = IslandAnchor.MEDIA,
                    heightDp = 72,
                    matchTitle = title,
                    beatMs = beat?.periodMs,
                    beatOffsetMs = beat?.offsetMs,
                ),
            )
        try {
            host.publish(IslandPayload(items = items, attachments = attachments))
        } catch (_: Exception) {
        }
    }

    private fun accessPrompt(): IslandItemPayload {
        val open = IslandAction(IslandActionType.OPEN_APP)
        return IslandItemPayload(
            key = ACCESS_ITEM,
            compact = listOf(
                IslandCellPayload(
                    key = ACCESS_ITEM,
                    text = getString(R.string.island_access_cell),
                    tone = IslandTone.ALERT
                )
            ),
            line = IslandLinePayload(
                start = getString(R.string.island_access_title),
                end = getString(R.string.island_access_action),
                tone = IslandTone.ALERT,
            ),
            dismissible = true,
            interactions = IslandInteractionsPayload(
                onTap = open,
                onExpandedTap = open,
                onOpen = open
            ),
        )
    }

    @Composable
    override fun ExpandedContent(itemKey: String) {
        val density = LocalDensity.current
        val info = hostInfoState.value
        val timed = lines.value
        val state = playback.value

        var now by remember { mutableLongStateOf(0L) }
        LaunchedEffect(state, info?.powerSave) {
            while (true) {
                now = positionMs(state)
                delay(if (info?.powerSave == true) 500 else 150)
            }
        }
        val index = timed.indexOfLast { it.timeMs <= now }

        var heightPx by remember { mutableIntStateOf(0) }
        val corner =
            with(density) { if (heightPx > 0) minOf(28.dp, (heightPx / 2f).toDp()) else 28.dp }

        Box(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .onSizeChanged {
                    heightPx = it.height
                    host?.resize(itemKey, it.height)
                }
                .islandSnapshot { host?.snapshot(itemKey, it) }
                .clip(RoundedCornerShape(corner))
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    val forward = targetState >= initialState
                    (slideInVertically { if (forward) it / 2 else -it / 2 } + fadeIn()) togetherWith
                            (slideOutVertically { if (forward) -it / 2 else it / 2 } + fadeOut())
                },
                label = "islandLyric",
            ) { shown ->
                val line = timed.getOrNull(shown)?.text
                val upcoming = timed.getOrNull(shown + 1)?.text
                Column(Modifier.fillMaxWidth()) {
                    BasicText(
                        text = line?.ifBlank { "♪" } ?: "♪",
                        style = CurrentLine,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!upcoming.isNullOrBlank()) {
                        BasicText(
                            text = upcoming,
                            style = NextLine,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }

    private fun positionMs(state: PlaybackState?): Long {
        state ?: return 0
        if (state.state != PlaybackState.STATE_PLAYING) return state.position
        return state.position + ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
    }

    private companion object {
        const val ATTACHMENT = "lyrics"
        const val ACCESS_ITEM = "access"
        val SURFACE = IslandSurfaceKey.attachment(ATTACHMENT)

        private val LyricShadow =
            Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 8f)
        val CurrentLine = TextStyle(
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            shadow = LyricShadow,
        )
        val NextLine = TextStyle(
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            shadow = LyricShadow,
        )
    }
}
