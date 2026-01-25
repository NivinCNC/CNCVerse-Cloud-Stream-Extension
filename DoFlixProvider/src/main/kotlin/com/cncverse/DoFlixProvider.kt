package com.cncverse

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class HeaderReplacementInterceptor(private val customHeaders: Map<String, String>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        // Remove existing headers that we want to replace
        customHeaders.keys.forEach { headerName ->
            requestBuilder.removeHeader(headerName)
        }

        // Add our custom headers
        customHeaders.forEach { (name, value) ->
            requestBuilder.addHeader(name, value)
        }

        return chain.proceed(requestBuilder.build())
    }
}

class DoFlixProvider : MainAPI() {
    companion object {
        var context: Context? = null
    }
    
    override var mainUrl = "https://panel.watchkaroabhi.com"
    override var name = "DoFlix"
    private val apiKey = "qNhKLJiZVyoKdi9NCQGz8CIGrpUijujE"
    override val hasMainPage = true
    override var lang = "ta"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    private val headers = mapOf(
        "Connection" to "Keep-Alive",
        "User-Agent" to "dooflix",
        "X-App-Version" to "304",
        "X-Package-Name" to "com.king.moja"
    )

    private val customClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HeaderReplacementInterceptor(headers))
            .build()
    }

    private suspend fun getWithCustomHeaders(url: String): String {
        val request = Request.Builder()
            .url(url)
            .build()

        return customClient.newCall(request).execute().use { response ->
            response.body.string()
        }
    }

    private val mapper = jacksonObjectMapper()

    // Movie genres
    private val movieGenres = mapOf(
        28 to "Action",
        12 to "Adventure",
        16 to "Animation",
        35 to "Comedy",
        80 to "Crime",
        99 to "Documentary",
        18 to "Drama",
        10751 to "Family",
        14 to "Fantasy",
        36 to "History",
        27 to "Horror",
        10402 to "Music",
        9648 to "Mystery",
        10749 to "Romance",
        878 to "Science Fiction",
        10770 to "TV Movie",
        53 to "Thriller",
        10752 to "War",
        37 to "Western"
    )

    // TV series genres
    private val seriesGenres = mapOf(
        10765 to "Sci-Fi & Fantasy",
        9648 to "Mystery",
        35 to "Comedy",
        18 to "Drama",
        10759 to "Action & Adventure",
        80 to "Crime",
        10767 to "Talk",
        10768 to "War & Politics",
        16 to "Animation",
        10751 to "Family",
        10764 to "Reality",
        10762 to "Kids",
        99 to "Documentary",
        37 to "Western",
        10763 to "News",
        10766 to "Soap"
    )

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

    private fun getQualityValue(qualityString: String?): Int {
        return when (qualityString?.uppercase()) {
            "4K", "2160P" -> Qualities.P2160.value
            "FHD", "1080P" -> Qualities.P1080.value
            "HD", "720P" -> Qualities.P720.value
            "SD", "480P" -> Qualities.P480.value
            "360P" -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MovieResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("custom_poster_tag") val customPosterTag: String?,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("popularity") val popularity: Double? = null,
        @JsonProperty("vote_count") val voteCount: Int? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null,
        @JsonProperty("genre_ids") val genreIds: List<Int>? = null,
        @JsonProperty("adult") val adult: Boolean? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("origin_country") val originCountry: List<String>? = null,
        @JsonProperty("gender") val gender: Int? = null,
        @JsonProperty("known_for") val knownFor: List<Any>? = null,
        @JsonProperty("known_for_department") val knownForDepartment: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
        @JsonProperty("video") val video: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeriesResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("custom_poster_tag") val customPosterTag: String?,
        @JsonProperty("original_name") val originalName: String,
        @JsonProperty("overview") val overview: String,
        @JsonProperty("first_air_date") val firstAirDate: String,
        @JsonProperty("popularity") val popularity: Double,
        @JsonProperty("vote_count") val voteCount: Int,
        @JsonProperty("vote_average") val voteAverage: Double,
        @JsonProperty("poster_path") val posterPath: String,
        @JsonProperty("backdrop_path") val backdropPath: String,
        @JsonProperty("original_language") val originalLanguage: String,
        @JsonProperty("genre_ids") val genreIds: List<Int>,
        @JsonProperty("origin_country") val originCountry: List<String>,
        @JsonProperty("adult") val adult: Boolean
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HomePageApiResponse(
        @JsonProperty("page") val page: Int,
        @JsonProperty("results") val results: List<MovieResult>,
        @JsonProperty("total_pages") val totalPages: Int,
        @JsonProperty("total_results") val totalResults: Int
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeriesPageApiResponse(
        @JsonProperty("page") val page: Int,
        @JsonProperty("results") val results: List<SeriesResult>,
        @JsonProperty("total_pages") val totalPages: Int,
        @JsonProperty("total_results") val totalResults: Int
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoItem(
        @JsonProperty("videos_id") val videosId: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("description") val description: String,
        @JsonProperty("slug") val slug: String,
        @JsonProperty("release") val release: String,
        @JsonProperty("is_tvseries") val isTvseries: String? = null,
        @JsonProperty("is_paid") val isPaid: String? = "0",
        @JsonProperty("runtime") val runtime: String,
        @JsonProperty("video_quality") val videoQuality: String,
        @JsonProperty("thumbnail_url") val thumbnailUrl: String,
        @JsonProperty("poster_url") val posterUrl: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchApiResponse(
        @JsonProperty("movie") val movie: List<VideoItem>,
        @JsonProperty("tvseries") val tvseries: List<VideoItem>,
        @JsonProperty("tv_channels") val tvChannels: List<Any>
    )

    // New TMDB-style movie details
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TMDBGenre(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ProductionCountry(
        @JsonProperty("iso_3166_1") val iso31661: String? = null,
        @JsonProperty("name") val name: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CastMember(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String,
        @JsonProperty("character") val character: String?,
        @JsonProperty("profile_path") val profilePath: String?,
        @JsonProperty("biography") val biography: String? = null,
        @JsonProperty("birthday") val birthday: String? = null,
        @JsonProperty("deathday") val deathday: String? = null,
        @JsonProperty("place_of_birth") val placeOfBirth: String? = null,
        @JsonProperty("gender") val gender: Int? = null,
        @JsonProperty("known_for_department") val knownForDepartment: String? = null,
        @JsonProperty("tmdb_id") val tmdbId: Int? = null,
        @JsonProperty("created_at") val createdAt: String? = null,
        @JsonProperty("updated_at") val updatedAt: String? = null,
        @JsonProperty("metadata") val metadata: String? = null,
        @JsonProperty("order") val order: Int? = null,
        @JsonProperty("adult") val adult: Boolean? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("popularity") val popularity: Double? = null,
        @JsonProperty("cast_id") val castId: Int? = null,
        @JsonProperty("credit_id") val creditId: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CrewMember(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String,
        @JsonProperty("job") val job: String?,
        @JsonProperty("department") val department: String?,
        @JsonProperty("profile_path") val profilePath: String? = null,
        @JsonProperty("biography") val biography: String? = null,
        @JsonProperty("birthday") val birthday: String? = null,
        @JsonProperty("deathday") val deathday: String? = null,
        @JsonProperty("place_of_birth") val placeOfBirth: String? = null,
        @JsonProperty("gender") val gender: Int? = null,
        @JsonProperty("known_for_department") val knownForDepartment: String? = null,
        @JsonProperty("tmdb_id") val tmdbId: Int? = null,
        @JsonProperty("created_at") val createdAt: String? = null,
        @JsonProperty("updated_at") val updatedAt: String? = null,
        @JsonProperty("metadata") val metadata: String? = null,
        @JsonProperty("adult") val adult: Boolean? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("popularity") val popularity: Double? = null,
        @JsonProperty("credit_id") val creditId: String? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)    data class Credits(
        @JsonProperty("cast") val cast: List<CastMember>,
        @JsonProperty("crew") val crew: List<CrewMember>,
        @JsonProperty("id") val id: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SimilarResults(
        @JsonProperty("results") val results: List<MovieResult>,
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null,
        @JsonProperty("total_results") val totalResults: Int? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)    data class MultiSearchResponse(
        @JsonProperty("page") val page: Int,
        @JsonProperty("results") val results: List<MovieResult>,
        @JsonProperty("total_pages") val totalPages: Int,
        @JsonProperty("total_results") val totalResults: Int
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MovieDetails(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String,
        @JsonProperty("overview") val overview: String,
        @JsonProperty("release_date") val releaseDate: String,
        @JsonProperty("runtime") val runtime: Int,
        @JsonProperty("poster_path") val posterPath: String,
        @JsonProperty("backdrop_path") val backdropPath: String,
        @JsonProperty("genres") val genres: List<TMDBGenre>,
        @JsonProperty("custom_poster_tag") val customPosterTag: String?,
        @JsonProperty("credits") val credits: Credits,
        @JsonProperty("similar") val similar: SimilarResults,
        @JsonProperty("budget") val budget: Int? = null,
        @JsonProperty("homepage") val homepage: String? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("origin_country") val originCountry: List<String>? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("popularity") val popularity: Double? = null,
        @JsonProperty("production_countries") val productionCountries: List<ProductionCountry>? = null,
        @JsonProperty("revenue") val revenue: Int? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("tagline") val tagline: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("vote_count") val voteCount: Int? = null,
        @JsonProperty("publish") val publish: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeasonInfo(
        @JsonProperty("season_number") val seasonNumber: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("episode_count") val episodeCount: Int,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("air_date") val airDate: String?,
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TvDetails(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String,
        @JsonProperty("first_air_date") val firstAirDate: String,
        @JsonProperty("poster_path") val posterPath: String,
        @JsonProperty("backdrop_path") val backdropPath: String,
        @JsonProperty("genres") val genres: List<TMDBGenre>,
        @JsonProperty("seasons") val seasons: List<SeasonInfo>,
        @JsonProperty("custom_poster_tag") val customPosterTag: String?,
        @JsonProperty("number_of_seasons") val numberOfSeasons: Int,
        @JsonProperty("credits") val credits: Credits,
        @JsonProperty("number_of_episodes") val numberOfEpisodes: Int? = null,
        @JsonProperty("similar") val similar: SimilarResults? = null,
        @JsonProperty("homepage") val homepage: String? = null,
        @JsonProperty("origin_country") val originCountry: List<String>? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("popularity") val popularity: Double? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("tagline") val tagline: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("vote_count") val voteCount: Int? = null,
        @JsonProperty("last_air_date") val lastAirDate: String? = null,
        @JsonProperty("in_production") val inProduction: Boolean? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("adult") val adult: Boolean? = null,
        @JsonProperty("created_by") val createdBy: List<Any>? = null,
        @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = null,
        @JsonProperty("languages") val languages: List<String>? = null,
        @JsonProperty("last_episode_to_air") val lastEpisodeToAir: Any? = null,
        @JsonProperty("networks") val networks: List<Any>? = null,
        @JsonProperty("next_episode_to_air") val nextEpisodeToAir: Any? = null,
        @JsonProperty("production_companies") val productionCompanies: List<Any>? = null,
        @JsonProperty("production_countries") val productionCountries: List<Any>? = null,
        @JsonProperty("spoken_languages") val spokenLanguages: List<Any>? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)    data class TvEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val stillPath: String?,
        @JsonProperty("air_date") val airDate: String?,
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("vote_count") val voteCount: Int? = null,
        @JsonProperty("production_code") val productionCode: String? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("show_id") val showId: Int? = null,
        @JsonProperty("crew") val crew: List<Any>? = null,
        @JsonProperty("guest_stars") val guestStars: List<Any>? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)    data class SeasonDetails(
        @JsonProperty("season_number") val seasonNumber: Int,
        @JsonProperty("episodes") val episodes: List<TvEpisode>,
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("_id") val mongoId: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)    data class StreamLink(
        @JsonProperty("host") val host: String,
        @JsonProperty("url") val url: String,
        @JsonProperty("quality") val quality: String,
        @JsonProperty("size") val size: String,
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("movie_id") val movieId: Int? = null,
        @JsonProperty("tv_show_id") val tvShowId: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("order") val order: Int? = null,
        @JsonProperty("created_at") val createdAt: String? = null,
        @JsonProperty("updated_at") val updatedAt: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MovieLinksResponse(
        @JsonProperty("links") val links: List<StreamLink>,
        @JsonProperty("id") val id: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeLinksResponse(
        @JsonProperty("results") val results: List<StreamLink>,
        @JsonProperty("id") val id: Int? = null
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/3/discover/movie?api_key=qNhKLJiZVyoKdi9NCQGz8CIGrpUijujE&language=en&sort_by=primary_release_date.desc&watch_region=IN&page=1" to "RecentMovies",
        "$mainUrl/api/3/discover/tv?api_key=qNhKLJiZVyoKdi9NCQGz8CIGrpUijujE&language=en&page=1&sort_by=first_air_date.desc" to "RecentSeries",
        "$mainUrl/api/3/discover/movie?api_key=qNhKLJiZVyoKdi9NCQGz8CIGrpUijujE&language=en&sort_by=popularity.desc&watch_region=IN&page=1" to "TrendingMovies",
        "$mainUrl/api/3/discover/tv?api_key=qNhKLJiZVyoKdi9NCQGz8CIGrpUijujE&language=en&page=1&sort_by=popularity.desc" to "TrendingSeries"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Show star popup on first visit (shared across all CNCVerse plugins)
        context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        
        val responseText = getWithCustomHeaders(request.data)
        val homePageList = mutableListOf<HomePageList>()

        // Check which endpoint is being requested
        when (request.name) {
            "RecentMovies" -> {
                val homeData = mapper.readValue<HomePageApiResponse>(responseText)
                if (homeData.results.isNotEmpty()) {
                    val movieItems = homeData.results.map { movie ->
                        newMovieSearchResponse(
                            name = movie.title ?: movie.name ?: "Unknown",
                            url = "movie,${movie.id}",
                            type = TvType.Movie
                        ) {
                            this.posterUrl = movie.posterPath
                            this.year = (movie.releaseDate ?: movie.firstAirDate)?.substringBefore("-")?.toIntOrNull()
                            this.quality = when {
                                (movie.voteAverage ?: 0.0) >= 8.0 -> SearchQuality.HD
                                (movie.voteAverage ?: 0.0) >= 7.0 -> SearchQuality.HD
                                else -> null
                            }
                        }
                    }
                    homePageList.add(HomePageList("📅 Recently Added Movies", movieItems))
                }
            }
            "TrendingMovies" -> {
                val homeData = mapper.readValue<HomePageApiResponse>(responseText)
                if (homeData.results.isNotEmpty()) {
                    val trendingMovies = homeData.results.map { movie ->
                        newMovieSearchResponse(
                            name = movie.title ?: movie.name ?: "Unknown",
                            url = "movie,${movie.id}",
                            type = TvType.Movie
                        ) {
                            this.posterUrl = movie.posterPath
                            this.year = (movie.releaseDate ?: movie.firstAirDate)?.substringBefore("-")?.toIntOrNull()
                            this.quality = when {
                                (movie.voteAverage ?: 0.0) >= 8.0 -> SearchQuality.HD
                                (movie.voteAverage ?: 0.0) >= 7.0 -> SearchQuality.HD
                                else -> null
                            }
                        }
                    }
                    homePageList.add(HomePageList("🔥 Trending Movies", trendingMovies))

                    // Group movies by genre
                    val genreGroups = mutableMapOf<Int, MutableList<MovieResult>>()
                    homeData.results.forEach { movie ->
                        movie.genreIds?.forEach { genreId ->
                            genreGroups.getOrPut(genreId) { mutableListOf() }.add(movie)
                        }
                    }

                    // Create lists for top genres (those with most content)
                    genreGroups.entries
                        .sortedByDescending { it.value.size }
                        .take(5)
                        .forEach { (genreId, movies) ->
                            val genreName = movieGenres[genreId] ?: "Other"
                            val genreItems = movies.map { movie ->
                                newMovieSearchResponse(
                                    name = movie.title ?: movie.name ?: "Unknown",
                                    url = "movie,${movie.id}",
                                    type = TvType.Movie
                                ) {
                                    this.posterUrl = movie.posterPath
                                    this.year = (movie.releaseDate ?: movie.firstAirDate)?.substringBefore("-")?.toIntOrNull()
                                    this.quality = when {
                                        (movie.voteAverage ?: 0.0) >= 8.0 -> SearchQuality.HD
                                        (movie.voteAverage ?: 0.0) >= 7.0 -> SearchQuality.HD
                                        else -> null
                                    }
                                }
                            }
                            homePageList.add(HomePageList(genreName, genreItems))
                        }
                }
            }
            "RecentSeries" -> {
                val seriesData = mapper.readValue<SeriesPageApiResponse>(responseText)
                if (seriesData.results.isNotEmpty()) {
                    val seriesItems = seriesData.results.map { series ->
                        newTvSeriesSearchResponse(
                            name = series.name,
                            url = "tvseries,${series.id}",
                            type = TvType.TvSeries
                        ) {
                            this.posterUrl = series.posterPath
                            this.year = series.firstAirDate.substringBefore("-").toIntOrNull()
                            this.quality = when {
                                series.voteAverage >= 8.0 -> SearchQuality.HD
                                series.voteAverage >= 7.0 -> SearchQuality.HD
                                else -> null
                            }
                        }
                    }
                    homePageList.add(HomePageList("📅 Recently Added Series", seriesItems))
                }
            }
            "TrendingSeries" -> {
                val seriesData = mapper.readValue<SeriesPageApiResponse>(responseText)
                if (seriesData.results.isNotEmpty()) {
                    val trendingSeries = seriesData.results.map { series ->
                        newTvSeriesSearchResponse(
                            name = series.name,
                            url = "tvseries,${series.id}",
                            type = TvType.TvSeries
                        ) {
                            this.posterUrl = series.posterPath
                            this.year = series.firstAirDate.substringBefore("-").toIntOrNull()
                            this.quality = when {
                                series.voteAverage >= 8.0 -> SearchQuality.HD
                                series.voteAverage >= 7.0 -> SearchQuality.HD
                                else -> null
                            }
                        }
                    }
                    homePageList.add(HomePageList("🔥 Trending Series", trendingSeries))

                    // Group series by genre
                    val genreGroups = mutableMapOf<Int, MutableList<SeriesResult>>()
                    seriesData.results.forEach { series ->
                        series.genreIds.forEach { genreId ->
                            genreGroups.getOrPut(genreId) { mutableListOf() }.add(series)
                        }
                    }

                    // Create lists for top genres (those with most content)
                    genreGroups.entries
                        .sortedByDescending { it.value.size }
                        .take(5)
                        .forEach { (genreId, seriesList) ->
                            val genreName = seriesGenres[genreId] ?: "Other"
                            val genreItems = seriesList.map { series ->
                                newTvSeriesSearchResponse(
                                    name = series.name,
                                    url = "tvseries,${series.id}",
                                    type = TvType.TvSeries
                                ) {
                                    this.posterUrl = series.posterPath
                                    this.year = series.firstAirDate.substringBefore("-").toIntOrNull()
                                    this.quality = when {
                                        series.voteAverage >= 8.0 -> SearchQuality.HD
                                        series.voteAverage >= 7.0 -> SearchQuality.HD
                                        else -> null
                                    }
                                }
                            }
                            homePageList.add(HomePageList(genreName, genreItems))
                        }
                }
            }
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<com.lagradost.cloudstream3.SearchResponse> {
        val searchResults = mutableListOf<com.lagradost.cloudstream3.SearchResponse>()
        
        // Search movies
        val movieSearchUrl = "$mainUrl/api/3/search/movie?api_key=$apiKey&language=en&page=1&query=$query"
        val movieResponseText = getWithCustomHeaders(movieSearchUrl)
        val movieSearchData = mapper.readValue<MultiSearchResponse>(movieResponseText)
        
        // Search TV shows
        val tvSearchUrl = "$mainUrl/api/3/search/tv?api_key=$apiKey&language=en&page=1&query=$query"
        val tvResponseText = getWithCustomHeaders(tvSearchUrl)
        val tvSearchData = mapper.readValue<MultiSearchResponse>(tvResponseText)

        // Add movie results
        movieSearchData.results.forEach { result ->
            val movieResult = newMovieSearchResponse(
                name = result.title ?: result.name ?: "Unknown",
                url = "movie,${result.id}",
                type = TvType.Movie
            ) {
                this.posterUrl = result.posterPath
                this.year = result.releaseDate?.substringBefore("-")?.toIntOrNull()
            }
            searchResults.add(movieResult)
        }

        // Add TV series results
        tvSearchData.results.forEach { result ->
            val seriesResult = newTvSeriesSearchResponse(
                name = result.name ?: result.title ?: "Unknown",
                url = "tvseries,${result.id}",
                type = TvType.TvSeries
            ) {
                this.posterUrl = result.posterPath
                this.year = result.firstAirDate?.substringBefore("-")?.toIntOrNull()
            }
            searchResults.add(seriesResult)
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
            val parts = if (url.startsWith("https")) {
                val path = url.substringAfter("https://").substringAfter("/")
                path.split(",")
            } else {
                url.split(",")
            }
            val type = parts[0]
            val id = parts[1]
            
            return if (type == "movie") {
                // Fetch movie details
                val detailsUrl = "$mainUrl/api/3/movie/$id?api_key=$apiKey&append_to_response=credits%2Csimilar"
                val responseText = getWithCustomHeaders(detailsUrl)
                val details = mapper.readValue<MovieDetails>(responseText)
                
                newMovieLoadResponse(
                    name = details.title,
                    url = url,
                    type = TvType.Movie,
                    dataUrl = id
                ) {
                    this.posterUrl = details.posterPath
                    this.backgroundPosterUrl = details.backdropPath
                    this.year = details.releaseDate.substringBefore("-").toIntOrNull()
                    this.plot = details.overview
                    this.tags = details.genres.map { it.name }
                    this.duration = details.runtime
                    
                    // Add actors from cast
                    this.actors = details.credits.cast.take(10).map { cast ->
                        ActorData(
                            Actor(cast.name, cast.profilePath),
                            roleString = cast.character
                        )
                    }
                    
                    // Add recommendations from similar movies
                    this.recommendations = details.similar.results.take(10).map { item ->
                        newMovieSearchResponse(
                            name = item.title ?: item.name ?: "Unknown",
                            url = "movie,${item.id}",
                            type = TvType.Movie
                        ) {
                            this.posterUrl = item.posterPath
                            this.year = (item.releaseDate ?: item.firstAirDate)?.substringBefore("-")?.toIntOrNull()
                        }
                    }
                }
            } else {
                // Fetch TV series details
                val detailsUrl = "$mainUrl/api/3/tv/$id?api_key=$apiKey&append_to_response=credits%2Csimilar"
                val responseText = getWithCustomHeaders(detailsUrl)
                val details = mapper.readValue<TvDetails>(responseText)
                
                // Fetch all episodes for all seasons
                val allEpisodes = mutableListOf<Episode>()
                for (season in details.seasons.filter { it.seasonNumber > 0 }) {
                    try {
                        val seasonUrl = "$mainUrl/api/3/tv/$id/season/${season.seasonNumber}?api_key=$apiKey&language=en"
                        val seasonText = getWithCustomHeaders(seasonUrl)
                        val seasonDetails = mapper.readValue<SeasonDetails>(seasonText)
                        
                        seasonDetails.episodes.forEach { ep ->
                            allEpisodes.add(
                                newEpisode("$id|${season.seasonNumber}|${ep.episodeNumber}") {
                                    this.name = ep.name
                                    this.season = season.seasonNumber
                                    this.episode = ep.episodeNumber
                                    this.posterUrl = ep.stillPath
                                    this.description = ep.overview
                                }
                            )
                        }
                    } catch (e: Exception) {
                        // Continue if a season fails
                        continue
                    }
                }
                
                newTvSeriesLoadResponse(
                    name = details.name,
                    url = url,
                    type = TvType.TvSeries,
                    episodes = allEpisodes
                ) {
                    this.posterUrl = details.posterPath
                    this.backgroundPosterUrl = details.backdropPath
                    this.year = details.firstAirDate.substringBefore("-").toIntOrNull()
                    this.plot = details.overview
                    this.tags = details.genres.map { it.name }
                    
                    // Add actors from cast
                    this.actors = details.credits.cast.take(10).map { cast ->
                        ActorData(
                            Actor(cast.name, cast.profilePath),
                            roleString = cast.character
                        )
                    }
                    
                    // Add recommendations from similar TV shows
                    this.recommendations = details.similar?.results?.take(10)?.map { item ->
                        newTvSeriesSearchResponse(
                            name = item.name ?: item.title ?: "Unknown",
                            url = "tvseries,${item.id}",
                            type = TvType.TvSeries
                        ) {
                            this.posterUrl = item.posterPath
                            this.year = (item.firstAirDate ?: item.releaseDate)?.substringBefore("-")?.toIntOrNull()
                        }
                    }
                }
            }
       
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
       
            // Data format for movies: id
            // Data format for episodes: id|seasonNumber|episodeNumber
            val parts = data.split("|")
            
            if (parts.size == 1) {
                // Movie links
                val movieId = parts[0]
                val linksUrl = "$mainUrl/api/3/movie/$movieId/links?api_key=$apiKey"
                val responseText = getWithCustomHeaders(linksUrl)
                val linksResponse = mapper.readValue<MovieLinksResponse>(responseText)
                
                linksResponse.links.forEach { link ->
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "${link.host} - ${link.quality}",
                            link.url,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://molop.art/"
                            this.quality = getQualityValue(link.quality)
                        }
                    )
                }
                
                return linksResponse.links.isNotEmpty()
            } else if (parts.size == 3) {
                // TV episode links
                val tvId = parts[0].substringAfterLast("/")
                val seasonNum = parts[1]
                val episodeNum = parts[2]
                
                val linksUrl = "$mainUrl/api/3/tv/$tvId/season/$seasonNum/episode/$episodeNum/links?api_key=$apiKey"
                val responseText = getWithCustomHeaders(linksUrl)
                val linksResponse = mapper.readValue<EpisodeLinksResponse>(responseText)
                
                linksResponse.results.forEach { link ->
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "${link.host} - ${link.quality}",
                            link.url,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://molop.art/"
                            this.quality = getQualityValue(link.quality)
                        }
                    )
                }
                
                return linksResponse.results.isNotEmpty()
            }
            
            return false
    }
}
