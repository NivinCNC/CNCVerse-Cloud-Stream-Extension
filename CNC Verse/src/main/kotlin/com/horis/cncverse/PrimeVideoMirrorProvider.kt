package com.horis.cncverse

import android.content.Context
import com.horis.cncverse.entities.EpisodesData
import com.horis.cncverse.entities.PostData
import com.horis.cncverse.entities.SearchData
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.APIHolder.unixTime
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

class PrimeVideoMirrorProvider : MainAPI() {
    companion object {
        var context: Context? = null
        private const val OMG10 = "aHR0cHM6Ly9vbWcxMC5jb20vNC8xMTEwNDQ4OQ=="
        @Volatile private var lastBrowserOpenMs = 0L
        @Volatile private var telegramPopupShown = false
        private const val BROWSER_DEBOUNCE_MS = 10_000L
    }
    
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )
    override var lang = "ta"

    override var mainUrl = "https://net52.cc"
    override var name = "Prime Video"

    override val hasMainPage = true
    private var cookie_value = ""
    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "sec-ch-ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Android WebView\";v=\"144\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Safari/537.36 /OS.Gatu v3.0",
        "X-Requested-With" to "XMLHttpRequest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        showTelegramPopup()
        // Show star popup on first visit (shared across all CNCVerse plugins)
        
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "ott" to "pv",
            "hd" to "on"
        )
        val document = app.get(
            "$mainUrl/mobile/home?app=1",
            cookies = cookies,
            headers = headers,
            referer = "$mainUrl/mobile/home?app=1",
        ).document
        val items = document.select(".tray-container, #top10").map {
            it.toHomePageList()
        }
        return newHomePageResponse(items, false)
    }

    private fun Element.toHomePageList(): HomePageList {
        val name = select("h2, span").text()
        val items = select("article, .top10-post").mapNotNull {
            it.toSearchResult()
        }
        return HomePageList(name, items, isHorizontalImages = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val id = selectFirst("a")?.attr("data-post") ?: attr("data-post")
        // val posterUrl =
        //     fixUrlNull(selectFirst(".card-img-container img, .top10-img img")?.attr("data-src"))

        return newAnimeSearchResponse("", Id(id).toJson()) {
            this.posterUrl = "https://imgcdn.kim/pv/v/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "pv"
        )
        val url = "$mainUrl/mobile/pv/search.php?s=$query&t=${APIHolder.unixTime}"
        val data = app.get(url, referer = "$mainUrl/home", cookies = cookies).parsed<SearchData>()

        return data.searchResult.map {
            newAnimeSearchResponse(it.t, Id(it.id).toJson()) {
                posterUrl = "https://imgcdn.kim/pv/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val id = parseJson<Id>(url).id
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "pv"
        )
        val data = app.get(
            "$mainUrl/mobile/pv/post.php?id=$id&t=${APIHolder.unixTime}",
            headers,
            referer = "$mainUrl/home",
            cookies = cookies
        ).parsed<PostData>()

        val episodes = arrayListOf<Episode>()

        val title = data.title
        val castList = data.cast?.split(",")?.map { it.trim() } ?: emptyList()
        val cast = castList.map {
            ActorData(
                Actor(it),
            )
        }
        val genre = data.genre?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }

        val rating = data.match?.replace("IMDb ", "")
        val runTime = convertRuntimeToMinutes(data.runtime.toString())

        val suggest = data.suggest?.map {
            newAnimeSearchResponse("", Id(it.id).toJson()) {
                this.posterUrl = "https://imgcdn.kim/pv/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }

        if (data.episodes.first() == null) {
            episodes.add(newEpisode(LoadData(title, id)) {
                name = data.title
            })
        } else {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    this.name = it.t
                    this.episode = it.ep.replace("E", "").toIntOrNull()
                    this.season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/pvepimg/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }

            if (data.nextPageShow == 1) {
                episodes.addAll(getEpisodes(title, url, data.nextPageSeason!!, 2))
            }

            data.season?.dropLast(1)?.amap {
                episodes.addAll(getEpisodes(title, url, it.id, 1))
            }
        }

        val type = if (data.episodes.first() == null) TvType.Movie else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = "https://imgcdn.kim/pv/v/$id.jpg"
            backgroundPosterUrl = "https://imgcdn.kim/pv/h/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
            plot = data.desc
            year = data.year.toIntOrNull()
            tags = genre
            actors = cast
            this.score =  Score.from10(rating)
            this.duration = runTime
            this.contentRating = data.ua
            this.recommendations = suggest
        }
    }

    private suspend fun getEpisodes(
        title: String, eid: String, sid: String, page: Int
    ): List<Episode> {
        val episodes = arrayListOf<Episode>()
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "pv"
        )
        var pg = page
        while (true) {
            val data = app.get(
                "$mainUrl/mobile/pv/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg",
                headers,
                referer = "$mainUrl/home",
                cookies = cookies
            ).parsed<EpisodesData>()
            data.episodes?.mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/pvepimg/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }
            if (data.nextPageShow == 0) break
            pg++
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val apiBase = resolveApiUrl()
        val id = parseJson<LoadData>(data).id
        val response = app.get(
            "$apiBase/newtv/player.php?id=$id",
            headers = buildNewTvHeaders("pv", mapOf("Usertoken" to ""))
        ).parsed<NewTvPlayerResponse>()

        if (response.status != "ok" || response.video_link.isNullOrBlank()) return false

        callback.invoke(
            newExtractorLink(name, name, response.video_link, type = ExtractorLinkType.M3U8) {
                this.referer = response.referer ?: apiBase
            }
        )

        return true
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                if (request.url.toString().contains(".m3u8")) {
                    val newRequest = request.newBuilder()
                        .header("Cookie", "hd=on")
                        .build()
                    return chain.proceed(newRequest)
                }
                return chain.proceed(request)
            }
        }
    }

    data class Id(
        val id: String
    )

    data class LoadData(
        val title: String, val id: String
    )



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