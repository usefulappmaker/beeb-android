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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
    private fun bytes(url: String, limit: Int): ByteArray {
        require(url.startsWith("https://"))
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
        http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build().newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "Caption service unavailable" }
            val body = r.body ?: error("Empty caption response")
            require(body.contentLength() <= limit)
            body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while(true) { val n=input.read(buffer); if(n<0)break; require(out.size()+n<=limit); out.write(buffer,0,n) }
                return out.toByteArray()
            }
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
        val result = linkedMapOf<String, Programme>(); val seen = mutableSetOf<String>(); var page = 1
        while (page <= 200) {
            val body = try { json("https://ibl.api.bbc.co.uk/ibl/v1/programmes/${Parsing.pid(show.id)}/episodes?page=$page&per_page=200").getJSONObject("programme_episodes") }
            catch (e: Exception) { if (page == 1) return listOf(episode(show.id)); throw e }
            check(body.optInt("page") == page && body.optInt("per_page") > 0) { "Unexpected episode pagination" }
            val newIds = body.list("elements").map { it.text("id") }.filter { seen.add(it) }
            val additions = body.list("elements").filter { PlaybackVersions.available(it).isNotEmpty() }.map(Programme::episode).filterNot { it.id in result }
            additions.forEach { result[it.id] = it }
            if (page * body.optInt("per_page") >= body.optInt("count")) return Parsing.ordered(result.values.toList())
            check(newIds.isNotEmpty()) { "Episode paging stopped early" }; page++
        }
        error("Programme has too many episode pages")
    }
    fun episode(id: String): Programme = Programme.episode(episodeJson(id))
    private fun episodeJson(id: String): JSONObject {
        val pid=Parsing.pid(id)
        return PlaybackVersions.episode(get("https://ibl.api.bbc.co.uk/ibl/v1/episodes/$pid"),pid)
    }
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
    private fun probeDebug(message: String) { if(BuildConfig.DEBUG) context.filesDir.resolve("uhd-probe-debug.txt").appendText(message+"\n") }
    private fun listing(version: String, set: String, selectorVersion: Int = 6) = get("https://open.live.bbc.co.uk/mediaselector/$selectorVersion/select/version/2.0/mediaset/$set/vpid/$version/format/xml")
    private fun hd(version: String, live: Boolean = false): Stream {
        var last: Exception? = null
        var fallback: Variant? = null
        for (api in listOf(6, 5)) for (set in listOf("iptv-all", "pc")) {
            try {
                val xml = listing(version, set, api)
                val masters = selector(xml, "video")
                for (master in masters) {
                    try {
                        val text = get(master); if (!text.startsWith("#EXTM3U")) continue
                        if (live) {
                            val captions = runCatching { LiveCaptionResources.create(text, master, selector(xml,"captions").filter { it.contains("\$Number\$") }, ::bytes) }.getOrNull()
                            return Stream(captions?.masterUrl ?: master, liveCaptions = captions)
                        }
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
    private fun uhd(p: Programme, client: OkHttpClient = vault.selectorClient()): Stream {
        val versions = PlaybackVersions.available(episodeJson(p.id)).map { it.text("id") }
        for (id in versions) {
            val response = runCatching { JSONObject(get("https://securegate.iplayer.bbc.co.uk/mediaselector/6/select/version/2.0/vpid/${Parsing.pid(id)}/format/json/mediaset/iptv-uhd/proto/https", client)) }.onFailure {
                if(BuildConfig.DEBUG) probeDebug("Selector failed: "+it.javaClass.simpleName+" "+Regex("HTTP [0-9]{3}").find(it.message.orEmpty())?.value.orEmpty())
            }.getOrNull() ?: continue
            if(BuildConfig.DEBUG) probeDebug("Selector video heights: "+response.list("media").filter { it.text("kind")=="video" }.map {it.optInt("height")})
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
    private fun bytes(url: String, maximum: Int, range: androidx.media3.exoplayer.dash.manifest.RangedUri? = null): ByteArray {
        require(url.startsWith("https://"))
        val request = Request.Builder().url(url)
        if (range != null && (range.start > 0 || range.length > 0)) {
            val end = if (range.length > 0) (range.start+range.length-1).toString() else ""
            request.header("Range", "bytes=${range.start}-$end")
        }
        return http.newCall(request.build()).execute().use { response ->
            check(response.isSuccessful && response.request.url.scheme == "https") { "UHD media could not be checked" }
            val input = response.body?.byteStream() ?: error("Empty UHD media response")
            input.use {
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { val n=it.read(buffer); if(n<0)break; check(output.size()+n<=maximum) { "UHD media check exceeded its size limit" }; output.write(buffer,0,n) }
                output.toByteArray()
            }
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    internal fun verifyUhdSetup(client: OkHttpClient, check: () -> Unit): String {
        if(BuildConfig.DEBUG) context.filesDir.resolve("uhd-probe-debug.txt").writeText("Starting UHD probe\n")
        // Same bounded fallback as the tvOS setup: at most three known UHD programmes, two episodes each.
        val shows = CatalogueIndex(context,this).allUhd().take(3)
        for (show in shows) {
            check()
            val candidates = runCatching {
                val page=json("https://ibl.api.bbc.co.uk/ibl/v1/programmes/${Parsing.pid(show.id)}/episodes?page=1&per_page=24")
                page.getJSONObject("programme_episodes").list("elements").filter { PlaybackVersions.available(it).any { v -> v.optBoolean("uhd") } }.take(2).map(Programme::episode)
            }.getOrElse { runCatching { listOf(episode(show.id)) }.getOrDefault(emptyList()) }
            if(BuildConfig.DEBUG) probeDebug("Candidates: "+candidates.size)
            for (p in candidates) {
                check()
                var stage="selector"
                val ok=runCatching {
                    val stream=uhd(p,client);check();stage="manifest"
                    val data=bytes(stream.url,4_000_000)
                    val manifest=androidx.media3.exoplayer.dash.manifest.DashManifestParser().parse(android.net.Uri.parse(stream.url),data.inputStream())
                    require(!manifest.dynamic && manifest.periodCount>0)
                    if(BuildConfig.DEBUG) probeDebug("Manifest representations: "+manifest.getPeriod(0).adaptationSets.flatMap {it.representations}.map {"${it.format.height}/${it.format.codecs}"})
                    for (adaptation in manifest.getPeriod(0).adaptationSets) for (representation in adaptation.representations) {
                        val f=representation.format
                        if(f.height<2160 || f.drmInitData!=null || !(f.codecs.orEmpty().startsWith("hvc1") || f.codecs.orEmpty().startsWith("hev1") || f.sampleMimeType=="video/hevc")) continue
                        val init=representation.initializationUri ?: continue
                        val reference=init.resolveUriString(representation.baseUrls.first().url)
                        stage="initialization";check(); val initialization=bytes(Parsing.resolve(reference,stream.url),2_000_000,init)
                        if(BuildConfig.DEBUG) probeDebug("HLG initialization: "+UhdSignalling.hlg(initialization))
                        if (UhdSignalling.hlg(initialization)) return show.title
                    }
                    false
                }.onFailure { if(BuildConfig.DEBUG) probeDebug("$stage failed: "+it.javaClass.simpleName+" "+Regex("HTTP [0-9]{3}").find(it.message.orEmpty())?.value.orEmpty()) }.getOrDefault(false)
                check()
                if(ok) return show.title
            }
        }
        error("BBC did not return a verifiable 4K HLG stream. Your existing UHD setup has been kept. Please try again later.")
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
