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
 * Firebase Remote Config Fetcher for SportzX App
 *
 * Fetches the dynamic API base URL from Firebase Remote Config,
 * mirroring the logic in sportzx.py (_get_api_url).
 *
 * Firebase project: sportzx-7cc3f
 * Package:          com.sportzx.live
 */
object SportzxFirebaseFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Data classes ──────────────────────────────────────────────────────────

    data class InstallResponse(
        val authToken: AuthToken?
    )
    data class AuthToken(
        val token: String?
    )
    data class RemoteConfigResponse(
        val entries: Map<String, String>? = null
    )

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Registers a Firebase Installation and returns the auth token,
     * mirroring the first HTTP call in sportzx.py _get_api_url().
     */
    private suspend fun getInstallAuthToken(): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://firebaseinstallations.googleapis.com/v1/projects/sportzx-7cc3f/installations"
            val body = """
                {
                    "fid": "$FID",
                    "appId": "$APP_ID",
                    "authVersion": "FIS_v2",
                    "sdkVersion": "a:18.0.0"
                }
            """.trimIndent()

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 13)")
                .header("X-Android-Cert", ANDROID_CERT)
                .header("X-Android-Package", PACKAGE_NAME)
                .header("x-firebase-client", "H4sIAAAAAAAAAKtWykhNLCpJSk0sKVayio7VUSpLLSrOzM9TslIyUqoFAFyivEQfAAAA")
                .header("x-goog-api-key", API_KEY)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                println("SportzxFirebase: Install failed HTTP ${response.code}")
                return@withContext null
            }
            val parsed = parseJson<InstallResponse>(response.body.string())
            parsed.authToken?.token
        } catch (e: Exception) {
            println("SportzxFirebase: Install exception — ${e.message}")
            null
        }
    }

    /**
     * Fetches Firebase Remote Config and returns the `entries` map,
     * mirroring the second HTTP call in sportzx.py _get_api_url().
     */
    private suspend fun fetchRemoteConfig(authToken: String): Map<String, String>? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/$PROJECT_NUMBER/namespaces/firebase:fetch"
                val body = """
                    {
                        "appVersion": "$APP_VERSION",
                        "firstOpenTime": "2025-11-10T16:00:00.000Z",
                        "timeZone": "Europe/Rome",
                        "appInstanceIdToken": "$authToken",
                        "languageCode": "it-IT",
                        "appBuild": "$APP_BUILD",
                        "appInstanceId": "$FID",
                        "countryCode": "IT",
                        "appId": "$APP_ID",
                        "platformVersion": "33",
                        "sdkVersion": "22.1.2",
                        "packageName": "$PACKAGE_NAME"
                    }
                """.trimIndent()

                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Dalvik/2.1.0 (Linux; Android 13)")
                    .header("X-Android-Cert", ANDROID_CERT)
                    .header("X-Android-Package", PACKAGE_NAME)
                    .header("X-Firebase-RC-Fetch-Type", "BASE/1")
                    .header("X-Goog-Api-Key", API_KEY)
                    .header("X-Goog-Firebase-Installations-Auth", authToken)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    println("SportzxFirebase: RemoteConfig failed HTTP ${response.code}")
                    return@withContext null
                }
                parseJson<RemoteConfigResponse>(response.body.string()).entries
            } catch (e: Exception) {
                println("SportzxFirebase: RemoteConfig exception — ${e.message}")
                null
            }
        }
}
