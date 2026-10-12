package com.scrawl.app

import java.util.Date

data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 2048f,
    val dt: UShort? = null,
)

data class Stroke(
    val size: Float,
    val pen: Pen,
    val color: Int,
    val maxPressure: Int = 4096,
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    val points: List<StrokePoint>,
    val createdAt: Date = Date(),
)
