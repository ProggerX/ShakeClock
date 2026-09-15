package com.proggerx.shakeclock

import android.content.Context
import android.text.format.DateFormat
import java.util.Locale

enum class ClockFace { DIGITAL, ANALOG }

enum class DateStyle { DAY_MONTH, MONTH_DAY }

enum class TimeStyle { SYSTEM, HOUR_12, HOUR_24 }

enum class IdleMode { OFF, ANIMATION, PERSISTENT }

class ToySettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var clockFace: ClockFace
        get() = enumOrDefault(prefs.getString(KEY_FACE, null), ClockFace.DIGITAL)
        set(value) = prefs.edit().putString(KEY_FACE, value.name).apply()

    var dateStyle: DateStyle
        get() = enumOrDefault(prefs.getString(KEY_DATE, null), defaultDateStyle())
        set(value) = prefs.edit().putString(KEY_DATE, value.name).apply()

    var timeStyle: TimeStyle
        get() = enumOrDefault(prefs.getString(KEY_TIME, null), TimeStyle.SYSTEM)
        set(value) = prefs.edit().putString(KEY_TIME, value.name).apply()

    var idleMode: IdleMode
        get() = enumOrDefault(prefs.getString(KEY_IDLE, null), IdleMode.OFF)
        set(value) = prefs.edit().putString(KEY_IDLE, value.name).apply()

    var showTemperature: Boolean
        get() = prefs.getBoolean(KEY_TEMPERATURE, false)
        set(value) = prefs.edit().putBoolean(KEY_TEMPERATURE, value).apply()

    var fahrenheit: Boolean
        get() = prefs.getBoolean(KEY_FAHRENHEIT, false)
        set(value) = prefs.edit().putBoolean(KEY_FAHRENHEIT, value).apply()

    var shakeThreshold: Float
        get() = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        set(value) = prefs.edit().putFloat(KEY_THRESHOLD, value).apply()

    var glyphAnimation: String?
        get() = prefs.getString(KEY_ANIMATION, null)
        set(value) = prefs.edit().putString(KEY_ANIMATION, value).apply()

    fun use24Hour(context: Context): Boolean = when (timeStyle) {
        TimeStyle.SYSTEM -> DateFormat.is24HourFormat(context)
        TimeStyle.HOUR_12 -> false
        TimeStyle.HOUR_24 -> true
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T =
        runCatching { enumValueOf<T>(name ?: "") }.getOrDefault(fallback)

    private fun defaultDateStyle(): DateStyle {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "ddMM")
        return if (pattern.indexOf('M') < pattern.indexOf('d')) {
            DateStyle.MONTH_DAY
        } else {
            DateStyle.DAY_MONTH
        }
    }

    companion object {
        const val PREFS_NAME = "shake_clock"
        const val KEY_FACE = "clock_face"
        const val KEY_DATE = "date_style"
        const val KEY_TIME = "time_style"
        const val KEY_IDLE = "idle_mode"
        const val KEY_TEMPERATURE = "show_temperature"
        const val KEY_FAHRENHEIT = "fahrenheit"
        const val KEY_THRESHOLD = "shake_threshold"
        const val DEFAULT_THRESHOLD = 26f
        const val KEY_ANIMATION = "glyph_animation"
    }
}
