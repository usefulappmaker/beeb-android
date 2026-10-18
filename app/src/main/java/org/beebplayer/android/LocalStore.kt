// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Progress(val episode: Programme, val next: Programme?, val position: Long, val duration: Long, val completed: Boolean, val updated: Long) {
    val target get() = if (completed) next else episode
    fun json() = JSONObject().put("episode", episode.json()).put("next", next?.json()).put("position", position).put("duration", duration).put("completed", completed).put("updated", updated)
}
class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("BeebLocal", Context.MODE_PRIVATE)
    fun saved(): List<Programme> = runCatching { JSONArray(prefs.getString("saved", "[]")).objects().map(Programme::restore) }.getOrDefault(emptyList())
    fun isSaved(p: Programme) = saved().any { it.id == p.id }
    fun toggle(p: Programme) {
        val all = saved().toMutableList(); if (!all.removeAll { it.id == p.id }) all.add(0, p)
        prefs.edit().putString("saved", JSONArray(all.map { it.json() }).toString()).apply()
    }
    fun history(): List<Progress> = runCatching {
        JSONArray(prefs.getString("history", "[]")).objects().map { j -> Progress(Programme.restore(j.getJSONObject("episode")), j.optJSONObject("next")?.let(Programme::restore), j.optLong("position"), j.optLong("duration"), j.optBoolean("completed"), j.optLong("updated")) }.sortedByDescending { it.updated }
    }.getOrDefault(emptyList())
    fun resume(p: Programme): Long = history().firstOrNull { it.episode.id == p.id && !it.completed }?.position ?: 0
    fun update(p: Programme, next: Programme?, position: Long, duration: Long, ended: Boolean = false) {
        if (position < 5000 && !ended || duration <= 0) return
        val all = history().filterNot { it.episode.showId == p.showId }.toMutableList()
        all.add(0, Progress(p, next, position.coerceAtMost(duration), duration, Parsing.finished(position, duration, ended), System.currentTimeMillis()))
        prefs.edit().putString("history", JSONArray(all.take(40).map { it.json() }).toString()).apply()
    }
    fun remove(showId: String) { prefs.edit().putString("history", JSONArray(history().filterNot { it.episode.showId == showId }.map { it.json() }).toString()).apply() }
    fun autoCaptions() = prefs.getBoolean("captionsOnRewind", true)
    fun autoCaptions(value: Boolean) { prefs.edit().putBoolean("captionsOnRewind", value).apply() }
}
