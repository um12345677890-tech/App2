package com.stocktracker.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.Quote
import com.stocktracker.app.data.SearchResult
import com.stocktracker.app.ui.theme.Gain
import com.stocktracker.app.ui.theme.Loss
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockScreen(viewModel: StockViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    var showSearchSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Suivi Bourse", fontWeight = FontWeight.Bold)
                        LiveStatusLine(state.lastUpdateMillis, state.isRefreshing)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showSearchSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = "Rechercher une valeur")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refreshNow() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.tickers, key = { it.symbol }) { ticker ->
                    TickerCard(
                        ticker = ticker,
                        onRemove = { viewModel.removeSymbol(ticker.symbol) }
                    )
                }
            }
        }
    }

    if (showSearchSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showSearchSheet = false
                viewModel.clearSearch()
            },
            sheetState = sheetState
        ) {
            SearchSheetContent(
                searchState = searchState,
                watchedSymbols = state.tickers.map { it.symbol }.toSet(),
                onQueryChange = viewModel::onSearchQueryChange,
                onAdd = viewModel::addSymbol
            )
        }
    }
}

/** Recherche mondiale : nom ou symbole, toutes bourses confondues. */
@Composable
private fun SearchSheetContent(
    searchState: SearchState,
    watchedSymbols: Set<String>,
    onQueryChange: (String) -> Unit,
    onAdd: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        Text(
            text = "Rechercher une valeur",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Toutes les bourses : Paris, NYSE, NASDAQ, Londres, Francfort, Tokyo…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = searchState.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Nom ou symbole") },
            placeholder = { Text("Ex. : LVMH, Apple, MSCI World…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        when {
            searchState.isSearching -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
            searchState.error != null -> {
                Text(
                    text = "Erreur : ${searchState.error}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
            searchState.results.isEmpty() && searchState.query.trim().length >= 2 -> {
                Text(
                    text = "Aucun résultat pour « ${searchState.query.trim()} »",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(searchState.results, key = { it.symbol }) { result ->
                        SearchResultRow(
                            result = result,
                            alreadyWatched = result.symbol in watchedSymbols,
                            onAdd = { onAdd(result.symbol) }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    result: SearchResult,
    alreadyWatched: Boolean,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !alreadyWatched, onClick = onAdd)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(result.symbol, result.exchange, result.type)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (alreadyWatched) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Déjà suivie",
                tint = Gain
            )
        } else {
            Icon(
                Icons.Default.Add,
                contentDescription = "Ajouter ${result.symbol}",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun LiveStatusLine(lastUpdateMillis: Long?, isRefreshing: Boolean) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.FRANCE) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (isRefreshing) Gain else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = lastUpdateMillis?.let { "Mis à jour à ${timeFormat.format(Date(it))}" }
                ?: "Chargement…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TickerCard(ticker: TickerUi, onRemove: () -> Unit) {
    val quote = ticker.quote
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = quote?.name ?: ticker.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = listOfNotNull(
                                ticker.symbol,
                                quote?.exchange?.takeIf { it.isNotBlank() }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (quote != null) {
                            Spacer(Modifier.size(8.dp))
                            MarketStateBadge(quote.marketState)
                        }
                    }
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Retirer ${ticker.symbol}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                ticker.isLoading && quote == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
                quote == null -> {
                    Text(
                        text = ticker.error ?: "Données indisponibles",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> QuoteContent(quote, staleError = ticker.error)
            }
        }
    }
}

private fun currencySymbolOf(currency: String): String = when (currency.uppercase()) {
    "EUR" -> "€"
    "USD" -> "$"
    "GBP" -> "£"
    "GBP_PENCE", "GBX" -> "p"
    "JPY" -> "¥"
    "CHF" -> "CHF"
    else -> currency
}

@Composable
private fun QuoteContent(quote: Quote, staleError: String?) {
    val changeColor = if (quote.change >= 0) Gain else Loss
    // Yahoo renvoie "GBp" pour les cours en pence à Londres.
    val currencySymbol =
        if (quote.currency == "GBp") "p" else currencySymbolOf(quote.currency)

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = String.format(Locale.FRANCE, "%,.2f %s", quote.price, currencySymbol),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = String.format(
                Locale.FRANCE,
                "%+,.2f (%+.2f %%)",
                quote.change,
                quote.changePercent
            ),
            style = MaterialTheme.typography.titleSmall,
            color = changeColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }

    if (quote.sparkline.size >= 2) {
        Spacer(Modifier.height(12.dp))
        Sparkline(
            values = quote.sparkline,
            baseline = quote.previousClose.toFloat(),
            lineColor = changeColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        )
    }

    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        StatItem("Clôture veille", formatOrDash(quote.previousClose, currencySymbol))
        StatItem("Plus bas", formatOrDash(quote.dayLow, currencySymbol))
        StatItem("Plus haut", formatOrDash(quote.dayHigh, currencySymbol))
    }

    if (staleError != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Dernière mise à jour en échec : $staleError",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

private fun formatOrDash(value: Double, currencySymbol: String): String =
    if (value.isNaN()) "—" else String.format(Locale.FRANCE, "%,.2f %s", value, currencySymbol)

@Composable
private fun StatItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MarketStateBadge(marketState: String) {
    val (label, color) = when (marketState) {
        "REGULAR" -> "Ouvert" to Gain
        "PRE" -> "Pré-ouverture" to Color(0xFFF39C12)
        "POST", "POSTPOST" -> "Après-clôture" to Color(0xFFF39C12)
        else -> "Fermé" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** Mini-graphique de la séance : ligne + dégradé, avec repère de la clôture de la veille. */
@Composable
private fun Sparkline(
    values: List<Float>,
    baseline: Float,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier) {
        val min = minOf(values.min(), baseline)
        val max = maxOf(values.max(), baseline)
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        fun yFor(value: Float) = size.height * (1f - (value - min) / span)

        // Repère en pointillés : clôture de la veille.
        val baselineY = yFor(baseline)
        val dash = 8f
        var x = 0f
        while (x < size.width) {
            drawLine(
                color = baselineColor.copy(alpha = 0.4f),
                start = Offset(x, baselineY),
                end = Offset((x + dash).coerceAtMost(size.width), baselineY),
                strokeWidth = 1.5f
            )
            x += dash * 2
        }

        val linePath = Path()
        values.forEachIndexed { index, value ->
            val point = Offset(index * stepX, yFor(value))
            if (index == 0) linePath.moveTo(point.x, point.y)
            else linePath.lineTo(point.x, point.y)
        }

        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.25f), Color.Transparent)
            )
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
