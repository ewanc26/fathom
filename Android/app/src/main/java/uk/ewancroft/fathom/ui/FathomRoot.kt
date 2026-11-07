package uk.ewancroft.fathom.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import uk.ewancroft.fathom.FathomViewModel
import uk.ewancroft.fathom.data.FathomRepository
import uk.ewancroft.fathom.shared.format.CountText
import uk.ewancroft.fathom.ui.components.EmptyState
import uk.ewancroft.fathom.ui.components.Notice
import uk.ewancroft.fathom.ui.history.HistoryScreen
import uk.ewancroft.fathom.ui.now.NowScreen
import uk.ewancroft.fathom.ui.profile.ProfileScreen
import uk.ewancroft.fathom.ui.stats.StatsScreen
import uk.ewancroft.fathom.ui.theme.FathomTheme

private enum class Tab(val label: String) {
    Now("Now"),
    Stats("Stats"),
    History("History"),
    Profile("Profile"),
}

@Composable
fun FathomRoot(viewModel: FathomViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    FathomTheme {
        if (state.identity == null) {
            ConnectScreen(
                connecting = state.connecting,
                error = state.error,
                onConnect = viewModel::connect,
            )
        } else {
            Dashboard(state = state, viewModel = viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dashboard(state: FathomViewModel.UiState, viewModel: FathomViewModel) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tab = Tab.entries[selected.coerceIn(0, Tab.entries.lastIndex)]

    val dashboard = state.dashboard
    // One clock reading per fetched dashboard, so every relative time on the
    // screen agrees with every other.
    val now = remember(dashboard) { System.currentTimeMillis() / 1_000L }
    val tz = remember(dashboard) { FathomRepository.currentOffsetMinutes(now) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(tab.label) },
                actions = {
                    TextButton(
                        onClick = viewModel::refresh,
                        enabled = !state.loading,
                    ) { Text("Refresh") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, candidate ->
                    NavigationBarItem(
                        selected = index == selected,
                        onClick = { selected = index },
                        icon = {
                            Icon(
                                imageVector = when (candidate) {
                                    Tab.Now -> Icons.Filled.Home
                                    Tab.Stats -> Icons.Filled.Star
                                    Tab.History -> Icons.AutoMirrored.Filled.List
                                    Tab.Profile -> Icons.Filled.Person
                                },
                                contentDescription = candidate.label,
                            )
                        },
                        label = { Text(candidate.label) },
                    )
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            // Pulling down re-reads the repo from any tab, because the toolbar
            // action is easy to miss on a phone and a refresh should not mean
            // relaunching. The pull indicator is not held spinning for the
            // read: the bar below is the persistent one, and it carries a
            // count, which a spinner cannot.
            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    dashboard == null && state.loading -> {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }

                    dashboard == null -> {
                        // The copy invites a pull, and a scrollable child is
                        // what gives the gesture something to nest inside when
                        // there is no data to scroll.
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        ) {
                            EmptyState(
                                title = "Nothing loaded",
                                body = state.error ?: "Pull refresh to try again.",
                            )
                        }
                    }

                    else -> when (tab) {
                        Tab.Now -> NowScreen(dashboard, now, tz)
                        Tab.Stats -> StatsScreen(
                            dashboard = dashboard,
                            period = state.period,
                            reading = state.loading,
                            onSelectPeriod = viewModel::selectPeriod,
                        )

                        Tab.History -> HistoryScreen(
                            dashboard = dashboard,
                            nowEpochSeconds = now,
                            tzOffsetMinutes = tz,
                            reading = state.loading,
                        )
                        Tab.Profile -> ProfileScreen(
                            dashboard = dashboard,
                            onDisconnect = viewModel::disconnect,
                        )
                    }
                }
            }

            if (state.loading && dashboard != null && !state.readingStatusDismissed) {
                // A long read needs a quantity, not just motion: "reading" with
                // no count looks the same at four hundred plays as at twenty
                // thousand, and the difference is six seconds versus two
                // minutes.
                Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Reading history — " +
                                    "${CountText.compact(dashboard.plays.size)} plays so far",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = viewModel::dismissReadingStatus) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Dismiss reading status",
                                )
                            }
                        }
                    }
                }
            }

            state.error?.let { message ->
                Notice(
                    text = message,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
            }
        }
    }
}
