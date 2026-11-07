package uk.ewancroft.fathom

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.ewancroft.fathom.data.Dashboard
import uk.ewancroft.fathom.data.FathomException
import uk.ewancroft.fathom.data.FathomRepository
import uk.ewancroft.fathom.data.IdentityStore
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
        val connecting: Boolean = false,
        val dashboard: Dashboard? = null,
        val error: String? = null,
    )

    private val store = IdentityStore(application)
    private val repository = FathomRepository()
    private val _state = MutableStateFlow(
        UiState(identity = store.identity(), period = store.period()),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        if (_state.value.identity != null) refresh()
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

        _state.update { it.copy(connecting = true, error = null) }
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
                                error = null,
                            )
                        }
                    },
                )
                // A history with no plays never reports progress, so the
                // identity is saved here as well as in the callback.
                store.saveIdentity(trimmed)
                _state.update {
                    it.copy(
                        identity = trimmed,
                        dashboard = dashboard,
                        connecting = false,
                        loading = false,
                        error = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                // A newer load superseded this one; cancelling is not a
                // failure and must not leave an error on screen.
                throw cancellation
            } catch (failure: FathomException) {
                _state.update {
                    it.copy(connecting = false, loading = false, error = failure.message)
                }
            } catch (failure: Exception) {
                _state.update {
                    it.copy(
                        connecting = false,
                        loading = false,
                        error = "Could not load that account. ${failure.message.orEmpty()}".trim(),
                    )
                }
            }
        }
    }

    fun refresh() {
        val identity = _state.value.identity ?: return
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val dashboard = repository.load(
                    identity = identity,
                    period = _state.value.period,
                    onProgress = { partial ->
                        // Keep the screens on the newest complete picture while
                        // the rest of the history arrives.
                        _state.update { it.copy(dashboard = partial, loading = true) }
                    },
                )
                _state.update { it.copy(dashboard = dashboard, loading = false, error = null) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: FathomException) {
                _state.update { it.copy(loading = false, error = failure.message) }
            } catch (failure: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "Could not refresh. ${failure.message.orEmpty()}".trim(),
                    )
                }
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

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun disconnect() {
        loadJob?.cancel()
        store.clearIdentity()
        _state.value = UiState(identity = null, period = _state.value.period)
    }
}
