package com.scrawl.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class OnboardingActivity : Activity() {

    private lateinit var stepContainer: LinearLayout
    private lateinit var enableCheck: ImageView
    private lateinit var switchCheck: ImageView
    private lateinit var enableBtn: View
    private lateinit var switchBtn: View
    private lateinit var doneSection: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ScrawlSettings.isOnboardingComplete(this)) {
            finish()
            return
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        stepContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(48), dp(32), dp(48))
        }

        // Title
        stepContainer.addView(TextView(this).apply {
            text = "Scrawl"
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
        })

        stepContainer.addView(TextView(this).apply {
            text = "Turn your stylus into a keyboard.\nWrite anywhere, in any app."
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(32))
        })

        // Step 1: Enable input method
        stepContainer.addView(buildStepHeader("1", "Enable Scrawl as an input method"))
        stepContainer.addView(TextView(this).apply {
            text = "Open system settings and toggle Scrawl on in the keyboard list."
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(dp(36), 0, 0, dp(8))
        })

        val enableRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(36), 0, 0, dp(24))
        }
        enableBtn = buildActionButton("Open Input Settings") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        enableRow.addView(enableBtn)
        enableCheck = buildCheckIcon()
        enableRow.addView(enableCheck)
        stepContainer.addView(enableRow)

        // Step 2: Set as default
        stepContainer.addView(buildStepHeader("2", "Set Scrawl as your keyboard"))
        stepContainer.addView(TextView(this).apply {
            text = "Choose Scrawl from the keyboard picker."
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(dp(36), 0, 0, dp(8))
        })

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(36), 0, 0, dp(24))
        }
        switchBtn = buildActionButton("Switch Keyboard") {
            @Suppress("DEPRECATION")
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        switchRow.addView(switchBtn)
        switchCheck = buildCheckIcon()
        switchRow.addView(switchCheck)
        stepContainer.addView(switchRow)

        // Step 3: Done
        stepContainer.addView(buildStepHeader("3", "You're ready"))
        stepContainer.addView(TextView(this).apply {
            text = "Open any app and tap a text field. Scrawl will appear as your keyboard."
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(dp(36), 0, 0, dp(12))
        })
        stepContainer.addView(TextView(this).apply {
            text = "Tip: drag the dotted grips on the top and bottom tool blocks to move them across the edge of the screen."
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(dp(36), 0, 0, dp(12))
        })
        stepContainer.addView(TextView(this).apply {
            text = "Tip: for the best writing experience, set your Boox refresh mode to Speed or Fast. In Regal mode, ink may not appear until you lift the pen."
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(dp(36), 0, 0, dp(20))
        })

        doneSection = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(36), 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL

            addView(buildActionButton("Done") {
                ScrawlSettings.setOnboardingComplete(this@OnboardingActivity)
                finish()
            })
        }
        stepContainer.addView(doneSection)

        scroll.addView(stepContainer)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshStepStates()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStepStates()
    }

    private fun refreshStepStates() {
        val imeEnabled = isScrawlEnabled()
        val imeActive = isScrawlActive()

        enableCheck.visibility = if (imeEnabled) View.VISIBLE else View.GONE
        switchCheck.visibility = if (imeActive) View.VISIBLE else View.GONE

        // Enable switch button only after step 1 is complete
        switchBtn.alpha = if (imeEnabled) 1f else 0.4f
        switchBtn.isEnabled = imeEnabled

        // Show done section only when both steps are done
        doneSection.visibility = if (imeEnabled && imeActive) View.VISIBLE else View.GONE
    }

    private fun isScrawlEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val myComponent = ComponentName(packageName, "$packageName.ScrawlIMEService")
        return imm.enabledInputMethodList.any { it.component == myComponent }
    }

    private fun isScrawlActive(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val myComponent = ComponentName(packageName, "$packageName.ScrawlIMEService")
        // currentInputMethodInfo is available on SDK 34+
        return if (android.os.Build.VERSION.SDK_INT >= 34) {
            imm.currentInputMethodInfo?.component == myComponent
        } else {
            val currentId = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
            currentId == myComponent.flattenToString()
        }
    }

    private fun buildStepHeader(number: String, title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))

            addView(TextView(this@OnboardingActivity).apply {
                text = number
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                val circleSize = dp(28)
                layoutParams = LinearLayout.LayoutParams(circleSize, circleSize).apply {
                    marginEnd = dp(8)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.BLACK)
                }
            })

            addView(TextView(this@OnboardingActivity).apply {
                text = title
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.BLACK)
            })
        }
    }

    private fun buildActionButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(20), dp(10), dp(20), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    private fun buildCheckIcon(): ImageView {
        return ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            setColorFilter(0xFF2E7D32.toInt())
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginStart = dp(12)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
