package com.stockpile.volume.capture

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Live GPS fix while the composable is on screen. Caller must hold location permission. */
@SuppressLint("MissingPermission")
@Composable
fun rememberLocation(): State<Location?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<Location?>(null) }
    DisposableEffect(Unit) {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { state.value = it }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        onDispose { client.removeLocationUpdates(callback) }
    }
    return state
}

/**
 * Compass heading (degrees, 0 = north) of the direction the back camera points,
 * assuming the phone is held upright in portrait. Smoothed to stop the needle jittering.
 */
@Composable
fun rememberCameraHeading(): State<Float> {
    val context = LocalContext.current
    val state = remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        var smoothed = Float.NaN
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                // Remap so the azimuth is that of the device's -Z axis (where the back camera looks).
                SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
                SensorManager.getOrientation(remapped, orientation)
                val deg = ((Math.toDegrees(orientation[0].toDouble()).toFloat()) + 360f) % 360f
                smoothed = if (smoothed.isNaN()) deg else {
                    var diff = deg - smoothed
                    if (diff > 180f) diff -= 360f
                    if (diff < -180f) diff += 360f
                    (smoothed + diff * 0.2f + 360f) % 360f
                }
                state.floatValue = smoothed
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return state
}
