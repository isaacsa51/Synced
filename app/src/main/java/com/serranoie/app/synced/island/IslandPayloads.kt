package com.serranoie.app.synced.island

import android.content.Context
import com.sameerasw.island.sdk.IslandAction
import com.sameerasw.island.sdk.IslandActionType
import com.sameerasw.island.sdk.IslandAnchor
import com.sameerasw.island.sdk.IslandAttachmentPayload
import com.sameerasw.island.sdk.IslandCellPayload
import com.sameerasw.island.sdk.IslandInteractionsPayload
import com.sameerasw.island.sdk.IslandItemPayload
import com.sameerasw.island.sdk.IslandLinePayload
import com.sameerasw.island.sdk.IslandSurfaceKey
import com.sameerasw.island.sdk.IslandTone
import com.serranoie.app.synced.R
import com.serranoie.app.synced.lyrics.Beat

private const val ATTACHMENT = "lyrics"
private const val ACCESS_ITEM = "access"

/** Surface key the host uses when it asks for the lyrics card. */
val LYRICS_SURFACE = IslandSurfaceKey.attachment(ATTACHMENT)

/** The lyrics card under the media player. A null [beat] means no pulse. */
fun lyricsAttachment(title: String?, beat: Beat?) = IslandAttachmentPayload(
    key = ATTACHMENT,
    anchor = IslandAnchor.MEDIA,
    heightDp = 72,
    matchTitle = title,
    beatMs = beat?.periodMs,
    beatOffsetMs = beat?.offsetMs,
)

/** Alert item asking the user to grant media access; tapping it opens Synced. */
fun accessPrompt(context: Context): IslandItemPayload {
    val open = IslandAction(IslandActionType.OPEN_APP)
    return IslandItemPayload(
        key = ACCESS_ITEM,
        compact = listOf(
            IslandCellPayload(
                key = ACCESS_ITEM,
                text = context.getString(R.string.island_access_cell),
                tone = IslandTone.ALERT,
            ),
        ),
        line = IslandLinePayload(
            start = context.getString(R.string.island_access_title),
            end = context.getString(R.string.island_access_action),
            tone = IslandTone.ALERT,
        ),
        dismissible = true,
        interactions = IslandInteractionsPayload(onTap = open, onExpandedTap = open, onOpen = open),
    )
}
