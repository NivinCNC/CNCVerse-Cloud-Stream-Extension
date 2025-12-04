package com.cncverse

//import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import okhttp3.FormBody
import org.jsoup.Jsoup
//import java.time.LocalDate
//import java.time.format.DateTimeFormatter

class TamilDhoolProvider : MainAPI() { // all providers must be an instance of MainAPI
    companion object {
        var context: android.content.Context? = null
    }
    
    override var mainUrl = "https://www.tamildhool.tech"
    override var name = "TamilDhool"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "zee-tamil" to "Zee Tamil TV",
        "sun-tv" to "Sun TV",
        "vijay-tv" to "Vijay TV",
        "kalaignar-tv" to "Kalaignar TV",
        "news-gossips" to "News Gossips TV",
    )

    data class TamilDhoolLinks(
        @JsonProperty("sourceName") val sourceName: String,
        @JsonProperty("sourceLink") val sourceLink: String
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Show star popup on first visit (shared across all CNCVerse plugins)
        context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        
        val query = request.data.format(page)
        val document = app.post(
            "$mainUrl/$query/",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
            referer = "$mainUrl/"
        ).document
        val home = document.select("article.regular-post").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(arrayListOf(HomePageList(request.name, home, isHorizontalImages = true)), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("section.entry-body > h3 > a")?.text()?.toString()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("section.entry-body > h3 > a")?.attr("href").toString())
        val posterUrl = fixUrlNull(this.selectFirst("div.post-thumb > a > img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
            this.quality = SearchQuality.HD
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.replace(" ", "+").lowercase()
        val document = app.get("$mainUrl/?s=$encodedQuery", referer = "$mainUrl/").document
        return document.select("article.regular-post").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.toString()?.trim()
            ?: return null
        val posterRegex = Regex("(https?:\\/\\/(www\\.)?[-a-zA-Z0-9@:%._\\+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_\\+.~#?&\\/\\/=]*jpg))")
        val posterRaw = doc.selectFirst("div.entry-cover")?.attr("style").toString()
        val poster = posterRegex.find(posterRaw)?.value?.trim()

        val linkElements = doc.select("div.entry-content link[rel=prefetch][href]")
        val link = linkElements.map {
            var href = it.attr("href")
            if (href.startsWith("https://dai.ly/")) {
                val id = href.removePrefix("https://dai.ly/")
                href = "https://www.dailymotion.com/embed/video/$id"
            }
            val sourceName = when {
            href.contains("thirai", true) -> "ThiraiOne"
            href.contains("dailymotion", true) -> "Dailymotion"
            href.contains("youtube", true) -> "Youtube"
            else -> "Unknown"
            }
            TamilDhoolLinks(
            sourceName,
            href
            )
        }

        val episodes = listOf(
            newEpisode(data = link.toJson()){
                name = title
                season = 1
                episode = 1
                this.posterUrl = poster
            }
        )

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster?.trim()
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = parseJson<ArrayList<TamilDhoolLinks>>(data)

        val thiraione   = link.filter { it.toString().contains("thirai", true) }
        val dailymotion = link.filter { it.toString().contains("dailymotion", true) }
        val youtube     = link.filter { it.toString().contains("youtube", true) }

        safeApiCall {
            if (thiraione.joinToString().isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        thiraione.joinToString { it.sourceName },
                        thiraione.joinToString { it.sourceName },
                        thiraione.joinToString { it.sourceLink }
                            .replace("/p/", "/v/") + ".m3u8",
                        type = ExtractorLinkType.M3U8)
                )
            }
            if (dailymotion.joinToString().isNotBlank()) {
                loadExtractor(dailymotion.first().sourceLink, subtitleCallback, callback)
            }
            if (youtube.joinToString().isNotBlank()) {
                loadExtractor(youtube.joinToString { it.sourceLink }, subtitleCallback, callback)
            }
                // Do nothing thereby failing link loading (No link found)
        }
        return true
    }
}
