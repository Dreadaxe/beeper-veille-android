package fr.dreadaxe.beeperveille

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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class WatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val watchId = inputData.getString("watchId")
        val saved = watchId?.let { Prefs.getWatch(applicationContext, it) }

        val rooms = saved?.roomIds ?: Prefs.selectedRooms(applicationContext)
        if (rooms.isEmpty()) return Result.success()

        val repo = BeeperRepository(applicationContext.contentResolver)
        val words = DigestBuilder.keywords(saved?.keywords ?: Prefs.keywords(applicationContext))
        val intervalHours = saved?.intervalHours ?: Prefs.intervalHours(applicationContext)
        var total = 0
        var hits = 0
        val now = System.currentTimeMillis()
        var notificationSince = Long.MAX_VALUE

        return try {
            rooms.forEach { roomId ->
                val last = if (saved != null) {
                    Prefs.lastWatchRun(applicationContext, saved.id, roomId)
                        .takeIf { it > 0 } ?: (now - intervalHours * 3600_000L)
                } else {
                    Prefs.lastAuto(applicationContext, roomId)
                        .takeIf { it > 0 } ?: (now - intervalHours * 3600_000L)
                }

                val since = last + 1
                notificationSince = minOf(notificationSince, since)

                val ms = repo.messagesSince(roomId, since, now)
                total += ms.size
                if (words.isNotEmpty()) {
                    hits += ms.count { m -> words.any { m.text.contains(it, true) } }
                }

                if (saved != null) Prefs.setLastWatchRun(applicationContext, saved.id, roomId, now)
                else Prefs.setLastAuto(applicationContext, roomId, now)
            }

            if (total > 0 && (words.isEmpty() || hits > 0)) {
                val since = notificationSince.takeIf { it != Long.MAX_VALUE }
                    ?: (now - intervalHours * 3600_000L)
                notify(
                    title = saved?.name ?: "Beeper Veille",
                    total = total,
                    hits = hits,
                    words = words,
                    watchId = saved?.id,
                    since = since,
                    until = now
                )
            }
            Result.success()
        } catch (_: SecurityException) {
            Result.failure()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    private fun notify(
        title: String,
        total: Int,
        hits: Int,
        words: List<String>,
        watchId: String?,
        since: Long,
        until: Long
    ) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Veilles Discord", NotificationManager.IMPORTANCE_DEFAULT)
        )

        val text = if (words.isEmpty()) {
            "$total nouveau(x) message(s) dans la veille « $title »."
        } else {
            "$hits occurrence(s) de mots-clés dans $total nouveau(x) message(s) — « $title »."
        }

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_WATCH_ID, watchId)
            putExtra(MainActivity.EXTRA_NOTIFICATION_SINCE, since)
            putExtra(MainActivity.EXTRA_NOTIFICATION_UNTIL, until)
            putExtra(MainActivity.EXTRA_AUTO_CHATGPT, false)
        }
        val openPending = PendingIntent.getActivity(
            applicationContext,
            (watchId ?: "default").hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val summarizeIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_WATCH_ID, watchId)
            putExtra(MainActivity.EXTRA_NOTIFICATION_SINCE, since)
            putExtra(MainActivity.EXTRA_NOTIFICATION_UNTIL, until)
            putExtra(MainActivity.EXTRA_AUTO_CHATGPT, true)
        }
        val summarizePending = PendingIntent.getActivity(
            applicationContext,
            ((watchId ?: "default") + "_chatgpt").hashCode(),
            summarizeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Veille Discord — $title")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_send, "Résumer dans ChatGPT", summarizePending)
            .setAutoCancel(true)
            .build()

        if (
            ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < 33
        ) {
            val id = watchId?.hashCode() ?: 1001
            NotificationManagerCompat.from(applicationContext).notify(id, n)
        }
    }

    companion object { const val CHANNEL = "beeper_watch" }
}
