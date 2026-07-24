package com.stocktracker.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
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

/** Point d'un graphique de cours. */
data class ChartPoint(
    val timeSeconds: Long,
    val close: Float
)

/** Série de cours d'une valeur sur une période donnée. */
data class ChartData(
    val points: List<ChartPoint>,
    val currency: String,
    val periodHigh: Double,
    val periodLow: Double,
    val startPrice: Double,
    val endPrice: Double
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
class StockRepository(context: Context) {

    // Cookies Yahoo conservés en mémoire : nécessaires pour l'API quoteSummary (jeton crumb).
    private val cookieStore = mutableListOf<Cookie>()

    private val client = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "http_cache"), 5L * 1024 * 1024))
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

    /** Série de cours d'une valeur pour une période (range/interval Yahoo). */
    suspend fun fetchChart(symbol: String, range: String, interval: String): ChartData =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(symbol, "UTF-8")
            val body = getWithFallback(
                "/v8/finance/chart/$encoded?interval=$interval&range=$range&includePrePost=false"
            )
            parseChart(symbol, body)
        }

    private fun parseChart(symbol: String, body: String): ChartData {
        val chart = JSONObject(body).getJSONObject("chart")
        if (!chart.isNull("error")) {
            val description = chart.getJSONObject("error").optString("description", "erreur inconnue")
            throw IOException("Symbole $symbol : $description")
        }
        val result = chart.getJSONArray("result").getJSONObject(0)
        val meta = result.getJSONObject("meta")
        val timestamps = result.optJSONArray("timestamp")
        val closes = result.optJSONObject("indicators")
            ?.optJSONArray("quote")
            ?.optJSONObject(0)
            ?.optJSONArray("close")

        val points = mutableListOf<ChartPoint>()
        if (timestamps != null && closes != null) {
            for (i in 0 until minOf(timestamps.length(), closes.length())) {
                if (!closes.isNull(i)) {
                    points.add(ChartPoint(timestamps.getLong(i), closes.getDouble(i).toFloat()))
                }
            }
        }
        if (points.size < 2) throw IOException("Pas assez de données pour $symbol")

        return ChartData(
            points = points,
            currency = meta.optString("currency", "EUR"),
            periodHigh = points.maxOf { it.close }.toDouble(),
            periodLow = points.minOf { it.close }.toDouble(),
            startPrice = points.first().close.toDouble(),
            endPrice = meta.optDouble("regularMarketPrice", points.last().close.toDouble())
        )
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
     * Récupère la composition d'une position : secteurs via l'API quoteSummary de
     * Yahoo (jeton « crumb » géré automatiquement), et allocation par pays réelle
     * du fonds via justETF pour les ETF, avec la répartition d'indice embarquée
     * en secours si justETF est indisponible.
     */
    suspend fun fetchComposition(symbol: String, name: String): Composition =
        withContext(Dispatchers.IO) {
            val base = runCatching {
                try {
                    fetchCompositionOnce(symbol, name)
                } catch (e: IOException) {
                    crumb = null
                    fetchCompositionOnce(symbol, name)
                }
            }.getOrNull()

            // Priorité des sources : pays Yahoo pour les actions et répartition
            // d'indice embarquée (exacte, issue des factsheets) pour les ETF
            // reconnus. justETF n'est tenté que pour les ETF dont l'indice n'est
            // pas reconnu, avec correspondance stricte du fonds.
            val looksLikeFund = name.uppercase().let {
                "ETF" in it || "UCITS" in it || "INDEX" in it
            }
            val justEtf = if (looksLikeFund && (base == null || base.countries.isEmpty())) {
                runCatching { fetchJustEtfCountries(symbol, name) }.getOrNull()
            } else {
                null
            }

            when {
                base != null && base.countries.isNotEmpty() -> base
                base != null && justEtf != null -> base.copy(
                    countries = justEtf.first,
                    countriesSource = justEtf.second
                )
                base != null -> base
                justEtf != null -> Composition(
                    sectors = emptyList(),
                    countries = justEtf.first,
                    countriesSource = justEtf.second,
                    location = regionFromName(name)
                )
                else -> throw IOException("Composition indisponible")
            }
        }

    /** Cache symbole → ISIN résolu via la recherche justETF. */
    private val isinCache = mutableMapOf<String, String>()

    /** ISIN connus de secours si la recherche justETF est indisponible. */
    private val knownIsins = mapOf(
        "WPEA.PA" to "IE0002XZSHO1",
        "PAEEM.PA" to "FR0013412020",
        "CW8.PA" to "LU1681043599"
    )

    /**
     * Allocation par pays réelle du fonds, lue sur sa fiche justETF (endpoints non
     * officiels : tout échec renvoie null et le repli sur l'indice s'applique).
     * Retourne les pays et le libellé de source incluant l'ISIN utilisé.
     */
    private fun fetchJustEtfCountries(
        symbol: String,
        name: String
    ): Pair<List<SectorWeight>, String>? {
        val isin = resolveIsin(symbol, name) ?: return null
        val html = getHtml("https://www.justetf.com/en/etf-profile.html?isin=$isin")

        // Sécurité anti-mauvais fonds : la fiche doit mentionner le ticker ou
        // au moins deux mots significatifs du nom du fonds.
        val ticker = symbol.substringBefore('.')
        val pageMatchesFund = html.contains(ticker, ignoreCase = true) ||
            significantWords(name).count { html.contains(it, ignoreCase = true) } >= 2
        if (!pageMatchesFund) return null

        val countries = parseJustEtfCountries(html)
        return countries.takeIf { it.size >= 2 }?.let { it to "justETF · ISIN $isin" }
    }

    private fun resolveIsin(symbol: String, name: String): String? {
        isinCache[symbol]?.let { return it }
        val ticker = symbol.substringBefore('.')
        val isin = runCatching { searchIsinViaJustEtf(ticker, name) }.getOrNull()
            ?: knownIsins[symbol]
        if (isin != null) isinCache[symbol] = isin
        return isin
    }

    /**
     * Recherche l'ISIN via l'endpoint JSON non documenté de justETF
     * (/servlet/etfs-search). Une ligne n'est retenue que si son ticker est
     * exactement celui recherché, ou si une seule ligne correspond au nom.
     */
    private fun searchIsinViaJustEtf(ticker: String, name: String): String? {
        val form = FormBody.Builder()
            .add("draw", "1")
            .add("start", "0")
            .add("length", "25")
            .add("search", "ETFS")
            .add("query", ticker)
            .add("lang", "en")
            .add("country", "FR")
            .add("universeType", "private")
            .build()
        val request = Request.Builder()
            .url("https://www.justetf.com/servlet/etfs-search")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .post(form)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: return null
            val rows = JSONObject(body).optJSONArray("data") ?: return null

            // 1) Correspondance exacte de ticker.
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optString("ticker").equals(ticker, ignoreCase = true)) {
                    return row.optString("isin").takeIf { it.isNotBlank() }
                }
            }
            // 2) Sinon, une unique ligne partageant ≥ 2 mots significatifs du nom.
            val nameWords = significantWords(name)
            val candidates = mutableListOf<String>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val rowWords = significantWords(row.optString("name"))
                if ((rowWords intersect nameWords).size >= 2) {
                    row.optString("isin").takeIf { it.isNotBlank() }?.let { candidates.add(it) }
                }
            }
            return candidates.singleOrNull()
        }
    }

    /** Mots discriminants d'un nom de fonds (hors mots génériques). */
    private fun significantWords(text: String): Set<String> =
        text.uppercase()
            .split(Regex("[^A-Z0-9&]+"))
            .filter { it.length >= 3 }
            .filterNot { it in genericFundWords }
            .toSet()

    private val genericFundWords = setOf(
        "ETF", "UCITS", "ACC", "DIST", "THE", "AND", "EUR", "USD", "FUND", "INDEX"
    )

    private fun parseJustEtfCountries(html: String): List<SectorWeight> {
        val start = html.indexOf(">Countries<").takeIf { it >= 0 } ?: return emptyList()
        val end = listOf(">Sectors<", ">Sector<")
            .mapNotNull { marker -> html.indexOf(marker, start).takeIf { it > start } }
            .minOrNull()
            ?: (start + 12_000).coerceAtMost(html.length)
        val segment = html.substring(start, end)

        val rowRegex = Regex(
            ">([A-Z][A-Za-z&.,'\\- ]{1,40})<[^%]{0,200}?>([0-9]{1,2}(?:[.,][0-9]{1,2})?)\\s*%<"
        )
        val countries = mutableListOf<SectorWeight>()
        for (match in rowRegex.findAll(segment)) {
            val rawLabel = match.groupValues[1].trim()
            val percent = match.groupValues[2].replace(',', '.').toDoubleOrNull() ?: continue
            if (percent <= 0.0 || percent > 100.0) continue
            val label = if (rawLabel.equals("Other", true) || rawLabel.equals("Others", true)) {
                "Autres"
            } else {
                countryLabel(rawLabel)
            }
            if (countries.none { it.label == label }) {
                countries.add(SectorWeight(label, percent / 100.0))
            }
        }
        // Sécurité anti-parsing aberrant : le total doit rester plausible.
        val total = countries.sumOf { it.weight }
        if (total < 0.3 || total > 1.1) return emptyList()
        countries.sortByDescending { it.weight }
        return countries
    }

    private fun getHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html")
            .header("Accept-Language", "en")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("Réponse vide")
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
        "South Korea", "Korea" -> "Corée du Sud"
        "Taiwan" -> "Taïwan"
        "India" -> "Inde"
        "Brazil" -> "Brésil"
        "Sweden" -> "Suède"
        "Denmark" -> "Danemark"
        "Norway" -> "Norvège"
        "Finland" -> "Finlande"
        "Austria" -> "Autriche"
        "Australia" -> "Australie"
        "Singapore" -> "Singapour"
        "Saudi Arabia" -> "Arabie saoudite"
        "South Africa" -> "Afrique du Sud"
        "Mexico" -> "Mexique"
        "Indonesia" -> "Indonésie"
        "Thailand" -> "Thaïlande"
        "Poland" -> "Pologne"
        "Greece" -> "Grèce"
        "Turkey" -> "Turquie"
        "United Arab Emirates" -> "Émirats arabes unis"
        "Malaysia" -> "Malaisie"
        "Chile" -> "Chili"
        "New Zealand" -> "Nouvelle-Zélande"
        "Israel" -> "Israël"
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
