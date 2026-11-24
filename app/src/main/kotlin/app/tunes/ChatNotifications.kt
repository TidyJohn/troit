package app.tunes

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.concurrent.TimeUnit

/** Foreground checks are quick; Android schedules closed-app checks when background work is allowed. */
internal object ChatNotifications {
    private const val CHANNEL = "troit-chat-messages"
    private const val WORK = "troit-chat-inbox"
    private const val TAG = "troit-chat"
    const val PEER = "chat_peer"
    private const val OWNER = "chat_owner"
    @Volatile var appVisible = false

    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun accountChanged(context: Context, owner: String?) {
        val prefs = context.getSharedPreferences("troit_chat_notifications", Context.MODE_PRIVATE)
        if (prefs.getString("owner", null) != owner) {
            clear(context)
            prefs.edit().putString("owner", owner).apply()
        }
        val manager = WorkManager.getInstance(context)
        if (owner == null) {
            manager.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<ChatNotificationWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf("owner" to owner))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // UPDATE replaces the account data while preserving an existing periodic schedule.
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun show(context: Context, alert: ChatAlert) {
        val owner = widgetAccount(context) ?: return
        if (alert.message.recipientId != owner || !allowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Chat messages", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "New messages from your Troit friends." })
        val intent = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("troit://chat/${alert.peer.id}"))
            .putExtra(PEER, alert.peer.id).putExtra(OWNER, owner)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val preview = alert.message.text?.take(300) ?: "Sent a sticker"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.widget_music)
            .setContentTitle(alert.peer.displayName)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(open).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(false)
            .build()
        try { manager.notify("$TAG:${alert.peer.id}", 1, notification) }
        catch (_: SecurityException) { /* Permission may change between the check and delivery. */ }
    }

    fun peerFromIntent(context: Context, intent: Intent?): String? {
        if (intent?.getStringExtra(OWNER) != widgetAccount(context)) return null
        val peer = intent?.getStringExtra(PEER)?.takeIf { it.length <= 128 && it.isNotBlank() } ?: return null
        markRead(context, peer)
        return peer
    }

    fun markRead(context: Context, peer: String) {
        context.getSystemService(NotificationManager::class.java).cancel("$TAG:$peer", 1)
    }

    private fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.startsWith("$TAG:") == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }
}

/** WorkManager is best effort (15-minute minimum), independent of widgets and music playback. */
class ChatNotificationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = coroutineScope {
        val owner = inputData.getString("owner") ?: return@coroutineScope Result.success()
        val context = applicationContext
        if (ChatNotifications.appVisible || widgetAccount(context) != owner || !ChatNotifications.allowed(context)) {
            return@coroutineScope Result.success()
        }
        try {
            // The background transport must never load or sync the user's music library.
            val sync = Sync(context.filesDir, "Phone", Library(File(context.cacheDir, "chat-transport.json")), this)
            val generation = sync.accountGeneration
            if (sync.userId != owner) return@coroutineScope Result.success()
            val alerts = ChatInbox(sync, context.filesDir).poll()
            if (widgetAccount(context) == owner && sync.accountGeneration == generation && !ChatNotifications.appVisible) {
                alerts.forEach { ChatNotifications.show(context, it) }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure is AccountApiException && (failure.setupRequired || failure.status in setOf(401, 403))) Result.success()
            else Result.retry()
        }
    }
}
