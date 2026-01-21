package com.cncverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.util.*
import java.util.Base64
import java.nio.charset.StandardCharsets
import org.jsoup.Jsoup
import okio.GzipSource
import org.json.JSONArray
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PikashowProvider : MainAPI() {
    override var mainUrl = "https://manoda.co"
    override var name = "Pikashow"
    override val hasMainPage = true
    override var lang = "ta"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    companion object {
        var context: Context? = null
    }

    private val apiKey = BuildConfig.PIKASHOW_API_KEY
    private val hmacSecret = BuildConfig.PIKASHOW_HMAC_SECRET
    private val mapper = jacksonObjectMapper()
    
    // Generate realistic device identifiers
    private val deviceUuid = UUID.randomUUID().toString()
    private val gaid = UUID.randomUUID().toString()

    // For series response
    data class PikashowSeries(
        @JsonProperty("t") val title: String? = null,
        @JsonProperty("g") val genre: String? = null,
        @JsonProperty("y") val year: Int? = null,
        @JsonProperty("c") val cover: String? = null,
        @JsonProperty("i") val imdbRating: String? = null,
        @JsonProperty("n") val seasons: Int? = null,
        @JsonProperty("detail") val details: List<SeasonDetail>? = null
    )

    data class SeasonDetail(
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("season") val season: String? = null,
        @JsonProperty("episodes_count") val episodesCount: Int? = null
    )

    data class PikashowSeriesResponse(
        @JsonProperty("series") val series: List<PikashowSeries>? = null
    )

    // For movies response (bollywood/hollywood)
    data class PikashowMovie(
        @JsonProperty("so") val sortOrder: Int? = null,
        @JsonProperty("t") val title: String? = null,
        @JsonProperty("g") val genre: String? = null,
        @JsonProperty("y") val year: Int? = null,
        @JsonProperty("q") val quality: String? = null,
        @JsonProperty("c") val cover: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("f") val format: Int? = null,
        @JsonProperty("clientUrls") val clientUrls: List<ClientUrl>? = null
    )

    data class ClientUrl(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class PikashowMovieResponse(
        @JsonProperty("records") val records: List<PikashowMovie>? = null
    )

    // For video API response
    data class VideoApiResponse(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("data") val data: VideoData? = null
    )

    data class VideoData(
        @JsonProperty("t") val title: String? = null,
        @JsonProperty("g") val genre: String? = null,
        @JsonProperty("y") val year: Int? = null,
        @JsonProperty("c") val cover: String? = null,
        @JsonProperty("i") val imdbRating: String? = null,
        @JsonProperty("n") val seasons: Int? = null,
        @JsonProperty("detail") val details: List<VideoSeasonDetail>? = null,
        @JsonProperty("so") val sortOrder: Int? = null,
        @JsonProperty("q") val quality: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("f") val format: Int? = null,
        @JsonProperty("clientUrls") val clientUrls: List<ClientUrl>? = null,
        @JsonProperty("videoUrl") val videoUrl: String? = null,
        @JsonProperty("playUrl") val playUrl: String? = null,
        @JsonProperty("resolutions") val resolutions: List<Resolution>? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null,
        @JsonProperty("languages") val languages: List<Language>? = null,
        @JsonProperty("languageOptions") val languageOptions: List<Language>? = null,
        @JsonProperty("heastr") val heastr: String? = null,
        @JsonProperty("uastr") val uastr: String? = null,
        @JsonProperty("uaStr") val uaStr: String? = null,
        @JsonProperty("headerStr") val headerStr: String? = null,
        @JsonProperty("sourceType") val sourceType: String? = null,
        @JsonProperty("host") val host: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("supportedLanguages") val supportedLanguages: List<String>? = null,
        @JsonProperty("season") val season: String? = null,
        @JsonProperty("episode") val episode: String? = null
    )

    data class VideoSeasonDetail(
        @JsonProperty("season") val season: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("episodes") val episodes: List<VideoEpisode>? = null
    )

    data class VideoEpisode(
        @JsonProperty("e") val episode: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class Resolution(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("width") val width: Int? = null,
        @JsonProperty("height") val height: Int? = null
    )

    data class Language(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("playUrl") val playUrl: String? = null,
        @JsonProperty("resolutions") val resolutions: List<Resolution>? = null
    )

    // Data classes for HDBV player parsing
    data class Keys(
        @JsonProperty("file") val file: String,
        @JsonProperty("key") val key: String
    )

    data class Season(
        @JsonProperty("id") val id: String,
        @JsonProperty("folder") val folder: List<HDBVEpisode>
    )

    data class HDBVEpisode(
        @JsonProperty("episode") val episode: String,
        @JsonProperty("folder") val folder: List<FileData>
    )

    data class FileData(
        @JsonProperty("file") val file: String
    )

    private fun generateSignature(timestampMs: Long? = null): Map<String, String> {
        val timestamp = timestampMs ?: System.currentTimeMillis()
        val timestampSeconds = timestamp / 1000
        val timestampStr = timestampSeconds.toString()

        // Construct message: API_KEY + ":" + TIMESTAMP
        val message = "$apiKey:$timestampStr"

        // Generate HMAC-SHA256 signature
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(hmacSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val signature = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        val signatureHex = signature.joinToString("") { "%02x".format(it) }

        return mapOf(
            "X-Timestamp" to timestampStr,
            "X-API-Key" to apiKey,
            "X-Signature" to signatureHex
        )
    }

    private fun getPikashowHeaders(): Map<String, String> {
        val sigHeaders = generateSignature()
        return mapOf(
            "Host" to "manoda.co",
            "user-agent" to "Pikashow/2509030 (Android 13; Pixel 5; Channel/pikashow; gaid/$gaid); Uuid/$deviceUuid",
            "X-API-Key" to sigHeaders["X-API-Key"]!!,
            "X-Signature" to sigHeaders["X-Signature"]!!,
            "X-Timestamp" to sigHeaders["X-Timestamp"]!!
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        context?.let { ctx ->
            withContext(Dispatchers.Main) {
                StarPopupHelper.showStarPopupIfNeeded(ctx)
            }
        }
        val headers = getPikashowHeaders()
        val homePageList = mutableListOf<HomePageList>()

        try {
            // Fetch different categories
            val categories = listOf(
                "series" to "TV Series",
                "hollywood" to "Hollywood Movies",
                "bollywood" to "Bollywood Movies"
            )

            categories.forEach { (type, displayName) ->
                try {
                    val url = "$mainUrl/v1/api/videos"
                    val params = mapOf(
                        "type" to type,
                        "channel" to "pikashow"
                    )

                    val response = app.get(
                        url = url,
                        params = params,
                        headers = headers,
                        timeout = 30
                    )

                    if (response.code == 200) {
                        val searchResults = when (type) {
                            "series" -> {
                                try {
                                    val seriesResponse = mapper.readValue<PikashowSeriesResponse>(response.text)
                                    seriesResponse.series?.mapNotNull { series ->
                                        series.title?.let { title ->
                                            newTvSeriesSearchResponse(
                                                name = title,
                                                url = "pikashow:${title}:$type",
                                                type = TvType.TvSeries
                                            ) {
                                                this.posterUrl = series.cover
                                                this.year = series.year
                                                this.quality = SearchQuality.HD // Default for series
                                            }
                                        }
                                    }?.asReversed() ?: emptyList() // show last response first
                                } catch (e: Exception) {
                                    println("Error parsing series response: ${e.message}")
                                    emptyList()
                                }
                            }
                            "hollywood", "bollywood" -> {
                                try {
                                    val movieResponse = mapper.readValue<PikashowMovieResponse>(response.text)
                                    movieResponse.records?.mapNotNull { movie ->
                                        movie.title?.let { title ->
                                            newMovieSearchResponse(
                                                name = title,
                                                url = "pikashow:${movie.sortOrder}:$type",
                                                type = TvType.Movie
                                            ) {
                                                this.posterUrl = movie.cover
                                                this.year = movie.year
                                                this.quality = getQualityFromString(movie.quality)
                                            }
                                        }
                                    }?.asReversed() ?: emptyList() // show last response first
                                } catch (e: Exception) {
                                    println("Error parsing movie response: ${e.message}")
                                    emptyList()
                                }
                            }
                            else -> emptyList()
                        }

                        if (searchResults.isNotEmpty()) {
                            homePageList.add(HomePageList(displayName, searchResults))
                        }
                    }
                } catch (e: Exception) {
                    // Continue with other categories if one fails
                    println("Failed to fetch $displayName: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("Error in getMainPage: ${e.message}")
            // Add fallback content
            val fallbackList = listOf(
                newMovieSearchResponse(
                    name = "Pikashow Service",
                    url = "error",
                    type = TvType.Movie
                ) {
                    this.posterUrl = null
                }
            )
            homePageList.add(HomePageList("Status", fallbackList))
        }

        return newHomePageResponse(homePageList)
    }

    private fun getQualityFromString(qualityString: String?): SearchQuality? {
        return when (qualityString?.uppercase()) {
            "HD", "720P" -> SearchQuality.HD
            "FHD", "1080P" -> SearchQuality.HD
            "4K", "2160P" -> SearchQuality.HD
            "CAM", "CAMRIP" -> SearchQuality.Cam
            "HDCAM" -> SearchQuality.HdCam
            "TELECINE", "TC" -> SearchQuality.Telecine
            "TELESYNC", "TS" -> SearchQuality.Telesync
            "WORKPRINT", "WP" -> SearchQuality.WorkPrint
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val searchResults = mutableListOf<SearchResponse>()
        val headers = getPikashowHeaders()
        val searchQuery = query.lowercase().trim()
        
        try {
            // Search in all three categories
            val categories = listOf(
                "series" to TvType.TvSeries,
                "hollywood" to TvType.Movie,
                "bollywood" to TvType.Movie
            )
            
            categories.forEach { (type, tvType) ->
                try {
                    val url = "$mainUrl/v1/api/videos"
                    val params = mapOf(
                        "type" to type,
                        "channel" to "pikashow"
                    )
                    
                    val response = app.get(
                        url = url,
                        params = params,
                        headers = headers,
                        timeout = 30
                    )
                    
                    if (response.code == 200) {
                        when (type) {
                            "series" -> {
                                try {
                                    val seriesResponse = mapper.readValue<PikashowSeriesResponse>(response.text)
                                    seriesResponse.series?.forEach { series ->
                                        series.title?.let { title ->
                                            // Filter by search query
                                            if (title.lowercase().contains(searchQuery) ||
                                                series.genre?.lowercase()?.contains(searchQuery) == true) {
                                                
                                                searchResults.add(
                                                    newTvSeriesSearchResponse(
                                                        name = title,
                                                        url = "pikashow:${title}:$type",
                                                        type = tvType
                                                    ) {
                                                        this.posterUrl = series.cover
                                                        this.year = series.year
                                                        this.quality = SearchQuality.HD
                                                    }
                                                )
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    println("Error parsing series search response: ${e.message}")
                                }
                            }
                            
                            "hollywood", "bollywood" -> {
                                try {
                                    val movieResponse = mapper.readValue<PikashowMovieResponse>(response.text)
                                    movieResponse.records?.forEach { movie ->
                                        movie.title?.let { title ->
                                            // Filter by search query
                                            if (title.lowercase().contains(searchQuery) ||
                                                movie.genre?.lowercase()?.contains(searchQuery) == true) {
                                                
                                                searchResults.add(
                                                    newMovieSearchResponse(
                                                        name = title,
                                                        url = "pikashow:${movie.sortOrder}:$type",
                                                        type = tvType
                                                    ) {
                                                        this.posterUrl = movie.cover
                                                        this.year = movie.year
                                                        this.quality = getQualityFromString(movie.quality)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    println("Error parsing movie search response: ${e.message}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("Error searching in $type: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("Error in search function: ${e.message}")
        }
        
        // Sort results to show best matches first
        return searchResults.sortedWith(compareBy<SearchResponse> { searchResponse ->
            val title = searchResponse.name.lowercase()
            when {
                // Exact match gets highest priority (0)
                title == searchQuery -> 0
                // Title starts with query gets second priority (1)
                title.startsWith(searchQuery) -> 1
                // Title contains query gets third priority (2)
                title.contains(searchQuery) -> 2
                // Other matches get lowest priority (3)
                else -> 3
            }
        }.thenBy { it.name }).take(50)
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            // Parse URL format: "pikashow:identifier:type"
            val withoutUrlScheme = url.removePrefix("$mainUrl/")
            val parts = withoutUrlScheme.split(":")
            if (parts.size != 3 || parts[0] != "pikashow") return null
            
            val identifier = parts[1]
            val type = parts[2]
            
            val headers = getPikashowHeaders()
            
            return when (type) {
                "series" -> {
                    // For series, we need to get episode details
                    val seriesUrl = "$mainUrl/v1/api/videos"
                    val params = mapOf(
                        "type" to "series",
                        "channel" to "pikashow"
                    )
                    
                    val response = app.get(
                        url = seriesUrl,
                        params = params,
                        headers = headers,
                        timeout = 30
                    )
                    
                    if (response.code == 200) {
                        val seriesResponse = mapper.readValue<PikashowSeriesResponse>(response.text)
                        val series = seriesResponse.series?.find { it.title == identifier }
                        
                        series?.let { seriesData ->
                            val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
                            
                            // Generate episodes based on season details
                            seriesData.details?.forEach { seasonDetail ->
                                val seasonNumber = seasonDetail.season?.toIntOrNull() ?: 1
                                val episodeCount = seasonDetail.episodesCount ?: 1
                                
                                for (episodeNum in 1..episodeCount) {
                                    episodes.add(
                                        newEpisode("pikashow_episode:${seriesData.title}:$seasonNumber:$episodeNum") {
                                            this.name = "Episode $episodeNum"
                                            this.season = seasonNumber
                                            this.episode = episodeNum
                                        }
                                    )
                                }
                            }
                            
                            newTvSeriesLoadResponse(
                                name = seriesData.title ?: "Unknown Series",
                                url = url,
                                type = TvType.TvSeries,
                                episodes = episodes
                            ) {
                                this.posterUrl = seriesData.cover
                                this.year = seriesData.year
                                this.plot = seriesData.genre
                                this.recommendations = emptyList()
                                this.tags = seriesData.genre?.split(",")?.map { it.trim() }
                            }
                        }
                    } else null
                }
                
                "hollywood", "bollywood" -> {
                    // For movies, get movie details
                    val movieUrl = "$mainUrl/v1/api/videos"
                    val params = mapOf(
                        "type" to type,
                        "channel" to "pikashow"
                    )
                    
                    val response = app.get(
                        url = movieUrl,
                        params = params,
                        headers = headers,
                        timeout = 30
                    )
                    
                    if (response.code == 200) {
                        val movieResponse = mapper.readValue<PikashowMovieResponse>(response.text)
                        val movie = movieResponse.records?.find { it.sortOrder.toString() == identifier }
                        
                        movie?.let { movieData ->
                            newMovieLoadResponse(
                                name = movieData.title ?: "Unknown Movie",
                                url = url,
                                type = TvType.Movie,
                                dataUrl = url // Use the same URL for loadLinks
                            ) {
                                this.posterUrl = movieData.cover
                                this.year = movieData.year
                                this.plot = movieData.genre
                                this.tags = movieData.genre?.split(",")?.map { it.trim() }
                                this.recommendations = emptyList()
                            }
                        }
                    } else null
                }
                
                else -> null
            }
        } catch (e: Exception) {
            println("Error in load function: ${e.message}")
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val withoutUrlScheme = data.removePrefix("$mainUrl/")
            val headers = getPikashowHeaders()
            
            // Parse different data formats
            when {
                withoutUrlScheme.startsWith("pikashow_episode:") -> {
                    // Handle episode links: "pikashow_episode:seriesTitle:season:episode"
                    val parts = withoutUrlScheme.split(":")
                    if (parts.size >= 4) {
                        val seriesTitle = parts[1]
                        val season = parts[2]
                        val episode = parts[3]
                        
                        // Get episode streaming URLs from video API
                        val videoUrl = "$mainUrl/v1/api/video"
                        val params = mapOf(
                            "type" to "series",
                            "videoId" to "0",
                            "title" to seriesTitle,
                            "noseasons" to season,
                            "noepisodes" to episode
                        )
                        
                        val response = app.get(
                            url = videoUrl,
                            params = params,
                            headers = headers,
                            timeout = 30
                        )
                        
                        if (response.code == 200) {
                            val videoResponse = mapper.readValue<VideoApiResponse>(response.text)
                            videoResponse.data?.let { videoData ->
                                addVideoLinksToCallback(videoData, callback, "Episode $episode")
                                return true
                            }
                        }
                    }
                }
                
                withoutUrlScheme.startsWith("pikashow:") -> {
                    // Handle movie/series links: "pikashow:identifier:type"
                    val parts = withoutUrlScheme.split(":")
                    if (parts.size >= 3) {
                        val identifier = parts[1]
                        val type = parts[2]
                        
                        // First get the content details from the list API
                        val listUrl = "$mainUrl/v1/api/videos"
                        val listParams = mapOf(
                            "type" to type,
                            "channel" to "pikashow"
                        )
                        
                        val listResponse = app.get(
                            url = listUrl,
                            params = listParams,
                            headers = headers,
                            timeout = 30
                        )
                        
                        if (listResponse.code == 200) {
                            var videoId: String? = null
                            var title: String? = null
                            
                            when (type) {
                                "series" -> {
                                    val seriesResponse = mapper.readValue<PikashowSeriesResponse>(listResponse.text)
                                    val series = seriesResponse.series?.find { it.title == identifier }
                                    series?.let {
                                        videoId = "0"
                                        title = it.title
                                    }
                                }
                                "hollywood", "bollywood" -> {
                                    val movieResponse = mapper.readValue<PikashowMovieResponse>(listResponse.text)
                                    val movie = movieResponse.records?.find { it.sortOrder.toString() == identifier }
                                    movie?.let {
                                        videoId = it.sortOrder.toString()
                                        title = it.title
                                    }
                                }
                            }
                            
                            // Now get streaming URLs from video API
                            if (videoId != null && title != null) {
                                // Capture a non-mutable local copy to avoid smart-cast / closure issues
                                val safeTitle = title
                                val videoUrl = "$mainUrl/v1/api/video"
                                val videoParams = mapOf(
                                    "type" to type,
                                    "videoId" to videoId,
                                    "title" to safeTitle,
                                    "noseasons" to "1",
                                    "noepisodes" to "0"
                                )
                                
                                val videoResponse = app.get(
                                    url = videoUrl,
                                    params = videoParams,
                                    headers = headers,
                                )
                                
                                if (videoResponse.code != 404) {
                                    val videoApiResponse = mapper.readValue<VideoApiResponse>(videoResponse.text)
                                    val contentNameLocal = safeTitle
                                    videoApiResponse.data?.let { videoData ->
                                        addVideoLinksToCallback(videoData, callback, contentNameLocal)
                                        return true
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            return false
        } catch (e: Exception) {
            println("Error in loadLinks: ${e.message}")
            return false
        }
    }
    
    private suspend fun addVideoLinksToCallback(
        videoData: VideoData,
        callback: (ExtractorLink) -> Unit,
        contentName: String
    ) {
        val baseHeaders = mutableMapOf(
            "Referer" to "https://samui390dod.com/",
            "Origin" to "https://samui390dod.com"
        )
        
        // Add heastr and user agent from response if available
        videoData.heastr?.let { baseHeaders["heastr"] = it }
        videoData.uastr?.let { baseHeaders["user-agent"] = it }
        videoData.uaStr?.let { baseHeaders["user-agent"] = it } // Also check uaStr variant
        
        // Parse headerStr if available (it's a JSON string of additional headers)
        videoData.headerStr?.let { headerStr ->
            try {
                val additionalHeaders = mapper.readValue<Map<String, String>>(headerStr)
                baseHeaders.putAll(additionalHeaders)
            } catch (e: Exception) {
                println("Failed to parse headerStr: ${e.message}")
            }
        }
        
        // Merge with response headers, giving priority to response headers
        val finalHeaders = if (videoData.headers != null) {
            val merged = baseHeaders.toMutableMap()
            merged.putAll(videoData.headers)
            // Ensure priority fields are still included even if response headers override
            videoData.heastr?.let { merged["heastr"] = it }
            videoData.uastr?.let { merged["user-agent"] = it }
            videoData.uaStr?.let { merged["user-agent"] = it }
            merged.toMutableMap()
        } else {
            baseHeaders.toMutableMap()
        }
        
        // Check if we have any resolutions to work with
        val hasResolutions = !videoData.resolutions.isNullOrEmpty()
        val hasLanguageResolutions = videoData.languageOptions?.any { !it.resolutions.isNullOrEmpty() } == true ||
                                   videoData.languages?.any { !it.resolutions.isNullOrEmpty() } == true
        
        if (hasResolutions || hasLanguageResolutions) {
            // Add resolutions from main data
            videoData.resolutions?.forEach { resolution ->
                resolution.url?.let { url ->
                    val linkType = when {
                    url.contains("m3u8") || videoData.sourceType == "hls" -> ExtractorLinkType.M3U8
                    videoData.sourceType == "direct" -> ExtractorLinkType.VIDEO
                    else -> ExtractorLinkType.VIDEO
                }
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "${resolution.label ?: "Unknown"} - $contentName",
                            url,
                            linkType
                        ) {
                            this.referer = "https://samui390dod.com/"
                            this.quality = getQualityValueFromLabel(resolution.label)
                            this.headers = finalHeaders
                        }
                    )
                }
            }
            
            // Add language options if available
            (videoData.languageOptions ?: videoData.languages)?.forEach { lang ->
                lang.resolutions?.forEach { resolution ->
                    resolution.url?.let { url ->
                        val linkType = when {
                            url.contains("m3u8") || videoData.sourceType == "hls" -> ExtractorLinkType.M3U8
                            videoData.sourceType == "direct" -> ExtractorLinkType.VIDEO
                            else -> ExtractorLinkType.VIDEO
                        }
                        val langName = if (lang.language.isNullOrBlank()) "Default" else lang.language
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "${resolution.label ?: "Unknown"} ($langName) - $contentName",
                                url,
                                type = linkType
                            ) {
                                this.referer = "https://samui390dod.com/"
                                this.quality = getQualityValueFromLabel(resolution.label)
                                this.headers = finalHeaders
                            }
                        )
                    }
                }
            }
        } else {
            // Use URL-based HDBV player parsing when no resolutions are available
            if (videoData.url != null) {
                try {
                    val streamingUrl = parseHDBVPlayerUrl(videoData.url)
                    if (streamingUrl.isNotEmpty()) {
                        val urlOrigin = videoData.url.substringBefore("/", "https://") + "://" + videoData.url.substringAfter("://").substringBefore("/") + "/"
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$contentName - HDBV",
                                streamingUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = urlOrigin
                                this.quality = Qualities.P720.value
                                this.headers = finalHeaders
                            }
                        )
                    } else {
                        // Fallback to direct URLs if HDBV parsing fails
                        fallbackToDirectUrls(videoData, callback, contentName, finalHeaders)
                    }
                } catch (e: Exception) {
                    println("Error parsing HDBV player URL: ${e.message}")
                    // Fallback to direct URLs if HDBV parsing fails
                    fallbackToDirectUrls(videoData, callback, contentName, finalHeaders)
                }
            } else {
                // Direct URL fallback
                fallbackToDirectUrls(videoData, callback, contentName, finalHeaders)
            }
        }
        

    }
    
    private suspend fun parseHDBVPlayerUrl(playerUrl: String): String {
        try {
            // Use the playerUrl directly as the HDBV player link
            val response = app.get(
                url = playerUrl,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Accept-Encoding" to "gzip, deflate, br",
                    "Accept-Language" to "en-CA,en;q=0.9;q=0.8;q=0.7,en-US;q=0.6",
                    "Connection" to "keep-alive",
                    "Host" to "samui390dod.com",
                    "Icy-MetaData" to "1",
                    "Origin" to "https://samui390dod.com",
                    "Referer" to "https://samui390dod.com/",
                    "sec-ch-ua" to "\"Chromium\";v=\"136\", \"Android WebView\";v=\"136\", \"Not.A/Brand\";v=\"99\"",
                    "sec-ch-ua-mobile" to "?1",
                    "sec-ch-ua-platform" to "\"Android\"",
                    "Sec-Fetch-Dest" to "video",
                    "sec-fetch-mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "sec-fetch-user" to "?1",
                    "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 13_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.1.1 Mobile/15E148 Safari/604.1",
                    "X-Requested-With" to "com.offshore.pikachu"
                )
            )

            if (response.code != 200) return ""
            
            val doc = Jsoup.parse(response.text)
            val scripts = doc.getElementsByTag("script")
            
            if (scripts.size < 8) return ""
            
            val script = scripts[7].toString()
            val regex = Regex("""HDVBPlayer\((.*?)\);""")
            val matchResult = regex.find(script)

            if (matchResult != null) {
                val jsonInsideHDVBPlayer = matchResult.groupValues[1]
                val fileKeys = mapper.readValue<Keys>(jsonInsideHDVBPlayer)

                // Extract origin from the playerUrl
                val origin = playerUrl.substringBefore("/", "https://") + "://" + playerUrl.substringAfter("://").substringBefore("/") + "/"
                val absoluteUrl = origin + fileKeys.file
                val headers = mapOf(
                    "Accept" to "*/*",
                    "Accept-Encoding" to "gzip, deflate, br",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Content-Length" to "0",
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Origin" to origin,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36",
                    "X-Csrf-Token" to fileKeys.key
                )

                val referer = playerUrl
                val postResponse = app.post(
                    url = absoluteUrl,
                    headers = headers,
                    referer = referer
                )

                if (postResponse.code == 200) {
                    // Handle gzipped response
                    val responseText = if (postResponse.headers["Content-Encoding"] == "gzip") {
                        // For CloudStream3, the response should already be decompressed
                        postResponse.text
                    } else {
                        postResponse.text
                    }

                    val jsonArray = JSONArray(responseText)
                    val seasons = mutableListOf<Season>()
                    
                    for (i in 0 until jsonArray.length()) {
                        val jsonObject = jsonArray.getJSONObject(i).toString()
                        val seasonData = mapper.readValue<Season>(jsonObject.replace("[]", ""))
                        seasons.add(seasonData)
                    }

                    // For movies, typically use first season and first episode
                    val episodeDetails = seasons.firstOrNull() ?: return ""
                    val episode = episodeDetails.folder.firstOrNull()?.folder?.firstOrNull()?.file?.replace("~", "") ?: return ""

                    val playlistResponse = app.post(
                        url = "${origin}playlist/$episode.txt",
                        headers = headers,
                        referer = referer
                    )

                    return if (playlistResponse.code == 200) {
                        playlistResponse.text.trim()
                    } else {
                        ""
                    }
                }
            }
        } catch (e: Exception) {
            println("Error parsing HDBV player URL: ${e.message}")
        }
        
        return ""
    }
    
    private fun extractImdbIdFromUrl(url: String): String {
        // Extract a unique identifier from the URL
        // This could be an IMDB ID, or any other unique identifier in the URL
        // For now, using a hash of the URL as identifier
        return try {
            val hash = MessageDigest.getInstance("MD5").digest(url.toByteArray())
            hash.joinToString("") { "%02x".format(it) }.take(10)
        } catch (e: Exception) {
            "default"
        }
    }
    
    private suspend fun fallbackToDirectUrls(
        videoData: VideoData,
        callback: (ExtractorLink) -> Unit,
        contentName: String,
        finalHeaders: Map<String, String>
    ) {
        val directUrl = videoData.playUrl ?: videoData.videoUrl ?: videoData.url
        directUrl?.let { url ->
            // Determine quality based on source type or host
            val quality = when {
                videoData.quality?.lowercase()?.contains("hd") == true -> Qualities.P720.value
                videoData.quality?.lowercase()?.contains("1080") == true -> Qualities.P1080.value
                videoData.quality?.lowercase()?.contains("720") == true -> Qualities.P720.value
                videoData.quality?.lowercase()?.contains("480") == true -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }
            
            // Determine link type based on URL extension or source type
            val linkType = when {
                url.contains("m3u8") || videoData.sourceType == "hls" -> ExtractorLinkType.M3U8
                videoData.sourceType == "direct" -> ExtractorLinkType.VIDEO
                else -> ExtractorLinkType.VIDEO
            }
            
            callback.invoke(
                newExtractorLink(
                    name,
                    "$contentName - ${videoData.host ?: "Direct"}",
                    url,
                    type = linkType
                ) {
                    this.referer = "https://samui390dod.com/"
                    this.quality = quality
                    this.headers = finalHeaders
                }
            )
        }
    }
    
    private fun getQualityValue(qualityString: String?): Int {
        return when (qualityString?.uppercase()) {
            "4K", "2160P" -> Qualities.P2160.value
            "FHD", "1080P" -> Qualities.P1080.value
            "HD", "720P" -> Qualities.P720.value
            "SD", "480P" -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
    
    private fun getQualityValueFromLabel(label: String?): Int {
        return when (label?.lowercase()) {
            "1080p" -> Qualities.P1080.value
            "720p" -> Qualities.P720.value
            "480p" -> Qualities.P480.value
            "360p" -> Qualities.P360.value
            "default" -> Qualities.P720.value
            else -> Qualities.Unknown.value
        }
    }
}