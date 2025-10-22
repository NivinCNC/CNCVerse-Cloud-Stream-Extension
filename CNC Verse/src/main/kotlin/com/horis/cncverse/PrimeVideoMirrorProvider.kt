package com.horis.cncverse

import com.horis.cncverse.entities.EpisodesData
import com.horis.cncverse.entities.PlayList
import com.horis.cncverse.entities.PostData
import com.horis.cncverse.entities.SearchData
import com.horis.cncverse.entities.MainPage
import com.horis.cncverse.entities.PostCategory
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.getQualityFromName
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.APIHolder.unixTime

class PrimeVideoMirrorProvider : MainAPI() {
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override var lang = "ta"

    override var mainUrl = "https://net2025.cc"
    override var name = "PrimeVideo"

    override val hasMainPage = true
    private var cookie_value = ""
    private val headers = mapOf(
        "X-Requested-With" to "XMLHttpRequest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "ott" to "pv",
            "hd" to "on"
        )
        val data = app.get(
            "$mainUrl/tv/pv/homepage.php",
            cookies = cookies,
            referer = "$mainUrl/tv/home",
        ).parsed<MainPage>()

        val items = data.post.map {
            it.toHomePageList()
        }

        return newHomePageResponse(items, false)
    }

    private fun PostCategory.toHomePageList(): HomePageList {
        val name = cate
        val items = ids.split(",").mapNotNull {
            toSearchResult(it)
        }
        return HomePageList(
            name,
            items,
            isHorizontalImages = true
        )
    }

    private fun toSearchResult(id: String): SearchResponse? {
        return newAnimeSearchResponse("", Id(id).toJson()) {
            this.posterUrl = "https://imgcdn.kim/pv/341/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/tv/home")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "ott" to "pv",
            "hd" to "on"
        )
        val url = "$mainUrl/pv/search.php?s=$query&t=${APIHolder.unixTime}"
        val data = app.get(url, referer = "$mainUrl/tv/home", cookies = cookies).parsed<SearchData>()

        return data.searchResult.map {
            newAnimeSearchResponse(it.t, Id(it.id).toJson()) {
                posterUrl = "https://imgcdn.kim/pv/341/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/tv/home")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = parseJson<Id>(url).id
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "ott" to "pv",
            "hd" to "on"
        )
        val data = app.get(
            "$mainUrl/pv/post.php?id=$id&t=${APIHolder.unixTime}",
            headers,
            referer = "$mainUrl/tv/home",
            cookies = cookies
        ).parsed<PostData>()

        val episodes = arrayListOf<Episode>()

        val title = data.title
        val castList = data.cast?.split(",")?.map { it.trim() } ?: emptyList()
        val cast = castList.map {
            ActorData(
                Actor(it),
            )
        }
        val genre = listOf(data.ua.toString()) + (data.genre?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList())
        val rating = data.match?.replace("IMDb ", "")?.toRatingInt()
        val runTime = convertRuntimeToMinutes(data.runtime.toString())

        if (data.episodes.first() == null) {
            episodes.add(newEpisode(LoadData(title, id)) {
                name = data.title
            })
        } else {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://img.nfmirrorcdn.top/pvepimg/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }

            if (data.nextPageShow == 1) {
                episodes.addAll(getEpisodes(title, url, data.nextPageSeason!!, 2))
            }

            data.season?.dropLast(1)?.amap {
                episodes.addAll(getEpisodes(title, url, it.id, 1))
            }
        }

        val type = if (data.episodes.first() == null) TvType.Movie else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = "https://imgcdn.kim/pv/341/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/tv/home")
            plot = data.desc
            year = data.year.toIntOrNull()
            tags = genre
            actors = cast
            this.rating = rating
            this.duration = runTime
        }
    }

    private suspend fun getEpisodes(
        title: String, eid: String, sid: String, page: Int
    ): List<Episode> {
        val episodes = arrayListOf<Episode>()
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "ott" to "pv",
            "hd" to "on"
        )
        var pg = page
        while (true) {
            val data = app.get(
                "$mainUrl/pv/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg",
                headers,
                referer = "$mainUrl/tv/home",
                cookies = cookies
            ).parsed<EpisodesData>()
            data.episodes?.mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://img.nfmirrorcdn.top/pvepimg/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }
            if (data.nextPageShow == 0) break
            pg++
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (title, id) = parseJson<LoadData>(data)
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "ott" to "pv",
            "hd" to "on"
        )
        val playlist = app.get(
            "$mainUrl/tv/pv/playlist.php?id=$id&t=$title&tm=${APIHolder.unixTime}",
            headers,
            referer = "$mainUrl/tv/home",
            cookies = cookies
        ).parsed<PlayList>()

        playlist.forEach { item ->
            item.sources.forEach {
                callback.invoke(
                    newExtractorLink(
                        name,
                        it.label,
                        "https://net51.cc${it.file.replace("/tv/", "/")}",
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://net51.cc/"
                        this.quality = getQualityFromName(it.file.substringAfter("q=", ""))
                    }
                )
            }

            item.tracks?.filter { it.kind == "captions" }?.map { track ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        track.label.toString(),
                        httpsify(track.file.toString())
                    )
                )
            }
        }

        return true
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                if (request.url.toString().contains(".m3u8")) {
                    val newRequest = request.newBuilder()
                        .header("Cookie", "hd=on")
                        .build()
                    return chain.proceed(newRequest)
                }
                return chain.proceed(request)
            }
        }
    }

    data class Id(
        val id: String
    )

    data class LoadData(
        val title: String, val id: String
    )

    data class Cookie(
        val cookie: String
    )
}