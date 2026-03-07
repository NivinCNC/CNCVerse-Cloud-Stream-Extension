package com.cncverse.desiserials

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.getAndUnpack

class DesiSerialsProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://www.desi-serials.to"
    override var name = "DesiSerials"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "latest-episodes" to "Latest Episodes",
        "star-plus-hdepisodes" to "Star Plus",
        "color-tv-hd" to "Colors TV",
        "zee-tv" to "Zee TV",
        "sony-tv" to "Sony TV",
        "sab-tv-hd" to "Sab TV",
        "and-tv" to "& TV",
        "star-bharat" to "Star Bharat"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}/"
        } else {
            "$mainUrl/${request.data}/page/$page/"
        }

        // Add typical browser headers to prevent OkHttp from getting blocked
        val document = app.get(url, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
        )).document
        
        android.util.Log.d("DesiSerials", "getMainPage url=$url title=${document.title()}")
        
        // Use a more specific article selector to avoid matching the main page container
        val posts = document.select("article.type-post, article.post-grid, .porto-sicon-wrapper, li.cat-item")
        android.util.Log.d("DesiSerials", "getMainPage posts=${posts.size}")
        
        val home = posts.mapNotNull {
            it.toSearchResult()
        }.toMutableList()
        android.util.Log.d("DesiSerials", "getMainPage home=${home.size}")

        if (home.isEmpty()) {
            val docTitle = document.title().ifBlank { "No Title" }
            val firstText = document.text().take(50)
            home.add(newTvSeriesSearchResponse("Debug: $docTitle | $firstText", url, TvType.TvSeries) {
                this.posterUrl = ""
            })
        }

        return newHomePageResponse(arrayListOf(HomePageList(request.name, home, isHorizontalImages = true)), hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h3.thumb-info-inner a, h2.entry-title a, h5 a.porto-sicon-title-link, h3.porto-post-title a") ?: this.selectFirst("a") ?: return null
        val title = titleElement.text().trim().takeIf { it.isNotBlank() } ?: titleElement.attr("title").trim().takeIf { it.isNotBlank() } ?: "Unknown Series"
        val href = fixUrl(titleElement.attr("href"))
        
        val imgElement = this.selectFirst("div.post-image img, span.post-image img") ?: this.selectFirst("img")
        var posterUrl = imgElement?.attr("data-oi")
        if (posterUrl.isNullOrBlank()) {
            posterUrl = imgElement?.attr("src")
        }
        posterUrl = fixUrlNull(posterUrl)

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.replace(" ", "+").lowercase()
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        
        val document = app.get(searchUrl, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
        )).document
        
        // Search results use div.post-item with h3.porto-post-title
        val results = document.select("div.post-item, article.type-post, article.post-grid, article.post")
        
        return results.mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
        )).document
        
        val title = doc.selectFirst("h1.page-title")?.text()?.trim() 
            ?: doc.selectFirst("h2.heading-primary")?.text()?.trim()
            ?: doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: return null

        val posterRegex = Regex("(https?:\\/\\/(www\\.)?[-a-zA-Z0-9@:%._\\+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_\\+.~#?&\\/\\/=]*jpg))")
        val posterRaw = doc.selectFirst("div.page-image img")?.attr("src") ?: doc.html()
        val poster = posterRegex.find(posterRaw)?.value?.trim()

        val episodes = mutableListOf<Episode>()
        
        // 1. Check if it's a list of episodes (Index page)
        val posts = doc.select("article.type-post")
        posts.forEach { element ->
            val a = element.selectFirst("h3.thumb-info-inner a, h2.entry-title a")
            if (a != null) {
                val epHref = fixUrl(a.attr("href"))
                val epTitle = a.text().trim()
                if (epHref != url) {
                    episodes.add(newEpisode(data = epHref) {
                        name = epTitle
                        this.posterUrl = poster
                    })
                }
            }
        }

        // 2. Single episode page - use the URL itself as the episode data
        //    loadLinks will handle extracting iframes and a[href] links from the page
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(data = url) {
                    name = title
                    season = 1
                    episode = 1
                    this.posterUrl = poster
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
            this.posterUrl = poster?.trim()
            this.posterHeaders = mapOf("referer" to "$mainUrl/")
        }
    }

    private fun <T, R : Any> Iterable<T>.mapNotBlank(transform: (T) -> R?): List<R> {
        return mapNotNull(transform).filter { 
            val s = it.toString()
            s.isNotBlank() && s != "null"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        android.util.Log.d("DesiSerials", "loadLinks data: " + data)

        suspend fun handleIframe(href: String, referer: String) {
            val url = if (href.startsWith("//")) "https:$href" else href
            
            if (url.contains("desi-snation") || url.contains("tvarticles") || url.contains("desi-serials") || url.contains("bolly")) {
                // Proprietary iframe wrapper, we need to dig deeper
                android.util.Log.d("DesiSerials", "handleIframe fetch wrapper: " + url)
                val vidResponse = app.get(url, headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                ))
                val vidText = vidResponse.text
                
                // Extract inner iframes using Jsoup (handles uppercase IFRAME tags)
                val vidDoc = vidResponse.document
                val nestedIframes = vidDoc.select("iframe[src]")
                val nestedMatches = nestedIframes.map { it.attr("src") }
                android.util.Log.d("DesiSerials", "handleIframe nested iframes: " + nestedMatches.size)
                
                nestedMatches.forEach { nestedSrc ->
                    val nestedUrl = nestedSrc
                    val fullNestedUrl = if (nestedUrl.startsWith("//")) "https:" + nestedUrl else nestedUrl
                    android.util.Log.d("DesiSerials", "handleIframe found nestedUrl: " + fullNestedUrl)
                    
                    if (fullNestedUrl.contains("flow.tvlogy")) {
                        // flow.tvlogy uses video.js with direct m3u8 sources in var config = {...}
                        try {
                            val playerHtml = app.get(fullNestedUrl, headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            )).text
                            android.util.Log.d("DesiSerials", "flow.tvlogy playerHtml length: " + playerHtml.length)
                            
                            val streamHeaders = mapOf(
                                "Referer" to "https://flow.tvlogy.to/",
                                "Origin" to "https://flow.tvlogy.to",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            )
                            
                            // Extract m3u8 URLs from the sources config
                            val m3u8Regex = Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""")
                            val m3u8Links = m3u8Regex.findAll(playerHtml).map { it.groupValues[1] }.distinct().toList()
                            android.util.Log.d("DesiSerials", "flow.tvlogy m3u8 links: " + m3u8Links.size)
                            
                            m3u8Links.forEach { source ->
                                android.util.Log.d("DesiSerials", "flow.tvlogy m3u8 source: " + source)
                                callback.invoke(
                                    newExtractorLink(
                                        "DesiSerials",
                                        "DesiSerials",
                                        source,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "https://flow.tvlogy.to/"
                                        this.quality = Qualities.Unknown.value
                                        this.headers = streamHeaders
                                    }
                                )
                            }
                            
                            // Also try to extract mp4 links if any
                            val mp4Regex = Regex("""(https?://[^"'\s\\]+\.mp4[^"'\s\\]*)""")
                            val mp4Links = mp4Regex.findAll(playerHtml).map { it.groupValues[1] }.distinct().toList()
                            mp4Links.forEach { source ->
                                callback.invoke(
                                    newExtractorLink(
                                        "DesiSerials",
                                        "DesiSerials",
                                        source,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "https://flow.tvlogy.to/"
                                        this.quality = Qualities.P720.value
                                        this.headers = streamHeaders
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            android.util.Log.d("DesiSerials", "flow.tvlogy exception: " + e.message)
                            loadExtractor(fullNestedUrl, subtitleCallback, callback)
                        }
                    } else if (fullNestedUrl.contains("speedwatch")) {
                        // SpeedWatch uses JuicyCodes.Run
                        try {
                            val playerHtml = app.get(fullNestedUrl, headers = mapOf("Referer" to url)).text
                            val base64Match = Regex("""JuicyCodes\.Run\s*\(\s*["']([^"']+)["']\s*\)""").find(playerHtml)
                            if (base64Match != null) {
                                val base64Code = base64Match.groupValues[1]
                                val decodedStr = String(android.util.Base64.decode(base64Code, android.util.Base64.DEFAULT))
                                val unpacked = getAndUnpack(decodedStr) ?: decodedStr
                                val m3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
                                val m3u8Links = m3u8Regex.findAll(unpacked).map { it.groupValues[1] }.distinct().toList()
                                m3u8Links.forEach { source ->
                                    callback.invoke(
                                        newExtractorLink(
                                            "SpeedWatch",
                                            "SpeedWatch",
                                            source,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = fullNestedUrl
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                }
                            } else {
                                loadExtractor(fullNestedUrl, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            loadExtractor(fullNestedUrl, subtitleCallback, callback)
                        }
                    } else if (fullNestedUrl.contains("vkprime") || fullNestedUrl.contains("vkspeed")) {
                        // Extract VkPrime/VkSpeed direct link
                        try {
                            val playerHtml = app.get(fullNestedUrl, headers = mapOf("Referer" to url)).text
                            val unpacked = getAndUnpack(playerHtml) ?: playerHtml
                            
                            val videoRegex = Regex("""(https?://[^"']+\.(?:mp4|m3u8)[^"']*)""")
                            val videoLinks = videoRegex.findAll(unpacked).map { it.groupValues[1] }.toList()
                            
                            if (videoLinks.isNotEmpty()) {
                                videoLinks.forEach { source ->
                                    val isM3u8 = source.contains(".m3u8")
                                    callback.invoke(
                                        newExtractorLink(
                                            "VkPrime",
                                            "VkPrime",
                                            source,
                                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = fullNestedUrl
                                            this.quality = Qualities.P720.value
                                        }
                                    )
                                }
                            } else {
                                loadExtractor(fullNestedUrl, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            loadExtractor(fullNestedUrl, subtitleCallback, callback)
                        }
                    } else {
                        // Let Cloudstream built-in extractors handle other video hosts
                        loadExtractor(fullNestedUrl, subtitleCallback, callback)
                    }
                }
                
                // Also look for direct video links just in case
                val videoRegex = Regex("""(https?://[^"']+\.(?:m3u8|mp4)[^"']*)""")
                val sources = videoRegex.findAll(vidText).map { it.groupValues[1] }.distinct().toList()
                
                sources.forEach { source ->
                    val isM3u8 = source.contains(".m3u8")
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            source,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = if (isM3u8) Qualities.Unknown.value else Qualities.P720.value
                        }
                    )
                }
            } else {
                loadExtractor(url, subtitleCallback, callback)
            }
        }

        if (data.startsWith("http")) {
            // Load iframes dynamically from the episode URL
            val doc = app.get(data, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )).document
            val iframes = doc.select("iframe[src]").map { it.attr("src") }
            val aLinks = doc.select("div.entry-content p a[href]").map { it.attr("href") }.filter { href ->
                href.contains("desi-snation") || href.contains("tvarticles") || href.contains("desi-serials") || href.contains("bolly") || href.contains("dai.ly") || href.contains("dailymotion.com") || href.contains("vkprime") || href.contains("speedwatch")
            }
            val allLinks = (iframes + aLinks).distinct()
            
            android.util.Log.d("DesiSerials", "loadLinks links found: \${allLinks.size}")
            allLinks.forEach {
                android.util.Log.d("DesiSerials", "loadLinks handleIframe: " + it)
                handleIframe(it, data)
            }
        } else if (data.startsWith("[")) {
            // Load directly from JSON payload (fallback for older loads)
            try {
                // Try parsing as a list of strings (iframe src)
                val links = parseJson<List<String>>(data)
                links.amap { link ->
                    handleIframe(link, "$mainUrl/")
                }
            } catch (e: Exception) {
                // Ignore parsing error for Episode objects because Episode object contains the url inside data field which is handled in next iteration
                android.util.Log.d("DesiSerials", "loadLinks JSON parse error: " + e.message)
            }
        }
        return true
    }
}
