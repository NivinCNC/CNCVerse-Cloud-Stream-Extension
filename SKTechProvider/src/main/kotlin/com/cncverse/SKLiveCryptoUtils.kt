package com.cncverse

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


object SKLiveCryptoUtils {
    private val V23_KEY = BuildConfig.SKLIVE_V23_KEY.toByteArray(Charsets.UTF_8)
    private val V23_IV  = BuildConfig.SKLIVE_V23_IV.toByteArray(Charsets.UTF_8)

    private val LEGACY_AES_KEY = hexStringToByteArray(BuildConfig.SKLIVE_KEY)
    private val LEGACY_AES_IV  = hexStringToByteArray(BuildConfig.SKLIVE_IV)

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

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    private fun decryptV23(encryptedData: String): String? {
        return try {
            val padded = if (encryptedData.length % 4 != 0) {
                encryptedData + "=".repeat(4 - encryptedData.length % 4)
            } else {
                encryptedData
            }
            val inner = byteArrayOf(*Base64.decode(padded, Base64.DEFAULT))
                .toMutableList()

            for (i in 0 until inner.size - 1 step 2) {
                val tmp = inner[i]
                inner[i] = inner[i + 1]
                inner[i + 1] = tmp
            }

            inner.reverse()

            val ciphertext = Base64.decode(inner.toByteArray(), Base64.DEFAULT)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(V23_KEY, "AES"),
                IvParameterSpec(V23_IV)
            )
            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
    private fun decryptLegacy(encryptedData: String): String? {
        return try {
            val standardB64 = customToStandardBase64(encryptedData)
            val decoded = Base64.decode(standardB64, Base64.DEFAULT)
            val decodedStr = String(decoded, Charsets.UTF_8)
            val reversedStr = decodedStr.reversed()
            val ciphertext = Base64.decode(reversedStr, Base64.DEFAULT)
            if (ciphertext.size % 16 == 0) {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                val secretKeySpec = SecretKeySpec(LEGACY_AES_KEY, "AES")
                val ivParameterSpec = IvParameterSpec(LEGACY_AES_IV)
                cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)

                val decrypted = cipher.doFinal(ciphertext)
                String(decrypted, Charsets.UTF_8)
            } else {
                println("    ERROR: Not block-aligned for AES!")
                decodedStr
            }
        } catch (e: Exception) {
            println("[ERROR] Legacy decryption failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun customToStandardBase64(customB64: String): String {
        val result = StringBuilder()
        for (char in customB64) {
            val asciiVal = char.code
            if (asciiVal < LOOKUP_TABLE_D.length) {
                result.append(LOOKUP_TABLE_D[asciiVal])
            } else {
                result.append(char)
            }
        }
        return result.toString()
    }

    // ── Public API (unchanged signature) ──────────────────────────────
    fun decryptSKLive(encryptedData: String): String? {
        decryptV23(encryptedData)?.let { return it }
        return decryptLegacy(encryptedData)
    }
}
