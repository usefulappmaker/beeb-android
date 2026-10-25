// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class UhdSetupSession {
    @Volatile private var cancelled = false
    private var committed = false
    @Synchronized fun cancel(): Boolean { if (committed) return false; cancelled = true; return true }
    fun check() { if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException() }
    @Synchronized fun commit(save: () -> Unit) { check(); save(); committed = true }
}

/** Reads only literal configuration scalars. No YAML execution, scripts, or plaintext identity files. */
object DirectUhdSetup {
    const val URL = "https://git.gay/Vinefeeder/twinvine-services/raw/branch/main/iP/config.yaml"
    const val MAX_BYTES = 131072
    class SetupError(message: String) : Exception(message)
    fun download(session: UhdSetupSession): ByteArray {
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()
        session.check()
        return client.newCall(Request.Builder().url(URL).header("Cache-Control", "no-cache").build()).execute().use { r ->
            if (r.code != 200 || r.request.url.toString() != URL || r.body == null || r.body!!.contentLength() > MAX_BYTES) throw SetupError("Could not download UHD setup. Check your connection and try again.")
            r.body!!.byteStream().use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { session.check(); val n = input.read(buffer); if (n < 0) break; if (out.size()+n > MAX_BYTES) throw SetupError("The UHD setup configuration is too large."); out.write(buffer,0,n) }
                out.toByteArray()
            }
        }
    }
    internal fun fields(data: ByteArray): Pair<ByteArray,String> {
        require(data.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString()
        val lines = text.lines()
        fun field(name: String): String {
            val positions = lines.indices.filter { lines[it].startsWith("$name:") }; require(positions.size == 1)
            val index = positions.single(); val scalar = lines[index].substringAfter(':').trim()
            if (scalar in listOf("|","|-","|+",">",">-")) {
                val values = lines.drop(index+1).takeWhile { it.isBlank() || it.startsWith(' ') || it.startsWith('\t') }.map(String::trim).filter { it.isNotBlank() && !it.startsWith('#') }
                return values.joinToString(if (name=="certificate") "" else "\n")
            }
            if (scalar.startsWith('"')) return JSONObject("{\"value\":$scalar}").getString("value")
            if (scalar.startsWith('\'')) { require(scalar.length>=2 && scalar.endsWith('\'')); return scalar.substring(1,scalar.length-1).replace("''", "'") }
            require(scalar.isNotBlank() && scalar.first() !in "!*&[{" )
            return scalar.substringBefore(" #").trim()
        }
        val agent=field("user_agent"); require(agent.isNotBlank() && agent.toByteArray().size<2048 && agent.none { it.code<32 || it.code==127 })
        val pem=Base64.getDecoder().decode(field("certificate").filterNot(Char::isWhitespace)); require(pem.size in 1 until MAX_BYTES)
        return pem to agent
    }
    internal fun block(pem: ByteArray, label: String): ByteArray {
        val text=pem.toString(Charsets.US_ASCII); val begin="-----BEGIN $label-----"; val end="-----END $label-----"
        require(text.split(begin).size==2 && text.split(end).size==2)
        val value=text.substringAfter(begin).substringBefore(end).filterNot(Char::isWhitespace)
        return Base64.getDecoder().decode(value).also { require(it.isNotEmpty()) }
    }
    private fun der(tag: Int, value: ByteArray): ByteArray {
        val size=value.size
        val length=if(size<128) byteArrayOf(size.toByte()) else {
            var n=size;val bytes=mutableListOf<Byte>();while(n>0){bytes.add(0,(n and 255).toByte());n=n ushr 8};byteArrayOf((128+bytes.size).toByte())+bytes.toByteArray()
        }
        return byteArrayOf(tag.toByte())+length+value
    }
    internal fun wrapRsa(pkcs1: ByteArray): ByteArray = der(0x30,byteArrayOf(2,1,0)+byteArrayOf(0x30,0x0d,0x06,0x09,0x2a,0x86.toByte(),0x48,0x86.toByte(),0xf7.toByte(),0x0d,1,1,1,5,0)+der(4,pkcs1))
    fun prepare(data: ByteArray): JSONObject {
        var pem: ByteArray?=null; var encodedKey: ByteArray?=null
        try {
            val values=fields(data); pem=values.first
            val certificate=CertificateFactory.getInstance("X.509").generateCertificate(block(pem,"CERTIFICATE").inputStream()) as X509Certificate
            certificate.checkValidity()
            encodedKey=runCatching { block(pem,"PRIVATE KEY") }.getOrElse { wrapRsa(block(pem,"RSA PRIVATE KEY")) }
            val key=KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encodedKey))
            val challenge=ByteArray(32).also(SecureRandom()::nextBytes)
            val signature=Signature.getInstance("SHA256withRSA").apply { initSign(key);update(challenge) }.sign()
            require(Signature.getInstance("SHA256withRSA").apply { initVerify(certificate.publicKey);update(challenge) }.verify(signature))
            val password=Base64.getEncoder().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))
            val store=KeyStore.getInstance("PKCS12").apply { load(null,null);setKeyEntry("beeb",key,password.toCharArray(),arrayOf(certificate)) }
            val bytes=ByteArrayOutputStream().apply { store.store(this,password.toCharArray()) }.toByteArray()
            return try { JSONObject().put("p12",Base64.getEncoder().encodeToString(bytes)).put("password",password).put("userAgent",values.second) } finally { bytes.fill(0) }
        } catch (_: Exception) { throw SetupError("The UHD configuration could not be validated. Your existing setup has been kept.") }
        finally { pem?.fill(0);encodedKey?.fill(0) }
    }
    fun enable(api: BbcApi, session: UhdSetupSession, progress: (String) -> Unit): String {
        progress("Downloading and validating UHD setup…")
        val bytes=download(session)
        val payload=try { prepare(bytes) } finally { bytes.fill(0) }
        try {
            session.check();progress("Checking BBC 4K HLG access…")
            return installVerified(api.vault,payload,session) { api.verifyUhdSetup(it,session::check) }
        } finally { payload.remove("p12");payload.remove("password") }
    }
    internal fun installVerified(vault: IdentityVault, payload: JSONObject, session: UhdSetupSession, verify: (OkHttpClient) -> String): String {
        session.check()
        val client=vault.candidateClient(payload)
        val title=try { verify(client) } finally { client.connectionPool.evictAll() }
        session.commit { vault.installCandidate(payload) }
        return title
    }
}
