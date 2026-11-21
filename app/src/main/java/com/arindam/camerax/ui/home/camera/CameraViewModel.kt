package com.arindam.camerax.ui.home.camera

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.media.MediaActionSound
import android.os.Build
import android.view.animation.PathInterpolator
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arindam.camerax.R
import com.arindam.camerax.data.camera.AppleLivePhotoWriter
import com.arindam.camerax.data.camera.MotionPhotoMuxer
import com.arindam.camerax.data.camera.PreviewViewHost
import com.arindam.camerax.di.AppDispatchers
import com.arindam.camerax.di.CameraInteractors
import com.arindam.camerax.domain.model.LastCameraSession
import com.arindam.camerax.data.media.MediaPublishException
import com.arindam.camerax.data.media.mediaFileInfo
import com.arindam.camerax.domain.model.CameraBindConfig
import com.arindam.camerax.domain.model.CameraExtension
import com.arindam.camerax.domain.model.CameraLens
import com.arindam.camerax.domain.model.CameraMode
import com.arindam.camerax.domain.model.CameraModeCatalog
import com.arindam.camerax.domain.model.CaptureAction
import com.arindam.camerax.domain.model.CaptureSettings
import com.arindam.camerax.domain.model.EffectMode
import com.arindam.camerax.domain.model.ExposurePriority
import com.arindam.camerax.domain.model.FlashMode
import com.arindam.camerax.domain.model.LowLightBoost
import com.arindam.camerax.domain.model.NightScene
import com.arindam.camerax.domain.model.PhysicalZoom
import com.arindam.camerax.domain.model.PipCorner
import com.arindam.camerax.domain.model.profile
import com.arindam.camerax.domain.model.RecordingEvent
import com.arindam.camerax.domain.model.StillFormat
import com.arindam.camerax.util.commons.Constants
import com.arindam.camerax.util.commons.Constants.UI.ANIMATION_FAST_MILLIS
import com.arindam.camerax.util.commons.Constants.UI.ANIMATION_SLOW_MILLIS
import com.arindam.camerax.util.coroutines.ShareWhileSubscribed
import com.arindam.camerax.util.log.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.exp
import kotlin.math.ln

/**
 * Presentation: live-feed UI state. Calls [CameraInteractors] only — never [CameraSession].
 * Increment [CameraUiState.bindRevision] when a Settings or mode change must rebind.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class CameraViewModel(
    private val interactors: CameraInteractors,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val dispatchers: AppDispatchers = AppDispatchers(),
    initialState: CameraUiState = CameraUiState()
) : ViewModel() {

    private val bindMutex = Mutex()
    private val _uiState = MutableStateFlow(initialState)
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()
    private val _externalCaptureReady = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val externalCaptureReady: SharedFlow<File> = _externalCaptureReady.asSharedFlow()

    private var outputDirectory: File? = null
    private var countdownJob: Job? = null
    private var focusJob: Job? = null
    private var zoomAnimator: ValueAnimator? = null
    private var zoomGestureAnchor = 1f
    private var pendingVideoFile: File? = null
    private var discardPendingVideo = false
    private var launchIntentApplied = false
    private var externalCapture = ExternalCaptureRequest()
    private var manualExtension = false
    private var captureConfirmEnabled = false
    private var flipWhileRecordingEnabled = false
    private var recordMutedByDefault = false
    private var dualDisabled = false
    private val shutterSound: MediaActionSound by lazy {
        MediaActionSound().apply {
            load(MediaActionSound.SHUTTER_CLICK)
            load(MediaActionSound.START_VIDEO_RECORDING)
            load(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }

    init {
        restoreChrome()
        applyCaptureSettings(interactors.loadCaptureSettings())
        persistChrome()
        viewModelScope.launch(dispatchers.io) {
            setOutputDirectory(interactors.picturesDirectory())
        }
        viewModelScope.launch {
            val features = withContext(dispatchers.default) {
                interactors.probeDeviceFeatures()
            }
            _uiState.update { state ->
                val resolvedMode = CameraModeCatalog.resolve(
                    mode = state.mode,
                    dualPhotoSupported = features.dualPhotoSupported
                )
                state.copy(
                    slowMotionSupported = features.slowMotion.available,
                    concurrentSupported = features.concurrent,
                    dualPhotoSupported = features.dualPhotoSupported,
                    videoFps60Supported = features.videoFps60,
                    mode = resolvedMode,
                    deviceFeatures = features
                )
            }
            persistChrome()
        }
        interactors.observeNightScene()
            .debounce(800)
            .onEach { scene ->
                _uiState.update { it.copy(nightScene = scene) }
                applyAutoNight(scene)
            }
            .launchIn(viewModelScope)
        interactors.observeLowLightBoost()
            .onEach { boost ->
                _uiState.update { it.copy(lowLightBoostActive = boost == LowLightBoost.ACTIVE) }
            }
            .launchIn(viewModelScope)
        interactors.observeRecording()
            .shareIn(viewModelScope, ShareWhileSubscribed, replay = 0)
            .onEach { event -> handleRecordEvent(event) }
            .launchIn(viewModelScope)
    }

    fun setOutputDirectory(directory: File) {
        outputDirectory = directory
        updateMotionRolling()
        viewModelScope.launch { refreshThumbnail() }
    }

    fun syncHost() {
        setOutputDirectory(interactors.picturesDirectory())
        applyCaptureSettings(interactors.loadCaptureSettings())
        updateMotionRolling()
    }

    fun updateMicrophonePermission(granted: Boolean) {
        _uiState.update { state ->
            val resolvedMuted = when {
                !granted -> true
                state.isRecording -> state.isMuted
                else -> recordMutedByDefault
            }
            state.copy(
                microphonePermissionGranted = granted,
                isMuted = resolvedMuted
            )
        }
    }

    fun picturesDirectory(): File = outputDirectory ?: interactors.picturesDirectory()

    fun hasGalleryItems(): Boolean = _uiState.value.thumbnail != null

    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        viewModelScope.launch {
            bindMutex.withLock {
                try {
                    val state = _uiState.value
                    val profile = state.mode.profile()
                    val result = interactors.bindCamera(
                        host = PreviewViewHost(lifecycleOwner, previewView),
                        config = CameraBindConfig(
                            lens = state.lens,
                            flash = state.flash,
                            extension = if (profile.allowsExtensions) {
                                state.extension
                            } else {
                                CameraExtension.NONE
                            },
                            effect = EffectMode.NONE,
                            cameraId = state.cameraId,
                            captureAspect = state.captureAspect,
                            videoQuality = state.videoQuality,
                            videoHdrRange = state.videoHdrRange,
                            slowMotion = false,
                            slowMotionQuality = state.slowMotionQuality,
                            slowMotionRate = state.slowMotionRate,
                            videoStabilization = state.videoStabilization,
                            ultraHdr = state.ultraHdr,
                            rawCapture = state.rawCapture,
                            rawFullSensor = state.rawFullSensor,
                            lowLightBoost = state.lowLightBoost,
                            retainRecording = flipWhileRecordingEnabled &&
                                state.isRecording &&
                                !state.motionCapturing &&
                                profile.allowsPersistentRecording,
                            concurrent = false,
                            dualPhoto = profile.bindDualPhoto,
                            videoFps60 = state.videoFps60 && profile.allowsFps60,
                            frontMirror = state.frontMirror
                        )
                    )
                    _uiState.update {
                        val dualPhotoSupported =
                            result.dualPhotoSupported || it.dualPhotoSupported
                        val resolved = CameraModeCatalog.resolve(
                            mode = it.mode,
                            dualPhotoSupported = dualPhotoSupported
                        )
                        val modeChanged = resolved != it.mode
                        it.copy(
                            hasFlash = result.hasFlash,
                            minZoom = result.minZoom,
                            maxZoom = result.maxZoom,
                            zoomRatio = result.zoomRatio,
                            supportedExtensions = result.supportedExtensions,
                            ultraHdrEnabled = result.ultraHdrEnabled,
                            stillFormat = result.stillFormat,
                            exposureLimits = result.exposureLimits,
                            iso = result.exposureLimits.isoMin.coerceAtLeast(100)
                                .coerceAtMost(result.exposureLimits.isoMax),
                            shutterNanos = 16_666_667L.coerceIn(
                                result.exposureLimits.shutterMinNanos,
                                result.exposureLimits.shutterMaxNanos
                            ),
                            exposureCompensation = if (result.exposureLimits.evSupported) {
                                it.exposureCompensation.coerceIn(
                                    result.exposureLimits.evMin,
                                    result.exposureLimits.evMax
                                )
                            } else {
                                0
                            },
                            physicalZooms = result.physicalZooms,
                            cameraId = result.boundCameraId,
                            slowMotionSupported = result.slowMotionSupported ||
                                it.slowMotionSupported ||
                                result.slowMotionFps > 0,
                            slowMotionFps = result.slowMotionFps,
                            videoStabilizationActive = result.videoStabilizationActive,
                            lowLightBoostSupported = result.lowLightBoostSupported,
                            videoHdrBound = result.videoHdrRange,
                            dualPhotoSupported = dualPhotoSupported,
                            videoFps60Supported = result.videoFps60Supported || it.videoFps60Supported,
                            videoFps60Active = result.videoFps60Active,
                            lastSession = LastCameraSession(
                                mode = resolved,
                                bound = result.session
                            ),
                            mode = resolved,
                            bindRevision = if (modeChanged) it.bindRevision + 1 else it.bindRevision,
                            message = null
                        )
                    }
                    interactors.setExposure(
                        _uiState.value.exposurePriority,
                        _uiState.value.iso,
                        _uiState.value.shutterNanos
                    )
                    interactors.setExposureCompensation(_uiState.value.exposureCompensation)
                } catch (error: Exception) {
                    Logger.error(TAG, "Bind failed: ${error.message}")
                    val state = _uiState.value
                    when {
                        state.mode.profile().bindDualPhoto -> {
                            _uiState.update {
                                it.copy(
                                    mode = CameraMode.PHOTO,
                                    bindRevision = it.bindRevision + 1,
                                    message = error.message ?: "Dual photo unavailable"
                                )
                            }
                            persistChrome()
                        }
                        state.lens == CameraLens.FRONT -> {
                            _uiState.update {
                                it.copy(
                                    lens = CameraLens.BACK,
                                    cameraId = null,
                                    bindRevision = it.bindRevision + 1,
                                    message = "Front camera unavailable"
                                )
                            }
                        }
                        else -> {
                            _uiState.update {
                                it.copy(message = error.message ?: "Unable to start camera")
                            }
                        }
                    }
                }
            }
        }
    }

    fun applyLaunchIntent(intent: Intent) {
        if (launchIntentApplied) return
        launchIntentApplied = true
        externalCapture = ExternalCaptureRequest.from(intent)
        when (externalCapture.kind) {
            ExternalCaptureKind.IMAGE_CAPTURE,
            ExternalCaptureKind.OPEN_PHOTO -> _uiState.update {
                it.copy(
                    mode = CameraMode.PHOTO,
                    lockCaptureMode = externalCapture.returnsResult
                )
            }

            ExternalCaptureKind.VIDEO_CAPTURE,
            ExternalCaptureKind.OPEN_VIDEO -> _uiState.update {
                it.copy(
                    mode = CameraMode.VIDEO,
                    lockCaptureMode = externalCapture.returnsResult
                )
            }

            ExternalCaptureKind.MOTION_PHOTO -> _uiState.update {
                val dropUltraHdr = it.ultraHdr
                it.copy(
                    mode = CameraMode.PHOTO,
                    motionPhotoEnabled = true,
                    ultraHdr = false,
                    lockCaptureMode = true,
                    bindRevision = if (dropUltraHdr) it.bindRevision + 1 else it.bindRevision
                )
            }

            ExternalCaptureKind.NONE -> Unit
        }
        persistChrome()
    }

    fun updateTargetRotation(rotation: Int) {
        interactors.setTargetRotation(rotation)
    }

    fun onHostStopped() {
        if (_uiState.value.isRecording || _uiState.value.motionCapturing) stopRecording()
    }

    /** Finalize an in-progress video or LIVE capture (system-back Save). */
    fun saveActiveCapture() {
        val state = _uiState.value
        if (state.isRecording || state.motionCapturing) {
            discardPendingVideo = false
            stopRecording()
        }
    }

    /** Drop an in-progress video or LIVE capture without publishing (system-back Discard). */
    fun discardActiveCapture() {
        val state = _uiState.value
        if (state.isRecording || state.motionCapturing) {
            discardPendingVideo = true
            stopRecording()
        }
    }

    fun setMode(mode: CameraMode) {
        if (_uiState.value.isRecording || _uiState.value.motionCapturing || _uiState.value.lockCaptureMode) return
        val state = _uiState.value
        val profile = mode.profile()
        if (!profile.isAvailable(state.dualPhotoSupported)) return
        countdownJob?.cancel()
        val previous = state.mode.profile()
        val clearExtension = (profile.captureAction == CaptureAction.VIDEO ||
            profile.clearsSessionExtras) &&
            state.extension != CameraExtension.NONE
        val rebind = previous.rebindOnEnter || profile.rebindOnEnter || clearExtension
        _uiState.update {
            it.copy(
                mode = mode,
                countdownRemaining = null,
                extension = if (!profile.allowsExtensions ||
                    clearExtension ||
                    profile.clearsSessionExtras
                ) {
                    CameraExtension.NONE
                } else {
                    it.extension
                },
                autoNightActive = if (profile.clearsSessionExtras) false else it.autoNightActive,
                motionPhotoEnabled = if (profile.clearsSessionExtras) false else it.motionPhotoEnabled,
                effectFrame = null,
                bindRevision = if (rebind) it.bindRevision + 1 else it.bindRevision
            )
        }
        persistChrome()
        updateMotionRolling()
    }

    fun toggleLens() {
        val state = _uiState.value
        val profile = state.mode.profile()
        if (!profile.showsFlip) return
        if (state.motionCapturing) return
        if (state.isRecording &&
            (!flipWhileRecordingEnabled || !profile.allowsPersistentRecording)
        ) return
        _uiState.update {
            it.copy(
                lens = it.lens.toggle(),
                cameraId = null,
                bindRevision = it.bindRevision + 1
            )
        }
        persistChrome()
    }

    /** Photo-header chip: Dual Photo on/off via the hidden DUAL_PHOTO mode. */
    fun toggleDualPhoto() {
        val state = _uiState.value
        if (!state.showsDualPhotoToggle) return
        setMode(
            if (state.mode == CameraMode.DUAL_PHOTO) {
                CameraMode.PHOTO
            } else {
                CameraMode.DUAL_PHOTO
            }
        )
    }

    /**
     * Drag-and-snap result: moves the Dual PiP inset. Persists silently; Dual video
     * composition is fixed at bind, so bump [CameraUiState.bindRevision] to rebind.
     */
    fun setPipCorner(corner: PipCorner) {
        if (_uiState.value.pipCorner == corner) return
        _uiState.update { it.copy(pipCorner = corner) }
        interactors.setPipCorner(corner)
        interactors.savePipCorner(corner.prefValue)
    }

    /**
     * Pinch-zoom of the Dual PiP inset: moves scale into the session (merge + composition)
     * and persists silently. Dual video composition is fixed at bind, so bump
     * [CameraUiState.bindRevision] to rebind.
     */
    fun setPipScale(scale: Float) {
        val clamped = scale.coerceIn(0.55f, 1.9f)
        if (_uiState.value.pipScale == clamped) return
        _uiState.update { it.copy(pipScale = clamped) }
        interactors.setPipScale(clamped)
        interactors.savePipScale(clamped.toString())
    }

    fun cycleFlash() {
        if (!_uiState.value.hasFlash) return
        val next = _uiState.value.flash.next()
        _uiState.update { it.copy(flash = next) }
        interactors.setFlash(next)
        persistChrome()
    }

    fun cycleTimer() {
        if (_uiState.value.isRecording) return
        _uiState.update { it.copy(timer = it.timer.next()) }
    }

    fun setZoom(ratio: Float) {
        cancelZoomAnimation()
        val state = _uiState.value
        val target = ratio.coerceIn(state.minZoom, state.maxZoom)
        val start = state.zoomRatio
        if (kotlin.math.abs(start - target) < 0.01f) {
            applyDigitalZoom(target)
            return
        }
        zoomAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 280
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { animation ->
                applyDigitalZoom(animation.animatedValue as Float)
            }
            start()
        }
    }

    fun beginZoomGesture() {
        cancelZoomAnimation()
        zoomGestureAnchor = _uiState.value.zoomRatio
    }

    fun zoomByPinch(scale: Float) {
        applyDigitalZoom(zoomGestureAnchor * scale)
    }

    fun zoomByDrag(deltaY: Float, viewportHeight: Float) {
        if (viewportHeight <= 0f) return
        val state = _uiState.value
        val min = state.minZoom.coerceAtLeast(0.1f)
        val max = state.maxZoom.coerceAtLeast(min + 0.01f)
        val span = ln(max) - ln(min)
        val logAnchor = ln(zoomGestureAnchor.coerceIn(min, max))
        val travel = (-deltaY / viewportHeight).coerceIn(-1.4f, 1.4f)
        applyDigitalZoom(exp((logAnchor + travel * span).coerceIn(ln(min), ln(max))))
    }

    private fun matchingPhysicalCamera(ratio: Float): PhysicalZoom? {
        val state = _uiState.value
        if (ratio >= (state.minZoom - 0.05f) && ratio <= (state.maxZoom + 0.05f)) return null
        return state.physicalZooms.minByOrNull {
            kotlin.math.abs(it.label - ratio)
        }?.takeIf {
            kotlin.math.abs(it.label - ratio) < 0.12f &&
                it.cameraId != state.cameraId &&
                !state.isRecording
        }
    }

    private fun switchPhysicalCamera(physical: PhysicalZoom) {
        _uiState.update {
            it.copy(
                cameraId = physical.cameraId,
                zoomRatio = physical.label,
                bindRevision = it.bindRevision + 1
            )
        }
    }

    private fun applyDigitalZoom(ratio: Float) {
        interactors.setZoom(ratio)?.let { zoom ->
            _uiState.update {
                it.copy(zoomRatio = zoom.ratio, minZoom = zoom.min, maxZoom = zoom.max)
            }
        }
    }

    private fun cancelZoomAnimation() {
        zoomAnimator?.cancel()
        zoomAnimator = null
    }

    fun tapToFocus(previewView: PreviewView, offset: Offset) {
        interactors.tapToFocus(offset.x, offset.y)
        focusJob?.cancel()
        _uiState.update { it.copy(focusPoint = offset) }
        focusJob = viewModelScope.launch {
            delay(900)
            _uiState.update { it.copy(focusPoint = null) }
        }
    }

    fun setExtension(extension: CameraExtension) {
        if (_uiState.value.isRecording || _uiState.value.motionCapturing) return
        manualExtension = true
        _uiState.update {
            it.copy(
                extension = extension,
                autoNightActive = false,
                bindRevision = it.bindRevision + 1
            )
        }
    }

    /**
     * Motion pre-roll: the session keeps a warm rolling clip only while Photo mode has
     * Motion on. Skipped mid-capture (that clip is already the rolling one).
     */
    private fun updateMotionRolling() {
        if (_uiState.value.motionCapturing) return
        val state = _uiState.value
        interactors.setMotionRolling(
            state.motionPhotoEnabled &&
                state.mode == CameraMode.PHOTO &&
                !state.isRecording
        )
    }

    fun toggleMotionPhoto() {
        val state = _uiState.value
        if (state.isRecording || state.motionCapturing ||
            state.lockCaptureMode || state.rawCapture ||
            !state.mode.profile().allowsMotionPhoto
        ) return
        val enabled = !_uiState.value.motionPhotoEnabled
        if (enabled && state.ultraHdr) {
            _uiState.update {
                it.copy(message = null, messageRes = R.string.motion_photo_disable_ultra_hdr)
            }
            return
        }
        val dropExtension = enabled && _uiState.value.extension != CameraExtension.NONE
        if (dropExtension) manualExtension = false
        _uiState.update {
            it.copy(
                motionPhotoEnabled = enabled,
                extension = if (dropExtension) CameraExtension.NONE else it.extension,
                autoNightActive = if (dropExtension) false else it.autoNightActive,
                bindRevision = if (dropExtension) it.bindRevision + 1 else it.bindRevision
            )
        }
        updateMotionRolling()
    }

    fun setExposurePriority(priority: ExposurePriority) {
        _uiState.update { it.copy(exposurePriority = priority) }
        interactors.setExposure(priority, _uiState.value.iso, _uiState.value.shutterNanos)
    }

    fun setIso(iso: Int) {
        val limits = _uiState.value.exposureLimits
        val value = iso.coerceIn(limits.isoMin, limits.isoMax)
        _uiState.update { it.copy(iso = value, exposurePriority = ExposurePriority.ISO) }
        interactors.setExposure(ExposurePriority.ISO, value, _uiState.value.shutterNanos)
    }

    fun setShutterNanos(nanos: Long) {
        val limits = _uiState.value.exposureLimits
        val value = nanos.coerceIn(limits.shutterMinNanos, limits.shutterMaxNanos)
        _uiState.update {
            it.copy(
                shutterNanos = value,
                exposurePriority = ExposurePriority.SHUTTER
            )
        }
        interactors.setExposure(ExposurePriority.SHUTTER, _uiState.value.iso, value)
    }

    fun setExposureCompensation(index: Int) {
        val limits = _uiState.value.exposureLimits
        val value = index.coerceIn(limits.evMin, limits.evMax)
        _uiState.update { it.copy(exposureCompensation = value) }
        interactors.setExposureCompensation(value)
    }

    fun onShutter(previewView: PreviewView) {
        val state = _uiState.value
        if (state.review != null) return
        if (state.motionCapturing) return
        when (state.captureAction) {
            CaptureAction.VIDEO -> {
                if (state.isRecording) stopRecording() else viewModelScope.launch { startRecording() }
            }
            CaptureAction.STILL -> {
                if (state.countdownRemaining != null) return
                if (state.timer.seconds > 0) {
                    startCountdown { takePhoto(previewView) }
                } else {
                    takePhoto(previewView)
                }
            }
        }
    }

    fun pauseOrResume() {
        val state = _uiState.value
        if (!state.isRecording) return
        if (state.isPaused) {
            interactors.resumeRecording()
            _uiState.update { it.copy(isPaused = false) }
        } else {
            interactors.pauseRecording()
            _uiState.update { it.copy(isPaused = true) }
        }
    }

    /** Recorded audio follows the Settings mute default once the mic is granted. */
    fun onMicrophonePermissionResult(granted: Boolean) {
        updateMicrophonePermission(granted)
        if (!granted) return
        if (_uiState.value.isRecording) {
            if (_uiState.value.isMuted && !recordMutedByDefault) {
                _uiState.update { it.copy(isMuted = false) }
                interactors.muteRecording(false)
            }
        } else if (!recordMutedByDefault) {
            _uiState.update { it.copy(isMuted = false) }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null, messageRes = null) }
    }

    fun keepCapture() {
        val review = _uiState.value.review ?: return
        publishAndFinish(review.file, review.isVideo)
        review.companions.forEach { publishQuietly(it) }
        _uiState.update { it.copy(thumbnail = review.file, review = null) }
    }

    fun retakeCapture() {
        val review = _uiState.value.review ?: return
        viewModelScope.launch {
            withContext(dispatchers.io) {
                review.file.delete()
                review.companions.forEach { it.delete() }
            }
        }
        _uiState.update { it.copy(review = null) }
    }

    fun applyCaptureSettings(settings: CaptureSettings) {
        captureConfirmEnabled = settings.confirmEnabled
        flipWhileRecordingEnabled = settings.flipWhileRecording
        recordMutedByDefault = settings.recordMuted
        val state = _uiState.value
        val resolvedMuted = when {
            state.isRecording -> state.isMuted
            !state.microphonePermissionGranted -> true
            else -> settings.recordMuted
        }
        if (state.flipWhileRecording != settings.flipWhileRecording ||
            (!state.isRecording && state.isMuted != resolvedMuted)
        ) {
            _uiState.update {
                it.copy(
                    flipWhileRecording = settings.flipWhileRecording,
                    isMuted = if (it.isRecording) it.isMuted else resolvedMuted
                )
            }
        }
        if (state.lowLightBoost != settings.lowLightBoost) {
            _uiState.update { it.copy(lowLightBoost = settings.lowLightBoost) }
            interactors.setLowLightBoost(settings.lowLightBoost)
        }
        // Grid is a viewfinder overlay pref (Settings → General) — no rebind on change.
        if (state.gridEnabled != settings.gridEnabled) {
            _uiState.update { it.copy(gridEnabled = settings.gridEnabled) }
        }
        // Pip corner: push into the session (merge + composition) without a rebind here;
        // entering Dual later binds with the current corner.
        if (state.pipCorner != settings.pipCorner) {
            _uiState.update { it.copy(pipCorner = settings.pipCorner) }
            interactors.setPipCorner(settings.pipCorner)
        }
        if (state.pipScale != settings.pipScale) {
            _uiState.update { it.copy(pipScale = settings.pipScale) }
            interactors.setPipScale(settings.pipScale)
        }
        if (state.captureAspect == settings.aspect &&
            state.videoQuality == settings.videoQuality &&
            state.videoHdrRange == settings.videoHdrRange &&
            state.videoStabilization == settings.videoStabilization &&
            state.slowMotionQuality == settings.slowMotionQuality &&
            state.slowMotionRate == settings.slowMotionRate &&
            state.ultraHdr == settings.ultraHdr &&
            state.rawCapture == settings.rawCapture &&
            state.rawFullSensor == settings.rawFullSensor &&
            state.videoFps60 == settings.videoFps60 &&
            state.frontMirror == settings.frontMirror
        ) return
        val dropExtension = settings.rawCapture && state.extension != CameraExtension.NONE
        if (dropExtension) manualExtension = false
        val forceMotion = externalCapture.kind == ExternalCaptureKind.MOTION_PHOTO
        _uiState.update {
            it.copy(
                captureAspect = settings.aspect,
                videoQuality = settings.videoQuality,
                videoHdrRange = settings.videoHdrRange,
                videoStabilization = settings.videoStabilization,
                slowMotionQuality = settings.slowMotionQuality,
                slowMotionRate = settings.slowMotionRate,
                ultraHdr = if (forceMotion) false else settings.ultraHdr,
                rawCapture = if (forceMotion) false else settings.rawCapture,
                rawFullSensor = settings.rawFullSensor,
                lowLightBoost = settings.lowLightBoost,
                videoFps60 = settings.videoFps60,
                frontMirror = settings.frontMirror,
                motionPhotoEnabled = when {
                    forceMotion -> true
                    settings.rawCapture || settings.ultraHdr -> false
                    else -> it.motionPhotoEnabled
                },
                extension = if (dropExtension) CameraExtension.NONE else it.extension,
                autoNightActive = if (dropExtension) false else it.autoNightActive,
                bindRevision = it.bindRevision + 1
            )
        }
    }

    override fun onCleared() {
        countdownJob?.cancel()
        cancelZoomAnimation()
        interactors.releaseCamera()
        shutterSound.release()
        super.onCleared()
    }

    private fun startCountdown(onFinished: () -> Unit) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            val seconds = _uiState.value.timer.seconds
            for (value in seconds downTo 1) {
                _uiState.update { it.copy(countdownRemaining = value) }
                delay(1_000)
            }
            _uiState.update { it.copy(countdownRemaining = null) }
            onFinished()
        }
    }

    private fun takePhoto(previewView: PreviewView) {
        val directory = outputDirectory ?: return
        val state = _uiState.value
        if (state.mode == CameraMode.DUAL_PHOTO) {
            viewModelScope.launch {
                interactors.captureDualPhoto(
                    outputDirectory = directory,
                    lens = state.lens
                ).fold(
                    onSuccess = { file ->
                        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                        completeCapture(file, video = false) {
                            copy(captureFlashToken = captureFlashToken + 1)
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(message = error.message ?: "Photo capture failed")
                        }
                    }
                )
            }
            return
        }
        val motion = !_uiState.value.rawCapture &&
            (!_uiState.value.ultraHdr ||
                externalCapture.kind == ExternalCaptureKind.MOTION_PHOTO) &&
            _uiState.value.stillFormat != StillFormat.RAW_JPEG &&
            _uiState.value.stillFormat != StillFormat.JPEG_ULTRA_HDR &&
            _uiState.value.stillFormat != StillFormat.HEIC_ULTRA_HDR &&
            (_uiState.value.motionPhotoEnabled ||
                externalCapture.kind == ExternalCaptureKind.MOTION_PHOTO)
        if (motion) _uiState.update { it.copy(motionCapturing = true) }
        viewModelScope.launch {
            val result = interactors.capturePhoto(
                outputDirectory = directory,
                lens = _uiState.value.lens,
                effect = _uiState.value.effect,
                motionPhoto = motion
            )
            result.fold(
                onSuccess = { file ->
                    shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                    completeCapture(file, video = false) {
                        copy(
                            captureFlashToken = captureFlashToken + 1,
                            motionCapturing = false
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        previewView.postDelayed({
                            previewView.foreground = ColorDrawable(android.graphics.Color.WHITE)
                            previewView.postDelayed(
                                { previewView.foreground = null },
                                ANIMATION_FAST_MILLIS
                            )
                        }, ANIMATION_SLOW_MILLIS)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            message = error.message ?: "Photo capture failed",
                            motionCapturing = false
                        )
                    }
                }
            )
        }
    }

    private fun startRecording() {
        val directory = outputDirectory ?: return
        interactors.startRecording(
            outputDirectory = directory,
            muted = _uiState.value.isMuted,
            persistent = flipWhileRecordingEnabled &&
                _uiState.value.mode.profile().allowsPersistentRecording
        ).fold(
            onSuccess = { file ->
                shutterSound.play(MediaActionSound.START_VIDEO_RECORDING)
                pendingVideoFile = file
                _uiState.update {
                    it.copy(isRecording = true, isPaused = false, recordingNanos = 0L)
                }
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(message = error.message ?: "Video capture failed")
                }
            }
        )
    }

    private fun stopRecording() {
        interactors.stopRecording()
    }

    private fun handleRecordEvent(event: RecordingEvent) {
        when (event) {
            is RecordingEvent.Status -> {
                _uiState.update {
                    it.copy(
                        recordingNanos = event.durationNanos,
                        recordingSizeBytes = event.sizeBytes
                    )
                }
            }

            RecordingEvent.Paused -> _uiState.update { it.copy(isPaused = true) }
            RecordingEvent.Resumed -> _uiState.update { it.copy(isPaused = false) }
            is RecordingEvent.Finalized -> {
                shutterSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                val file = pendingVideoFile
                pendingVideoFile = null
                val discard = discardPendingVideo
                discardPendingVideo = false
                if (discard) {
                    viewModelScope.launch {
                        withContext(dispatchers.io) { file?.delete() }
                    }
                    _uiState.update {
                        it.copy(
                            isRecording = false,
                            isPaused = false,
                            recordingNanos = 0L,
                            recordingSizeBytes = 0L,
                            isMuted = recordMutedByDefault,
                            message = null
                        )
                    }
                } else if (event.success && file != null) {
                    completeCapture(file, video = true) {
                        copy(
                            isRecording = false,
                            isPaused = false,
                            recordingNanos = 0L,
                            recordingSizeBytes = 0L,
                            isMuted = recordMutedByDefault,
                            message = null
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isRecording = false,
                            isPaused = false,
                            recordingNanos = 0L,
                            recordingSizeBytes = 0L,
                            isMuted = recordMutedByDefault,
                            message = if (event.success) null else "Video capture failed"
                        )
                    }
                }
            }
        }
    }

    private fun applyAutoNight(scene: NightScene) {
        val state = _uiState.value
        if (manualExtension ||
            state.motionPhotoEnabled ||
            state.rawCapture ||
            !state.mode.profile().allowsNightAuto ||
            state.isRecording ||
            state.motionCapturing ||
            CameraExtension.NIGHT !in state.supportedExtensions
        ) return
        when (scene) {
            NightScene.RECOMMENDED -> if (state.extension != CameraExtension.NIGHT) {
                _uiState.update {
                    it.copy(
                        extension = CameraExtension.NIGHT,
                        autoNightActive = true,
                        bindRevision = it.bindRevision + 1
                    )
                }
            }

            NightScene.NOT_RECOMMENDED -> if (state.autoNightActive && state.extension == CameraExtension.NIGHT) {
                _uiState.update {
                    it.copy(
                        extension = CameraExtension.NONE,
                        autoNightActive = false,
                        bindRevision = it.bindRevision + 1
                    )
                }
            }

            NightScene.UNKNOWN -> Unit
        }
    }

    private fun completeCapture(
        file: File,
        video: Boolean,
        extra: CameraUiState.() -> CameraUiState = { this }
    ) {
        if (captureConfirmEnabled) {
            viewModelScope.launch {
                val review = withContext(dispatchers.io) { captureReview(file, video) }
                _uiState.update { extra(it).copy(review = review) }
            }
            return
        }
        viewModelScope.launch {
            val companions = withContext(dispatchers.io) { companionStill(file, video) }
            publishAndFinish(file, video)
            companions.forEach { publishQuietly(it) }
            _uiState.update { extra(it).copy(thumbnail = file, review = null) }
        }
    }

    private fun publishAndFinish(file: File, video: Boolean) {
        publishQuietly(file)
        if (externalCapture.kind == ExternalCaptureKind.IMAGE_CAPTURE ||
            externalCapture.kind == ExternalCaptureKind.MOTION_PHOTO ||
            (externalCapture.kind == ExternalCaptureKind.VIDEO_CAPTURE && video)
        ) {
            _externalCaptureReady.tryEmit(file)
        }
    }

    private fun publishQuietly(file: File) {
        viewModelScope.launch {
            interactors.publishMedia(file).onFailure { error ->
                val expected = error is MediaPublishException && error.expected
                if (expected) {
                    Logger.warning(TAG, "Publish failed: ${error.message}", error)
                } else {
                    Logger.error(TAG, "Publish failed: ${error.message}", error)
                }
                _uiState.update {
                    it.copy(message = error.message ?: "Unable to save to gallery")
                }
            }
        }
    }

    private fun captureReview(file: File, video: Boolean): CaptureReview {
        val motion = !video &&
            (MotionPhotoMuxer.isMotionPhoto(file) || AppleLivePhotoWriter.isLivePhoto(file))
        val (width, height, durationNanos) = mediaFileInfo(file, video).let {
            Triple(it.width, it.height, it.durationNanos)
        }
        val format = _uiState.value.stillFormat
        val formatRes = when {
            video || motion -> null
            format == StillFormat.HEIC_ULTRA_HDR -> R.string.ultrahdr_heic
            format == StillFormat.JPEG_ULTRA_HDR -> R.string.ultrahdr
            format == StillFormat.RAW_JPEG -> R.string.raw_dng
            else -> null
        }
        return CaptureReview(
            file = file,
            isVideo = video,
            isMotionPhoto = motion,
            width = width,
            height = height,
            durationLabel = durationNanos?.let { formatRecordingTime(it) },
            formatLabelRes = formatRes,
            companions = companionStill(file, video)
        )
    }

    /** Files published alongside the capture: the DNG companion and the Live Photo clip. */
    private fun companionStill(file: File, video: Boolean): List<File> {
        if (video) return emptyList()
        val companions = mutableListOf<File>()
        val dng = File(file.parentFile, file.nameWithoutExtension + Constants.FILE.DNG_EXTENSION)
        if (dng.exists() && dng != file) companions += dng
        val live = AppleLivePhotoWriter.companionFor(file)
        if (live.exists() && live.length() > 0L && AppleLivePhotoWriter.isLivePhoto(file)) {
            companions += live
        }
        return companions
    }

    companion object {
        private const val TAG = "CameraViewModel"
        private const val STATE_MODE = "camera_mode"
        private const val STATE_LENS = "camera_lens"
        private const val STATE_FLASH = "camera_flash"
    }

    private fun restoreChrome() {
        val mode = savedState.get<String>(STATE_MODE)?.let { name ->
            runCatching { CameraMode.valueOf(name) }.getOrNull()
        }
        val lens = savedState.get<String>(STATE_LENS)?.let { name ->
            runCatching { CameraLens.valueOf(name) }.getOrNull()
        }
        val flash = savedState.get<String>(STATE_FLASH)?.let { name ->
            runCatching { FlashMode.valueOf(name) }.getOrNull()
        }
        if (mode == null && lens == null && flash == null) return
        _uiState.update {
            it.copy(
                mode = mode ?: it.mode,
                lens = lens ?: it.lens,
                flash = flash ?: it.flash
            )
        }
    }

    private fun persistChrome() {
        val state = _uiState.value
        savedState[STATE_MODE] = state.mode.name
        savedState[STATE_LENS] = state.lens.name
        savedState[STATE_FLASH] = state.flash.name
    }

    private suspend fun refreshThumbnail() {
        val directory = outputDirectory ?: return
        val latest = withContext(dispatchers.io) { interactors.getLatestMedia(directory) }
        _uiState.update { it.copy(thumbnail = latest) }
    }
}
