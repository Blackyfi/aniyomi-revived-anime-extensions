package eu.kanade.tachiyomi.animeextension.ar.animezid

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.megamaxmultiserver.MegaMaxMultiServer
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.voeextractor.VoeExtractor
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
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest

class AnimeZid :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeZid"

    override val baseUrl = "https://animezid.org"

    override val lang = "ar"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = categoryRequest("anime", page)

    override fun popularAnimeParse(response: Response) = parseCards(response)

    // =============================== Latest ===============================
    // Newest episodes; each card opens the episode, which is mapped back to its series.
    override fun latestUpdatesRequest(page: Int) = categoryRequest("new-anime-eps", page)

    override fun latestUpdatesParse(response: Response) = parseCards(response).let { page ->
        AnimesPage(page.animes.distinctBy { it.title }, page.hasNextPage)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search.php".toHttpUrl().newBuilder()
                .addQueryParameter("keywords", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, headers)
        }
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.toUriPart() ?: "anime"
        return categoryRequest(category, page)
    }

    override fun searchAnimeParse(response: Response) = parseCards(response)

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("يتم تجاهل التصنيف عند البحث بالاسم"),
        CategoryFilter(),
    )

    private class CategoryFilter :
        AnimeFilter.Select<String>(
            "التصنيف",
            CATEGORIES.map { it.first }.toTypedArray(),
        ) {
        fun toUriPart() = CATEGORIES[state].second
    }

    // Series categories page with series_page, episode and movie categories with page.
    private fun categoryRequest(category: String, page: Int) = GET(
        "$baseUrl/category.php".toHttpUrl().newBuilder()
            .addQueryParameter("cat", category)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("series_page", page.toString())
            .build(),
        headers,
    )

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val document = fetchShowPage(anime.url)
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text()?.let(::cleanTitle) ?: anime.title
            thumbnail_url = document.selectFirst("figure img")?.attr("abs:src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.selectFirst("section p, p.az-cinema-summary")?.text()
            genre = document.select("nav[class*=breadcrumb] a[href*=category.php]").eachText().joinToString()
            status = SAnime.UNKNOWN
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // Episode pages that belong to a series show the series instead; movies stay on their own page.
    private suspend fun fetchShowPage(url: String): Document {
        val document = client.newCall(GET(baseUrl + url, headers)).awaitSuccess().useAsJsoup()
        if (!url.contains("watch.php")) return document
        val seriesUrl = seriesLink(document) ?: return document
        return client.newCall(GET(seriesUrl, headers)).awaitSuccess().useAsJsoup()
    }

    private fun seriesLink(document: Document) = document.select("nav[class*=breadcrumb] a[href*=/series/]")
        .firstOrNull { !it.attr("href").contains("/season/") }
        ?.attr("abs:href")

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = fetchShowPage(anime.url)
        if (document.location().contains("watch.php")) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(document.location())
                    name = "مشاهدة"
                    episode_number = 1F
                },
            )
        }

        val seasons = document.select("a.az-card__link[href*=/season/]")
            .map { it.attr("abs:href") }
            .distinct()
        val multiSeason = seasons.size > 1
        return seasons.flatMapIndexed { index, seasonUrl ->
            seasonEpisodes(seasonUrl).map { (number, link) ->
                SEpisode.create().apply {
                    setUrlWithoutDomain(link)
                    val label = number.toBigDecimal().stripTrailingZeros().toPlainString()
                    name = if (multiSeason) "الموسم ${index + 1} - الحلقة $label" else "الحلقة $label"
                    episode_number = if (multiSeason) (index + 1) * 1000F + number else number
                }
            }
        }.sortedByDescending { it.episode_number }
    }

    // Each season lists 60 episodes per page.
    private suspend fun seasonEpisodes(seasonUrl: String): List<Pair<Float, String>> {
        val episodes = mutableListOf<Pair<Float, String>>()
        var page = 1
        while (true) {
            val url = if (page == 1) seasonUrl else "$seasonUrl?page=$page"
            val document = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
            document.select("[data-episode-number]").forEach { item ->
                val link = item.selectFirst("a[href*=watch.php]")?.attr("abs:href") ?: return@forEach
                episodes += (item.attr("data-episode-number").toFloatOrNull() ?: 0F) to link
            }
            val hasNext = document.select(".pagination a.page-link[href*=page=${page + 1}]").isNotEmpty()
            if (!hasNext || page >= 50) break
            page++
        }
        return episodes.distinctBy { it.second }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val vid = (baseUrl + episode.url).toHttpUrl().queryParameter("vid")
            ?: throw Exception("رابط الحلقة غير صالح")
        val playUrl = "$baseUrl/play.php?vid=$vid"
        val playPage = client.newCall(GET(playUrl, headers.newBuilder().set("Referer", baseUrl + episode.url).build()))
            .awaitSuccess().useAsJsoup()
        val player = playPage.selectFirst("[data-playback-create-url]")
        if (player == null) {
            if (playPage.selectFirst(".az-app-only-poster") != null) {
                throw Exception("هذه الحلقة متاحة في تطبيق أنمي زد فقط")
            }
            throw Exception("لم يتم العثور على المشغل")
        }

        val playback = Playback(player)
        val session = playback.createSession(vid)
        val embeds = session.sources
            .filter { it.type == "embedded_web" && SUPPORTED_PROVIDERS.any { name -> it.provider.equals(name, true) } }
            // The site allows only a handful of resolve calls in a short time, so ask for one at a time.
            .mapNotNull { source -> runCatching { source.provider to playback.resolve(session, source) }.getOrNull() }

        return embeds.parallelCatchingFlatMap { (provider, url) -> extractVideos(url, provider) }.sort()
    }

    private inner class Playback(player: Element) {
        private val createUrl = player.attr("abs:data-playback-create-url")
        private val bits = player.attr("data-pb-v").toIntOrNull() ?: 0
        private val csrf = player.attr("data-playback-csrf")
        private val token = xorHex(player.attr("data-pb-a"), player.attr("data-pb-b"))
        private val sessionToken = csrf.ifEmpty { token }
        private var warm: String? = null

        init {
            if (bits and BIT_COOKIE != 0) {
                val cookie = Cookie.Builder()
                    .domain(baseUrl.toHttpUrl().host)
                    .path("/web-playback")
                    .name("az_pc")
                    .value(sha256("pc|$sessionToken").toHex().take(32))
                    .secure()
                    .build()
                client.cookieJar.saveFromResponse(createUrl.toHttpUrl(), listOf(cookie))
            }
        }

        suspend fun createSession(vid: String): SessionDto {
            if (bits and BIT_WARM != 0) {
                warm = post(createUrl, """{"content_id":"$vid","warm":1}""")["w"]?.jsonPrimitive?.content
                delay(1100)
            }
            return post(createUrl, """{"content_id":"$vid"}""").toString().parseAs<SessionDto>()
        }

        suspend fun resolve(session: SessionDto, source: SourceDto): String {
            val url = if (bits and BIT_ROUTE != 0 && source.r != null) {
                createUrl.toHttpUrl().resolve(source.r)!!.toString()
            } else {
                "$createUrl/${session.session_id}/sources/${source.id}/resolve"
            }
            val launch = post(url, "{}")["launch_url"]!!.jsonPrimitive.content
            val launchUrl = baseUrl.toHttpUrl().resolve(launch)!!.toString()
            // The launch page answers an iframe navigation with a redirect to the host embed.
            val response = noRedirectClient.newCall(GET(launchUrl, iframeHeaders)).execute()
            return response.use { it.header("Location") } ?: throw Exception("No embed for ${source.provider}")
        }

        private suspend fun post(url: String, body: String): JsonObject {
            val path = url.toHttpUrl().encodedPath
            val headers = apiHeaders.newBuilder().apply {
                if (csrf.isNotEmpty()) {
                    add("X-Playback-CSRF", csrf)
                } else {
                    val salt = if (bits and BIT_SALT != 0) "$PROOF_SALT|" else ""
                    add("X-Playback-Proof", sha256("$salt$token|$path").toHex())
                }
                warm?.let { add("X-Playback-Warm", it) }
            }.build()
            val text = client.newCall(POST(url, headers, body.toRequestBody(JSON_MEDIA_TYPE)))
                .awaitSuccess().use { it.body.string() }
            val json = text.parseAs<JsonObject>()
            if (bits and BIT_ENCRYPTED == 0 || "d" !in json || "n" !in json) return json
            return decrypt(json["d"]!!.jsonPrimitive.content, json["n"]!!.jsonPrimitive.content).parseAs<JsonObject>()
        }

        private fun decrypt(data: String, nonce: String): String {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            var block = -1
            var key = ByteArray(0)
            for (i in bytes.indices) {
                if (i shr 5 != block) {
                    block = i shr 5
                    key = sha256("$sessionToken|enc|$nonce|$block")
                }
                bytes[i] = (bytes[i].toInt() xor key[i and 31].toInt()).toByte()
            }
            return String(bytes)
        }
    }

    private val noRedirectClient by lazy { client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    private val apiHeaders by lazy {
        headersBuilder()
            .add("Accept", "*/*")
            .add("Origin", baseUrl)
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Dest", "empty")
            .addClientHints()
            .build()
    }

    private val iframeHeaders by lazy {
        headersBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Upgrade-Insecure-Requests", "1")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Dest", "iframe")
            .addClientHints()
            .build()
    }

    private fun Headers.Builder.addClientHints() = apply {
        add("sec-ch-ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"131\"")
        add("sec-ch-ua-mobile", "?1")
        add("sec-ch-ua-platform", "\"Android\"")
    }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val megaMax by lazy { MegaMaxMultiServer(client, headers) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    private suspend fun extractVideos(url: String, provider: String): List<Video> = when {
        "megamax" in url -> megaMax.extractUrls(url)
            .filter { it.name.lowercase() in MEGAMAX_DRIVERS }
            .parallelCatchingFlatMap { mirror -> extractVideos(mirror.url, mirror.quality) }
        "uqload" in url -> uqloadExtractor.videosFromUrl(url, provider.takeIf { it.endsWith("p") }.orEmpty())
        "lulu" in url -> luluExtractor.videosFromUrl(url, "")
        "mixdrop" in url -> mixDropExtractor.videosFromUrl(url, prefix = provider.takeIf { it.endsWith("p") }?.let { "$it " }.orEmpty())
        // Voe's MP4 download link answers 403 to players, only its HLS stream plays.
        "voe" in url -> voeExtractor.videosFromUrl(url).filterNot { it.quality.endsWith(":MP4") }
        DOOD_REGEX.containsMatchIn(url) -> doodExtractor.videosFromUrl(url)
        // StreamRuby keeps moving to new domains, so go by the provider name.
        provider.equals("StreamRuby", true) -> streamRubyVideos(url)
        else -> streamWishExtractor.videosFromUrl(url) { "$provider - $it" }
    }

    // The embed page only holds a form; posting it returns the player with the HLS source.
    private suspend fun streamRubyVideos(url: String): List<Video> {
        val embedUrl = client.newCall(GET(url, headers)).awaitSuccess().use { it.request.url }
        val origin = "${embedUrl.scheme}://${embedUrl.host}"
        val form = FormBody.Builder()
            .add("op", "embed")
            .add("file_code", embedUrl.pathSegments.last().substringAfterLast("-").substringBefore("."))
            .add("auto", "1")
            .add("referer", "$baseUrl/")
            .build()
        val html = client.newCall(POST("$origin/dl", headers.newBuilder().set("Referer", embedUrl.toString()).build(), form))
            .awaitSuccess().use { it.body.string() }
        val playlist = HLS_SOURCE_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        return playlistUtils.extractFromHls(playlist, referer = "$origin/", videoNameGen = { "StreamRuby - $it" })
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(compareByDescending { it.quality.contains(quality) })
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "الجودة المفضلة"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES.map { it.removeSuffix("p") }.toTypedArray()
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun parseCards(response: Response): AnimesPage {
        val url = response.request.url
        val page = url.queryParameter("page")?.toIntOrNull() ?: 1
        val document = response.useAsJsoup()
        val animes = document.select("article.az-card > a.az-card__link")
            // Skip the recommendation rails, they repeat on every page.
            .filterNot { it.parents().any { parent -> parent.hasClass("az-rail") } }
            .map(::cardToAnime)
            .distinctBy { it.url }
        val hasNext = document.select(".pagination a.page-link").any { link ->
            PAGE_REGEX.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() == page + 1
        }
        return AnimesPage(animes, hasNext)
    }

    private fun cardToAnime(link: Element) = SAnime.create().apply {
        setUrlWithoutDomain(link.attr("abs:href"))
        val img = link.selectFirst("img")
        title = cleanTitle(link.selectFirst(".az-card__title")?.text() ?: img?.attr("alt").orEmpty())
        thumbnail_url = img?.attr("abs:src")
    }

    // "مسلسل X مترجم" or "انمي X الحلقة 12 مدبلجة" -> "X"
    private fun cleanTitle(title: String) = title
        .replace(TITLE_PREFIX_REGEX, "")
        .replace(TITLE_SUFFIX_REGEX, "")
        .trim()
        .ifEmpty { title }

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun xorHex(a: String, b: String): String {
        if (a.isEmpty() || a.length != b.length) return ""
        return a.chunked(2).zip(b.chunked(2)) { x, y -> "%02x".format(x.toInt(16) xor y.toInt(16)) }.joinToString("")
    }

    @Serializable
    private class SessionDto(val session_id: String, val sources: List<SourceDto> = emptyList())

    @Serializable
    private class SourceDto(
        val id: String,
        val provider: String = "",
        val type: String = "",
        val r: String? = null,
    )

    companion object {
        private const val BIT_ROUTE = 1
        private const val BIT_ENCRYPTED = 2
        private const val BIT_SALT = 4
        private const val BIT_WARM = 8
        private const val BIT_COOKIE = 16
        private const val PROOF_SALT = "a7c3e91f5d20b846"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val SUPPORTED_PROVIDERS = listOf("MegaMax", "StreamRuby", "Uqload", "DoodStream")
        private val MEGAMAX_DRIVERS = setOf("lulustream", "mixdrop", "voe", "uqload")
        private val DOOD_REGEX = Regex("dood|ds2play|playmogo|d0000d|d000d")
        private val HLS_SOURCE_REGEX = Regex("""sources:\s*\[\s*\{\s*file:\s*"([^"]+)"""")
        private val PAGE_REGEX = Regex("""[?&](?:series_)?page=(\d+)""")
        private val TITLE_PREFIX_REGEX = Regex("""^(?:مسلسل|انمي|أنمي|كرتون|فيلم)\s+""")
        private val TITLE_SUFFIX_REGEX = Regex("""\s+(?:الحلقة\s+\d+.*|(?:مترجم|مترجمة|مدبلج|مدبلجة))$""")

        private val CATEGORIES = listOf(
            "الأنمي" to "anime",
            "أنمي مترجم" to "translated-anime",
            "أنمي مدبلج" to "dubbed-anime",
            "مسلسلات كرتون" to "cartoon",
            "سبيستون" to "spacetoon",
            "مسلسلات ديزني" to "disney-series",
            "الأفلام" to "movies",
            "أفلام انيميشن مدبلجة" to "dubbed-animation",
            "ديزني بالعربية الفصحى" to "disney-arabic",
            "ديزني بالمصري" to "disney-masr",
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
