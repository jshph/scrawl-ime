package com.scrawl.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Device capability profile for performance tuning across Boox hardware.
 *
 * Scrawl targets multiple Boox e-ink devices with different SoCs, RAM, and
 * display types. This profile detects hardware class at startup and exposes
 * tunables so hot paths (stroke rendering, bitmap allocation, HWR binding)
 * can adapt without device-specific if/else scattered through the codebase.
 *
 * Known devices:
 *   - Note Air 5C (2024): Snapdragon 680, 6GB RAM, 10.3" Kaleido 3 color, Android 14
 *   - Nova Air 2  (2022): older SoC, 3GB RAM, 7.8" Carta monochrome, Android 11
 *
 * To add a new device: adjust the thresholds below or add model-specific overrides.
 */
object DeviceProfile {

    private const val TAG = "DeviceProfile"

    /** RAM threshold (MB) below which we consider the device "low memory". */
    private const val LOW_RAM_THRESHOLD_MB = 4096

    /** Core count threshold below which we consider the device "low CPU". */
    private const val LOW_CPU_CORE_THRESHOLD = 8

    enum class Tier { HIGH, LOW }

    lateinit var tier: Tier
        private set

    /** True if the display is monochrome e-ink (no Kaleido/color layer). */
    var isMonochrome: Boolean = false
        private set

    /** Total device RAM in MB. */
    var totalRamMb: Long = 0
        private set

    var cpuCores: Int = 0
        private set

    // --- Tunables derived from tier ---

    /**
     * Progressive bake threshold: max live InkStroke points before baking
     * to the backing bitmap. Lower = less per-frame work on slow CPUs.
     * Note Air 5C: 80 (original). Nova Air 2: 40.
     */
    val bakeThreshold: Int get() = when (tier) {
        Tier.HIGH -> 80
        Tier.LOW -> 40
    }

    /**
     * Whether to defer stroke baking to a background thread on low-tier devices
     * to avoid blocking pen-up.
     */
    val deferBakeWhenRawDrawing: Boolean get() = tier == Tier.LOW

    /**
     * The handwriting layer overlays the host app, so it needs an alpha channel
     * on every display. RGB_565 makes even an empty monochrome canvas opaque.
     */
    val bitmapConfig: android.graphics.Bitmap.Config get() =
        android.graphics.Bitmap.Config.ARGB_8888

    fun init(context: Context) {
        cpuCores = Runtime.getRuntime().availableProcessors()
        totalRamMb = getTotalRamMb(context)
        isMonochrome = detectMonochrome()

        tier = if (totalRamMb < LOW_RAM_THRESHOLD_MB || cpuCores < LOW_CPU_CORE_THRESHOLD) {
            Tier.LOW
        } else {
            Tier.HIGH
        }

        Log.i(TAG, "DeviceProfile: tier=$tier cores=$cpuCores ram=${totalRamMb}MB " +
                "mono=$isMonochrome model=${Build.MODEL} bakeThreshold=$bakeThreshold " +
                "bitmapConfig=$bitmapConfig deferBake=$deferBakeWhenRawDrawing")
    }

    private fun getTotalRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    /**
     * Detect monochrome vs color e-ink. Kaleido (color) models typically have
     * "Color" or "C" suffix in model name. Falls back to monochrome assumption
     * for unknown Onyx models since most are mono.
     */
    private fun detectMonochrome(): Boolean {
        if (!"ONYX".equals(Build.MANUFACTURER, ignoreCase = true)) return false
        val model = Build.MODEL.uppercase()
        val colorIndicators = listOf("KALEIDO", "COLOR")
        if (colorIndicators.any { model.contains(it) }) return false
        // Boox convention: color models end with "C" (e.g. 3C, 4C, 5C, "Tab Mini C")
        if (model.trimEnd().endsWith("C")) return false
        return true
    }
}
