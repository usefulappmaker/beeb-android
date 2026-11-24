// SPDX-License-Identifier: GPL-3.0-or-later
package org.beebplayer.android

import java.time.LocalDate

/**
 * Splits a programme's episodes into the runs a viewer recognises.
 *
 * The BBC carries the grouping in the first clause of an episode subtitle:
 *
 *     "Series 22: 18. Ad Astra v Makeweights"        -> Series 22, episode 18
 *     "Series 21 Specials: 3. Law Special: A v B"    -> Series 21 Specials, episode 3
 *     "2025/26: 37. Grand Final: Edinburgh v Manchester" -> 2025/26, episode 37
 *     "Christmas 2025: 10. Final"                    -> Christmas 2025, episode 10
 *     "Cyfres 1: Hwyl Fwrdd"                         -> Cyfres 1, ordered by position/date
 *     "2026/27: Wales v Portugal"                    -> 2026/27, ordered by date
 *
 * Rule set:
 *  1. A recognisable run label (Series/Season/Cyfres/Sraith N, an academic year, or a named
 *     festive run) always groups, whatever else the subtitle contains.
 *  2. Any other subtitle prefix groups only when at least two episodes share it, so a one-off
 *     "The Final: Part 1" cannot create a junk tab.
 *  3. Everything else is the programme's flat "Episodes" list.
 *  4. Runs are presented newest first; episodes inside a run oldest first.
 *
 * Episode order inside a run is the visible number ("18."), then the broadcaster's
 * `parent_position`, then the broadcast date. Nothing depends on the current date or network.
 */
object SeriesCatalog {
    data class Group(val label: String, val episodes: List<Programme>)

    const val EPISODES = "Episodes"

    private val numbered = Regex("^(?:Series|Season|Cyfres|Sraith)\\s+(?:\\d+|[A-Z])(?:\\b.*)$", RegexOption.IGNORE_CASE)
    private val academicYear = Regex("^\\d{4}(?:/\\d{2,4})?$")
    private val namedRun = Regex(
        "^(?:Christmas|New Year|New Year's|Festive|Halloween|Easter|Summer|Winter|Spring|Autumn|Specials|Extras|Bonus)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val leadingNumber = Regex("^\\s*(\\d{1,4})\\s*(?:[.)\\]:\\-–]\\s+|\\s+)")
    /** "Episode 4", "Ep 12", "Pennod 5" — the number is words away from the start. */
    private val spelledNumber = Regex("^(?:episode|ep|pennod|rhaglen)\\s*(\\d{1,4})\\b", RegexOption.IGNORE_CASE)

    /** The run label shown in a series tab, or [EPISODES] when the subtitle has no run. */
    fun label(subtitle: String): String {
        val prefix = prefix(subtitle) ?: return EPISODES
        return if (structural(prefix)) prefix else EPISODES
    }

    /** Episodes grouped into runs, newest run first, each run ordered oldest episode first. */
    fun groups(episodes: List<Programme>): List<Group> {
        if (episodes.isEmpty()) return emptyList()
        val counts = episodes.mapNotNull { prefix(it.subtitle) }.groupingBy { it }.eachCount()
        fun runOf(episode: Programme): String {
            val raw = prefix(episode.subtitle)
            if (raw == null) return if (episode.series.isNotBlank()) episode.series else EPISODES
            if (structural(raw)) return raw
            return if ((counts[raw] ?: 0) >= 2) raw else EPISODES
        }
        return episodes.groupBy(::runOf)
            .map { (label, items) -> Group(label, items.sortedWith(episodeOrder)) }
            .sortedWith(
                // The unlabelled bucket is a fallback, so it never outranks a real named run.
                compareBy<Group> { if (it.label == EPISODES) 1 else 0 }
                    .thenByDescending { it.latestDate() }
                    .thenByDescending { ordinal(it.label) }
                    .thenBy { it.label },
            )
    }

    /** Every episode in broadcast order: oldest run first, newest last. Used for "next episode". */
    fun ordered(episodes: List<Programme>): List<Programme> = groups(episodes).asReversed().flatMap { it.episodes }

    private val episodeOrder = compareBy<Programme>(
        { episodeNumber(it) ?: Int.MAX_VALUE },
        { it.position.takeIf { position -> position > 0 } ?: Int.MAX_VALUE },
        { it.date },
        { it.subtitle },
    )

    private fun prefix(subtitle: String): String? {
        val index = subtitle.indexOf(": ")
        if (index <= 0) return null
        return subtitle.substring(0, index).trim().takeIf { it.isNotEmpty() }
    }

    private fun structural(prefix: String) =
        numbered.matches(prefix) || academicYear.matches(prefix) || namedRun.containsMatchIn(prefix)

    private fun episodeNumber(episode: Programme): Int? {
        val subtitle = episode.subtitle
        val head = prefix(subtitle)
        val rest = if (head != null && subtitle.startsWith("$head:")) subtitle.substringAfter(':') else subtitle
        val trimmed = rest.trim()
        return leadingNumber.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
            ?: spelledNumber.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun ordinal(label: String): Int {
        Regex("\\d+").find(label)?.value?.toIntOrNull()?.let { return it }
        // Lettered runs: QI uses "Series A".."Series X".
        val letter = Regex("(?:Series|Season|Cyfres|Sraith)\\s+([A-Z])", RegexOption.IGNORE_CASE)
            .find(label)?.groupValues?.get(1)?.uppercase()?.singleOrNull()
        return if (letter != null && letter in 'A'..'Z') letter - 'A' + 1 else -1
    }

    private fun Group.latestDate(): Long = episodes.maxOf { epochDay(it.date) }

    private fun epochDay(date: String): Long =
        runCatching { LocalDate.parse(date.take(10)).toEpochDay() }.getOrDefault(Long.MIN_VALUE)
}
