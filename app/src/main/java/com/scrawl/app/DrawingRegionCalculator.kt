package com.scrawl.app

import android.graphics.Rect

/**
 * Computes tiled drawing regions by subtracting exclusion rects (toolbars)
 * from the canvas bounds. Output rects are non-overlapping and gap-free.
 *
 * Uses horizontal strip decomposition: splits the canvas at each exclusion
 * rect's Y edges, then within each strip computes the X complement.
 */
object DrawingRegionCalculator {

    private const val MIN_DIMENSION = 50 // ignore slivers smaller than this

    /**
     * @param canvas  Full canvas bounds (screen-space)
     * @param exclude Toolbar/overlay rects to exclude (screen-space), padded by caller
     * @return List of non-overlapping rects covering the drawable area
     */
    fun compute(canvas: Rect, exclude: List<Rect>): List<Rect> {
        if (exclude.isEmpty()) return listOf(canvas)

        // Collect Y breakpoints
        val ys = sortedSetOf(canvas.top, canvas.bottom)
        for (e in exclude) {
            if (e.top > canvas.top && e.top < canvas.bottom) ys.add(e.top)
            if (e.bottom > canvas.top && e.bottom < canvas.bottom) ys.add(e.bottom)
        }

        val yList = ys.toList()
        val result = mutableListOf<Rect>()

        for (i in 0 until yList.size - 1) {
            val stripTop = yList[i]
            val stripBottom = yList[i + 1]
            if (stripBottom - stripTop < MIN_DIMENSION) continue

            // Find excluded X ranges in this strip
            val xRanges = mutableListOf<IntArray>() // [left, right] pairs
            for (e in exclude) {
                if (e.top < stripBottom && e.bottom > stripTop) {
                    val left = e.left.coerceAtLeast(canvas.left)
                    val right = e.right.coerceAtMost(canvas.right)
                    if (left < right) xRanges.add(intArrayOf(left, right))
                }
            }

            // Merge overlapping X ranges
            xRanges.sortBy { it[0] }
            val merged = mutableListOf<IntArray>()
            for (r in xRanges) {
                if (merged.isEmpty() || merged.last()[1] < r[0]) {
                    merged.add(r)
                } else {
                    merged.last()[1] = maxOf(merged.last()[1], r[1])
                }
            }

            // Compute complement (drawable) X ranges
            var x = canvas.left
            for (m in merged) {
                if (m[0] > x && m[0] - x >= MIN_DIMENSION) {
                    result.add(Rect(x, stripTop, m[0], stripBottom))
                }
                x = m[1]
            }
            if (canvas.right > x && canvas.right - x >= MIN_DIMENSION) {
                result.add(Rect(x, stripTop, canvas.right, stripBottom))
            }
        }

        // Fallback: if everything was excluded, return the full canvas
        return result.ifEmpty { listOf(canvas) }
    }
}
