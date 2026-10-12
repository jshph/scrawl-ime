package com.scrawl.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.scrawl.app.action.START_RECORDING"
        const val ACTION_STOP = "com.scrawl.app.action.STOP_RECORDING"
        const val ACTION_STATE_CHANGED = "com.scrawl.app.action.RECORDING_STATE_CHANGED"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        private const val CHANNEL_ID = "scrawl_recording"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "ScrawlRecording"

        private enum class State { IDLE, STARTING, RECORDING, STOPPING }

        @Volatile
        private var state = State.IDLE

        val isActive: Boolean
            get() = state != State.IDLE

        val isRecording: Boolean
            get() = state == State.RECORDING

        @Volatile
        var startTimeMs = 0L
            private set

        @Volatile
        var outputUri: Uri? = null
            private set

        @Synchronized
        fun requestStart(context: Context): Boolean {
            if (state != State.IDLE) return false
            state = State.STARTING
            return try {
                context.startForegroundService(Intent(context, RecordingService::class.java).apply {
                    action = ACTION_START
                })
                true
            } catch (e: RuntimeException) {
                state = State.IDLE
                Log.e(TAG, "Failed to request recording start", e)
                false
            }
        }

        @Synchronized
        fun requestStop(context: Context): Boolean {
            if (state == State.IDLE || state == State.STOPPING) return false
            state = State.STOPPING
            return try {
                context.startService(Intent(context, RecordingService::class.java).apply {
                    action = ACTION_STOP
                })
                true
            } catch (e: RuntimeException) {
                state = if (startTimeMs != 0L) State.RECORDING else State.IDLE
                Log.e(TAG, "Failed to request recording stop", e)
                false
            }
        }
    }

    private var recorder: MediaRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outputDocument: DocumentFile? = null
    private var imeReceiverRegistered = false

    private val inputMethodChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_INPUT_METHOD_CHANGED || recorder == null) return
            if (!isScrawlSelectedInputMethod()) {
                Log.i(TAG, "Scrawl is no longer the selected input method; saving recording")
                stopRecording()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            inputMethodChangedReceiver,
            IntentFilter(Intent.ACTION_INPUT_METHOD_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        imeReceiverRegistered = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (recorder != null) return

        var pendingRecorder: MediaRecorder? = null
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())

            val dir = ScrawlSettings.getRecordingDocumentFile(this)
            if (dir == null || !dir.exists()) {
                throw IllegalStateException("Recording folder is unavailable")
            }
            val createdFile = dir.createFile("audio/mp4", buildFilename())
                ?: throw IllegalStateException("Failed to create recording file")
            outputDocument = createdFile
            outputUri = createdFile.uri

            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "scrawl:recording").apply {
                acquire()
            }

            val fd = contentResolver.openFileDescriptor(createdFile.uri, "w")
                ?: throw IllegalStateException("Failed to open recording file")
            pfd = fd

            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            pendingRecorder = mediaRecorder
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(128_000)
            mediaRecorder.setAudioSamplingRate(44_100)
            mediaRecorder.setOutputFile(fd.fileDescriptor)
            mediaRecorder.prepare()
            mediaRecorder.start()

            recorder = mediaRecorder
            pendingRecorder = null
            startTimeMs = System.currentTimeMillis()
            state = State.RECORDING
            sendStateChanged()
            Log.i(TAG, "Recording started: ${createdFile.uri}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            try {
                pendingRecorder?.release()
            } catch (releaseError: Exception) {
                Log.w(TAG, "Failed to release recorder after start failure", releaseError)
            }
            releaseResources(deleteOutput = true)
            sendStateChanged(e.message ?: "Failed to start recording")
            stopSelf()
        }
    }

    private fun stopRecording() {
        val mediaRecorder = recorder
        if (mediaRecorder == null) {
            releaseResources(deleteOutput = state != State.IDLE)
            sendStateChanged()
            stopSelf()
            return
        }

        val savedUri = outputUri
        var errorMessage: String? = null
        var deleteOutput = false
        try {
            mediaRecorder.stop()
            Log.i(TAG, "Recording saved: $savedUri")
        } catch (e: Exception) {
            deleteOutput = true
            errorMessage = e.message ?: "Failed to save recording"
            Log.e(TAG, "Failed to stop recording", e)
        } finally {
            try {
                mediaRecorder.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release recorder", e)
            }
        }
        releaseResources(deleteOutput)
        sendStateChanged(errorMessage)
        stopSelf()
    }

    private fun releaseResources(deleteOutput: Boolean) {
        recorder = null
        try {
            pfd?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close recording file", e)
        }
        pfd = null
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        if (deleteOutput) {
            try {
                outputDocument?.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete incomplete recording", e)
            }
        }
        outputDocument = null
        startTimeMs = 0L
        outputUri = null
        state = State.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun isScrawlSelectedInputMethod(): Boolean {
        val selectedId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ) ?: return false
        val selectedComponent = ComponentName.unflattenFromString(selectedId) ?: return false
        return selectedComponent.packageName == packageName &&
            selectedComponent.className == ScrawlIMEService::class.java.name
    }

    private fun sendStateChanged(errorMessage: String? = null) {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
            if (errorMessage != null) putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
        })
    }

    override fun onDestroy() {
        if (recorder != null) {
            stopRecording()
        } else if (state != State.IDLE) {
            releaseResources(deleteOutput = true)
            sendStateChanged()
        }
        if (imeReceiverRegistered) {
            unregisterReceiver(inputMethodChangedReceiver)
            imeReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active audio recording"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Scrawl")
            .setContentText("Recording audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    private fun buildFilename(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(Date())
        return "$timestamp.m4a"
    }
}
