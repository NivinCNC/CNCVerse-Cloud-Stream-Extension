package com.animesuge.provider

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import java.net.URLEncoder
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

class AnimeSuge : MainAPI() {
    companion object {
        var context: android.content.Context? = null
        private const val OMG10 = "aHR0cHM6Ly9vbWcxMC5jb20vNC8xMTEwNDQ4OQ=="
        @Volatile private var lastBrowserOpenMs = 0L
        @Volatile private var telegramPopupShown = false
        @Volatile private var subscriptionPopupShown = false
        private const val BROWSER_DEBOUNCE_MS = 10_000L
    }

    override var mainUrl = "https://animesuge.cz"
    override var name = "AnimeSuge"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updated" to "Recently Updated",
        "$mainUrl/new-release"    to "New Releases",
        "$mainUrl/most-viewed"    to "Popular Anime",
        "$mainUrl/status/finished-airing"   to "Completed",
        "$mainUrl/status/currently-airing"  to "Ongoing"
    )

    // ── VRF helpers (RC4 → Base64 → shiftCharcode → Base64 → ROT13) ────────

    private fun rc4(key: ByteArray, input: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
        }
        var i = 0; j = 0
        val out = ByteArray(input.size)
        for (x in input.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
            out[x] = ((input[x].toInt() and 0xFF) xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }

    private fun shiftCharcode(t: String): ByteArray {
        val result = ByteArray(t.length)
        for (r in t.indices) {
            var s = t[r].code
            when (r % 8) {
                0 -> s -= 3; 1 -> s += 3; 2 -> s -= 4; 3 -> s += 2
                4 -> s -= 2; 5 -> s += 5; 6 -> s += 4; 7 -> s += 5
            }
            result[r] = s.toByte()
        }
        return result
    }

    private fun rot13(s: String) = s.map { c ->
        when (c) {
            in 'a'..'z' -> ((c.code - 'a'.code + 13) % 26 + 'a'.code).toChar()
            in 'A'..'Z' -> ((c.code - 'A'.code + 13) % 26 + 'A'.code).toChar()
            else -> c
        }
    }.joinToString("")

    private fun generateVrf(input: String): String {
        val encoded = URLEncoder.encode(input, "UTF-8").replace("+", "%20")
        val key = "ysJhV6U27FVIjjuk".toByteArray(Charsets.UTF_8)
        val rc4Bytes = rc4(key, encoded.toByteArray(Charsets.UTF_8))
        val b64 = Base64.encodeToString(rc4Bytes, Base64.URL_SAFE or Base64.NO_WRAP)
        val shifted = shiftCharcode(b64)
        val b64Shifted = Base64.encodeToString(shifted, Base64.URL_SAFE or Base64.NO_WRAP)
        return rot13(b64Shifted)
    }

    // ── Common request headers ───────────────────────────────────────────────

    private val ajaxHeaders = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer"          to "$mainUrl/"
    )

    // ── Home page ────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {         }
        val url = request.data + if (page > 1) "?page=$page" else ""
        val doc = app.get(url).document
        val items = doc.select("div.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = true)
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a.poster") ?: selectFirst("a") ?: return null
        val rawHref = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val href = fixUrl(rawHref.replace(Regex("/ep-\\d+$"), ""))
        val title = selectFirst(".name a, .name, img")?.let {
            if (it.tagName() == "img") it.attr("alt") else it.text()
        }?.trim() ?: return null
        val poster = selectFirst("img.lazyload, img")
            ?.let { it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src") }
            ?.let { if (it.startsWith("http")) it else "$mainUrl/$it" }
        return newAnimeSearchResponse(title, href) { this.posterUrl = poster }
    }

    // ── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {        if 
        val encoded = URLEncoder.encode(query, "UTF-8")
        return app.get("$mainUrl/filter?keyword=$encoded").document
            .select("div.item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    // ── Load (detail page) ───────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val animeUrl = url.replace(Regex("/ep-\\d+$"), "")
        val doc = app.get(animeUrl).document

        // Anime ID — from .watch-wrap[data-id] OR inline script mangaId
        val dataId = doc.selectFirst(".watch-wrap[data-id]")?.attr("data-id")
            ?: Regex("""mangaId\s*=\s*(\d+)""").find(doc.html())?.groupValues?.get(1)
            ?: return null

        val title = doc.selectFirst(".maindata h1.title, h1.title[itemprop=name], h1.title")
            ?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" Episode")?.trim()
            ?: "Unknown"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst(".description .full.cts-block div, .description .full div")
            ?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val genres = doc.select(".meta a[href*='/genre/'], .data a[href*='/genre/']")
            .map { it.text().trim() }

        // Fetch episode list with VRF
        val vrf = generateVrf(dataId)
        val epsText = app.get(
            "$mainUrl/ajax/episode/list/$dataId?vrf=$vrf",
            headers = ajaxHeaders
        ).text
        val epsJson = parseJson<AjaxResponse>(epsText)
        val epsHtml = epsJson.result ?: return null
        val epsSoup = Jsoup.parse(epsHtml)

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        epsSoup.select("a[data-ids]").forEach { epLink ->
            val epNum   = epLink.text().toIntOrNull()
                ?: epLink.attr("data-slug").toIntOrNull() ?: 1
            val epTitle = epLink.attr("data-num").takeIf { it.isNotBlank() } ?: "Episode $epNum"
            val dataIds = epLink.attr("data-ids").takeIf { it.isNotBlank() } ?: return@forEach
            val hasSub  = epLink.attr("data-sub") == "1"
            val hasDub  = epLink.attr("data-dub") == "1"

            if (hasSub) subEpisodes.add(newEpisode("$animeUrl|$dataId|$epNum|$dataIds|sub") {
                episode = epNum; name = epTitle
            })
            if (hasDub) dubEpisodes.add(newEpisode("$animeUrl|$dataId|$epNum|$dataIds|dub") {
                episode = epNum; name = epTitle
            })
        }

        return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot      = plot
            this.tags      = genres
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    // ── Load Links ───────────────────────────────────────────────────────────

    /**
     * data format: "{animeUrl}|{dataId}|{epNum}|{dataIds}|{sub|dub}"
     *
     * Flow:
     *   1. GET /ajax/server/list?servers={dataIds}  → HTML of server buttons
     *   2. Filter .server-type[data-type=sub|dub] → get each .server[data-link-id]
     *   3. GET /ajax/server?get={linkId}            → JSON with result.url (player embed URL)
     *   4. loadExtractor(playerUrl) or handle megaplay clones directly
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val parts = data.split("|")
        if (parts.size < 5) return false

        val animeUrl     = parts[0]
        // parts[1] = dataId, parts[2] = epNum — not needed beyond here
        val dataIds      = parts[3]
        val selectedType = parts[4] // "sub" or "dub"

        // 1. Get server list
        val serverListText = app.get(
            "$mainUrl/ajax/server/list?servers=$dataIds",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer"          to "$animeUrl/"
            )
        ).text
        val serverListJson = parseJson<AjaxResponse>(serverListText)
        val serverListHtml = serverListJson.result ?: return false
        val serverListSoup = Jsoup.parse(serverListHtml)

        // 2. Collect matching server link IDs
        val serversToLoad = mutableListOf<Pair<String, String>>() // (serverName, linkId)
        serverListSoup.select(".server-type").forEach { st ->
            val typeAttr = st.attr("data-type")
            val isMatch = if (selectedType == "sub") {
                typeAttr in listOf("sub", "hsub", "h-sub", "raw")
            } else {
                typeAttr in listOf("dub", "adub", "a-dub")
            }
            if (!isMatch) return@forEach
            st.select(".server").forEach { s ->
                val linkId     = s.attr("data-link-id").takeIf { it.isNotBlank() } ?: return@forEach
                val serverName = s.selectFirst("span")?.text()?.trim() ?: "Server"
                serversToLoad.add(serverName to linkId)
            }
        }

        if (serversToLoad.isEmpty()) return false

        var found = false

        // 3. For each server, resolve the player URL then extract
        serversToLoad.forEach { (serverName, linkId) ->
            try {
                val serverInfoText = app.get(
                    "$mainUrl/ajax/server?get=$linkId",
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer"          to "$animeUrl/"
                    )
                ).text
                val serverInfoJson = parseJson<ServerInfoResponse>(serverInfoText)
                val playerUrl = serverInfoJson.result?.url
                    ?.takeIf { it.isNotBlank() } ?: return@forEach

                val loaded = loadExtractor(playerUrl, "$mainUrl/", subtitleCallback, callback)
                if (loaded) found = true
            } catch (e: Exception) {
                // skip this server and try the next
            }
        }

        return found
    }

    // ── Data classes ─────────────────────────────────────────────────────────

    data class AjaxResponse(
        @JsonProperty("status") val status: Int?    = null,
        @JsonProperty("result") val result: String? = null
    )

    data class ServerInfoResponse(
        @JsonProperty("status") val status: Int?              = null,
        @JsonProperty("result") val result: ServerInfoResult? = null
    )

    data class ServerInfoResult(
        @JsonProperty("url") val url: String? = null
    )

   

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
}
