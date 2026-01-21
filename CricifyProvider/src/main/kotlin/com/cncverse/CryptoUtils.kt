package com.cncverse

import com.lagradost.cloudstream3.base64DecodeArray
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private val CRICIFY_PROVIDER_SECRET1 = BuildConfig.CRICIFY_PROVIDER_SECRET1
    private val CRICIFY_PROVIDER_SECRET2 = BuildConfig.CRICIFY_PROVIDER_SECRET2

    // Key and IV pairs loaded from BuildConfig
    private val KEYS = mapOf(
        "key1" to parseKeyInfo(CRICIFY_PROVIDER_SECRET1),
        "key2" to parseKeyInfo(CRICIFY_PROVIDER_SECRET2)
    )
    
    private fun parseKeyInfo(secret: String): KeyInfo {
        val parts = secret.split(":")
        return KeyInfo(
            key = hexStringToByteArray(parts[0]),
            iv = hexStringToByteArray(parts[1])
        )
    }
    
    private data class KeyInfo(val key: ByteArray, val iv: ByteArray)
    
    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
    
    fun decryptData(encryptedBase64: String): String? {
        return try {
            // Clean the base64 string
            val cleanBase64 = encryptedBase64.trim()
                .replace("\n", "")
                .replace("\r", "")
                .replace(" ", "")
                .replace("\t", "")
            
            // Decode base64
            val ciphertext = base64DecodeArray(cleanBase64)
            
            // Try each key
            for ((keyName, keyInfo) in KEYS) {
                val result = tryDecrypt(ciphertext, keyInfo)
                if (result != null) {
                    return result
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun tryDecrypt(ciphertext: ByteArray, keyInfo: KeyInfo): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKeySpec = SecretKeySpec(keyInfo.key, "AES")
            val ivParameterSpec = IvParameterSpec(keyInfo.iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
            
            val decrypted = cipher.doFinal(ciphertext)
            val text = String(decrypted, Charsets.UTF_8)
            
            // Validate result looks like JSON or contains http
            if (text.startsWith("{") || text.startsWith("[") || text.contains("http", ignoreCase = true)) {
                text
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}