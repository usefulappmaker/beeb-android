package org.beebplayer.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PlaybackVersionsTest {
    @Test fun replacementUhdWinsAndUnavailableAccessibilityVersionsAreExcluded() {
        val data="""{"episodes":[{"id":"p09pm8b0","title":"Vigil","versions":[
        {"id":"p09tjhvs","kind":"audio-described"},{"id":"m002hzwl","kind":"signed"},
        {"id":"m0032x5w","kind":"original","availability":{"end":"2000-01-01T00:00:00Z"}},
        {"id":"m0000001","kind":"original","availability":{"start":"2099-01-01T00:00:00Z"}},
        {"id":"m000z8m3","kind":"technical-replacement","uhd":true,"availability":{"start":"2022-01-01T00:00:00Z","end":"2099-01-01T00:00:00Z"}},
        {"id":"m0000002","kind":"original"}]}]}"""
        val episode=PlaybackVersions.episode(data,"p09pm8b0")
        assertEquals(listOf("m000z8m3","m0000002"),PlaybackVersions.available(episode).map {it.text("id")})
        assertEquals("m000z8m3",Programme.episode(episode).version)
        assertTrue(runCatching {PlaybackVersions.episode(data,"m0032x5w")}.isFailure)
        assertTrue(runCatching {PlaybackVersions.episode("<html>Service Temporarily Unavailable</html>","p09pm8b0")}.isFailure)
    }
    @Test fun expiresAtBoundaryAndDeduplicatesIds() {
        val e=JSONObject("""{"versions":[{"id":"m0000001","availability":{"end":"2026-01-01T00:00:00.000Z"}},{"id":"m0000002"},{"id":"m0000002"},{"id":"../bad"}]}""")
        assertEquals(listOf("m0000002"),PlaybackVersions.available(e,Instant.parse("2026-01-01T00:00:00Z")).map {it.text("id")})
    }
}
