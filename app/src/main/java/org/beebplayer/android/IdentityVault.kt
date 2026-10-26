// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class IdentityVault(context: Context) {
    private val file = context.noBackupFilesDir.resolve("uhd-identity.bin")
    private val recovery = context.noBackupFilesDir.resolve("uhd-identity-recovery.bin")
    private val alias = "BeebUhdWrappingKey"
    private fun key(create: Boolean = false): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "The saved UHD wrapping key is unavailable. Use Enable BBC UHD to configure playback again." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized private fun read(): JSONObject {
        var failure: Exception? = null
        for (candidate in listOf(file, recovery)) {
            if (!candidate.exists() && !candidate.resolveSibling(candidate.name + ".bak").exists()) continue
            try {
                val bytes = android.util.AtomicFile(candidate).openRead().use { it.readBytes() }
                require(bytes.size in 29..262144)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                val result = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
                // Migrate a legacy install in place, and repair a damaged/missing primary from its recovery copy.
                if (candidate == recovery) runCatching { atomicWrite(file, bytes) }
                if (!recovery.exists()) runCatching { atomicWrite(recovery, bytes) }
                return result
            } catch (e: Exception) { failure = e }
        }
        throw failure ?: IllegalStateException("No saved UHD identity")
    }
    private fun atomicWrite(target: java.io.File, bytes: ByteArray) {
        val atomic = android.util.AtomicFile(target); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    @Synchronized private fun save(j: JSONObject) {
        val store = identity(j); val aliases = store.aliases().toList()
        require(aliases.any { store.isKeyEntry(it) }) { "Setup package contains no identity" }
        aliases.mapNotNull { store.getCertificate(it) as? X509Certificate }.forEach { it.checkValidity() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
        val bytes = cipher.iv + cipher.doFinal(j.toString().toByteArray(Charsets.UTF_8))
        atomicWrite(file, bytes); atomicWrite(recovery, bytes)
    }
    fun configured() = runCatching { read(); true }.getOrDefault(false)
    fun status(): String = if (configured()) "UHD identity connected · saved on this TV" else if (file.exists() || recovery.exists()) "Saved UHD identity cannot be unlocked. Use Enable BBC UHD to configure playback again; the existing files have been preserved." else "No UHD setup saved. Select Enable BBC UHD to get started."
    @Synchronized fun forget() {
        android.util.AtomicFile(file).delete(); android.util.AtomicFile(recovery).delete()
        file.resolveSibling("uhd-identity.tmp").delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
    fun exportBackup(password: CharArray): ByteArray { val plain = read().toString().toByteArray(Charsets.UTF_8); return try { IdentityBackup.encrypt(plain, password) } finally { plain.fill(0) } }
    fun importBackup(bytes: ByteArray, password: CharArray) { val plain = IdentityBackup.decrypt(bytes, password); try { save(JSONObject(String(plain, Charsets.UTF_8))) } finally { plain.fill(0) } }
    private fun identity(j: JSONObject): KeyStore {
        val data = Base64.decode(j.getString("p12"), Base64.DEFAULT)
        require(data.size < 131072 && j.text("userAgent").isNotBlank() && j.text("userAgent").length < 2048 && !j.text("userAgent").contains(Regex("[\\r\\n]"))) { "Invalid setup package" }
        return KeyStore.getInstance("PKCS12").apply { data.inputStream().use { load(it, j.getString("password").toCharArray()) } }
    }
    fun connect(link: String) {
        val uri = URI(link.trim()); val host = uri.host.orEmpty()
        val octets = host.split('.').mapNotNull(String::toIntOrNull)
        require(uri.scheme == "https" && uri.port in 1..65535 && uri.path == "/identity" && uri.rawQuery == null && uri.userInfo == null && octets.size == 4 && octets.all { it in 0..255 } && (octets[0] == 10 || octets[0] == 192 && octets[1] == 168 || octets[0] == 172 && octets[1] in 16..31 || octets[0] == 127)) { "Use the complete setup URL from your Mac on the private local network." }
        val fields = uri.fragment.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val pin = fields["fp"].orEmpty(); val token = fields["token"].orEmpty()
        require(pin.matches(Regex("[a-f0-9]{64}")) && token.matches(Regex("[a-f0-9]{64}"))) { "Setup URL is missing its fingerprint or token" }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { error("Not a client trust manager") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val digest = MessageDigest.getInstance("SHA-256").digest(chain.first().encoded).joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(digest.toByteArray(), pin.toByteArray())) throw java.security.cert.CertificateException("Setup certificate fingerprint mismatch")
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        val client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust).hostnameVerifier { name, _ -> name == host }.followRedirects(false).followSslRedirects(false).callTimeout(25, TimeUnit.SECONDS).build()
        val url = URI("https", null, host, uri.port, "/identity", null, null).toString()
        val j = client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { response ->
            check(response.isSuccessful) { "Setup link expired or transfer failed. Run the helper again." }
            val bytes = response.body?.byteStream()?.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (output.size() <= 131072) { val count = input.read(buffer, 0, minOf(buffer.size, 131073 - output.size())); if (count < 0) break; output.write(buffer, 0, count) }
                output.toByteArray()
            } ?: error("Empty setup response")
            require(bytes.size < 131072) { "Setup response too large" }; JSONObject(String(bytes, Charsets.UTF_8))
        }
        save(j)
    }

    fun selectorClient(): OkHttpClient = candidateClient(read())
    internal fun installCandidate(payload: JSONObject) { save(payload) }
    internal fun candidateClient(j: JSONObject): OkHttpClient {
        val store = identity(j)
        store.aliases().toList().mapNotNull { store.getCertificate(it) as? X509Certificate }.forEach { it.checkValidity() }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, j.getString("password").toCharArray()) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val tls = SSLContext.getInstance("TLS").apply { init(km.keyManagers, arrayOf(tm), SecureRandom()) }
        return OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, tm).followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).addInterceptor { chain ->
            require(chain.request().url.host == "securegate.iplayer.bbc.co.uk") { "Unexpected authentication destination" }
            chain.proceed(chain.request().newBuilder().header("User-Agent", j.getString("userAgent")).build())
        }.build()
    }
}
