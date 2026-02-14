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
 * Firebase Remote Config Fetcher for SKTech Live App
 * Fetches remote config from Firebase to get API endpoints dynamically
 */
object FirebaseRemoteConfigFetcher {
    
    private const val PACKAGE_NAME = "com.live.sktechtv"
    
    // Get credentials from BuildConfig (injected from local.properties)
    private val API_KEY: String
        get() = try {
            com.cncverse.BuildConfig.SKTECH_FIREBASE_API_KEY
        } catch (e: Exception) {
            ""
        }
    
    private val APP_ID: String
        get() = try {
            com.cncverse.BuildConfig.SKTECH_FIREBASE_APP_ID
        } catch (e: Exception) {
            ""
        }
    
    private val PROJECT_NUMBER: String
        get() = try {
            com.cncverse.BuildConfig.SKTECH_FIREBASE_PROJECT_NUMBER
        } catch (e: Exception) {
            ""
        }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    data class RemoteConfigResponse(
        val entries: Map<String, String>? = null,
        val appName: String? = null,
        val state: String? = null,
        val templateVersion: String? = null
    )
    
    /**
     * Fetches Firebase Remote Config and returns the entries map
     * @return Map of config entries or null if fetch fails
     */
    suspend fun fetchRemoteConfig(): Map<String, String>? {
        if (API_KEY.isBlank() || APP_ID.isBlank() || PROJECT_NUMBER.isBlank()) {
            return null
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/$PROJECT_NUMBER/namespaces/firebase:fetch"
                
                // Generate fake instance ID
                val appInstanceId = UUID.randomUUID().toString().replace("-", "")
                
                // Request payload based on ConfigFetchHttpClient.java
                val payload = """
                    {
                        "appInstanceId": "$appInstanceId",
                        "appInstanceIdToken": "",
                        "appId": "$APP_ID",
                        "countryCode": "US",
                        "languageCode": "en-US",
                        "platformVersion": "30",
                        "timeZone": "UTC",
                        "appVersion": "5.0",
                        "appBuild": "50",
                        "packageName": "$PACKAGE_NAME",
                        "sdkVersion": "22.1.0",
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
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body.string()
                    if (!responseBody.isNullOrBlank()) {
                        val configResponse = parseJson<RemoteConfigResponse>(responseBody)
                        return@withContext configResponse.entries
                    }
                }
                
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
    
    /**
     * Gets the base API URL from Firebase Remote Config
     * Returns the api_url field which is the base URL for categories and events
     * @return The base API URL or null if fetch fails
     */
    suspend fun getBaseApiUrl(): String? {
        val entries = fetchRemoteConfig()
        return entries?.get("api_url")?.trimEnd('/')
    }
    
    /**
     * Gets the Telegram URL from Firebase Remote Config
     * @return The Telegram URL or null if fetch fails
     */
    suspend fun getTelegramUrl(): String? {
        val entries = fetchRemoteConfig()
        return entries?.get("telegram_url")
    }
    
    /**
     * Gets the web URL from Firebase Remote Config
     * @return The web URL or null if fetch fails
     */
    suspend fun getWebUrl(): String? {
        val entries = fetchRemoteConfig()
        return entries?.get("web_url")
    }
}
