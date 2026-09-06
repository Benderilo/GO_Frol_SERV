package com.example.frolovsistems.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.frolovsistems.MainActivity
import com.example.frolovsistems.core.net.RequestDto
import com.example.frolovsistems.di.ServiceLocator
import java.util.concurrent.TimeUnit

/**
 * Фоновый опрос новых заявок с сайта. Раз в 30 минут сравниваем самый свежий
 * id со строкой «уже видели» в SharedPreferences: всё, что новее, падает
 * в системные уведомления. Тап по уведомлению открывает вкладку «Заявки».
 */
class NewRequestsWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        ServiceLocator.init(applicationContext)
        val list = ServiceLocator.crm.requests("new").getOrNull()
            // Сеть/авторизация не готовы — пробуем позже, это не провал фичи.
            ?: return if (runAttemptCount < 3) Result.retry() else Result.success()

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seenId = prefs.getLong(KEY_SEEN_ID, -1L)
        val maxId = list.maxOfOrNull { it.id } ?: 0L

        // Первый запуск только запоминает точку отсчёта: не орать про всё старое.
        if (seenId < 0L) {
            prefs.edit().putLong(KEY_SEEN_ID, maxId).apply()
            return Result.success()
        }

        val fresh = list.filter { it.id > seenId }.sortedBy { it.id }
        if (fresh.isNotEmpty()) {
            postNotification(fresh)
            prefs.edit().putLong(KEY_SEEN_ID, maxId).apply()
        }
        return Result.success()
    }

    private fun postNotification(fresh: List<RequestDto>) {
        // Без разрешения (Android 13+) notify бросит SecurityException.
        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel()

        val first = fresh.first()
        val text = if (fresh.size == 1) {
            "${first.name} • ${first.phone}"
        } else {
            "${first.name} • ${first.phone} и ещё ${fresh.size - 1}"
        }
        val title = if (fresh.size == 1) "Новая заявка с сайта" else "Новых заявок: ${fresh.size}"

        // Тап открывает приложение сразу на вкладке «Заявки».
        val intent = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_SECTION, MainActivity.SECTION_REQUESTS)
        val pending = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Новые заявки",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Обращения, пришедшие с формы на сайте" }
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val PREFS = "new_requests_watch"
        private const val KEY_SEEN_ID = "seen_id"
        private const val CHANNEL_ID = "new_requests"
        private const val NOTIFICATION_ID = 1001
        private const val WORK_NAME = "new-requests-watch"

        /** Разово запросить сеть и крутиться дальше по расписанию. UPDATE — чтобы не плодить копии. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NewRequestsWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
