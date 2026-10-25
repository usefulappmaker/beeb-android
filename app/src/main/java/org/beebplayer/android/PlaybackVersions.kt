// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import org.json.JSONObject
import java.time.Instant
import java.util.Locale

object PlaybackVersions {
    fun episode(data: String, id: String): JSONObject = try {
        JSONObject(data).list("episodes").firstOrNull { it.text("id") == id }
            ?: error("BBC's episode service returned details for a different episode.")
    } catch (_: org.json.JSONException) { error("BBC's episode service returned unreadable details. Please try again later.") }
    fun available(episode: JSONObject, now: Instant = Instant.now()): List<JSONObject> {
        val usable = episode.list("versions").filter { v ->
            val kind = v.text("kind", "original").lowercase(Locale.ROOT)
            val availability = v.optJSONObject("availability")
            fun date(key: String) = availability?.text(key)?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val start = date("start"); val end = date("end")
            v.text("id").matches(Regex("[a-z0-9]{8}")) && !kind.contains("signed") && !kind.contains("describ") &&
                (start == null || start <= now) && (end == null || now < end)
        }
        return usable.sortedByDescending { it.optBoolean("uhd") }.distinctBy { it.text("id") }
    }
}
