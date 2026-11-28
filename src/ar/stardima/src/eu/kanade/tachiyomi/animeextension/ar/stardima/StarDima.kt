package eu.kanade.tachiyomi.animeextension.ar.stardima

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
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
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class StarDima :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "StarDima"

    override val baseUrl = "https://www.stardima.com"

    override val lang = "ar"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/mosalsalat?page=$page", headers)

    override fun popularAnimeParse(response: Response) = parseGrid(response)

    // =============================== Latest ===============================
    // New releases are grouped by day and link to single episodes; open their show instead.
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/newrelases", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animes = parseCards(response.useAsJsoup()).onEach { anime ->
            anime.url = anime.url.substringBefore("/play/")
            anime.title = anime.title.substringBefore(" - حلقة").trim()
        }.distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, headers)
        }
        val section = filters.filterIsInstance<SectionFilter>().firstOrNull()?.toUriPart() ?: "mosalsalat"
        return GET("$baseUrl/$section?page=$page", headers)
    }

    override fun searchAnimeParse(response: Response) = parseGrid(response)

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("يتم تجاهل القسم عند البحث بالاسم"),
        SectionFilter(),
    )

    private class SectionFilter :
        AnimeFilter.Select<String>(
            "القسم",
            SECTIONS.map { it.first }.toTypedArray(),
        ) {
        fun toUriPart() = SECTIONS[state].second
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()
        val hero = document.selectFirst("section:has(h1)")
        return SAnime.create().apply {
            title = hero?.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")!!.attr("content")
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            description = hero?.selectFirst("p")?.text()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            genre = hero?.select("a[href*=/search/]")?.eachText()?.joinToString()
            status = if (document.location().contains("/movie/")) SAnime.COMPLETED else SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.newCall(GET(baseUrl + anime.url, headers)).awaitSuccess().useAsJsoup()
        val playUrl = document.selectFirst("section a[href*=/play/]")?.attr("abs:href")
            ?: throw Exception("لا توجد حلقات متاحة")

        // Movies have a single play page.
        if (anime.url.startsWith("/movie/")) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(playUrl)
                    name = "مشاهدة"
                    episode_number = 1F
                },
            )
        }

        val showPath = anime.url.trimEnd('/')
        val playPage = client.newCall(GET(playUrl, headers)).awaitSuccess().useAsJsoup()
        val seasons = playPage.select("a.season-item[data-season-id]")
        if (seasons.isEmpty()) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(playUrl)
                    name = "الحلقة 1"
                    episode_number = 1F
                },
            )
        }

        val ajaxHeaders = headersBuilder().add("X-Requested-With", "XMLHttpRequest").build()
        return seasons.flatMapIndexed { index, season ->
            val seasonName = season.attr("data-season-number")
            val seasonUrl = "$baseUrl/series/season/${season.attr("data-season-id")}?X-Requested-With=XMLHttpRequest"
            client.newCall(GET(seasonUrl, ajaxHeaders)).awaitSuccess().parseAs<SeasonDto>().episodes.map { ep ->
                SEpisode.create().apply {
                    url = "$showPath/play/${ep.id}"
                    name = buildString {
                        if (seasons.size > 1) append("$seasonName - ")
                        append("الحلقة ${ep.title.ifBlank { ep.episode_number.toString() }}")
                    }
                    // Keep seasons apart in the list: 1.x, 2.x...
                    episode_number = if (seasons.size > 1) (index + 1) * 1000F + ep.episode_number else ep.episode_number.toFloat()
                    scanlator = if (ep.is_exclusive) "VIP" else null
                }
            }
        }.sortedByDescending { it.episode_number }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val playPage = client.newCall(GET(baseUrl + episode.url, headers)).awaitSuccess().useAsJsoup()
        val watchUrl = playPage.selectFirst("#video-player-container iframe[src]")?.attr("abs:src")
            ?: playPage.selectFirst("meta[property=og:video]")?.attr("content")?.takeIf { "/watch/" in it }
            ?: throw Exception("هذه الحلقة متاحة لمشتركي VIP فقط")

        val playerUrl = watchUrl.toHttpUrl()
        val playerOrigin = "${playerUrl.scheme}://${playerUrl.host}"
        val hashId = playerUrl.pathSegments.last()
        val embed = client.newCall(GET(watchUrl, headers)).awaitSuccess().useAsJsoup()
        val page = embed.selectFirst("[data-page]")!!.attr("data-page").parseAs<InertiaPage>()

        val apiHeaders = headersBuilder()
            .set("Referer", watchUrl)
            .add("Accept", "application/json")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
        return page.props.video.servers
            .filter { it.id != 0L && it.status == "completed" && !it.is_vip }
            .parallelCatchingFlatMap { server ->
                val link = client.newCall(GET("$playerOrigin/embed/$hashId/server/${server.id}/url", apiHeaders))
                    .awaitSuccess().parseAs<ServerUrlDto>()
                extractVideos(unwrapEmbed(link.watch_url), server.name)
            }
            .sort()
    }

    // Some hosts are wrapped in a relay page that carries the real embed in its "id" parameter.
    private fun unwrapEmbed(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        return parsed.queryParameter("id")?.takeIf { it.startsWith("http") } ?: url
    }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    private suspend fun extractVideos(url: String, serverName: String): List<Video> = when {
        "uqload" in url -> uqloadExtractor.videosFromUrl(url)
        "lulu" in url -> luluExtractor.videosFromUrl(url, "")
        "mixdrop" in url -> mixDropExtractor.videosFromUrl(url)
        // hgcloud only serves a script that sends the browser on to one of these mirrors.
        HGCLOUD_REGEX.containsMatchIn(url) ->
            vidHideExtractor.videosFromUrl(HGCLOUD_REGEX.replace(url, HGCLOUD_MIRRORS.random())) { "StreamHG - $it" }
                // Its /stream/ playlist hides the segments in PNG files that mpv can't play.
                .filterNot { it.videoUrl?.contains("/stream/") == true }
        "dood" in url -> doodExtractor.videosFromUrl(url)
        "voe" in url -> voeExtractor.videosFromUrl(url).filterNot { it.quality.endsWith(":MP4") }
        STREAMWISH_REGEX.containsMatchIn(url) -> streamWishExtractor.videosFromUrl(url)
        url.substringBefore("?").let { it.endsWith(".m3u8") || it.endsWith(".mp4") } ->
            listOf(Video(url, "$serverName - Direct", url, headers))
        else -> emptyList()
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
    private fun parseGrid(response: Response): AnimesPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val document = response.useAsJsoup()
        val lastPage = document.selectFirst("#video-grid")?.attr("data-last-page")?.toIntOrNull() ?: 1
        return AnimesPage(parseCards(document).distinctBy { it.url }, page < lastPage)
    }

    private fun parseCards(document: Document): List<SAnime> = document.select("img[alt^=Poster for]").mapNotNull { img ->
        val card = img.parent() ?: return@mapNotNull null
        val link = card.selectFirst("a[href*=/tvshow/], a[href*=/movie/]") ?: return@mapNotNull null
        cardToAnime(link, img)
    }

    private fun cardToAnime(link: Element, img: Element) = SAnime.create().apply {
        setUrlWithoutDomain(link.attr("abs:href"))
        title = link.selectFirst("h3")?.text() ?: img.attr("alt").removePrefix("Poster for ")
        thumbnail_url = img.attr("abs:src")
    }

    @Serializable
    private class SeasonDto(val episodes: List<EpisodeDto> = emptyList())

    @Serializable
    private class EpisodeDto(
        val id: Long,
        val episode_number: Int = 0,
        val title: String = "",
        val is_exclusive: Boolean = false,
    )

    @Serializable
    private class InertiaPage(val props: PropsDto)

    @Serializable
    private class PropsDto(val video: VideoDto)

    @Serializable
    private class VideoDto(val servers: List<ServerDto> = emptyList())

    @Serializable
    private class ServerDto(
        val id: Long,
        val name: String = "",
        val status: String = "",
        val is_vip: Boolean = false,
    )

    @Serializable
    private class ServerUrlDto(val watch_url: String)

    companion object {
        private val HGCLOUD_REGEX = Regex("""(?<=//)(?:www\.)?(?:hgcloud\.to|hglink\.to)""")
        private val HGCLOUD_MIRRORS = listOf("hanerix.com", "vibuxer.com", "audinifer.com")
        private val STREAMWISH_REGEX = Regex("streamwish|playerwish|wishfast|strwish|swdyu")

        private val SECTIONS = listOf(
            "مسلسلات" to "mosalsalat",
            "أفلام" to "aflam",
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
