package org.beebplayer.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ParsingTest {
    @Test fun episodeStillsAreDistinctAndShowLogosStayAtShowLevel() {
        val first = JSONObject("""{"id":"m0000001","title":"Show","subtitle":"Series 1: 1","images":{"standard":"https://example.org/{recipe}/one.jpg","promotional_with_logo":"https://example.org/logo.jpg"},"versions":[{"id":"m0000011","kind":"original","uhd":true,"hdr":false}]}""")
        val second = JSONObject(first.toString()).put("id", "m0000002").put("images", JSONObject().put("standard", "https://example.org/{recipe}/two.jpg").put("promotional_with_logo", "https://example.org/logo.jpg"))
        assertTrue(Programme.episode(first).image.endsWith("640x360/one.jpg"))
        assertTrue(Programme.episode(second).image.endsWith("640x360/two.jpg"))
        assertTrue(Programme.show(first).image.endsWith("logo.jpg"))
        assertFalse(Programme.episode(first).hdr)
        assertEquals(Programme.episode(first), Programme.restore(Programme.episode(first).json()))
    }
    @Test fun bannerAvoidsLogosAndHandlesQuotedBraces() {
        val episode = JSONObject("""{"id":"m0000001","tleo":{"id":"m0000011"},"title":{"default":"Example } title"},"synopsis":{"small":"A \\\"quoted\\\" story"},"image":{"promotional":"https://example.org/{recipe}/plain.jpg","promotionalWithLogo":"https://example.org/logo.jpg"}}""")
        val state = JSONObject().put("bundles", JSONArray().put(JSONObject().put("id", "editorial").put("title", JSONObject().put("default", "New")).put("entities", JSONArray().put(JSONObject().put("episode", episode)))))
        val home = Parsing.home("<script>window.__IPLAYER_REDUX_STATE__ = $state;</script>")
        assertTrue(home.features.single().image.endsWith("1920x1080/plain.jpg"))
        assertTrue(home.rows.single().second.single().image.endsWith("logo.jpg"))
        episode.put("image", JSONObject().put("promotionalWithLogo", "https://example.org/logo.jpg"))
        assertEquals("", Parsing.home("window.__IPLAYER_REDUX_STATE__ = $state").features.single().image)
    }
    @Test fun pidOnlyAcceptsBbcEpisodeLinksAndValidIds() {
        assertEquals("m002cgz3", Parsing.pid("https://www.bbc.co.uk/iplayer/episode/m002cgz3/example"))
        for (invalid in listOf("https://bbc.co.uk.attacker.invalid/iplayer/episode/m002cgz3", "../bad", "m000000x/evil")) assertTrue(runCatching { Parsing.pid(invalid) }.isFailure)
    }
    @Test fun hlsQueryStaysOnItsOwnHostAndQualitySortsCorrectly() {
        val text = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=7000000,RESOLUTION=1920x1080\nhigh.m3u8?extra=1\n"
        val variants = Parsing.variants(text, "https://cdn.example/path/master.m3u8?token=demo")
        assertEquals(1080, variants.first().height)
        assertEquals("https://cdn.example/path/high.m3u8?token=demo&extra=1", variants.first().url)
        assertEquals("https://other.example/media", Parsing.resolve("https://other.example/media", "https://cdn.example/master?token=demo"))
    }
    @Test fun skipMarkersAreBoundedAndMalformedMarkersAreIgnored() {
        val version = JSONObject("""{"interactions":[{"type":"skip","subtype":"intro","interaction_points":{"show_from":10,"skip_to":30}},{"type":"skip","subtype":"recap","interaction_points":{"show_from":-5,"skip_to":2}},{"type":"skip","subtype":"intro","interaction_points":{"show_from":9,"skip_to":9}}]}""")
        val marker = Skip.parse(version).single()
        assertFalse(marker.contains(9999)); assertTrue(marker.contains(10000)); assertFalse(marker.contains(30000))
    }
    @Test fun orderingAndCreditsCompletionUseMetadata() {
        val p = Programme("m0000001", "Show", series = "Series 2", position = 1)
        val q = Programme("m0000002", "Show", series = "Series 1", position = 2)
        val r = Programme("m0000003", "Show", series = "Series 1", position = 1)
        assertEquals(listOf(r, q, p), Parsing.ordered(listOf(p, q, r)))
        assertTrue(Parsing.finished(3_500_000, 3_600_000, false)); assertFalse(Parsing.finished(3_000_000, 3_600_000, false))
        assertTrue(Parsing.finished(1000, 3600000, true)); assertFalse(Parsing.finished(0, 0, false))
    }
}
