package com.arindam.camerax.data.camera

import com.arindam.camerax.domain.model.PipCorner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

// Robolectric: pipRect returns an android.graphics.RectF (stubbed to zeros on plain JVM).
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DualCompositionTest {

    @Test
    fun pipCenterNdc_mapsCornersWithSignedAxes() {
        val (topLeftX, topLeftY) = DualComposition.pipCenterNdc(PipCorner.TOP_LEFT)
        val (topRightX, topRightY) = DualComposition.pipCenterNdc(PipCorner.TOP_RIGHT)
        val (bottomLeftX, bottomLeftY) = DualComposition.pipCenterNdc(PipCorner.BOTTOM_LEFT)
        val (bottomRightX, bottomRightY) = DualComposition.pipCenterNdc(PipCorner.BOTTOM_RIGHT)
        assertTrue(topLeftX < 0f && topLeftY > 0f)
        assertTrue(topRightX > 0f && topRightY > 0f)
        assertTrue(bottomLeftX < 0f && bottomLeftY < 0f)
        assertTrue(bottomRightX > 0f && bottomRightY < 0f)
    }

    @Test
    fun pipRect_staysInsideBaseForEveryCorner() {
        PipCorner.entries.forEach { corner ->
            val rect = DualComposition.pipRect(
                baseWidth = 1000f,
                baseHeight = 2000f,
                insetAspect = 1f,
                corner = corner
            )
            assertTrue(rect.left >= 0f)
            assertTrue(rect.top >= 0f)
            assertTrue(rect.right <= 1000f)
            assertTrue(rect.bottom <= 2000f)
        }
    }

    @Test
    fun pipRect_scalesWithPinchFactorAndStaysInside() {
        val base = DualComposition.pipRect(
            baseWidth = 1000f,
            baseHeight = 2000f,
            insetAspect = 1f,
            corner = PipCorner.TOP_LEFT
        )
        val bigger = DualComposition.pipRect(
            baseWidth = 1000f,
            baseHeight = 2000f,
            insetAspect = 1f,
            corner = PipCorner.TOP_LEFT,
            scale = 1.5f
        )
        val smaller = DualComposition.pipRect(
            baseWidth = 1000f,
            baseHeight = 2000f,
            insetAspect = 1f,
            corner = PipCorner.BOTTOM_RIGHT,
            scale = 0.7f
        )
        assertEquals(1.5f * base.width(), bigger.width(), 0.001f)
        assertEquals(0.7f * base.width(), smaller.width(), 0.001f)
        listOf(bigger, smaller).forEach { rect ->
            assertTrue(rect.left >= 0f)
            assertTrue(rect.top >= 0f)
            assertTrue(rect.right <= 1000f)
            assertTrue(rect.bottom <= 2000f)
        }
    }

    @Test
    fun pipRect_clampsScaleToSupportedRange() {
        val huge = DualComposition.pipRect(
            baseWidth = 1000f,
            baseHeight = 2000f,
            insetAspect = 1f,
            scale = 10f
        )
        val tiny = DualComposition.pipRect(
            baseWidth = 1000f,
            baseHeight = 2000f,
            insetAspect = 1f,
            scale = 0f
        )
        val minExpected = DualComposition.PIP_SCALE * 1000f * 0.55f - 0.001f
        assertTrue(
            "huge.right=${huge.right} huge.width=${huge.width()} tiny.width=${tiny.width()} min=$minExpected",
            tiny.width() >= minExpected
        )
        assertTrue(huge.right <= 1000f)
    }

    @Test
    fun nearestCorner_snapsByCenterQuadrant() {
        assertEquals(
            PipCorner.TOP_LEFT,
            DualComposition.nearestCorner(100f, 100f, 1000f, 1000f)
        )
        assertEquals(
            PipCorner.TOP_RIGHT,
            DualComposition.nearestCorner(900f, 100f, 1000f, 1000f)
        )
        assertEquals(
            PipCorner.BOTTOM_LEFT,
            DualComposition.nearestCorner(100f, 900f, 1000f, 1000f)
        )
        assertEquals(
            PipCorner.BOTTOM_RIGHT,
            DualComposition.nearestCorner(900f, 900f, 1000f, 1000f)
        )
    }
}
