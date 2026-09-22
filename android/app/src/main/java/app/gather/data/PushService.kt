package app.gather.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.gather.GenoaApplication
import app.gather.MainActivity
import app.gather.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val repo = (application as GenoaApplication).repository
        if (repo.session.token != null)
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { repo.api.json("PUT", "/api/devices", json("token" to token)) }
            }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        (application as GenoaApplication).repository.requestSync()
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        )
            return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "messages",
                "Messages and reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val intent =
            notificationIntent(this, message.data["conversation_id"], message.data["message_id"], message.data["event_id"] ?: message.messageId ?: java.util.UUID.randomUUID().toString())
        val pending =
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        manager.notify(
            (message.data["event_id"] ?: message.messageId ?: "genoa").hashCode(),
            NotificationCompat.Builder(this, "messages")
                .setSmallIcon(R.drawable.genoa_notification)
                .setContentTitle(message.notification?.title ?: "Genoa")
                .setContentText(message.notification?.body ?: "A message is waiting")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }
}

fun notificationIntent(context: android.content.Context, conversationId: String?, messageId: String?, eventId: String): Intent =
    Intent(context, MainActivity::class.java)
        .putExtra("conversation_id", conversationId)
        .putExtra("message_id", messageId)
        .setData(android.net.Uri.parse("genoa://notification/${android.net.Uri.encode(eventId)}"))
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
