package com.stocktracker.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.Holding
import com.stocktracker.app.data.Quote
import com.stocktracker.app.data.SearchResult
import com.stocktracker.app.data.SectorWeight
import com.stocktracker.app.data.StockRepository
import com.stocktracker.app.data.WatchlistStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** État d'affichage d'une valeur suivie, avec la position détenue (quantité + PRU). */
data class TickerUi(
    val symbol: String,
    val quote: Quote? = null,
    val error: String? = null,
    val isLoading: Boolean = true,
    val quantity: Double = 0.0,
    val pru: Double = 0.0
)

data class UiState(
    val tickers: List<TickerUi> = emptyList(),
    val lastUpdateMillis: Long? = null,
    val isRefreshing: Boolean = false
)

/** Composition d'une position pour l'onglet dédié. */
data class CompositionUi(
    val isLoading: Boolean = true,
    val error: String? = null,
    val sectors: List<SectorWeight> = emptyList(),
    val countries: List<SectorWeight> = emptyList(),
    val countriesSource: String? = null,
    val location: String? = null
)

/** État de la recherche mondiale de valeurs. */
data class SearchState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null
)

class StockViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StockRepository()
    private val store = WatchlistStore(application)

    private val _uiState = MutableStateFlow(
        UiState(
            tickers = store.load().map {
                TickerUi(symbol = it.symbol, quantity = it.quantity, pru = it.pru)
            }
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _compositions = MutableStateFlow<Map<String, CompositionUi>>(emptyMap())
    val compositions: StateFlow<Map<String, CompositionUi>> = _compositions.asStateFlow()

    private var searchJob: Job? = null

    /**
     * Charge la composition (secteurs + localisation) des valeurs suivies.
     * Les compositions déjà chargées sont conservées ; les échecs sont retentés.
     */
    fun loadCompositions() {
        _uiState.value.tickers.forEach { ticker ->
            val existing = _compositions.value[ticker.symbol]
            if (existing != null && (existing.isLoading || existing.error == null)) return@forEach
            _compositions.update { it + (ticker.symbol to CompositionUi(isLoading = true)) }
            viewModelScope.launch {
                val name = _uiState.value.tickers
                    .find { it.symbol == ticker.symbol }?.quote?.name ?: ticker.symbol
                runCatching { repository.fetchComposition(ticker.symbol, name) }
                    .onSuccess { composition ->
                        _compositions.update {
                            it + (ticker.symbol to CompositionUi(
                                isLoading = false,
                                sectors = composition.sectors,
                                countries = composition.countries,
                                countriesSource = composition.countriesSource,
                                location = composition.location
                            ))
                        }
                    }
                    .onFailure { throwable ->
                        _compositions.update {
                            it + (ticker.symbol to CompositionUi(
                                isLoading = false,
                                error = throwable.message ?: "Composition indisponible"
                            ))
                        }
                    }
            }
        }
    }

    init {
        // Rafraîchissement automatique tant que le ViewModel est vivant.
        viewModelScope.launch {
            while (isActive) {
                refreshAll()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun refreshNow() {
        viewModelScope.launch { refreshAll() }
    }

    fun addSymbol(rawSymbol: String) {
        val symbol = rawSymbol.trim().uppercase()
        if (symbol.isEmpty()) return
        val current = _uiState.value.tickers
        if (current.any { it.symbol == symbol }) return

        val updated = current + TickerUi(symbol = symbol)
        _uiState.update { it.copy(tickers = updated) }
        persist(updated)
        refreshNow()
    }

    /** Met à jour la position détenue (quantité de parts et PRU) d'une valeur. */
    fun updateHolding(symbol: String, quantity: Double, pru: Double) {
        val updated = _uiState.value.tickers.map { ticker ->
            if (ticker.symbol == symbol) {
                ticker.copy(
                    quantity = quantity.coerceAtLeast(0.0),
                    pru = pru.coerceAtLeast(0.0)
                )
            } else {
                ticker
            }
        }
        _uiState.update { it.copy(tickers = updated) }
        persist(updated)
    }

    /** Recherche par nom ou symbole sur toutes les bourses (avec anti-rebond). */
    fun onSearchQueryChange(query: String) {
        _searchState.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _searchState.update { it.copy(results = emptyList(), isSearching = false, error = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _searchState.update { it.copy(isSearching = true) }
            runCatching { repository.searchSymbols(query.trim()) }
                .onSuccess { results ->
                    _searchState.update {
                        it.copy(results = results, isSearching = false, error = null)
                    }
                }
                .onFailure { throwable ->
                    _searchState.update {
                        it.copy(
                            isSearching = false,
                            error = throwable.message ?: "Erreur réseau"
                        )
                    }
                }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchState.value = SearchState()
    }

    fun removeSymbol(symbol: String) {
        val updated = _uiState.value.tickers.filterNot { it.symbol == symbol }
        _uiState.update { it.copy(tickers = updated) }
        persist(updated)
    }

    private fun persist(tickers: List<TickerUi>) {
        store.save(tickers.map { Holding(it.symbol, it.quantity, it.pru) })
    }

    private suspend fun refreshAll() {
        val symbols = _uiState.value.tickers.map { it.symbol }
        if (symbols.isEmpty()) {
            _uiState.update { it.copy(isRefreshing = false) }
            return
        }
        _uiState.update { it.copy(isRefreshing = true) }

        val results = coroutineScope {
            symbols.map { symbol ->
                async {
                    symbol to runCatching { repository.fetchQuote(symbol) }
                }
            }.awaitAll()
        }.toMap()

        _uiState.update { state ->
            val tickers = state.tickers.map { ticker ->
                val result = results[ticker.symbol] ?: return@map ticker
                result.fold(
                    onSuccess = { quote ->
                        ticker.copy(quote = quote, error = null, isLoading = false)
                    },
                    onFailure = { throwable ->
                        // On garde la dernière cotation connue en cas d'échec ponctuel.
                        ticker.copy(
                            error = throwable.message ?: "Erreur réseau",
                            isLoading = false
                        )
                    }
                )
            }
            state.copy(
                tickers = tickers,
                lastUpdateMillis = System.currentTimeMillis(),
                isRefreshing = false
            )
        }
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 15_000L
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}
