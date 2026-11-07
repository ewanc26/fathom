package uk.ewancroft.fathom

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
        viewModelScope.launch {
            try {
                val dashboard = repository.load(trimmed, _state.value.period)
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
            } catch (failure: FathomException) {
                _state.update { it.copy(connecting = false, error = failure.message) }
            } catch (failure: Exception) {
                _state.update {
                    it.copy(
                        connecting = false,
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
                val dashboard = repository.load(identity, _state.value.period)
                _state.update { it.copy(dashboard = dashboard, loading = false, error = null) }
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
        _state.update { it.copy(period = period) }
        // The period only changes the aggregation, not what is fetched, but
        // re-running the fetch keeps one path through the repository.
        refresh()
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
