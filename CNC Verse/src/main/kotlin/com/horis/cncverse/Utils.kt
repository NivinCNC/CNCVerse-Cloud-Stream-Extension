package com.horis.cncverse

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlin.reflect.KClass
import okhttp3.FormBody
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import android.content.Context
import com.lagradost.api.Log
import org.json.JSONObject

val JSONParser = object : ResponseParser {
    val mapper: ObjectMapper = jacksonObjectMapper().configure(
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false
    ).configure(
        JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true
    )

    override fun <T : Any> parse(text: String, kClass: KClass<T>): T {
        return mapper.readValue(text, kClass.java)
    }

    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? {
        return try {
            mapper.readValue(text, kClass.java)
        } catch (e: Exception) {
            null
        }
    }

    override fun writeValueAsString(obj: Any): String {
        return mapper.writeValueAsString(obj)
    }
}

val app = Requests(responseParser = JSONParser).apply {
    defaultHeaders = mapOf("User-Agent" to USER_AGENT)
}

inline fun <reified T : Any> parseJson(text: String): T {
    return JSONParser.parse(text, T::class)
}

inline fun <reified T : Any> tryParseJson(text: String): T? {
    return try {
        return JSONParser.parseSafe(text, T::class)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun convertRuntimeToMinutes(runtime: String): Int {
    var totalMinutes = 0

    val parts = runtime.split(" ")

    for (part in parts) {
        when {
            part.endsWith("h") -> {
                val hours = part.removeSuffix("h").trim().toIntOrNull() ?: 0
                totalMinutes += hours * 60
            }
            part.endsWith("m") -> {
                val minutes = part.removeSuffix("m").trim().toIntOrNull() ?: 0
                totalMinutes += minutes
            }
        }
    }

    return totalMinutes
}

suspend fun bypass(mainUrl: String): String {
    // Check persistent storage first
    val (savedCookie, savedTimestamp) = NetflixMirrorStorage.getCookie()

    // Return cached cookie if valid (≤15 hours old)
    if (!savedCookie.isNullOrEmpty() && System.currentTimeMillis() - savedTimestamp < 54_000_000) {
        return savedCookie
    }

    val newCookie = try {
        var verifyCheck: String
        var verifyResponse: NiceResponse
        do {
            verifyResponse = app.post("$mainUrl/tv/p.php")
            verifyCheck = verifyResponse.text
        } while (!verifyCheck.contains("\"r\":\"n\""))
        verifyResponse.cookies["t_hash_t"].orEmpty()
    } catch (e: Exception) {
        // Clear invalid cookie on failure
        NetflixMirrorStorage.clearCookie()
        throw e
    }

    // Persist the new cookie
    if (newCookie.isNotEmpty()) {
        NetflixMirrorStorage.saveCookie(newCookie)
    }
    return newCookie
}

suspend fun getVideoToken(mainUrl: String, newUrl: String, id: String, cookies: Map<String, String>): String {
    val requestBody = FormBody.Builder().add("id", id).build()
    val headers = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/",
    )
    val json = app.post("$mainUrl/play.php", cookies = cookies, requestBody = requestBody, headers = headers).text
    val h = JSONObject(json).getString("h")

    val headers2 = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-GB,en;q=0.9",
        "Connection" to "keep-alive",
        "Host" to "net52.cc",
        "Referer" to "$mainUrl/",
        "sec-ch-ua" to "\"Chromium\";v=\"142\", \"Brave\";v=\"142\", \"Not_A Brand\";v=\"99\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Linux\"",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Fetch-Storage-Access" to "none",
        "Sec-Fetch-User" to "?1",
        "Sec-GPC" to "1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
    )
    val document = app.get("$newUrl/play.php?id=$id&$h", headers = headers2).document
    val token = document.select("body").attr("data-h")
    return token
}