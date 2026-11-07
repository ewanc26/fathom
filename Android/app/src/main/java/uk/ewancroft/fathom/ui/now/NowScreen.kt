package uk.ewancroft.fathom.ui.now

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uk.ewancroft.fathom.data.Dashboard
import uk.ewancroft.fathom.shared.format.DurationText
import uk.ewancroft.fathom.shared.format.RelativeTime
import uk.ewancroft.fathom.shared.model.TealStatus
import uk.ewancroft.fathom.shared.stats.DayBucket
import uk.ewancroft.fathom.shared.time.IsoInstant
import uk.ewancroft.fathom.ui.components.EmptyState
import uk.ewancroft.fathom.ui.components.Panel
import uk.ewancroft.fathom.ui.components.PlayRow
import uk.ewancroft.fathom.ui.components.StatTile

/**
 * The dashboard's opening screen: what is playing, what today has been, and
 * how the last fortnight looks.
 *
 * Today's figures come from the last bucket of the fourteen-day series, not
 * from a second aggregation: two paths to the same number is how a screen
 * ends up contradicting itself.
 */
@Composable
fun NowScreen(
    dashboard: Dashboard,
    nowEpochSeconds: Long,
    tzOffsetMinutes: Int,
) {
    val today = dashboard.stats.recentDays.lastOrNull()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NowPlayingCard(dashboard, nowEpochSeconds, tzOffsetMinutes)

        Panel {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatTile(
                    value = (today?.playCount ?: 0).toString(),
                    label = "plays today",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = DurationText.listening(today?.seconds ?: 0L),
                    label = "time today",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = dashboard.stats.currentStreakDays.toString(),
                    label = "day streak",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Panel {
            Column {
                Text(
                    text = "Last 14 days",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (dashboard.stats.hasData) {
                        "${dashboard.stats.playCount} plays in ${periodWord(dashboard)}"
                    } else {
                        "No plays in this period yet"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Chart(dashboard.stats.recentDays)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = dashboard.stats.recentDays.firstOrNull()?.let {
                            RelativeTime.shortDate(
                                it.epochDay,
                                dashboard.stats.recentDays.last().epochDay,
                            )
                        }.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "today",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Panel {
            Column {
                Text(
                    text = "Recently played",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (dashboard.plays.isEmpty()) {
                    EmptyState(
                        title = "Nothing yet",
                        body = "This account has no teal.fm plays in either namespace.",
                    )
                } else {
                    dashboard.plays.take(4).forEach { play ->
                        PlayRow(play, nowEpochSeconds, tzOffsetMinutes)
                    }
                }
            }
        }
    }
}

/** The status record, when there is one worth showing. */
@Composable
private fun NowPlayingCard(
    dashboard: Dashboard,
    nowEpochSeconds: Long,
    tzOffsetMinutes: Int,
) {
    val status: TealStatus? = dashboard.status
    val live = status != null &&
        status.isListening(nowEpochSeconds, IsoInstant.parse(status.expiry))
    val item = status?.item

    Panel {
        Column {
            Text(
                text = if (live) "NOW PLAYING" else "NOT PLAYING",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (live) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(8.dp))

            if (live && item != null) {
                Text(
                    text = item.title(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = item.artistLine(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IsoInstant.parse(status.time)?.let { since ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "since ${RelativeTime.clock(since, tzOffsetMinutes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = "Nothing is playing right now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "A status record appears here while a tracker is running.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Bars for the recent days.
 *
 * An empty day is drawn as a short stub in the muted colour rather than as
 * nothing, so a gap reads as zero plays instead of a missing bar.
 */
@Composable
private fun Chart(days: List<DayBucket>) {
    if (days.isEmpty()) return
    val busiest = days.maxOf { it.playCount }.coerceAtLeast(1)

    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        days.forEach { day ->
            val height = 6 + (54 * (day.playCount.toFloat() / busiest)).toInt()
            Box(
                Modifier
                    .weight(1f)
                    .height(height.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (day.playCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
            )
        }
    }
}

private fun periodWord(dashboard: Dashboard): String =
    if (dashboard.stats.playCount == 1) "1 play total" else "this period"
