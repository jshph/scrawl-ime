package com.scrawl.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderInvalidationPolicyTest {
    @Test
    fun `canvas invalidation redraws the model and invalidates the view`() {
        val steps = RenderInvalidationPolicy.steps(
            RenderInvalidationRequest(
                backend = RenderBackend.Canvas,
                useRawPenControl = true,
            )
        )

        assertEquals(
            listOf(
                RenderInvalidationStep.RedrawModel,
                RenderInvalidationStep.InvalidateView,
            ),
            steps,
        )
    }

    @Test
    fun `backend setting parser always resolves to canvas`() {
        assertEquals(RenderBackend.Canvas, RenderBackend.fromSetting("canvas"))
        assertEquals(RenderBackend.Canvas, RenderBackend.fromSetting("app_pen"))
        assertEquals(RenderBackend.Canvas, RenderBackend.fromSetting("sf"))
        assertEquals(RenderBackend.Canvas, RenderBackend.fromSetting("epd_direct"))
        assertEquals(RenderBackend.Canvas, RenderBackend.fromSetting("something_else"))
    }
}
