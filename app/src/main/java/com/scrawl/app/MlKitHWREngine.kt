package com.scrawl.app

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google ML Kit Digital Ink Recognition — portable HWR fallback.
 *
 * Used on devices where the Onyx KHwrService is not available (e.g. Nova Air 2
 * on older firmware, or non-Boox Android devices). Downloads the recognition
 * model on first use (~20-30MB per language).
 *
 * This engine is NEVER initialized on devices where OnyxHWREngine succeeds,
 * so the model is never downloaded on devices that don't need it.
 *
 * Ported from Notable's InboxSyncEngine, adapted for Scrawl's Stroke model
 * and IME keyboard context (shorter text, single lines or small paragraphs).
 */
object MlKitHWREngine : HWREngine {

    private const val TAG = "MlKitHWR"

    // Below this stroke count, recognize all strokes in a single pass.
    // Above it, segment into lines (better for long multi-line text).
    // IME keyboard input is typically < 50 strokes per commit.
    private const val SINGLE_PASS_THRESHOLD = 80

    private var currentLangTag: String? = null
    private var model: DigitalInkRecognitionModel? = null
    private var recognizer: com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer? = null

    override suspend fun prepare(context: Context): Boolean {
        return try {
            ensureModel("en-US")
            true
        } catch (e: Exception) {
            Log.e(TAG, "prepare failed: ${e.message}")
            false
        }
    }

    override suspend fun recognizeStrokes(
        strokes: List<Stroke>,
        viewWidth: Float,
        viewHeight: Float,
        language: String
    ): String? {
        if (strokes.isEmpty()) return ""

        val langTag = onyxLangToTag(language)
        ensureModel(langTag)

        val rec = recognizer ?: return null

        return try {
            // Use full canvas dimensions as WritingArea so ML Kit understands
            // the spatial scale (matches what the Onyx engine receives).
            val writingArea = WritingArea(viewWidth, viewHeight)

            // For IME input (compact canvas, short text), try recognizing all
            // strokes as a single unit first — this preserves cross-line context
            // that per-line segmentation loses. Only fall back to per-line
            // recognition for large stroke counts (full-page writing).
            if (strokes.size <= SINGLE_PASS_THRESHOLD) {
                val ink = buildInk(strokes)
                val context = RecognitionContext.builder()
                    .setPreContext("")
                    .setWritingArea(writingArea)
                    .build()

                val text = suspendCancellableCoroutine<String> { cont ->
                    rec.recognize(ink, context)
                        .addOnSuccessListener { result ->
                            val best = result.candidates.firstOrNull()?.text ?: ""
                            Log.d(TAG, "Single-pass: ${strokes.size} strokes → '${best.take(80)}'")
                            cont.resume(best)
                        }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
                text
            } else {
                // Many strokes — segment into lines for better accuracy on
                // long multi-line text (ported from Notable's InboxSyncEngine)
                val lines = segmentIntoLines(strokes)
                Log.d(TAG, "Segmented ${strokes.size} strokes into ${lines.size} lines")

                val recognizedLines = mutableListOf<String>()
                var preContext = ""

                for (lineStrokes in lines) {
                    val ink = buildInk(lineStrokes)
                    val context = RecognitionContext.builder()
                        .setPreContext(preContext)
                        .setWritingArea(writingArea)
                        .build()

                    val text = suspendCancellableCoroutine<String> { cont ->
                        rec.recognize(ink, context)
                            .addOnSuccessListener { result ->
                                cont.resume(result.candidates.firstOrNull()?.text ?: "")
                            }
                            .addOnFailureListener { cont.resumeWithException(it) }
                    }

                    if (text.isNotBlank()) {
                        recognizedLines.add(text)
                        preContext = text.takeLast(20)
                    }
                }

                recognizedLines.joinToString("\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "recognizeStrokes failed: ${e.message}")
            null
        }
    }

    override suspend fun compileLexicon(language: String, lexicon: String): Boolean {
        // ML Kit doesn't support custom lexicons — silently succeed
        return true
    }

    override fun release(context: Context) {
        recognizer?.close()
        recognizer = null
        model = null
        currentLangTag = null
    }

    // --- Model management ---

    private suspend fun ensureModel(langTag: String) {
        if (langTag == currentLangTag && recognizer != null) return

        recognizer?.close()

        val identifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(langTag)
            ?: throw IllegalStateException("No ML Kit model for language: $langTag")
        val m = DigitalInkRecognitionModel.builder(identifier).build()

        val manager = RemoteModelManager.getInstance()
        val isDownloaded = suspendCancellableCoroutine<Boolean> { cont ->
            manager.isModelDownloaded(m)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

        if (!isDownloaded) {
            Log.i(TAG, "Downloading ML Kit model for $langTag...")
            suspendCancellableCoroutine<Void?> { cont ->
                manager.download(m, DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(null) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            Log.i(TAG, "Model downloaded for $langTag")
        }

        recognizer = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(m).build()
        )
        model = m
        currentLangTag = langTag
    }

    // --- Stroke → Ink conversion ---

    private fun buildInk(strokes: List<Stroke>): Ink {
        val inkBuilder = Ink.builder()
        for (stroke in strokes) {
            val strokeBuilder = Ink.Stroke.builder()
            val baseTime = stroke.createdAt.time
            for ((i, point) in stroke.points.withIndex()) {
                val t = if (point.dt != null) {
                    baseTime + point.dt.toLong()
                } else {
                    baseTime + (i * 10L)
                }
                strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, t))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }
        return inkBuilder.build()
    }

    /**
     * Group strokes into horizontal lines by clustering on vertical midpoint.
     * Ported from Notable's InboxSyncEngine.segmentIntoLines.
     */
    private fun segmentIntoLines(strokes: List<Stroke>): List<List<Stroke>> {
        if (strokes.isEmpty()) return emptyList()

        val sorted = strokes.sortedWith(
            compareBy<Stroke> { (it.top + it.bottom) / 2f }.thenBy { it.left }
        )

        val strokeHeights = sorted.map { it.bottom - it.top }.filter { it > 0 }.sorted()
        val medianHeight = if (strokeHeights.isNotEmpty()) {
            strokeHeights[strokeHeights.size / 2]
        } else {
            50f
        }
        val lineGapThreshold = medianHeight * 0.75f

        val lines = mutableListOf<MutableList<Stroke>>()
        var currentLine = mutableListOf(sorted.first())
        var currentLineCenter = (sorted.first().top + sorted.first().bottom) / 2f

        for (stroke in sorted.drop(1)) {
            val strokeCenter = (stroke.top + stroke.bottom) / 2f
            if (strokeCenter - currentLineCenter > lineGapThreshold) {
                lines.add(currentLine)
                currentLine = mutableListOf(stroke)
                currentLineCenter = strokeCenter
            } else {
                currentLine.add(stroke)
                currentLineCenter = currentLine
                    .map { (it.top + it.bottom) / 2f }.average().toFloat()
            }
        }
        lines.add(currentLine)

        return lines.map { line ->
            line.sortedWith(compareBy<Stroke> { it.createdAt.time }.thenBy { it.left })
        }
    }

    /**
     * Map Onyx HWR language codes (e.g. "en_US") to ML Kit BCP-47 tags (e.g. "en-US").
     */
    private fun onyxLangToTag(onyxLang: String): String {
        return onyxLang.replace('_', '-')
    }
}
