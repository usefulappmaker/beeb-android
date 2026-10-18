// SPDX-License-Identifier: GPL-3.0-or-later
// Discovery/request flow adapted from Beeb/get_iplayer; see NOTICE.md.
package org.beebplayer.android

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.io.StringReader
import java.time.Instant
import java.util.concurrent.TimeUnit

class BbcApi(private val context: Context) {
    val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    val vault = IdentityVault(context)
    fun get(url: String, client: OkHttpClient = http): String {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Request cancelled")
        require(url.startsWith("https://"))
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "BBC returned HTTP ${response.code}. Content or network access may be restricted." }
            return response.body?.string() ?: error("Empty service response")
        }
    }
    private fun json(url: String) = JSONObject(get(url))
    fun home(): Home {
        val file = context.cacheDir.resolve("home-v1.json")
        return try { val html = get("https://www.bbc.co.uk/iplayer"); val parsed = Parsing.home(html); file.writeText(html); parsed }
        catch (e: Exception) { if (file.exists()) Parsing.home(file.readText()) else throw e }
    }
    fun genres() = json("https://ibl.api.bbc.co.uk/ibl/v1/categories").list("categories").filter { it.text("kind") == "genre" }.map { it.text("id") to it.text("title") }
    fun page(key: String, category: Boolean, number: Int): Page {
        require(key.matches(Regex("[a-z0-9-]+")) && number > 0)
        val path = if (category) "categories/$key/programmes" else "atoz/$key/programmes"
        val body = json("https://ibl.api.bbc.co.uk/ibl/v1/$path?page=$number&per_page=30").getJSONObject(if (category) "category_programmes" else "atoz_programmes")
        check(body.optInt("page") == number && body.optInt("per_page") in 1..30 && body.list("elements").size <= 30) { "Unexpected BBC catalogue page" }
        return Page(body.list("elements").map(Programme::show), number, maxOf(1, (body.optInt("count") + body.optInt("per_page") - 1) / body.optInt("per_page")))
    }
    fun episodes(show: Programme): List<Programme> {
        val result = linkedMapOf<String, Programme>(); var page = 1
        while (page <= 200) {
            val body = try { json("https://ibl.api.bbc.co.uk/ibl/v1/programmes/${Parsing.pid(show.id)}/episodes?page=$page&per_page=200").getJSONObject("programme_episodes") }
            catch (e: Exception) { if (page == 1) return listOf(episode(show.id)); throw e }
            check(body.optInt("page") == page && body.optInt("per_page") > 0) { "Unexpected episode pagination" }
            val additions = body.list("elements").map(Programme::episode).filterNot { it.id in result }
            additions.forEach { result[it.id] = it }
            if (page * body.optInt("per_page") >= body.optInt("count")) return Parsing.ordered(result.values.toList())
            check(additions.isNotEmpty()) { "Episode paging stopped early" }; page++
        }
        error("Programme has too many episode pages")
    }
    fun episode(id: String): Programme = Programme.episode(episodeJson(id))
    private fun episodeJson(id: String) = json("https://ibl.api.bbc.co.uk/ibl/v1/episodes/${Parsing.pid(id)}").list("episodes").firstOrNull() ?: error("Episode no longer available")
    fun channels(): List<Channel> = json("https://ibl.api.bbc.co.uk/ibl/v1/channels").list("channels").filter { it.optBoolean("has_schedule") }.map { j ->
        val id = j.text("id"); val name = j.text("title")
        runCatching {
            val now = Instant.now()
            val current = json("https://ibl.api.bbc.co.uk/ibl/v1/channels/$id/broadcasts?per_page=20").optJSONObject("broadcasts")?.list("elements")?.firstOrNull { b -> !b.optBoolean("blanked") && Instant.parse(b.text("scheduled_start")) <= now && now < Instant.parse(b.text("scheduled_end")) }
            val e = current?.optJSONObject("episode")
            Channel(id, name, e?.text("title") ?: name, artwork(e?.optJSONObject("images")))
        }.getOrDefault(Channel(id, name))
    }
    private fun selector(xml: String, kind: String): List<String> {
        val parser = Xml.newPullParser(); parser.setInput(StringReader(xml)); var matching = false; val urls = mutableListOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (parser.name == "media") matching = parser.getAttributeValue(null, "kind") == kind
                if (parser.name == "connection" && matching && (kind == "captions" || parser.getAttributeValue(null, "transferFormat") == "hls")) {
                    parser.getAttributeValue(null, "href")?.takeIf { it.startsWith("https://") }?.let(urls::add)
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "media") matching = false
            parser.next()
        }
        return urls.distinct()
    }
    private fun listing(version: String, set: String, selectorVersion: Int = 6) = get("https://open.live.bbc.co.uk/mediaselector/$selectorVersion/select/version/2.0/mediaset/$set/vpid/$version/format/xml")
    private fun hd(version: String, live: Boolean = false): Stream {
        var last: Exception? = null
        var fallback: Variant? = null
        for (api in listOf(6, 5)) for (set in listOf("iptv-all", "pc")) {
            try {
                val masters = selector(listing(version, set, api), "video")
                for (master in masters) {
                    try {
                        val text = get(master); if (!text.startsWith("#EXTM3U")) continue
                        if (live) return Stream(master)
                        val variants = Parsing.variants(text, master).filter { it.height < 2160 }
                        val best = variants.firstOrNull { it.height >= 1080 }
                        if (best != null && get(best.url).startsWith("#EXTM3U")) return Stream(best.url, version)
                        variants.firstOrNull { it.height >= 720 }?.let { v ->
                            val candidate = v.url.replace(Regex("video=[0-9]+"), "video=12000000")
                            if (candidate != v.url) runCatching { get(candidate) }.getOrNull()?.let { media ->
                                if (media.startsWith("#EXTM3U") && media.contains("#EXTINF:") && media.contains("video=12000000")) return Stream(candidate, version)
                            }
                        }
                        variants.firstOrNull()?.let { candidate -> if (fallback == null || candidate.height > fallback!!.height || candidate.height == fallback!!.height && candidate.bandwidth > fallback!!.bandwidth) fallback = candidate }
                        if (text.contains("#EXTINF:")) return Stream(master, version)
                    } catch (e: Exception) { last = e }
                }
            } catch (e: Exception) { last = e }
        }
        fallback?.let { return Stream(it.url, version) }
        error(last?.message ?: "No compatible BBC stream returned")
    }
    private fun uhd(p: Programme): Stream {
        val client = vault.selectorClient()
        val versions = json("https://www.bbc.co.uk/programmes/${Parsing.pid(p.id)}/playlist.json").list("allAvailableVersions").filter { v ->
            val types = v.optJSONArray("types")?.toString().orEmpty(); !types.contains("described") && !types.contains("signed")
        }.map { it.text("pid") }.ifEmpty { listOf(p.version) }.filter { it.isNotEmpty() }.distinct()
        for (id in versions) {
            val response = runCatching { JSONObject(get("https://securegate.iplayer.bbc.co.uk/mediaselector/6/select/version/2.0/vpid/${Parsing.pid(id)}/format/json/mediaset/iptv-uhd/proto/https", client)) }.getOrNull() ?: continue
            for (video in response.list("media").filter { it.text("kind") == "video" && it.optInt("height") >= 2160 }) {
                for (connection in video.list("connection").filter { it.text("transferFormat") == "dash" }.sortedBy { it.optInt("priority", 99) }) {
                    val url = connection.text("href")
                    if (url.startsWith("https://")) {
                        val mpd = get(url)
                        check(!mpd.contains("ContentProtection") && !mpd.contains("type=\"dynamic\"")) { "Protected or dynamic UHD presentations are not supported" }
                        return Stream(url, id, true)
                    }
                }
            }
        }
        error("BBC returned no compatible UHD stream")
    }
    fun stream(p: Programme, allowUhd: Boolean): Stream {
        val fresh = episode(p.id)
        var source = if (allowUhd && vault.configured()) runCatching { uhd(fresh) }.getOrNull() ?: hd(fresh.version) else hd(fresh.version)
        val details = runCatching { episodeJson(p.id) }.getOrNull()
        val v = details?.list("versions")?.firstOrNull { it.text("id") == source.version }
        // Download optional captions before attaching them: caption network failures must not abort UHD.
        val captions = runCatching {
            val url = selector(listing(source.version, "iptv-all"), "captions").firstOrNull() ?: return@runCatching ""
            val ttml = get(url); require(ttml.length < 4_000_000 && ttml.contains("<tt"))
            val file = context.cacheDir.resolve("captions-${Parsing.pid(source.version)}.ttml"); file.writeText(ttml); file.toURI().toString()
        }.getOrDefault("")
        source = source.copy(caption = captions, skips = v?.let(Skip::parse).orEmpty())
        return source
    }
    fun live(id: String): Stream { require(id.matches(Regex("[a-z0-9_]+"))); return hd(id, true) }
}
