package org.beebplayer.android

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest

/** Run seed on 0.1.2, install 0.1.3 with adb install -r, then run verify. Debug app only. */
@RunWith(AndroidJUnit4::class)
class UpdatePersistenceTest {
    @Test fun identitySurvivesSignedApkUpdate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug"))
        val vault = IdentityVault(context)
        val marker = context.filesDir.resolve("synthetic-update-fixture")
        when (InstrumentationRegistry.getArguments().getString("phase")) {
            "seed" -> {
                assumeFalse("Do not replace a user's preview identity", vault.configured() && !marker.exists())
                val held = HeldCertificate.Builder().commonName("Synthetic update fixture").addSubjectAlternativeName("127.0.0.1").build()
                val password = "synthetic-test-only"
                val keys = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("test", held.keyPair.private, password.toCharArray(), arrayOf(held.certificate)) }
                val p12 = ByteArrayOutputStream().apply { keys.store(this, password.toCharArray()) }.toByteArray()
                val body = JSONObject().put("p12", Base64.encodeToString(p12, Base64.NO_WRAP)).put("password", password).put("userAgent", "Synthetic update test").toString()
                val tls = HandshakeCertificates.Builder().heldCertificate(held).build()
                val server = MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); start(java.net.InetAddress.getByName("127.0.0.1"), 0); enqueue(MockResponse().setBody(body)) }
                val pin = MessageDigest.getInstance("SHA-256").digest(held.certificate.encoded).joinToString("") { "%02x".format(it) }
                try { vault.connect("https://127.0.0.1:${server.port}/identity#fp=$pin&token=${"a".repeat(64)}"); assertTrue(vault.configured()); marker.writeText("synthetic") } finally { server.shutdown() }
            }
            "verify" -> {
                assertTrue("Seed fixture must exist across update", marker.exists())
                try {
                    assertTrue("Legacy encrypted identity and Android Keystore key must survive the APK update", vault.configured())
                    assertNotNull(vault.selectorClient())
                    assertTrue(context.noBackupFilesDir.resolve("uhd-identity-recovery.bin").exists())
                    val backupPassword = "synthetic portable backup password".toCharArray()
                    val backup = vault.exportBackup(backupPassword)
                    assertTrue(runCatching { vault.importBackup(backup, "incorrect backup password".toCharArray()) }.isFailure)
                    assertTrue("Failed import must preserve the existing identity", vault.configured())
                    context.noBackupFilesDir.resolve("uhd-identity.bin").writeBytes(byteArrayOf(0))
                    assertTrue("Recovery copy must repair damaged primary storage", vault.configured())
                    vault.forget(); assertFalse(vault.configured())
                    vault.importBackup(backup, backupPassword)
                    assertTrue("Portable backup must restore under a newly generated wrapping key", vault.configured())
                } finally { vault.forget(); marker.delete() }
            }
            else -> assumeFalse("Two-stage update test is run explicitly", true)
        }
    }
}
