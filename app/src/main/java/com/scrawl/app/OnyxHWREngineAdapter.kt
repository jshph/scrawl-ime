package com.scrawl.app

import android.content.Context

/**
 * HWREngine adapter wrapping the existing OnyxHWREngine singleton.
 * Used on Boox devices where com.onyx.android.ksync is available.
 */
object OnyxHWREngineAdapter : HWREngine {

    override suspend fun prepare(context: Context): Boolean {
        return OnyxHWREngine.bindAndAwait(context)
    }

    override suspend fun recognizeStrokes(
        strokes: List<Stroke>,
        viewWidth: Float,
        viewHeight: Float,
        language: String
    ): String? {
        return OnyxHWREngine.recognizeStrokes(strokes, viewWidth, viewHeight, language)
    }

    override suspend fun compileLexicon(language: String, lexicon: String): Boolean {
        return OnyxHWREngine.compileLexicon(language, lexicon)
    }

    override fun release(context: Context) {
        OnyxHWREngine.unbind(context)
    }
}
