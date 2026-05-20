package com.cncverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.api.Log
import okhttp3.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class StreamFlixWebSocketExtractor {
    private val client = OkHttpClient()

    data class WebSocketRequest(
        @JsonProperty("t") val t: String,
        @JsonProperty("d") val d: WebSocketData
    )

    data class WebSocketData(
        @JsonProperty("a") val a: String,
        @JsonProperty("r") val r: Int,
        @JsonProperty("b") val b: WebSocketBody
    )

    data class WebSocketBody(
        @JsonProperty("p") val p: String,
        @JsonProperty("h") val h: String
    )

    data class EpisodeData(
        @JsonProperty("key") val key: Int,
        @JsonProperty("link") val link: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String,
        @JsonProperty("runtime") val runtime: Int,
        @JsonProperty("still_path") val stillPath: String?,
        @JsonProperty("vote_average") val voteAverage: Double
    )

    suspend fun getEpisodesFromWebSocket(movieKey: String, totalSeasons: Int = 1): Map<Int, Map<Int, EpisodeData>> {
        return withTimeoutOrNull(30000) { // 30 second timeout
            suspendCancellableCoroutine { continuation ->
                val request = Request.Builder()
                    .url("wss://chilflix-410be-default-rtdb.asia-southeast1.firebasedatabase.app/.ws?ns=chilflix-410be-default-rtdb&v=5")
                    .build()

                val webSocket = client.newWebSocket(request, object : WebSocketListener() {
                    private var seasonsData = mutableMapOf<Int, Map<Int, EpisodeData>>()
                    private var receivedInitialData = false
                    private var expectedResponses = 0
                    private var responsesReceived = 0
                    private var currentSeason = 1
                    private var seasonsCompleted = 0
                    private var messageBuffer = StringBuilder()

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d("StreamFlix", "WebSocket opened, requesting $totalSeasons seasons")
                        
                        // Request first season
                        val requestData = WebSocketRequest(
                            t = "d",
                            d = WebSocketData(
                                a = "q",
                                r = currentSeason,
                                b = WebSocketBody(
                                    p = "Data/$movieKey/seasons/$currentSeason/episodes",
                                    h = ""
                                )
                            )
                        )
                        
                        val requestJson = requestData.toJson()
                        webSocket.send(requestJson)
                        Log.d("StreamFlix", "Sent request for season $currentSeason: $requestJson")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        Log.d("StreamFlix", "Received: ${text.take(100)}${if (text.length > 100) "..." else ""}")
                        
                        // Check if this is just a number (expected responses count)
                        try {
                            val number = text.trim().toInt()
                            if (expectedResponses == 0) { // Only set if not already set
                                expectedResponses = number
                                Log.d("StreamFlix", "Expecting $expectedResponses data responses for season $currentSeason")
                            }
                            return
                        } catch (e: NumberFormatException) {
                            // Not a number, continue with JSON parsing
                        }
                        
                        // Add to buffer and try to parse
                        messageBuffer.append(text)
                        val fullMessage = messageBuffer.toString()
                        
                        try {
                            val jsonObject = mapper.readTree(fullMessage)
                            // Successfully parsed, clear buffer and process
                            messageBuffer.clear()
                            processJsonMessage(jsonObject, webSocket)
                        } catch (e: Exception) {
                            // JSON parsing failed, might be incomplete message
                            if (fullMessage.length > 100000) { // Prevent infinite buffer growth
                                Log.e("StreamFlix", "Message too large, clearing buffer")
                                messageBuffer.clear()
                                if (continuation.isActive) {
                                    continuation.resume(seasonsData)
                                    webSocket.close(1000, "Message too large")
                                }
                            }
                            // Otherwise keep buffering until complete JSON arrives
                        }
                    }
                    
                    private fun processJsonMessage(jsonObject: JsonNode, webSocket: WebSocket) {
                        try {
                            if (jsonObject.has("t") && jsonObject.get("t").asText() == "d") {
                                val data = jsonObject.get("d")
                                
                                // Check for completion status message
                                val bData = data.get("b")
                                if (data.has("r") && bData != null && bData.has("s")) {
                                    val status = bData.get("s").asText()
                                    if (status == "ok") {
                                        Log.d("StreamFlix", "Received completion status for season $currentSeason")
                                        seasonsCompleted++
                                        Log.d("StreamFlix", "Season $currentSeason complete via status ($seasonsCompleted/$totalSeasons)")
                                        
                                        // Request next season if available
                                        if (seasonsCompleted < totalSeasons) {
                                            currentSeason++
                                            expectedResponses = 0  // Reset for next season
                                            responsesReceived = 0   // Reset for next season
                                            
                                            val requestData = WebSocketRequest(
                                                t = "d",
                                                d = WebSocketData(
                                                    a = "q",
                                                    r = currentSeason,
                                                    b = WebSocketBody(
                                                        p = "Data/$movieKey/seasons/$currentSeason/episodes",
                                                        h = ""
                                                    )
                                                )
                                            )
                                            
                                            webSocket.send(requestData.toJson())
                                            Log.d("StreamFlix", "Requesting season $currentSeason")
                                        } else {
                                            // All seasons completed, close and return
                                            Log.d("StreamFlix", "All $totalSeasons seasons completed")
                                            if (continuation.isActive) {
                                                continuation.resume(seasonsData)
                                                webSocket.close(1000, "Done")
                                            }
                                        }
                                        return
                                    }
                                }
                                
                                if (bData != null && bData.has("d")) {
                                    val episodes = bData.get("d")
                                    
                                    // Extract season number from path
                                    val path = bData.get("p")?.asText() ?: ""
                                    val seasonMatch = Regex("seasons/(\\d+)/episodes").find(path)
                                    val seasonNumber = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: currentSeason
                                    
                                    val episodeMap = mutableMapOf<Int, EpisodeData>()
                                    episodes?.fields()?.forEach { entry ->
                                        try {
                                            val episodeData = parseJson<EpisodeData>(entry.value.toString())
                                            episodeMap[entry.key.toInt()] = episodeData
                                            Log.d("StreamFlix", "Parsed episode ${entry.key}: ${episodeData.name}")
                                        } catch (e: Exception) {
                                            Log.e("StreamFlix", "Error parsing episode: ${e.message}")
                                        }
                                    }
                                    
                                    if (episodeMap.isNotEmpty()) {
                                        // Merge with existing episodes for this season
                                        val existingEpisodes = seasonsData[seasonNumber] ?: mutableMapOf()
                                        val mergedEpisodes = existingEpisodes.toMutableMap()
                                        mergedEpisodes.putAll(episodeMap)
                                        seasonsData[seasonNumber] = mergedEpisodes
                                        responsesReceived++
                                        Log.d("StreamFlix", "Added ${episodeMap.size} episodes for season $seasonNumber (${mergedEpisodes.size} total) ($responsesReceived/$expectedResponses)")
                                        
                                        // Don't automatically proceed here - wait for completion status
                                        if (expectedResponses == 0) {
                                            Log.d("StreamFlix", "No expected count yet, waiting for more data")
                                        }
                                    }
                                } else if (!receivedInitialData) {
                                    // First response might be different, try again
                                    receivedInitialData = true
                                } else {
                                    // No more data expected
                                    if (continuation.isActive) {
                                        continuation.resume(seasonsData)
                                        webSocket.close(1000, "Done")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("StreamFlix", "Error processing JSON message: ${e.message}")
                            if (continuation.isActive) {
                                continuation.resume(seasonsData)
                                webSocket.close(1000, "Error")
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e("StreamFlix", "WebSocket failed: ${t.message}")
                        if (continuation.isActive) {
                            continuation.resume(emptyMap())
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d("StreamFlix", "WebSocket closing: $code $reason")
                        if (continuation.isActive) {
                            continuation.resume(seasonsData)
                        }
                    }
                })

                continuation.invokeOnCancellation {
                    webSocket.close(1000, "Cancelled")
                }
            }
        } ?: emptyMap()
    }
}
