
package com.cncverse

//import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import okhttp3.FormBody
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TamilUltraProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://cors.cncverse.workers.dev/https://tamilultratv.com"
    override var name = "TamilUltra"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Live
    )

    companion object {
        var context: Context? = null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        context?.let { ctx ->
            withContext(Dispatchers.Main) {
                StarPopupHelper.showStarPopupIfNeeded(ctx)
            }
        }
         val genreClasses = listOf(
            "genre_tamil-news" to "Tamil News",
            "genre_tamil-movies" to "Tamil Movies",
            "genre_tamil-kids" to "Tamil Kids",
            "genre_tamil-infotainment" to "Tamil Infotainment",
            "genre_tamil-music" to "Tamil Music",
            "genre_tamil-entertainment" to "Tamil Entertainment",
            "genre_sports" to "Sports"
        )

        val document = app.get(mainUrl).document

        val home = genreClasses.mapNotNull { (className, displayName) ->
            document.select("div#$className").firstOrNull()?.toHomePageList(displayName)
        }

        return newHomePageResponse(home)
    }

    private fun Element.toHomePageList(sectionName: String): HomePageList {
        val items = select("article.item").mapNotNull { it.toSearchResult() }
        return HomePageList(sectionName, items)
    }


    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.data > h3 > a")?.text()?.trim()
            ?: return null
        val href = "https://cors.cncverse.workers.dev/" + fixUrl(this.selectFirst("div.data > h3 > a")?.attr("href").toString())
        val posterUrl = fixUrlNull(this.selectFirst("div.poster > img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.Live) {
                this.posterUrl = posterUrl
            }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document

        return document.select("div.result-item").mapNotNull {
            val title =
                it.selectFirst("article > div.details > div.title > a")?.text().toString().trim()
            val href = fixUrl(
                it.selectFirst("article > div.details > div.title > a")?.attr("href").toString()
            )

            val finalUrl = if (href.startsWith("/")) {
                mainUrl + href
            } else {
                "https://cors.cncverse.workers.dev/" + href
            }
            val posterUrl = fixUrlNull(
                it.selectFirst("article > div.image > div.thumbnail > a > img")?.attr("src")
            )

            newMovieSearchResponse(title, finalUrl, TvType.Live) {
                    this.posterUrl = posterUrl
                }
        }
    }

    private suspend fun getEmbed(postid: String?, nume: String, referUrl: String?): NiceResponse {
        val body = FormBody.Builder()
            .addEncoded("action", "doo_player_ajax")
            .addEncoded("post", postid.toString())
            .addEncoded("nume", nume)
            .addEncoded("type", "movie")
            .build()

        return app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            requestBody = body,
            referer = referUrl
        )
    }

    data class EmbedUrl (
        @JsonProperty("embed_url") var embedUrl : String,
        @JsonProperty("type") var type : String?
    )

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.select("div.sheader > div.data > h1").text()
        val poster = fixUrlNull(doc.selectFirst("div.poster > img")?.attr("src"))
        val id = doc.select("#player-option-1").attr("data-post")
        val m3u8 = fixUrlNull(
                getEmbed(
                    id,
                    "1",
                    url
                ).parsed<EmbedUrl>().embedUrl
            ).toString()
        val link = "https://tamilultratv.com/" + m3u8.substringAfter(".php?")
        return newMovieLoadResponse("$title (Use Vpn if content didn't play)", id, TvType.Live, "$m3u8,$link") {
                this.posterUrl = poster
            }
    }

    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
      val referer = data.substringBefore(",")
      var link = data.substringAfter(",")
        // Log.d("TamilUltraProvider", "Link: $link")
        callback.invoke(
            newExtractorLink(
                name,
                name,
                link,
                type = ExtractorLinkType.M3U8
            )
            {
                this.quality = Qualities.Unknown.value
                this.referer = referer
            }
        )

        return true
    }

}
