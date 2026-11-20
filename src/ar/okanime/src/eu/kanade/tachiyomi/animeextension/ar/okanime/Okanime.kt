package eu.kanade.tachiyomi.animeextension.ar.okanime

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.vidbomextractor.VidBomExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Okanime :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Okanime"

    override val baseUrl = "https://ww3.okanime.xyz"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/top-anime?page=$page")

    override fun popularAnimeSelector() = "div.section-content div.anime-card"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("div.anime-title > h4 > a")!!.also {
            setUrlWithoutDomain(it.attr("href"))
            title = it.text()
        }
        thumbnail_url = element.selectFirst("img")!!.absUrl("src")
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination a[data-track-control=pagination_next]"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/recently-uploaded-episodes?page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$id", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/anime/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }

        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = baseUrl.toHttpUrl().newBuilder()
        .addPathSegment("anime-list")
        .addQueryParameter("q", query)
        .apply { if (page > 1) addQueryParameter("page", page.toString()) }
        .build()
        .let(::GET)

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("h1.animepage-h1")!!.text()
        thumbnail_url = document.selectFirst("img.animepage-poster")?.absUrl("src")
        genre = document.select("div.animepage-genres a").eachText().joinToString()

        val metaRows = document.select("dl.animepage-meta div.animepage-meta-row")
        status = metaRows.firstOrNull { it.selectFirst("dt")?.text() == "الحالة" }
            ?.selectFirst("dd")
            ?.text()
            .let {
                when (it) {
                    "يعرض الآن", "يعرض الان" -> SAnime.ONGOING
                    "مكتمل" -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }
            }
        description = buildString {
            document.selectFirst("div.synopsis-text")
                ?.text()
                ?.let { append("$it\n") }

            metaRows.forEach { row ->
                val label = row.selectFirst("dt")?.text().orEmpty()
                val value = row.selectFirst("dd")?.text().orEmpty()
                if (label.isNotBlank() && value.isNotBlank()) append("\n$label: $value")
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "a.ep-compact-btn"

    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.attr("title").ifBlank { element.text() }
        episode_number = element.attr("data-umami-event-episode_number").toFloatOrNull()
            ?: element.text().toFloatOrNull()
            ?: 1F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
        return response.useAsJsoup()
            .select("#streamlinks a.ep-link[data-server]")
            .parallelCatchingFlatMapBlocking { element ->
                val url = SET_SERVER_REGEX.find(element.attr("@click"))
                    ?.groupValues?.get(1)
                    ?.replace("\\u0026", "&")
                    ?: return@parallelCatchingFlatMapBlocking emptyList()
                val quality = element.attr("data-umami-event-quality").ifBlank { "720p" }
                extractVideosFromUrl(url, quality, hosterSelection)
            }
    }

    // Inspirated by JavGuru(all)
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }

    private suspend fun extractVideosFromUrl(url: String, quality: String, selection: Set<String>): List<Video> = when {
        "https://doo" in url && "/e/" in url && selection.contains("Dood") -> {
            doodExtractor.videoFromUrl(url, "DoodStream - $quality")
                ?.let(::listOf)
        }

        "mp4upload" in url && selection.contains("Mp4upload") -> {
            mp4uploadExtractor.videosFromUrl(url, headers)
        }

        "ok.ru" in url && selection.contains("Okru") -> {
            okruExtractor.videosFromUrl(url)
        }

        "voe.sx" in url && selection.contains("Voe") -> {
            voeExtractor.videosFromUrl(url)
        }

        VID_BOM_DOMAINS.any(url::contains) && selection.contains("VidBom") -> {
            vidBomExtractor.videosFromUrl(url)
        }

        else -> null
    }.orEmpty()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

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
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_SELECTION_KEY
            title = PREF_HOSTER_SELECTION_TITLE
            entries = PREF_HOSTER_SELECTION_ENTRIES
            entryValues = PREF_HOSTER_SELECTION_ENTRIES
            setDefaultValue(PREF_HOSTER_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    companion object {
        const val PREFIX_SEARCH = "id:"

        private val SET_SERVER_REGEX = Regex("""setServer\('([^']+)'\)""")

        private val VID_BOM_DOMAINS = listOf("vidbam", "vadbam", "vidbom", "vidbm")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")

        private const val PREF_HOSTER_SELECTION_KEY = "pref_hoster_selection"
        private const val PREF_HOSTER_SELECTION_TITLE = "Enable/Disable hosts"
        private val PREF_HOSTER_SELECTION_ENTRIES = arrayOf("Dood", "Voe", "Mp4upload", "VidBom", "Okru")
        private val PREF_HOSTER_SELECTION_DEFAULT by lazy { PREF_HOSTER_SELECTION_ENTRIES.toSet() }
    }
}
