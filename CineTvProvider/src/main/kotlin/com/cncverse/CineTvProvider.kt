package com.cncverse

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.*
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.net.URI

class CineTvProvider : MainAPI() {
    companion object {
        var context: Context? = null
        
        // DES3 constants from BuildConfig
        private val SECRET_KEY_ENCRYPTED = BuildConfig.CINETV_SECRET_KEY_ENCRYPTED
        private val DES_KEY = BuildConfig.CINETV_DES_KEY
        private val DES_IV = BuildConfig.CINETV_DES_IV
        
        // AES constants from BuildConfig
        private val AES_KEY = BuildConfig.CINETV_AES_KEY
        private val AES_IV = BuildConfig.CINETV_AES_IV
        
        // URL signing secret from BuildConfig
        private val WS_SECRET = BuildConfig.CINETV_WS_SECRET
    }
    
    override var mainUrl = "https://i6a6.t9z0.com"
    override var name = "CineTv"
    override val hasMainPage = true
    override var lang = "ta"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    
    private val deviceId = "2987149b2e2a63b2"
    private val gaid = ""
    private var token: String? = null
    private val mapper = jacksonObjectMapper()
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VodItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("vod_name") val vodName: String,
        @JsonProperty("vod_pic") val vodPic: String?,
        @JsonProperty("vod_year") val vodYear: String?,
        @JsonProperty("vod_actor") val vodActor: String?,
        @JsonProperty("vod_director") val vodDirector: String?,
        @JsonProperty("vod_blurb") val vodBlurb: String?,
        @JsonProperty("type_pid") val typePid: Int, // 1 = Movie, 2 = TV Series
        @JsonProperty("vod_total") val vodTotal: Int?, // Total episodes
        @JsonProperty("vod_serial") val vodSerial: Int?, // Current episode
        @JsonProperty("vod_douban_score") val vodDoubanScore: Double?,
        @JsonProperty("vod_en") val vodEn: String?,
        @JsonProperty("audio_language_tag") val audioLanguageTag: String?,
        @JsonProperty("vod_area") val vodArea: String?
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiResponse(
        @JsonProperty("code") val code: Int,
        @JsonProperty("message") val message: String,
        @JsonProperty("result") val result: List<VodItem>?
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TopicResult(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("type_id") val typeId: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("vod_list") val vodList: List<VodItem>?
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TopicApiResponse(
        @JsonProperty("code") val code: Int,
        @JsonProperty("message") val message: String,
        @JsonProperty("result") val result: TopicResult?
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class InitResponse(
        @JsonProperty("code") val code: Int,
        @JsonProperty("result") val result: InitResult?
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class InitResult(
        @JsonProperty("user_info") val userInfo: UserInfo?
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class UserInfo(
        @JsonProperty("token") val token: String?
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VodCollection(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("vod_url") val vodUrl: String?,
        @JsonProperty("down_url") val downUrl: String?,
        @JsonProperty("duration") val duration: String?,
        @JsonProperty("vod_duration") val vodDuration: Int?,
        @JsonProperty("collection") val collection: Int?,
        @JsonProperty("is_p2p") val isP2p: Int?
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AudioTypeOption(
        @JsonProperty("type") val type: Int?,
        @JsonProperty("type_name") val typeName: String?
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeriesInfo(
        @JsonProperty("vod_id") val vodId: Int?,
        @JsonProperty("series") val series: String?,
        @JsonProperty("default") val default: Boolean?
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VodInfoResult(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("vod_name") val vodName: String?,
        @JsonProperty("vod_pic") val vodPic: String?,
        @JsonProperty("vod_year") val vodYear: String?,
        @JsonProperty("vod_actor") val vodActor: String?,
        @JsonProperty("vod_director") val vodDirector: String?,
        @JsonProperty("vod_blurb") val vodBlurb: String?,
        @JsonProperty("type_pid") val typePid: Int?,
        @JsonProperty("vod_total") val vodTotal: Int?,
        @JsonProperty("vod_serial") val vodSerial: Int?,
        @JsonProperty("vod_douban_score") val vodDoubanScore: Double?,
        @JsonProperty("vod_tag") val vodTag: String?,
        @JsonProperty("vod_collection") val vodCollection: List<VodCollection>?,
        @JsonProperty("audio_type_option") val audioTypeOption: List<AudioTypeOption>?,
        @JsonProperty("series_info") val seriesInfo: List<SeriesInfo>?,
        @JsonProperty("audio_language_tag") val audioLanguageTag: String?,
        @JsonProperty("vod_area") val vodArea: String?
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VodInfoResponse(
        @JsonProperty("code") val code: Int,
        @JsonProperty("message") val message: String?,
        @JsonProperty("result") val result: VodInfoResult?
    )
    
    // DES3 Decryption
    private fun des3Decrypt(encryptedText: String): String {
        return try {
            val cipher = Cipher.getInstance("DESede/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(DES_KEY.toByteArray().copyOf(24), "DESede")
            val ivSpec = IvParameterSpec(DES_IV.toByteArray())
            
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val encryptedData = Base64.decode(encryptedText, Base64.DEFAULT)
            val decryptedData = cipher.doFinal(encryptedData)
            
            String(decryptedData)
        } catch (e: Exception) {
            throw Exception("DES3 decryption failed: ${e.message}")
        }
    }
    
    // MD5 Hash
    private fun md5Hash(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
    
    // Generate Signature
    private fun generateSign(curTime: String): String {
        val decryptedSecret = des3Decrypt(SECRET_KEY_ENCRYPTED)
        val signString = decryptedSecret + deviceId + curTime
        return md5Hash(signString).uppercase()
    }
    
    // Generate P2P Token (for video playback requests)
    private fun generateP2pToken(deviceId: String, vodId: String, timestamp: String): String {
        val salt = "Zox882LYjEn4Rqpa"
        val concatenated = salt + deviceId + vodId + timestamp
        return md5Hash(concatenated).uppercase()
    }
    
    // Sign URL with wsSecret and wsTime parameters
    private fun signVideoUrl(url: String): String {
        // Extract path from URL
        val uri = URI(url)
        val path = uri.path
        
        // Calculate expiry time (5 hours from now) in hex format
        val expirySeconds = 5 * 60 * 60
        val wsTime = java.lang.Long.toHexString(System.currentTimeMillis() / 1000 + expirySeconds)
        
        // Generate wsSecret using MD5 hash of: SECRET + path + wsTime
        val raw = WS_SECRET + path + wsTime
        val wsSecret = md5Hash(raw)
        
        // Append parameters to URL
        return "$url?wsSecret=$wsSecret&wsTime=$wsTime"
    }
    
    // AES Decryption
    private fun aesDecrypt(encryptedBase64: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray())
            
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val encryptedData = Base64.decode(encryptedBase64, Base64.DEFAULT)
            val decryptedData = cipher.doFinal(encryptedData)
            
            // Check if gzip compressed
            if (decryptedData.size >= 2 && decryptedData[0] == 0x1f.toByte() && decryptedData[1] == 0x8b.toByte()) {
                // GZIP decompression
                val gzipInputStream = GZIPInputStream(decryptedData.inputStream())
                return gzipInputStream.bufferedReader().use { it.readText() }
            }
            
            String(decryptedData)
        } catch (e: Exception) {
            throw Exception("AES decryption failed: ${e.message}")
        }
    }
    
    // Fetch Device Token
    private suspend fun fetchDeviceToken(invitedBy: String = ""): String {
        val url = "$mainUrl/api/public/init"
        val curTime = System.currentTimeMillis().toString()
        
        val headers = mapOf(
            "androidid" to deviceId,
            "app_id" to "cinetvin",
            "app_language" to "en",
            "channel_code" to "cinetvin_3001",
            "Connection" to "Keep-Alive",
            "Content-Type" to "application/x-www-form-urlencoded",
            "cur_time" to curTime,
            "device_id" to deviceId,
            "en_al" to "0",
            "gaid" to gaid,
            "Host" to "i6a6.t9z0.com",
            "is_display" to "GMT+05:30",
            "is_language" to "en",
            "is_vvv" to "0",
            "log-header" to "I am the log request header.",
            "mob_mfr" to "google",
            "mobmodel" to "Pixel 5",
            "package_name" to "com.cti.cinetvin",
            "sign" to generateSign(curTime),
            "sys_platform" to "2",
            "sysrelease" to "13",
            "token" to "",
            "User-Agent" to "okhttp/4.11.0",
            "version" to "30000"
        )
        
        val formBody = FormBody.Builder()
            .add("invited_by", invitedBy)
            .add("is_install", "1")
            .build()
        
        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .apply {
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        
        return try {
            val response = app.baseClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseText = response.body?.string()?.trim() ?: ""
                
                // Decrypt if encrypted (response doesn't start with '{')
                val jsonText = if (responseText.isNotEmpty() && !responseText.startsWith("{")) {
                    aesDecrypt(responseText)
                } else {
                    responseText
                }
                
                val initResponse = mapper.readValue<InitResponse>(jsonText)
                initResponse.result?.userInfo?.token ?: ""
            } else {
                ""
            }
        } catch (e: Exception) {
            throw Exception("Failed to fetch device token: ${e.message}")
        }
    }
    
    // Get Headers
    private suspend fun getHeaders(curTime: String? = null): Map<String, String> {
        val timestamp = curTime ?: System.currentTimeMillis().toString()
        
        if (token == null || token == "") {
            token = fetchDeviceToken()
        }
        
        return mapOf(
            "Accept-Encoding" to "identity",
            "androidid" to deviceId,
            "app_id" to "cinetvin",
            "app_language" to "en",
            "channel_code" to "cinetvin_3001",
            "Connection" to "Keep-Alive",
            "Content-Type" to "application/x-www-form-urlencoded",
            "cur_time" to timestamp,
            "device_id" to deviceId,
            "en_al" to "0",
            "gaid" to gaid,
            "Host" to "i6a6.t9z0.com",
            "is_display" to "GMT+05:30",
            "is_language" to "en",
            "is_vvv" to "0",
            "log-header" to "I am the log request header.",
            "mob_mfr" to "google",
            "mobmodel" to "Pixel 5",
            "package_name" to "com.cti.cinetvin",
            "sign" to generateSign(timestamp),
            "sys_platform" to "2",
            "sysrelease" to "13",
            "token" to (token ?: ""),
            "User-Agent" to "okhttp/4.11.0",
            "version" to "30000"
        )
    }
    
    // Search Recommend API
    private suspend fun searchRecommend(pageNumber: Int = 1): ApiResponse? {
        val url = "$mainUrl/api/search/recommend"
        val curTime = System.currentTimeMillis().toString()
        val headers = getHeaders(curTime)
        
        val formBody = FormBody.Builder()
            .add("pn", pageNumber.toString())
            .build()
        
        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .apply {
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        
        return try {
            val response = app.baseClient.newCall(request).execute()
            if (response.isSuccessful) {
                val encryptedText = response.body?.string() ?: return null
                val decryptedJson = aesDecrypt(encryptedText)
                mapper.readValue<ApiResponse>(decryptedJson)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    // Topic VOD List API
    private suspend fun topicVodList(topicId: Int, pageNumber: Int = 1): List<VodItem>? {
        val url = "$mainUrl/api/topic/vod_list"
        val curTime = System.currentTimeMillis().toString()
        val headers = getHeaders(curTime)
        
        val formBody = FormBody.Builder()
            .add("topic_id", topicId.toString())
            .add("pn", pageNumber.toString())
            .build()
        
        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .apply {
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        
        return try {
            val response = app.baseClient.newCall(request).execute()
            if (response.isSuccessful) {
                val encryptedText = response.body?.string() ?: return null
                val decryptedJson = aesDecrypt(encryptedText)
                val topicResponse = mapper.readValue<TopicApiResponse>(decryptedJson)
                topicResponse.result?.vodList
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    // Search VOD API
    private suspend fun searchVod(keyword: String, pageNumber: Int = 1): ApiResponse? {
        val url = "$mainUrl/api/search/result"
        val curTime = System.currentTimeMillis().toString()
        val headers = getHeaders(curTime)
        
        val formBody = FormBody.Builder()
            .add("kw", keyword)
            .add("pn", pageNumber.toString())
            .build()
        
        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .apply {
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        
        return try {
            val response = app.baseClient.newCall(request).execute()
            if (response.isSuccessful) {
                val encryptedText = response.body?.string() ?: return null
                val decryptedJson = aesDecrypt(encryptedText)
                mapper.readValue<ApiResponse>(decryptedJson)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    // Get VOD Info API
    private suspend fun getVodInfo(vodId: String, audioType: Int = 0): VodInfoResponse? {
        val url = "$mainUrl/api/vod/info_new"
        val curTime = System.currentTimeMillis().toString()
        val headers = getHeaders(curTime)
        
        // Generate P2P token for request body
        val p2pToken = generateP2pToken(deviceId, vodId, curTime)
        
        val formBody = FormBody.Builder()
            .add("sign", p2pToken)
            .add("vod_id", vodId)
            .add("cur_time", curTime)
            .add("audio_type", audioType.toString())
            .build()
        
        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .apply {
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        
        return try {
            val response = app.baseClient.newCall(request).execute()
            if (response.isSuccessful) {
                val encryptedText = response.body?.string() ?: return null
                val decryptedJson = aesDecrypt(encryptedText)
                mapper.readValue<VodInfoResponse>(decryptedJson)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    override val mainPage = mainPageOf(
        "1" to "Recommended",
        "4008" to "Trending Now",
        "4464" to "Most Popular",
        "4009" to "Hottest International Films",
        "4134" to "This Month: You Can't Miss",
        "4004" to "Top Series This Week"
    )
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val vodItems = if (request.data == "1") {
            searchRecommend(page)?.result
        } else {
            topicVodList(request.data.toInt(), page)
        }
        
        val items = mutableListOf<SearchResponse>()
        
        vodItems?.forEach { vod ->
            when (vod.typePid) {
                1 -> {
                    // Movie
                    items.add(
                        newMovieSearchResponse(
                            name = vod.vodName,
                            url = "${vod.id},1",
                            type = TvType.Movie
                        ) {
                            this.posterUrl = vod.vodPic
                            this.year = vod.vodYear?.toIntOrNull()
                        }
                    )
                }
                2 -> {
                    // TV Series
                    items.add(
                        newTvSeriesSearchResponse(
                            name = vod.vodName,
                            url = "${vod.id},2",
                            type = TvType.TvSeries
                        ) {
                            this.posterUrl = vod.vodPic
                            this.year = vod.vodYear?.toIntOrNull()
                        }
                    )
                }
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = true)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val searchResponse = searchVod(query) ?: return emptyList()
        val vodItems = searchResponse.result ?: return emptyList()
        
        val items = mutableListOf<SearchResponse>()
        
        vodItems.forEach { vod ->
            when (vod.typePid) {
                1 -> {
                    // Movie
                    items.add(
                        newMovieSearchResponse(
                            name = vod.vodName,
                            url = "${vod.id},1",
                            type = TvType.Movie
                        ) {
                            this.posterUrl = vod.vodPic
                            this.year = vod.vodYear?.toIntOrNull()
                        }
                    )
                }
                2 -> {
                    // TV Series
                    items.add(
                        newTvSeriesSearchResponse(
                            name = vod.vodName,
                            url = "${vod.id},2",
                            type = TvType.TvSeries
                        ) {
                            this.posterUrl = vod.vodPic
                            this.year = vod.vodYear?.toIntOrNull()
                        }
                    )
                }
            }
        }
        
        return items
    }
    
    override suspend fun load(url: String): LoadResponse? {
        // URL format: "vodId,typePid" (e.g., "542795,1" for movie or "249461,2" for series)
        val parts = url.split(",")
        if (parts.size != 2) return null
        
        val vodId = parts[0].substringAfterLast("/") // Extract vodId before comma
        val typePid = parts[1].toIntOrNull() ?: return null
        
        // Fetch VOD info
        val vodInfoResponse = getVodInfo(vodId) ?: return null
        val vodInfo = vodInfoResponse.result ?: return null
        
        val name = vodInfo.vodName ?: return null
        val posterUrl = vodInfo.vodPic
        val year = vodInfo.vodYear?.toIntOrNull()
        val plot = vodInfo.vodBlurb
        val score = vodInfo.vodDoubanScore?.let { Score.from10(it) }
        val tags = vodInfo.vodTag?.split("/")?.map { it.trim() }
        val actors = vodInfo.vodActor?.split(",")?.map { actorName ->
            ActorData(Actor(actorName.trim()))
        }
        
        return when (typePid) {
            1 -> {
                // Movie
                val movieData = vodInfo.vodCollection?.firstOrNull()?.let { collection ->
                    "${vodId}|${collection.collection ?: 1}"
                } ?: "${vodId}|1"
                
                newMovieLoadResponse(name, url, TvType.Movie, movieData) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.plot = plot
                    this.score = score
                    this.tags = tags
                    this.actors = actors
                }
            }
            2 -> {
                // TV Series
                val episodes = vodInfo.vodCollection?.map { collection ->
                    newEpisode("${vodId}|${collection.collection ?: 1}") {
                        this.name = "Episode ${collection.title}"
                        this.season = 1
                        this.episode = collection.collection
                    }
                } ?: emptyList()
                
                newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.plot = plot
                    this.score = score
                    this.tags = tags
                    this.actors = actors
                }
            }
            else -> null
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Data format: "vodId|collection" (e.g., "542795|1")
        val parts = data.split("|")
        if (parts.size != 2) return false
        
        val vodId = parts[0].substringAfterLast("/") // Extract vodId before comma
        val collection = parts[1].toIntOrNull() ?: return false
        
        // Fetch VOD info
        val vodInfoResponse = getVodInfo(vodId) ?: return false
        val vodInfo = vodInfoResponse.result ?: return false
        
        // Find the specific episode/collection
        val episode = vodInfo.vodCollection?.find { it.collection == collection } ?: return false
        
        // Get video URL
        val videoUrl = episode.vodUrl ?: episode.downUrl ?: return false
        
        // Sign the video URL with wsSecret and wsTime
        val signedUrl = signVideoUrl(videoUrl)
        
        callback.invoke(
            newExtractorLink(
                name,
                name,
                signedUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = mainUrl
            }
        )
        
        return true
    }
}
