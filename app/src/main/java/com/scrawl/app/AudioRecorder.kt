package com.scrawl.app

import android.content.Context
import android.net.Uri
import android.util.Log
import java.util.Locale

/**
 * Small UI-facing controller for [RecordingService]. Recording state lives in the
 * service so it survives recreation of the IME and its input view.
 */
class AudioRecorder(context: Context) {
    private val appContext = context.applicationContext

    val isRecording: Boolean
        get() = RecordingService.isActive

    fun start(): Boolean {
        val dir = ScrawlSettings.getRecordingDocumentFile(appContext)
        if (dir == null || !dir.exists()) {
            Log.e(TAG, "No recording folder configured")
            return false
        }
        return RecordingService.requestStart(appContext)
    }

    fun stop(): Uri? {
        val uri = RecordingService.outputUri
        return if (RecordingService.requestStop(appContext)) uri else null
    }

    fun elapsedTimestamp(): String {
        val startTimeMs = RecordingService.startTimeMs
        if (startTimeMs == 0L) return ""
        val elapsed = System.currentTimeMillis() - startTimeMs
        val hours = elapsed / 3_600_000
        val minutes = (elapsed / 60_000) % 60
        val seconds = (elapsed / 1_000) % 60
        return String.format(Locale.US, "[%02d:%02d:%02d]", hours, minutes, seconds)
    }

    private companion object {
        const val TAG = "Scrawl"
    }
}
