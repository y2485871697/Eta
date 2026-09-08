package io.github.mangi.eta.agent.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock

/** 按需读取系统已有的最近位置，不持续监听，也不主动唤醒 GPS。 */
internal object DeviceLocationProvider {
    enum class AccessState {
        DENIED,
        FOREGROUND_ONLY,
        DISABLED,
        AVAILABLE,
    }

    sealed interface Result {
        data class Available(
            val latitude: Double,
            val longitude: Double,
            val accuracyMeters: Float?,
            val ageMillis: Long,
        ) : Result

        data class Unavailable(val status: String) : Result
    }

    fun accessState(context: Context): AccessState {
        val foregroundGranted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!foregroundGranted) return AccessState.DENIED
        if (context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return AccessState.FOREGROUND_ONLY
        }
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return AccessState.DISABLED
        return if (manager.isLocationEnabled) AccessState.AVAILABLE else AccessState.DISABLED
    }

    @SuppressLint("MissingPermission")
    fun latest(context: Context): Result {
        return when (accessState(context)) {
            AccessState.DENIED -> Result.Unavailable("permission_required")
            AccessState.FOREGROUND_ONLY -> Result.Unavailable("background_permission_required")
            AccessState.DISABLED -> Result.Unavailable("location_disabled")
            AccessState.AVAILABLE -> {
                val manager = context.getSystemService(LocationManager::class.java)
                    ?: return Result.Unavailable("unavailable")
                val providers = runCatching { manager.getProviders(true) }
                    .getOrElse { return Result.Unavailable("unavailable") }
                val location = providers
                    .asSequence()
                    .mapNotNull { provider ->
                        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                    }
                    .maxByOrNull { it.elapsedRealtimeNanos }
                    ?: return Result.Unavailable("unavailable")
                val ageMillis = if (location.elapsedRealtimeNanos > 0L) {
                    ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L)
                        .coerceAtLeast(0L)
                } else {
                    (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
                }
                Result.Available(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                    ageMillis = ageMillis,
                )
            }
        }
    }
}
