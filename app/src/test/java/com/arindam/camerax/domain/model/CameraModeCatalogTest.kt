package com.arindam.camerax.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModeCatalogTest {

    @Test
    fun visibleModes_arePhotoAndVideoOnly() {
        val modes = CameraModeCatalog.visibleModes(dualPhotoSupported = false)
        assertEquals(listOf(CameraMode.PHOTO, CameraMode.VIDEO), modes)
    }

    @Test
    fun visibleModes_neverIncludesDualPhoto_evenWhenSupported() {
        val modes = CameraModeCatalog.visibleModes(dualPhotoSupported = true)
        assertFalse(modes.contains(CameraMode.DUAL_PHOTO))
    }

    @Test
    fun photoProfile_allowsLiveNightAndExtensions() {
        val profile = CameraModeCatalog.profile(CameraMode.PHOTO)
        assertEquals(CaptureAction.STILL, profile.captureAction)
        assertTrue(profile.showsFlash)
        assertTrue(profile.showsTimer)
        assertTrue(profile.showsMotion)
        assertTrue(profile.allowsMotionPhoto)
        assertTrue(profile.allowsNightAuto)
        assertTrue(profile.allowsExtensions)
        assertFalse(profile.bindDualPhoto)
    }

    @Test
    fun videoProfile_isVideoCapture() {
        val profile = CameraModeCatalog.profile(CameraMode.VIDEO)
        assertEquals(CaptureAction.VIDEO, profile.captureAction)
        assertTrue(profile.allowsPersistentRecording)
        assertTrue(profile.allowsFps60)
        assertFalse(profile.allowsExtensions)
        assertFalse(profile.allowsMotionPhoto)
        assertFalse(profile.allowsNightAuto)
    }

    @Test
    fun dualPhotoProfile_isStillCaptureWithoutExtensionsOrMotionPhoto() {
        val profile = CameraModeCatalog.profile(CameraMode.DUAL_PHOTO)
        assertEquals(CaptureAction.STILL, profile.captureAction)
        assertTrue(profile.bindDualPhoto)
        assertTrue(profile.rebindOnEnter)
        assertTrue(profile.clearsSessionExtras)
        assertTrue(profile.showsFlip)
        assertTrue(profile.showsPip)
        assertFalse(profile.showsZoom)
        assertFalse(profile.allowsExtensions)
        assertFalse(profile.allowsMotionPhoto)
    }

    @Test
    fun dualPhoto_availabilityRequiresFrontAndBackCameras() {
        val profile = CameraModeCatalog.profile(CameraMode.DUAL_PHOTO)
        assertTrue(profile.isAvailable(dualPhotoSupported = true))
        assertFalse(profile.isAvailable(dualPhotoSupported = false))
    }

    @Test
    fun resolve_keepsDualPhotoWhenSupported() {
        val resolved = CameraModeCatalog.resolve(
            mode = CameraMode.DUAL_PHOTO,
            dualPhotoSupported = true
        )
        assertEquals(CameraMode.DUAL_PHOTO, resolved)
    }

    @Test
    fun resolve_fallsBackFromDualPhotoToPhoto() {
        val resolved = CameraModeCatalog.resolve(
            mode = CameraMode.DUAL_PHOTO,
            dualPhotoSupported = false
        )
        assertEquals(CameraMode.PHOTO, resolved)
    }

    @Test
    fun profile_existsForEveryMode() {
        CameraMode.entries.forEach { mode ->
            assertEquals(mode, CameraModeCatalog.profile(mode).mode)
        }
    }

    @Test
    fun captureAspect_fromPrefDefaultsToRatio43() {
        assertEquals(CaptureAspect.RATIO_4_3, CaptureAspect.fromPref(null))
        assertEquals(CaptureAspect.FULL, CaptureAspect.fromPref("full"))
    }

    @Test
    fun videoQuality_fromPrefDefaultsToFhd() {
        assertEquals(VideoQuality.FHD, VideoQuality.fromPref("unknown"))
        assertEquals(VideoQuality.UHD, VideoQuality.fromPref("uhd"))
    }

    @Test
    fun videoHdrRange_fromPrefDefaultsToSdr() {
        assertEquals(VideoHdrRange.SDR, VideoHdrRange.fromPref(null))
        assertEquals(VideoHdrRange.HLG10, VideoHdrRange.fromPref("hlg10"))
    }

    @Test
    fun slowMotionRate_forFrameRatesIncludesAuto() {
        val rates = SlowMotionRate.forFrameRates(listOf(120, 240))
        assertEquals(SlowMotionRate.AUTO, rates.first())
        assertTrue(rates.contains(SlowMotionRate.FPS_120))
        assertTrue(rates.contains(SlowMotionRate.FPS_240))
        assertFalse(rates.contains(SlowMotionRate.FPS_960))
    }

    @Test
    fun slowMotionRate_fromPrefDefaultsToAuto() {
        assertEquals(SlowMotionRate.AUTO, SlowMotionRate.fromPref(null))
        assertEquals(SlowMotionRate.FPS_120, SlowMotionRate.fromPref("120"))
    }

    @Test
    fun cameraLens_toggleFlipsFrontAndBack() {
        assertEquals(CameraLens.FRONT, CameraLens.BACK.toggle())
        assertEquals(CameraLens.BACK, CameraLens.FRONT.toggle())
    }

    @Test
    fun flashMode_nextCycles() {
        assertEquals(FlashMode.ON, FlashMode.OFF.next())
        assertEquals(FlashMode.OFF, FlashMode.TORCH.next())
    }
}
