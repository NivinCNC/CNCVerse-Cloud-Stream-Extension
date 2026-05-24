package com.cncverse

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// ── Data classes shared by provider/live-event layers ────────────────────────

data class PlayZTVProviderEntry(
    val id: Int,
    val title: String,
    val image: String,
    val catLink: String?
)

data class PlayZTVCategoryWrapper(
    val cat: String   // inner JSON string
)

data class PlayZTVCategoryData(
    val visible: Boolean?,
    val name: String,
    val logo: String?,
    val type: String?,
    val api: String
)

data class PlayZTVEventWrapper(
    val event: String   // inner JSON string
)

data class PlayZTVEventData(
    val category: String?,
    val eventName: String?,
    val eventLogo: String?,
    val teamAName: String?,
    val teamBName: String?,
    val teamAFlag: String?,
    val teamBFlag: String?,
    val date: String?,
    val time: String?,
    val end_date: String?,
    val end_time: String?,
    val links: String?,
    val link_names: List<String>?,
    val visible: Boolean?,
    val priority: Int?
)

// ── Live event domain model (mirrored from SKTech) ────────────────────────────

data class PlayZLiveEventData(
    val id: Int,
    val title: String,
    val image: String?,
    val slug: String,
    val cat: String?,
    val eventInfo: PlayZLiveEventInfo?,
    val publish: Int,
    val formats: List<PlayZLiveEventFormat>?
)

data class PlayZLiveEventInfo(
    val teamA: String?,
    val teamB: String?,
    val teamAFlag: String?,
    val teamBFlag: String?,
    val eventCat: String?,
    val eventName: String?,
    val eventLogo: String?,
    val isHot: String?,
    val eventType: String?,
    val startTime: String?,
    val endTime: String?
)

data class PlayZLiveEventFormat(
    val title: String?,
    val webLink: String?
)

// ── Manager singleton ─────────────────────────────────────────────────────────

object PlayZTVProviderManager {

    /** Hardcoded fallback base URLs from the PlayZTV plugin.js */
    private val DEFAULT_BASE_URLS = listOf(
        "https://adsflw.xyz",
        "https://playztv2828.store"
    )

    private var cachedBaseUrl: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun parseDateTime(date: String?, time: String?): String? {
        if (date == null || time == null) return null
        return try {
            val parts = date.split("/")
            if (parts.size == 3) {
                val day = parts[0]; val month = parts[1]; val year = parts[2]
                "$year/$month/$day $time +0000"
            } else null
        } catch (_: Exception) { null }
    }

    /** Returns an active base URL, trying Firebase first then defaults. */
    private suspend fun getBaseUrl(): String {
        cachedBaseUrl?.let { return it }

        val firebaseUrl = PlayZTVFirebaseFetcher.getBaseApiUrl()
        if (!firebaseUrl.isNullOrBlank()) {
            cachedBaseUrl = firebaseUrl
            return firebaseUrl
        }

        // Try each default URL until one responds
        for (url in DEFAULT_BASE_URLS) {
            try {
                val req = Request.Builder()
                    .url("$url/categories.txt")
                    .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 10; SM-A505F)")
                    .head()
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.code < 500) {
                    cachedBaseUrl = url
                    return url
                }
            } catch (_: Exception) { /* try next */ }
        }

        cachedBaseUrl = DEFAULT_BASE_URLS.first()
        return cachedBaseUrl!!
    }

    private suspend fun fetchDecrypted(path: String): String? = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        val url = "$baseUrl/$path"
        return@withContext try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 10; SM-A505F)")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body.string()
                if (body.isNotBlank()) PlayZTVCryptoUtils.decryptPlayZTV(body.trim()) else null
            } else {
                println("PlayZTV: HTTP ${response.code} fetching $url")
                null
            }
        } catch (e: Exception) {
            println("PlayZTV: Exception fetching $url – ${e.message}")
            null
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetches the provider/category list from `{baseUrl}/categories.txt`.
     * Returns a list of maps compatible with SKTech's plugin registration.
     */
    suspend fun fetchProviders(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val decrypted = fetchDecrypted("categories.txt")
            if (!decrypted.isNullOrBlank()) {
                val wrappers = parseJson<List<PlayZTVCategoryWrapper>>(decrypted)
                return@withContext wrappers.mapIndexedNotNull { index, wrapper ->
                    try {
                        val cat = parseJson<PlayZTVCategoryData>(wrapper.cat)
                        if (cat.visible != false) {
                            mapOf(
                                "id" to (index + 1),
                                "title" to cat.name,
                                "image" to (cat.logo ?: ""),
                                "catLink" to cat.api
                            )
                        } else null
                    } catch (e: Exception) {
                        println("PlayZTV: Failed to parse category at $index – ${e.message}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            println("PlayZTV: fetchProviders exception – ${e.message}")
        }
        emptyList()
    }

    /**
     * Fetches live events from `{baseUrl}/events.txt`.
     */
    suspend fun fetchLiveEvents(): List<PlayZLiveEventData> = withContext(Dispatchers.IO) {
        try {
            val decrypted = fetchDecrypted("events.txt")
            if (!decrypted.isNullOrBlank()) {
                val wrappers = parseJson<List<PlayZTVEventWrapper>>(decrypted)
                val events = wrappers.mapIndexedNotNull { index, wrapper ->
                    try {
                        val ev = parseJson<PlayZTVEventData>(wrapper.event)
                        PlayZLiveEventData(
                            id = index + 1,
                            title = ev.eventName ?: "Unknown Event",
                            image = ev.eventLogo,
                            slug = ev.links?.substringBeforeLast(".") ?: "",
                            cat = ev.category,
                            eventInfo = PlayZLiveEventInfo(
                                teamA = ev.teamAName,
                                teamB = ev.teamBName,
                                teamAFlag = ev.teamAFlag,
                                teamBFlag = ev.teamBFlag,
                                eventCat = ev.category,
                                eventName = ev.eventName,
                                eventLogo = ev.eventLogo,
                                isHot = null,
                                eventType = ev.category,
                                startTime = parseDateTime(ev.date, ev.time),
                                endTime = parseDateTime(ev.end_date, ev.end_time)
                            ),
                            publish = if (ev.visible == true) 1 else 0,
                            formats = ev.link_names?.map { name ->
                                PlayZLiveEventFormat(title = name, webLink = ev.links)
                            } ?: emptyList()
                        )
                    } catch (e: Exception) {
                        println("PlayZTV: Failed to parse event at $index – ${e.message}")
                        null
                    }
                }
                return@withContext events.filter { it.publish == 1 }
            }
        } catch (e: Exception) {
            println("PlayZTV: fetchLiveEvents exception – ${e.message}")
        }
        emptyList()
    }

    /**
     * Fetches stream list from `{baseUrl}/{slug}.txt`.
     * Returns a list of [PlayZStreamUrl] or null.
     */
    suspend fun fetchChannelStreams(slug: String): List<PlayZStreamUrl>? = withContext(Dispatchers.IO) {
        try {
            val decrypted = fetchDecrypted("$slug.txt")
            if (!decrypted.isNullOrBlank()) {
                return@withContext parseJson<List<PlayZStreamUrl>>(decrypted)
            }
        } catch (e: Exception) {
            println("PlayZTV: fetchChannelStreams exception for $slug – ${e.message}")
        }
        null
    }
}

// ── Stream URL model returned by /channels/{slug}.txt ────────────────────────

data class PlayZStreamUrl(
    val name: String?,
    val link: String?,
    val scheme: Int?,
    val api: String?,
    val tokenApi: String?
)
