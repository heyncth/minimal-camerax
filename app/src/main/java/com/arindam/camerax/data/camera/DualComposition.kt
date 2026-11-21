package com.arindam.camerax.data.camera

import android.graphics.RectF
import com.arindam.camerax.domain.model.PipCorner

/**
 * Data: Dual composition layout — one NDC source for the Dual video PiP stream
 * ([androidx.camera.core.CompositionSettings]), the Dual photo merge, and the preview outline.
 *
 * NDC: origin at frame center, +X right, +Y up, range [-1, 1]. Offset is applied after scale.
 * Composition uses the PreviewView [androidx.camera.core.ViewPort], so NDC maps to the full view.
 * The inset corner comes from the shared [PipCorner] state (drag the outline to move it).
 */
internal object DualComposition {
    /** Inset center magnitude per axis (top-left matches iPhone Dual Capture). */
    private const val PIP_OFFSET_MAGNITUDE = 0.66f
    const val PIP_SCALE = 0.28f
    /** Corner radius as a fraction of min(pipWidth, pipHeight). */
    const val PIP_RADIUS_FRACTION = 0.08f
    /** White border stroke as a fraction of min(baseWidth, baseHeight). */
    const val BORDER_FRACTION = 0.004f

    /** Inset center in NDC for [corner] (+X right, +Y up). */
    fun pipCenterNdc(corner: PipCorner): Pair<Float, Float> = when (corner) {
        PipCorner.TOP_LEFT -> -PIP_OFFSET_MAGNITUDE to PIP_OFFSET_MAGNITUDE
        PipCorner.TOP_RIGHT -> PIP_OFFSET_MAGNITUDE to PIP_OFFSET_MAGNITUDE
        PipCorner.BOTTOM_LEFT -> -PIP_OFFSET_MAGNITUDE to -PIP_OFFSET_MAGNITUDE
        PipCorner.BOTTOM_RIGHT -> PIP_OFFSET_MAGNITUDE to -PIP_OFFSET_MAGNITUDE
    }

    /**
     * Pixel-space inset rect over a [baseWidth] x [baseHeight] frame.
     * [insetAspect] = insetHeight / insetWidth (the merger passes the real secondary aspect;
     * the preview outline assumes the secondary matches the base frame).
     * Clamped inside the base so odd sensor aspects never clip the inset off-frame.
     */
    fun pipRect(
        baseWidth: Float,
        baseHeight: Float,
        insetAspect: Float,
        corner: PipCorner = PipCorner.TOP_LEFT,
        scale: Float = 1f
    ): RectF {
        val pipWidth = (baseWidth * PIP_SCALE * scale.coerceIn(0.55f, 1.9f)).coerceAtLeast(1f)
        val pipHeight = (pipWidth * insetAspect).coerceAtLeast(1f)
        val (ndcX, ndcY) = pipCenterNdc(corner)
        val centerX = baseWidth * (0.5f + ndcX / 2f)
        val centerY = baseHeight * (0.5f - ndcY / 2f)
        val left = (centerX - pipWidth / 2f).coerceIn(0f, (baseWidth - pipWidth).coerceAtLeast(0f))
        val top = (centerY - pipHeight / 2f).coerceIn(0f, (baseHeight - pipHeight).coerceAtLeast(0f))
        return RectF(left, top, left + pipWidth, top + pipHeight)
    }

    /** Nearest inset corner to a pixel-space point (drag-and-snap for the preview outline). */
    fun nearestCorner(x: Float, y: Float, baseWidth: Float, baseHeight: Float): PipCorner {
        if (baseWidth <= 0f || baseHeight <= 0f) return PipCorner.TOP_LEFT
        val left = ((x / baseWidth) * 2f - 1f) < 0f
        val top = (1f - (y / baseHeight) * 2f) >= 0f
        return when {
            left && top -> PipCorner.TOP_LEFT
            !left && top -> PipCorner.TOP_RIGHT
            left && !top -> PipCorner.BOTTOM_LEFT
            else -> PipCorner.BOTTOM_RIGHT
        }
    }
}
