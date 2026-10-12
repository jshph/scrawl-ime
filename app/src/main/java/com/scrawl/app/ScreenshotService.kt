package com.scrawl.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

class ScreenshotService : Service() {

    companion object {
        const val ACTION_START = "com.scrawl.app.action.START_PROJECTION"
        const val ACTION_CAPTURE = "com.scrawl.app.action.CAPTURE"
        const val ACTION_STOP = "com.scrawl.app.action.STOP_PROJECTION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_RECT_LEFT = "rect_left"
        const val EXTRA_RECT_TOP = "rect_top"
        const val EXTRA_RECT_RIGHT = "rect_right"
        const val EXTRA_RECT_BOTTOM = "rect_bottom"
        const val EXTRA_CANVAS_X = "canvas_x"
        const val EXTRA_CANVAS_Y = "canvas_y"
        const val EXTRA_OUTPUT_URI = "output_uri"
        private const val CHANNEL_ID = "scrawl_screenshot"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "ScrawlScreenshot"

        var isReady = false
            private set

        /** Callback invoked on main thread when capture completes */
        var captureCallback: ((Boolean) -> Unit)? = null
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var screenWidth = 0
    private var screenHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (data != null) startProjection(resultCode, data)
            }
            ACTION_CAPTURE -> {
                val uriStr = intent.getStringExtra(EXTRA_OUTPUT_URI)
                val rect = RectF(
                    intent.getFloatExtra(EXTRA_RECT_LEFT, 0f),
                    intent.getFloatExtra(EXTRA_RECT_TOP, 0f),
                    intent.getFloatExtra(EXTRA_RECT_RIGHT, 0f),
                    intent.getFloatExtra(EXTRA_RECT_BOTTOM, 0f)
                )
                val canvasX = intent.getIntExtra(EXTRA_CANVAS_X, 0)
                val canvasY = intent.getIntExtra(EXTRA_CANVAS_Y, 0)
                if (uriStr != null) {
                    captureFrame(rect, Uri.parse(uriStr), canvasX, canvasY)
                }
            }
            ACTION_STOP -> stopProjection()
        }
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped")
                cleanupDisplay()
                isReady = false
                projection = null
            }
        }, handler)

        // Set up persistent VirtualDisplay + ImageReader
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = projection!!.createVirtualDisplay(
            "ScrawlScreenshot",
            screenWidth, screenHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, handler
        )

        isReady = true
        Log.i(TAG, "MediaProjection ready, VirtualDisplay created ${screenWidth}x${screenHeight}")
    }

    private fun captureFrame(rect: RectF, outUri: Uri, canvasScreenX: Int, canvasScreenY: Int) {
        val reader = imageReader
        if (reader == null) {
            Log.e(TAG, "No active ImageReader")
            captureCallback?.invoke(false)
            captureCallback = null
            return
        }

        // Grab the latest frame from the ImageReader
        val image = reader.acquireLatestImage()
        if (image == null) {
            Log.e(TAG, "No image available from ImageReader")
            captureCallback?.invoke(false)
            captureCallback = null
            return
        }

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to just the screen width (remove row padding)
            val cleanBitmap = if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle()
                cropped
            } else {
                bitmap
            }

            // Crop to the requested region
            val x = (rect.left + canvasScreenX).toInt().coerceAtLeast(0)
            val y = (rect.top + canvasScreenY).toInt().coerceAtLeast(0)
            val w = rect.width().toInt().coerceAtMost(cleanBitmap.width - x)
            val h = rect.height().toInt().coerceAtMost(cleanBitmap.height - y)
            Log.i(TAG, "crop: x=$x y=$y w=$w h=$h screen=${screenWidth}x${screenHeight}")

            if (w > 0 && h > 0) {
                val cropped = Bitmap.createBitmap(cleanBitmap, x, y, w, h)
                cleanBitmap.recycle()

                contentResolver.openOutputStream(outUri)?.use { out ->
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                cropped.recycle()
                Log.i(TAG, "Screenshot saved: $outUri")
                captureCallback?.invoke(true)
            } else {
                Log.e(TAG, "Invalid crop dimensions")
                cleanBitmap.recycle()
                captureCallback?.invoke(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture processing failed: ${e.message}", e)
            captureCallback?.invoke(false)
        } finally {
            image.close()
            captureCallback = null
        }
    }

    private fun cleanupDisplay() {
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
    }

    private fun stopProjection() {
        cleanupDisplay()
        projection?.stop()
        projection = null
        isReady = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cleanupDisplay()
        projection?.stop()
        projection = null
        isReady = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screenshot",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Screen capture for Scrawl"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Scrawl")
            .setContentText("Screenshot capture ready")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
