package com.scrawl.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class FolderPickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQ_FOLDER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                // Take persistable permission so it survives reboots
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)

                val target = intent.getStringExtra(EXTRA_TARGET) ?: TARGET_RECORDING
                when (target) {
                    TARGET_RECORDING -> ScrawlSettings.setRecordingFolderUri(this, uri.toString())
                    TARGET_SCREENSHOT -> ScrawlSettings.setScreenshotFolderUri(this, uri.toString())
                }
            }
        }
        finish()
    }

    companion object {
        private const val REQ_FOLDER = 2001
        const val EXTRA_TARGET = "pick_target"
        const val TARGET_RECORDING = "recording"
        const val TARGET_SCREENSHOT = "screenshot"
    }
}
