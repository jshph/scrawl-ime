package com.scrawl.app

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AnnotationMode { None, WikiLink, Tag, Screenshot, Header1, Header2, Header3, Header4 }

class ScrawlIMEService : InputMethodService() {
    private enum class ToolbarEdge { TOP, BOTTOM, LEFT, RIGHT }

    private var canvasView: IMECanvasView? = null
    private var topBar: View? = null
    private var bottomBar: View? = null
    private var onOffBtn: ImageView? = null
    private var eraserBtn: ImageView? = null
    private var clearBtn: ImageView? = null
    private var wikiLinkBtn: TextView? = null
    private var tagBtn: TextView? = null
    private var headerBtn: TextView? = null
    private var penBtn: TextView? = null
    private var annotationMode = AnnotationMode.None
    private var collapsedHandle: View? = null
    private var sidebarCollapsed = false
    private var hamburgerFlyout: LinearLayout? = null
    private var hamburgerBackdrop: View? = null
    private var hamburgerBtn: ImageView? = null
    private var topBarDragHandle: View? = null
    private var bottomBarDragHandle: View? = null
    private var topBarEdge = ToolbarEdge.LEFT
    private var bottomBarEdge = ToolbarEdge.LEFT
    private var recordBtn: ImageView? = null
    private var audioRecorder: AudioRecorder? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastCommittedText: String? = null
    private var isCommitting = false
    private var pulsateAnimator: ObjectAnimator? = null
    private var commitJob: kotlinx.coroutines.Job? = null
    private var screenshotBtn: ImageView? = null
    private var radialMenu: RadialMenuView? = null
    private var rootButtonState = 0
    private val toolbarInset by lazy { dpToPx(4) }
    private val bottomToolbarDefaultOffset by lazy { dpToPx(80) }

    private enum class CanvasMode { WRITE, SELECT }
    private var canvasMode = CanvasMode.WRITE

    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateRecordButton()
            intent?.getStringExtra(RecordingService.EXTRA_ERROR_MESSAGE)?.let { message ->
                android.widget.Toast.makeText(
                    this@ScrawlIMEService,
                    message,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioRecorder = AudioRecorder(applicationContext)
        ContextCompat.registerReceiver(
            this,
            recordingStateReceiver,
            IntentFilter(RecordingService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        DeviceProfile.init(this)
        // Probe HWR engines: try Onyx KHwrService first, fall back to ML Kit.
        // On devices without ksync (e.g. Nova Air 2 on old firmware), this
        // triggers the ML Kit model download on first launch.
        serviceScope.launch { HWREngineProvider.init(this@ScrawlIMEService) }
    }

    override fun onCreateInputView(): View {
        val screenHeight = resources.displayMetrics.heightPixels

        val root = object : FrameLayout(this) {
            private var loggedButtons = 0

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked != MotionEvent.ACTION_MOVE) {
                    Log.i("ScrawlLayers", "root touch=${MotionEvent.actionToString(event.action)} tool=${event.getToolType(0)} x=${event.x} y=${event.y} buttons=${event.buttonState} mode=$canvasMode radial=${radialMenu?.isShowing}")
                }
                return super.dispatchTouchEvent(event)
            }

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                val logEvent = event.buttonState != loggedButtons ||
                    event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                    event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
                loggedButtons = event.buttonState
                if (logEvent) {
                    Log.i("ScrawlLayers", "root generic=${MotionEvent.actionToString(event.action)} tool=${event.getToolType(0)} x=${event.x} y=${event.y} buttons=${event.buttonState}")
                }
                val handled = super.dispatchGenericMotionEvent(event)
                if (logEvent) Log.i("ScrawlLayers", "root generic handled=$handled trackedButtons=$rootButtonState radial=${radialMenu?.isShowing}")
                return handled
            }
        }.apply {
            setOnGenericMotionListener(barrelButtonListener)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                screenHeight
            )
        }

        canvasView = IMECanvasView(this).also {
            it.persistenceContext = this
            it.onAnnotationModeReset = { clearAnnotation() }
            it.onFingerDoubleTap = { enterSelect() }
            it.onStrokeCountChanged = { count -> updateModeButton(count) }
            // Barrel button detection handled on root FrameLayout (works in both modes)
            it.currentPenSize = ScrawlSettings.getPenWidth(this)
            it.pressureSensitivity = ScrawlSettings.getPressureSensitivity(this)
        }
        root.addView(
            canvasView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Top toolbar
        val top = createTopBar()
        topBar = top
        root.addView(
            top,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                marginStart = toolbarInset
                topMargin = toolbarInset
            }
        )

        // Bottom toolbar
        val bottom = createBottomBar()
        bottomBar = bottom
        root.addView(
            bottom,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                marginStart = toolbarInset
                topMargin = toolbarInset
            }
        )

        // Backdrop to dismiss hamburger flyout on outside tap
        val backdrop = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener { dismissHamburger() }
        }
        hamburgerBackdrop = backdrop
        root.addView(backdrop, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Hamburger flyout — overlay anchored from the bottom bar
        val flyout = createHamburgerFlyout()
        hamburgerFlyout = flyout
        flyout.visibility = View.GONE
        root.addView(
            flyout,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                marginStart = toolbarInset
            }
        )

        val handle = createCollapsedHandle()
        collapsedHandle = handle
        handle.visibility = View.GONE
        root.addView(handle)

        radialMenu = RadialMenuView(this).also {
            it.visibility = View.GONE
            it.onDismiss = { canvasView?.showAndroidUi(overlayVisible = false) }
        }
        root.addView(radialMenu, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Restore saved toolbar positions after layout
        root.post {
            val topLayout = top as LinearLayout
            val bottomLayout = bottom as LinearLayout
            val topOffset = ScrawlSettings.getTopBarOffset(this)
            val bottomOffset = ScrawlSettings.getBottomBarOffset(this)

            positionToolbarOnEdge(topLayout, topBarEdge, topOffset)
            positionToolbarOnEdge(bottomLayout, bottomBarEdge, bottomOffset)
            updateRawDrawingRegion()
        }

        // Show tooltip overlay on first open
        if (!ScrawlSettings.hasSeenTooltips(this)) {
            val overlay = TooltipOverlayView(this) {
                ScrawlSettings.setTooltipsShown(this)
                updateRawDrawingRegion()
            }
            root.addView(overlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            root.post {
                val anchors = mutableListOf<TooltipOverlayView.TooltipAnchor>()
                topBarDragHandle?.let { anchors.add(TooltipOverlayView.TooltipAnchor(it, "Drag this grip to move the top tools")) }
                clearBtn?.let { anchors.add(TooltipOverlayView.TooltipAnchor(it, "Clear all strokes")) }
                onOffBtn?.let { anchors.add(TooltipOverlayView.TooltipAnchor(it, "Commit handwriting / switch mode")) }
                bottomBarDragHandle?.let { anchors.add(TooltipOverlayView.TooltipAnchor(it, "Drag this grip to move the bottom tools")) }
                eraserBtn?.let { anchors.add(TooltipOverlayView.TooltipAnchor(it, "Toggle eraser")) }
                wikiLinkBtn?.let { anchors.add(TooltipOverlayView.TooltipAnchor(it, "Mark region as [[wikilink]]")) }
                tagBtn?.let { anchors.add(TooltipOverlayView.TooltipAnchor(it, "Mark region as #tag")) }
                headerBtn?.let { anchors.add(TooltipOverlayView.TooltipAnchor(it, "Mark region as header (tap to cycle H1–H4)")) }
                hamburgerBtn?.let { anchors.add(TooltipOverlayView.TooltipAnchor(it, "More: record, settings")) }
                overlay.setAnchors(anchors)
            }
        }

        return root
    }

    private val barrelButtonListener = View.OnGenericMotionListener { _, event ->
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            val buttons = event.buttonState
            val pressed = buttons and rootButtonState.inv()
            val released = rootButtonState and buttons.inv()
            rootButtonState = buttons
            if (pressed != 0 || released != 0) {
                Log.i("ScrawlLayers", "barrel pressed=$pressed released=$released mode=$canvasMode")
            }
            if (pressed and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) {
                executePenButtonAction(ScrawlSettings.getPenButton1(this), event.x, event.y)
                return@OnGenericMotionListener true
            }
            if (pressed and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) {
                executePenButtonAction(ScrawlSettings.getPenButton2(this), event.x, event.y)
                return@OnGenericMotionListener true
            }
            if (released and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
                radialMenu?.dismiss("barrel-release")
                return@OnGenericMotionListener true
            }
        }
        false
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = window?.window?.decorView ?: return
        outInsets.contentTopInsets = view.height
        outInsets.visibleTopInsets = view.height

        if (canvasMode == CanvasMode.WRITE) {
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
        } else {
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            if (sidebarCollapsed) {
                val handle = collapsedHandle
                if (handle != null && handle.width > 0) {
                    val loc = IntArray(2)
                    handle.getLocationInWindow(loc)
                    outInsets.touchableRegion.set(
                        loc[0], loc[1],
                        loc[0] + handle.width, loc[1] + handle.height
                    )
                } else {
                    outInsets.touchableRegion.set(0, 0, dpToPx(56), view.height)
                }
            } else {
                outInsets.touchableRegion.setEmpty()
                val overlays = listOfNotNull(topBar, bottomBar, hamburgerFlyout, hamburgerBackdrop)
                for (bar in overlays) {
                    if (bar.visibility == View.VISIBLE && bar.width > 0) {
                        val loc = IntArray(2)
                        bar.getLocationInWindow(loc)
                        outInsets.touchableRegion.op(
                            loc[0], loc[1],
                            loc[0] + bar.width, loc[1] + bar.height,
                            android.graphics.Region.Op.UNION
                        )
                    }
                }
                if (outInsets.touchableRegion.isEmpty) {
                    outInsets.touchableRegion.set(0, 0, dpToPx(56), view.height)
                }
            }
        }
    }

    // ── Top bar: drag handle, clear, eraser, pen/commit ──

    @SuppressLint("ClickableViewAccessibility")
    private fun createTopBar(): View {
        val bg = GradientDrawable().apply {
            setColor(0xFFF5F5F5.toInt())
            cornerRadius = dpToPx(16).toFloat()
        }
        topBarEdge = parseEdge(ScrawlSettings.getTopBarEdge(this), ToolbarEdge.LEFT)
        val layout = LinearLayout(this).apply {
            background = bg
            elevation = 2f
        }
        rebuildTopBar(layout, topBarEdge)
        return layout
    }

    // ── Bottom bar: mode-dependent — writing: undo/erase/annotations/screenshot, select: undo/backspace/space/enter ──

    @SuppressLint("ClickableViewAccessibility")
    private fun createBottomBar(): View {
        val bg = GradientDrawable().apply {
            setColor(0xFFF5F5F5.toInt())
            cornerRadius = dpToPx(16).toFloat()
        }
        bottomBarEdge = parseEdge(ScrawlSettings.getBottomBarEdge(this), ToolbarEdge.LEFT)
        val layout = LinearLayout(this).apply {
            background = bg
            elevation = 2f
        }
        rebuildBottomBar(layout, bottomBarEdge)
        return layout
    }

    // ── Annotation: separate [[ and # toggle buttons ──

    private fun toggleWikiLink() {
        if (canvasMode != CanvasMode.WRITE) return
        if (annotationMode == AnnotationMode.WikiLink) {
            clearAnnotation()
        } else {
            annotationMode = AnnotationMode.WikiLink
            canvasView?.annotationMode = AnnotationMode.WikiLink
            canvasView?.invalidate()
            updateAnnotationButtons()
            updateOnOffButton()
        }
    }

    private fun toggleTag() {
        if (canvasMode != CanvasMode.WRITE) return
        if (annotationMode == AnnotationMode.Tag) {
            clearAnnotation()
        } else {
            annotationMode = AnnotationMode.Tag
            canvasView?.annotationMode = AnnotationMode.Tag
            canvasView?.invalidate()
            updateAnnotationButtons()
            updateOnOffButton()
        }
    }

    private val headerCycle = listOf(
        AnnotationMode.Header1, AnnotationMode.Header2,
        AnnotationMode.Header3, AnnotationMode.Header4
    )

    private fun toggleHeader() {
        if (canvasMode != CanvasMode.WRITE) return
        val idx = headerCycle.indexOf(annotationMode)
        if (idx >= 0) {
            // Cycle to next, or clear if at end
            val next = idx + 1
            if (next < headerCycle.size) {
                annotationMode = headerCycle[next]
                canvasView?.annotationMode = annotationMode
                canvasView?.invalidate()
                updateAnnotationButtons()
                updateOnOffButton()
            } else {
                clearAnnotation()
            }
        } else {
            annotationMode = AnnotationMode.Header1
            canvasView?.annotationMode = AnnotationMode.Header1
            canvasView?.invalidate()
            updateAnnotationButtons()
            updateOnOffButton()
        }
    }

    private fun clearAnnotation() {
        annotationMode = AnnotationMode.None
        canvasView?.annotationMode = AnnotationMode.None
        canvasView?.invalidate()
        updateAnnotationButtons()
    }

    private fun updateAnnotationButtons() {
        fun applyToggle(btn: TextView?, active: Boolean) {
            btn ?: return
            val bg = GradientDrawable().apply {
                setColor(if (active) Color.DKGRAY else Color.WHITE)
                cornerRadius = dpToPx(10).toFloat()
            }
            btn.background = bg
            btn.setTextColor(if (active) Color.WHITE else Color.BLACK)
        }
        applyToggle(wikiLinkBtn, annotationMode == AnnotationMode.WikiLink)
        applyToggle(tagBtn, annotationMode == AnnotationMode.Tag)
        val headerActive = annotationMode in headerCycle
        val hLabel = when (annotationMode) {
            AnnotationMode.Header1 -> "H1"
            AnnotationMode.Header2 -> "H2"
            AnnotationMode.Header3 -> "H3"
            AnnotationMode.Header4 -> "H4"
            else -> "H"
        }
        headerBtn?.text = hLabel
        applyToggle(headerBtn, headerActive)
        setIconActive(screenshotBtn, annotationMode == AnnotationMode.Screenshot, R.drawable.ic_crop_free, R.drawable.ic_crop_free)
    }

    // ── Hamburger flyout ──

    private fun createHamburgerFlyout(): LinearLayout {
        return rebuildHamburgerFlyout(LinearLayout.VERTICAL)
    }

    private fun rebuildHamburgerFlyout(flyoutOrientation: Int): LinearLayout {
        val flyout = hamburgerFlyout ?: LinearLayout(this)
        flyout.removeAllViews()
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFF5F5F5.toInt())
            cornerRadius = dpToPx(16).toFloat()
        }
        flyout.background = bg
        flyout.elevation = 2f
        flyout.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
        flyout.orientation = flyoutOrientation
        flyout.gravity = if (flyoutOrientation == LinearLayout.VERTICAL) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL

        penBtn = makeBtn(penLabel()) { cyclePen() }
        flyout.addView(penBtn)

        // Pen width slider — vertical sliders when flyout is vertical (toolbar horizontal)
        val verticalSliders = flyoutOrientation == LinearLayout.VERTICAL
        flyout.addView(makeSliderRow(
            label = "Width",
            min = ScrawlSettings.PEN_WIDTH_MIN,
            max = ScrawlSettings.PEN_WIDTH_MAX,
            initial = canvasView?.currentPenSize ?: ScrawlSettings.PEN_WIDTH_DEFAULT,
            showPreviewDot = true,
            vertical = verticalSliders,
            formatValue = { "%.1f".format(it) }
        ) { value ->
            canvasView?.currentPenSize = value
            canvasView?.syncTouchHelperPen()
            ScrawlSettings.setPenWidth(this, value)
        })

        // Pressure sensitivity slider
        flyout.addView(makeSliderRow(
            label = "Pressure",
            min = ScrawlSettings.SENSITIVITY_MIN,
            max = ScrawlSettings.SENSITIVITY_MAX,
            initial = canvasView?.pressureSensitivity ?: ScrawlSettings.SENSITIVITY_DEFAULT,
            showPreviewDot = false,
            vertical = verticalSliders,
            formatValue = { "%d%%".format((it * 100).toInt()) }
        ) { value ->
            canvasView?.pressureSensitivity = value
            ScrawlSettings.setPressureSensitivity(this, value)
        })

        flyout.addView(makeIconBtn(R.drawable.ic_keyboard) { showImePicker() })
        recordBtn = makeIconBtn(R.drawable.ic_mic) { toggleRecording() }
        flyout.addView(recordBtn)
        updateRecordButton()
        flyout.addView(makeIconBtn(R.drawable.ic_settings) { launchSettings() })
        return flyout
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeSliderRow(
        label: String,
        min: Float,
        max: Float,
        initial: Float,
        showPreviewDot: Boolean,
        vertical: Boolean = false,
        formatValue: (Float) -> String,
        onChange: (Float) -> Unit
    ): LinearLayout {
        val trackLength = dpToPx(120)
        val thumbSize = dpToPx(20)
        val trackThickness = dpToPx(4)
        val rowSize = dpToPx(40)

        val row = LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
            layoutParams = if (vertical) {
                LinearLayout.LayoutParams(rowSize, LinearLayout.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, rowSize)
            }
        }

        // Label
        val labelView = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(0xFF666666.toInt())
            gravity = Gravity.CENTER
            layoutParams = if (vertical) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(dpToPx(52), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }
        row.addView(labelView)

        // Track container (holds track + thumb)
        val trackContainer = FrameLayout(this).apply {
            layoutParams = if (vertical) {
                LinearLayout.LayoutParams(rowSize, trackLength)
            } else {
                LinearLayout.LayoutParams(trackLength, rowSize)
            }
        }

        // Track background
        val trackBg = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFFCCCCCC.toInt())
                cornerRadius = dpToPx(2).toFloat()
            }
            layoutParams = if (vertical) {
                FrameLayout.LayoutParams(trackThickness, trackLength).apply { gravity = Gravity.CENTER_HORIZONTAL }
            } else {
                FrameLayout.LayoutParams(trackLength, trackThickness).apply { gravity = Gravity.CENTER_VERTICAL }
            }
        }
        trackContainer.addView(trackBg)

        // Filled portion of track
        val trackFill = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFF333333.toInt())
                cornerRadius = dpToPx(2).toFloat()
            }
            layoutParams = if (vertical) {
                FrameLayout.LayoutParams(trackThickness, 0).apply { gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM }
            } else {
                FrameLayout.LayoutParams(0, trackThickness).apply { gravity = Gravity.CENTER_VERTICAL }
            }
        }
        trackContainer.addView(trackFill)

        // Thumb
        val thumbBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(dpToPx(2), 0xFF333333.toInt())
        }
        val thumb = View(this).apply {
            background = thumbBg
            layoutParams = FrameLayout.LayoutParams(thumbSize, thumbSize).apply {
                gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            }
        }
        trackContainer.addView(thumb)

        // Preview dot (for pen width) — shows to the right/below value
        var previewDot: View? = null
        if (showPreviewDot) {
            previewDot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.BLACK)
                }
            }
        }

        // Value label
        val valueView = TextView(this).apply {
            text = formatValue(initial)
            textSize = 11f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = if (vertical) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dpToPx(4)
                }
            } else {
                LinearLayout.LayoutParams(dpToPx(36), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dpToPx(6)
                }
            }
        }

        // Position updater
        var currentValue = initial
        fun updateThumbPosition(fraction: Float) {
            val usable = trackLength - thumbSize
            val pos = (fraction * usable).toInt()
            if (vertical) {
                // Bottom-up: fraction 0 = bottom, 1 = top
                thumb.translationY = ((1f - fraction) * usable).toFloat()
                (trackFill.layoutParams as FrameLayout.LayoutParams).height = pos + thumbSize / 2
            } else {
                thumb.translationX = pos.toFloat()
                (trackFill.layoutParams as FrameLayout.LayoutParams).width = pos + thumbSize / 2
            }
            trackFill.requestLayout()
        }

        fun updatePreviewDot(value: Float) {
            if (showPreviewDot && previewDot != null) {
                val dotSize = dpToPx(value.coerceIn(min, max).toInt().coerceAtLeast(2))
                previewDot.layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    if (vertical) topMargin = dpToPx(2) else marginStart = dpToPx(2)
                    gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
                }
                previewDot.requestLayout()
            }
        }

        // Initial position — post to ensure layout is ready
        trackContainer.post {
            val fraction = ((initial - min) / (max - min)).coerceIn(0f, 1f)
            updateThumbPosition(fraction)
            updatePreviewDot(initial)
        }

        // Touch handling for drag
        trackContainer.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val fraction = if (vertical) {
                        (1f - event.y / trackLength).coerceIn(0f, 1f)
                    } else {
                        (event.x / trackLength).coerceIn(0f, 1f)
                    }
                    currentValue = min + fraction * (max - min)
                    updateThumbPosition(fraction)
                    valueView.text = formatValue(currentValue)
                    updatePreviewDot(currentValue)
                    onChange(currentValue)
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }

        row.addView(trackContainer)
        row.addView(valueView)
        if (previewDot != null) row.addView(previewDot)

        return row
    }

    private fun dismissHamburger() {
        hamburgerFlyout?.visibility = View.GONE
        hamburgerBackdrop?.visibility = View.GONE
    }

    private fun toggleHamburger() {
        val flyout = hamburgerFlyout ?: return
        val btn = hamburgerBtn ?: return
        if (flyout.visibility == View.VISIBLE) {
            dismissHamburger()
            return
        }
        val bottomLayout = bottomBar as? LinearLayout ?: return
        val toolbarOrientation = bottomLayout.orientation
        val flyoutOrientation = if (toolbarOrientation == LinearLayout.HORIZONTAL) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        rebuildHamburgerFlyout(flyoutOrientation)

        val loc = IntArray(2)
        btn.getLocationInWindow(loc)
        val rootLoc = IntArray(2)
        (flyout.parent as? View)?.getLocationInWindow(rootLoc)
        flyout.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val parent = flyout.parent as? View
        if (toolbarOrientation == LinearLayout.HORIZONTAL) {
            val desiredX = (loc[0] - rootLoc[0]).toFloat()
            // Place above the toolbar; if at top edge, place below instead
            val aboveY = (loc[1] - rootLoc[1] - flyout.measuredHeight - dpToPx(8)).toFloat()
            val belowY = (loc[1] - rootLoc[1] + btn.height + dpToPx(8)).toFloat()
            val desiredY = if (aboveY >= 0) aboveY else belowY
            flyout.translationX = clampTranslationX(flyout, parent, desiredX)
            flyout.translationY = clampTranslationY(flyout, parent, desiredY)
        } else {
            // Position to the right of the entire toolbar, not just the button
            val barLoc = IntArray(2)
            bottomLayout.getLocationInWindow(barLoc)
            val desiredX = (barLoc[0] - rootLoc[0] + bottomLayout.width + dpToPx(8)).toFloat()
            val desiredY = (loc[1] - rootLoc[1] + btn.height / 2 - flyout.measuredHeight / 2).toFloat()
            flyout.translationX = clampTranslationX(flyout, parent, desiredX)
            flyout.translationY = clampTranslationY(flyout, parent, desiredY)
        }
        hamburgerBackdrop?.visibility = View.VISIBLE
        flyout.visibility = View.VISIBLE
    }

    // ── Drag handle builder ──

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDragHandle(target: LinearLayout, isTopBar: Boolean): View {
        val grip = ImageView(this).apply {
            setImageResource(R.drawable.ic_drag_handle)
            setColorFilter(0xFFBBBBBB.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        var dragStart = 0f
        var dragStartTrans = 0f
        var startEdge = ToolbarEdge.LEFT
        var currentDragEdge = ToolbarEdge.LEFT
        var lastTouchParentX = 0f
        var lastTouchParentY = 0f
        grip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startEdge = if (isTopBar) topBarEdge else bottomBarEdge
                    currentDragEdge = startEdge
                    val horizontal = startEdge == ToolbarEdge.TOP || startEdge == ToolbarEdge.BOTTOM
                    dragStart = if (horizontal) event.rawX else event.rawY
                    dragStartTrans = if (horizontal) target.translationX else target.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val parent = target.parent as? View ?: return@setOnTouchListener true
                    val parentLoc = IntArray(2)
                    parent.getLocationInWindow(parentLoc)
                    val touchX = event.rawX - parentLoc[0]
                    val touchY = event.rawY - parentLoc[1]
                    lastTouchParentX = touchX
                    lastTouchParentY = touchY
                    val dragEdge = nearestEdgeFromPoint(parent, touchX, touchY)

                    if (dragEdge != currentDragEdge) {
                        // Edge changed during drag — retype layout in place
                        currentDragEdge = dragEdge
                        val horizontal = dragEdge == ToolbarEdge.TOP || dragEdge == ToolbarEdge.BOTTOM
                        retypeToolbarLayout(target, horizontal)
                    }

                    if (dragEdge == startEdge) {
                        // Original edge — delta-based 1D slide + snap perpendicular axis
                        val horizontal = startEdge == ToolbarEdge.TOP || startEdge == ToolbarEdge.BOTTOM
                        val raw = if (horizontal) event.rawX else event.rawY
                        val desired = dragStartTrans + (raw - dragStart)
                        if (horizontal) {
                            target.translationX = clampTranslationX(target, parent, desired)
                            target.translationY = snapPerpendicularY(target, parent, startEdge)
                        } else {
                            target.translationX = snapPerpendicularX(target, parent, startEdge)
                            target.translationY = clampTranslationY(target, parent, desired)
                        }
                    } else {
                        // Different edge — snap toolbar to that edge, centered on touch
                        projectToolbarToEdge(target, parent, dragEdge, touchX, touchY)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val parent = target.parent as? View
                    val finalEdge = if (parent != null) {
                        nearestEdgeFromPoint(parent, lastTouchParentX, lastTouchParentY)
                    } else startEdge

                    if (finalEdge != startEdge) {
                        // Edge changed — do a full rebuild (deferred) to finalize
                        val tx = lastTouchParentX
                        val ty = lastTouchParentY
                        target.post {
                            if (isTopBar) {
                                topBarEdge = finalEdge
                                rebuildTopBar(target, finalEdge)
                                reapplyTopBarState()
                            } else {
                                bottomBarEdge = finalEdge
                                rebuildBottomBar(target, finalEdge)
                                reapplyBottomBarState()
                            }
                            target.post {
                                val p = target.parent as? View ?: return@post
                                val offset = touchOffsetForEdge(target, p, finalEdge, tx, ty)
                                positionToolbarOnEdge(target, finalEdge, offset)
                                persistToolbar(isTopBar, finalEdge, offset)
                                updateRawDrawingRegion()
                            }
                        }
                    } else {
                        val horizontal = finalEdge == ToolbarEdge.TOP || finalEdge == ToolbarEdge.BOTTOM
                        val offset = if (horizontal) target.translationX else target.translationY
                        persistToolbar(isTopBar, finalEdge, offset)
                        updateRawDrawingRegion()
                    }
                    dismissHamburger()
                    true
                }
                else -> false
            }
        }
        return grip
    }

    /** Which parent edge is the point closest to? */
    private fun nearestEdgeFromPoint(parent: View, x: Float, y: Float): ToolbarEdge {
        val distances = listOf(
            ToolbarEdge.LEFT to x,
            ToolbarEdge.TOP to y,
            ToolbarEdge.RIGHT to (parent.width - x),
            ToolbarEdge.BOTTOM to (parent.height - y)
        )
        return distances.minByOrNull { it.second }?.first ?: ToolbarEdge.LEFT
    }

    /** Retype a toolbar's orientation and update child layout params in-place (no add/remove). */
    private fun retypeToolbarLayout(layout: LinearLayout, horizontal: Boolean) {
        configureToolbarLayout(layout, horizontal)
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            when {
                child === topBarDragHandle || child === bottomBarDragHandle -> {
                    applyDragHandleLayout(child, horizontal)
                }
                lp.width == dpToPx(1) || lp.height == dpToPx(1) -> {
                    // Divider — swap dimensions
                    if (horizontal) {
                        lp.width = dpToPx(1)
                        lp.height = dpToPx(28)
                    } else {
                        lp.width = dpToPx(28)
                        lp.height = dpToPx(1)
                    }
                    child.layoutParams = lp
                }
            }
        }
    }

    private fun snapPerpendicularX(layout: View, parent: View, edge: ToolbarEdge): Float {
        val inset = toolbarInset.toFloat()
        return when (edge) {
            ToolbarEdge.LEFT -> 0f
            ToolbarEdge.RIGHT -> (parent.width - layout.width - layout.left).toFloat() - inset
            else -> layout.translationX // horizontal edges slide along X, no perpendicular snap needed
        }
    }

    private fun snapPerpendicularY(layout: View, parent: View, edge: ToolbarEdge): Float {
        val inset = toolbarInset.toFloat()
        return when (edge) {
            ToolbarEdge.TOP -> 0f
            ToolbarEdge.BOTTOM -> (parent.height - layout.height - layout.top).toFloat() - inset
            else -> layout.translationY // vertical edges slide along Y, no perpendicular snap needed
        }
    }

    /** During drag: snap toolbar to an edge, centered on the touch point along that edge. */
    private fun projectToolbarToEdge(layout: View, parent: View, edge: ToolbarEdge, touchX: Float, touchY: Float) {
        val inset = toolbarInset.toFloat()
        when (edge) {
            ToolbarEdge.LEFT -> {
                layout.translationX = 0f
                layout.translationY = clampTranslationY(layout, parent, touchY - layout.top - layout.height / 2f)
            }
            ToolbarEdge.RIGHT -> {
                layout.translationX = (parent.width - layout.width - layout.left).toFloat() - inset
                layout.translationY = clampTranslationY(layout, parent, touchY - layout.top - layout.height / 2f)
            }
            ToolbarEdge.TOP -> {
                layout.translationY = 0f
                layout.translationX = clampTranslationX(layout, parent, touchX - layout.left - layout.width / 2f)
            }
            ToolbarEdge.BOTTOM -> {
                layout.translationY = (parent.height - layout.height - layout.top).toFloat() - inset
                layout.translationX = clampTranslationX(layout, parent, touchX - layout.left - layout.width / 2f)
            }
        }
    }

    /** Convert a touch position to an offset along the given edge (using post-rebuild dimensions). */
    private fun touchOffsetForEdge(layout: View, parent: View, edge: ToolbarEdge, touchX: Float, touchY: Float): Float {
        return when (edge) {
            ToolbarEdge.TOP, ToolbarEdge.BOTTOM ->
                clampTranslationX(layout, parent, touchX - layout.left - layout.width / 2f)
            ToolbarEdge.LEFT, ToolbarEdge.RIGHT ->
                clampTranslationY(layout, parent, touchY - layout.top - layout.height / 2f)
        }
    }

    private fun reapplyTopBarState() {
        updateOnOffButton()
    }

    private fun reapplyBottomBarState() {
        updateOnOffButton()
        updateRecordButton()
        if (canvasMode == CanvasMode.WRITE) {
            val canvas = canvasView ?: return
            setIconActive(eraserBtn, canvas.isEraserMode, R.drawable.ic_ink_eraser, R.drawable.ic_ink_eraser)
            updateAnnotationButtons()
        }
    }

    private fun rebuildTopBar(layout: LinearLayout, edge: ToolbarEdge) {
        val horizontal = edge == ToolbarEdge.TOP || edge == ToolbarEdge.BOTTOM
        configureToolbarLayout(layout, horizontal)
        layout.removeAllViews()

        val dragHandle = makeDragHandle(layout, isTopBar = true)
        applyDragHandleLayout(dragHandle, horizontal)
        topBarDragHandle = dragHandle
        layout.addView(dragHandle)

        clearBtn = makeIconBtn(R.drawable.ic_close) { onClearPressed() }
        layout.addView(clearBtn)
        layout.addView(makeDivider(layout.orientation))

        onOffBtn = makeIconBtn(R.drawable.ic_edit) { toggleOnOff() }
        layout.addView(onOffBtn)
    }

    private fun rebuildBottomBar(layout: LinearLayout, edge: ToolbarEdge) {
        val horizontal = edge == ToolbarEdge.TOP || edge == ToolbarEdge.BOTTOM
        configureToolbarLayout(layout, horizontal)
        layout.removeAllViews()

        val dragHandle = makeDragHandle(layout, isTopBar = false)
        applyDragHandleLayout(dragHandle, horizontal)
        bottomBarDragHandle = dragHandle
        layout.addView(dragHandle)

        layout.addView(makeIconBtn(R.drawable.ic_undo) { undoLastStroke() })
        layout.addView(makeDivider(layout.orientation))

        if (canvasMode == CanvasMode.WRITE) {
            eraserBtn = makeIconBtn(R.drawable.ic_ink_eraser) { toggleEraser() }
            layout.addView(eraserBtn)
            layout.addView(makeDivider(layout.orientation))

            wikiLinkBtn = makeBtn("[[") { toggleWikiLink() }
            layout.addView(wikiLinkBtn)
            layout.addView(makeDivider(layout.orientation))

            tagBtn = makeBtn("#") { toggleTag() }
            layout.addView(tagBtn)
            layout.addView(makeDivider(layout.orientation))

            headerBtn = makeBtn("H") { toggleHeader() }
            layout.addView(headerBtn)
            layout.addView(makeDivider(layout.orientation))

            screenshotBtn = makeIconBtn(R.drawable.ic_crop_free) { toggleScreenshotMode() }
            layout.addView(screenshotBtn)
            layout.addView(makeDivider(layout.orientation))
        } else {
            eraserBtn = null
            wikiLinkBtn = null
            tagBtn = null
            headerBtn = null
            screenshotBtn = null

            layout.addView(makeIconBtn(R.drawable.ic_backspace) { sendBackspace() })
            layout.addView(makeDivider(layout.orientation))

            layout.addView(makeIconBtn(R.drawable.ic_space_bar) { sendSpace() })
            layout.addView(makeDivider(layout.orientation))

            layout.addView(makeIconBtn(R.drawable.ic_keyboard_return) { sendEnter() })
            layout.addView(makeDivider(layout.orientation))
        }

        hamburgerBtn = makeIconBtn(R.drawable.ic_menu) { toggleHamburger() }
        layout.addView(hamburgerBtn)
        layout.addView(makeDivider(layout.orientation))

        val collapseIcon = when (edge) {
            ToolbarEdge.RIGHT -> R.drawable.ic_chevron_right
            else -> R.drawable.ic_chevron_left
        }
        layout.addView(makeIconBtn(collapseIcon) { collapseSidebar() })
    }

    private fun configureToolbarLayout(layout: LinearLayout, horizontal: Boolean) {
        layout.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        layout.gravity = if (horizontal) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        if (horizontal) {
            layout.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
        } else {
            layout.setPadding(dpToPx(4), dpToPx(8), dpToPx(4), dpToPx(8))
        }
    }

    private fun applyDragHandleLayout(handle: View, horizontal: Boolean) {
        handle.layoutParams = LinearLayout.LayoutParams(
            if (horizontal) dpToPx(18) else ViewGroup.LayoutParams.MATCH_PARENT,
            if (horizontal) dpToPx(44) else dpToPx(14)
        ).apply {
            if (horizontal) {
                setMargins(0, 0, dpToPx(6), 0)
            } else {
                setMargins(0, 0, 0, dpToPx(6))
            }
        }
    }

    /** Position toolbar snapped to the given edge with offset along that edge. */
    private fun positionToolbarOnEdge(layout: View, edge: ToolbarEdge, offset: Float) {
        val parent = layout.parent as? View ?: return
        val inset = toolbarInset.toFloat()
        // Perpendicular axis: snap to edge (layout.left/top already = toolbarInset from margins)
        // Parallel axis: use offset (clamped)
        when (edge) {
            ToolbarEdge.LEFT -> {
                layout.translationX = 0f // margin provides inset
                layout.translationY = if (offset < 0f) defaultOffsetForEdge(layout, parent, edge) else
                    clampTranslationY(layout, parent, offset)
            }
            ToolbarEdge.RIGHT -> {
                layout.translationX = (parent.width - layout.width - layout.left - inset.toInt()).toFloat()
                layout.translationY = if (offset < 0f) defaultOffsetForEdge(layout, parent, edge) else
                    clampTranslationY(layout, parent, offset)
            }
            ToolbarEdge.TOP -> {
                layout.translationY = 0f // margin provides inset
                layout.translationX = if (offset < 0f) defaultOffsetForEdge(layout, parent, edge) else
                    clampTranslationX(layout, parent, offset)
            }
            ToolbarEdge.BOTTOM -> {
                layout.translationY = (parent.height - layout.height - layout.top - inset.toInt()).toFloat()
                layout.translationX = if (offset < 0f) defaultOffsetForEdge(layout, parent, edge) else
                    clampTranslationX(layout, parent, offset)
            }
        }
    }

    /** Default offset for a toolbar when no saved position exists. */
    private fun defaultOffsetForEdge(layout: View, parent: View, edge: ToolbarEdge): Float {
        val isBottomBar = layout === bottomBar
        return when (edge) {
            ToolbarEdge.LEFT, ToolbarEdge.RIGHT -> {
                if (isBottomBar) {
                    // Position near bottom
                    (parent.height - layout.height - layout.top - bottomToolbarDefaultOffset).toFloat()
                        .coerceAtLeast(0f)
                } else {
                    0f // top of the edge
                }
            }
            ToolbarEdge.TOP, ToolbarEdge.BOTTOM -> 0f
        }
    }

    private fun persistToolbar(isTopBar: Boolean, edge: ToolbarEdge, offset: Float) {
        if (isTopBar) {
            ScrawlSettings.setTopBarEdge(this, edge.name)
            ScrawlSettings.setTopBarOffset(this, offset)
        } else {
            ScrawlSettings.setBottomBarEdge(this, edge.name)
            ScrawlSettings.setBottomBarOffset(this, offset)
        }
    }

    private fun parseEdge(name: String, default: ToolbarEdge): ToolbarEdge {
        return try { ToolbarEdge.valueOf(name) } catch (_: Exception) { default }
    }

    private fun updateRawDrawingRegion() {
        val canvas = canvasView ?: return
        if (canvasMode != CanvasMode.WRITE || !ScrawlSettings.hasSeenTooltips(this)) {
            canvas.configureRawDrawingRegion(null, enabled = false)
            return
        }
        if (canvas.width <= 0 || canvas.height <= 0) return

        val margin = dpToPx(8)
        val limit = Rect(0, 0, canvas.width, canvas.height)
        val canvasLoc = IntArray(2)
        canvas.getLocationInWindow(canvasLoc)

        fun applyToolbar(view: View?, edge: ToolbarEdge) {
            if (view == null || view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            val left = loc[0] - canvasLoc[0]
            val top = loc[1] - canvasLoc[1]
            val right = left + view.width
            val bottom = top + view.height
            when (edge) {
                ToolbarEdge.LEFT -> limit.left = maxOf(limit.left, right + margin)
                ToolbarEdge.RIGHT -> limit.right = minOf(limit.right, left - margin)
                ToolbarEdge.TOP -> limit.top = maxOf(limit.top, bottom + margin)
                ToolbarEdge.BOTTOM -> limit.bottom = minOf(limit.bottom, top - margin)
            }
        }

        applyToolbar(topBar, topBarEdge)
        applyToolbar(bottomBar, bottomBarEdge)

        limit.left = limit.left.coerceIn(0, canvas.width)
        limit.top = limit.top.coerceIn(0, canvas.height)
        limit.right = limit.right.coerceIn(limit.left, canvas.width)
        limit.bottom = limit.bottom.coerceIn(limit.top, canvas.height)

        canvas.configureRawDrawingRegion(limit, enabled = true)
        Log.d("ScrawlPerf", "rawDrawingRegion=$limit")
    }

    // ── Shared button builders ──

    private fun makeBtn(label: String, onClick: () -> Unit): TextView {
        val size = dpToPx(44)
        val btnBg = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dpToPx(10).toFloat()
        }

        return TextView(this).apply {
            text = label
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            background = btnBg
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
            }
            setOnClickListener { Log.i("ScrawlLayers", "text button click label=$label"); onClick(); canvasView?.showAndroidUi() }
        }
    }

    private fun makeIconBtn(drawableRes: Int, onClick: () -> Unit): ImageView {
        val size = dpToPx(44)
        val btnBg = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dpToPx(10).toFloat()
        }

        return ImageView(this).apply {
            setImageResource(drawableRes)
            setColorFilter(Color.BLACK)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = btnBg
            val pad = dpToPx(10)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
            }
            setOnClickListener { Log.i("ScrawlLayers", "icon button click drawable=$drawableRes"); onClick(); canvasView?.showAndroidUi() }
        }
    }

    private fun makeDivider(orientation: Int = LinearLayout.VERTICAL): View {
        return if (orientation == LinearLayout.HORIZONTAL) {
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(1),
                    dpToPx(28)
                ).apply {
                    setMargins(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                }
                setBackgroundColor(0xFFE0E0E0.toInt())
            }
        } else {
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(28),
                    dpToPx(1)
                ).apply {
                    setMargins(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                }
                setBackgroundColor(0xFFE0E0E0.toInt())
            }
        }
    }

    // ── Toggle helpers ──

    private fun setButtonActive(btn: TextView?, active: Boolean, activeLabel: String, inactiveLabel: String) {
        btn ?: return
        btn.text = if (active) activeLabel else inactiveLabel
        val bg = GradientDrawable().apply {
            setColor(if (active) Color.DKGRAY else Color.WHITE)
            cornerRadius = dpToPx(10).toFloat()
        }
        btn.background = bg
        btn.setTextColor(if (active) Color.WHITE else Color.BLACK)
    }

    private fun setIconActive(btn: ImageView?, active: Boolean, activeRes: Int, inactiveRes: Int) {
        btn ?: return
        btn.setImageResource(if (active) activeRes else inactiveRes)
        val bg = GradientDrawable().apply {
            setColor(if (active) Color.DKGRAY else Color.WHITE)
            cornerRadius = dpToPx(10).toFloat()
        }
        btn.background = bg
        btn.setColorFilter(if (active) Color.WHITE else Color.BLACK)
    }

    // ── Actions ──

    private fun toggleOnOff() {
        if (isCommitting) {
            // During commit, tapping the pulsating button cancels and restores handwriting
            commitJob?.cancel()
            commitJob = null
            setCommittingUI(false)
            // Strokes are still on canvas (clearAll hasn't run yet) — just stay in write mode
            return
        }
        if (canvasMode == CanvasMode.WRITE && annotationMode != AnnotationMode.None) {
            // Annotation active — clear it, reactivate normal pen
            clearAnnotation()
            updateOnOffButton()
            return
        }
        when (canvasMode) {
            CanvasMode.WRITE -> {
                val strokeCount = canvasView?.getStrokes()?.size ?: 0
                if (shouldShowCommit(strokeCount)) {
                    commitHandwriting()  // commits and switches to select
                } else {
                    enterSelect()
                }
            }
            CanvasMode.SELECT -> enterWrite()
        }
    }

    private fun enterSelect() {
        val canvas = canvasView ?: return
        clearAnnotation()
        // Auto-commit pending strokes before switching
        if (canvas.getStrokes().isNotEmpty()) {
            commitHandwriting()
            return  // commitHandwriting calls enterSelect() after async commit
        }
        canvasMode = CanvasMode.SELECT
        canvas.visibility = View.INVISIBLE
        canvas.deactivateRawDrawing()
        rebuildBottomBarForMode()
        updateOnOffButton()
        window?.window?.decorView?.requestLayout()
    }

    private fun enterWrite() {
        canvasMode = CanvasMode.WRITE
        lastCommittedText = null
        val canvas = canvasView
        if (canvas?.hasStash() == true) {
            canvas.restoreStrokes()
        }
        canvas?.visibility = View.VISIBLE
        canvas?.activateRawDrawing()
        rebuildBottomBarForMode()
        updateOnOffButton()
        updateRawDrawingRegion()
        window?.window?.decorView?.requestLayout()
    }

    private fun rebuildBottomBarForMode() {
        val layout = bottomBar as? LinearLayout ?: return
        rebuildBottomBar(layout, bottomBarEdge)
        reapplyBottomBarState()
        layout.post {
            val offset = ScrawlSettings.getBottomBarOffset(this)
            positionToolbarOnEdge(layout, bottomBarEdge, offset)
            updateRawDrawingRegion()
        }
    }

    private fun shouldShowCommit(strokeCount: Int): Boolean {
        val hasScreenshot = canvasView?.getScreenshotRegion() != null
        return strokeCount >= 2 || hasScreenshot
    }

    private fun updateModeButton(strokeCount: Int) {
        if (canvasMode != CanvasMode.WRITE) return
        val btn = onOffBtn ?: return
        if (shouldShowCommit(strokeCount)) {
            setIconActive(btn, true, R.drawable.ic_check, R.drawable.ic_check)
        } else {
            val penActive = annotationMode == AnnotationMode.None
            setIconActive(btn, penActive, R.drawable.ic_edit, R.drawable.ic_edit)
        }
    }

    private fun updateOnOffButton() {
        val btn = onOffBtn ?: return
        when (canvasMode) {
            CanvasMode.WRITE -> {
                val strokeCount = canvasView?.getStrokes()?.size ?: 0
                if (shouldShowCommit(strokeCount)) {
                    setIconActive(btn, true, R.drawable.ic_check, R.drawable.ic_check)
                } else {
                    val penActive = annotationMode == AnnotationMode.None
                    setIconActive(btn, penActive, R.drawable.ic_edit, R.drawable.ic_edit)
                }
                resetClearButton(false)
            }
            CanvasMode.SELECT -> {
                setIconActive(btn, false, R.drawable.ic_text_select, R.drawable.ic_text_select)
                resetClearButton(true)
            }
        }
        updateAnnotationButtons()
    }

    private fun resetClearButton(grayed: Boolean) {
        val clear = clearBtn ?: return
        val bg = GradientDrawable().apply {
            setColor(if (grayed) 0xFFE0E0E0.toInt() else Color.WHITE)
            cornerRadius = dpToPx(10).toFloat()
        }
        clear.background = bg
        clear.setColorFilter(if (grayed) 0xFFAAAAAA.toInt() else Color.BLACK)
        clear.isEnabled = !grayed
    }

    private fun setCommittingUI(committing: Boolean) {
        isCommitting = committing
        if (committing) {
            // Gray out all buttons in both bars
            grayOutBar(topBar)
            grayOutBar(bottomBar)

            // Mode button: show select/restore icon and pulsate
            val btn = onOffBtn ?: return
            setIconActive(btn, false, R.drawable.ic_text_select, R.drawable.ic_text_select)
            btn.isEnabled = true  // keep tappable
            btn.alpha = 1f
            pulsateAnimator = ObjectAnimator.ofFloat(btn, "alpha", 1f, 0.3f).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            pulsateAnimator?.cancel()
            pulsateAnimator = null
            onOffBtn?.alpha = 1f
            restoreBar(topBar)
            restoreBar(bottomBar)
            updateOnOffButton()
        }
    }

    private fun grayOutChild(child: View, exclude: View?) {
        if (child == exclude) return
        when (child) {
            is TextView -> { child.isEnabled = false; child.alpha = 0.35f }
            is ImageView -> { child.isEnabled = false; child.alpha = 0.35f }
            is LinearLayout -> {
                for (i in 0 until child.childCount) grayOutChild(child.getChildAt(i), exclude)
            }
        }
    }

    private fun restoreChild(child: View) {
        when (child) {
            is TextView -> { child.isEnabled = true; child.alpha = 1f }
            is ImageView -> { child.isEnabled = true; child.alpha = 1f }
            is LinearLayout -> {
                for (i in 0 until child.childCount) restoreChild(child.getChildAt(i))
            }
        }
    }

    private fun grayOutBar(bar: View?) {
        val layout = bar as? LinearLayout ?: return
        for (i in 0 until layout.childCount) {
            grayOutChild(layout.getChildAt(i), onOffBtn)
        }
    }

    private fun restoreBar(bar: View?) {
        val layout = bar as? LinearLayout ?: return
        for (i in 0 until layout.childCount) {
            restoreChild(layout.getChildAt(i))
        }
    }

    private fun isWithinWord(): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(1, 0)
        val after = ic.getTextAfterCursor(1, 0)
        if (before.isNullOrEmpty() || after.isNullOrEmpty()) return false
        return before.last().isLetterOrDigit() && after.first().isLetterOrDigit()
    }

    private fun needsLeadingSpace(): Boolean {
        if (isWithinWord()) return false
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(1, 0)
        if (before.isNullOrEmpty()) return false
        val ch = before[before.length - 1]
        return ch != ' ' && ch != '\n' && ch != '\t'
    }

    private fun collapseLines(text: String, strokes: List<Stroke>, canvasWidth: Float = 0f): String {
        if (!text.contains('\n') || strokes.isEmpty()) return text

        data class Row(var top: Float, var bottom: Float, var left: Float, var right: Float)
        val sorted = strokes.sortedBy { it.top }
        val rows = mutableListOf<Row>()
        for (s in sorted) {
            val last = rows.lastOrNull()
            if (last != null && s.top <= last.bottom) {
                last.bottom = maxOf(last.bottom, s.bottom)
                last.left = minOf(last.left, s.left)
                last.right = maxOf(last.right, s.right)
            } else {
                rows.add(Row(s.top, s.bottom, s.left, s.right))
            }
        }

        if (rows.size <= 1) return text.replace('\n', ' ')

        val gaps = (1 until rows.size).map { i -> rows[i].top - rows[i - 1].bottom }
        val lineHeights = rows.map { it.bottom - it.top }
        val medianLineHeight = lineHeights.sorted()[lineHeights.size / 2]

        // Right-edge margin: if a row's strokes end within this fraction of
        // the canvas width from the right edge, the writer likely ran out of
        // room (line wrap) rather than intentionally breaking.
        val edgeMarginFraction = 0.15f

        Log.i("Scrawl", "collapseLines: ${rows.size} rows, canvasW=$canvasWidth")

        val lines = text.split('\n')
        val newlineCount = lines.size - 1

        // Build a set of gap indices that should be kept as newlines.
        val keepNewline = BooleanArray(gaps.size)
        for (i in gaps.indices) {
            val prevRow = rows[i]
            val nextRow = rows[i + 1]
            val localHeight = (prevRow.bottom - prevRow.top + nextRow.bottom - nextRow.top) / 2f
            val gapThreshold = localHeight * 1.5f

            // Did the previous row reach near the right edge? If so, the
            // writer likely wrapped rather than deliberately breaking.
            val reachedRightEdge = canvasWidth > 0f &&
                    prevRow.right >= canvasWidth * (1f - edgeMarginFraction)

            // Does the next row start near where the previous row started?
            // Relative to prevRow.left (not global minimum) so indented writing works.
            val returnedToLeft = nextRow.left <= prevRow.left + medianLineHeight

            // Does the next row end near the same x as the previous row?
            // Same right-end = writer filled to the same stopping point → likely a wrap.
            val endAligned = canvasWidth > 0f &&
                    kotlin.math.abs(nextRow.right - prevRow.right) <= medianLineHeight

            keepNewline[i] = when {
                reachedRightEdge || endAligned -> {
                    // Wrap signals — only keep newline for very large gaps (paragraph break)
                    gaps[i] > gapThreshold * 2f
                }
                returnedToLeft -> {
                    // Next row starts near where prev row started, prev ended short of edge
                    // — classic carriage return, always a newline.
                    true
                }
                else -> {
                    // Neither wrap nor carriage-return signal — use gap heuristic
                    gaps[i] > localHeight * 0.5f
                }
            }

            Log.d("Scrawl", "  gap[$i]=${gaps[i]} reachedEdge=$reachedRightEdge endAligned=$endAligned returnedLeft=$returnedToLeft -> ${if (keepNewline[i]) "\\n" else "' '"}")
        }

        // When the HWR returns more newlines than we have stroke-row gaps,
        // distribute text lines across the rows we detected.
        if (newlineCount != gaps.size) {
            Log.d("Scrawl", "collapseLines: newline count ($newlineCount) != gap count (${gaps.size}), distributing")
            // Assign each HWR text line to a row: split newlines into groups,
            // one group per row, then join lines within each row with spaces.
            val rowCount = rows.size
            if (newlineCount < gaps.size) {
                // Fewer text lines than rows — can't map, fall back to gap decisions
                // with available lines (just join all with spaces)
                return text.replace('\n', ' ')
            }
            // More text lines than rows: distribute evenly, putting extra in last row
            val linesPerRow = newlineCount / rowCount
            val remainder = newlineCount % rowCount
            val rowTexts = mutableListOf<String>()
            var lineIdx = 0
            for (r in 0 until rowCount) {
                val count = linesPerRow + (if (r == rowCount - 1) remainder else 0) + (if (r == 0) 1 else 0)
                val chunk = lines.subList(lineIdx, minOf(lineIdx + count, lines.size))
                rowTexts.add(chunk.joinToString(" "))
                lineIdx += count
            }
            // Grab any remaining lines into the last row
            if (lineIdx < lines.size) {
                rowTexts[rowTexts.size - 1] = rowTexts.last() + " " + lines.subList(lineIdx, lines.size).joinToString(" ")
            }
            val sb = StringBuilder(rowTexts[0])
            for (i in gaps.indices) {
                sb.append(if (keepNewline[i]) '\n' else ' ')
                sb.append(rowTexts[i + 1])
            }
            return sb.toString()
        }

        val sb = StringBuilder(lines[0])
        for (i in gaps.indices) {
            if (keepNewline[i]) {
                sb.append('\n')
            } else {
                sb.append(' ')
            }
            sb.append(lines[i + 1])
        }
        return sb.toString()
    }

    private fun commitHandwriting() {
        val canvas = canvasView ?: return
        val allStrokes = canvas.getStrokes()
        val ssRegion = canvas.getScreenshotRegion()
        val hasScreenshot = ssRegion != null

        // Filter out strokes inside the screenshot rect (they're captured as image)
        val strokeList = if (ssRegion != null) {
            allStrokes.filter { s ->
                val cx = (s.left + s.right) / 2f
                val cy = (s.top + s.bottom) / 2f
                !ssRegion.bounds.contains(cx, cy)
            }
        } else {
            allStrokes
        }

        if (strokeList.isEmpty() && !hasScreenshot) {
            if (canvasMode == CanvasMode.WRITE) {
                canvasMode = CanvasMode.SELECT
                canvas.visibility = View.INVISIBLE
                canvas.deactivateRawDrawing()
                rebuildBottomBarForMode()
                updateOnOffButton()
                window?.window?.decorView?.requestLayout()
            }
            return
        }

        // Filter screenshot regions out of text annotations
        val regions = canvas.getAnnotations().filter { it.mode != AnnotationMode.Screenshot }
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        Log.i("Scrawl", "commit: strokes=${strokeList.size}, annotations=${regions.size}, screenshot=$hasScreenshot")

        setCommittingUI(true)

        commitJob = serviceScope.launch {
            var success = false
            var imageSuffix = ""

            // If there's a screenshot region, capture it via MediaProjection
            if (ssRegion != null) {
                val ssDir = ScrawlSettings.getScreenshotDocumentFile(this@ScrawlIMEService)
                if (ssDir == null || !ssDir.exists()) {
                    android.widget.Toast.makeText(this@ScrawlIMEService, "Set screenshot folder in Scrawl settings", android.widget.Toast.LENGTH_LONG).show()
                    setCommittingUI(false)
                    commitJob = null
                    return@launch
                }

                // Hide UI elements but keep canvas visible (strokes need to be captured)
                canvas.hideAnnotationOverlays = true
                canvas.invalidate()
                topBar?.visibility = View.INVISIBLE
                bottomBar?.visibility = View.INVISIBLE
                hamburgerFlyout?.visibility = View.INVISIBLE
                hamburgerBackdrop?.visibility = View.INVISIBLE
                collapsedHandle?.visibility = View.INVISIBLE

                delay(600) // e-ink refresh

                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val filename = "scrawl-$timestamp.png"
                val ssFile = ssDir.createFile("image/png", filename)
                if (ssFile == null) {
                    android.widget.Toast.makeText(this@ScrawlIMEService, "Failed to create screenshot file", android.widget.Toast.LENGTH_LONG).show()
                    restoreUIAfterCapture(canvas)
                    setCommittingUI(false)
                    commitJob = null
                    return@launch
                }
                val loc = IntArray(2)
                canvasView?.getLocationOnScreen(loc)

                if (!ScreenshotService.isReady) {
                    // Permission not granted yet — restore UI and prompt
                    restoreUIAfterCapture(canvas)
                    android.widget.Toast.makeText(this@ScrawlIMEService, "Grant screenshot permission, then commit again", android.widget.Toast.LENGTH_LONG).show()
                    val intent = Intent(this@ScrawlIMEService, ScreenshotActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    setCommittingUI(false)
                    commitJob = null
                    return@launch
                }

                // Service is ready, capture directly
                val captured = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    sendCaptureIntent(ssRegion.bounds, ssFile.uri, loc[0], loc[1]) { result ->
                        cont.resumeWith(Result.success(result))
                    }
                }
                restoreUIAfterCapture(canvas)
                if (captured) {
                    imageSuffix = "\n\n![[${ssFile.name}]]"
                } else {
                    Log.w("Scrawl", "Screenshot capture failed, committing text only")
                }
            }

            try {
                // If only screenshot, no strokes to HWR
                if (strokeList.isEmpty()) {
                    if (imageSuffix.isNotEmpty()) {
                        val prefix = if (needsLeadingSpace()) " " else ""
                        val committed = maybeAppendCommitNewline(prefix + imageSuffix.trimStart())
                        currentInputConnection?.commitText(committed, 1)
                        lastCommittedText = committed
                    }
                    success = true
                    return@launch
                }

                val hwr = HWREngineProvider.engine
                val bound = hwr.prepare(this@ScrawlIMEService)
                if (!bound) { Log.i("Scrawl", "HWR prepare failed"); return@launch }

                val lang = ScrawlSettings.getHwrLanguage(this@ScrawlIMEService)
                val lexicon = ScrawlSettings.getCustomLexicon(this@ScrawlIMEService)
                if (lexicon.isNotBlank()) hwr.compileLexicon(lang, lexicon)

                if (regions.isEmpty()) {
                    val rawText = hwr.recognizeStrokes(strokeList, w, h, lang)
                    if (rawText == OnyxHWREngine.LANG_NOT_AVAILABLE) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                this@ScrawlIMEService,
                                "Language \"$lang\" not installed — download it in Boox keyboard settings, or switch to en_US",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        return@launch
                    }
                    Log.i("Scrawl", "commit plain raw: $rawText")
                    val text = if (!rawText.isNullOrEmpty()) collapseLines(rawText, strokeList, w) else rawText
                    Log.i("Scrawl", "commit plain: $text")
                    if (!text.isNullOrEmpty()) {
                        val prefix = if (needsLeadingSpace()) " " else ""
                        val ts = recordingTimestamp()
                        val suffix = if (ts.isNotEmpty()) "\n\n" else ""
                        val committed = maybeAppendCommitNewline(ts + prefix + text + imageSuffix + suffix)
                        currentInputConnection?.commitText(committed, 1)
                        lastCommittedText = committed
                    } else if (imageSuffix.isNotEmpty()) {
                        val prefix = if (needsLeadingSpace()) " " else ""
                        val committed = maybeAppendCommitNewline(prefix + imageSuffix.trimStart())
                        currentInputConnection?.commitText(committed, 1)
                        lastCommittedText = committed
                    }
                } else {
                    val rawFullText = hwr.recognizeStrokes(strokeList, w, h, lang) ?: ""
                    if (rawFullText == OnyxHWREngine.LANG_NOT_AVAILABLE) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                this@ScrawlIMEService,
                                "Language \"$lang\" not installed — download it in Boox keyboard settings, or switch to en_US",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        return@launch
                    }
                    val fullText = collapseLines(rawFullText, strokeList, w)
                    Log.i("Scrawl", "commit fullText: '$fullText' (raw: '$rawFullText')")
                    if (fullText.isNotBlank()) {
                        val rowHeight = regions.maxOf { it.bounds.height() }
                        val sortedRegions = regions.sortedWith(compareBy(
                            { (it.bounds.centerY() / rowHeight).toInt() },
                            { it.bounds.left }
                        ))
                        val annotStrokeIndices = mutableSetOf<Int>()
                        val regionStrokeMap = mutableMapOf<Int, List<Stroke>>()
                        for ((idx, region) in sortedRegions.withIndex()) {
                            val overlapping = strokeList.filterIndexed { i, s ->
                                val strokeRect = RectF(s.left, s.top, s.right, s.bottom)
                                RectF.intersects(strokeRect, region.bounds).also { hit ->
                                    if (hit) annotStrokeIndices.add(i)
                                }
                            }
                            regionStrokeMap[idx] = overlapping
                        }

                        val nonAnnotStrokes = strokeList.filterIndexed { i, _ -> i !in annotStrokeIndices }
                        var result = fullText

                        if (nonAnnotStrokes.isNotEmpty()) {
                            val baseText = hwr.recognizeStrokes(nonAnnotStrokes, w, h, lang) ?: ""
                            Log.i("Scrawl", "commit baseText: '$baseText'")

                            val annotTexts = AnnotationTextProcessor.diffWords(baseText, fullText)
                            Log.i("Scrawl", "commit diff segments: $annotTexts (need ${sortedRegions.size})")

                            if (annotTexts.size == sortedRegions.size) {
                                result = AnnotationTextProcessor.applyAnnotations(
                                    fullText, annotTexts, sortedRegions.map { it.mode }
                                )
                            } else {
                                Log.i("Scrawl", "commit diff mismatch, falling back to per-annotation HWR")
                                result = fallbackAnnotate(sortedRegions, regionStrokeMap, fullText, w, h, lang)
                            }
                        } else {
                            result = fallbackAnnotate(sortedRegions, regionStrokeMap, fullText, w, h, lang)
                        }

                        val prefix = if (needsLeadingSpace()) " " else ""
                        val ts = recordingTimestamp()
                        val suffix = if (ts.isNotEmpty()) "\n\n" else ""
                        val committed = maybeAppendCommitNewline(ts + prefix + result + imageSuffix + suffix)
                        Log.i("Scrawl", "commit final: '$committed'")
                        currentInputConnection?.commitText(committed, 1)
                        lastCommittedText = committed
                    }
                }
                success = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // let cancellation propagate normally
            } catch (e: Exception) {
                Log.e("Scrawl", "commit failed: ${e.message}", e)
            } finally {
                commitJob = null
                setCommittingUI(false)
                if (success) {
                    canvas.clearAll()
                    if (audioRecorder?.isRecording == true) {
                        // Stay in write mode during recording for continuous timestamped input
                    } else {
                        canvasMode = CanvasMode.SELECT
                        canvas.visibility = View.INVISIBLE
                        canvas.deactivateRawDrawing()
                        rebuildBottomBarForMode()
                        updateOnOffButton()
                        window?.window?.decorView?.requestLayout()
                    }
                } else {
                    // Commit failed or was cancelled — keep strokes on canvas for retry
                    Log.i("Scrawl", "commit incomplete, preserving strokes for retry")
                }
            }
        }
    }

    private suspend fun fallbackAnnotate(
        regions: List<IMECanvasView.AnnotationRegion>,
        strokeMap: Map<Int, List<Stroke>>,
        fullText: String,
        w: Float, h: Float,
        lang: String = "en_US"
    ): String {
        var result = fullText
        for ((idx, region) in regions.withIndex()) {
            val strokes = strokeMap[idx] ?: continue
            if (strokes.isEmpty()) continue
            val rawText = (HWREngineProvider.engine.recognizeStrokes(strokes, w, h, lang) ?: "").trim()
            if (rawText.isBlank()) continue
            Log.i("Scrawl", "fallback annot ${region.mode}: raw='$rawText'")
            val match = AnnotationTextProcessor.findBestMatch(rawText, result)
            if (match != null) {
                val cleaned = match.trimEnd('.', ',', ';', ':', '!', '?')
                val trailing = match.removePrefix(cleaned)
                val wrapped = AnnotationTextProcessor.wrapText(region.mode, cleaned) + trailing
                result = result.replaceFirst(match, wrapped)
            }
        }
        return result
    }

    private fun undoLastStroke() {
        if (canvasMode == CanvasMode.SELECT && lastCommittedText != null) {
            val text = lastCommittedText!!
            currentInputConnection?.deleteSurroundingText(text.length, 0)
            lastCommittedText = null
            return
        }
        canvasView?.undoLastStroke()
    }

    private fun redoStroke() {
        canvasView?.redoStroke()
    }

    private fun onClearPressed() {
        val canvas = canvasView ?: return
        radialMenu?.dismiss()
        clearAnnotation()
        canvas.isEraserMode = false
        canvas.clearAll()
        setIconActive(eraserBtn, false, R.drawable.ic_ink_eraser, R.drawable.ic_ink_eraser)
        refreshToolbarButton(eraserBtn)
        updateOnOffButton()
        Log.i("ScrawlLayers", "clear button reset mode=$canvasMode eraser=${canvas.isEraserMode} annotation=$annotationMode")
    }

    private fun executePenButtonAction(action: PenButtonAction, x: Float, y: Float) {
        when (action) {
            PenButtonAction.NONE -> {}
            PenButtonAction.ERASER -> toggleEraser()
            PenButtonAction.WRITE_SELECT -> toggleOnOff()
            PenButtonAction.UNDO -> undoLastStroke()
            PenButtonAction.RADIAL_MENU -> showRadialMenu(x, y)
        }
    }

    private fun showRadialMenu(x: Float, y: Float) {
        Log.i("ScrawlLayers", "showRadialMenu requested mode=$canvasMode x=$x y=$y")
        if (canvasMode != CanvasMode.WRITE) return
        val menu = radialMenu ?: return
        if (menu.isShowing) {
            menu.dismiss()
            return
        }
        val eraser = canvasView?.isEraserMode == true
        val hasStrokes = (canvasView?.getStrokes()?.size ?: 0) > 0
        val items = listOf(
            RadialMenuView.Item(if (hasStrokes) R.drawable.ic_check else R.drawable.ic_edit, null, active = hasStrokes) { toggleOnOff() },
            RadialMenuView.Item(0, "[[", active = annotationMode == AnnotationMode.WikiLink) { toggleWikiLink() },
            RadialMenuView.Item(0, "#", active = annotationMode == AnnotationMode.Tag) { toggleTag() },
            RadialMenuView.Item(0, "H", active = headerCycle.contains(annotationMode)) { toggleHeader() },
            RadialMenuView.Item(R.drawable.ic_crop_free, null, active = annotationMode == AnnotationMode.Screenshot) { toggleScreenshotMode() },
            RadialMenuView.Item(R.drawable.ic_ink_eraser, null, active = eraser) { toggleEraser() },
            RadialMenuView.Item(R.drawable.ic_close, null) { onClearPressed() },
            RadialMenuView.Item(R.drawable.ic_undo, null) { undoLastStroke() },
            RadialMenuView.Item(R.drawable.ic_redo, null) { redoStroke() },
        )
        canvasView?.showAndroidUi(overlayVisible = true)
        menu.show(x, y, items.map { item ->
            item.copy(action = {
                item.action()
                // Tool changes may enable raw rendering again. Yield only after
                // the selected action has updated its Android UI state.
                canvasView?.showAndroidUi()
            })
        })
    }

    private fun toggleEraser() {
        val canvas = canvasView ?: return
        canvas.isEraserMode = !canvas.isEraserMode
        canvas.refreshAfterToolToggle()
        setIconActive(eraserBtn, canvas.isEraserMode, R.drawable.ic_ink_eraser, R.drawable.ic_ink_eraser)
        refreshToolbarButton(eraserBtn)
        Log.d("ScrawlPerf", "toggleEraser: ${canvas.isEraserMode}")
    }

    private fun refreshToolbarButton(btn: View?) {
        btn ?: return
        fun refresh(view: View) {
            view.invalidate()
            view.postInvalidate()
        }
        btn.post {
            refresh(btn)
            bottomBar?.let { refresh(it) }
            btn.postDelayed({
                refresh(btn)
                bottomBar?.let { refresh(it) }
            }, 80L)
        }
    }

    private fun sendBackspace() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun sendSpace() {
        currentInputConnection?.commitText(" ", 1)
    }

    private fun sendEnter() {
        currentInputConnection?.commitText("\n", 1)
    }

    private fun maybeAppendCommitNewline(text: String): String {
        if (!ScrawlSettings.getAppendNewlineOnCommit(this)) return text
        if (text.isEmpty() || text.endsWith("\n")) return text
        // Auto-append newline only when not inserting within a word
        if (isWithinWord()) return text
        // Don't append newline if cursor is mid-line or replacing selected text
        val ic = currentInputConnection
        if (ic != null) {
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) return text
            val after = ic.getTextAfterCursor(1, 0)
            if (!after.isNullOrEmpty() && after[0] != '\n') return text
        }
        return "$text\n"
    }

    private fun penLabel(): String {
        return when (canvasView?.currentPen ?: Pen.FOUNTAIN) {
            Pen.FOUNTAIN -> "Ftn"
            Pen.BALLPEN -> "Ball"
        }
    }

    private fun cyclePen() {
        val canvas = canvasView ?: return
        val pens = Pen.entries
        val idx = pens.indexOf(canvas.currentPen)
        canvas.currentPen = pens[(idx + 1) % pens.size]
        penBtn?.text = penLabel()
    }

    // ── Recording ──

    private fun toggleRecording() {
        val recorder = audioRecorder ?: return
        if (recorder.isRecording) {
            recorder.stop()
            updateRecordButton()
        } else {
            // Check permission before starting
            val hasPermission = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                val intent = Intent(this, PermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            }
            if (ScrawlSettings.getRecordingFolderUri(this) == null) {
                android.widget.Toast.makeText(this, "Set a recording folder in Scrawl settings", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            requestBatteryOptimizationExemption()
            if (recorder.start()) {
                updateRecordButton()
            }
        }
    }

    private fun updateRecordButton() {
        val btn = recordBtn ?: return
        val recording = audioRecorder?.isRecording == true
        btn.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_mic)
        val bg = GradientDrawable().apply {
            setColor(if (recording) 0xFFCC0000.toInt() else Color.WHITE)
            cornerRadius = dpToPx(10).toFloat()
        }
        btn.background = bg
        btn.setColorFilter(if (recording) Color.WHITE else Color.BLACK)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun recordingTimestamp(): String {
        val recorder = audioRecorder ?: return ""
        if (!recorder.isRecording) return ""
        return recorder.elapsedTimestamp() + " "
    }

    // ── Screenshot capture ──

    private fun toggleScreenshotMode() {
        Log.i("Scrawl", "toggleScreenshotMode: canvasMode=$canvasMode, annotationMode=$annotationMode")
        if (canvasMode != CanvasMode.WRITE) return
        if (annotationMode == AnnotationMode.Screenshot) {
            clearAnnotation()
        } else {
            annotationMode = AnnotationMode.Screenshot
            canvasView?.annotationMode = AnnotationMode.Screenshot
            canvasView?.invalidate()
            updateAnnotationButtons()
            updateOnOffButton()
        }
    }

    /** Capture the screenshot region and crop — called during commit. */
    private fun sendCaptureIntent(rect: RectF, outUri: android.net.Uri, canvasX: Int, canvasY: Int, callback: (Boolean) -> Unit) {
        ScreenshotService.captureCallback = callback
        val intent = Intent(this, ScreenshotService::class.java).apply {
            action = ScreenshotService.ACTION_CAPTURE
            putExtra(ScreenshotService.EXTRA_RECT_LEFT, rect.left)
            putExtra(ScreenshotService.EXTRA_RECT_TOP, rect.top)
            putExtra(ScreenshotService.EXTRA_RECT_RIGHT, rect.right)
            putExtra(ScreenshotService.EXTRA_RECT_BOTTOM, rect.bottom)
            putExtra(ScreenshotService.EXTRA_CANVAS_X, canvasX)
            putExtra(ScreenshotService.EXTRA_CANVAS_Y, canvasY)
            putExtra(ScreenshotService.EXTRA_OUTPUT_URI, outUri.toString())
        }
        startService(intent)
    }

    private fun restoreUIAfterCapture(canvas: IMECanvasView) {
        canvas.hideAnnotationOverlays = false
        canvas.invalidate()
        if (sidebarCollapsed) {
            collapsedHandle?.visibility = View.VISIBLE
        } else {
            topBar?.visibility = View.VISIBLE
            bottomBar?.visibility = View.VISIBLE
        }
    }

    private fun launchSettings() {
        val intent = Intent(this, ScrawlSettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // ── Collapsed handle ──

    @SuppressLint("ClickableViewAccessibility")
    private fun createCollapsedHandle(): View {
        val bg = GradientDrawable().apply {
            setColor(0xFFF5F5F5.toInt())
            cornerRadius = dpToPx(12).toFloat()
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = bg
            elevation = 2f
            setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(36),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginStart = dpToPx(4)
            }
        }

        val grip = ImageView(this).apply {
            setImageResource(R.drawable.ic_drag_handle)
            setColorFilter(0xFF999999.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(20)
            )
        }
        layout.addView(grip)

        val expandBtnBg = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dpToPx(8).toFloat()
        }
        val expandBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(Color.DKGRAY)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = expandBtnBg
            val pad = dpToPx(4)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(28), dpToPx(28)
            ).apply {
                topMargin = dpToPx(4)
            }
            setOnClickListener { expandSidebar() }
        }
        layout.addView(expandBtn)

        var dragStartY = 0f
        var dragStartTransY = 0f
        grip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    dragStartTransY = layout.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.translationY = dragStartTransY + (event.rawY - dragStartY)
                    true
                }
                else -> false
            }
        }

        return layout
    }

    private fun collapseSidebar() {
        sidebarCollapsed = true
        dismissHamburger()
        topBar?.visibility = View.GONE
        bottomBar?.visibility = View.GONE
        collapsedHandle?.visibility = View.VISIBLE
        window?.window?.decorView?.requestLayout()
    }

    private fun expandSidebar() {
        sidebarCollapsed = false
        dismissHamburger()
        topBar?.visibility = View.VISIBLE
        bottomBar?.visibility = View.VISIBLE
        collapsedHandle?.visibility = View.GONE
        collapsedHandle?.translationY = 0f
        window?.window?.decorView?.requestLayout()
    }

    private fun showImePicker() {
        @Suppress("DEPRECATION")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    // --- Bluetooth / hardware keyboard detection ---

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val hardKeyboardVisible =
            newConfig.keyboard == Configuration.KEYBOARD_QWERTY &&
            newConfig.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

        if (hardKeyboardVisible) {
            Log.i("Scrawl", "Hardware keyboard detected, showing IME picker")
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    private fun clampTranslationX(target: View, parent: View?, desired: Float): Float {
        parent ?: return desired
        val min = -target.left.toFloat()
        val max = (parent.width - target.width - target.left).toFloat()
        return desired.coerceIn(min, max)
    }

    private fun clampTranslationY(target: View, parent: View?, desired: Float): Float {
        parent ?: return desired
        val min = -target.top.toFloat()
        val max = (parent.height - target.height - target.top).toFloat()
        return desired.coerceIn(min, max)
    }

    // --- Lifecycle ---

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.i("ScrawlLayers", "input view start restarting=$restarting committing=$isCommitting")
        // Don't reset mode while a commit is in progress — apps like Boox Notes
        // can re-trigger onStartInputView mid-commit, which would rebuild the toolbar
        // while buttons are grayed out, leaving the UI frozen.
        if (isCommitting) return
        if (!restarting) {
            canvasView?.dropStash()
            canvasView?.restorePersistedStrokes()
        }
        val multiline = info.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (canvasView?.lastToolWasPen != false && multiline) enterWrite() else enterSelect()
        // Re-prepare HWR engine in case the service was killed between uses
        serviceScope.launch { HWREngineProvider.engine.prepare(this@ScrawlIMEService) }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Log.i("ScrawlLayers", "input view finish finishingInput=$finishingInput")
        radialMenu?.dismiss("input-view-finish")
        rootButtonState = 0
        canvasView?.deactivateRawDrawing()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        canvasView?.deactivateRawDrawing()
        unregisterReceiver(recordingStateReceiver)
        super.onDestroy()
        serviceScope.cancel()
        HWREngineProvider.release(this)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
