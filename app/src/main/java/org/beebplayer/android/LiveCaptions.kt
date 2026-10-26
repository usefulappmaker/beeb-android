// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** Same BBC numbered-fragment / programme-date-time bridge as the tvOS edition.
 * Produces a native HLS WebVTT rendition; audio/video are never proxied or transcoded. */
object LiveCaptions {
    data class Segment(val sequence: Long, val discontinuity: Int, val date: Double, val duration: Double, val video: String, val number: Long) {
        val path get() = "$discontinuity/$sequence.vtt"
    }
    data class Timeline(val segments: List<Segment>, val target: Int) {
        fun playlist(): String = buildString {
            val first = segments.first()
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:$target\n#EXT-X-MEDIA-SEQUENCE:${first.sequence}\n#EXT-X-DISCONTINUITY-SEQUENCE:${first.discontinuity}\n")
            var previous = first.discontinuity
            for (s in segments) {
                if (s.discontinuity != previous) { append("#EXT-X-DISCONTINUITY\n"); previous = s.discontinuity }
                append(String.format(Locale.ROOT, "#EXTINF:%.6f,\n%s\n", s.duration, s.path))
            }
        }
    }
    fun timeline(text: String, base: String): Timeline {
        require(text.startsWith("#EXTM3U") && !text.contains("#EXT-X-MAP:") && !text.contains("#EXT-X-ENDLIST"))
        var sequence = 0L; var discontinuity = 0; var target = 4
        var date: Double? = null; var duration: Double? = null
        val result = mutableListOf<Segment>()
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = l.substringAfter(':').toLong()
                l.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") -> discontinuity = l.substringAfter(':').toInt()
                l == "#EXT-X-DISCONTINUITY" -> { discontinuity++; date = null }
                l.startsWith("#EXT-X-TARGETDURATION:") -> target = l.substringAfter(':').toInt()
                l.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> { val t = OffsetDateTime.parse(l.substringAfter(':')).toInstant(); date = t.epochSecond + t.nano / 1e9 }
                l.startsWith("#EXTINF:") -> duration = l.substringAfter(':').substringBefore(',').toDouble()
                l.isNotEmpty() && !l.startsWith('#') -> {
                    val start = requireNotNull(date); val length = requireNotNull(duration)
                    require(kotlin.math.abs(length - 3.84) < .01 && sequence >= 0)
                    val url = Parsing.resolve(l, base)
                    val number = java.net.URI(url).path.substringAfterLast('/').substringBefore('.').toLong()
                    result.add(Segment(sequence++, discontinuity, start, length, url, number)); date = start + length; duration = null
                    require(result.size <= 4096)
                }
            }
        }
        require(result.isNotEmpty() && target in 1..60)
        return Timeline(result, target)
    }
    data class Cue(val start: Double, val end: Double, val text: String)
    fun cues(fragment: ByteArray): List<Cue> {
        require(fragment.size <= 8_000_000)
        var offset = 0
        while (offset + 8 <= fragment.size) {
            val size = ByteBuffer.wrap(fragment, offset, 4).int.toLong() and 0xffffffffL
            val type = String(fragment, offset + 4, 4, Charsets.US_ASCII)
            val header = if (size == 1L) 16 else 8
            require(offset + header <= fragment.size)
            val length = when (size) { 0L -> (fragment.size - offset).toLong(); 1L -> ByteBuffer.wrap(fragment, offset + 8, 8).long; else -> size }
            require(length >= header && length <= fragment.size - offset)
            if (type == "mdat") return ttml(fragment.copyOfRange(offset + header, offset + length.toInt()))
            offset += length.toInt()
        }
        error("No timed text in live caption fragment")
    }
    private fun ttml(data: ByteArray): List<Cue> {
        require(!data.toString(Charsets.UTF_8).contains("<!DOCTYPE", true))
        val result = mutableListOf<Cue>()
        val handler = object : DefaultHandler() {
            val stack = mutableListOf(0.0 to Double.POSITIVE_INFINITY)
            var paragraph: Pair<Double, Double>? = null
            var text = StringBuilder(); var frameRate = 30.0; var tickRate = 1.0
            fun time(raw: String?): Double? {
                if (raw.isNullOrBlank()) return null
                val parts = raw.split(':').mapNotNull(String::toDoubleOrNull)
                if (parts.size == 3) return parts[0]*3600 + parts[1]*60 + parts[2]
                if (parts.size == 4) return parts[0]*3600 + parts[1]*60 + parts[2] + parts[3]/frameRate
                for ((suffix, scale) in listOf("ms" to .001, "h" to 3600.0, "m" to 60.0, "s" to 1.0, "f" to 1/frameRate, "t" to 1/tickRate))
                    if (raw.endsWith(suffix)) return raw.removeSuffix(suffix).toDoubleOrNull()?.times(scale)
                return null
            }
            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) {
                val name = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')
                if (name == "tt") {
                    frameRate = (a.getValue("ttp:frameRate")?.toDoubleOrNull() ?: 30.0).coerceAtLeast(1.0)
                    a.getValue("ttp:frameRateMultiplier")?.split(' ')?.mapNotNull(String::toDoubleOrNull)?.takeIf { it.size == 2 && it[1] > 0 }?.let { frameRate *= it[0]/it[1] }
                    tickRate = (a.getValue("ttp:tickRate")?.toDoubleOrNull() ?: 1.0).coerceAtLeast(1.0)
                }
                val parent = stack.last(); val start = parent.first + (time(a.getValue("begin")) ?: 0.0)
                val end = minOf(parent.second, time(a.getValue("end"))?.plus(parent.first) ?: time(a.getValue("dur"))?.plus(start) ?: parent.second)
                stack.add(start to end)
                if (name == "p") { paragraph = start to end; text = StringBuilder() }
                else if (name == "br" && paragraph != null) text.append('\n')
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (paragraph != null) text.append(ch, start, length) }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val name = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')
                if (name == "p") {
                    val value = text.lines().joinToString("\n") { it.trim().replace(Regex("\\s+"), " ") }.trim()
                    paragraph?.let { (start, end) -> if (start.isFinite() && end.isFinite() && end > start && start >= 0 && value.isNotEmpty()) result.add(Cue(start, end, value)) }; paragraph = null
                }
                stack.removeAt(stack.lastIndex)
            }
        }
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true; runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }; runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) } }
        factory.newSAXParser().parse(ByteArrayInputStream(data), handler)
        return result.sortedBy { it.start }
    }
    fun firstVideoPts(bytes: ByteArray): Long? {
        fun n(i: Int) = bytes[i].toInt() and 255
        for (packet in 0..bytes.size-188 step 188) {
            if (n(packet) != 0x47 || n(packet+1) and 0x40 == 0) continue
            val control = n(packet+3) shr 4 and 3
            if (control != 1 && control != 3) continue
            val p = packet + 4 + if (control == 3) 1+n(packet+4) else 0
            if (p+14 > packet+188 || n(p) != 0 || n(p+1) != 0 || n(p+2) != 1 || n(p+3) !in 0xe0..0xef || n(p+7) and 0x80 == 0) continue
            val t = p+9
            return ((n(t).toLong() shr 1 and 7) shl 30) or (n(t+1).toLong() shl 22) or ((n(t+2).toLong() shr 1) shl 15) or (n(t+3).toLong() shl 7) or (n(t+4).toLong() shr 1)
        }
        return null
    }
    fun webVtt(cues: List<Cue>, s: Segment, anchorDate: Double, anchorPts: Long): String {
        val wrap = 1L shl 33; val raw = anchorPts + Math.round((s.date-anchorDate)*90000); val pts = (raw % wrap + wrap) % wrap
        fun stamp(seconds: Double): String { val ms = Math.round(maxOf(0.0, seconds)*1000); return String.format(Locale.ROOT,"%02d:%02d:%02d.%03d",ms/3600000,ms/60000%60,ms/1000%60,ms%1000) }
        return buildString {
            append("WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:$pts\n\n")
            cues.filter { it.end > s.date && it.start < s.date+s.duration }.forEach {
                append("${stamp(maxOf(0.0,it.start-s.date))} --> ${stamp(minOf(s.duration,it.end-s.date))}\n")
                append(it.text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")+"\n\n")
            }
        }
    }
    fun master(text: String, base: String, subtitleUrl: String): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"bbc-subtitles\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=NO,AUTOSELECT=YES,FORCED=NO,URI=\"$subtitleUrl\"\n")
        val uri = Regex("URI=\"([^\"]+)\"")
        for (raw in text.lineSequence()) {
            var line = raw.trim()
            if (line.isEmpty() || line == "#EXTM3U" || line.startsWith("#EXT-X-VERSION:")) continue
            line = uri.replace(line) { "URI=\"${Parsing.resolve(it.groupValues[1],base)}\"" }
            if (line.startsWith("#EXT-X-STREAM-INF:")) line += ",SUBTITLES=\"bbc-subtitles\""
            else if (!line.startsWith('#')) line = Parsing.resolve(line,base)
            append(line+"\n")
        }
    }
}

/** A bounded, request-driven caption window. Only manifests and small caption fragments are adapted. */
class LiveCaptionResources private constructor(val authorisationUrl: String, private val media: String, private val templates: List<String>, private val fetch: (String, Int) -> ByteArray) {
    private val base = "https://beeb-captions.invalid/${java.util.UUID.randomUUID()}/"
    val masterUrl = base+"master.m3u8"
    private lateinit var master: ByteArray
    private val segments = linkedMapOf<String,LiveCaptions.Segment>()
    private val anchors = mutableMapOf<Int,Pair<Double,Long>>()
    private var cachedAt = 0L; private var cached = byteArrayOf()
    // Numeric stages only; never caption text, network URLs or credentials. No main-thread lock.
    @Volatile internal var debugSummary = ""
        private set
    @Synchronized private fun playlist(): ByteArray {
        val now = System.nanoTime()
        if (now-cachedAt in 1..1_000_000_000L && cached.isNotEmpty()) return cached
        val timeline = try { LiveCaptions.timeline(fetch(media,1_000_000).toString(Charsets.UTF_8),media) } catch(e: Exception) {
            if(e is java.io.InterruptedIOException || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            if(cached.isNotEmpty()) return cached
            throw e
        }
        val keep = segments.values.sortedByDescending { it.sequence }.take(128)
        segments.clear(); keep.forEach { segments[it.path]=it }; timeline.segments.forEach { segments[it.path]=it }
        anchors.keys.retainAll(segments.values.map { it.discontinuity }.toSet())
        cached = timeline.playlist().toByteArray(); cachedAt=now
        return cached
    }
    fun owns(url: String) = url.startsWith(base)
    @Synchronized fun resource(url: String): ByteArray {
        require(owns(url))
        val path=url.removePrefix(base)
        if (path=="master.m3u8") return master
        if (path=="english.m3u8") return playlist()
        val s=segments[path] ?: throw java.io.IOException("Live caption segment left the playlist window")
        if(BuildConfig.DEBUG)debugSummary="Requested segment ${s.number}; clock ${s.date}"
        return try {
            val anchor=anchors.getOrPut(s.discontinuity) { s.date to (LiveCaptions.firstVideoPts(fetch(s.video,8_000_000)) ?: error("No live video clock")) }
            for (template in templates) {
                if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val cues=runCatching { LiveCaptions.cues(fetch(template.replace("\$Number\$",s.number.toString()),8_000_000)) }.getOrNull() ?: continue
                if(BuildConfig.DEBUG)debugSummary="Segment ${s.number}; cues ${cues.size}; mapped ${cues.count {it.end>s.date && it.start<s.date+s.duration}}; lead ${cues.firstOrNull()?.let {it.start-s.date}}; PTS ${anchor.second}"
                return LiveCaptions.webVtt(cues,s,anchor.first,anchor.second).toByteArray()
            }
            if(BuildConfig.DEBUG)debugSummary="No caption fragment for ${s.number}"
            LiveCaptions.webVtt(emptyList(),s,anchor.first,anchor.second).toByteArray()
        } catch(e: Exception) {
            if(BuildConfig.DEBUG)debugSummary="Caption clock unavailable: ${e.javaClass.simpleName}"
            if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            // Missing captions must never terminate live video. The next fragment can recover.
            "WEBVTT\n\n".toByteArray()
        }
    }
    companion object {
        fun create(text: String, master: String, templates: List<String>, fetch: (String,Int)->ByteArray): LiveCaptionResources? {
            if(templates.isEmpty() || text.contains("TYPE=SUBTITLES")) return null
            val media=Parsing.variants(text,master).firstOrNull()?.url ?: return null
            val resources=LiveCaptionResources(master,media,templates,fetch)
            resources.playlist()
            resources.master=LiveCaptions.master(text,master,resources.base+"english.m3u8").toByteArray()
            return resources
        }
    }
}
