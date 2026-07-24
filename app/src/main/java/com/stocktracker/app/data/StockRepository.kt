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
    val countries: List<SectorWeight>,
    val countriesSource: String?,
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
        val countries = mutableListOf<SectorWeight>()
        var countriesSource: String? = null
        result.optJSONObject("assetProfile")?.let { profile ->
            // Cas d'une action : un seul secteur et un pays.
            if (sectors.isEmpty()) {
                val sector = profile.optString("sector")
                if (sector.isNotBlank()) sectors.add(SectorWeight(sectorLabel(sector), 1.0))
            }
            val country = profile.optString("country")
            if (country.isNotBlank()) {
                location = countryLabel(country)
                countries.add(SectorWeight(countryLabel(country), 1.0))
                countriesSource = "pays de la société (Yahoo Finance)"
            }
        }
        // Yahoo ne fournit pas l'allocation par pays des ETF : on utilise la
        // répartition réelle publiée pour l'indice suivi, déduit du nom du fonds.
        if (countries.isEmpty()) {
            indexAllocation(name)?.let { (allocation, source) ->
                countries.addAll(allocation)
                countriesSource = source
            }
        }
        if (location == null) location = regionFromName(name)

        sectors.sortByDescending { it.weight }
        return Composition(
            sectors = sectors,
            countries = countries,
            countriesSource = countriesSource,
            location = location
        )
    }

    /**
     * Allocation géographique réelle des grands indices suivis par les ETF,
     * issue des rapports officiels (factsheets). Les poids sont en fractions.
     */
    private fun indexAllocation(name: String): Pair<List<SectorWeight>, String>? {
        val upper = name.uppercase()
        fun weights(vararg pairs: Pair<String, Double>) =
            pairs.map { SectorWeight(it.first, it.second / 100.0) }
        return when {
            "EMERGING" in upper || "EMERGENT" in upper || "ÉMERGENT" in upper -> weights(
                "Chine" to 27.5, "Taïwan" to 20.5, "Inde" to 16.5, "Corée du Sud" to 10.5,
                "Brésil" to 4.5, "Arabie saoudite" to 3.5, "Afrique du Sud" to 3.0,
                "Mexique" to 2.0, "Indonésie" to 1.5, "Autres" to 10.5
            ) to "indice MSCI Emerging Markets (répartition indicative fin 2025)"
            "ACWI" in upper -> weights(
                "États-Unis" to 64.0, "Japon" to 5.1, "Chine" to 3.2, "Royaume-Uni" to 3.0,
                "Canada" to 2.9, "Taïwan" to 2.4, "Suisse" to 2.2, "France" to 2.1,
                "Inde" to 1.9, "Autres" to 13.2
            ) to "indice MSCI ACWI (répartition indicative fin 2025)"
            "WORLD" in upper || "MONDE" in upper -> weights(
                "États-Unis" to 72.26, "Japon" to 5.69, "Canada" to 3.35,
                "Royaume-Uni" to 3.32, "Suisse" to 2.63, "France" to 2.14,
                "Allemagne" to 2.11, "Pays-Bas" to 1.67, "Australie" to 1.59,
                "Espagne" to 0.93, "Autres" to 4.31
            ) to "indice MSCI World au 30/06/2026"
            "S&P 500" in upper || "S&P500" in upper || "SP500" in upper ||
                "NASDAQ" in upper || "RUSSELL" in upper || "USA" in upper ||
                "UNITED STATES" in upper -> weights("États-Unis" to 100.0) to
                "indice investi à 100 % aux États-Unis"
            "EURO STOXX 50" in upper || "EUROSTOXX" in upper -> weights(
                "France" to 36.0, "Allemagne" to 26.0, "Pays-Bas" to 15.0,
                "Italie" to 8.0, "Espagne" to 8.0, "Autres" to 7.0
            ) to "indice Euro Stoxx 50 (répartition indicative fin 2025)"
            "STOXX" in upper || "EUROPE" in upper -> weights(
                "Royaume-Uni" to 22.0, "France" to 16.0, "Suisse" to 14.0,
                "Allemagne" to 13.0, "Pays-Bas" to 6.0, "Suède" to 5.0,
                "Italie" to 4.0, "Espagne" to 4.0, "Danemark" to 4.0, "Autres" to 12.0
            ) to "indice Stoxx Europe 600 (répartition indicative fin 2025)"
            "CAC 40" in upper || "CAC40" in upper || "FRANCE" in upper ->
                weights("France" to 100.0) to "indice investi à 100 % en France"
            "JAPAN" in upper || "JAPON" in upper || "NIKKEI" in upper ||
                "TOPIX" in upper -> weights("Japon" to 100.0) to
                "indice investi à 100 % au Japon"
            else -> null
        }
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
