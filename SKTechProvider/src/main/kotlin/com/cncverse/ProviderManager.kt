package com.cncverse

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class ProviderData(
    val id: Int,
    val title: String,
    val image: String,
    val catLink: String?
)

// SK Tech specific wrapper for categories
data class SKTechCategoryWrapper(
    val cat: String  // This is a JSON string that needs to be parsed
)

// Inner category data from the nested JSON
data class SKTechCategoryData(
    val visible: Boolean?,
    val name: String,
    val logo: String?,
    val type: String?,
    val api: String
)

// SK Tech specific wrapper for events
data class SKTechEventWrapper(
    val event: String  // This is a JSON string that needs to be parsed
)

// Inner event data from the nested JSON
data class SKTechEventData(
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

// Data classes for Live Events
data class LiveEventData(
    val id: Int,
    val title: String,
    val image: String?,
    val slug: String,
    val cat: String?,
    val eventInfo: LiveEventInfo?,
    val publish: Int,
    val formats: List<LiveEventFormat>?
)

data class LiveEventInfo(
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

data class LiveEventFormat(
    val title: String?,
    val webLink: String?
)

object ProviderManager {
    // SK Tech API URLs
    private const val CATEGORIES_URL = "https://sufyanpromax.space/categories.txt"
    private const val EVENTS_URL = "https://sufyanpromax.space/events.txt"
    
    // Helper function to convert SK Tech date/time format to expected format
    private fun parseDateTime(date: String?, time: String?): String? {
        if (date == null || time == null) return null
        try {
            // SK Tech format: date="23/01/2026", time="13:30:00"
            // Expected format: "yyyy/MM/dd HH:mm:ss Z"
            val parts = date.split("/")
            if (parts.size == 3) {
                val day = parts[0]
                val month = parts[1]
                val year = parts[2]
                return "$year/$month/$day $time +0000"
            }
        } catch (e: Exception) {
            println("SKTech: Failed to parse date/time: $date $time")
        }
        return null
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    // Fallback providers (empty for SK Tech, will fetch from API)
    private val fallbackProviders = emptyList<Map<String, Any>>()
    
    suspend fun fetchProviders(): List<Map<String, Any>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(CATEGORIES_URL)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val encryptedData = response.body.string()
                    if (!encryptedData.isNullOrBlank()) {
                        println("SKTech: Fetched encrypted categories data: ${encryptedData.length} chars")
                        
                        val decryptedData = SKLiveCryptoUtils.decryptSKLive(encryptedData.trim())
                        if (!decryptedData.isNullOrBlank()) {
                            println("SKTech: Decrypted categories successfully: ${decryptedData.length} chars")
                            
                            // Parse the wrapper objects that contain category JSON strings
                            val wrappers = parseJson<List<SKTechCategoryWrapper>>(decryptedData)
                            
                            // Parse each category JSON string and convert to provider map
                            val providers = wrappers.mapIndexedNotNull { index, wrapper ->
                                try {
                                    val categoryData = parseJson<SKTechCategoryData>(wrapper.cat)
                                    
                                    // Only include visible categories
                                    if (categoryData.visible != false) {
                                        mapOf(
                                            "id" to (index + 1),
                                            "title" to categoryData.name,
                                            "image" to (categoryData.logo ?: ""),
                                            "catLink" to categoryData.api
                                        )
                                    } else {
                                        null
                                    }
                                } catch (e: Exception) {
                                    println("SKTech: Failed to parse category at index $index: ${e.message}")
                                    e.printStackTrace()
                                    null
                                }
                            }
                            
                            return@withContext providers
                        } else {
                            println("SKTech: Failed to decrypt categories data")
                        }
                    } else {
                        println("SKTech: Empty response from categories URL")
                    }
                } else {
                    println("SKTech: HTTP error ${response.code} fetching categories")
                }
            } catch (e: Exception) {
                println("SKTech: Exception fetching providers: ${e.message}")
                e.printStackTrace()
            }
            // Return fallback providers if fetching fails
            fallbackProviders
        }
    }
    
    /**
     * Fetches live events from the SK Tech API
     */
    suspend fun fetchLiveEvents(): List<LiveEventData> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(EVENTS_URL)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val encryptedData = response.body.string()
                    if (!encryptedData.isNullOrBlank()) {
                        println("SKTech: Fetched encrypted events data: ${encryptedData.length} chars")
                        
                        val decryptedData = SKLiveCryptoUtils.decryptSKLive(encryptedData.trim())
                        if (!decryptedData.isNullOrBlank()) {
                            println("SKTech: Decrypted events successfully: ${decryptedData.length} chars")
                            
                            // Parse the wrapper objects that contain event JSON strings
                            val wrappers = parseJson<List<SKTechEventWrapper>>(decryptedData)
                            
                            // Parse each event JSON string and convert to LiveEventData
                            val events = wrappers.mapIndexedNotNull { index, wrapper ->
                                try {
                                    val eventData = parseJson<SKTechEventData>(wrapper.event)
                                    
                                    // Convert SKTechEventData to LiveEventData
                                    LiveEventData(
                                        id = index + 1,
                                        title = eventData.eventName ?: "Unknown Event",
                                        image = eventData.eventLogo,
                                        slug = eventData.links?.substringBeforeLast(".") ?: "",
                                        cat = eventData.category,
                                        eventInfo = LiveEventInfo(
                                            teamA = eventData.teamAName,
                                            teamB = eventData.teamBName,
                                            teamAFlag = eventData.teamAFlag,
                                            teamBFlag = eventData.teamBFlag,
                                            eventCat = eventData.category,
                                            eventName = eventData.eventName,
                                            eventLogo = eventData.eventLogo,
                                            isHot = null,
                                            eventType = eventData.category,
                                            startTime = parseDateTime(eventData.date, eventData.time),
                                            endTime = parseDateTime(eventData.end_date, eventData.end_time)
                                        ),
                                        publish = if (eventData.visible == true) 1 else 0,
                                        formats = eventData.link_names?.map { name ->
                                            LiveEventFormat(
                                                title = name,
                                                webLink = eventData.links
                                            )
                                        } ?: emptyList()
                                    )
                                } catch (e: Exception) {
                                    println("SKTech: Failed to parse event at index $index: ${e.message}")
                                    e.printStackTrace()
                                    null
                                }
                            }
                            
                            // Filter only visible/published events
                            return@withContext events.filter { it.publish == 1 }
                        } else {
                            println("SKTech: Failed to decrypt events data")
                        }
                    } else {
                        println("SKTech: Empty response from events URL")
                    }
                } else {
                    println("SKTech: HTTP error ${response.code} fetching events")
                }
            } catch (e: Exception) {
                println("SKTech: Exception fetching live events: ${e.message}")
                e.printStackTrace()
            }
            emptyList()
        }
    }
}
