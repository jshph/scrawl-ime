package com.scrawl.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Semi-transparent overlay that shows tooltip bubbles anchored to keyboard buttons.
 * Displayed on first keyboard open, dismissed individually or all at once.
 */
@SuppressLint("ViewConstructor")
class TooltipOverlayView(
    context: Context,
    private val onDismissAll: () -> Unit,
) : FrameLayout(context) {

    data class TooltipAnchor(
        val anchorView: View,
        val text: String,
    )

    private val tooltipViews = mutableListOf<View>()
    private var anchors: List<TooltipAnchor> = emptyList()

    init {
        setBackgroundColor(0x33000000) // light scrim
        isClickable = true // intercept touches on the scrim
    }

    fun setAnchors(anchors: List<TooltipAnchor>) {
        this.anchors = anchors
        // Defer layout until the overlay is measured and positioned
        post { layoutTooltips() }
    }

    private fun layoutTooltips() {
        removeAllViews()
        tooltipViews.clear()

        // "Skip all" button in top-right
        val skipBtn = TextView(context).apply {
            text = "Skip"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            background = tooltipBg()
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { dismissAll() }
        }
        addView(skipBtn, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(16)
            marginEnd = dp(16)
        })

        val overlayLoc = IntArray(2)
        getLocationOnScreen(overlayLoc)

        for (anchor in anchors) {
            val anchorLoc = IntArray(2)
            anchor.anchorView.getLocationOnScreen(anchorLoc)

            val bubble = buildTooltipBubble(anchor.text)
            tooltipViews.add(bubble)

            // Position to the right of the anchor button
            val lp = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                val anchorCenterY = anchorLoc[1] - overlayLoc[1] + anchor.anchorView.height / 2
                topMargin = anchorCenterY - dp(16) // roughly center the bubble
                marginStart = anchorLoc[0] - overlayLoc[0] + anchor.anchorView.width + dp(8)
            }
            addView(bubble, lp)
        }
    }

    private fun buildTooltipBubble(text: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = tooltipBg()
            setPadding(dp(12), dp(8), dp(8), dp(8))
            elevation = 4f
        }

        row.addView(TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
        })

        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.DKGRAY)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val s = dp(20)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { dismissOne(row) }
        })

        return row
    }

    private fun dismissOne(bubble: View) {
        tooltipViews.remove(bubble)
        removeView(bubble)
        if (tooltipViews.isEmpty()) {
            dismissAll()
        }
    }

    private fun dismissAll() {
        onDismissAll()
        (parent as? ViewGroup)?.removeView(this)
    }

    private fun tooltipBg() = android.graphics.drawable.GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(10).toFloat()
        setStroke(dp(1), 0xFFCCCCCC.toInt())
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
