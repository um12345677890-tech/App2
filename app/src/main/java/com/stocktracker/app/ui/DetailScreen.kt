package com.stocktracker.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.stocktracker.app.data.ChartData
import com.stocktracker.app.ui.theme.Gain
import com.stocktracker.app.ui.theme.Loss
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Période affichable : libellé + paramètres range/interval de l'API Yahoo. */
data class ChartPeriod(val label: String, val range: String, val interval: String)

val chartPeriods = listOf(
    ChartPeriod("1J", "1d", "5m"),
    ChartPeriod("5J", "5d", "15m"),
    ChartPeriod("1M", "1mo", "1d"),
    ChartPeriod("6M", "6mo", "1d"),
    ChartPeriod("1A", "1y", "1wk"),
    ChartPeriod("5A", "5y", "1mo"),
    ChartPeriod("Max", "max", "1mo")
)

/** Écran détail d'une valeur : graphique interactif par période et statistiques. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    ticker: TickerUi,
    charts: Map<String, ChartUi>,
    onLoadChart: (symbol: String, periodLabel: String, range: String, interval: String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var selectedPeriod by rememberSaveable { mutableStateOf(chartPeriods.first().label) }
    val period = chartPeriods.first { it.label == selectedPeriod }
    val chart = charts["${ticker.symbol}|${period.label}"]

    LaunchedEffect(ticker.symbol, period.label) {
        onLoadChart(ticker.symbol, period.label, period.range, period.interval)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour"
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = ticker.quote?.name ?: ticker.symbol,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = ticker.symbol,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            ticker.quote?.let { quote ->
                val currencySymbol = currencySymbolOf(quote.currency)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatMoney(quote.price, currencySymbol),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = formatSignedMoney(quote.change, currencySymbol) +
                            " (" + formatSignedPercent(quote.changePercent) + ")",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (quote.change >= 0) Gain else Loss,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chartPeriods.forEach { p ->
                    FilterChip(
                        selected = p.label == selectedPeriod,
                        onClick = { selectedPeriod = p.label },
                        label = { Text(p.label) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            when {
                chart == null || chart.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
                chart.error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Graphique indisponible : ${chart.error}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                chart.data != null -> {
                    PeriodChartSection(data = chart.data, periodLabel = period.label)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PeriodChartSection(data: ChartData, periodLabel: String) {
    val currencySymbol = currencySymbolOf(data.currency)
    val periodChange = data.endPrice - data.startPrice
    val periodChangePercent =
        if (data.startPrice != 0.0) periodChange / data.startPrice * 100.0 else 0.0
    val chartColor = if (periodChange >= 0) Gain else Loss

    val dateFormat = remember(periodLabel) {
        if (periodLabel == "1J" || periodLabel == "5J") {
            SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
        } else {
            SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
        }
    }

    // Point actuellement touché sur la courbe (repère interactif).
    var selected by remember(data) { mutableStateOf<Int?>(null) }
    val selectedPoint = selected?.let { data.points.getOrNull(it) }

    if (selectedPoint != null) {
        val pctFromStart = if (data.startPrice != 0.0) {
            (selectedPoint.close - data.startPrice) / data.startPrice * 100.0
        } else {
            0.0
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatMoney(selectedPoint.close.toDouble(), currencySymbol),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = formatSignedPercent(pctFromStart),
                style = MaterialTheme.typography.labelLarge,
                color = if (pctFromStart >= 0) Gain else Loss,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = dateFormat.format(Date(selectedPoint.timeSeconds * 1000)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        Text(
            text = "Touchez ou glissez sur le graphique pour lire le cours à une date",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(8.dp))

    PeriodChart(
        data = data,
        lineColor = chartColor,
        selectedIndex = selected,
        onSelectedChange = { selected = it },
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
    )

    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = dateFormat.format(Date(data.points.first().timeSeconds * 1000)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = dateFormat.format(Date(data.points.last().timeSeconds * 1000)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        DetailStat("Perf. période", formatSignedPercent(periodChangePercent), chartColor)
        DetailStat("Plus bas", formatMoney(data.periodLow, currencySymbol))
        DetailStat("Plus haut", formatMoney(data.periodHigh, currencySymbol))
    }
}

@Composable
private fun DetailStat(label: String, value: String, valueColor: Color? = null) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Grand graphique de période : ligne + dégradé + repère du cours de départ, et
 * repère interactif (croix + point) suivant le doigt pour lire le cours à une date.
 */
@Composable
private fun PeriodChart(
    data: ChartData,
    lineColor: Color,
    selectedIndex: Int?,
    onSelectedChange: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val markerColor = MaterialTheme.colorScheme.onSurface
    val pointCount = data.points.size
    Canvas(
        modifier = modifier.pointerInput(pointCount) {
            awaitEachGesture {
                fun update(x: Float) {
                    if (pointCount < 2) return
                    val idx = ((x / size.width) * (pointCount - 1))
                        .roundToInt()
                        .coerceIn(0, pointCount - 1)
                    onSelectedChange(idx)
                }
                val down = awaitFirstDown()
                update(down.position.x)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    update(change.position.x)
                    change.consume()
                }
            }
        }
    ) {
        val values = data.points
        val min = minOf(data.periodLow.toFloat(), data.startPrice.toFloat())
        val max = maxOf(data.periodHigh.toFloat(), data.startPrice.toFloat())
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        fun yFor(value: Float) = size.height * (1f - (value - min) / span)

        // Repère en pointillés : cours au début de la période.
        val baselineY = yFor(data.startPrice.toFloat())
        val dash = 10f
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
        values.forEachIndexed { index, point ->
            val offset = Offset(index * stepX, yFor(point.close))
            if (index == 0) linePath.moveTo(offset.x, offset.y)
            else linePath.lineTo(offset.x, offset.y)
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

        // Repère interactif : ligne verticale + point sur la courbe au doigt.
        if (selectedIndex != null && selectedIndex in values.indices) {
            val cx = selectedIndex * stepX
            val cy = yFor(values[selectedIndex].close)
            drawLine(
                color = markerColor.copy(alpha = 0.6f),
                start = Offset(cx, 0f),
                end = Offset(cx, size.height),
                strokeWidth = 2f
            )
            drawCircle(color = lineColor, radius = 11f, center = Offset(cx, cy))
            drawCircle(color = markerColor, radius = 5f, center = Offset(cx, cy))
        }
    }
}
