package com.cncverse

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Firebase Remote Config fetcher for the PlayZTV app.
 *
 * Firebase credentials are taken from the PlayZTV plugin.js:
 *   packageName  : com.playz.tv
 *   apiKey       : AIzaSyDKRqLlbaZBIpHzLBiQTUrJqr3gN-nDWWc
 *   appId        : 1:516859456626:android:12a75869902c4f8a6826eb
 *   projectNumber: 516859456626
 */
object PlayZTVFirebaseFetcher {

    private const val PACKAGE_NAME = "com.playz.tv"
    private const val API_KEY = "AIzaSyDKRqLlbaZBIpHzLBiQTUrJqr3gN-nDWWc"
    private const val APP_ID = "1:516859456626:android:12a75869902c4f8a6826eb"
    private const val PROJECT_NUMBER = "516859456626"
    private const val APP_VERSION = "2.1"
    private const val APP_BUILD = "4"
    private const val SDK_VERSION = "22.1.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class RemoteConfigResponse(
        val entries: Map<String, String>? = null,
        val state: String? = null
    )

    /**
     * Fetches Firebase Remote Config entries.
     * @return Map of config entries or null if the fetch fails.
     */
    suspend fun fetchRemoteConfig(): Map<String, String>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/$PROJECT_NUMBER/namespaces/firebase:fetch"
            val appInstanceId = UUID.randomUUID().toString().replace("-", "")

            val payload = """
                {
                    "appInstanceId": "$appInstanceId",
                    "appInstanceIdToken": "",
                    "appId": "$APP_ID",
                    "countryCode": "US",
                    "languageCode": "en-US",
                    "platformVersion": "30",
                    "timeZone": "UTC",
                    "appVersion": "$APP_VERSION",
                    "appBuild": "$APP_BUILD",
                    "packageName": "$PACKAGE_NAME",
                    "sdkVersion": "$SDK_VERSION",
                    "analyticsUserProperties": {}
                }
            """.trimIndent()

            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Android-Package", PACKAGE_NAME)
                .header("X-Goog-Api-Key", API_KEY)
                .header("X-Google-GFE-Can-Retry", "yes")
                .header("User-Agent", "okhttp/4.12.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body.string()
                if (body.isNotBlank()) {
                    return@withContext parseJson<RemoteConfigResponse>(body).entries
                }
            }
            null
        } catch (e: Exception) {
            println("PlayZTV: Firebase fetch failed – ${e.message}")
            null
        }
    }

    /**
     * Returns the `api_url` entry from Firebase Remote Config,
     * or null if it cannot be retrieved.
     */
    suspend fun getBaseApiUrl(): String? {
        return fetchRemoteConfig()?.get("api_url")?.trimEnd('/')
    }
}
