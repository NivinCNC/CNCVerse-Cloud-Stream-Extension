package com.cncverse

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// ── Data classes (matching decrypted events.txt / categories.txt) ─────────────

/**
 * A single category entry from the decrypted categories.txt.
 *
 * Decrypted structure (flat JSON array, NOT double-encoded):
 * [
 *   { "id": "2", "title": "Sports", "image": "...", "catLink": "Sports" },
 *   ...
 * ]
 */
data class SportzxCategoryData(
    val id: String?,
    val title: String,
    val image: String?,
    val catLink: String?
)

/**
 * A single event from the decrypted events.txt.
 *
 * Decrypted structure (flat JSON array, NOT double-encoded):
 * [
 *   {
 *     "id": 50002,
 *     "title": "Formula 1",
 *     "image": "o",
 *     "cat": "F1",
 *     "eventInfo": { "teamA": "...", ... , "startTime": "2026/07/03 11:30:00 +0000" },
 *     "publish": "1",
 *     "formatsNew": [ { "title": "SKY F1 FHD", "logo": "..." }, ... ]
 *   },
 *   ...
 * ]
 */
data class SportzxEventData(
    val id: Int?,
    val title: String?,
    val image: String?,
    val cat: String?,
    val eventInfo: SportzxEventInfo?,
    val publish: String?,            // "1" = published
    val formatsNew: List<SportzxFormat>?
)

data class SportzxEventInfo(
    val teamA: String?,
    val teamB: String?,
    val teamAFlag: String?,
    val teamBFlag: String?,
    val eventName: String?,
    val eventType: String?,
    val eventBanner: String?,
    val eventLogo: String?,
    val isHot: String?,
    val startTime: String?,          // "2026/07/03 11:30:00 +0000"
    val endTime: String?
)

data class SportzxFormat(
    val title: String?,
    val logo: String?
)

/**
 * A single stream entry from the decrypted /channels/{id}.json.
 *
 * Decrypted structure (flat JSON array):
 * [
 *   {
 *     "title": "SKY F1 FHD",
 *     "link": "https://…stream.m3u8|Referer=https://example.com",
 *     "api": "kid:key"          // optional DRM clearkey "keyId:keyValue"
 *   },
 *   ...
 * ]
 */
data class SportzxStreamEntry(
    val title: String?,
    val link: String?,
    val api: String?
)

// ── Shared LiveEvent models (re-used by SportzxLiveEventsProvider) ─────────────

data class SportzxLiveEventData(
    val id: Int,
    val title: String,
    val image: String?,
    val eventId: Int,              // numeric event ID used to build channel URL
    val cat: String?,
    val eventInfo: SportzxLiveEventInfo?,
    val publish: Int,
    val formats: List<SportzxLiveEventFormat>?
)

data class SportzxLiveEventInfo(
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

data class SportzxLiveEventFormat(
    val title: String?,
    val logo: String?
)

object SportzxProviderManager {

    @Volatile private var cachedBaseUrl: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseHeaders = mapOf(
        "User-Agent" to "Dalvik/2.1.0 (Linux; Android 13)"
    )

    // ── URL resolution ────────────────────────────────────────────────────────

    /**
     * Returns the effective API base URL (no trailing slash).
     * Priority: Firebase Remote Config → cached value.
     */
    suspend fun getBaseUrl(): String {
        cachedBaseUrl?.let { return it }
        val firebaseUrl = SportzxFirebaseFetcher.getBaseApiUrl()
        cachedBaseUrl = if (!firebaseUrl.isNullOrBlank()) firebaseUrl.trimEnd('/')
                        else ""
        return cachedBaseUrl!!
    }

    fun invalidateCache() {
        cachedBaseUrl = null
    }
 
}
