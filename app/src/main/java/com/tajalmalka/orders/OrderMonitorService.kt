package com.tajalmalka.orders

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.webkit.CookieManager
import androidx.core.app.NotificationManagerCompat
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class OrderMonitorService : Service() {

    private var scheduler: ScheduledExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        startForeground(NotificationHelper.MONITOR_NOTIFICATION_ID, NotificationHelper.monitorNotification(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (scheduler == null || scheduler?.isShutdown == true) {
            scheduler = Executors.newSingleThreadScheduledExecutor().also { executor ->
                executor.scheduleWithFixedDelay({ checkOrdersSafely() }, 5, 30, TimeUnit.SECONDS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scheduler?.shutdownNow()
        scheduler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkOrdersSafely() {
        try {
            checkOrders()
        } catch (_: Exception) {
            // Network failures are ignored; the next scheduled pass retries automatically.
        }
    }

    private fun checkOrders() {
        val connection = (URL(MainActivity.TARGET_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TajAlmalkaOrdersAndroid/1.0")
            CookieManager.getInstance().getCookie(MainActivity.TARGET_URL)?.let {
                setRequestProperty("Cookie", it)
            }
        }

        val status = connection.responseCode
        if (status !in 200..299) {
            connection.disconnect()
            return
        }

        val finalUrl = connection.url.toString().lowercase()
        val html = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        if (finalUrl.contains("login") || looksLikeLoginPage(html)) return

        val fingerprint = extractOrderFingerprint(html)
        if (fingerprint.isBlank()) return

        val prefs = getSharedPreferences("order_monitor", MODE_PRIVATE)
        val previous = prefs.getString("last_fingerprint", null)

        if (previous == null) {
            prefs.edit().putString("last_fingerprint", fingerprint).apply()
            return
        }

        if (previous != fingerprint) {
            prefs.edit().putString("last_fingerprint", fingerprint).apply()
            NotificationHelper.showNewOrder(this, "تم رصد طلب جديد أو تغيير في قائمة الطلبات")
        }
    }

    private fun looksLikeLoginPage(html: String): Boolean {
        val lower = html.lowercase()
        return (lower.contains("type=\"password\"") || lower.contains("type='password'")) &&
            (lower.contains("login") || lower.contains("تسجيل الدخول"))
    }

    private fun extractOrderFingerprint(html: String): String {
        val ids = linkedSetOf<String>()

        Regex("data-order-id\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { ids += it.groupValues[1] }

        Regex("(?:order[_-]?id|orderId)\\s*[:=]\\s*[\\\"']?(\\d+)", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { ids += it.groupValues[1] }

        Regex("/orders?/([0-9]+)", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { ids += it.groupValues[1] }

        if (ids.isNotEmpty()) {
            return sha256(ids.sorted().joinToString("|"))
        }

        val tbody = Regex("<tbody[^>]*>(.*?)</tbody>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: html

        val cleaned = tbody
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<input[^>]*(?:csrf|_token)[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(600_000)

        return if (cleaned.isBlank()) "" else sha256(cleaned)
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
