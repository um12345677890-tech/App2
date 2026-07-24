package com.stocktracker.app.data

/**
 * Cotation d'un instrument, telle que renvoyée par l'API de graphique Yahoo Finance.
 */
data class Quote(
    val symbol: String,
    val name: String,
    val currency: String,
    val price: Double,
    val previousClose: Double,
    val dayHigh: Double,
    val dayLow: Double,
    val marketState: String,
    val marketTimeSeconds: Long,
    val sparkline: List<Float>
) {
    val change: Double get() = price - previousClose
    val changePercent: Double
        get() = if (previousClose != 0.0) change / previousClose * 100.0 else 0.0
}
