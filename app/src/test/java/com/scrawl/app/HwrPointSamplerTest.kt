package com.scrawl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HwrPointSamplerTest {
    @Test fun denseRawStrokeKeepsEndpointsAndOnePointPerEightMilliseconds() {
        val points = (0..100).map { time ->
            StrokePoint(time.toFloat(), 0f, dt = time.toUShort())
        }

        val sampled = HwrPointSampler.sample(points)

        assertEquals(listOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80, 88, 96, 100),
            sampled.map { it.dt!!.toInt() })
    }

    @Test fun sparseAndShortStrokesArePreserved() {
        val sparse = listOf(0, 10, 20, 30).map { StrokePoint(it.toFloat(), 0f, dt = it.toUShort()) }
        val short = (0..10).map { StrokePoint(it.toFloat(), 0f, dt = it.toUShort()) }

        assertSame(sparse, HwrPointSampler.sample(sparse))
        assertSame(short, HwrPointSampler.sample(short))
    }

    @Test fun missingTimingDoesNotDiscardGeometry() {
        val points = (0..30).map { time ->
            StrokePoint(time.toFloat(), 0f, dt = if (time == 15) null else time.toUShort())
        }

        assertSame(points, HwrPointSampler.sample(points))
    }
}
