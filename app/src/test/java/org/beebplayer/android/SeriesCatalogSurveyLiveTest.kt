package org.beebplayer.android

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Coverage guard across the live catalogue. Prints the breakdown and fails if the share of
 * episodes carrying a recognised run label collapses, which is what a wholesale BBC subtitle
 * format change would look like.
 */
class SeriesCatalogSurveyLiveTest {
    private fun fetch(path: String): JSONObject {
        val connection = java.net.URI("https://ibl.api.bbc.co.uk/ibl/v1/$path").toURL().openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/json")
        return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun episodes(pid: String): List<Programme> {
        val all = mutableListOf<Programme>()
        var page = 1
        while (page <= 3) {
            val body = fetch("programmes/$pid/episodes?page=$page&per_page=200").getJSONObject("programme_episodes")
            all += body.list("elements").map {
                Programme(it.text("id"), it.text("title"), it.text("subtitle"), position = it.optInt("parent_position"), date = it.text("release_date_time"))
            }
            val count = body.optInt("count"); val per = body.optInt("per_page")
            if (per <= 0 || page * per >= count) break
            page++
        }
        return all
    }

    @Test fun survey() {
        assumeTrue(System.getenv("BEEB_LIVE") == "1")
        val pids = mutableListOf<Pair<String, String>>()
        for (letter in "abcdefghijklmnopqrstuvwxyz") {
            val elements = runCatching { fetch("atoz/$letter/programmes?per_page=200").getJSONObject("atoz_programmes").list("elements") }.getOrNull() ?: continue
            elements.take(10).forEach { pids += it.text("id") to it.text("title") }
        }
        var shows = 0; var multi = 0; var oneSeries = 0; var noStructure = 0; var singleEpisode = 0; var empty = 0
        val noStructureExamples = mutableListOf<String>()
        val oneSeriesExamples = mutableListOf<String>()
        val prefixKinds = mutableMapOf<String, Int>()
        for ((pid, title) in pids) {
            val list = runCatching { episodes(pid) }.getOrNull() ?: continue
            if (list.isEmpty()) { empty++; continue }
            shows++
            val groups = SeriesCatalog.groups(list)
            if (list.size < 2) singleEpisode++ else if (groups.size > 1) multi++ else {
                // One tab is only right when every episode carries the same recognised run label.
                val labels = list.map { SeriesCatalog.label(it.subtitle) }.toSet()
                if (labels.size == 1 && labels.single() != SeriesCatalog.EPISODES) {
                    oneSeries++
                    if (oneSeriesExamples.size < 5) oneSeriesExamples += "$title [${list.size}] " + labels.single()
                } else {
                    noStructure++
                    if (noStructureExamples.size < 30) {
                        noStructureExamples += "$title [${list.size} eps] :: " + list.take(3).joinToString(" / ") { it.subtitle.take(44) }
                    }
                }
            }
            for (episode in list) {
                val sub = episode.subtitle
                val prefix = if (sub.contains(": ")) sub.substringBefore(": ") else null
                val kind = when {
                    prefix == null -> "no ':' prefix"
                    SeriesCatalog.label(sub) != SeriesCatalog.EPISODES -> "structural label"
                    else -> "other prefix"
                }
                prefixKinds[kind] = (prefixKinds[kind] ?: 0) + 1
            }
        }
        println("SURVEY shows=$shows empty=$empty singleEpisode=$singleEpisode multiTab=$multi oneSeries=$oneSeries noStructure=$noStructure")
        println("SURVEY one-series examples: $oneSeriesExamples")
        println("SURVEY episode prefix kinds: $prefixKinds")
        val recognised = (prefixKinds["structural label"] ?: 0)
        val total = prefixKinds.values.sum()
        println("SURVEY recognised=$recognised/$total")
        org.junit.Assert.assertTrue("only $recognised of $total episodes carried a recognised run label", total == 0 || recognised * 100 / total >= 70)
        println("SURVEY no-structure examples:")
        noStructureExamples.forEach { println("SURVEY   $it") }
    }
}
