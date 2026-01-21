package com.cncverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import com.lagradost.cloudstream3.utils.loadExtractor

class XonProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    
    override var mainUrl = "http://myavens18052002.xyz/nzapis"
    override var name = "Xon"
    override val hasMainPage = true
    override var lang = "ta"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    private var apiKey = "553y845hfhdlfhjkl438943943839443943fdhdkfjfj9834lnfd98"
    private var authToken: String? = null
    private var authExpireTime = 0L
    
    private fun getHeaders(): Map<String, String> {
        return mapOf(
            "api" to apiKey,
            "Cache-Control" to "no-cache",
            "caller" to "vion-official-app",
            "Connection" to "Keep-Alive",
            "Host" to "myavens18052002.xyz",
            "User-Agent" to "okhttp/3.14.9"
        )
    }

    private val mapper = jacksonObjectMapper()

    // Cache variables
    private var cachedLanguages: List<Language> = emptyList()
    private var cachedShows: List<Show> = emptyList()
    private var cachedSeasons: List<Season> = emptyList()
    private var cachedEpisodes: List<Episode> = emptyList()
    private var cachedMovies: List<Movie> = emptyList()
    private var lastCacheTime = 0L
    private val cacheRefreshInterval = 24 * 60 * 60 * 1000L // 24 hours

    // Data classes for API responses
    data class FirebaseAuthResponse(
        @JsonProperty("kind") val kind: String?,
        @JsonProperty("idToken") val idToken: String,
        @JsonProperty("refreshToken") val refreshToken: String,
        @JsonProperty("expiresIn") val expiresIn: String,
        @JsonProperty("localId") val localId: String
    )

    data class FirebaseSettingsResponse(
        @JsonProperty("name") val name: String?,
        @JsonProperty("fields") val fields: Map<String, Map<String, String>>,
        @JsonProperty("createTime") val createTime: String?,
        @JsonProperty("updateTime") val updateTime: String?
    )

    data class Language(
        @JsonProperty("id") val id: Int,
        @JsonProperty("no") val no: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("audio") val audio: String
    )

    data class Show(
        @JsonProperty("id") val id: Int,
        @JsonProperty("no") val no: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("thumb") val thumb: String,
        @JsonProperty("cover") val cover: String,
        @JsonProperty("des") val des: String,
        @JsonProperty("language") val language: Int,
        @JsonProperty("backup_img") val backupImg: String,
        @JsonProperty("locked") val locked: Int
    )

    data class Season(
        @JsonProperty("id") val id: Int,
        @JsonProperty("no") val no: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("thumb") val thumb: String,
        @JsonProperty("cover") val cover: String,
        @JsonProperty("genre") val genre: String?,
        @JsonProperty("des") val des: String?,
        @JsonProperty("type") val type: String,
        @JsonProperty("link") val link: String?,
        @JsonProperty("ongoing") val ongoing: Int,
        @JsonProperty("trending") val trending: Int,
        @JsonProperty("language") val language: Int,
        @JsonProperty("show_id") val showId: Int,
        @JsonProperty("block_ads") val blockAds: Int,
        @JsonProperty("backup_img") val backupImg: String?,
        @JsonProperty("ttype") val ttype: Int,
        @JsonProperty("trailer") val trailer: String?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("series") val series: String?,
        @JsonProperty("season") val season: String?,
        @JsonProperty("locked") val locked: Int
    )

    data class Episode(
        @JsonProperty("id") val id: Int,
        @JsonProperty("no") val no: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("thumb") val thumb: String,
        @JsonProperty("cover") val cover: String,
        @JsonProperty("des") val des: String,
        @JsonProperty("tags") val tags: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("link") val link: String,
        @JsonProperty("basic") val basic: String,
        @JsonProperty("sd") val sd: String,
        @JsonProperty("hd") val hd: String,
        @JsonProperty("fhd") val fhd: String,
        @JsonProperty("season_id") val seasonId: Int,
        @JsonProperty("show_id") val showId: Int,
        @JsonProperty("language") val language: Int,
        @JsonProperty("premium") val premium: Int,
        @JsonProperty("wfeathers") val wfeathers: Int,
        @JsonProperty("bfeathers") val bfeathers: Int,
        @JsonProperty("sfeathers") val sfeathers: Int,
        @JsonProperty("block_ads") val blockAds: Int,
        @JsonProperty("trending") val trending: Int,
        @JsonProperty("eplay") val eplay: String,
        @JsonProperty("backup_img") val backupImg: String,
        @JsonProperty("locked") val locked: Int,
        @JsonProperty("updated_at") val updatedAt: String
    )

    data class EpisodesResponse(
        @JsonProperty("current_time") val currentTime: String,
        @JsonProperty("episodes") val episodes: List<Episode>
    )

    data class Movie(
        @JsonProperty("id") val id: Int,
        @JsonProperty("no") val no: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("thumb") val thumb: String,
        @JsonProperty("cover") val cover: String,
        @JsonProperty("genre") val genre: String,
        @JsonProperty("des") val des: String,
        @JsonProperty("tags") val tags: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("link") val link: String,
        @JsonProperty("trailer") val trailer: String,
        @JsonProperty("ttype") val ttype: Int,
        @JsonProperty("basic") val basic: String,
        @JsonProperty("sd") val sd: String,
        @JsonProperty("hd") val hd: String,
        @JsonProperty("fhd") val fhd: String,
        @JsonProperty("show_id") val showId: Int,
        @JsonProperty("language") val language: Int,
        @JsonProperty("premium") val premium: Int,
        @JsonProperty("wfeathers") val wfeathers: Int,
        @JsonProperty("bfeathers") val bfeathers: Int,
        @JsonProperty("sfeathers") val sfeathers: Int,
        @JsonProperty("block_ads") val blockAds: Int,
        @JsonProperty("trending") val trending: Int,
        @JsonProperty("special") val special: Int,
        @JsonProperty("eplay") val eplay: String,
        @JsonProperty("backup_img") val backupImg: String,
        @JsonProperty("locked") val locked: Int
    )

    private suspend fun authenticateAndGetSettings() {
        try {
            // Step 1: Get Firebase authentication token
            val authResponse = app.post(
                "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=AIzaSyAC__yhrI4ExLcqWbZjsLN33_gVgyp6w3A",
                headers = mapOf("Content-Type" to "application/json"),
                data = mapOf<String, String>()
            )
            
            val authData = mapper.readValue<FirebaseAuthResponse>(authResponse.body.string())
            authToken = authData.idToken
            authExpireTime = System.currentTimeMillis() + (authData.expiresIn.toLongOrNull() ?: 3600) * 1000
            
            // Step 2: Get settings from Firestore using the auth token
            val settingsResponse = app.get(
                "https://firestore.googleapis.com/v1/projects/xon-app/databases/(default)/documents/settings/BvJwsNb0eaObbigSefkm",
                headers = mapOf("Authorization" to "Bearer ${authData.idToken}")
            )
            
            val settingsData = mapper.readValue<FirebaseSettingsResponse>(settingsResponse.body.string())
            
            // Update API key and base URL from settings
            settingsData.fields["api"]?.get("stringValue")?.let { 
                apiKey = it 
            }
            settingsData.fields["base"]?.get("stringValue")?.let { 
                mainUrl = it.removeSuffix("/")
            }
            
        } catch (e: Exception) {
            println("Xon Provider: Failed to authenticate - ${e.message}")
            // Fall back to hardcoded values if authentication fails
        }
    }

    suspend fun refreshCache() {
        val currentTime = System.currentTimeMillis()
        
        // Check if we need to re-authenticate
        if (authToken == null || currentTime >= authExpireTime) {
            authenticateAndGetSettings()
        }
        
        if (currentTime - lastCacheTime < cacheRefreshInterval &&
            cachedLanguages.isNotEmpty() &&
            cachedShows.isNotEmpty()) {
            return // Cache is still fresh
        }

        try {
            val headers = getHeaders()
            
            // Fetch languages
            val languagesResponse = app.get("$mainUrl/nzgetlanguages.php", headers = headers)
            cachedLanguages = mapper.readValue<List<Language>>(languagesResponse.body.string())

            // Fetch shows
            val showsResponse = app.get("$mainUrl/nzgetshows.php", headers = headers)
            cachedShows = mapper.readValue<List<Show>>(showsResponse.body.string())

            // Fetch seasons
            val seasonsResponse = app.get("$mainUrl/nzgetseasons.php", headers = headers)
            cachedSeasons = mapper.readValue<List<Season>>(seasonsResponse.body.string())

            // Fetch episodes
            val episodesResponse = app.get("$mainUrl/nzgetepisodes_v2.php?since=", headers = headers)
            val episodesData = mapper.readValue<EpisodesResponse>(episodesResponse.body.string())
            cachedEpisodes = episodesData.episodes

            // Fetch movies
            val moviesResponse = app.get("$mainUrl/nzgetmovies.php", headers = headers)
            cachedMovies = mapper.readValue<List<Movie>>(moviesResponse.body.string())

            lastCacheTime = currentTime
        } catch (e: Exception) {
            // Log error but don't crash
            println("Xon Provider: Failed to refresh cache - ${e.message}")
        }
    }

    private fun getLanguageName(languageId: Int): String {
        return cachedLanguages.find { it.id == languageId }?.name ?: "Unknown"
    }

    private fun getShowName(showId: Int): String {
        return cachedShows.find { it.id == showId }?.name ?: "Unknown Show"
    }

    private fun formatPosterUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "https://archive.org/download/$url"
        }
    }

    override val mainPage = mainPageOf(
        "trending_shows" to "Trending Shows",
        "latest_episodes" to "Latest Episodes",
        "movies" to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Show star popup on first visit (shared across all CNCVerse plugins)
        context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        
        refreshCache()
        val homePageList = mutableListOf<HomePageList>()

        when (request.data) {
            "trending_shows" -> {
                val trendingShows = cachedShows.take(20).map { show ->
                    val languageName = getLanguageName(show.language)
                    newTvSeriesSearchResponse(
                        name = "${show.name} ($languageName)",
                        url = "show:${show.id}",
                        type = TvType.TvSeries
                    ) {
                        this.posterUrl = formatPosterUrl(show.cover.ifEmpty { show.thumb })
                    }
                }
                homePageList.add(HomePageList("Trending Shows", trendingShows, isHorizontalImages = true))
            }

            "latest_episodes" -> {
                val latestEpisodes = cachedEpisodes.take(20).map { episode ->
                    val showName = getShowName(episode.showId)
                    val languageName = getLanguageName(episode.language)
                    newTvSeriesSearchResponse(
                        name = "$showName - ${episode.name} ($languageName)",
                        url = "episode:${episode.id}",
                        type = TvType.TvSeries
                    ) {
                        this.posterUrl = formatPosterUrl(episode.thumb)
                    }
                }
                homePageList.add(HomePageList("Latest Episodes", latestEpisodes, isHorizontalImages = true))
            }

            "movies" -> {
                val movieItems = cachedMovies.take(20).map { movie ->
                    val languageName = getLanguageName(movie.language)
                    newMovieSearchResponse(
                        name = "${movie.name} ($languageName)",
                        url = "movie:${movie.id}",
                        type = TvType.Movie
                    ) {
                        this.posterUrl = formatPosterUrl(movie.cover.ifEmpty { movie.thumb })
                    }
                }
                homePageList.add(HomePageList("Movies", movieItems, isHorizontalImages = true))
            }
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        refreshCache()
        val searchResults = mutableListOf<SearchResponse>()

        cachedShows.filter {
            it.name.contains(query, ignoreCase = true) ||
            (it.des.contains(query, ignoreCase = true))
        }.forEach { show ->
            val languageName = getLanguageName(show.language)
            searchResults.add(
                newTvSeriesSearchResponse(
                    name = "${show.name} ($languageName)",
                    url = "show:${show.id}",
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = formatPosterUrl(show.cover.ifEmpty { show.thumb })
                }
            )
        }

        // Search in episodes
        cachedEpisodes.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.tags.contains(query, ignoreCase = true)
        }.forEach { episode ->
            val showName = getShowName(episode.showId)
            val languageName = getLanguageName(episode.language)
            searchResults.add(
                newTvSeriesSearchResponse(
                    name = "$showName - ${episode.name} ($languageName)",
                    url = "episode:${episode.id}",
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = formatPosterUrl(episode.thumb)
                }
            )
        }

        // Search in movies
        cachedMovies.filter {
            it.name.contains(query, ignoreCase = true) ||
            (it.des.contains(query, ignoreCase = true)) ||
            it.tags.contains(query, ignoreCase = true)
        }.forEach { movie ->
            val languageName = getLanguageName(movie.language)
            searchResults.add(
                newMovieSearchResponse(
                    name = "${movie.name} ($languageName)",
                    url = "movie:${movie.id}",
                    type = TvType.Movie
                ) {
                    this.posterUrl = formatPosterUrl(movie.cover.ifEmpty { movie.thumb })
                }
            )
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        refreshCache()
        val str = url.substringAfterLast("/")
        val parts = str.split(":")
        if (parts.size != 2) return null

        val type = parts[0]
        val id = parts[1].toIntOrNull() ?: return null

        return when (type) {
            "show" -> {
                val show = cachedShows.find { it.id == id } ?: return null
                val showSeasons = cachedSeasons.filter { it.showId == id }
                val languageName = getLanguageName(show.language)

                val episodes = mutableListOf<Episode>()
                showSeasons.forEach { season ->
                    episodes.addAll(cachedEpisodes.filter { it.seasonId == season.id })
                }

                newTvSeriesLoadResponse(
                    name = "${show.name} ($languageName)",
                    url = url,
                    type = TvType.TvSeries,
                    episodes = episodes.map { episode ->
                        newEpisode("episode:${episode.id}") {
                            this.name = episode.name
                            this.season = showSeasons.find { it.id == episode.seasonId }?.no
                            this.episode = episode.no
                            this.posterUrl = formatPosterUrl(episode.thumb)
                            this.description = episode.des
                        }
                    }
                ) {
                    this.posterUrl = formatPosterUrl(show.cover.ifEmpty { show.thumb })
                    this.plot = "${show.des}\n\nLanguage: $languageName"
                }
            }

            "movie" -> {
                val movie = cachedMovies.find { it.id == id } ?: return null
                val languageName = getLanguageName(movie.language)

                newMovieLoadResponse(
                    name = "${movie.name} ($languageName)",
                    url = url,
                    type = TvType.Movie,
                    dataUrl = "movie:${movie.id}"
                ) {
                    this.posterUrl = formatPosterUrl(movie.cover.ifEmpty { movie.thumb })
                    this.plot = "${movie.des}\n\nLanguage: $languageName"
                }
            }

            "episode" -> {
                val episode = cachedEpisodes.find { it.id == id } ?: return null
                val show = cachedShows.find { it.id == episode.showId }
                val season = cachedSeasons.find { it.id == episode.seasonId }
                val languageName = getLanguageName(episode.language)

                newMovieLoadResponse(
                    name = "${show?.name ?: "Unknown"} - ${episode.name} ($languageName)",
                    url = url,
                    type = TvType.TvSeries,
                    dataUrl = "episode:${episode.id}"
                ) {
                    this.posterUrl = formatPosterUrl(episode.thumb)
                    this.plot = "${episode.des}\n\nSeason: ${season?.name ?: "Unknown"}\nLanguage: $languageName"
                }
            }

            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        refreshCache()
        val str = data.substringAfterLast("/")
        val parts = str.split(":")
        if (parts.size != 2) return false

        val type = parts[0]
        val id = parts[1].toIntOrNull() ?: return false

        when (type) {
            "episode" -> {
                val episode = cachedEpisodes.find { it.id == id } ?: return false

                // Add different quality links
                if (episode.basic.isNotEmpty()) {
                    callback(
                        newExtractorLink(
                            name,
                            "$name - Basic",
                            url = formatPosterUrl(episode.basic),
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.P240.value
                        }
                    )
                }

                if (episode.sd.isNotEmpty()) {
                    callback(
                        newExtractorLink(
                            name,
                            "$name - SD",
                            url = formatPosterUrl(episode.sd),
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.P480.value
                        }
                    )
                }

                if (episode.hd.isNotEmpty()) {
                    callback(
                        newExtractorLink(
                            name,
                            "$name - HD",
                            url = formatPosterUrl(episode.hd),
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.P720.value
                        }
                    )
                }

                if (episode.fhd.isNotEmpty()) {
                    callback(
                        newExtractorLink(
                            name,
                            "$name - FHD",
                            url = formatPosterUrl(episode.fhd),
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                }

                // Add external player link if available
                if (episode.link.isNotEmpty()) {
                   loadExtractor(episode.link, subtitleCallback, callback)
                }
            }

            "movie" -> {
                val movie = cachedMovies.find { it.id == id } ?: return false

                // Add different quality links
                if (movie.basic.isNotEmpty()) {
                    callback(
                        newExtractorLink(
                            name,
                            "$name - Basic",
                            url = formatPosterUrl(movie.basic),
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.P240.value
                        }
                    )
                }

                if (movie.sd.isNotEmpty()) {
                    callback(
                        newExtractorLink(
                            name,
                            "$name - SD",
                            url = formatPosterUrl(movie.sd),
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.P480.value
                        }
                    )
                }

                if (movie.hd.isNotEmpty()) {
                    callback(
                        newExtractorLink(
                            name,
                            "$name - HD",
                            url = formatPosterUrl(movie.hd),
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.P720.value
                        }
                    )
                }

                if (movie.fhd.isNotEmpty()) {
                    callback(
                        newExtractorLink(
                            name,
                            "$name - FHD",
                            url = formatPosterUrl(movie.fhd),
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                }

                // Add external player link if available
                if (movie.link.isNotEmpty()) {
                    loadExtractor(movie.link, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
