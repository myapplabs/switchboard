package llc.applabs.switchboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import llc.applabs.switchboard.domain.PushTokens
import llc.applabs.switchboard.domain.model.NotifyKinds

/** FCM tokens for the shared push registration. */
class FcmTokens : PushTokens {
    override val platform = "android"
    override suspend fun token(): String? = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
}

/**
 * Notices from the hub, through the AppLabs relay. They carry a kind and opaque ids, never content, so the
 * text is ours and generic; tapping opens the conversation, where the app loads the details from the hub.
 */
class PushService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { runCatching { (application as SwitchboardApp).container.push.register(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val kind = data["kind"] ?: return
        // The app is open: it already shows this live, so no notification on top.
        if ((application as? SwitchboardApp)?.inForeground == true) return
        Notifications.show(this, kind, data["session_id"], data["approval_id"] ?: data["run_id"] ?: data["job_id"] ?: data["task_id"], data["task_id"])
    }
}

object Notifications {
    private val texts = mapOf(
        NotifyKinds.APPROVAL to ("Approval needed" to "An agent is waiting for your OK before it runs a command."),
        NotifyKinds.QUESTION to ("Question from an agent" to "An agent asked you something and is waiting for your answer."),
        NotifyKinds.RUN_FINISHED to ("Answer ready" to "An agent finished working on your request."),
        NotifyKinds.JOB_FAILED to ("Scheduled job failed" to "One of your agents' jobs didn't succeed."),
        NotifyKinds.JOB_FINISHED to ("Job results ready" to "A scheduled job posted its results."),
        NotifyKinds.MEMBER_JOINED to ("New member" to "Someone accepted an invite to your hub."),
        NotifyKinds.TASK_NEEDS_YOU to ("A card needs you" to "A card on your board is stuck and waiting for you."),
        NotifyKinds.TASK_REVIEW to ("Ready for review" to "Agents finished a card. Review it to mark it done."),
    )
    private val channelNames = mapOf(
        NotifyKinds.APPROVAL to "Approvals", NotifyKinds.QUESTION to "Questions", NotifyKinds.RUN_FINISHED to "Finished answers",
        NotifyKinds.JOB_FAILED to "Failed jobs", NotifyKinds.JOB_FINISHED to "Job results", NotifyKinds.MEMBER_JOINED to "New members",
        NotifyKinds.TASK_NEEDS_YOU to "Cards that need you", NotifyKinds.TASK_REVIEW to "Cards to review",
    )

    /** One channel per kind, so people can also mute kinds in Android's settings. */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        channelNames.forEach { (kind, name) ->
            val importance = if (kind == NotifyKinds.APPROVAL || kind == NotifyKinds.QUESTION || kind == NotifyKinds.TASK_NEEDS_YOU) NotificationManager.IMPORTANCE_HIGH
                else NotificationManager.IMPORTANCE_DEFAULT
            nm.createNotificationChannel(NotificationChannel(kind, name, importance))
        }
    }

    fun show(context: Context, kind: String, sessionId: String?, ref: String?, taskId: String? = null) {
        val (title, body) = texts[kind] ?: return
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (taskId != null) putExtra(EXTRA_TASK, taskId) else sessionId?.let { putExtra(EXTRA_SESSION, it) }
        }
        val pending = PendingIntent.getActivity(context, (taskId ?: sessionId ?: kind).hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, kind)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title).setContentText(body)
            .setAutoCancel(true).setContentIntent(pending)
            .setCategory(if (kind == NotifyKinds.APPROVAL || kind == NotifyKinds.QUESTION) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify((ref ?: sessionId ?: kind).hashCode(), n) }
    }

    const val EXTRA_SESSION = "session_id"
    const val EXTRA_TASK = "task_id"
}
