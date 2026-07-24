package com.stocktracker.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Alerte de prix sur une valeur : seuil haut (cours ≥) et/ou bas (cours ≤). */
data class PriceAlert(
    val symbol: String,
    val above: Double? = null,
    val below: Double? = null
) {
    val isActive: Boolean get() = above != null || below != null
}

/** Persistance des alertes de prix dans les SharedPreferences (JSON). */
class AlertStore(context: Context) {

    private val prefs = context.getSharedPreferences("price_alerts", Context.MODE_PRIVATE)

    fun load(): List<PriceAlert> {
        val json = prefs.getString(KEY_ALERTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            val alerts = mutableListOf<PriceAlert>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val symbol = item.optString("symbol")
                if (symbol.isBlank()) continue
                alerts.add(
                    PriceAlert(
                        symbol = symbol,
                        above = item.optDouble("above").takeIf { !it.isNaN() && it > 0.0 },
                        below = item.optDouble("below").takeIf { !it.isNaN() && it > 0.0 }
                    )
                )
            }
            alerts.toList()
        }.getOrDefault(emptyList())
    }

    /** Enregistre l'alerte ; sans aucun seuil, elle est supprimée. */
    fun set(alert: PriceAlert) {
        val others = load().filterNot { it.symbol == alert.symbol }
        save(if (alert.isActive) others + alert else others)
    }

    private fun save(alerts: List<PriceAlert>) {
        val array = JSONArray()
        alerts.forEach { alert ->
            array.put(
                JSONObject()
                    .put("symbol", alert.symbol)
                    .put("above", alert.above ?: JSONObject.NULL)
                    .put("below", alert.below ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(KEY_ALERTS, array.toString()).apply()
    }

    companion object {
        private const val KEY_ALERTS = "alerts"
    }
}
