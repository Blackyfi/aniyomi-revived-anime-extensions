package aniyomi.lib.uqloadextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class UqloadExtractor(private val client: OkHttpClient) {

    companion object {
        // Every other uqload domain redirects here, and some ISPs block the old ones.
        const val BASE_URL = "https://uqload.vc/"

        private val hostRegex by lazy { Regex("""https?://(?:www\.)?[^/]+/""") }

        // sources: ["url"] (old inline player) or sources:[{file:"url"}] (packed jwplayer)
        private val SOURCE_REGEX by lazy { Regex("""sources:\s*\[\s*(?:\{\s*file:\s*)?"([^"]+)"""") }
    }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val fixedUrl = if (url.startsWith(BASE_URL, true)) url else url.replace(hostRegex, BASE_URL)
        val doc = client.newCall(GET(fixedUrl)).awaitSuccess().useAsJsoup()
        // The player setup used to be inline; it now ships inside a packed eval() script.
        val script = doc.selectFirst("script:containsData(sources:)")?.data()
            ?: doc.select("script").map { it.data() }
                .filter { "eval(function(p,a,c,k,e,d)" in it }
                .firstNotNullOfOrNull(JsUnpacker::unpackAndCombine)
            ?: return emptyList()

        val videoUrl = SOURCE_REGEX.find(script)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") }
            ?: return emptyList()

        val videoHeaders = Headers.headersOf("Referer", BASE_URL)
        val quality = if (prefix.isNotBlank()) "${prefix.trim()} Uqload" else "Uqload"

        return listOf(Video(videoUrl, quality, videoUrl, videoHeaders))
    }
}
