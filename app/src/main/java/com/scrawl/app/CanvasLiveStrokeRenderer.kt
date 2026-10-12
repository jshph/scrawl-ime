package com.scrawl.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke as InkStroke

/**
 * Android-native live preview path. Handles only low-latency presentation of the
 * current in-progress stroke; committed rendering remains separate.
 */
class CanvasLiveStrokeRenderer {
    private val markerFamily by lazy { StockBrushes.marker() }
    private val pressurePenFamily by lazy { StockBrushes.pressurePen() }
    private val identityMatrix = Matrix()

    private var batch: MutableStrokeInputBatch? = null
    private var liveInkStroke: InkStroke? = null
    private var liveDirty = false
    private var brush: Brush? = null

    fun beginStroke(pen: Pen, size: Float) {
        batch = MutableStrokeInputBatch()
        liveInkStroke = null
        liveDirty = false
        val family = if (pen == Pen.FOUNTAIN) pressurePenFamily else markerFamily
        brush = Brush.createWithColorIntArgb(
            family = family,
            colorIntArgb = Color.BLACK,
            size = size,
            epsilon = 0.1f,
        )
    }

    fun addPoint(x: Float, y: Float, pressure: Float, elapsedMs: Long, sensitivity: Float) {
        batch?.add(
            type = InputToolType.STYLUS,
            x = x,
            y = y,
            elapsedTimeMillis = elapsedMs,
            pressure = applyPressureSensitivity(pressure.coerceIn(0f, 1f), sensitivity),
            tiltRadians = -1f,
            orientationRadians = -1f,
        )
        liveDirty = true
    }

    fun rebuildFromPoints(points: List<StrokePoint>, sensitivity: Float) {
        val newBatch = MutableStrokeInputBatch()
        for (pt in points) {
            newBatch.add(
                type = InputToolType.STYLUS,
                x = pt.x,
                y = pt.y,
                elapsedTimeMillis = pt.dt?.toLong() ?: 0L,
                pressure = applyPressureSensitivity((pt.pressure / 4096f).coerceIn(0f, 1f), sensitivity),
                tiltRadians = -1f,
                orientationRadians = -1f,
            )
        }
        batch = newBatch
        liveDirty = true
    }

    fun draw(canvas: Canvas, activePointCount: Int, perfBuild: (Long) -> Unit, perfRender: (Long) -> Unit) {
        if (liveDirty) {
            liveDirty = false
            val currentBrush = brush
            val currentBatch = batch
            if (activePointCount >= 2 && currentBrush != null && currentBatch != null) {
                val t0 = System.nanoTime()
                liveInkStroke = InkStroke(currentBrush, currentBatch)
                perfBuild(System.nanoTime() - t0)
            }
        }

        liveInkStroke?.let {
            try {
                val t0 = System.nanoTime()
                inkRenderer.draw(canvas, it, identityMatrix)
                perfRender(System.nanoTime() - t0)
            } catch (_: Exception) {
            }
        }
    }

    fun clear() {
        liveInkStroke = null
        batch = null
        brush = null
        liveDirty = false
    }
}
