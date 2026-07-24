package com.stocktracker.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Position détenue sur une valeur : quantité de parts et PRU (prix de revient unitaire). */
data class Holding(
    val symbol: String,
    val quantity: Double = 0.0,
    val pru: Double = 0.0
)

/**
 * Persistance de la liste de valeurs suivies et des positions (quantité + PRU)
 * dans les SharedPreferences, au format JSON.
 */
class WatchlistStore(context: Context) {

    private val prefs = context.getSharedPreferences("watchlist", Context.MODE_PRIVATE)

    fun load(): List<Holding> {
        val json = prefs.getString(KEY_HOLDINGS, null)
        if (json != null) {
            return runCatching { parseHoldings(json) }.getOrDefault(defaultHoldings())
        }
        // Migration depuis l'ancien format (liste de symboles séparés par des virgules).
        val legacy = prefs.getString(KEY_SYMBOLS_LEGACY, null)
        if (legacy != null) {
            return legacy.split(',').filter { it.isNotBlank() }.map { Holding(symbol = it) }
        }
        return defaultHoldings()
    }

    fun save(holdings: List<Holding>) {
        val array = JSONArray()
        holdings.forEach { holding ->
            array.put(
                JSONObject()
                    .put("symbol", holding.symbol)
                    .put("quantity", holding.quantity)
                    .put("pru", holding.pru)
            )
        }
        prefs.edit()
            .putString(KEY_HOLDINGS, array.toString())
            .remove(KEY_SYMBOLS_LEGACY)
            .apply()
    }

    private fun parseHoldings(json: String): List<Holding> {
        val array = JSONArray(json)
        val holdings = mutableListOf<Holding>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val symbol = item.optString("symbol")
            if (symbol.isBlank()) continue
            holdings.add(
                Holding(
                    symbol = symbol,
                    quantity = item.optDouble("quantity", 0.0),
                    pru = item.optDouble("pru", 0.0)
                )
            )
        }
        return holdings
    }

    private fun defaultHoldings() = DEFAULT_SYMBOLS.map { Holding(symbol = it) }

    companion object {
        private const val KEY_HOLDINGS = "holdings"
        private const val KEY_SYMBOLS_LEGACY = "symbols"
        val DEFAULT_SYMBOLS = listOf("WPEA.PA", "PAEEM.PA")
    }
}
