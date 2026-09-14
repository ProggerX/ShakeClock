package com.proggerx.shakeclock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object WeatherRepository {

    private const val TAG = "ShakeClockWeather"
    private const val PREFS = "shake_clock_weather"
    private const val KEY_CELSIUS = "temperature_celsius"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val MAX_AGE_MS = 20 * 60 * 1000L

    private val executor = Executors.newSingleThreadExecutor()
    private val fetching = AtomicBoolean(false)

    fun celsius(context: Context): Int? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_CELSIUS)) return null
        return prefs.getInt(KEY_CELSIUS, 0)
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        if (app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val age = System.currentTimeMillis() - prefs.getLong(KEY_UPDATED_AT, 0L)
        if (age < MAX_AGE_MS) return
        if (!fetching.compareAndSet(false, true)) return

        executor.execute {
            try {
                val location = lastLocation(app)
                if (location == null) {
                    Log.w(TAG, "No last known location available")
                    return@execute
                }
                val url = URL(
                    String.format(
                        Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m",
                        location.latitude,
                        location.longitude,
                    )
                )
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                }
                try {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val current = JSONObject(body).getJSONObject("current")
                    val degrees = Math.round(current.getDouble("temperature_2m")).toInt()
                    prefs.edit()
                        .putInt(KEY_CELSIUS, degrees)
                        .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                        .apply()
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Weather refresh failed", e)
            } finally {
                fetching.set(false)
            }
        }
    }

    private fun lastLocation(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        var best: Location? = null
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            val location = try {
                manager.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                Log.w(TAG, "Location permission missing for $provider")
                null
            } catch (e: Exception) {
                Log.w(TAG, "Location unavailable for $provider", e)
                null
            }
            if (location != null && (best == null || location.time > best.time)) {
                best = location
            }
        }
        return best
    }
}
