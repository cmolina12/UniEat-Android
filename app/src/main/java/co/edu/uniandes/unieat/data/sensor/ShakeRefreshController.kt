package co.edu.uniandes.unieat.data.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

internal const val SHAKE_THRESHOLD_G = 2.7f
internal const val SHAKE_THROTTLE_MS = 1_500L

internal fun isShake(
    x: Float,
    y: Float,
    z: Float,
    lastShakeMs: Long,
    nowMs: Long,
): Boolean {
    val g = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
    return g > SHAKE_THRESHOLD_G && nowMs - lastShakeMs > SHAKE_THROTTLE_MS
}

/** Sensor feature: a deliberate shake refreshes the feed, throttled to avoid repeated requests. */
class ShakeRefreshController(
    context: Context,
    private val onShake: () -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var lastShakeMs = 0L

    fun start() {
        accelerometer?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()
        if (isShake(x, y, z, lastShakeMs, now)) {
            lastShakeMs = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
