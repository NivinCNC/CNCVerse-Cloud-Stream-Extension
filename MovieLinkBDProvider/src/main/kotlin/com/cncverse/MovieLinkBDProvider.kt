package com.cncverse

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import android.content.Context

class MovieLinkBDProvider : MainAPI() {
    companion object {
        var appContext: Context? = null
        // The site uses a rotating subdomain mirror; we store the resolved base
        // and fall back to movielinkbd.one if the mirror fails.
        private const val FALLBACK_URL = "https://movielinkbd.one"
    }

    override var mainUrl = "https://movielinkbd.one"
    override var name = "MovieLinkBD"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false

    override val mainPage = mainPageOf(
        "/" to "Recently Updated",
        "/type/movies" to "All Movies",
        "/type/series" to "All Web Series",
        "/language/hindi" to "Hindi Movies",
        "/language/bangla" to "Bangla Movies",
        "/language/bangla-dubbed" to "Bangla Dubbed",
        "/language/dual-audio" to "Dual Audio",
        "/language/english" to "English",
        "/southIndian" to "South Indian",
        "/language/korean" to "Korean",
        "/anime" to "Anime Zone",
        "/drama" to "K/J/C Drama",
        "/ongoing" to "Ongoing Series",
        "/genre/action" to "Action",
        "/genre/thriller" to "Thriller",
        "/genre/horror" to "Horror",
        "/genre/romance" to "Romance",
        "/category/wwe" to "WWE"
    )

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.AnimeMovie,
        TvType.Anime,
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // ── Resolve the live mirror URL ─────────────────────────────────────────
    // The canonical domain (movielinkbd.one) may redirect to a CDN mirror such as
    // https://sqghcr.movielinkbd.li/.  We follow the redirect once and cache it.
    @Volatile private var resolvedBase: String? = null

    private suspend fun getBase(): String {
        resolvedBase?.let { return it }
        return try {
            val resp = app.get(
                mainUrl, headers = headers,
                allowRedirects = true, timeout = 15
            )
            val finalUrl = resp.url.trimEnd('/')
            // keep only the origin (scheme + host)
            val uri = java.net.URI(finalUrl)
            val base = "${uri.scheme}://${uri.host}"
            resolvedBase = base
            base
        } catch (_: Exception) {
            FALLBACK_URL
        }
    }

    // ── Homepage / category pages ───────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getBase()
        val path = request.data
        val url = when {
            // Homepage: /, /page/2, /page/3 …
            path == "/" && page == 1 -> "$base/"
            path == "/" -> "$base/page/$page"
            // Category pages: /type/movies, /type/movies/page/2 …
            page == 1 -> "$base$path"
            else -> "$base$path/page/$page"
        }
        val doc = app.get(url, headers = headers, timeout = 30).document
        val items = parseMovieCards(doc, base)
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = true), hasNext = items.isNotEmpty())
    }

    // ── Search ──────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val base = getBase()
        val doc = app.get("$base/?search=${query.trim()}", headers = headers, timeout = 30).document
        return parseMovieCards(doc, base)
    }

    // ── Parse movie cards from listing pages ───────────────────────────────
    private fun parseMovieCards(doc: org.jsoup.nodes.Document, base: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // The site renders movie cards as <a> tags wrapping poster images,
        // each followed by a title link.  Common wrapper selectors:
        val cards = doc.select("div.movie-item, div.item-box, div.film-item, div.post-item, .movie-card")

        if (cards.isNotEmpty()) {
            cards.forEach { card ->
                val aTag = card.selectFirst("a[href*='/movie/'], a[href*='/series/'], a[href*='/anime/'], a[href*='/download18plus/']")
                    ?: return@forEach
                val href = aTag.attr("abs:href").ifEmpty { base + aTag.attr("href") }
                val title = card.selectFirst(".title, .movie-title, h3, h2")?.text()?.trim()
                    ?: aTag.attr("title").trim()
                    ?: return@forEach
                val img = card.selectFirst("img")
                val poster = img?.attr("data-src")?.ifEmpty { img.attr("src") }
                    ?: img?.attr("src")

                val type = if (href.contains("/series/") || href.contains("/anime/"))
                    TvType.TvSeries else TvType.Movie

                results.add(newMovieSearchResponse(title, href, type) {
                    this.posterUrl = poster
                })
            }
            return results
        }

        // Fallback: collect all anchor links to /movie/, /series/, /anime/ paths
        // that contain a child <img> (these are the poster links)
        val movieLinkPattern = "a[href*='/movie/'], a[href*='/series/'], a[href*='/anime/'], a[href*='/download18plus/']"
        val seen = mutableSetOf<String>()
        doc.select(movieLinkPattern).forEach { a ->
            val href = a.attr("abs:href").ifEmpty { base + a.attr("href") }
            if (!seen.add(href)) return@forEach
            // Skip nav/footer links (they usually don't have images)
            val img = a.selectFirst("img") ?: return@forEach
            val poster = img.attr("data-src").ifEmpty { img.attr("src") }

            // Title: try sibling/parent text nodes, then strip the encoded ID from the href
            val titleEl = a.parent()?.selectFirst(".title, .movie-title, h3, h2, [class*='name']")
            val title = titleEl?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: a.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: a.text().trim().takeIf { it.isNotEmpty() }
                ?: return@forEach

            val type = if (href.contains("/series/") || href.contains("/anime/"))
                TvType.TvSeries else TvType.Movie

            results.add(newMovieSearchResponse(title, href, type) {
                this.posterUrl = poster.takeIf { it.isNotEmpty() }
            })
        }

        // Another fallback: the read_url_content (markdown) showed that each
        // listing page simply has <a href="/movie/...">Title</a> text links.
        // If no images found at all, still return title-only cards.
        if (results.isEmpty()) {
            doc.select(movieLinkPattern).forEach { a ->
                val href = a.attr("abs:href").ifEmpty { base + a.attr("href") }
                if (!seen.add(href)) return@forEach
                val title = a.text().trim().takeIf { it.isNotEmpty() } ?: return@forEach
                // Skip nav items (short single words like "HOME", "HINDI", etc.)
                if (title.length < 4 || title.all { it.isUpperCase() || it == ' ' }) return@forEach
                val type = if (href.contains("/series/") || href.contains("/anime/"))
                    TvType.TvSeries else TvType.Movie
                results.add(newMovieSearchResponse(title, href, type))
            }
        }

        return results
    }


    // ── Detail page ─────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, timeout = 30).document

        // Title
        val rawTitle = doc.selectFirst("h1, .movie-title, .film-title, [class*='title']")?.text()?.trim()
            ?: doc.title().substringBefore("•").trim()

        // Year
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        // Poster
        val poster = doc.selectFirst("img.poster, img[class*='poster'], .poster img, .thumb img, img[src*='poster'], img[src*='uploads']")
            ?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            ?.takeIf { it.isNotEmpty() }

        // Meta info block — the site renders label: value pairs
        fun metaVal(label: String): String? {
            return doc.select("li, p, span, div").firstOrNull { el ->
                el.text().contains(label, ignoreCase = true)
            }?.text()?.substringAfter(":")?.trim()
        }

        // Storyline / plot
        val plot = doc.selectFirst(".storyline p, .storyline, [class*='story'] p, [class*='plot']")
            ?.text()?.trim()
            ?: metaVal("Storyline")

        // Genre, cast, language
        val genre = metaVal("Genre")
        val cast = metaVal("Cast")
        val language = metaVal("Language")
        val rating = doc.selectFirst("[class*='imdb'], [class*='rating']")?.text()
            ?.let { Regex("[0-9.]+").find(it)?.value?.toFloatOrNull() }

        val fullPlot = buildString {
            language?.let { append("Language: $it\n") }
            genre?.let { append("Genre: $it\n") }
            cast?.let { append("Cast: $it\n") }
            plot?.let { append("\n$it") }
        }.trim()

        // Determine if this is a series or movie by the URL path
        val isSeries = url.contains("/series/") || url.contains("/anime/")

        // ── Download links ──────────────────────────────────────────────────
        // Each quality button is an <a href="/getLink/..."> on the detail page.
        // We collect them grouped by episode sections (for series) or flat (for movies).

        // All getLink anchors
        val linkAnchors = doc.select("a[href*='/getLink/']")
        // Watch online anchors
        val watchAnchors = doc.select("a[href*='/getWatch/']")

        if (!isSeries) {
            // Movie: collect all download links as quality|getLink pairs
            val linksData = (linkAnchors + watchAnchors).mapNotNull { a ->
                val href = a.attr("abs:href").ifEmpty {
                    val h = a.attr("href")
                    if (h.startsWith("http")) h else "$mainUrl$h"
                }
                val text = a.text().trim()
                val quality = extractQualityLabel(text)
                "$quality|$href"
            }.joinToString(" ; ")

            return newMovieLoadResponse(rawTitle, url, TvType.Movie, linksData) {
                this.posterUrl = poster
                this.year = year
                this.plot = fullPlot.takeIf { it.isNotEmpty() }
                this.rating = rating?.let { (it * 1000).toInt() }
            }
        }

        // Series: group link anchors by episode sections
        // Look for headings/sections that contain episode identifiers
        val episodesData = mutableListOf<Episode>()

        // Try structured episode sections first
        val episodeSections = doc.select(
            "div.episode-section, div.season-section, h3:contains(Episode), h4:contains(Episode), " +
            "div[class*='episode'], div[class*='season'], strong:contains(Ep), b:contains(Ep)"
        )

        if (episodeSections.isNotEmpty()) {
            episodeSections.forEach { section ->
                val sectionText = section.text()
                val epRange = Regex("(?:Ep|Episode)[^\\d]*(\\d+)(?:[^\\d]+(\\d+))?", RegexOption.IGNORE_CASE)
                    .find(sectionText)
                val start = epRange?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val end = epRange?.groupValues?.get(2)?.toIntOrNull() ?: start

                // Collect links from siblings after this section heading
                val sectionLinks = mutableListOf<String>()
                var sib = section.nextElementSibling()
                while (sib != null && !sib.tagName().matches(Regex("h[1-6]"))) {
                    sib.select("a[href*='/getLink/'], a[href*='/getWatch/']").forEach { a ->
                        val href = a.attr("abs:href").ifEmpty {
                            val h = a.attr("href")
                            if (h.startsWith("http")) h else "$mainUrl$h"
                        }
                        val quality = extractQualityLabel(a.text())
                        sectionLinks.add("$quality|$href")
                    }
                    sib = sib.nextElementSibling()
                }

                if (sectionLinks.isNotEmpty()) {
                    val epUrl = sectionLinks.joinToString(" ; ")
                    for (epNum in start..end) {
                        episodesData.add(newEpisode(epUrl) {
                            this.name = "Episode $epNum"
                            this.season = 1
                            this.episode = epNum
                        })
                    }
                }
            }
        }

        // Fallback: if no structured sections found, treat all links as a single batch
        if (episodesData.isEmpty() && linkAnchors.isNotEmpty()) {
            val allLinks = (linkAnchors + watchAnchors).mapNotNull { a ->
                val href = a.attr("abs:href").ifEmpty {
                    val h = a.attr("href")
                    if (h.startsWith("http")) h else "$mainUrl$h"
                }
                val quality = extractQualityLabel(a.text())
                "$quality|$href"
            }.joinToString(" ; ")

            episodesData.add(newEpisode(allLinks) {
                this.name = "Full Season"
                this.season = 1
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, episodesData) {
            this.posterUrl = poster
            this.year = year
            this.plot = fullPlot.takeIf { it.isNotEmpty() }
            this.rating = rating?.let { (it * 1000).toInt() }
        }
    }

    // ── Load links (resolve getLink → direct file URL) ───────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        data.split(" ; ").forEach { item ->
            val parts = item.split("|")
            val qualityLabel = parts.getOrNull(0)?.trim() ?: ""
            val linkUrl = parts.getOrNull(1)?.trim() ?: item.trim()
            if (linkUrl.isEmpty()) return@forEach

            when {
                // getLink page → resolve to direct URL
                linkUrl.contains("/getLink/") -> {
                    resolveGetLink(linkUrl, qualityLabel, callback)
                }
                // getWatch page → resolve to stream URL
                linkUrl.contains("/getWatch/") -> {
                    resolveGetWatch(linkUrl, qualityLabel, callback)
                }
                // Direct file link
                linkUrl.contains("/file/") -> {
                    val quality = labelToQuality(qualityLabel)
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name [$qualityLabel]",
                            url = linkUrl,
                            referer = mainUrl,
                            quality = quality,
                            type = ExtractorLinkType.VIDEO,
                            headers = headers
                        )
                    )
                }
                else -> {
                    // Try loading as generic extractor link
                    com.lagradost.cloudstream3.utils.loadExtractor(linkUrl, mainUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    // ── Resolve /getLink/ to direct download URL ─────────────────────────────
    private suspend fun resolveGetLink(
        getLinkUrl: String,
        qualityLabel: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(getLinkUrl, headers = headers, timeout = 20).document

            // ONE CLICK DOWNLOAD → /file/... direct link
            val fileAnchor = doc.selectFirst("a[href*='/file/']")
            if (fileAnchor != null) {
                val fileUrl = fileAnchor.attr("abs:href")
                    .ifEmpty { fileAnchor.attr("href") }
                val quality = labelToQuality(qualityLabel)
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name [$qualityLabel]",
                        url = fileUrl,
                        referer = getLinkUrl,
                        quality = quality,
                        type = ExtractorLinkType.VIDEO,
                        headers = headers
                    )
                )
            }

            // Also check for mCloud or other hosted links
            doc.select("a[href]").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isEmpty() || href.contains("/file/")) return@forEach
                if (href.startsWith("http") && !href.contains("movielinkbd") &&
                    !href.contains("telegram") && !href.contains("google.com/store")) {
                    com.lagradost.cloudstream3.utils.loadExtractor(
                        href, getLinkUrl,
                        subtitleCallback = {},
                        callback = callback
                    )
                }
            }
        } catch (_: Exception) { }
    }

    // ── Resolve /getWatch/ to stream URL ────────────────────────────────────
    private suspend fun resolveGetWatch(
        getWatchUrl: String,
        qualityLabel: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(getWatchUrl, headers = headers, timeout = 20).document
            // Look for a video src or iframe src
            val videoSrc = doc.selectFirst("video source, video[src]")?.attr("src")
                ?: doc.selectFirst("iframe[src]")?.attr("src")
            if (!videoSrc.isNullOrEmpty()) {
                val quality = labelToQuality(qualityLabel)
                if (videoSrc.contains("m3u8")) {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name [Stream]",
                            url = videoSrc,
                            referer = getWatchUrl,
                            quality = quality,
                            type = ExtractorLinkType.M3U8,
                            headers = headers
                        )
                    )
                } else {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name [Stream]",
                            url = videoSrc,
                            referer = getWatchUrl,
                            quality = quality,
                            type = ExtractorLinkType.VIDEO,
                            headers = headers
                        )
                    )
                }
            }
        } catch (_: Exception) { }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private fun extractQualityLabel(text: String): String {
        return when {
            text.contains("4K", ignoreCase = true) || text.contains("2160", ignoreCase = true) -> "4K"
            text.contains("1080", ignoreCase = true) -> "1080p"
            text.contains("720p HEVC", ignoreCase = true) || text.contains("720 HEVC", ignoreCase = true) -> "720p HEVC"
            text.contains("720", ignoreCase = true) -> "720p"
            text.contains("480", ignoreCase = true) -> "480p"
            text.contains("360", ignoreCase = true) -> "360p"
            text.contains("Watch Online", ignoreCase = true) -> "Stream"
            text.contains("Download", ignoreCase = true) -> "Download"
            else -> text.take(30).trim().ifEmpty { "Unknown" }
        }
    }

    private fun labelToQuality(label: String): Int {
        return when {
            label.contains("4K", ignoreCase = true) || label.contains("2160", ignoreCase = true) -> Qualities.P2160.value
            label.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            label.contains("720", ignoreCase = true) -> Qualities.P720.value
            label.contains("480", ignoreCase = true) -> Qualities.P480.value
            label.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
