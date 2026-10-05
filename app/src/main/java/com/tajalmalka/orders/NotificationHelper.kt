package com.tajalmalka.orders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {
    private const val MONITOR_CHANNEL = "order_monitor_v1"
    private const val ORDER_CHANNEL = "new_orders_alarm_v1"
    const val MONITOR_NOTIFICATION_ID = 4100

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)

        val monitor = NotificationChannel(
            MONITOR_CHANNEL,
            "مراقبة الطلبات",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "إشعار ثابت أثناء مراقبة الطلبات"
            setSound(null, null)
        }

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val order = NotificationChannel(
            ORDER_CHANNEL,
            "تنبيهات الطلبات الجديدة",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "جرس واهتزاز عند وصول طلب جديد"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 900)
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }

        manager.createNotificationChannel(monitor)
        manager.createNotificationChannel(order)
    }

    fun monitorNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_order)
            .setContentTitle("طلبات تاج الملكة")
            .setContentText("مراقبة الطلبات الجديدة مفعّلة")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .build()
    }

    fun showNewOrder(context: Context, message: String) {
        createChannels(context)
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val notification = NotificationCompat.Builder(context, ORDER_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_order)
            .setContentTitle("🔔 طلب جديد - تاج الملكة")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message\nاضغط لفتح لوحة الطلبات."))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 250, 500, 250, 900))
            .setContentIntent(openAppIntent(context))
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Android 13+ notification permission was not granted yet.
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
