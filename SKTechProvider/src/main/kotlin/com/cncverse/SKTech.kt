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

class SKTech(
    private val customName: String = "IPTV Player",
    private val customMainUrl: String = "https://sufyanpromax.space/categories.txt"
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
            response.body.string()
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request : MainPageRequest
    ): HomePageResponse {
        // Show star popup on first visit (shared across all CNCVerse plugins)
        SKTech.context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        
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

        while (i < allLines.size) {
            val line = allLines[i].trim()

            if (line.isNotEmpty()) {
                when {
                    line.startsWith(SKTech.EXT_INF) -> {
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
                    line.startsWith(SKTech.EXT_VLC_OPT) -> {
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
                        val licenseKey = line.removePrefix("#KODIPROP:inputstream.adaptive.license_key=").trim()

                        // Check if license key is a URL
                        if (licenseKey.startsWith("http://") || licenseKey.startsWith("https://")) {
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

                            if (drmKeyBase64 != null) bufferedKey = drmKeyBase64
                            if (drmKidBase64 != null) bufferedKeyId = drmKidBase64
                        }
                    }
                    !line.startsWith("#") -> {
                        // URL line - now create the PlaylistItem with all buffered properties
                        var fullLine = line
                        var j = i + 1

                        // Continue reading lines until we find a line that starts with # or we reach end of file
                        while (j < allLines.size &&
                               !allLines[j].trim().startsWith("#") &&
                               allLines[j].trim().isNotEmpty()) {
                            fullLine += allLines[j].trim()
                            j++
                        }

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
        startsWith(SKTech.EXT_M3U) || startsWith(SKTech.EXT_INF) || startsWith("#KODIPROP")

    /**
     * Get title of media.
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
     */
    private fun String.getUrl(): String? {
        return split("|").firstOrNull()?.replaceQuotesAndTrim()
    }

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
            }
        }

        return attributes
    }

    /**
     * Get value from a tag.
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
