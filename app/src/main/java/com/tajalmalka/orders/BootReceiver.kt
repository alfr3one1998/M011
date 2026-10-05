package com.tajalmalka.orders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences("order_monitor", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
        if (enabled) {
            ContextCompat.startForegroundService(context, Intent(context, OrderMonitorService::class.java))
        }
    }
}
