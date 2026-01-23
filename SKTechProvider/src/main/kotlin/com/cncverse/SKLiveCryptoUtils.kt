package com.cncverse

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SKLive Decryptor
 * Ported from Python standalone decryptor
 * Uses extracted alphabet from nc.a class
 */
object SKLiveCryptoUtils {
    
    // Extracted from nc.a via Frida
    // Field d (lookup table for custom→standard base64 translation)
    // Index = ASCII code of custom char, Value = standard base64 char
    private val LOOKUP_TABLE_D = (
        "\u0000\u0001\u0002\u0003\u0004\u0005\u0006\u0007\b\t\n\u000B\u000C\r\u000E\u000F" +
        "\u0010\u0011\u0012\u0013\u0014\u0015\u0016\u0017\u0018\u0019\u001A\u001B\u001C\u001D\u001E\u001F" +
        " !\"#\$%&'()*+,-./" +
        "0123456789:;<=>?" +
        "@EGMNKABUVCDYHLI" +
        "FPOZQSRWTXJ[\\]^_" +
        "`egmnkabuvcdyhli" +
        "fpozqsrwtxj{|}~\u007F"
    )
    
    // AES key and IV (captured from Frida)
    private val AES_KEY = hexStringToByteArray(BuildConfig.SKLIVE_KEY) 
    private val AES_IV = hexStringToByteArray(BuildConfig.SKLIVE_IV)
    
    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
    
    /**
     * Translate custom base64 to standard base64 using lookup table d
     */
    private fun customToStandardBase64(customB64: String): String {
        val result = StringBuilder()
        for (char in customB64) {
            val asciiVal = char.code
            if (asciiVal < LOOKUP_TABLE_D.length) {
                val standardChar = LOOKUP_TABLE_D[asciiVal]
                result.append(standardChar)
            } else {
                // Out of range, keep original
                result.append(char)
            }
        }
        return result.toString()
    }
    
    /**
     * Complete SKLive decryption pipeline:
     * 1. Custom base64 → Standard base64 (using lookup table d)
     * 2. Base64 decode → intermediate string
     * 3. REVERSE the string (critical step from h.t.m() decompilation!)
     * 4. Base64 decode → AES ciphertext
     * 5. AES-CBC decrypt → JSON plaintext
     */
    fun decryptSKLive(encryptedData: String): String? {
        return try {
            val standardB64 = customToStandardBase64(encryptedData)
            val decoded = Base64.decode(standardB64, Base64.DEFAULT)
            val decodedStr = String(decoded, Charsets.UTF_8)
            val reversedStr = decodedStr.reversed()
            val ciphertext = Base64.decode(reversedStr, Base64.DEFAULT)
            if (ciphertext.size % 16 == 0) {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                val secretKeySpec = SecretKeySpec(AES_KEY, "AES")
                val ivParameterSpec = IvParameterSpec(AES_IV)
                cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
                
                val decrypted = cipher.doFinal(ciphertext)
                val plaintext = String(decrypted, Charsets.UTF_8)
                plaintext
            } else {
                println("    ERROR: Not block-aligned for AES!")
                decodedStr
            }
            
        } catch (e: Exception) {
            println("[ERROR] Decryption failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}
