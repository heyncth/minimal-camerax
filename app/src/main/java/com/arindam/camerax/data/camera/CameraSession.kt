package com.arindam.camerax.data.camera

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.util.Size
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CompositionSettings
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.LowLightBoostState
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.core.net.toFile
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.arindam.camerax.domain.model.BoundSession
import com.arindam.camerax.domain.model.BoundSessionKind
import com.arindam.camerax.domain.model.CameraBindConfig
import com.arindam.camerax.domain.model.CameraBindResult
import com.arindam.camerax.domain.model.CameraExtension
import com.arindam.camerax.domain.model.CameraHost
import com.arindam.camerax.domain.model.CameraLens
import com.arindam.camerax.domain.model.CaptureAspect
import com.arindam.camerax.domain.model.EffectMode
import com.arindam.camerax.domain.model.ExposureLimits
import com.arindam.camerax.domain.model.ExposurePriority
import com.arindam.camerax.domain.model.FlashMode
import com.arindam.camerax.domain.model.LowLightBoost
import com.arindam.camerax.domain.model.NightScene
import com.arindam.camerax.domain.model.PipCorner
import com.arindam.camerax.domain.model.PhysicalZoom
import com.arindam.camerax.domain.model.RecordingEvent
import com.arindam.camerax.domain.model.StillFormat
import com.arindam.camerax.domain.model.VideoHdrRange
import com.arindam.camerax.domain.model.ZoomInfo
import com.arindam.camerax.domain.repository.CameraRepository
import com.arindam.camerax.util.commons.Constants
import com.arindam.camerax.util.log.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Data: CameraX implementation of [com.arindam.camerax.domain.repository.CameraRepository].
 * Bind, capture, record, zoom, AE, extensions, Dual, and slo-mo live here. UI must not
 * call this class; go through use cases / [com.arindam.camerax.di.CameraInteractors] from
 * [com.arindam.camerax.di.AppContainer].
 */
class CameraSession(private val context: Context) : CameraRepository {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var highSpeedSession = false
    private var imageAnalysis: ImageAnalysis? = null
    private var colorAnalyzer: ColorEffectAnalyzer? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val _effectFrame = MutableStateFlow<Bitmap?>(null)
    override val effectFrame: StateFlow<Bitmap?> = _effectFrame.asStateFlow()
    private var boundPreviewView: PreviewView? = null
    private var frontMirrorEnabled = true
    private val _nightScene = MutableStateFlow(NightScene.UNKNOWN)
    override val nightScene: StateFlow<NightScene> = _nightScene.asStateFlow()
    private val _lowLightBoost = MutableStateFlow(LowLightBoost.OFF)
    override val lowLightBoost: StateFlow<LowLightBoost> = _lowLightBoost.asStateFlow()
    private val _recordingEvents = MutableSharedFlow<RecordingEvent>(extraBufferCapacity = 16)
    override val recordingEvents: SharedFlow<RecordingEvent> = _recordingEvents.asSharedFlow()
    private var lowLightBoostLiveData: LiveData<Int>? = null
    private val lowLightBoostObserver = Observer<Int> { value ->
        _lowLightBoost.value = when (value) {
            LowLightBoostState.ACTIVE -> LowLightBoost.ACTIVE
            LowLightBoostState.INACTIVE -> LowLightBoost.INACTIVE
            else -> LowLightBoost.OFF
        }
    }
    private var flashMode: FlashMode = FlashMode.OFF
    private var lowLightBoostRequested = true
    private var stillFormat: StillFormat = StillFormat.JPEG
    private var motionStill: File? = null
    private var motionVideo: File? = null
    private var motionOnSaved: ((File) -> Unit)? = null
    private var motionOnError: ((String) -> Unit)? = null
    private var motionAwaitingVideo = false
    private var motionVideoIsRolling = false
    private val motionStopRunnable = Runnable { stopMotionRollingForCapture() }
    @Volatile
    private var motionRollingRequested = false
    @Volatile
    private var motionRollingActive = false
    private var motionRollingFile: File? = null
    private var motionRollingStartedAt = 0L
    private var motionShutterAt = 0L
    private val motionPrerollDir by lazy {
        File(context.cacheDir, "motion_preroll").apply { mkdirs() }
    }
    private val motionRollingRestartRunnable = Runnable { restartMotionRolling() }
    private var previewBoosted = false
    private var userExposureIndex: Int? = null
    private var fps60Active = false
    private var lastBoundKind = BoundSessionKind.STANDARD
    @Volatile
    private var pipCorner: PipCorner = PipCorner.TOP_LEFT
    @Volatile
    private var pipScale: Float = 1f
    private var lastStillsOnlyFallback = false
    private var lastBoundExtension = CameraExtension.NONE
    private var lastRawFullSensor = false
    private var dualPhotoActive = false
    private var dualPhotoSimultaneous = false
    /** Set after the simultaneous graph fails; keeps the sequential tier from retrying it every bind. */
    private var dualPhotoSequentialOnly = false
    private var dualImageCapture: ImageCapture? = null
    private var dualSecondaryLens: CameraLens? = null
    private var dualBaseHost: PreviewViewHost? = null
    private var dualBaseConfig: CameraBindConfig? = null

    private val initMutex = Mutex()

    suspend fun initialize() {
        if (cameraProvider != null) return
        initMutex.withLock {
            if (cameraProvider != null) return  // double-check after acquiring lock
            cameraProvider = awaitProvider()
            extensionsManager = awaitExtensions(cameraProvider!!)
        }
    }

    fun supportedExtensions(selector: CameraSelector): Set<CameraExtension> {
        val manager = extensionsManager ?: return emptySet()
        return CameraExtension.entries.filter { extension ->
            extension != CameraExtension.NONE &&
                manager.isExtensionAvailable(selector, extension.toExtensionMode())
        }.toSet()
    }

    @MainThread
    @OptIn(ExperimentalCamera2Interop::class)
    override suspend fun bind(host: CameraHost, config: CameraBindConfig): CameraBindResult {
        // Rebind kills the active recording — drop the rolling pre-roll first;
        // finishBind restarts it when motion photos are still requested.
        cancelMotionRolling()
        return bindSession(host, config)
    }

    private suspend fun bindSession(host: CameraHost, config: CameraBindConfig): CameraBindResult {
        val previewHost = host as? PreviewViewHost
            ?: throw IllegalArgumentException("Unsupported camera host")
        val lifecycleOwner = previewHost.lifecycleOwner
        val previewView = previewHost.previewView
        boundPreviewView = previewView
        initialize()
        val provider = cameraProvider ?: throw IllegalStateException("Camera provider missing")
        if (config.retainRecording && canRetainRecording()) {
            return rebindWhileRecording(previewHost, config)
        }
        provider.unbindAll()
        stopColorAnalysis()
        highSpeedSession = false
        imageCapture = null
        videoCapture = null
        preview = null
        dualPhotoActive = false
        dualPhotoSimultaneous = false
        dualImageCapture = null
        dualSecondaryLens = null
        dualBaseHost = null
        dualBaseConfig = null

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = if (config.slowMotion || config.captureAspect == CaptureAspect.FULL) {
            PreviewView.ScaleType.FILL_CENTER
        } else {
            PreviewView.ScaleType.FIT_CENTER
        }
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

        if (config.concurrent) {
            if (!isDualCameraSupported(context, provider)) {
                throw IllegalStateException("Dual camera is not supported on this device")
            }
            return bindConcurrent(provider, previewHost, config, rotation)
        }

        if (config.dualPhoto) {
            // Simultaneous tier: separate-stream ConcurrentCamera with an ImageCapture per lens.
            // Failure falls through to the standard single-camera bind (sequential tier).
            if (bindDualPhoto(provider, previewHost, config, rotation)) {
                dualBaseHost = previewHost
                dualBaseConfig = config
                dualPhotoActive = true
                return finishBind(
                    config = config,
                    slowMotionSupported = isHighSpeedSupported(camera?.cameraInfo),
                    slowMotionFps = 0
                )
            }
        }

        val requestedSelector = selectorFor(config)
        val requestedInfo = runCatching { provider.getCameraInfo(requestedSelector) }.getOrNull()
        val baseSelector = if (requestedInfo != null) {
            requestedSelector
        } else {
            if (!config.cameraId.isNullOrBlank()) {
                Logger.warning(TAG, "Falling back from camera ${config.cameraId} to default lens")
            }
            config.lens.toSelector()
        }
        val manager = extensionsManager
        val stillInfo = requestedInfo
            ?: runCatching { provider.getCameraInfo(baseSelector) }.getOrNull()
        // Dual photo always merges plain JPEGs — skip RAW and Ultra HDR / HEIC outputs.
        val useRaw = config.rawCapture && !config.slowMotion && !config.dualPhoto &&
            stillInfo != null && stillInfo.supportsRawJpeg()
        val useExtension = !useRaw &&
            !config.slowMotion &&
            config.extension != CameraExtension.NONE &&
            config.cameraId == null &&
            manager != null &&
            manager.isExtensionAvailable(baseSelector, config.extension.toExtensionMode())
        val selector = if (useExtension) {
            manager.getExtensionEnabledCameraSelector(baseSelector, config.extension.toExtensionMode())
        } else {
            baseSelector
        }

        if (config.slowMotion) {
            stillFormat = StillFormat.JPEG
            val bound = bindHighSpeed(provider, lifecycleOwner, selector, previewView, rotation, config)
            if (bound != null) {
                camera = bound.camera
                highSpeedSession = true
                return finishBind(
                    config = config,
                    slowMotionSupported = true,
                    slowMotionFps = bound.fps
                )
            }
            Logger.warning(TAG, "High-speed bind failed; falling back to standard session")
        }

        val previewBuilder = Preview.Builder().setTargetRotation(rotation)
        previewBuilder.setResolutionSelector(config.captureAspect.toResolutionSelector())
        Camera2Interop.Extender(previewBuilder).apply {
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                CaptureRequest.EDGE_MODE_HIGH_QUALITY
            )
            setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            )
        }
        val cameraInfo = stillInfo
        val wantStab = config.videoStabilization && !useExtension
        val previewStab = wantStab && isPreviewStabilizationSupported(cameraInfo)
        val videoStab = wantStab && isVideoStabilizationSupported(cameraInfo)
        previewBuilder.setPreviewStabilizationEnabled(previewStab)
        attachNightModeMonitor(previewBuilder)
        val liveEffects = config.liveEffects && !useRaw
        val preview = previewBuilder.build().also { it.surfaceProvider = previewView.surfaceProvider }
        this.preview = preview

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(config.flash.toImageCaptureMode())
            .setTargetRotation(rotation)
        val useFullSensor = useRaw &&
            config.rawFullSensor &&
            stillInfo.supportsFullSensorRaw(context) == true
        lastRawFullSensor = useFullSensor
        lastBoundExtension = if (useExtension) config.extension else CameraExtension.NONE
        lastBoundKind = BoundSessionKind.STANDARD
        lastStillsOnlyFallback = false
        if (useFullSensor) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Camera2Interop.Extender(captureBuilder).setCaptureRequestOption(
                    CaptureRequest.SENSOR_PIXEL_MODE,
                    CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                )
            }
            captureBuilder.setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
        } else {
            captureBuilder.setResolutionSelector(config.captureAspect.toResolutionSelector())
        }
        val resolved = resolveStillOutput(
            info = runCatching { provider.getCameraInfo(selector) }.getOrNull()
                ?: stillInfo
                ?: provider.getCameraInfo(baseSelector),
            ultraHdr = config.ultraHdr && !config.dualPhoto && !useRaw && !liveEffects,
            rawCapture = useRaw
        )
        stillFormat = resolved.second
        if (resolved.first != ImageCapture.OUTPUT_FORMAT_JPEG) {
            captureBuilder.setOutputFormat(resolved.first)
        }
        val capture = captureBuilder.build()
        imageCapture = capture

        val recorder = Recorder.Builder()
            .setQualitySelector(config.videoQuality.toQualitySelector())
            .build()
        val videoRange = resolveVideoHdrRange(cameraInfo, config)
        val video = buildVideoCapture(recorder, videoStab, videoRange, config.frontMirror)
        videoCapture = video

        val includeVideo = !useExtension && !liveEffects
        val analysis = if (liveEffects) {
            buildColorAnalysis(rotation, config.effect)
        } else {
            stopColorAnalysis()
            null
        }
        val wantFps60 = config.videoFps60 && includeVideo && !useExtension

        camera = try {
            bindWithFallback(
                provider = provider,
                lifecycleOwner = lifecycleOwner,
                selector = selector,
                preview = preview,
                imageCapture = capture,
                videoCapture = if (includeVideo) video else null,
                imageAnalysis = analysis,
                fps60 = wantFps60
            )
        } catch (error: Exception) {
            if (!config.cameraId.isNullOrBlank()) {
                Logger.warning(TAG, "Bind with camera ${config.cameraId} failed: ${error.message}; falling back to default lens")
                bindWithFallback(
                    provider = provider,
                    lifecycleOwner = lifecycleOwner,
                    selector = config.lens.toSelector(),
                    preview = preview,
                    imageCapture = capture,
                    videoCapture = if (includeVideo) video else null,
                    imageAnalysis = analysis,
                    fps60 = wantFps60
                )
            } else {
                throw error
            }
        }
        if (!includeVideo) videoCapture = null

        if (config.dualPhoto) {
            dualPhotoActive = true
            dualPhotoSimultaneous = false
            dualImageCapture = null
            dualSecondaryLens = null
            dualBaseHost = previewHost
            dualBaseConfig = config
            lastBoundKind = BoundSessionKind.DUAL_PHOTO
        } else {
            dualPhotoActive = false
            dualBaseHost = null
            dualBaseConfig = null
            dualPhotoSequentialOnly = false
        }

        return finishBind(
            config = config,
            slowMotionSupported = if (config.slowMotion) {
                false
            } else {
                isHighSpeedSupported(camera?.cameraInfo)
            },
            slowMotionFps = 0
        )
    }

    override suspend fun capturePhoto(
        outputDirectory: File,
        lens: CameraLens,
        effect: EffectMode,
        motionPhoto: Boolean
    ): Result<File> = suspendCancellableCoroutine { continuation ->
        val onSaved: (File) -> Unit = { file ->
            if (continuation.isActive) continuation.resume(Result.success(file))
        }
        val onError: (String) -> Unit = { message ->
            if (continuation.isActive) {
                continuation.resume(Result.failure(IllegalStateException(message)))
            }
        }
        if (motionPhoto && stillFormat != StillFormat.RAW_JPEG &&
            stillFormat != StillFormat.JPEG_ULTRA_HDR &&
            stillFormat != StillFormat.HEIC_ULTRA_HDR &&
            videoCapture != null
        ) {
            captureMotionPhoto(outputDirectory, lens, effect, onSaved, onError)
        } else {
            takeStill(outputDirectory, lens, effect, onSaved, onError)
        }
    }

    /**
     * Dual photo: stills from both lenses merged into one PiP JPEG. Captures simultaneously when
     * the concurrent graph bound ([dualPhotoSimultaneous]); otherwise captures the primary,
     * rebinds to the secondary lens, captures, and restores the primary (sequential tier).
     * When one lens fails, saves the surviving still alone (single-camera fallback).
     */
    override suspend fun captureDualPhoto(
        outputDirectory: File,
        lens: CameraLens
    ): Result<File> {
        if (!dualPhotoActive) {
            return Result.failure(IllegalStateException("Camera is not ready"))
        }
        val primaryLens = dualBaseConfig?.lens ?: lens
        Logger.info(
            TAG,
            "Dual capture tier=${if (dualPhotoSimultaneous) "simultaneous" else "sequential"}"
        )
        return if (dualPhotoSimultaneous) {
            captureDualSimultaneous(outputDirectory, primaryLens)
        } else {
            captureDualSequential(outputDirectory, primaryLens)
        }
    }

    /** Decode bounds only (no pixels) for logging output sizes. */
    private fun fileDims(file: File): String = runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        "${opts.outWidth}x${opts.outHeight}"
    }.getOrDefault("?x?")

    private suspend fun captureDualSimultaneous(
        outputDirectory: File,
        primaryLens: CameraLens
    ): Result<File> {
        val primaryCapture = imageCapture
            ?: return Result.failure(IllegalStateException("Camera is not ready"))
        val secondaryCapture = dualImageCapture
            ?: return Result.failure(IllegalStateException("Camera is not ready"))
        val secondaryLens = dualSecondaryLens ?: primaryLens.toggle()
        val primaryFile = createFile(outputDirectory, Constants.FILE.PHOTO_EXTENSION)
        val secondaryFile = createFile(outputDirectory, Constants.FILE.PHOTO_EXTENSION)
        val (primaryResult, secondaryResult) = coroutineScope {
            val primary = async { awaitStill(primaryCapture, primaryFile, primaryLens) }
            val secondary = async { awaitStill(secondaryCapture, secondaryFile, secondaryLens) }
            primary.await() to secondary.await()
        }
        return combineDualStills(outputDirectory, primaryResult, secondaryResult)
    }

    private suspend fun captureDualSequential(
        outputDirectory: File,
        primaryLens: CameraLens
    ): Result<File> {
        val host = dualBaseHost
            ?: return captureBoundStill(outputDirectory, primaryLens)
        val base = dualBaseConfig
            ?: return captureBoundStill(outputDirectory, primaryLens)
        val secondaryLens = primaryLens.toggle()
        val primaryCapture = imageCapture
            ?: return Result.failure(IllegalStateException("Camera is not ready"))
        val primaryFile = createFile(outputDirectory, Constants.FILE.PHOTO_EXTENSION)
        val primaryResult = awaitStill(primaryCapture, primaryFile, primaryLens)

        // Rebind to the secondary lens for its still (bind keeps dualPhoto so stills stay plain JPEG).
        val secondaryResult = runCatching {
            bind(host, base.copy(lens = secondaryLens, cameraId = null))
            val secondaryCapture = imageCapture
                ?: throw IllegalStateException("Camera is not ready")
            val secondaryFile = createFile(outputDirectory, Constants.FILE.PHOTO_EXTENSION)
            awaitStill(secondaryCapture, secondaryFile, secondaryLens)
        }.getOrElse { error ->
            Logger.warning(TAG, "Dual sequential secondary capture failed: ${error.message}")
            Result.failure(
                error as? IllegalStateException ?: IllegalStateException(error.message ?: "Photo capture failed")
            )
        }

        // Restore the primary session; keep dual state alive even if the restore bind fails.
        val restored = runCatching {
            bind(host, base.copy(lens = primaryLens))
        }
        restored.onFailure { error ->
            Logger.error(TAG, "Dual sequential primary restore failed: ${error.message}")
        }
        dualPhotoActive = true
        dualPhotoSimultaneous = false
        dualBaseHost = host
        dualBaseConfig = base
        lastBoundKind = BoundSessionKind.DUAL_PHOTO

        return combineDualStills(outputDirectory, primaryResult, secondaryResult)
    }

    private suspend fun captureBoundStill(outputDirectory: File, lens: CameraLens): Result<File> {
        val capture = imageCapture
            ?: return Result.failure(IllegalStateException("Camera is not ready"))
        val file = createFile(outputDirectory, Constants.FILE.PHOTO_EXTENSION)
        return awaitStill(capture, file, lens)
    }

    private suspend fun awaitStill(
        capture: ImageCapture,
        file: File,
        lens: CameraLens
    ): Result<File> = suspendCancellableCoroutine { continuation ->
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = shouldMirrorFrontOutput(lens)
        }
        val options = ImageCapture.OutputFileOptions.Builder(file)
            .setMetadata(metadata)
            .build()
        capture.takePicture(
            options,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val saved = output.savedUri?.toFile() ?: file
                    Logger.info(TAG, "Dual still ${saved.name} ${fileDims(saved)}")
                    if (continuation.isActive) continuation.resume(Result.success(saved))
                }

                override fun onError(exception: ImageCaptureException) {
                    Logger.error(TAG, "Dual still failed: ${exception.message}")
                    if (continuation.isActive) {
                        continuation.resume(
                            Result.failure(
                                IllegalStateException(exception.message ?: "Photo capture failed")
                            )
                        )
                    }
                }
            }
        )
    }

    /** Merges both stills on [cameraExecutor]; on merge failure falls back to the primary alone. */
    private suspend fun combineDualStills(
        directory: File,
        primary: Result<File>,
        secondary: Result<File>
    ): Result<File> {
        val primaryFile = primary.getOrNull()
        val secondaryFile = secondary.getOrNull()
        if (primaryFile == null && secondaryFile == null) {
            return Result.failure(
                primary.exceptionOrNull() ?: IllegalStateException("Photo capture failed")
            )
        }
        if (primaryFile == null) return secondary
        if (secondaryFile == null) return primary
        val merged = suspendCoroutine<File?> { continuation ->
            cameraExecutor.execute {
                var result: File? = null
                try {
                    val output = createFile(directory, Constants.FILE.PHOTO_EXTENSION)
                    result = DualPhotoMerger.merge(primaryFile, secondaryFile, output, pipCorner, pipScale)
                    if (result == null) output.delete()
                } catch (error: Throwable) {
                    Logger.error(TAG, "Dual merge failed: ${error.message}", error)
                    result = null
                }
                if (result != null) {
                    Logger.info(TAG, "Dual merged ${result.name} ${fileDims(result)}")
                    primaryFile.delete()
                    secondaryFile.delete()
                } else {
                    // Single-camera fallback: keep the primary frame only.
                    secondaryFile.delete()
                }
                continuation.resume(result)
            }
        }
        return Result.success(merged ?: primaryFile)
    }

    override fun startRecording(
        outputDirectory: File,
        muted: Boolean,
        persistent: Boolean
    ): Result<File> = beginRecording(
        outputDirectory = outputDirectory,
        muted = muted,
        persistent = persistent,
        emitEvents = true
    )

    private fun beginRecording(
        outputDirectory: File,
        muted: Boolean,
        persistent: Boolean,
        emitEvents: Boolean
    ): Result<File> {
        val capture = videoCapture ?: return Result.failure(
            IllegalStateException("Video capture is not available with this effect")
        )
        recording?.stop()
        val videoFile = createFile(outputDirectory, Constants.FILE.VIDEO_EXTENSION)
        val availableBytes = android.os.StatFs(outputDirectory.absolutePath).availableBytes
        val sizeLimit = (availableBytes - STORAGE_RESERVE_BYTES).coerceAtLeast(MIN_RECORDING_BYTES)
        val output = FileOutputOptions.Builder(videoFile)
            .setFileSizeLimit(sizeLimit)
            .build()
        val pending = persistentPending(
            capture.output.prepareRecording(context, output),
            persist = persistent
        )
        val withAudio = shouldEnableRecordingAudio(
            highSpeedSession = highSpeedSession,
            recordAudioGranted = PermissionChecker.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PermissionChecker.PERMISSION_GRANTED
        )
        val active = if (withAudio) pending.withAudioEnabled() else pending
        recording = active.start(ContextCompat.getMainExecutor(context)) { event ->
            val domain = event.toDomain() ?: return@start
            if (domain is RecordingEvent.Finalized) {
                if (motionAwaitingVideo) {
                    motionAwaitingVideo = false
                    if (!domain.success) motionVideo = null
                    finishMotionIfReady()
                }
            }
            if (emitEvents) _recordingEvents.tryEmit(domain)
        }
        if (withAudio && muted) {
            recording?.mute(true)
        }
        return Result.success(videoFile)
    }

    override fun pauseRecording() {
        recording?.pause()
    }

    override fun resumeRecording() {
        recording?.resume()
    }

    override fun stopRecording() {
        recording?.stop()
        recording = null
    }

    override fun muteRecording(muted: Boolean) {
        recording?.mute(muted)
    }

    override fun setFlash(mode: FlashMode) {
        flashMode = mode
        imageCapture?.flashMode = mode.toImageCaptureMode()
        dualImageCapture?.flashMode = mode.toImageCaptureMode()
        applyTorch(mode)
        applyLowLightBoost()
    }

    override fun setPipCorner(corner: PipCorner) {
        pipCorner = corner
    }

    override fun setPipScale(scale: Float) {
        pipScale = scale
    }

    override fun setMotionRolling(enabled: Boolean) {
        motionRollingRequested = enabled
        if (enabled) ensureMotionRolling() else cancelMotionRolling()
    }

    /** Starts the rolling pre-roll clip when it is wanted and the recorder is idle. */
    private fun ensureMotionRolling() {
        if (!motionRollingRequested || motionAwaitingVideo || motionRollingActive) return
        if (recording != null) return
        if (videoCapture == null || imageCapture == null) return
        startMotionRolling()
    }

    private fun startMotionRolling() {
        prunePrerollDir()
        val file = beginRecording(
            outputDirectory = motionPrerollDir,
            muted = true,
            persistent = false,
            emitEvents = false
        ).getOrElse { error ->
            Logger.warning(TAG, "Motion pre-roll failed: ${error.message}")
            null
        } ?: return
        motionRollingFile = file
        motionRollingStartedAt = SystemClock.elapsedRealtime()
        motionRollingActive = true
        scheduleMotionRollingRestart()
    }

    private fun scheduleMotionRollingRestart() {
        mainHandler.removeCallbacks(motionRollingRestartRunnable)
        mainHandler.postDelayed(motionRollingRestartRunnable, MOTION_ROLLING_RESTART_MS)
    }

    /** Bound-size restart: swap the rolling clip (only the last 1.5 s is ever used). */
    private fun restartMotionRolling() {
        if (!motionRollingRequested || !motionRollingActive || motionAwaitingVideo) return
        if (recording == null) {
            motionRollingActive = false
            return
        }
        stopRollingRecording()
        if (motionRollingRequested) startMotionRolling()
    }

    private fun stopRollingRecording() {
        mainHandler.removeCallbacks(motionRollingRestartRunnable)
        motionRollingActive = false
        recording?.stop()
        recording = null
        motionRollingFile = null
    }

    /** Drops the rolling clip (rebind, release, motion off, or still-capture failure). */
    private fun cancelMotionRolling() {
        mainHandler.removeCallbacks(motionRollingRestartRunnable)
        mainHandler.removeCallbacks(motionStopRunnable)
        if (motionRollingActive) {
            motionRollingActive = false
            recording?.stop()
            recording = null
        }
        motionRollingFile = null
    }

    /** Removes stale rolling clips (app cache; keeps the dir bounded). */
    private fun prunePrerollDir() {
        val cutoff = System.currentTimeMillis() - PREROLL_MAX_AGE_MS
        motionPrerollDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    override fun setLowLightBoost(enabled: Boolean) {
        lowLightBoostRequested = enabled
        applyLowLightBoost()
    }

    override fun setZoomRatio(ratio: Float): ZoomInfo? {
        val state = camera?.cameraInfo?.zoomState?.value ?: return null
        val clamped = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        camera?.cameraControl?.setZoomRatio(clamped)
        return ZoomInfo(
            ratio = clamped,
            min = state.minZoomRatio,
            max = state.maxZoomRatio
        )
    }

    override fun tapToFocus(x: Float, y: Float) {
        val previewView = boundPreviewView ?: return
        val mappedX = if (previewView.scaleX < 0f) {
            previewView.width - x
        } else {
            x
        }
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(mappedX, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    override fun setEffect(type: EffectMode) {
        colorAnalyzer?.effect = type
    }

    override fun setTargetRotation(rotation: Int) {
        preview?.targetRotation = rotation
        imageCapture?.targetRotation = rotation
        dualImageCapture?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
        imageAnalysis?.targetRotation = rotation
    }

    @OptIn(ExperimentalCamera2Interop::class)
    override fun setExposure(priority: ExposurePriority, iso: Int, shutterNanos: Long) {
        val control = camera?.cameraControl ?: return
        if (Build.VERSION.SDK_INT < 36) return
        val camera2 = Camera2CameraControl.from(control)
        if (priority == ExposurePriority.AUTO) {
            camera2.clearCaptureRequestOptions()
            return
        }
        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        when (priority) {
            ExposurePriority.ISO -> {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_PRIORITY_MODE,
                    CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY
                )
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            }
            ExposurePriority.SHUTTER -> {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_PRIORITY_MODE,
                    CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY
                )
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNanos)
            }
            ExposurePriority.AUTO -> Unit
        }
        camera2.setCaptureRequestOptions(builder.build())
    }

    override fun setExposureCompensation(index: Int) {
        val exposure = camera?.cameraInfo?.exposureState ?: return
        if (!exposure.isExposureCompensationSupported) return
        userExposureIndex = index
        val range = exposure.exposureCompensationRange
        camera?.cameraControl?.setExposureCompensationIndex(index.coerceIn(range.lower, range.upper))
    }

    override fun unbind() {
        cancelMotionRolling()
        recording?.stop()
        recording = null
        camera?.cameraControl?.enableTorch(false)
        cameraProvider?.unbindAll()
        stopColorAnalysis()
        camera = null
        preview = null
        imageCapture = null
        videoCapture = null
        boundPreviewView = null
        highSpeedSession = false
        fps60Active = false
        previewBoosted = false
        userExposureIndex = null
        dualPhotoActive = false
        dualPhotoSimultaneous = false
        dualImageCapture = null
        dualSecondaryLens = null
        dualBaseHost = null
        dualBaseConfig = null
        stopWatchingLowLightBoost()
        _lowLightBoost.value = LowLightBoost.OFF
        _nightScene.value = NightScene.UNKNOWN
        clearMotionCapture()
    }

    override fun release() {
        unbind()
        // Keep capture/analysis executors alive: AppContainer owns this session for the process.
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun selectorFor(config: CameraBindConfig): CameraSelector {
        val id = config.cameraId
        if (id.isNullOrBlank()) return config.lens.toSelector()
        return CameraSelector.Builder()
            .addCameraFilter { infos ->
                val match = infos.filter { Camera2CameraInfo.from(it).cameraId == id }
                if (match.isEmpty()) {
                    Logger.warning(TAG, "Camera id $id is not independently bindable")
                }
                match
            }
            .build()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun discoverPhysicalZooms(facing: Int): List<PhysicalZoom> {
        val provider = cameraProvider ?: return emptyList()
        val infos = provider.availableCameraInfos.filter { it.lensFacing == facing }
        if (infos.size < 2) return emptyList()

        val labeled = infos.mapNotNull { info ->
            val camera2 = Camera2CameraInfo.from(info)
            val focals = camera2.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            ) ?: return@mapNotNull null
            val sensor = camera2.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
            )
            val minFocal = focals.minOrNull() ?: return@mapNotNull null
            val equivalent = if (sensor != null && sensor.width > 0f) {
                36f * minFocal / sensor.width
            } else {
                minFocal * 7f
            }
            val label = when {
                equivalent < 20f -> 0.5f
                equivalent < 40f -> 1f
                equivalent < 70f -> 2f
                else -> 5f
            }
            PhysicalZoom(cameraId = camera2.cameraId, label = label) to equivalent
        }
        return labeled
            .groupBy { it.first.label }
            .map { (_, group) ->
                val target = when (group.first().first.label) {
                    0.5f -> 16f
                    1f -> 28f
                    2f -> 55f
                    else -> 100f
                }
                group.minBy { kotlin.math.abs(it.second - target) }.first
            }
            .sortedBy { it.label }
    }

    private fun applyLowLightPreviewBoost(scene: NightScene) {
        if (userExposureIndex != null) return
        if (lowLightBoostRequested && camera?.cameraInfo?.isLowLightBoostSupported == true) return
        val exposure = camera?.cameraInfo?.exposureState ?: return
        if (!exposure.isExposureCompensationSupported) return
        val range = exposure.exposureCompensationRange
        if (scene == NightScene.RECOMMENDED && !previewBoosted && range.upper > 0) {
            camera?.cameraControl?.setExposureCompensationIndex(
                2.coerceAtMost(range.upper)
            )
            previewBoosted = true
        } else if (scene != NightScene.RECOMMENDED && previewBoosted) {
            camera?.cameraControl?.setExposureCompensationIndex(0)
            previewBoosted = false
        }
    }

    private fun applyTorch(mode: FlashMode) {
        camera?.cameraControl?.enableTorch(mode == FlashMode.TORCH)
    }

    private fun takeStill(
        outputDirectory: File,
        lens: CameraLens,
        effect: EffectMode,
        onSaved: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val capture = imageCapture ?: return onError("Camera is not ready")
        if (stillFormat == StillFormat.RAW_JPEG) {
            takeRawJpeg(capture, outputDirectory, lens, onSaved, onError)
            return
        }
        val extension = if (stillFormat == StillFormat.HEIC_ULTRA_HDR) {
            Constants.FILE.HEIC_EXTENSION
        } else {
            Constants.FILE.PHOTO_EXTENSION
        }
        val photoFile = createFile(outputDirectory, extension)
        val mirrorOutput = shouldMirrorFrontOutput(lens)
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = mirrorOutput
        }
        val options = ImageCapture.OutputFileOptions.Builder(photoFile)
            .setMetadata(metadata)
            .build()
        capture.takePicture(
            options,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val file = output.savedUri?.toFile() ?: photoFile
                    val preserveHdr = stillFormat != StillFormat.JPEG
                    val processed = if (preserveHdr) {
                        file
                    } else {
                        applyStillOutput(file, effect)
                    }
                    onSaved(processed)
                }

                override fun onError(exception: ImageCaptureException) {
                    Logger.error(TAG, "Photo capture failed: ${exception.message}")
                    onError(exception.message ?: "Photo capture failed")
                }
            }
        )
    }

    private fun takeRawJpeg(
        capture: ImageCapture,
        outputDirectory: File,
        lens: CameraLens,
        onSaved: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val stamp = SimpleDateFormat(Constants.FILE.FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val jpegFile = File(outputDirectory, stamp + Constants.FILE.PHOTO_EXTENSION)
        val dngFile = File(outputDirectory, stamp + Constants.FILE.DNG_EXTENSION)
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = shouldMirrorFrontOutput(lens)
        }
        val jpegOptions = ImageCapture.OutputFileOptions.Builder(jpegFile)
            .setMetadata(metadata)
            .build()
        val dngOptions = ImageCapture.OutputFileOptions.Builder(dngFile)
            .setMetadata(metadata)
            .build()
        var pendingJpeg: File? = null
        var pendingDng: File? = null
        capture.takePicture(
            dngOptions,
            jpegOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val file = output.savedUri?.toFile()
                    when (output.imageFormat) {
                        ImageFormat.JPEG -> pendingJpeg = file ?: jpegFile
                        else -> pendingDng = file ?: dngFile
                    }
                    val jpeg = pendingJpeg
                    if (jpeg != null && pendingDng != null) onSaved(jpeg)
                }

                override fun onError(exception: ImageCaptureException) {
                    Logger.error(TAG, "RAW capture failed: ${exception.message}")
                    onError(exception.message ?: "RAW capture failed")
                }
            }
        )
    }

    private fun captureMotionPhoto(
        outputDirectory: File,
        lens: CameraLens,
        effect: EffectMode,
        onSaved: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        if (imageCapture == null) {
            // No stills available; skip recording to avoid orphaned video.
            takeStill(outputDirectory, lens, effect, onSaved, onError)
            return
        }
        val rolling = motionRollingFile
            ?.takeIf { motionRollingActive && it.exists() && it.length() > 0L }
        if (rolling == null) {
            captureMotionPhotoNoPreroll(outputDirectory, lens, effect, onSaved, onError)
            return
        }
        // Warm rolling clip: mark the shutter, keep recording, stop at +1.5 s, then trim
        // [shutter - 1.5s, shutter + 1.5s] keyframe-aligned around the still.
        motionStill = null
        motionOnSaved = onSaved
        motionOnError = onError
        motionAwaitingVideo = true
        motionVideoIsRolling = true
        motionVideo = rolling
        motionShutterAt = SystemClock.elapsedRealtime()
        takeStill(
            outputDirectory = outputDirectory,
            lens = lens,
            effect = effect,
            onSaved = { file ->
                motionStill = file
                finishMotionIfReady()
            },
            onError = { message ->
                mainHandler.removeCallbacks(motionStopRunnable)
                cancelMotionRolling()
                rolling.delete()
                clearMotionCapture()
                if (motionRollingRequested) ensureMotionRolling()
                onError(message)
            }
        )
        mainHandler.removeCallbacks(motionStopRunnable)
        mainHandler.postDelayed(motionStopRunnable, MOTION_DURATION_MS)
    }

    /** Legacy path when no rolling clip is warm: record from the shutter for 1.5 s. */
    private fun captureMotionPhotoNoPreroll(
        outputDirectory: File,
        lens: CameraLens,
        effect: EffectMode,
        onSaved: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        motionStill = null
        motionOnSaved = onSaved
        motionOnError = onError
        motionAwaitingVideo = true
        motionVideoIsRolling = false
        val videoFile = beginRecording(
            outputDirectory = outputDirectory,
            muted = true,
            persistent = false,
            emitEvents = false
        ).getOrElse { error ->
            motionAwaitingVideo = false
            motionVideo = null
            Logger.warning(TAG, "Motion video failed: ${error.message}")
            finishMotionIfReady()
            null
        }
        if (videoFile == null) {
            motionAwaitingVideo = false
            takeStill(outputDirectory, lens, effect, onSaved, onError)
            return
        }
        motionVideo = videoFile
        takeStill(
            outputDirectory = outputDirectory,
            lens = lens,
            effect = effect,
            onSaved = { file ->
                motionStill = file
                finishMotionIfReady()
            },
            onError = { message ->
                stopRecording()
                motionVideo?.delete()
                clearMotionCapture()
                onError(message)
            }
        )
        mainHandler.postDelayed({ stopRecording() }, MOTION_DURATION_MS)
    }

    private fun stopMotionRollingForCapture() {
        mainHandler.removeCallbacks(motionRollingRestartRunnable)
        motionRollingActive = false
        recording?.stop()
        recording = null
        motionRollingFile = null
    }

    private fun finishMotionIfReady() {
        val still = motionStill ?: return
        if (motionAwaitingVideo) return
        val video = motionVideo
        val onSaved = motionOnSaved ?: return
        val wasRolling = motionVideoIsRolling
        val shutterAt = motionShutterAt
        val rollingStartedAt = motionRollingStartedAt
        clearMotionCapture()
        cameraExecutor.execute {
            var saved = still
            if (video != null && video.exists() && video.length() > 0L) {
                var muxVideo = video
                var presentationUs = 0L
                var trimmed: MotionPhotoTrimmer.TrimmedClip? = null
                if (wasRolling && rollingStartedAt > 0L && shutterAt > rollingStartedAt) {
                    // Rolling clip spans [rollingStarted .. shutter + 1.5s]; keep the
                    // 3 s live-photo window around the shutter (keyframe-aligned).
                    val wallShutterMs = shutterAt - rollingStartedAt
                    val windowStartMs = (wallShutterMs - MOTION_DURATION_MS).coerceAtLeast(0L)
                    val windowEndMs = wallShutterMs + MOTION_DURATION_MS
                    val trimOutput = File(motionPrerollDir, "trim_${System.nanoTime()}.mp4")
                    trimmed = MotionPhotoTrimmer.trim(video, windowStartMs, windowEndMs, trimOutput)
                    if (trimmed != null) {
                        muxVideo = trimmed.file
                        presentationUs = (wallShutterMs - trimmed.startMs) * 1000
                    } else {
                        presentationUs = wallShutterMs * 1000
                    }
                }
                // Apple Live Photo first (JPEG MakerNote + .mov sibling, what Photos reads),
                // then Android Motion Photo v1 as the fallback when the MOV write fails.
                val live = runCatching {
                    val identifier = AppleLivePhotoWriter.newIdentifier()
                    val companion = AppleLivePhotoWriter.companionFor(still)
                    if (AppleLivePhotoWriter.writeVideo(muxVideo, companion, identifier)) {
                        AppleLivePhotoWriter.attachIdentifier(still, identifier)
                        true
                    } else {
                        companion.delete()
                        false
                    }
                }.getOrDefault(false)
                if (live) {
                    saved = still
                } else {
                    val muxResult = runCatching {
                        val muxed = File(still.parentFile, still.nameWithoutExtension + "_motion.jpg")
                        MotionPhotoMuxer.mux(still, muxVideo, muxed, presentationUs)
                        still.delete()
                        muxed
                    }
                    muxResult.onSuccess { muxed -> saved = muxed }
                    muxResult.onFailure { error ->
                        Logger.error(TAG, "Motion mux failed: ${error.message}", error)
                    }
                }
                video.delete()
                trimmed?.file?.delete()
            }
            mainHandler.post {
                onSaved(saved)
                if (motionRollingRequested) ensureMotionRolling()
            }
        }
    }

    private fun clearMotionCapture() {
        motionStill = null
        motionVideo = null
        motionOnSaved = null
        motionOnError = null
        motionAwaitingVideo = false
        motionVideoIsRolling = false
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun attachNightModeMonitor(builder: Preview.Builder) {
        if (Build.VERSION.SDK_INT < 36) return
        Camera2Interop.Extender(builder).setSessionCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val value = result.get(CaptureResult.EXTENSION_NIGHT_MODE_INDICATOR) ?: return
                    val scene = when (value) {
                        CameraMetadata.EXTENSION_NIGHT_MODE_INDICATOR_ON -> NightScene.RECOMMENDED
                        CameraMetadata.EXTENSION_NIGHT_MODE_INDICATOR_OFF -> NightScene.NOT_RECOMMENDED
                        else -> NightScene.UNKNOWN
                    }
                    if (_nightScene.value != scene) _nightScene.value = scene
                    applyLowLightPreviewBoost(scene)
                }
            }
        )
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun exposureLimits(): ExposureLimits {
        val bound = camera ?: return ExposureLimits()
        val exposure = bound.cameraInfo.exposureState
        val evSupported = exposure.isExposureCompensationSupported
        val evRange = exposure.exposureCompensationRange
        val evMin = if (evSupported) evRange.lower else 0
        val evMax = if (evSupported) evRange.upper else 0
        val evStep = if (evSupported) exposure.exposureCompensationStep.toFloat() else 0f
        if (Build.VERSION.SDK_INT < 36) {
            return ExposureLimits(
                evSupported = evSupported,
                evMin = evMin,
                evMax = evMax,
                evStep = evStep
            )
        }
        val info = Camera2CameraInfo.from(bound.cameraInfo)
        val modes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES)
        val iso = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val shutter = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val priorities = buildSet {
            add(ExposurePriority.AUTO)
            modes?.forEach { mode ->
                when (mode) {
                    CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY ->
                        add(ExposurePriority.ISO)
                    CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY ->
                        add(ExposurePriority.SHUTTER)
                }
            }
        }
        return ExposureLimits(
            isoMin = iso?.lower ?: 50,
            isoMax = iso?.upper ?: 3200,
            shutterMinNanos = shutter?.lower ?: 1_000_000L,
            shutterMaxNanos = shutter?.upper ?: 250_000_000L,
            supportedPriorities = priorities,
            evSupported = evSupported,
            evMin = evMin,
            evMax = evMax,
            evStep = evStep
        )
    }

    private fun buildColorAnalysis(rotation: Int, initial: EffectMode): ImageAnalysis {
        val analyzer = ColorEffectAnalyzer { bitmap -> _effectFrame.value = bitmap }.also {
            it.effect = initial
            colorAnalyzer = it
        }
        return ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor, analyzer)
                imageAnalysis = analysis
            }
    }

    private fun stopColorAnalysis() {
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        colorAnalyzer?.release()
        colorAnalyzer = null
        _effectFrame.value = null
    }

    private fun canRetainRecording(): Boolean =
        recording != null && videoCapture != null && preview != null && !highSpeedSession

    @OptIn(ExperimentalPersistentRecording::class)
    private fun persistentPending(pending: PendingRecording, persist: Boolean): PendingRecording {
        if (!persist || highSpeedSession || motionAwaitingVideo) return pending
        return pending.asPersistentRecording()
    }

    private fun rebindWhileRecording(
        host: PreviewViewHost,
        config: CameraBindConfig
    ): CameraBindResult {
        val lifecycleOwner = host.lifecycleOwner
        val previewView = host.previewView
        boundPreviewView = previewView
        val provider = cameraProvider ?: throw IllegalStateException("Camera provider missing")
        val previewUseCase = preview ?: throw IllegalStateException("Preview missing")
        val video = videoCapture ?: throw IllegalStateException("Video capture missing")
        previewUseCase.surfaceProvider = previewView.surfaceProvider
        val selector = selectorFor(config)
        provider.unbindAll()
        camera = try {
            val group = UseCaseGroup.Builder()
                .addUseCase(previewUseCase)
                .addUseCase(video)
                .also { builder ->
                    previewView.viewPort?.let { builder.setViewPort(it) }
                }
                .build()
            provider.bindToLifecycle(lifecycleOwner, selector, group)
        } catch (error: Exception) {
            Logger.warning(TAG, "Flip while recording failed: ${error.message}")
            throw error
        }
        return finishBind(
            config = config,
            slowMotionSupported = isHighSpeedSupported(camera?.cameraInfo),
            slowMotionFps = 0
        )
    }

    private fun bindWithFallback(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        selector: CameraSelector,
        preview: Preview?,
        imageCapture: ImageCapture,
        videoCapture: VideoCapture<Recorder>?,
        imageAnalysis: ImageAnalysis?,
        fps60: Boolean = false
    ): Camera {
        val attempts = mutableListOf<List<UseCase>>()
        if (imageAnalysis != null) {
            attempts += listOfNotNull(preview, imageCapture, imageAnalysis)
            attempts += listOf(imageCapture, imageAnalysis)
        } else {
            attempts += listOfNotNull(preview, imageCapture, videoCapture)
            attempts += listOfNotNull(preview, imageCapture)
        }
        var lastError: Exception? = null
        for (useCases in attempts) {
            try {
                provider.unbindAll()
                val bound = bindSession(
                    provider = provider,
                    lifecycleOwner = lifecycleOwner,
                    selector = selector,
                    useCases = useCases,
                    fps60 = fps60 && videoCapture != null && useCases.contains(videoCapture)
                )
                this.videoCapture = videoCapture?.takeIf { capture -> useCases.contains(capture) }
                this.imageCapture = imageCapture.takeIf { capture -> useCases.contains(capture) }
                this.preview = preview?.takeIf { useCases.contains(it) }
                lastStillsOnlyFallback = videoCapture != null &&
                    !useCases.contains(videoCapture)
                if (imageAnalysis == null || !useCases.contains(imageAnalysis)) {
                    stopColorAnalysis()
                }
                Logger.debug(TAG, "Bound use cases: ${useCases.map { it.javaClass.simpleName }}")
                return bound
            } catch (error: Exception) {
                lastError = error
                Logger.warning(TAG, "Bind attempt failed: ${error.message}")
            }
        }
        throw lastError ?: IllegalStateException("Unable to bind camera")
    }

    private fun bindSession(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        selector: CameraSelector,
        useCases: List<UseCase>,
        fps60: Boolean
    ): Camera {
        val groupBuilder = UseCaseGroup.Builder().apply {
            useCases.forEach { addUseCase(it) }
            boundPreviewView?.viewPort?.let { setViewPort(it) }
        }
        if (fps60) {
            try {
                val builder = SessionConfig.Builder(*useCases.toTypedArray())
                    .setRequiredFeatureGroup(GroupableFeature.FPS_60)
                val bound = provider.bindToLifecycle(lifecycleOwner, selector, builder.build())
                fps60Active = true
                return bound
            } catch (error: Exception) {
                Logger.warning(TAG, "60 fps feature group bind failed: ${error.message}")
            }
        }
        fps60Active = false
        return provider.bindToLifecycle(lifecycleOwner, selector, groupBuilder.build())
    }

    private fun bindConcurrent(
        provider: ProcessCameraProvider,
        host: PreviewViewHost,
        config: CameraBindConfig,
        rotation: Int
    ): CameraBindResult {
        val group = provider.concurrentFrontBackGroup()
            ?: throw IllegalStateException("Dual camera is not supported on this device")
        val backInfo = group.firstOrNull { info ->
            info.lensFacing == CameraSelector.LENS_FACING_BACK
        } ?: throw IllegalStateException("Dual camera is not supported on this device")
        val frontInfo = group.firstOrNull { info ->
            info.lensFacing == CameraSelector.LENS_FACING_FRONT
        } ?: throw IllegalStateException("Dual camera is not supported on this device")

        // Fit the 16:9 composed frame; FILL_CENTER can crop away the primary stream on tall phones.
        host.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        val streams = concurrentStreamSelector()
        val previewUseCase = Preview.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(streams)
            .build()
            .also { it.surfaceProvider = host.previewView.surfaceProvider }
        preview = previewUseCase

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .build()
        // Composition mode ignores VideoCapture mirrorMode; stream mirrors with Preview.
        val video = VideoCapture.Builder(recorder)
            .setTargetRotation(rotation)
            .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
            .build()

        val sharedBuilder = UseCaseGroup.Builder()
            .addUseCase(previewUseCase)
            .addUseCase(video)
        // Align composition output with the PreviewView aspect so both cameras fill the frame.
        host.previewView.viewPort?.let(sharedBuilder::setViewPort)
        val shared = sharedBuilder.build()
        val backComposition = CompositionSettings.DEFAULT
        // NDC: origin center, +X right, +Y up. Equal scale avoids squashing the front stream.
        val (pipNdcX, pipNdcY) = DualComposition.pipCenterNdc(pipCorner)
        val frontComposition = CompositionSettings.Builder()
            .setAlpha(1f)
            .setOffset(pipNdcX, pipNdcY)
            .setScale(DualComposition.PIP_SCALE * pipScale, DualComposition.PIP_SCALE * pipScale)
            .build()
        val configs = listOf(
            SingleCameraConfig(
                backInfo.toConcurrentSelector(),
                shared,
                backComposition,
                host.lifecycleOwner
            ),
            SingleCameraConfig(
                frontInfo.toConcurrentSelector(),
                shared,
                frontComposition,
                host.lifecycleOwner
            )
        )

        imageCapture = null
        val concurrent = try {
            videoCapture = video
            provider.bindToLifecycle(configs)
        } catch (error: Exception) {
            Logger.warning(TAG, "Dual composition bind failed: ${error.message}")
            videoCapture = null
            preview = null
            provider.unbindAll()
            throw IllegalStateException("Dual camera is not supported on this device", error)
        }
        camera = concurrent.cameras.firstOrNull { bound ->
            bound.cameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK
        } ?: concurrent.cameras.first()
        fps60Active = false
        stillFormat = StillFormat.JPEG
        lastBoundKind = BoundSessionKind.CONCURRENT
        lastBoundExtension = CameraExtension.NONE
        lastRawFullSensor = false
        lastStillsOnlyFallback = false
        Logger.debug(
            TAG,
            "Bound Dual composition: ${concurrent.cameras.map { bound -> bound.cameraInfo.lensFacing }}"
        )
        return finishBind(
            config = config,
            slowMotionSupported = isHighSpeedSupported(camera?.cameraInfo),
            slowMotionFps = 0
        )
    }

    /**
     * Simultaneous Dual photo tier: non-composition [androidx.camera.core.ConcurrentCamera] —
     * each camera gets its own UseCaseGroup (primary Preview + ImageCapture, secondary
     * ImageCapture only), because the shared-stream composition mode cannot bind ImageCapture.
     * Returns false when the device has no concurrent front+back pair, the graph failed once
     * before ([dualPhotoSequentialOnly]), or the bind throws; caller uses the sequential tier.
     */
    private fun bindDualPhoto(
        provider: ProcessCameraProvider,
        host: PreviewViewHost,
        config: CameraBindConfig,
        rotation: Int
    ): Boolean {
        if (dualPhotoSequentialOnly) return false
        val group = provider.concurrentFrontBackGroup()?.takeIf { isDualCameraSupported(context, provider) }
        if (group == null) {
            dualPhotoSequentialOnly = true
            return false
        }
        val backInfo = group.firstOrNull { info ->
            info.lensFacing == CameraSelector.LENS_FACING_BACK
        }
        val frontInfo = group.firstOrNull { info ->
            info.lensFacing == CameraSelector.LENS_FACING_FRONT
        }
        if (backInfo == null || frontInfo == null) {
            dualPhotoSequentialOnly = true
            return false
        }
        val frontPrimary = config.lens == CameraLens.FRONT
        val primaryInfo = if (frontPrimary) frontInfo else backInfo
        val secondaryInfo = if (frontPrimary) backInfo else frontInfo

        val previewUseCase = Preview.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(config.captureAspect.toResolutionSelector())
            .build()
            .also { it.surfaceProvider = host.previewView.surfaceProvider }
        val primaryCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(config.flash.toImageCaptureMode())
            .setTargetRotation(rotation)
            .setResolutionSelector(config.captureAspect.toResolutionSelector())
            .build()
        val secondaryCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(config.flash.toImageCaptureMode())
            .setTargetRotation(rotation)
            .setResolutionSelector(config.captureAspect.toResolutionSelector())
            .build()
        val primaryGroup = UseCaseGroup.Builder()
            .addUseCase(previewUseCase)
            .addUseCase(primaryCapture)
            .also { builder -> host.previewView.viewPort?.let { builder.setViewPort(it) } }
            .build()
        val secondaryGroup = UseCaseGroup.Builder()
            .addUseCase(secondaryCapture)
            .build()
        val configs = listOf(
            SingleCameraConfig(
                primaryInfo.toConcurrentSelector(),
                primaryGroup,
                CompositionSettings.DEFAULT,
                host.lifecycleOwner
            ),
            SingleCameraConfig(
                secondaryInfo.toConcurrentSelector(),
                secondaryGroup,
                CompositionSettings.DEFAULT,
                host.lifecycleOwner
            )
        )

        return try {
            provider.unbindAll()
            val concurrent = provider.bindToLifecycle(configs)
            val boundPrimary = concurrent.cameras.firstOrNull { bound ->
                bound.cameraInfo.lensFacing ==
                    (if (frontPrimary) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
            } ?: throw IllegalStateException("Dual photo bound no primary camera")
            camera = boundPrimary
            preview = previewUseCase
            imageCapture = primaryCapture
            dualImageCapture = secondaryCapture
            dualSecondaryLens = if (frontPrimary) CameraLens.BACK else CameraLens.FRONT
            dualPhotoSimultaneous = true
            videoCapture = null
            fps60Active = false
            stillFormat = StillFormat.JPEG
            lastBoundKind = BoundSessionKind.DUAL_PHOTO
            lastBoundExtension = CameraExtension.NONE
            lastRawFullSensor = false
            lastStillsOnlyFallback = false
            Logger.debug(
                TAG,
                "Bound dual photo simultaneously: primary=${config.lens} secondary=$dualSecondaryLens"
            )
            true
        } catch (error: Exception) {
            Logger.warning(
                TAG,
                "Dual photo simultaneous bind failed: ${error.message}; using sequential capture"
            )
            provider.unbindAll()
            preview = null
            imageCapture = null
            dualImageCapture = null
            dualSecondaryLens = null
            dualPhotoSimultaneous = false
            dualPhotoSequentialOnly = true
            false
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun CameraInfo.toConcurrentSelector(): CameraSelector {
        val id = Camera2CameraInfo.from(this).cameraId
        val facing = lensFacing
        val builder = CameraSelector.Builder().addCameraFilter { infos ->
            infos.filter { info -> Camera2CameraInfo.from(info).cameraId == id }
        }
        if (facing != CameraSelector.LENS_FACING_UNKNOWN) {
            builder.requireLensFacing(facing)
        }
        return builder.build()
    }

    private fun concurrentStreamSelector(): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()

    private fun bindHighSpeed(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        selector: CameraSelector,
        previewView: PreviewView,
        rotation: Int,
        config: CameraBindConfig
    ): HighSpeedBind? {
        val cameraInfo = runCatching { provider.getCameraInfo(selector) }.getOrNull() ?: return null
        val capabilities = Recorder.getHighSpeedVideoCapabilities(cameraInfo) ?: return null
        val supported = capabilities.getSupportedQualities(DynamicRange.SDR)
        if (supported.isEmpty()) return null
        val requested = config.slowMotionQuality.toQuality()
        val quality = (listOf(requested) + supported).firstOrNull { it in supported } ?: return null
        val previewUseCase = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality))
            .build()
        val video = VideoCapture.Builder(recorder)
            .setMirrorMode(videoMirrorMode(config.frontMirror))
            .build()
        val probe = HighSpeedVideoSessionConfig(video, previewUseCase)
        val ranges = cameraInfo.getSupportedFrameRateRanges(probe).highSpeedFrameRateRanges()
        if (ranges.isEmpty()) return null
        val requestedFps = config.slowMotionRate.fps
        val fpsRange = if (requestedFps > 0) {
            ranges.firstOrNull { it.upper == requestedFps }
                ?: ranges.maxByOrNull { it.upper }
        } else {
            ranges.maxByOrNull { it.upper }
        } ?: return null
        val session = HighSpeedVideoSessionConfig(
            videoCapture = video,
            preview = previewUseCase,
            frameRateRange = fpsRange,
            isSlowMotionEnabled = true
        )
        return try {
            provider.unbindAll()
            val bound = provider.bindToLifecycle(lifecycleOwner, selector, session)
            this.preview = previewUseCase
            this.videoCapture = video
            this.imageCapture = null
            lastBoundKind = BoundSessionKind.HIGH_SPEED
            lastBoundExtension = CameraExtension.NONE
            lastRawFullSensor = false
            lastStillsOnlyFallback = false
            HighSpeedBind(camera = bound, fps = fpsRange.upper)
        } catch (error: Exception) {
            Logger.warning(TAG, "High-speed session bind failed: ${error.message}")
            null
        }
    }

    private fun isHighSpeedSupported(info: CameraInfo?): Boolean {
        if (info == null) return false
        return info.cachedSlowMotion()?.available == true
    }

    private fun resolveVideoHdrRange(info: CameraInfo?, config: CameraBindConfig): DynamicRange {
        if (config.slowMotion || info == null) return DynamicRange.SDR
        val requested = config.videoHdrRange.toDynamicRange()
        val supported = runCatching {
            Recorder.getVideoCapabilities(info).supportedDynamicRanges
        }.getOrDefault(emptySet())
        return if (requested in supported) requested else DynamicRange.SDR
    }

    private fun isPreviewStabilizationSupported(info: CameraInfo?): Boolean {
        if (info == null) return false
        return runCatching {
            Preview.getPreviewCapabilities(info).isStabilizationSupported
        }.getOrDefault(false)
    }

    private fun isVideoStabilizationSupported(info: CameraInfo?): Boolean {
        if (info == null) return false
        return runCatching {
            Recorder.getVideoCapabilities(info).isStabilizationSupported
        }.getOrDefault(false)
    }

    private fun buildVideoCapture(
        recorder: Recorder,
        stabilize: Boolean,
        range: DynamicRange = DynamicRange.SDR,
        frontMirror: Boolean
    ): VideoCapture<Recorder> = VideoCapture.Builder(recorder)
        .setVideoStabilizationEnabled(stabilize)
        .setDynamicRange(range)
        .setMirrorMode(videoMirrorMode(frontMirror))
        .build()

    private fun videoMirrorMode(frontMirror: Boolean): Int =
        if (frontMirror) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF

    private fun shouldMirrorFrontOutput(lens: CameraLens): Boolean =
        frontMirrorEnabled && lens == CameraLens.FRONT

    private fun applyViewfinderMirror(config: CameraBindConfig) {
        applyViewfinderMirror(
            boundPreviewView,
            frontCamera = config.lens == CameraLens.FRONT && !config.concurrent,
            frontMirror = config.frontMirror
        )
    }

    private fun applyViewfinderMirror(
        previewView: PreviewView?,
        frontCamera: Boolean,
        frontMirror: Boolean
    ) {
        previewView?.scaleX = if (frontCamera && !frontMirror) -1f else 1f
    }

    private fun toBindResult(
        config: CameraBindConfig,
        slowMotionSupported: Boolean,
        slowMotionFps: Int
    ): CameraBindResult {
        val zoom = camera?.cameraInfo?.zoomState?.value
        val facing = if (config.lens == CameraLens.FRONT) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val info = camera?.cameraInfo
        return CameraBindResult(
            hasFlash = camera?.cameraInfo?.hasFlashUnit() == true,
            minZoom = zoom?.minZoomRatio ?: 1f,
            maxZoom = zoom?.maxZoomRatio ?: 1f,
            zoomRatio = zoom?.zoomRatio ?: 1f,
            videoAvailable = videoCapture != null,
            supportedExtensions = supportedExtensions(config.lens.toSelector()),
            stillFormat = stillFormat,
            ultraHdrEnabled = stillFormat == StillFormat.JPEG_ULTRA_HDR ||
                stillFormat == StillFormat.HEIC_ULTRA_HDR,
            nightIndicatorSupported = Build.VERSION.SDK_INT >= 36,
            exposureLimits = exposureLimits(),
            physicalZooms = discoverPhysicalZooms(facing),
            boundCameraId = runCatching {
                camera?.let { Camera2CameraInfo.from(it.cameraInfo).cameraId }
            }.getOrNull(),
            slowMotionSupported = slowMotionSupported,
            slowMotionFps = slowMotionFps,
            videoStabilizationSupported = isPreviewStabilizationSupported(info) ||
                isVideoStabilizationSupported(info),
            videoStabilizationActive = preview?.isPreviewStabilizationEnabled == true,
            lowLightBoostSupported = info?.isLowLightBoostSupported == true,
            videoHdrRange = videoCapture?.dynamicRange?.toVideoHdrRange() ?: VideoHdrRange.SDR,
            concurrentSupported = cameraProvider?.let { provider ->
                isDualCameraSupported(context, provider)
            } == true,
            dualPhotoSupported = cameraProvider?.availableCameraInfos?.let { infos ->
                infos.any { info -> info.lensFacing == CameraSelector.LENS_FACING_BACK } &&
                    infos.any { info -> info.lensFacing == CameraSelector.LENS_FACING_FRONT }
            } == true,
            videoFps60Supported = info?.cachedVideoFps60() == true,
            videoFps60Active = fps60Active,
            session = BoundSession(
                kind = lastBoundKind,
                preview = preview != null,
                stills = imageCapture != null,
                video = videoCapture != null,
                analysis = imageAnalysis != null,
                stillsOnlyFallback = lastStillsOnlyFallback,
                cameraId = runCatching {
                    camera?.let { Camera2CameraInfo.from(it.cameraInfo).cameraId }
                }.getOrNull(),
                lens = config.lens,
                extension = lastBoundExtension,
                stillFormat = stillFormat,
                videoHdr = videoCapture?.dynamicRange?.toVideoHdrRange() ?: VideoHdrRange.SDR,
                videoFps60 = fps60Active,
                videoStabilization = preview?.isPreviewStabilizationEnabled == true,
                rawFullSensor = lastRawFullSensor
            )
        )
    }

    private fun finishBind(
        config: CameraBindConfig,
        slowMotionSupported: Boolean,
        slowMotionFps: Int
    ): CameraBindResult {
        flashMode = config.flash
        frontMirrorEnabled = config.frontMirror
        lowLightBoostRequested = config.lowLightBoost
        applyViewfinderMirror(config)
        applyTorch(config.flash)
        watchLowLightBoost()
        applyLowLightBoost()
        // Rolling motion pre-roll starts only after the new use cases are live.
        if (motionRollingRequested && !motionAwaitingVideo) {
            mainHandler.post { ensureMotionRolling() }
        }
        return toBindResult(config, slowMotionSupported, slowMotionFps)
    }

    private fun watchLowLightBoost() {
        stopWatchingLowLightBoost()
        val live = camera?.cameraInfo?.lowLightBoostState ?: run {
            _lowLightBoost.value = LowLightBoost.OFF
            return
        }
        lowLightBoostLiveData = live
        live.observeForever(lowLightBoostObserver)
        _lowLightBoost.value = when (live.value) {
            LowLightBoostState.ACTIVE -> LowLightBoost.ACTIVE
            LowLightBoostState.INACTIVE -> LowLightBoost.INACTIVE
            else -> LowLightBoost.OFF
        }
    }

    private fun stopWatchingLowLightBoost() {
        lowLightBoostLiveData?.removeObserver(lowLightBoostObserver)
        lowLightBoostLiveData = null
    }

    private fun applyLowLightBoost() {
        val cam = camera ?: return
        val supported = cam.cameraInfo.isLowLightBoostSupported
        val enable = lowLightBoostRequested &&
            supported &&
            !highSpeedSession &&
            flashMode != FlashMode.TORCH
        if (!supported && !enable) {
            _lowLightBoost.value = LowLightBoost.OFF
            return
        }
        val future = cam.cameraControl.enableLowLightBoostAsync(enable)
        future.addListener({
            runCatching { future.get() }.onFailure { error ->
                Logger.warning(TAG, "Low light boost failed: ${error.message}")
            }
        }, cameraExecutor)
    }

    private data class HighSpeedBind(val camera: Camera, val fps: Int)

    private fun applyStillOutput(file: File, type: EffectMode): File {
        if (type == EffectMode.NONE) return file
        return try {
            StillImageExif.rewriteJpeg(file, quality = 95, maxEdge = MAX_STILL_EDGE) { oriented ->
                ColorEffects.applyToBitmap(oriented, type)
            }
        } catch (error: Throwable) {
            Logger.error(TAG, "Still effect failed: ${error.message}", error)
            file
        }
    }

    private fun createFile(baseFolder: File, extension: String): File {
        val name = SimpleDateFormat(Constants.FILE.FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        return File(baseFolder, name + extension)
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                continuation.resume(future.get())
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }, cameraExecutor)
    }

    private suspend fun awaitExtensions(provider: ProcessCameraProvider): ExtensionsManager =
        suspendCoroutine { continuation ->
            val future = ExtensionsManager.getInstanceAsync(context, provider)
            future.addListener({
                try {
                    continuation.resume(future.get())
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            }, cameraExecutor)
        }

    companion object {
        private const val TAG = "CameraSession"
        private const val MOTION_DURATION_MS = 1_500L
        /** Rolling pre-roll clip restart cadence (bounds cache size; only last 1.5 s used). */
        private const val MOTION_ROLLING_RESTART_MS = 20_000L
        /** Stale rolling clips older than this are pruned from the app cache. */
        private const val PREROLL_MAX_AGE_MS = 60_000L
        /** Cap decoded still edge length when applying color effects. */
        private const val MAX_STILL_EDGE = 8192
        /** Reserve 100 MB of free space when setting recording file size limits. */
        private const val STORAGE_RESERVE_BYTES = 100L * 1024 * 1024
        /** Minimum recording size limit (10 MB) even when storage is critically low. */
        private const val MIN_RECORDING_BYTES = 10L * 1024 * 1024
    }
}
