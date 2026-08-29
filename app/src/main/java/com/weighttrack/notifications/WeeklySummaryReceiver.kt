package com.weighttrack.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.weighttrack.MainActivity
import com.weighttrack.R
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.domain.ProgressCalculator
import com.weighttrack.domain.WeeklySummaryBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Posts the weekly summary, then books the next one.
 *
 * If there is nothing worth saying, nothing is posted. A weekly notification that always fires
 * regardless of whether it has news is one people turn off within a month.
 */
@AndroidEntryPoint
class WeeklySummaryReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var progressCalculator: ProgressCalculator

    @Inject lateinit var scheduler: WeeklySummaryScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = settingsRepository.settings.first()
                scheduler.reschedule(settings)
                if (!settings.weeklySummaryEnabled) return@launch

                val snapshot = progressCalculator.observe().first()
                val today = LocalDate.now()
                // A milestone crossed in the last week is the one thing worth leading with.
                val milestone = snapshot.milestones
                    .filter { it.reached && it.reachedOn != null }
                    .filter { !it.reachedOn!!.isBefore(today.minusDays(6)) }
                    .maxByOrNull { it.reachedOn!! }
                    ?.grams

                val summary = WeeklySummaryBuilder.build(
                    series = snapshot.series,
                    unit = snapshot.settings.weightUnit,
                    goalDirection = snapshot.goal?.direction,
                    milestoneReachedThisWeek = milestone,
                    today = today,
                ) ?: return@launch

                post(context, summary.headline, summary.detail)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun post(context: Context, title: String, body: String) {
        WeeklySummaryScheduler.ensureChannel(context)
        if (!ReminderReceiver.hasNotificationPermission(context)) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val openApp = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, WeeklySummaryScheduler.CHANNEL_SUMMARY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(WeeklySummaryScheduler.NOTIFICATION_ID, notification)
        }
    }
}
