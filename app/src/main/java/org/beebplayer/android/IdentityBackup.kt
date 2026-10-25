// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable, password-encrypted backup; deliberately independent of the Android wrapping key. */
object IdentityBackup {
    const val MAX_BYTES = 262144
    private val magic = "BEEBUHD1".toByteArray(Charsets.US_ASCII)
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        require(password.size >= 12) { "Use a backup password of at least 12 characters" }
        val spec = PBEKeySpec(password, salt, 600000, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }
    fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        require(payload.size in 1..131071)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt)); updateAAD(magic) }
        return magic + salt + cipher.iv + cipher.doFinal(payload)
    }
    fun decrypt(backup: ByteArray, password: CharArray): ByteArray {
        require(backup.size in 53..MAX_BYTES && backup.copyOfRange(0, 8).contentEquals(magic)) { "Not a Beeb UHD backup" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(password, backup.copyOfRange(8, 24)), GCMParameterSpec(128, backup.copyOfRange(24, 36))); updateAAD(magic)
        }
        return try { cipher.doFinal(backup.copyOfRange(36, backup.size)).also { require(it.size < 131072) } }
        catch (e: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("Incorrect backup password or damaged backup", e) }
    }
}
