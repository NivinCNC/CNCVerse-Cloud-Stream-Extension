package com.cncverse.radioindia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.net.URLDecoder

class RadioIndiaProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://www.radioindia.in"
    override var name = "Radio India"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    // Note: LiveSearchResponse is typically used for live TV/Radio providers
    override val mainPage = mainPageOf(
        "$mainUrl" to "Top Stations",
        "$mainUrl/radio/dance-electronic-160" to "Dance / Electronic",
        "$mainUrl/radio/classical-music-159" to "Classical Music",
        "$mainUrl/radio/oldies-classic-hits-155" to "Oldies / Classic Hits",
        "$mainUrl/radio/jazz-blues-157" to "Jazz / Blues",
        "$mainUrl/radio/metal-153" to "Metal",
        "$mainUrl/radio/rb-hip-hop-163" to "R&B / Hip Hop",
        "$mainUrl/radio/80s-90s-154" to "80s / 90s",
        "$mainUrl/radio/chillout-lounge-161" to "Chillout / Lounge",
        "$mainUrl/radio/rock-156" to "Rock",
        "$mainUrl/radio/pop-todays-hits-152" to "Pop / Today's Hits",
        "$mainUrl/radio/latino-caribbean-162" to "Latino / Caribbean",
        "$mainUrl/radio/country-158" to "Country"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        RadioIndiaProvider.context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        val document = app.get(request.data).document
        val home = document.select("ul.mdc-grid-list__tiles li a").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = true)
        )
    }

    private fun Element.toSearchResponse(): LiveSearchResponse? {
        val title = this.selectFirst("span.mdc-grid-tile__title")?.text() ?: return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        
        // Use data-src first, fallback to src
        val imgEl = this.selectFirst("img.mdc-grid-tile__primary-content")
        val posterUrl = imgEl?.attr("data-src")?.takeIf { it.isNotBlank() } ?: imgEl?.attr("src")

        return newLiveSearchResponse(
            name = title,
            url = href,
            type = TvType.Live
        ) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.lang = "hi"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select("ul.mdc-grid-list__tiles li a").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val html = document.html()

        // Title is in the h1 display element
        val title = document.selectFirst("h1.mdc-typography--display1")?.text()?.trim()
            ?: "Unknown Radio"

        // Poster URL is injected via JS: mytuner_vars.radio_images = ['url']
        val poster = Regex("""mytuner_vars\.radio_images\s*=\s*\['([^']+)'\]""").find(html)
            ?.groupValues?.get(1)

        // Description from slogan span or meta
        val description = document.selectFirst("span.slogan")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
        }
    }

    // the genk function translated to Kotlin
    private fun genk(str: String): String {
        var hex = ""
        var j = 0
        for (i in 0 until 32) {
            val charCode = str[j].code
            hex += charCode.toString(16)
            j++
            if (j >= str.length) j = 0
        }
        return hex
    }


    private fun parseHex(hexStr: String): ByteArray {
        val len = hexStr.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hexStr[i], 16) shl 4)
                    + Character.digit(hexStr[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun decryptMytuner(cipherTextBase64: String, ivHex: String, timestampData: String): String? {
        try {
            val cipherData = Base64.decode(cipherTextBase64, Base64.DEFAULT)
            
            val keyHex = genk(timestampData)
            val keyBytes = parseHex(keyHex)
            val ivBytes = parseHex(ivHex)

            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)

            // CryptoJS uses CFB mode with 128-bit segment size (CFB128) by default.
            // Java's "AES/CFB/NoPadding" is CFB128, matching CryptoJS's mode.CFB.
            val cipher = Cipher.getInstance("AES/CFB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            val decryptedBytes = cipher.doFinal(cipherData)
            return String(decryptedBytes, StandardCharsets.UTF_8).trimEnd('\u0000').trim()
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // The data is the radio page url
        val document = app.get(data).document
        val html = document.html()

        // timestamp_data comes from: $("#last-update").data("timestamp")
        // which is the data-timestamp attribute of the #last-update div
        val timestampData = document.selectFirst("#last-update")?.attr("data-timestamp")
            ?: throw Exception("Timestamp not found")
        // Extract playlists - site uses JS single-quote syntax, not JSON
        // e.g. [[{'cipher': 'xxx', 'iv': 'yyy', 'type': 'mp3', 'is_https': 'true'}]]
        val playlistMatch = Regex("""mytuner_vars\.radio_playlists\s*=\s*(\[.*?\]);""")
            .find(html)?.groupValues?.get(1) ?: return throw Exception("Playlists not found")

        // Extract individual track objects {... } inside the outer array
        val itemRegex = Regex("""\{'cipher':\s*'([^']*)',\s*'iv':\s*'([^']*)',\s*'type':\s*'([^']*)',\s*'is_https':\s*'([^']*)'\}""")
        var found = false
        itemRegex.findAll(playlistMatch).forEach { m ->
            val cipher = m.groupValues[1]
            val iv = m.groupValues[2]
            val type = m.groupValues[3]

            if (cipher.isNotBlank() && iv.isNotBlank()) {
                val decryptedUrl = decryptMytuner(cipher, iv, timestampData)
                if (decryptedUrl != null) {
                    found = true
                    callback(
                        newExtractorLink(
                            name = this.name,
                            source = this.name,
                            url = decryptedUrl,
                            type = if (decryptedUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }
        return found
    }
}
