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
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import org.json.JSONObject

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

class SKTech(
    private val customName: String = "IPTV Player",
    private val customMainUrl: String = "https://welalagaa.site/categories.txt"
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

    private fun decryptContent(content: String): String {
        return try {
            // Check if content is already valid M3U
            if (content.startsWith("#EXTM3U") || content.startsWith("#EXTINF") || content.startsWith("#KODIPROP")) {
                return content
            }

            val trimmedContent = content.trim()
            
            // Try to decrypt encrypted content
            if (trimmedContent.length < 79) {
                return trimmedContent // Too short to be encrypted, return as-is
            }

            // Extract parts for decryption
            val part1 = trimmedContent.substring(0, 10)
            val part2 = trimmedContent.substring(34, trimmedContent.length - 54)
            val part3 = trimmedContent.substring(trimmedContent.length - 10)
            val encryptedData = part1 + part2 + part3

            val ivBase64 = trimmedContent.substring(10, 34)
            val keyBase64 = trimmedContent.substring(trimmedContent.length - 54, trimmedContent.length - 10)

            // Decode from Base64
            val iv = Base64.decode(ivBase64, Base64.DEFAULT)
            val key = Base64.decode(keyBase64, Base64.DEFAULT)
            val encrypted = Base64.decode(encryptedData, Base64.DEFAULT)

            // Decrypt using AES/CBC/PKCS5Padding
            val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decrypted = cipher.doFinal(encrypted)

            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            println("Decryption failed: ${e.message}")
            content // Return original content if decryption fails
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request : MainPageRequest
    ): HomePageResponse {
        // Show star popup on first visit (shared across all CNCVerse plugins)
        SKTech.context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        
        val rawContent = getWithCustomHeaders(mainUrl)
        val decryptedContent = decryptContent(rawContent)
        val data = IptvPlaylistParser().parseM3U(decryptedContent)
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
                newLiveSearchResponse(channelname, LoadData(streamurl, channelname, posterurl, nation, key, keyid, userAgent, cookie, licenseUrl, channel.drmKeys, headers).toJson(), TvType.Live)
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
        val rawContent = getWithCustomHeaders(mainUrl)
        val decryptedContent = decryptContent(rawContent)
        val data = IptvPlaylistParser().parseM3U(decryptedContent)
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
            newLiveSearchResponse(channelname, LoadData(streamurl, channelname, posterurl, nation, key, keyid, userAgent, cookie, licenseUrl, channel.drmKeys, channel.headers).toJson(), TvType.Live)
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
                val normalizedKey = loadData.key.base64ToHexOrNull() ?: loadData.key.trim()
                val normalizedKid = loadData.keyid.base64ToHexOrNull() ?: loadData.keyid.trim()
                val playerKey = normalizedKey.hexToBase64UrlOrNull() ?: normalizedKey
                val playerKid = normalizedKid.hexToBase64UrlOrNull() ?: normalizedKid

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
                        this.key=playerKey
                        this.kid=playerKid
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
        var bufferedDrmKeys: Map<String, String> = emptyMap()
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
                            if (licenseKey.startsWith("{")) {
                                // Updated logic for JSON clearkey payload.
                                val parsedKeys = parseLicenseKeysMap(licenseKey)
                                if (parsedKeys.isNotEmpty()) {
                                    bufferedDrmKeys = parsedKeys
                                    val firstPair = parsedKeys.entries.firstOrNull()
                                    if (firstPair != null) {
                                        bufferedKeyId = firstPair.key
                                        bufferedKey = firstPair.value
                                    }
                                }
                            } else {
                                // Old logic for non-JSON values: split and decode hex -> base64url.
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
                            licenseUrl = urlLicenseUrl ?: bufferedLicenseUrl,
                            drmKeys = bufferedDrmKeys
                        )

                        playlistItems.add(item)

                        // Reset all buffers for next item
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
