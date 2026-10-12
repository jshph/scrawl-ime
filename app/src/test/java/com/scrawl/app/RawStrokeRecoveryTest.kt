package com.scrawl.app

import org.junit.Assert.*
import org.junit.Test

class RawStrokeRecoveryTest {
    private fun point(x: Float, y: Float, time: Int, pressure: Float = 1f) =
        StrokePoint(x, y, pressure, time.toUShort())

    private val path = listOf(point(0f, 0f, 0), point(20f, 10f, 8), point(80f, 40f, 16))
    private val end = point(80f, 40f, 20, 0f)

    @Test fun repairsExactSdkReplayWithoutLosingPenUp() {
        assertEquals(path + end, recoverRepeatedRawStroke(path + path + end))
    }

    @Test fun preservesLegitimateClosedShape() {
        val closed = path + point(10f, 5f, 24) + point(0f, 0f, 32)
        assertSame(closed, recoverRepeatedRawStroke(closed))
    }

    @Test fun preservesRevisitedGeometryAtLaterTimes() {
        val twice = path + path.map { it.copy(dt = (it.dt!!.toInt() + 24).toUShort()) } + end.copy(dt = 60u.toUShort())
        assertSame(twice, recoverRepeatedRawStroke(twice))
    }

    @Test fun timestampResetAloneDoesNotAuthorizeRepair() {
        val different = path + path.map { it.copy(x = it.x + 100f) } + end
        assertSame(different, recoverRepeatedRawStroke(different))
    }

    @Test fun requiresMatchingPressureAndCompleteSignature() {
        val altered = path + path.map { it.copy(pressure = 0.5f) } + end
        assertSame(altered, recoverRepeatedRawStroke(altered))
        val partial = path + path.take(2)
        assertSame(partial, recoverRepeatedRawStroke(partial))
        val noTimes = (path + path + end).map { it.copy(dt = null) }
        assertSame(noTimes, recoverRepeatedRawStroke(noTimes))
    }
}
