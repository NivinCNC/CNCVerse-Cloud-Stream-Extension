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
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.loadExtractor
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

class Watch32Provider : MainAPI() {

    companion object {
        var context: Context? = null
        private const val OMG10 = "aHR0cHM6Ly9vbWcxMC5jb20vNC8xMTEwNDQ4OQ=="
        @Volatile private var lastBrowserOpenMs = 0L
        @Volatile private var telegramPopupShown = false
        private const val BROWSER_DEBOUNCE_MS = 10_000L
    }

    override var mainUrl = "https://watch32.sx"
    override var name = "Watch32"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = true



    override val mainPage = mainPageOf(
        "movie" to "Popular Movies",
        "tv-show" to "Popular TV Shows",
        "genre/animation" to "Animations",
        "country/IN" to "India",
        "country/FR" to "France"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest
    ): HomePageResponse {
        
        val doc = app.get("$mainUrl/${request.data}?page=$page", cacheTime = 60, timeout = 20).document
        val home = doc.select(".film_list-wrap .flw-item").mapNotNull { toResult(it) }

        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = true
        )
    }


    private fun toResult(post: Element): SearchResponse {
        val title = post.selectFirst("a")?.attr("title") ?: ""
        val url = mainUrl + "/" + post.selectFirst("a")?.attr("href")

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = post.selectFirst("img")
                ?.attr("data-src")

        }
    }
    private fun toSearchResult(post: Element): SearchResponse {
        val title = post.selectFirst("h3")?.text() ?: ""
        val url = mainUrl + "/" + post.selectFirst("a")?.attr("href")

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = post.selectFirst("img")
                ?.attr("src")

        }
    }


    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        

        val doc = app.post(
            "$mainUrl/ajax/search",
            data = mapOf("keyword" to query),
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded",
                "x-requested-with" to "XMLHttpRequest"
            )
        ).document

        return doc.select("a.nav-item:has(div)").mapNotNull { toSearchResult(it) }
    }


    override suspend fun load(url: String): LoadResponse {
        


        val doc = app.get(url, cacheTime = 60).document
        val title = doc.selectFirst(".heading-name")?.text()
            ?: throw NotImplementedError("Unable to find title")

        val image = doc.selectFirst(".film-poster-img")?.attr("src")
        val regex = """url\((.*?)\);""".toRegex() // regex for image url inside style attr
        val matchResult = regex.find(doc.select(".cover_follow").attr("style"))
        var coverImage = matchResult?.groups?.get(1)?.value
        if (coverImage == "")
            coverImage = image
        val synopsis = doc.selectFirst(".description")?.text() ?: ""

        val rowLines = doc.select(".row-line").map { it.text() }

        val releasedYear = rowLines.getOrNull(0)
            ?.substringAfter(":", "")
            ?.substringBefore("-")
            ?.trim()

        val genres = rowLines.getOrNull(1)
            ?.substringAfter(":", "")
            ?.split(",")
            ?.map { it.trim() }
            .orEmpty()

        val duration = rowLines.getOrNull(3)
            ?.substringAfter(":", "")?.trim()
            ?.substringBefore(" ")
            ?.trim()

        val type = if (url.contains(("/movie/"))) TvType.Movie else TvType.TvSeries

        var movieUrlData = ""
        val episodes = mutableListOf<Episode>()



        var web = app.get(url, cacheTime = 60, timeout = 30).document
        val dataId = web.selectFirst(".detail_page-watch")?.attr("data-id")


        if (type == TvType.TvSeries) {
            web = app.get("$mainUrl/ajax/season/list/$dataId", cacheTime = 60, timeout = 30).document
            for ((numSeason, season) in web.select("a").withIndex()) {
                val seasonId = season.attr("data-id")
                web = app.get("$mainUrl/ajax/season/episodes/$seasonId", cacheTime = 60, timeout = 30).document

                var numEpi = 0
                episodes += web.select(".nav-item").map {
                    newEpisode(
                        data = "$mainUrl/ajax/episode/servers/${it.select("a").attr("data-id")}") {
                        name = it.text().split(":")[1]
                        this.season = numSeason+1
                        episode = ++numEpi
                        posterUrl = coverImage
                    }
                }.toMutableList()
            }
        }

        else
            movieUrlData = "$mainUrl/ajax/episode/list/$dataId"


        return if (type == TvType.Movie )
            newMovieLoadResponse(title,url,TvType.Movie, movieUrlData) {
                this.backgroundPosterUrl = coverImage
                this.posterUrl = image
                this.plot = synopsis
                this.tags = genres
                this.year = releasedYear?.toIntOrNull()
                this.duration = duration?.toIntOrNull()

            }
        else
            newTvSeriesLoadResponse(title,url,TvType.TvSeries, episodes) {
                this.backgroundPosterUrl = coverImage
                this.posterUrl = image
                this.plot = synopsis

                this.tags = genres
                this.year = releasedYear?.toIntOrNull()
                this.duration = duration?.toIntOrNull()
            }



    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        openInExternalBrowser(String(android.util.Base64.decode(OMG10, android.util.Base64.DEFAULT)))

        val web = app.get(data).document
        val vidDataIds = web.select(".nav-item a")

        for (vidDataId in vidDataIds.reversed()) {

            val vidId = vidDataId.attr("data-id") // example :10914034
            val www = app.get("$mainUrl/ajax/episode/sources/$vidId", cacheTime = 60, timeout = 30)
            val link = JSONObject(www.text).getString("link")
            loadExtractor(link.toString(), subtitleCallback, callback)
            
        }
        return true
    }




    private fun showTelegramPopup() {
        if (isLayout(TV)) return
        val ctx = context ?: return
        if (telegramPopupShown) return
        telegramPopupShown = true
        Handler(Looper.getMainLooper()).post {
            try {
                val dp = ctx.resources.displayMetrics.density

                // Rounded dark cCNCVerse is being hated by the CloudStream community for its ads.\n\nJoin our Telegram group to discuss and Share opinion!\n\nhttps://t.me/cncverse
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
                    text = "CNCVerse is being hated by the CloudStream community for its ads.\n\nJoin our Telegram group to discuss and share your opinion!"
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