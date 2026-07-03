package com.cncverse.M3UPlaylistPlayer

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import org.json.JSONObject
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

class HeaderReplacementInterceptor(private val customHeaders: Map<String, String>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestBuilder = originalRequest.newBuilder()

        // Remove existing headers that we want to replace
        customHeaders.keys.forEach { headerName ->
            requestBuilder.removeHeader(headerName)
        }

        // Add our custom headers
        customHeaders.forEach { (name, value) ->
            requestBuilder.addHeader(name, value)
        }

        return chain.proceed(requestBuilder.build())
    }
}

class LoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val bodyCopy = req.body
        val buffer = okio.Buffer()
        bodyCopy?.writeTo(buffer)
        return chain.proceed(req)
    }
}

class M3UPlaylistPlayer(
    private val customName: String,
    private val customMainUrl: String
) : MainAPI() {
    companion object {
        
        var context: android.content.Context? = null
        const val EXT_M3U = "#EXTM3U"
        const val EXT_INF = "#EXTINF"
        const val EXT_VLC_OPT = "#EXTVLCOPT"
        private const val OMG10 = "aHR0cHM6Ly9vbWcxMC5jb20vNC8xMTEwNDQ4OQ=="
        @Volatile private var lastBrowserOpenMs = 0L
        @Volatile private var telegramPopupShown = false
        @Volatile private var subscriptionPopupShown = false
        private const val BROWSER_DEBOUNCE_MS = 10_000L
    }
    
    override var lang = "en"
    override var mainUrl = customMainUrl
    override var name = customName
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Live,
    )
    private val headers = mapOf(
        "User-Agent" to "OTT Navigator/1.7.1.4 (Linux;Android 13; en; 1fin92n)",
    )

    private val customHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HeaderReplacementInterceptor(headers))
            .build()
    }

    private suspend fun getWithCustomHeaders(url: String): String {
        val request = Request.Builder()
            .url(url)
            .build()

        return customHttpClient.newCall(request).execute().use { response ->
            response.body.string()
        }
    }

    private fun String.base64ToHexOrNull(): String? {
        val raw = trim()
        val normalizedHex = raw.replace("-", "")
        if (normalizedHex.isNotEmpty() && normalizedHex.length % 2 == 0 && normalizedHex.matches(Regex("^[0-9a-fA-F]+$"))) {
            return normalizedHex.lowercase()
        }

        return try {
            val normalized = raw
                .replace('-', '+')
                .replace('_', '/')
                .let { value ->
                    val padding = (4 - (value.length % 4)) % 4
                    value + "=".repeat(padding)
                }
            val decoded = Base64.decode(normalized, Base64.DEFAULT)
            decoded.joinToString(separator = "") { byte -> "%02x".format(byte) }
        } catch (_: Exception) {
            null
        }
    }

    private fun String.hexToBase64UrlOrNull(): String? {
        val normalizedHex = trim().replace("-", "")
        if (normalizedHex.isEmpty() || normalizedHex.length % 2 != 0 || !normalizedHex.matches(Regex("^[0-9a-fA-F]+$"))) {
            return null
        }

        return try {
            val bytes = normalizedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: Exception) {
            null
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


    private fun getMpdStream(url: String, customHeaders: Map<String, String>): String {
        val client = OkHttpClient.Builder()
            .addInterceptor(HeaderReplacementInterceptor(customHeaders))
            .build()

        val request = Request.Builder()
            .url(url)
            .build()

        return client.newCall(request).execute().use { response ->
            response.body.string()
        }
    }

    private fun getDRMKeysFromLicenseServer(url: String, kid: String): String {
        val userAgent = "OTT Navigator/1.7.1.4 (Linux;Android 13; en; 1fin92n)"
        val client = OkHttpClient.Builder()
            .addInterceptor(HeaderReplacementInterceptor(
                mapOf(
                    "User-Agent" to userAgent,
                    "Content-Type" to "application/json",
                )
            ))
            .addInterceptor(LoggingInterceptor())
            .build()

        // Prepare the request body with the KID
        val json = "{\"kids\":[\"$kid\"],\"type\":\"temporary\"}"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        return client.newCall(request).execute().use { response ->
          // Parse the response to extract the DRM key
          val responseBody = response.body.string()
          val jsonResponse = parseJson<Map<String, Any>>(responseBody)
          @Suppress("UNCHECKED_CAST")
          val keys = jsonResponse["keys"] as? List<Map<String, String>> ?: return ""
          val matchedKey = keys.firstOrNull { it["kid"] == kid } ?: keys.firstOrNull() ?: return ""
          matchedKey["k"] ?: ""
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request : MainPageRequest
    ): HomePageResponse {         }
      
        val data = IptvPlaylistParser().parseM3U(decryptedContent)
        return newHomePageResponse(data.items.groupBy{it.attributes["group-title"]}.map { group ->
            val title = group.key ?: "Channels"
            val show = group.value.map { channel ->
                val streamurl = channel.url.toString()
                val channelname = channel.title.toString()
                val posterurl = channel.attributes["tvg-logo"].toString()
                val nation = channel.attributes["group-title"].toString()
                val key = channel.key ?: ""
                val keyid = channel.keyid ?: ""
                val userAgent = channel.userAgent ?: ""
                val cookie = channel.cookie ?: ""
                val licenseUrl = channel.licenseUrl ?: ""
                val headers = channel.headers
                newLiveSearchResponse(channelname, LoadData(streamurl, channelname, posterurl, nation, key, keyid, userAgent, cookie, licenseUrl, channel.drmKeys, headers).toJson(), TvType.Live)
                {
                    this.posterUrl = posterurl
                    this.apiName
                    this.lang = channel.attributes["group-title"]
                }
            }
            HomePageList(
                title,
                show,
                isHorizontalImages = true
            )
        }, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {        
        val rawContent = getWithCustomHeaders(mainUrl)
        val decryptedContent = decryptContent(rawContent)
        val data = IptvPlaylistParser().parseM3U(decryptedContent)
        return data.items.filter { it.title?.contains(query, ignoreCase = true) ?: false }.map { channel ->
            val streamurl = channel.url.toString()
            val channelname = channel.title.toString()
            val posterurl = channel.attributes["tvg-logo"].toString()
            val nation = channel.attributes["group-title"].toString()
            val key = channel.key ?: ""
            val keyid = channel.keyid ?: ""
            val userAgent = channel.userAgent ?: ""
            val cookie = channel.cookie ?: ""
            val licenseUrl = channel.licenseUrl ?: ""
            val headers = channel.headers
            newLiveSearchResponse(channelname, LoadData(streamurl, channelname, posterurl, nation, key, keyid, userAgent, cookie, licenseUrl, channel.drmKeys, headers).toJson(), TvType.Live)
            {
                this.posterUrl = posterurl
                this.apiName
                this.lang = channel.attributes["group-title"]
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LoadData>(url)
        return newLiveStreamLoadResponse(data.title, url, url)
        {
            this.posterUrl = data.poster
            this.plot = data.nation
        }
    }

    data class LoadData(
        val url: String,
        val title: String,
        val poster: String,
        val nation: String,
        val key: String,
        val keyid: String,
        val userAgent: String,
        val cookie: String,
        val licenseUrl: String,
        val drmKeys: Map<String, String> = emptyMap(),
        val headers: Map<String, String>,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val loadData = parseJson<LoadData>(data)
        if (loadData.url.contains("mpd")) {
                // Get DRM KID from MPD Stream
                val mpdStr = getMpdStream(
                    url = loadData.url,
                    customHeaders = headers
                )
                val regex = Regex("""cenc:default_KID=["']([0-9a-fA-F\-]{36})["']""")
                val matchResult = regex.find(mpdStr)
                val drmKid = matchResult?.groups?.get(1)?.value ?: UUID.randomUUID().toString()

                // DRM KID is in Hex format with dashes, need to convert to Base64
                val drmKidBytes = drmKid.replace("-", "").chunked(2)
                    .map { it.toInt(16).toByte() }
                    .toByteArray()
                val drmKidBase64 = Base64.encodeToString(drmKidBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

                // Get DRM Key from License Server
                val keyBase64 = getDRMKeysFromLicenseServer(
                    url = loadData.licenseUrl,
                    kid = drmKidBase64
                )
                if (keyBase64.isNotEmpty()) {
                    callback.invoke(
                        newDrmExtractorLink(
                            this.name,
                            this.name,
                            loadData.url,
                            INFER_TYPE,
                            CLEARKEY_UUID
                        )
                        {
                            this.quality = Qualities.Unknown.value
                            if (headers.isNotEmpty()) {
                                this.headers = headers
                            }
                            this.key = keyBase64.trim()
                            this.kid = drmKidBase64.trim()
                        }
                    )
                    return true
                }

                callback.invoke(
                    newDrmExtractorLink(
                        this.name,
                        this.name,
                        loadData.url,
                        INFER_TYPE,
                        CLEARKEY_UUID
                    )
                    {
                        this.quality = Qualities.Unknown.value
                        if (headers.isNotEmpty()) {
                            this.headers = headers
                        }
                        this.licenseUrl = loadData.licenseUrl.trim()
                    }
                )
            } else {
                // Fallback to regular MPD link if no DRM keys available
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        url = loadData.url,
                        ExtractorLinkType.DASH
                    ) {
                        this.referer = ""
                        this.quality = Qualities.Unknown.value
                        if (headers.isNotEmpty()) {
                            this.headers = headers
                        }
                    }
                )
            }
        } else if(loadData.url.contains("&e=.m3u")) {
            val headers = mutableMapOf<String, String>()
            headers.putAll(loadData.headers)
            if (loadData.userAgent.isNotEmpty()) {
                headers["User-Agent"] = loadData.userAgent
            } else {
                headers["User-Agent"] = "OTT Navigator/1.7.1.4 (Linux;Android 13; en; 1fin92n)"
            }
            if (loadData.cookie.isNotEmpty()) {
                headers["Cookie"] = loadData.cookie
            }
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    url = loadData.url,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                    if (headers.isNotEmpty()) {
                        this.headers = headers
                    }
                }
            )
        } else if(loadData.url.contains("play.php?")) {
            val userAgent = if (loadData.userAgent.isNotEmpty()) loadData.userAgent else "OTT Navigator/1.7.1.4 (Linux;Android 13; en; 1fin92n)"
            val headers = mutableMapOf("User-Agent" to userAgent)
            headers.putAll(loadData.headers)
            if (loadData.cookie.isNotEmpty()) {
                headers["Cookie"] = loadData.cookie
            }
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    url = loadData.url,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        } else {
            val headers = mutableMapOf<String, String>()
            headers.putAll(loadData.headers)
            if (loadData.userAgent.isNotEmpty()) {
                headers["User-Agent"] = loadData.userAgent
            } else {
                headers["User-Agent"] = "OTT Navigator/1.7.1.4 (Linux;Android 13; en; 1fin92n)"
            }
            if (loadData.cookie.isNotEmpty()) {
                headers["Cookie"] = loadData.cookie
            }
            callback.invoke(
                newExtractorLink(
                    this.name,
                    loadData.title,
                    url = loadData.url,
                    INFER_TYPE
                ) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                    if (headers.isNotEmpty()) {
                        this.headers = headers
                    }
                }
            )
        }
        return true
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            private var currentCookie: String? = extractorLink.headers["Cookie"]

            override fun intercept(chain: Interceptor.Chain): Response {
                var request = chain.request()

                // Inject cookie if available
                if (!currentCookie.isNullOrEmpty()) {
                    request = request.newBuilder()
                        .removeHeader("Cookie")
                        .addHeader("Cookie", currentCookie!!)
                        .build()
                }

                val response = chain.proceed(request)

                // Intercept Set-Cookie headers
                val setCookies = response.headers("Set-Cookie")
                if (setCookies.isNotEmpty()) {
                    val newCookies = setCookies.map { it.substringBefore(";") }
                    val cookieMap = mutableMapOf<String, String>()
                    
                    if (!currentCookie.isNullOrEmpty()) {
                        currentCookie!!.split(";").forEach {
                            val parts = it.trim().split("=", limit = 2)
                            if (parts.size == 2) {
                                cookieMap[parts[0]] = parts[1]
                            }
                        }
                    }
                    
                    newCookies.forEach {
                        val parts = it.trim().split("=", limit = 2)
                        if (parts.size == 2) {
                            cookieMap[parts[0]] = parts[1]
                        }
                    }
                    
                    currentCookie = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                }

                return response
            }
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

                val bgDraw = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#1A1A2E"))
                    cornerRadius = 16f * dp
                }

                val root = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
                    background = bgDraw
                }

                val titleTv = android.widget.TextView(ctx).apply {
                    text = "\uD83D\uDCAC Join CNCVerse Community"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 17f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
                        .also { it.bottomMargin = (10 * dp).toInt() }
                }

                val dividerV = android.view.View(ctx).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#2D2D4A"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, 1)
                        .also { it.bottomMargin = (14 * dp).toInt() }
                }

                val msgTv = android.widget.TextView(ctx).apply {
                    text = "Join our Telegram group to discuss and share your opinion!"
                    setTextColor(android.graphics.Color.parseColor("#A0A0A8"))
                    textSize = 14f
                    setLineSpacing(0f, 1.4f)
                    layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
                        .also { it.bottomMargin = (18 * dp).toInt() }
                }

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

   
}

data class Playlist(
    val items: List<PlaylistItem> = emptyList(),
)

data class PlaylistItem(
    val title: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val url: String? = null,
    val userAgent: String? = null,
    val key: String? = null,
    val keyid: String? = null,
    val cookie: String? = null,
    val licenseUrl: String? = null,
    val drmKeys: Map<String, String> = emptyMap(),
)

class IptvPlaylistParser {

    private fun String.hexOrNull(): String? {
        val normalizedHex = replace("-", "").trim()
        if (normalizedHex.isBlank() || normalizedHex.length % 2 != 0) return null
        return if (normalizedHex.matches(Regex("^[0-9a-fA-F]+$"))) {
            normalizedHex.lowercase()
        } else {
            null
        }
    }

    private fun String.base64ToHexOrNull(): String? {
        val normalized = trim()
            .replace('-', '+')
            .replace('_', '/')
            .let { value ->
                val padding = (4 - (value.length % 4)) % 4
                value + "=".repeat(padding)
            }

        return try {
            val decoded = Base64.decode(normalized, Base64.DEFAULT)
            decoded.joinToString(separator = "") { byte -> "%02x".format(byte) }
        } catch (_: Exception) {
            null
        }
    }

    private fun String.normalizeDrmHexOrNull(): String? {
        val trimmed = trim()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) return null
        return trimmed.hexOrNull() ?: trimmed.base64ToHexOrNull()
    }

    private fun parseLicenseKeysMap(licenseKey: String): Map<String, String> {
        val trimmedKey = licenseKey.trim()
        if (!trimmedKey.startsWith("{")) return emptyMap()

        return try {
            val json = JSONObject(trimmedKey)
            val keys = json.optJSONArray("keys") ?: return emptyMap()
            val parsed = mutableMapOf<String, String>()

            for (index in 0 until keys.length()) {
                val item = keys.optJSONObject(index) ?: continue
                val kid = item.optString("kid").normalizeDrmHexOrNull()
                val key = item.optString("k").normalizeDrmHexOrNull()

                if (!kid.isNullOrEmpty() && !key.isNullOrEmpty()) {
                    parsed[kid] = key
                }
            }

            parsed
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parseLicenseKeyPair(licenseKey: String): Pair<String?, String?>? {
        val trimmedKey = licenseKey.trim()
        if (trimmedKey.isEmpty()) return null

        if (trimmedKey.startsWith("{")) {
            return try {
                val json = JSONObject(trimmedKey)
                val keys = json.optJSONArray("keys") ?: return null

                for (index in 0 until keys.length()) {
                    val item = keys.optJSONObject(index) ?: continue
                    val kid = item.optString("kid").normalizeDrmHexOrNull()
                    val key = item.optString("k").normalizeDrmHexOrNull()

                    if (kid != null || key != null) {
                        return key to kid
                    }
                }

                null
            } catch (_: Exception) {
                null
            }
        }

        val parts = when {
            trimmedKey.contains(":") -> trimmedKey.split(":", limit = 2)
            trimmedKey.contains(",") -> trimmedKey.split(",", limit = 2)
            else -> emptyList()
        }

        if (parts.size != 2) return null

        val keyId = parts[0].trim().normalizeDrmHexOrNull()
        val key = parts[1].trim().normalizeDrmHexOrNull()

        return key to keyId
    }

    fun parseM3U(content: String): Playlist {
        return parseM3U(content.byteInputStream())
    }

    @Throws(PlaylistParserException::class)
    fun parseM3U(input: InputStream): Playlist {
        val allLines = input.bufferedReader().readLines()
        val playlistItems: MutableList<PlaylistItem> = mutableListOf()
        var i = 0

        // Buffer for all properties - accumulate until URL line is found
        var bufferedCookie: String? = null
        var bufferedUserAgent: String? = null
        var bufferedHeaders: Map<String, String> = emptyMap()
        var bufferedKey: String? = null
        var bufferedKeyId: String? = null
        var bufferedLicenseUrl: String? = null
        var bufferedDrmKeys: Map<String, String> = emptyMap()
        var bufferedTitle: String? = null
        var bufferedAttributes: Map<String, String> = emptyMap()

        while (i < allLines.size) {
            val line = allLines[i].trim()

            if (line.isNotEmpty()) {
                when {
                    line.startsWith(M3UPlaylistPlayer.EXT_INF) -> {
                        bufferedTitle = line.getTitle()
                        bufferedAttributes = line.getAttributes()

                        // Extract DRM keys from attributes if present
                        val keyFromAttr = bufferedAttributes["key"] ?: bufferedAttributes["drm-key"]
                        val keyidFromAttr = bufferedAttributes["keyid"] ?: bufferedAttributes["drm-keyid"] ?: bufferedAttributes["kid"]
                        
                        // Only use attribute keys if no buffered keys exist
                        if (bufferedKey == null) bufferedKey = keyFromAttr
                        if (bufferedKeyId == null) bufferedKeyId = keyidFromAttr
                    }
                    line.startsWith("#EXTHTTP:") -> {
                        val json = line.removePrefix("#EXTHTTP:").trim()
                        try {
                            val map = parseJson<Map<String, String>>(json)
                            if (map.containsKey("cookie")) {
                                bufferedCookie = map["cookie"]
                            }
                            if (map.containsKey("user-agent")) {
                                bufferedUserAgent = map["user-agent"]
                            }
                        } catch (e: Exception) { }
                    }
                    line.startsWith(M3UPlaylistPlayer.EXT_VLC_OPT) -> {
                        val userAgent = line.getTagValue("http-user-agent")
                        val referrer = line.getTagValue("http-referrer") ?: line.getTagValue("http-referer")

                        if (userAgent != null) bufferedUserAgent = userAgent
                        if (referrer != null) {
                            bufferedHeaders = bufferedHeaders + mapOf("Referer" to referrer)
                        }
                    }
                    line.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                        val licenseKey = line.removePrefix("#KODIPROP:inputstream.adaptive.license_key=").trim()

                        if (licenseKey.startsWith("http://") || licenseKey.startsWith("https://")) {
                            bufferedLicenseUrl = licenseKey
                        } else {
                            if (licenseKey.startsWith("{")) {
                                val parsedKeys = parseLicenseKeysMap(licenseKey)
                                if (parsedKeys.isNotEmpty()) {
                                    bufferedDrmKeys = parsedKeys
                                    val firstPair = parsedKeys.entries.firstOrNull()
                                    if (firstPair != null) {
                                        if (bufferedKey == null) bufferedKey = firstPair.value
                                        if (bufferedKeyId == null) bufferedKeyId = firstPair.key
                                    }
                                }

                                val parsedKeyPair = parseLicenseKeyPair(licenseKey)
                                if (parsedKeyPair != null) {
                                    val (key, keyId) = parsedKeyPair
                                    if (key != null) bufferedKey = key
                                    if (keyId != null) bufferedKeyId = keyId
                                }
                            } else {
                                val parts = when {
                                    licenseKey.contains(":") -> licenseKey.split(":")
                                    licenseKey.contains(",") -> licenseKey.split(",")
                                    else -> listOf(licenseKey)
                                }

                                val drmKidBytes = parts.getOrNull(0)
                                    ?.replace("-", "")
                                    ?.chunked(2)
                                    ?.mapNotNull {
                                        try { it.toInt(16).toByte() }
                                        catch (_: NumberFormatException) { null }
                                    }?.toByteArray()

                                val drmKeyBytes = parts.getOrNull(1)
                                    ?.replace("-", "")
                                    ?.chunked(2)
                                    ?.mapNotNull {
                                        try { it.toInt(16).toByte() }
                                        catch (_: NumberFormatException) { null }
                                    }?.toByteArray()

                                val drmKidBase64 = if (drmKidBytes != null && drmKidBytes.isNotEmpty()) {
                                    Base64.encodeToString(
                                        drmKidBytes,
                                        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                                    )
                                } else {
                                    null
                                }

                                val drmKeyBase64 = if (drmKeyBytes != null && drmKeyBytes.isNotEmpty()) {
                                    Base64.encodeToString(
                                        drmKeyBytes,
                                        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                                    )
                                } else {
                                    null
                                }

                                if (drmKeyBase64 != null) bufferedKey = drmKeyBase64
                                if (drmKidBase64 != null) bufferedKeyId = drmKidBase64
                            }
                        }
                    }
                    !line.startsWith("#") -> {
                        var fullLine = line
                        var j = i + 1

                        while (j < allLines.size &&
                               !allLines[j].trim().startsWith("#") &&
                               allLines[j].trim().isNotEmpty()) {
                            fullLine += allLines[j].trim()
                            j++
                        }

                        i = j - 1

                        val url = fullLine.getUrl()
                        val urlUserAgent = fullLine.getUrlParameter("user-agent")
                        val urlReferrer = fullLine.getUrlParameter("referer")
                        val urlReferrerAlias = fullLine.getUrlParameter("referrer")
                        val urlCookie = fullLine.getUrlParameter("cookie")
                        val urlOrigin = fullLine.getUrlParameter("origin")
                        val urlKey = fullLine.getUrlParameter("key")
                        val urlKeyid = fullLine.getUrlParameter("keyid")
                        val urlLicenseUrl = fullLine.getUrlParameter("licenseUrl")

                        var finalHeaders = bufferedHeaders
                        val resolvedReferrer = urlReferrer ?: urlReferrerAlias
                        if (resolvedReferrer != null) {
                            finalHeaders = finalHeaders + mapOf("Referer" to resolvedReferrer)
                        }
                        if (urlOrigin != null) {
                            finalHeaders = finalHeaders + mapOf("Origin" to urlOrigin)
                        }

                        val item = PlaylistItem(
                            title = bufferedTitle ?: "Unknown Channel",
                            attributes = bufferedAttributes,
                            url = url,
                            headers = finalHeaders,
                            userAgent = urlUserAgent ?: bufferedUserAgent,
                            cookie = urlCookie ?: bufferedCookie,
                            key = urlKey ?: bufferedKey,
                            keyid = urlKeyid ?: bufferedKeyId,
                            licenseUrl = urlLicenseUrl ?: bufferedLicenseUrl,
                            drmKeys = bufferedDrmKeys
                        )

                        playlistItems.add(item)

                        bufferedCookie = null
                        bufferedUserAgent = null
                        bufferedHeaders = emptyMap()
                        bufferedKey = null
                        bufferedKeyId = null
                        bufferedLicenseUrl = null
                        bufferedDrmKeys = emptyMap()
                        bufferedTitle = null
                        bufferedAttributes = emptyMap()
                    }
                }
            }
            i++
        }
        return Playlist(playlistItems)
    }

    private fun String.replaceQuotesAndTrim(): String {
        return replace("\"", "").trim()
    }

    private fun String.isExtendedM3u(): Boolean =
        startsWith(M3UPlaylistPlayer.EXT_M3U) || startsWith(M3UPlaylistPlayer.EXT_INF) || startsWith("#KODIPROP")

    private fun String.getTitle(): String? {
        val extInfRegex = Regex("(#EXTINF:.?[0-9]+)", RegexOption.IGNORE_CASE)
        val afterExtInf = replace(extInfRegex, "").trim()

        var lastCommaIndex = -1
        var insideQuotes = false

        for (i in afterExtInf.indices) {
            when (afterExtInf[i]) {
                '"' -> insideQuotes = !insideQuotes
                ',' -> if (!insideQuotes) lastCommaIndex = i
            }
        }

        return if (lastCommaIndex != -1 && lastCommaIndex < afterExtInf.length - 1) {
            afterExtInf.substring(lastCommaIndex + 1).trim().replaceQuotesAndTrim()
        } else {
            afterExtInf.split(",").lastOrNull()?.replaceQuotesAndTrim()
        }
    }

    private fun String.getUrl(): String? {
        return split("|").firstOrNull()?.replaceQuotesAndTrim()
    }

    private fun String.getUrlParameter(key: String): String? {
        val urlRegex = Regex("^(.*)\\|", RegexOption.IGNORE_CASE)
        val paramsString = replace(urlRegex, "").replaceQuotesAndTrim()

        val params = paramsString.split("&")

        for (param in params) {
            val keyValuePair = param.split("=", limit = 2)
            if (keyValuePair.size == 2) {
                val paramKey = keyValuePair[0].trim()
                val paramValue = keyValuePair[1].trim()
                if (paramKey.equals(key, ignoreCase = true)) {
                    return paramValue.replaceQuotesAndTrim()
                }
            }
        }

        return null
    }

    private fun String.getAttributes(): Map<String, String> {
        val extInfRegex = Regex("(#EXTINF:.?[0-9]+)", RegexOption.IGNORE_CASE)
        val afterExtInf = replace(extInfRegex, "").trim()

        var lastCommaIndex = -1
        var insideQuotes = false

        for (i in afterExtInf.indices) {
            when (afterExtInf[i]) {
                '"' -> insideQuotes = !insideQuotes
                ',' -> if (!insideQuotes) lastCommaIndex = i
            }
        }

        val attributesString = if (lastCommaIndex != -1) {
            afterExtInf.substring(0, lastCommaIndex).trim()
        } else {
            afterExtInf.trim()
        }

        val attributes = mutableMapOf<String, String>()
        val attributeRegex = Regex("""(\w[-\w]*)\s*=\s*(?:"([^"]*)"|([^\s,]+))""", RegexOption.IGNORE_CASE)

        attributeRegex.findAll(attributesString).forEach { matchResult ->
            val key = matchResult.groups[1]?.value ?: ""
            val quotedValue = matchResult.groups[2]?.value
            val unquotedValue = matchResult.groups[3]?.value
            val value = quotedValue ?: unquotedValue ?: ""

            if (key.isNotEmpty()) {
                attributes[key] = value.trim()
            }
        }

        return attributes
    }

    private fun String.getTagValue(key: String): String? {
        val keyRegex = Regex("$key=(.*)", RegexOption.IGNORE_CASE)
        return keyRegex.find(this)?.groups?.get(1)?.value?.replaceQuotesAndTrim()
    }
}

sealed class PlaylistParserException(message: String) : Exception(message) {
    class InvalidHeader :
        PlaylistParserException("Invalid file header. Header doesn't start with #EXTM3U")
}
