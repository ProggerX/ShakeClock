package com.proggerx.shakeclock

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var settings: ToySettings
    private lateinit var faceToggle: MaterialButtonToggleGroup
    private lateinit var dateToggle: MaterialButtonToggleGroup
    private lateinit var timeToggle: MaterialButtonToggleGroup
    private lateinit var unitToggle: MaterialButtonToggleGroup
    private lateinit var temperatureSwitch: MaterialSwitch
    private lateinit var thresholdSlider: Slider
    private lateinit var thresholdValue: TextView

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                WeatherRepository.refresh(this)
            } else {
                Toast.makeText(this, R.string.location_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        applyInsets()
        settings = ToySettings(this)

        faceToggle = findViewById(R.id.faceToggle)
        dateToggle = findViewById(R.id.dateToggle)
        timeToggle = findViewById(R.id.timeToggle)
        unitToggle = findViewById(R.id.unitToggle)
        temperatureSwitch = findViewById(R.id.temperatureSwitch)
        thresholdSlider = findViewById(R.id.thresholdSlider)
        thresholdValue = findViewById(R.id.thresholdValue)

        applyNdot()
        bindFace()
        bindDate()
        bindTime()
        bindThreshold()
        bindTemperature()
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun applyNdot() {
        val ndot = ndotTypeface()
        val title = SpannableString(getString(R.string.app_name))
        title.setSpan(TypefaceSpan(ndot), 0, title.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        supportActionBar?.title = title

        for (id in listOf(
            R.id.faceLabel,
            R.id.dateLabel,
            R.id.timeLabel,
            R.id.thresholdLabel,
            R.id.temperatureLabel,
        )) {
            findViewById<TextView>(id).typeface = ndot
        }
        thresholdValue.typeface = ndot
    }

    private fun ndotTypeface(): Typeface = try {
        Typeface.createFromFile("/system/fonts/NDot55Caps.otf")
    } catch (e: Exception) {
        Typeface.create("NDot55All", Typeface.BOLD)
    }

    private fun bindFace() {
        faceToggle.check(
            if (settings.clockFace == ClockFace.ANALOG) R.id.faceAnalog else R.id.faceDigital
        )
        faceToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            settings.clockFace =
                if (checkedId == R.id.faceAnalog) ClockFace.ANALOG else ClockFace.DIGITAL
        }
    }

    private fun bindDate() {
        dateToggle.check(
            if (settings.dateStyle == DateStyle.MONTH_DAY) R.id.dateMonthDay else R.id.dateDayMonth
        )
        dateToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            settings.dateStyle =
                if (checkedId == R.id.dateMonthDay) DateStyle.MONTH_DAY else DateStyle.DAY_MONTH
        }
    }

    private fun bindTime() {
        timeToggle.check(
            when (settings.timeStyle) {
                TimeStyle.HOUR_12 -> R.id.time12
                TimeStyle.HOUR_24 -> R.id.time24
                TimeStyle.SYSTEM -> R.id.timeSystem
            }
        )
        timeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            settings.timeStyle = when (checkedId) {
                R.id.time12 -> TimeStyle.HOUR_12
                R.id.time24 -> TimeStyle.HOUR_24
                else -> TimeStyle.SYSTEM
            }
        }
    }

    private fun bindThreshold() {
        val value = settings.shakeThreshold.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)
        thresholdSlider.value = value
        showThreshold(value)
        thresholdSlider.addOnChangeListener { _, newValue, fromUser ->
            showThreshold(newValue)
            if (fromUser) settings.shakeThreshold = newValue
        }
    }

    private fun showThreshold(value: Float) {
        thresholdValue.text = getString(R.string.threshold_value, value.toInt())
    }

    private fun bindTemperature() {
        temperatureSwitch.isChecked = settings.showTemperature
        setUnitEnabled(settings.showTemperature)
        unitToggle.check(if (settings.fahrenheit) R.id.unitFahrenheit else R.id.unitCelsius)
        unitToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            settings.fahrenheit = checkedId == R.id.unitFahrenheit
        }
        temperatureSwitch.setOnCheckedChangeListener { _, checked ->
            settings.showTemperature = checked
            setUnitEnabled(checked)
            if (checked) {
                if (hasLocationPermission()) {
                    WeatherRepository.refresh(this)
                } else {
                    locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            }
        }
    }

    private fun setUnitEnabled(enabled: Boolean) {
        for (index in 0 until unitToggle.childCount) {
            unitToggle.getChildAt(index).isEnabled = enabled
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val THRESHOLD_MIN = 8f
        const val THRESHOLD_MAX = 60f
    }
}
