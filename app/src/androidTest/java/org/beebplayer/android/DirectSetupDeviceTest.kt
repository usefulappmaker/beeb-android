package org.beebplayer.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class DirectSetupDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun payload(name: String): org.json.JSONObject {
        val held=HeldCertificate.Builder().rsa2048().commonName(name).build()
        val pem=held.certificatePem()+held.privateKeyPkcs8Pem()
        return DirectUhdSetup.prepare(("user_agent: Synthetic fixture\ncertificate: "+Base64.getEncoder().encodeToString(pem.toByteArray())).toByteArray())
    }
    @Test fun failedOrCancelledProbeKeepsExistingIdentityAndSuccessCommits() {
        check(context.packageName.endsWith(".debug"));val vault=IdentityVault(context)
        assumeFalse("Do not replace an existing debug identity",vault.configured())
        try {
            vault.installCandidate(payload("Original synthetic fixture"))
            val file=context.noBackupFilesDir.resolve("uhd-identity.bin");val original=file.readBytes()
            val candidate=payload("Candidate synthetic fixture")
            assertTrue(runCatching { DirectUhdSetup.installVerified(vault,candidate,UhdSetupSession()) { error("Synthetic BBC failure") } }.isFailure)
            assertArrayEquals(original,file.readBytes());assertTrue(vault.configured())
            val cancel=UhdSetupSession()
            assertTrue(runCatching { DirectUhdSetup.installVerified(vault,candidate,cancel) { cancel.cancel();"Cancelled fixture" } }.isFailure)
            assertArrayEquals(original,file.readBytes())
            DirectUhdSetup.installVerified(vault,candidate,UhdSetupSession()) { assertArrayEquals(original,file.readBytes());"Verified synthetic fixture" }
            assertFalse(original.contentEquals(file.readBytes()));assertTrue(vault.configured())
        } finally { vault.forget() }
    }
    @Test fun liveConfigurationValidatesAndBbcUhdProbeDoesNotSaveIt() {
        check(context.packageName.endsWith(".debug"));val api=BbcApi(context)
        val file=context.noBackupFilesDir.resolve("uhd-identity.bin");val before=if(file.exists())file.readBytes() else null
        val session=UhdSetupSession();val bytes=DirectUhdSetup.download(session)
        val candidate=try {DirectUhdSetup.prepare(bytes)}finally{bytes.fill(0)}
        try {
            val client=api.vault.candidateClient(candidate)
            try { assertTrue(api.verifyUhdSetup(client,session::check).isNotBlank()) } finally { client.connectionPool.evictAll() }
        } finally {
            candidate.remove("p12");candidate.remove("password")
            if(before==null)assertFalse(file.exists()) else assertArrayEquals(before,file.readBytes())
        }
    }
}
