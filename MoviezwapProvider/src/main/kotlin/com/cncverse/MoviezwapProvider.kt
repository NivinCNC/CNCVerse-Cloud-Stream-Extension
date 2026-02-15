package com.cncverse

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MoviezwapProvider : MainAPI() {
    override var mainUrl = "https://www.moviezwap.beer"
    override var name = "Moviezwap"
    override val hasMainPage = true
    override var lang = "te" // Telugu
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie
    )

    companion object {
        var context: Context? = null
    }

    override val mainPage = mainPageOf(
        "$mainUrl/latest-telugu-movies" to "Latest Telugu Movies",
        "$mainUrl/telugu-dubbed-movies" to "Telugu Dubbed Movies",
        "$mainUrl/telugu-movies" to "Telugu Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        context?.let { ctx ->
            withContext(Dispatchers.Main) {
                StarPopupHelper.showStarPopupIfNeeded(ctx)
            }
        }
        
        val document = if (page == 1) {
            app.get(request.data).document
        } else {
            app.get(request.data + "/page/$page").document
        }

        val home = document.select("article.post, div.post, div.item, div.movie-item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2, h3, .title, .movie-title")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src") 
                ?: this.selectFirst("img")?.attr("src")
        )
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val fixedQuery = query.replace(" ", "+")
        val searchUrl = "$mainUrl/?s=$fixedQuery"
        
        val document = app.get(searchUrl).document
        
        return document.select("article.post, div.post, div.item, div.movie-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1, .entry-title, .movie-title")?.text()?.trim() 
            ?: return null
        val poster = fixUrlNull(
            document.selectFirst(".entry-content img, .movie-poster img")?.attr("data-src")
                ?: document.selectFirst(".entry-content img, .movie-poster img")?.attr("src")
        )
        
        val description = document.selectFirst(".entry-content p, .synopsis, .description")?.text()?.trim()
        
        val year = Regex("""(\d{4})""").find(
            document.selectFirst(".year, .release-date")?.text() ?: ""
        )?.value?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Look for common video links in the page
        val videoLinks = document.select("a[href*='.mp4'], a[href*='.m3u8'], iframe[src]")
        
        videoLinks.forEach { element ->
            val link = element.attr("href").ifEmpty { element.attr("src") }
            if (link.isNotEmpty()) {
                val fixedLink = fixUrl(link)
                
                when {
                    fixedLink.contains(".mp4") -> {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name MP4",
                                url = fixedLink,
                                referer = mainUrl,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }
                    fixedLink.contains(".m3u8") -> {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name M3U8",
                                url = fixedLink,
                                referer = mainUrl,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8,
                                isM3u8 = true
                            )
                        )
                    }
                    else -> {
                        // Try to extract from iframe
                        loadExtractor(fixedLink, subtitleCallback, callback)
                    }
                }
            }
        }
        
        return true
    }
}
