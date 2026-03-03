package com.cncverse

import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import okio.ByteString
import okhttp3.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class StreamFlixWebSocketExtractor {
    private val gson = Gson()
    private val client = OkHttpClient()

    data class WebSocketRequest(
        @SerializedName("t") val t: String,
        @SerializedName("d") val d: WebSocketData
    )

    data class WebSocketData(
        @SerializedName("a") val a: String,
        @SerializedName("r") val r: Int,
        @SerializedName("b") val b: WebSocketBody
    )

    data class WebSocketBody(
        @SerializedName("p") val p: String,
        @SerializedName("h") val h: String
    )

    data class EpisodeData(
        @SerializedName("key") val key: Int,
        @SerializedName("link") val link: String,
        @SerializedName("name") val name: String,
        @SerializedName("overview") val overview: String,
        @SerializedName("runtime") val runtime: Int,
        @SerializedName("still_path") val stillPath: String?,
        @SerializedName("vote_average") val voteAverage: Double
    )

    suspend fun getEpisodesFromWebSocket(movieKey: String, totalSeasons: Int = 1): Map<Int, Map<Int, EpisodeData>> {
        return withTimeoutOrNull(30000) {
            suspendCancellableCoroutine { continuation ->
                val request = Request.Builder()
                    .url("wss://chilflix-410be-default-rtdb.asia-southeast1.firebasedatabase.app/.ws?ns=chilflix-410be-default-rtdb&v=5")
                    .build()

                val listener = StreamFlixWebSocketListener(movieKey, totalSeasons, continuation)
                val webSocket = client.newWebSocket(request, listener)

                continuation.invokeOnCancellation {
                    webSocket.close(1000, "Cancelled")
                }
            }
        } ?: emptyMap()
    }

    private inner class StreamFlixWebSocketListener(
        private val movieKey: String,
        private val totalSeasons: Int,
        private val continuation: CancellableContinuation<Map<Int, Map<Int, EpisodeData>>>
    ) : WebSocketListener() {
        private val seasonsData = mutableMapOf<Int, Map<Int, EpisodeData>>()
        private var expectedResponses = 0
        private var responsesReceived = 0
        private var currentSeason = 1
        private var seasonsCompleted = 0
        private val messageBuffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("StreamFlix", "WebSocket opened, requesting $totalSeasons seasons")
            sendSeasonRequest(webSocket, currentSeason)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleStringMessage(webSocket, text)
        }

        private fun handleStringMessage(webSocket: WebSocket, text: String) {
            // Check if this is just a number (expected responses count)
            val number = text.trim().toIntOrNull()
            if (number != null) {
                if (expectedResponses == 0) {
                    expectedResponses = number
                    Log.d("StreamFlix", "Expecting $expectedResponses data responses for season $currentSeason")
                }
                return
            }

            // Buffer and try to parse JSON
            messageBuffer.append(text)
            try {
                val jsonObject = JsonParser.parseString(messageBuffer.toString()).asJsonObject
                messageBuffer.setLength(0)
                processJsonMessage(jsonObject, webSocket)
            } catch (e: Exception) {
                if (messageBuffer.length > 100000) {
                    Log.e("StreamFlix", "Message too large, clearing buffer")
                    messageBuffer.setLength(0)
                    complete(webSocket, seasonsData, "Message too large")
                }
            }
        }

        private fun processJsonMessage(jsonObject: JsonObject, webSocket: WebSocket) {
            try {
                if (jsonObject.get("t")?.asString != "d") return
                val data = jsonObject.getAsJsonObject("d")

                if (handleStatus(data, webSocket)) return
                if (handleData(data)) return
            } catch (e: Exception) {
                Log.e("StreamFlix", "Error processing JSON message: ${e.message}")
                complete(webSocket, seasonsData, "Error")
            }
        }

        private fun handleStatus(data: JsonObject, webSocket: WebSocket): Boolean {
            val b = data.getAsJsonObject("b") ?: return false
            if (b.get("s")?.asString != "ok") return false

            seasonsCompleted++
            Log.d("StreamFlix", "Season $currentSeason complete ($seasonsCompleted/$totalSeasons)")

            if (seasonsCompleted < totalSeasons) {
                currentSeason++
                expectedResponses = 0
                responsesReceived = 0
                sendSeasonRequest(webSocket, currentSeason)
            } else {
                complete(webSocket, seasonsData, "Done")
            }
            return true
        }

        private fun handleData(data: JsonObject): Boolean {
            val b = data.getAsJsonObject("b") ?: return false
            val episodesJson = b.getAsJsonObject("d") ?: return false

            val path = b.get("p")?.asString ?: ""
            val seasonMatch = Regex("seasons/(\\d+)/episodes").find(path)
            val seasonNumber = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: currentSeason

            val episodeMap = episodesJson.entrySet().associate { entry ->
                entry.key.toInt() to gson.fromJson(entry.value, EpisodeData::class.java)
            }

            if (episodeMap.isNotEmpty()) {
                val existing = seasonsData[seasonNumber] ?: emptyMap()
                seasonsData[seasonNumber] = existing + episodeMap
                responsesReceived++
                Log.d("StreamFlix", "Updated season $seasonNumber: ${episodeMap.size} new episodes ($responsesReceived/$expectedResponses)")
            }
            return true
        }

        private fun sendSeasonRequest(webSocket: WebSocket, season: Int) {
            val requestData = WebSocketRequest(
                t = "d",
                d = WebSocketData(
                    a = "q",
                    r = season,
                    b = WebSocketBody(p = "Data/$movieKey/seasons/$season/episodes", h = "")
                )
            )
            webSocket.send(gson.toJson(requestData))
        }

        private fun complete(webSocket: WebSocket, result: Map<Int, Map<Int, EpisodeData>>, reason: String) {
            if (continuation.isActive) {
                continuation.resume(result)
                webSocket.close(1000, reason)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("StreamFlix", "WebSocket failed: ${t.message}")
            complete(webSocket, emptyMap(), "Failure")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            complete(webSocket, seasonsData, reason)
        }
    }
}
