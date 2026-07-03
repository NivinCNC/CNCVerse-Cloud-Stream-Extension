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
import okhttp3.OkHttpClient
import okhttp3.Request
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import android.content.Intent
import android.net.Uri

/**
 * SportzxLiveEventsProvider
 *
 * Structured identically to SKTechProvider's LiveEventsProvider, but:
 *  - Uses [SportzxProviderManager] for data fetching
 *  - Uses [SportzxCryptoUtils] for decryption (FNV + AES-CBC from sportzx.py)
 *  - Parses the flat (non-double-encoded) Sportzx API format
 *  - Event channel streams come from /channels/{id}.json (not slug-based .txt)
 */
class SportzxLiveEventsProvider : MainAPI() {

    companion object {
    }

    override var mainUrl = "https://sportzx.live"
    override var name = "⚡SportzX Live Events"
    override var lang = "ta"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Load data passed through search → load → loadLinks ───────────────────

    data class SportzxLoadData(
        val eventId: Int,
        val title: String,
        val poster: String,
        val cat: String?,
        val formats: List<SportzxLiveEventFormat>,
        val eventInfo: SportzxLiveEventInfo?
    )

    // ── Event helpers ─────────────────────────────────────────────────────────

    private fun createDisplayTitle(event: SportzxLiveEventData): String {
        val info = event.eventInfo
        return if (info != null &&
                   !info.teamA.isNullOrBlank() &&
                   !info.teamB.isNullOrBlank()) {
            if (info.teamA == info.teamB) info.teamA
            else "${info.teamA} vs ${info.teamB}"
        } else {
            event.title
        }
    }

    private fun getEventStatus(event: SportzxLiveEventData): String {
        val info = event.eventInfo ?: return ""
        val now  = System.currentTimeMillis()
        return try {
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val start = info.startTime?.let { fmt.parse(it)?.time }
            val end   = info.endTime?.let   { fmt.parse(it)?.time }
            when {
                end   != null && now >= end   -> "✅"
                start != null && now >= start -> "🔴"
                start != null && now < start  -> "🔜"
                else                          -> ""
            }
        } catch (e: Exception) { "" }
    }

    private fun isEventLive(event: SportzxLiveEventData): Boolean {
        val info = event.eventInfo ?: return false
        val now  = System.currentTimeMillis()
        return try {
            val fmt   = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val start = info.startTime?.let { fmt.parse(it)?.time }
            val end   = info.endTime?.let   { fmt.parse(it)?.time }
            if (end != null && now >= end) false
            else start != null && now >= start
        } catch (e: Exception) { false }
    }

    private fun isEventEnded(event: SportzxLiveEventData): Boolean {
        val info = event.eventInfo ?: return false
        val now  = System.currentTimeMillis()
        return try {
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val end = info.endTime?.let { fmt.parse(it)?.time }
            end != null && now >= end
        } catch (e: Exception) { false }
    }

    /** Generates the CNCVerse match-card poster URL. */
    private fun generateMatchCardUrl(event: SportzxLiveEventData): String {
        val info = event.eventInfo
        val title   = java.net.URLEncoder.encode(info?.eventName ?: event.title, "UTF-8")
        val teamA   = java.net.URLEncoder.encode(info?.teamA ?: "Team A", "UTF-8")
        val teamB   = java.net.URLEncoder.encode(info?.teamB ?: "Team B", "UTF-8")
        val teamAImg = info?.teamAFlag ?: ""
        val teamBImg = info?.teamBFlag ?: ""
        val eventLogo = info?.eventLogo ?: ""
        val isLive  = isEventLive(event)
        val isEnded = isEventEnded(event)
        val time = try {
            info?.startTime?.let {
                val df = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                val disp = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US)
                val d = df.parse(it)
                d?.let { dd -> java.net.URLEncoder.encode(disp.format(dd), "UTF-8") } ?: ""
            } ?: ""
        } catch (e: Exception) { "" }

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

    // ── Popups (subscription / telegram) ─────────────────────────────────────

   

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
                    setPadding((24*dp).toInt(),(20*dp).toInt(),(24*dp).toInt(),(16*dp).toInt())
                    background = bgDraw
                }
                val titleTv = android.widget.TextView(ctx).apply {
                    text = "\uD83D\uDCAC Join CNCVerse Community"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 17f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1,-2)
                        .also { it.bottomMargin = (10*dp).toInt() }
                }
                val dividerV = android.view.View(ctx).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#2D2D4A"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1,1)
                        .also { it.bottomMargin = (14*dp).toInt() }
                }
                val msgTv = android.widget.TextView(ctx).apply {
                    text = "Join our Telegram group to discuss and share your opinion!"
                    setTextColor(android.graphics.Color.parseColor("#A0A0A8"))
                    textSize = 14f; setLineSpacing(0f, 1.4f)
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1,-2)
                        .also { it.bottomMargin = (18*dp).toInt() }
                }
                val btnRow = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.END
                }
                val laterTv = android.widget.TextView(ctx).apply {
                    text = "Later"
                    setTextColor(android.graphics.Color.parseColor("#808090")); textSize = 14f
                    val p = (10*dp).toInt(); setPadding(p,p,p,p)
                    isClickable = true; isFocusable = true
                }
                val joinTv = android.widget.TextView(ctx).apply {
                    text = "Join Telegram"
                    setTextColor(android.graphics.Color.parseColor("#5B9BF5")); textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val p = (10*dp).toInt(); setPadding(p,p,0,p)
                    isClickable = true; isFocusable = true
                }
                btnRow.addView(laterTv); btnRow.addView(joinTv)
                root.addView(titleTv); root.addView(dividerV); root.addView(msgTv)
                root.addView(btnRow)
                val dialog = android.app.AlertDialog.Builder(ctx)
                    .setView(root).setCancelable(true).create()
                dialog.window?.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                laterTv.setOnClickListener { dialog.dismiss() }
                joinTv.setOnClickListener {
                    dialog.dismiss()
                    try {
                        val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://t.me/cncverse"))
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

    // ── Main page ─────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val events = SportzxProviderManager.fetchLiveEvents()

        val grouped = events.groupBy { it.eventInfo?.eventCat ?: it.cat ?: "Other" }

        val homePageLists = grouped.map { (category, categoryEvents) ->
            val icon = when (category.lowercase()) {
                "cricket"    -> "🏏"
                "football"   -> "⚽"
                "basketball" -> "🏀"
                "ice hockey" -> "🏒"
                "boxing"     -> "🥊"
                "ufc"        -> "🥊"
                "motorsport","f1" -> "🏎️"
                "tennis"     -> "🎾"
                "motogp"     -> "🏍️"
                "wwe"        -> "🤼"
                else         -> "📺"
            }

            val searchResponses = categoryEvents
                .sortedWith(
                    compareBy<SportzxLiveEventData> { event ->
                        val status = getEventStatus(event)
                        when {
                            status.contains("🔴") -> 0
                            status.contains("🔜") -> 1
                            status.contains("✅") -> 2
                            else -> 3
                        }
                    }.thenBy { event ->
                        try {
                            val info = event.eventInfo ?: return@thenBy Long.MAX_VALUE
                            val startTime = info.startTime ?: return@thenBy Long.MAX_VALUE
                            val fmt = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", java.util.Locale.US)
                            fmt.parse(startTime)?.time ?: Long.MAX_VALUE
                        } catch (e: Exception) { Long.MAX_VALUE }
                    }
                )
                .map { event ->
                    val displayTitle = createDisplayTitle(event)
                    val status = getEventStatus(event)
                    val fullTitle = if (status.isNotBlank()) "$status $displayTitle" else displayTitle
                    val posterUrl = generateMatchCardUrl(event)

                    val loadData = SportzxLoadData(
                        eventId   = event.eventId,
                        title     = displayTitle,
                        poster    = posterUrl,
                        cat       = event.cat,
                        formats   = event.formats ?: emptyList(),
                        eventInfo = event.eventInfo
                    )

                    newLiveSearchResponse(
                        name = fullTitle,
                        url  = loadData.toJson(),
                        type = TvType.Live
                    ) { this.posterUrl = posterUrl }
                }

            HomePageList(
                name = "$icon $category",
                list = searchResponses,
                isHorizontalImages = true
            )
        }.sortedBy { list ->
            when {
                list.name.contains("Cricket",    ignoreCase = true) -> 0
                list.name.contains("Football",   ignoreCase = true) -> 1
                list.name.contains("Basketball", ignoreCase = true) -> 2
                else                                                 -> 10
            }
        }

        return newHomePageResponse(homePageLists, hasNext = false)
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val events = SportzxProviderManager.fetchLiveEvents()
        return events.filter { event ->
            listOfNotNull(
                event.title,
                event.eventInfo?.teamA,
                event.eventInfo?.teamB,
                event.eventInfo?.eventName
            ).joinToString(" ").contains(query, ignoreCase = true)
        }.map { event ->
            val displayTitle = createDisplayTitle(event)
            val status = getEventStatus(event)
            val fullTitle = if (status.isNotBlank()) "$status $displayTitle" else displayTitle
            val posterUrl = generateMatchCardUrl(event)

            val loadData = SportzxLoadData(
                eventId   = event.eventId,
                title     = displayTitle,
                poster    = posterUrl,
                cat       = event.cat,
                formats   = event.formats ?: emptyList(),
                eventInfo = event.eventInfo
            )

            newLiveSearchResponse(
                name = fullTitle,
                url  = loadData.toJson(),
                type = TvType.Live
            ) { this.posterUrl = posterUrl }
        }
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<SportzxLoadData>(url)
        val info = data.eventInfo
        val plot = buildString {
            info?.let {
                it.eventType?.takeIf { t -> t != "null" }?.let { t -> append("📌 $t\n") }
                it.eventName?.let { n -> append("🏆 $n\n") }
                it.startTime?.let { st ->
                    try {
                        val df   = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                        val disp = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
                        val d = df.parse(st)
                        d?.let { dd -> append("🕐 ${disp.format(dd)}\n") }
                    } catch (e: Exception) { append("🕐 $st\n") }
                }
            }
            append("\n📡 Available Servers: ${data.formats.size}")
        }

        return newLiveStreamLoadResponse(name = data.title, url = url, dataUrl = url) {
            this.posterUrl = data.poster
            this.plot = plot
        }
    }

    // ── Load links ────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val streams = try {
            parseJson<List<SportzxStreamEntry>>(streamJson)
        } catch (e: Exception) {
            println("Sportzx: Failed to parse stream list — ${e.message}")
            return false
        }

        if (streams.isEmpty()) return false

        streams.forEach { stream ->
            val serverName = stream.title ?: "Server"
            val link = stream.link ?: return@forEach
            val parts = link.split("|", limit = 2)
            val url   = parts[0].trim()
            if (url.isBlank()) return@forEach

            // Parse headers after |
            val headers = mutableMapOf<String, String>()
            if (parts.size > 1) {
                parts[1].split("&").forEach { kv ->
                    val eq = kv.split("=", limit = 2)
                    if (eq.size == 2) {
                        val k = when (eq[0].trim().lowercase()) {
                            "user-agent" -> "User-Agent"
                            "referer"    -> "Referer"
                            "origin"     -> "Origin"
                            "cookie"     -> "Cookie"
                            else         -> eq[0].trim()
                        }
                        headers[k] = eq[1].trim()
                    }
                }
            }

            try {
                when {
                    url.contains(".mpd") -> {
                        if (!drmParts.isNullOrEmpty() && drmParts.size == 2 &&
                            drmParts[0].isNotBlank() && drmParts[1].isNotBlank()) {

                            val kidHex = drmParts[0].trim().replace("-", "")
                            val keyHex = drmParts[1].trim().replace("-", "")

                            fun hexToBase64Url(hex: String): String? = try {
                                val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                                Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                            } catch (_: Exception) { null }

                            val kidB64 = hexToBase64Url(kidHex)
                            val keyB64 = hexToBase64Url(keyHex)

                            if (kidB64 != null && keyB64 != null) {
                                callback.invoke(
                                    newDrmExtractorLink(
                                        this.name,
                                        serverName,
                                        url,
                                        INFER_TYPE,
                                        CLEARKEY_UUID
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.key = keyB64
                                        this.kid = kidB64
                                        if (headers.isNotEmpty()) this.headers = headers
                                    }
                                )
                            } else {
                                // hex conversion failed — deliver as plain DASH
                                callback.invoke(
                                    newExtractorLink(this.name, serverName, url, ExtractorLinkType.DASH) {
                                        this.quality = Qualities.Unknown.value
                                        if (headers.isNotEmpty()) this.headers = headers
                                    }
                                )
                            }
                        } else {
                            // No api / empty — plain DASH without DRM keys
                            callback.invoke(
                                newExtractorLink(this.name, serverName, url, ExtractorLinkType.DASH) {
                                    this.quality = Qualities.Unknown.value
                                    if (headers.isNotEmpty()) this.headers = headers
                                }
                            )
                        }
                    }
                    else -> {
                        // HLS / M3U8
                        val finalHeaders = headers.toMutableMap()
                        if (!finalHeaders.containsKey("User-Agent")) {
                            finalHeaders["User-Agent"] =
                                "Mozilla/5.0 (Linux; Android 10; Pixel 3 XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
                        }
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name   = serverName,
                                url    = url,
                                type   = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.Unknown.value
                                if (finalHeaders.isNotEmpty()) this.headers = finalHeaders
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return true
    }
}
