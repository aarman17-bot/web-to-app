package com.webtoapp.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.webtoapp.core.logging.AppLogger

class BridgeAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SCHEDULED_NOTIFICATION = "com.webtoapp.bridge.SCHEDULED_NOTIFICATION"

        const val EXTRA_TITLE = "bridge_notif_title"
        const val EXTRA_BODY = "bridge_notif_body"
        const val EXTRA_CHANNEL_ID = "bridge_notif_channel_id"
        const val EXTRA_TAG = "bridge_notif_tag"
        const val EXTRA_DEEP_LINK = "bridge_notif_deep_link"
        const val EXTRA_PLAY_SOUND = "bridge_notif_play_sound"

        private const val DEFAULT_CHANNEL_ID = "webapp_bridge_notifications"
        private const val DEFAULT_CHANNEL_NAME = "Web App Notifications"
        private const val TAG = "BridgeAlarmReceiver"

        // ---- Reminder channels -------------------------------------------------
        // These ids are permanent. Android keys the user's per-channel settings
        // (sound, importance, ...) by id: renaming an id would orphan those settings.
        const val CHANNEL_LAST_SESSION = "last_session"
        const val CHANNEL_ONE_SESSION_LEFT = "one_session_left"
        const val CHANNEL_SUNSET_SIGNBOARD = "sunset_signboard"
        const val CHANNEL_CLOSING = "closing"
        const val CHANNEL_WORK_START = "work_start"
        const val CHANNEL_WORK_END = "work_end"

        private val MANAGED_CHANNELS: List<Pair<String, String>> = listOf(
            CHANNEL_LAST_SESSION to "یادآور آخرین جلسه",
            CHANNEL_ONE_SESSION_LEFT to "یادآور یک جلسه باقی‌مانده",
            CHANNEL_SUNSET_SIGNBOARD to "یادآور روشن کردن تابلو",
            CHANNEL_CLOSING to "یادآور بستن آموزشگاه",
            CHANNEL_WORK_START to "شروع ساعت کاری",
            CHANNEL_WORK_END to "پایان ساعت کاری"
        )

        private const val MANAGED_CHANNEL_DESCRIPTION =
            "برای تغییر صدا، همین دسته را در تنظیمات اعلان گوشی باز کنید."

        fun isManagedChannel(channelId: String): Boolean =
            MANAGED_CHANNELS.any { it.first == channelId }

        /**
         * Creates the six reminder channels. Safe to call any number of times:
         * a channel that already exists is left completely untouched, so a sound
         * the user picked in the phone settings is never overwritten. No sound is
         * set here on purpose, so a new channel uses the normal system sound.
         */
        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
                MANAGED_CHANNELS.forEach { (id, name) ->
                    if (manager.getNotificationChannel(id) != null) return@forEach
                    val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                        description = MANAGED_CHANNEL_DESCRIPTION
                        setShowBadge(true)
                        enableVibration(true)
                    }
                    manager.createNotificationChannel(channel)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to create reminder channels", e)
            }
        }

        fun postNotification(
            context: Context,
            title: String,
            body: String,
            tag: String = "",
            channelId: String = DEFAULT_CHANNEL_ID,
            deepLink: String = "",
            playSound: Boolean = true
        ): Boolean {
            return try {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.POST_NOTIFICATIONS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    AppLogger.w(TAG, "POST_NOTIFICATIONS permission not granted")
                    return false
                }

                val effectiveChannelId = channelId.ifBlank { DEFAULT_CHANNEL_ID }
                val managed = isManagedChannel(effectiveChannelId)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (managed) {
                        // Fixed channels: created once, never re-configured here.
                        ensureChannels(context)
                    } else {
                        // Legacy behaviour for the default channel and any other id.
                        val importance = if (playSound) {
                            NotificationManager.IMPORTANCE_DEFAULT
                        } else {
                            NotificationManager.IMPORTANCE_LOW
                        }
                        val channel = NotificationChannel(
                            effectiveChannelId,
                            DEFAULT_CHANNEL_NAME,
                            importance
                        ).apply {
                            setShowBadge(true)
                            enableVibration(true)
                            if (!playSound) setSound(null, null)
                        }
                        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.createNotificationChannel(channel)
                    }
                }

                val launchIntent = context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                    ?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        if (deepLink.isNotBlank()) {
                            data = android.net.Uri.parse(deepLink)
                        }
                    }

                val requestCode = (tag.ifBlank { "$title|$body" }).hashCode()
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    requestCode,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val priority = when {
                    !playSound -> NotificationCompat.PRIORITY_LOW
                    managed -> NotificationCompat.PRIORITY_HIGH
                    else -> NotificationCompat.PRIORITY_DEFAULT
                }

                val notification = NotificationCompat.Builder(context, effectiveChannelId)
                    .setSmallIcon(context.applicationInfo.icon)
                    .setContentTitle(title.ifBlank {
                        context.applicationInfo.loadLabel(context.packageManager).toString()
                    })
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setPriority(priority)
                    .build()

                NotificationManagerCompat.from(context).notify(requestCode, notification)
                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to post notification", e)
                false
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCHEDULED_NOTIFICATION) return

        try {
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "Notification"
            val body = intent.getStringExtra(EXTRA_BODY) ?: ""
            val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: DEFAULT_CHANNEL_ID
            val tag = intent.getStringExtra(EXTRA_TAG) ?: ""
            val deepLink = intent.getStringExtra(EXTRA_DEEP_LINK) ?: ""
            val playSound = intent.getBooleanExtra(EXTRA_PLAY_SOUND, true)

            postNotification(context, title, body, tag, channelId, deepLink, playSound)
            AppLogger.d(TAG, "Scheduled notification fired: title=$title, tag=$tag")
        } catch (e: Exception) {
            AppLogger.e(TAG, "onReceive failed", e)
        }
    }
}
