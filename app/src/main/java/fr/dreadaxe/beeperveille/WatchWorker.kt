package fr.dreadaxe.beeperveille

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class WatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val rooms = Prefs.selectedRooms(applicationContext)
        if (rooms.isEmpty()) return Result.success()
        val repo = BeeperRepository(applicationContext.contentResolver)
        val words = DigestBuilder.keywords(Prefs.keywords(applicationContext))
        var total = 0
        var hits = 0
        val now = System.currentTimeMillis()
        return try {
            rooms.forEach { roomId ->
                val last = Prefs.lastAuto(applicationContext, roomId).takeIf { it > 0 } ?: (now - 24L * 3600_000L)
                val ms = repo.messagesSince(roomId, last + 1, now, 3000)
                total += ms.size
                if (words.isNotEmpty()) hits += ms.count { m -> words.any { m.text.contains(it, true) } }
                Prefs.setLastAuto(applicationContext, roomId, now)
            }
            if (total > 0 && (words.isEmpty() || hits > 0)) notify(total, hits, words)
            Result.success()
        } catch (_: SecurityException) {
            Result.failure()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    private fun notify(total: Int, hits: Int, words: List<String>) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Veille Discord", NotificationManager.IMPORTANCE_DEFAULT))
        val text = if (words.isEmpty()) "$total nouveau(x) message(s) dans les salons suivis."
        else "$hits occurrence(s) de mots-clés dans $total nouveau(x) message(s)."
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Beeper Veille")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < 33) {
            NotificationManagerCompat.from(applicationContext).notify(1001, n)
        }
    }

    companion object { const val CHANNEL = "beeper_watch" }
}
