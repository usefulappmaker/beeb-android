package org.beebplayer.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesCatalogTest {
    private fun episode(subtitle: String, position: Int = 0, date: String = "") =
        Programme("m00${subtitle.hashCode().toUInt()}", "Show", subtitle, position = position, date = date)

    private fun run(label: String, count: Int, start: String = "", step: Int = 7) =
        (1..count).map { n ->
            val date = if (start.isEmpty()) "" else java.time.LocalDate.parse(start).plusDays((n - 1) * step.toLong()).toString()
            episode("$label: $n. Episode $n", position = n, date = date)
        }

    @Test fun onlyConnectIsSplitIntoItsNumberedRuns() {
        val episodes = run("Series 22", 18, "2026-07-13") + run("Series 21", 28, "2025-07-21") +
            run("Series 21 Specials", 4, "2025-12-23", step = 2)
        val groups = SeriesCatalog.groups(episodes)
        assertEquals(listOf("Series 22", "Series 21", "Series 21 Specials"), groups.map { it.label })
        assertEquals(18, groups[0].episodes.size)
        assertEquals(28, groups[1].episodes.size)
        assertEquals(4, groups[2].episodes.size)
        assertEquals("Series 22: 1. Episode 1", groups[0].episodes.first().subtitle)
        assertEquals("Series 22: 18. Episode 18", groups[0].episodes.last().subtitle)
    }

    @Test fun onlyConnectRealSubtitlesProduceSeriesTabsNotOneFlatList() {
        // Verbatim shapes from BBC's episode service; slice_id is empty for every one of these.
        val subtitles = listOf(
            "Series 22: 18. Ad Astra v Makeweights",
            "Series 22: 1. Culture Club v Makeweights",
            "Series 21: 28. Final",
            "Series 21: 27. Third Place Play-Off",
            "Series 21 Specials: 4. Third-Place Play-Off Play-Off Special: Poptimists v Ramblers",
            "Series 21 Specials: 3. Law Special: Cartophiles v Harmonics",
        ).map { episode(it) }
        val groups = SeriesCatalog.groups(subtitles)
        assertEquals(listOf("Series 22", "Series 21", "Series 21 Specials"), groups.map { it.label })
        assertEquals("Series 22: 1. Culture Club v Makeweights", groups[0].episodes.first().subtitle)
        assertEquals("Series 21 Specials", SeriesCatalog.label("Series 21 Specials: 3. Law Special: Cartophiles v Harmonics"))
    }

    @Test fun universityChallengeOrdersSeriesYearsAndFestiveRuns() {
        val episodes = run("Series 33", 18, "2026-07-13") + run("2025/26", 37, "2025-09-01") +
            run("Christmas 2025", 10, "2025-12-22")
        val groups = SeriesCatalog.groups(episodes)
        assertEquals(listOf("Series 33", "2025/26", "Christmas 2025"), groups.map { it.label })
        val year = groups.single { it.label == "2025/26" }
        assertEquals(37, year.episodes.size)
        assertEquals("2025/26: 1. Episode 1", year.episodes.first().subtitle)
        assertEquals("2025/26: 37. Episode 37", year.episodes.last().subtitle)
        assertEquals("2025/26", SeriesCatalog.label("2025/26: 37. Grand Final: Edinburgh v Manchester"))
        assertEquals("Christmas 2025", SeriesCatalog.label("Christmas 2025: 10. Final"))
    }

    @Test fun unnumberedYearRunsKeepBroadcastOrder() {
        val episodes = listOf(
            episode("2026/27: Wales v Portugal", date = "2026-09-05"),
            episode("2026/27: Hungary v Northern Ireland", date = "2026-09-12"),
            episode("2026/27: Wales v Portugal Extra", date = "2026-10-03"),
        )
        val groups = SeriesCatalog.groups(episodes)
        assertEquals(listOf("2026/27"), groups.map { it.label })
        assertEquals(
            listOf("Wales v Portugal", "Hungary v Northern Ireland", "Wales v Portugal Extra"),
            groups.single().episodes.map { it.subtitle.substringAfter(": ") },
        )
    }

    @Test fun letteredRunsAreGroupedAndOrderedByLetter() {
        val episodes = run("Series X", 2, "2026-10-01") + run("Series W", 3, "2026-01-01") + run("Series V", 2, "2025-01-01")
        assertEquals(listOf("Series X", "Series W", "Series V"), SeriesCatalog.groups(episodes).map { it.label })
        // With no dates at all the letter itself decides, newest letter first.
        val undated = run("Series X", 1) + run("Series W", 1) + run("Series V", 1)
        assertEquals(listOf("Series X", "Series W", "Series V"), SeriesCatalog.groups(undated).map { it.label })
    }

    @Test fun welshAndOtherRunVocabularyGroups() {
        assertEquals("Cyfres 2", SeriesCatalog.label("Cyfres 2: Cyfrifiannell"))
        assertEquals("Season 3", SeriesCatalog.label("Season 3: Arrival"))
        assertEquals("Specials", SeriesCatalog.label("Specials: Christmas"))
        val groups = SeriesCatalog.groups(
            listOf(episode("Cyfres 1: Hwyl Fwrdd", date = "2025-01-01"), episode("Cyfres 2: Cyfrifiannell", date = "2026-01-01")),
        )
        assertEquals(listOf("Cyfres 2", "Cyfres 1"), groups.map { it.label })
    }

    @Test fun repeatedNonRunPrefixBecomesATabButAOneOffDoesNot() {
        val episodes = listOf(
            episode("Revival: Episode 1", position = 1, date = "2020-01-01"),
            episode("Revival: Episode 2", position = 2, date = "2020-01-08"),
            episode("The Final: Part 1", position = 3, date = "2020-02-01"),
        )
        val groups = SeriesCatalog.groups(episodes)
        assertEquals(listOf("Revival", SeriesCatalog.EPISODES), groups.map { it.label })
        assertEquals(listOf("The Final: Part 1"), groups.last().episodes.map { it.subtitle })
        assertEquals(SeriesCatalog.EPISODES, SeriesCatalog.label("The Final: Part 1"))
    }

    @Test fun flatProgrammeOrdersByVisibleNumberThenPosition() {
        val groups = SeriesCatalog.groups(
            listOf(
                episode("3. Gamma", position = 3),
                episode("1. Alpha", position = 1),
                episode("2. Beta", position = 2),
            ),
        )
        assertEquals(listOf(SeriesCatalog.EPISODES), groups.map { it.label })
        assertEquals(listOf("1. Alpha", "2. Beta", "3. Gamma"), groups.single().episodes.map { it.subtitle })
    }

    @Test fun positionOrdersEpisodesThatCarryNoVisibleNumber() {
        val groups = SeriesCatalog.groups(
            listOf(
                episode("Series U: VG: Part 2", position = 2),
                episode("Series U: VG: Part 1", position = 1),
            ),
        )
        assertEquals(listOf("Series U"), groups.map { it.label })
        assertEquals(listOf(1, 2), groups.single().episodes.map { it.position })
    }

    @Test fun orderedFlattensOldestRunFirstForNextEpisode() {
        val old = run("Series 1", 2, "2020-01-01")
        val new = run("Series 2", 2, "2021-01-01")
        assertEquals(
            listOf("Series 1: 1. Episode 1", "Series 1: 2. Episode 2", "Series 2: 1. Episode 1", "Series 2: 2. Episode 2"),
            SeriesCatalog.ordered(old + new).map { it.subtitle },
        )
        // Display order is the reverse: newest run first.
        assertEquals("Series 2", SeriesCatalog.groups(old + new).first().label)
    }

    @Test fun spelledEpisodeNumbersOrderTheRun() {
        // "Pennod 5" and "Episode 3" carry the number in words; without this they fell back to
        // broadcast date and arrived out of order.
        val groups = SeriesCatalog.groups(
            listOf(
                episode("Cyfres 1: Pennod 5", position = 0),
                episode("Cyfres 1: Pennod 2", position = 0),
                episode("Cyfres 1: Pennod 3", position = 0),
            ),
        )
        assertEquals(listOf("Pennod 2", "Pennod 3", "Pennod 5"), groups.single().episodes.map { it.subtitle.substringAfter(": ") })
        val flat = SeriesCatalog.groups(
            listOf(episode("Episode 4"), episode("Episode 2"), episode("Episode 3")),
        )
        assertEquals(listOf("Episode 2", "Episode 3", "Episode 4"), flat.single().episodes.map { it.subtitle })
    }

    @Test fun emptyInputIsHandled() {
        assertTrue(SeriesCatalog.groups(emptyList()).isEmpty())
        assertTrue(SeriesCatalog.ordered(emptyList()).isEmpty())
        assertEquals(SeriesCatalog.EPISODES, SeriesCatalog.label(""))
    }
}
