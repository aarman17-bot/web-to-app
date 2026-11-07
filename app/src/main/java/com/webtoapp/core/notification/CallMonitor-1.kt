package com.webtoapp.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.webtoapp.core.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Incoming-call helper driven by the page (see NativeBridge.setCallConfig):
 *  - shows a caller-ID notification (name + info from the student directory) while the phone rings,
 *  - sends a "missed call" SMS (known / unknown number, after-hours text, quiet hours, cooldown),
 *  - keeps a list of call events that the page reads with getCallEvents().
 * Calls forwarded from the academy line arrive as "<prefix>98<mobile without 0>"
 * (e.g. +9821 98 9381002449); the real caller is the part after the prefix.
 */
object CallMonitor {
    private const val PREFS = "bridge_call"
    private const val KEY_CFG = "cfg"
    private const val KEY_EVENTS = "events"
    private const val CHANNEL_ID = "call_caller_id"
    private const val NOTIF_ID = 7710
    private const val MAX_EVENTS = 200
    private const val SMS_WAIT_MS = 90_000L
    private const val TAG = "CallMonitor"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isoNow(): String = iso(System.currentTimeMillis())

    private fun iso(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    // ---- config ------------------------------------------------------------

    fun setConfig(context: Context, json: String): Boolean {
        return try {
            val o = JSONObject(json)
            prefs(context).edit().putString(KEY_CFG, o.toString()).apply()
            if (!o.optBoolean("monitor", false)) cancelNotification(context)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "setConfig failed", e)
            false
        }
    }

    private fun config(context: Context): JSONObject? = try {
        JSONObject(prefs(context).getString(KEY_CFG, "") ?: "")
    } catch (e: Exception) {
        null
    }

    fun hasPermissions(context: Context): Boolean {
        fun ok(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return ok(android.Manifest.permission.READ_PHONE_STATE) && ok(android.Manifest.permission.READ_CALL_LOG)
    }

    // ---- number handling ---------------------------------------------------

    /** Returns 09xxxxxxxxx or null. Accepts +98 / 0098 / 98 / 9xxxxxxxxx forms. */
    fun normalizeMobile(raw: String): String? {
        var d = raw.filter { it.isDigit() }
        d = when {
            d.startsWith("0098") -> "0" + d.substring(4)
            d.startsWith("98") && d.length == 12 -> "0" + d.substring(2)
            d.length == 10 && d.startsWith("9") -> "0$d"
            else -> d
        }
        return if (Regex("^09\\d{9}$").matches(d)) d else null
    }

    private class Caller(val raw: String, val caller: String, val viaAcademy: Boolean)

    private fun parseCaller(raw: String, cfg: JSONObject): Caller {
        val digits = raw.filter { it.isDigit() }
        val academy = cfg.optJSONObject("academy")
        if (academy != null && academy.optBoolean("on", false)) {
            val arr = academy.optJSONArray("prefixes") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.optString(i).filter { it.isDigit() }
                if (p.isNotEmpty() && digits.startsWith(p)) {
                    val rest = normalizeMobile(digits.substring(p.length))
                    if (rest != null) return Caller(raw, rest, true)
                }
            }
        }
        return Caller(raw, normalizeMobile(digits) ?: raw, false)
    }

    private class Person(val name: String, val info: String)

    /** Name saved for this number in the phone's own contacts (needs READ_CONTACTS; null when missing/not granted). */
    private fun contactName(context: Context, mobile: String): String? {
        try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) return null
            val digits = mobile.filter { it.isDigit() }
            if (digits.length < 7) return null
            val variants = LinkedHashSet<String>()
            variants.add(mobile)
            if (digits.startsWith("09") && digits.length == 11) variants.add("+98" + digits.substring(1))
            for (v in variants) {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(v))
                context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) {
                        val n = cur.getString(0)
                        if (!n.isNullOrBlank()) return n
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "contact lookup failed", e)
        }
        return null
    }

    private fun lookup(cfg: JSONObject, mobile: String): Person? {
        val dir = cfg.optJSONArray("directory") ?: return null
        for (i in 0 until dir.length()) {
            val o = dir.optJSONObject(i) ?: continue
            if (o.optString("p") == mobile) return Person(o.optString("n"), o.optString("i"))
        }
        return null
    }

    // ---- phone state -------------------------------------------------------

    @Synchronized
    fun onPhoneState(context: Context, state: String?, number: String?) {
        val app = context.applicationContext
        val cfg = config(app) ?: return
        if (!cfg.optBoolean("monitor", false)) return
        val p = prefs(app)
        val now = System.currentTimeMillis()
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                var at = p.getLong("r_at", 0L)
                if (at == 0L || now - at > 120_000L) {
                    at = now
                    p.edit().putLong("r_at", at).putLong("r_ans", 0L).putString("r_num", "").putBoolean("r_shown", false).apply()
                }
                val num = if (!number.isNullOrBlank()) number else (p.getString("r_num", "") ?: "")
                p.edit().putString("r_num", num).apply()
                if (num.isNotBlank() && !p.getBoolean("r_shown", false) && cfg.optBoolean("popup", false)) {
                    showCallerNotification(app, cfg, num)
                    p.edit().putBoolean("r_shown", true).apply()
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (p.getLong("r_at", 0L) > 0L && p.getLong("r_ans", 0L) == 0L) {
                    p.edit().putLong("r_ans", now).apply()
                }
                cancelNotification(app)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val at = p.getLong("r_at", 0L)
                if (at > 0L) finishCall(app, cfg, p.getString("r_num", "") ?: "", p.getLong("r_ans", 0L), now)
                p.edit().putLong("r_at", 0L).putLong("r_ans", 0L).putString("r_num", "").putBoolean("r_shown", false).apply()
                cancelNotification(app)
            }
        }
    }

    private fun finishCall(context: Context, cfg: JSONObject, rawNumber: String, answeredAt: Long, now: Long) {
        if (rawNumber.isBlank()) return // no READ_CALL_LOG: the system hides the number
        val c = parseCaller(rawNumber, cfg)
        val answered = answeredAt > 0L
        val contact = contactName(context, c.caller)
        val ev = JSONObject()
            .put("id", "c" + now + (1000..9999).random())
            .put("ts", now)
            .put("number", rawNumber)
            .put("caller", c.caller)
            .put("viaAcademy", c.viaAcademy)
            .put("contact", contact ?: "")
            .put("state", if (answered) "answered" else "missed")
            .put("at", iso(now))
            .put("durationSec", if (answered) ((now - answeredAt) / 1000L) else 0L)
            .put("smsState", "none")
            .put("smsPending", false)
        if (!answered) {
            try { maybeSendMissedSms(context, cfg, c, ev) } catch (e: Exception) { AppLogger.e(TAG, "missed SMS failed", e) }
        }
        addEvent(context, ev)
    }

    // ---- caller-ID notification ---------------------------------------------

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "شناسایی تماس", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "نمایش نام شاگرد هنگام زنگ خوردن تلفن"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun showCallerNotification(context: Context, cfg: JSONObject, raw: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
            ensureChannel(context)
            val c = parseCaller(raw, cfg)
            val person = lookup(cfg, c.caller)
            val label = cfg.optJSONObject("academy")?.optString("label", "آکادمی") ?: "آکادمی"
            val contact = if (person == null) contactName(context, c.caller) else null
            val who = when {
                person != null && person.name.isNotBlank() -> person.name
                contact != null -> contact
                else -> c.caller
            }
            val title = (if (c.viaAcademy) "🏫 $label — " else "📞 ") + who
            val text = when {
                person != null && person.info.isNotBlank() -> person.info
                contact != null -> "مخاطبِ گوشی — شاگردِ ثبت‌شده نیست"
                person == null -> "شمارهٔ ناشناس"
                else -> c.caller
            }
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
            val pi = if (launch != null) PendingIntent.getActivity(
                context, NOTIF_ID, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ) else null
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setTimeoutAfter(90_000L)
                .apply { if (pi != null) setContentIntent(pi) }
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID, n)
        } catch (e: Exception) {
            AppLogger.e(TAG, "caller notification failed", e)
        }
    }

    private fun cancelNotification(context: Context) {
        try { NotificationManagerCompat.from(context).cancel(NOTIF_ID) } catch (_: Exception) {}
    }

    // ---- missed-call SMS -----------------------------------------------------

    private fun inRange(hour: Int, from: Int, to: Int): Boolean =
        if (from == to) false else if (from < to) hour in from until to else (hour >= from || hour < to)

    private fun fa(n: Int): String {
        val s = n.toString().padStart(2, '0') + ":۰۰"
        val map = "۰۱۲۳۴۵۶۷۸۹"
        return s.map { if (it in '0'..'9') map[it - '0'] else it }.joinToString("")
    }

    private fun maybeSendMissedSms(context: Context, cfg: JSONObject, c: Caller, ev: JSONObject) {
        val ms = cfg.optJSONObject("missedSms") ?: return
        val mobile = normalizeMobile(c.caller) ?: return // a landline / foreign number cannot get this SMS
        val person = lookup(cfg, mobile)
        val known = person != null
        if (known && !ms.optBoolean("known", false)) return
        if (!known && !ms.optBoolean("unknown", false)) return

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val quiet = ms.optJSONObject("quiet")
        if (quiet != null && quiet.optBoolean("on", false) &&
            inRange(hour, quiet.optInt("from", 22), quiet.optInt("to", 8))
        ) { ev.put("smsReason", "ساعتِ آرام"); return }

        val p = prefs(context)
        val last = p.getLong("last_$mobile", 0L)
        val cool = ms.optLong("cooldownMin", 60L).coerceAtLeast(1L) * 60_000L
        if (System.currentTimeMillis() - last < cool) { ev.put("smsReason", "پیامکِ اخیر"); return }

        val ah = ms.optJSONObject("afterHours")
        var text = if (known) ms.optString("textKnown") else ms.optString("textUnknown")
        if (ah != null && ah.optBoolean("on", false)) {
            val from = ah.optInt("from", 10)
            val to = ah.optInt("to", 20)
            val jsDay = cal.get(Calendar.DAY_OF_WEEK) - 1 // JS getDay(): 0 = Sunday ... 5 = Friday
            var closed = false
            val cd = ah.optJSONArray("closedDays")
            if (cd != null) for (i in 0 until cd.length()) if (cd.optInt(i, -1) == jsDay) closed = true
            if (closed || !inRange(hour, from, to)) {
                val t = ah.optString("text")
                if (t.isNotBlank()) text = t
                text = text.replace("{from}", fa(from)).replace("{to}", fa(to))
            }
        }
        val name = person?.name ?: ""
        text = if (name.isBlank()) text.replace(" {name} عزیز", "").replace("{name}", "") else text.replace("{name}", name)
        if (text.isBlank()) return

        val tag = "call_" + ev.optString("id")
        val r = SmsSender.sendTagged(context, mobile, text, tag)
        ev.put("smsText", text)
        if (r == "queued") {
            ev.put("smsPending", true)
            p.edit().putLong("last_$mobile", System.currentTimeMillis()).apply()
        } else {
            ev.put("smsState", "failed").put("smsReason", r)
        }
    }

    /** Called by [SmsReports] when the radio reports the missed-call SMS result. */
    @Synchronized
    fun onSmsSent(context: Context, eventId: String, ok: Boolean, reason: String) {
        try {
            val arr = JSONArray(prefs(context).getString(KEY_EVENTS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("id") == eventId) {
                    o.put("smsPending", false)
                    o.put("smsState", if (ok) "sent" else "failed")
                    if (!ok) o.put("smsReason", reason)
                }
            }
            prefs(context).edit().putString(KEY_EVENTS, arr.toString()).apply()
        } catch (e: Exception) {
            AppLogger.e(TAG, "onSmsSent failed", e)
        }
    }

    // ---- events (read by the page) ---------------------------------------------

    @Synchronized
    private fun addEvent(context: Context, ev: JSONObject) {
        try {
            val arr = JSONArray(prefs(context).getString(KEY_EVENTS, "[]") ?: "[]")
            arr.put(ev)
            val out = JSONArray()
            for (i in (arr.length() - MAX_EVENTS).coerceAtLeast(0) until arr.length()) out.put(arr.get(i))
            prefs(context).edit().putString(KEY_EVENTS, out.toString()).apply()
        } catch (e: Exception) {
            AppLogger.e(TAG, "addEvent failed", e)
        }
    }

    /** Events whose SMS result is still unknown are held back for up to 90 s, then handed over as "sent". */
    @Synchronized
    fun readEvents(context: Context): String {
        return try {
            val arr = JSONArray(prefs(context).getString(KEY_EVENTS, "[]") ?: "[]")
            val now = System.currentTimeMillis()
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optBoolean("smsPending", false)) {
                    if (now - o.optLong("ts", 0L) < SMS_WAIT_MS) continue
                    val copy = JSONObject(o.toString())
                    copy.put("smsState", "sent").put("smsPending", false)
                    out.put(copy)
                } else {
                    out.put(o)
                }
            }
            out.toString()
        } catch (e: Exception) {
            "[]"
        }
    }

    @Synchronized
    fun clearEvents(context: Context, ids: Collection<String>) {
        try {
            val set = ids.toSet()
            val arr = JSONArray(prefs(context).getString(KEY_EVENTS, "[]") ?: "[]")
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!set.contains(o.optString("id"))) out.put(o)
            }
            prefs(context).edit().putString(KEY_EVENTS, out.toString()).apply()
        } catch (e: Exception) {
            AppLogger.e(TAG, "clearEvents failed", e)
        }
    }
}

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val pending = goAsync()
        try {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            CallMonitor.onPhoneState(context, state, number)
        } catch (e: Exception) {
            AppLogger.e("CallStateReceiver", "onReceive failed", e)
        } finally {
            try { pending.finish() } catch (_: Exception) {}
        }
    }
}
