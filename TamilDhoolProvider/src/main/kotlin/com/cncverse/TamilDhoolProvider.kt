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
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
//import java.time.LocalDate
//import java.time.format.DateTimeFormatter

class TamilDhoolProvider : MainAPI() { // all providers must be an instance of MainAPI
    companion object {
        var context: android.content.Context? = null
        private const val OMG10 = "aHR0cHM6Ly9vbWcxMC5jb20vNC8xMTEwNDQ4OQ=="
        @Volatile private var lastBrowserOpenMs = 0L
        @Volatile private var telegramPopupShown = false
        private const val BROWSER_DEBOUNCE_MS = 10_000L
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
        showTelegramPopup()
        // Show star popup on first visit (shared across all CNCVerse plugins)
        
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
        val title = this.selectFirst("section.entry-body > h3 > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("section.entry-body > h3 > a")?.attr("href").toString())
        val posterUrl = this.selectFirst("div.post-thumb > a > picture > img")?.attr("src")?: fixUrlNull(this.selectFirst("div.post-thumb > a > img")?.attr("src"))
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
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
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
        openInExternalBrowser(String(android.util.Base64.decode(OMG10, android.util.Base64.DEFAULT)))
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