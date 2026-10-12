package com.scrawl.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

class ScreenshotActivity : Activity() {

    companion object {
        private const val REQ_PROJECTION = 2001
        private const val TAG = "ScrawlScreenshot"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Log.i(TAG, "MediaProjection permission granted")
                val serviceIntent = Intent(this, ScreenshotService::class.java).apply {
                    action = ScreenshotService.ACTION_START
                    putExtra(ScreenshotService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenshotService.EXTRA_DATA, data)
                }
                startForegroundService(serviceIntent)
            } else {
                Log.w(TAG, "MediaProjection permission denied")
                ScreenshotService.captureCallback?.invoke(false)
                ScreenshotService.captureCallback = null
            }
        }
        finish()
    }
}
