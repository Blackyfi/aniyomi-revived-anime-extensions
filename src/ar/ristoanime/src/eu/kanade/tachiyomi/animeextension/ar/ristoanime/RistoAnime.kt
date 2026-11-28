package eu.kanade.tachiyomi.animeextension.ar.ristoanime

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.sendvidextractor.SendvidExtractor
import aniyomi.lib.sibnetextractor.SibnetExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidguardextractor.VidGuardExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
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
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class RistoAnime :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "RistoAnime"

    override val baseUrl = "https://ristoanime.cc"

    override val lang = "ar"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/series/" + if (page > 1) "?offset=$page" else "", headers)

    override fun popularAnimeParse(response: Response) = parseCards(response.useAsJsoup())

    // =============================== Latest ===============================
    // The home page lists the newest episodes; each one opens on its own page with the full episode list.
    override fun latestUpdatesRequest(page: Int) = GET(if (page > 1) "$baseUrl/?page=$page/" else "$baseUrl/", headers)

    override fun latestUpdatesParse(response: Response) = parseCards(response.useAsJsoup())

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder().addQueryParameter("s", query).build()
            return GET(url, headers)
        }
        val section = filters.filterIsInstance<SectionFilter>().firstOrNull()?.toUriPart() ?: "series"
        return when (section) {
            "movies" -> GET("$baseUrl/movies/" + if (page > 1) "page/$page/" else "", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = parseCards(response.useAsJsoup())

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
        return SAnime.create().apply {
            title = cleanTitle(document.selectFirst("h1.PostTitle")!!.text())
            thumbnail_url = document.selectFirst("div.Poster img")?.attr("abs:src")
            description = document.selectFirst("div.StoryArea p")?.text()
            genre = taxonomy(document, "النوع")?.select("a")?.eachText()?.joinToString()
            status = when (taxonomy(document, "الحالة")?.text()?.substringAfter(":")?.trim()) {
                "now" -> SAnime.ONGOING
                "end", "finished", "completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    private fun taxonomy(document: Document, label: String) = document.select("div.TaxContent > li").firstOrNull { li ->
        li.selectFirst("span")?.text()?.contains(label) == true
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val episodes = document.select("div.EpisodesList > a[href]").map { link ->
            SEpisode.create().apply {
                setUrlWithoutDomain(link.attr("abs:href"))
                val number = link.selectFirst("em")?.text()
                name = if (number != null) "الحلقة $number" else link.text()
                episode_number = number?.toFloatOrNull() ?: 0F
            }
        }
        // Movies have no episode list and play on their own page.
        if (episodes.isEmpty()) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(document.location())
                    name = "مشاهدة"
                    episode_number = 1F
                },
            )
        }
        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val watchUrl = "$baseUrl${episode.url}".substringBefore("?").trimEnd('/') + "/?watch=1"
        val document = client.newCall(GET(watchUrl, headers)).awaitSuccess().useAsJsoup()
        return document.select("ul#watch > li[data-watch]")
            .map { it.attr("data-watch") }
            .filter { it.startsWith("http") }
            .distinct()
            .parallelCatchingFlatMap(::extractVideos)
            .sort()
    }

    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val vidMolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    // Each extractor already puts the host name in the quality label.
    private suspend fun extractVideos(url: String): List<Video> = when {
        "vidmoly" in url -> vidMolyExtractor.videosFromUrl(url)
        "sendvid" in url -> sendvidExtractor.videosFromUrl(url)
        "uqload" in url -> uqloadExtractor.videosFromUrl(url)
        // Voe's MP4 download link answers 403 to players, only its HLS stream plays.
        "voe" in url -> voeExtractor.videosFromUrl(url).filterNot { it.quality.endsWith(":MP4") }
        "sibnet" in url -> sibnetExtractor.videosFromUrl(url)
        "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url, headers)
        STREAMWISH_REGEX.containsMatchIn(url) -> streamWishExtractor.videosFromUrl(url)
        VIDGUARD_REGEX.containsMatchIn(url) -> vidGuardExtractor.videosFromUrl(url)
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
    private fun parseCards(document: Document): AnimesPage {
        val animes = document.select("div.MovieItem > a[href]").map(::cardToAnime).distinctBy { it.url }
        // Every list marks the current page and links the following ones right after it.
        val hasNext = document.selectFirst(".pagination .current")?.parent()
            ?.nextElementSibling()?.selectFirst("a[href]") != null
        return AnimesPage(animes, hasNext)
    }

    private fun cardToAnime(link: Element) = SAnime.create().apply {
        setUrlWithoutDomain(link.attr("abs:href"))
        title = cleanTitle(link.selectFirst("div.title h4")?.text() ?: link.text())
        thumbnail_url = link.selectFirst("div.poster")?.let { poster ->
            BACKGROUND_REGEX.find(poster.attr("style").ifEmpty { poster.attr("data-style") })?.groupValues?.get(1)
        }
    }

    // "انمي X الحلقة 8 مترجمة اون لاين" -> "X"
    private fun cleanTitle(title: String) = title
        .replace(TITLE_PREFIX_REGEX, "")
        .replace(TITLE_SUFFIX_REGEX, "")
        .trim()
        .ifEmpty { title }

    companion object {
        private val STREAMWISH_REGEX = Regex("streamwish|playerwish|wishfast|strwish|swdyu")
        private val VIDGUARD_REGEX = Regex("vidguard|listeamed|vgfplay|vgembed|bembed|vid-guard")
        private val BACKGROUND_REGEX = Regex("""url\(['"]?([^'")]+)""")
        private val TITLE_PREFIX_REGEX = Regex("""^(?:جميع حلقات\s+)?(?:انمي|فيلم)\s+""")
        private val TITLE_SUFFIX_REGEX = Regex("""\s+(?:الحلقة\s+\d+.*|(?:مترجم|مترجمة|مدبلج|مدبلجة)(?:\s+.*)?)$""")

        private val SECTIONS = listOf(
            "مسلسلات الانمي" to "series",
            "أفلام الانمي" to "movies",
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
