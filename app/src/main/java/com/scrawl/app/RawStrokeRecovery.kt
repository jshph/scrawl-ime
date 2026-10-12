package com.scrawl.app

/**
 * Recover the exact BEGIN/MOVE + repeated full batch + END corruption from the
 * old Onyx callback assembly. Require identical coordinates, pressure and time
 * in both copies; a genuinely closed path or a timestamp reset is not enough.
 * The persisted file is left intact until the normal next document save.
 */
fun recoverRepeatedRawStroke(points: List<StrokePoint>): List<StrokePoint> {
    if (points.size < 5 || points.size % 2 != 1) return points
    val half = points.size / 2
    if (points.first().dt?.toInt() != 0) return points
    val lastTime = points[half - 1].dt?.toInt() ?: return points
    val endTime = points.last().dt?.toInt() ?: return points
    if (lastTime <= 0 || endTime < lastTime) return points
    for (i in 0 until half) {
        if (points[i] != points[i + half]) return points
        val time = points[i].dt?.toInt() ?: return points
        if (i > 0 && time < points[i - 1].dt!!.toInt()) return points
    }
    return points.drop(half)
}
