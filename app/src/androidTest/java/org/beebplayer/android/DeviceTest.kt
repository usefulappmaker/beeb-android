package org.beebplayer.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeFalse
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import java.security.KeyStore
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import android.util.Base64

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun pinnedProvisioningEncryptsSyntheticIdentityAndRejectsWrongPin() {
        val vault = IdentityVault(context)
        assumeFalse("Never replace an existing identity in this test", vault.configured())
        val held = HeldCertificate.Builder().commonName("Synthetic Beeb identity test").addSubjectAlternativeName("127.0.0.1").build()
        val password = "synthetic-test-only"
        val keys = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("test", held.keyPair.private, password.toCharArray(), arrayOf(held.certificate)) }
        val bytes = ByteArrayOutputStream().apply { keys.store(this, password.toCharArray()) }.toByteArray()
        val body = JSONObject().put("p12", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("password", password).put("userAgent", "Synthetic test agent").toString()
        val tls = HandshakeCertificates.Builder().heldCertificate(held).build()
        val server = MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); start(java.net.InetAddress.getByName("127.0.0.1"), 0); enqueue(MockResponse().setBody(body)) }
        val pin = MessageDigest.getInstance("SHA-256").digest(held.certificate.encoded).joinToString("") { "%02x".format(it) }
        val token = "a".repeat(64)
        try {
            assertTrue(runCatching { vault.connect("https://127.0.0.1:${server.port}/identity#fp=${"0".repeat(64)}&token=$token") }.isFailure)
            vault.connect("https://127.0.0.1:${server.port}/identity#fp=$pin&token=$token")
            assertTrue(IdentityVault(context).configured())
            assertFalse(context.noBackupFilesDir.resolve("uhd-identity.bin").readBytes().toString(Charsets.ISO_8859_1).contains(password))
            assertNotNull(IdentityVault(context).selectorClient())
        } finally { vault.forget(); server.shutdown() }
        assertFalse(IdentityVault(context).configured())
    }
    @Test fun localProgressAndSavedProgrammesSurviveNewStoreInstances() {
        val store = LocalStore(context)
        val p = Programme("m0000001", "Synthetic test", showId = "m0000011", position = 1)
        val next = p.copy(id = "m0000002", position = 2)
        try {
            store.update(p, next, 25000, 60000)
            assertEquals(25000, LocalStore(context).resume(p))
            store.update(p, next, 60000, 60000, true)
            assertEquals(next, LocalStore(context).history().first { it.episode.showId == p.showId }.target)
            store.toggle(p); assertTrue(LocalStore(context).isSaved(p)); store.toggle(p)
            assertTrue(runCatching { IdentityVault(context).connect("https://evil.example/identity#fp=bad&token=bad") }.isFailure)
        } finally { store.remove(p.showId); if (store.isSaved(p)) store.toggle(p) }
    }
    @Test fun liveBbcDiscoveryAndNativePlayback() {
        val api = BbcApi(context)
        val feed = api.home()
        assertTrue(feed.features.isNotEmpty()); assertTrue(feed.rows.isNotEmpty())
        val page = api.page("a", false, 1); assertTrue(page.shows.size <= 30)
        var selected: Programme? = null
        for (show in feed.features.take(3)) {
            selected = runCatching { api.episodes(show.show).firstOrNull { it.version.isNotEmpty() } }.getOrNull()
            if (selected != null) break
        }
        val p = selected ?: error("No playable episode found in current BBC homepage features")
        val stream = api.stream(p, false)
        assertTrue(stream.url.startsWith("https://")); assertTrue(stream.version.isNotEmpty())
        val instrument = InstrumentationRegistry.getInstrumentation()
        val diagnostic = context.filesDir.resolve("playback-diagnostic.json")
        diagnostic.delete()
        val activity = instrument.startActivitySync(Intent(context, PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("episode", p.json().toString()).putExtra("restart", true)) as PlaybackActivity
        val deadline = System.currentTimeMillis() + 90_000
        var decoded = false
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
            val j = runCatching { JSONObject(diagnostic.readText()) }.getOrNull() ?: continue
            if (j.optInt("height") > 0 && j.optLong("positionMs") > 1000) { decoded = true; break }
            if (j.optString("failure").isNotEmpty()) error("Native player failed: ${j.optString("failure")}")
        }
        assertTrue("Real BBC video must decode and advance", decoded)
        instrument.runOnMainSync { activity.debugSeek(65_000) }
        Thread.sleep(1500)
        var before = JSONObject()
        instrument.runOnMainSync { before = activity.debugSnapshot(); activity.debugRewindCheck() }
        var rewound = JSONObject()
        instrument.runOnMainSync { rewound = activity.debugSnapshot() }
        assertTrue("10-second rewind enables captions", rewound.optBoolean("captionsEnabled"))
        assertTrue("Native seek moved backwards", rewound.optLong("positionMs") <= before.optLong("positionMs") - 8000)
        val captionsDeadline = System.currentTimeMillis() + 40_000
        var restored = false; var cues = false
        while (System.currentTimeMillis() < captionsDeadline) {
            Thread.sleep(500)
            var snapshot = JSONObject(); instrument.runOnMainSync { snapshot = activity.debugSnapshot() }
            cues = cues || snapshot.optInt("captionSamples") > 0
            if (!snapshot.optBoolean("captionsEnabled") && snapshot.optLong("positionMs") >= before.optLong("positionMs")) { restored = true; break }
        }
        assertTrue("Native subtitle renderer received BBC caption cues after rewind", cues)
        assertTrue("Captions returned to Off after replay interval", restored)
        instrument.runOnMainSync { activity.finish() }
    }
}
