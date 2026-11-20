package eu.kanade.tachiyomi.animeextension.ar.egydead

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class EgyDead :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Egy Dead"

    // TODO: Check frequency of url changes to potentially
    // add back overridable baseurl preference
    override val baseUrl = "https://egydead.here"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ================================== popular ==================================

    override fun popularAnimeSelector(): String = "div.shows-container a.show-card"

    override fun popularAnimeNextPageSelector(): String = NEXT_PAGE_SELECTOR

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/?page=$page", headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("abs:href"))
        val title = element.selectFirst("p.title")?.text() ?: element.selectFirst("img")?.attr("alt").orEmpty()
        // Series and category listings are made of episode cards, the entry is the whole season
        anime.title = if ("/episode/" in anime.url) title.replace(EPISODE_SUFFIX_REGEX, "") else title
        anime.thumbnail_url = element.selectFirst("img")?.attr("abs:src")
        return anime
    }

    // ================================== episodes ==================================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val url = response.request.url.encodedPath
        return when {
            url.startsWith("/film/") -> listOf(watchEpisode(url, "مشاهدة", 1F))

            url.startsWith("/episode/") -> {
                val parent = document.selectFirst("nav.bc a[href*=/season/]")
                    ?: document.selectFirst("nav.bc a[href*=/series/]")
                    ?: return listOf(watchEpisode(url, document.selectFirst("h1.title")?.text().orEmpty(), 1F))
                seasonsEpisodes(parent.attr("abs:href"))
            }

            else -> seasonsEpisodes(response.request.url.toString(), document)
        }.reversed()
    }

    private fun seasonsEpisodes(url: String, loaded: Document? = null): List<SEpisode> {
        val document = loaded ?: client.newCall(GET(url, headers)).execute().useAsJsoup()
        val seasonLinks = document.select("a.epss[href*=/season/]").map { it.attr("abs:href") }
        return if (url.contains("/series/")) {
            seasonLinks.flatMap { seasonEpisodes(client.newCall(GET(it, headers)).execute().useAsJsoup()) }
        } else {
            seasonEpisodes(document)
        }
    }

    private fun seasonEpisodes(document: Document): List<SEpisode> {
        val season = document.selectFirst("h1.title")?.text().orEmpty()
            .substringAfter("الموسم ", "").substringBefore(" ")
        return document.select(episodeListSelector()).map {
            val number = it.selectFirst("span.fs-2")?.text().orEmpty()
            val name = if (season.isNotEmpty()) "الموسم $season الحلقة $number" else "الحلقة $number"
            watchEpisode(it.attr("abs:href").toHttpUrl().encodedPath, name, number.toFloatOrNull() ?: 0F)
        }
    }

    private fun watchEpisode(path: String, name: String, number: Float) = SEpisode.create().apply {
        url = "/watch/" + path.substringAfter('/').substringAfter('/')
        this.name = name
        episode_number = number
    }

    override fun episodeListSelector() = "a.epss[href*=/episode/]"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ================================== video urls ==================================
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    // Servers are resolved through a CSRF-protected POST; the hidden honeytoken button must not be used
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val watchUrl = baseUrl + episode.url
        val document = client.newCall(GET(watchUrl, headers)).awaitSuccess().useAsJsoup()
        val script = document.selectFirst("script:containsData(watchResolveKey)")?.data().orEmpty()
        val key = RESOLVE_KEY_REGEX.find(script)?.groupValues?.get(1) ?: return emptyList()
        val csrf = CSRF_REGEX.find(script)?.groupValues?.get(1).orEmpty()
        val resolveHeaders = headersBuilder()
            .set("Accept", "application/json")
            .set("X-CSRF-TOKEN", csrf)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", watchUrl)
            .build()
        // The resolve endpoint answers 429 when several servers are resolved at once
        return document.select(videoListSelector()).flatMap {
            runCatching { resolveServer(it.attr("data-token"), key, resolveHeaders) }.getOrDefault(emptyList())
        }
    }

    private suspend fun resolveServer(token: String, key: String, resolveHeaders: Headers): List<Video> {
        val body = buildJsonObject {
            put("key", key)
            put("token", token)
        }.toString().toRequestBody("application/json".toMediaType())
        val resolved = client.newCall(POST("$baseUrl/watch/resolve", resolveHeaders, body)).awaitSuccess().parseAs<ResolveDto>()
        val frame = client.newCall(GET(baseUrl + resolved.url, headers)).awaitSuccess().useAsJsoup()
        return extractVideos(frame.selectFirst("iframe")?.attr("abs:src").orEmpty())
    }

    @Serializable
    private class ResolveDto(val url: String)

    private suspend fun extractVideos(url: String): List<Video> = when {
        DOOD_REGEX.containsMatchIn(url) -> {
            DoodExtractor(client).videoFromUrl(url, "Dood mirror")?.let(::listOf)
        }

        MIXDROP_REGEX.containsMatchIn(url) -> {
            MixDropExtractor(client).videoFromUrl(url)
        }

        url.contains("ahvsh") -> {
            val request = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
            val script = request.selectFirst("script:containsData(sources)")!!.data()
            val streamLink = Regex("sources:\\s*\\[\\{\\s*\\t*file:\\s*[\"']([^\"']+)").find(script)!!.groupValues[1]
            val quality = Regex("'qualityLabels'\\s*:\\s*\\{\\s*\".*?\"\\s*:\\s*\"(.*?)\"").find(script)!!.groupValues[1]
            Video(streamLink, "StreamHide: $quality", streamLink).let(::listOf)
        }

        "fastvid" in url -> vidHideExtractor.videosFromUrl(url)

        STREAMWISH_REGEX.containsMatchIn(url) -> {
            streamWishExtractor.videosFromUrl(url)
        }

        "voe.sx" in url -> voeExtractor.videosFromUrl(url)

        url.contains("fanakishtuna") -> {
            val request = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
            val data = request.selectFirst("script:containsData(sources)")!!.data()
            val streamLink = Regex("sources:\\s*\\[\\{\\s*\\t*file:\\s*[\"']([^\"']+)").find(data)!!.groupValues[1]
            listOf(Video(streamLink, "Mirror: High Quality", streamLink))
        }

        url.contains("uqload") -> {
            val newURL = url.replace("https://uqload.co/", "https://www.uqload.co/")
            val request = client.newCall(GET(newURL, headers)).awaitSuccess().useAsJsoup()
            val data = request.selectFirst("script:containsData(sources)")!!.data()
            val streamLink = data.substringAfter("sources: [\"").substringBefore("\"]")
            listOf(Video(streamLink, "Uqload: Mirror", streamLink))
        }

        else -> null
    } ?: emptyList()

    override fun videoListSelector() = "button.btn-server[data-token]:not([data-honeytoken])"

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080p")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ================================== search ==================================

    override fun searchAnimeNextPageSelector(): String = NEXT_PAGE_SELECTOR

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("s", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, headers)
        }
        val category = filters.filterIsInstance<CategoryList>().firstOrNull()
            ?.let { getCategoryList()[it.state].query }
            .orEmpty()
        return if (category.isEmpty()) {
            popularAnimeRequest(page)
        } else {
            val url = "$baseUrl/category".toHttpUrl().newBuilder()
                .addPathSegment(category)
                .addQueryParameter("page", page.toString())
                .build()
            GET(url, headers)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun getFilterList() = AnimeFilterList(
        CategoryList(categoriesName),
    )

    private class CategoryList(categories: Array<String>) : AnimeFilter.Select<String>("الأقسام", categories)

    private data class CatUnit(val name: String, val query: String)

    private val categoriesName = getCategoryList().map {
        it.name
    }.toTypedArray()

    private fun getCategoryList() = listOf(
        CatUnit("اختر القسم", ""),
        CatUnit("افلام اجنبى", "افلام-اجنبي"),
        CatUnit("افلام انمى", "افلام-انمي"),
        CatUnit("افلام تركيه", "افلام-تركية"),
        CatUnit("افلام اسيويه", "افلام-اسيوية"),
        CatUnit("افلام عربي", "افلام-عربي"),
        CatUnit("افلام هندي", "افلام-هندي"),
        CatUnit("مسلسلات اجنبية", "مسلسلات-اجنبي"),
        CatUnit("مسلسلات انمى", "مسلسلات-انمي"),
        CatUnit("مسلسلات تركية", "مسلسلات-تركية"),
        CatUnit("مسلسلات اسيوىة", "مسلسلات-اسيوية"),
        CatUnit("مسلسلات مدبلجة", "مسلسلات-مدبلجة"),
        CatUnit("مسلسلات عربي", "مسلسلات-عربي"),
        CatUnit("مسلسلات هندية", "مسلسلات-هندية"),
        CatUnit("برامج تلفزيونية", "برامج-تلفزيونية"),
        CatUnit("عروض مصارعة", "عروض-مصارعة"),
    )

    // ================================== details ==================================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.poster[style]")?.attr("style")
            ?.let { POSTER_REGEX.find(it)?.groupValues?.get(1) }
        anime.title = document.select("h1.title").text()
        anime.genre = document.select("div.d-flex:has(> span:containsOwn(النوع)) div.ch a").joinToString(", ") { it.text() }
        anime.description = document.select("div.description, span.description").first()?.text()
        anime.status = if (anime.title.contains("كامل") || anime.title.contains("فيلم")) SAnime.COMPLETED else SAnime.ONGOING
        return anime
    }

    // ================================== latest ==================================

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector(): String = NEXT_PAGE_SELECTOR

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // ================================== preferences ==================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "DoodStream", "Uqload")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "Dood", "Uqload")
            setDefaultValue("1080")
            summary = "%s"
        }
        screen.addPreference(videoQualityPref)
    }

    // like|kharabnahk
    companion object {
        private val DOOD_REGEX = Regex("(do*d(?:stream)?\\.(?:com?|watch|to|s[ho]|cx|la|w[sf]|pm|re|yt|stream))/[de]/([0-9a-zA-Z]+)|ds2play|playmogo")
        private val MIXDROP_REGEX = Regex("mdbekjwqa|m[i1]xdr[o0]p")
        private val NEXT_PAGE_SELECTOR = "li.page-item.active + li.page-item, li.page-item:has(button.cursor-normal) + li.page-item"
        private val EPISODE_SUFFIX_REGEX = Regex("\\s*الحلقة.*$")
        private val RESOLVE_KEY_REGEX = Regex("watchResolveKey\\s*=\\s*\"([^\"]+)\"")
        private val CSRF_REGEX = Regex("csrfToken\\s*=\\s*\"([^\"]+)\"")
        private val POSTER_REGEX = Regex("url\\(([^)]+)\\)")
        private val STREAMWISH_REGEX = Regex("ajmidyad|alhayabambi|atabknh[ks]|https://.*\\.sbs/e/")
    }
}
