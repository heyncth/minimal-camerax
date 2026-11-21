package com.arindam.camerax.ui.home.camera

import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arindam.camerax.R
import com.arindam.camerax.data.camera.DualComposition
import com.arindam.camerax.domain.model.CameraMode
import com.arindam.camerax.domain.model.CaptureAspect
import com.arindam.camerax.domain.model.PipCorner
import com.arindam.camerax.ui.compose.CameraAlertDialog
import com.arindam.camerax.ui.theme.AppTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Compose viewfinder: a letterboxed [PreviewView] (4:3 stills, 16:9 video) plus the iPhone-style
 * chrome from [CameraChrome]. Gestures (focus / pinch / drag zoom) stay here; capture goes
 * through [CameraViewModel].
 */
@Composable
fun CameraScreen(
    onGalleryClicked: () -> Unit,
    onSettingsClicked: () -> Unit,
    onExternalCaptureReady: (File) -> Unit,
    viewModel: CameraViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val inspection = LocalInspectionMode.current
    val configuration = LocalConfiguration.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            isClickable = false
            isFocusable = false
        }
    }

    var showActiveCaptureExitDialog by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = state.isRecording) { showActiveCaptureExitDialog = true }
    LaunchedEffect(state.isRecording) {
        if (!state.isRecording) showActiveCaptureExitDialog = false
    }
    LaunchedEffect(
        state.bindRevision,
        state.lens,
        state.extension,
        state.mode,
        previewView
    ) {
        if (inspection) return@LaunchedEffect
        viewModel.bind(lifecycleOwner, previewView)
        previewView.display?.rotation?.let(viewModel::updateTargetRotation)
    }
    LaunchedEffect(configuration.orientation, configuration.screenWidthDp) {
        if (!inspection) {
            previewView.display?.rotation?.let(viewModel::updateTargetRotation)
        }
    }
    LaunchedEffect(state.message, state.messageRes) {
        val text = state.message
            ?: state.messageRes?.let { context.getString(it) }
            ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        viewModel.consumeMessage()
    }
    LaunchedEffect(viewModel) {
        viewModel.externalCaptureReady.collect(onExternalCaptureReady)
    }

    DisposableEffect(previewView) {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (previewView.display?.displayId == displayId) {
                    previewView.display?.rotation?.let(viewModel::updateTargetRotation)
                }
            }
        }
        displayManager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displayManager?.unregisterDisplayListener(listener) }
    }
    DisposableEffect(context) {
        val orientationEventListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                viewModel.updateTargetRotation(rotation)
            }
        }
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
        }
        onDispose { orientationEventListener.disable() }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onHostStopped()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val compact = maxHeight < 480.dp ||
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val aspect = previewAspect(state)
        Column(Modifier.fillMaxSize()) {
            CameraHeader(
                state = state,
                compact = compact,
                onFlashClicked = viewModel::cycleFlash,
                onTimerClicked = viewModel::cycleTimer,
                onLiveClicked = viewModel::toggleMotionPhoto,
                onDualPhotoClicked = viewModel::toggleDualPhoto,
                onSettingsClicked = onSettingsClicked
            )
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Letterbox the viewfinder to the capture aspect (iPhone shows 4:3 in Photo).
                val width = minOf(maxWidth, maxHeight * aspect)
                val height = width / aspect
                var viewport by remember { mutableStateOf(IntSize.Zero) }
                Box(
                    modifier = Modifier
                        .size(width, height)
                        .clipToBounds()
                        .onSizeChanged { viewport = it }
                ) {
                    if (!inspection) {
                        AndroidView(
                            factory = { previewView },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    PreviewGestures(
                        previewView = previewView,
                        enabled = !inspection,
                        zoomEnabled = state.showsZoomChips,
                        onZoomStart = viewModel::beginZoomGesture,
                        onPinch = viewModel::zoomByPinch,
                        onZoomDrag = viewModel::zoomByDrag,
                        onTap = { focus -> viewModel.tapToFocus(previewView, focus) }
                    )
                    if (state.gridEnabled) RuleOfThirdsGrid()
                    FocusRing(state.focusPoint)
                    if (state.showsDualPhotoOutline) {
                        DualPipOverlay(
                            state = state,
                            viewport = viewport,
                            onCornerChange = viewModel::setPipCorner,
                            onScaleChange = viewModel::setPipScale,
                            modifier = Modifier.zIndex(1f)
                        )
                    }
                    CountdownOverlay(state.countdownRemaining)
                }
            }
            CameraFooter(
                state = state,
                compact = compact,
                onModeSelected = viewModel::setMode,
                onFlipClicked = viewModel::toggleLens,
                onShutterClicked = { viewModel.onShutter(previewView) },
                onGalleryClicked = onGalleryClicked,
                onZoomSelected = viewModel::setZoom
            )
        }
        state.review?.let { review ->
            AppTheme {
                CaptureConfirmOverlay(
                    review = review,
                    onRetake = viewModel::retakeCapture,
                    onKeep = viewModel::keepCapture
                )
            }
        }
        CameraAlertDialog(
            show = showActiveCaptureExitDialog,
            title = stringResource(R.string.active_capture_exit_title),
            text = stringResource(R.string.active_capture_exit_body),
            confirmLabel = stringResource(R.string.active_capture_save),
            dismissLabel = stringResource(R.string.active_capture_discard),
            onDismiss = { showActiveCaptureExitDialog = false },
            onDismissLabel = viewModel::discardActiveCapture,
            onConfirm = viewModel::saveActiveCapture
        )
    }
}

/** Viewfinder aspect: 16:9 for video, otherwise the still capture aspect (4:3 default). */
private fun previewAspect(state: CameraUiState): Float = when {
    state.mode == CameraMode.VIDEO -> 16f / 9f
    state.captureAspect == CaptureAspect.RATIO_16_9 -> 16f / 9f
    else -> 4f / 3f
}

/**
 * Tap-to-focus plus pinch / vertical-drag zoom on the viewfinder. The gesture layer is exactly the
 * [PreviewView] box, so pointer coordinates map 1:1 to metering coordinates.
 */
@Composable
private fun PreviewGestures(
    previewView: PreviewView,
    enabled: Boolean,
    zoomEnabled: Boolean,
    onZoomStart: () -> Unit,
    onPinch: (Float) -> Unit,
    onZoomDrag: (Float, Float) -> Unit,
    onTap: (Offset) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (!enabled) {
                    Modifier
                } else {
                    Modifier.pointerInput(previewView, zoomEnabled) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            val start = down.position
                            val slop = viewConfiguration.touchSlop
                            var dragged = false
                            var pinching = false
                            var cumulativeZoom = 1f
                            if (zoomEnabled) onZoomStart()
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (zoomEnabled && pressed.size >= 2) {
                                    pinching = true
                                    dragged = true
                                    cumulativeZoom *= event.calculateZoom()
                                    onPinch(cumulativeZoom)
                                    pressed.forEach { change ->
                                        if (change.positionChanged()) change.consume()
                                    }
                                } else if (zoomEnabled && !pinching) {
                                    val pointer = pressed.first()
                                    val dx = pointer.position.x - start.x
                                    val dy = pointer.position.y - start.y
                                    if (abs(dx) > slop || abs(dy) > slop) {
                                        if (abs(dy) >= abs(dx)) {
                                            dragged = true
                                            onZoomDrag(dy, size.height.toFloat())
                                            if (pointer.positionChanged()) pointer.consume()
                                        } else {
                                            dragged = true
                                            break
                                        }
                                    }
                                } else {
                                    val pointer = pressed.first()
                                    val dx = pointer.position.x - start.x
                                    val dy = pointer.position.y - start.y
                                    if (abs(dx) > slop || abs(dy) > slop) dragged = true
                                }
                            }
                            if (!dragged && !pinching) onTap(start)
                        }
                    }
                }
            )
    )
}

/**
 * Draggable + pinchable Dual Photo PiP outline at the merge position ([DualComposition]).
 *
 * Drag follows the finger 1:1 (no corner snapping mid-gesture), release springs onto the
 * nearest corner with a haptic. The gesture never restarts on corner/scale changes — earlier
 * versions keyed `pointerInput` on those values, which cancelled drags halfway through.
 */
@Composable
private fun DualPipOverlay(
    state: CameraUiState,
    viewport: IntSize,
    onCornerChange: (PipCorner) -> Unit,
    onScaleChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    if (viewport.width <= 0 || viewport.height <= 0) return
    val density = LocalDensity.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val cornerState = rememberUpdatedState(state.pipCorner)
    val committedScale = rememberUpdatedState(state.pipScale)
    val baseWidth = viewport.width.toFloat()
    val baseHeight = viewport.height.toFloat()
    val insetAspect = baseHeight / baseWidth

    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var liveScale by remember { mutableFloatStateOf(state.pipScale) }
    var snapJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(state.pipScale) { liveScale = state.pipScale }

    val rect = DualComposition.pipRect(
        baseWidth = baseWidth,
        baseHeight = baseHeight,
        insetAspect = insetAspect,
        corner = cornerState.value,
        scale = liveScale
    )
    val pipWidth = rect.width()
    val pipHeight = rect.height()
    val radius = minOf(pipWidth, pipHeight) * DualComposition.PIP_RADIUS_FRACTION
    val stroke = (minOf(baseWidth, baseHeight) * DualComposition.BORDER_FRACTION)
        .coerceAtLeast(2f)
    val left = (rect.left + dragOffset.x)
        .coerceIn(0f, (baseWidth - pipWidth).coerceAtLeast(0f))
    val top = (rect.top + dragOffset.y)
        .coerceIn(0f, (baseHeight - pipHeight).coerceAtLeast(0f))

    Box(
        modifier = modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(
                with(density) { pipWidth.toDp() },
                with(density) { pipHeight.toDp() }
            )
            .pointerInput(baseWidth, baseHeight, insetAspect) {
                awaitEachGesture {
                    // Consume the down so tap-to-focus / pinch zoom below never sees touches
                    // that start on the outline.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    snapJob?.cancel()
                    snapJob = null

                    val cornerAtStart = cornerState.value
                    val scaleAtStart = liveScale
                    val startRect = DualComposition.pipRect(
                        baseWidth = baseWidth,
                        baseHeight = baseHeight,
                        insetAspect = insetAspect,
                        corner = cornerAtStart,
                        scale = scaleAtStart
                    )
                    val slop = viewConfiguration.touchSlop
                    var mode = 0 // 0 = undecided, 1 = drag, 2 = pinch
                    var moved = Offset.Zero
                    var last = down.position
                    var zoom = 1f
                    var pinchScale = scaleAtStart
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            mode = 2
                            zoom *= event.calculateZoom()
                            pinchScale = (scaleAtStart * zoom).coerceIn(0.55f, 1.9f)
                            if (pinchScale != liveScale) liveScale = pinchScale
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        } else if (mode == 2) {
                            // One finger of a pinch lifted — keep the scale, ignore strays.
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        } else {
                            val pointer = pressed.first()
                            val delta = pointer.position - last
                            last = pointer.position
                            if (mode == 1 ||
                                abs(moved.x + delta.x) > slop ||
                                abs(moved.y + delta.y) > slop
                            ) {
                                mode = 1
                                moved += delta
                                dragOffset = moved
                                if (pointer.positionChanged()) pointer.consume()
                            }
                        }
                    }
                    when (mode) {
                        1 -> {
                            val snap = DualComposition.nearestCorner(
                                x = startRect.left + moved.x + startRect.width() / 2f,
                                y = startRect.top + moved.y + startRect.height() / 2f,
                                baseWidth = baseWidth,
                                baseHeight = baseHeight
                            )
                            val newRect = DualComposition.pipRect(
                                baseWidth = baseWidth,
                                baseHeight = baseHeight,
                                insetAspect = insetAspect,
                                corner = snap,
                                scale = scaleAtStart
                            )
                            // Keep the outline under the finger, then spring onto the corner.
                            val preserved = Offset(
                                startRect.left + moved.x - newRect.left,
                                startRect.top + moved.y - newRect.top
                            )
                            dragOffset = preserved
                            if (snap != cornerAtStart) onCornerChange(snap)
                            haptics.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                            )
                            snapJob = scope.launch {
                                animate(
                                    initialValue = 1f,
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                ) { progress, _ ->
                                    dragOffset = preserved * progress
                                }
                            }
                        }

                        2 -> if (abs(pinchScale - committedScale.value) > 0.001f) {
                            onScaleChange(pinchScale)
                        }
                    }
                }
            }
            .drawBehind {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.9f),
                    topLeft = Offset.Zero,
                    size = Size(pipWidth, pipHeight),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = stroke)
                )
            }
    )
}
