package com.mybrowser.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.Date

/**
 * Clock and battery for the fullscreen overlay.
 *
 * Immersive fullscreen hides the system bars, so the player is the only place these can be read.
 * Observation follows the player's own attachment — nothing is registered while no video owns the
 * screen — and the sticky battery broadcast delivers the current level on registration, so the
 * first value costs no query.
 */
class PlayerStatusSource(private val context: Context) {
    var time by mutableStateOf("")
        private set
    var batteryPercent by mutableStateOf<Int?>(null)
        private set
    var charging by mutableStateOf(false)
        private set

    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) readBattery(intent) else refreshTime()
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        registered = runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        refreshTime()
    }

    fun unregister() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** The clock the user configured, in the system's 12/24-hour form. */
    private fun refreshTime() {
        time = DateFormat.getTimeFormat(context).format(Date())
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
        batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }
}
