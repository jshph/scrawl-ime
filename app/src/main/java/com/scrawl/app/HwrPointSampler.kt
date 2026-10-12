package com.scrawl.app

/** Limits dense raw pen input before sending it to the Onyx recognizer. */
internal object HwrPointSampler {
    private const val INTERVAL_MS = 8

    fun sample(points: List<StrokePoint>): List<StrokePoint> {
        if (points.size <= 12) return points
        val firstTime = points.first().dt?.toInt() ?: return points
        if (points.last().dt == null) return points
        val result = ArrayList<StrokePoint>(points.size)
        result.add(points.first())
        var lastKeptTime = firstTime
        for (index in 1 until points.lastIndex) {
            val point = points[index]
            val time = point.dt?.toInt() ?: return points
            if (time - lastKeptTime >= INTERVAL_MS) {
                result.add(point)
                lastKeptTime = time
            }
        }
        result.add(points.last())
        return result
    }
}
