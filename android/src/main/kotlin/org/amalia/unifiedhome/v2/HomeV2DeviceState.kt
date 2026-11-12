package org.amalia.unifiedhome.v2

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Fresh OS snapshot, never conversation context or a synthetic fallback battery percentage. */
internal fun homeV2DeviceState(context: Context, node: String, name: String): JSONObject {
    val now = System.currentTimeMillis()
    val zone = TimeZone.getDefault()
    val clock = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).apply { timeZone = zone }
    val time = JSONObject().put("epoch_ms", now).put("local_iso", clock.format(Date(now)))
        .put("timezone", zone.id).put("utc_offset_seconds", zone.getOffset(now) / 1000)
    val battery = JSONObject().put("available", false).put("present", JSONObject.NULL)
        .put("percent", JSONObject.NULL).put("charging", JSONObject.NULL).put("ac_connected", JSONObject.NULL)
        .put("status", "unknown").put("source", "android_battery_manager")
    try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val raw = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (raw != null && raw in 0..100) raw
            else if (level >= 0 && scale > 0 && level <= scale) level.toLong().times(100).div(scale).toInt() else null
        val state = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val present = intent?.takeIf { it.hasExtra(BatteryManager.EXTRA_PRESENT) }
            ?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
        battery.put("available", intent != null || percent != null)
            .put("present", present ?: JSONObject.NULL)
            .put("percent", if (present == false) JSONObject.NULL else percent ?: JSONObject.NULL)
            .put("charging", when (state) {
                BatteryManager.BATTERY_STATUS_CHARGING -> true
                BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL -> false
                else -> JSONObject.NULL
            }).put("ac_connected", if (plugged >= 0) plugged != 0 else JSONObject.NULL)
            .put("status", when (state) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                else -> "unknown"
            })
    } catch (_: SecurityException) { battery.put("error", "permission_denied") }
    catch (_: RuntimeException) { battery.put("error", "battery_unavailable") }
    val system = JSONObject().put("os", "Android").put("release", Build.VERSION.RELEASE)
        .put("sdk_int", Build.VERSION.SDK_INT).put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL).put("uptime_ms", SystemClock.elapsedRealtime())
    return JSONObject().put("node", node).put("name", name).put("sampled_at_ms", now)
        .put("time", time).put("battery", battery).put("system", system)
}
