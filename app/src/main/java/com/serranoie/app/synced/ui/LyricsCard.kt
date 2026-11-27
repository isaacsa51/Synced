package com.serranoie.app.synced.ui

import android.media.session.PlaybackState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.serranoie.app.synced.lyrics.LyricLine
import com.serranoie.app.synced.lyrics.indexAt
import com.serranoie.app.synced.lyrics.progressAt
import com.serranoie.app.synced.media.positionNow
import kotlinx.coroutines.delay

/**
 * The synced lyrics card: the current line, the next one, and optionally the previous one.
 * [glowColor] null turns the glow off. With [animate] the glow sweeps across the line over its duration;
 * without it the whole line glows at once. [modifier] is applied before clipping, for snapshots.
 */
@Composable
fun LyricsCard(
    lines: List<LyricLine>,
    playback: PlaybackState?,
    powerSave: Boolean,
    animate: Boolean,
    fadeMs: Int = 300,
    showPast: Boolean,
    glowColor: Color?,
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glow = glowColor?.let(::glowStyle)
    val sweeping = glow != null && animate

    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playback, powerSave, sweeping) {
        while (true) {
            now = playback.positionNow()
            delay(
                when {
                    powerSave -> 500
                    sweeping -> 50
                    else -> 150
                }
            )
        }
    }
    val index = lines.indexAt(now)

    IslandCard(onHeightChanged, modifier) {
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                if (!animate) return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
                val forward = targetState >= initialState
                (slideInVertically(tween(fadeMs)) { if (forward) it / 2 else -it / 2 } + fadeIn(tween(fadeMs))) togetherWith
                        (slideOutVertically(tween(fadeMs)) { if (forward) -it / 2 else it / 2 } + fadeOut(tween(fadeMs)))
            },
            label = "islandLyric",
        ) { shown ->
            Column(Modifier.fillMaxWidth()) {
                if (showPast) SideLine(
                    lines.getOrNull(shown - 1)?.text,
                    Modifier.padding(bottom = 2.dp)
                )

                val text = italicizeAsides(lines.getOrNull(shown)?.text?.ifBlank { null } ?: "♪")
                val progress = lines.progressAt(shown, now)?.let { if (sweeping) it else 1f }
                Line(
                    text = if (glow == null || progress == null) text else sweep(
                        text,
                        glow,
                        progress
                    ),
                    style = CurrentLine,
                    maxLines = 2,
                )

                SideLine(lines.getOrNull(shown + 1)?.text, Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Shown instead of lyrics while media access is missing; tapping it calls [onTap]. */
@Composable
fun AccessCard(
    text: String,
    onTap: () -> Unit,
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) = IslandCard(onHeightChanged, modifier.clickable(onClick = onTap)) {
    Line(AnnotatedString(text), CurrentLine.copy(fontSize = 15.sp), maxLines = 3)
}

/** Shared shell: full width, grows with its content, and reports its height so the host can resize the island. */
@Composable
private fun IslandCard(
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    var heightPx by remember { mutableIntStateOf(0) }
    val corner = with(LocalDensity.current) {
        if (heightPx > 0) minOf(28.dp, (heightPx / 2f).toDp()) else 28.dp
    }
    Box(
        Modifier
            .fillMaxWidth()
            .wrapContentHeight(align = Alignment.Top, unbounded = true)
            .onSizeChanged {
                heightPx = it.height
                onHeightChanged(it.height)
            }
            .then(modifier)
            .clip(RoundedCornerShape(corner))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) { content() }
}

@Composable
private fun SideLine(text: String?, modifier: Modifier) {
    if (text.isNullOrBlank()) return
    Line(italicizeAsides(text), OtherLine, maxLines = 1, modifier)
}

@Composable
private fun Line(
    text: AnnotatedString,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
) = BasicText(
    text = text,
    style = style,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier.fillMaxWidth(),
)
