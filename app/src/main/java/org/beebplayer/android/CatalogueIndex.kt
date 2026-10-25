// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Full title index shared with the tvOS port. Refreshes are transactional and never replace it with an incomplete crawl. */
class CatalogueIndex(context: Context, private val api: BbcApi) {
    private val file = AtomicFile(context.filesDir.resolve("catalogue-v1.json"))
    private val seed = context.assets.open("catalogue-seed.json").bufferedReader().use { JSONObject(it.readText()) }
    private val snapshot = runCatching { file.openRead().bufferedReader().use { JSONObject(it.readText()) }.also { require(it.list("shows").isNotEmpty()) } }.getOrDefault(seed)
    @Volatile private var items = snapshot.list("shows").map(Programme::restore)
    @Volatile private var updated = snapshot.optLong("updated")
    private val knownUhd = seed.list("shows").filter { it.optBoolean("uhd") }.map { it.text("id") }.toMutableSet()
    init { knownUhd.addAll(items.filter { it.uhd }.map { it.id }) }
    fun needsRefresh() = System.currentTimeMillis()-updated > 24*60*60_000L
    fun allUhd() = items.filter { it.uhd }.sortedBy { normal(it.title) }
    fun search(query: String): List<Programme> {
        val needle=normal(query.trim()); if(needle.isEmpty()) return emptyList()
        val words=needle.split(Regex("\\s+"))
        return items.filter { p -> val text=normal(p.title+" "+p.summary); words.all(text::contains) }
            .sortedWith(compareBy<Programme> { val t=normal(it.title); when { t==needle -> 0; t.startsWith(needle) -> 1; t.contains(needle) -> 2; else -> 3 } }.thenBy { normal(it.title) })
    }
    @Synchronized fun discover(show: Programme, episodes: List<Programme>) {
        if(episodes.none { it.uhd }) return
        knownUhd.add(show.id)
        val p=show.copy(uhd=true,hdr=episodes.any { it.hdr })
        items=items.filterNot { it.id==p.id }+p
        save(items,updated)
    }
    @Synchronized fun refresh() {
        val result=linkedMapOf<String,Programme>()
        for(letter in listOf("0-9")+('a'..'z').map(Char::toString)) {
            var number=1; var total: Int?=null; val seen=mutableSetOf<String>()
            while(true) {
                check(number<=100) { "Catalogue paging limit reached" }
                val page=JSONObject(api.get("https://ibl.api.bbc.co.uk/ibl/v1/atoz/$letter/programmes?page=$number&per_page=200")).getJSONObject("atoz_programmes")
                val count=page.getInt("count"); val per=page.getInt("per_page")
                check(page.getInt("page")==number && per in 1..200 && (total==null || total==count)) { "BBC catalogue changed; saved catalogue retained" }
                total=count
                val entries=page.list("elements").map(Programme::show)
                check(entries.size<=per)
                val fresh=entries.filter { seen.add(it.id) }
                fresh.forEach { result[it.id]=it.copy(uhd=it.uhd || it.id in knownUhd) }
                if(number*per>=count) { check(seen.size==count) { "Incomplete BBC catalogue; saved catalogue retained" }; break }
                check(fresh.isNotEmpty()) { "BBC catalogue stopped early" }; number++
            }
        }
        check(result.isNotEmpty()) { "Empty catalogue; saved catalogue retained" }
        val next=result.values.toList(); val now=System.currentTimeMillis()
        save(next,now); items=next; updated=now
    }
    private fun save(shows: List<Programme>, time: Long) {
        val bytes=JSONObject().put("updated",time).put("shows",JSONArray(shows.map { it.json() })).toString().toByteArray()
        val out=file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch(e: Exception) { file.failWrite(out); throw e }
    }
    companion object { fun normal(s: String)=Normalizer.normalize(s,Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"").lowercase(Locale.ROOT) }
}
