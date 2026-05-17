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

class LiveEventsProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://cfyhljddgbkkufh82.top"
    override var name = "⚡Cricify Live Events"
    override var lang = "ta"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val client =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

    // Data classes for stream response from /channels/{slug}.txt
    data class ChannelStreamResponse(
            val streamUrls: List<StreamUrl>?,
            val related: List<LiveEventData>?,
            val prevChannel: String?,
            val nextChannel: String?
    )

    data class StreamUrl(
            val api: String?, // DRM key in format "key:kid" or empty
            val id: Int?,
            val link: String?, // Stream URL (may contain | for headers)
            val title: String?, // Server name
            val type: String?, // "0" = m3u8, "7" = mpd with DRM
            val webLink: String?
    )

    // Load data class for passing event info
    data class LiveEventLoadData(
            val eventId: Int,
            val title: String,
            val poster: String,
            val slug: String,
            val formats: List<LiveEventFormat>,
            val eventInfo: LiveEventInfo?
    )

    // Create display title with match info
    private fun createDisplayTitle(event: LiveEventData): String {
        val eventInfo = event.eventInfo
        return if (eventInfo != null &&
                        !eventInfo.teamA.isNullOrBlank() &&
                        !eventInfo.teamB.isNullOrBlank()
        ) {
            if (eventInfo.teamA == eventInfo.teamB) {
                // Same team means it's a show/event, not a match
                eventInfo.teamA
            } else {
                "${eventInfo.teamA} vs ${eventInfo.teamB}"
            }
        } else {
            event.title
        }
    }

   // Get event status (Live, Upcoming, Ended)
    private fun getEventStatus(event: LiveEventData): String {
        val eventInfo = event.eventInfo ?: return ""
        val now = System.currentTimeMillis()

        try {
            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val startTime = eventInfo.startTime?.let { dateFormat.parse(it)?.time }
            val endTime = eventInfo.endTime?.let { dateFormat.parse(it)?.time }

            // Apply exact logic from official app (rc.c.p and rc.c.o):
            // 1. If end_time exists and has passed -> "ended"
            // 2. Else if start_time exists and has passed -> "live"
            // 3. Else -> "upcoming"
            return when {
                endTime != null && now >= endTime -> "✅"
                startTime != null && now >= startTime -> "🔴"
                startTime != null && now < startTime -> "🔜"
                else -> ""
            }
        } catch (e: Exception) {
            return ""
        }
    }

    // Check if event is currently live
    private fun isEventLive(event: LiveEventData): Boolean {
        val eventInfo = event.eventInfo ?: return false
        val now = System.currentTimeMillis()
        
        return try {
            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val startTime = eventInfo.startTime?.let { dateFormat.parse(it)?.time }
            val endTime = eventInfo.endTime?.let { dateFormat.parse(it)?.time }
            
            // Logic:
            // If end_time has passed -> false
            // Else if start_time has passed -> true
            // Else -> false
            if (endTime != null && now >= endTime) {
                false
            } else if (startTime != null && now >= startTime) {
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

        /**
     * Check if event has ended based on its end date/time
     */
    private fun isEventEnded(event: LiveEventData): Boolean {
        val eventInfo = event.eventInfo ?: return false
        val now = System.currentTimeMillis()

        return try {
            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val endTime = eventInfo.endTime?.let { dateFormat.parse(it)?.time }
            endTime != null && now >= endTime
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Generate a match card poster URL using the CNCVerse API
     * API: https://live-card-cncverse.vercel.app/api/match-card
     */
    private fun generateMatchCardUrl(event: LiveEventData): String {
        val eventInfo = event.eventInfo
        
        val title = java.net.URLEncoder.encode(eventInfo?.eventName ?: event.title, "UTF-8")
        val teamA = java.net.URLEncoder.encode(eventInfo?.teamA ?: "Team A", "UTF-8")
        val teamB = java.net.URLEncoder.encode(eventInfo?.teamB ?: "Team B", "UTF-8")
        val teamAImg = eventInfo?.teamAFlag ?: ""
        val teamBImg = eventInfo?.teamBFlag ?: ""
        val eventLogo = eventInfo?.eventLogo ?: ""
        val isLive = isEventLive(event)
        val isEnded = isEventEnded(event)
        
        // Format time for display
        val time = try {
            eventInfo?.startTime?.let {
            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val displayFormat = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US)
            val date = dateFormat.parse(it)
            date?.let { d -> java.net.URLEncoder.encode(displayFormat.format(d), "UTF-8") } ?: ""
            } ?: ""
        } catch (e: Exception) {
            ""
        }
        
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Show star popup on first visit
        LiveEventsProvider.context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }

        // Fetch live events using ProviderManager (same as providers)
        val events = ProviderManager.fetchLiveEvents()

        // Group events by eventCat
        val groupedEvents = events.groupBy { it.eventInfo?.eventCat ?: it.cat ?: "Other" }

        val homePageLists =
                groupedEvents
                        .map { (category, categoryEvents) ->
                            val icon =
                                    when (category.lowercase()) {
                                        "cricket" -> "🏏"
                                        "football" -> "⚽"
                                        "basketball" -> "🏀"
                                        "ice hockey" -> "🏒"
                                        "boxing" -> "🥊"
                                        "motorsport" -> "🏎️"
                                        "tennis" -> "🎾"
                                        else -> "📺"
                                    }

                            val searchResponses =
                                    categoryEvents
                                    .sortedByDescending{ isEventLive(it) }
                                    .map { event ->
                                        val displayTitle = createDisplayTitle(event)
                                        val status = getEventStatus(event)
                                        val fullTitle =
                                                if (status.isNotBlank()) "$status $displayTitle"
                                                else displayTitle

                                        // Use match card API for poster
                                        val posterUrl = generateMatchCardUrl(event)

                                        val loadData =
                                                LiveEventLoadData(
                                                        eventId = event.id,
                                                        title = displayTitle,
                                                        poster = posterUrl,
                                                        slug = event.slug,
                                                        formats = event.formats ?: emptyList(),
                                                        eventInfo = event.eventInfo
                                                )

                                        newLiveSearchResponse(
                                                name = fullTitle,
                                                url = loadData.toJson(),
                                                type = TvType.Live
                                        ) { this.posterUrl = posterUrl }
                                    }

                            HomePageList(
                                    name = "$icon $category",
                                    list = searchResponses,
                                    isHorizontalImages = true
                            )
                        }
                        .sortedBy { list ->
                            // Sort categories: Live events first, then by category name
                            when {
                                list.name.contains("Cricket", ignoreCase = true) -> 0
                                list.name.contains("Football", ignoreCase = true) -> 1
                                list.name.contains("Basketball", ignoreCase = true) -> 2
                                else -> 10
                            }
                        }

        return newHomePageResponse(homePageLists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val events = ProviderManager.fetchLiveEvents()

        return events
                .filter { event ->
                    val searchText =
                            listOfNotNull(
                                            event.title,
                                            event.eventInfo?.teamA,
                                            event.eventInfo?.teamB,
                                            event.eventInfo?.eventName,
                                            event.eventInfo?.eventType
                                    )
                                    .joinToString(" ")

                    searchText.contains(query, ignoreCase = true)
                }
                .map { event ->
                    val displayTitle = createDisplayTitle(event)
                    val status = getEventStatus(event)
                    val fullTitle =
                            if (status.isNotBlank()) "$status $displayTitle" else displayTitle

                    // Use match card API for poster
                    val posterUrl = generateMatchCardUrl(event)

                    val loadData =
                            LiveEventLoadData(
                                    eventId = event.id,
                                    title = displayTitle,
                                    poster = posterUrl,
                                    slug = event.slug,
                                    formats = event.formats ?: emptyList(),
                                    eventInfo = event.eventInfo
                            )

                    newLiveSearchResponse(
                            name = fullTitle,
                            url = loadData.toJson(),
                            type = TvType.Live
                    ) { this.posterUrl = posterUrl }
                }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LiveEventLoadData>(url)

        val eventInfo = data.eventInfo
        val plot = buildString {
            eventInfo?.let { info ->
                info.eventType?.let { append("📌 $it\n") }
                info.eventName?.let { append("🏆 $it\n") }
                info.startTime?.let {
                    try {
                        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                        val displayFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
                        val date = dateFormat.parse(it)
                        date?.let { d -> append("🕐 ${displayFormat.format(d)}\n") }
                    } catch (e: Exception) {
                        append("🕐 $it\n")
                    }
                }
            }
            append("\n📡 Available Servers: ${data.formats.size}")
        }

        return newLiveStreamLoadResponse(name = data.title, url = url, dataUrl = url) {
            this.posterUrl = data.poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LiveEventLoadData>(data)

        // Fetch stream URLs from /channels/{slug}.txt
        val streamResponse = fetchChannelStreams(loadData.slug)

        if (streamResponse?.streamUrls.isNullOrEmpty()) {
            return false
        }

        streamResponse.streamUrls.forEach { stream ->
            val serverName = stream.title ?: "Server"
            val streamLink = stream.link ?: return@forEach

            // Parse the link - may contain headers after |
            val (url, headers) = parseStreamLink(streamLink)

            if (url.isBlank()) return@forEach

            val resolvedUrl = resolveEmbedUrlIfNeeded(url) ?: return@forEach

            try {
                when (stream.type) {
                    "7" -> {
                        // MPD with DRM (ClearKey)
                        val drmInfo = stream.api?.split(":")
                        if (drmInfo != null && drmInfo.size == 2) {
                            val drmKidBytes =
                                    drmInfo[0]
                                            .replace("-", "")
                                            .chunked(2)
                                            .map { it.toInt(16).toByte() }
                                            .toByteArray()
                            val drmKidBase64 =
                                    Base64.encodeToString(
                                            drmKidBytes,
                                            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                                    )
                            val drmKeyBytes =
                                    drmInfo[1]
                                            .replace("-", "")
                                            .chunked(2)
                                            .map { it.toInt(16).toByte() }
                                            .toByteArray()
                            val drmKeyBase64 = Base64.encodeToString(
                                drmKeyBytes,
                                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                            )
                            callback.invoke(
                                    newDrmExtractorLink(
                                            this.name,
                                            serverName,
                                            resolvedUrl,
                                            INFER_TYPE,
                                            CLEARKEY_UUID
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.key = drmKeyBase64
                                        this.kid = drmKidBase64
                                        if (headers.isNotEmpty()) {
                                            this.headers = headers
                                        }
                                    }
                            )
                        } else {
                            // MPD without keys - regular dash
                            callback.invoke(
                                    newExtractorLink(
                                            source = this.name,
                                            name = serverName,
                                        url = resolvedUrl,
                                            type = ExtractorLinkType.DASH
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        if (headers.isNotEmpty()) {
                                            this.headers = headers
                                        }
                                    }
                            )
                        }
                    }
                    else -> {
                        // M3U8 or other types
                        val linkType =
                                if (resolvedUrl.contains(".mpd")) {
                                    ExtractorLinkType.DASH
                                } else {
                                    ExtractorLinkType.M3U8
                                }

                        val finalHeaders = headers.toMutableMap()
                        if (linkType == ExtractorLinkType.M3U8 && !finalHeaders.containsKey("User-Agent")) {
                            finalHeaders["User-Agent"] = "Mozilla/5.0 (Linux; Android 10; Pixel 3 XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
                        }

                        callback.invoke(
                                newExtractorLink(
                                        source = this.name,
                                        name = serverName,
                                    url = resolvedUrl,
                                        type = linkType
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    if (finalHeaders.isNotEmpty()) {
                                        this.headers = finalHeaders
                                    }
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

    private fun isDirectStreamUrl(url: String): Boolean {
        return url.contains(".m3u8") || url.contains(".mpd") || url.contains(".mp4") ||
                url.contains(".ts") || url.contains(".mkv") || url.contains(".webm")
    }

    private suspend fun resolveEmbedUrlIfNeeded(url: String): String? {
        if (isDirectStreamUrl(url)) {
            return url
        }
        return loadEmbedInWebView(url)
    }

    /**
     * Load embed page in WebView and intercept streaming requests
     */
    private suspend fun loadEmbedInWebView(embedUrl: String): String? {
        return withContext(Dispatchers.Main) {
            suspendCoroutine { continuation ->
                try {
                    val context = LiveEventsProvider.context
                    if (context == null) {
                        continuation.resume(null)
                        return@suspendCoroutine
                    }

                    val webView = WebView(context)
                    val settings = webView.settings

                    settings.javaScriptEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.domStorageEnabled = true
                    settings.allowContentAccess = true
                    settings.allowFileAccess = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.mediaPlaybackRequiresUserGesture = false

                    var urlCaptured = false
                    var capturedUrl: String? = null

                    val bridge = object {
                        @android.webkit.JavascriptInterface
                        fun onStreamUrlFound(url: String) {
                            if (!urlCaptured && url.isNotBlank()) {
                                urlCaptured = true
                                capturedUrl = url
                                Handler(Looper.getMainLooper()).post {
                                    try {
                                        webView.destroy()
                                    } catch (e: Exception) {
                                        // Already destroyed
                                    }
                                    continuation.resume(url)
                                }
                            }
                        }
                    }

                    webView.addJavascriptInterface(bridge, "StreamBridge")

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                                view: WebView,
                                request: android.webkit.WebResourceRequest
                        ): android.webkit.WebResourceResponse? {
                            val requestUrl = request.url.toString()
                            if (isDirectStreamUrl(requestUrl)) {
                                if (!urlCaptured) {
                                    urlCaptured = true
                                    capturedUrl = requestUrl
                                    Handler(Looper.getMainLooper()).post {
                                        try {
                                            webView.destroy()
                                        } catch (e: Exception) {
                                            // Already destroyed
                                        }
                                        continuation.resume(requestUrl)
                                    }
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            super.onPageFinished(view, pageUrl)

                            if (!urlCaptured) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    try {
                                        val jsCode = """
                                            (function() {
                                                if (typeof playbackURL !== 'undefined' && playbackURL) {
                                                    window.StreamBridge.onStreamUrlFound(playbackURL);
                                                }
                                            })();
                                        """.trimIndent()
                                        webView.evaluateJavascript(jsCode, null)
                                    } catch (e: Exception) {
                                        // Ignore
                                    }
                                }, 500)
                            }

                            if (!urlCaptured) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (!urlCaptured) {
                                        try {
                                            webView.destroy()
                                        } catch (e: Exception) {
                                            // Already destroyed
                                        }
                                        continuation.resume(null)
                                    }
                                }, 3000)
                            }
                        }
                    }

                    webView.webChromeClient = WebChromeClient()
                    webView.loadUrl(embedUrl)

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!urlCaptured && capturedUrl == null) {
                            try {
                                webView.destroy()
                            } catch (e: Exception) {
                                // Already destroyed
                            }
                            try {
                                continuation.resume(null)
                            } catch (e: Exception) {
                                // Already resumed
                            }
                        }
                    }, 30000)
                } catch (e: Exception) {
                    continuation.resume(null)
                }
            }
        }
    }

    /** Fetches stream URLs from /channels/{slug}.txt */
    private suspend fun fetchChannelStreams(slug: String): ChannelStreamResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = ProviderManager.getBaseUrl()
                val url = "$baseUrl/channels/${slug.lowercase()}.txt"

                val request =
                        Request.Builder()
                                .url(url)
                                .header(
                                        "User-Agent",
                                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                                .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val encryptedData = response.body.string()
                    if (!encryptedData.isNullOrBlank()) {
                        val decryptedData = CryptoUtils.decryptData(encryptedData.trim())
                        if (!decryptedData.isNullOrBlank()) {
                            return@withContext parseJson<ChannelStreamResponse>(decryptedData)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            null
        }
    }

    /**
     * Parses stream link that may contain headers after | Format: url|Header1=value1&Header2=value2
     * Returns Pair(url, headers map)
     */
    private fun parseStreamLink(link: String): Pair<String, Map<String, String>> {
        val headers = mutableMapOf<String, String>()

        if (!link.contains("|")) {
            return Pair(link, headers)
        }

        val parts = link.split("|", limit = 2)
        val url = parts[0]

        if (parts.size > 1) {
            val headerPart = parts[1]
            // Parse headers: Header1=value1&Header2=value2
            headerPart.split("&").forEach { headerPair ->
                val keyValue = headerPair.split("=", limit = 2)
                if (keyValue.size == 2) {
                    val key = keyValue[0].trim()
                    val value = keyValue[1].trim()
                    // Convert common header names
                    val headerName =
                            when (key.lowercase()) {
                                "user-agent" -> "User-Agent"
                                "referer" -> "Referer"
                                "origin" -> "Origin"
                                "cookie" -> "Cookie"
                                else -> key
                            }
                    headers[headerName] = value
                }
            }
        }

        return Pair(url, headers)
    }
}
