package com.stocktracker.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Résultat de la recherche mondiale de valeurs. */
data class SearchResult(
    val symbol: String,
    val name: String,
    val exchange: String,
    val type: String
)

/**
 * Cotations et recherche via l'API publique Yahoo Finance, qui couvre toutes les
 * bourses mondiales (Euronext, NYSE, NASDAQ, LSE, XETRA, TSE…) sans clé d'API :
 *  - cotation : /v8/finance/chart/SYMBOLE
 *  - recherche : /v1/finance/search?q=…
 * Deux hôtes (query1 et query2) sont essayés pour la résilience.
 */
class StockRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val hosts = listOf(
        "https://query1.finance.yahoo.com",
        "https://query2.finance.yahoo.com"
    )

    /** Noms lisibles utilisés si l'API ne renvoie pas de nom long. */
    private val fallbackNames = mapOf(
        "WPEA.PA" to "iShares MSCI World Swap PEA UCITS ETF",
        "PAEEM.PA" to "Amundi PEA MSCI Emerging Markets UCITS ETF"
    )

    suspend fun fetchQuote(symbol: String): Quote = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(symbol, "UTF-8")
        val body = getWithFallback(
            "/v8/finance/chart/$encoded?interval=5m&range=1d&includePrePost=false"
        )
        parseQuote(symbol, body)
    }

    /** Recherche une valeur par nom ou symbole sur toutes les bourses. */
    suspend fun searchSymbols(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = getWithFallback(
            "/v1/finance/search?q=$encoded&quotesCount=20&newsCount=0&listsCount=0"
        )
        parseSearchResults(body)
    }

    private fun getWithFallback(pathAndQuery: String): String {
        var lastError: IOException? = null
        for (host in hosts) {
            try {
                val request = Request.Builder()
                    .url(host + pathAndQuery)
                    .header("User-Agent", "Mozilla/5.0 (Android) StockTracker/1.0")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    return response.body?.string() ?: throw IOException("Réponse vide")
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Erreur réseau")
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
            exchange = meta.optString("fullExchangeName").ifBlank {
                meta.optString("exchangeName")
            },
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

    private fun parseSearchResults(body: String): List<SearchResult> {
        val quotes = JSONObject(body).optJSONArray("quotes") ?: return emptyList()
        val results = mutableListOf<SearchResult>()
        for (i in 0 until quotes.length()) {
            val item = quotes.optJSONObject(i) ?: continue
            val symbol = item.optString("symbol")
            if (symbol.isBlank()) continue
            val name = item.optString("longname").ifBlank {
                item.optString("shortname").ifBlank { symbol }
            }
            results.add(
                SearchResult(
                    symbol = symbol,
                    name = name,
                    exchange = item.optString("exchDisp").ifBlank { item.optString("exchange") },
                    type = item.optString("typeDisp").ifBlank { item.optString("quoteType") }
                )
            )
        }
        return results
    }
}
