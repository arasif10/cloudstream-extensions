package recloudstream.myanimes

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * MyAnimes (myanimes.in): a WordPress catalogue of anime series and movies with
 * Hindi, Tamil, Telugu, Malayalam and English audio.
 *
 * The home page is one document with a row per section, so every row of
 * [mainPage] is cut out of a single download (cached for a few minutes) instead
 * of hitting the site once per row.
 *
 * Playback data lives on the watch page as a `data-sources` JSON array holding
 * player URLs. The usable ones are hydrax.php pages, which map an audio
 * language to an AbyssPlayer link; AbyssPlayer hands out an encrypted `datas`
 * blob that is decoded with the public enc-dec.app helper.
 */
class MyAnimesProvider : MainAPI() {

    override var mainUrl = "https://myanimes.in"
    override var name = "MyAnimes"

    // Hindi is the catalogue's main dub language and what the app draws the flag from.
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries)

    private val app = Requests(defaultHeaders = mapOf("User-Agent" to USER_AGENT))

    override val mainPage = mainPageOf(
        "latest" to "Latest Drop",
        "top-series" to "Top 10 Series",
        "top-movies" to "Top 10 Movies",
        "most-series" to "Most-Watched Series",
        "most-movies" to "Most-Watched Movies",
        "onair" to "On-Air Series",
        "latest-movies" to "Latest Anime Movies",
        "lang:hindi" to "Hindi",
        "lang:tamil" to "Tamil",
        "lang:telugu" to "Telugu",
        "lang:english" to "English",
        "lang:japanese" to "Japanese",
        "lang:korean" to "Korean",
    )

    // ============================== Home page ==============================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = if (request.data.startsWith(LANGUAGE_PREFIX)) {
            browse("filter_language", request.data.removePrefix(LANGUAGE_PREFIX))
        } else {
            homeRows()[request.data].orEmpty()
        }
        return newHomePageResponse(request.name, items, false)
    }

    private var homeCache: Pair<Long, Map<String, List<SearchResponse>>>? = null
    private val homeMutex = Mutex()

    private suspend fun homeRows(): Map<String, List<SearchResponse>> {
        homeCache?.let { (savedAt, rows) ->
            if (System.currentTimeMillis() - savedAt < HOME_CACHE_MS) return rows
        }
        return homeMutex.withLock {
            homeCache?.let { (savedAt, rows) ->
                if (System.currentTimeMillis() - savedAt < HOME_CACHE_MS) return@withLock rows
            }
            val document = Jsoup.parse(app.get("$mainUrl/", headers = headers()).text, mainUrl)
            val rows = linkedMapOf<String, List<SearchResponse>>()
            document.select("section.as-section").forEach { section ->
                val heading = section.selectFirst(".as-section-heading h2")?.text()?.trim()
                val key = heading?.let { rowKey(it) } ?: return@forEach
                rows[key] = section.select("article.as-card").mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }
            }
            homeCache = System.currentTimeMillis() to rows
            rows
        }
    }

    private fun rowKey(heading: String) = when {
        heading.startsWith("Latest Drop") -> "latest"
        heading.startsWith("Top 10 Series") -> "top-series"
        heading.startsWith("Top 10 Movies") -> "top-movies"
        heading.startsWith("Most-Watched Series") -> "most-series"
        heading.startsWith("Most-Watched Movies") -> "most-movies"
        heading.startsWith("On-Air Series") -> "onair"
        heading.startsWith("Latest Anime Movies") -> "latest-movies"
        else -> null
    }

    /** The site's catalogue filters are plain query parameters on the home URL. */
    private suspend fun browse(filter: String, value: String): List<SearchResponse> {
        val html = app.get("$mainUrl/?myanimes_browse=1&$filter=$value", headers = headers()).text
        return cards(html)
    }

    private fun cards(html: String): List<SearchResponse> =
        Jsoup.parse(html, mainUrl).select("article.as-card")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

    // ============================== Search ==============================

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // The search results are rendered server side and the site does not paginate them.
        val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
        return newSearchResponseList(cards(app.get(url, headers = headers()).text), false)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("a.as-card-link") ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
            .takeIf { it.startsWith("http") } ?: return null
        val title = link.attr("aria-label").ifBlank { selectFirst("h3")?.text().orEmpty() }
            .trim()
            .takeIf { it.isNotBlank() } ?: return null
        val poster = selectFirst(".as-poster img")
            ?.let { image -> image.absUrl("src").ifBlank { image.attr("src") } }
            ?.takeIf { it.isNotBlank() }

        val type = if (href.contains("/movies/")) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, href, type, fix = false) {
            this.posterUrl = poster
        }
    }

    // ============================== Details ==============================

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = url
        val document = Jsoup.parse(app.get(pageUrl, headers = headers(pageUrl)).text, pageUrl)

        val title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - My Animes")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
        val tags = document.select("a[href*=filter_genre]")
            .mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
            .distinct()

        // Series and movies share the detail layout, but only series list episodes.
        val facts = document.selectFirst("p.ma-series-facts")?.text().orEmpty()
        val year = YEAR_REGEX.find(facts)?.value?.toIntOrNull()
        val showStatus = when {
            facts.contains("Ongoing", true) || facts.contains("Airing", true) -> ShowStatus.Ongoing
            facts.contains("Released", true) || facts.contains("Completed", true) -> ShowStatus.Completed
            else -> null
        }

        if (pageUrl.contains("/movies/")) {
            return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, pageUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }

        val episodes = document.select("div.as-episode-grid a.as-episode").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val season = anchor.attr("data-ma-season").toIntOrNull() ?: 1
            val number = anchor.attr("data-ma-number").toIntOrNull()
                ?: EPISODE_REGEX.find(href)?.groupValues?.get(2)?.toIntOrNull()
                ?: return@mapNotNull null
            val episodeName = anchor.selectFirst("h3")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: "Episode $number"

            newEpisode(href) {
                this.name = episodeName
                this.episode = number
                this.season = season
            }
        }.distinctBy { it.data }

        return newAnimeLoadResponse(title, pageUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            this.showStatus = showStatus
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ============================== Playback ==============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = data
        val document = Jsoup.parse(app.get(pageUrl, headers = headers(pageUrl)).text, pageUrl)

        val sources = document.select("[data-sources]")
            .mapNotNull { element -> runCatching { JSONArray(element.attr("data-sources")) }.getOrNull() }
            .flatMap { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }
            .distinctBy { it.optString("url") }

        var found = false
        for (source in sources) {
            val url = source.optString("url").takeIf { it.isNotBlank() } ?: continue
            when {
                url.contains("hydrax.php") || url.contains("abyss") ->
                    if (abyssLinks(url, pageUrl, callback)) found = true
                // A direct media file, used by a few entries.
                url.endsWith(".m3u8") || url.endsWith(".mp4") -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = url,
                            type = linkType(url),
                        ) {
                            this.referer = pageUrl
                            this.headers = headers(pageUrl).toMutableMap()
                        },
                    )
                    found = true
                }
            }
            // streamp2p.php is a peer-to-peer player; it never exposes a media URL.
        }
        return found
    }

    /**
     * The hydrax player page maps an audio language to an AbyssPlayer link, and
     * each of those needs its own decode round trip, so the languages are
     * resolved in parallel.
     */
    private suspend fun abyssLinks(
        playerUrl: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playerHtml = runCatching {
            app.get(playerUrl, headers = headers(pageUrl), cacheTime = 0).text
        }.getOrNull() ?: return false

        val tracksJson = TRACKS_REGEX.find(playerHtml)?.groupValues?.get(1) ?: return false
        val tracks = runCatching { JSONObject(tracksJson) }.getOrNull() ?: return false
        val languages = tracks.keys().asSequence().toList()
        if (languages.isEmpty()) return false

        val resolved = coroutineScope {
            languages.map { language ->
                async { abyssSources(language, tracks.optString(language)) }
            }.awaitAll()
        }.flatten()

        resolved.forEach { (language, quality, url) ->
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name $language",
                    url = url,
                    type = linkType(url),
                ) {
                    this.referer = "https://playhydrax.com/"
                    this.quality = getQualityFromName(quality)
                },
            )
        }
        return resolved.isNotEmpty()
    }

    private suspend fun abyssSources(language: String, playerUrl: String): List<Triple<String, String, String>> {
        if (playerUrl.isBlank()) return emptyList()
        val html = runCatching {
            app.get(playerUrl, headers = headers("https://playhydrax.com/"), cacheTime = 0).text
        }.getOrNull() ?: return emptyList()

        val blob = DATAS_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        val decoded = runCatching {
            app.post(
                "$ENC_DEC_API/dec-abyss",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Origin" to ENC_DEC_API,
                    "User-Agent" to USER_AGENT,
                ),
                json = JSONObject(mapOf("text" to blob)),
                cacheTime = 0,
            ).text
        }.getOrNull() ?: return emptyList()

        val sourcesArray = runCatching {
            JSONObject(decoded).optJSONObject("result")?.optJSONArray("sources")
        }.getOrNull() ?: return emptyList()

        return (0 until sourcesArray.length()).mapNotNull { index ->
            val source = sourcesArray.optJSONObject(index) ?: return@mapNotNull null
            if (!source.optBoolean("status", true)) return@mapNotNull null
            val url = source.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Triple(language, source.optString("type"), url)
        }
    }

    // ============================== Helpers ==============================

    private fun linkType(url: String) =
        if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

    private fun headers(referer: String? = null): Map<String, String> {
        val base = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )
        return if (referer.isNullOrBlank()) base else base + mapOf("Referer" to referer)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val ENC_DEC_API = "https://enc-dec.app"
        private const val LANGUAGE_PREFIX = "lang:"
        private const val HOME_CACHE_MS = 5 * 60 * 1000L

        private val TRACKS_REGEX = Regex("""var\s+tracks\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
        private val DATAS_REGEX = Regex("""const\s+datas\s*=\s*"([^"]+)"""")
        private val YEAR_REGEX = Regex("""\b(?:19|20)\d{2}\b""")
        private val EPISODE_REGEX = Regex("""-(\d+)x(\d+)/?$""")
    }
}
