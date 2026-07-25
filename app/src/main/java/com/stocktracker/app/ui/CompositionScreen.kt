package com.stocktracker.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.SectorWeight
import java.util.Locale

/** Dégradé de bleus façon rapport de gestion, du plus foncé au plus clair. */
private val DonutPalette = listOf(
    Color(0xFF2E4A7A), Color(0xFF3A5CA0), Color(0xFF4A6FC4), Color(0xFF5F86DB),
    Color(0xFF77A0E8), Color(0xFF92B8F0), Color(0xFFADCCF5), Color(0xFFC6DDF9),
    Color(0xFFDDEDFC), Color(0xFFEFF8FE)
)
private val OtherColor = Color(0xFF8A93A6)

private fun colorFor(index: Int, label: String): Color =
    if (label == "Autres") OtherColor
    else DonutPalette[index % DonutPalette.size]

/**
 * Onglet « Composition » : allocation géographique et répartition sectorielle
 * de chaque position.
 */
@Composable
fun CompositionTab(
    tickers: List<TickerUi>,
    compositions: Map<String, CompositionUi>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(tickers, key = { it.symbol }) { ticker ->
            CompositionCard(
                ticker = ticker,
                composition = compositions[ticker.symbol]
            )
        }
    }
}

@Composable
private fun CompositionCard(ticker: TickerUi, composition: CompositionUi?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = ticker.quote?.name ?: ticker.symbol,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = ticker.symbol,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            when {
                composition == null || composition.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                composition.error != null -> {
                    Text(
                        text = "Composition indisponible : ${composition.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    Text(
                        text = "📍 Localisation : ${composition.location ?: "non précisée"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )

                    if (composition.countries.size >= 2) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Allocation géographique",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        DonutWithLegend(entries = composition.countries)
                        composition.countriesSource?.let { source ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Source : $source",
                                style = MaterialTheme.typography.labelSmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (composition.sectors.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Répartition sectorielle",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        composition.sectors.forEach { sector ->
                            SectorBar(label = sector.label, weight = sector.weight)
                            Spacer(Modifier.height(6.dp))
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Répartition sectorielle non fournie par la source.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Anneau façon rapport de gestion, suivi d'une légende à deux colonnes. */
@Composable
private fun DonutWithLegend(entries: List<SectorWeight>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            DonutChart(
                entries = entries,
                modifier = Modifier.size(170.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        entries.chunked(2).forEach { rowEntries ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowEntries.forEach { entry ->
                    val index = entries.indexOf(entry)
                    LegendItem(
                        color = colorFor(index, entry.label),
                        label = entry.label,
                        weight = entry.weight,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowEntries.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun DonutChart(entries: List<SectorWeight>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.22f
        val diameter = size.minDimension - strokeWidth
        val topLeft = Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f
        )
        val arcSize = Size(diameter, diameter)
        val total = entries.sumOf { it.weight }.toFloat().takeIf { it > 0f } ?: 1f

        var startAngle = -90f
        entries.forEachIndexed { index, entry ->
            val sweep = entry.weight.toFloat() / total * 360f
            val gap = minOf(1.2f, sweep * 0.15f)
            drawArc(
                color = colorFor(index, entry.label),
                startAngle = startAngle + gap / 2f,
                sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth)
            )
            startAngle += sweep
        }
    }
}

@Composable
private fun LegendItem(
    color: Color,
    label: String,
    weight: Double,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = String.format(Locale.FRANCE, "%.2f %%", weight * 100),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun SectorBar(label: String, weight: Double) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = String.format(Locale.FRANCE, "%.1f %%", weight * 100),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(weight.toFloat().coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
