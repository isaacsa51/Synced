package com.serranoie.app.synced

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricLine(val timeMs: Long, val text: String)

object Lrclib {
    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT = "Synced/1.0"
    private val tag = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")

    fun fetch(artist: String, track: String, album: String?, durationSec: Long): List<LyricLine> {
        val exact = buildList {
            add("artist_name" to artist)
            add("track_name" to track)
            album?.takeIf { it.isNotBlank() }?.let { add("album_name" to it) }
            if (durationSec > 0) add("duration" to durationSec.toString())
        }
        get("$BASE/get?${query(exact)}")?.let { JSONObject(it) }?.synced()?.let { return it }

        val search = get("$BASE/search?${query(listOf("q" to "$artist $track"))}") ?: return emptyList()
        val results = JSONArray(search)
        for (i in 0 until results.length()) {
            results.getJSONObject(i).synced()?.let { return it }
        }
        return emptyList()
    }

    fun parse(lrc: String): List<LyricLine> =
        lrc.lineSequence()
            .flatMap { raw ->
                val stamps = tag.findAll(raw).toList()
                val text = raw.substring(stamps.lastOrNull()?.range?.last?.plus(1) ?: 0).trim()
                stamps.map { m ->
                    val (min, sec) = m.destructured
                    LyricLine(min.toLong() * 60_000 + (sec.toDouble() * 1000).toLong(), text)
                }
            }
            .sortedBy { it.timeMs }
            .toList()

    private fun JSONObject.synced(): List<LyricLine>? =
        optString("syncedLyrics").takeIf { !isNull("syncedLyrics") && it.isNotBlank() }?.let(::parse)?.ifEmpty { null }

    private fun query(params: List<Pair<String, String>>) =
        params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
