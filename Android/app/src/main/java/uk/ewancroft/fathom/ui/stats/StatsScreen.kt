package uk.ewancroft.fathom.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.ewancroft.fathom.data.Dashboard
import uk.ewancroft.fathom.shared.auth.Provenance
import uk.ewancroft.fathom.shared.format.CountText
import uk.ewancroft.fathom.shared.format.DurationText
import uk.ewancroft.fathom.shared.format.RelativeTime
import uk.ewancroft.fathom.shared.format.ServiceNames
import uk.ewancroft.fathom.shared.stats.Period
import uk.ewancroft.fathom.shared.time.CivilDays
import uk.ewancroft.fathom.ui.components.EmptyState
import uk.ewancroft.fathom.ui.components.Notice
import uk.ewancroft.fathom.ui.components.Panel
import uk.ewancroft.fathom.ui.components.RankRow
import uk.ewancroft.fathom.ui.components.StatTile

/**
 * The period's figures, with where they came from stated underneath.
 *
 * The provenance line is not decoration. A teal.fm AppView would report
 * network-wide top artists; Fathom reports what is in this repo, and the two
 * are different numbers that both legitimately call themselves "top artists".
 */
@Composable
fun StatsScreen(
    dashboard: Dashboard,
    period: Period,
    reading: Boolean,
    nowEpochSeconds: Long,
    tzOffsetMinutes: Int,
    onSelectPeriod: (Period) -> Unit,
) {
    val stats = dashboard.stats
    val today = CivilDays.epochDay(nowEpochSeconds, tzOffsetMinutes)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Period.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == period,
                    onClick = { onSelectPeriod(candidate) },
                    label = { Text(candidate.label) },
                )
            }
        }

        Panel {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        value = CountText.compact(stats.playCount),
                        label = "plays",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = DurationText.listening(stats.listenedSeconds),
                        label = "in tracks",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        value = CountText.compact(stats.uniqueArtists),
                        label = "artists",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = CountText.compact(stats.uniqueReleases),
                        label = "releases",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = CountText.compact(stats.uniqueTracks),
                        label = "tracks",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        stats.busiestDay?.let { busiest ->
            Panel {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        value = busiest.playCount.toString(),
                        label = "on the busiest day",
                        detail = RelativeTime.friendlyDay(busiest.epochDay, today),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = ServiceNames.label(stats.topService) ?: "—",
                        label = "most used service",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Panel {
            Column {
                Text(
                    text = "Top artists",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                if (stats.topArtists.isEmpty()) {
                    EmptyState("No artists", "Nothing was played in this period.")
                } else {
                    val leader = stats.topArtists.first().playCount.coerceAtLeast(1)
                    stats.topArtists.forEachIndexed { index, artist ->
                        RankRow(
                            rank = index + 1,
                            title = artist.name,
                            subtitle = null,
                            playCount = artist.playCount,
                            fraction = artist.playCount.toFloat() / leader,
                        )
                    }
                }
            }
        }

        Panel {
            Column {
                Text(
                    text = "Top releases",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                if (stats.topReleases.isEmpty()) {
                    EmptyState(
                        "No releases",
                        "The plays in this period name no release.",
                    )
                } else {
                    val leader = stats.topReleases.first().playCount.coerceAtLeast(1)
                    stats.topReleases.forEachIndexed { index, release ->
                        RankRow(
                            rank = index + 1,
                            title = release.name,
                            subtitle = null,
                            playCount = release.playCount,
                            fraction = release.playCount.toFloat() / leader,
                        )
                    }
                }
            }
        }

        Panel {
            Column {
                Text(
                    text = "Top tracks",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                if (stats.topTracks.isEmpty()) {
                    EmptyState("No tracks", "Nothing was played in this period.")
                } else {
                    val leader = stats.topTracks.first().playCount.coerceAtLeast(1)
                    stats.topTracks.forEachIndexed { index, track ->
                        RankRow(
                            rank = index + 1,
                            title = track.name,
                            subtitle = track.artist,
                            playCount = track.playCount,
                            fraction = track.playCount.toFloat() / leader,
                        )
                    }
                }
            }
        }

        provenance(dashboard, reading)
    }
}

@Composable
private fun provenance(dashboard: Dashboard, reading: Boolean) {
    val notes = buildList {
        add(Provenance.COMPUTED_LOCALLY)
        if (dashboard.sampled) {
            add(
                if (reading) {
                    "Still reading the rest of the history; these figures will grow"
                } else {
                    "Only part of the history was read, so this is not a complete total"
                },
            )
        }
        if (dashboard.skippedRecords > 0) {
            add("${dashboard.skippedRecords} unreadable ${if (dashboard.skippedRecords == 1) "record was" else "records were"} skipped")
        }
        if (dashboard.stats.undatedPlays > 0) {
            val undated = dashboard.stats.undatedPlays
            add(
                if (undated == 1) {
                    "1 play has no readable timestamp; it cannot be dated or drawn"
                } else {
                    "$undated plays have no readable timestamp; they cannot be dated or drawn"
                },
            )
        }
        if (dashboard.usedLegacyNamespace) {
            add("Includes records from the older ${Provenance.LEGACY_NAMESPACE} namespace")
        }
    }
    Notice(notes.joinToString(". ") + ".")
}
