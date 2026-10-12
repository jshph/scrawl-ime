package com.scrawl.app

import android.graphics.RectF

data class AnnotationRegion(
    val bounds: RectF,
    val mode: AnnotationMode,
)
