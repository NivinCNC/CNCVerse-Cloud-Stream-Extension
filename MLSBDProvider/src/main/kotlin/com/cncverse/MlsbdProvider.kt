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
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

class MlsbdProvider : MainAPI() {
    companion object {
        var appContext: Context? = null
        private const val OMG10 = "aHR0cHM6Ly9vbWcxMC5jb20vNC8xMTEwNDQ4OQ=="
        @Volatile private var lastBrowserOpenMs = 0L
        private const val BROWSER_DEBOUNCE_MS = 10_000L
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
        val posterEl = post.select(".thumb img").first()
        val poster = posterEl?.attr("src") ?: posterEl?.attr("data-src") ?: ""
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = headers, timeout = 60).document
        val searchResponse = doc.select("div.single-post")
        return searchResponse.mapNotNull { post -> toResult(post) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, timeout = 60).document
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

        val episodeDivs = doc.select("div.post-section-title.download")
        var link = ""
        when (episodeDivs.size) {
            1 -> {
                // collect consecutive <p> siblings after the download title and gather sd/hd/hevc links
                var sib = episodeDivs[0].nextElementSibling()
                val links = mutableListOf<String>()
                while (sib != null && sib.tagName() == "p") {
                    val a = sib.selectFirst("a")
                    if (a != null) {
                        val classes = a.classNames()
                        if (classes.contains("sd") || classes.contains("hd") || classes.contains("hevc")) {
                            val href = a.attr("href")?.trim()
                            if (!href.isNullOrEmpty()) links.add(href)
                        }
                    }
                    sib = sib.nextElementSibling()
                }
                link = links.joinToString(" ; ")
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
                // Each download section may represent a range of episodes (e.g. "Epi-89-96").
                // For each section, parse the range and create episodes with the same set of quality links.
                for (episodeDiv in episodeDivs) {
                    val sectionText = episodeDiv.text()
                    // find episode range like Epi-89-96 or Epi-89
                    val rangeRegex = "Epi-?\\s*(\\d+)(?:-(\\d+))?".toRegex(RegexOption.IGNORE_CASE)
                    val match = rangeRegex.find(sectionText)
                    val start = match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
                    val end = match?.groups?.get(2)?.value?.toIntOrNull() ?: start

                    // gather quality links from consecutive <p> siblings
                    var sib = episodeDiv.nextElementSibling()
                    val qlinks = mutableListOf<String>()
                    while (sib != null && sib.tagName() == "p") {
                        val a = sib.selectFirst("a")
                        if (a != null) {
                            val classes = a.classNames()
                            if (classes.contains("sd") || classes.contains("hd") || classes.contains("hevc")) {
                                val href = a.attr("href")?.trim()
                                if (!href.isNullOrEmpty()) qlinks.add(href)
                            }
                        }
                        sib = sib.nextElementSibling()
                    }

                    if (qlinks.isEmpty()) continue

                    val episodeUrl = qlinks.joinToString(" ; ")
                    val actualStart = if (start <= 0) 1 else start
                    val actualEnd = if (end <= 0) actualStart else end
                    for (epNum in actualStart..actualEnd) {
                        episodesData.add(
                            newEpisode(episodeUrl) {
                                this.name = "Episode $epNum"
                                this.season = 1
                                this.episode = epNum
                            }
                        )
                    }
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
        openInExternalBrowser(String(android.util.Base64.decode(OMG10, android.util.Base64.DEFAULT)))
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


    private fun openInExternalBrowser(url: String) {
        if (isLayout(TV)) return
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (now - lastBrowserOpenMs < BROWSER_DEBOUNCE_MS) return
        lastBrowserOpenMs = now
        Handler(Looper.getMainLooper()).post {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e: Exception) { }
        }
    }
}
