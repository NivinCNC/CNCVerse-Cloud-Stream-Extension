package com.cncverse

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import android.content.Intent
import android.net.Uri

/**
 * PlayZTV Live Events provider.
 *
 * Fetches live sports events from the PlayZTV back-end (same base URL as the
 * IPTV provider). Event slugs resolve to stream lists via
 * `PlayZTVProviderManager.fetchChannelStreams(slug)`.
 */
class PlayZTVLiveEventsProvider : MainAPI() {

    companion object {
        var context: android.content.Context? = null
        private const val OMG10 = "aHR0cHM6Ly9vbWcxMC5jb20vNC8xMTEwNDQ4OQ=="
        @Volatile private var lastBrowserOpenMs = 0L
        @Volatile private var telegramPopupShown = false
        private const val BROWSER_DEBOUNCE_MS = 10_000L
    }

    override var mainUrl = "https://adsflw.xyz"
    override var name = "⚡PlayZTV Live Events"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Display helpers ───────────────────────────────────────────────────────

    private fun createDisplayTitle(event: PlayZLiveEventData): String {
        val info = event.eventInfo ?: return event.title
        return if (!info.teamA.isNullOrBlank() && !info.teamB.isNullOrBlank() &&
            info.teamA != info.teamB
        ) "${info.teamA} vs ${info.teamB}"
        else info.teamA ?: event.title
    }

    private fun getEventStatus(event: PlayZLiveEventData): String {
        val info = event.eventInfo ?: return ""
        val now = System.currentTimeMillis()
        return try {
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val start = info.startTime?.let { fmt.parse(it)?.time }
            val end = info.endTime?.let { fmt.parse(it)?.time }
            when {
                end != null && now >= end -> "✅"
                start != null && now >= start -> "🔴"
                start != null && now < start -> "🔜"
                else -> ""
            }
        } catch (_: Exception) { "" }
    }

    private fun isEventLive(event: PlayZLiveEventData): Boolean {
        val info = event.eventInfo ?: return false
        val now = System.currentTimeMillis()
        return try {
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val start = info.startTime?.let { fmt.parse(it)?.time }
            val end = info.endTime?.let { fmt.parse(it)?.time }
            if (end != null && now >= end) false
            else start != null && now >= start
        } catch (_: Exception) { false }
    }

    private fun isEventEnded(event: PlayZLiveEventData): Boolean {
        val info = event.eventInfo ?: return false
        val now = System.currentTimeMillis()
        return try {
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val end = info.endTime?.let { fmt.parse(it)?.time }
            end != null && now >= end
        } catch (_: Exception) { false }
    }

    private fun generateMatchCardUrl(event: PlayZLiveEventData): String {
        val info = event.eventInfo
        val encode: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8") }

        val title = encode(info?.eventName ?: event.title)
        val teamA = encode(info?.teamA ?: "Team A")
        val teamB = encode(info?.teamB ?: "Team B")
        val teamAImg = info?.teamAFlag ?: ""
        val teamBImg = info?.teamBFlag ?: ""
        val eventLogo = info?.eventLogo ?: ""
        val isLive = isEventLive(event)
        val isEnded = isEventEnded(event)

        val time = try {
            info?.startTime?.let {
                val df = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                val disp = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US)
                df.parse(it)?.let { d -> encode(disp.format(d)) } ?: ""
            } ?: ""
        } catch (_: Exception) { "" }

        return buildString {
            append("https://live-card-png.cricify.workers.dev/?")
            append("title=$title")
            append("&teamA=$teamA")
            append("&teamB=$teamB")
            if (teamAImg.isNotBlank()) append("&teamAImg=$teamAImg")
            if (teamBImg.isNotBlank()) append("&teamBImg=$teamBImg")
            if (eventLogo.isNotBlank()) append("&eventLogo=$eventLogo")
            if (time.isNotBlank()) append("&time=$time")
            append("&isLive=$isLive")
            append("&isEnded=$isEnded")
        }
    }

    // ── Load data ─────────────────────────────────────────────────────────────

    data class LiveEventLoadData(
        val eventId: Int,
        val title: String,
        val poster: String,
        val slug: String,
        val formats: List<PlayZLiveEventFormat>,
        val eventInfo: PlayZLiveEventInfo?
    )

    // ── CloudStream interface ─────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        showTelegramPopup()

        val events = PlayZTVProviderManager.fetchLiveEvents()
        val grouped = events.groupBy { it.eventInfo?.eventCat ?: it.cat ?: "Other" }

        val pages = grouped
            .map { (category, catEvents) ->
                val icon = when (category.lowercase()) {
                    "cricket" -> "🏏"; "football" -> "⚽"; "basketball" -> "🏀"
                    "ice hockey" -> "🏒"; "boxing" -> "🥊"
                    "motorsport" -> "🏎️"; "tennis" -> "🎾"
                    else -> "📺"
                }
                val items = catEvents
                    .sortedByDescending { isEventLive(it) }
                    .map { event ->
                        val displayTitle = createDisplayTitle(event)
                        val status = getEventStatus(event)
                        val fullTitle = if (status.isNotBlank()) "$status $displayTitle" else displayTitle
                        val poster = generateMatchCardUrl(event)
                        val loadData = LiveEventLoadData(
                            eventId = event.id, title = displayTitle, poster = poster,
                            slug = event.slug, formats = event.formats ?: emptyList(),
                            eventInfo = event.eventInfo
                        )
                        newLiveSearchResponse(fullTitle, loadData.toJson(), TvType.Live) {
                            this.posterUrl = poster
                        }
                    }
                HomePageList("$icon $category", items, isHorizontalImages = true)
            }
            .sortedBy { list ->
                when {
                    list.name.contains("Cricket", ignoreCase = true) -> 0
                    list.name.contains("Football", ignoreCase = true) -> 1
                    list.name.contains("Basketball", ignoreCase = true) -> 2
                    else -> 10
                }
            }

        return newHomePageResponse(pages, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        
        return PlayZTVProviderManager.fetchLiveEvents()
            .filter { event ->
                listOfNotNull(
                    event.title, event.eventInfo?.teamA, event.eventInfo?.teamB,
                    event.eventInfo?.eventName, event.eventInfo?.eventType
                ).joinToString(" ").contains(query, ignoreCase = true)
            }
            .map { event ->
                val displayTitle = createDisplayTitle(event)
                val status = getEventStatus(event)
                val fullTitle = if (status.isNotBlank()) "$status $displayTitle" else displayTitle
                val poster = generateMatchCardUrl(event)
                val loadData = LiveEventLoadData(
                    eventId = event.id, title = displayTitle, poster = poster,
                    slug = event.slug, formats = event.formats ?: emptyList(),
                    eventInfo = event.eventInfo
                )
                newLiveSearchResponse(fullTitle, loadData.toJson(), TvType.Live) {
                    this.posterUrl = poster
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        
        val data = parseJson<LiveEventLoadData>(url)
        val info = data.eventInfo
        val plot = buildString {
            info?.let { i ->
                i.eventType?.let { append("📌 $it\n") }
                i.eventName?.let { append("🏆 $it\n") }
                i.startTime?.let {
                    try {
                        val df = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                        val disp = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
                        df.parse(it)?.let { d -> append("🕐 ${disp.format(d)}\n") }
                    } catch (_: Exception) { append("🕐 $it\n") }
                }
            }
            append("\n📡 Available Servers: ${data.formats.size}")
        }
        return newLiveStreamLoadResponse(data.title, url, url) {
            this.posterUrl = data.poster
            this.plot = plot
        }
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

                // Rounded dark card background
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        openInExternalBrowser(String(android.util.Base64.decode(OMG10, android.util.Base64.DEFAULT)))
        val loadData = parseJson<LiveEventLoadData>(data)
        val streams = PlayZTVProviderManager.fetchChannelStreams(loadData.slug)
        if (streams.isNullOrEmpty()) return false
        streams.forEach { stream ->
            val serverName = stream.name ?: "Server"
            val streamLink = stream.link ?: return@forEach
            val (url, headers) = parseStreamLink(streamLink)
            if (url.isBlank()) return@forEach

            try {
                when {
                    url.contains(".mpd") -> {
                        val drmInfo = stream.api?.split(":")
                        if (drmInfo != null && drmInfo.size == 2) {
                            val kidBase64 = hexToBase64(drmInfo[0])
                            val keyBase64 = hexToBase64(drmInfo[1])
                            callback.invoke(
                                newDrmExtractorLink(this.name, serverName, url, INFER_TYPE, CLEARKEY_UUID) {
                                    this.quality = Qualities.Unknown.value
                                    this.key = keyBase64
                                    this.kid = kidBase64
                                    if (headers.isNotEmpty()) this.headers = headers
                                }
                            )
                        } else {
                            callback.invoke(
                                newExtractorLink(this.name, serverName, url, ExtractorLinkType.DASH) {
                                    this.quality = Qualities.Unknown.value
                                    if (headers.isNotEmpty()) this.headers = headers
                                }
                            )
                        }
                    }
                    else -> {
                        val finalHeaders = headers.toMutableMap()
                        if (!finalHeaders.containsKey("User-Agent")) {
                            finalHeaders["User-Agent"] =
                                "Mozilla/5.0 (Linux; Android 10; Pixel 3 XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
                        }
                        callback.invoke(
                            newExtractorLink(this.name, serverName, url, ExtractorLinkType.M3U8) {
                                this.quality = Qualities.Unknown.value
                                if (finalHeaders.isNotEmpty()) this.headers = finalHeaders
                            }
                        )
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return true
    }

@Suppress("ObjectLiteralToLambda")
override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {

    return object : Interceptor {

        override fun intercept(chain: Interceptor.Chain): Response {

            var request = chain.request()

            // FIX encoded slash issue
            // %2F -> /
            val fixedUrl = request.url.toString()
                .replace(Regex("(?i)%2f"), "/")

            // Rebuild request with fixed URL
            request = request.newBuilder()
                .url(fixedUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; Pixel 3 XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
                )
                .build()

            return chain.proceed(request)
        }
    }
}

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Parses `url|Header1=val1|Header2=val2` format. */
    private fun parseStreamLink(link: String): Pair<String, Map<String, String>> {
        val parts = link.split("|")
        var url = parts.firstOrNull()?.trim() ?: ""
        url = url.replace("%2F", "/")
        val headers = parts.drop(1).mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq > 0) part.substring(0, eq).trim() to part.substring(eq + 1).trim()
            else null
        }.toMap()
        return url to headers
    }

    private fun hexToBase64(hex: String): String {
        val bytes = hex.replace("-", "").chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
