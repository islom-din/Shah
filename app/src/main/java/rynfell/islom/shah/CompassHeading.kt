package rynfell.islom.shah

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

internal enum class HeadingStatus { Waiting, Available, HoldFlat, Calibrate, Unavailable }

internal class CompassHeading(
    private val sensorManager: SensorManager,
    private val screenRotation: () -> Int,
    private val onHeading: (HeadingStatus, Float?, Long) -> Unit,
) : SensorEventListener {
    private val matrix = FloatArray(9)
    private val screenMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var lastTimestamp = 0L
    private var filteredAzimuth = 0f

    fun start() {
        val registered = listOf(Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            .mapNotNull(sensorManager::getDefaultSensor)
            .any { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        onHeading(if (registered) HeadingStatus.Waiting else HeadingStatus.Unavailable, null, 0)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        lastTimestamp = 0L
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val headingError = event.values.getOrNull(4) ?: -1f
        if (event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ||
            headingError > Math.toRadians(20.0)
        ) {
            reportInvalidHeading(HeadingStatus.Calibrate, event.timestamp)
            return
        }

        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val (screenX, screenY) = when (screenRotation()) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(matrix, screenX, screenY, screenMatrix)
        // The second column is the direction of the screen's top edge in world coordinates.
        if (hypot(screenMatrix[1], screenMatrix[4]) < 0.5f) {
            reportInvalidHeading(HeadingStatus.HoldFlat, event.timestamp)
            return
        }

        SensorManager.getOrientation(screenMatrix, orientation)
        val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val elapsed = event.timestamp - lastTimestamp
        filteredAzimuth = if (lastTimestamp == 0L || elapsed <= 0 || elapsed > 500_000_000) {
            azimuth
        } else {
            val weight = (1.0 - exp(-elapsed / 85_000_000.0)).toFloat()
            normalizeDegrees(filteredAzimuth + signedDegrees(azimuth - filteredAzimuth) * weight)
        }
        lastTimestamp = event.timestamp
        onHeading(HeadingStatus.Available, filteredAzimuth, event.timestamp)
    }

    private fun reportInvalidHeading(status: HeadingStatus, timestamp: Long) {
        lastTimestamp = 0L
        onHeading(status, null, timestamp)
    }
}

internal fun normalizeDegrees(degrees: Float): Float = (degrees % 360f + 360f) % 360f

internal fun signedDegrees(degrees: Float): Float = normalizeDegrees(degrees + 180f) - 180f

internal class CompassAlignment {
    var aligned = false
        private set
    private var enteredAt: Long? = null
    private var feedbackArmed = true

    fun update(error: Float?, timestamp: Long): Boolean {
        if (error == null) {
            aligned = false
            enteredAt = null
            return false
        }

        val distance = abs(error)
        if (distance >= 7f) {
            aligned = false
            enteredAt = null
            feedbackArmed = true
        }
        if (aligned || distance > 3f) {
            enteredAt = null
            return false
        }

        val start = enteredAt ?: timestamp.also { enteredAt = it }
        if (timestamp - start < 200_000_000L) return false

        aligned = true
        val shouldVibrate = feedbackArmed
        feedbackArmed = false
        return shouldVibrate
    }
}
