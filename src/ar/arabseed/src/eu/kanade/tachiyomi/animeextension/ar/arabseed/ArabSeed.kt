package eu.kanade.tachiyomi.animeextension.ar.arabseed

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

class ArabSeed :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "عرب سيد"

    // TODO: Check frequency of url changes to potentially
    // add back overridable baseurl preference
    override val baseUrl = "https://m.myseed.tv"

    override val lang = "ar"

    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "a.movie__block, div.series__box > a"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/trend/${pagePath(page)}", headers)

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.attr("title")
        thumbnail_url = element.selectFirst("img")?.let { img ->
            img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
        }
    }

    override fun popularAnimeNextPageSelector() = "div.paginate a.next, nav.paginate a.next"

    private fun pagePath(page: Int) = if (page > 1) "page/$page/" else ""

    // ============================== Episode ===============================
    override fun episodeListSelector() = "ul.episodes__list a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val episodes = document.select(episodeListSelector())
        return when {
            episodes.isEmpty() -> {
                SEpisode.create().apply {
                    setUrlWithoutDomain(document.location())
                    name = "مشاهدة"
                }.let(::listOf)
            }

            else -> episodes.map(::episodeFromElement)
        }
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.text()
        episode_number = element.selectFirst("b")?.text()?.toFloatOrNull() ?: 0F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()
        val watchUrl = doc.selectFirst("a.watch__btn")?.attr("abs:href")
            ?: "${doc.location().trimEnd('/')}/watch/"
        val element = client.newCall(GET(watchUrl, headers)).execute().useAsJsoup()
        return videosFromElement(element)
    }

    override fun videoListSelector() = "div.servers__list li[data-link]"

    private fun videosFromElement(document: Document): List<Video> = document.select(videoListSelector())
        .parallelCatchingFlatMapBlocking { element ->
            val quality = element.attr("data-qu")
            val name = element.attr("data-server-name")
            val link = element.attr("data-link")
            if (link.startsWith("/")) {
                getDirectVideos("$baseUrl$link", "$name ${quality}p")
            } else {
                getVideosFromUrl(decodeEmbedUrl(link), "$name ${quality}p")
            }
        }

    // Third-party servers are wrapped as <gateway>/vid/?id=<base64 of the embed url>
    private fun decodeEmbedUrl(link: String): String {
        val id = link.toHttpUrlOrNull()?.queryParameter("id") ?: return link
        return runCatching { String(Base64.getUrlDecoder().decode(id)) }.getOrDefault(link)
    }

    private suspend fun getDirectVideos(url: String, quality: String): List<Video> {
        val gatewayUrl = client.newCall(GET(url, headers)).awaitSuccess()
            .useAsJsoup().selectFirst("iframe")!!.attr("abs:src")
        val gatewayHeaders = headersBuilder().set("Referer", "$baseUrl/").build()
        val videoUrl = client.newCall(GET(gatewayUrl, gatewayHeaders)).awaitSuccess()
            .useAsJsoup().selectFirst("video source")!!.attr("abs:src")
        val videoHeaders = headersBuilder().set("Referer", "${gatewayUrl.toHttpUrl().run { "$scheme://$host" }}/").build()
        return listOf(Video(videoUrl, quality, videoUrl, videoHeaders))
    }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    private suspend fun getVideosFromUrl(url: String, quality: String): List<Video> = when {
        "dood" in url -> doodExtractor.videosFromUrl(url, quality)

        "fviplions" in url || "wish" in url -> streamwishExtractor.videosFromUrl(url, quality)

        "voe.sx" in url -> voeExtractor.videosFromUrl(url, quality)

        else -> null
    } ?: emptyList()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeSelector() = popularAnimeSelector()

    // The "all" search type only returns single episodes, so series and movies are queried separately
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) return super.getSearchAnime(page, query, filters)
        val pages = SEARCH_TYPES.map { type ->
            val url = "$baseUrl/find/".toHttpUrl().newBuilder()
                .addQueryParameter("word", query)
                .addQueryParameter("type", type)
                .addQueryParameter("page_number", page.toString())
                .build()
            client.newCall(GET(url, headers)).awaitSuccess().use(::searchAnimeParse)
        }
        return AnimesPage(pages.flatMap { it.animes }, pages.any { it.hasNextPage })
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter
        val category = typeFilter.toUriPart()
        if (category.isEmpty()) throw Exception("اختر فلتر")

        return GET("$baseUrl/category/$category/${pagePath(page)}", headers)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.poster__single img")?.let { img ->
            img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
        }
        title = document.selectFirst("h1.post__name")!!.text()
            .replace(" مترجم", "").replace("فيلم ", "")
        genre = document.select("ul.info__area__ul > li:contains(نوع العرض) ul.tags__list a").eachText().joinToString()
        description = document.selectFirst("div.post__story p")?.text()
        status = when {
            document.location().contains("/selary/") -> SAnime.UNKNOWN
            else -> SAnime.COMPLETED
        }
    }

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("الفلترات مش هتشتغل لو بتبحث او وهي فاضيه"),
        TypeFilter(),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class TypeFilter :
        UriPartFilter(
            "القسم",
            arrayOf(
                Pair("أختر", ""),
                Pair("افلام عربي", "arabic-movies"),
                Pair("افلام اجنبى", "foreign-movies"),
                Pair("افلام اسيوية", "asian-movies"),
                Pair("افلام هندى", "indian-movies"),
                Pair("افلام تركية", "turkish-movies"),
                Pair("افلام مدبلجة", "dubbed-movies"),
                Pair("افلام كلاسيكيه", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%83%d9%84%d8%a7%d8%b3%d9%8a%d9%83%d9%8a%d9%87"),
                Pair("افلام انيميشن", "animation-movies"),
                Pair("افلام Netflix", "netflix/netflix-movies"),
                Pair("مسلسلات عربي", "arabic-series"),
                Pair("مسلسلات اجنبي", "foreign-series"),
                Pair("مسلسلات تركيه", "turkish-series"),
                Pair("مسلسلات كورية", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%83%d9%88%d8%b1%d9%8a%d9%87"),
                Pair("مسلسلات مصرية", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%85%d8%b5%d8%b1%d9%8a%d9%87"),
                Pair("مسلسلات هندية", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%87%d9%86%d8%af%d9%8a%d8%a9"),
                Pair("مسلسلات مدبلجة", "dubbed-series"),
                Pair("مسلسلات كرتون", "cartoon-series"),
                Pair("مسلسلات Netflix", "netflix/netflix-series"),
                Pair("مسلسلات رمضان 2026", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86/ramadan-series-2026"),
                Pair("مسلسلات رمضان 2025", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86/ramadan-series-2025"),
                Pair("مسلسلات رمضان 2024", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86/ramadan-series-2024"),
                Pair("مسلسلات رمضان 2023", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86/ramadan-series-2023"),
                Pair("برامج تلفزيونية", "%d8%a8%d8%b1%d8%a7%d9%85%d8%ac-%d8%aa%d9%84%d9%81%d8%b2%d9%8a%d9%88%d9%86%d9%8a%d8%a9"),
                Pair("مصارعة", "wwe-shows"),
            ),
        )

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    // =============================== Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }
        screen.addPreference(videoQualityPref)
    }

    // ============================= Utilities ==============================
    companion object {
        private val SEARCH_TYPES = listOf("series", "movies")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES by lazy {
            PREF_QUALITY_ENTRIES.map { it.substringBefore("p") }.toTypedArray()
        }
    }
}
