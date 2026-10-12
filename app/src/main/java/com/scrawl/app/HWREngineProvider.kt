package com.scrawl.app

import android.content.Context
import android.util.Log

/**
 * Selects and provides the active HWR engine at runtime.
 *
 * On Boox devices with the Onyx KHwrService (ksync) available, uses OnyxHWREngine
 * for best quality and speed. On devices without it (older firmware, non-Boox),
 * falls back to ML Kit Digital Ink Recognition.
 *
 * The fallback is lazy: ML Kit model download only happens if OnyxHWREngine
 * fails to bind, so devices that support the Onyx engine never touch ML Kit.
 */
object HWREngineProvider {

    private const val TAG = "HWREngineProvider"

    @Volatile
    var engine: HWREngine = OnyxHWREngineAdapter
        private set

    @Volatile
    var isOnyxAvailable: Boolean = false
        private set

    private const val FORCE_MLKIT = false

    /**
     * Probe the Onyx HWR service. If it responds, lock in OnyxHWREngine.
     * If it fails, switch to MlKitHWREngine and prepare it (triggers model download).
     *
     * Call once from ScrawlIMEService.onCreate.
     */
    suspend fun init(context: Context) {
        val onyxReady = if (FORCE_MLKIT) {
            Log.w(TAG, "FORCE_MLKIT=true — skipping Onyx HWR probe")
            false
        } else {
            try {
                OnyxHWREngineAdapter.prepare(context)
            } catch (e: Exception) {
                Log.w(TAG, "OnyxHWR probe failed: ${e.message}")
                false
            }
        }

        if (onyxReady) {
            engine = OnyxHWREngineAdapter
            isOnyxAvailable = true
            Log.i(TAG, "Using OnyxHWREngine (ksync service available)")
        } else {
            Log.i(TAG, "OnyxHWR not available, falling back to ML Kit")
            engine = MlKitHWREngine
            isOnyxAvailable = false
            try {
                MlKitHWREngine.prepare(context)
                Log.i(TAG, "MlKitHWREngine ready")
            } catch (e: Exception) {
                Log.e(TAG, "MlKitHWREngine prepare failed: ${e.message}")
            }
        }
    }

    fun release(context: Context) {
        engine.release(context)
    }
}
