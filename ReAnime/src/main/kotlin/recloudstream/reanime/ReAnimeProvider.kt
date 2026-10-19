package recloudstream.reanime

import com.lagradost.cloudstream3.DubStatus
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
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.Requests
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Re:ANIME (reanime.to).
 *
 * The site is an API in front of AniList metadata, so search, details and
 * episodes are JSON; playback comes from the same `/api/flix` endpoint the site's
 * own watch page calls, which answers with FlixCloud embeds. Those embeds are
 * encrypted (see [FlixLocalServer]) and are unsealed here.
 */
class ReAnimeProvider : MainAPI() {

    override var mainUrl = "https://reanime.to"
    override var name = "Re:ANIME"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.OVA)

    override val mainPage = mainPageOf(
        "popular" to "Popular",
        "latest" to "Latest",
        "movies" to "Movies",
    )

    private val app = Requests(defaultHeaders = mapOf("User-Agent" to USER_AGENT))

    private val api get() = "$mainUrl/api/v1"

    private val detailCache = ConcurrentHashMap<String, JSONObject>()

    /** Latest-aired is cursor paginated; the cursor for a page is kept from the page before it. */
    private val latestCursors = ConcurrentHashMap<Int, String>()

    private val flixServer by lazy {
        FlixLocalServer(mapOf("User-Agent" to USER_AGENT))
    }

    // ============================== Home page ==============================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? =
        when (request.data) {
            "latest" -> latestPage(page, request.name)
            "movies" -> cataloguePage(page, request.name, sort = "popularity_desc", format = "movie")
            else -> cataloguePage(page, request.name, sort = "popularity_desc")
        }

    private suspend fun cataloguePage(
        page: Int,
        sectionName: String,
        sort: String,
        format: String? = null,
    ): HomePageResponse {
        val params = linkedMapOf(
            "limit" to LIMIT.toString(),
            "offset" to ((page - 1) * LIMIT).toString(),
            "sort" to sort,
        )
        format?.let { params["format"] = it }

        val json = getJson("$api/search", "$mainUrl/search", params)
        val items = json.optJSONArray("results").objects().mapNotNull { it.toSearchResponse() }
        val hasNext = (json.optInt("offset") + json.optInt("limit")) < json.optInt("total")
        return newHomePageResponse(sectionName, items, hasNext)
    }

    private suspend fun latestPage(page: Int, sectionName: String): HomePageResponse {
        val params = linkedMapOf("limit" to "20")
        params["lang"] = "en"
        latestCursors[page - 1]?.let { params["cursor"] = it }

        val json = getJson("$api/home/latest-aired", "$mainUrl/home", params)
        json.optString("next_cursor").takeIf { it.isNotBlank() }?.let { latestCursors[page] = it }

        val items = json.optJSONArray("data").objects().mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(sectionName, items, json.optBoolean("has_more", false))
    }

    // ============================== Search ==============================

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val json = getJson(
            "$api/search",
            "$mainUrl/search",
            linkedMapOf(
                "limit" to LIMIT.toString(),
                "offset" to ((page - 1) * LIMIT).toString(),
                "q" to query,
            ),
        )
        val items = json.optJSONArray("results").objects().mapNotNull { it.toSearchResponse() }
        val hasNext = (json.optInt("offset") + json.optInt("limit")) < json.optInt("total")
        return newSearchResponseList(items, hasNext)
    }

    // ============================== Details ==============================

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val detail = fetchDetail(slug) ?: return null
        val title = preferredTitle(detail.optJSONObject("title")) ?: slug

        val subbed = mutableListOf<Episode>()
        val dubbed = mutableListOf<Episode>()
        val maxSub = detail.optInt("subbed", 0)
        val maxDub = detail.optInt("dubbed", 0)

        fetchEpisodes(slug)
            .sortedBy { it.optDouble("episode_number", 0.0) }
            .forEach { episode ->
                val number = episode.optDouble("episode_number", 0.0)
                val shownNumber = if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
                val episodeTitle = preferredEpisodeTitle(episode)
                val name = buildString {
                    append("Episode ").append(shownNumber)
                    if (episodeTitle.isNotBlank() && !episodeTitle.contains("Episode", ignoreCase = true)) {
                        append(" - ").append(episodeTitle)
                    }
                    if (episode.optBoolean("is_recap")) append(" [Recap]")
                    if (episode.optBoolean("is_filler")) append(" [Filler]")
                }
                val thumbnail = episode.optString("thumbnail").takeIf { it.isNotBlank() }
                val description = episode.optString("description").takeIf { it.isNotBlank() }
                val aired = episode.optString("aired").takeIf { it.isNotBlank() }

                fun build(audio: String) = newEpisode(
                    url = "$slug|$shownNumber|$audio",
                    fix = false,
                    initializer = {
                        this.name = name
                        this.episode = number.roundToInt()
                        this.season = 1
                        this.posterUrl = thumbnail
                        this.description = description
                        aired?.let { value -> addDate(value.take(10), "yyyy-MM-dd") }
                    },
                )

                // The site tracks how many episodes carry each audio track, and answers a
                // missing count with 0, which means "not reported" rather than "none".
                if (maxSub == 0 || number <= maxSub) subbed += build("sub")
                if (maxDub > 0 && number <= maxDub) dubbed += build("dub")
            }

        return newAnimeLoadResponse(title, "$mainUrl/anime/$slug", TvType.Anime) {
            this.posterUrl = coverUrl(detail.optJSONObject("cover_image"))
            this.plot = buildDescription(detail)
            this.tags = buildTags(detail)
            this.year = detail.optInt("season_year", 0).takeIf { it > 0 }
            this.backgroundPosterUrl = detail.optString("banner_image").takeIf { it.isNotBlank() }
            this.showStatus = when (detail.optString("status").uppercase(Locale.US)) {
                "RELEASING" -> ShowStatus.Ongoing
                "FINISHED" -> ShowStatus.Completed
                else -> null
            }
            addEpisodes(DubStatus.Subbed, subbed)
            addEpisodes(DubStatus.Dubbed, dubbed)
        }
    }

    // ============================== Playback ==============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parts = data.split("|")
        val slug = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return false
        val episodeNumber = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return false
        val preferredAudio = parts.getOrNull(2) ?: "sub"

        val anilistId = (fetchDetail(slug)?.optInt("anilist_id", 0) ?: 0)
        if (anilistId <= 0) return false

        val watchUrl = "$mainUrl/watch/$slug?ep=$episodeNumber"
        val flix = runCatching { getJson("$mainUrl/api/flix/$anilistId/$episodeNumber", watchUrl) }
            .getOrNull() ?: return false
        if (!flix.optBoolean("success", false)) return false

        // Sub and dub servers often share the same embed link, so both are kept.
        val servers = flix.optJSONArray("servers").objects()
            .mapNotNull { server ->
                val link = server.optString("dataLink").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Server(
                    link = link,
                    audio = server.optString("dataType").ifBlank { "sub" },
                    label = server.optString("serverName"),
                )
            }
            .distinctBy { it.link + "|" + it.audio }
            .sortedBy { if (it.audio.equals(preferredAudio, ignoreCase = true)) 0 else 1 }

        var found = false
        val seenSubtitles = mutableSetOf<String>()
        servers.forEach { server ->
            val stream = runCatching { openStream(server.link) }.getOrNull() ?: return@forEach
            val audioLabel = if (server.audio.equals("dub", ignoreCase = true)) "Dub" else "Sub"

            stream.subtitles.forEach { (url, language) ->
                // Sub and dub servers often point at the same tracks.
                if (seenSubtitles.add(url)) subtitleCallback(newSubtitleFile(language, url))
            }

            stream.variants.forEach { variant ->
                found = true
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${server.label.ifBlank { "FlixCloud" }} · ${variant.label} · $audioLabel",
                        url = variant.url,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = watchUrl
                        this.quality = getQualityFromName(variant.height?.let { "${it}p" })
                        this.headers = mapOf()
                    },
                )
            }
        }
        return found
    }

    private data class Server(val link: String, val audio: String, val label: String)

    private data class Variant(val label: String, val url: String, val height: Int?)

    private data class Stream(
        val variants: List<Variant>,
        val subtitles: List<Pair<String, String>>,
    )

    /**
     * Unseals one FlixCloud embed: mask from the shipped hls.js, embed data from the
     * page, token then stream from enc-dec, and finally the playlist itself.
     */
    private suspend fun openStream(dataLink: String): Stream? {
        val html = app.get(dataLink, headers = flixHeaders()).text

        val mask = runCatching {
            val scriptPath = HLS_SCRIPT_REGEX.find(html)?.groupValues?.get(1) ?: return@runCatching null
            val scriptUrl = if (scriptPath.startsWith("http")) scriptPath else FlixLocalServer.FLIX_CLOUD + scriptPath
            val script = app.get(scriptUrl, headers = flixHeaders()).text
            XOR_MASK_REGEX.find(script)?.groupValues?.get(1)
                ?.split(",")
                ?.map { it.trim().toInt().toByte() }
                ?.toByteArray()
        }.getOrNull()

        val embed = EMBED_DATA_REGEX.find(html)?.groupValues?.get(1) ?: return null
        val embedJson = runCatching { JSONObject(json5ToJson(embed)) }.getOrNull() ?: return null

        val server = flixServer
        mask?.let { server.segmentMask = it }

        val subtitles = embedJson.optJSONArray("subtitles").objects().mapNotNull { subtitle ->
            val url = subtitle.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            server.subtitleUrl(url) to subtitle.optString("language").ifBlank { "Unknown" }
        }

        // The token is made from the embed data, minus the subtitles.
        val embedForToken = JSONObject(embedJson.toString()).apply { remove("subtitles") }
        val tokenBody = """{"data":$embedForToken}"""
        val tokenJson = runCatching {
            JSONObject(
                app.post(
                    "$ENC_DEC/api/dec-flixcloud?type=token",
                    headers = mapOf("Accept" to "*/*"),
                    requestBody = tokenBody.toRequestBody(JSON_MEDIA_TYPE),
                ).text,
            )
        }.getOrNull() ?: return null
        if (tokenJson.optInt("status", 0) != 200) return null
        val tokenResult = tokenJson.optJSONObject("result") ?: return null

        val encrypted = runCatching {
            app.get(
                "${FlixLocalServer.FLIX_CLOUD}/api/m3u8/${tokenResult.optString("token")}",
                headers = flixHeaders(),
            ).text
        }.getOrNull() ?: return null
        val streamResponse = runCatching { JSONObject(encrypted) }.getOrNull() ?: return null

        val streamBody = """{"data":{"context":${tokenResult.optJSONObject("context") ?: "{}"},""" +
            """"stream_response":${streamResponse.toString()}}}"""
        val streamJson = runCatching {
            JSONObject(
                app.post(
                    "$ENC_DEC/api/dec-flixcloud?type=stream",
                    headers = mapOf("Accept" to "*/*"),
                    requestBody = streamBody.toRequestBody(JSON_MEDIA_TYPE),
                ).text,
            )
        }.getOrNull() ?: return null
        if (streamJson.optInt("status", 0) != 200) return null
        val streamResult = streamJson.optJSONObject("result") ?: return null

        val streamUrl = streamResult.optString("stream").takeIf { it.isNotBlank() } ?: return null
        val wPayload = streamResult.optJSONObject("context")?.optString("w_payload")
            ?.takeIf { it.isNotBlank() } ?: return null

        // The playlist is served by the local proxy already rewritten, so its variant
        // lines are ready-to-play local URLs with real resolutions attached.
        val playlistUrl = server.playlistUrl(streamUrl, wPayload)
        val playlist = runCatching { app.get(playlistUrl).text }.getOrNull() ?: return null

        val variants = mutableListOf<Variant>()
        var pendingHeight: Int? = null
        playlist.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") ->
                    pendingHeight = RESOLUTION_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()

                line.isNotEmpty() && !line.startsWith("#") -> {
                    val height = pendingHeight
                    variants += Variant(
                        label = height?.let { "${it}p" } ?: "Auto",
                        url = line,
                        height = height,
                    )
                    pendingHeight = null
                }
            }
        }
        if (variants.isEmpty()) variants += Variant("Auto", playlistUrl, null)

        return Stream(variants, subtitles)
    }

    // ============================== Parsing helpers ==============================

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private suspend fun getJson(
        url: String,
        referer: String,
        params: Map<String, String> = emptyMap(),
    ): JSONObject {
        val response = app.get(url, headers = apiHeaders(referer), params = params)
        if (response.code !in 200..299) {
            throw IllegalStateException("HTTP ${response.code} from $url")
        }
        return JSONObject(response.text)
    }

    private fun apiHeaders(referer: String): Map<String, String> = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to referer,
    )

    private fun flixHeaders(referer: String = "${FlixLocalServer.FLIX_CLOUD}/"): Map<String, String> = mapOf(
        "Accept" to "*/*",
        "Origin" to FlixLocalServer.FLIX_CLOUD,
        "Referer" to referer,
    )

    private suspend fun fetchDetail(slug: String): JSONObject? {
        detailCache[slug]?.let { return it }
        val detail = runCatching { getJson("$api/anime/$slug", "$mainUrl/anime/$slug") }.getOrNull()
            ?: return null
        detailCache[slug] = detail
        return detail
    }

    private suspend fun fetchEpisodes(slug: String): List<JSONObject> = runCatching {
        getJson(
            "$api/anime/$slug/episodes",
            "$mainUrl/anime/$slug",
            mapOf("limit" to "2000"),
        ).optJSONArray("data").objects()
    }.getOrDefault(emptyList())

    private fun JSONObject.toSearchResponse(): SearchResponse? {
        val id = optString("anime_id").takeIf { it.isNotBlank() } ?: return null
        val title = preferredTitle(optJSONObject("title")) ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id", fix = false) {
            this.posterUrl = coverUrl(optJSONObject("cover_image"))
        }
    }

    private fun preferredTitle(title: JSONObject?): String? {
        if (title == null) return null
        return title.optString("romaji").takeIf { it.isNotBlank() }
            ?: title.optString("english").takeIf { it.isNotBlank() }
            ?: title.optString("native").takeIf { it.isNotBlank() }
    }

    private fun preferredEpisodeTitle(episode: JSONObject): String =
        episode.optString("title").takeIf { it.isNotBlank() }
            ?: episode.optString("title_romanji").takeIf { it.isNotBlank() }
            ?: episode.optString("title_japanese").takeIf { it.isNotBlank() }
            ?: ""

    /**
     * AniList's extra-large cover is a TMDB image for many titles, and TMDB rejects
     * the size suffix the site appends, so those fall back to the large variant.
     */
    private fun coverUrl(cover: JSONObject?): String? {
        if (cover == null) return null
        val extraLarge = cover.optString("extra_large").takeIf { it.isNotBlank() }
        val large = cover.optString("large").takeIf { it.isNotBlank() }
        val medium = cover.optString("medium").takeIf { it.isNotBlank() }
        return if (extraLarge != null && !extraLarge.contains("tmdb.org")) {
            extraLarge
        } else {
            large ?: medium ?: extraLarge
        }
    }

    private fun buildTags(detail: JSONObject): List<String> {
        // Genres arrive as plain strings, tags as {name, rank} objects.
        val genres = detail.optJSONArray("genres").objects().mapNotNull { genre ->
            genre.optString("name").takeIf { it.isNotBlank() }
        }
        val plainGenres = (0 until (detail.optJSONArray("genres")?.length() ?: 0)).mapNotNull { index ->
            detail.optJSONArray("genres")?.opt(index)?.toString()?.takeIf { it.isNotBlank() }
        }
        val tags = (detail.optJSONArray("tags")?.objects() ?: emptyList())
            .sortedByDescending { it.optInt("rank", 0) }
            .mapNotNull { it.optString("name").takeIf { name -> name.isNotBlank() } }
        return (genres + plainGenres + tags).distinct()
    }

    private fun buildDescription(detail: JSONObject): String = buildString {
        detail.optInt("average_score", 0).takeIf { it > 0 }?.let { score ->
            val stars = (score / 20.0).roundToInt().coerceIn(1, 5)
            append("★".repeat(stars)).append("☆".repeat(5 - stars)).append(' ').append(score)
            append("\n\n")
        }

        detail.optString("description").takeIf { it.isNotBlank() }?.let { raw ->
            val cleaned = raw
                .replace(BR_REGEX, "\n")
                .replace(HTML_TAG_REGEX, "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim()
            if (cleaned.isNotBlank()) {
                append(cleaned)
                append("\n\n")
            }
        }

        val altTitles = buildList {
            detail.optJSONObject("title")?.let { title ->
                listOf("english", "romaji", "native").forEach { key ->
                    title.optString(key).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
            (0 until (detail.optJSONArray("synonyms")?.length() ?: 0)).forEach { index ->
                detail.optJSONArray("synonyms")?.optString(index)?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.distinct()

        val info = mutableListOf<String>()
        if (altTitles.isNotEmpty()) info += "**Alternative Titles**: ${altTitles.joinToString(" • ")}"
        detail.optString("format").takeIf { it.isNotBlank() }?.let { info += "**Format**: $it" }

        val source = detail.optString("source").takeIf { it.isNotBlank() }
            ?.replace("_", " ")
            ?.lowercase(Locale.US)
            ?.replaceFirstChar { it.titlecase(Locale.US) }
        val country = detail.optString("country_of_origin").takeIf { it.isNotBlank() }
        if (source != null || country != null) {
            info += "**Source**: " + listOfNotNull(source, country).joinToString(" • ")
        }

        val start = formatFuzzyDate(detail.optJSONObject("start_date"))
        val end = formatFuzzyDate(detail.optJSONObject("end_date"))
        when {
            start != null && end != null && start == end -> info += "**Air Date**: On $start"
            start != null && end != null -> info += "**Airing**: From $start to $end"
            start != null -> info += "**Start Date**: $start"
            end != null -> info += "**End Date**: $end"
        }

        detail.optJSONObject("next_airing_episode")?.let { next ->
            val episode = next.optInt("episode", 0)
            val airingAt = next.optString("airing_at").takeIf { it.isNotBlank() }
            if (episode > 0 && airingAt != null) {
                parseAiringDate(airingAt)?.let { info += "**Next Airing**: Episode $episode on $it" }
            }
        }

        val season = detail.optString("season").takeIf { it.isNotBlank() && it != "0" }
            ?.replaceFirstChar { it.titlecase(Locale.US) }
        val seasonYear = detail.optInt("season_year", 0).takeIf { it > 0 }
        if (season != null && seasonYear != null) info += "**Season**: $season $seasonYear"

        detail.optInt("duration", 0).takeIf { it > 0 }?.let { info += "**Duration**: ${it}m" }
        detail.optString("rating").takeIf { it.isNotBlank() }?.let { info += "**Rating**: $it" }

        if (info.isNotEmpty()) {
            append(info.joinToString("\n"))
            append("\n")
        }

        val trackers = buildList {
            detail.optInt("anilist_id", 0).takeIf { it > 0 }?.let { add("[AniList](https://anilist.co/anime/$it)") }
            detail.optInt("mal_id", 0).takeIf { it > 0 }?.let { add("[MAL](https://myanimelist.net/anime/$it)") }
            detail.optInt("kitsu_id", 0).takeIf { it > 0 }?.let { add("[Kitsu](https://kitsu.io/anime/$it)") }
            detail.optInt("anidb_id", 0).takeIf { it > 0 }?.let { add("[AniDB](https://anidb.net/anime/$it)") }
            detail.optInt("themoviedb_id", 0).takeIf { it > 0 }
                ?.let { add("[TMDB](https://www.themoviedb.org/tv/$it)") }
            detail.optString("imdb_id").takeIf { it.isNotBlank() }
                ?.let { add("[IMDB](https://www.imdb.com/title/$it)") }
        }
        if (trackers.isNotEmpty()) {
            append("**Trackers**: ${trackers.joinToString(" • ")}")
            append("\n")
        }

        detail.optJSONObject("trailer")
            ?.takeIf { it.optString("site") == "youtube" }
            ?.optString("id")
            ?.takeIf { it.isNotBlank() }
            ?.let { append("**Trailer**: [YouTube](https://www.youtube.com/watch?v=$it)\n") }

        detail.optString("banner_image").takeIf { it.isNotBlank() }?.let { append("\n![Banner]($it)") }
    }.trim()

    private fun formatFuzzyDate(date: JSONObject?): String? {
        if (date == null) return null
        val year = date.optInt("year", 0)
        val month = date.optInt("month", 0)
        val day = date.optInt("day", 0)
        if (year <= 0 || month !in 1..12) return null
        val monthName = MONTHS[month - 1]
        return if (day > 0) "$monthName ${ordinal(day)}, $year" else "$monthName $year"
    }

    private fun ordinal(number: Int): String = when {
        number in 11..13 -> "${number}th"
        else -> when (number % 10) {
            1 -> "${number}st"
            2 -> "${number}nd"
            3 -> "${number}rd"
            else -> "${number}th"
        }
    }

    private fun parseAiringDate(iso: String): String? = runCatching {
        val parts = iso.substringBefore('T').split('-')
        formatFuzzyDate(
            JSONObject()
                .put("year", parts[0].toInt())
                .put("month", parts[1].toInt())
                .put("day", parts[2].toInt()),
        )
    }.getOrNull()

    private fun json5ToJson(json5: String): String = json5
        .replace(JSON5_KEY_REGEX) { "${it.groupValues[1]}\"${it.groupValues[2]}\"${it.groupValues[3]}" }
        .replace(JSON5_TRAILING_COMMA_REGEX, "$1")
        .replace(JSON5_UNDEFINED_REGEX, ": null")

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private const val LIMIT = 36
        private const val ENC_DEC = FlixLocalServer.ENC_DEC
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val MONTHS = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )

        private val BR_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("""</?(i|b|em|p|strong)>""", RegexOption.IGNORE_CASE)
        private val EMBED_DATA_REGEX =
            Regex("""type:\s*"data",\s*data:\s*(\{.*?\}),?\s*uses:""", RegexOption.DOT_MATCHES_ALL)
        private val JSON5_KEY_REGEX = Regex("""([{,]\s*)([\w_]+)(\s*:)""")
        private val JSON5_TRAILING_COMMA_REGEX = Regex(""",\s*([}\]])""")
        private val JSON5_UNDEFINED_REGEX = Regex(""":\s*undefined\b""")
        private val HLS_SCRIPT_REGEX = Regex("""href="([^"]*hls\.js[^"]*)"""")
        private val XOR_MASK_REGEX = Regex("""for\(var f=\[(\d{1,3}(?:,\d{1,3}){15})]""")
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""")
    }
}
