package com.webtoapp.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.webtoapp.core.logging.AppLogger
import java.io.File
import java.util.Locale

class BridgeAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SCHEDULED_NOTIFICATION = "com.webtoapp.bridge.SCHEDULED_NOTIFICATION"

        const val EXTRA_TITLE = "bridge_notif_title"
        const val EXTRA_BODY = "bridge_notif_body"
        const val EXTRA_CHANNEL_ID = "bridge_notif_channel_id"
        const val EXTRA_TAG = "bridge_notif_tag"
        const val EXTRA_DEEP_LINK = "bridge_notif_deep_link"
        const val EXTRA_PLAY_SOUND = "bridge_notif_play_sound"
        const val EXTRA_WAKE_SCREEN = "bridge_notif_wake_screen"
        const val EXTRA_SOUND_KEY = "bridge_notif_sound_key"
        const val EXTRA_SOUND2_KEY = "bridge_notif_sound2_key"
        const val EXTRA_SOUND_VOL = "bridge_notif_sound_vol"
        const val EXTRA_SPEAK = "bridge_notif_speak"
        const val EXTRA_SPEAK_GAP = "bridge_notif_speak_gap"
        const val EXTRA_DISPLAY = "bridge_notif_display"
        const val EXTRA_AUTO_CANCEL_MS = "bridge_notif_auto_cancel_ms"
        const val EXTRA_HIDDEN = "bridge_notif_hidden"
        const val EXTRA_SMS_PHONE = "bridge_notif_sms_phone"
        const val EXTRA_SMS_BODY = "bridge_notif_sms_body"

        private const val DEFAULT_CHANNEL_ID = "webapp_bridge_notifications"
        private const val DEFAULT_CHANNEL_NAME = "Web App Notifications"
        private const val TAG = "BridgeAlarmReceiver"
        private const val WAKE_DURATION_MS = 15_000L
        private const val PLAY_MAX_MS = 25_000L
        const val SILENT_CHANNEL_ID = "app_sound_reminders"
        private const val CALL_WAIT_MAX_MS = 8_000L
        private const val CALL_POLL_MS = 500L

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

        // ---- Automatic SMS -----------------------------------------------------
        // A scheduled reminder may carry a phone number and a text. When the user has
        // switched automatic sending on, the alarm sends that SMS itself (even if the
        // app is closed) and remembers the tag so the page can mark it done later.

        private const val SMS_PREFS = "bridge_sms"
        private const val SMS_KEY_AUTO = "auto_enabled"
        private const val SMS_KEY_SENT = "sent_tags"

        private fun smsPrefs(context: Context) =
            context.applicationContext.getSharedPreferences(SMS_PREFS, Context.MODE_PRIVATE)

        fun setAutoSmsEnabled(context: Context, enabled: Boolean) {
            smsPrefs(context).edit().putBoolean(SMS_KEY_AUTO, enabled).apply()
        }

        fun isAutoSmsEnabled(context: Context): Boolean =
            smsPrefs(context).getBoolean(SMS_KEY_AUTO, false)

        @Synchronized
        fun markSmsSent(context: Context, tag: String) {
            val prefs = smsPrefs(context)
            val set = HashSet(prefs.getStringSet(SMS_KEY_SENT, emptySet()) ?: emptySet())
            set.add(tag)
            prefs.edit().putStringSet(SMS_KEY_SENT, set).apply()
        }

        fun readSentSmsTags(context: Context): List<String> =
            (smsPrefs(context).getStringSet(SMS_KEY_SENT, emptySet()) ?: emptySet()).toList()

        @Synchronized
        fun removeSentSmsTags(context: Context, tags: Collection<String>) {
            val prefs = smsPrefs(context)
            val set = HashSet(prefs.getStringSet(SMS_KEY_SENT, emptySet()) ?: emptySet())
            set.removeAll(tags.toSet())
            prefs.edit().putStringSet(SMS_KEY_SENT, set).apply()
        }

        @Suppress("DEPRECATION")
        private fun smsManager(context: Context): android.telephony.SmsManager? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(android.telephony.SmsManager::class.java)
            } else {
                android.telephony.SmsManager.getDefault()
            }
        } catch (e: Exception) {
            null
        }

        /** Returns "sent", "no_permission", "invalid" or "error". */
        fun sendSmsNow(context: Context, phone: String, body: String): String {
            if (ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.SEND_SMS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return "no_permission"
            val number = phone.filter { it.isDigit() || it == '+' }
            if (number.length < 5 || number.length > 20) return "invalid"
            if (body.isBlank() || body.length > 1500) return "invalid"
            return try {
                val manager = smsManager(context) ?: return "error"
                val parts = manager.divideMessage(body)
                if (parts.size > 1) {
                    manager.sendMultipartTextMessage(number, null, parts, null, null)
                } else {
                    manager.sendTextMessage(number, null, body, null, null)
                }
                "sent"
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to send SMS", e)
                "error"
            }
        }

        fun soundFile(context: Context, key: String): File =
            File(File(context.filesDir, "bridge_sounds"), "$key.wav")

        /**
         * One extra silent channel, used when the app itself plays the sound.
         * Created once; an existing channel is never touched.
         */
        fun ensureSilentChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
                if (manager.getNotificationChannel(SILENT_CHANNEL_ID) != null) return
                val channel = NotificationChannel(
                    SILENT_CHANNEL_ID,
                    "یادآور با صدای اپ",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "این دسته بی‌صداست؛ صدا را خود اپ پخش می‌کند. صدا را از داخل اپ عوض کنید."
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(true)
                }
                manager.createNotificationChannel(channel)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to create silent channel", e)
            }
        }

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

        /**
         * Turns the screen on for a short time. Needs the WAKE_LOCK permission in
         * the manifest; without it Android throws SecurityException, which is
         * caught here so the notification itself is never affected.
         */
        @Suppress("DEPRECATION")
        private fun wakeScreenBriefly(context: Context) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    ?: return
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "$TAG:wake"
                )
                wakeLock.acquire(WAKE_DURATION_MS)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to wake screen", e)
            }
        }

        /**
         * True while the phone is ringing or a call (normal or VoIP) is active.
         * Uses the audio mode, which needs no permission. Any failure counts as "no call",
         * so a bell is never lost because of a problem here.
         */
        fun isInCall(context: Context): Boolean = try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val mode = am?.mode ?: AudioManager.MODE_NORMAL
            mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_RINGTONE ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mode == AudioManager.MODE_CALL_SCREENING)
        } catch (e: Exception) {
            false
        }

        /**
         * Plays the saved sound, then (after gapMs) an optional second sound or
         * spoken text. Calls pending.finish() when done or after a hard timeout.
         */
        fun playAppSound(
            context: Context,
            file: File,
            volume: Float,
            file2: File?,
            speak: String,
            gapMs: Long,
            pending: BroadcastReceiver.PendingResult?,
            callChecked: Boolean = false
        ) {
            val app = context.applicationContext
            val handler = Handler(Looper.getMainLooper())

            // A call is in progress: never play a bell over it. Wait a few seconds in case
            // the call ends right now; otherwise skip the sound (the notification stays).
            if (!callChecked && isInCall(app)) {
                val startedAt = System.currentTimeMillis()
                val poll = object : Runnable {
                    override fun run() {
                        try {
                            if (!isInCall(app)) {
                                playAppSound(context, file, volume, file2, speak, gapMs, pending, true)
                            } else if (System.currentTimeMillis() - startedAt >= CALL_WAIT_MAX_MS) {
                                AppLogger.i(TAG, "Bell sound skipped: phone call in progress")
                                try { pending?.finish() } catch (_: Exception) {}
                            } else {
                                handler.postDelayed(this, CALL_POLL_MS)
                            }
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "call wait failed", e)
                            try { pending?.finish() } catch (_: Exception) {}
                        }
                    }
                }
                handler.postDelayed(poll, CALL_POLL_MS)
                return
            }
            var finished = false
            var wakeLock: PowerManager.WakeLock? = null
            try {
                val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:play")
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire(PLAY_MAX_MS + 2_000L)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to hold wake lock for sound", e)
            }

            fun finish() {
                if (finished) return
                finished = true
                try { wakeLock?.release() } catch (_: Exception) {}
                try { pending?.finish() } catch (_: Exception) {}
            }
            handler.postDelayed({ finish() }, PLAY_MAX_MS)

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            fun speakNow() {
                if (speak.isBlank() || finished) {
                    finish()
                    return
                }
                var tts: TextToSpeech? = null
                try {
                    tts = TextToSpeech(app) { status ->
                        val engine = tts
                        if (engine == null || status != TextToSpeech.SUCCESS) {
                            engine?.shutdown()
                            finish()
                            return@TextToSpeech
                        }
                        val lang = engine.setLanguage(Locale("fa", "IR"))
                        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                            AppLogger.w(TAG, "Persian TTS voice is not available")
                            engine.shutdown()
                            finish()
                            return@TextToSpeech
                        }
                        engine.setAudioAttributes(attrs)
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {
                                engine.shutdown()
                                finish()
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                engine.shutdown()
                                finish()
                            }
                        })
                        val params = Bundle().apply {
                            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
                        }
                        engine.speak(speak, TextToSpeech.QUEUE_FLUSH, params, "bridge_speak")
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "TTS failed", e)
                    finish()
                }
            }

            fun playFile(f: File, onDone: () -> Unit) {
                val player = MediaPlayer()
                try {
                    player.setAudioAttributes(attrs)
                    player.setDataSource(f.absolutePath)
                    player.setVolume(volume, volume)
                    player.setOnCompletionListener { mp ->
                        mp.release()
                        onDone()
                    }
                    player.setOnErrorListener { mp, _, _ ->
                        mp.release()
                        onDone()
                        true
                    }
                    player.prepare()
                    player.start()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to play sound file", e)
                    try { player.release() } catch (_: Exception) {}
                    onDone()
                }
            }

            fun afterFirst() {
                if (file2 != null && file2.isFile && file2.length() > 44L) {
                    handler.postDelayed({
                        if (!finished) playFile(file2) { finish() }
                    }, gapMs)
                } else if (speak.isNotBlank()) {
                    handler.postDelayed({ speakNow() }, gapMs)
                } else {
                    finish()
                }
            }

            playFile(file) { afterFirst() }
        }

        fun postNotification(
            context: Context,
            title: String,
            body: String,
            tag: String = "",
            channelId: String = DEFAULT_CHANNEL_ID,
            deepLink: String = "",
            playSound: Boolean = true,
            timeoutMs: Long = 0L
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
                val managed = isManagedChannel(effectiveChannelId) || effectiveChannelId == SILENT_CHANNEL_ID

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (managed) {
                        // Fixed channels: created once, never re-configured here.
                        ensureChannels(context)
                        ensureSilentChannel(context)
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
                    .apply { if (timeoutMs > 0L) setTimeoutAfter(timeoutMs) }
                    .build()

                NotificationManagerCompat.from(context).notify(requestCode, notification)
                if (timeoutMs > 0L && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    // setTimeoutAfter needs API 26; best-effort fallback for older phones.
                    Handler(Looper.getMainLooper()).postDelayed({
                        try { NotificationManagerCompat.from(context).cancel(requestCode) } catch (_: Exception) {}
                    }, timeoutMs)
                }
                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to post notification", e)
                false
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCHEDULED_NOTIFICATION) return

        var pending: PendingResult? = null
        try {
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "Notification"
            val body = intent.getStringExtra(EXTRA_BODY) ?: ""
            val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: DEFAULT_CHANNEL_ID
            val tag = intent.getStringExtra(EXTRA_TAG) ?: ""
            val deepLink = intent.getStringExtra(EXTRA_DEEP_LINK) ?: ""
            val playSound = intent.getBooleanExtra(EXTRA_PLAY_SOUND, true)
            val wakeScreen = intent.getBooleanExtra(EXTRA_WAKE_SCREEN, false)
            val soundKey = intent.getStringExtra(EXTRA_SOUND_KEY) ?: ""
            val sound2Key = intent.getStringExtra(EXTRA_SOUND2_KEY) ?: ""
            val soundVol = intent.getFloatExtra(EXTRA_SOUND_VOL, 1f)
            val speakText = intent.getStringExtra(EXTRA_SPEAK) ?: ""
            val speakGap = intent.getLongExtra(EXTRA_SPEAK_GAP, 3500L)
            val display = intent.getStringExtra(EXTRA_DISPLAY) ?: ""
            val autoMs = intent.getLongExtra(EXTRA_AUTO_CANCEL_MS, 0L)
            val hiddenReq = intent.getBooleanExtra(EXTRA_HIDDEN, false) || display == "sound"
            val smsPhone = intent.getStringExtra(EXTRA_SMS_PHONE) ?: ""
            val smsBody = intent.getStringExtra(EXTRA_SMS_BODY) ?: ""

            // Reminder that carries an SMS: send it now if automatic mode is on.
            var smsNote = ""
            if (smsPhone.isNotBlank() && smsBody.isNotBlank() && isAutoSmsEnabled(context)) {
                val result = sendSmsNow(context, smsPhone, smsBody)
                if (result == "sent") {
                    if (tag.isNotBlank()) markSmsSent(context, tag)
                    postNotification(
                        context, "✉️ پیامک خودکار ارسال شد", title, "sms_done_$tag",
                        DEFAULT_CHANNEL_ID, "", true, 0L
                    )
                    AppLogger.d(TAG, "Auto SMS sent: tag=$tag")
                    return
                }
                // Could not send: fall through and show the normal reminder instead.
                smsNote = "⚠️ ارسال خودکار نشد ($result) — "
                AppLogger.w(TAG, "Auto SMS failed ($result): tag=$tag")
            }

            val file: File? = if (soundKey.isNotBlank()) soundFile(context, soundKey) else null
            val file2: File? = if (sound2Key.isNotBlank()) soundFile(context, sound2Key) else null
            val useAppSound = file != null && file.isFile && file.length() > 44L
            if (useAppSound) pending = goAsync()

            // "sound" mode: no notification at all, but only when the app plays the sound itself.
            val hide = hiddenReq && useAppSound
            val timeoutMs = when {
                display == "auto" -> autoMs.coerceAtLeast(1_000L)
                display == "sound" && !useAppSound -> 3_000L
                else -> 0L
            }
            val posted = if (hide) true else postNotification(
                context, title, smsNote + body, tag,
                if (useAppSound) SILENT_CHANNEL_ID else channelId,
                deepLink, playSound, timeoutMs
            )
            if (posted && wakeScreen) wakeScreenBriefly(context)
            if (posted && useAppSound && file != null) {
                val p = pending
                pending = null
                playAppSound(context, file, soundVol, file2, speakText, speakGap, p)
            }
            AppLogger.d(TAG, "Scheduled notification fired: title=$title, tag=$tag")
        } catch (e: Exception) {
            AppLogger.e(TAG, "onReceive failed", e)
        } finally {
            try { pending?.finish() } catch (_: Exception) {}
        }
    }
}
