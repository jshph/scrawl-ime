package com.scrawl.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.MemoryFile
import android.os.ParcelFileDescriptor
import android.util.Log
import com.onyx.android.sdk.hwr.service.HWRInputArgs
import com.onyx.android.sdk.hwr.service.HWROutputArgs
import com.onyx.android.sdk.hwr.service.HWROutputCallback
import com.onyx.android.sdk.hwr.service.IHWRService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "OnyxHWR"

object OnyxHWREngine {

    /** Returned by recognizeStrokes when the language pack isn't installed on the device. */
    const val LANG_NOT_AVAILABLE = "\u0000__LANG_NOT_AVAILABLE__"

    @Volatile private var service: IHWRService? = null
    @Volatile private var bound = false
    @Volatile private var connectLatch = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IHWRService.Stub.asInterface(binder)
            bound = true
            Log.i(TAG, "HWR service connected")
            connectLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            initialized = false
            Log.w(TAG, "HWR service disconnected")
        }
    }

    /** ksync versions below this lack the content field in HWRInputArgs. */
    private const val KSYNC_CONTENT_FIELD_MIN_VERSION = 32000L

    suspend fun bindAndAwait(context: Context, timeoutMs: Long = 2000): Boolean {
        if (bound && service != null) return true

        connectLatch = CountDownLatch(1)
        appContext = context.applicationContext

        // Detect ksync version to set HWRInputArgs format
        try {
            val ksyncInfo = context.packageManager.getPackageInfo("com.onyx.android.ksync", 0)
            val ksyncVersion = if (android.os.Build.VERSION.SDK_INT >= 28) {
                ksyncInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION") ksyncInfo.versionCode.toLong()
            }
            HWRInputArgs.includeContentField = ksyncVersion >= KSYNC_CONTENT_FIELD_MIN_VERSION
            Log.i(TAG, "ksync version=$ksyncVersion includeContentField=${HWRInputArgs.includeContentField}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not detect ksync version: ${e.message}")
        }

        val intent = Intent().apply {
            component = ComponentName(
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KHwrService"
            )
        }
        val bindStarted = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind: ${e.message}")
            return false
        }
        if (!bindStarted) return false

        return withContext(Dispatchers.IO) {
            connectLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } && service != null
    }

    fun unbind(context: Context) {
        if (bound) {
            try { context.unbindService(connection) } catch (_: Exception) {}
            bound = false
            service = null
            initialized = false
        }
    }

    @Volatile private var initialized = false
    @Volatile private var currentLang: String = "en_US"
    @Volatile private var currentLexicon: String = ""

    /** Returns true if the recognizer activated successfully for the given language. */
    private suspend fun ensureInitialized(svc: IHWRService, viewWidth: Float, viewHeight: Float, language: String): Boolean {
        if (initialized && language == currentLang) return true

        // Language changed — force re-init
        if (language != currentLang) initialized = false

        val inputArgs = HWRInputArgs().apply {
            lang = language
            contentType = "Text"
            recognizerType = "MS_ON_SCREEN"
            this.viewWidth = viewWidth
            this.viewHeight = viewHeight
            isTextEnable = true
        }

        suspendCancellableCoroutine { cont ->
            try {
                svc.init(inputArgs, true, object : HWROutputCallback.Stub() {
                    override fun read(args: HWROutputArgs?) {
                        initialized = args?.recognizerActivated == true
                        currentLang = language
                        Log.i(TAG, "HWR init lang=$language activated=$initialized")
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "HWR init IPC failed: ${e.message}")
                if (cont.isActive) cont.resume(Unit)
            }
        }
        return initialized
    }

    suspend fun compileLexicon(language: String, lexicon: String): Boolean {
        val svc = service ?: return false
        if (lexicon.isBlank()) return true
        if (lexicon == currentLexicon && language == currentLang) return true

        return suspendCancellableCoroutine { cont ->
            try {
                svc.compileRecognizeText(lexicon, language, object : HWROutputCallback.Stub() {
                    override fun read(args: HWROutputArgs?) {
                        val ok = args?.compileSuccess == true
                        if (ok) currentLexicon = lexicon
                        Log.i(TAG, "Lexicon compile success=$ok")
                        if (cont.isActive) cont.resume(ok)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Lexicon compile IPC failed: ${e.message}")
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    suspend fun recognizeStrokes(
        strokes: List<Stroke>,
        viewWidth: Float,
        viewHeight: Float,
        language: String = "en_US"
    ): String? {
        val svc = service ?: run {
            Log.w(TAG, "recognizeStrokes: service is null (not bound)")
            return null
        }
        if (strokes.isEmpty()) return ""

        val sampledStrokes = strokes.map { stroke ->
            stroke.copy(points = HwrPointSampler.sample(stroke.points))
        }
        val totalPoints = strokes.sumOf { it.points.size }
        val sampledPoints = sampledStrokes.sumOf { it.points.size }
        Log.i(TAG, "recognizeStrokes: strokes=${strokes.size} points=$totalPoints sampledPoints=$sampledPoints lang=$language view=${viewWidth}x${viewHeight}")

        val initT0 = System.nanoTime()
        val langReady = ensureInitialized(svc, viewWidth, viewHeight, language)
        val initMs = (System.nanoTime() - initT0) / 1_000_000
        Log.i(TAG, "recognizeStrokes: init took ${initMs}ms activated=$langReady")
        if (!langReady) {
            Log.w(TAG, "Recognizer not activated for lang=$language — language pack may not be installed")
            return LANG_NOT_AVAILABLE
        }

        val protoBytes = buildProtobuf(sampledStrokes, viewWidth, viewHeight, language)
        Log.d(TAG, "recognizeStrokes: protobuf=${protoBytes.size} bytes")
        val pfd = createMemoryFilePfd(protoBytes) ?: return null

        val recognizeT0 = System.nanoTime()
        return try {
            val result = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine { cont ->
                    try {
                        svc.batchRecognize(pfd, object : HWROutputCallback.Stub() {
                            override fun read(args: HWROutputArgs?) {
                                try {
                                    val errorJson = args?.hwrResult
                                    if (!errorJson.isNullOrBlank()) {
                                        Log.e(TAG, "HWR error: ${errorJson.take(300)}")
                                        cont.resume("")
                                        return
                                    }
                                    val resultPfd = args?.pfd
                                    if (resultPfd == null) {
                                        Log.w(TAG, "recognizeStrokes: result PFD is null")
                                        cont.resume("")
                                        return
                                    }
                                    val json = readPfdAsString(resultPfd)
                                    resultPfd.close()
                                    Log.d(TAG, "recognizeStrokes: raw JSON (${json.length} chars): ${json.take(500)}")
                                    cont.resume(parseHwrResult(json))
                                } catch (e: Exception) {
                                    if (cont.isActive) cont.resumeWithException(e)
                                }
                            }
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "batchRecognize IPC failed: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
            val recognizeMs = (System.nanoTime() - recognizeT0) / 1_000_000
            Log.i(TAG, "recognizeStrokes: completed in ${recognizeMs}ms → \"${result?.take(100)}\"${if ((result?.length ?: 0) > 100) "…" else ""}")
            result
        } finally {
            pfd.close()
        }
    }

    private fun readPfdAsString(pfd: ParcelFileDescriptor): String {
        val input = java.io.FileInputStream(pfd.fileDescriptor)
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var n: Int
        while (input.read(buf).also { n = it } != -1) {
            baos.write(buf, 0, n)
        }
        return baos.toString("UTF-8")
    }

    private fun parseHwrResult(json: String): String {
        return try {
            val obj = JSONObject(json)
            if (obj.has("exception")) return ""
            val result = obj.optJSONObject("result")
            if (result != null) return result.optString("label", "")
            obj.optString("label", "")
        } catch (_: Exception) { "" }
    }

    // --- Protobuf encoding ---

    private fun buildProtobuf(
        strokes: List<Stroke>, viewWidth: Float, viewHeight: Float, language: String = "en_US"
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeTag(out, 1, 2); writeString(out, language)
        writeTag(out, 2, 2); writeString(out, "Text")
        writeTag(out, 4, 2); writeString(out, "MS_ON_SCREEN")
        writeTag(out, 5, 5); writeFixed32(out, viewWidth)
        writeTag(out, 6, 5); writeFixed32(out, viewHeight)
        writeTag(out, 10, 0); writeVarint(out, 1)

        for (stroke in strokes) {
            val points = stroke.points
            if (points.isEmpty()) continue
            val strokeEpoch = stroke.createdAt.time
            for ((i, point) in points.withIndex()) {
                val eventType = when (i) {
                    0 -> 0
                    points.size - 1 -> 2
                    else -> 1
                }
                val timestamp = if (point.dt != null) {
                    strokeEpoch + point.dt.toLong()
                } else {
                    strokeEpoch + (i * 10L)
                }
                val pointerBytes = encodePointerProto(
                    point.x, point.y, timestamp, point.pressure, 0, eventType, 0
                )
                writeTag(out, 15, 2); writeBytes(out, pointerBytes)
            }
        }
        return out.toByteArray()
    }

    private fun encodePointerProto(
        x: Float, y: Float, t: Long, f: Float,
        pointerId: Int, eventType: Int, pointerType: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeTag(out, 1, 5); writeFixed32(out, x)
        writeTag(out, 2, 5); writeFixed32(out, y)
        writeTag(out, 3, 0); writeVarint(out, (t shl 1) xor (t shr 63))
        writeTag(out, 4, 5); writeFixed32(out, f)
        writeTag(out, 5, 0); writeVarint(out, ((pointerId shl 1) xor (pointerId shr 31)).toLong())
        writeTag(out, 6, 0); writeVarint(out, eventType.toLong())
        writeTag(out, 7, 0); writeVarint(out, pointerType.toLong())
        return out.toByteArray()
    }

    private fun writeTag(out: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        writeVarint(out, ((fieldNumber shl 3) or wireType).toLong())
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    private fun writeFixed32(out: ByteArrayOutputStream, value: Float) {
        val bits = java.lang.Float.floatToIntBits(value)
        out.write(bits and 0xFF)
        out.write((bits shr 8) and 0xFF)
        out.write((bits shr 16) and 0xFF)
        out.write((bits shr 24) and 0xFF)
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeBytes(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private var appContext: Context? = null

    private fun createMemoryFilePfd(data: ByteArray): ParcelFileDescriptor? {
        return try {
            val memFile = MemoryFile("hwr_input", data.size)
            memFile.writeBytes(data, 0, 0, data.size)
            val method = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
            method.isAccessible = true
            val fd = method.invoke(memFile) as FileDescriptor
            val pfd = ParcelFileDescriptor.dup(fd)
            memFile.close()
            pfd
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MemoryFile PFD: ${e.message}")
            null
        }
    }
}
