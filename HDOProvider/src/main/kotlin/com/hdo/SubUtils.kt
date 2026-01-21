package com.hdo

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.annotation.JsonProperty
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Locale

// Data classes for subtitle APIs
data class SubtitlesAPI(
    @JsonProperty("subtitles") val subtitles: List<SubtitleItem>?
)

data class SubtitleItem(
    @JsonProperty("lang") val lang: String?,
    @JsonProperty("url") val url: String?
)

data class WyZIESUB(
    @JsonProperty("display") val display: String?,
    @JsonProperty("url") val url: String?
)

object SubUtils {

    // Utility function to get language name from code
    private fun getLanguage(code: String?): String? {
        return when (code?.lowercase()) {
            "en" -> "English"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            "it" -> "Italian"
            "pt" -> "Portuguese"
            "ru" -> "Russian"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "zh" -> "Chinese"
            "ar" -> "Arabic"
            "hi" -> "Hindi"
            "ta" -> "Tamil"
            "te" -> "Telugu"
            "ml" -> "Malayalam"
            "kn" -> "Kannada"
            else -> code?.uppercase()
        }
    }

    suspend fun invokeSubtitleAPI(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val url = if (season == null) {
            "https://opensubtitles-v3.strem.io/subtitles/movie/$id.json"
        } else {
            "https://opensubtitles-v3.strem.io/subtitles/series/$id:$season:$episode.json"
        }
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        )
        app.get(url, headers = headers, timeout = 100L)
            .parsedSafe<SubtitlesAPI>()?.subtitles?.map {
                val lan = getLanguage(it.lang) ?: "Unknown"
                val suburl = it.url ?: ""
                subtitleCallback.invoke(
                    newSubtitleFile(
                        lan.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },  // Use label for the name
                        suburl     // Use extracted URL
                    )
                )
            }
    }


    suspend fun invokeWyZIESUBAPI(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val WyZIESUBAPI = "https://sub.wyzie.ru"
        val url = if (season == null) {
            "$WyZIESUBAPI/search?id=$id"
        } else {
            "$WyZIESUBAPI/search?id=$id&season=$season&episode=$episode"
        }

        val res = app.get(url).toString()
        val gson = Gson()
        val listType = object : TypeToken<List<WyZIESUB>>() {}.type
        val subtitles: List<WyZIESUB> = gson.fromJson(res, listType)
        subtitles.map {
            val lan = it.display ?: "Unknown"
            val suburl = it.url ?: ""
            subtitleCallback.invoke(
                newSubtitleFile(
                    lan.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },  // Use label for the name
                    suburl     // Use extracted URL
                )
            )
        }
    }
}
