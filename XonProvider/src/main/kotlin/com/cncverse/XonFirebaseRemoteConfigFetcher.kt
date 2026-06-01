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
 * Firebase Remote Config Fetcher for XON TV App
 * Fetches remote config from Firebase to get API endpoints dynamically
 */
object XonFirebaseRemoteConfigFetcher {
    
    private const val PACKAGE_NAME = "Com.XON.Anime.Cartoon.XonTV.app"
    private const val ANDROID_CERT = "85A99DC9E15F7BDE6212D089742331EE32E80FE6"
    
    // Get credentials from BuildConfig (injected from local.properties)
    private val API_KEY: String
        get() = try {
            com.cncverse.BuildConfig.XON_FIREBASE_API_KEY
        } catch (e: Exception) {
            ""
        }
    
    private val APP_ID: String
        get() = try {
            com.cncverse.BuildConfig.XON_FIREBASE_APP_ID
        } catch (e: Exception) {
            ""
        }
    
    private val PROJECT_NUMBER: String
        get() = try {
            com.cncverse.BuildConfig.XON_FIREBASE_PROJECT_NUMBER
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
                        "countryCode": "IN",
                        "languageCode": "en-IN",
                        "platformVersion": "33",
                        "timeZone": "Asia/Calcutta",
                        "appVersion": "17",
                        "appBuild": "17",
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
                    .header("X-Android-Cert", ANDROID_CERT)
                    .header("X-Firebase-RC-Fetch-Type", "BASE/1")
                    .header("X-Goog-Api-Key", API_KEY)
                    .header("X-Google-GFE-Can-Retry", "yes")
                    .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 5 Build/TQ3A.230901.001)")
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
     * @return The base API URL or null if fetch fails
     */
    suspend fun getBaseUrl(): String? {
        val entries = fetchRemoteConfig()
        return entries?.get("base_url")?.trimEnd('/')
    }
    
    /**
     * Gets the API key from Firebase Remote Config
     * @return The API key or null if fetch fails
     */
    suspend fun getApiKey(): String? {
        val entries = fetchRemoteConfig()
        return entries?.get("api_key")
    }
    
    /**
     * Gets the caller name from Firebase Remote Config
     * @return The caller name or null if fetch fails
     */
    suspend fun getCallerName(): String? {
        val entries = fetchRemoteConfig()
        return entries?.get("caller_name")
    }
    
    /**
     * Gets all config entries at once to avoid multiple network calls
     * @return Triple of (baseUrl, apiKey, callerName) or null values if fetch fails
     */
    suspend fun getAllConfig(): Triple<String?, String?, String?> {
        val entries = fetchRemoteConfig()
        return Triple(
            entries?.get("base_url")?.trimEnd('/'),
            entries?.get("api_key"),
            entries?.get("caller_name")
        )
    }
}
