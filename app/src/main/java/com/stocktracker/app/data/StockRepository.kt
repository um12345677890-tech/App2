package com.stocktracker.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Récupère les cotations via l'API publique de graphique Yahoo Finance
 * (https://query1.finance.yahoo.com/v8/finance/chart/SYMBOLE).
 * Aucune clé d'API n'est nécessaire.
 */
class StockRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Noms lisibles utilisés si l'API ne renvoie pas de nom long. */
    private val fallbackNames = mapOf(
        "WPEA.PA" to "iShares MSCI World Swap PEA UCITS ETF",
        "PAEEM.PA" to "Amundi PEA MSCI Emerging Markets UCITS ETF"
    )

    suspend fun fetchQuote(symbol: String): Quote = withContext(Dispatchers.IO) {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol" +
            "?interval=5m&range=1d&includePrePost=false"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) StockTracker/1.0")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} pour $symbol")
            }
            val body = response.body?.string()
                ?: throw IOException("Réponse vide pour $symbol")
            parseQuote(symbol, body)
        }
    }

    private fun parseQuote(symbol: String, body: String): Quote {
        val chart = JSONObject(body).getJSONObject("chart")
        if (!chart.isNull("error")) {
            val description = chart.getJSONObject("error").optString("description", "erreur inconnue")
            throw IOException("Symbole $symbol : $description")
        }
        val result = chart.getJSONArray("result").getJSONObject(0)
        val meta = result.getJSONObject("meta")

        val price = meta.getDouble("regularMarketPrice")
        val previousClose = when {
            meta.has("chartPreviousClose") -> meta.getDouble("chartPreviousClose")
            meta.has("previousClose") -> meta.getDouble("previousClose")
            else -> price
        }

        val sparkline = mutableListOf<Float>()
        val indicators = result.optJSONObject("indicators")
        val quoteArray = indicators?.optJSONArray("quote")
        if (quoteArray != null && quoteArray.length() > 0) {
            val closes = quoteArray.getJSONObject(0).optJSONArray("close")
            if (closes != null) {
                for (i in 0 until closes.length()) {
                    if (!closes.isNull(i)) {
                        sparkline.add(closes.getDouble(i).toFloat())
                    }
                }
            }
        }

        val name = meta.optString("longName").ifBlank {
            meta.optString("shortName").ifBlank {
                fallbackNames[symbol] ?: symbol
            }
        }

        return Quote(
            symbol = symbol,
            name = name,
            currency = meta.optString("currency", "EUR"),
            price = price,
            previousClose = previousClose,
            dayHigh = meta.optDouble("regularMarketDayHigh", Double.NaN),
            dayLow = meta.optDouble("regularMarketDayLow", Double.NaN),
            marketState = meta.optString("marketState", ""),
            marketTimeSeconds = meta.optLong("regularMarketTime", System.currentTimeMillis() / 1000),
            sparkline = sparkline
        )
    }
}
