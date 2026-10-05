package com.tajalmalka.orders

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.webkit.CookieManager
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
            // Retry automatically on the next scheduled pass.
        }
    }

    private fun checkOrders() {
        val prefs = getSharedPreferences("order_monitor", MODE_PRIVATE)
        val targetUrl = prefs.getString("orders_url", null) ?: MainActivity.DEFAULT_ORDERS_URL

        val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TajAlmalkaOrdersAndroid/1.1")

            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(targetUrl)
                ?: cookieManager.getCookie("https://tajalmalka.com")
            if (!cookie.isNullOrBlank()) {
                setRequestProperty("Cookie", cookie)
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

        if (finalUrl.contains("login") || looksLikeLoginPage(html) || looksLikeNotFoundPage(html)) return

        val fingerprint = extractOrderFingerprint(html)
        if (fingerprint.isBlank()) return

        val previous = prefs.getString("last_fingerprint", null)

        if (previous == null) {
            prefs.edit().putString("last_fingerprint", fingerprint).apply()
            return
        }

        if (previous != fingerprint) {
            prefs.edit().putString("last_fingerprint", fingerprint).apply()
            NotificationHelper.showNewOrder(this, "وصل طلب جديد أو حدث تغيير في قائمة الطلبات")
        }
    }

    private fun looksLikeLoginPage(html: String): Boolean {
        val lower = html.lowercase()
        return (lower.contains("type=\"password\"") || lower.contains("type='password'")) &&
            (lower.contains("login") || lower.contains("تسجيل الدخول"))
    }

    private fun looksLikeNotFoundPage(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("لم يتم العثور على الصفحة") ||
            lower.contains("page not found") ||
            lower.contains(">404<")
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
            ?: return ""

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
