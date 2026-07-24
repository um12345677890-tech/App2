package com.stocktracker.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cache disque des dernières cotations connues, pour afficher instantanément les
 * cartes au lancement au lieu de spinners, en attendant le premier rafraîchissement.
 */
class QuoteCache(context: Context) {

    private val prefs = context.getSharedPreferences("quote_cache", Context.MODE_PRIVATE)

    fun save(quotes: Collection<Quote>, updatedAtMillis: Long) {
        val array = JSONArray()
        quotes.forEach { quote ->
            array.put(
                JSONObject()
                    .put("symbol", quote.symbol)
                    .put("name", quote.name)
                    .put("exchange", quote.exchange)
                    .put("currency", quote.currency)
                    .put("price", quote.price)
                    .put("previousClose", quote.previousClose)
                    .put("dayHigh", if (quote.dayHigh.isNaN()) JSONObject.NULL else quote.dayHigh)
                    .put("dayLow", if (quote.dayLow.isNaN()) JSONObject.NULL else quote.dayLow)
                    .put("marketState", quote.marketState)
                    .put("marketTimeSeconds", quote.marketTimeSeconds)
                    .put("sparkline", JSONArray(quote.sparkline.map { it.toDouble() }))
            )
        }
        prefs.edit()
            .putString(KEY_QUOTES, array.toString())
            .putLong(KEY_UPDATED_AT, updatedAtMillis)
            .apply()
    }

    /** Dernières cotations par symbole, ou map vide si aucun cache. */
    fun load(): Pair<Map<String, Quote>, Long?> {
        val json = prefs.getString(KEY_QUOTES, null) ?: return emptyMap<String, Quote>() to null
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L).takeIf { it > 0L }
        return runCatching {
            val array = JSONArray(json)
            val quotes = mutableMapOf<String, Quote>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val symbol = item.optString("symbol")
                if (symbol.isBlank()) continue
                val sparkline = mutableListOf<Float>()
                item.optJSONArray("sparkline")?.let { spark ->
                    for (j in 0 until spark.length()) {
                        sparkline.add(spark.getDouble(j).toFloat())
                    }
                }
                quotes[symbol] = Quote(
                    symbol = symbol,
                    name = item.optString("name", symbol),
                    exchange = item.optString("exchange"),
                    currency = item.optString("currency", "EUR"),
                    price = item.getDouble("price"),
                    previousClose = item.optDouble("previousClose", item.getDouble("price")),
                    dayHigh = item.optDouble("dayHigh", Double.NaN),
                    dayLow = item.optDouble("dayLow", Double.NaN),
                    marketState = item.optString("marketState"),
                    marketTimeSeconds = item.optLong("marketTimeSeconds"),
                    sparkline = sparkline
                )
            }
            quotes.toMap() to updatedAt
        }.getOrDefault(emptyMap<String, Quote>() to null)
    }

    companion object {
        private const val KEY_QUOTES = "quotes"
        private const val KEY_UPDATED_AT = "updated_at"
    }
}
