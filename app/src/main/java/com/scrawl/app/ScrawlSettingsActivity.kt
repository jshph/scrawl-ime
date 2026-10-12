package com.scrawl.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class ScrawlSettingsActivity : Activity() {

    private lateinit var recordingDisplay: TextView
    private lateinit var screenshotDisplay: TextView
    private lateinit var langSpinner: Spinner
    private lateinit var lexiconInput: EditText
    private lateinit var penButton1Spinner: Spinner
    private lateinit var penButton2Spinner: Spinner
    private lateinit var appendNewlineSwitch: Switch
    private var langCodes: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFFF5F5F5.toInt())
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Title
        column.addView(TextView(this).apply {
            text = "Scrawl Settings"
            textSize = 22f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(16))
        })

        // Storage section header
        column.addView(TextView(this).apply {
            text = "Storage folders (optional)"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, dp(4), 0, dp(4))
        })
        column.addView(TextView(this).apply {
            text = "Choose specific folders for recordings and screenshots. Scrawl only accesses the folders you select."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(8))
        })

        // Recording folder
        val recordingLabelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        recordingLabelRow.addView(TextView(this).apply {
            text = "Recording folder:"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        recordingLabelRow.addView(TextView(this).apply {
            text = "\uD83D\uDCC2 Browse"
            textSize = 15f
            setTextColor(0xFF4488CC.toInt())
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener { launchFolderPicker(FolderPickerActivity.TARGET_RECORDING) }
        })
        column.addView(recordingLabelRow)
        recordingDisplay = TextView(this).apply {
            text = ScrawlSettings.displayPath(ScrawlSettings.getRecordingFolderUri(this@ScrawlSettingsActivity))
            textSize = 16f
            setTextColor(Color.BLACK)
            background = inputBg()
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        column.addView(recordingDisplay)

        // Screenshot folder
        val screenshotLabelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        screenshotLabelRow.addView(TextView(this).apply {
            text = "Screenshot folder:"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        screenshotLabelRow.addView(TextView(this).apply {
            text = "\uD83D\uDCC2 Browse"
            textSize = 15f
            setTextColor(0xFF4488CC.toInt())
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener { launchFolderPicker(FolderPickerActivity.TARGET_SCREENSHOT) }
        })
        column.addView(screenshotLabelRow)
        screenshotDisplay = TextView(this).apply {
            text = ScrawlSettings.displayPath(ScrawlSettings.getScreenshotFolderUri(this@ScrawlSettingsActivity))
            textSize = 16f
            setTextColor(Color.BLACK)
            background = inputBg()
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        column.addView(screenshotDisplay)

        // HWR Language
        column.addView(TextView(this).apply {
            text = "Handwriting language:"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(14), 0, 0)
        })
        val languages = ScrawlSettings.SUPPORTED_LANGUAGES
        langCodes = languages.keys.toList()
        val langNames = languages.values.toList()
        val currentLang = ScrawlSettings.getHwrLanguage(this)
        langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ScrawlSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                langNames
            )
            val idx = langCodes.indexOf(currentLang).coerceAtLeast(0)
            setSelection(idx)
            background = inputBg()
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        column.addView(langSpinner)

        // Custom lexicon
        column.addView(TextView(this).apply {
            text = "Custom words (one per line):"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(14), 0, 0)
        })
        lexiconInput = EditText(this).apply {
            setText(ScrawlSettings.getCustomLexicon(this@ScrawlSettingsActivity))
            textSize = 16f
            setTextColor(Color.BLACK)
            minLines = 4
            maxLines = 6
            isSingleLine = false
            setHorizontallyScrolling(false)
            gravity = Gravity.TOP or Gravity.START
            background = inputBg()
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        column.addView(lexiconInput)

        column.addView(TextView(this).apply {
            text = "Commit behavior"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, dp(20), 0, dp(4))
        })
        column.addView(TextView(this).apply {
            text = "Add a newline after finishing handwriting so links become tappable immediately and the next note starts on a new line."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(8))
        })
        appendNewlineSwitch = Switch(this).apply {
            text = "Append newline after checkmark"
            textSize = 15f
            setTextColor(Color.BLACK)
            isChecked = ScrawlSettings.getAppendNewlineOnCommit(this@ScrawlSettingsActivity)
        }
        column.addView(appendNewlineSwitch)

        // Pen button mapping
        column.addView(TextView(this).apply {
            text = "Pen button mapping"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, dp(20), 0, dp(4))
        })
        column.addView(TextView(this).apply {
            text = "Button order varies by stylus. Try pressing each button to find which is which."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(8))
        })

        val actions = PenButtonAction.entries
        val actionLabels = actions.map { it.label }

        column.addView(TextView(this).apply {
            text = "Button 1 (lower / secondary):"
            textSize = 15f
            setTextColor(Color.DKGRAY)
        })
        penButton1Spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ScrawlSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                actionLabels
            )
            val current = ScrawlSettings.getPenButton1(this@ScrawlSettingsActivity)
            setSelection(actions.indexOf(current).coerceAtLeast(0))
            background = inputBg()
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        column.addView(penButton1Spinner)

        column.addView(TextView(this).apply {
            text = "Button 2 (upper / tertiary):"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(10), 0, 0)
        })
        penButton2Spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ScrawlSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                actionLabels
            )
            val current = ScrawlSettings.getPenButton2(this@ScrawlSettingsActivity)
            setSelection(actions.indexOf(current).coerceAtLeast(0))
            background = inputBg()
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        column.addView(penButton2Spinner)

        // Save / Close buttons
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }
        btnRow.addView(TextView(this).apply {
            text = "Save"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.DKGRAY)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { save() }
        })
        btnRow.addView(TextView(this).apply {
            text = "Close"
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
                setStroke(1, 0xFFCCCCCC.toInt())
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { finish() }
        })
        column.addView(btnRow)

        scroll.addView(column)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        // Refresh values in case FolderPickerActivity changed them
        recordingDisplay.text = ScrawlSettings.displayPath(ScrawlSettings.getRecordingFolderUri(this))
        screenshotDisplay.text = ScrawlSettings.displayPath(ScrawlSettings.getScreenshotFolderUri(this))
    }

    private fun save() {
        // Folder URIs are set directly by FolderPickerActivity — save other settings
        ScrawlSettings.setHwrLanguage(this, langCodes[langSpinner.selectedItemPosition])
        ScrawlSettings.setCustomLexicon(this, lexiconInput.text.toString())
        ScrawlSettings.setAppendNewlineOnCommit(this, appendNewlineSwitch.isChecked)
        ScrawlSettings.setPenButton1(this, PenButtonAction.entries[penButton1Spinner.selectedItemPosition])
        ScrawlSettings.setPenButton2(this, PenButtonAction.entries[penButton2Spinner.selectedItemPosition])
        finish()
    }

    private fun launchFolderPicker(target: String) {
        val intent = Intent(this, FolderPickerActivity::class.java).apply {
            putExtra(FolderPickerActivity.EXTRA_TARGET, target)
        }
        startActivity(intent)
    }

    private fun inputBg() = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(8).toFloat()
        setStroke(dp(1), 0xFF999999.toInt())
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
