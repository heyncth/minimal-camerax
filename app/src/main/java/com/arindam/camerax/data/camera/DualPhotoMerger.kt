package com.arindam.camerax.data.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.withClip
import com.arindam.camerax.domain.model.PipCorner
import java.io.File

/**
 * Data: PiP merge for Dual photo stills — primary full frame with the secondary drawn as a
 * rounded inset using the same NDC layout as [DualComposition]. Both inputs go through
 * [StillImageExif.decodeSampled] so EXIF rotation / front mirror land in the pixels; output is
 * written upright ([android.media.ExifInterface.ORIENTATION_NORMAL]).
 * Blocking decode + draw — call off the main thread (CameraSession runs this on cameraExecutor).
 */
internal object DualPhotoMerger {

    /**
     * Writes [output] as the merged JPEG and returns it, or null when either input
     * fails to decode (caller then falls back to a single-camera result).
     * [corner] is the shared Dual PiP inset corner; [scale] is the shared pinch-zoom factor
     * (both come from the draggable preview outline).
     */
    fun merge(
        primary: File,
        secondary: File,
        output: File,
        corner: PipCorner = PipCorner.TOP_LEFT,
        scale: Float = 1f
    ): File? {
        val decodedPrimary = StillImageExif.decodeSampled(primary, MAX_EDGE) ?: return null
        // Decoded bitmaps are immutable — copy before drawing the inset onto them.
        val base = decodedPrimary.copy(Bitmap.Config.ARGB_8888, true)
        decodedPrimary.recycle()
        if (base == null) return null
        val inset = StillImageExif.decodeSampled(secondary, MAX_EDGE)
        if (inset == null) {
            base.recycle()
            return null
        }
        try {
            drawInset(base, inset, corner, scale)
            StillImageExif.writeJpeg(base, output, quality = 95)
            return output
        } finally {
            inset.recycle()
            base.recycle()
        }
    }

    private fun drawInset(base: Bitmap, inset: Bitmap, corner: PipCorner, scale: Float) {
        val rect = DualComposition.pipRect(
            baseWidth = base.width.toFloat(),
            baseHeight = base.height.toFloat(),
            insetAspect = inset.height.toFloat() / inset.width,
            corner = corner,
            scale = scale
        )
        val radius = minOf(rect.width(), rect.height()) * DualComposition.PIP_RADIUS_FRACTION

        val canvas = Canvas(base)
        val draw = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.withClip(clip) {
            drawBitmap(inset, null, rect, draw)
        }

        val stroke = (minOf(base.width, base.height) * DualComposition.BORDER_FRACTION)
            .coerceAtLeast(2f)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Color.argb(0xD0, 0xFF, 0xFF, 0xFF)
        }
        // Inset the stroke so half of it stays inside the rounded clip edge.
        val strokeRect = RectF(rect)
        strokeRect.inset(stroke / 2f, stroke / 2f)
        canvas.drawRoundRect(strokeRect, radius, radius, border)
    }

    private const val MAX_EDGE = 8192
}
