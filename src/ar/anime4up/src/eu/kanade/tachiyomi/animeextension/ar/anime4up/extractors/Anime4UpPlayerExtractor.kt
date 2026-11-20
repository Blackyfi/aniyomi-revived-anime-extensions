package eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient

class Anime4UpPlayerExtractor(private val client: OkHttpClient, private val headers: Headers) {

    fun videosFromUrl(url: String, serverName: String): List<Video> {
        val script = client.newCall(GET(url, headers)).execute().use { response ->
            response.asJsoup().select("script:not([src])").map { it.data() }.firstOrNull { "streamUrl" in it }
        } ?: return emptyList()

        val streamUrl = STREAM_REGEX.find(script)?.groupValues?.get(1) ?: return emptyList()
        val subtitles = TRACKS_REGEX.find(script)?.groupValues?.get(1)?.let(::parseTracks).orEmpty()

        val master = client.newCall(GET(streamUrl, headers)).execute().use { it.body.string() }
        val variants = master.lines().zipWithNext().mapNotNull { (info, variantUrl) ->
            if (!info.startsWith("#EXT-X-STREAM-INF") || !variantUrl.startsWith("http")) return@mapNotNull null
            val height = RESOLUTION_REGEX.find(info)?.groupValues?.get(1)
            variantUrl to (height?.let { "${it}p" } ?: "Auto")
        }

        if (variants.isEmpty()) {
            return listOf(Video(streamUrl, "$serverName - Auto", streamUrl, subtitleTracks = subtitles))
        }
        return variants.map { (variantUrl, quality) ->
            Video(variantUrl, "$serverName - $quality", variantUrl, subtitleTracks = subtitles)
        }
    }

    private fun parseTracks(raw: String): List<Track> = runCatching {
        (Json.parseToJsonElement(raw) as JsonArray).mapNotNull {
            val track = it.jsonObject
            val file = track["file"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Track(file, track["label"]?.jsonPrimitive?.content ?: "Arabic")
        }
    }.getOrDefault(emptyList())

    companion object {
        private val STREAM_REGEX = Regex("""streamUrl\s*=\s*"([^"]+)"""")
        private val TRACKS_REGEX = Regex("""const tracks\s*=\s*(\[.*?\]);""")
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""")
    }
}
