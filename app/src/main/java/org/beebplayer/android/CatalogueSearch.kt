// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CancellationException

/** Immutable pre-normalised index; called only on the search worker, never the UI thread. */
object CatalogueSearch {
    private val marks=Regex("\\p{M}+")
    private val whitespace=Regex("\\s+")
    fun normal(s: String)=marks.replace(Normalizer.normalize(s,Normalizer.Form.NFD),"").lowercase(Locale.ROOT)
    data class Entry(val programme: Programme,val title: String,val searchable: String)
    private fun checkCancelled() { if(Thread.currentThread().isInterrupted)throw CancellationException() }
    fun index(items: List<Programme>): List<Entry> = items.map { p -> checkCancelled(); Entry(p,normal(p.title),normal(p.title+" "+p.summary)) }
    fun search(index: List<Entry>,query: String): List<Programme> {
        val needle=normal(query.trim());if(needle.isEmpty())return emptyList()
        val words=needle.split(whitespace)
        return index.filter { checkCancelled(); words.all(it.searchable::contains) }
            .sortedWith(compareBy<Entry> { when {it.title==needle -> 0; it.title.startsWith(needle)->1;it.title.contains(needle)->2;else->3} }.thenBy {it.title})
            .map {checkCancelled();it.programme}
    }
}
