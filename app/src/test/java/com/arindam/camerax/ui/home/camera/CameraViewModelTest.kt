package com.arindam.camerax.ui.home.camera

import androidx.lifecycle.SavedStateHandle
import com.arindam.camerax.di.AppDispatchers
import com.arindam.camerax.di.cameraInteractors
import com.arindam.camerax.domain.model.CameraLens
import com.arindam.camerax.domain.model.CameraMode
import com.arindam.camerax.domain.model.CaptureAspect
import com.arindam.camerax.domain.model.CaptureSettings
import com.arindam.camerax.domain.model.DeviceCaptureFeatures
import com.arindam.camerax.domain.model.FlashMode
import com.arindam.camerax.domain.model.PipCorner
import com.arindam.camerax.testing.FakeCameraRepository
import com.arindam.camerax.testing.FakeDeviceFeaturesRepository
import com.arindam.camerax.testing.FakeMediaRepository
import com.arindam.camerax.testing.FakeSettingsRepository
import com.arindam.camerax.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val camera = FakeCameraRepository()
    private val media = FakeMediaRepository()
    private val settings = FakeSettingsRepository()
    private val features = FakeDeviceFeaturesRepository()

    private fun viewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        device: DeviceCaptureFeatures = DeviceCaptureFeatures(),
        initial: CameraUiState = CameraUiState()
    ): CameraViewModel {
        features.features = device
        return CameraViewModel(
            interactors = cameraInteractors(camera, media, settings, features),
            savedState = handle,
            dispatchers = AppDispatchers(
                main = mainDispatcherRule.dispatcher,
                default = mainDispatcherRule.dispatcher,
                io = mainDispatcherRule.dispatcher
            ),
            initialState = initial
        )
    }

    @Test
    fun visibleModes_onlyPhotoAndVideo() {
        val vm = viewModel(device = DeviceCaptureFeatures(dualPhotoSupported = true))
        assertEquals(
            listOf(CameraMode.PHOTO, CameraMode.VIDEO),
            vm.uiState.value.visibleModes
        )
    }

    @Test
    fun cycleFlash_updatesStateAndRepository() {
        val vm = viewModel(initial = CameraUiState(hasFlash = true))
        vm.cycleFlash()
        assertEquals(FlashMode.ON, vm.uiState.value.flash)
        assertEquals(FlashMode.ON, camera.lastFlash)
    }

    @Test
    fun consumeMessage_clearsBanner() {
        val vm = viewModel(initial = CameraUiState(message = "failed"))
        vm.consumeMessage()
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun savedState_restoresModeLensFlash() {
        val handle = SavedStateHandle(
            mapOf(
                "camera_mode" to CameraMode.VIDEO.name,
                "camera_lens" to "FRONT",
                "camera_flash" to FlashMode.TORCH.name
            )
        )
        val vm = viewModel(handle = handle, initial = CameraUiState(hasFlash = true))
        assertEquals(CameraMode.VIDEO, vm.uiState.value.mode)
        assertEquals(CameraLens.FRONT, vm.uiState.value.lens)
        assertEquals(FlashMode.TORCH, vm.uiState.value.flash)
    }

    @Test
    fun setMode_persistsToSavedState() {
        val handle = SavedStateHandle()
        val vm = viewModel(handle = handle)
        vm.setMode(CameraMode.VIDEO)
        assertEquals(CameraMode.VIDEO.name, handle["camera_mode"])
    }

    @Test
    fun probe_fallsBackToPhotoWhenSavedDualPhotoUnsupported() {
        val vm = viewModel(initial = CameraUiState(mode = CameraMode.DUAL_PHOTO))
        assertEquals(CameraMode.PHOTO, vm.uiState.value.mode)
        assertEquals(false, vm.uiState.value.visibleModes.contains(CameraMode.DUAL_PHOTO))
    }

    @Test
    fun setMode_hiddenDualPhotoIsIgnored() {
        val vm = viewModel()
        vm.setMode(CameraMode.DUAL_PHOTO)
        assertEquals(CameraMode.PHOTO, vm.uiState.value.mode)
    }

    @Test
    fun setMode_dualPhotoWhenSupported() {
        val vm = viewModel(device = DeviceCaptureFeatures(dualPhotoSupported = true))
        vm.setMode(CameraMode.DUAL_PHOTO)
        assertEquals(CameraMode.DUAL_PHOTO, vm.uiState.value.mode)
    }

    @Test
    fun toggleDualPhoto_entersWhenSupportedAndExitsBack() {
        val vm = viewModel(device = DeviceCaptureFeatures(dualPhotoSupported = true))
        vm.toggleDualPhoto()
        assertEquals(CameraMode.DUAL_PHOTO, vm.uiState.value.mode)
        vm.toggleDualPhoto()
        assertEquals(CameraMode.PHOTO, vm.uiState.value.mode)
    }

    @Test
    fun toggleDualPhoto_noOpWhenUnsupported() {
        val vm = viewModel(device = DeviceCaptureFeatures(dualPhotoSupported = false))
        vm.toggleDualPhoto()
        assertEquals(CameraMode.PHOTO, vm.uiState.value.mode)
    }

    @Test
    fun hasGalleryItems_falseUntilThumbnailLoads() {
        val vm = viewModel()
        assertEquals(false, vm.hasGalleryItems())
    }

    @Test
    fun hasGalleryItems_trueWhenLatestMediaExists() {
        media.add(File("shot.jpg"))
        val vm = viewModel()
        assertEquals(true, vm.hasGalleryItems())
    }

    @Test
    fun cycleTimer_advancesWhileIdle() {
        val vm = viewModel()
        vm.cycleTimer()
        assertEquals(3, vm.uiState.value.timer.seconds)
    }

    @Test
    fun gridEnabled_loadsFromSettings() {
        settings.settings = CaptureSettings(gridEnabled = true)
        val vm = viewModel()
        assertEquals(true, vm.uiState.value.gridEnabled)
    }

    @Test
    fun gridEnabled_defaultsOff() {
        val vm = viewModel()
        assertEquals(false, vm.uiState.value.gridEnabled)
    }

    @Test
    fun setPipCorner_updatesStateSessionAndPersist() {
        val vm = viewModel()
        vm.setPipCorner(PipCorner.BOTTOM_RIGHT)
        assertEquals(PipCorner.BOTTOM_RIGHT, vm.uiState.value.pipCorner)
        assertEquals(PipCorner.BOTTOM_RIGHT, camera.lastPipCorner)
        assertEquals(PipCorner.BOTTOM_RIGHT.prefValue, settings.savedPipCorner)
    }

    @Test
    fun pipCorner_loadsFromSettings() {
        settings.settings = CaptureSettings(pipCorner = PipCorner.BOTTOM_LEFT)
        val vm = viewModel()
        assertEquals(PipCorner.BOTTOM_LEFT, vm.uiState.value.pipCorner)
        assertEquals(PipCorner.BOTTOM_LEFT, camera.lastPipCorner)
    }

    @Test
    fun setPipScale_updatesStateSessionAndPersist() {
        val vm = viewModel()
        vm.setPipScale(1.4f)
        assertEquals(1.4f, vm.uiState.value.pipScale, 0.001f)
        assertEquals(1.4f, camera.lastPipScale!!, 0.001f)
        assertEquals("1.4", settings.savedPipScale)
    }

    @Test
    fun setPipScale_clampsOutOfRange() {
        val vm = viewModel()
        vm.setPipScale(5f)
        assertEquals(1.9f, vm.uiState.value.pipScale, 0.001f)
        vm.setPipScale(0.1f)
        assertEquals(0.55f, vm.uiState.value.pipScale, 0.001f)
    }

    @Test
    fun pipScale_loadsFromSettings() {
        settings.settings = CaptureSettings(pipScale = 1.3f)
        val vm = viewModel()
        assertEquals(1.3f, vm.uiState.value.pipScale, 0.001f)
        assertEquals(1.3f, camera.lastPipScale!!, 0.001f)
    }

    @Test
    fun updateMicrophonePermission_forcesMutedWhenDenied() {
        settings.settings = CaptureSettings(recordMuted = false)
        val vm = viewModel(initial = CameraUiState(mode = CameraMode.VIDEO, isMuted = false))
        vm.updateMicrophonePermission(granted = false)
        assertEquals(false, vm.uiState.value.microphonePermissionGranted)
        assertEquals(true, vm.uiState.value.isMuted)
    }

    @Test
    fun applyCaptureSettings_updatesAspectAndRebind() {
        val vm = viewModel()
        val before = vm.uiState.value.bindRevision
        vm.applyCaptureSettings(
            CaptureSettings(aspect = CaptureAspect.FULL)
        )
        assertEquals(CaptureAspect.FULL, vm.uiState.value.captureAspect)
        assertEquals(before + 1, vm.uiState.value.bindRevision)
    }

    @Test
    fun toggleMotionPhoto_blockedWhenUltraHdrOn() {
        settings.settings = CaptureSettings(ultraHdr = true)
        val vm = viewModel()
        assertTrue(vm.uiState.value.ultraHdr)
        vm.toggleMotionPhoto()
        assertFalse(vm.uiState.value.motionPhotoEnabled)
        assertEquals(
            com.arindam.camerax.R.string.motion_photo_disable_ultra_hdr,
            vm.uiState.value.messageRes
        )
    }

    @Test
    fun applyCaptureSettings_clearsMotionWhenUltraHdrEnabled() {
        val vm = viewModel(initial = CameraUiState(motionPhotoEnabled = true))
        vm.applyCaptureSettings(CaptureSettings(ultraHdr = true))
        assertFalse(vm.uiState.value.motionPhotoEnabled)
        assertTrue(vm.uiState.value.ultraHdr)
    }
}
