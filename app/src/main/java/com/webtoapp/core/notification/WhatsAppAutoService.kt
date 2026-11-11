package com.webtoapp.core.notification

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.webtoapp.core.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Drives WhatsApp for one pending receipt job (max 25 s), only inside WhatsApp windows:
 *  - image mode: wa.me chat opens -> paste the receipt image from the clipboard into the
 *    message box -> (optional caption) -> press Send.
 *  - text mode: wa.me chat opens with the text prefilled -> press Send.
 */
class WhatsAppAutoService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in WhatsAppAuto.PACKAGES) return
        val job = WhatsAppAuto.job ?: return
        if (job.armed) return
        val root = rootInActiveWindow ?: return

        if (job.mode == "paste" && job.stage == 0) {
            if (findInput(root, pkg) == null) return
            job.armed = true
            handler.postDelayed({ doPaste(job, pkg) }, 1300L)
            return
        }
        if (findSend(root, pkg) == null) return
        job.armed = true
        handler.postDelayed({ clickSend(job, pkg) }, 900L)
    }

    override fun onInterrupt() {}

    private fun doPaste(job: WhatsAppAuto.Job, pkg: String) {
        if (WhatsAppAuto.job !== job) return
        val root = rootInActiveWindow
        val input = if (root != null) findInput(root, pkg) else null
        if (input == null) { job.armed = false; return }
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        job.pasteTries++
        job.stage = 1
        job.armed = false
        // if the image preview never shows up, try pasting once more
        handler.postDelayed({
            if (WhatsAppAuto.job === job && job.stage == 1 && !job.clicked && job.pasteTries < 2) job.stage = 0
        }, 6000L)
    }

    private fun clickSend(job: WhatsAppAuto.Job, pkg: String) {
        if (WhatsAppAuto.job !== job) return
        val root = rootInActiveWindow
        val btn = if (root != null) findSend(root, pkg) else null
        if (root == null || btn == null) { job.armed = false; return }

        if (job.mode == "paste" && job.caption.isNotBlank() && !job.captionSet) {
            val field = findCaption(root, pkg)
            if (field != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, job.caption)
                }
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                job.captionSet = true
                handler.postDelayed({ clickSend(job, pkg) }, 700L)
                return
            }
        }

        var node: AccessibilityNodeInfo? = btn
        var clicked = false
        while (node != null && !clicked) {
            if (node.isClickable) clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node = node.parent
        }
        if (!clicked) { job.armed = false; return }
        job.clicked = true
        handler.postDelayed({ WhatsAppAuto.finish(applicationContext, job.tag, "sent", "") }, 1500L)
    }

    private fun findInput(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$pkg:id/entry")?.firstOrNull { it.isVisibleToUser }?.let { return it }
        return findEditable(root, 0)
    }

    private fun findCaption(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$pkg:id/caption")?.firstOrNull { it.isVisibleToUser }?.let { return it }
        return findEditable(root, 0)
    }

    private fun findEditable(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 25) return null
        if (n.className?.toString() == "android.widget.EditText" && n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    private fun findSend(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$pkg:id/send")
            ?.firstOrNull { it.isVisibleToUser }?.let { return it }
        return findByDesc(root, 0)
    }

    private fun findByDesc(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (n == null || depth > 25) return null
        val d = n.contentDescription?.toString()?.trim()
        if (d != null && d in SEND_LABELS && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) findByDesc(n.getChild(i), depth + 1)?.let { return it }
        return null
    }

    companion object {
        private val SEND_LABELS = setOf("Send", "ارسال", "إرسال", "Enviar")
    }
}

object WhatsAppAuto {
    val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    private const val PREFS = "wa_auto_prefs"
    private const val JOB_TIMEOUT_MS = 25_000L
    private val main = Handler(Looper.getMainLooper())

    class Job(val tag: String, val startedAt: Long, val mode: String, val caption: String) {
        @Volatile var armed = false
        @Volatile var stage = 0          // paste mode: 0 = waiting to paste, 1 = pasted
        @Volatile var pasteTries = 0
        @Volatile var captionSet = false
        @Volatile var clicked = false
    }

    @Volatile var job: Job? = null

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: Exception) { false }

    fun isServiceEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, WhatsAppAutoService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun status(ctx: Context): String = JSONObject()
        .put("serviceEnabled", isServiceEnabled(ctx))
        .put("whatsapp", isInstalled(ctx, "com.whatsapp"))
        .put("business", isInstalled(ctx, "com.whatsapp.w4b"))
        .put("sdk", Build.VERSION.SDK_INT)
        .toString()

    /**
     * [text] is the caption in image mode (may be empty) or the whole message in text mode.
     * Returns "queued" (result arrives later via [readResults]) or
     * "invalid" / "no_service" / "no_whatsapp" / "busy" / "error".
     */
    fun send(ctx: Context, phone: String, text: String, base64Png: String, tag: String): String {
        return try {
            val d = phone.filter { it.isDigit() }
            val num = when {
                d.startsWith("98") && d.length == 12 -> d
                d.startsWith("09") && d.length == 11 -> "98" + d.substring(1)
                d.startsWith("9") && d.length == 10 -> "98$d"
                else -> return "invalid"
            }
            if (!isServiceEnabled(ctx)) return "no_service"
            // The academy number lives in WhatsApp Business, so prefer it when both are installed.
            val pkg = when {
                isInstalled(ctx, "com.whatsapp.w4b") -> "com.whatsapp.w4b"
                isInstalled(ctx, "com.whatsapp") -> "com.whatsapp"
                else -> return "no_whatsapp"
            }
            val cur = job
            if (cur != null && System.currentTimeMillis() - cur.startedAt < JOB_TIMEOUT_MS + 5000) return "busy"
            val safeTag = tag.filter { it.isLetterOrDigit() || it == '_' }.take(60).ifEmpty { "wa_${System.currentTimeMillis()}" }

            var clipUri: Uri? = null
            val intent: Intent
            val mode: String
            if (base64Png.isNotBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bytes = Base64.decode(base64Png, Base64.DEFAULT)
                clipUri = saveImage(ctx, bytes, "receipt-$safeTag.png") ?: return "error"
                mode = "paste"
                intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num")).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                mode = "text"
                intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num?text=" + Uri.encode(text))).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            job = Job(safeTag, System.currentTimeMillis(), mode, if (mode == "paste") text else "")
            main.post {
                try {
                    if (clipUri != null) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, "receipt", clipUri))
                    }
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    AppLogger.w("WhatsAppAuto", "start failed", e)
                    finish(ctx, safeTag, "failed", "start_failed")
                }
            }
            main.postDelayed({
                val j = job
                if (j != null && j.tag == safeTag) finish(ctx, safeTag, "failed", "timeout")
            }, JOB_TIMEOUT_MS)
            "queued"
        } catch (e: Exception) {
            AppLogger.e("WhatsAppAuto", "send failed", e)
            "error"
        }
    }

    fun finish(ctx: Context, tag: String, status: String, reason: String) {
        val j = job
        if (j == null || j.tag != tag) return
        job = null
        addResult(ctx, tag, status, reason)
        try {
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                ctx.startActivity(it)
            }
        } catch (e: Exception) {
            AppLogger.w("WhatsAppAuto", "could not return to app", e)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveImage(ctx: Context, bytes: ByteArray, name: String): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AcademyReceipts")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        return uri
    }

    @Synchronized private fun addResult(ctx: Context, tag: String, status: String, reason: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(p.getString("results", "[]"))
        arr.put(JSONObject().put("tag", tag).put("status", status).put("reason", reason).put("at", System.currentTimeMillis()))
        p.edit().putString("results", arr.toString()).apply()
    }

    @Synchronized fun readResults(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("results", "[]") ?: "[]"

    @Synchronized fun removeResults(ctx: Context, tags: List<String>) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = JSONArray(p.getString("results", "[]"))
        val keep = JSONArray()
        for (i in 0 until old.length()) { val o = old.getJSONObject(i); if (o.optString("tag") !in tags) keep.put(o) }
        p.edit().putString("results", keep.toString()).apply()
    }
}
