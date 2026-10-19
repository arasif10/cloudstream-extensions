package recloudstream.desidubanime

import android.util.Base64
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * DesiDubAnime (desidubanime.me): Hindi/Tamil/Telugu/English dubs on the
 * Kiranime WordPress theme.
 *
 * Every catalogue and every season is an `admin-ajax.php` call; playback comes
 * from the base64-encoded embeds the watch page publishes, most of which are
 * Dean Edwards-packed JWPlayer pages.
 */
class DesiDubAnimeProvider : MainAPI() {

    override var mainUrl = "https://www.desidubanime.me"
    override var name = "DesiDubAnime"
    override var lang = "all"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "popular" to "Popular",
        "latest" to "Recently Updated",
    )

    private val app = Requests(defaultHeaders = mapOf("User-Agent" to USER_AGENT))

    /** The theme's admin-ajax nonce, read once from any page. */
    private var cachedNonce: String? = null

    // ============================== Catalogue ==============================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val orderBy = if (request.data == "latest") "updated" else "popular"
        val (items, hasNext) = catalogue(page, "", orderBy)
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val (items, hasNext) = catalogue(page, query, "")
        return newSearchResponseList(items, hasNext)
    }

    private suspend fun catalogue(
        page: Int,
        keyword: String,
        orderBy: String,
    ): Pair<List<SearchResponse>, Boolean> {
        val response = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            headers = ajaxHeaders("$mainUrl/search/"),
            data = linkedMapOf(
                "action" to "advanced_search",
                "nonce" to searchNonce(),
                "page" to page.toString(),
                "s_keyword" to keyword,
                "orderby" to orderBy,
                "order" to "DESC",
            ),
        )
        val data = runCatching { JSONObject(response.text).optJSONObject("data") }.getOrNull()
            ?: return emptyList<SearchResponse>() to false

        val document = Jsoup.parse(data.optString("html"), mainUrl)
        val items = document.select("article.anime-card").mapNotNull { it.toSearchResponse() }
        val hasNext = data.optInt("current_page", 1) < data.optInt("max_pages", 0)
        return items to hasNext
    }

    private suspend fun searchNonce(): String {
        cachedNonce?.let { return it }
        val html = app.get("$mainUrl/search/", headers = headers()).text
        val nonce = NONCE_REGEX.find(html)?.groupValues?.get(1).orEmpty()
        if (nonce.isNotBlank()) cachedNonce = nonce
        return nonce
    }

    /**
     * A card links at episode pages; the series URL only appears in the Info
     * button's onclick handler.
     */
    private fun Element.toSearchResponse(): SearchResponse? {
        val onclick = selectFirst("button[onclick*='/anime/']")?.attr("onclick").orEmpty()
        val seriesUrl = ANIME_URL_REGEX.find(onclick)?.value ?: return null
        // The heading holds one span per language; its title attribute is the clean name.
        val title = selectFirst("h3 a")?.attr("title")?.takeIf { it.isNotBlank() }
            ?: selectFirst("h3 a")?.text()?.takeIf { it.isNotBlank() }
            ?: selectFirst("a.stretched-link")?.attr("title")?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }

        return newAnimeSearchResponse(title, seriesUrl, TvType.Anime, fix = false) {
            this.posterUrl = poster
        }
    }

    // ============================== Details ==============================

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = absolute(url)
        val document = Jsoup.parse(app.get(pageUrl, headers = headers(pageUrl)).text, pageUrl)

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.removeSuffix(" - Desi Dub Anime")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.selectFirst("img.anime-main-image")?.absUrl("src")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

        val plot = buildString {
            append(
                document.getElementsByAttributeValue("aria-label", "Anime Overview")
                    .firstOrNull()?.wholeText()?.trim()
                    ?: document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty(),
            )
            val altNames = listOf("Native", "English", "Synonyms")
                .mapNotNull { label -> document.infoValue(label)?.text()?.trim() }
                .filter { it.isNotBlank() && !it.equals("N/A", true) }
            if (altNames.isNotEmpty()) {
                append("\n\n")
                append(altNames.joinToString(" / "))
            }
        }.trim()

        val genres = document.infoValue("Genres")?.select("a")
            ?.mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
            ?.distinct()
            .orEmpty()
        val aired = document.infoValue("Aired")?.text().orEmpty()
        val year = YEAR_IN_AIRED_REGEX.find(aired)?.value?.toIntOrNull()
        val seasons = readSeasons(document)
        val episodes = readEpisodes(seasons)
        val recommendations = readRecommendations(document, pageUrl)

        return newTvSeriesLoadResponse(title, pageUrl, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            this.showStatus = document.statusFromAired()
            this.recommendations = recommendations
        }
    }

    private fun readSeasons(document: Document): List<Season> {
        val buttons = document.select("button[data-season]")
            .mapNotNull { button ->
                val id = button.attr("data-season").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Season(id, button.text().trim().ifBlank { "Season" })
            }
        if (buttons.isNotEmpty()) {
            return buttons.distinctBy { it.id }.mapIndexed { index, season ->
                season.copy(number = seasonNumber(season.label, index + 1))
            }
        }
        return document.select("#seasonContent[data-season]")
            .mapNotNull { it.attr("data-season").takeIf { id -> id.isNotBlank() } }
            .map { Season(it, "Season 1", 1) }
    }

    private fun seasonNumber(label: String, fallback: Int): Int =
        Regex("\\d+").find(label)?.value?.toIntOrNull() ?: fallback

    /**
     * The theme renders every season button of the franchise on each page and
     * each id answers with its own episodes, so all of them are merged.
     */
    private suspend fun readEpisodes(seasons: List<Season>): List<Episode> {
        if (seasons.isEmpty()) return emptyList()
        val multiSeason = seasons.size > 1
        val episodes = mutableListOf<Episode>()

        for (season in seasons) {
            var page = 1
            while (true) {
                val response = app.get(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    headers = ajaxHeaders("$mainUrl/"),
                    params = mapOf(
                        "action" to "get_episodes",
                        "anime_id" to season.id,
                        "page" to page.toString(),
                        "order" to "desc",
                    ),
                )
                val data = runCatching { JSONObject(response.text).optJSONObject("data") }.getOrNull()
                    ?: break
                val array = data.optJSONArray("episodes") ?: break
                if (array.length() == 0) break

                for (index in 0 until array.length()) {
                    val episode = array.optJSONObject(index) ?: continue
                    val episodeUrl = episode.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val number = episode.optString("meta_number").ifBlank {
                        episode.optString("number").substringAfter("Episode").trim()
                    }
                    val label = episode.optString("number").ifBlank { "Episode $number" }
                    val episodeTitle = episode.optString("title")
                    val thumbnail = episode.optString("thumbnail").takeIf { it.isNotBlank() }
                    val released = episode.optString("released")

                    episodes += newEpisode(
                        url = absolute(episodeUrl),
                        fix = false,
                        initializer = {
                            this.name = buildString {
                                if (multiSeason) append("S${season.number} ")
                                append(label)
                                if (episodeTitle.isNotBlank() && !label.contains(episodeTitle)) {
                                    append(" - ").append(episodeTitle)
                                }
                            }
                            this.episode = number.toFloatOrNull()?.toInt() ?: (index + 1)
                            this.season = season.number
                            this.posterUrl = thumbnail
                            released.takeIf { it.isNotBlank() }?.let { value ->
                                addDate(value.take(10), "yyyy-MM-dd")
                            }
                        },
                    )
                }
                if (page >= data.optInt("max_episodes_page", 1)) break
                page++
            }
        }
        return episodes
    }

    /**
     * The series page lazy-loads a "Recommended" widget whose post id is base64
     * encoded in a data attribute.
     */
    private suspend fun readRecommendations(document: Document, pageUrl: String): List<SearchResponse> {
        val payload = document.selectFirst("[data-lazy-load-components-id]")
            ?.attr("data-lazy-load-components-id")
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val postId = runCatching {
            JSONObject(String(Base64.decode(payload, Base64.DEFAULT))).optInt("id", -1)
        }.getOrDefault(-1)
        if (postId <= 0) return emptyList()

        val widget = runCatching {
            app.get(
                "$mainUrl/wp-json/kiranime/v1/widget?name=recommended&id=$postId&post_type=anime&display=grid",
                headers = ajaxHeaders(pageUrl),
            ).text
        }.getOrNull() ?: return emptyList()
        val html = runCatching { JSONObject(widget).optString("result") }.getOrDefault("")
        if (html.isBlank()) return emptyList()

        return Jsoup.parse(html, mainUrl).select("article.anime-card").mapNotNull { it.toSearchResponse() }
    }

    // ============================== Playback ==============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = absolute(data)
        val document = Jsoup.parse(
            runCatching { app.get(pageUrl, headers = headers(pageUrl)).text }.getOrNull() ?: return false,
            pageUrl,
        )

        val servers = document.select("span[data-embed-id]").mapNotNull { span ->
            val encoded = span.attr("data-embed-id")
            val parts = encoded.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            runCatching {
                Server(
                    label = String(Base64.decode(parts[0], Base64.DEFAULT)).trim(),
                    payload = String(Base64.decode(parts[1], Base64.DEFAULT)).trim(),
                )
            }.getOrNull()
        }
        if (servers.isEmpty()) return false

        val links = coroutineScope {
            servers.map { server ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(25_000L) {
                        runCatching { resolveServer(server, pageUrl) }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }.awaitAll()
        }.flatten()

        val ordered = links
            .distinctBy { it.url }
            .sortedWith(
                compareBy<ExtractorLink> { serverPriority(it.name) }
                    .thenByDescending { it.quality },
            )
        ordered.forEach { callback(it) }
        return ordered.isNotEmpty()
    }

    private data class Server(val label: String, val payload: String)

    private data class Variant(val height: Int?, val url: String)

    private data class Audio(val language: String, val url: String)

    private suspend fun resolveServer(server: Server, episodeUrl: String): List<ExtractorLink> {
        val url = payloadUrl(server.payload) ?: return emptyList()
        return when {
            url.contains("abyssplayer.com") || url.contains("p2pplay.pro") -> emptyList()
            url.contains("filesforever.link/embed/") ->
                resolveFilesForever(url.substringAfterLast("/"))

            url.contains("vidmoly") -> resolvePlainPage(url, server.label.ifBlank { "Vidmoly" }, episodeUrl)
            else -> resolvePlainPage(url, server.label.ifBlank { "Server" }, episodeUrl)
        }
    }

    private fun payloadUrl(payload: String): String? {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        val candidate = IFRAME_SRC_REGEX.find(trimmed)?.groupValues?.get(1)
            ?: URL_IN_TEXT_REGEX.find(trimmed)?.value
            ?: return null
        return if (candidate.startsWith("//")) "https:$candidate" else candidate
    }

    /**
     * filesforever exposes its sub-hosts through embedhelper2.php; only StreamHG
     * (packed JWPlayer) and StreamTape answer with a static stream.
     */
    private suspend fun resolveFilesForever(sid: String): List<ExtractorLink> {
        if (sid.isBlank()) return emptyList()
        val referer = "https://filesforever.link/embed/$sid"
        val response = runCatching {
            app.post(
                "https://pro.iqsmartgames.com/embedhelper2.php",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to "https://filesforever.link",
                ),
                data = linkedMapOf(
                    "sid" to sid,
                    "UserFavSite" to "",
                    "currentDomain" to """["www.desidubanime.me","filesforever.link"]""",
                ),
            ).text
        }.getOrNull() ?: return emptyList()

        val json = runCatching { JSONObject(response) }.getOrNull() ?: return emptyList()
        val sources = json.optJSONObject("sources") ?: return emptyList()
        val codes = runCatching {
            JSONObject(String(Base64.decode(json.optString("mresult"), Base64.DEFAULT)))
        }.getOrNull() ?: return emptyList()

        val found = mutableListOf<ExtractorLink>()
        for (key in sources.keys()) {
            if (key !in LIVE_SOURCE_KEYS) continue
            val meta = sources.optJSONObject(key) ?: continue
            val code = codes.optString(key).takeIf { it.isNotBlank() } ?: continue
            val siteUrl = meta.optString("siteUrl").replace("\\/", "/")
            if (siteUrl.isBlank()) continue
            val playerUrl = siteUrl + code
            val name = if (key == "smwh") "StreamHG" else "StreamTape"

            if (key == "strmtp") {
                found += resolveStreamTape(playerUrl, name, sid)
            } else {
                found += resolvePackedPage(playerUrl, name, referer)
            }
        }
        return found
    }

    private suspend fun resolveStreamTape(playerUrl: String, name: String, sid: String): List<ExtractorLink> {
        val html = tryGet(playerUrl, "https://filesforever.link/embed/$sid") ?: return emptyList()
        val link = STREAMTAPE_LINK_REGEX.find(html)?.value ?: return emptyList()
        val direct = "https://streamtape.site/$link"
        return listOf(
            newExtractorLink(
                source = this.name,
                name = "$name · Direct",
                url = direct,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.referer = playerUrl
                this.quality = getQualityFromName("720p")
                this.headers = headers(playerUrl)
            },
        )
    }

    private suspend fun resolvePackedPage(
        playerUrl: String,
        name: String,
        referer: String,
    ): List<ExtractorLink> {
        val html = tryGet(playerUrl, referer) ?: return emptyList()
        val masterUrl = decodePackedStreamUrl(html, playerUrl) ?: return emptyList()
        return hlsLinks(masterUrl, name, playerUrl)
    }

    private suspend fun resolvePlainPage(
        embedUrl: String,
        name: String,
        referer: String,
    ): List<ExtractorLink> {
        val html = tryGet(embedUrl, referer) ?: return emptyList()
        val masterUrl = decodePackedStreamUrl(html, embedUrl)
            ?: M3U8_REGEX.find(html)?.value
            ?: return emptyList()
        return hlsLinks(masterUrl, name, embedUrl)
    }

    /**
     * Expands an HLS master into one link per quality. Multi-audio masters get
     * one link per quality and language, because each variant is hard-muxed to
     * a single dub and the player's audio menu cannot switch it.
     */
    private suspend fun hlsLinks(
        masterUrl: String,
        serverName: String,
        referer: String,
    ): List<ExtractorLink> {
        val playlist = tryGet(masterUrl, referer) ?: return emptyList()
        val variants = parseVariants(playlist, masterUrl)
        val audios = parseAudio(playlist, masterUrl)
        val links = mutableListOf<ExtractorLink>()

        if (audios.size > 1) {
            variants.forEach { variant ->
                audios.forEach { audio ->
                    val rewritten = rewriteAudio(variant.url, audio.url) ?: return@forEach
                    links += hlsLink("$serverName · ${variant.height}p · ${audio.language}", rewritten, variant.height, referer)
                }
            }
        } else {
            variants.forEach { variant ->
                links += hlsLink("$serverName · ${variant.height}p", variant.url, variant.height, referer)
            }
        }

        links += hlsLink("$serverName · Auto", masterUrl, variants.firstOrNull()?.height, referer)
        return links
    }

    private suspend fun hlsLink(
        name: String,
        url: String,
        height: Int?,
        referer: String,
    ): ExtractorLink = newExtractorLink(
        source = this.name,
        name = name,
        url = url,
        type = ExtractorLinkType.M3U8,
    ) {
        this.referer = referer
        this.quality = getQualityFromName(height?.let { "${it}p" })
        this.headers = headers(referer)
    }

    private fun parseVariants(playlist: String, masterUrl: String): List<Variant> {
        val variants = mutableListOf<Variant>()
        var pendingHeight: Int? = null
        playlist.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    pendingHeight = RESOLUTION_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()
                }

                line.isNotEmpty() && !line.startsWith("#") -> {
                    pendingHeight?.let { variants += Variant(it, resolveUrl(line, masterUrl)) }
                    pendingHeight = null
                }
            }
        }
        return variants.distinctBy { it.height }
    }

    private fun parseAudio(playlist: String, masterUrl: String): List<Audio> =
        AUDIO_MEDIA_REGEX.findAll(playlist).mapNotNull { match ->
            val line = match.value
            val uri = AUDIO_URI_REGEX.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val language = AUDIO_LANGUAGE_REGEX.find(line)?.groupValues?.get(1).orEmpty()
            val name = AUDIO_NAME_REGEX.find(line)?.groupValues?.get(1).orEmpty()
            Audio(
                language = englishAudioName(name, language),
                url = resolveUrl(uri, masterUrl),
            )
        }.distinctBy { it.url }.toList()

    /** index-v1-a2.m3u8 + index-a4.m3u8 -> index-v1-a4.m3u8 */
    private fun rewriteAudio(variantUrl: String, audioUrl: String): String? {
        val slot = AUDIO_SLOT_REGEX.find(variantUrl)?.value ?: return null
        val index = AUDIO_INDEX_REGEX.find(audioUrl)?.groupValues?.get(1) ?: return null
        return variantUrl.replace(slot, "-a$index.m3u8")
    }

    private fun englishAudioName(name: String, language: String): String {
        val byLanguage = mapOf(
            "en" to "English", "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu",
            "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "bn" to "Bengali",
            "ml" to "Malayalam", "kn" to "Kannada", "mr" to "Marathi",
        )
        byLanguage[language.lowercase(Locale.US)]?.let { return it }
        val byName = mapOf(
            "हिन्दी" to "Hindi", "हिंदी" to "Hindi", "தமிழ்" to "Tamil", "తెలుగు" to "Telugu",
            "ಕನ್ನಡ" to "Kannada", "മലയാളം" to "Malayalam", "বাংলা" to "Bengali",
            "मराठी" to "Marathi", "日本語" to "Japanese", "한국어" to "Korean",
            "中文" to "Chinese", "English" to "English",
        )
        return byName[name.trim()] ?: name.trim().ifBlank { language.ifBlank { "Audio" } }
    }

    /**
     * The stream hosts pack their JWPlayer config with a Dean Edwards packer;
     * the HLS URL sits in its links/sources/file config.
     */
    private fun decodePackedStreamUrl(html: String, pageUrl: String): String? = runCatching {
        var searchFrom = 0
        while (searchFrom < html.length) {
            val start = html.indexOf("eval(function(p,a,c,k,e,d)", searchFrom)
            if (start == -1) return@runCatching null
            val segment = html.substring(start)
            val args = PACKER_ARGS_REGEX.find(segment) ?: return@runCatching null
            val source = args.groupValues[1]
            val radix = args.groupValues[2].toIntOrNull() ?: 10
            val count = args.groupValues[3].toIntOrNull() ?: 0
            val dictionary = args.groupValues[4].split("|")

            var decoded = source
            for (index in count - 1 downTo 0) {
                if (index < dictionary.size && dictionary[index].isNotEmpty()) {
                    decoded = decoded.replace(Regex("\\b" + toBase(index, radix) + "\\b"), dictionary[index])
                }
            }

            val linksBlock = LINKS_BLOCK_REGEX.find(decoded)
            if (linksBlock != null) {
                for (key in listOf("hls4", "hls3", "hls2", "hls")) {
                    val link = Regex("""["']?$key["']?\s*:\s*["']([^"']+)["']""")
                        .find(linksBlock.groupValues[1])
                    if (link != null) {
                        val raw = link.groupValues[1]
                        if (raw.contains("m3u8") || raw.contains("master.txt") || raw.contains("mp4")) {
                            return resolveUrl(raw, pageUrl)
                        }
                    }
                }
            }

            FILE_REGEX.find(decoded)?.groupValues?.get(1)?.let { return resolveUrl(it, pageUrl) }
            SOURCES_REGEX.find(decoded)?.groupValues?.get(1)?.let { return resolveUrl(it, pageUrl) }
            searchFrom = start + 10
        }
        null
    }.getOrNull()

    private fun toBase(number: Int, radix: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        if (number == 0) return "0"
        var value = number
        var out = ""
        while (value > 0) {
            out = digits[value % radix] + out
            value /= radix
        }
        return out
    }

    private fun resolveUrl(raw: String, base: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return runCatching { URI(base).resolve(raw).toString() }.getOrDefault(raw)
    }

    private suspend fun tryGet(url: String, referer: String): String? = runCatching {
        val response = app.get(url, headers = headers(referer))
        if (response.code !in 200..299) null else response.text
    }.getOrNull()

    private fun serverPriority(name: String): Int = when {
        name.startsWith("StreamHG") -> 0
        name.startsWith("Vidmoly") || name.startsWith("VMoly") -> 1
        name.startsWith("StreamTape") -> 2
        else -> 3
    }

    // ============================== Page helpers ==============================

    private fun absolute(raw: String): String {
        val url = raw.trim()
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            url.isEmpty() -> mainUrl
            else -> "$mainUrl/$url"
        }
    }

    private fun headers(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to referer,
    )

    private fun ajaxHeaders(referer: String): Map<String, String> =
        headers(referer) + mapOf("X-Requested-With" to "XMLHttpRequest")

    private fun Document.infoValue(label: String): Element? =
        select("dt").firstOrNull { it.text().equals(label, ignoreCase = true) }?.nextElementSibling()

    private fun Document.statusFromAired(): ShowStatus? {
        val aired = infoValue("Aired")?.text().orEmpty()
        if (aired.isBlank()) return null
        val endRaw = if (aired.contains(" to ", ignoreCase = true)) {
            aired.substringAfterLast("to ").trim()
        } else {
            aired
        }
        val end = runCatching { SimpleDateFormat("MMM d, yyyy", Locale.US).parse(endRaw) }.getOrNull()
            ?: return ShowStatus.Ongoing
        return if (end.before(Date())) ShowStatus.Completed else ShowStatus.Ongoing
    }

    private data class Season(val id: String, val label: String, val number: Int = 1)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private val NONCE_REGEX = Regex("\"search_actions\":\"([0-9a-f]+)\"")
        private val ANIME_URL_REGEX = Regex("""https?://[^'"]+/anime/[^'"]+""")
        private val IFRAME_SRC_REGEX = Regex("""(?i)\bsrc\s*=\s*["']([^"']+)["']""")
        private val URL_IN_TEXT_REGEX = Regex("""https?://[^"'\s<>]+""")
        private val M3U8_REGEX = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""")
        private val STREAMTAPE_LINK_REGEX = Regex("""get_video\?id=[^"'<\s]+""")
        private val YEAR_IN_AIRED_REGEX = Regex("""\b(19|20)\d{2}\b""")

        private val AUDIO_MEDIA_REGEX = Regex("""#EXT-X-MEDIA:TYPE=AUDIO[^\r\n]*""")
        private val AUDIO_URI_REGEX = Regex("""URI="([^"]+)"""")
        private val AUDIO_LANGUAGE_REGEX = Regex("""LANGUAGE="([^"]+)"""")
        private val AUDIO_NAME_REGEX = Regex("""NAME="([^"]+)"""")
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""")

        private val AUDIO_INDEX_REGEX = Regex("""-a(\d+)\.m3u8""")
        private val AUDIO_SLOT_REGEX = Regex("""-a\d+\.m3u8""")

        // No bare braces here: Android's ICU regex rejects a stray one.
        private val PACKER_ARGS_REGEX = Regex(
            """[}][(]'(.*)',\s*(\d+),\s*(\d+),'(.*)'[.]split[(]'[|]'[)]""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val LINKS_BLOCK_REGEX = Regex("""links\s*=\s*[{]([^}]*)[}]""")
        private val FILE_REGEX = Regex("""["']?file["']?\s*:\s*"([^"]*(?:m3u8|\.mp4)[^"]*)"""")
        private val SOURCES_REGEX = Regex("""sources\s*:\s*\[\s*\{[^}]*?file\s*:\s*"([^"]+)"""")

        /** filesforever keys that actually answer with a stream. */
        private val LIVE_SOURCE_KEYS = listOf("smwh", "strmtp")
    }
}
