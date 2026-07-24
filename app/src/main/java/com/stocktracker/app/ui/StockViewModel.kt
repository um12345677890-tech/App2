package com.stocktracker.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.Quote
import com.stocktracker.app.data.StockRepository
import com.stocktracker.app.data.WatchlistStore
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

/** État d'affichage d'une valeur suivie. */
data class TickerUi(
    val symbol: String,
    val quote: Quote? = null,
    val error: String? = null,
    val isLoading: Boolean = true
)

data class UiState(
    val tickers: List<TickerUi> = emptyList(),
    val lastUpdateMillis: Long? = null,
    val isRefreshing: Boolean = false
)

class StockViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StockRepository()
    private val store = WatchlistStore(application)

    private val _uiState = MutableStateFlow(
        UiState(tickers = store.load().map { TickerUi(symbol = it) })
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

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
        store.save(updated.map { it.symbol })
        refreshNow()
    }

    fun removeSymbol(symbol: String) {
        val updated = _uiState.value.tickers.filterNot { it.symbol == symbol }
        _uiState.update { it.copy(tickers = updated) }
        store.save(updated.map { it.symbol })
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
    }
}
