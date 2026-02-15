package com.cncverse

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import org.jsoup.nodes.Element
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MoviezwapProvider : MainAPI() {
    // Updated domain as per user request
    override var mainUrl = "https://www.moviezwap.surf"
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

    // Common category URL patterns for Telugu movie sites
    // These may need adjustment based on actual site structure
    override val mainPage = mainPageOf(
        "$mainUrl/category/telugu-movies" to "Telugu Movies",
        "$mainUrl/category/telugu-dubbed" to "Telugu Dubbed",
        "$mainUrl/category/latest-movies" to "Latest Movies",
        "$mainUrl/category/tamil-movies" to "Tamil Movies"
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
        
        val document = try {
            if (page == 1) {
                app.get(request.data).document
            } else {
                // Support common pagination patterns
                app.get(request.data + "/page/$page").document
            }
        } catch (e: Exception) {
            Log.e("MoviezwapProvider", "Error fetching main page: ${e.message}")
            return newHomePageResponse(arrayListOf(HomePageList(request.name, emptyList())), hasNext = false)
        }

        // Comprehensive selectors for various WordPress/custom themes
        val home = document.select(
            "article.post, article.type-post, article[class*='post'], " +
            "div.post, div.post-item, div.item, div.movie-item, div.movie, " +
            "div.video-block, div.video-item, div[class*='movie'], " +
            "li.post, li.movie-item, li[class*='movie']"
        ).mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Try multiple common title selectors
        val title = this.selectFirst(
            "h2, h3, h2.entry-title, h3.entry-title, h2.post-title, h3.post-title, " +
            ".title, .movie-title, .post-title, .entry-title, " +
            "a.title, .entry-title a, h2 a, h3 a"
        )?.text()?.trim() ?: return null
        
        // Find the main link - try different patterns
        val href = this.selectFirst("a")?.attr("href") 
            ?: this.selectFirst("h2 a, h3 a, .title a, .entry-title a")?.attr("href")
            ?: return null
        
        // Try multiple image selectors (including lazy loading attributes)
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src") 
                ?: this.selectFirst("img")?.attr("data-lazy-src")
                ?: this.selectFirst("img")?.attr("data-original")
                ?: this.selectFirst("img")?.attr("src")
        )
        
        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val fixedQuery = query.replace(" ", "+")
        val searchUrl = "$mainUrl/?s=$fixedQuery"
        
        val document = try {
            app.get(searchUrl).document
        } catch (e: Exception) {
            Log.e("MoviezwapProvider", "Error during search: ${e.message}")
            return emptyList()
        }
        
        return document.select(
            "article.post, article.type-post, article[class*='post'], " +
            "div.post, div.post-item, div.item, div.movie-item, div.movie, " +
            "div.video-block, div.video-item, div[class*='movie'], " +
            "li.post, li.movie-item, li[class*='movie']"
        ).mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e("MoviezwapProvider", "Error loading movie: ${e.message}")
            return null
        }
        
        // Try multiple title selectors
        val title = document.selectFirst(
            "h1, h1.entry-title, h1.post-title, h1.movie-title, " +
            ".entry-title, .movie-title, .post-title, .film-title"
        )?.text()?.trim() ?: return null
        
        // Try multiple poster selectors
        val poster = fixUrlNull(
            document.selectFirst(".entry-content img, .movie-poster img, .wp-post-image, .film-poster img")?.attr("data-src")
                ?: document.selectFirst(".entry-content img, .movie-poster img, .wp-post-image, .film-poster img")?.attr("data-lazy-src")
                ?: document.selectFirst(".entry-content img, .movie-poster img, .wp-post-image, .film-poster img")?.attr("src")
        )
        
        val description = document.selectFirst(
            ".entry-content p, .synopsis, .description, .movie-description, p.storyline, .plot"
        )?.text()?.trim()
        
        // Look for year in various places
        val yearText = document.selectFirst(".year, .release-date, .date, time, .release")?.text() 
            ?: document.selectFirst("time")?.attr("datetime")
            ?: ""
        val year = Regex("""(\d{4})""").find(yearText)?.value?.toIntOrNull()

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
        val document = try {
            app.get(data).document
        } catch (e: Exception) {
            Log.e("MoviezwapProvider", "Error loading links: ${e.message}")
            return false
        }
        
        // Look for common video links and iframes in the page
        val videoLinks = document.select(
            "a[href*='.mp4'], a[href*='.m3u8'], " +
            "iframe[src], iframe[data-src], " +
            "video source[src], video[src], " +
            "div.player iframe, div[class*='player'] iframe, " +
            ".video-player iframe, .embed-responsive iframe"
        )
        
        var foundLinks = false
        
        videoLinks.forEach { element ->
            val link = element.attr("href").ifEmpty { 
                element.attr("src").ifEmpty { 
                    element.attr("data-src") 
                } 
            }
            
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
                        foundLinks = true
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
                        foundLinks = true
                    }
                    else -> {
                        // Try to extract from iframe using CloudStream's built-in extractors
                        safeApiCall {
                            loadExtractor(fixedLink, subtitleCallback, callback)
                            foundLinks = true
                        }
                    }
                }
            }
        }
        
        return foundLinks
    }
}
