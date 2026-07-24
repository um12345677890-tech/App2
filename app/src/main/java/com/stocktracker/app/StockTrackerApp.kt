package com.stocktracker.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.stocktracker.app.worker.PriceAlertWorker
import java.util.concurrent.TimeUnit

class StockTrackerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createAlertChannel()
        schedulePriceAlertWorker()
    }

    private fun createAlertChannel() {
        val channel = NotificationChannel(
            PriceAlertWorker.CHANNEL_ID,
            "Alertes de prix",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications quand un seuil de cours est franchi"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun schedulePriceAlertWorker() {
        val request = PeriodicWorkRequestBuilder<PriceAlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PriceAlertWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
