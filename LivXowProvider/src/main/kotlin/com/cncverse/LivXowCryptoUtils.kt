package com.cncverse

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LivXow Crypto Utils
 *
 * Reverse-engineered from the LivXow APK (com.livxow.tv).
 *
 * Decryption pipeline (rc.a.b → c6/f0.java):
 *  1. Character substitution cipher  — maps shuffled alphabet → standard alphabet
 *  2. Base64 decode (with padding)
 *  3. AES/CBC/PKCS5Padding decrypt   — hardcoded key + IV embedded in the APK
 *
 * Pass-through: if the raw response already starts with `{` or `[` it is
 * already plain JSON and is returned as-is.
 */
object LivXowCryptoUtils {

    /**
     * Step 1 — applies the substitution cipher to [str].
     * Characters outside 0..127 are passed through unchanged.
     */
    private fun decryptSubstitution(str: String): String {
        val chars = CharArray(str.length) { i ->
            val c = str[i].code
            if (c in 0..127) decodeTable[c] else str[i]
        }
        return String(chars)
    }


    fun decryptHttpResponse(str: String): String {
        // Already plain JSON — pass through
        if (str.startsWith("{") || str.startsWith("[")) return str

        return try {
            // Step 1: substitution
            val substituted = decryptSubstitution(str)

            // Step 2: Base64 pad + decode
            val padded = if (substituted.length % 4 != 0) {
                substituted + "=".repeat(4 - (substituted.length % 4))
            } else {
                substituted
            }
            val decoded = Base64.decode(padded, Base64.DEFAULT)

            // Step 3: AES-CBC decrypt
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(AES_KEY, "AES"),
                IvParameterSpec(AES_IV)
            )
            String(cipher.doFinal(decoded), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Convenience wrapper — returns null instead of empty string on failure,
     * which is more idiomatic for callers that need null-checking.
     */
    fun decrypt(str: String): String? = decryptHttpResponse(str).ifBlank { null }

    /**
     * Converts a hex string (e.g. DRM kid/key) to unpadded URL-safe Base64.
     * Used when building ClearKey JSON payloads.
     */
    fun hexToBase64Unpadded(hex: String): String {
        val clean = hex.replace("-", "").replace(" ", "")
        val bytes = ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

}
