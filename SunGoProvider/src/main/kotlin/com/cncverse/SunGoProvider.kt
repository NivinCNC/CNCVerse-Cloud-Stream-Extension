package com.cncverse

//import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import okhttp3.FormBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class SunGoProvider : MainAPI() { // all providers must be an instance of MainAPI
    companion object {
        var context: android.content.Context? = null
    }
    
    override var mainUrl = "https://cors.cncverse.workers.dev/https://www.sungohd.com"
    override var name = "SunGo"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Live
    )

    private suspend fun getEmbed(postid: String?, nume: String): NiceResponse {
        val body = FormBody.Builder()
            .addEncoded("action", "doo_player_ajax")
            .addEncoded("post", postid.toString())
            .addEncoded("nume", nume)
            .addEncoded("type", "movie")
            .build()

        return app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            requestBody = body
        )
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Show star popup on first visit (shared across all CNCVerse plugins)
        context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        
        val genreClasses = listOf(
            "genre_tamil" to "Tamil",
            "genre_malayalam-tv" to "Malayalam TV",
            "genre_hindi-tv" to "Hindi TV",
            "genre_sports" to "Sports",
            "genre_telugu-tv" to "Telugu TV"
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
        val encodedQuery = query.replace(" ", "+")
        val document = app.get("$mainUrl/?s=$encodedQuery").document
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

    data class EmbedUrl (
        @JsonProperty("embed_url") var embedUrl : String,
        @JsonProperty("type") var type : String?
    )

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.select("div.sheader > div.data > h1").text()
        val poster = fixUrlNull(doc.selectFirst("div.poster > img")?.attr("src"))
        val id = doc.select("#player-option-1").attr("data-post")
        val adLink = fixUrlNull(
                getEmbed(
                    id,
                    "1"
                ).parsed<EmbedUrl>().embedUrl
            ).toString()
        val link = "https://cdn.sungohd.com/embed/" + adLink.substringAfter("?id=")
        val embedDoc = app.get(link).document
        val movieId = embedDoc.selectFirst("#embed-player")?.attr("data-movie-id")
        val serverId = embedDoc.selectFirst("a.server.dropdown-item")?.attr("data-id")
        val streamApi = "https://cdn.sungohd.com/ajax/get_stream_link?id=$serverId&movie=$movieId&is_init=false"
        val streamResp = app.get(streamApi)
        val embedUrl = streamResp.parsedSafe<Map<String, Any>>()?.get("data")
                    ?.let { (it as? Map<*, *>)?.get("link")?.toString() }
                    ?: throw Exception("Failed to get stream link")
        if(embedUrl.contains("youtube")) {
            return newMovieLoadResponse(title, id, TvType.Live, embedUrl) {
                this.posterUrl = poster
            }
        }
        var referer = if (embedUrl.contains("?http")) {
            val firstUrl = embedUrl.substringBefore("?http")
            try {
            val uri = java.net.URI(firstUrl)
            "${uri.scheme}://${uri.host}/"
            } catch (e: Exception) {
            firstUrl 
            }
        } else {
            "https://cdn.sungohd.com/"
        }
        val embedPlayerResponse = app.get(embedUrl, referer = "https://cdn.sungohd.com/").document
        val m3u8Url = if (embedUrl.contains("https://jiotv.site")) {
            val evalScript = embedPlayerResponse.select("script")
            .mapNotNull { it.data() }
            .firstOrNull { it.contains("eval(function(") }
            ?: throw Exception("❌ No obfuscated eval script found")
            val kaken = extractKakenFromObfuscatedEval(evalScript)
            val kakenResponse = app.post(
                "https://jiotv.site/api/",
                requestBody = kaken.toRequestBody(),
                referer = "https://cdn.sungohd.com/"
            )
            val kakenUrl = extractM3u8FileFromJson(kakenResponse.text)
            kakenUrl
        } else {
            embedPlayerResponse.select("script")
            .asSequence()
            .mapNotNull { script ->
                Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]""")
                .find(script.data())?.groupValues?.get(1)
            }
            .firstOrNull() ?: throw Exception("No .m3u8 found")
        }

        return newMovieLoadResponse(title, id, TvType.Live, "$m3u8Url,$referer") {
                this.posterUrl = poster
            }
    }

    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = data.substringBeforeLast(",")
        var referer = data.substringAfterLast(",")
        callback.invoke(
            newExtractorLink(
                name,
                name,
                link,
                type = ExtractorLinkType.M3U8
            )
            {
                this.quality = Qualities.Unknown.value
                this.referer =  referer
            }
        )
        return true
    }

}

fun extractKakenFromObfuscatedEval(evalScript: String): String {
    val regex = Regex("""eval\(function\(([^)]+)\)\{.+?return p\}.*?\('(.*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)

    val match = regex.find(evalScript)
        ?: throw Exception("❌ Failed to parse eval block")

    val (args, pEncoded, aStr, cStr, kJoined) = match.destructured

    val a = aStr.toInt()
    val c = cStr.toInt()
    val k = kJoined.split("|").toMutableList()

    var p = pEncoded

    for (i in c - 1 downTo 0) {
        val from = i.toString(a)
        if (i < k.size && k[i].isNotEmpty()) {
            p = p.replace("\\b$from\\b".toRegex(), k[i])
        }
    }

    val kaken = Regex("""window\.kaken\s*=\s*["']([^"']+)["']""")
        .find(p)?.groupValues?.get(1)
        ?: throw Exception("❌ `window.kaken` not found after decoding")

    return kaken
}

fun extractM3u8FileFromJson(json: String): String {
    val root = JSONObject(json)

    val sourcesArray = root.getJSONArray("sources")
    if (sourcesArray.length() == 0) throw Exception("No sources found")

    val file = sourcesArray.getJSONObject(0).getString("file")
    return file
}
