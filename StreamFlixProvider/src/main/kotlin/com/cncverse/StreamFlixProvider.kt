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
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

class StreamFlixProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    
    override var mainUrl = "https://api.streamflix.app"
    override var name = "StreamFlix 2.0"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ta"
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val gson = Gson()
    private var configData: ConfigResponse? = null
    
    // Data classes for API responses
    data class StreamFlixData(
        @SerializedName("data") val data: List<StreamFlixItem>
    )

    data class StreamFlixItem(
        @SerializedName("isTV") val isTV: Boolean,
        @SerializedName("moviename") val movieName: String?,
        @SerializedName("moviedesc") val movieDesc: String?,
        @SerializedName("movieposter") val moviePoster: String?,
        @SerializedName("moviebanner") val movieBanner: String?,
        @SerializedName("movieyear") val movieYear: String?,
        @SerializedName("movierating") val movieRating: Double,
        @SerializedName("movietype") val movieType: String?,
        @SerializedName("movieinfo") val movieInfo: String?,
        @SerializedName("movieduration") val movieDuration: String?,
        @SerializedName("moviekey") val movieKey: String?,
        @SerializedName("movielink") val movieLink: String?,
        @SerializedName("movietrailer") val movieTrailer: String?,
        @SerializedName("movieimdb") val movieImdb: String?,
        @SerializedName("tmdb") val tmdb: String?,
        @SerializedName("movieviews") val movieViews: Int?,
        @SerializedName("newseason") val newSeason: String?
    )

    data class ConfigResponse(
        @SerializedName("movies") val movies: List<String>,
        @SerializedName("tv") val tv: List<String>,
        @SerializedName("premium") val premium: List<String>,
        @SerializedName("download") val download: List<String>,
        @SerializedName("latest") val latest: Int,
        @SerializedName("banner") val banner: String,
        @SerializedName("video") val video: String,
        @SerializedName("newapp") val newApp: Boolean,
        @SerializedName("notice") val notice: Boolean,
        @SerializedName("title") val title: String,
        @SerializedName("text") val text: String
    )

    data class WebSocketRequest(
        @SerializedName("t") val type: String,
        @SerializedName("d") val data: WebSocketData
    )

    data class WebSocketData(
        @SerializedName("a") val action: String,
        @SerializedName("r") val request: Int,
        @SerializedName("b") val body: WebSocketBody
    )

    data class WebSocketBody(
        @SerializedName("p") val path: String,
        @SerializedName("h") val hash: String
    )

    data class Episode(
        @SerializedName("key") val key: Int,
        @SerializedName("link") val link: String,
        @SerializedName("name") val name: String,
        @SerializedName("overview") val overview: String,
        @SerializedName("runtime") val runtime: Int,
        @SerializedName("still_path") val stillPath: String?,
        @SerializedName("vote_average") val voteAverage: Double
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
                configData = gson.fromJson(response.text, ConfigResponse::class.java)
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
        // Show star popup on first visit (shared across all CNCVerse plugins)
        context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        
        val items = mutableListOf<HomePageList>()
        
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "en-US,en;q=0.9",
                "Connection" to "keep-alive"
            )
            
            val response = app.get("$mainUrl/data.json", headers = headers, timeout = 30)
            
            val data = gson.fromJson(response.text, StreamFlixData::class.java)
            
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
            val data = gson.fromJson(response.text, StreamFlixData::class.java)
            
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
            val data = gson.fromJson(response.text, StreamFlixData::class.java)
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
}
