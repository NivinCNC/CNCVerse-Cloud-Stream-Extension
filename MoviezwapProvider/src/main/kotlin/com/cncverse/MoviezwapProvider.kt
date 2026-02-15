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
    // Using CORS proxy to bypass geo-blocks
    override var mainUrl = "https://www.moviezwap.surf"
    override var name = "Moviezwap"
    override val hasMainPage = true
    override var lang = "te" // Telugu
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        var context: Context? = null
    }

    // Main page categories based on actual Moviezwap URL structure
    override val mainPage = mainPageOf(
        "$mainUrl/category/Telugu-(2026)-Movies.html" to "Telugu (2026) Movies",
        "$mainUrl/category/Telugu-(2025)-Movies.html" to "Telugu (2025) Movies",
        "$mainUrl/category/Tamil-(2026)-Movies.html" to "Tamil (2026) Movies",
        "$mainUrl/category/Tamil-(2025)-Movies.html" to "Tamil (2025) Movies",
        "$mainUrl/category/Telugu-Dubbed-Movies-[Hollywood].html" to "Telugu Dubbed Hollywood",
        "$mainUrl/category/HOT-Web-Series.html" to "HOT Web Series"
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
            val url = if (page == 1) {
                request.data
            } else {
                // Moviezwap pagination: /category/Name.html -> /category/Name/2.html
                request.data.removeSuffix(".html") + "/$page.html"
            }
            app.get(url).document
        } catch (e: Exception) {
            Log.e("MoviezwapProvider", "Error fetching main page: ${e.message}")
            return newHomePageResponse(arrayListOf(HomePageList(request.name, emptyList())), hasNext = false)
        }

        // Moviezwap uses simple structure - links in the content area
        val home = document.select("a[href*='/movie/']").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href")
        if (!href.contains("/movie/")) return null
        
        // Extract title from link text or href
        val title = this.text().trim().ifEmpty {
            // Extract from URL if link text is empty
            href.substringAfterLast("/")
                .removeSuffix(".html")
                .replace("-", " ")
                .replace("(", " (")
        }
        
        if (title.isBlank()) return null
        
        // Determine if it's a series based on keywords
        val isSeries = title.contains(Regex("(?i)(season|episodes?|eps|all episodes|web series)"))
        
        // Poster images are only available on detail pages, not in listings
        return if (isSeries) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries)
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val fixedQuery = query.replace(" ", "+")
        val searchUrl = "$mainUrl/search.php?q=$fixedQuery"
        
        val document = try {
            app.get(searchUrl).document
        } catch (e: Exception) {
            Log.e("MoviezwapProvider", "Error during search: ${e.message}")
            return emptyList()
        }
        
        return document.select("a[href*='/movie/']").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e("MoviezwapProvider", "Error loading movie: ${e.message}")
            return null
        }
        
        // Moviezwap title is usually in h2 heading  
        val title = document.selectFirst("h2")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore("-")?.trim()
            ?: return null
        
        // Poster image - Moviezwap pattern
        val poster = fixUrlNull(
            document.selectFirst("img[src*='/poster/']")?.attr("src")
        )
        
        // Description/plot from the page
        val description = document.select("td:contains(Desc/Plot) + td").text().trim().ifEmpty {
            document.selectFirst("p")?.text()?.trim()
        }
        
        // Extract year from category or release date
        val yearText = document.select("td:contains(Release Date) + td").text()
            .ifEmpty { document.select("td:contains(Category) + td").text() }
        val year = Regex("""(\d{4})""").find(yearText)?.value?.toIntOrNull()

        // Check if this is a series by looking for season/episode links
        val isSeries = title.contains(Regex("(?i)(season|episodes?|eps|all episodes|web series)"))
        val seasonLinks = document.select("div.catList a[href*='/movie/']")
        
        return if (isSeries && seasonLinks.isNotEmpty()) {
            // This is a series with multiple seasons/episodes
            val episodes = seasonLinks.mapNotNull { element ->
                val episodeTitle = element.text().trim()
                val episodeUrl = fixUrl(element.attr("href"))
                
                // Try to extract season and episode numbers
                val seasonMatch = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(episodeTitle)
                val episodeMatch = Regex("""Eps?\s*\(?(\d+)(?:\s*to\s*(\d+))?\)?""", RegexOption.IGNORE_CASE).find(episodeTitle)
                
                val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epStart = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                
                // If episodes are merged (e.g., "Eps 01 to 08"), create single entry
                // Otherwise it would create 8 entries all pointing to the same merged file
                newEpisode(episodeUrl) {
                    this.name = episodeTitle
                    this.season = season
                    this.episode = epStart
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
            }
        } else {
            // Regular movie
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
            }
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
        
        // Moviezwap uses download links with pattern: download.php?file=xxxxx
        val downloadLinks = document.select("a[href*='dwload.php']").map { 
            it.attr("href", it.attr("href").replace("dwload.php", "download.php"))
            it
        }

        
        var foundLinks = false
        
        downloadLinks.forEach { linkElement ->
            val downloadPageUrl = fixUrl(linkElement.attr("href"))
            val linkText = linkElement.text().trim()
            
            // Extract quality from link text (e.g., "320p", "480p", "720p", "1080p")
            val quality = when {
                linkText.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
                linkText.contains("720p", ignoreCase = true) -> Qualities.P720.value
                linkText.contains("480p", ignoreCase = true) -> Qualities.P480.value
                linkText.contains("360p", ignoreCase = true) -> Qualities.P360.value
                linkText.contains("320p", ignoreCase = true) -> 320
                else -> Qualities.Unknown.value
            }
            
            // Fetch the download.php page to get the actual download link
            val actualDownloadUrl = try {
                val downloadPage = app.get(downloadPageUrl).document
                // Look for "Fast Download Server" link
                downloadPage.selectFirst("a:contains(Fast Download Server)")?.attr("href")
                    ?: downloadPageUrl
            } catch (e: Exception) {
                Log.e("MoviezwapProvider", "Error fetching download page: ${e.message}")
                downloadPageUrl
            }
            
            callback.invoke(
                newExtractorLink(
                    name,
                    "$name - $linkText",
                    actualDownloadUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = mainUrl
                }
            )
            foundLinks = true
        }
        
        return foundLinks
    }
}
