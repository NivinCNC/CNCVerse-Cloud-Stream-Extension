package com.hdo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import android.util.Log
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class HDO : TmdbProvider() {
    override var name = "HDO"
    override val hasMainPage = true
    override var lang = "ta"
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    companion object {
         var cont: Context? = null
    }
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Show star popup on first home page visit (shared across all CNCVerse plugins)
        cont?.let { context ->
            withContext(Dispatchers.Main) {
                StarPopupHelper.showStarPopupIfNeeded(context)
            }
        }
        
        // Call the parent implementation
        return super.getMainPage(page, request)
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mediaData = AppUtils.parseJson<TmdbLink>(data).toLinkData()
        
        Log.d("HDOProvider", "Loading links for: ${mediaData.title} (${mediaData.type})")
        
        // Use Hula API server from localhost:3000
        safeApiCall {
            SubUtils.invokeWyZIESUBAPI(
                mediaData.imdbId,
                mediaData.season,
                mediaData.episode,
                subtitleCallback,
            )
        
            SubUtils.invokeSubtitleAPI(
                mediaData.imdbId,
                mediaData.season,
                mediaData.episode,
                subtitleCallback
            )
            
            // Call Hula API server
            Log.d("HDOProvider", "Calling Hula API server...")
            val hasResults = callHulaApiServer(mediaData, callback)
            
            if (hasResults) {
                Log.d("HDOProvider", "Successfully loaded video links from Hula API")
            } else {
                Log.w("HDOProvider", "Failed to load video links from Hula API")
            }
        }


        
        return true
    }

    // --- Data classes and helper functions ---

    data class HulaMovieInfo(
        @JsonProperty("imdbId") val imdbId: String? = null,
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("type") val type: String? = null
    )
    
    data class HulaApiResponse(
        @JsonProperty("query") val query: HulaMovieInfo? = null,
        @JsonProperty("count") val count: Int = 0,
        @JsonProperty("results") val results: List<HulaResult> = emptyList()
    )
    
    data class HulaResult(
        @JsonProperty("provider") val provider: String? = null,
        @JsonProperty("host") val host: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("quality") val quality: Int = 720,
        @JsonProperty("url") val url: String = "",
        @JsonProperty("headers") val headers: Map<String, String> = emptyMap(),
        @JsonProperty("tracks") val tracks: List<HulaTrack> = emptyList()
    )
    
    data class HulaTrack(
        @JsonProperty("file") val file: String = "",
        @JsonProperty("quality") val quality: Int = 720
    )

    private fun TmdbLink.toLinkData(): HulaMovieInfo {
        val isMovie = this.season == null
        return HulaMovieInfo(
            imdbId = imdbID,
            tmdbId = tmdbID,
            title = movieName,
            year = movieName?.substringAfterLast("(", "")?.substringBefore(")", "")?.toIntOrNull(),
            season = season,
            episode = episode,
            type = if (isMovie) "movie" else "tv"
        )
    }
    
    private suspend fun callHulaApiServer(
        mediaData: HulaMovieInfo,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val apiUrl = buildString {
                append("https://hdo-cncverse.vercel.app/api/stream?")
                mediaData.imdbId?.let { append("imdb=$it&") }
                mediaData.tmdbId?.let { append("tmdb=$it&") }
                mediaData.title?.let {
                    val sanitized = it
                        .replace(Regex("[^\\p{L}\\p{Nd}\\s']+"), " ") // replace special chars except apostrophe with space
                        .replace(Regex("\\s+"), " ") // collapse multiple spaces
                        .trim()
                    append("title=${java.net.URLEncoder.encode(sanitized, "UTF-8")}&")
                }
                mediaData.year?.let { append("year=$it&") }
                mediaData.season?.let { append("season=$it&") }
                mediaData.episode?.let { append("episode=$it&") }
                if (endsWith("&")) {
                    setLength(length - 1)
                }
            }
            
            Log.d("HDOProvider", "Calling Hula API: $apiUrl")
            
            val response = app.get(apiUrl, timeout = 30)
            if (response.code != 200) {
                Log.e("HDOProvider", "Hula API error: ${response.code}")
                return false
            }
            
            val apiResponse = AppUtils.parseJson<HulaApiResponse>(response.text)
            if (apiResponse.results.isNullOrEmpty()) {
                Log.w("HDOProvider", "No results from Hula API")
                return false
            }
            
            Log.d("HDOProvider", "Hula API returned ${apiResponse.results.size} results")
            
            for (result in apiResponse.results) {
                try {
                    val referer = result.headers["referer"] ?: result.headers["Referer"] ?: ""
                    val headers = result.headers
                    
                    // If tracks are available, create a link for each quality
                    if (result.tracks.isNotEmpty()) {
                        for (track in result.tracks) {
                            if (track.file.isNotEmpty()) {
                                val quality = track.quality
                                callback(
                                    newExtractorLink(
                                        result.provider ?: "Hula",
                                        "${result.provider} - ${quality}p",
                                        track.file,
                                        ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = quality
                                        this.referer = referer
                                        this.headers = headers
                                    }
                                )
                                Log.d("HDOProvider", "Added link from ${result.provider}: ${quality}p")
                            }
                        }
                    } else if (result.url.isNotEmpty()) {
                        // Fallback: use the main URL if no tracks
                        callback(
                            newExtractorLink(
                                result.provider ?: "Hula",
                                "${result.provider} - 720p",
                                result.url,
                                ExtractorLinkType.M3U8
                            ) {
                                this.quality = 720
                                this.referer = referer
                                this.headers = headers
                            }
                        )
                        Log.d("HDOProvider", "Added link from ${result.provider}: 720p (fallback)")
                    }
                } catch (e: Exception) {
                    Log.e("HDOProvider", "Error creating ExtractorLink: ${e.message}")
                }
            }
            
            true
            
        } catch (e: Exception) {
            Log.e("HDOProvider", "Error calling Hula API: ${e.message}", e)
            false
        }
    }
}