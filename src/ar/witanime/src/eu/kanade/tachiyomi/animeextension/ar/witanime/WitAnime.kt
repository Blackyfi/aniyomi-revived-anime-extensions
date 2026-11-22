package eu.kanade.tachiyomi.animeextension.ar.witanime

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidbomextractor.VidBomExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.vkextractor.VkExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animeextension.ar.witanime.extractors.SharedExtractor
import eu.kanade.tachiyomi.animeextension.ar.witanime.extractors.SoraPlayExtractor
import eu.kanade.tachiyomi.animeextension.ar.witanime.extractors.VideaExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

class WitAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "WIT ANIME"

    override val baseUrl = "https://witanime.site"

    override val lang = "ar"

    override val supportsLatest = true

    // The site answers its player endpoints with 404 unless the request carries the client
    // hints a browser sends.
    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("sec-ch-ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
        .add("sec-ch-ua-mobile", "?0")
        .add("sec-ch-ua-platform", "\"Windows\"")

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = searchAnimeRequest(page, "", AnimeFilterList())

    override fun popularAnimeParse(response: Response) = parseCardPage(response.asJsoup())

    // =============================== Latest ===============================
    // The site has no paged list of new episodes, only the section on the home page.
    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val section = response.asJsoup().select("section")
            .firstOrNull { it.selectFirst("h2")?.text()?.contains("أحدث الحلقات") == true }
            ?: return AnimesPage(emptyList(), false)

        val animes = section.select("a[href*=/watch/]").mapNotNull { link ->
            val slug = link.attr("href").toHttpUrl().pathSegments.getOrNull(1) ?: return@mapNotNull null
            SAnime.create().apply {
                url = "/anime/$slug"
                title = link.selectFirst("h3")?.text() ?: link.selectFirst("img")?.attr("alt").orEmpty()
                thumbnail_url = link.selectFirst("img")?.attr("abs:src")
            }
        }.distinctBy { it.url }

        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = when {
        query.isNotBlank() -> {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query.trim())
                .addQueryParameter("page", page.toString())
                .build()
            GET(url, headers)
        }
        else -> {
            val section = filters.firstInstanceOrNull<SectionFilter>()?.toUriPart() ?: "browse"
            val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "Most Viewed"
            val url = "$baseUrl/$section${pagePath(page)}".toHttpUrl().newBuilder()
                .addQueryParameter("sort", sort)
                .build()
            GET(url, headers)
        }
    }

    override fun searchAnimeParse(response: Response) = parseCardPage(response.asJsoup())

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("الفلاتر لا تعمل مع البحث بالاسم"),
        SectionFilter(),
        SortFilter(),
    )

    private class SectionFilter :
        UriPartFilter(
            "القسم",
            arrayOf(
                Pair("الأنمي", "browse"),
                Pair("الأفلام", "movies"),
            ),
        )

    private class SortFilter :
        UriPartFilter(
            "الترتيب",
            arrayOf(
                Pair("الأكثر مشاهدة", "Most Viewed"),
                Pair("الأعلى تقييمًا", "Top Rated"),
                Pair("A-Z", "A-Z"),
                Pair("Z-A", "Z-A"),
            ),
        )

    private open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val document = response.asJsoup()
        val info = document.selectFirst("h1")!!.parent()!!

        title = info.selectFirst("h1")!!.text()
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        genre = info.select("span.rounded-full").eachText().joinToString()

        val fields = info.select("div.grid > div").associate {
            it.selectFirst("span")?.text().orEmpty() to it.selectFirst("p")?.text().orEmpty()
        }
        author = fields["الاستوديو"]

        val badges = info.select("div.mb-4 > span").eachText()
        status = when {
            badges.any { it.contains("مستمر") || it.contains("يعرض") } -> SAnime.ONGOING
            badges.any { it.contains("مكتمل") || it.contains("منتهي") } -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        description = buildString {
            info.selectFirst("p.leading-relaxed")?.text()?.let { append(it).append("\n\n") }
            info.selectFirst("h1 + p")?.text()?.takeIf(String::isNotBlank)?.let { append("الاسم: $it\n") }
            badges.firstOrNull()?.let { append("النوع: $it\n") }
            fields.filterValues(String::isNotBlank).forEach { (key, value) -> append("$key: $value\n") }
        }.trim()
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        // Movies have a single watch page at /watch/movie/<slug>.
        if (response.request.url.pathSegments.firstOrNull() == "movie") {
            val link = document.selectFirst("a[href*=/watch/movie/]") ?: return emptyList()
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(link.attr("abs:href"))
                    name = "الفيلم"
                    episode_number = 1F
                },
            )
        }

        // Each episode is linked twice (thumbnail list and number grid), so keep one per URL.
        val slug = response.request.url.pathSegments.getOrNull(1) ?: return emptyList()
        return document.select("a[href*=/watch/$slug/]")
            .distinctBy { it.attr("href") }
            .mapNotNull { link ->
                val number = link.attr("href").substringAfterLast("/").toFloatOrNull()
                    ?: return@mapNotNull null
                SEpisode.create().apply {
                    setUrlWithoutDomain(link.attr("abs:href"))
                    name = "الحلقة ${number.toInt()}"
                    episode_number = number
                }
            }
            .sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    // The watch page loads its servers with a POST to "<episode>/sources". Each server
    // has a token: POST /watch/stream-source/<token> unlocks it, then
    // /watch/stream-gate/<token> leads to the host's embed page.
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val pageUrl = baseUrl + episode.url
        val document = client.newCall(GET(pageUrl, headers)).awaitSuccess().asJsoup()
        val csrf = document.selectFirst("meta[name=csrf-token]")?.attr("content")
            ?: return emptyList()
        val sourcesPath = SOURCES_URL_REGEX.find(document.html())?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?: "${episode.url}/sources"

        val apiHeaders = headersBuilder()
            .set("Referer", pageUrl)
            .add("Origin", baseUrl)
            .add("Accept", "application/json")
            .add("X-CSRF-TOKEN", csrf)
            // No Sec-Fetch-* here: the site answers 404 when a non-browser client sends them.
            .build()

        val manifest = call(POST(baseUrl + sourcesPath, apiHeaders))
            .parseAs<SourcesDto>()

        val servers = manifest.players.flatMap { (quality, list) -> list.map { quality to it } }
            .filter { (_, server) -> TOKEN_REGEX.matches(server.token) }
            // Every server costs two rate-limited requests, so skip hosts we can't play.
            .filterNot { (_, server) -> server.label?.lowercase() in UNSUPPORTED_SERVERS }

        // The site rate limits its player endpoints, so resolve the servers one at a time.
        val videos = mutableListOf<Video>()
        for ((quality, server) in servers) {
            val name = buildString {
                append(server.label ?: "WitAnime")
                append(" - ").append(quality)
                if (server.version == "dub") append(" - مدبلج")
            }
            val found = runCatching {
                val embedUrl = resolveServer(server.token, apiHeaders) ?: return@runCatching emptyList()
                extractVideos(embedUrl, "$name: ")
            }.getOrDefault(emptyList())
            videos += found
            delay(SERVER_DELAY_MS)
        }
        return videos
    }

    /** Like awaitSuccess, but waits and retries once when the site answers 429. */
    private suspend fun call(request: Request): Response {
        val response = client.newCall(request).await()
        if (response.code != 429) {
            if (!response.isSuccessful) {
                response.close()
                throw IOException("HTTP error ${response.code} for ${request.url.encodedPath}")
            }
            return response
        }
        val waitSeconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 10) ?: 3
        response.close()
        delay(waitSeconds * 1000)
        return client.newCall(request).awaitSuccess()
    }

    private suspend fun resolveServer(token: String, apiHeaders: Headers): String? {
        // The gate only opens after the player has asked for this source.
        call(POST("$baseUrl/watch/stream-source/$token", apiHeaders)).close()

        // The gate 404s when it gets a Referer or Sec-Fetch-* headers from us.
        val gateHeaders = headersBuilder()
            .removeAll("Referer")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Upgrade-Insecure-Requests", "1")
            .build()

        val gate = call(GET("$baseUrl/watch/stream-gate/$token", gateHeaders))
        val finalUrl = gate.request.url
        if (finalUrl.host != baseUrl.toHttpUrl().host) {
            gate.close()
            return finalUrl.toString()
        }

        val document = gate.asJsoup()
        return document.selectFirst("iframe[src]")?.attr("abs:src")
            ?: document.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                ?.substringAfter("url=", "")?.takeIf(String::isNotBlank)
            ?: document.selectFirst("source[src], video[src]")?.attr("abs:src")
            ?: GATE_URL_REGEX.find(document.html())?.groupValues?.get(1)
    }

    private val soraPlayExtractor by lazy { SoraPlayExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val sharedExtractor by lazy { SharedExtractor(client) }
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val videaExtractor by lazy { VideaExtractor(client) }

    private suspend fun extractVideos(url: String, prefix: String): List<Video> = when {
        url.contains("soraplay") || url.contains("yonaplay") -> soraPlayExtractor.videosFromUrl(url, headers)
        url.contains("videa.hu") -> videaExtractor.videosFromUrl(url).map { Video(it.url, prefix + it.quality, it.videoUrl, it.headers) }
        HGCLOUD_REGEX.containsMatchIn(url) ->
            vidHideExtractor.videosFromUrl(HGCLOUD_REGEX.replace(url, HGCLOUD_MIRRORS.random())) { "${prefix}HGCloud: $it" }
        url.contains("dood") -> doodExtractor.videoFromUrl(url, "${prefix}Dood")?.let(::listOf)
        url.contains("4shared") -> sharedExtractor.videosFromUrl(url)?.let(::listOf)
        url.contains("dailymotion") -> dailymotionExtractor.videosFromUrl(url, prefix)
        url.contains("ok.ru") -> okruExtractor.videosFromUrl(url, prefix)
        url.contains("mp4upload") -> mp4uploadExtractor.videosFromUrl(url, headers, prefix)
        VIDBOM_REGEX.containsMatchIn(url) -> vidBomExtractor.videosFromUrl(url)
        STREAMWISH_REGEX.containsMatchIn(url) -> streamWishExtractor.videosFromUrl(url, prefix)
        FILEMOON_REGEX.containsMatchIn(url) -> filemoonExtractor.videosFromUrl(url, prefix = prefix)
        VIDHIDE_REGEX.containsMatchIn(url) -> vidHideExtractor.videosFromUrl(url) { "$prefix$it" }
        url.contains("voe") -> voeExtractor.videosFromUrl(url, prefix)
        url.contains("mixdrop") -> mixDropExtractor.videosFromUrl(url, prefix = prefix)
        url.contains("streamtape") -> streamTapeExtractor.videosFromUrl(url, "${prefix}Streamtape")
        url.contains("uqload") -> uqloadExtractor.videosFromUrl(url, prefix)
        url.contains("vk.com") || url.contains("vkvideo") -> vkExtractor.videosFromUrl(url, prefix)
        url.substringBefore("?").let { it.endsWith(".m3u8") || it.endsWith(".mp4") } ->
            listOf(Video(url, "${prefix}Direct", url, headers))
        else -> null
    } ?: emptyList()

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun pagePath(page: Int) = if (page > 1) "/page/$page" else ""

    private fun parseCardPage(document: Document): AnimesPage {
        val cards = document.select("div.grid > div[data-preview-card] > a[href]")
        val animes = cards.map(::cardToAnime).distinctBy { it.url }
        // The movies list pages with Livewire buttons instead of links, but always shows full pages.
        val hasNext = document.selectFirst("a[rel=next]") != null ||
            (document.location().contains("/movies") && cards.size >= MOVIES_PAGE_SIZE)
        return AnimesPage(animes, hasNext)
    }

    private fun cardToAnime(link: Element) = SAnime.create().apply {
        setUrlWithoutDomain(link.attr("abs:href"))
        val image = link.selectFirst("img")
        title = link.selectFirst("h3")?.text() ?: image?.attr("alt").orEmpty()
        thumbnail_url = image?.attr("abs:src")
    }

    private inline fun <reified T> AnimeFilterList.firstInstanceOrNull(): T? = filterIsInstance<T>().firstOrNull()

    @Serializable
    class SourcesDto(val players: Map<String, List<ServerDto>> = emptyMap())

    @Serializable
    class ServerDto(
        val token: String,
        val label: String? = null,
        val version: String? = null,
        val lang: String? = null,
    )

    companion object {
        private val SOURCES_URL_REGEX = Regex("""sourcesUrl:\s*'([^']+)'""")
        private val TOKEN_REGEX = Regex("^[a-f0-9]{64}$")
        private val GATE_URL_REGEX = Regex("""location(?:\.href)?\s*=\s*["'](https?://[^"']+)""")

        private val VIDBOM_REGEX = Regex("//v[aie]d[bp][aoe]?m")
        private val STREAMWISH_REGEX = Regex("streamwish|wishfast|swdyu|sfastwish|strwish|wishembed|hgplaycdn|playerwish")
        private val FILEMOON_REGEX = Regex("filemoon|moonplayer|kerapoxy|smdfs")

        // hgcloud.to only serves a script that sends the browser on to one of these mirrors.
        private val HGCLOUD_REGEX = Regex("""(?<=//)(?:www\.)?(?:hgcloud\.to|hglink\.to)""")
        private val HGCLOUD_MIRRORS = listOf("hanerix.com", "vibuxer.com", "audinifer.com")
        private val UNSUPPORTED_SERVERS = setOf("mega", "videas")

        private val VIDHIDE_REGEX = Regex("vidhide|filelions|vid-guard|dhtpre|dlions|mivalyo")

        private const val MOVIES_PAGE_SIZE = 18
        private const val SERVER_DELAY_MS = 600L

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES by lazy {
            PREF_QUALITY_ENTRIES.map { it.substringBefore("p") }.toTypedArray()
        }
    }
}
