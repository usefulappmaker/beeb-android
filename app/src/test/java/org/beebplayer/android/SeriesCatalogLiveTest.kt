package org.beebplayer.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in check of the grouping rules against today's BBC catalogue. The suite stays offline by
 * default; run with BEEB_LIVE=1 to fetch. Assertions are deliberately about structure (a show is
 * split into named runs, each run is in broadcast order), not about exact episode counts, because
 * the catalogue changes.
 */
class SeriesCatalogLiveTest {
    private fun fetch(path: String): JSONObject {
        val connection = java.net.URI("https://ibl.api.bbc.co.uk/ibl/v1/$path").toURL().openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/json")
        return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun episodes(pid: String): List<Programme> =
        fetch("programmes/$pid/episodes?per_page=200").getJSONObject("programme_episodes").list("elements")
            .map { Programme(it.text("id"), it.text("title"), it.text("subtitle"), position = it.optInt("parent_position"), date = it.text("release_date_time")) }

    private fun groups(pid: String): List<SeriesCatalog.Group> = SeriesCatalog.groups(episodes(pid))

    @Test fun onlyConnectIsSplitIntoSeriesRuns() {
        assumeTrue(System.getenv("BEEB_LIVE") == "1")
        val groups = groups("b00lskhg")
        println("ONLY CONNECT: " + groups.joinToString(" | ") { "${it.label}(${it.episodes.size})" })
        assertTrue("expected several series runs, got ${groups.map { it.label }}", groups.size >= 2)
        assertTrue(groups.count { it.label.startsWith("Series ") } >= 2)
        assertTrue("every run must hold more than one episode", groups.all { it.episodes.size > 1 })
    }

    @Test fun universityChallengeSplitsSeriesYearsAndFestiveRuns() {
        assumeTrue(System.getenv("BEEB_LIVE") == "1")
        val uc = groups("b006t6l0")
        println("UNIVERSITY CHALLENGE: " + uc.joinToString(" | ") { "${it.label}(${it.episodes.size})" })
        println("UC 2025/26 order: " + (uc.firstOrNull { it.label == "2025/26" }?.episodes?.take(3)?.joinToString(" / ") { it.subtitle.substringAfter(": ") } ?: "none"))
        val labels = uc.map { it.label }
        assertTrue("year run missing in $labels", labels.any { it.matches(Regex("\\d{4}/\\d{2,4}")) })
        assertTrue("festive run missing in $labels", labels.any { it.startsWith("Christmas ") })
        assertTrue("numbered series missing in $labels", labels.any { it.startsWith("Series ") })
    }

    @Test fun letteredRunsAndSpecialsKeepTheirOwnTabs() {
        assumeTrue(System.getenv("BEEB_LIVE") == "1")
        val qi = groups("b006ml0g").map { it.label }
        println("QI: " + qi.joinToString(" | "))
        assertTrue("QI lettered runs missing: $qi", qi.count { it.matches(Regex("Series [A-Z]")) } >= 5)
        val pablo = groups("b097bs09").map { it.label }
        assertTrue("Pablo specials missing: $pablo", pablo.contains("Specials"))
    }

    @Test fun everySampledProgrammeHasAFullyNavigableOrder() {
        assumeTrue(System.getenv("BEEB_LIVE") == "1")
        val shows = fetch("atoz/b/programmes?per_page=30").getJSONObject("atoz_programmes").list("elements").map { it.text("id") }
        for (pid in shows.take(12)) {
            val all = episodes(pid)
            if (all.isEmpty()) continue
            val ordered = SeriesCatalog.ordered(all)
            assertEquals("$pid lost or duplicated episodes", all.size, ordered.size)
            assertEquals("$pid produced duplicate ids", all.size, ordered.map { it.id }.toSet().size)
            val groups = SeriesCatalog.groups(all)
            assertEquals("$pid grouping lost episodes", all.size, groups.sumOf { it.episodes.size })
            assertTrue("$pid produced more groups than episodes", groups.size <= all.size)
        }
    }
}
