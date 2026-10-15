package com.stockpile.volume.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.stockpile.volume.data.GeoPoint
import com.stockpile.volume.measure.GeoAverage
import com.stockpile.volume.measure.GeoSample
import kotlinx.coroutines.delay

private const val TARGET_M = 1.0
private const val MAX_SECONDS = 90

/**
 * Collects GPS fixes for up to 90 s while the user stands still at the pile and averages them.
 * Any location source Android provides is used – including an external Bluetooth GNSS/RTK
 * receiver set as the phone's mock location provider.
 */
@SuppressLint("MissingPermission")
@Composable
fun GpsFixDialog(onResult: (GeoPoint) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }

    val samples = remember { mutableStateListOf<GeoSample>() }
    var seconds by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(true) }

    if (granted) {
        DisposableEffect(Unit) {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val cb = object : LocationCallback() {
                override fun onLocationResult(r: LocationResult) {
                    if (!running) return
                    r.locations.forEach { l ->
                        if (l.hasAccuracy()) samples.add(GeoSample(l.latitude, l.longitude, l.accuracy.toDouble()))
                    }
                }
            }
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).setMinUpdateIntervalMillis(500L).build()
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())
            onDispose { client.removeLocationUpdates(cb) }
        }
        LaunchedEffect(Unit) {
            while (running && seconds < MAX_SECONDS) { delay(1000); seconds++ }
            running = false
        }
    }

    val est = GeoAverage.estimate(samples.toList())
    val reached = est != null && est.accuracyM <= TARGET_M && seconds >= 20
    LaunchedEffect(reached) { if (reached) running = false }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zaměření GPS polohy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!granted) {
                    Text("Povolte přístup k přesné poloze.")
                    return@Column
                }
                Text(
                    "Stůjte na místě u hromady pod otevřeným nebem a telefon držte v klidu. " +
                        "Aplikace sbírá polohu až $MAX_SECONDS s a průměruje ji.",
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(progress = { seconds / MAX_SECONDS.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("Měření: $seconds s · vzorků ${samples.size}", style = MaterialTheme.typography.bodySmall)
                if (est == null) {
                    Text("Čekám na signál GPS…")
                } else {
                    Text(String.format(java.util.Locale.US, "%.5f, %.5f", GeoAverage.round5(est.lat), GeoAverage.round5(est.lon)), fontWeight = FontWeight.Bold)
                    val color = when {
                        est.accuracyM <= TARGET_M -> Color(0xFF2E7D32)
                        est.accuracyM <= 3 -> Color(0xFFB26A00)
                        else -> MaterialTheme.colorScheme.error
                    }
                    Text("Odhad přesnosti ±%.1f m".format(est.accuracyM), color = color, fontWeight = FontWeight.SemiBold)
                    if (!running && est.accuracyM > TARGET_M) {
                        Text(
                            "Telefon přesnosti 1 m nedosáhl. Zkuste jiné místo dál od budov a stromů, nebo připojte externí GNSS/RTK přijímač.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = est != null,
                onClick = {
                    est?.let {
                        onResult(GeoPoint(GeoAverage.round5(it.lat), GeoAverage.round5(it.lon),
                            Math.round(it.accuracyM * 10) / 10.0, samples = it.used, source = "gps-average"))
                    }
                },
            ) { Text(if (running) "Použít teď" else "Použít polohu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}
