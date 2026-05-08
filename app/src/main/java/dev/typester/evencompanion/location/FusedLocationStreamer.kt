package dev.typester.evencompanion.location

import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import dev.typester.evencompanion.core.uniffi.Location
import dev.typester.evencompanion.core.uniffi.LocationStreamer
import java.util.concurrent.atomic.AtomicBoolean

class FusedLocationStreamer(
    private val fusedClient: FusedLocationProviderClient,
    private val onLocation: (Location) -> Unit,
) : LocationStreamer {

    private val subscribed = AtomicBoolean(false)

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onLocation(it.toCoreLocation()) }
        }
    }

    override fun start() {
        if (!subscribed.compareAndSet(false, true)) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            subscribed.set(false)
        }
    }

    override fun stop() {
        if (!subscribed.compareAndSet(true, false)) return
        fusedClient.removeLocationUpdates(callback)
    }
}
