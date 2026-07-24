package com.stocktracker.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
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

/** Poids d'un secteur dans une position (0.0 à 1.0). */
data class SectorWeight(
    val label: String,
    val weight: Double
)

/** Composition d'une position : répartition sectorielle et localisation géographique. */
data class Composition(
    val sectors: List<SectorWeight>,
    val location: String?
)

/**
 * Cotations et recherche via l'API publique Yahoo Finance, qui couvre toutes les
 * bourses mondiales (Euronext, NYSE, NASDAQ, LSE, XETRA, TSE…) sans clé d'API :
 *  - cotation : /v8/finance/chart/SYMBOLE
 *  - recherche : /v1/finance/search?q=…
 * Deux hôtes (query1 et query2) sont essayés pour la résilience.
 */
class StockRepository {

    // Cookies Yahoo conservés en mémoire : nécessaires pour l'API quoteSummary (jeton crumb).
    private val cookieStore = mutableListOf<Cookie>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(cookieStore) { cookieStore.addAll(cookies) }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                synchronized(cookieStore) { cookieStore.filter { it.matches(url) } }
        })
        .build()

    @Volatile
    private var crumb: String? = null

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

    /**
     * Récupère la composition (secteurs + localisation) d'une position via l'API
     * quoteSummary de Yahoo. Cette API exige un jeton « crumb » lié à un cookie :
     * on le récupère automatiquement et on réessaie une fois s'il a expiré.
     */
    suspend fun fetchComposition(symbol: String, name: String): Composition =
        withContext(Dispatchers.IO) {
            try {
                fetchCompositionOnce(symbol, name)
            } catch (e: IOException) {
                crumb = null
                fetchCompositionOnce(symbol, name)
            }
        }

    private fun fetchCompositionOnce(symbol: String, name: String): Composition {
        val crumbValue = obtainCrumb()
        val encoded = URLEncoder.encode(symbol, "UTF-8")
        val body = getWithFallback(
            "/v10/finance/quoteSummary/$encoded" +
                "?modules=assetProfile%2CtopHoldings" +
                "&crumb=" + URLEncoder.encode(crumbValue, "UTF-8")
        )
        return parseComposition(body, name)
    }

    private fun obtainCrumb(): String {
        crumb?.let { return it }
        synchronized(this) {
            crumb?.let { return it }
            // Un premier appel dépose le cookie Yahoo nécessaire au jeton.
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url("https://fc.yahoo.com")
                        .header("User-Agent", USER_AGENT)
                        .build()
                ).execute().close()
            }
            val request = Request.Builder()
                .url("https://query1.finance.yahoo.com/v1/test/getcrumb")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/plain")
                .build()
            client.newCall(request).execute().use { response ->
                val value = response.body?.string()?.trim().orEmpty()
                if (!response.isSuccessful || value.isEmpty() || value.length > 32) {
                    throw IOException("Impossible d'obtenir le jeton Yahoo (crumb)")
                }
                crumb = value
                return value
            }
        }
    }

    private fun parseComposition(body: String, name: String): Composition {
        val result = JSONObject(body)
            .getJSONObject("quoteSummary")
            .optJSONArray("result")
            ?.optJSONObject(0)
            ?: throw IOException("Composition indisponible")

        val sectors = mutableListOf<SectorWeight>()
        result.optJSONObject("topHoldings")
            ?.optJSONArray("sectorWeightings")
            ?.let { array ->
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val key = entry.keys().asSequence().firstOrNull() ?: continue
                    val raw = entry.optJSONObject(key)?.optDouble("raw") ?: continue
                    if (!raw.isNaN() && raw > 0.0) {
                        sectors.add(SectorWeight(sectorLabel(key), raw))
                    }
                }
            }

        var location: String? = null
        result.optJSONObject("assetProfile")?.let { profile ->
            // Cas d'une action : un seul secteur et un pays.
            if (sectors.isEmpty()) {
                val sector = profile.optString("sector")
                if (sector.isNotBlank()) sectors.add(SectorWeight(sectorLabel(sector), 1.0))
            }
            val country = profile.optString("country")
            if (country.isNotBlank()) location = countryLabel(country)
        }
        if (location == null) location = regionFromName(name)

        sectors.sortByDescending { it.weight }
        return Composition(sectors = sectors, location = location)
    }

    private fun sectorLabel(key: String): String = when (key.lowercase().replace(" ", "_")) {
        "technology" -> "Technologie"
        "financial_services" -> "Services financiers"
        "healthcare" -> "Santé"
        "consumer_cyclical" -> "Consommation cyclique"
        "consumer_defensive" -> "Consommation de base"
        "industrials" -> "Industrie"
        "communication_services" -> "Communication"
        "energy" -> "Énergie"
        "utilities" -> "Services publics"
        "basic_materials" -> "Matériaux"
        "realestate", "real_estate" -> "Immobilier"
        else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun countryLabel(country: String): String = when (country) {
        "United States" -> "États-Unis"
        "France" -> "France"
        "Germany" -> "Allemagne"
        "United Kingdom" -> "Royaume-Uni"
        "Japan" -> "Japon"
        "China" -> "Chine"
        "Switzerland" -> "Suisse"
        "Netherlands" -> "Pays-Bas"
        "Spain" -> "Espagne"
        "Italy" -> "Italie"
        "Belgium" -> "Belgique"
        "Ireland" -> "Irlande"
        "Luxembourg" -> "Luxembourg"
        "Canada" -> "Canada"
        "South Korea" -> "Corée du Sud"
        "Taiwan" -> "Taïwan"
        "India" -> "Inde"
        "Brazil" -> "Brésil"
        else -> country
    }

    /** Déduit la zone géographique d'un ETF depuis son nom (Yahoo ne la fournit pas). */
    private fun regionFromName(name: String): String? {
        val upper = name.uppercase()
        return when {
            "ACWI" in upper -> "Monde (développés + émergents)"
            "EMERGING" in upper || "EMERGENT" in upper || "ÉMERGENT" in upper ->
                "Marchés émergents"
            "WORLD" in upper || "MONDE" in upper -> "Monde développé"
            "S&P 500" in upper || "S&P500" in upper || "SP500" in upper ||
                "NASDAQ" in upper || "RUSSELL" in upper || "USA" in upper ||
                "UNITED STATES" in upper -> "États-Unis"
            "EURO STOXX" in upper || "EUROZONE" in upper || "EMU" in upper ->
                "Zone euro"
            "EUROPE" in upper || "STOXX" in upper -> "Europe"
            "CAC 40" in upper || "CAC40" in upper || "FRANCE" in upper -> "France"
            "JAPAN" in upper || "JAPON" in upper || "NIKKEI" in upper ||
                "TOPIX" in upper -> "Japon"
            "CHINA" in upper || "CHINE" in upper -> "Chine"
            "INDIA" in upper || "INDE " in upper -> "Inde"
            "ASIA" in upper || "ASIE" in upper -> "Asie"
            "PACIFIC" in upper || "PACIFIQUE" in upper -> "Pacifique"
            "GLOBAL" in upper -> "Monde"
            else -> null
        }
    }

    private fun getWithFallback(pathAndQuery: String): String {
        var lastError: IOException? = null
        for (host in hosts) {
            try {
                val request = Request.Builder()
                    .url(host + pathAndQuery)
                    .header("User-Agent", USER_AGENT)
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

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
