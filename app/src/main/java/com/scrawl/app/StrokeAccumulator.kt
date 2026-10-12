package com.scrawl.app

import android.graphics.Color
import android.graphics.RectF
import java.util.Date

/**
 * Canonical in-progress stroke state shared by all live rendering backends.
 *
 * `activePoints` holds the currently visible tail for low-latency presentation.
 * `allPoints` retains the full stroke for persistence, HWR, and final commit.
 */
class StrokeAccumulator(
    private val minMoveSq: Float,
) {
    private var startTimeMs = 0L
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastDt = -1L

    val activePoints = mutableListOf<StrokePoint>()
    val allPoints = mutableListOf<StrokePoint>()

    val hasEnoughPoints: Boolean
        get() = allPoints.size >= 2

    val startedAt: Long
        get() = startTimeMs

    fun reset(startTimeMs: Long) {
        this.startTimeMs = startTimeMs
        activePoints.clear()
        allPoints.clear()
        lastX = Float.NaN
        lastY = Float.NaN
        lastDt = -1L
    }

    /**
     * Adds a point using an absolute event timestamp. Returns the accepted point,
     * or null if it was filtered as a duplicate/jitter sample.
     */
    fun addAbsolutePoint(
        x: Float,
        y: Float,
        pressure: Float,
        eventTimeMs: Long,
        pressureScale: Float,
        filteredCounter: (() -> Unit)? = null,
    ): StrokePoint? {
        val dt = (eventTimeMs - startTimeMs).toInt().coerceAtLeast(if (lastDt >= 0) lastDt.toInt() else 0)
        return addElapsedPoint(x, y, pressure, dt, pressureScale, filteredCounter)
    }

    /**
     * Adds a point using a precomputed elapsed time. Used for Boox raw callbacks.
     */
    fun addElapsedPoint(
        x: Float,
        y: Float,
        pressure: Float,
        elapsedMs: Int,
        pressureScale: Float,
        filteredCounter: (() -> Unit)? = null,
    ): StrokePoint? {
        val dtLong = elapsedMs.toLong()
        if (x == lastX && y == lastY && dtLong == lastDt) {
            filteredCounter?.invoke()
            return null
        }
        if (!lastX.isNaN()) {
            val dx = x - lastX
            val dy = y - lastY
            if (dx * dx + dy * dy < minMoveSq && dtLong == lastDt) {
                filteredCounter?.invoke()
                return null
            }
        }
        lastX = x
        lastY = y
        lastDt = dtLong

        val point = StrokePoint(
            x = x,
            y = y,
            pressure = pressure * pressureScale,
            dt = elapsedMs.toUShort(),
        )
        activePoints.add(point)
        allPoints.add(point)
        return point
    }

    fun bounds(points: List<StrokePoint> = allPoints): RectF? {
        if (points.size < 2) return null
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        for (pt in points) {
            if (pt.x < minX) minX = pt.x
            if (pt.x > maxX) maxX = pt.x
            if (pt.y < minY) minY = pt.y
            if (pt.y > maxY) maxY = pt.y
        }
        return if (minX < maxX && minY < maxY) RectF(minX, minY, maxX, maxY) else null
    }

    fun committedStroke(
        penSize: Float,
        pen: Pen,
        color: Int = Color.BLACK,
        points: List<StrokePoint> = allPoints,
    ): Stroke? {
        val bounds = bounds(points) ?: return null
        return Stroke(
            size = penSize,
            pen = pen,
            color = color,
            top = bounds.top,
            bottom = bounds.bottom,
            left = bounds.left,
            right = bounds.right,
            points = points.toList(),
            createdAt = Date(startTimeMs),
        )
    }

    fun trimActiveToTail(tailSize: Int) {
        if (activePoints.size <= tailSize) return
        val tail = activePoints.subList(activePoints.size - tailSize, activePoints.size).toList()
        activePoints.clear()
        activePoints.addAll(tail)
    }

    fun clear() {
        activePoints.clear()
        allPoints.clear()
        lastX = Float.NaN
        lastY = Float.NaN
        lastDt = -1L
    }
}
