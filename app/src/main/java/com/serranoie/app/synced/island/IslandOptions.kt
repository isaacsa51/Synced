package com.serranoie.app.synced.island

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import com.sameerasw.island.sdk.IslandOptionPayload
import com.serranoie.app.synced.R

/** One toggle shown in the host's settings for Synced. */
enum class IslandOption(val key: String, @StringRes val title: Int, val default: Boolean) {
    BEAT_PULSE("beat_pulse", R.string.option_beat_pulse, true),
    LYRIC_ANIMATION("lyric_animation", R.string.option_lyric_animation, true),
    THREE_LINES("three_lines", R.string.option_three_lines, false),
    ACCENT_GLOW("accent_glow", R.string.option_accent_glow, false),
}

/** Saved toggle values, readable from Compose so the lyrics redraw when one changes. */
class IslandOptions(private val context: Context) {
    private val prefs = context.getSharedPreferences("island", Context.MODE_PRIVATE)
    private val values = IslandOption.entries.associateWith { mutableStateOf(prefs.getBoolean(it.key, it.default)) }

    operator fun get(option: IslandOption): Boolean = values.getValue(option).value

    /** Returns false for a key this app doesn't know. */
    fun set(key: String, checked: Boolean): Boolean {
        val option = IslandOption.entries.firstOrNull { it.key == key } ?: return false
        prefs.edit().putBoolean(key, checked).apply()
        values.getValue(option).value = checked
        return true
    }

    fun payload() = IslandOption.entries.map { IslandOptionPayload(it.key, context.getString(it.title), this[it]) }
}
