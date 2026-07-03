package com.animesuge.provider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink

class Vidwish : MegaPlay() {
    override val name = "Vidwish"
    override val mainUrl = "https://vidwish.live"
}

class Vidtube : MegaPlay() {
    override val name = "Vidtube"
    override val mainUrl = "https://vidtube.site"
}

open class MegaPlay : ExtractorApi() {
    override val name = "MegaPlay"
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractMegaPlayUrl(url, referer, mainUrl, name, subtitleCallback, callback)
    }

    companion object {
        suspend fun extractMegaPlayUrl(
            url: String,
            referer: String?,
            host: String,
            serverName: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            val playbackHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "Origin" to host,
                "Referer" to "$host/",
            )

            val pageHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to (referer ?: "https://anikoto.cz/")
            )

            val doc = app.get(url, headers = pageHeaders).document
            val playerEl = doc.selectFirst("#megaplay-player")
            val streamId = playerEl?.attr("data-id")
                ?: playerEl?.attr("data-realid")
                ?: Regex("""/stream/s-\d+/(\d+)/""").find(url)?.groupValues?.get(1)
                ?: return

            val type = if (url.contains("/dub", ignoreCase = true)) "dub" else "sub"

            val ajaxHeaders = mapOf(

                "Referer" to url,
            )

            val jsonText = try {
                app.get(
                    "$host/stream/getSources?id=$streamId&type=$type",
                    headers = ajaxHeaders,
                    referer = url
                ).text
            } catch (e: Exception) {
                Log.e("MegaPlay", "getSources failed: ${e.message}")
                return
            }

            val root = try {
                parseJson<MegaPlayResponse>(jsonText)
            } catch (e: Exception) {
                null
            } ?: return
            val m3u8 = root.sources?.file
            if (m3u8.isNullOrBlank()) {
                Log.e("MegaPlay", "No m3u8 in response for id=$streamId")
                return
            }

            val generated = M3u8Helper.generateM3u8(serverName, m3u8, host, headers = playbackHeaders)
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
            } else {
                callback(
                    newExtractorLink(serverName, serverName, m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "$host/"
                        this.headers = playbackHeaders
                    }
                )
            }

            try {
                root.tracks.forEach { track ->
                    val kind = track.kind ?: return@forEach
                    if (kind != "captions" && kind != "subtitles") return@forEach
                    val file = track.file ?: return@forEach
                    val label = track.label ?: "Unknown"
                    subtitleCallback(
                        newSubtitleFile(label, file) {
                            this.headers = playbackHeaders
                        }
                    )
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    data class MegaPlayResponse(
        @JsonProperty("sources") val sources: Sources? = null,
        @JsonProperty("tracks") val tracks: List<Track> = emptyList()
    )

    data class Sources(
        @JsonProperty("file") val file: String? = null
    )

    data class Track(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}