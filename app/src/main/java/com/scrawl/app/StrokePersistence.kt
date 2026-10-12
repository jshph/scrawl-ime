package com.scrawl.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date

object StrokePersistence {
    private const val TAG = "StrokePersistence"
    private const val FILE_NAME = "pending_strokes.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun save(context: Context, strokes: List<Stroke>, annotations: List<IMECanvasView.AnnotationRegion>) {
        try {
            val root = JSONObject()

            val strokesArr = JSONArray()
            for (s in strokes) {
                val sObj = JSONObject()
                sObj.put("size", s.size.toDouble())
                sObj.put("pen", s.pen.name)
                sObj.put("color", s.color)
                sObj.put("top", s.top.toDouble())
                sObj.put("bottom", s.bottom.toDouble())
                sObj.put("left", s.left.toDouble())
                sObj.put("right", s.right.toDouble())
                sObj.put("createdAt", s.createdAt.time)
                val ptsArr = JSONArray()
                for (p in s.points) {
                    val pObj = JSONObject()
                    pObj.put("x", p.x.toDouble())
                    pObj.put("y", p.y.toDouble())
                    pObj.put("pressure", p.pressure.toDouble())
                    if (p.dt != null) pObj.put("dt", p.dt.toInt())
                    ptsArr.put(pObj)
                }
                sObj.put("points", ptsArr)
                strokesArr.put(sObj)
            }
            root.put("strokes", strokesArr)

            val annsArr = JSONArray()
            for (a in annotations) {
                val aObj = JSONObject()
                aObj.put("left", a.bounds.left.toDouble())
                aObj.put("top", a.bounds.top.toDouble())
                aObj.put("right", a.bounds.right.toDouble())
                aObj.put("bottom", a.bounds.bottom.toDouble())
                aObj.put("mode", a.mode.name)
                annsArr.put(aObj)
            }
            root.put("annotations", annsArr)

            file(context).writeText(root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save strokes: ${e.message}")
        }
    }

    data class SavedData(val strokes: List<Stroke>, val annotations: List<IMECanvasView.AnnotationRegion>)

    fun load(context: Context): SavedData? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            val root = JSONObject(f.readText())

            val strokesArr = root.getJSONArray("strokes")
            val strokes = mutableListOf<Stroke>()
            for (i in 0 until strokesArr.length()) {
                val sObj = strokesArr.getJSONObject(i)
                val ptsArr = sObj.getJSONArray("points")
                val points = mutableListOf<StrokePoint>()
                for (j in 0 until ptsArr.length()) {
                    val pObj = ptsArr.getJSONObject(j)
                    points.add(StrokePoint(
                        x = pObj.getDouble("x").toFloat(),
                        y = pObj.getDouble("y").toFloat(),
                        pressure = pObj.getDouble("pressure").toFloat(),
                        dt = if (pObj.has("dt")) pObj.getInt("dt").toUShort() else null
                    ))
                }
                val recoveredPoints = recoverRepeatedRawStroke(points)
                if (recoveredPoints !== points) {
                    Log.i(TAG, "Recovered repeated raw batch stroke=$i points=${points.size}->${recoveredPoints.size}")
                }
                strokes.add(Stroke(
                    size = sObj.getDouble("size").toFloat(),
                    pen = Pen.valueOf(sObj.getString("pen")),
                    color = sObj.getInt("color"),
                    top = sObj.getDouble("top").toFloat(),
                    bottom = sObj.getDouble("bottom").toFloat(),
                    left = sObj.getDouble("left").toFloat(),
                    right = sObj.getDouble("right").toFloat(),
                    points = recoveredPoints,
                    createdAt = Date(sObj.getLong("createdAt"))
                ))
            }

            val annsArr = root.optJSONArray("annotations")
            val annotations = mutableListOf<IMECanvasView.AnnotationRegion>()
            if (annsArr != null) {
                for (i in 0 until annsArr.length()) {
                    val aObj = annsArr.getJSONObject(i)
                    annotations.add(IMECanvasView.AnnotationRegion(
                        bounds = android.graphics.RectF(
                            aObj.getDouble("left").toFloat(),
                            aObj.getDouble("top").toFloat(),
                            aObj.getDouble("right").toFloat(),
                            aObj.getDouble("bottom").toFloat()
                        ),
                        mode = AnnotationMode.valueOf(aObj.getString("mode"))
                    ))
                }
            }

            if (strokes.isEmpty() && annotations.isEmpty()) null
            else SavedData(strokes, annotations)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load strokes: ${e.message}")
            null
        }
    }

    fun clear(context: Context) {
        try { file(context).delete() } catch (_: Exception) {}
    }
}
