package com.cncverse

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
import com.lagradost.cloudstream3.base64Encode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets

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
    val bodyString = buffer.readUtf8()

    println("URL: ${req.url}")
    println("Method: ${req.method}")
    println("Headers:\n${req.headers}")
    println("Body:\n$bodyString")

    return chain.proceed(req)
  }
}

class Cricify(
    private val customName: String = "IPTV Player",
    private val customMainUrl: String = "https://fifabd.site/OPLLX7/LIVE2.m3u"
) : MainAPI() {
    companion object {
        var context: android.content.Context? = null
        const val EXT_M3U = "#EXTM3U"
        const val EXT_INF = "#EXTINF"
        const val EXT_VLC_OPT = "#EXTVLCOPT"
    }
    
    override var lang = "ta"
    override var mainUrl = customMainUrl
    override var name = customName
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Live,
    )
    private val headers = mapOf(
        "accept" to "*/*",
        "Cache-Control" to "no-cache, no-store",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:78.0) Gecko/20100101 Firefox/78.0",
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
            response.body?.string() ?: ""
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
      val userAgent = "Dalvik/2.1.0 (Linux; U; Android)"
        val client = OkHttpClient.Builder()
            .addInterceptor(HeaderReplacementInterceptor(
              mapOf(
                "User-Agent" to userAgent,
                "Content-Type" to "application/json;charset=UTF-8",
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
          val response = response.body.string()
          val jsonResponse = parseJson<Map<String, Any>>(response)
          val keys = jsonResponse["keys"] as? List<Map<String, String>> ?: return ""
          val firstKey = keys.firstOrNull() ?: return ""
          firstKey["k"] ?: ""
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request : MainPageRequest
    ): HomePageResponse {
        // Show star popup on first visit (shared across all CNCVerse plugins)
        Cricify.context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        
        val data = IptvPlaylistParser().parseM3U(getWithCustomHeaders(mainUrl))
        return newHomePageResponse(data.items.groupBy{it.attributes["group-title"]}.map { group ->
            val title = group.key ?: ""
            val show = group.value.map { channel ->
                val streamurl = channel.url.toString()
                val channelname = channel.title.toString()
                val posterurl = channel.attributes["tvg-logo"].toString()
                val nation = channel.attributes["group-title"].toString()
                val key= channel.key ?: ""
                val keyid= channel.keyid ?: ""
                val userAgent = channel.userAgent ?: ""
                val cookie = channel.cookie ?: ""
                val licenseUrl = channel.licenseUrl ?: ""
                val headers = channel.headers
                newLiveSearchResponse(channelname, LoadData(streamurl, channelname, posterurl, nation, key, keyid, userAgent, cookie, licenseUrl, headers).toJson(), TvType.Live)
                {
                    this.posterUrl=posterurl
                    this.apiName
                    this.lang=channel.attributes["group-title"]
                }
            }
            HomePageList(
                title,
                show,
                isHorizontalImages = true
            )
        },false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val data = IptvPlaylistParser().parseM3U(getWithCustomHeaders(mainUrl))
        return data.items.filter { it.title?.contains(query,ignoreCase = true) ?: false }.map { channel ->
                val streamurl = channel.url.toString()
                val channelname = channel.title.toString()
                val posterurl = channel.attributes["tvg-logo"].toString()
                val nation = channel.attributes["group-title"].toString()
                val key = channel.key ?: ""
                val keyid = channel.keyid ?: ""
                val userAgent = channel.userAgent ?: ""
                val cookie = channel.cookie ?: ""
                val licenseUrl = channel.licenseUrl ?: ""
            newLiveSearchResponse(channelname, LoadData(streamurl, channelname, posterurl, nation, key, keyid, userAgent, cookie, licenseUrl, headers).toJson(), TvType.Live)
            {
                this.posterUrl=posterurl
                this.apiName
                this.lang=channel.attributes["group-title"]
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LoadData>(url)
        return newLiveStreamLoadResponse(data.title,url,url)
        {
            this.posterUrl=data.poster
            this.plot=data.nation
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
        val headers: Map<String, String>,
    )
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadData>(data)
        if (loadData.url.contains("mpd"))
        {
            val headers = mutableMapOf<String, String>()
            headers.putAll(loadData.headers)
            if (loadData.userAgent.isNotEmpty()) {
                headers["User-Agent"] = loadData.userAgent
            }
            if (loadData.cookie.isNotEmpty()) {
                headers["Cookie"] = loadData.cookie
            }

            val hasValidKeys = loadData.key.isNotEmpty() && loadData.keyid.isNotEmpty() &&
                              loadData.key.trim() != "null" && loadData.keyid.trim() != "null"
            val hasLicenseUrl = loadData.licenseUrl.isNotEmpty() && loadData.licenseUrl.trim() != "null"

            if (hasValidKeys) {
                callback.invoke(
                    newDrmExtractorLink(
                        this.name,
                        this.name,
                        loadData.url,
                        INFER_TYPE,
                        CLEARKEY_UUID
                    )
                    {
                        this.quality=Qualities.Unknown.value
                        if (headers.isNotEmpty()) {
                            this.headers = headers
                        }
                        this.key=loadData.key.trim()
                        this.kid=loadData.keyid.trim()
                    }
                )
            } else if (hasLicenseUrl) {
              println("Attempting to fetch DRM keys from License Server")
              // Get DRM KID from MPD Stream
              val mpdStr = getMpdStream(
                url = loadData.url,
                customHeaders = headers
              )
              val regex = Regex("""cenc:default_KID=["']([0-9a-fA-F\-]{36})["']""")
              val matchResult = regex.find(mpdStr)
              val drmKid = matchResult?.groups?.get(1)?.value ?: UUID.randomUUID().toString()
              println("Extracted DRM KID: $drmKid")

              // DRM KID is in Hex format with dashes, need to convert to Base64
              val drmKidBytes = drmKid.replace("-", "").chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
              val drmKidBase64 = Base64.encodeToString(drmKidBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
              println("Converted DRM KID to Base64: $drmKidBase64")

              // Get DRM Key from License Server
              val keyBase64 = getDRMKeysFromLicenseServer(
                url = loadData.licenseUrl,
                kid = drmKidBase64
              )
              println("Fetched DRM Key from License Server: $keyBase64")
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
                    this.quality=Qualities.Unknown.value
                    if (headers.isNotEmpty()) {
                      this.headers = headers
                    }
                    this.key=keyBase64.trim()
                    this.kid=drmKidBase64.trim()
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
                  this.quality=Qualities.Unknown.value
                  if (headers.isNotEmpty()) {
                    this.headers = headers
                  }
                  this.licenseUrl=loadData.licenseUrl.trim()
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
        }
        else if(loadData.url.contains("&e=.m3u"))
            {
                val headers = mutableMapOf<String, String>()
                headers.putAll(loadData.headers)
                if (loadData.userAgent.isNotEmpty()) {
                    headers["User-Agent"] = loadData.userAgent
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

            }
        else if(loadData.url.contains("play.php?"))
            {
                val headers = mutableMapOf("User-Agent" to loadData.userAgent)
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

            }
        else
        {
            val headers = mutableMapOf<String, String>()
            headers.putAll(loadData.headers)
            if (loadData.userAgent.isNotEmpty()) {
                headers["User-Agent"] = loadData.userAgent
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
)


class IptvPlaylistParser {


    /**
     * Parse M3U8 string into [Playlist]
     *
     * @param content M3U8 content string.
     * @throws PlaylistParserException if an error occurs.
     */
    fun parseM3U(content: String): Playlist {
        return parseM3U(content.byteInputStream())
    }

    /**
     * Parse M3U8 content [InputStream] into [Playlist]
     *
     * @param input Stream of input data.
     * @throws PlaylistParserException if an error occurs.
     */
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
        var bufferedTitle: String? = null
        var bufferedAttributes: Map<String, String> = emptyMap()

        println("Parsing M3U with ${allLines.size} lines")

        while (i < allLines.size) {
            val line = allLines[i].trim()

            if (line.isNotEmpty()) {
                when {
                    line.startsWith(Cricify.EXT_INF) -> {
                        println("Found EXTINF line: $line")
                        bufferedTitle = line.getTitle()
                        bufferedAttributes = line.getAttributes()
                        println("Parsed title: $bufferedTitle, attributes: $bufferedAttributes")

                        // Extract DRM keys from attributes if present
                        val keyFromAttr = bufferedAttributes["key"] ?: bufferedAttributes["drm-key"]
                        val keyidFromAttr = bufferedAttributes["keyid"] ?: bufferedAttributes["drm-keyid"] ?: bufferedAttributes["kid"]
                        
                        // Only use attribute keys if no buffered keys exist
                        if (bufferedKey == null) bufferedKey = keyFromAttr
                        if (bufferedKeyId == null) bufferedKeyId = keyidFromAttr
                    }
                    line.startsWith("#EXTHTTP:") -> {
                        // Parse JSON for cookie and user-agent, buffer them
                        val json = line.removePrefix("#EXTHTTP:").trim()
                        try {
                            val map = parseJson<Map<String, String>>(json)
                            if (map.containsKey("cookie")) {
                                bufferedCookie = map["cookie"]
                            }
                            if (map.containsKey("user-agent")) {
                                bufferedUserAgent = map["user-agent"]
                            }
                        } catch (e: Exception) {
                            // Ignore parsing errors
                        }
                    }
                    line.startsWith(Cricify.EXT_VLC_OPT) -> {
                        // Buffer user agent and referrer
                        val userAgent = line.getTagValue("http-user-agent")
                        val referrer = line.getTagValue("http-referrer")

                        if (userAgent != null) bufferedUserAgent = userAgent
                        if (referrer != null) {
                            bufferedHeaders = bufferedHeaders + mapOf("referrer" to referrer)
                        }
                    }
                    line.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                        // Parse keyid and key from license_key and buffer them
                        println("Found KODIPROP license_key line: $line")
                        val licenseKey = line.removePrefix("#KODIPROP:inputstream.adaptive.license_key=").trim()
                        println("Extracted license key: $licenseKey")

                        // Check if license key is a URL
                        if (licenseKey.startsWith("http://") || licenseKey.startsWith("https://")) {
                            println("Using License URL: $licenseKey")
                            bufferedLicenseUrl = licenseKey
                        } else {
                            // Handle different license key formats (hex encoded keys)
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
                                    catch (e: NumberFormatException) { null }
                                }?.toByteArray()

                            val drmKeyBytes = parts.getOrNull(1)
                                ?.replace("-", "")
                                ?.chunked(2)
                                ?.mapNotNull {
                                    try { it.toInt(16).toByte() }
                                    catch (e: NumberFormatException) { null }
                                }?.toByteArray()

                            val drmKidBase64 = if (drmKidBytes != null && drmKidBytes.isNotEmpty())
                                Base64.encodeToString(
                                    drmKidBytes,
                                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                                ) else null

                            val drmKeyBase64 = if (drmKeyBytes != null && drmKeyBytes.isNotEmpty())
                                Base64.encodeToString(
                                    drmKeyBytes,
                                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                                ) else null

                            println("Decoded DRM keys - keyid: $drmKidBase64, key: $drmKeyBase64")
                            if (drmKeyBase64 != null) bufferedKey = drmKeyBase64
                            if (drmKidBase64 != null) bufferedKeyId = drmKidBase64
                        }
                    }
                    !line.startsWith("#") -> {
                        // URL line - now create the PlaylistItem with all buffered properties
                        println("Found URL line: $line")

                        // Handle multi-line URLs by accumulating lines until we find a complete URL or hit a comment
                        var fullLine = line
                        var j = i + 1

                        // Continue reading lines until we find a line that starts with # or we reach end of file
                        while (j < allLines.size &&
                               !allLines[j].trim().startsWith("#") &&
                               allLines[j].trim().isNotEmpty()) {
                            fullLine += allLines[j].trim()
                            j++
                        }

                        println("Full URL line: $fullLine")

                        // Update index to skip the lines we've already processed
                        i = j - 1

                        // Parse URL and its pipe-separated parameters
                        val url = fullLine.getUrl()
                        val urlUserAgent = fullLine.getUrlParameter("user-agent")
                        val urlReferrer = fullLine.getUrlParameter("referer")
                        val urlCookie = fullLine.getUrlParameter("cookie")
                        val urlOrigin = fullLine.getUrlParameter("origin")
                        val urlKey = fullLine.getUrlParameter("key")
                        val urlKeyid = fullLine.getUrlParameter("keyid")
                        val urlLicenseUrl = fullLine.getUrlParameter("licenseUrl")

                        println("Parsed URL: $url")
                        println("Parsed UserAgent: $urlUserAgent")
                        println("Parsed Cookie: $urlCookie")
                        println("Parsed Referrer: $urlReferrer")
                        println("Parsed Origin: $urlOrigin")

                        // Build final headers - URL params override buffered values
                        var finalHeaders = bufferedHeaders
                        if (urlReferrer != null) {
                            finalHeaders = finalHeaders + mapOf("referrer" to urlReferrer)
                        }
                        if (urlOrigin != null) {
                            finalHeaders = finalHeaders + mapOf("origin" to urlOrigin)
                        }

                        // Create the PlaylistItem - URL params take priority over buffered values
                        val item = PlaylistItem(
                            title = bufferedTitle ?: "Unknown Channel",
                            attributes = bufferedAttributes,
                            url = url,
                            headers = finalHeaders,
                            userAgent = urlUserAgent ?: bufferedUserAgent,
                            cookie = urlCookie ?: bufferedCookie,
                            key = urlKey ?: bufferedKey,
                            keyid = urlKeyid ?: bufferedKeyId,
                            licenseUrl = urlLicenseUrl ?: bufferedLicenseUrl
                        )

                        playlistItems.add(item)
                        println("Created playlist item: ${item.title}, index: ${playlistItems.size - 1}")

                        // Reset all buffers for next item
                        bufferedCookie = null
                        bufferedUserAgent = null
                        bufferedHeaders = emptyMap()
                        bufferedKey = null
                        bufferedKeyId = null
                        bufferedLicenseUrl = null
                        bufferedTitle = null
                        bufferedAttributes = emptyMap()
                    }
                }
            }
            i++
        }
        println("Parsing completed. Found ${playlistItems.size} playlist items")
        return Playlist(playlistItems)
    }

    /**
     * Replace "" (quotes) from given string.
     */
    private fun String.replaceQuotesAndTrim(): String {
        return replace("\"", "").trim()
    }

    /**
     * Check if given content is valid M3U8 playlist.
     */
    private fun String.isExtendedM3u(): Boolean =
        startsWith(Cricify.EXT_M3U) || startsWith(Cricify.EXT_INF) || startsWith("#KODIPROP")

    /**
     * Get title of media.
     *
     * Example:-
     *
     * Input:
     * ```
     * #EXTINF:-1 tvg-id="1234" group-title="Kids" tvg-logo="url/to/logo", Title
     * ```
     * Result: Title
     */
    private fun String.getTitle(): String? {
        val extInfRegex = Regex("(#EXTINF:.?[0-9]+)", RegexOption.IGNORE_CASE)
        val afterExtInf = replace(extInfRegex, "").trim()

        // Find the last comma that's not inside quotes
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
            // Fallback to original logic if no comma found
            afterExtInf.split(",").lastOrNull()?.replaceQuotesAndTrim()
        }
    }

    /**
     * Get media url.
     *
     * Example:-
     *
     * Input:
     * ```
     * https://example.com/sample.m3u8|user-agent="Custom"
     * ```
     * Result: https://example.com/sample.m3u8
     */
    private fun String.getUrl(): String? {
        return split("|").firstOrNull()?.replaceQuotesAndTrim()
    }

    /**
     * Get url parameters.
     *
     * Example:-
     *
     * Input:
     * ```
     * http://192.54.104.122:8080/d/abcdef/video.mp4|User-Agent=Mozilla&Referer=CustomReferrer
     * ```
     * Result will be equivalent to kotlin map:
     * ```Kotlin
     * mapOf(
     *   "User-Agent" to "Mozilla",
     *   "Referer" to "CustomReferrer"
     * )
     * ```
     */
  /*  private fun String.getUrlParameters(): Map<String, String> {
        val urlRegex = Regex("^(.*)\\|", RegexOption.IGNORE_CASE)
        val headersString = replace(urlRegex, "").replaceQuotesAndTrim()
        return headersString.split("&").mapNotNull {
            val pair = it.split("=")
            if (pair.size == 2) pair.first() to pair.last() else null
        }.toMap()
    }

   */

    private fun String.getUrlParameter(key: String): String? {
        val urlRegex = Regex("^(.*)\\|", RegexOption.IGNORE_CASE)
        val paramsString = replace(urlRegex, "").replaceQuotesAndTrim()

        // Split by & to get individual parameters
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

    /**
     * Get attributes from `#EXTINF` tag as Map<String, String>.
     *
     * Example:-
     *
     * Input:
     * ```
     * #EXTINF:-1 tvg-id="1234" group-title="Kids" tvg-logo="url/to/logo", Title
     * ```
     * Result will be equivalent to kotlin map:
     * ```Kotlin
     * mapOf(
     *   "tvg-id" to "1234",
     *   "group-title" to "Kids",
     *   "tvg-logo" to "url/to/logo"
     *)
     * ```
     */
    private fun String.getAttributes(): Map<String, String> {
        val extInfRegex = Regex("(#EXTINF:.?[0-9]+)", RegexOption.IGNORE_CASE)
        val afterExtInf = replace(extInfRegex, "").trim()

        // Find the last comma that's not inside quotes to separate title from attributes
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

        println("Attributes string: '$attributesString'")

        val attributes = mutableMapOf<String, String>()

        // Use regex to match key="value" or key=value patterns
        val attributeRegex = Regex("""(\w[-\w]*)\s*=\s*(?:"([^"]*)"|([^\s,]+))""", RegexOption.IGNORE_CASE)

        attributeRegex.findAll(attributesString).forEach { matchResult ->
            val key = matchResult.groups[1]?.value ?: ""
            val quotedValue = matchResult.groups[2]?.value
            val unquotedValue = matchResult.groups[3]?.value
            val value = quotedValue ?: unquotedValue ?: ""

            if (key.isNotEmpty()) {
                attributes[key] = value.trim()
                println("Found attribute: $key = $value")
            }
        }

        return attributes
    }

    /**
     * Get value from a tag.
     *
     * Example:-
     *
     * Input:
     * ```
     * #EXTVLCOPT:http-referrer=http://example.com/
     * ```
     * Result: http://example.com/
     */
    private fun String.getTagValue(key: String): String? {
        val keyRegex = Regex("$key=(.*)", RegexOption.IGNORE_CASE)
        return keyRegex.find(this)?.groups?.get(1)?.value?.replaceQuotesAndTrim()
    }

}

/**
 * Exception thrown when an error occurs while parsing playlist.
 */
sealed class PlaylistParserException(message: String) : Exception(message) {

    /**
     * Exception thrown if given file content is not valid.
     */
    class InvalidHeader :
        PlaylistParserException("Invalid file header. Header doesn't start with #EXTM3U")

}
