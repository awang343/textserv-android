package com.textserv.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.textserv.R
import com.textserv.data.Contacts
import com.textserv.data.TelephonyStore
import com.textserv.ui.MainActivity

object Notifier {
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_ERRORS = "errors"
    const val KEY_REPLY = "reply"

    /** Thread currently on screen; we don't notify for it. */
    @Volatile var activeThreadId: Long = -1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ERRORS, "Delivery problems", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun canPost(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openThreadIntent(ctx: Context, threadId: Long): PendingIntent = PendingIntent.getActivity(
        ctx, threadId.toInt(),
        Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_THREAD_ID, threadId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** (Re)builds the notification for a thread from its unread messages; cancels it if none. */
    fun notifyThread(ctx: Context, threadId: Long) {
        if (!canPost(ctx)) return
        val nm = NotificationManagerCompat.from(ctx)
        if (threadId == activeThreadId) return
        val unread = TelephonyStore.unread(ctx, threadId)
        if (unread.isEmpty()) { nm.cancel(threadId.toInt()); return }

        val addresses = TelephonyStore.threadAddresses(ctx, threadId)
        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me)
        if (addresses.size > 1) {
            style.isGroupConversation = true
            style.conversationTitle = Contacts.title(ctx, addresses)
        }
        for (m in unread) {
            val who = m.sender ?: addresses.firstOrNull() ?: ""
            val person = Person.Builder().setName(Contacts.name(ctx, who)).setKey(who).build()
            val text = m.body.ifEmpty { if (m.attachments.isNotEmpty()) "📎 Attachment" else "" }
            style.addMessage(text, m.date, person)
        }

        val reply = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Reply",
            actionIntent(ctx, NotificationActionReceiver.ACTION_REPLY, threadId, mutable = true),
        ).addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val markRead = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view, "Mark read",
            actionIntent(ctx, NotificationActionReceiver.ACTION_MARK_READ, threadId, mutable = false),
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val n = NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openThreadIntent(ctx, threadId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .addAction(reply)
            .addAction(markRead)
            .setWhen(unread.last().date)
            .build()
        @Suppress("MissingPermission")
        nm.notify(threadId.toInt(), n)
    }

    fun cancelThread(ctx: Context, threadId: Long) =
        NotificationManagerCompat.from(ctx).cancel(threadId.toInt())

    private fun actionIntent(ctx: Context, action: String, threadId: Long, mutable: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, threadId.toInt(),
            Intent(ctx, NotificationActionReceiver::class.java).setAction(action)
                .putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, threadId),
            (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun notifyDownloadFailed(ctx: Context, location: String, tid: String, from: String?, subId: Int) {
        if (!canPost(ctx)) return
        val retry = PendingIntent.getBroadcast(
            ctx, tid.hashCode(),
            Intent(ctx, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_RETRY_DOWNLOAD)
                .putExtra("location", location).putExtra("tid", tid).putExtra("from", from).putExtra("sub", subId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ERRORS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Couldn't download MMS")
            .setContentText("From ${from?.let { Contacts.name(ctx, it) } ?: "unknown"}. Mobile data must be on.")
            .addAction(android.R.drawable.ic_popup_sync, "Retry", retry)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(tid.hashCode(), n)
    }
}
