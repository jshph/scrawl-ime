package com.scrawl.app

import android.content.Context

/**
 * Abstraction over handwriting recognition backends.
 *
 * Scrawl supports two HWR engines:
 *   1. OnyxHWREngine — calls Boox firmware's KHwrService (fast, high quality,
 *      but only available on devices with com.onyx.android.ksync installed)
 *   2. MlKitHWREngine — Google ML Kit Digital Ink Recognition (portable fallback,
 *      works on any Android device, downloads model on first use)
 *
 * The active engine is selected at startup via [HWREngineProvider.init] based on
 * whether the Onyx service is reachable. On devices like the Note Air 5C, the
 * Onyx engine is used and ML Kit is never initialized (no model download).
 */
interface HWREngine {
    /** Attempt to prepare the engine. Returns true if ready. */
    suspend fun prepare(context: Context): Boolean

    /** Recognize strokes and return the recognized text, or null on failure. */
    suspend fun recognizeStrokes(
        strokes: List<Stroke>,
        viewWidth: Float,
        viewHeight: Float,
        language: String = "en_US"
    ): String?

    /** Optional: compile a custom lexicon for recognition context. */
    suspend fun compileLexicon(language: String, lexicon: String): Boolean = true

    /** Clean up resources. */
    fun release(context: Context) {}
}
