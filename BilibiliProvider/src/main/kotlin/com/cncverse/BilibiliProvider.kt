@file:OptIn(Prerelease::class)

package com.cncverse

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newAudioFile
import java.net.URLEncoder


class BilibiliProvider : MainAPI() {
    override var mainUrl = "https://www.bilibili.tv"
    override var name = "BilibiliTV(Requires CS Prerelease)"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Movie,
        TvType.TvSeries,
    )

    companion object {
        var context: Context? = null
        private const val TAG = "BilibiliTVProvider"
        
        // Web API base - these endpoints work without authentication
        private const val API_BASE = "https://api.bilibili.tv"
        private const val WEB_API = "$API_BASE/intl/gateway/web/v2"
        // Playurl API (used by yt-dlp)
        private const val PLAYURL_API = "$API_BASE/intl/gateway/web/playurl"
        
        // User agent for web requests (Chrome 131 required for some APIs)
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    // Headers for API requests
    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // Content access error types
    private enum class ContentAccessError {
        NONE, GEO_LOCKED, PREMIUM_REQUIRED
    }

    // Helper function to check content access restrictions
    private suspend fun checkContentAccess(epId: String?, aid: String?): ContentAccessError {
        try {
            val playurlUrl = when {
                epId != null -> "$PLAYURL_API?ep_id=$epId&device=wap&platform=web&qn=64&tf=0&type=0"
                aid != null -> "$PLAYURL_API?s_locale=en_US&platform=web&aid=$aid&qn=120"
                else -> return ContentAccessError.NONE
            }
            
            val response = app.get(playurlUrl, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )).text
            
            val json = parseJson<BiliPlayurlResponse>(response)
            
            return when {
                // Code 10015001 = geo-restricted ("版权地区受限")
                json.code == 10015001 || json.message?.contains("地区") == true || json.message?.contains("region") == true -> ContentAccessError.GEO_LOCKED
                // Code 10004004 = premium content
                json.code == 10004004 -> ContentAccessError.PREMIUM_REQUIRED
                else -> ContentAccessError.NONE
            }
        } catch (e: Exception) {
            return ContentAccessError.NONE
        }
    }

    // Legacy helper for backward compatibility
    private suspend fun checkGeoRestriction(epId: String?, aid: String?): Boolean {
        return checkContentAccess(epId, aid) == ContentAccessError.GEO_LOCKED
    }

    override val mainPage = mainPageOf(
        "foryou" to "For You",
        "timeline" to "Latest Updates",
        "search:movie" to "Movies",
        "search:anime" to "Anime",
        "search:drama" to "Drama",
        "search:action" to "Action",
        "search:comedy" to "Comedy",
        "search:romance" to "Romance",
        "search:thriller" to "Thriller",
        "search:horror" to "Horror",
        "search:fantasy" to "Fantasy",
        "search:adventure" to "Adventure",
        "search:isekai" to "Isekai",
        "search:hindi" to "Hindi Dubbed",
        "search:tagalog" to "Tagalog Dubbed",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val home = mutableListOf<SearchResponse>()
        
        try {
            when {
                request.data == "foryou" -> {
                    // Get diverse content using popular search terms
                    val popularTerms = listOf("full movie", "anime", "action", "comedy", "drama")
                    val term = popularTerms[page % popularTerms.size]
                    
                    val searchUrl = "$WEB_API/search_v2?keyword=$term&platform=web&pn=$page&ps=30"
                    val response = app.get(searchUrl, headers = headers).text
                    Log.d(TAG, "For you response: ${response.take(500)}")
                    
                    val json = parseJson<BiliSearchResponse>(response)
                    
                    // Parse all modules - both OGV and UGC
                    json.data?.modules?.forEach { module ->
                        module.items?.forEach { item ->
                            val title = item.title ?: return@forEach
                            val seasonId = item.seasonId
                            val aid = item.aid
                            
                            if (seasonId != null) {
                                val href = "$mainUrl/en/play/$seasonId"
                                home.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                                    this.posterUrl = item.cover?.ensureHttps()
                                })
                            } else if (aid != null) {
                                val href = "$mainUrl/en/video/$aid"
                                home.add(newMovieSearchResponse(title, href, TvType.Movie) {
                                    this.posterUrl = item.cover?.ensureHttps()
                                })
                            }
                        }
                    }
                }
                request.data == "timeline" -> {
                    // Use timeline API for latest updates
                    val timelineUrl = "$WEB_API/ogv/timeline?platform=web&s_locale=en_US"
                    val response = app.get(timelineUrl, headers = headers).text
                    Log.d(TAG, "Timeline response: ${response.take(500)}")
                    
                    val json = parseJson<BiliTimelineResponse>(response)
                    
                    // Collect cards from all days
                    json.data?.items?.forEach { day ->
                        day.cards?.forEach { card ->
                            val title = card.title ?: return@forEach
                            val seasonId = card.seasonId ?: return@forEach
                            val href = "$mainUrl/en/play/$seasonId"
                            
                            home.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                                this.posterUrl = card.cover?.ensureHttps()
                            })
                        }
                    }
                }
                request.data.startsWith("search:") -> {
                    // Use search API for categories
                    val keyword = request.data.removePrefix("search:")
                    val searchUrl = "$WEB_API/search_v2?keyword=$keyword&platform=web&pn=$page&ps=30"
                    
                    val response = app.get(searchUrl, headers = headers).text
                    Log.d(TAG, "Search main page response: ${response.take(500)}")
                    
                    val json = parseJson<BiliSearchResponse>(response)
                    
                    // Parse all modules - OGV contains anime/movies
                    json.data?.modules?.forEach { module ->
                        module.items?.forEach { item ->
                            val title = item.title ?: return@forEach
                            // For OGV, get season_id; for UGC, get aid
                            val seasonId = item.seasonId
                            val aid = item.aid
                            
                            if (seasonId != null) {
                                val href = "$mainUrl/en/play/$seasonId"
                                home.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                                    this.posterUrl = item.cover?.ensureHttps()
                                })
                            } else if (aid != null) {
                                val href = "$mainUrl/en/video/$aid"
                                home.add(newMovieSearchResponse(title, href, TvType.Movie) {
                                    this.posterUrl = item.cover?.ensureHttps()
                                })
                            }
                        }
                    }
                }
                else -> {
                    // Fallback to search
                    val searchUrl = "$WEB_API/search_v2?keyword=${request.data}&platform=web&pn=$page&ps=30"
                    val response = app.get(searchUrl, headers = headers).text
                    val json = parseJson<BiliSearchResponse>(response)
                    
                    json.data?.modules?.forEach { module ->
                        module.items?.forEach { item ->
                            val title = item.title ?: return@forEach
                            val seasonId = item.seasonId
                            
                            if (seasonId != null) {
                                val href = "$mainUrl/en/play/$seasonId"
                                home.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                                    this.posterUrl = item.cover?.ensureHttps()
                                })
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading main page: ${e.message}", e)
        }

        return newHomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = home.size >= 15)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$WEB_API/search_v2?keyword=$encodedQuery&platform=web&pn=1&ps=30"
            
            Log.d(TAG, "Search URL: $searchUrl")
            val response = app.get(searchUrl, headers = headers).text
            Log.d(TAG, "Search response: ${response.take(500)}")
            
            val json = parseJson<BiliSearchResponse>(response)
            
            json.data?.modules?.forEach { module ->
                when (module.type) {
                    "ogv" -> {
                        // Anime/Movies (licensed content)
                        module.items?.forEach { item ->
                            val title = item.title ?: return@forEach
                            val seasonId = item.seasonId ?: return@forEach
                            val href = "$mainUrl/en/play/$seasonId"
                            
                            results.add(newAnimeSearchResponse(title, href, TvType.Anime) {
                                this.posterUrl = item.cover?.ensureHttps()
                            })
                        }
                    }
                    "ugc" -> {
                        // User generated content (videos)
                        module.items?.forEach { item ->
                            val title = item.title ?: return@forEach
                            val aid = item.aid ?: return@forEach
                            val href = "$mainUrl/en/video/$aid"
                            
                            results.add(newMovieSearchResponse(title, href, TvType.Movie) {
                                this.posterUrl = item.cover?.ensureHttps()
                            })
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search error: ${e.message}", e)
        }
        
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "Loading: $url")
        return when {
            url.contains("/play/") -> loadSeason(url)
            url.contains("/video/") -> loadVideo(url)
            else -> null
        }
    }

    private suspend fun loadSeason(url: String): LoadResponse? {
        // Extract season ID from URL: https://www.bilibili.tv/en/play/{seasonId}
        val seasonId = Regex("/play/(\\d+)").find(url)?.groupValues?.get(1) ?: return null
        
        try {
            // Get season info using the working web API
            val seasonInfoUrl = "$WEB_API/ogv/play/season_info?season_id=$seasonId&platform=web"
            Log.d(TAG, "Season info URL: $seasonInfoUrl")
            
            val seasonResponse = app.get(seasonInfoUrl, headers = headers).text
            Log.d(TAG, "Season info response: ${seasonResponse.take(1000)}")
            
            val seasonJson = parseJson<BiliSeasonInfoResponse>(seasonResponse)
            val season = seasonJson.data?.season ?: return null
            
            val title = season.title ?: return null
            val poster = season.verticalCover?.ensureHttps() ?: season.horizontalCover?.ensureHttps()
            var description = season.description
            
            // Check content access restrictions using first episode
            val episodesUrl = "$WEB_API/ogv/play/episodes?season_id=$seasonId&platform=web"
            val episodesResponse = app.get(episodesUrl, headers = headers).text
            val episodesJson = parseJson<BiliEpisodesResponse>(episodesResponse)
            val firstEpId = episodesJson.data?.sections?.firstOrNull()?.episodes?.firstOrNull()?.episodeId
            
            if (firstEpId != null) {
                when (checkContentAccess(firstEpId, null)) {
                    ContentAccessError.GEO_LOCKED -> throw ErrorLoadingException("⚠️ GEO-LOCKED: This content is not available in your region. Use a VPN to access content from Southeast Asia (Thailand, Indonesia, Vietnam, etc.)")
                    ContentAccessError.PREMIUM_REQUIRED -> throw ErrorLoadingException("⚠️ PREMIUM CONTENT: This content requires a Bilibili TV subscription to watch.")
                    ContentAccessError.NONE -> { /* Content is accessible */ }
                }
            }
            
            Log.d(TAG, "Episodes response: ${episodesResponse.take(1000)}")
            
            val episodes = mutableListOf<Episode>()
            var episodeCounter = 1
            
            episodesJson.data?.sections?.forEach { section ->
                section.episodes?.forEach { ep ->
                    val epId = ep.episodeId ?: return@forEach
                    
                    // Skip PV and promotional content
                    if (ep.shortTitleDisplay?.contains("PV", ignoreCase = true) == true) {
                        return@forEach
                    }
                    
                    episodes.add(
                        newEpisode(
                            EpisodeData(
                                epId = epId,
                                seasonId = seasonId
                            ).toJson()
                        ) {
                            this.name = ep.longTitleDisplay ?: ep.titleDisplay ?: ep.shortTitleDisplay ?: "Episode $episodeCounter"
                            this.episode = ep.shortTitleDisplay?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: episodeCounter
                            this.posterUrl = ep.cover?.ensureHttps()
                        }
                    )
                    episodeCounter++
                }
            }
            
            // Parse year from playerDate
            val year = season.playerDate?.substring(0, 4)?.toIntOrNull()
            
            // Get styles/genres
            val tags = season.styles?.mapNotNull { it.title }
            
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
            }
        } catch (e: ErrorLoadingException) {
            throw e // Re-throw to show geo-lock message to user
        } catch (e: Exception) {
            Log.e(TAG, "Error loading season: ${e.message}", e)
            return null
        }
    }

    private suspend fun loadVideo(url: String): LoadResponse? {
        val aid = Regex("/video/(\\d+)").find(url)?.groupValues?.get(1) ?: return null
        
        try {
            // Check content access restrictions first
            when (checkContentAccess(null, aid)) {
                ContentAccessError.GEO_LOCKED -> throw ErrorLoadingException("⚠️ GEO-LOCKED: This content is not available in your region. Use a VPN to access content from Southeast Asia (Thailand, Indonesia, Vietnam, etc.)")
                ContentAccessError.PREMIUM_REQUIRED -> throw ErrorLoadingException("⚠️ PREMIUM CONTENT: This content requires a Bilibili TV subscription to watch.")
                ContentAccessError.NONE -> { /* Content is accessible */ }
            }
            
            // Fetch video page to get info
            val videoPage = app.get(url, headers = headers).text
            
            // Try to extract title from page
            val titleMatch = Regex("""<title>([^<]+)</title>""").find(videoPage)
            val title = titleMatch?.groupValues?.get(1)?.replace(" - Bilibili", "")?.trim() ?: "Video $aid"
            
            // Try to extract cover image
            val coverMatch = Regex("""<meta property="og:image" content="([^"]+)"""").find(videoPage)
            val cover = coverMatch?.groupValues?.get(1)?.ensureHttps()
            
            // Try to extract description
            val descMatch = Regex("""<meta property="og:description" content="([^"]+)"""").find(videoPage)
            val description = descMatch?.groupValues?.get(1)
            
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                EpisodeData(aid = aid).toJson()
            ) {
                this.posterUrl = cover
                this.plot = description
            }
        } catch (e: ErrorLoadingException) {
            throw e // Re-throw to show geo-lock message to user
        } catch (e: Exception) {
            Log.e(TAG, "Error loading video: ${e.message}", e)
            return newMovieLoadResponse(
                "Video $aid",
                url,
                TvType.Movie,
                EpisodeData(aid = aid).toJson()
            ) {
                this.plot = "Bilibili.tv video"
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "Loading links for: $data")
        
        try {
            val episodeData = parseJson<EpisodeData>(data)
            
            val epId = episodeData.epId
            val seasonId = episodeData.seasonId
            val aid = episodeData.aid
            
            var foundVideo = false
            
            // Method 1: Try the playurl API (what yt-dlp uses)
            if (epId != null || aid != null) {
                foundVideo = tryPlayurlApi(epId, aid, callback)
            }
            
            // Method 2: Try extracting from web page
            if (!foundVideo) {
                val playerUrl = when {
                    epId != null && seasonId != null -> "$mainUrl/en/play/$seasonId/$epId"
                    aid != null -> "$mainUrl/en/video/$aid"
                    else -> null
                }
                
                if (playerUrl != null) {
                    foundVideo = tryExtractFromPage(playerUrl, callback)
                }
            }
            
            // Method 3: Try the web player embed
            if (!foundVideo) {
                val embedUrl = when {
                    epId != null -> "https://www.bilibili.tv/embed/$epId"
                    aid != null -> "https://www.bilibili.tv/embed/video/$aid"
                    else -> null
                }
                
                if (embedUrl != null) {
                    try {
                        val embedContent = app.get(embedUrl, headers = headers).text
                        foundVideo = tryExtractFromPage(embedUrl, callback, embedContent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching embed: ${e.message}")
                    }
                }
            }
            
            // Always provide web player as fallback option
            val playerUrl = when {
                epId != null && seasonId != null -> "$mainUrl/en/play/$seasonId/$epId"
                aid != null -> "$mainUrl/en/video/$aid"
                else -> null
            }
            
            if (playerUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - Web Player (Open in Browser)",
                        url = playerUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = mainUrl
                    }
                )
            }
            
            // Load subtitles
            if (epId != null) {
                loadSubtitles(epId, subtitleCallback)
            }
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading links: ${e.message}", e)
        }
        
        return false
    }
    
    private suspend fun tryPlayurlApi(epId: String?, aid: String?, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // Exact same logic as the JavaScript downloader
            // Check if it's an episode ID (4-8 digit numeric) or aid
            val isEpisode = epId != null && epId.matches(Regex("^\\d{4,8}$"))
            
            // Build URL exactly like the downloader:
            // For ep_id: ?ep_id=${valor}&device=wap&platform=web&qn=64&tf=0&type=0
            // For aid: ?s_locale=en_US&platform=web&aid=${valor}&qn=120
            val playurlUrl = when {
                isEpisode -> "$PLAYURL_API?ep_id=$epId&device=wap&platform=web&qn=64&tf=0&type=0"
                epId != null -> "$PLAYURL_API?ep_id=$epId&device=wap&platform=web&qn=64&tf=0&type=0"
                aid != null -> "$PLAYURL_API?s_locale=en_US&platform=web&aid=$aid&qn=120"
                else -> return false
            }
            
            Log.d(TAG, "Playurl API (downloader format): $playurlUrl")
            
            // Make request with minimal headers like the downloader
            val response = app.get(playurlUrl, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )).text
            Log.d(TAG, "Playurl response: ${response.take(2000)}")
            
            val json = parseJson<BiliPlayurlResponse>(response)
            
            if (json.code != 0) {
                Log.d(TAG, "Playurl API error code: ${json.code}, message: ${json.message}")
                
                // Check for geo-restriction error (code 10015001 = "版权地区受限" / Copyright region restricted)
                if (json.code == 10015001 || json.message?.contains("地区") == true || json.message?.contains("region") == true) {
                    // Show geo-locked message to user
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "⚠️ GEO-LOCKED - Content not available in your region",
                            url = "https://bilibili.tv/geo-restricted",
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true // Return true so user sees the message
                }
                
                // Check for premium content error (code 10004004)
                if (json.code == 10004004) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "⚠️ PREMIUM CONTENT - Requires Bilibili TV subscription",
                            url = "https://bilibili.tv/premium",
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true // Return true so user sees the message
                }
                
                return false
            }
            
            val playurl = json.data?.playurl
            if (playurl == null) {
                Log.d(TAG, "Playurl data is null")
                return false
            }
            
            var urlVideo: String? = null
            var urlAudio: String? = null
            var foundVideoQuality = 0
            
            // Extract video URL - iterate through quality order like the downloader: 112, 80, 64, 32
            // The downloader checks: if (calidadVideo === 112 && url !== '') break, etc.
            val qualityOrder = listOf(112, 80, 64, 32)
            
            playurl.video?.let { videoList ->
                Log.d(TAG, "Found ${videoList.size} video streams")
                
                // Iterate like the downloader - pick first available with non-empty URL
                for (targetQuality in qualityOrder) {
                    for (videoInfo in videoList) {
                        val streamInfo = videoInfo.streamInfo
                        val videoResource = videoInfo.videoResource
                        val quality = streamInfo?.quality ?: 0
                        val url = videoResource?.url?.trim() ?: ""
                        
                        Log.d(TAG, "Video stream: quality=$quality, url=${url.take(100)}")
                        
                        if (quality == targetQuality && url.isNotEmpty()) {
                            urlVideo = url
                            foundVideoQuality = quality
                            Log.d(TAG, "Selected video quality $quality")
                            break
                        }
                    }
                    if (urlVideo != null) break
                }
                
                // If no video found with target qualities, take any available
                if (urlVideo == null) {
                    for (videoInfo in videoList) {
                        val videoResource = videoInfo.videoResource
                        val url = videoResource?.url?.trim() ?: ""
                        if (url.isNotEmpty()) {
                            urlVideo = url
                            foundVideoQuality = videoInfo.streamInfo?.quality ?: 0
                            break
                        }
                    }
                }
            }
            
            // Extract audio - get first item from audio_resource like the downloader
            // const audioInfo = audioInfoLista[0]; urlAudio = audioInfo.url;
            playurl.audioResource?.let { audioList ->
                Log.d(TAG, "Found ${audioList.size} audio streams")
                
                if (audioList.isNotEmpty()) {
                    val audioInfo = audioList[0]
                    urlAudio = audioInfo.url?.trim()
                    Log.d(TAG, "Selected audio: ${urlAudio?.take(100)}")
                }
            }
            
            var foundVideo = false
            
            // For DASH streams with separate video and audio, attach audio tracks to video
            if (!urlVideo.isNullOrEmpty()) {
                val qualityName = when (foundVideoQuality) {
                    112 -> "1080P+"
                    80 -> "1080P"
                    64 -> "720P"
                    32 -> "480P"
                    16 -> "360P"
                    else -> "${foundVideoQuality}p"
                }
                
                val qualityValue = when (foundVideoQuality) {
                    112 -> Qualities.P1080.value
                    80 -> Qualities.P1080.value
                    64 -> Qualities.P720.value
                    32 -> Qualities.P480.value
                    16 -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                
                val streamHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                )
                
                // Build audio tracks list
                val audioTracksList = mutableListOf<AudioFile>()
                if (!urlAudio.isNullOrEmpty()) {
                    audioTracksList.add(
                        newAudioFile(urlAudio!!) {
                            this.headers = streamHeaders
                        }
                    )
                }
                
                // Also add any additional audio tracks from audioResource
                playurl.audioResource?.forEachIndexed { index, audioInfo ->
                    val audioUrl = audioInfo.url?.trim() ?: ""
                    if (audioUrl.isNotEmpty() && audioUrl != urlAudio) {
                        audioTracksList.add(
                            newAudioFile(audioUrl) {
                                this.headers = streamHeaders
                            }
                        )
                    }
                }
                
                // Add main video stream with audio tracks
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - $qualityName",
                        url = urlVideo!!,
                        type = INFER_TYPE
                    ) {
                        this.quality = qualityValue
                        this.referer = mainUrl
                        this.headers = streamHeaders
                        this.audioTracks = audioTracksList
                    }
                )
                foundVideo = true
                Log.d(TAG, "Added video stream: $qualityName with ${audioTracksList.size} audio tracks, url: ${urlVideo?.take(100)}")
            }
            
            // Also add other available qualities with audio tracks
            playurl.video?.forEach { videoInfo ->
                val videoResource = videoInfo.videoResource
                val streamInfo = videoInfo.streamInfo
                val url = videoResource?.url?.trim() ?: ""
                val quality = streamInfo?.quality ?: 0
                
                if (url.isNotEmpty() && url != urlVideo) {
                    val qualityName = when (quality) {
                        112 -> "1080P+"
                        80 -> "1080P"
                        64 -> "720P"
                        32 -> "480P"
                        16 -> "360P"
                        else -> "${quality}p"
                    }
                    
                    val qualityValue = when (quality) {
                        112 -> Qualities.P1080.value
                        80 -> Qualities.P1080.value
                        64 -> Qualities.P720.value
                        32 -> Qualities.P480.value
                        16 -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    
                    val altStreamHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$mainUrl/"
                    )
                    
                    // Build audio tracks for alt streams too
                    val altAudioTracks = mutableListOf<AudioFile>()
                    if (!urlAudio.isNullOrEmpty()) {
                        altAudioTracks.add(
                            newAudioFile(urlAudio!!) {
                                this.headers = altStreamHeaders
                            }
                        )
                    }
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - $qualityName (Alt)",
                            url = url,
                            type = INFER_TYPE
                        ) {
                            this.quality = qualityValue
                            this.referer = mainUrl
                            this.headers = altStreamHeaders
                            this.audioTracks = altAudioTracks
                        }
                    )
                }
            }
            
            return foundVideo
        } catch (e: Exception) {
            Log.e(TAG, "Playurl API error: ${e.message}", e)
            return false
        }
    }
    
    private suspend fun tryExtractFromPage(pageUrl: String, callback: (ExtractorLink) -> Unit, existingContent: String? = null): Boolean {
        try {
            val pageContent = existingContent ?: app.get(pageUrl, headers = headers).text
            Log.d(TAG, "Page content length: ${pageContent.length}")
            
            var foundVideo = false
            
            // Pattern 1: Look for __INITIAL_STATE__ or __INITIAL_DATA__ with better regex
            val initialStatePatterns = listOf(
                Regex("""window\.__INITIAL_STATE__\s*=\s*(\{[\s\S]*?\});\s*(?:\(function|window\.|</script)"""),
                Regex("""window\.__INITIAL_DATA__\s*=\s*(\{[\s\S]*?\});\s*(?:</script|window\.)"""),
                Regex("""<script[^>]*id="__NEXT_DATA__"[^>]*>(\{[\s\S]*?\})</script>"""),
            )
            
            for (pattern in initialStatePatterns) {
                val match = pattern.find(pageContent)
                if (match != null) {
                    try {
                        val stateJson = match.groupValues[1]
                        Log.d(TAG, "Found state data length: ${stateJson.length}, preview: ${stateJson.take(500)}")
                        foundVideo = extractVideoUrlsFromJson(stateJson, pageUrl, callback)
                        if (foundVideo) {
                            Log.d(TAG, "Found video from state data")
                            break
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing state: ${e.message}")
                    }
                }
            }
            
            // Pattern 2: Look for video URLs in script tags containing "playurl" or "video_resource"
            if (!foundVideo) {
                val scriptRegex = Regex("""<script[^>]*>([\s\S]*?)</script>""")
                scriptRegex.findAll(pageContent).forEach { scriptMatch ->
                    val scriptContent = scriptMatch.groupValues[1]
                    if (scriptContent.contains("playurl") || scriptContent.contains("video_resource") || 
                        scriptContent.contains("baseUrl") || scriptContent.contains("base_url")) {
                        Log.d(TAG, "Found potential video script, length: ${scriptContent.length}")
                        if (extractVideoUrlsFromJson(scriptContent, pageUrl, callback)) {
                            foundVideo = true
                            return@forEach
                        }
                    }
                }
            }
            
            // Pattern 3: Look for playurlSSRData (used by bilibili.com bangumi)
            if (!foundVideo) {
                val playurlSSRDataRegex = Regex("""playurlSSRData\s*=\s*(\{[\s\S]+?\})\s*[\n;]""")
                val playurlSSRDataMatch = playurlSSRDataRegex.find(pageContent)
                
                if (playurlSSRDataMatch != null) {
                    try {
                        val ssrJson = playurlSSRDataMatch.groupValues[1]
                        Log.d(TAG, "Found playurlSSRData: ${ssrJson.take(500)}")
                        foundVideo = extractVideoUrlsFromJson(ssrJson, pageUrl, callback)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing playurlSSRData: ${e.message}")
                    }
                }
            }
            
            // Pattern 4: Look for window.__playinfo__ 
            if (!foundVideo) {
                val playInfoRegex = Regex("""window\.__playinfo__\s*=\s*(\{[\s\S]*?\});""")
                val playInfoMatch = playInfoRegex.find(pageContent)
                
                if (playInfoMatch != null) {
                    try {
                        val playInfoJson = playInfoMatch.groupValues[1]
                        Log.d(TAG, "Found playInfo: ${playInfoJson.take(500)}")
                        
                        val playInfo = parseJson<BiliPlayInfo>(playInfoJson)
                        
                        playInfo.data?.dash?.video?.forEach { video ->
                            val videoUrl = video.baseUrl ?: video.base_url ?: return@forEach
                            val quality = getQualityFromId(video.id ?: 0)
                            
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name - ${quality.name}",
                                    url = videoUrl,
                                    type = INFER_TYPE
                                ) {
                                    this.quality = quality.ordinal
                                    this.referer = mainUrl
                                    this.headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer" to pageUrl
                                    )
                                }
                            )
                            foundVideo = true
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing playInfo: ${e.message}", e)
                    }
                }
            }
            
            // Pattern 5: Look for m3u8 URLs directly in page content
            if (!foundVideo) {
                val m3u8Regex = Regex("""(https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*)""")
                m3u8Regex.findAll(pageContent).distinctBy { it.value }.take(5).forEach { match ->
                    val videoUrl = match.value
                        .replace("\\u002F", "/")
                        .replace("\\/", "/")
                    
                    if (isValidVideoUrl(videoUrl)) {
                        Log.d(TAG, "Found m3u8 URL: $videoUrl")
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name - HLS",
                                url = videoUrl,
                                type = INFER_TYPE
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.referer = mainUrl
                                this.headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to pageUrl
                                )
                            }
                        )
                        foundVideo = true
                    }
                }
            }
            
            // Pattern 6: Look for video/mp4 URLs with better patterns
            if (!foundVideo) {
                val mp4Patterns = listOf(
                    Regex("""(https?://[^"'\s<>\\]+\.mp4[^"'\s<>\\]*)"""),
                    Regex(""""url"\s*:\s*"(https?://[^"]+\.mp4[^"]*)""""),
                    Regex("""src\s*[=:]\s*["'](https?://[^"']+\.mp4[^"']*)["']"""),
                )
                
                for (pattern in mp4Patterns) {
                    pattern.findAll(pageContent).distinctBy { it.groupValues.getOrNull(1) ?: it.value }.take(5).forEach { match ->
                        val videoUrl = (match.groupValues.getOrNull(1) ?: match.value)
                            .replace("\\u002F", "/")
                            .replace("\\/", "/")
                        
                        if (isValidVideoUrl(videoUrl)) {
                            Log.d(TAG, "Found MP4 URL: $videoUrl")
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name - MP4",
                                    url = videoUrl,
                                    type = INFER_TYPE
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.referer = mainUrl
                                    this.headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer" to pageUrl
                                    )
                                }
                            )
                            foundVideo = true
                        }
                    }
                    if (foundVideo) break
                }
            }
            
            // Pattern 7: Look for m4s segment URLs (DASH) - common for bilibili
            if (!foundVideo) {
                val m4sRegex = Regex("""(https?://[^"'\s<>\\]+\.m4s[^"'\s<>\\]*)""")
                m4sRegex.findAll(pageContent).distinctBy { it.value }.take(5).forEach { match ->
                    val videoUrl = match.value
                        .replace("\\u002F", "/")
                        .replace("\\/", "/")
                    
                    if (isValidVideoUrl(videoUrl)) {
                        Log.d(TAG, "Found M4S URL: $videoUrl")
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name - DASH Segment",
                                url = videoUrl,
                                type = INFER_TYPE
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.referer = mainUrl
                                this.headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to pageUrl
                                )
                            }
                        )
                        foundVideo = true
                    }
                }
            }
            
            // Pattern 8: Look for any bilibili CDN URLs
            if (!foundVideo) {
                val cdnPatterns = listOf(
                    Regex("""(https?://[^"'\s<>\\]*(?:upos-sz|bilivideo|bstar)[^"'\s<>\\]+)"""),
                    Regex("""(https?://[^"'\s<>\\]*akamaized\.net[^"'\s<>\\]+)"""),
                )
                
                for (pattern in cdnPatterns) {
                    pattern.findAll(pageContent).distinctBy { it.value }.take(5).forEach { match ->
                        val videoUrl = match.value
                            .replace("\\u002F", "/")
                            .replace("\\/", "/")
                        
                        if (videoUrl.length > 50) { // CDN URLs are typically long
                            Log.d(TAG, "Found CDN URL: $videoUrl")
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name - CDN Stream",
                                    url = videoUrl,
                                    type = INFER_TYPE
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.referer = mainUrl
                                    this.headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer" to pageUrl
                                    )
                                }
                            )
                            foundVideo = true
                        }
                    }
                    if (foundVideo) break
                }
            }
            
            return foundVideo
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting from page: ${e.message}", e)
            return false
        }
    }
    
    private fun isValidVideoUrl(url: String): Boolean {
        val validDomains = listOf(
            "bilibili", "bstar", "bstarstatic", 
            "upos", "akamai", "bilivideo", 
            "hdslb", "acgvideo"
        )
        return validDomains.any { url.contains(it, ignoreCase = true) }
    }

    private suspend fun extractVideoUrlsFromJson(json: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        var foundVideo = false
        
        // Look for video URLs in the JSON
        val urlPatterns = listOf(
            Regex(""""url"\s*:\s*"(https?://[^"]+)""""),
            Regex(""""baseUrl"\s*:\s*"(https?://[^"]+)""""),
            Regex(""""base_url"\s*:\s*"(https?://[^"]+)""""),
            Regex(""""playUrl"\s*:\s*"(https?://[^"]+)""""),
            Regex(""""play_url"\s*:\s*"(https?://[^"]+)""""),
        )
        
        urlPatterns.forEach { pattern ->
            pattern.findAll(json).distinctBy { it.groupValues[1] }.forEach { match ->
                val url = match.groupValues[1]
                    .replace("\\u002F", "/")
                    .replace("\\/", "/")
                
                // Check if it's a video URL
                if (url.contains("m3u8") || url.contains("mp4") || url.contains("video") || url.contains("playurl")) {
                    if (url.contains("bilibili") || url.contains("bstar") || url.contains("upos") || url.contains("akamai") || url.contains("bilivideo")) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name - Stream",
                                url = url,
                                type = INFER_TYPE
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.referer = mainUrl
                                this.headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to referer
                                )
                            }
                        )
                        foundVideo = true
                    }
                }
            }
        }
        
        return foundVideo
    }

    private suspend fun loadSubtitles(
        epId: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val subtitleUrl = "$WEB_API/subtitle?ep_id=$epId&platform=web&s_locale=en_US"
            val response = app.get(subtitleUrl, headers = headers).text
            val json = parseJson<BiliSubtitleResponse>(response)
            
            json.data?.subtitles?.forEach { subtitle ->
                val subUrl = subtitle.url?.ensureHttps() ?: return@forEach
                val lang = subtitle.langDoc ?: subtitle.lang ?: "Unknown"
                
                subtitleCallback.invoke(
                    newSubtitleFile(
                        lang = lang,
                        url = subUrl
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading subtitles: ${e.message}")
        }
    }

    // Helper functions
    private fun String.ensureHttps(): String {
        return when {
            this.startsWith("//") -> "https:$this"
            this.startsWith("http://") -> this.replace("http://", "https://")
            else -> this
        }
    }
    
    // Generate DASH manifest to combine video and audio streams
    private fun generateDashManifest(videoUrl: String, audioUrl: String, quality: Int): String {
        val width = when (quality) {
            112 -> 1920
            80 -> 1920
            64 -> 1280
            32 -> 854
            16 -> 640
            else -> 1280
        }
        val height = when (quality) {
            112 -> 1080
            80 -> 1080
            64 -> 720
            32 -> 480
            16 -> 360
            else -> 720
        }
        
        return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT1.5S" mediaPresentationDuration="PT1H">
  <Period>
    <AdaptationSet mimeType="video/mp4" contentType="video">
      <Representation id="video" bandwidth="2000000" width="$width" height="$height">
        <BaseURL>$videoUrl</BaseURL>
      </Representation>
    </AdaptationSet>
    <AdaptationSet mimeType="audio/mp4" contentType="audio" lang="und">
      <Representation id="audio" bandwidth="128000">
        <BaseURL>$audioUrl</BaseURL>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>"""
    }
    
    private fun getQualityFromId(qn: Int): Qualities {
        return when (qn) {
            127 -> Qualities.P2160 // 8K
            126 -> Qualities.P2160 // Dolby Vision
            125 -> Qualities.P2160 // HDR
            120 -> Qualities.P2160 // 4K
            116 -> Qualities.P1080
            112 -> Qualities.P1080 // 1080P+
            80 -> Qualities.P1080
            74 -> Qualities.P720 // 720P60
            64 -> Qualities.P720
            32 -> Qualities.P480
            16 -> Qualities.P360
            else -> Qualities.Unknown
        }
    }
    
    /**
     * Generate a DASH MPD URL for combining video and audio streams
     * Uses our CORS proxy to serve a dynamically generated MPD manifest
     */
    private fun generateDashMpdUrl(videoUrl: String, audioUrl: String, quality: Int): String {
        // Since we can't use data URLs with ExoPlayer's DASH parser,
        // we'll use a special endpoint on our CORS proxy that generates MPD manifests
        val encodedVideoUrl = URLEncoder.encode(videoUrl, "UTF-8")
        val encodedAudioUrl = URLEncoder.encode(audioUrl, "UTF-8")
        
        // Generate MPD via our CORS proxy's MPD generator endpoint
        // Format: https://cors.cncverse.workers.dev/mpd?video=URL&audio=URL&quality=QN
        return "https://cors.cncverse.workers.dev/mpd?video=$encodedVideoUrl&audio=$encodedAudioUrl&quality=$quality"
    }
    
    private fun getQualityFromResolution(height: Int): Qualities {
        return when {
            height >= 2160 -> Qualities.P2160
            height >= 1440 -> Qualities.P1440
            height >= 1080 -> Qualities.P1080
            height >= 720 -> Qualities.P720
            height >= 480 -> Qualities.P480
            height >= 360 -> Qualities.P360
            else -> Qualities.Unknown
        }
    }

    // Data class for Episode Data
    data class EpisodeData(
        @JsonProperty("epId") val epId: String? = null,
        @JsonProperty("seasonId") val seasonId: String? = null,
        @JsonProperty("aid") val aid: String? = null,
    )

    // Data classes for Search API response
    data class BiliSearchResponse(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("data") val data: BiliSearchData? = null,
    )
    
    data class BiliSearchData(
        @JsonProperty("modules") val modules: List<BiliSearchModule>? = null,
        @JsonProperty("has_next") val hasNext: Boolean? = null,
    )
    
    data class BiliSearchModule(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("items") val items: List<BiliSearchItem>? = null,
    )
    
    data class BiliSearchItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("season_id") val seasonId: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("aid") val aid: String? = null,
        @JsonProperty("view") val view: String? = null,
        @JsonProperty("season_type") val seasonType: String? = null,
        @JsonProperty("description") val description: String? = null,
    )

    // Data classes for Season Info API response
    data class BiliSeasonInfoResponse(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("data") val data: BiliSeasonInfoData? = null,
    )
    
    data class BiliSeasonInfoData(
        @JsonProperty("season") val season: BiliSeason? = null,
    )
    
    data class BiliSeason(
        @JsonProperty("season_id") val seasonId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("vertical_cover") val verticalCover: String? = null,
        @JsonProperty("horizontal_cover") val horizontalCover: String? = null,
        @JsonProperty("player_date") val playerDate: String? = null,
        @JsonProperty("styles") val styles: List<BiliStyle>? = null,
        @JsonProperty("view") val view: String? = null,
        @JsonProperty("season_type") val seasonType: String? = null,
    )
    
    data class BiliStyle(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
    )

    // Data classes for Episodes API response
    data class BiliEpisodesResponse(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("data") val data: BiliEpisodesData? = null,
    )
    
    data class BiliEpisodesData(
        @JsonProperty("sections") val sections: List<BiliSection>? = null,
    )
    
    data class BiliSection(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("ep_list_title") val epListTitle: String? = null,
        @JsonProperty("episodes") val episodes: List<BiliEpisode>? = null,
    )
    
    data class BiliEpisode(
        @JsonProperty("episode_id") val episodeId: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("title_display") val titleDisplay: String? = null,
        @JsonProperty("short_title_display") val shortTitleDisplay: String? = null,
        @JsonProperty("long_title_display") val longTitleDisplay: String? = null,
        @JsonProperty("publish_time") val publishTime: String? = null,
    )

    // Data classes for Subtitle API response
    data class BiliSubtitleResponse(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("data") val data: BiliSubtitleData? = null,
    )
    
    data class BiliSubtitleData(
        @JsonProperty("subtitles") val subtitles: List<BiliSubtitle>? = null,
    )
    
    data class BiliSubtitle(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("lang_doc") val langDoc: String? = null,
    )

    // Data classes for PlayInfo (from page script)
    data class BiliPlayInfo(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("data") val data: BiliPlayInfoData? = null,
    )
    
    data class BiliPlayInfoData(
        @JsonProperty("dash") val dash: BiliDash? = null,
    )
    
    data class BiliDash(
        @JsonProperty("video") val video: List<BiliDashVideo>? = null,
        @JsonProperty("audio") val audio: List<BiliDashAudio>? = null,
    )
    
    data class BiliDashVideo(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("baseUrl") val baseUrl: String? = null,
        @JsonProperty("base_url") val base_url: String? = null,
        @JsonProperty("bandwidth") val bandwidth: Int? = null,
        @JsonProperty("codecid") val codecid: Int? = null,
    )
    
    data class BiliDashAudio(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("baseUrl") val baseUrl: String? = null,
        @JsonProperty("base_url") val base_url: String? = null,
        @JsonProperty("bandwidth") val bandwidth: Int? = null,
    )

    // Data classes for Timeline API response
    data class BiliTimelineResponse(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("data") val data: BiliTimelineData? = null,
    )
    
    data class BiliTimelineData(
        @JsonProperty("items") val items: List<BiliTimelineDay>? = null,
    )
    
    data class BiliTimelineDay(
        @JsonProperty("day_of_week") val dayOfWeek: String? = null,
        @JsonProperty("date_text") val dateText: String? = null,
        @JsonProperty("cards") val cards: List<BiliTimelineCard>? = null,
    )
    
    data class BiliTimelineCard(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("season_id") val seasonId: String? = null,
        @JsonProperty("episode_id") val episodeId: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("view") val view: String? = null,
        @JsonProperty("index_show") val indexShow: String? = null,
        @JsonProperty("style_list") val styleList: List<String>? = null,
    )

    // Data classes for Playurl API response (yt-dlp style)
    data class BiliPlayurlResponse(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("data") val data: BiliPlayurlData? = null,
    )
    
    data class BiliPlayurlData(
        @JsonProperty("playurl") val playurl: BiliPlayurlInfo? = null,
    )
    
    data class BiliPlayurlInfo(
        @JsonProperty("video") val video: List<BiliVideoStream>? = null,
        @JsonProperty("audio_resource") val audioResource: List<BiliAudioResource>? = null,
    )
    
    data class BiliVideoStream(
        @JsonProperty("video_resource") val videoResource: BiliVideoResource? = null,
        @JsonProperty("stream_info") val streamInfo: BiliStreamInfo? = null,
    )
    
    data class BiliVideoResource(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("width") val width: Int? = null,
        @JsonProperty("height") val height: Int? = null,
        @JsonProperty("bandwidth") val bandwidth: Int? = null,
        @JsonProperty("codecs") val codecs: String? = null,
        @JsonProperty("size") val size: Long? = null,
    )
    
    data class BiliStreamInfo(
        @JsonProperty("desc_words") val descWords: String? = null,
        @JsonProperty("quality") val quality: Int? = null,
    )
    
    data class BiliAudioResource(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("bandwidth") val bandwidth: Int? = null,
        @JsonProperty("codecs") val codecs: String? = null,
        @JsonProperty("size") val size: Long? = null,
    )
}
