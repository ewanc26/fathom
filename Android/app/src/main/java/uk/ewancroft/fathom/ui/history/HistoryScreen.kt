package uk.ewancroft.fathom.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.ewancroft.fathom.data.Dashboard
import uk.ewancroft.fathom.shared.format.RelativeTime
import uk.ewancroft.fathom.shared.model.Play
import uk.ewancroft.fathom.shared.search.PlaySearchIndex
import uk.ewancroft.fathom.shared.time.CivilDate
import uk.ewancroft.fathom.shared.time.CivilDays
import uk.ewancroft.fathom.shared.time.IsoInstant
import uk.ewancroft.fathom.ui.components.EmptyState
import uk.ewancroft.fathom.ui.components.PlayRow

/** One day's plays, already ordered newest first. */
private data class DaySection(val epochDay: Long, val plays: List<Play>)

/** A month that has at least one dated play, newest first. */
private data class MonthKey(val year: Int, val month: Int) {
    fun label(): String = "${RelativeTime.monthName(month)} $year"
}

/**
 * Every play Fathom read, grouped by the day the listener had, with a local
 * search and a jump to any month that appears in the history.
 *
 * The grouping uses the device's UTC offset rather than the record's own, so
 * a play at 00:30 local appears under today rather than under yesterday
 * because the timestamp happened to be UTC. Search never reads the network:
 * it filters the plays already in memory, and the empty state says so.
 */
@Composable
fun HistoryScreen(
    dashboard: Dashboard,
    nowEpochSeconds: Long,
    tzOffsetMinutes: Int,
    reading: Boolean,
) {
    var query by rememberSaveable(dashboard.did) { mutableStateOf("") }
    val index = remember(dashboard.plays) { PlaySearchIndex(dashboard.plays) }
    val matches = remember(index, query) { index.search(query) }

    val dated = remember(matches, tzOffsetMinutes) {
        matches.mapNotNull { play -> IsoInstant.parse(play.playedTime)?.let { play to it } }
    }
    val sections = remember(dated, tzOffsetMinutes) {
        dated
            .groupBy { (_, instant) -> CivilDays.epochDay(instant, tzOffsetMinutes) }
            .toSortedMap(compareByDescending { it })
            .map { (day, entries) -> DaySection(day, entries.map { it.first }) }
    }
    val undated = remember(matches) {
        matches.filter { IsoInstant.parse(it.playedTime) == null }
    }
    val months = remember(sections) {
        sections
            .map {
                val date = CivilDate.fromEpochDay(it.epochDay)
                MonthKey(date.year, date.month)
            }
            .distinct()
    }

    if (dashboard.plays.isEmpty()) {
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
        } else {
            EmptyState(
                title = "No history",
                body = "This account has no plays in either teal.fm namespace.",
            )
        }
        return
    }

    val today = CivilDays.epochDay(nowEpochSeconds, tzOffsetMinutes)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var monthMenuOpen by remember { mutableStateOf(false) }

    // A new query starts at the newest result rather than leaving the list
    // scrolled to a position that belonged to the previous query.
    LaunchedEffect(query) {
        listState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search track, artist or release") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    query.isBlank() -> "${dashboard.plays.size} plays"
                    matches.size == 1 -> "1 match"
                    else -> "${matches.size} matches"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (months.isNotEmpty()) {
                Box {
                    TextButton(onClick = { monthMenuOpen = true }) {
                        Text("Jump to month")
                    }
                    DropdownMenu(
                        expanded = monthMenuOpen,
                        onDismissRequest = { monthMenuOpen = false },
                    ) {
                        months.forEach { month ->
                            DropdownMenuItem(
                                text = { Text(month.label()) },
                                onClick = {
                                    monthMenuOpen = false
                                    val section = sections.indexOfFirst {
                                        val date = CivilDate.fromEpochDay(it.epochDay)
                                        date.year == month.year && date.month == month.month
                                    }
                                    if (section >= 0) {
                                        scope.launch {
                                            listState.animateScrollToItem(
                                                sectionStart(sections, section),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        if (sections.isEmpty() && undated.isEmpty()) {
            EmptyState(
                title = "No plays match",
                body = "Try a track, an artist or a release name.",
            )
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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

            if (undated.isNotEmpty()) {
                item(key = "undated-header") {
                    Text(
                        text = "No date",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                    )
                }
                items(undated) { play ->
                    PlayRow(play, nowEpochSeconds, tzOffsetMinutes)
                }
                item(key = "undated-notice") {
                    Text(
                        text = if (undated.size == 1) {
                            "1 play has no readable timestamp."
                        } else {
                            "${undated.size} plays have no readable timestamp."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
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
}

/** The LazyColumn position of a day header, counting the header before each section. */
private fun sectionStart(sections: List<DaySection>, index: Int): Int {
    var position = 0
    for (earlier in 0 until index) {
        position += 1 + sections[earlier].plays.size
    }
    return position
}
