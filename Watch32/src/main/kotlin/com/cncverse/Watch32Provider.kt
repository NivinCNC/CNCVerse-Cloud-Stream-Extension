package com.cncverse


import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.loadExtractor
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Watch32Provider : MainAPI() {

    companion object {
        var context: Context? = null
    }

    override var mainUrl = "https://watch32.sx"
    override var name = "Watch32"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = true



    override val mainPage = mainPageOf(
        "movie" to "Popular Movies",
        "tv-show" to "Popular TV Shows",
        "genre/animation" to "Animations",
        "country/IN" to "India",
        "country/FR" to "France"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest
    ): HomePageResponse {
        context?.let { ctx ->
            withContext(Dispatchers.Main) {
                StarPopupHelper.showStarPopupIfNeeded(ctx)
            }
        }
        val doc = app.get("$mainUrl/${request.data}?page=$page", cacheTime = 60, timeout = 20).document
        val home = doc.select(".film_list-wrap .flw-item").mapNotNull { toResult(it) }

        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = true
        )
    }


    private fun toResult(post: Element): SearchResponse {
        val title = post.selectFirst("a")?.attr("title") ?: ""
        val url = mainUrl + "/" + post.selectFirst("a")?.attr("href")

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = post.selectFirst("img")
                ?.attr("data-src")

        }
    }
    private fun toSearchResult(post: Element): SearchResponse {
        val title = post.selectFirst("h3")?.text() ?: ""
        val url = mainUrl + "/" + post.selectFirst("a")?.attr("href")

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = post.selectFirst("img")
                ?.attr("src")

        }
    }


    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {

        val doc = app.post(
            "$mainUrl/ajax/search",
            data = mapOf("keyword" to query),
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded",
                "x-requested-with" to "XMLHttpRequest"
            )
        ).document

        return doc.select("a.nav-item:has(div)").mapNotNull { toSearchResult(it) }
    }


    override suspend fun load(url: String): LoadResponse {


        val doc = app.get(url, cacheTime = 60).document
        val title = doc.selectFirst(".heading-name")?.text()
            ?: throw NotImplementedError("Unable to find title")

        val image = doc.selectFirst(".film-poster-img")?.attr("src")
        val regex = """url\((.*?)\);""".toRegex() // regex for image url inside style attr
        val matchResult = regex.find(doc.select(".cover_follow").attr("style"))
        var coverImage = matchResult?.groups?.get(1)?.value
        if (coverImage == "")
            coverImage = image
        val synopsis = doc.selectFirst(".description")?.text() ?: ""

        val rowLines = doc.select(".row-line").map { it.text() }

        val releasedYear = rowLines.getOrNull(0)
            ?.substringAfter(":", "")
            ?.substringBefore("-")
            ?.trim()

        val genres = rowLines.getOrNull(1)
            ?.substringAfter(":", "")
            ?.split(",")
            ?.map { it.trim() }
            .orEmpty()

        val duration = rowLines.getOrNull(3)
            ?.substringAfter(":", "")?.trim()
            ?.substringBefore(" ")
            ?.trim()

        val type = if (url.contains("/movie/")) TvType.Movie else TvType.TvSeries

        var movieUrlData = ""
        val episodes = mutableListOf<Episode>()



        var web = app.get(url, cacheTime = 60, timeout = 30).document
        val dataId = web.selectFirst(".detail_page-watch")?.attr("data-id")


        if (type == TvType.TvSeries) {
            web = app.get("$mainUrl/ajax/season/list/$dataId", cacheTime = 60, timeout = 30).document
            for ((numSeason, season) in web.select("a").withIndex()) {
                val seasonId = season.attr("data-id")
                web = app.get("$mainUrl/ajax/season/episodes/$seasonId", cacheTime = 60, timeout = 30).document

                var numEpi = 0
                episodes += web.select(".nav-item").map {
                    newEpisode(
                        data = "$mainUrl/ajax/episode/servers/${it.select("a").attr("data-id")}") {
                        name = it.text().split(":")[1]
                        this.season = numSeason+1
                        episode = ++numEpi
                        posterUrl = coverImage
                    }
                }.toMutableList()
            }
        }

        else
            movieUrlData = "$mainUrl/ajax/episode/list/$dataId"


        return if (type == TvType.Movie )
            newMovieLoadResponse(title,url,TvType.Movie, movieUrlData) {
                this.backgroundPosterUrl = coverImage
                this.posterUrl = image
                this.plot = synopsis
                this.tags = genres
                this.year = releasedYear?.toIntOrNull()
                this.duration = duration?.toIntOrNull()

            }
        else
            newTvSeriesLoadResponse(title,url,TvType.TvSeries, episodes) {
                this.backgroundPosterUrl = coverImage
                this.posterUrl = image
                this.plot = synopsis

                this.tags = genres
                this.year = releasedYear?.toIntOrNull()
                this.duration = duration?.toIntOrNull()
            }



    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val web = app.get(data).document
        val vidDataIds = web.select(".nav-item a")

        for (vidDataId in vidDataIds.reversed()) {

            val vidId = vidDataId.attr("data-id") // example :10914034
            val www = app.get("$mainUrl/ajax/episode/sources/$vidId", cacheTime = 60, timeout = 30)
            val link = JSONObject(www.text).getString("link")
            loadExtractor(link.toString(), subtitleCallback, callback)
            
        }
        return true
    }

}