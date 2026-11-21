package com.arindam.camerax.data.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DualPhotoMergerTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun merge_writesUprightJpegWithPrimaryDimensions() {
        val primary = folder.newFile("primary.jpg")
        val secondary = folder.newFile("secondary.jpg")
        val output = folder.newFile("merged.jpg")
        writeJpeg(primary, width = 64, height = 48, color = Color.RED)
        writeJpeg(secondary, width = 32, height = 32, color = Color.BLUE)

        val result = DualPhotoMerger.merge(primary, secondary, output)

        assertNotNull(result)
        assertTrue(output.exists())
        assertTrue(output.length() > 0)
        assertEquals(ExifInterface.ORIENTATION_NORMAL, StillImageExif.readOrientation(output))
        val decoded = StillImageExif.decodeSampled(output, maxEdge = 128)
        assertNotNull(decoded)
        decoded!!.use {
            assertEquals(64, it.width)
            assertEquals(48, it.height)
        }
    }

    @Test
    fun merge_undecodableSecondary_returnsNull() {
        val primary = folder.newFile("primary.jpg")
        val secondary = folder.newFile("secondary.jpg")
        val output = folder.newFile("merged.jpg")
        writeJpeg(primary, width = 32, height = 24, color = Color.GREEN)
        secondary.writeText("not a jpeg")

        assertNull(DualPhotoMerger.merge(primary, secondary, output))
    }

    private fun writeJpeg(file: File, width: Int, height: Int, color: Int) {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            FileOutputStream(file).use { stream ->
                require(compress(Bitmap.CompressFormat.JPEG, 95, stream))
            }
            recycle()
        }
    }

    private inline fun Bitmap.use(block: (Bitmap) -> Unit) {
        try {
            block(this)
        } finally {
            recycle()
        }
    }
}
