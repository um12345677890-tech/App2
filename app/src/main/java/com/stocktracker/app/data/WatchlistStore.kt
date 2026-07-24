package com.stocktracker.app.data

import android.content.Context

/**
 * Persistance de la liste de valeurs suivies dans les SharedPreferences.
 */
class WatchlistStore(context: Context) {

    private val prefs = context.getSharedPreferences("watchlist", Context.MODE_PRIVATE)

    fun load(): List<String> {
        val stored = prefs.getString(KEY_SYMBOLS, null)
        return stored?.split(',')?.filter { it.isNotBlank() } ?: DEFAULT_SYMBOLS
    }

    fun save(symbols: List<String>) {
        prefs.edit().putString(KEY_SYMBOLS, symbols.joinToString(",")).apply()
    }

    companion object {
        private const val KEY_SYMBOLS = "symbols"
        val DEFAULT_SYMBOLS = listOf("WPEA.PA", "PAEEM.PA")
    }
}
