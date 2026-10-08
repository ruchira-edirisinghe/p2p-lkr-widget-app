package dev.dfanso.lkrp2p.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dfanso.lkrp2p.data.FxQuote
import dev.dfanso.lkrp2p.data.FxRange
import dev.dfanso.lkrp2p.data.FxRepository
import dev.dfanso.lkrp2p.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FxUiState(
    val from: String,
    val to: String,
    val range: FxRange = FxRange.MONTH,
    val amountText: String = "1",
    val quote: FxQuote? = null,
    val favorites: List<FxQuote> = emptyList(),
    val currencies: List<Pair<String, String>> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val amount: Double? get() = amountText.replace(",", "").toDoubleOrNull()
    val pairKey: String get() = "$from/$to"
}

/** The Currencies tab: a converter for any pair, its chart and a favourites list. */
class FxViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings.get(app)
    private val repo = FxRepository.get(app)

    private val _state = MutableStateFlow(FxUiState(from = settings.fxFrom, to = settings.fxTo))
    val state: StateFlow<FxUiState> = _state.asStateFlow()

    init {
        reload()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.refreshIfDue(maxAgeSec = 3_600) }
            reload()
            backfill()
        }
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val failed = withContext(Dispatchers.IO) { runCatching { repo.refresh() }.isFailure }
            _state.update {
                it.copy(loading = false, error = if (failed) "Couldn't update rates. Showing the last saved ones." else null)
            }
            reload()
            backfill()
        }
    }

    fun setPair(from: String, to: String) {
        settings.fxFrom = from
        settings.fxTo = to
        _state.update { it.copy(from = from, to = to) }
        reload()
    }

    fun swap() = setPair(_state.value.to, _state.value.from)

    fun setRange(range: FxRange) {
        _state.update { it.copy(range = range) }
        reload()
        viewModelScope.launch { backfill() }
    }

    fun setAmount(text: String) {
        _state.update { it.copy(amountText = text.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(14)) }
    }

    fun toggleFavorite() {
        val key = _state.value.pairKey
        val current = settings.fxFavorites
        settings.fxFavorites = if (key in current) current - key else listOf(key) + current
        reload()
    }

    fun removeFavorite(key: String) {
        settings.fxFavorites = settings.fxFavorites - key
        reload()
    }

    /** Every stored day of the current pair as CSV. */
    suspend fun csv(): String = withContext(Dispatchers.IO) {
        val s = _state.value
        buildString {
            appendLine("date,${s.from.uppercase()}_${s.to.uppercase()}")
            repo.history(s.from, s.to).forEach { (date, rate) -> appendLine("$date,$rate") }
        }
    }

    private suspend fun backfill() {
        withContext(Dispatchers.IO) { repo.backfill(_state.value.range) }
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            val s = _state.value
            val (quote, favorites, currencies) = withContext(Dispatchers.IO) {
                Triple(
                    repo.quote(s.from, s.to, s.range),
                    settings.fxFavorites.map { key ->
                        val (f, t) = key.split("/")
                        repo.quote(f, t, FxRange.WEEK)
                    },
                    repo.currencies(),
                )
            }
            _state.update { it.copy(quote = quote, favorites = favorites, currencies = currencies) }
        }
    }

    fun name(code: String) = repo.name(code)
}
