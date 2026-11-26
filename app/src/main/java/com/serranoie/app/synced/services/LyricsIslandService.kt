package com.serranoie.app.synced.services

import android.app.WallpaperColors
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sameerasw.island.sdk.HostInfo
import com.sameerasw.island.sdk.IslandAction
import com.sameerasw.island.sdk.IslandActionType
import com.sameerasw.island.sdk.IslandCellPayload
import com.sameerasw.island.sdk.IslandInteractionsPayload
import com.sameerasw.island.sdk.IslandItemPayload
import com.sameerasw.island.sdk.IslandLinePayload
import com.sameerasw.island.sdk.IslandOptionPayload
import com.sameerasw.island.sdk.IslandTone
import com.sameerasw.island.sdk.IslandAnchor
import com.sameerasw.island.sdk.IslandAttachmentPayload
import com.sameerasw.island.sdk.IslandHostHandle
import com.sameerasw.island.sdk.IslandPayload
import com.sameerasw.island.sdk.IslandSurfaceKey
import com.sameerasw.island.sdk.compose.ComposeIslandProviderService
import com.sameerasw.island.sdk.compose.islandSnapshot
import com.serranoie.app.synced.Beat
import com.serranoie.app.synced.BeatEstimator
import com.serranoie.app.synced.Lrclib
import com.serranoie.app.synced.LyricLine
import com.serranoie.app.synced.R
import com.serranoie.app.synced.SetupActivity
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

class LyricsIslandService : ComposeIslandProviderService() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val lines = mutableStateOf<List<LyricLine>>(emptyList())
    private var beat: Beat? = null
    private val playback = mutableStateOf<PlaybackState?>(null)
    private val artColor = mutableStateOf<Int?>(null)
    private var art: Bitmap? = null

    private val prefs by lazy { getSharedPreferences("island", MODE_PRIVATE) }
    private val toggles by lazy { DEFAULTS.mapValues { (key, on) -> mutableStateOf(prefs.getBoolean(key, on)) } }
    private fun on(key: String) = toggles.getValue(key).value

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

    override fun onOptionChanged(key: String, checked: Boolean) {
        if (key !in DEFAULTS) return
        prefs.edit().putBoolean(key, checked).apply()
        main.post {
            toggles.getValue(key).value = checked
            publish()
        }
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
        art = null
        artColor.value = null
    }

    private fun load(metadata: MediaMetadata?) {
        loadArtColor(metadata)
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

    // Art often arrives in a later metadata update than the title, so this runs on every change.
    private fun loadArtColor(metadata: MediaMetadata?) {
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (bitmap === art) return
        art = bitmap
        artColor.value = null
        bitmap ?: return
        io.execute {
            val color = try {
                vividColor(WallpaperColors.fromBitmap(bitmap))
            } catch (_: Exception) {
                null
            }
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
        val items = if (needsAccess) listOf(accessPrompt()) else emptyList()
        val attachments =
            if (needsAccess || hostInfo?.supportsAttachments != true || lines.value.isEmpty()) emptyList()
            else listOf(
                IslandAttachmentPayload(
                    key = ATTACHMENT,
                    anchor = IslandAnchor.MEDIA,
                    heightDp = 72,
                    matchTitle = title,
                    beatMs = beat?.periodMs?.takeIf { on(OPT_PULSE) },
                    beatOffsetMs = beat?.offsetMs?.takeIf { on(OPT_PULSE) },
                ),
            )
        try {
            host.publish(IslandPayload(items = items, options = options(), attachments = attachments))
        } catch (_: Exception) {
        }
    }

    private fun options() = listOf(
        OPT_PULSE to R.string.option_beat_pulse,
        OPT_ANIMATION to R.string.option_lyric_animation,
        OPT_THREE_LINES to R.string.option_three_lines,
        OPT_GLOW to R.string.option_accent_glow,
    ).map { (key, title) -> IslandOptionPayload(key, getString(title), on(key)) }

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
        val info = hostInfoState.value
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, info?.fontScale ?: base.fontScale)) {
            Lyrics(itemKey, info)
        }
    }

    @Composable
    private fun Lyrics(itemKey: String, info: HostInfo?) {
        val density = LocalDensity.current
        val timed = lines.value
        val state = playback.value
        val animate = on(OPT_ANIMATION)
        val showPast = on(OPT_THREE_LINES)
        val glow = if (on(OPT_GLOW)) SpanStyle(
            shadow = Shadow(Color(artColor.value ?: info?.accent ?: 0xFFFFFFFF.toInt()), blurRadius = 24f),
        ) else null

        var now by remember { mutableLongStateOf(0L) }
        LaunchedEffect(state, info?.powerSave, glow != null) {
            while (true) {
                now = positionMs(state)
                delay(
                    when {
                        info?.powerSave == true -> 500
                        glow != null -> 50 // smooth sweep
                        else -> 150
                    }
                )
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
                    if (!animate) return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
                    val forward = targetState >= initialState
                    (slideInVertically { if (forward) it / 2 else -it / 2 } + fadeIn()) togetherWith
                            (slideOutVertically { if (forward) -it / 2 else it / 2 } + fadeOut())
                },
                label = "islandLyric",
            ) { shown ->
                val line = timed.getOrNull(shown)?.text
                val upcoming = timed.getOrNull(shown + 1)?.text
                val past = timed.getOrNull(shown - 1)?.text
                Column(Modifier.fillMaxWidth()) {
                    if (showPast && !past.isNullOrBlank()) {
                        BasicText(
                            text = italicizeAsides(past),
                            style = NextLine,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 2.dp),
                        )
                    }
                    val text = italicizeAsides(line?.ifBlank { "♪" } ?: "♪")
                    val start = timed.getOrNull(shown)?.timeMs
                    val end = timed.getOrNull(shown + 1)?.timeMs ?: start?.plus(LAST_LINE_MS)
                    BasicText(
                        text = if (glow == null || start == null || end == null) text
                        else sweep(text, glow, ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)),
                        style = CurrentLine,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!upcoming.isNullOrBlank()) {
                        BasicText(
                            text = italicizeAsides(upcoming),
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

    /** Backing vocals / ad-libs on parenthesis render in italic. */
    private fun italicizeAsides(text: String): AnnotatedString = buildAnnotatedString {
        var last = 0
        for (m in ASIDE.findAll(text)) {
            append(text, last, m.range.first)
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.value) }
            last = m.range.last + 1
        }
        append(text, last, text.length)
    }

    /** Glows [text] from its first character up to [progress] (0..1) of its length. */
    private fun sweep(text: AnnotatedString, glow: SpanStyle, progress: Float) = buildAnnotatedString {
        append(text)
        val lit = kotlin.math.ceil(progress * text.length).toInt()
        if (lit > 0) addStyle(glow, 0, lit)
    }

    private fun positionMs(state: PlaybackState?): Long {
        state ?: return 0
        if (state.state != PlaybackState.STATE_PLAYING) return state.position
        return state.position + ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
    }

    private companion object {
        // ponytail: picks the most saturated of the art's top three colors; grey covers fall back to the host accent.
        fun vividColor(colors: WallpaperColors): Int? {
            val hsv = FloatArray(3)
            return listOfNotNull(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor)
                .map { it.toArgb() }
                .maxByOrNull { android.graphics.Color.colorToHSV(it, hsv); hsv[1] }
                ?.takeIf { android.graphics.Color.colorToHSV(it, hsv); hsv[1] > 0.2f && hsv[2] > 0.25f }
        }

        private fun sans(weight: FontWeight, style: FontStyle) = Font(
            R.font.google_sans_flex, weight, style,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight.weight),
                FontVariation.slant(if (style == FontStyle.Italic) -10f else 0f),
            ),
        )

        val GoogleSans = FontFamily(
            sans(FontWeight.Normal, FontStyle.Normal),
            sans(FontWeight.Normal, FontStyle.Italic),
            sans(FontWeight.SemiBold, FontStyle.Normal),
            sans(FontWeight.SemiBold, FontStyle.Italic),
        )

        const val ATTACHMENT = "lyrics"
        const val ACCESS_ITEM = "access"
        const val OPT_PULSE = "beat_pulse"
        const val OPT_ANIMATION = "lyric_animation"
        const val LAST_LINE_MS = 4_000L // ponytail: last line has no next timestamp; fixed sweep length
        const val OPT_THREE_LINES = "three_lines"
        const val OPT_GLOW = "accent_glow"
        val DEFAULTS = mapOf(OPT_PULSE to true, OPT_ANIMATION to true, OPT_THREE_LINES to false, OPT_GLOW to false)
        val SURFACE = IslandSurfaceKey.attachment(ATTACHMENT)
        val ASIDE = Regex("""\([^()]*\)""")

        private val LyricShadow =
            Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 8f)
        val CurrentLine = TextStyle(
            color = Color.White,
            fontSize = 17.sp,
            fontFamily = GoogleSans,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            shadow = LyricShadow,
        )
        val NextLine = TextStyle(
            fontFamily = GoogleSans,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            shadow = LyricShadow,
        )
    }
}
