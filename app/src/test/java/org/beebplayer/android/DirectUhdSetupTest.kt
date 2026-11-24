package org.beebplayer.android

import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.security.KeyFactory
import java.security.KeyStore
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit

class DirectUhdSetupTest {
    private fun config(cert: HeldCertificate, key: HeldCertificate=cert): ByteArray {
        val pem=cert.certificatePem()+key.privateKeyPkcs8Pem()
        return "user_agent: 'Synthetic test agent'\ncertificate: |\n  ${Base64.getEncoder().encodeToString(pem.toByteArray())}\n".toByteArray()
    }
    @Test fun packageMatchesCertificateAndSurvivesPkcs12RoundTrip() {
        val held=HeldCertificate.Builder().rsa2048().commonName("Synthetic Beeb fixture").build()
        val payload=DirectUhdSetup.prepare(config(held))
        val store=KeyStore.getInstance("PKCS12").apply { load(Base64.getDecoder().decode(payload.getString("p12")).inputStream(),payload.getString("password").toCharArray()) }
        assertArrayEquals(held.certificate.encoded,store.getCertificate(store.aliases().nextElement()).encoded)
        assertEquals("Synthetic test agent",payload.getString("userAgent"))
    }
    @Test fun rejectsWrongKeyExpiredCertificatesDuplicatesAndYamlExecution() {
        val first=HeldCertificate.Builder().rsa2048().commonName("Synthetic A").build()
        val other=HeldCertificate.Builder().rsa2048().commonName("Synthetic B").build()
        assertTrue(runCatching {DirectUhdSetup.prepare(config(first,other))}.isFailure)
        val old=HeldCertificate.Builder().rsa2048().commonName("Expired fixture").validityInterval(0,TimeUnit.DAYS.toMillis(1)).build()
        assertTrue(runCatching {DirectUhdSetup.prepare(config(old))}.isFailure)
        val duplicate=config(first)+"user_agent: duplicate\n".toByteArray()
        for(bytes in listOf(duplicate,"user_agent: !!evil run\ncertificate: *alias".toByteArray(),ByteArray(131073),"user_agent: \"bad\\r\\nheader\"\ncertificate: AAAA".toByteArray()))assertTrue(runCatching {DirectUhdSetup.prepare(bytes)}.isFailure)
    }
    @Test fun cancellationPreventsCommitAndHlgRequiresCorrectColourSignalling() {
        val session=UhdSetupSession();session.cancel();var saved=false
        assertTrue(runCatching {session.commit { saved=true }}.isFailure);assertFalse(saved)
        val init=byteArrayOf(0,0,0,19)+"colrnclx".toByteArray()+byteArrayOf(0,9,0,18,0,9,0)
        assertTrue(UhdSignalling.hlg(init));init[15]=16;assertFalse(UhdSignalling.hlg(init))
    }
    private fun pkcs1Of(held: HeldCertificate): ByteArray =
        Base64.getMimeDecoder().decode(held.privateKeyPkcs1Pem().substringAfter("-----BEGIN RSA PRIVATE KEY-----").substringBefore("-----END RSA PRIVATE KEY-----"))
    @Test fun legacyPemKeyIsWrappedIntoValidPkcs8() {
        val held = HeldCertificate.Builder().rsa2048().commonName("Synthetic legacy fixture").build()
        val pkcs1 = pkcs1Of(held)
        assertTrue("fixture must need long-form DER lengths", pkcs1.size > 128)
        val rebuilt = DirectUhdSetup.wrapRsa(pkcs1)
        val parsed = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(rebuilt))
        assertArrayEquals(held.keyPair.private.encoded, parsed.encoded)
    }
}
