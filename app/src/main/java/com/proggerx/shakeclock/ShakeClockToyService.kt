package com.proggerx.shakeclock

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixFrame
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphMatrixObject
import com.nothing.ketchum.GlyphMatrixUtils
import com.nothing.ketchum.GlyphToy
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ShakeClockToyService : Service(), SensorEventListener {

    private sealed interface Section {
        data object Time : Section
        data object Date : Section
        data object Temperature : Section
        data object Animation : Section
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val toyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == GlyphToy.MSG_GLYPH_TOY &&
                msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA) == GlyphToy.EVENT_AOD
            ) {
                if (!listening) startListening()
                WeatherRepository.refresh(this@ShakeClockToyService)
                refreshDisplay()
            } else {
                super.handleMessage(msg)
            }
        }
    }

    private val messenger = Messenger(toyHandler)

    private lateinit var settings: ToySettings
    private lateinit var animationStore: AnimationStore
    private var matrixManager: GlyphMatrixManager? = null
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var listening = false

    private var visible = false
    private var windowActive = false
    private var section: Section = Section.Time
    private var lastRenderedKey = ""
    private var lastShakeAt = 0L
    private val gravity = FloatArray(3)

    private var animating = false
    private var animEntries: List<AnimationStore.Entry> = emptyList()
    private var animEntryIndex = 0
    private var animFrameIndex = 0
    private var animFrames: List<GlyphAnimation.Frame> = emptyList()

    private val hideRunnable = Runnable { enterIdle() }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!visible) return
            refreshDisplay()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    private val animationRunnable = object : Runnable {
        override fun run() {
            if (!visible || section != Section.Animation || animEntries.isEmpty()) {
                animating = false
                return
            }
            if (animFrames.isEmpty()) {
                animFrames = animationStore.frames(animEntries[animEntryIndex].id)
                animFrameIndex = 0
                if (animFrames.isEmpty()) {
                    animEntryIndex = (animEntryIndex + 1) % animEntries.size
                    mainHandler.post(this)
                    return
                }
            }
            val frame = animFrames[animFrameIndex]
            animFrameIndex++
            if (animFrameIndex >= animFrames.size) {
                animFrameIndex = 0
                animEntryIndex = (animEntryIndex + 1) % animEntries.size
                animFrames = animationStore.frames(animEntries[animEntryIndex].id)
            }
            matrixManager?.let { manager ->
                try {
                    manager.setMatrixFrame(frame.pixels)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not draw animation frame", e)
                }
            }
            mainHandler.postDelayed(this, frame.durationMs)
        }
    }

    private val connection = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(componentName: ComponentName?) {
            val manager = matrixManager ?: return
            val target = targetDevice()
            if (target == null) {
                Log.w(TAG, "No supported Glyph Matrix device detected")
                return
            }
            if (manager.register(target)) {
                WeatherRepository.refresh(this@ShakeClockToyService)
                startListening()
                enterIdle()
            } else {
                Log.w(TAG, "Glyph Matrix registration failed for $target")
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            stopListening()
            shutdownDisplay()
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        settings = ToySettings(applicationContext)
        animationStore = AnimationStore(applicationContext)

        val manager = GlyphMatrixManager.getInstance(applicationContext)
        matrixManager = manager
        manager.init(connection)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopListening()
        shutdownDisplay()
        matrixManager?.unInit()
        matrixManager = null
        return false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        gravity[0] = GRAVITY_FILTER * gravity[0] + (1 - GRAVITY_FILTER) * event.values[0]
        gravity[1] = GRAVITY_FILTER * gravity[1] + (1 - GRAVITY_FILTER) * event.values[1]
        gravity[2] = GRAVITY_FILTER * gravity[2] + (1 - GRAVITY_FILTER) * event.values[2]

        val dx = event.values[0] - gravity[0]
        val dy = event.values[1] - gravity[1]
        val dz = event.values[2] - gravity[2]
        val linear = sqrt(dx * dx + dy * dy + dz * dz)

        if (linear < settings.shakeThreshold) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastShakeAt < SHAKE_COOLDOWN_MS) return
        lastShakeAt = now

        if (windowActive) {
            advanceSection()
        } else {
            windowActive = true
            visible = true
            section = Section.Time
            lastRenderedKey = ""
            refreshDisplay()
        }
        startWindowTimer()
        startTicking()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun enterIdle() {
        windowActive = false
        mainHandler.removeCallbacks(hideRunnable)
        when (settings.idleMode) {
            IdleMode.OFF -> {
                visible = false
                stopAnimation()
                mainHandler.removeCallbacks(tickRunnable)
                matrixManager?.turnOff()
            }

            IdleMode.ANIMATION -> {
                if (animationStore.selectedEntries().isEmpty()) {
                    visible = false
                    stopAnimation()
                    mainHandler.removeCallbacks(tickRunnable)
                    matrixManager?.turnOff()
                } else {
                    visible = true
                    section = Section.Animation
                    lastRenderedKey = ""
                    refreshDisplay()
                    startTicking()
                }
            }

            IdleMode.PERSISTENT -> {
                visible = true
                refreshDisplay()
                startTicking()
            }
        }
    }

    private fun shutdownDisplay() {
        windowActive = false
        visible = false
        lastRenderedKey = ""
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.removeCallbacks(tickRunnable)
        stopAnimation()
        matrixManager?.turnOff()
    }

    private fun startWindowTimer() {
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.postDelayed(hideRunnable, DISPLAY_DURATION_MS)
    }

    private fun startTicking() {
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.postDelayed(tickRunnable, TICK_MS)
    }

    private fun advanceSection() {
        val sections = availableSections()
        if (sections.isEmpty()) {
            shutdownDisplay()
            return
        }
        val index = sections.indexOf(section)
        section = sections[(index + 1) % sections.size]
        lastRenderedKey = ""
        refreshDisplay()
    }

    private fun availableSections(): List<Section> {
        val sections = mutableListOf<Section>(Section.Time, Section.Date)
        if (settings.showTemperature && WeatherRepository.celsius(this) != null) {
            sections.add(Section.Temperature)
        }
        if (animationStore.selectedEntries().isNotEmpty()) {
            sections.add(Section.Animation)
        }
        return sections
    }

    private fun refreshDisplay() {
        if (!visible) return
        val manager = matrixManager ?: return
        when (section) {
            is Section.Time -> {
                stopAnimation()
                if (settings.clockFace == ClockFace.ANALOG) {
                    renderAnalogClock(manager)
                } else {
                    renderDigitalClock(manager)
                }
            }

            is Section.Date -> {
                stopAnimation()
                renderDate(manager)
            }

            is Section.Temperature -> {
                stopAnimation()
                renderTemperature(manager)
            }

            is Section.Animation -> startAnimation()
        }
    }

    private fun startAnimation() {
        val entries = animationStore.selectedEntries()
        if (entries.isEmpty()) {
            stopAnimation()
            return
        }
        if (animating && entries == animEntries) return
        animEntries = entries
        animEntryIndex = 0
        animFrameIndex = 0
        animFrames = animationStore.frames(entries[0].id)
        if (!animating) {
            animating = true
            mainHandler.post(animationRunnable)
        }
    }

    private fun stopAnimation() {
        if (animating || animEntries.isNotEmpty()) {
            animating = false
            mainHandler.removeCallbacks(animationRunnable)
        }
        animEntries = emptyList()
        animFrames = emptyList()
        animEntryIndex = 0
        animFrameIndex = 0
    }

    private fun renderDigitalClock(manager: GlyphMatrixManager) {
        val calendar = Calendar.getInstance()
        val hour = formatHour(calendar)
        val minute = String.format(Locale.US, "%02d", calendar.get(Calendar.MINUTE))
        val key = "time:$hour:$minute"
        if (key == lastRenderedKey) return
        lastRenderedKey = key
        drawTextFrame(manager, hour, minute)
    }

    private fun renderAnalogClock(manager: GlyphMatrixManager) {
        val calendar = Calendar.getInstance()
        val key = "clock:${calendar.get(Calendar.HOUR)}:${calendar.get(Calendar.MINUTE)}"
        if (key == lastRenderedKey) return
        lastRenderedKey = key

        val hand = GlyphMatrixObject.Builder()
            .setImageSource(analogClockBitmap(calendar))
            .setPosition(0, 0)
            .setScale(100)
            .setBrightness(CLOCK_BRIGHTNESS)
            .build()

        val frame = GlyphMatrixFrame.Builder()
            .addTop(hand)
            .build(applicationContext)

        setFrame(manager, frame)
    }

    private fun renderDate(manager: GlyphMatrixManager) {
        val calendar = Calendar.getInstance()
        val day = String.format(Locale.US, "%02d", calendar.get(Calendar.DAY_OF_MONTH))
        val month = String.format(Locale.US, "%02d", calendar.get(Calendar.MONTH) + 1)
        val monthFirst = settings.dateStyle == DateStyle.MONTH_DAY
        val top = if (monthFirst) month else day
        val bottom = if (monthFirst) day else month
        val key = "date:$top.$bottom"
        if (key == lastRenderedKey) return
        lastRenderedKey = key
        drawTextFrame(manager, top, bottom)
    }

    private fun renderTemperature(manager: GlyphMatrixManager) {
        val celsius = WeatherRepository.celsius(this) ?: return
        val value = if (settings.fahrenheit) {
            Math.round(celsius * 9 / 5.0 + 32).toInt()
        } else {
            celsius
        }
        val unit = if (settings.fahrenheit) "\u00B0F" else "\u00B0C"
        val top = value.toString()
        val key = "temp:$top:$unit"
        if (key == lastRenderedKey) return
        lastRenderedKey = key
        drawTextFrame(manager, top, unit)
    }

    private fun drawTextFrame(manager: GlyphMatrixManager, top: String, bottom: String) {
        val matrixLength = Common.getDeviceMatrixLength()
        val topY = ((matrixLength - 2 * GLYPH_HEIGHT) / 2).coerceAtLeast(0)

        val topObject = GlyphMatrixObject.Builder()
            .setText(top)
            .setPosition(centeredX(top, matrixLength), topY)
            .setBrightness(CLOCK_BRIGHTNESS)
            .build()

        val bottomObject = GlyphMatrixObject.Builder()
            .setText(bottom)
            .setPosition(centeredX(bottom, matrixLength), topY + GLYPH_HEIGHT)
            .setBrightness(CLOCK_BRIGHTNESS)
            .build()

        val frame = GlyphMatrixFrame.Builder()
            .addTop(topObject)
            .addLow(bottomObject)
            .build(applicationContext)

        setFrame(manager, frame)
    }

    private fun setFrame(manager: GlyphMatrixManager, frame: GlyphMatrixFrame) {
        try {
            manager.setMatrixFrame(frame.render())
        } catch (e: Exception) {
            Log.e(TAG, "Could not draw the display", e)
        }
    }

    private fun formatHour(calendar: Calendar): String {
        if (settings.use24Hour(this)) {
            return String.format(Locale.US, "%02d", calendar.get(Calendar.HOUR_OF_DAY))
        }
        val hour = calendar.get(Calendar.HOUR)
        return (if (hour == 0) 12 else hour).toString()
    }

    private fun centeredX(text: String, matrixLength: Int): Int {
        val letters = GlyphMatrixUtils.getLetterConfigs(text, this, null)
        val width = if (letters.isNullOrEmpty()) {
            text.length * GLYPH_WIDTH
        } else {
            GlyphMatrixUtils.getLetterMaxLength(letters, false)
        }
        return ((matrixLength - width) / 2).coerceAtLeast(0)
    }

    private fun analogClockBitmap(calendar: Calendar): Bitmap {
        val size = Common.getDeviceMatrixLength()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = (size - 1) / 2f
        val radius = size / 2f - 0.5f

        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1f
            strokeCap = Paint.Cap.ROUND
        }

        canvas.drawCircle(center, center, radius, paint)

        val hour = calendar.get(Calendar.HOUR)
        val minute = calendar.get(Calendar.MINUTE)
        val hourAngle = Math.toRadians(hour * 30.0 + minute * 0.5)
        val minuteAngle = Math.toRadians(minute * 6.0)

        drawHand(canvas, paint, center, hourAngle, radius * 0.5f)
        drawHand(canvas, paint, center, minuteAngle, radius * 0.82f)

        paint.style = Paint.Style.FILL
        canvas.drawCircle(center, center, 0.6f, paint)

        return bitmap
    }

    private fun drawHand(
        canvas: Canvas,
        paint: Paint,
        center: Float,
        angle: Double,
        length: Float,
    ) {
        val dx = (sin(angle) * length).toFloat()
        val dy = (-cos(angle) * length).toFloat()
        canvas.drawLine(center, center, center + dx, center + dy, paint)
    }

    private fun targetDevice(): String? = when {
        Common.is25111p() -> Glyph.DEVICE_25111p
        Common.is23112() -> Glyph.DEVICE_23112
        else -> null
    }

    private fun startListening() {
        if (listening) return
        val manager = sensorManager ?: return
        val sensor = accelerometer ?: run {
            Log.w(TAG, "No accelerometer available")
            return
        }
        listening = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopListening() {
        if (!listening) return
        sensorManager?.unregisterListener(this)
        listening = false
    }

    private companion object {
        const val TAG = "ShakeClockToy"
        const val DISPLAY_DURATION_MS = 10_000L
        const val TICK_MS = 1_000L
        const val SHAKE_COOLDOWN_MS = 700L
        const val GRAVITY_FILTER = 0.8f
        const val CLOCK_BRIGHTNESS = 190
        const val GLYPH_WIDTH = 4
        const val GLYPH_HEIGHT = 6
    }
}
