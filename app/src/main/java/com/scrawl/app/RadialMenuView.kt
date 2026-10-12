package com.scrawl.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.*

class RadialMenuView(context: Context) : View(context) {

    data class Item(
        val iconRes: Int,
        val text: String?,
        val active: Boolean = false,
        val action: () -> Unit
    )

    private var items: List<Item> = emptyList()
    private var centerX = 0f
    private var centerY = 0f
    private var highlightedIndex = -1

    private val density = resources.displayMetrics.density
    private val ringRadius = 70f * density
    private val itemRadius = 22f * density
    private val iconHalf = (12f * density).toInt()

    private val itemBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val activeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
    }
    private val highlightBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    var onDismiss: (() -> Unit)? = null

    fun show(cx: Float, cy: Float, menuItems: List<Item>) {
        items = menuItems
        val margin = ringRadius + itemRadius + 12 * density
        val w = width.toFloat()
        val h = height.toFloat()
        centerX = if (w > margin * 2) cx.coerceIn(margin, w - margin) else cx
        centerY = if (h > margin * 2) cy.coerceIn(margin, h - margin) else cy
        highlightedIndex = -1
        visibility = VISIBLE
        Log.i("ScrawlLayers", "radial show items=${items.size} size=${width}x${height}")
        invalidate()
    }

    fun dismiss(reason: String = "explicit") {
        if (visibility != VISIBLE) return
        Log.i("ScrawlLayers", "radial dismiss reason=$reason")
        visibility = GONE
        highlightedIndex = -1
        items = emptyList()
        onDismiss?.invoke()
    }

    val isShowing get() = visibility == VISIBLE

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.i("ScrawlLayers", "radial size=${w}x${h} center=$centerX,$centerY")
    }

    private fun itemCenter(i: Int): Pair<Float, Float> {
        val angle = (2.0 * PI * i / items.size) - PI / 2.0
        return (centerX + ringRadius * cos(angle).toFloat()) to
               (centerY + ringRadius * sin(angle).toFloat())
    }

    private fun hitTest(x: Float, y: Float): Int {
        for (i in items.indices) {
            val (ix, iy) = itemCenter(i)
            if (hypot(x - ix, y - iy) <= itemRadius * 1.15f) return i
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        Log.i("ScrawlLayers", "radial onDraw items=${items.size}")

        for (i in items.indices) {
            val (ix, iy) = itemCenter(i)
            val item = items[i]
            val bg = when {
                i == highlightedIndex -> highlightBgPaint
                item.active -> activeBgPaint
                else -> itemBgPaint
            }
            val fgColor = if (item.active && i != highlightedIndex) Color.WHITE else Color.BLACK
            canvas.drawCircle(ix, iy, itemRadius, bg)
            canvas.drawCircle(ix, iy, itemRadius, outlinePaint)

            if (item.text != null) {
                textPaint.color = fgColor
                val fm = textPaint.fontMetrics
                canvas.drawText(item.text, ix, iy - (fm.ascent + fm.descent) / 2f, textPaint)
            } else if (item.iconRes != 0) {
                ContextCompat.getDrawable(context, item.iconRes)?.let { d ->
                    d.setTint(fgColor)
                    d.setBounds(
                        (ix - iconHalf).toInt(), (iy - iconHalf).toInt(),
                        (ix + iconHalf).toInt(), (iy + iconHalf).toInt()
                    )
                    d.draw(canvas)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                highlightedIndex = hitTest(event.x, event.y)
                Log.i("ScrawlLayers", "radial touch down hit=$highlightedIndex tool=${event.getToolType(0)}")
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val prev = highlightedIndex
                highlightedIndex = hitTest(event.x, event.y)
                if (highlightedIndex != prev) invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val hit = hitTest(event.x, event.y)
                Log.i("ScrawlLayers", "radial touch up hit=$hit")
                val action = if (hit >= 0) items[hit].action else null
                dismiss("touch-up")
                action?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dismiss("touch-cancel")
                return true
            }
        }
        // Any touch outside items dismisses
        dismiss("other-touch-${event.actionMasked}")
        return true
    }
}
