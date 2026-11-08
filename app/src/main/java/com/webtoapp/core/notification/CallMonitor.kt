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
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
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
 * Incoming-call / incoming-SMS helper driven by the page (see NativeBridge.setCallConfig):
 *  - shows a caller-ID notification (name + info from the student directory) while the phone rings,
 *  - sends a "missed call" SMS (known / unknown number, after-hours text, quiet hours, cooldown),
 *  - shows a sender-ID notification when an SMS arrives from a registered number (student / teacher),
 *  - keeps a list of call / SMS events that the page reads with getCallEvents().
 *
 * Calls forwarded from the academy line arrive as "<prefix>[98]<mobile>"
 * (e.g. +9821 98 9381002449 or +9821 9038112389); the real caller is the part after the prefix.
 *
 * Every call is classified into a scope:
 *  - "known"   : the real number is in the directory (student / registered in the app)
 *  - "academy" : not in the directory, but forwarded from the academy line (maybe a new student)
 *  - "contact" : not in the directory, no academy prefix, but saved in the phone contacts -> shown by name, no auto SMS
 *  - "direct"  : a non-registered number that is not in the phone contacts -> completely ignored
 *                (no popup, no SMS, no contact lookup, nothing stored).
 * Incoming SMS is handled only for "known" numbers; everything else is ignored and never stored.
 */
object CallMonitor {
    private const val PREFS = "bridge_call"
    private const val KEY_CFG = "cfg"
    private const val KEY_EVENTS = "events"
    private const val CHANNEL_ID = "call_caller_id"
    private const val NOTIF_ID = 7710
    private const val SMS_CHANNEL_ID = "sms_sender_id"
    private const val SMS_NOTIF_ID = 7711
    private const val SMS_PREVIEW_MAX = 160
    private const val MAX_EVENTS = 200
    private const val SMS_WAIT_MS = 90_000L
    private const val TAG = "CallMonitor"

    /** Shown in the app's "health & test" panel so you can see at a glance that the new APK is really installed. */
    const val BUILD_TAG = "2026-11-08.8"

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

    /**
     * Academy-line prefixes from the config. Entries are separated by comma / ، / ; only
     * (spaces stay INSIDE an entry, so "+9821 98" is one prefix). A prefix that ends with "98"
     * (people copy the example "+9821 98 9381002449") is also tried without that "98", because
     * the forwarded number is sometimes "<prefix>98<mobile>" and sometimes just "<prefix><mobile>".
     * Prefixes shorter than 3 digits are ignored: "98" alone would match every Iranian mobile.
     */
    private fun academyPrefixes(cfg: JSONObject): List<String> {
        val academy = cfg.optJSONObject("academy") ?: return emptyList()
        if (!academy.optBoolean("on", false)) return emptyList()
        val arr = academy.optJSONArray("prefixes") ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            for (part in arr.optString(i).split(Regex("[,،;]+"))) {
                val p = part.filter { it.isDigit() }
                if (p.length < 3) continue
                out.add(p)
                if (p.length > 4 && p.endsWith("98")) out.add(p.dropLast(2))
            }
        }
        return out.toList()
    }

    private fun parseCaller(raw: String, cfg: JSONObject): Caller {
        val digits = raw.filter { it.isDigit() }
        for (p in academyPrefixes(cfg)) {
            if (digits.startsWith(p)) {
                val rest = normalizeMobile(digits.substring(p.length))
                if (rest != null) return Caller(raw, rest, true)
            }
        }
        return Caller(raw, normalizeMobile(digits) ?: raw, false)
    }

    /** "known" (registered in the app) / "academy" (forwarded from the academy line) / "direct" (ignored). */
    private fun scopeOf(context: Context, cfg: JSONObject, c: Caller): String {
        val mobile = normalizeMobile(c.caller)
        if (mobile != null && lookup(cfg, mobile) != null) return "known"
        if (c.viaAcademy) return "academy"
        // Not registered and no academy prefix, but saved in the manager's phone contacts:
        // the carrier sometimes delivers a forwarded call with the plain caller number.
        if (mobile != null && contactName(context, mobile) != null) return "contact"
        return "direct"
    }

    private class Person(val name: String, val info: String, val teacher: Boolean = false, val lead: Boolean = false)

    private var nameCacheKey = ""
    private var nameCacheVal: String? = null
    private var nameCacheAt = 0L

    /**
     * Name saved for this number in the phone's own contacts (needs READ_CONTACTS; null when missing/not granted).
     * Made robust against "works once, fails once": the system lookup is tried twice (the contacts provider can be
     * cold when the receiver wakes the app), then every saved number is compared by its last 10 digits (handles
     * spaces / odd formats), and a found name is remembered for a minute so the notification and the call record agree.
     */
    private fun contactName(context: Context, mobile: String): String? {
        try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) return null
            val digits = mobile.filter { it.isDigit() }
            if (digits.length < 7) return null
            val now = System.currentTimeMillis()
            if (nameCacheKey == digits && nameCacheVal != null && now - nameCacheAt < 60_000L) return nameCacheVal
            var found: String? = phoneLookupName(context, mobile, digits)
            if (found == null) {
                try { Thread.sleep(250) } catch (_: InterruptedException) {}
                found = phoneLookupName(context, mobile, digits)
            }
            if (found == null) found = scanContactsByTail(context, digits)
            if (found != null) {
                nameCacheKey = digits
                nameCacheVal = found
                nameCacheAt = now
            }
            return found
        } catch (e: Exception) {
            AppLogger.w(TAG, "contact lookup failed", e)
        }
        return null
    }

    private fun phoneLookupName(context: Context, mobile: String, digits: String): String? {
        try {
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
            AppLogger.w(TAG, "phone lookup failed", e)
        }
        return null
    }

    private fun scanContactsByTail(context: Context, digits: String): String? {
        try {
            val tail = digits.takeLast(10)
            if (tail.length < 10) return null
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val num = (cur.getString(0) ?: "").filter { it.isDigit() }
                    if (num.length >= 10 && num.takeLast(10) == tail) {
                        val n = cur.getString(1)
                        if (!n.isNullOrBlank()) return n
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "contact scan failed", e)
        }
        return null
    }

    private fun lookup(cfg: JSONObject, mobile: String): Person? {
        val dir = cfg.optJSONArray("directory") ?: return null
        for (i in 0 until dir.length()) {
            val o = dir.optJSONObject(i) ?: continue
            if (o.optString("p") == mobile) return Person(o.optString("n"), o.optString("i"), o.optInt("t", 0) == 1, o.optInt("l", 0) == 1)
        }
        return null
    }

    // ---- diagnostics (health & number test panel in the app) ---------------------

    /** Counts only (never numbers / texts) of calls & SMS ignored today because the sender is not registered. */
    private fun bumpIgnored(context: Context, key: String) {
        try {
            val p = prefs(context)
            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val e = p.edit()
            if (p.getString("ign_day", "") != day) e.putString("ign_day", day).putInt("ign_calls", 0).putInt("ign_sms", 0)
            e.apply()
            p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
        } catch (_: Exception) {}
    }

    fun notificationsAllowed(context: Context): Boolean {
        val permOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permOk && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * What the phone would do for [raw] (a number as it appears in the call log, e.g. +98219038112389),
     * using the SAME code and the SAME stored config as a real call. Empty [raw] = status only.
     */
    fun diagnose(context: Context, raw: String): String {
        val o = JSONObject()
        try {
            val app = context.applicationContext
            val cfg = config(app)
            val p = prefs(app)
            val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val sameDay = p.getString("ign_day", "") == today
            o.put("build", BUILD_TAG)
            o.put("hasConfig", cfg != null)
            o.put("monitor", cfg?.optBoolean("monitor", false) ?: false)
            o.put("directorySize", cfg?.optJSONArray("directory")?.length() ?: 0)
            o.put("ignoredCallsToday", if (sameDay) p.getInt("ign_calls", 0) else 0)
            o.put("ignoredSmsToday", if (sameDay) p.getInt("ign_sms", 0) else 0)
            o.put(
                "perm",
                JSONObject()
                    .put("call", hasPermissions(app))
                    .put("contacts", ContextCompat.checkSelfPermission(app, android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
                    .put("smsIn", hasSmsReceivePermission(app))
                    .put("notif", notificationsAllowed(app))
                    .put("overlay", canShowOverlay(app))
            )
            if (cfg != null) {
                o.put("prefixes", JSONArray(academyPrefixes(cfg)))
                if (raw.isNotBlank()) {
                    val c = parseCaller(raw, cfg)
                    val scope = scopeOf(app, cfg, c)
                    val mobile = normalizeMobile(c.caller)
                    val person = if (mobile != null) lookup(cfg, mobile) else null
                    val ms = cfg.optJSONObject("missedSms")
                    val smsKind = when {
                        scope == "direct" || scope == "contact" || person?.teacher == true || mobile == null -> "none"
                        person != null && !person.lead -> "known"
                        else -> "unknown"
                    }
                    o.put("caller", c.caller)
                    o.put("via", c.viaAcademy)
                    o.put("scope", scope)
                    o.put("person", person?.name ?: "")
                    o.put("lead", person?.lead ?: false)
                    o.put("contact", contactName(app, c.caller) ?: "")
                    o.put("popup", scope != "direct" && cfg.optBoolean("popup", false))
                    o.put("smsKind", smsKind)
                    o.put(
                        "smsEnabled",
                        ms != null && when (smsKind) {
                            "known" -> ms.optBoolean("known", false)
                            "unknown" -> ms.optBoolean("unknown", false)
                            else -> false
                        }
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "diagnose failed", e)
        }
        return o.toString()
    }

    /**
     * Calls made AFTER a missed call: outgoing calls (any duration) and answered incoming calls since [sinceMs].
     * The page uses it to close "missed call" cards automatically. Result: [{"n":"09…","t":ms,"d":"out"|"in"}].
     */
    fun callbacksSince(context: Context, sinceMs: Long): String {
        val out = JSONArray()
        try {
            if (!hasPermissions(context)) return "[]"
            val app = context.applicationContext
            val cfg = config(app)
            val since = sinceMs.coerceAtLeast(System.currentTimeMillis() - 3L * 24 * 3600 * 1000)
            app.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                CallLog.Calls.DATE + " >= ?",
                arrayOf(since.toString()),
                CallLog.Calls.DATE + " DESC"
            )?.use { cur ->
                var rows = 0
                while (cur.moveToNext() && rows < 500) {
                    rows++
                    val num = cur.getString(0) ?: continue
                    val type = cur.getInt(1)
                    val date = cur.getLong(2)
                    val dur = cur.getLong(3)
                    val dir = when {
                        type == CallLog.Calls.OUTGOING_TYPE -> "out"
                        type == CallLog.Calls.INCOMING_TYPE && dur > 0L -> "in"
                        else -> continue
                    }
                    val real = if (cfg != null) parseCaller(num, cfg).caller else num
                    val mobile = normalizeMobile(real) ?: continue
                    out.put(JSONObject().put("n", mobile).put("t", date).put("d", dir))
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "callbacksSince failed", e)
        }
        return out.toString()
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
                val stored = p.getString("r_num", "") ?: ""
                val num = when {
                    number.isNullOrBlank() -> stored
                    stored.isBlank() -> number
                    normalizeMobile(parseCaller(stored, cfg).caller) != null -> stored // keep the first usable number of this ring
                    else -> number
                }
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
        val scope = scopeOf(context, cfg, c)
        if (scope == "direct") { bumpIgnored(context, "ign_calls"); return } // a non-registered number calling the manager's own phone: not our business
        val answered = answeredAt > 0L
        val contact = if (scope == "academy" || scope == "contact") contactName(context, c.caller) else null
        val ev = JSONObject()
            .put("id", "c" + now + (1000..9999).random())
            .put("kind", "call")
            .put("scope", scope)
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
        if (!answered && scope != "contact") { // no automatic SMS to people who are only in the phone contacts
            try { maybeSendMissedSms(context, cfg, c, ev) } catch (e: Exception) { AppLogger.e(TAG, "missed SMS failed", e) }
        }
        addEvent(context, ev)
    }

    // ---- on-screen caller card (drawn over the phone's own call screen) -------------

    private var overlayView: android.view.View? = null
    private val overlayHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val overlayAutoHide = Runnable { removeOverlayNow() }
    private var overlayCtx: Context? = null

    /** True when the user allowed "Appear on top" (Settings > Apps > this app) for the app. */
    fun canShowOverlay(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)

    private fun removeOverlayNow() {
        try {
            val v = overlayView ?: return
            val app = overlayCtx ?: return
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            wm.removeView(v)
        } catch (e: Exception) {
            AppLogger.w(TAG, "overlay remove failed", e)
        } finally {
            overlayView = null
            overlayHandler.removeCallbacks(overlayAutoHide)
        }
    }

    private fun hideOverlay() {
        overlayHandler.post { removeOverlayNow() }
    }

    private class CallHistory(val total: Int, val missed: Int, val last: Long)

    /** Earlier incoming calls from this caller, read from the phone's call log (forwarded "+9821…" numbers are unwrapped). */
    private fun callHistory(context: Context, cfg: JSONObject, mobile: String): CallHistory {
        var total = 0
        var missed = 0
        var last = 0L
        try {
            if (!hasPermissions(context)) return CallHistory(0, 0, 0L)
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null, CallLog.Calls.DATE + " DESC"
            )?.use { cur ->
                var rows = 0
                while (cur.moveToNext() && rows < 1000) {
                    rows++
                    val type = cur.getInt(1)
                    if (type != CallLog.Calls.INCOMING_TYPE && type != CallLog.Calls.MISSED_TYPE &&
                        type != CallLog.Calls.REJECTED_TYPE
                    ) continue
                    val num = cur.getString(0) ?: continue
                    if (parseCaller(num, cfg).caller != mobile) continue
                    total++
                    if (type != CallLog.Calls.INCOMING_TYPE) missed++
                    val ts = cur.getLong(2)
                    if (ts > last) last = ts
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "call history failed", e)
        }
        return CallHistory(total, missed, last)
    }

    private fun faDigits(s: String): String {
        val map = "۰۱۲۳۴۵۶۷۸۹"
        val sb = StringBuilder()
        for (ch in s) sb.append(if (ch in '0'..'9') map[ch - '0'] else ch)
        return sb.toString()
    }

    private fun dayStart(ms: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun whenText(ts: Long): String {
        val days = Math.round((dayStart(System.currentTimeMillis()) - dayStart(ts)) / 86_400_000.0).toInt()
        val hm = SimpleDateFormat("HH:mm", Locale.US).format(Date(ts))
        return when {
            days <= 0 -> "امروز $hm"
            days == 1 -> "دیروز $hm"
            else -> "$days روز پیش"
        }
    }

    private fun showOverlay(
        context: Context, header: String, name: String, info: String,
        history: String, color: Int, phone: String
    ) {
        try {
            if (!canShowOverlay(context)) return
            val app = context.applicationContext
            overlayHandler.post {
                try {
                    removeOverlayNow()
                    overlayCtx = app
                    val d = app.resources.displayMetrics.density
                    val box = android.widget.LinearLayout(app)
                    box.orientation = android.widget.LinearLayout.VERTICAL
                    val pad = (14 * d).toInt()
                    box.setPadding(pad, pad, pad, pad)
                    val bg = android.graphics.drawable.GradientDrawable()
                    bg.setColor(color)
                    bg.cornerRadius = 18 * d
                    box.background = bg

                    fun line(txt: String, sizeSp: Float, argb: Int, bold: Boolean) {
                        if (txt.isBlank()) return
                        val tv = android.widget.TextView(app)
                        tv.text = txt
                        tv.setTextColor(argb)
                        tv.textSize = sizeSp
                        if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
                        tv.gravity = android.view.Gravity.CENTER
                        box.addView(tv)
                    }
                    line(header, 13f, 0xFFE8EAED.toInt(), false)
                    line(name, 22f, 0xFFFFFFFF.toInt(), true)
                    line(info, 14f, 0xFFFFFFFF.toInt(), false)
                    line(history, 13f, 0xFFE8EAED.toInt(), false)

                    val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        // Same extra as the notification: the page opens the caller's student page.
                        if (phone.isNotBlank()) putExtra("wta_open_phone", phone)
                    }
                    if (launch != null) {
                        val btn = android.widget.TextView(app)
                        btn.text = "📂 باز کردن در اپ"
                        btn.setTextColor(0xFFFFFFFF.toInt())
                        btn.textSize = 14f
                        btn.setTypeface(btn.typeface, android.graphics.Typeface.BOLD)
                        btn.gravity = android.view.Gravity.CENTER
                        val bb = android.graphics.drawable.GradientDrawable()
                        bb.setColor(0x33FFFFFF)
                        bb.cornerRadius = 12 * d
                        btn.background = bb
                        val bp = (8 * d).toInt()
                        btn.setPadding(bp * 2, bp, bp * 2, bp)
                        val blp = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        blp.topMargin = (10 * d).toInt()
                        btn.layoutParams = blp
                        btn.setOnClickListener {
                            try { app.startActivity(launch) } catch (e: Exception) { AppLogger.w(TAG, "overlay open failed", e) }
                            removeOverlayNow()
                        }
                        box.addView(btn)
                    }

                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE
                    val lp = android.view.WindowManager.LayoutParams(
                        app.resources.displayMetrics.widthPixels - (32 * d).toInt(),
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        // touchable (for the open button) but never focusable, so the call screen keeps working
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT
                    )
                    lp.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                    lp.y = (56 * d).toInt()

                    val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    wm.addView(box, lp)
                    overlayView = box
                    overlayHandler.postDelayed(overlayAutoHide, 90_000L)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "overlay show failed", e)
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "overlay failed", e)
        }
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
            val notifOk = !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)
            val c = parseCaller(raw, cfg)
            if (scopeOf(context, cfg, c) == "direct") return // not a student / not from the academy line
            ensureChannel(context)
            val person = lookup(cfg, c.caller)
            val label = cfg.optJSONObject("academy")?.optString("label", "آکادمی") ?: "آکادمی"
            val contact = if (person == null) contactName(context, c.caller) else null
            val who = when {
                person != null && person.name.isNotBlank() -> person.name
                contact != null -> contact
                else -> c.caller
            }
            val title = (if (c.viaAcademy) "🏫 $label — " else "📞 مستقیم — ") + who
            val text = when {
                person != null && person.info.isNotBlank() -> person.info
                contact != null -> if (c.viaAcademy) "مخاطبِ گوشی — تماس به خطِ آکادمی (دایورت)" else "مخاطبِ گوشی — تماس مستقیم به خطِ شما"
                person == null -> "شمارهٔ ناشناس"
                else -> c.caller
            }
            // on-screen card above the phone's call screen (needs "Appear on top")
            val kindLabel = when {
                person?.teacher == true -> "👨‍🏫 مدرس"
                person?.lead == true -> "🎯 علاقه‌مند"
                person != null -> "🎓 شاگرد"
                contact != null -> "📇 مخاطبِ گوشی"
                else -> "❓ ناشناس"
            }
            val kindColor = when {
                person?.teacher == true -> 0xF21565C0.toInt()
                person?.lead == true -> 0xF2E65100.toInt()
                person != null -> 0xF22E7D32.toInt()
                contact != null -> 0xF2455A64.toInt()
                else -> 0xF2424242.toInt()
            }
            val hist = callHistory(context, cfg, c.caller)
            val histText = if (hist.total == 0) "اولین تماس ثبت‌شده" else faDigits(
                "تماس‌های قبلی: ${hist.total}" + (if (hist.missed > 0) " (${hist.missed} بی‌پاسخ)" else "") +
                    " · آخرین: " + whenText(hist.last)
            )
            val route = if (c.viaAcademy) "🏫 $label" else "📞 مستقیم"
            showOverlay(context, "$kindLabel · $route", who, text, histText, kindColor, c.caller)
            if (!notifOk) return
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    // The page reads this (NativeBridge.getLaunchAction) to open the caller's student page.
                    if (c.caller.isNotBlank()) putExtra("wta_open_phone", c.caller)
                }
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
        hideOverlay()
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
        if (person?.teacher == true) return // the "missed call" texts are written for students, not teachers
        val known = person != null && !person.lead // a "lead" (interested caller, not yet a student) gets the generic text
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

    // ---- incoming SMS (sender-ID) ---------------------------------------------------

    private fun ensureSmsChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(SMS_CHANNEL_ID) != null) return
        val ch = NotificationChannel(SMS_CHANNEL_ID, "شناسایی فرستندهٔ پیامک", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "نمایش نام شاگرد هنگام رسیدن پیامک از شمارهٔ ثبت‌شده"
            setSound(null, null) // the SMS app already makes the sound; this one only adds the name
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    fun hasSmsReceivePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * Called by [SmsInReceiver]. Works ONLY for numbers registered in the app (the directory sent by the page):
     * anything else (friends, family, banks, short codes, alphanumeric senders) is ignored — no notification,
     * no event, and the text is never stored.
     */
    @Synchronized
    fun onIncomingSms(context: Context, sender: String?, body: String?) {
        try {
            val app = context.applicationContext
            val cfg = config(app) ?: return
            if (!cfg.optBoolean("monitor", false)) return
            val si = cfg.optJSONObject("smsIn") ?: return
            if (!si.optBoolean("on", false)) return
            val mobile = normalizeMobile(sender ?: return)
            if (mobile == null) { bumpIgnored(app, "ign_sms"); return }
            val person = lookup(cfg, mobile)
            if (person == null) { bumpIgnored(app, "ign_sms"); return }
            val now = System.currentTimeMillis()
            val showText = si.optBoolean("preview", true)
            val preview = if (showText) (body ?: "").trim().replace(Regex("\\s+"), " ").take(SMS_PREVIEW_MAX) else ""
            showSmsNotification(app, person, mobile, preview)
            addEvent(
                app,
                JSONObject()
                    .put("id", "s" + now + (1000..9999).random())
                    .put("kind", "sms")
                    .put("scope", "known")
                    .put("ts", now)
                    .put("number", sender)
                    .put("caller", mobile)
                    .put("viaAcademy", false)
                    .put("contact", "")
                    .put("state", "received")
                    .put("at", iso(now))
                    .put("durationSec", 0)
                    .put("text", preview)
                    .put("smsState", "none")
                    .put("smsPending", false)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "incoming SMS handling failed", e)
        }
    }

    private fun showSmsNotification(context: Context, person: Person, mobile: String, preview: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
            ensureSmsChannel(context)
            val title = "💬 " + person.name.ifBlank { mobile }
            val text = if (preview.isNotBlank()) preview else "پیامک جدید"
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("wta_open_phone", mobile) // same hook as the caller-ID tap: opens the student's page
                }
            val pi = if (launch != null) PendingIntent.getActivity(
                context, mobile.hashCode(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ) else null
            val b = NotificationCompat.Builder(context, SMS_CHANNEL_ID)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
            if (person.info.isNotBlank()) b.setSubText(person.info)
            if (pi != null) b.setContentIntent(pi)
            // tag = number: a second SMS from the same person replaces the first instead of stacking
            NotificationManagerCompat.from(context).notify(mobile, SMS_NOTIF_ID, b.build())
        } catch (e: Exception) {
            AppLogger.e(TAG, "sms notification failed", e)
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

/** Registered in the manifest for android.provider.Telephony.SMS_RECEIVED (needs RECEIVE_SMS). */
class SmsInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        try {
            val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            if (msgs.isEmpty()) return
            val sender = msgs[0].originatingAddress
            val body = msgs.joinToString("") { it.messageBody ?: "" } // multipart SMS arrive as several parts
            CallMonitor.onIncomingSms(context, sender, body)
        } catch (e: Exception) {
            AppLogger.e("SmsInReceiver", "onReceive failed", e)
        }
    }
}
