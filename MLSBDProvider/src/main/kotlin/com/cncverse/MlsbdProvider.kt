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
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import android.content.Context

class MlsbdProvider : MainAPI() {
    companion object {
        var appContext: Context? = null
    }

    override var mainUrl = "https://mlsbd.co"
    override var name = "Mlsbd"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val mainPage = mainPageOf(
        "" to "Latest Movies",
        "/category/bangla-dubbed/page/" to "Bangla Dubbed",
        "/category/dual-audio-movies/page/" to "Multi Audio Movies",
        "/category/tv-series/page/" to "TV Series",
        "/category/bollywood-movies/page/" to "Bollywood Movies",
        "/category/bangla-movies/page/" to "Bengali Movies",
        "/category/hollywood-movies/page/" to "Hollywood Movies"
    )
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.NSFW,
        TvType.AsianDrama,
        TvType.AnimeMovie,
    )
    private val headers = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        appContext?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        val url = if (request.data == "") mainUrl
        else "$mainUrl${request.data}$page/"
        val doc = app.get(url, cacheTime = 1440, allowRedirects = true, timeout = 60, headers = headers).document
        val homeResponse = doc.select("div.single-post")
        val home = homeResponse.mapNotNull { post -> toResult(post) }
        return newHomePageResponse(HomePageList(request.name, home, isHorizontalImages = true), true)
    }

    private fun toResult(post: Element): SearchResponse {
        val title = post.select(".post-title").text()
        val url = post.select(".thumb > a").attr("href")
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = post.select(".thumb>a>picture>img:nth-child(3)").attr("src")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        SmartlinkHelper.ping(appContext)
        val doc = app.get("$mainUrl/?s=$query", headers = headers).document
        val searchResponse = doc.select("div.single-post")
        return searchResponse.mapNotNull { post -> toResult(post) }
    }

    override suspend fun load(url: String): LoadResponse {
        SmartlinkHelper.ping(appContext)
        val doc = app.get(url).document
        val title = doc.select(".name").text()
        val year = "(?<=\\()\\d{4}(?=\\))".toRegex().find(title)?.value?.toIntOrNull()
        val image = doc.select("img.aligncenter").attr("src")
        doc.select("br").append("\\n")
        val plot = doc.select(".single-post-title").text() + "\n" +
                doc.select(".misc").text() + "\n" +
                doc.select(".details").text().replace("\\n ", "\n") + "\n" +
                doc.select(".storyline").text() + "\n" +
                doc.select(".production").text().replace("\\n ", "\n") + "\n" +
                doc.select(".media").text().replace("\\n ", "\n")

        val episodeDivs = doc.select("div.post-section-title.download").reversed()
        var link = ""
        when (episodeDivs.size) {
            1 -> {
                episodeDivs[0].nextElementSibling()?.nextElementSibling()
                    ?.select("a.Dbtn.hd, a.Dbtn.sd, a.Dbtn.hevc")
                    ?.forEach { link += it.attr("href") + " ; " }
                return newMovieLoadResponse(title, url, TvType.Movie, link) {
                    this.posterUrl = image
                    this.year = year
                    this.plot = plot
                }
            }
            0 -> return newMovieLoadResponse(title, url, TvType.Movie, "") {
                this.posterUrl = image
                this.year = year
                this.plot = plot
            }
            else -> {
                val episodesData = mutableListOf<Episode>()
                for (episodeDiv in episodeDivs) {
                    var episodeUrl = ""
                    var episodeNum = 0
                    var downloadLink = episodeDiv.nextElementSibling()?.nextElementSibling()
                    //480p
                    episodeUrl += downloadLink?.selectFirst("a")?.attr("href") + " ; "
                    //720p
                    downloadLink = downloadLink?.nextElementSibling()
                    episodeUrl += downloadLink?.selectFirst("a")?.attr("href") + " ; "
                    //1080p
                    downloadLink = downloadLink?.nextElementSibling()
                    episodeUrl += downloadLink?.selectFirst("a")?.attr("href")
                    episodeNum++
                    episodesData.add(
                        newEpisode(episodeUrl) {
                            this.name = "Episode $episodeNum"
                            this.season = 1
                            this.episode = episodeNum
                        }
                    )
                }
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                    this.posterUrl = image
                    this.year = year
                    this.plot = plot
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
        data.split(" ; ").forEach { link ->
            val trimmed = link.trim()
            if (trimmed.isEmpty()) return@forEach
            if (trimmed.contains("savelinks")) {
                val doc = app.get(trimmed).document
                // Links are inside <ul><li><a href="..."> on savelinks.me
                doc.select("ul li a[href^=http]").forEach {
                    val url = it.attr("href").trim()
                    if (url.isNotEmpty()) {
                        loadExtractor(url, trimmed, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }
}
