package uk.ewancroft.fathom.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.ewancroft.fathom.data.Dashboard
import uk.ewancroft.fathom.shared.format.RelativeTime
import uk.ewancroft.fathom.shared.model.Play
import uk.ewancroft.fathom.shared.time.CivilDays
import uk.ewancroft.fathom.shared.time.IsoInstant
import uk.ewancroft.fathom.ui.components.EmptyState
import uk.ewancroft.fathom.ui.components.PlayRow

/** One day's plays, already ordered newest first. */
private data class DaySection(val epochDay: Long, val plays: List<Play>)

/** What History says about records it cannot place under a day. */
private fun undatedNotice(count: Int): String =
    if (count == 1) {
        "1 play has no readable timestamp and is not shown."
    } else {
        "$count plays have no readable timestamp and are not shown."
    }

/**
 * Every play Fathom read, grouped by the day the listener had.
 *
 * The grouping uses the device's UTC offset rather than the record's own, so
 * a play at 00:30 local appears under today rather than under yesterday
 * because the timestamp happened to be UTC.
 */
@Composable
fun HistoryScreen(
    dashboard: Dashboard,
    nowEpochSeconds: Long,
    tzOffsetMinutes: Int,
    reading: Boolean,
) {
    val sections = remember(dashboard.plays, tzOffsetMinutes) {
        val dated = dashboard.plays
            .mapNotNull { play -> IsoInstant.parse(play.playedTime)?.let { play to it } }
        dated
            .groupBy { (_, instant) -> CivilDays.epochDay(instant, tzOffsetMinutes) }
            .toSortedMap(compareByDescending { it })
            .map { (day, entries) -> DaySection(day, entries.map { it.first }) }
    }

    val undated = dashboard.stats.undatedPlays

    if (sections.isEmpty()) {
        if (reading) {
            EmptyState(
                title = "Reading history",
                body = "The plays are still arriving from this account's data server.",
            )
        } else if (dashboard.sampled) {
            EmptyState(
                title = "No plays read",
                body = dashboard.readFailure?.let {
                    "The history stopped before any plays could be read. $it"
                } ?: "Only part of the history was read, and no dated plays arrived.",
            )
        } else if (undated > 0) {
            EmptyState(title = "No dated plays", body = undatedNotice(undated))
        } else {
            EmptyState(
                title = "No history",
                body = "This account has no dated plays in either teal.fm namespace.",
            )
        }
        return
    }

    val today = CivilDays.epochDay(nowEpochSeconds, tzOffsetMinutes)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        sections.forEach { section ->
            item(key = "day-${section.epochDay}") {
                Text(
                    text = RelativeTime.friendlyDay(section.epochDay, today),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                )
            }
            items(section.plays) { play ->
                PlayRow(play, nowEpochSeconds, tzOffsetMinutes)
            }
        }

        if (undated > 0) {
            item(key = "undated") {
                Text(
                    text = undatedNotice(undated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }

        if (dashboard.sampled) {
            item(key = "sampled") {
                Text(
                    text = if (reading) {
                        "Still reading the rest of the history."
                    } else {
                        dashboard.readFailure?.let { "Only part of the history was read. $it" }
                            ?: "Only part of the history was read."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
