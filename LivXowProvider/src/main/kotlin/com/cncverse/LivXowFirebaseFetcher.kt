package com.cncverse

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Firebase Remote Config Fetcher for LivXow TV App
 * Fetches remote config from Firebase to get API endpoints dynamically
 *
 * App: com.livxow.tv
 * Project: 459539398637
 */
object LivXowFirebaseFetcher {

    // Firebase credentials — injected from local.properties via BuildConfig
    private val API_KEY: String
        get() = try {
            com.cncverse.BuildConfig.LIVXOW_FIREBASE_API_KEY
        } catch (e: Exception) {
            ""
        }

    private val APP_ID: String
        get() = try {
            com.cncverse.BuildConfig.LIVXOW_FIREBASE_APP_ID
        } catch (e: Exception) {
            ""
        }

    private val PROJECT_NUMBER: String
        get() = try {
            com.cncverse.BuildConfig.LIVXOW_FIREBASE_PROJECT_NUMBER
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
     * Fetches Firebase Remote Config and returns the entries map.
     * Uses the exact headers and body observed from the LivXow APK traffic.
     *
     * @return Map of config entries or null if fetch fails
     */
    suspend fun fetchRemoteConfig(): Map<String, String>? {
        if (API_KEY.isBlank() || APP_ID.isBlank() || PROJECT_NUMBER.isBlank()) {
            println("LivXow: Firebase credentials not configured")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/$PROJECT_NUMBER/namespaces/firebase:fetch"

                val payload = """
                    {
                        "appVersion": "$APP_VERSION",
                        "timeZone": "Asia\/Calcutta",
                        "appInstanceIdToken": "",
                        "languageCode": "en-IN",
                        "appBuild": "$APP_BUILD",
                        "appInstanceId": "$APP_INSTANCE_ID",
                        "countryCode": "IN",
                        "analyticsUserProperties": {},
                        "appId": "$APP_ID",
                        "platformVersion": "$PLATFORM_VERSION",
                        "sdkVersion": "$SDK_VERSION",
                        "packageName": "$PACKAGE_NAME"
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
                        println("LivXow: Firebase fetch succeeded — state=${configResponse.state}, templateVersion=${configResponse.templateVersion}")
                        return@withContext configResponse.entries
                    }
                } else {
                    println("LivXow: Firebase fetch HTTP error ${response.code}")
                }

                null
            } catch (e: Exception) {
                println("LivXow: Firebase fetch exception — ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Gets the base API URL from Firebase Remote Config (entries["api_url"]).
     * @return Trimmed base URL or null if fetch fails
     */
    suspend fun getBaseApiUrl(): String? {
        val entries = fetchRemoteConfig()
        return entries?.get("api_url")?.trimEnd('/')
    }

    /**
     * Gets the Telegram URL from Firebase Remote Config.
     * Prefers new_telegram_url, falls back to telegram_url.
     */
    suspend fun getTelegramUrl(): String? {
        val entries = fetchRemoteConfig()
        return entries?.get("new_telegram_url") ?: entries?.get("telegram_url")
    }

    /**
     * Gets the web URL from Firebase Remote Config.
     */
    suspend fun getWebUrl(): String? {
        val entries = fetchRemoteConfig()
        return entries?.get("web_url")
    }

    /**
     * Fetches all config entries at once to avoid multiple network round-trips.
     * @return Triple of (apiUrl, telegramUrl, webUrl) — any may be null
     *
}
