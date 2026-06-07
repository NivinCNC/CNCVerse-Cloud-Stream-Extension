package com.Tamilian

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink


import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout


class Tamilian : TmdbProvider() {
    override var name = "Tamilian"
    override val hasMainPage = true
    override var lang = "ta"
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
    )

    companion object
    {
        var context: android.content.Context? = null
        const val HOST="https://embedojo.net"
        private const val OMG10 = "aHR0cHM6Ly9vbWcxMC5jb20vNC8xMTEwNDQ4OQ=="
        @Volatile private var lastBrowserOpenMs = 0L
        @Volatile private var telegramPopupShown = false
        private const val BROWSER_DEBOUNCE_MS = 10_000L
    }
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        showTelegramPopup()
       
        return super.getMainPage(page, request)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        openInExternalBrowser(String(android.util.Base64.decode(OMG10, android.util.Base64.DEFAULT)))
        val mediaData = AppUtils.parseJson<TmdbLink>(data).toLinkData()
        val script = app.get("$HOST/tamil/tmdb/${mediaData.tmdbId}")
            .document.selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()?.let { getAndUnpack(it) }

        val token = script?.substringAfter("FirePlayer(\"")?.substringBefore("\",")
        val m3u8 = app.post("$HOST/player/index.php?data=$token&do=getVideo", headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
            .parsedSafe<VideoData>()
        val headers= mapOf("Origin" to "https://embedojo.net")
        m3u8?.let {
            safeApiCall {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        url = it.videoSource,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                        this.headers = headers
                    }
                )
            }
        }
        return true
    }


    private fun TmdbLink.toLinkData(): LinkData {
        return LinkData(
            imdbId = imdbID,
            tmdbId = tmdbID,
            title = movieName,
            season = season,
            episode = episode
        )
    }


    data class LinkData(
        @JsonProperty("simklId") val simklId: Int? = null,
        @JsonProperty("traktId") val traktId: Int? = null,
        @JsonProperty("imdbId") val imdbId: String? = null,
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("tvdbId") val tvdbId: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("aniId") val aniId: String? = null,
        @JsonProperty("malId") val malId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("orgTitle") val orgTitle: String? = null,
        @JsonProperty("isAnime") val isAnime: Boolean = false,
        @JsonProperty("airedYear") val airedYear: Int? = null,
        @JsonProperty("lastSeason") val lastSeason: Int? = null,
        @JsonProperty("epsTitle") val epsTitle: String? = null,
        @JsonProperty("jpTitle") val jpTitle: String? = null,
        @JsonProperty("date") val date: String? = null,
        @JsonProperty("airedDate") val airedDate: String? = null,
        @JsonProperty("isAsian") val isAsian: Boolean = false,
        @JsonProperty("isBollywood") val isBollywood: Boolean = false,
        @JsonProperty("isCartoon") val isCartoon: Boolean = false,
    )


    data class VideoData(
        val hls: Boolean,
        val videoImage: String,
        val videoSource: String,
        val securedLink: String,
        val downloadLinks: List<Any?>,
        val attachmentLinks: List<Any?>,
        val ck: String,
    )




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