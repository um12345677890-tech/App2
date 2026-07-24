package com.stocktracker.app.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stocktracker.app.MainActivity
import com.stocktracker.app.R
import com.stocktracker.app.data.AlertStore
import com.stocktracker.app.data.PriceAlert
import com.stocktracker.app.data.Quote
import com.stocktracker.app.data.StockRepository
import java.util.Locale

/**
 * Vérifie périodiquement (en arrière-plan, même app fermée) les alertes de prix
 * et envoie une notification quand un seuil est franchi. Un seuil déclenché est
 * désactivé pour ne pas notifier en boucle.
 */
class PriceAlertWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = AlertStore(applicationContext)
        val alerts = store.load().filter { it.isActive }
        if (alerts.isEmpty()) return Result.success()

        val repository = StockRepository(applicationContext)
        alerts.forEach { alert ->
            val quote = runCatching { repository.fetchQuote(alert.symbol) }.getOrNull()
                ?: return@forEach
            var updated: PriceAlert = alert
            alert.above?.let { threshold ->
                if (quote.price >= threshold) {
                    notifyThreshold(quote, threshold, isAbove = true)
                    updated = updated.copy(above = null)
                }
            }
            alert.below?.let { threshold ->
                if (quote.price <= threshold) {
                    notifyThreshold(quote, threshold, isAbove = false)
                    updated = updated.copy(below = null)
                }
            }
            if (updated != alert) store.set(updated)
        }
        return Result.success()
    }

    private fun notifyThreshold(quote: Quote, threshold: Double, isAbove: Boolean) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return

        val currency = if (quote.currency == "EUR") "€" else quote.currency
        val direction = if (isAbove) "au-dessus de" else "en dessous de"
        val text = String.format(
            Locale.FRANCE,
            "Cours actuel : %,.2f %s — %s votre seuil de %,.2f %s.",
            quote.price, currency, direction, threshold, currency
        )

        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Alerte ${quote.symbol} ${if (isAbove) "📈" else "📉"}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            manager.notify(quote.symbol.hashCode() + if (isAbove) 1 else 0, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "price_alerts"
        const val WORK_NAME = "price-alerts"
    }
}
