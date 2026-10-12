package com.scrawl.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.View

enum class RenderBackend {
    Canvas;

    companion object {
        fun fromSetting(value: String): RenderBackend = Canvas
    }
}

enum class InvalidateReason {
    Restore,
    StrokeCommit,
    RawStrokeCommit,
    AppPenStrokeCommit,
    EraseCommit,
    Undo,
    Redo,
    ClearAll,
    AnnotationChanged,
    ToolToggle,
    StashRestore,
    FullRepaint,
}

enum class RenderInvalidationStep {
    RedrawModel,
    InvalidateView,
}

data class RenderInvalidationRequest(
    val backend: RenderBackend,
    val useRawPenControl: Boolean,
)

object RenderInvalidationPolicy {
    fun steps(request: RenderInvalidationRequest): List<RenderInvalidationStep> {
        return listOf(
            RenderInvalidationStep.RedrawModel,
            RenderInvalidationStep.InvalidateView,
        )
    }
}

class ScrawlRenderManager(
    private val host: View,
    private val strokes: () -> List<Stroke>,
    private val pressureSensitivity: () -> Float,
    private val onBeforeFullRedraw: () -> Unit = {},
) {
    private var backingBitmap: Bitmap? = null

    fun onSizeChanged(width: Int, height: Int) {
        backingBitmap?.recycle()
        backingBitmap = null
        if (width <= 0 || height <= 0) return
        backingBitmap = Bitmap.createBitmap(width, height, DeviceProfile.bitmapConfig)
        redrawModel()
    }

    fun drawPersistentLayer(canvas: Canvas, drawStrokesDirectly: Boolean) {
        if (drawStrokesDirectly) {
            for (stroke in strokes()) {
                drawStroke(canvas, stroke, pressureSensitivity())
            }
            return
        }

        val bitmap = backingBitmap ?: return
        if (DeviceProfile.deferBakeWhenRawDrawing) {
            synchronized(bitmap) {
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            }
        } else {
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        }
    }

    fun redrawModel() {
        val bitmap = backingBitmap ?: return
        onBeforeFullRedraw()
        val drawBlock = {
            val canvas = Canvas(bitmap)
            // Persistent ink must preserve the host app beneath on every display.
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            for (stroke in strokes()) {
                drawStroke(canvas, stroke, pressureSensitivity())
            }
        }
        if (DeviceProfile.deferBakeWhenRawDrawing) {
            synchronized(bitmap) { drawBlock() }
        } else {
            drawBlock()
        }
    }

    fun bakeStroke(stroke: Stroke) {
        val bitmap = backingBitmap ?: return
        val drawBlock = {
            drawStroke(Canvas(bitmap), stroke, pressureSensitivity())
        }
        if (DeviceProfile.deferBakeWhenRawDrawing) {
            synchronized(bitmap) { drawBlock() }
        } else {
            drawBlock()
        }
    }

    fun invalidateModel(reason: InvalidateReason, useRawPenControl: Boolean = true, useRegal: Boolean = false) {
        redrawModel()
        host.invalidate()
    }

    fun refreshView(useRegal: Boolean = false) {
        host.invalidate()
    }
}
