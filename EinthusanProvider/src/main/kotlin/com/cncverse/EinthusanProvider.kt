package com.cncverse

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import okhttp3.FormBody
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EinthusanProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://cors.cncverse.workers.dev/https://einthusan.tv"
    override var name = "Einthusan"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay: Long = 100
    override val supportedTypes = setOf(
        TvType.Movie
    )

    companion object {
        var context: Context? = null
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movie/results/?find=Recent&lang=tamil" to "Tamil Movies",
        "$mainUrl/movie/results/?find=Recent&lang=hindi" to "Hindi Movies",
        "$mainUrl/movie/results/?find=Recent&lang=telugu" to "Telugu Movies",
        "$mainUrl/movie/results/?find=Recent&lang=malayalam" to "Malayalam Movies",
        "$mainUrl/movie/results/?find=Recent&lang=kannada" to "Kannada Movies",
        "$mainUrl/movie/results/?find=Recent&lang=bengali" to "Bengali Movies",
        "$mainUrl/movie/results/?find=Recent&lang=marathi" to "Marathi Movies",
        "$mainUrl/movie/results/?find=Recent&lang=punjabi" to "Punjabi Movies"
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
            app.get(request.data + "&page=$page").document
        }

        //Log.d("Document", document.toString())
        val home = document.select("#UIMovieSummary > ul > li").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.block2 > a.title > h3")?.text()?.trim() ?: return null
        //Log.d("title", title)
        val href = fixUrl(mainUrl + this.selectFirst("div.block2 > a.title")?.attr("href").toString())
        //Log.d("href", href)
        val posterUrl = fixUrlNull("https:${this.selectFirst("div.block1 > a > img")?.attr("src")}")
        //Log.d("posterUrl", posterUrl.toString())
        return newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = SearchQuality.HD
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val fixedQuery = query.replace(" ", "+")
        val resultTamil = app.get("$mainUrl/movie/results/?lang=tamil&query=$fixedQuery").document.select("#UIMovieSummary > ul > li").mapNotNull {
            it.toSearchResult()
        }
        val resultHindi = app.get("$mainUrl/movie/results/?lang=hindi&query=$fixedQuery").document.select("#UIMovieSummary > ul > li").mapNotNull {
            it.toSearchResult()
        }
        val resultMalayalam = app.get("$mainUrl/movie/results/?lang=malayalam&query=$fixedQuery").document.select("#UIMovieSummary > ul > li").mapNotNull {
            it.toSearchResult()
        }
        val resultTelugu = app.get("$mainUrl/movie/results/?lang=telugu&query=$fixedQuery").document.select("#UIMovieSummary > ul > li").mapNotNull {
            it.toSearchResult()
        }
        val resultKannada = app.get("$mainUrl/movie/results/?lang=kannada&query=$fixedQuery").document.select("#UIMovieSummary > ul > li").mapNotNull {
            it.toSearchResult()
        }
        val resultBengali = app.get("$mainUrl/movie/results/?lang=bengali&query=$fixedQuery").document.select("#UIMovieSummary > ul > li").mapNotNull {
            it.toSearchResult()
        }
        val resultMarathi = app.get("$mainUrl/movie/results/?lang=marathi&query=$fixedQuery").document.select("#UIMovieSummary > ul > li").mapNotNull {
            it.toSearchResult()
        }
        val resultPunjabi = app.get("$mainUrl/movie/results/?lang=punjabi&query=$fixedQuery").document.select("#UIMovieSummary > ul > li").mapNotNull {
            it.toSearchResult()
        }
        val merge = resultTamil + resultHindi + resultMalayalam + resultTelugu + resultKannada + resultBengali + resultMarathi + resultPunjabi

        return merge.sortedBy { searchResponse -> 
            val cleanName = searchResponse.name.replace("(\\()+(.*)+(\\))".toRegex(), "").lowercase()
            val cleanQuery = query.lowercase()
            when {
                cleanName.contains(cleanQuery) -> 0
                cleanName.startsWith(cleanQuery) -> 1
                else -> 2
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        //Log.d("Doc", doc.toString())
        val title = doc.select("#UIMovieSummary > ul > li > div.block2 > a.title > h3").text().trim().ifEmpty { return null }
        //Log.d("title", title)
        val href = fixUrl(mainUrl + doc.select("#UIMovieSummary > ul > li > div.block2 > a.title").attr("href"))
        //Log.d("href", href)
        val poster = fixUrlNull("https:${doc.select("#UIMovieSummary > ul > li > div.block1 > a > img").attr("src")}")
        //Log.d("poster", poster.toString())
        val tags = doc.select("ul.average-rating > li").map { it.select("label").text() }
        val year =
            doc.selectFirst("div.block2 > div.info > p")?.ownText()?.trim()?.toInt()
        //Log.d("year", year.toString())
        val description = doc.selectFirst("p.synopsis")?.text()?.trim()
        val score = doc.select("ul.average-rating > li > p[data-value]").toString().let { Score.from10(it) }
        //Log.d("rating", rating.toString())
        val actors =
            doc.select("div.professionals > div").map {
                ActorData(
                    Actor(
                        it.select("div.prof > p").text(),
                        "https:" + it.select("div.imgwrap img").attr("src")
                    ),
                    roleString = it.select("div.prof > label").text(),
                )
            }
        val mp4link = doc.select("#UIVideoPlayer").attr("data-mp4-link")
        val m3u8link = doc.select("#UIVideoPlayer").attr("data-hls-link")

        return newMovieLoadResponse(title, "$mp4link,$m3u8link", TvType.Movie, "$mp4link,$m3u8link") {
                this.posterUrl = poster?.trim()
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
                this.actors = actors
            }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mp4link = data.substringBefore(",")
        val m3u8link = data.substringAfter(",")

        val ipfind = Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b")
        val fixedmp4link = ipfind.replace(mp4link, "cdn1.einthusan.io")
        val fixedm3u8link = ipfind.replace(m3u8link, "cdn1.einthusan.io")
            callback.invoke(
                newExtractorLink(
                    source = "$name-MP4",
                    name = "$name-MP4",
                    url = fixedmp4link,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.headers = mapOf("Referer" to "$mainUrl/")
                    this.quality = Qualities.Unknown.value
                }
            )
            callback.invoke(
                newExtractorLink(
                    source = "$name-M3U8",
                    name = "$name-M3U8",
                    url = fixedm3u8link,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf("Referer" to "$mainUrl/")
                    this.quality = Qualities.Unknown.value
                }
            )

        return true
    }
}
