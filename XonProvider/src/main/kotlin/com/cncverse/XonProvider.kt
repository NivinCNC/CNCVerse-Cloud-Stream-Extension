package com.cncverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import com.lagradost.cloudstream3.utils.loadExtractor
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

class XonProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
        private const val OMG10 = "aHR0cHM6Ly9vbWcxMC5jb20vNC8xMTEwNDQ4OQ=="
        @Volatile private var lastBrowserOpenMs = 0L
        @Volatile private var telegramPopupShown = false
        private const val BROWSER_DEBOUNCE_MS = 10_000L
    }
    
    override var mainUrl = "https://xon-avens.xyz/apis"
    override var name = "Xon"
    override val hasMainPage = true
    override var lang = "ta"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    private var apiKey = "553y845hfhdlfhjkl438943943839443943fdhdkfjfj9834lnfd98"
    private var callerName = "vion-official-app"
    private var configExpireTime = 0L
    private var configFetched = false
    
    private fun getHeaders(): Map<String, String> {
        val host = try {
            java.net.URI(mainUrl).host ?: "xon-avens.xyz"
        } catch (_: Exception) { "xon-avens.xyz" }
        return mapOf(
            "Accept-Encoding" to "gzip",
            "API" to apiKey,
            "CALLER" to callerName,
            "Connection" to "Keep-Alive",
            "Host" to host,
            "User-Agent" to "okhttp/5.3.2"
        )
    }

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // ─── Data classes matching current API responses ───────────────────────────

    /**
     * Represents an entry from nzgetshows.php.
     * This endpoint now returns MOVIES (not TV seasons).
     * The "show_name" / "language_name" fields carry embedded metadata.
     */
    data class Movie(
        @JsonProperty("id") val id: Int,
        @JsonProperty("no") val no: Int,
        @JsonProperty("name") val name: String,
        // New API uses "poster" instead of "thumb"
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("genre") val genre: String? = null,
        @JsonProperty("des") val des: String? = null,
        @JsonProperty("tags") val tags: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("trailer") val trailer: String? = null,
        @JsonProperty("ttype") val ttype: Int = 0,
        @JsonProperty("basic") val basic: String? = null,
        @JsonProperty("sd") val sd: String? = null,
        @JsonProperty("hd") val hd: String? = null,
        @JsonProperty("fhd") val fhd: String? = null,
        @JsonProperty("show_id") val showId: Int = 0,
        @JsonProperty("language") val language: Int = 0,
        @JsonProperty("show_name") val showName: String? = null,
        @JsonProperty("language_name") val languageName: String? = null,
        @JsonProperty("premium") val premium: Int = 0,
        @JsonProperty("wfeathers") val wfeathers: Int = 0,
        @JsonProperty("bfeathers") val bfeathers: Int = 0,
        @JsonProperty("sfeathers") val sfeathers: Int = 0,
        @JsonProperty("trending") val trending: Int = 0,
        @JsonProperty("special") val special: Int = 0,
        @JsonProperty("xPlayer2") val xPlayer2: String? = null,
        @JsonProperty("xPlayer3") val xPlayer3: String? = null,
        @JsonProperty("locked") val locked: Int = 0,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("avg_runtime") val avgRuntime: String? = null,
        @JsonProperty("age_rating") val ageRating: String? = null,
        @JsonProperty("top10") val top10: Int = 0,
        @JsonProperty("play_code") val playCode: String? = null,
        @JsonProperty("created_at") val createdAt: String? = null,
        @JsonProperty("updated_at") val updatedAt: String? = null
    )

    data class MoviesResponse(
        @JsonProperty("status") val status: Boolean = false,
        @JsonProperty("last_updated") val lastUpdated: String = "",
        @JsonProperty("movies") val movies: List<Movie> = emptyList()
    )

    /**
     * Represents an entry from nzgetepisodes_v2.php.
     * Fields like aplayer1/aplayer2 replace old eplay/link.
     * showName, languageName, season_name are now embedded.
     */
    data class Episode(
        @JsonProperty("id") val id: Int,
        @JsonProperty("no") val no: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("thumb") val thumb: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("des") val des: String? = null,
        @JsonProperty("tags") val tags: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("basic") val basic: String? = null,
        @JsonProperty("sd") val sd: String? = null,
        @JsonProperty("hd") val hd: String? = null,
        @JsonProperty("fhd") val fhd: String? = null,
        @JsonProperty("season_id") val seasonId: Int = 0,
        @JsonProperty("show_id") val showId: Int = 0,
        @JsonProperty("language") val language: Int = 0,
        @JsonProperty("premium") val premium: Int = 0,
        @JsonProperty("wfeathers") val wfeathers: Int = 0,
        @JsonProperty("bfeathers") val bfeathers: Int = 0,
        @JsonProperty("sfeathers") val sfeathers: Int = 0,
        @JsonProperty("trending") val trending: Int = 0,
        // New fields replacing old "eplay" / "link"
        @JsonProperty("aplayer1") val aplayer1: String? = null,
        @JsonProperty("aplayer2") val aplayer2: String? = null,
        @JsonProperty("locked") val locked: Int = 0,
        @JsonProperty("play_code") val playCode: String? = null,
        // Embedded metadata (no need for separate shows/seasons lookup)
        @JsonProperty("showName") val showName: String? = null,
        @JsonProperty("languageName") val languageName: String? = null,
        @JsonProperty("season_name") val seasonName: String? = null,
        @JsonProperty("updated_at") val updatedAt: String? = null
    )

    data class EpisodesResponse(
        @JsonProperty("episodes") val episodes: List<Episode> = emptyList()
    )

    // ─── Cache ─────────────────────────────────────────────────────────────────

    private var cachedMovies: List<Movie> = emptyList()
    private var cachedEpisodes: List<Episode> = emptyList()
    private var lastCacheTime = 0L
    private val cacheRefreshInterval = 24 * 60 * 60 * 1000L // 24 hours

    // ─── Remote config ─────────────────────────────────────────────────────────

    private suspend fun fetchRemoteConfig() {
        try {
            val (baseUrl, fetchedApiKey, fetchedCallerName) = XonFirebaseRemoteConfigFetcher.getAllConfig()
            baseUrl?.let { mainUrl = it }
            fetchedApiKey?.let { apiKey = it }
            fetchedCallerName?.let { callerName = it }
            configFetched = true
            configExpireTime = System.currentTimeMillis() + 3600 * 1000
        } catch (e: Exception) {
            println("Xon Provider: Failed to fetch remote config - ${e.message}")
        }
    }

    // ─── Cache refresh ─────────────────────────────────────────────────────────

    suspend fun refreshCache() {
        val currentTime = System.currentTimeMillis()

        if (!configFetched || currentTime >= configExpireTime) {
            fetchRemoteConfig()
        }

        if (currentTime - lastCacheTime < cacheRefreshInterval &&
            cachedMovies.isNotEmpty() &&
            cachedEpisodes.isNotEmpty()
        ) {
            return // Cache is still fresh
        }

        try {
            val headers = getHeaders()

            // nzgetshows.php now returns the movies list
            val moviesRaw = app.get("$mainUrl/nzgetshows.php", headers = headers).body.string()
            val moviesResponse = mapper.readValue<MoviesResponse>(moviesRaw)
            cachedMovies = moviesResponse.movies

            // nzgetepisodes_v2.php returns episodes
            val episodesRaw = app.get("$mainUrl/nzgetepisodes_v2.php", headers = headers).body.string()
            val episodesResponse = mapper.readValue<EpisodesResponse>(episodesRaw)
            cachedEpisodes = episodesResponse.episodes

            lastCacheTime = currentTime
        } catch (e: Exception) {
            println("Xon Provider: Failed to refresh cache - ${e.message}")
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun formatUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) url
        else "https://archive.org/download/$url"
    }

    /** Best poster image for a movie (poster → cover → fallback) */
    private fun Movie.bestPoster(): String =
        when {
            !poster.isNullOrEmpty() -> formatUrl(poster)
            !cover.isNullOrEmpty()  -> formatUrl(cover)
            else                    -> ""
        }

    /** Display name including language */
    private fun Movie.displayName(): String =
        if (!languageName.isNullOrEmpty()) "${name.orEmpty()} ($languageName)" else name.orEmpty()

    private fun Episode.displayName(): String =
        if (!showName.isNullOrEmpty() && !languageName.isNullOrEmpty())
            "$showName – ${name.orEmpty()} ($languageName)"
        else name.orEmpty()

    // ─── Main page ─────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "trending"        to "Trending",
        "latest_episodes" to "Latest Episodes",
        "movies"          to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        showTelegramPopup()

        refreshCache()
        val list = mutableListOf<HomePageList>()

        when (request.data) {
            "trending" -> {
                // Trending movies
                val trendingMovies = cachedMovies
                    .filter { it.trending == 1 }
                    .take(20)
                    .map { movie ->
                        newMovieSearchResponse(movie.displayName(), "movie:${movie.id}", TvType.Movie) {
                            this.posterUrl = movie.bestPoster()
                        }
                    }
                if (trendingMovies.isNotEmpty())
                    list.add(HomePageList("Trending Movies", trendingMovies, isHorizontalImages = true))

                // Trending episodes (grouped by show)
                val trendingEpisodes = cachedEpisodes
                    .filter { it.trending == 1 }
                    .take(20)
                    .map { ep ->
                        newTvSeriesSearchResponse(ep.displayName(), "episode:${ep.id}", TvType.TvSeries) {
                            this.posterUrl = if (!ep.thumb.isNullOrEmpty()) formatUrl(ep.thumb) else ""
                        }
                    }
                if (trendingEpisodes.isNotEmpty())
                    list.add(HomePageList("Trending Episodes", trendingEpisodes, isHorizontalImages = true))
            }

            "latest_episodes" -> {
                val latestEpisodes = cachedEpisodes.take(20).map { ep ->
                    newTvSeriesSearchResponse(ep.displayName(), "episode:${ep.id}", TvType.TvSeries) {
                        this.posterUrl = if (!ep.thumb.isNullOrEmpty()) formatUrl(ep.thumb) else ""
                    }
                }
                list.add(HomePageList("Latest Episodes", latestEpisodes, isHorizontalImages = true))
            }

            "movies" -> {
                // Group movies by language for a richer main page
                val byLanguage = cachedMovies.groupBy { it.languageName.orEmpty() }
                byLanguage.forEach { (lang, movies) ->
                    val items = movies.take(20).map { movie ->
                        newMovieSearchResponse(movie.name, "movie:${movie.id}", TvType.Movie) {
                            this.posterUrl = movie.bestPoster()
                        }
                    }
                    val label = if (lang.isNotEmpty()) "$lang Movies" else "Movies"
                    list.add(HomePageList(label, items, isHorizontalImages = true))
                }
            }
        }

        return newHomePageResponse(list)
    }

    // ─── Search ────────────────────────────────────────────────────────────────

    /** Known language names/aliases → canonical name for display matching */
    private val knownLanguages = listOf(
        "tamil", "hindi", "telugu", "malayalam", "kannada",
        "english", "bengali", "marathi", "punjabi", "gujarati",
        "odia", "urdu", "assamese", "japanese", "korean", "chinese"
    )

    /**
     * Extracts a language keyword from the query (if any) and returns
     * a Pair(detectedLanguage, remainingQuery).
     * e.g. "vikram tamil" → Pair("tamil", "vikram")
     *      "tamil movies" → Pair("tamil", "movies")
     *      "vikram"       → Pair(null, "vikram")
     */
    private fun extractLanguageFromQuery(query: String): Pair<String?, String> {
        val tokens = query.trim().split(Regex("\\s+"))
        val langToken = tokens.firstOrNull { token ->
            knownLanguages.any { it.equals(token, ignoreCase = true) }
        } ?: return Pair(null, query.trim())

        val remaining = tokens.filterNot { it.equals(langToken, ignoreCase = true) }
            .joinToString(" ").trim()
        return Pair(langToken.lowercase(), remaining)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        refreshCache()
        val results = mutableListOf<SearchResponse>()

        val (detectedLang, textQuery) = extractLanguageFromQuery(query)

        // Filter movies
        cachedMovies.filter { movie ->
            // Language filter: if a language was detected, only include matching items
            val langMatch = detectedLang == null ||
                movie.languageName?.contains(detectedLang, ignoreCase = true) == true

            // Text filter: if remaining query is non-empty, match against fields
            val textMatch = textQuery.isEmpty() ||
                movie.name.contains(textQuery, ignoreCase = true) ||
                (movie.des?.contains(textQuery, ignoreCase = true) == true) ||
                (movie.tags?.contains(textQuery, ignoreCase = true) == true) ||
                (movie.showName?.contains(textQuery, ignoreCase = true) == true)

            langMatch && textMatch
        }.forEach { movie ->
            results.add(
                newMovieSearchResponse(movie.displayName(), "movie:${movie.id}", TvType.Movie) {
                    this.posterUrl = movie.bestPoster()
                }
            )
        }

        // Filter episodes
        cachedEpisodes.filter { ep ->
            val langMatch = detectedLang == null ||
                ep.languageName?.contains(detectedLang, ignoreCase = true) == true

            val textMatch = textQuery.isEmpty() ||
                ep.name.contains(textQuery, ignoreCase = true) ||
                (ep.tags?.contains(textQuery, ignoreCase = true) == true) ||
                (ep.showName?.contains(textQuery, ignoreCase = true) == true)

            langMatch && textMatch
        }.forEach { ep ->
            results.add(
                newTvSeriesSearchResponse(ep.displayName(), "episode:${ep.id}", TvType.TvSeries) {
                    this.posterUrl = if (!ep.thumb.isNullOrEmpty()) formatUrl(ep.thumb) else ""
                }
            )
        }

        return results
    }

    // ─── Load ──────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        refreshCache()
        val str = url.substringAfterLast("/")
        val parts = str.split(":")
        if (parts.size != 2) return null

        val type = parts[0]
        val id   = parts[1].toIntOrNull() ?: return null

        return when (type) {
            "movie" -> {
                val movie = cachedMovies.find { it.id == id } ?: return null

                newMovieLoadResponse(
                    name    = movie.displayName(),
                    url     = url,
                    type    = TvType.Movie,
                    dataUrl = "movie:${movie.id}"
                ) {
                    this.posterUrl = movie.bestPoster()
                    this.plot = buildString {
                        movie.des?.let { append(it); append("\n\n") }
                        if (!movie.rating.isNullOrEmpty())       append("⭐ ${movie.rating}\n")
                        if (!movie.avgRuntime.isNullOrEmpty())   append("⏱ ${movie.avgRuntime}\n")
                        if (!movie.ageRating.isNullOrEmpty())    append("👶 ${movie.ageRating}\n")
                        if (!movie.languageName.isNullOrEmpty()) append("🌐 ${movie.languageName}")
                    }
                    this.year = movie.createdAt?.take(4)?.toIntOrNull()
                }
            }

            "episode" -> {
                val ep = cachedEpisodes.find { it.id == id } ?: return null

                // Group all episodes belonging to the same show+language into a proper TvSeries
                val showEpisodes = cachedEpisodes.filter {
                    it.showId == ep.showId && it.language == ep.language
                }.sortedWith(compareBy({ it.seasonId }, { it.no }))

                // Build season number mapping (season_id → sequential season number)
                val seasonIds = showEpisodes.map { it.seasonId }.distinct().sorted()
                val seasonNoMap = seasonIds.withIndex().associate { (idx, sid) -> sid to (idx + 1) }

                val episodeList = showEpisodes.map { e ->
                    newEpisode("episode:${e.id}") {
                        this.name      = e.name
                        this.season    = seasonNoMap[e.seasonId]
                        this.episode   = e.no
                        this.posterUrl = if (!e.thumb.isNullOrEmpty()) formatUrl(e.thumb) else ""
                        this.description = e.des?.ifEmpty { null }
                    }
                }

                val showTitle = ep.showName?.ifEmpty { "Show" } ?: "Show"
                val langLabel = ep.languageName.orEmpty()
                val displayTitle = if (langLabel.isNotEmpty()) "$showTitle ($langLabel)" else showTitle

                newTvSeriesLoadResponse(
                    name     = displayTitle,
                    url      = url,
                    type     = TvType.TvSeries,
                    episodes = episodeList
                ) {
                    // Use movie poster if available (look up by show_id)
                    val matchedMovie = cachedMovies.find {
                        it.showId == ep.showId && it.language == ep.language
                    }
                    this.posterUrl = matchedMovie?.bestPoster()
                        ?: (if (!ep.thumb.isNullOrEmpty()) formatUrl(ep.thumb) else "")
                    this.plot = if (langLabel.isNotEmpty()) "Language: $langLabel" else null
                }
            }

            else -> null
        }
    }

    // ─── Load links ────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        openInExternalBrowser(String(android.util.Base64.decode(OMG10, android.util.Base64.DEFAULT)))
        refreshCache()
        val str = data.substringAfterLast("/")
        val parts = str.split(":")
        if (parts.size != 2) return false

        val type = parts[0]
        val id   = parts[1].toIntOrNull() ?: return false

        when (type) {
            "episode" -> {
                val ep = cachedEpisodes.find { it.id == id } ?: return false
                addVideoLinks(ep.basic.orEmpty(), ep.sd.orEmpty(), ep.hd.orEmpty(), ep.fhd.orEmpty(), callback)
                if (!ep.aplayer1.isNullOrEmpty()) loadExtractor(ep.aplayer1, subtitleCallback, callback)
                if (!ep.aplayer2.isNullOrEmpty()) loadExtractor(ep.aplayer2, subtitleCallback, callback)
            }

            "movie" -> {
                val movie = cachedMovies.find { it.id == id } ?: return false
                addVideoLinks(movie.basic.orEmpty(), movie.sd.orEmpty(), movie.hd.orEmpty(), movie.fhd.orEmpty(), callback)
                if (!movie.xPlayer2.isNullOrEmpty()) loadExtractor(movie.xPlayer2, subtitleCallback, callback)
                if (!movie.xPlayer3.isNullOrEmpty()) loadExtractor(movie.xPlayer3, subtitleCallback, callback)
            }

            else -> return false
        }

        return true
    }

    // ─── Helper: add direct video quality links ────────────────────────────────

    private suspend fun addVideoLinks(
        basic: String,
        sd: String,
        hd: String,
        fhd: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (basic.isNotEmpty()) callback(makeLink("Basic", formatUrl(basic), Qualities.P240.value))
        if (sd.isNotEmpty())    callback(makeLink("SD",    formatUrl(sd),    Qualities.P480.value))
        if (hd.isNotEmpty())    callback(makeLink("HD",    formatUrl(hd),    Qualities.P720.value))
        if (fhd.isNotEmpty())   callback(makeLink("FHD",   formatUrl(fhd),   Qualities.P1080.value))
    }

    private suspend fun makeLink(label: String, url: String, quality: Int): ExtractorLink =
        newExtractorLink(name, "$name - $label", url = url, ExtractorLinkType.VIDEO) {
            this.referer = mainUrl
            this.quality = quality
        }



    private fun showTelegramPopup() {
        if (isLayout(TV)) return
        val ctx = context ?: return
        if (telegramPopupShown) return
        telegramPopupShown = true
        Handler(Looper.getMainLooper()).post {
            try {
                val dp = ctx.resources.displayMetrics.density

                // Rounded dark cCNCVerse is being hated by the CloudStream community for its ads.\n\nJoin our Telegram group to discuss and Share opinion!\n\nhttps://t.me/cncverse
                val bgDraw = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#1A1A2E"))
                    cornerRadius = 16f * dp
                }

                val root = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
                    background = bgDraw
                }

                // Title
                val titleTv = android.widget.TextView(ctx).apply {
                    text = "\uD83D\uDCAC Join CNCVerse Community"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 17f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
                        .also { it.bottomMargin = (10 * dp).toInt() }
                }

                // Thin divider
                val dividerV = android.view.View(ctx).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#2D2D4A"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, 1)
                        .also { it.bottomMargin = (14 * dp).toInt() }
                }

                // Message
                val msgTv = android.widget.TextView(ctx).apply {
                    text = "CNCVerse is being hated by the CloudStream community for its ads.\n\nJoin our Telegram group to discuss and share your opinion!"
                    setTextColor(android.graphics.Color.parseColor("#A0A0A8"))
                    textSize = 14f
                    setLineSpacing(0f, 1.4f)
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
                        .also { it.bottomMargin = (18 * dp).toInt() }
                }

                // Button row
                val btnRow = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END
                }
                val laterTv = android.widget.TextView(ctx).apply {
                    text = "Later"
                    setTextColor(android.graphics.Color.parseColor("#808090"))
                    textSize = 14f
                    val p = (10 * dp).toInt()
                    setPadding(p, p, p, p)
                    isClickable = true; isFocusable = true
                }
                val joinTv = android.widget.TextView(ctx).apply {
                    text = "Join Telegram"
                    setTextColor(android.graphics.Color.parseColor("#5B9BF5"))
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val p = (10 * dp).toInt()
                    setPadding(p, p, 0, p)
                    isClickable = true; isFocusable = true
                }
                btnRow.addView(laterTv)
                btnRow.addView(joinTv)
                root.addView(titleTv)
                root.addView(dividerV)
                root.addView(msgTv)
                root.addView(btnRow)

                val dialog = android.app.AlertDialog.Builder(ctx)
                    .setView(root)
                    .setCancelable(true)
                    .create()

                // Transparent window so rounded card corners show
                dialog.window?.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                )

                laterTv.setOnClickListener { dialog.dismiss() }
                joinTv.setOnClickListener {
                    dialog.dismiss()
                    try {
                        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/cncverse"))
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                    } catch (_: Exception) {}
                }
                dialog.show()
            } catch (_: Exception) {}
        }
    }
    private fun openInExternalBrowser(url: String) {
        if (isLayout(TV)) return
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        if (now - lastBrowserOpenMs < BROWSER_DEBOUNCE_MS) return
        lastBrowserOpenMs = now
        Handler(Looper.getMainLooper()).post {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e: Exception) { }
        }
    }
}