package com.tramphim.cloudstream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * CloudStream provider for Trạm Phim (https://tramphim3.org).
 *
 * The site is a Next.js (App Router) frontend on top of several Vietnamese
 * movie back-ends (VSmov, Nguonc, KKPhim/PhimApi, ViCDN).
 *
 * How data is obtained:
 *  - Main page sections -> server-rendered HTML list pages, e.g. /phim-le?page=2
 *                          (cards are <a class="movie-card"> elements)
 *  - Search             -> public JSON endpoint /api/search?keyword=..&limit=..
 *  - Detail             -> the film object is embedded in the streamed RSC payload
 *                          (self.__next_f.push chunks) as "movie":{...} followed by
 *                          "episodes":[{server_name, server_data:[{link_m3u8, link_embed}]}]
 *  - Extra servers      -> /api/backup-servers?slug=..&tmdb_id=.. (Nguonc/KKPhim/ViCDN/VSmov)
 *  - VSmov embeds       -> the player page exposes {baseUrl}/stream/{hash}/master.m3u8
 *                          plus WebVTT subtitles; resolved natively without JS.
 */
class TramPhimProvider : MainAPI() {
    override var mainUrl = "https://tramphim3.org"
    override var name = "Trạm Phim"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
    )

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8",
    )

    /** Section path -> display name. `data` of mainPage is the site path. */
    private val sections = linkedMapOf(
        "/phim-le" to "Phim lẻ",
        "/phim-bo" to "Phim bộ",
        "/phim-chieu-rap" to "Phim chiếu rạp",
        "/phim-song-ngu" to "Phim song ngữ",
        "/phim-long-tien" to "Phim lồng tiếng",
        "/hoat-hinh" to "Hoạt hình",
        "/phim-sap-chieu" to "Phim sắp chiếu",
    )

    override val mainPage = mainPageOf(
        *sections.entries.map { (path, title) -> path to title }.toTypedArray()
    )

    // ------------------------------------------------------------------ //
    // JSON models
    // ------------------------------------------------------------------ //

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Taxonomy(
        val id: String? = null,
        val name: String? = null,
        val slug: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbInfo(
        val id: String? = null,
        val type: String? = null,
        val season: Int? = null,
        val year: Int? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ImdbInfo(
        val id: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FilmObject(
        val _id: String? = null,
        val name: String? = null,
        val slug: String? = null,
        @JsonProperty("origin_name") val originName: String? = null,
        val content: String? = null,
        val type: String? = null, // "single" | "series"
        val status: String? = null, // completed | ongoing | trailer
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("trailer_url") val trailerUrl: String? = null,
        val time: String? = null,
        @JsonProperty("episode_current") val episodeCurrent: String? = null,
        @JsonProperty("episode_total") val episodeTotal: String? = null,
        val quality: String? = null,
        val lang: String? = null,
        val year: Int? = null,
        val actor: List<String>? = null,
        val director: List<String>? = null,
        val category: List<Taxonomy>? = null,
        val country: List<Taxonomy>? = null,
        val tmdb: TmdbInfo? = null,
        val imdb: ImdbInfo? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        val name: String? = null,
        val slug: String? = null,
        val filename: String? = null,
        @JsonProperty("link_embed") val linkEmbed: String? = null,
        @JsonProperty("link_m3u8") val linkM3u8: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeGroup(
        @JsonProperty("server_name") val serverName: String? = null,
        @JsonProperty("server_data") val serverData: List<EpisodeItem>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchEnvelope(
        val items: List<FilmObject>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BackupServers(
        @JsonProperty("nguoncEpisodes") val nguoncEpisodes: List<EpisodeGroup>? = null,
        @JsonProperty("phimApiEpisodes") val phimApiEpisodes: List<EpisodeGroup>? = null,
        @JsonProperty("vsmovEpisodes") val vsmovEpisodes: List<EpisodeGroup>? = null,
        @JsonProperty("vicdnEpisodes") val vicdnEpisodes: List<EpisodeGroup>? = null,
    )

    /** One playable source of one episode/movie, passed into loadLinks(). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Track(
        val server: String = "Trạm Phim",
        val label: String? = null,
        val quality: String? = null,
        val m3u8: String? = null,
        val embed: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Tracks(val tracks: List<Track> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VsmovSubtitle(
        val name: String? = null,
        val url: String? = null,
        val code: String? = null,
    )

    // ------------------------------------------------------------------ //
    // Helpers
    // ------------------------------------------------------------------ //

    private fun typeForSection(path: String): TvType = when (path) {
        "/phim-bo" -> TvType.TvSeries
        "/hoat-hinh" -> TvType.Anime
        else -> TvType.Movie
    }

    private fun isAnime(categories: List<Taxonomy>?): Boolean {
        val slugs = categories.orEmpty().mapNotNull { it.slug?.lowercase() }
        val names = categories.orEmpty().mapNotNull { it.name?.lowercase() }
        return slugs.any { it == "hoat-hinh" || it == "anime" || it == "animation" } ||
            names.any { it.contains("hoạt hình") || it.contains("anime") }
    }

    private fun typeForFilm(film: FilmObject?): TvType {
        val anime = isAnime(film?.category)
        return when (film?.type) {
            "series", "tvshows", "tv_series" -> if (anime) TvType.Anime else TvType.TvSeries
            else -> if (anime) TvType.AnimeMovie else TvType.Movie
        }
    }

    private fun normalizeUrl(href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("/") -> "$mainUrl$href"
        else -> "$mainUrl/$href"
    }

    /** Cards use the wsrv.nl image proxy; unwrap it to the original poster URL. */
    private fun unwrapImage(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (!url.contains("wsrv.nl")) return url
        val encoded = Regex("""[?&]url=([^&]+)""").find(url)?.groupValues?.get(1) ?: return url
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: url
    }

    private fun qualityFrom(label: String?): Int {
        val l = label?.lowercase() ?: return Qualities.Unknown.value
        return when {
            "4k" in l || "2160" in l -> Qualities.P2160.value
            "1080" in l || "full hd" in l || "fhd" in l -> Qualities.P1080.value
            "720" in l || l.contains(Regex("""\bhd\b""")) -> Qualities.P720.value
            "sd" in l -> Qualities.P480.value
            "cam" in l || Regex("""\bts\b""").containsMatchIn(l) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    /** Maps the site's quality labels ("CAM TS", "HD", "4K", ...) onto CloudStream's search badge. */
    private fun searchQuality(label: String?): SearchQuality? {
        val l = label?.lowercase() ?: return null
        return when {
            "4k" in l || "2160" in l || "uhd" in l -> SearchQuality.FourK
            "cam" in l || Regex("\\bts\\b").containsMatchIn(l) -> SearchQuality.HdCam
            "1080" in l || "full hd" in l || "fhd" in l || Regex("\\bhd\\b").containsMatchIn(l) || "720" in l -> SearchQuality.HD
            "sd" in l -> SearchQuality.SD
            "bluray" in l || "blue-ray" in l -> SearchQuality.BlueRay
            else -> null
        }
    }

    private fun linkType(url: String): ExtractorLinkType {
        val clean = url.substringBefore("?").lowercase()
        return when {
            clean.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".webm") -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.M3U8
        }
    }

    private fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "${uri.scheme}://${uri.host}$port"
    }.getOrNull()

    private fun cleanTextList(values: List<String>?): List<String> = values.orEmpty()
        .map { it.trim() }
        .filter { it.isNotBlank() && it != "Đang cập nhật" && it.lowercase() != "unknown" && it.lowercase() != "n/a" }
        .distinct()

    private fun String.stripHtml(): String = runCatching {
        Jsoup.parse(this).text().trim()
    }.getOrNull() ?: this

    private fun episodeNumber(name: String?, index: Int): Int {
        Regex("""\d+""").find(name ?: "")?.value?.toIntOrNull()?.let { return it }
        return index + 1
    }

    /** Decodes the JS string escapes used inside self.__next_f.push([1,"..."]) chunks. */
    private fun jsUnescape(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val n = raw[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'v' -> { sb.append('\u000B'); i += 2 }
                    '0' -> { sb.append('\u0000'); i += 2 }
                    '\n' -> i += 2 // line continuation
                    'x' -> if (i + 4 <= raw.length) {
                        val code = raw.substring(i + 2, i + 4).toIntOrNull(16)
                        if (code != null) { sb.append(code.toChar()); i += 4 } else { sb.append(n); i += 2 }
                    } else {
                        sb.append(n); i += 2
                    }
                    'u' -> if (i + 6 <= raw.length) {
                        val code = raw.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) { sb.append(code.toChar()); i += 6 } else { sb.append(n); i += 2 }
                    } else {
                        sb.append(n); i += 2
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }

    private val nextChunkRegex = Regex("""self\.__next_f\.push\(\[1,\s*"((?:[^"\\]|\\.)*)"\]\)""")

    /** Joins all RSC flight chunks of a page into one decoded blob. */
    private fun rscBlob(html: String): String? {
        val chunks = nextChunkRegex.findAll(html).map { it.groupValues[1] }.toList()
        if (chunks.isEmpty()) return null
        return chunks.joinToString("") { jsUnescape(it) }
    }

    /**
     * Finds the balanced end of the bracketed expression starting at [start]
     * (which must point at the opening char). String-aware.
     * Returns the index just past the closing bracket, or -1.
     */
    private fun balancedEnd(s: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < s.length) {
            val ch = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
            i++
        }
        return -1
    }

    private val movieKeyRegex = Regex(""""movie"\s*:\s*""")

    /** Extracts the film JSON object whose slug matches [slug], plus where it ends in the blob. */
    private fun findFilm(blob: String, slug: String): Pair<FilmObject, Int>? {
        for (match in movieKeyRegex.findAll(blob)) {
            val brace = match.range.last + 1
            if (brace >= blob.length || blob[brace] != '{') continue
            val end = balancedEnd(blob, brace, '{', '}')
            if (end == -1) continue
            val film = runCatching { parseJson<FilmObject>(blob.substring(brace, end)) }.getOrNull() ?: continue
            if (film.slug == slug) return film to end
        }
        return null
    }

    /** Extracts the "episodes":[ ... ] array located at/after [from] in the blob. */
    private fun findEpisodes(blob: String, from: Int): List<EpisodeGroup> {
        val regex = Regex(""""episodes"\s*:\s*\[""")
        val match = regex.find(blob, from) ?: return emptyList()
        val bracketStart = match.range.last // index of '['
        val end = balancedEnd(blob, bracketStart, '[', ']')
        if (end == -1) return emptyList()
        return runCatching { parseJson<List<EpisodeGroup>>(blob.substring(bracketStart, end)) }
            .getOrNull().orEmpty()
    }

    /** Related movies embedded further down the RSC payload. */
    private fun findRecommendations(blob: String, excludeSlug: String, limit: Int = 15): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (match in movieKeyRegex.findAll(blob)) {
            if (out.size >= limit) break
            val brace = match.range.last + 1
            if (brace >= blob.length || blob[brace] != '{') continue
            val end = balancedEnd(blob, brace, '{', '}')
            if (end == -1) continue
            val film = runCatching { parseJson<FilmObject>(blob.substring(brace, end)) }.getOrNull() ?: continue
            val filmSlug = film.slug ?: continue
            if (filmSlug == excludeSlug) continue
            buildSearchResponse(film)?.let { out.add(it) }
        }
        return out
    }

    private fun buildSearchResponse(film: FilmObject): SearchResponse? {
        val slug = film.slug ?: return null
        val title = film.name?.trim()?.takeIf { it.isNotBlank() }
            ?: film.originName?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val url = "$mainUrl/phim/$slug"
        val type = typeForFilm(film)
        val poster = unwrapImage(film.posterUrl ?: film.thumbUrl)
        val quality = searchQuality(film.quality)
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, url) {
                this.posterUrl = poster
                this.year = film.year
                this.quality = quality
            }
            TvType.Anime -> newAnimeSearchResponse(title, url) {
                this.posterUrl = poster
                this.year = film.year
                this.quality = quality
            }
            else -> newMovieSearchResponse(title, url) {
                this.posterUrl = poster
                this.year = film.year
                this.quality = quality
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Main page
    // ------------------------------------------------------------------ //

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = "$mainUrl${path}?page=$page"
        val html = runCatching { app.get(url, headers = baseHeaders).text }.getOrNull()
        val results = html?.let { parseCards(it, typeForSection(path)) }.orEmpty()
        return newHomePageResponse(
            HomePageList(request.name, results),
            hasNext = results.isNotEmpty(),
        )
    }

    private fun parseCards(html: String, type: TvType): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        return doc.select("a.movie-card").mapNotNull { el ->
            val href = el.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val titleAttr = el.attr("title")
            val name = el.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: titleAttr.removePrefix("Xem ").substringBefore(" Full HD").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val link = normalizeUrl(href)
            val year = Regex("""(\d{4})\s*$""").find(titleAttr)?.groupValues?.get(1)?.toIntOrNull()
            val poster = unwrapImage(el.selectFirst("img")?.attr("src"))
            val badgeText = el.select("span").eachText().joinToString(" ")
            val quality = searchQuality(badgeText.ifBlank { titleAttr })
            when (type) {
                TvType.TvSeries -> newTvSeriesSearchResponse(name, link) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = quality
                }
                TvType.Anime -> newAnimeSearchResponse(name, link) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = quality
                }
                else -> newMovieSearchResponse(name, link) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = quality
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Search
    // ------------------------------------------------------------------ //

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val body = runCatching {
            app.get("$mainUrl/api/search?keyword=$encoded&limit=24", headers = baseHeaders).text
        }.getOrNull() ?: return emptyList()
        val envelope = runCatching { parseJson<SearchEnvelope>(body) }.getOrNull() ?: return emptyList()
        return envelope.items.orEmpty()
            .mapNotNull { buildSearchResponse(it) }
            .distinctBy { it.url }
    }

    // ------------------------------------------------------------------ //
    // Detail
    // ------------------------------------------------------------------ //

    override suspend fun load(url: String): LoadResponse? {
        val slug = Regex("""/phim/([a-zA-Z0-9-]+)/?$""").find(url)?.groupValues?.get(1)
            ?: Regex("""/phim/([a-zA-Z0-9-]+)""").find(url)?.groupValues?.get(1)
            ?: return null

        val html = runCatching {
            app.get("$mainUrl/phim/$slug", headers = baseHeaders).text
        }.getOrNull() ?: return null

        val blob = rscBlob(html) ?: return null
        val (film, filmEnd) = findFilm(blob, slug) ?: return null
        val pageEpisodes = findEpisodes(blob, filmEnd)
        val backups = fetchBackups(slug, film.tmdb?.id?.takeIf { it.isNotBlank() })

        // Merge every server group, tagging the backup sources for clarity.
        val groups = mutableListOf<Pair<String, List<EpisodeItem>>>()
        pageEpisodes.forEach { group ->
            group.serverData?.takeIf { it.isNotEmpty() }?.let {
                groups.add((group.serverName ?: "Trạm Phim") to it)
            }
        }
        backups?.nguoncEpisodes?.forEach { g ->
            g.serverData?.takeIf { it.isNotEmpty() }?.let { groups.add("Nguonc • ${g.serverName ?: "Vietsub"}" to it) }
        }
        backups?.phimApiEpisodes?.forEach { g ->
            g.serverData?.takeIf { it.isNotEmpty() }?.let { groups.add("KKPhim • ${g.serverName ?: "Vietsub"}" to it) }
        }
        backups?.vsmovEpisodes?.forEach { g ->
            g.serverData?.takeIf { it.isNotEmpty() }?.let { groups.add("VSmov • ${g.serverName ?: "Vietsub"}" to it) }
        }
        backups?.vicdnEpisodes?.forEach { g ->
            g.serverData?.takeIf { it.isNotEmpty() }?.let { groups.add("ViCDN • ${g.serverName ?: "Vietsub"}" to it) }
        }

        fun tracksOf(item: EpisodeItem, server: String) = Track(
            server = server,
            label = item.name,
            quality = film.quality,
            m3u8 = item.linkM3u8?.trim()?.takeIf { it.isNotBlank() },
            embed = item.linkEmbed?.trim()?.takeIf { it.isNotBlank() },
        )

        val isSeries = film.type == "series" || film.type == "tv_series" || film.type == "tvshows"
        val title = film.name?.trim().takeIf { !it.isNullOrBlank() } ?: film.originName ?: "Trạm Phim"
        val poster = unwrapImage(film.posterUrl ?: film.thumbUrl)
        val background = unwrapImage(film.thumbUrl ?: film.posterUrl)
        val plot = film.content?.stripHtml()?.takeIf { it.isNotBlank() }
        val tags = cleanTextList(film.category?.mapNotNull { it.name }) +
            cleanTextList(film.country?.mapNotNull { it.name })
        val cast = cleanTextList(film.actor).map { ActorData(Actor(it)) }
        val score = film.tmdb?.voteAverage ?: film.imdb?.voteAverage
        val recommendations = findRecommendations(blob, slug)
        val comingSoon = film.status.equals("trailer", ignoreCase = true) ||
            film.episodeCurrent.equals("trailer", ignoreCase = true)

        if (isSeries) {
            // Union of all servers keyed by parsed episode number.
            val byNumber = sortedMapOf<Int, MutableList<Track>>()
            for ((server, items) in groups) {
                items.forEachIndexed { index, item ->
                    val track = tracksOf(item, server)
                    if (track.m3u8 == null && track.embed == null) return@forEachIndexed
                    val number = episodeNumber(item.name ?: item.filename ?: item.slug, index)
                    byNumber.getOrPut(number) { mutableListOf() }.add(track)
                }
            }
            val season = film.tmdb?.season?.takeIf { it > 0 } ?: 1
            val episodes = byNumber.map { (number, tracks) ->
                newEpisode(Tracks(tracks.distinctBy { (it.m3u8 ?: "") to (it.embed ?: "") })) {
                    this.name = "Tập $number"
                    this.season = season
                    this.episode = number
                    this.posterUrl = poster
                }
            }
            return newTvSeriesLoadResponse(title, url, typeForFilm(film), episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = film.year
                this.tags = tags.distinct()
                this.actors = cast
                this.recommendations = recommendations
                this.comingSoon = comingSoon || episodes.isEmpty()
                addDuration(film.time)
                score?.let { addScore(it.toString()) }
                addTrailer(film.trailerUrl)
            }
        }

        val tracks = groups.flatMap { (server, items) -> items.map { tracksOf(it, server) } }
            .filter { it.m3u8 != null || it.embed != null }
            .distinctBy { (it.m3u8 ?: "") to (it.embed ?: "") }
        return newMovieLoadResponse(title, url, typeForFilm(film), Tracks(tracks)) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
            this.year = film.year
            this.tags = tags.distinct()
            this.actors = cast
            this.recommendations = recommendations
            this.comingSoon = comingSoon || tracks.isEmpty()
            addDuration(film.time)
            score?.let { addScore(it.toString()) }
            addTrailer(film.trailerUrl)
        }
    }

    private suspend fun fetchBackups(slug: String, tmdbId: String?): BackupServers? {
        val query = buildString {
            append("slug=").append(URLEncoder.encode(slug, "UTF-8"))
            tmdbId?.let { append("&tmdb_id=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        return runCatching {
            app.get("$mainUrl/api/backup-servers?$query", headers = baseHeaders).text
        }.getOrNull()?.let { runCatching { parseJson<BackupServers>(it) }.getOrNull() }
    }

    // ------------------------------------------------------------------ //
    // Links
    // ------------------------------------------------------------------ //

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = runCatching { parseJson<Tracks>(data) }.getOrNull() ?: return false
        if (payload.tracks.isEmpty()) return false

        var emitted = false
        for (track in payload.tracks.distinctBy { (it.m3u8 ?: "") to (it.embed ?: "") }) {
            val label = listOfNotNull(track.server, track.label)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { "Trạm Phim" }
            val quality = qualityFrom(listOfNotNull(track.quality, track.label).joinToString(" "))

            track.m3u8?.takeIf { it.startsWith("http") }?.let { url ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name • $label",
                        url = url,
                        type = linkType(url),
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                    }
                )
                emitted = true
            }

            val embed = track.embed?.takeIf { it.startsWith("http") } ?: continue
            when {
                // streamc.xyz serves AES-GCM-encrypted HLS only its obfuscated in-page JS
                // can decode — not worth attempting; direct m3u8 servers cover the content.
                embed.contains("streamc.xyz") -> {}
                embed.contains("streamvsmov.com") -> {
                    if (resolveVsmov(embed, label, subtitleCallback, callback)) emitted = true
                }
                else -> {
                    runCatching {
                        if (loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)) emitted = true
                    }
                }
            }
        }
        return emitted
    }

    /**
     * VSmov player pages carry:
     *   const baseUrl = "https://v5.streamvsmov.com";
     *   const videoHash = "<uuid>";
     *   playerOptions = { ..., subtitles: [...], enableSignedUrl: false, signedMasterUrl: "" }
     * The playable playlist is {baseUrl}/stream/{hash}/master.m3u8 (or signedMasterUrl).
     */
    private suspend fun resolveVsmov(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = runCatching {
            app.get(embedUrl, headers = baseHeaders + ("Referer" to "$mainUrl/")).text
        }.getOrNull() ?: return false

        val hash = Regex("""/video/([0-9a-fA-F-]{32,40})""").find(embedUrl)?.groupValues?.get(1)
            ?: Regex("""videoHash\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return false
        val base = Regex("""baseUrl\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.trimEnd('/')
            ?: originOf(embedUrl) ?: return false

        val signed = Regex("""enableSignedUrl\s*:\s*true""").containsMatchIn(html)
        val signedUrl = Regex("""signedMasterUrl\s*:\s*"([^"]*)"""").find(html)?.groupValues?.get(1)
        val master = if (signed && !signedUrl.isNullOrBlank()) signedUrl else "$base/stream/$hash/master.m3u8"

        // Attach VSmov's own WebVTT subtitles (vietsub / lồng tiếng / phụ đề Anh).
        Regex(""""subtitles"\s*:\s*(\[[^\]]*\])""").find(html)?.groupValues?.get(1)?.let { arr ->
            runCatching { parseJson<List<VsmovSubtitle>>(arr) }.getOrNull()?.forEach { sub ->
                val rel = sub.url?.takeIf { it.isNotBlank() } ?: return@forEach
                val absolute = if (rel.startsWith("http")) rel else "$base$rel"
                val lang = when (sub.code?.lowercase()) {
                    "vie", "vi" -> "Vietnamese"
                    "eng", "en" -> "English"
                    else -> sub.code ?: sub.name ?: "vi"
                }
                subtitleCallback(newSubtitleFile(lang, absolute))
            }
        }

        callback(
            newExtractorLink(
                source = name,
                name = "$name • $label",
                url = master,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = "$base/"
            }
        )
        return true
    }
}
