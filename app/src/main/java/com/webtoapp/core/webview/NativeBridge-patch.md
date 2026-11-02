# تغییرات NativeBridge.kt

مسیر فایل در ریپو: جستجو کنید `NativeBridge.kt` (پکیج `com.webtoapp.core.webview`).
در هر مورد، متن «پیدا کن» را در فایل پیدا کنید (فقط یک‌بار وجود دارد) و با «جایگزین کن» عوضش کنید.
فایل `BridgeAlarmReceiver.kt` را هم کامل با نسخهٔ پیوست عوض کنید (`com/webtoapp/core/notification/`).

---

## تغییر ۱ (لازم) — ساخت کانال‌ها هنگام زمان‌بندی

**پیدا کن:**
```kotlin
            scheduleBridgeAlarm(triggerAtMillis, title, body, tag, channelId, deepLink, playSound)
```
**جایگزین کن:**
```kotlin
            BridgeAlarmReceiver.ensureChannels(context)
            scheduleBridgeAlarm(triggerAtMillis, title, body, tag, channelId, deepLink, playSound)
```

## تغییر ۲ (لازم) — کانال‌ها هنگام درخواست مجوز هم ساخته شوند

**پیدا کن:**
```kotlin
    fun requestNotificationPermission(): String {
        if (!capabilities.notification) return "denied"
```
**جایگزین کن:**
```kotlin
    fun requestNotificationPermission(): String {
        if (!capabilities.notification) return "denied"
        BridgeAlarmReceiver.ensureChannels(context)
```

## تغییر ۳ (لازم) — کانال‌ها قبل از باز شدن تنظیمات اعلان موجود باشند

**پیدا کن:**
```kotlin
    fun openNotificationSettings(): Boolean {
        if (!capabilities.notification) return false
        return try {
```
**جایگزین کن:**
```kotlin
    fun openNotificationSettings(): Boolean {
        if (!capabilities.notification) return false
        return try {
            BridgeAlarmReceiver.ensureChannels(context)
```

---

## تغییر ۴ (توصیه‌شده) — لغو واقعی آلارم‌های زمان‌بندی‌شده

در کد فعلی `cancelNotification` فقط اعلانِ «نمایش‌داده‌شده» را پاک می‌کند، نه آلارمِ در انتظار را؛
شناسهٔ آلارم هم به زمان وابسته است و بعداً قابل بازسازی نیست. نتیجه: یادآورِ حذف‌شده یا جابه‌جاشده
می‌تواند همچنان در زمان قدیمی اجرا شود. این تغییر آن را درست می‌کند.

**۴-الف. پیدا کن:**
```kotlin
            val requestCode = "$tag|$safeTriggerAt".hashCode()
```
**جایگزین کن:**
```kotlin
            val requestCode = tag.hashCode()
```

**۴-ب. پیدا کن:**
```kotlin
            NotificationManagerCompat.from(context).cancel(tag.hashCode())
            true
```
**جایگزین کن:**
```kotlin
            NotificationManagerCompat.from(context).cancel(tag.hashCode())
            cancelBridgeAlarm(tag)
            true
```

**۴-پ. پیدا کن:**
```kotlin
    private fun getAppLabel(): String {
```
**جایگزین کن (تابع جدید را قبل از آن اضافه کن):**
```kotlin
    private fun cancelBridgeAlarm(tag: String) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, BridgeAlarmReceiver::class.java).apply {
                action = BridgeAlarmReceiver.ACTION_SCHEDULED_NOTIFICATION
            }
            val pending = PendingIntent.getBroadcast(
                context,
                tag.hashCode(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return
            alarmManager.cancel(pending)
            pending.cancel()
        } catch (e: Exception) {
            AppLogger.w("NativeBridge", "Failed to cancel bridge alarm", e)
        }
    }

    private fun getAppLabel(): String {
```

---

## ساخت

برای ساخت از GitHub Actions یا Android Studio:
```
./gradlew :shell:assembleRelease :app:syncShellTemplateApk --no-configuration-cache
./gradlew assembleStandardRelease
```
بعد برنامهٔ ساخته‌شده را نصب کنید و APK آموزشگاه را با آن دوباره بسازید
(قالب `webview_shell.apk` داخل خود برنامهٔ WebToApp است).
