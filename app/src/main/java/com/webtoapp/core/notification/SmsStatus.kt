package com.webtoapp.core.notification

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.webtoapp.core.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sends an SMS and asks Android for the two results:
 *  - "sent"      (the radio accepted it / failed: no signal, airplane mode, ...)
 *  - "delivered" (the carrier reported delivery - not every carrier does)
 * Results are collected by [SmsStatusReceiver] into [SmsReports], which the page polls.
 */
object SmsSender {
    const val ACTION_SENT = "com.webtoapp.bridge.SMS_SENT"
    const val ACTION_DELIVERED = "com.webtoapp.bridge.SMS_DELIVERED"

    @Suppress("DEPRECATION")
    private fun manager(context: Context): SmsManager? = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    } catch (e: Exception) {
        null
    }

    /** Returns "queued", "no_permission", "invalid" or "error". The real outcome arrives later via [SmsReports]. */
    fun sendTagged(context: Context, phone: String, body: String, tag: String): String {
        val app = context.applicationContext
        if (ContextCompat.checkSelfPermission(app, android.Manifest.permission.SEND_SMS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return "no_permission"
        val number = phone.filter { it.isDigit() || it == '+' }
        if (number.length < 5 || number.length > 20) return "invalid"
        if (body.isBlank() || body.length > 1500 || tag.isBlank()) return "invalid"
        return try {
            val sms = manager(app) ?: return "error"
            val parts = sms.divideMessage(body)
            SmsReports.begin(app, tag, parts.size)
            val sentList = ArrayList<PendingIntent>()
            val delList = ArrayList<PendingIntent>()
            for (i in 0 until parts.size) {
                sentList.add(statusIntent(app, ACTION_SENT, tag, i, parts.size))
                delList.add(statusIntent(app, ACTION_DELIVERED, tag, i, parts.size))
            }
            sms.sendMultipartTextMessage(number, null, parts, sentList, delList)
            "queued"
        } catch (e: Exception) {
            AppLogger.e("SmsSender", "sendTagged failed", e)
            "error"
        }
    }

    private fun statusIntent(app: Context, action: String, tag: String, part: Int, parts: Int): PendingIntent {
        val intent = Intent(app, SmsStatusReceiver::class.java).apply {
            this.action = action
            putExtra("tag", tag)
            putExtra("part", part)
            putExtra("parts", parts)
        }
        return PendingIntent.getBroadcast(
            app,
            (action + tag + part).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** Persistent list of send / delivery results, read by the page through NativeBridge. */
object SmsReports {
    private const val PREFS = "bridge_sms_reports"
    private const val KEY_LIST = "reports"
    private const val MAX = 300

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun begin(context: Context, tag: String, parts: Int) {
        prefs(context).edit()
            .putInt("parts_$tag", parts.coerceAtLeast(1))
            .putInt("sok_$tag", 0).putBoolean("sdone_$tag", false)
            .putInt("dok_$tag", 0).putBoolean("ddone_$tag", false)
            .apply()
    }

    fun reasonFor(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "خطای عمومی (احتمالاً شارژ یا سیم‌کارت)"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "آنتن نیست"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "حالت پرواز"
        SmsManager.RESULT_ERROR_NULL_PDU -> "خطای قالب پیامک"
        else -> "کد $code"
    }

    @Synchronized
    fun onSent(context: Context, tag: String, resultCode: Int) {
        val p = prefs(context)
        if (p.getBoolean("sdone_$tag", false)) return
        val parts = p.getInt("parts_$tag", 1)
        if (resultCode != Activity.RESULT_OK) {
            p.edit().putBoolean("sdone_$tag", true).apply()
            finishSent(context, tag, false, reasonFor(resultCode))
            return
        }
        val n = p.getInt("sok_$tag", 0) + 1
        p.edit().putInt("sok_$tag", n).apply()
        if (n >= parts) {
            p.edit().putBoolean("sdone_$tag", true).apply()
            finishSent(context, tag, true, "")
        }
    }

    @Synchronized
    fun onDelivered(context: Context, tag: String, resultCode: Int) {
        val p = prefs(context)
        if (p.getBoolean("ddone_$tag", false)) return
        val parts = p.getInt("parts_$tag", 1)
        if (resultCode != Activity.RESULT_OK) {
            p.edit().putBoolean("ddone_$tag", true).apply()
            if (!tag.startsWith("call_")) add(context, tag, "delivered", "failed", "تحویل نشد")
            return
        }
        val n = p.getInt("dok_$tag", 0) + 1
        p.edit().putInt("dok_$tag", n).apply()
        if (n >= parts) {
            p.edit().putBoolean("ddone_$tag", true).apply()
            if (!tag.startsWith("call_")) add(context, tag, "delivered", "delivered", "")
        }
    }

    private fun finishSent(context: Context, tag: String, ok: Boolean, reason: String) {
        if (tag.startsWith("call_")) {
            // missed-call SMS: update the call event instead of the page's SMS history
            CallMonitor.onSmsSent(context, tag.removePrefix("call_"), ok, reason)
        } else {
            add(context, tag, "sent", if (ok) "ok" else "failed", reason)
        }
    }

    @Synchronized
    private fun add(context: Context, tag: String, stage: String, status: String, reason: String) {
        try {
            val arr = JSONArray(prefs(context).getString(KEY_LIST, "[]") ?: "[]")
            val o = JSONObject()
                .put("tag", tag).put("stage", stage).put("status", status)
                .put("reason", reason).put("at", CallMonitor.isoNow())
            arr.put(o)
            val trimmed = JSONArray()
            val from = (arr.length() - MAX).coerceAtLeast(0)
            for (i in from until arr.length()) trimmed.put(arr.get(i))
            prefs(context).edit().putString(KEY_LIST, trimmed.toString()).apply()
        } catch (e: Exception) {
            AppLogger.e("SmsReports", "add failed", e)
        }
    }

    fun read(context: Context): String = prefs(context).getString(KEY_LIST, "[]") ?: "[]"

    @Synchronized
    fun remove(context: Context, tags: Collection<String>) {
        try {
            val set = tags.toSet()
            val arr = JSONArray(prefs(context).getString(KEY_LIST, "[]") ?: "[]")
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!set.contains(o.optString("tag"))) out.put(o)
            }
            val e = prefs(context).edit().putString(KEY_LIST, out.toString())
            // counters of a tag can go once its delivery stage has finished (a late "delivered" still needs them)
            for (t in set) {
                if (prefs(context).getBoolean("ddone_$t", false)) {
                    e.remove("parts_$t").remove("sok_$t").remove("sdone_$t").remove("dok_$t").remove("ddone_$t")
                }
            }
            e.apply()
        } catch (ex: Exception) {
            AppLogger.e("SmsReports", "remove failed", ex)
        }
    }
}

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra("tag") ?: return
        when (intent.action) {
            SmsSender.ACTION_SENT -> SmsReports.onSent(context, tag, resultCode)
            SmsSender.ACTION_DELIVERED -> SmsReports.onDelivered(context, tag, resultCode)
        }
    }
}
