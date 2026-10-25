// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

fun JSONObject.text(key: String, fallback: String = "") = if (isNull(key)) fallback else optString(key, fallback)
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.list(key: String) = optJSONArray(key)?.objects().orEmpty()
fun JSONObject.fields(key: String, vararg choices: String): String = optJSONObject(key)?.let { obj -> choices.map { obj.text(it) }.firstOrNull { it.isNotBlank() } }.orEmpty()
fun artwork(images: JSONObject?, episode: Boolean = false, large: Boolean = false): String {
    val keys = if (episode) listOf("standard", "promotional", "promotional_with_logo") else listOf("promotional_with_logo", "standard", "promotional")
    return keys.map { images?.text(it).orEmpty() }.firstOrNull { it.isNotBlank() }?.replace("{recipe}", if (large) "1920x1080" else "640x360").orEmpty()
}

data class Programme(val id: String, val title: String, val subtitle: String = "", val summary: String = "", val image: String = "", val version: String = "", val showId: String = id, val uhd: Boolean = false, val hdr: Boolean = false, val duration: String = "", val series: String = "Episodes", val position: Int = 0, val date: String = "") {
    fun json() = JSONObject().put("id", id).put("title", title).put("subtitle", subtitle).put("summary", summary).put("image", image).put("version", version).put("showId", showId).put("uhd", uhd).put("hdr", hdr).put("duration", duration).put("series", series).put("position", position).put("date", date)
    companion object {
        fun restore(j: JSONObject) = Programme(j.text("id"), j.text("title"), j.text("subtitle"), j.text("summary"), j.text("image"), j.text("version"), j.text("showId", j.text("id")), j.optBoolean("uhd"), j.optBoolean("hdr"), j.text("duration"), j.text("series", "Episodes"), j.optInt("position"), j.text("date"))
        fun episode(j: JSONObject): Programme {
            require(j.text("id").matches(Regex("[a-z0-9]{8}"))) { "Invalid episode identifier" }
            val v = PlaybackVersions.available(j).firstOrNull() ?: error("No standard version of this episode is currently available.")
            val sub = j.text("subtitle")
            return Programme(j.text("id"), j.text("title"), sub, j.fields("synopses", "large", "medium", "small"), artwork(j.optJSONObject("images"), episode = true), v.text("id"), j.text("tleo_id", j.text("id")), v.optBoolean("uhd"), v.optBoolean("hdr"), v.optJSONObject("duration")?.text("text").orEmpty(), if (j.text("slice_id") != "" && sub.contains(": ")) sub.substringBefore(": ") else "Episodes", j.optInt("parent_position"), j.text("release_date_time"))
        }
        fun show(j: JSONObject) = Programme(j.text("id"), j.text("title"), summary = j.optJSONObject("synopses")?.text("medium").orEmpty(), image = artwork(j.optJSONObject("images")), uhd = j.list("initial_children").any { it.list("versions").any { v -> v.optBoolean("uhd") } })
    }
}
data class Feature(val show: Programme, val image: String, val headline: String, val summary: String)
data class Home(val features: List<Feature>, val rows: List<Pair<String, List<Programme>>>)
data class Page(val shows: List<Programme>, val number: Int, val totalPages: Int)
data class Channel(val id: String, val name: String, val title: String = name, val image: String = "")
data class Skip(val title: String, val startMs: Long, val endMs: Long) {
    fun contains(ms: Long) = ms >= startMs && ms < endMs
    companion object {
        fun parse(version: JSONObject): List<Skip> = version.list("interactions").mapNotNull { j ->
            val p = j.optJSONObject("interaction_points") ?: return@mapNotNull null
            val start = p.optDouble("show_from", Double.NaN); val end = p.optDouble("skip_to", Double.NaN)
            if (j.text("type") != "skip" || j.text("subtype") !in listOf("intro", "recap") || !start.isFinite() || !end.isFinite() || start < 0 || end <= start) null
            else Skip(if (j.text("subtype") == "intro") "Skip intro" else "Skip recap", (start * 1000).toLong(), (end * 1000).toLong())
        }
    }
}
data class Stream(val url: String, val version: String = "", val uhd: Boolean = false, val caption: String = "", val skips: List<Skip> = emptyList())
data class Variant(val url: String, val height: Int, val bandwidth: Long)

object Parsing {
    fun pid(input: String): String {
        val raw = input.trim()
        if (raw.matches(Regex("[b-df-hj-np-tv-z0-9]{8}"))) return raw
        val uri = runCatching { URI(raw) }.getOrNull()
        if (uri != null && (uri.host == "bbc.co.uk" || uri.host?.endsWith(".bbc.co.uk") == true)) {
            val parts = uri.path.orEmpty().split('/')
            parts.forEachIndexed { i, s -> if (s in listOf("episode", "episodes", "programmes") && i + 1 < parts.size) return pid(parts[i + 1]) }
        }
        error("Enter an individual BBC episode URL or eight-character ID.")
    }
    fun state(html: String): JSONObject {
        val marker = html.indexOf("window.__IPLAYER_REDUX_STATE__")
        require(marker >= 0) { "BBC homepage data is unavailable." }
        val start = html.indexOf('{', marker)
        require(start >= 0)
        var depth = 0; var quoted = false; var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else if (c == '"') quoted = true else if (c == '{') depth++ else if (c == '}' && --depth == 0) return JSONObject(html.substring(start, i + 1))
        }
        error("BBC homepage data is incomplete.")
    }
    fun home(html: String): Home {
        val bundles = state(html).list("bundles")
        var hero = emptyList<Feature>(); var editorial = emptyList<Feature>()
        val rows = mutableListOf<Pair<String, List<Programme>>>()
        for (bundle in bundles) {
            val seen = mutableSetOf<String>()
            val features = bundle.list("entities").mapNotNull { entity ->
                val e = entity.optJSONObject("episode") ?: return@mapNotNull null
                if (e.optBoolean("live")) return@mapNotNull null
                val id = e.optJSONObject("tleo")?.text("id").orEmpty().ifEmpty { e.text("id") }
                if (!id.matches(Regex("[a-z0-9]{8}")) || !seen.add(id)) return@mapNotNull null
                val show = Programme(id, e.fields("title", "default", "editorial"), summary = e.fields("synopsis", "programmeSmall", "small", "editorial"), image = e.fields("image", "promotionalWithLogo", "default", "promotional").replace("{recipe}", "640x360"))
                Feature(show, e.fields("image", "promotional", "default").replace("{recipe}", "1920x1080"), e.fields("title", "editorial", "default"), e.fields("synopsis", "editorial", "small"))
            }
            when (bundle.text("id")) { "hero" -> hero = features; "editorial" -> editorial = features }
            if (features.isNotEmpty() && bundle.text("id") != "hero") rows += bundle.fields("title", "default", "small").ifEmpty { "Discover" } to features.map { it.show }
        }
        val popular = rows.filter { it.first.contains("popular", true) }
        require(rows.isNotEmpty()) { "BBC returned no programmes." }
        return Home((editorial.ifEmpty { hero }).take(8), popular + rows.filterNot { it in popular })
    }
    fun resolve(reference: String, base: String): String {
        val parent = URI(base); val child = parent.resolve(reference)
        require(child.scheme == "https") { "Only secure media URLs are supported." }
        return if (parent.host == child.host && !parent.rawQuery.isNullOrEmpty()) {
            val plain = child.toString().substringBefore('?').substringBefore('#')
            plain + "?" + parent.rawQuery + (child.rawQuery?.let { "&$it" } ?: "")
        } else child.toString()
    }
    fun variants(text: String, base: String): List<Variant> {
        var attrs = ""
        return text.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) { attrs = line; null }
            else if (line.isNotEmpty() && !line.startsWith('#') && attrs.isNotEmpty()) {
                val h = Regex("RESOLUTION=[0-9]+x([0-9]+)").find(attrs)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val bw = Regex("(?:[:,])BANDWIDTH=([0-9]+)").find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                attrs = ""; Variant(resolve(line, base), h, bw)
            } else null
        }.sortedWith(compareByDescending<Variant> { it.height }.thenByDescending { it.bandwidth }).toList()
    }
    fun ordered(items: List<Programme>): List<Programme> {
        fun series(p: Programme) = Regex("(?:Series|Season)\\s+(\\d+)", RegexOption.IGNORE_CASE).find(p.series)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
        return items.sortedWith(compareBy<Programme> { series(it) }.thenBy { it.series }.thenBy { if (it.position > 0) it.position else Int.MAX_VALUE }.thenBy { it.date }.thenBy { it.subtitle })
    }
    fun finished(position: Long, duration: Long, ended: Boolean) = ended || (duration > 0 && position >= duration * .95 && duration - position <= 120_000)
}
