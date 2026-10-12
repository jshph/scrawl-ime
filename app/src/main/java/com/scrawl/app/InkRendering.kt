package com.scrawl.app

import android.graphics.Canvas
import android.graphics.Matrix
import kotlin.math.pow
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke as InkStroke

val inkRenderer: CanvasStrokeRenderer by lazy { CanvasStrokeRenderer.create() }

private val markerFamily by lazy { StockBrushes.marker() }
private val pressurePenFamily by lazy { StockBrushes.pressurePen() }
private val identityMatrix = Matrix()
private const val SYNTHETIC_DT_MS = 5L

fun brushForStroke(stroke: Stroke): Brush {
    val family = when (stroke.pen) {
        Pen.BALLPEN -> markerFamily
        Pen.FOUNTAIN -> pressurePenFamily
    }
    return Brush.createWithColorIntArgb(
        family = family,
        colorIntArgb = stroke.color,
        size = stroke.size,
        epsilon = 0.1f
    )
}

/** Remap raw normalised pressure through the sensitivity curve.
 *  sensitivity=0 → flat 0.5 (uniform width), sensitivity=1 → full progressive response.
 *  Uses a concave power curve (exponent < 1) so light pressure already produces
 *  noticeable width variation — more dramatic early-ramp modulation than a linear scale. */
fun applyPressureSensitivity(raw: Float, sensitivity: Float): Float {
    if (sensitivity <= 0f) return 0.5f
    // exponent goes from 1.0 (linear) at sensitivity=0 down to 0.4 (strongly progressive) at 1.0
    val exponent = (1f - sensitivity * 0.6f).toDouble()
    val curved = raw.toDouble().pow(exponent).toFloat()
    return 0.5f + (curved - 0.5f) * sensitivity
}

fun Stroke.toInkStroke(sensitivity: Float = 0.5f): InkStroke {
    val batch = MutableStrokeInputBatch()
    if (points.isEmpty()) {
        return InkStroke(brushForStroke(this), batch.toImmutable())
    }

    // Ink API requires strictly non-decreasing elapsed_time. Persisted strokes
    // can have non-monotonic dt from progressive baking (tail segments restart
    // at dt=0). Enforce monotonic by tracking the running max and using
    // SYNTHETIC_DT_MS spacing for resets — 1ms jumps cause the renderer to
    // draw straight-line artifacts between spatially distant points.
    var lastElapsed = 0L
    for (i in points.indices) {
        val pt = points[i]
        val rawElapsed = pt.dt?.toLong() ?: (i * SYNTHETIC_DT_MS)
        val elapsedMs = if (rawElapsed >= lastElapsed) rawElapsed else lastElapsed + SYNTHETIC_DT_MS
        lastElapsed = elapsedMs
        val rawPressure = (pt.pressure / maxPressure.toFloat()).coerceIn(0f, 1f)
        val pressure = applyPressureSensitivity(rawPressure, sensitivity)

        batch.add(
            type = InputToolType.STYLUS,
            x = pt.x,
            y = pt.y,
            elapsedTimeMillis = elapsedMs,
            pressure = pressure,
            tiltRadians = -1f,
            orientationRadians = -1f
        )
    }
    return InkStroke(brushForStroke(this), batch)
}

fun drawStroke(canvas: Canvas, stroke: Stroke, sensitivity: Float = 0.5f) {
    if (stroke.points.size < 2) return
    // Draw each model stroke as an independent open path. The point sequence
    // must not replay a completed raw batch after its streamed points.
    drawStrokePath(canvas, stroke)
}

private val pathPaint = android.graphics.Paint().apply {
    style = android.graphics.Paint.Style.STROKE
    isAntiAlias = true
    strokeCap = android.graphics.Paint.Cap.ROUND
    strokeJoin = android.graphics.Paint.Join.ROUND
}

private fun drawStrokePath(canvas: Canvas, stroke: Stroke) {
    val pts = stroke.points
    pathPaint.color = stroke.color
    pathPaint.strokeWidth = stroke.size

    val path = android.graphics.Path()
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) {
        // Quadratic bezier through midpoints for smooth curves
        val prev = pts[i - 1]
        val cur = pts[i]
        val midX = (prev.x + cur.x) / 2f
        val midY = (prev.y + cur.y) / 2f
        path.quadTo(prev.x, prev.y, midX, midY)
    }
    // Final segment to the last point
    val last = pts.last()
    path.lineTo(last.x, last.y)
    canvas.drawPath(path, pathPaint)
}
