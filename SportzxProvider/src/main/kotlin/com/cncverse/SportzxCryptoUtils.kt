package com.cncverse

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sportzx AES-CBC decryption utility.
 *
 * Mirrors the Python implementation in sportzx.py exactly:
 *   - APP_PASSWORD = "oAR80SGuX3EEjUGFRwLFKBTiris="
 *   - Key & IV are derived via a custom FNV-based algorithm (_generate_aes_key_iv)
 *   - Ciphertext is standard Base64-decoded, then decrypted with AES/CBC/PKCS7
 */
object SportzxCryptoUtils {
    fun decrypt(b64Data: String): String? {
        val trimmed = b64Data.trim()
        if (trimmed.isEmpty()) return null

        return try {
            val ct = Base64.decode(trimmed, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(AES_KEY, "AES"),
                IvParameterSpec(AES_IV)
            )
            val pt = cipher.doFinal(ct)
            String(pt, Charsets.UTF_8)
        } catch (e: Exception) {
            println("SportzxCrypto: Decryption failed — ${e.message}")
            null
        }
    }
}
