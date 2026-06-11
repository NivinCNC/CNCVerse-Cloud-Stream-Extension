package com.cncverse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

//suspend fun main() {
//    val providerTester = com.lagradost.cloudstreamtest.ProviderTester(RtallyProvider())
////    providerTester.testAll()
////    providerTester.testMainPage(verbose = true)
////    providerTester.testSearch(query = "gun",verbose = true)
////    providerTester.testLoad("https://rtally.vercel.app/post/from-season-1")
////    providerTester.testLoad("https://rtally.vercel.app/post/the-substance")
////    providerTester.testLoad("https://rtally.vercel.app/post/all-of-us-are-dead-season-1")
//    providerTester.testLoad("https://rtally.vercel.app/post/bigg-boss-season-18")
//}

class RtallyProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
        private const val OMG10 = "aHR0cHM6Ly9vbWcxMC5jb20vNC8xMTEwNDQ4OQ=="
        @Volatile private var lastBrowserOpenMs = 0L
        @Volatile private var telegramPopupShown = false
        private const val BROWSER_DEBOUNCE_MS = 10_000L
    }
    
    override var mainUrl = "https://www.rtally.shop"
    override var name = "Rtally"
    override var lang = "ta"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.AnimeMovie,
        TvType.Anime
    )
    override val mainPage = mainPageOf(
        "/categories/trending" to "Trending",
        "/categories/featured" to "Featured",
        "/categories/hollywood" to "Hollywood",
        "/categories/bengali" to "Bangla",
        "/categories/bollywood" to "Bollywood",
        "/categories/tv-shows" to "Tv Shows",
        "/categories/korean" to "Korean",
        "/categories/anime" to "Anime"
    )
    private val headers =
        mapOf("user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        showTelegramPopup()
        // Show star popup on first visit (shared across all CNCVerse plugins)
        
        val doc = app.get(
            "$mainUrl${request.data}?page=$page",
            cacheTime = 60,
            headers = headers
        ).document
        val home = doc.select("section.md\\:col-span-3 div.grid a[href]").mapNotNull { toResult(it) }
        return newHomePageResponse(request.name, home, true)
    }

    private fun toResult(post: Element): SearchResponse {
        val title = post.select("h4").text()
        val url = mainUrl + post.attr("href")
        // Try to get image from img tag first, fallback to background-image style
        var posterUrl = post.select("img").attr("src")
        if (posterUrl.isNullOrEmpty()) {
            val styleAttr = post.select("div[style*=background-image]").attr("style")
            posterUrl = styleAttr.substringAfter("url(").substringBefore(")").substringBefore("?")
        }
        val language = post.select("div.absolute.bottom-2.left-2").text()
        val rating = post.select("div.absolute.bottom-2.right-2").text()
        val type = post.select("h5.border").text()
        
        return newAnimeSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
            addDubStatus(
                dubExist = when {
                    "Dual" in language -> true
                    "Hindi" in language -> true
                    "Tamil" in language -> true
                    "Telugu" in language -> true
                    "Bangla" in language -> true
                    else -> false
                },
                subExist = "Eng-Sub" in language
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        
        val doc = app.get(
            "$mainUrl/search/$query",
            cacheTime = 60,
            headers = headers
        ).document
        return doc.select("div.grid:nth-child(1) > a[href]:not([target])").mapNotNull { toResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        
        val doc = app.get(
            url,
            cacheTime = 60,
            headers = headers
        ).document
        val title = doc.select(".font-serif").text()
        // Try to get image from img tag first, fallback to background-image style
        var image = doc.selectFirst(".w-\\[200px\\] > img:nth-child(1)")?.attr("src")
        if (image.isNullOrEmpty()) {
            val styleAttr = doc.select("div[style*=background-image]").first()?.attr("style")
            image = styleAttr?.substringAfter("url(")?.substringBefore(")")?.substringBefore("?")
        }
        val plot = doc.selectFirst("p.text-sm:nth-child(2)")?.text()
        val year = doc.select("div.infoDiv:nth-child(7) > span:nth-child(2)").text().toIntOrNull()
        val recommendations = doc.select(".gap-8").mapNotNull {
            val link = it.select("a")
            newMovieSearchResponse(link.text(), link.attr("href"), TvType.Movie)
            {
                this.posterUrl = it.select("img").attr("src")
            }
        }
        val episode = doc.select("ul.flex > li")
        if (episode.isNotEmpty()) {
            val episodesData = mutableListOf<Episode>()
            val scriptHtml = doc.select("script").joinToString { it.html() }.replace("\\", "")
            val linkList: MutableList<String> = mutableListOf()
            doc.select("div.justify-center:nth-child(2) > a").forEach {
                val link = it.attr("href")
                when {
                    //Filemoon
                    link.contains("filemoon") -> extractFileMoonUrls(scriptHtml)?.split(",")
                        ?.forEachIndexed { index, id ->
                            if (index in linkList.indices) {
                                linkList[index] += "https://filemoon.sx/e/$id ; "
                            } else {
                                linkList.add("https://filemoon.sx/e/$id ; ")
                            }
                        }
                    //Vidhideplus
                    link.contains("vidhideplus") -> extractVidhideplus(scriptHtml)?.split(",")
                        ?.forEachIndexed { index, id ->
                            if (index in linkList.indices) {
                                linkList[index] += "https://vidhideplus.com/v/$id ; "
                            } else {
                                linkList.add("https://vidhideplus.com/v/$id ; ")
                            }
                        }
                    //StreamWish
                    link.contains("wish") -> extractStreamwishUrls(scriptHtml)?.split(",")
                        ?.forEachIndexed { index, id ->
                            if (index in linkList.indices) {
                                linkList[index] += "https://playerwish.com/e/$id ; "
                            } else {
                                linkList.add("https://playerwish.com/e/$id ; ")
                            }
                        }
                }
            }
            linkList.forEachIndexed {index, it ->
                episodesData.add(
                    newEpisode(it)
                )
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesData) {
                this.posterUrl = image
                this.plot = plot
                this.year = year
                this.recommendations = recommendations
            }

        } else {
            var links = ""
            doc.select("div.justify-center:nth-child(2) > a").forEach {
                links += downloadToEmbedUrl(it)
            }
            return newMovieLoadResponse(title, url, TvType.Movie, links) {
                this.posterUrl = image
                this.plot = plot
                this.year = year
                this.recommendations = recommendations
            }
        }
    }

    private fun downloadToEmbedUrl(urlElement: Element): String {
        val url = urlElement.attr("href")
        return when {
            //Filemoon
            url.contains("filemoon") -> url.replace("/download/", "/e/") + " ; "
            //Vidhideplus
            url.contains("vidhideplus") -> url.replace("/download/", "/v/") + " ; "
            //Vidhidepre
            url.contains("vidhidepre") -> url.replace("/d/", "/v/") + " ; "
            //StreamWish
            url.contains("playerwish") -> url.replace("/d/", "/e/") + " ; "
            else -> url + " ; "
        }
    }

    private val fileMoonRegex = Regex("\"multiLinksDl\":\\s*\"([^\"]+)\"")
    private fun extractFileMoonUrls(text: String): String? {
        val fileMoonMatch = fileMoonRegex.find(text)
        return fileMoonMatch?.groupValues?.getOrNull(1)
    }

    private val streamwishMultiUrlRegex = Regex("\"streamwishMultiUrl\":\\s*\"([^\"]+)\"")
    private fun extractStreamwishUrls(text: String): String? {
        val streamwishMultiUrlMatch = streamwishMultiUrlRegex.find(text)
        return streamwishMultiUrlMatch?.groupValues?.getOrNull(1)
    }

    private val vidhideplusRegex = Regex("\"multiLinksSl\":\\s*\"([^\"]+)\"")
    private fun extractVidhideplus(text: String): String? {
        val vidhideplusMatch = vidhideplusRegex.find(text)
        return vidhideplusMatch?.groupValues?.getOrNull(1)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        openInExternalBrowser(String(android.util.Base64.decode(OMG10, android.util.Base64.DEFAULT)))
        data.split(" ; ").forEach {
            loadExtractor(
                it,
                subtitleCallback,
                callback
            )
        }
        return true
    }



    private fun showTelegramPopup() {
        if (isLayout(TV)) return
        val ctx = context ?: return
        if (telegramPopupShown) return
        val prefs = ctx.getSharedPreferences("cncverse_prefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("telegram_popup_shown", false)) { telegramPopupShown = true; return }
        telegramPopupShown = true
        prefs.edit().putBoolean("telegram_popup_shown", true).apply()
        Handler(Looper.getMainLooper()).post {
            try {
                val dp = ctx.resources.displayMetrics.density

                
                val bgDraw = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#1A1A2E"))
                    cornerRadius = 16f * dp
                }

                val root = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
                    background = bgDraw
                }

                // Title
                val titleTv = android.widget.TextView(ctx).apply {
                    text = "\uD83D\uDCAC Join CNCVerse Community"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 17f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
                        .also { it.bottomMargin = (10 * dp).toInt() }
                }

                // Thin divider
                val dividerV = android.view.View(ctx).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#2D2D4A"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, 1)
                        .also { it.bottomMargin = (14 * dp).toInt() }
                }

                // Message
                val msgTv = android.widget.TextView(ctx).apply {
                    text = "Join our Telegram group to discuss and share your opinion!"
                    setTextColor(android.graphics.Color.parseColor("#A0A0A8"))
                    textSize = 14f
                    setLineSpacing(0f, 1.4f)
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
                        .also { it.bottomMargin = (18 * dp).toInt() }
                }

                // Button row
                val btnRow = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END
                }
                val laterTv = android.widget.TextView(ctx).apply {
                    text = "Later"
                    setTextColor(android.graphics.Color.parseColor("#808090"))
                    textSize = 14f
                    val p = (10 * dp).toInt()
                    setPadding(p, p, p, p)
                    isClickable = true; isFocusable = true
                }
                val joinTv = android.widget.TextView(ctx).apply {
                    text = "Join Telegram"
                    setTextColor(android.graphics.Color.parseColor("#5B9BF5"))
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val p = (10 * dp).toInt()
                    setPadding(p, p, 0, p)
                    isClickable = true; isFocusable = true
                }
                btnRow.addView(laterTv)
                btnRow.addView(joinTv)
                root.addView(titleTv)
                root.addView(dividerV)
                root.addView(msgTv)
                root.addView(btnRow)

                val dialog = android.app.AlertDialog.Builder(ctx)
                    .setView(root)
                    .setCancelable(true)
                    .create()

                // Transparent window so rounded card corners show
                dialog.window?.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                )

                laterTv.setOnClickListener { dialog.dismiss() }
                joinTv.setOnClickListener {
                    dialog.dismiss()
                    try {
                        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/cncverse"))
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                    } catch (_: Exception) {}
                }
                dialog.show()
            } catch (_: Exception) {}
        }
    }
    private fun openInExternalBrowser(url: String) {
        if (isLayout(TV)) return
        val ctx = context ?: return
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