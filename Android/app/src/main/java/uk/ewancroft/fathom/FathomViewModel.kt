package uk.ewancroft.fathom

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.ewancroft.fathom.data.Dashboard
import uk.ewancroft.fathom.data.FathomException
import uk.ewancroft.fathom.data.FathomRepository
import uk.ewancroft.fathom.data.HistoryCacheStore
import uk.ewancroft.fathom.data.IdentityStore
import uk.ewancroft.fathom.shared.cache.CachedHistory
import uk.ewancroft.fathom.shared.model.ReadCheckpoint
import uk.ewancroft.fathom.shared.stats.Period

/**
 * The single screen-scoped owner of dashboard state.
 *
 * One state holder rather than one per tab: every tab reads the same fetched
 * plays, and letting each tab re-fetch would mean four different answers to
 * "how many plays this week" on the same screen.
 */
class FathomViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val identity: String? = null,
        val period: Period = Period.ThirtyDays,
        val loading: Boolean = false,
        /** True only once a read has actually published progress worth showing. */
        val readingHistory: Boolean = false,
        /** True when the reading banner has been hidden for the current read. */
        val readingStatusDismissed: Boolean = false,
        val connecting: Boolean = false,
        val dashboard: Dashboard? = null,
        val error: String? = null,
    )

    private val store = IdentityStore(application)
    private val cache = HistoryCacheStore(application)
    private val repository = FathomRepository()
    private val _state = MutableStateFlow(
        UiState(identity = store.identity(), period = store.period()),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var statusJob: Job? = null

    init {
        val identity = _state.value.identity
        if (identity != null) {
            // The saved copy paints the dashboard at once; the repo read that
            // refresh starts replaces it page by page. Painted from a
            // background coroutine because decoding tens of thousands of
            // plays is not main-thread work.
            viewModelScope.launch {
                val restored = withContext(Dispatchers.IO) {
                    cache.read(identity)?.let { repository.fromCache(it, _state.value.period) }
                }
                if (restored != null) {
                    // Replace a smaller picture but never a larger one: the
                    // network may already have published its first (empty)
                    // page while this was decoding.
                    _state.update { state ->
                        val current = state.dashboard
                        if (current == null || current.plays.size < restored.plays.size) {
                            state.copy(dashboard = restored)
                        } else {
                            state
                        }
                    }
                }
            }
            refresh()
        }
    }

    /**
     * Loads [raw] and, only once that worked, remembers it.
     *
     * Saving first would leave a typo in the preferences forever, with the
     * app reopening onto an error the person cannot clear except by signing
     * out.
     */
    fun connect(raw: String) {
        val trimmed = raw.trim().removePrefix("@")
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = "Enter a handle or a DID.") }
            return
        }

        _state.update {
            it.copy(
                connecting = true,
                readingHistory = false,
                readingStatusDismissed = false,
                error = null,
            )
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            var saved = false
            try {
                val dashboard = repository.load(
                    identity = trimmed,
                    period = _state.value.period,
                    onProgress = { partial ->
                        // The account is real as soon as its records decode, so
                        // open the dashboard before the history has finished
                        // arriving: twenty thousand plays is minutes of pages.
                        if (!saved) {
                            saved = true
                            store.saveIdentity(trimmed)
                        }
                        _state.update {
                            it.copy(
                                identity = trimmed,
                                dashboard = partial,
                                connecting = false,
                                loading = true,
                                readingHistory = true,
                                error = null,
                            )
                        }
                    },
                )
                // A history with no plays never reports progress, so the
                // identity is saved here as well as in the callback.
                store.saveIdentity(trimmed)
                // Saved even when partial: the flag travels with it, so the
                // next launch draws the same plays with the same label rather
                // than pretending it has nothing to show.
                withContext(Dispatchers.IO) {
                    cache.write(
                        repository.toCache(
                            identity = trimmed,
                            dashboard = dashboard,
                            savedAtEpochSeconds = System.currentTimeMillis() / 1_000L,
                        ),
                    )
                }
                _state.update {
                    it.copy(
                        identity = trimmed,
                        dashboard = dashboard,
                        connecting = false,
                        loading = false,
                        readingHistory = false,
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                // A newer load superseded this one; cancelling is not a
                // failure and must not leave an error on screen.
                throw cancellation
            } catch (failure: FathomException) {
                _state.update {
                    it.copy(
                        connecting = false,
                        loading = false,
                        readingHistory = false,
                        error = failure.message,
                    )
                }
            } catch (failure: Exception) {
                _state.update {
                    it.copy(
                        connecting = false,
                        loading = false,
                        readingHistory = false,
                        error = "Could not load that account. ${failure.message.orEmpty()}".trim(),
                    )
                }
            }
        }
    }

    fun refresh() = startRefresh(forceFull = false)

    /** Re-reads the whole history, picking up edits, deletions and backfills. */
    fun refreshEverything() = startRefresh(forceFull = true)

    private fun startRefresh(forceFull: Boolean) {
        val identity = _state.value.identity ?: return
        loadJob?.cancel()
        _state.update {
            it.copy(
                loading = true,
                readingHistory = false,
                readingStatusDismissed = false,
                error = null,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val checkpoint = checkpoint(identity, forceFull)
                val dashboard = repository.load(
                    identity = identity,
                    period = _state.value.period,
                    checkpoint = checkpoint,
                    forceFull = forceFull,
                    onProgress = { partial ->
                        // Keep the screens on the newest complete picture while
                        // the rest of the history arrives. The first publish
                        // has no plays yet; letting it through would blank a
                        // saved fifty-thousand-play snapshot at once.
                        _state.update { state ->
                            val current = state.dashboard
                            if (current != null && partial.plays.size < current.plays.size) {
                                state.copy(loading = true, readingHistory = true)
                            } else {
                                state.copy(
                                    dashboard = partial,
                                    loading = true,
                                    readingHistory = true,
                                )
                            }
                        }
                    },
                )
                withContext(Dispatchers.IO) {
                    cache.write(
                        repository.toCache(
                            identity = identity,
                            dashboard = dashboard,
                            savedAtEpochSeconds = System.currentTimeMillis() / 1_000L,
                        ),
                    )
                }
                _state.update {
                    it.copy(
                        dashboard = dashboard,
                        loading = false,
                        readingHistory = false,
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: FathomException) {
                _state.update {
                    it.copy(loading = false, readingHistory = false, error = failure.message)
                }
            } catch (failure: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        readingHistory = false,
                        error = "Could not refresh. ${failure.message.orEmpty()}".trim(),
                    )
                }
            }
        }
    }

    private suspend fun checkpoint(identity: String, forceFull: Boolean): ReadCheckpoint? {
        if (forceFull) return null
        _state.value.dashboard?.let { return it.toCheckpoint() }
        return withContext(Dispatchers.IO) { cache.read(identity)?.toCheckpoint() }
    }

    /**
     * Re-reads the status record once a minute while the dashboard is
     * resumed.
     *
     * A status record outlives its expiry, so without this the opening card
     * would go on naming a track long after it stopped. Only the status is
     * re-read: plays and figures stay where they are.
     */
    fun startStatusPolling() {
        if (statusJob?.isActive == true) return
        statusJob = viewModelScope.launch {
            refreshStatus()
            while (isActive) {
                delay(STATUS_REFRESH_MILLIS)
                refreshStatus()
            }
        }
    }

    fun stopStatusPolling() {
        statusJob?.cancel()
        statusJob = null
    }

    private suspend fun refreshStatus() {
        val current = _state.value
        val dashboard = current.dashboard ?: return
        if (current.loading || current.connecting) return

        val refreshed = try {
            repository.refreshStatus(dashboard)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // A failed poll is not a dashboard failure: keep the last status
            // and try again in a minute.
            return
        }

        _state.update { state ->
            val latest = state.dashboard
            if (latest == null || latest.did != dashboard.did) {
                state
            } else {
                state.copy(
                    dashboard = latest.copy(pds = refreshed.pds, status = refreshed.status),
                )
            }
        }
    }

    fun selectPeriod(period: Period) {
        if (period == _state.value.period) return
        store.savePeriod(period)

        val loaded = _state.value.dashboard
        if (loaded == null) {
            _state.update { it.copy(period = period) }
            refresh()
            return
        }

        // The period changes the aggregation, not the records. The plays are
        // already in memory, and re-reading a history that can now run to
        // thousands of them would make a question about figures we hold cost
        // a minute to answer.
        _state.update {
            it.copy(period = period, dashboard = repository.rerange(loaded, period))
        }
    }

    /** Hides the reading banner without stopping the read. */
    fun dismissReadingStatus() {
        _state.update { it.copy(readingStatusDismissed = true) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun disconnect() {
        stopStatusPolling()
        loadJob?.cancel()
        cache.clear()
        store.clearIdentity()
        _state.value = UiState(identity = null, period = _state.value.period)
    }

    private companion object {
        /** One small record read a minute: enough for a ten-minute status to age out. */
        const val STATUS_REFRESH_MILLIS = 60_000L
    }
}

private fun Dashboard.toCheckpoint(): ReadCheckpoint = ReadCheckpoint(
    did = did,
    plays = plays,
    skipped = skippedRecords,
    legacy = usedLegacyNamespace,
    complete = !sampled,
)

private fun CachedHistory.toCheckpoint(): ReadCheckpoint = ReadCheckpoint(
    did = did,
    plays = plays,
    skipped = skipped,
    legacy = legacy,
    complete = !sampled,
)
