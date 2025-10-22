package com.horis.cncverse

import com.horis.cncverse.entities.EpisodesData
import com.horis.cncverse.entities.PlayList
import com.horis.cncverse.entities.PostData
import com.horis.cncverse.entities.SearchData
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

class DisneyPlusProvider : MainAPI() {
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override var lang = "ta"

    override var mainUrl = "https://net2025.cc"
    override var name = "Disney Plus"

    override val hasMainPage = true
    private var cookie_value = ""
    private val headers = mapOf(
        "X-Requested-With" to "XMLHttpRequest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "ott" to "dp",
            "hd" to "on"
        )
        val document = app.get(
            "$mainUrl/mobile/home",
            cookies = cookies,
            referer = "$mainUrl/tv/home",
        ).document
        val items = document.select(".tray-container, #top10").map {
            it.toHomePageList()
        }
        return newHomePageResponse(items, false)
    }

    private fun Element.toHomePageList(): HomePageList {
        val name = select("h2, span").text()
        val items = select("article, .top10-post").mapNotNull {
            it.toSearchResult()
        }
        return HomePageList(name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val id = selectFirst("a")?.attr("data-post") ?: attr("data-post") ?: return null
        val posterUrl =
            fixUrlNull(selectFirst(".card-img-container img, .top10-img img")?.attr("data-src"))

        return newAnimeSearchResponse("", Id(id).toJson()) {
            this.posterUrl = posterUrl
            posterHeaders = mapOf("Referer" to "$mainUrl/tv/home")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "dp"
        )
        val url = "$mainUrl/mobile/hs/search.php?s=$query&t=${APIHolder.unixTime}"
        val data = app.get(url, referer = "$mainUrl/tv/home", cookies = cookies).parsed<SearchData>()

        return data.searchResult.map {
            newAnimeSearchResponse(it.t, Id(it.id).toJson()) {
                posterUrl = "https://imgcdn.kim/hs/v/166/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/tv/home")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val id = parseJson<Id>(url).id
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "dp"
        )
        val data = app.get(
            "$mainUrl/mobile/hs/post.php?id=$id&t=${APIHolder.unixTime}",
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
                    this.name = it.t
                    this.episode = it.ep.replace("E", "").toIntOrNull()
                    this.season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/hsepimg/${it.id}.jpg"
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
            posterUrl = "https://imgcdn.kim/hs/v/166/$id.jpg"
            backgroundPosterUrl ="https://imgcdn.kim/hs/h/166/$id.jpg"
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
            "hd" to "on",
            "ott" to "dp"
        )
        var pg = page
        while (true) {
            val data = app.get(
                "$mainUrl/mobile/hs/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg",
                headers,
                referer = "$mainUrl/tv/home",
                cookies = cookies
            ).parsed<EpisodesData>()
            data.episodes?.mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/hsepimg/${it.id}.jpg"
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
            "hd" to "on",
            "ott" to "dp"
        )
        val playlist = app.get(
            "$mainUrl/mobile/hs/playlist.php?id=$id&t=$title&tm=${APIHolder.unixTime}",
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
                        fixUrl(it.file),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/tv/home"
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
}