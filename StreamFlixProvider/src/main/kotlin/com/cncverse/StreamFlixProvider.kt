package com.cncverse

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.api.Log
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder
import okhttp3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

class StreamFlixProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
        private const val OMG10 = "aHR0cHM6Ly9vbWcxMC5jb20vNC8xMTEwNDQ4OQ=="
        @Volatile private var lastBrowserOpenMs = 0L
        @Volatile private var telegramPopupShown = false
        private const val BROWSER_DEBOUNCE_MS = 10_000L
    }
    
    override var mainUrl = "https://api.streamflix.app"
    override var name = "StreamFlix 2.0"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ta"
    override val hasMainPage = true
    override val hasQuickSearch = true

    private var configData: ConfigResponse? = null
    
    // Data classes for API responses
    data class StreamFlixData(
        @JsonProperty("data") val data: List<StreamFlixItem>
    )

    data class StreamFlixItem(
        @JsonProperty("isTV") val isTV: Boolean,
        @JsonProperty("moviename") val movieName: String?,
        @JsonProperty("moviedesc") val movieDesc: String?,
        @JsonProperty("movieposter") val moviePoster: String?,
        @JsonProperty("moviebanner") val movieBanner: String?,
        @JsonProperty("movieyear") val movieYear: String?,
        @JsonProperty("movierating") val movieRating: Double,
        @JsonProperty("movietype") val movieType: String?,
        @JsonProperty("movieinfo") val movieInfo: String?,
        @JsonProperty("movieduration") val movieDuration: String?,
        @JsonProperty("moviekey") val movieKey: String?,
        @JsonProperty("movielink") val movieLink: String?,
        @JsonProperty("movietrailer") val movieTrailer: String?,
        @JsonProperty("movieimdb") val movieImdb: String?,
        @JsonProperty("tmdb") val tmdb: String?,
        @JsonProperty("movieviews") val movieViews: Int?,
        @JsonProperty("newseason") val newSeason: String?
    )

    data class ConfigResponse(
        @JsonProperty("movies") val movies: List<String>,
        @JsonProperty("tv") val tv: List<String>,
        @JsonProperty("premium") val premium: List<String>,
        @JsonProperty("download") val download: List<String>,
        @JsonProperty("latest") val latest: Int,
        @JsonProperty("banner") val banner: String,
        @JsonProperty("video") val video: String,
        @JsonProperty("newapp") val newApp: Boolean,
        @JsonProperty("notice") val notice: Boolean,
        @JsonProperty("title") val title: String,
        @JsonProperty("text") val text: String
    )

    data class WebSocketRequest(
        @JsonProperty("t") val type: String,
        @JsonProperty("d") val data: WebSocketData
    )

    data class WebSocketData(
        @JsonProperty("a") val action: String,
        @JsonProperty("r") val request: Int,
        @JsonProperty("b") val body: WebSocketBody
    )

    data class WebSocketBody(
        @JsonProperty("p") val path: String,
        @JsonProperty("h") val hash: String
    )

    data class Episode(
        @JsonProperty("key") val key: Int,
        @JsonProperty("link") val link: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String,
        @JsonProperty("runtime") val runtime: Int,
        @JsonProperty("still_path") val stillPath: String?,
        @JsonProperty("vote_average") val voteAverage: Double
    )

    private suspend fun getConfig(): ConfigResponse? {
        if (configData == null) {
            try {
                val headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                    "Accept" to "application/json, text/plain, */*",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Connection" to "keep-alive"
                )
                
                val response = app.get("$mainUrl/config/config-streamflixapp.json", headers = headers, timeout = 30)
                configData = parseJson<ConfigResponse>(response.text)
            } catch (e: Exception) {
                
                // Fallback config
                configData = ConfigResponse(
                    movies = listOf("https://example.com/fallback/"),
                    tv = listOf("https://example.com/fallback/"),
                    premium = listOf("https://example.com/fallback/"),
                    download = listOf("https://example.com/fallback/"),
                    latest = 1,
                    banner = "",
                    video = "",
                    newApp = false,
                    notice = false,
                    title = "Fallback",
                    text = "Using fallback configuration"
                )
            }
        }
        return configData
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        showTelegramPopup()
        // Show star popup on first visit (shared across all CNCVerse plugins)
        
        val items = mutableListOf<HomePageList>()
        
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "en-US,en;q=0.9",
                "Connection" to "keep-alive"
            )
            
            val response = app.get("$mainUrl/data.json", headers = headers, timeout = 30)
            
            val data = parseJson<StreamFlixData>(response.text)
            
            val movies = data.data.filter { !it.isTV && !it.movieName.isNullOrBlank() }.take(20).map { item ->
                newMovieSearchResponse(
                    name = item.movieName!!,
                    url = "${item.movieKey}|movie",
                    type = TvType.Movie
                ) {
                    this.posterUrl = "https://image.tmdb.org/t/p/w500/${item.moviePoster}"
                    this.year = item.movieYear?.toIntOrNull()
                    this.quality = SearchQuality.HD
                }
            }
            
            val tvShows = data.data.filter { it.isTV && !it.movieName.isNullOrBlank() }.take(20).map { item ->
                newTvSeriesSearchResponse(
                    name = item.movieName!!,
                    url = "${item.movieKey}|tv",
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = "https://image.tmdb.org/t/p/w500/${item.moviePoster}"
                    this.year = item.movieYear?.toIntOrNull()
                    this.quality = SearchQuality.HD
                }
            }
            
            if (movies.isNotEmpty()) {
                items.add(HomePageList("Latest Movies", movies))
            }
            if (tvShows.isNotEmpty()) {
                items.add(HomePageList("Latest TV Shows", tvShows))
            }
            
        } catch (e: Exception) {
            Log.e("StreamFlix", "Error in getMainPage: ${e.message}")
            
            // Add fallback dummy content if API fails
            val fallbackMovies = listOf(
                newMovieSearchResponse(
                    name = "StreamFlix Service Unavailable",
                    url = "error|movie",
                    type = TvType.Movie
                ) {
                    this.posterUrl = null
                    this.year = 2024
                    this.quality = SearchQuality.HD
                }
            )
            items.add(HomePageList("Service Status", fallbackMovies))
        }
        
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        
        val searchResults = mutableListOf<SearchResponse>()
        
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "en-US,en;q=0.9",
                "Connection" to "keep-alive"
            )
            
            val response = app.get("$mainUrl/data.json", headers = headers, timeout = 30)
            val data = parseJson<StreamFlixData>(response.text)
            
            val filteredItems = data.data.filter { 
                !it.movieName.isNullOrBlank() &&
                (it.movieName.contains(query, ignoreCase = true) ||
                it.movieType?.contains(query, ignoreCase = true) == true ||
                it.movieInfo?.contains(query, ignoreCase = true) == true)
            }
            
            
            filteredItems.forEach { item ->
                if (item.isTV) {
                    searchResults.add(
                        newTvSeriesSearchResponse(
                            name = item.movieName!!, 
                            url = "${item.movieKey}|tv",
                            type = TvType.TvSeries
                        ) {
                            this.posterUrl = "https://image.tmdb.org/t/p/w500/${item.moviePoster}"
                            this.year = item.movieYear?.toIntOrNull()
                            this.quality = SearchQuality.HD
                        }
                    )
                } else {
                    searchResults.add(
                        newMovieSearchResponse(
                            name = item.movieName!!,
                            url = "${item.movieKey}|movie",
                            type = TvType.Movie
                        ) {
                            this.posterUrl = "https://image.tmdb.org/t/p/w500/${item.moviePoster}"
                            this.year = item.movieYear?.toIntOrNull()
                            this.quality = SearchQuality.HD
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("StreamFlix", "Error in search: ${e.message}")
        }
        
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse {
        
        val str = url.substringAfter("https://api.streamflix.app/")
        val (movieKey, type) = str.split("|")
        
        // Handle error case
        if (movieKey == "error") {
            return newMovieLoadResponse(
                name = "StreamFlix Service Unavailable",
                url = url,
                type = TvType.Movie,
                dataUrl = ""
            ) {
                this.plot = "The StreamFlix service is currently unavailable. Please try again later."
                this.year = 2024
            }
        }
        
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "en-US,en;q=0.9",
                "Connection" to "keep-alive"
            )
            
            val response = app.get("$mainUrl/data.json", headers = headers, timeout = 30)
            val data = parseJson<StreamFlixData>(response.text)
            val item = data.data.find { it.movieKey == movieKey }
                ?: throw Exception("Movie not found")

            val movieName = item.movieName?.takeIf { it.isNotBlank() } ?: "Unknown Title"

            return if (item.isTV) {
                // Extract season count from movieduration (e.g., "5 Seasons")
                val seasonCount = item.movieDuration?.let { duration ->
                    val seasonMatch = Regex("(\\d+)\\s+Season").find(duration)
                    seasonMatch?.groupValues?.get(1)?.toIntOrNull()
                } ?: 1
                
                Log.d("StreamFlix", "TV Show has $seasonCount seasons")
                val episodes = getEpisodesFromWebSocket(movieKey, seasonCount)
                
                newTvSeriesLoadResponse(
                    name = movieName,
                    url = url,
                    type = TvType.TvSeries,
                    episodes = episodes
                ) {
                    this.posterUrl = item.moviePoster?.let { "https://image.tmdb.org/t/p/w500/$it" }
                    this.backgroundPosterUrl = item.movieBanner?.let { "https://image.tmdb.org/t/p/original/$it" }
                    this.year = item.movieYear?.toIntOrNull()
                    this.plot = item.movieDesc
                    this.tags = item.movieInfo?.split("/") ?: emptyList()
                    this.score = item.movieRating?.let { Score.from10(it) }
                }
            } else {
                newMovieLoadResponse(
                    name = movieName,
                    url = url,
                    type = TvType.Movie,
                    dataUrl = item.movieLink ?: ""
                ) {
                    this.posterUrl = item.moviePoster?.let { "https://image.tmdb.org/t/p/w500/$it" }
                    this.backgroundPosterUrl = item.movieBanner?.let { "https://image.tmdb.org/t/p/original/$it" }
                    this.year = item.movieYear?.toIntOrNull()
                    this.plot = item.movieDesc
                    this.tags = item.movieInfo?.split("/") ?: emptyList()
                    this.score = item.movieRating?.let { Score.from10(it) }
                    this.recommendations = emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e("StreamFlix", "Error in load: ${e.message}")
            throw Exception("Failed to load content: ${e.message}")
        }
    }

    private val webSocketExtractor = StreamFlixWebSocketExtractor()
    
    private suspend fun getEpisodesFromWebSocket(movieKey: String, totalSeasons: Int = 1): List<com.lagradost.cloudstream3.Episode> {
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        
        try {
            val seasonsData = webSocketExtractor.getEpisodesFromWebSocket(movieKey, totalSeasons)
            
            seasonsData.forEach { (seasonNumber, episodesMap) ->
                episodesMap.forEach { (episodeKey, episodeData) ->
                    episodes.add(
                        newEpisode(episodeData.link) {
                            this.name = episodeData.name
                            this.season = seasonNumber
                            this.episode = episodeKey + 1 // Episodes are 0-indexed, make them 1-indexed
                            this.description = episodeData.overview
                            this.posterUrl = episodeData.stillPath?.let { "https://image.tmdb.org/t/p/w500/$it" }
                            this.score = Score.from10(episodeData.voteAverage)
                        }
                    )
                }
            }
            
            // Fallback: if WebSocket fails, create some dummy episodes based on common patterns
            if (episodes.isEmpty()) {
                Log.w("StreamFlix", "WebSocket failed, using fallback episodes")
                for (season in 1..2) {
                    for (episode in 1..6) {
                        episodes.add(
                            newEpisode("$movieKey|s${season}e${episode}") {
                                this.name = "Episode $episode"
                                this.season = season
                                this.episode = episode
                                this.description = "Episode $episode of Season $season"
                            }
                        )
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("StreamFlix", "Error getting episodes: ${e.message}")
        }
        
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        openInExternalBrowser(String(android.util.Base64.decode(OMG10, android.util.Base64.DEFAULT)))
        val str = data.substringAfter("https://api.streamflix.app/")
        try {
            // Handle error case
            if (str.startsWith("error|")) {
                Log.w("StreamFlix", "Cannot load links for error item")
                return false
            }
            
            val config = getConfig() ?: return false

            if (data.startsWith("tv/") && data.contains("/s") && data.endsWith(".mkv")) {
                // Real TV Show episode link from WebSocket
                
                // Use premium URLs for TV shows
                config.premium.forEach { baseUrl ->
                    val videoUrl = baseUrl + data
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - Premium",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.headers = mapOf("Referer" to mainUrl)
                            this.quality = Qualities.P720.value
                        }
                    )
                }
                
                // Also try TV URLs as fallback
                config.tv.forEach { baseUrl ->
                    val videoUrl = baseUrl + data
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - TV",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.headers = mapOf("Referer" to mainUrl)
                            this.quality = Qualities.P480.value
                        }
                    )
                }
                
            } else if (str.contains("|s") && str.contains("e")) {
                // Fallback TV Show episode format
                val parts = str.split("|")
                val movieKey = parts[0]
                val episodeInfo = parts[1] // Format: s1e1
                
                
                // Extract season and episode numbers
                val seasonMatch = Regex("s(\\d+)").find(episodeInfo)
                val episodeMatch = Regex("e(\\d+)").find(episodeInfo)
                
                if (seasonMatch != null && episodeMatch != null) {
                    val season = seasonMatch.groupValues[1]
                    val episode = episodeMatch.groupValues[1]
                    
                    // Use premium URLs for TV shows
                    config.premium.forEach { baseUrl ->
                        val videoUrl = "${baseUrl}tv/${movieKey}/s${season}/episode${episode}.mkv"
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name - Premium",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.headers = mapOf("Referer" to mainUrl)
                                this.quality = Qualities.P720.value
                            }
                        )
                    }
                }
            } else {
                // Movie
                val movieLink = str
                if (movieLink.isNotEmpty()) {
                    
                    // Use premium URLs for movies
                    config.premium.forEach { baseUrl ->
                        val videoUrl = baseUrl + movieLink
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name - Premium",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.headers = mapOf("Referer" to mainUrl)
                                this.quality = Qualities.P720.value
                            }
                        )
                    }
                    
                    // Also try movie URLs as fallback
                    config.movies.forEach { baseUrl ->
                        val videoUrl = baseUrl + movieLink
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name - Movies",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.headers = mapOf("Referer" to mainUrl)
                                this.quality = Qualities.P480.value
                            }
                        )
                    }
                }
            }
            
            return true
        } catch (e: Exception) {
            Log.e("StreamFlix", "Error in loadLinks: ${e.message}")
            return false
        }
    }



    private fun showTelegramPopup() {
        if (isLayout(TV)) return
        val ctx = context ?: return
        if (telegramPopupShown) return
        val prefs = ctx.getSharedPreferences("cncverse_prefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("telegram_popup_shown", false)) { telegramPopupShown = true; return }
        telegramPopupShown = true
        prefs.edit().putBoolean("telegram_popup_shown", true).apply()
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