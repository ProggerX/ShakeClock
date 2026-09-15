package com.proggerx.shakeclock

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Spannable
import android.text.SpannableString
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var settings: ToySettings
    private lateinit var animationStore: AnimationStore

    private lateinit var faceToggle: MaterialButtonToggleGroup
    private lateinit var dateToggle: MaterialButtonToggleGroup
    private lateinit var timeToggle: MaterialButtonToggleGroup
    private lateinit var idleToggle: MaterialButtonToggleGroup
    private lateinit var unitToggle: MaterialButtonToggleGroup
    private lateinit var temperatureSwitch: MaterialSwitch
    private lateinit var thresholdSlider: Slider
    private lateinit var thresholdValue: TextView
    private lateinit var animationList: LinearLayout
    private lateinit var animationEmpty: TextView

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                WeatherRepository.refresh(this)
            } else {
                Toast.makeText(this, R.string.location_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val pickAnimation =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importAnimation(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        applyInsets()
        settings = ToySettings(this)
        animationStore = AnimationStore(this)

        faceToggle = findViewById(R.id.faceToggle)
        dateToggle = findViewById(R.id.dateToggle)
        timeToggle = findViewById(R.id.timeToggle)
        idleToggle = findViewById(R.id.idleToggle)
        unitToggle = findViewById(R.id.unitToggle)
        temperatureSwitch = findViewById(R.id.temperatureSwitch)
        thresholdSlider = findViewById(R.id.thresholdSlider)
        thresholdValue = findViewById(R.id.thresholdValue)
        animationList = findViewById(R.id.animationList)
        animationEmpty = findViewById(R.id.animationEmpty)

        applyNdot()
        bindFace()
        bindDate()
        bindTime()
        bindIdle()
        bindThreshold()
        bindTemperature()
        bindAnimations()
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
            R.id.idleLabel,
            R.id.thresholdLabel,
            R.id.temperatureLabel,
            R.id.animationLabel,
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

    private fun bindIdle() {
        idleToggle.check(
            when (settings.idleMode) {
                IdleMode.ANIMATION -> R.id.idleAnimation
                IdleMode.PERSISTENT -> R.id.idleKeep
                IdleMode.OFF -> R.id.idleOff
            }
        )
        idleToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            settings.idleMode = when (checkedId) {
                R.id.idleAnimation -> IdleMode.ANIMATION
                R.id.idleKeep -> IdleMode.PERSISTENT
                else -> IdleMode.OFF
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

    private fun bindAnimations() {
        findViewById<MaterialButton>(R.id.importAnimationButton).setOnClickListener {
            pickAnimation.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        findViewById<MaterialButton>(R.id.removeAnimationsButton).setOnClickListener {
            animationStore.removeAll()
            refreshAnimationList()
        }
        refreshAnimationList()
    }

    private fun refreshAnimationList() {
        val entries = animationStore.entries()
        animationList.removeAllViews()
        animationEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        for (entry in entries) {
            val checkBox = MaterialCheckBox(this)
            checkBox.text = getString(
                R.string.animation_entry,
                entry.name,
                resources.getQuantityString(
                    R.plurals.animation_frames,
                    entry.frameCount,
                    entry.frameCount,
                ),
            )
            checkBox.isChecked = entry.selected
            checkBox.setOnCheckedChangeListener { _, checked ->
                animationStore.setSelected(entry.id, checked)
            }
            animationList.addView(checkBox)
        }
    }

    private fun importAnimation(uri: Uri) {
        val source = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        val name = queryDisplayName(uri) ?: getString(R.string.animation_default_name)
        val entry = source?.let { animationStore.import(name, it) }
        if (entry == null) {
            Toast.makeText(this, R.string.animation_invalid, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, getString(R.string.animation_imported, entry.name), Toast.LENGTH_SHORT)
            .show()
        refreshAnimationList()
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: return null
        return cursor.use { if (it.moveToFirst()) it.getString(0) else null }
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
