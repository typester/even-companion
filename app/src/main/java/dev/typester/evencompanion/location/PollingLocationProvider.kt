package dev.typester.evencompanion.location

import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import dev.typester.evencompanion.core.uniffi.Location
import dev.typester.evencompanion.core.uniffi.LocationProvider
import java.util.concurrent.locks.ReentrantLock

class PollingLocationProvider(
    private val fusedClient: FusedLocationProviderClient,
    private val idleTimeoutMs: Long = 60_000L,
    private val firstFixTimeoutMs: Long = 10_000L,
) : LocationProvider {

    @Volatile private var lastLocation: Location? = null
    private val lock = ReentrantLock()
    private var isSubscribed = false
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { lastLocation = it.toCoreLocation() }
        }
    }

    private val stopRunnable = Runnable {
        lock.lock()
        try {
            if (isSubscribed) {
                fusedClient.removeLocationUpdates(callback)
                isSubscribed = false
                lastLocation = null
            }
        } finally {
            lock.unlock()
        }
    }

    override fun current(): Location? {
        resetIdleTimer()
        ensureSubscribed()

        lastLocation?.let { return it }

        val deadline = System.currentTimeMillis() + firstFixTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            lastLocation?.let { return it }
        }
        return null
    }

    fun cleanup() {
        handler.removeCallbacks(stopRunnable)
        lock.lock()
        try {
            if (isSubscribed) {
                fusedClient.removeLocationUpdates(callback)
                isSubscribed = false
            }
        } finally {
            lock.unlock()
        }
        lastLocation = null
    }

    private fun resetIdleTimer() {
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, idleTimeoutMs)
    }

    private fun ensureSubscribed() {
        lock.lock()
        try {
            if (!isSubscribed) {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                    .setMinUpdateIntervalMillis(500L)
                    .build()
                try {
                    fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                    isSubscribed = true
                } catch (_: SecurityException) {}
            }
        } finally {
            lock.unlock()
        }
    }
}

internal fun android.location.Location.toCoreLocation() = Location(
    latitude = latitude,
    longitude = longitude,
    altitude = if (hasAltitude()) altitude else null,
    accuracyM = if (hasAccuracy()) accuracy else null,
    bearingDeg = if (hasBearing()) bearing else null,
    speedMps = if (hasSpeed()) speed else null,
    speedAccuracyMps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasSpeedAccuracy())
        speedAccuracyMetersPerSecond else null,
    timestampMs = time,
)
