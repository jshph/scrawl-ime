package com.scrawl.app

import android.util.Log
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke as InkStroke
import java.util.Date

class IMECanvasView(context: Context) : View(context) {

    private val strokes = mutableListOf<Stroke>()
    private val redoStack = mutableListOf<Any>()  // Stroke or AnnotationRegion
    private var backingBitmap: Bitmap? = null

    private val currentPoints = mutableListOf<StrokePoint>()
    private var strokeStartTime = 0L

    // Live Ink API rendering — deferred to onDraw for minimal lag
    private var liveBatch: MutableStrokeInputBatch? = null
    private var liveInkStroke: InkStroke? = null
    private var liveDirty = false
    private val identityMatrix = Matrix()
    private var liveBrush: Brush? = null

    // Progressive bake: allStrokePoints keeps full history for HWR,
    // currentPoints holds only the live rendering tail
    private val allStrokePoints = mutableListOf<StrokePoint>()

    // Finger double-tap callback — triggers mode switch to select
    var onFingerDoubleTap: (() -> Unit)? = null
    var onStrokeCountChanged: ((Int) -> Unit)? = null
    var onPenButton1: ((Float, Float) -> Unit)? = null
    var onPenButton2: ((Float, Float) -> Unit)? = null
    var onPenButtonRelease: (() -> Unit)? = null
    var persistenceContext: Context? = null

    // Double-tap detection state
    private var lastFingerTapMs = 0L
    private var lastFingerTapX = 0f
    private var lastFingerTapY = 0f

    private companion object {
        const val TAG = "ScrawlPerf"
        const val BAKE_OVERLAP = 8
        const val MIN_MOVE_SQ = 0.25f  // (0.5px)^2 — sub-pixel jitter threshold
        const val PERF_LOG_INTERVAL = 60  // log summary every N frames

        const val DOUBLE_TAP_TIMEOUT_MS = 300L  // max time between taps
        const val DOUBLE_TAP_SLOP_PX = 50f      // max distance between taps
        const val TAP_MAX_DURATION_MS = 200L     // max hold time to count as a tap
    }

    // Bake threshold from DeviceProfile — lower on weaker devices to cap
    // per-frame InkStroke construction cost. See DeviceProfile.kt.
    private val bakeThreshold: Int get() = DeviceProfile.bakeThreshold

    // Perf counters — reset every PERF_LOG_INTERVAL frames
    private var perfFrameCount = 0
    private var perfBuildNs = 0L      // InkStroke construction
    private var perfRenderNs = 0L     // inkRenderer.draw
    private var perfTouchToDrawNs = 0L // touch event time → onDraw start
    private var perfBakeCount = 0
    private var perfBakeNs = 0L
    private var perfPointsFiltered = 0
    private var lastTouchNs = 0L      // uptimeNanos at last ACTION_MOVE
    var lastToolWasPen = true          // tracks whether last input was stylus (true) or finger (false)

    // Onyx TouchHelper — raw EPD drawing, bypasses Android View pipeline
    private var touchHelper: Any? = null  // com.onyx.android.sdk.pen.TouchHelper
    private var rawDrawingActive = false
    private var rawUiOverlayVisible = false
    private var rawStrokeSuppressed = false
    private var rawRenderingSuspended = false

    /** Yield the vendor preview to Android UI; keep raw input/model ownership intact. */
    fun showAndroidUi(overlayVisible: Boolean = rawUiOverlayVisible) {
        rawUiOverlayVisible = overlayVisible
        val th = touchHelper as? com.onyx.android.sdk.pen.TouchHelper ?: return
        if (!rawDrawingActive) return
        try {
            th.setRawDrawingRenderEnabled(false)
            rawRenderingSuspended = true
            redrawBitmap()
            invalidate()
            (parent as? View)?.invalidate()
            Log.i("ScrawlLayers", "raw preview suspended overlay=$rawUiOverlayVisible strokes=${strokes.size} input=${th.isRawDrawingInputEnabled} render=${th.isRawDrawingRenderEnabled}")
        } catch (e: Exception) {
            Log.w("ScrawlLayers", "raw UI handoff failed", e)
        }
    }

    private fun beginRawPreview(): Boolean {
        rawStrokeSuppressed = rawUiOverlayVisible || !rawDrawingActive
        if (rawStrokeSuppressed) {
            Log.i("ScrawlLayers", "raw stroke ignored overlay=$rawUiOverlayVisible")
            return false
        }
        if (annotationMode != AnnotationMode.None && !isEraserMode) {
            // Keep the raw reader for geometry, but let Android draw the region
            // preview instead of rendering this selection gesture as black ink.
            if (!rawRenderingSuspended) showAndroidUi()
            Log.i("ScrawlLayers", "raw annotation preview=Canvas mode=$annotationMode")
            return true
        }
        if (rawRenderingSuspended) {
            try {
                (touchHelper as? com.onyx.android.sdk.pen.TouchHelper)?.setRawDrawingRenderEnabled(true)
                rawRenderingSuspended = false
                Log.i("ScrawlLayers", "raw preview resumed for stroke")
            } catch (e: Exception) {
                Log.w("ScrawlLayers", "raw preview resume failed", e)
            }
        }
        return true
    }

    private var rawDrawingConfirmed = false  // true once we receive a real callback
    private var rawStrokeStartTime = 0L
    private var rawEraseDirty = false
    private var rawEraseRemovedStrokes = 0
    private var rawEraseRemovedAnnotations = 0
    private var pendingBake = false  // deferred progressive bake — runs in onDraw after rendering

    private val markerFamily by lazy { StockBrushes.marker() }
    private val pressurePenFamily by lazy { StockBrushes.pressurePen() }

    var isEraserMode = false
        set(value) {
            if (field == value) return
            field = value
            syncTouchHelperForToolMode()
        }
    var hideAnnotationOverlays = false
    var currentPen = Pen.FOUNTAIN
    var currentPenSize = 5f
    /** 0 = flat (uniform width), 1 = max progressive pressure response */
    var pressureSensitivity = 0.5f
    var annotationMode = AnnotationMode.None
        set(value) {
            field = value
            // Suspend before the next pen DOWN, not after the first raw mark.
            // Returning to normal ink resumes at the next raw stroke begin.
            if (value != AnnotationMode.None) showAndroidUi()
        }
    // Stored annotation regions (drawn by user in annotation mode)
    data class AnnotationRegion(val bounds: RectF, val mode: AnnotationMode)
    private val annotations = mutableListOf<AnnotationRegion>()

    // Annotation overlay paints — ported from Notable's pageDrawing.kt
    private val wikiLinkFillPaint = Paint().apply {
        color = Color.argb(50, 0, 100, 255)
        style = Paint.Style.FILL
    }
    private val wikiLinkBracketPaint = Paint().apply {
        color = Color.argb(200, 0, 80, 220)
        style = Paint.Style.FILL
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val tagFillPaint = Paint().apply {
        color = Color.argb(50, 0, 180, 0)
        style = Paint.Style.FILL
    }
    private val tagHashPaint = Paint().apply {
        color = Color.argb(200, 0, 150, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val headerFillPaint = Paint().apply {
        color = Color.argb(40, 80, 80, 80)
        style = Paint.Style.FILL
    }
    private val headerLabelPaint = Paint().apply {
        color = Color.argb(200, 60, 60, 60)
        style = Paint.Style.FILL
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val annotationUnderlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    // Dashed stroke paint for annotation drawing preview (like Notable's Pen.DASHED)
    private val annotationPreviewPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    // Screenshot region paints — high contrast for e-ink
    private val screenshotFillPaint = Paint().apply {
        color = Color.argb(30, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val screenshotBorderPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 12f), 0f)
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backingBitmap?.recycle()
        if (w > 0 && h > 0) {
            // Bitmap dimensions come directly from the view size, which is
            // screen-dependent. Smaller screens (Nova Air 2: 7.8") naturally
            // produce smaller bitmaps than larger ones (Note Air 5C: 10.3"),
            // reducing memory pressure on low-RAM devices with no extra logic.
            // All displays need an alpha channel to preserve the host app beneath.
            backingBitmap = Bitmap.createBitmap(w, h, DeviceProfile.bitmapConfig)
            redrawBitmap()
        }

        if (isOnyxDevice() && w > 0 && h > 0) {
            initEpdFallback()
        }
    }

    /**
     * Initialize Onyx TouchHelper for raw EPD drawing.
     * Falls back to EpdController update modes if TouchHelper fails.
     */
    private fun initTouchHelper() {
        val limit = rawDrawingLimit ?: return
        try {
            val th = com.onyx.android.sdk.pen.TouchHelper.create(
                this, createRawInputCallback()
            )
            th.setStrokeStyle(com.onyx.android.sdk.pen.TouchHelper.STROKE_STYLE_FOUNTAIN)
            th.setStrokeWidth(currentPenSize)
            th.setStrokeColor(Color.BLACK)
            th.setLimitRect(limit, emptyList())
            th.openRawDrawing()
            th.setRawDrawingEnabled(true)
            th.setRawDrawingRenderEnabled(true)
            try {
                th.setSingleRegionMode()
            } catch (_: Exception) {}
            th.setPenUpRefreshEnabled(false)  // we handle refresh ourselves
            th.enableFingerTouch(true)        // let finger events through to onTouchEvent
            touchHelper = th
            rawDrawingActive = true
            syncTouchHelperForToolMode()
            Log.i(TAG, "TouchHelper initialized: raw EPD drawing active limit=$limit")
        } catch (e: Exception) {
            Log.w(TAG, "TouchHelper init failed, falling back to EpdController: ${e.message}")
            touchHelper = null
            rawDrawingActive = false
            initEpdFallback()
        }
    }

    /** Legacy EPD update mode setup — fallback when TouchHelper isn't available. */
    private fun initEpdFallback() {
        try {
            val epdClass = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
            val updateModeClass = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode")
            val hwMode = updateModeClass.getField("HAND_WRITING_REPAINT_MODE").get(null)
            val setMethod = epdClass.getMethod("setViewDefaultUpdateMode", View::class.java, updateModeClass)
            setMethod.invoke(null, this, hwMode)
            val enablePost = epdClass.getMethod("enablePost", View::class.java, Int::class.javaPrimitiveType)
            enablePost.invoke(null, this, 1)
        } catch (_: Exception) {
            try {
                val epdClass = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController")
                val updateModeClass = Class.forName("com.onyx.android.sdk.api.device.epd.UpdateMode")
                val regalMode = updateModeClass.getField("REGAL").get(null)
                val setMethod = epdClass.getMethod("setViewDefaultUpdateMode", View::class.java, updateModeClass)
                setMethod.invoke(null, this, regalMode)
            } catch (_: Exception) {}
        }
    }

    private fun createRawInputCallback(): com.onyx.android.sdk.pen.RawInputCallback {
        return object : com.onyx.android.sdk.pen.RawInputCallback() {
            override fun onBeginRawDrawing(smooth: Boolean, point: com.onyx.android.sdk.data.note.TouchPoint) {
                if (!beginRawPreview()) return
                exitEpdTurbo()
                rawDrawingConfirmed = true
                lastToolWasPen = true
                if (isEraserMode) {
                    rawEraseDirty = false
                    rawEraseRemovedStrokes = 0
                    rawEraseRemovedAnnotations = 0
                    eraseAtBatched(point.x, point.y)
                    Log.d(TAG, "rawDrawing ERASE BEGIN (${point.x}, ${point.y})")
                    return
                }
                rawStrokeStartTime = point.timestamp
                currentPoints.clear()
                allStrokePoints.clear()
                lastX = Float.NaN; lastY = Float.NaN; lastDt = -1L
                addRawPoint(point)
                Log.d(TAG, "rawDrawing BEGIN (${point.x}, ${point.y})")
            }

            override fun onRawDrawingTouchPointMoveReceived(point: com.onyx.android.sdk.data.note.TouchPoint) {
                if (rawStrokeSuppressed) return
                if (isEraserMode) {
                    eraseAtBatched(point.x, point.y)
                    return
                }
                addRawPoint(point)
            }

            override fun onRawDrawingTouchPointListReceived(list: com.onyx.android.sdk.pen.data.TouchPointList) {
                if (rawStrokeSuppressed) return
                if (!isEraserMode && list.size() > 0) {
                    // Onyx 1.5.1 delivers the complete stroke here before END,
                    // including points already delivered by BEGIN/MOVE. Appending
                    // it draws a spurious segment from the last point to the first.
                    Log.i("ScrawlLayers", "raw final batch replaces streamed=${allStrokePoints.size} batch=${list.size()}")
                    currentPoints.clear()
                    allStrokePoints.clear()
                    lastX = Float.NaN; lastY = Float.NaN; lastDt = -1L
                }
                for (i in 0 until list.size()) {
                    val point = list.get(i)
                    if (isEraserMode) eraseAtBatched(point.x, point.y)
                    else addRawPoint(point)
                }
            }

            override fun onEndRawDrawing(smooth: Boolean, point: com.onyx.android.sdk.data.note.TouchPoint) {
                if (rawStrokeSuppressed) return
                if (isEraserMode) {
                    eraseAtBatched(point.x, point.y)
                    finishRawErasing()
                    return
                }
                addRawPoint(point)
                finishRawStroke()
                Log.d(TAG, "rawDrawing END pts=${allStrokePoints.size}")
            }

            override fun onBeginRawErasing(smooth: Boolean, point: com.onyx.android.sdk.data.note.TouchPoint) {
                if (!beginRawPreview()) return
                exitEpdTurbo()
                rawEraseDirty = false
                rawEraseRemovedStrokes = 0
                rawEraseRemovedAnnotations = 0
                eraseAtBatched(point.x, point.y)
            }

            override fun onRawErasingTouchPointMoveReceived(point: com.onyx.android.sdk.data.note.TouchPoint) {
                if (rawStrokeSuppressed) return
                eraseAtBatched(point.x, point.y)
            }

            override fun onRawErasingTouchPointListReceived(list: com.onyx.android.sdk.pen.data.TouchPointList) {
                if (rawStrokeSuppressed) return
                for (i in 0 until list.size()) {
                    eraseAtBatched(list.get(i).x, list.get(i).y)
                }
            }

            override fun onEndRawErasing(smooth: Boolean, point: com.onyx.android.sdk.data.note.TouchPoint) {
                if (rawStrokeSuppressed) return
                eraseAtBatched(point.x, point.y)
                finishRawErasing()
            }
        }
    }

    /** Convert a raw TouchPoint into a StrokePoint and accumulate for HWR. */
    private fun addRawPoint(point: com.onyx.android.sdk.data.note.TouchPoint) {
        val x = point.x
        val y = point.y
        val pressure = point.pressure
        val dt = (point.timestamp - rawStrokeStartTime).toInt().coerceAtLeast(0)

        // Same jitter/duplicate filtering as addPoint
        if (x == lastX && y == lastY && dt.toLong() == lastDt) { perfPointsFiltered++; return }
        if (!lastX.isNaN()) {
            val dx = x - lastX; val dy = y - lastY
            if (dx * dx + dy * dy < MIN_MOVE_SQ && dt.toLong() == lastDt) { perfPointsFiltered++; return }
        }
        lastX = x; lastY = y; lastDt = dt.toLong()

        val sp = StrokePoint(
            x = x, y = y,
            pressure = pressure * 4096f,
            dt = dt.toUShort()
        )
        currentPoints.add(sp)
        allStrokePoints.add(sp)
        if (annotationMode != AnnotationMode.None) postInvalidateOnAnimation()
    }

    /** Finish a raw-drawn stroke: bake to backing bitmap with Jetpack Ink. */
    private fun finishRawStroke() {
        if (allStrokePoints.size < 2) {
            currentPoints.clear()
            allStrokePoints.clear()
            return
        }

        // Annotation mode: consume as region
        if (annotationMode != AnnotationMode.None) {
            val completedMode = annotationMode
            val bounds = pointsBoundingBox(allStrokePoints)
            if (bounds != null) {
                annotations.add(AnnotationRegion(bounds, annotationMode))
            }
            currentPoints.clear()
            allStrokePoints.clear()
            annotationMode = AnnotationMode.None
            onAnnotationModeReset?.invoke()
            persistStrokes()
            // The raw preview is just the user's selection gesture. Replace it
            // with the model's annotation as soon as the pen lifts, just as we
            // yield to Android when showing a menu.
            showAndroidUi()
            invalidate()
            Log.i("ScrawlLayers", "raw annotation committed mode=$completedMode bounds=$bounds annotations=${annotations.size}")
            return
        }

        var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
        for (pt in allStrokePoints) {
            if (pt.x < minX) minX = pt.x; if (pt.x > maxX) maxX = pt.x
            if (pt.y < minY) minY = pt.y; if (pt.y > maxY) maxY = pt.y
        }

        val stroke = Stroke(
            size = currentPenSize, pen = currentPen, color = Color.BLACK,
            top = minY, bottom = maxY, left = minX, right = maxX,
            points = allStrokePoints.toList(), createdAt = Date(rawStrokeStartTime)
        )
        strokes.add(stroke)
        redoStack.clear()
        onStrokeCountChanged?.invoke(strokes.size)

        // Bake stroke with Jetpack Ink onto backing bitmap.
        // On low-tier devices (e.g. Nova Air 2), defer to background thread —
        // the EPD layer already shows the stroke, so the bitmap bake is only
        // needed for undo/redraw consistency and can tolerate latency.
        if (DeviceProfile.deferBakeWhenRawDrawing) {
            val strokeCopy = stroke
            val bmpRef = backingBitmap
            if (bmpRef != null) {
                bakeHandler.post {
                    val bakeT0 = System.nanoTime()
                    synchronized(bmpRef) { drawStroke(Canvas(bmpRef), strokeCopy, pressureSensitivity) }
                    val bakeUs = (System.nanoTime() - bakeT0) / 1000
                    Log.d(TAG, "finishRawStroke[deferred]: pts=${strokeCopy.points.size} bake=${bakeUs}µs")
                }
            }
        } else {
            val bakeT0 = System.nanoTime()
            bakeStroke(stroke)
            val bakeUs = (System.nanoTime() - bakeT0) / 1000
            Log.d(TAG, "finishRawStroke: pts=${allStrokePoints.size} bake=${bakeUs}µs")
        }

        currentPoints.clear()
        allStrokePoints.clear()
        persistStrokes()
        // Force view redraw so the pressure-rendered bitmap replaces the
        // uniform-width raw EPD stroke. Without this, users whose Onyx
        // compositor doesn't auto-refresh see a ~300ms delay before the
        // final ink (with pressure lines) appears after pen lift.
        invalidate()
        refreshViewDu()
    }

    private var rawDrawingLimit: Rect? = null

    /**
     * Configure the stable writing box used by TouchHelper raw drawing.
     *
     * This is intentionally a single rectangle, not dynamic toolbar pause/resume.
     * Toggling raw drawing around toolbar taps clears or corrupts the EPD raw
     * layer on some firmware.
     */
    fun configureRawDrawingRegion(limit: Rect?, enabled: Boolean) {
        val cleanLimit = limit?.takeIf { it.width() > 16 && it.height() > 16 }
        val changed = rawDrawingLimit != cleanLimit
        rawDrawingLimit = cleanLimit

        if (!enabled || cleanLimit == null || !isOnyxDevice()) {
            deactivateRawDrawing()
            return
        }

        if (touchHelper == null) {
            initTouchHelper()
            return
        }

        if (changed) {
            // Region changes only happen after layout/toolbar drag, not normal
            // button taps. Re-open raw drawing so setLimitRect is applied before
            // the next stroke.
            deactivateRawDrawing()
            initTouchHelper()
        } else {
            activateRawDrawing()
        }
    }

    /** Update TouchHelper stroke style to match current pen settings. */
    fun syncTouchHelperPen() {
        val th = touchHelper as? com.onyx.android.sdk.pen.TouchHelper ?: return
        th.setStrokeWidth(currentPenSize)
        th.setStrokeColor(Color.BLACK)
        val style = when (currentPen) {
            Pen.FOUNTAIN -> com.onyx.android.sdk.pen.TouchHelper.STROKE_STYLE_FOUNTAIN
            Pen.BALLPEN -> com.onyx.android.sdk.pen.TouchHelper.STROKE_STYLE_MARKER
        }
        th.setStrokeStyle(style)
    }

    fun refreshAfterToolToggle() {
        redrawBitmap()
        invalidate()
        try {
            com.onyx.android.sdk.api.device.epd.EpdController.invalidate(
                this,
                com.onyx.android.sdk.api.device.epd.UpdateMode.DU
            )
        } catch (_: Exception) {}
    }

    private fun finishRawErasing() {
        if (rawEraseDirty) {
            persistStrokes()
            onStrokeCountChanged?.invoke(strokes.size)
        }
        // Publish the model at pen-up, including a no-hit gesture's preview
        // cleanup. Keep the reader alive; the next stroke resumes raw rendering.
        redrawBitmap()
        showAndroidUi()
        invalidate()
        Log.i("ScrawlLayers", "raw erase committed removedStrokes=$rawEraseRemovedStrokes removedAnnotations=$rawEraseRemovedAnnotations strokes=${strokes.size} dirty=$rawEraseDirty")
        rawEraseDirty = false
    }

    /** Switch between raw pen drawing and Canvas eraser mode. */
    private fun syncTouchHelperForToolMode() {
        val th = touchHelper as? com.onyx.android.sdk.pen.TouchHelper
        if (th == null) {
            if (!isEraserMode && rawDrawingLimit != null && isOnyxDevice()) initTouchHelper()
            return
        }
        try {
            if (isEraserMode) {
                // Match Notate/Boox Notes: keep raw drawing alive and switch the
                // driver into its eraser/dashed hardware channel. Pausing or
                // closing raw drawing blanks the visible raw layer on Note Air 5C.
                com.onyx.android.sdk.device.Device.currentDevice().setEraserRawDrawingEnabled(true)
                com.onyx.android.sdk.device.Device.currentDevice().setStrokeParameters(
                    com.onyx.android.sdk.pen.TouchHelper.STROKE_STYLE_DASH,
                    floatArrayOf(5.0f)
                )
                th.setStrokeStyle(com.onyx.android.sdk.pen.TouchHelper.STROKE_STYLE_DASH)
                th.setStrokeWidth(5f)
                th.setStrokeColor(Color.BLACK)
                th.setRawDrawingRenderEnabled(true)
                th.setRawDrawingEnabled(true)
                com.onyx.android.sdk.api.device.epd.EpdController.setScreenHandWritingPenState(
                    this,
                    com.onyx.android.sdk.pen.EpdPenManager.PEN_ERASING
                )
                rawDrawingActive = true
                Log.d(TAG, "toolMode=eraser rawDrawing=native")
            } else {
                com.onyx.android.sdk.device.Device.currentDevice().setEraserRawDrawingEnabled(false)
                syncTouchHelperPen()
                th.setRawDrawingRenderEnabled(true)
                th.setRawDrawingEnabled(true)
                com.onyx.android.sdk.api.device.epd.EpdController.setScreenHandWritingPenState(
                    this,
                    com.onyx.android.sdk.pen.EpdPenManager.PEN_DRAWING
                )
                rawDrawingActive = true
                Log.d(TAG, "toolMode=pen rawDrawing=$rawDrawingActive")
            }
        } catch (e: Exception) {
            Log.w(TAG, "syncTouchHelperForToolMode: ${e.message}")
        }
    }

    private fun refreshViewDu() {
        try {
            com.onyx.android.sdk.api.device.epd.EpdController.invalidate(
                this,
                com.onyx.android.sdk.api.device.epd.UpdateMode.DU
            )
        } catch (_: Exception) {}
    }


    // --- EPD turbo mode for faster screen refresh during active drawing ---
    private var epdTurboActive = false

    private fun enterEpdTurbo() {
        if (epdTurboActive || !isOnyxDevice()) return
        try {
            com.onyx.android.sdk.api.device.epd.EpdController.setEpdTurbo(1)
            epdTurboActive = true
            Log.d(TAG, "EPD turbo ON")
        } catch (e: Exception) {
            Log.w(TAG, "enterEpdTurbo failed: ${e.message}")
        }
    }

    private fun exitEpdTurbo() {
        if (!epdTurboActive) return
        try {
            com.onyx.android.sdk.api.device.epd.EpdController.setEpdTurbo(0)
            epdTurboActive = false
            Log.d(TAG, "EPD turbo OFF")
        } catch (e: Exception) {
            Log.w(TAG, "exitEpdTurbo failed: ${e.message}")
        }
    }

    /** Shut down raw drawing — call when IME hides. */
    fun deactivateRawDrawing() {
        if (!rawDrawingActive) return
        try {
            val th = touchHelper as? com.onyx.android.sdk.pen.TouchHelper
            th?.setRawDrawingEnabled(false)
            th?.closeRawDrawing()
        } catch (e: Exception) {
            Log.w(TAG, "deactivateRawDrawing: ${e.message}")
        }
        rawDrawingActive = false
        rawDrawingConfirmed = false
        rawRenderingSuspended = false
        rawStrokeSuppressed = true
        rawUiOverlayVisible = false
        Log.i("ScrawlLayers", "raw drawing deactivated strokes=${strokes.size}")
    }

    /** Re-enable raw drawing — call when IME shows. */
    fun activateRawDrawing() {
        if (rawDrawingActive) return
        val th = touchHelper as? com.onyx.android.sdk.pen.TouchHelper
        if (th != null) {
            try {
                th.openRawDrawing()
                th.setRawDrawingEnabled(true)
                rawDrawingActive = true
                rawRenderingSuspended = false
                Log.i("ScrawlLayers", "raw drawing activated input=${th.isRawDrawingInputEnabled} render=${th.isRawDrawingRenderEnabled} strokes=${strokes.size}")
            } catch (e: Exception) {
                Log.w(TAG, "activateRawDrawing: ${e.message}")
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frameStartNs = System.nanoTime()
        if (lastTouchNs != 0L) {
            perfTouchToDrawNs += frameStartNs - lastTouchNs
        }

        backingBitmap?.let { bmp ->
            // Synchronized when deferred bake thread may be writing to the bitmap
            if (DeviceProfile.deferBakeWhenRawDrawing) {
                synchronized(bmp) { canvas.drawBitmap(bmp, 0f, 0f, null) }
            } else {
                canvas.drawBitmap(bmp, 0f, 0f, null)
            }
        }

        // Rebuild InkStroke only once per frame if batch changed
        if (liveDirty) {
            liveDirty = false
            val brush = liveBrush
            liveBatch?.let { batch ->
                if (currentPoints.size >= 2 && brush != null) {
                    val t0 = System.nanoTime()
                    liveInkStroke = InkStroke(brush, batch)
                    perfBuildNs += System.nanoTime() - t0
                }
            }
        }

        if (rawDrawingActive && rawDrawingConfirmed && annotationMode == AnnotationMode.None) {
            // TouchHelper draws live strokes directly to EPD — skip Canvas preview
        } else if (annotationMode == AnnotationMode.None) {
            // Normal ink stroke preview (fallback path)
            liveInkStroke?.let {
                try {
                    val t0 = System.nanoTime()
                    inkRenderer.draw(canvas, it, identityMatrix)
                    perfRenderNs += System.nanoTime() - t0
                } catch (_: Exception) {}
            }
        } else if (currentPoints.size >= 2) {
            // Annotation mode: draw dashed rectangle preview from current points
            val bounds = pointsBoundingBox(currentPoints)
            if (bounds != null) {
                annotationPreviewPaint.color = when (annotationMode) {
                    AnnotationMode.WikiLink -> Color.rgb(0, 100, 255)
                    AnnotationMode.Tag -> Color.rgb(0, 180, 0)
                    AnnotationMode.Screenshot -> Color.DKGRAY
                    AnnotationMode.Header1, AnnotationMode.Header2,
                    AnnotationMode.Header3, AnnotationMode.Header4 -> Color.rgb(80, 80, 80)
                    else -> Color.DKGRAY
                }
                canvas.drawRect(bounds, annotationPreviewPaint)
            }
        }

        // Draw stored annotation overlays on top (suppressed during screenshot capture)
        if (!hideAnnotationOverlays) {
            for (ann in annotations) {
                drawAnnotationOverlay(canvas, ann.bounds, ann.mode)
            }
        }

        // Deferred progressive bake — runs after live stroke is rendered,
        // so the bake cost doesn't stall touch event processing
        if (pendingBake) {
            pendingBake = false
            bakeCurrentSegment()
        }

        // Periodic perf summary
        perfFrameCount++
        if (perfFrameCount >= PERF_LOG_INTERVAL) {
            val n = perfFrameCount
            Log.d(TAG, "frames=$n " +
                "build=${perfBuildNs / n / 1000}µs/f " +
                "render=${perfRenderNs / n / 1000}µs/f " +
                "touch→draw=${perfTouchToDrawNs / n / 1000}µs/f " +
                "bakes=$perfBakeCount(${perfBakeNs / 1000}µs) " +
                "filtered=$perfPointsFiltered " +
                "livePts=${currentPoints.size} allPts=${allStrokePoints.size}")
            perfFrameCount = 0; perfBuildNs = 0; perfRenderNs = 0
            perfTouchToDrawNs = 0; perfBakeCount = 0; perfBakeNs = 0
            perfPointsFiltered = 0
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Barrel button detection handled by root FrameLayout in ScrawlIMEService
        return super.onGenericMotionEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastToolWasPen = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        }

        return when (toolType) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> handleStylusEvent(event, toolType)
            MotionEvent.TOOL_TYPE_FINGER -> handleFingerEvent(event)
            else -> false
        }
    }

    private var lastButtonState = 0

    private fun handleStylusEvent(event: MotionEvent, toolType: Int): Boolean {
        // Barrel button detection handled by root FrameLayout in ScrawlIMEService
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            lastButtonState = 0
        }

        // When TouchHelper is confirmed working, it handles stylus input directly via EPD.
        // The writing box excludes toolbar rails, so pen taps there can reach toolbar views.
        if (rawDrawingActive && rawDrawingConfirmed) return false

        val useEraser = isEraserMode || toolType == MotionEvent.TOOL_TYPE_ERASER

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!rawDrawingActive) enterEpdTurbo()
                if (useEraser) eraseAt(event.x, event.y)
                else startStroke(event)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) {
                    if (useEraser) eraseAt(event.getHistoricalX(i), event.getHistoricalY(i))
                    else addPoint(
                        event.getHistoricalX(i),
                        event.getHistoricalY(i),
                        event.getHistoricalPressure(i),
                        event.getHistoricalEventTime(i)
                    )
                }
                if (useEraser) eraseAt(event.x, event.y)
                else {
                    addPoint(event.x, event.y, event.pressure, event.eventTime)
                    lastTouchNs = System.nanoTime()
                    postInvalidateOnAnimation()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (useEraser) eraseAt(event.x, event.y)
                else finishStroke(event)
                if (!rawDrawingActive) exitEpdTurbo()
            }
        }
        return true
    }

    private var fingerDownMs = 0L
    private var fingerDownX2 = 0f
    private var fingerDownY2 = 0f

    private fun handleFingerEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerDownMs = event.eventTime
                fingerDownX2 = event.x
                fingerDownY2 = event.y
            }
            MotionEvent.ACTION_UP -> {
                val holdDuration = event.eventTime - fingerDownMs
                if (holdDuration > TAP_MAX_DURATION_MS) return true  // not a tap, ignore

                val now = event.eventTime
                val sinceLast = now - lastFingerTapMs
                val dx = event.x - lastFingerTapX
                val dy = event.y - lastFingerTapY
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                if (sinceLast < DOUBLE_TAP_TIMEOUT_MS && dist < DOUBLE_TAP_SLOP_PX) {
                    // Double tap detected
                    Log.d(TAG, "finger DOUBLE TAP → switching to select mode")
                    lastFingerTapMs = 0L  // reset so a third tap doesn't re-trigger
                    onFingerDoubleTap?.invoke()
                } else {
                    // Record as first tap
                    lastFingerTapMs = now
                    lastFingerTapX = event.x
                    lastFingerTapY = event.y
                }
            }
        }
        return true  // always consume finger events
    }

    @Suppress("RedundantOverride")
    override fun performClick(): Boolean = super.performClick()

    private fun startStroke(event: MotionEvent) {
        strokeStartTime = event.eventTime
        currentPoints.clear()
        allStrokePoints.clear()
        liveBatch = MutableStrokeInputBatch()
        liveInkStroke = null
        liveDirty = false
        lastX = Float.NaN; lastY = Float.NaN; lastDt = -1L
        val family = if (currentPen == Pen.FOUNTAIN) pressurePenFamily else markerFamily
        liveBrush = Brush.createWithColorIntArgb(
            family = family,
            colorIntArgb = Color.BLACK,
            size = currentPenSize,
            epsilon = 0.1f
        )
        addPoint(event.x, event.y, event.pressure, event.eventTime)
    }

    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastDt = -1L

    private fun addPoint(x: Float, y: Float, pressure: Float, eventTime: Long) {
        // Coerce elapsed time to never decrease — Boox stylus can deliver out-of-order timestamps
        val dt = (eventTime - strokeStartTime).toInt().coerceAtLeast(if (lastDt >= 0) lastDt.toInt() else 0)

        // Skip duplicate position+time (Ink API crashes) and sub-pixel jitter
        if (x == lastX && y == lastY && dt.toLong() == lastDt) { perfPointsFiltered++; return }
        if (!lastX.isNaN()) {
            val dx = x - lastX; val dy = y - lastY
            if (dx * dx + dy * dy < MIN_MOVE_SQ && dt.toLong() == lastDt) { perfPointsFiltered++; return }
        }
        lastX = x; lastY = y; lastDt = dt.toLong()

        val point = StrokePoint(
            x = x,
            y = y,
            pressure = pressure * 4096f,
            dt = dt.toUShort()
        )
        currentPoints.add(point)
        allStrokePoints.add(point)

        // Add to the Ink API batch — InkStroke rebuilt in onDraw only once per frame
        liveBatch?.add(
            type = InputToolType.STYLUS,
            x = x,
            y = y,
            elapsedTimeMillis = dt.toLong(),
            pressure = applyPressureSensitivity(pressure.coerceIn(0f, 1f), pressureSensitivity),
            tiltRadians = -1f,
            orientationRadians = -1f
        )
        liveDirty = true

        // Progressive bake: mark needed, defer to onDraw to keep touch events fast
        if (currentPoints.size >= bakeThreshold && annotationMode == AnnotationMode.None) {
            pendingBake = true
        }
    }

    private fun finishStroke(event: MotionEvent) {
        addPoint(event.x, event.y, event.pressure, event.eventTime)
        performClick()

        liveInkStroke = null
        liveBatch = null
        liveBrush = null
        liveDirty = false

        if (allStrokePoints.size < 2) {
            currentPoints.clear()
            allStrokePoints.clear()
            invalidate()
            return
        }

        // Annotation mode: consume stroke as a region, not handwriting
        if (annotationMode != AnnotationMode.None) {
            val bounds = pointsBoundingBox(allStrokePoints)
            Log.i("Scrawl", "finishStroke annotation: mode=$annotationMode, points=${allStrokePoints.size}, bounds=$bounds")
            if (bounds != null) {
                annotations.add(AnnotationRegion(bounds, annotationMode))
                Log.i("Scrawl", "Added annotation region: $bounds, total=${annotations.size}")
            }
            currentPoints.clear()
            allStrokePoints.clear()
            // One-shot: reset annotation mode after drawing the box
            annotationMode = AnnotationMode.None
            onAnnotationModeReset?.invoke()
            onStrokeCountChanged?.invoke(strokes.size)
            persistStrokes()
            invalidate()
            return
        }

        var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
        for (pt in allStrokePoints) {
            if (pt.x < minX) minX = pt.x
            if (pt.x > maxX) maxX = pt.x
            if (pt.y < minY) minY = pt.y
            if (pt.y > maxY) maxY = pt.y
        }

        strokes.add(
            Stroke(
                size = currentPenSize,
                pen = currentPen,
                color = Color.BLACK,
                top = minY, bottom = maxY, left = minX, right = maxX,
                points = allStrokePoints.toList(),
                createdAt = Date(strokeStartTime)
            )
        )
        redoStack.clear()
        onStrokeCountChanged?.invoke(strokes.size)

        // Incremental bake: only render the remaining tail (prefix already baked progressively)
        val bakeT0 = System.nanoTime()
        if (currentPoints.size >= 2) {
            var tMinX = Float.MAX_VALUE; var tMaxX = Float.MIN_VALUE
            var tMinY = Float.MAX_VALUE; var tMaxY = Float.MIN_VALUE
            for (pt in currentPoints) {
                if (pt.x < tMinX) tMinX = pt.x; if (pt.x > tMaxX) tMaxX = pt.x
                if (pt.y < tMinY) tMinY = pt.y; if (pt.y > tMaxY) tMaxY = pt.y
            }
            bakeStroke(Stroke(
                size = currentPenSize, pen = currentPen, color = Color.BLACK,
                top = tMinY, bottom = tMaxY, left = tMinX, right = tMaxX,
                points = currentPoints.toList(), createdAt = Date(strokeStartTime)
            ))
        }
        val bakeUs = (System.nanoTime() - bakeT0) / 1000
        Log.d(TAG, "finishStroke: totalPts=${allStrokePoints.size} tailPts=${currentPoints.size} bake=${bakeUs}µs")

        lastTouchNs = 0L
        currentPoints.clear()
        allStrokePoints.clear()
        persistStrokes()
        invalidate()
    }

    private fun eraseAt(x: Float, y: Float) {
        val radius = 30f
        val beforeStrokes = strokes.size
        val beforeAnnotations = annotations.size
        val removedStrokes = strokes.removeAll { s -> strokeHitByEraser(s, x, y, radius) }
        val removedAnnotations = annotations.removeAll { a ->
            x in (a.bounds.left - radius)..(a.bounds.right + radius) &&
                    y in (a.bounds.top - radius)..(a.bounds.bottom + radius)
        }
        if (removedStrokes || removedAnnotations) {
            persistStrokes()
            redrawBitmap()
            invalidate()
            Log.d(TAG, "canvas ERASE removedStrokes=${beforeStrokes - strokes.size} removedAnnotations=${beforeAnnotations - annotations.size} strokes=${strokes.size}")
        }
    }

    /** Erase without redraw — caller is responsible for redraw after batch. */
    private fun eraseAtBatched(x: Float, y: Float) {
        val radius = 30f
        val beforeStrokes = strokes.size
        val beforeAnnotations = annotations.size
        val removedStrokes = strokes.removeAll { s -> strokeHitByEraser(s, x, y, radius) }
        val removedAnnotations = annotations.removeAll { a ->
            x in (a.bounds.left - radius)..(a.bounds.right + radius) &&
                    y in (a.bounds.top - radius)..(a.bounds.bottom + radius)
        }
        if (removedStrokes || removedAnnotations) {
            rawEraseRemovedStrokes += beforeStrokes - strokes.size
            rawEraseRemovedAnnotations += beforeAnnotations - annotations.size
            rawEraseDirty = true
        }
    }

    private fun strokeHitByEraser(stroke: Stroke, x: Float, y: Float, radius: Float): Boolean {
        val hitRadius = radius + stroke.size * 0.5f
        if (x !in (stroke.left - hitRadius)..(stroke.right + hitRadius) ||
            y !in (stroke.top - hitRadius)..(stroke.bottom + hitRadius)) {
            return false
        }

        val points = stroke.points
        if (points.isEmpty()) return false
        val hitSq = hitRadius * hitRadius
        if (points.size == 1) {
            val dx = points[0].x - x
            val dy = points[0].y - y
            return dx * dx + dy * dy <= hitSq
        }

        for (i in 1 until points.size) {
            if (distanceToSegmentSq(x, y, points[i - 1], points[i]) <= hitSq) {
                return true
            }
        }
        return false
    }

    private fun distanceToSegmentSq(x: Float, y: Float, a: StrokePoint, b: StrokePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 0.0001f) {
            val px = x - a.x
            val py = y - a.y
            return px * px + py * py
        }
        val t = (((x - a.x) * dx + (y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        val px = x - projX
        val py = y - projY
        return px * px + py * py
    }

    // Callback for service to sync button state when annotation mode resets (one-shot)
    var onAnnotationModeReset: (() -> Unit)? = null

    private fun pointsBoundingBox(points: List<StrokePoint>): RectF? {
        if (points.size < 2) return null
        var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
        for (pt in points) {
            if (pt.x < minX) minX = pt.x
            if (pt.x > maxX) maxX = pt.x
            if (pt.y < minY) minY = pt.y
            if (pt.y > maxY) maxY = pt.y
        }
        return if (minX < maxX && minY < maxY) RectF(minX, minY, maxX, maxY) else null
    }

    private fun drawAnnotationOverlay(canvas: Canvas, rect: RectF, mode: AnnotationMode) {
        val boxHeight = rect.height()
        val padding = boxHeight * 0.15f

        when (mode) {
            AnnotationMode.WikiLink -> {
                val bracketSize = boxHeight * 0.55f
                wikiLinkBracketPaint.textSize = bracketSize
                val bracketWidth = wikiLinkBracketPaint.measureText("[[")
                val bracketGap = padding * 0.5f

                val expanded = RectF(
                    rect.left - bracketWidth - bracketGap,
                    rect.top - padding,
                    rect.right + bracketWidth + bracketGap,
                    rect.bottom + padding
                )
                val cr = boxHeight * 0.15f
                canvas.drawRoundRect(expanded, cr, cr, wikiLinkFillPaint)

                val textY = rect.centerY() + bracketSize * 0.35f
                canvas.drawText("[[", expanded.left + bracketGap * 0.5f, textY, wikiLinkBracketPaint)
                canvas.drawText("]]", rect.right + bracketGap * 0.5f, textY, wikiLinkBracketPaint)

                annotationUnderlinePaint.color = Color.argb(120, 0, 80, 220)
                canvas.drawLine(rect.left, rect.bottom + padding * 0.3f, rect.right, rect.bottom + padding * 0.3f, annotationUnderlinePaint)
            }
            AnnotationMode.Tag -> {
                val hashSize = boxHeight * 0.65f
                tagHashPaint.textSize = hashSize
                val hashWidth = tagHashPaint.measureText("#")
                val hashGap = padding * 0.6f

                val expanded = RectF(
                    rect.left - hashWidth - hashGap,
                    rect.top - padding,
                    rect.right + padding,
                    rect.bottom + padding
                )
                val cr = boxHeight * 0.15f
                canvas.drawRoundRect(expanded, cr, cr, tagFillPaint)

                val textY = rect.centerY() + hashSize * 0.35f
                canvas.drawText("#", expanded.left + hashGap * 0.3f, textY, tagHashPaint)

                annotationUnderlinePaint.color = Color.argb(120, 0, 150, 0)
                canvas.drawLine(rect.left, rect.bottom + padding * 0.3f, rect.right, rect.bottom + padding * 0.3f, annotationUnderlinePaint)
            }
            AnnotationMode.Header1, AnnotationMode.Header2,
            AnnotationMode.Header3, AnnotationMode.Header4 -> {
                val label = when (mode) {
                    AnnotationMode.Header1 -> "H1"
                    AnnotationMode.Header2 -> "H2"
                    AnnotationMode.Header3 -> "H3"
                    else -> "H4"
                }
                val labelSize = boxHeight * 0.6f
                headerLabelPaint.textSize = labelSize
                val labelWidth = headerLabelPaint.measureText(label)
                val labelGap = padding * 0.6f

                val expanded = RectF(
                    rect.left - labelWidth - labelGap,
                    rect.top - padding,
                    rect.right + padding,
                    rect.bottom + padding
                )
                val cr = boxHeight * 0.15f
                canvas.drawRoundRect(expanded, cr, cr, headerFillPaint)

                val textY = rect.centerY() + labelSize * 0.35f
                canvas.drawText(label, expanded.left + labelGap * 0.3f, textY, headerLabelPaint)

                annotationUnderlinePaint.color = Color.argb(120, 60, 60, 60)
                canvas.drawLine(rect.left, rect.bottom + padding * 0.3f, rect.right, rect.bottom + padding * 0.3f, annotationUnderlinePaint)
            }
            AnnotationMode.Screenshot -> {
                canvas.drawRect(rect, screenshotFillPaint)
                canvas.drawRect(rect, screenshotBorderPaint)
            }
            AnnotationMode.None -> {}
        }
    }

    fun getAnnotations(): List<AnnotationRegion> = annotations.toList()

    fun getScreenshotRegion(): AnnotationRegion? =
        annotations.firstOrNull { it.mode == AnnotationMode.Screenshot }

    fun removeScreenshotRegion() {
        annotations.removeAll { it.mode == AnnotationMode.Screenshot }
        persistStrokes()
        invalidate()
    }

    private fun redrawBitmap() {
        val bmp = backingBitmap ?: return
        // Drain any pending deferred bakes before full redraw to avoid races
        if (DeviceProfile.deferBakeWhenRawDrawing) {
            bakeHandler.removeCallbacksAndMessages(null)
        }
        val block = {
            val canvas = Canvas(bmp)
            // Keep the host app visible beneath the handwriting layer on every display.
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            for (stroke in strokes) {
                drawStroke(canvas, stroke, pressureSensitivity)
            }
        }
        if (DeviceProfile.deferBakeWhenRawDrawing) synchronized(bmp) { block() } else block()
    }

    /** Render a single stroke onto the backing bitmap (incremental bake). */
    private fun bakeStroke(stroke: Stroke) {
        val bmp = backingBitmap ?: return
        if (DeviceProfile.deferBakeWhenRawDrawing) {
            synchronized(bmp) { drawStroke(Canvas(bmp), stroke, pressureSensitivity) }
        } else {
            drawStroke(Canvas(bmp), stroke, pressureSensitivity)
        }
    }

    /**
     * Progressive bake: when the live batch exceeds bakeThreshold points,
     * bake the current segment to the bitmap and keep only a small overlap
     * tail for continued live rendering. Caps per-frame InkStroke cost to O(bakeThreshold).
     */
    private fun bakeCurrentSegment() {
        val bmp = backingBitmap ?: return
        if (currentPoints.size < bakeThreshold) return
        val t0 = System.nanoTime()

        // Bake full current segment (overlap included for smooth join)
        val segPoints = currentPoints.toList()
        var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
        for (pt in segPoints) {
            if (pt.x < minX) minX = pt.x; if (pt.x > maxX) maxX = pt.x
            if (pt.y < minY) minY = pt.y; if (pt.y > maxY) maxY = pt.y
        }
        drawStroke(Canvas(bmp), Stroke(
            size = currentPenSize, pen = currentPen, color = Color.BLACK,
            top = minY, bottom = maxY, left = minX, right = maxX,
            points = segPoints, createdAt = Date(strokeStartTime)
        ), pressureSensitivity)

        // Keep only the overlap tail for live rendering
        val tail = currentPoints.subList(currentPoints.size - BAKE_OVERLAP, currentPoints.size).toList()
        currentPoints.clear()
        currentPoints.addAll(tail)

        // Rebuild live batch from tail points
        liveBatch = MutableStrokeInputBatch()
        for (pt in tail) {
            liveBatch?.add(
                type = InputToolType.STYLUS,
                x = pt.x, y = pt.y,
                elapsedTimeMillis = pt.dt?.toLong() ?: 0L,
                pressure = applyPressureSensitivity((pt.pressure / 4096f).coerceIn(0f, 1f), pressureSensitivity),
                tiltRadians = -1f, orientationRadians = -1f
            )
        }
        liveDirty = true
        perfBakeCount++
        perfBakeNs += System.nanoTime() - t0
    }

    // Background bake thread — used on low-tier devices (DeviceProfile.deferBakeWhenRawDrawing)
    // to avoid blocking pen-up when TouchHelper raw drawing is active. The EPD layer already
    // shows the stroke; the bitmap bake is only needed for undo/redraw consistency.
    private val bakeThread = HandlerThread("StrokeBake").apply { start() }
    private val bakeHandler = Handler(bakeThread.looper)

    private val persistThread = HandlerThread("StrokePersist").apply { start() }
    private val persistHandler = Handler(persistThread.looper)
    private val persistDebounceMs = 300L
    private val persistRunnable = Runnable {
        val ctx = persistenceContext ?: return@Runnable
        val strokesCopy: List<Stroke>
        val annsCopy: List<IMECanvasView.AnnotationRegion>
        synchronized(this) {
            strokesCopy = ArrayList(strokes)
            annsCopy = ArrayList(annotations)
        }
        StrokePersistence.save(ctx, strokesCopy, annsCopy)
    }

    private fun persistStrokes() {
        if (persistenceContext == null) return
        persistHandler.removeCallbacks(persistRunnable)
        persistHandler.postDelayed(persistRunnable, persistDebounceMs)
    }

    fun restorePersistedStrokes() {
        val ctx = persistenceContext ?: return
        val data = StrokePersistence.load(ctx) ?: return
        strokes.clear()
        strokes.addAll(data.strokes)
        annotations.clear()
        annotations.addAll(data.annotations)
        onStrokeCountChanged?.invoke(strokes.size)
        redrawBitmap()
        invalidate()
    }

    fun getStrokes(): List<Stroke> = strokes.toList()

    fun canUndo(): Boolean = strokes.isNotEmpty() || annotations.isNotEmpty()

    fun undoLastStroke() {
        // Remove the most recent item: annotation if it's newer, otherwise stroke
        // For simplicity, remove whichever was added last (annotations are drawn after strokes)
        if (annotations.isNotEmpty()) {
            redoStack.add(annotations.removeAt(annotations.lastIndex))
        } else if (strokes.isNotEmpty()) {
            redoStack.add(strokes.removeAt(strokes.lastIndex))
        } else {
            return
        }
        onStrokeCountChanged?.invoke(strokes.size)
        persistStrokes()
        redrawBitmap()
        invalidate()
        // Refresh EPD layer if raw drawing is active
        if (rawDrawingActive && rawDrawingConfirmed) {
            try {
                (touchHelper as? com.onyx.android.sdk.pen.TouchHelper)?.restartRawDrawing()
            } catch (_: Exception) {}
        }
    }

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun redoStroke() {
        if (redoStack.isEmpty()) return
        val item = redoStack.removeAt(redoStack.lastIndex)
        when (item) {
            is Stroke -> strokes.add(item)
            is AnnotationRegion -> annotations.add(item)
        }
        onStrokeCountChanged?.invoke(strokes.size)
        persistStrokes()
        redrawBitmap()
        invalidate()
        if (rawDrawingActive && rawDrawingConfirmed) {
            try {
                (touchHelper as? com.onyx.android.sdk.pen.TouchHelper)?.restartRawDrawing()
            } catch (_: Exception) {}
        }
    }

    fun clearAll() {
        strokes.clear()
        annotations.clear()
        redoStack.clear()
        currentPoints.clear()
        allStrokePoints.clear()
        liveInkStroke = null
        liveBatch = null
        liveBrush = null
        liveDirty = false
        stashedStrokes = null
        stashedAnnotations = null
        pendingBake = false
        lastX = Float.NaN; lastY = Float.NaN; lastDt = -1L
        lastTouchNs = 0L
        rawEraseDirty = false
        rawEraseRemovedStrokes = 0
        rawEraseRemovedAnnotations = 0
        // Discard trailing callbacks from the cleared gesture. BEGIN resets this.
        rawStrokeSuppressed = true
        onStrokeCountChanged?.invoke(0)
        persistHandler.removeCallbacks(persistRunnable)
        val ctx = persistenceContext
        if (ctx != null) StrokePersistence.clear(ctx)
        redrawBitmap()
        invalidate()
        showAndroidUi()
        Log.i("ScrawlLayers", "canvas cleared eraser=$isEraserMode annotation=$annotationMode rawActive=$rawDrawingActive")
    }

    // Stash/restore strokes for finger-touch select mode transition
    private var stashedStrokes: List<Stroke>? = null
    private var stashedAnnotations: List<AnnotationRegion>? = null

    fun hasStash(): Boolean = stashedStrokes != null

    fun stashStrokes() {
        stashedStrokes = strokes.toList()
        stashedAnnotations = annotations.toList()
    }

    fun restoreStrokes() {
        val ss = stashedStrokes ?: return
        val sa = stashedAnnotations ?: emptyList()
        strokes.clear()
        strokes.addAll(ss)
        annotations.clear()
        annotations.addAll(sa)
        stashedStrokes = null
        stashedAnnotations = null
        onStrokeCountChanged?.invoke(strokes.size)
        redrawBitmap()
        invalidate()
    }

    fun dropStash() {
        stashedStrokes = null
        stashedAnnotations = null
    }

    private fun isOnyxDevice(): Boolean {
        return "ONYX".equals(Build.MANUFACTURER, ignoreCase = true)
    }
}
