package org.beebplayer.android

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class LiveCaptionsTest {
    private val base="https://example.test/live/video.m3u8?token=fixture"
    private val listing="""#EXTM3U
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:10
#EXT-X-DISCONTINUITY-SEQUENCE:2
#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00Z
#EXTINF:3.84,
100.ts
#EXTINF:3.84,
101.ts
#EXT-X-DISCONTINUITY
#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:07.680Z
#EXTINF:3.84,
102.ts
"""
    private fun fragment(xml: String): ByteArray { val b=xml.toByteArray();return ByteBuffer.allocate(8+b.size).putInt(8+b.size).put("mdat".toByteArray()).put(b).array() }
    @Test fun timelineRetainsClockSequenceDiscontinuityAndAuthorisation() {
        val t=LiveCaptions.timeline(listing,base)
        assertEquals(listOf(10L,11L,12L),t.segments.map {it.sequence})
        assertEquals(listOf(2,2,3),t.segments.map {it.discontinuity})
        assertEquals(listOf(100L,101L,102L),t.segments.map {it.number})
        assertTrue(t.segments.all {it.video.endsWith("?token=fixture")})
        assertEquals(3.84,t.segments[1].date-t.segments[0].date,.0001)
        assertTrue(t.playlist().contains("#EXT-X-DISCONTINUITY\n#EXTINF:3.840000,\n3/12.vtt"))
        assertFalse(t.playlist().contains("ENDLIST"))
        assertTrue(runCatching {LiveCaptions.timeline(listing.replace("3.84","6.00"),base)}.isFailure)
    }
    @Test fun fragmentedTtmlBecomesPlainTimedNativeWebVtt() {
        val start=LiveCaptions.timeline(listing,base).segments.first()
        val xml="""<tt xmlns="http://www.w3.org/ns/ttml"><body begin="${start.date}s"><div><p begin="1s" end="5s"><span>First &amp; second</span><br/>Next line</p></div></body></tt>"""
        val cues=LiveCaptions.cues(fragment(xml))
        assertEquals("First & second\nNext line",cues.single().text)
        val vtt=LiveCaptions.webVtt(cues,start,start.date,12345L)
        assertTrue(vtt.contains("MPEGTS:12345"));assertTrue(vtt.contains("00:00:01.000 --> 00:00:03.840"))
        assertTrue(vtt.contains("First &amp; second\nNext line"))
        assertTrue(runCatching {LiveCaptions.cues(fragment("<!DOCTYPE tt SYSTEM \"https://example.test/entity\"><tt/>"))}.isFailure)
        assertTrue(runCatching {LiveCaptions.cues(byteArrayOf(0,0,0,30,109,100,97,116))}.isFailure)
    }
    @Test fun ptsExtractionAndWrapUseVideoClock() {
        val pts=(1L shl 33)-45000
        val b=ByteArray(188);b[0]=0x47;b[1]=0x40;b[3]=0x10;b[6]=1;b[7]=0xe0.toByte();b[11]=0x80.toByte()
        val at=13;b[at]=(((pts shr 30 and 7) shl 1) or 0x21).toByte();b[at+1]=(pts shr 22).toByte();b[at+2]=(((pts shr 15 and 127) shl 1) or 1).toByte();b[at+3]=(pts shr 7).toByte();b[at+4]=(((pts and 127) shl 1) or 1).toByte()
        assertEquals(pts,LiveCaptions.firstVideoPts(b))
        val s=LiveCaptions.Segment(1,0,10.0,3.84,base,100)
        assertTrue(LiveCaptions.webVtt(emptyList(),s,9.0,pts).contains("MPEGTS:45000"))
    }
    @Test fun renditionPreservesAudioAndVideoAndMissingCaptionsRecover() {
        val master="""#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",URI="audio.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,AUDIO="audio"
video.m3u8
"""
        var missing=true
        val resources=LiveCaptionResources.create(master,base,listOf("https://example.test/\$Number\$.m4s")) {url,_ ->
            when {
                url.contains("video.m3u8") -> listing.toByteArray()
                url.contains(".ts") -> ByteArray(188) // no PTS: empty captions, live video survives
                missing -> error("caption unavailable")
                else -> fragment("<tt/>")
            }
        }!!
        val output=resources.resource(resources.masterUrl).toString(Charsets.UTF_8)
        assertTrue(output.contains("TYPE=AUDIO"));assertTrue(output.contains("https://example.test/live/audio.m3u8?token=fixture"))
        assertTrue(output.contains("SUBTITLES=\"bbc-subtitles\""))
        val subtitle=Regex("URI=\"(https://beeb-captions.invalid/[^\"]+)\"").find(output)!!.groupValues[1]
        assertTrue(resources.resource(subtitle).toString(Charsets.UTF_8).contains("2/10.vtt"))
        val vtt=resources.resource(subtitle.substringBeforeLast('/')+"/2/10.vtt").toString(Charsets.UTF_8)
        assertTrue(vtt.startsWith("WEBVTT"));missing=false
        assertFalse(resources.owns("https://example.test/live/video.m3u8"))
    }
}
