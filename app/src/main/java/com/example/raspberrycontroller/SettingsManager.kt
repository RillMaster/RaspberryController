package com.example.raspberrycontroller

import android.content.Context
import org.json.JSONArray

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("ssh_settings", Context.MODE_PRIVATE)

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean("first_launch", true)
        set(value) = prefs.edit().putBoolean("first_launch", value).apply()

    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(value) = prefs.edit().putString("host", value).apply()

    var port: Int
        get() = prefs.getInt("port", 22)
        set(value) = prefs.edit().putInt("port", value).apply()

    var username: String
        get() = prefs.getString("username", "pi") ?: "pi"
        set(value) = prefs.edit().putString("username", value).apply()

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(value) = prefs.edit().putString("password", value).apply()

    // Pi-hole
    var piHolePassword: String
        get()      = prefs.getString("pihole_password", "") ?: ""
        set(value) = prefs.edit().putString("pihole_password", value).apply()

    var piHoleAutoRefresh: Boolean
        get()      = prefs.getBoolean("pihole_auto_refresh", true)
        set(value) = prefs.edit().putBoolean("pihole_auto_refresh", value).apply()

    var piHoleRefreshDelaySec: Int
        get()      = prefs.getInt("pihole_refresh_delay_sec", 30)
        set(value) = prefs.edit().putInt("pihole_refresh_delay_sec", value).apply()

    var sshTimeoutMs: Int
        get() = prefs.getInt("ssh_timeout_ms", 8000)
        set(value) = prefs.edit().putInt("ssh_timeout_ms", value).apply()

    var tempRefreshMs: Int
        get() = prefs.getInt("temp_refresh_ms", 2000)
        set(value) = prefs.edit().putInt("temp_refresh_ms", value).apply()

    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) = prefs.edit().putString("theme", value).apply()

    var biometricEnabled: Boolean
        get() = prefs.getBoolean("biometric_enabled", false)
        set(value) = prefs.edit().putBoolean("biometric_enabled", value).apply()

    var shortcuts: List<Pair<String, String>>
        get() {
            val json = prefs.getString("shortcuts", null) ?: return defaultShortcuts()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map {
                    val parts = arr.getString(it).split("|", limit = 2)
                    Pair(parts[0], if (parts.size > 1) parts[1] else parts[0])
                }
            } catch (e: Exception) {
                defaultShortcuts()
            }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { (label, cmd) -> arr.put("$label|$cmd") }
            prefs.edit().putString("shortcuts", arr.toString()).apply()
        }

    fun defaultShortcuts() = listOf(
        Pair("ls",     "ls -la"),
        Pair("top",    "top -bn1 | head -20"),
        Pair("temp",   "cat /sys/class/thermal/thermal_zone0/temp"),
        Pair("df",     "df -h"),
        Pair("free",   "free -h"),
        Pair("uptime", "uptime"),
        Pair("reboot", "sudo reboot")
    )

    fun isConfigured(): Boolean = host.isNotEmpty() && username.isNotEmpty()
}