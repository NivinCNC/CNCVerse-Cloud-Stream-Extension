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
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class LiveEventsProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
        private var cachedWebUrl: String? = null
        private const val DEFAULT_WEB_URL = "https://welalagaa.site"
    }

    override var mainUrl = DEFAULT_WEB_URL
    override var name = "⚡SKTech Live Events"
    override var lang = "ta"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val client =
            OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

    /**
     * Gets the web URL from Firebase Remote Config
     * Falls back to default URL if Firebase fetch fails
     */
    private suspend fun getWebUrl(): String {
        // Return cached URL if available
        cachedWebUrl?.let { return it }
        
        // Try to fetch from Firebase Remote Config
        val firebaseUrl = FirebaseRemoteConfigFetcher.getBaseApiUrl()
        if (!firebaseUrl.isNullOrBlank()) {
            cachedWebUrl = firebaseUrl
            mainUrl = cachedWebUrl!!
            return cachedWebUrl!!
        }
        
        // Fall back to default URL
        cachedWebUrl = DEFAULT_WEB_URL
        mainUrl = DEFAULT_WEB_URL
        return DEFAULT_WEB_URL
    }

    // Data classes for stream response from /channels/{slug}.txt
    data class ChannelStreamResponse(
            val streamUrls: List<StreamUrl>?,
            val related: List<LiveEventData>?,
            val prevChannel: String?,
            val nextChannel: String?
    )

    data class StreamUrl(
            val name: String?, // Server name
            val link: String?, // Direct stream URL (may contain | for headers)
            val scheme: Int?, // 0 = direct link, other values may indicate different stream types
            val api: String?, // DRM key or additional API info
            val tokenApi: String? // JSON string with token fetching instructions
    )

    // Data class for parsing tokenApi JSON
    data class TokenApiConfig(
            val url: String?,
            val api: String?,
            val type: String?,
            val link_key: String?,
            val default_string: String?,
            val request_type: String?,
            val request_body_type: String?,
            val ip_api: String?
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
        SmartlinkHelper.ping(context)
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
        SmartlinkHelper.ping(context)
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
        println("SKTech: Fetched stream response for slug ${loadData.slug}: ${streamResponse} streams")
        if (streamResponse?.streamUrls.isNullOrEmpty()) {
            return false
        }

        streamResponse.streamUrls.forEach { stream ->
            val serverName = stream.name ?: "Server"
            
            // Check if we need to fetch URL from tokenApi
            val streamLink = if (!stream.tokenApi.isNullOrBlank() && stream.link.isNullOrBlank()) {
                try {
                    // Parse tokenApi config and fetch the actual stream URL
                    val tokenConfig = parseJson<TokenApiConfig>(stream.tokenApi)
                    fetchStreamFromTokenApi(tokenConfig) ?: return@forEach
                } catch (e: Exception) {
                    println("SKTech: Failed to parse tokenApi: ${e.message}")
                    return@forEach
                }
            } else {
                stream.link ?: return@forEach
            }

            // Parse the link - may contain headers after |
            val (url, headers) = parseStreamLink(streamLink)

            if (url.isBlank()) return@forEach

            try {
                // Determine link type based on URL content
                when {
                    url.contains(".mpd") -> {
                        // DASH/MPD stream
                        val drmInfo = stream.api?.split(":")
                        if (drmInfo != null && drmInfo.size == 2) {
                            // MPD with DRM keys
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
                                            url,
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
                                            url = url,
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
                        val finalHeaders = headers.toMutableMap()
                        if (!finalHeaders.containsKey("User-Agent")) {
                            finalHeaders["User-Agent"] = "Mozilla/5.0 (Linux; Android 10; Pixel 3 XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
                        }
                        callback.invoke(
                                newExtractorLink(
                                        source = this.name,
                                        name = serverName,
                                        url = url,
                                        type = ExtractorLinkType.M3U8
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

    /** Fetches stream URLs from /channels/{slug}.txt using SKLive decryption */
    private suspend fun fetchChannelStreams(slug: String): ChannelStreamResponse? {
        return withContext(Dispatchers.IO) {
            try {
                // Get the web URL from Firebase Remote Config (or use cached/default)
                val webUrl = getWebUrl()
                val url = "$webUrl/$slug.txt"

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
                        println("SKTech: Fetched encrypted channel data for $slug: ${encryptedData.length} chars")
                        
                        // Use SKLive decryption
                        val decryptedData = SKLiveCryptoUtils.decryptSKLive(encryptedData.trim())
                        if (!decryptedData.isNullOrBlank()) {
                            println("SKTech: Decrypted channel data successfully")
                            println("SKTech: Decrypted data: ${decryptedData.take(200)}")
                            
                            // API returns direct array of StreamUrl objects, not a wrapper
                            return@withContext try {
                                val streamUrls = parseJson<List<StreamUrl>>(decryptedData)
                                ChannelStreamResponse(
                                    streamUrls = streamUrls,
                                    related = null,
                                    prevChannel = null,
                                    nextChannel = null
                                )
                            } catch (e: Exception) {
                                println("SKTech: Failed to parse stream URLs: ${e.message}")
                                null
                            }
                        } else {
                            println("SKTech: Failed to decrypt channel data for $slug")
                        }
                    }
                } else {
                    println("SKTech: HTTP error ${response.code} fetching channel $slug")
                }
            } catch (e: Exception) {
                println("SKTech: Exception fetching channel streams: ${e.message}")
                e.printStackTrace()
            }
            null
        }
    }

    /**
     * Fetches the actual stream URL from a tokenApi configuration
     * Handles different types: token, embed, json, sp, html, yt, ls
     */
    private suspend fun fetchStreamFromTokenApi(config: TokenApiConfig): String? {
        return withContext(Dispatchers.IO) {
            try {
                println("SKTech: Fetching stream from tokenApi type=${config.type}")
                
                return@withContext when (config.type?.lowercase()) {
                    "embed" -> handleEmbedExtraction(config)
                    "json", "sp" -> handleJsonExtraction(config)
                    "html" -> handleHtmlExtraction(config)
                    "yt" -> handleYoutubeExtraction(config)
                    "ls" -> handleLocationServiceExtraction(config)
                    else -> handleDirectApiCall(config)
                }
            } catch (e: Exception) {
                println("SKTech: Exception in fetchStreamFromTokenApi: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Handle embed type: fetches from API, then loads in WebView and intercepts final streaming URL
     */
    private suspend fun handleEmbedExtraction(config: TokenApiConfig): String? {
        return withContext(Dispatchers.IO) {
            try {
                val embedUrl = config.url?.takeIf { it.isNotBlank() } ?: config.api?.takeIf { it.isNotBlank() }
                if (embedUrl.isNullOrBlank()) {
                    return@withContext null
                }

                if (config.url.isNullOrBlank()) {
                    println("SKTech: Embed URL empty, using API URL as embed URL: $embedUrl")
                }

                // If we got a URL that looks like a stream, return it directly
                if (embedUrl.contains(".m3u8") || embedUrl.contains(".mpd") || 
                    embedUrl.contains(".mp4") || embedUrl.contains(".ts") || 
                    embedUrl.contains(".mkv") || embedUrl.contains(".webm")) {
                    println("SKTech: Embed URL is already a stream: $embedUrl")
                    return@withContext embedUrl
                }

                // Otherwise, load in WebView to intercept streaming requests
                return@withContext loadEmbedInWebView(embedUrl, config)
            } catch (e: Exception) {
                println("SKTech: Exception in handleEmbedExtraction: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Load embed page in WebView and intercept streaming requests
     */
    private suspend fun loadEmbedInWebView(embedUrl: String, config: TokenApiConfig): String? {
        return withContext(Dispatchers.Main) {
            suspendCoroutine { continuation ->
                try {
                    val context = LiveEventsProvider.context
                    if (context == null) {
                        println("SKTech: No context available for WebView")
                        continuation.resume(null)
                        return@suspendCoroutine
                    }

                    val webView = WebView(context)
                    val settings = webView.settings
                    
                    // Configure WebView for media playback
                    settings.javaScriptEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.domStorageEnabled = true
                    settings.allowContentAccess = true
                    settings.allowFileAccess = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.mediaPlaybackRequiresUserGesture = false

                    var urlCaptured = false
                    var capturedUrl: String? = null

                    // Create bridge object to receive URLs from JavaScript
                    val bridge = object {
                        @android.webkit.JavascriptInterface
                        fun onStreamUrlFound(url: String) {
                            println("SKTech: JavaScript bridge received stream URL: $url")
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

                    // Add JavaScript interface
                    webView.addJavascriptInterface(bridge, "StreamBridge")

                    // Set WebViewClient to intercept streaming URLs
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                            val url = request.url.toString()
                            
                            // Intercept streaming URLs (m3u8, mpd, mp4, ts, mkv, webm)
                            if (url.contains(".m3u8") || url.contains(".mpd") || url.contains(".mp4") || 
                                url.contains(".ts") || url.contains(".mkv") || url.contains(".webm")) {
                                println("SKTech: Intercepted streaming URL from WebView: $url")
                                if (!urlCaptured) {
                                    urlCaptured = true
                                    capturedUrl = url
                                    // Cleanup and resume
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
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            super.onPageFinished(view, pageUrl)
                            println("SKTech: WebView page finished loading: $pageUrl")
                            
                            // If no URL was captured, inject JavaScript to extract playbackURL
                            if (!urlCaptured) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    println("SKTech: Injecting JavaScript to extract stream URL")
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
                                        println("SKTech: Error injecting JavaScript: ${e.message}")
                                    }
                                }, 500)
                            }
                            
                            // Wait a bit more for dynamic content
                            if (!urlCaptured) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (!urlCaptured) {
                                        println("SKTech: No streaming URL found after page load timeout")
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

                    println("SKTech: Loading embed in WebView")
                    
                        println("SKTech: Loading URL: $embedUrl")
                        webView.loadUrl(embedUrl)
                    
                    // Timeout after 30 seconds
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!urlCaptured && capturedUrl == null) {
                            println("SKTech: Embed WebView extraction timeout after 30s")
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
                    println("SKTech: Exception in loadEmbedInWebView: ${e.message}")
                    e.printStackTrace()
                    continuation.resume(null)
                }
            }
        }
    }

    /**
     * Handle JSON/SP type: parse JSON response and extract stream URL
     */
    private suspend fun handleJsonExtraction(config: TokenApiConfig): String? {
        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = config.api ?: return@withContext null
                println("SKTech: Fetching JSON stream from: $apiUrl")
                
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body.string()
                    println("SKTech: JSON response: ${responseBody.take(200)}")
                    
                    // Try to extract using link_key
                    if (!config.link_key.isNullOrBlank()) {
                        try {
                            val json = parseJson<Map<String, Any>>(responseBody)
                            val streamUrl = json[config.link_key] as? String
                            if (!streamUrl.isNullOrBlank()) {
                                println("SKTech: Extracted JSON stream URL: $streamUrl")
                                return@withContext streamUrl
                            }
                        } catch (e: Exception) {
                            println("SKTech: Failed to parse JSON response: ${e.message}")
                        }
                    }
                    
                    return@withContext responseBody.trim()
                }
            } catch (e: Exception) {
                println("SKTech: Exception in handleJsonExtraction: ${e.message}")
            }
            null
        }
    }

    /**
     * Handle HTML type: parse HTML response using regex patterns
     */
    private suspend fun handleHtmlExtraction(config: TokenApiConfig): String? {
        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = config.api ?: return@withContext null
                println("SKTech: Fetching HTML stream from: $apiUrl")
                
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body.string()
                    
                    // Try to extract URL from HTML using common patterns
                    val patterns = listOf(
                        "player\\.load\\(\\{[^}]*source:\\s*\"([^\"]+)\"",
                        "src\\s*=\\s*['\"]([^'\"]*\\.m3u8[^'\"]*)['\"]",
                        "url\\s*:\\s*['\"]([^'\"]*\\.mpd[^'\"]*)['\"]"
                    )
                    
                    for (pattern in patterns) {
                        val regex = Regex(pattern)
                        val match = regex.find(responseBody)
                        if (match != null) {
                            val url = match.groupValues[1]
                            println("SKTech: Extracted HTML stream URL: $url")
                            return@withContext url
                        }
                    }
                    
                    println("SKTech: No streaming URL found in HTML response")
                }
            } catch (e: Exception) {
                println("SKTech: Exception in handleHtmlExtraction: ${e.message}")
            }
            null
        }
    }

    /**
     * Handle YouTube type: prepare for yt-dlp extraction
     */
    private suspend fun handleYoutubeExtraction(config: TokenApiConfig): String? {
        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = config.url ?: config.api ?: return@withContext null
                println("SKTech: YouTube URL for extraction: $apiUrl")
                
                // Return the URL for external yt-dlp processing
                // Cloudstream3 will handle YouTube extraction internally
                return@withContext apiUrl
            } catch (e: Exception) {
                println("SKTech: Exception in handleYoutubeExtraction: ${e.message}")
            }
            null
        }
    }

    /**
     * Handle Location Service type: resolve region-based stream URL
     */
    private suspend fun handleLocationServiceExtraction(config: TokenApiConfig): String? {
        return withContext(Dispatchers.IO) {
            try {
                val ipApiUrl = config.ip_api?.let { 
                    if (it.startsWith("aHR0")) {
                        // Base64 encoded
                        String(Base64.decode(it, Base64.DEFAULT))
                    } else {
                        it
                    }
                } ?: "https://ip-api.streamingucms.com/"
                
                println("SKTech: Resolving location service from: $ipApiUrl")
                
                val request = Request.Builder()
                    .url(ipApiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body.string()
                    println("SKTech: Location service response: $responseBody")
                    
                    // Try to extract stream URL from response
                    if (!config.link_key.isNullOrBlank()) {
                        try {
                            val json = parseJson<Map<String, Any>>(responseBody)
                            val streamUrl = json[config.link_key] as? String
                            if (!streamUrl.isNullOrBlank()) {
                                println("SKTech: Extracted location-based stream URL: $streamUrl")
                                return@withContext streamUrl
                            }
                        } catch (e: Exception) {
                            // Continue
                        }
                    }
                    
                    return@withContext responseBody.trim()
                }
            } catch (e: Exception) {
                println("SKTech: Exception in handleLocationServiceExtraction: ${e.message}")
            }
            null
        }
    }

    /**
     * Handle direct API call (token or unknown type)
     */
    private suspend fun handleDirectApiCall(config: TokenApiConfig): String? {
        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = config.api ?: return@withContext null
                println("SKTech: Direct API call to: $apiUrl")
                
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body.string()
                    println("SKTech: Direct API response: ${responseBody.take(200)}")
                    
                    // Try to extract using link_key
                    if (!config.link_key.isNullOrBlank()) {
                        try {
                            val json = parseJson<Map<String, Any>>(responseBody)
                            val streamUrl = json[config.link_key] as? String
                            if (!streamUrl.isNullOrBlank()) {
                                println("SKTech: Extracted stream URL from API: $streamUrl")
                                return@withContext streamUrl
                            }
                        } catch (e: Exception) {
                            // Continue
                        }
                    }
                    
                    return@withContext responseBody.trim()
                }
            } catch (e: Exception) {
                println("SKTech: Exception in handleDirectApiCall: ${e.message}")
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
