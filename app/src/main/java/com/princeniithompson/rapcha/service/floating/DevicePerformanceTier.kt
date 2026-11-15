package com.princeniithompson.rapcha.service.floating

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Classifies budget/older hardware so Rapcha automatically enables lightweight glow shaders
 * and avoids expensive offscreen saveLayer/RenderEffect passes on devices such as Android 11 / 3-4 GB RAM phones.
 */
object DevicePerformanceTier {
    private const val LOW_RAM_THRESHOLD_BYTES = 4_300_000_000L

    @Volatile
    private var cachedIsLowPower: Boolean? = null

    fun evaluate(
        isLowRamDevice: Boolean,
        totalMemBytes: Long,
        sdkInt: Int
    ): Boolean {
        if (isLowRamDevice) return true
        if (totalMemBytes in 1L..LOW_RAM_THRESHOLD_BYTES) return true
        return sdkInt <= Build.VERSION_CODES.R
    }

    fun isLowPowerDevice(context: Context): Boolean {
        cachedIsLowPower?.let { return it }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = am?.isLowRamDevice == true
        val totalMem = try {
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            info.totalMem
        } catch (_: Throwable) {
            0L
        }
        val computed = evaluate(
            isLowRamDevice = isLowRam,
            totalMemBytes = totalMem,
            sdkInt = Build.VERSION.SDK_INT
        )
        cachedIsLowPower = computed
        return computed
    }
}
