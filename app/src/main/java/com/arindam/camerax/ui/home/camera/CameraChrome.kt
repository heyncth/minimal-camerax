package com.arindam.camerax.ui.home.camera

import android.media.MediaMetadataRetriever
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Timer3
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.arindam.camerax.R
import com.arindam.camerax.domain.model.CameraMode
import com.arindam.camerax.domain.model.CaptureAction
import com.arindam.camerax.domain.model.FlashMode
import com.arindam.camerax.domain.model.TimerMode
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Presentation: iPhone-style camera chrome. No glass, no accent colour, no technical badges —
 * one top row, a mode strip, zoom pills and the shutter. Visibility flags come from
 * [com.arindam.camerax.domain.model.CameraModeProfile] via [CameraUiState].
 */

private val IphoneYellow = Color(0xFFFFD60A)
private val ChromeWhite = Color.White
private val ChromeInactive = Color.White.copy(alpha = 0.72f)
private val ShutterRed = Color(0xFFFF3B30)

/** Top row: flash, DUAL / LIVE / timer chips and the settings gear. Recording shows elapsed time. */
@Composable
fun CameraHeader(
    state: CameraUiState,
    compact: Boolean = false,
    onFlashClicked: () -> Unit,
    onTimerClicked: () -> Unit,
    onLiveClicked: () -> Unit,
    onDualPhotoClicked: () -> Unit,
    onSettingsClicked: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                )
            )
            .padding(start = 20.dp, end = 20.dp, top = 8.dp)
            .height(if (compact) 40.dp else 44.dp),
        contentAlignment = Alignment.Center
    ) {
        if (state.isRecording) {
            RecordingIndicator(state.recordingNanos)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.showsFlash) {
                    HeaderIcon(
                        icon = flashIcon(state.flash),
                        description = stringResource(state.flash.labelRes),
                        active = state.flash != FlashMode.OFF,
                        onClick = onFlashClicked
                    )
                } else {
                    Spacer(Modifier.width(44.dp))
                }
                Spacer(Modifier.weight(1f))
                if (state.showsDualPhotoToggle) {
                    HeaderText(
                        text = stringResource(R.string.dual_label),
                        description = stringResource(
                            if (state.mode == CameraMode.DUAL_PHOTO) {
                                R.string.dual_photo_on
                            } else {
                                R.string.dual_photo_off
                            }
                        ),
                        active = state.mode == CameraMode.DUAL_PHOTO,
                        onClick = onDualPhotoClicked
                    )
                    Spacer(Modifier.width(16.dp))
                }
                if (state.showsMotion) {
                    HeaderText(
                        text = stringResource(R.string.live_label),
                        description = stringResource(
                            if (state.motionPhotoEnabled) {
                                R.string.motion_photo_on
                            } else {
                                R.string.motion_photo_off
                            }
                        ),
                        active = state.motionPhotoEnabled,
                        onClick = onLiveClicked
                    )
                    Spacer(Modifier.width(16.dp))
                }
                if (state.showsTimer) {
                    HeaderIcon(
                        icon = timerIcon(state.timer),
                        description = stringResource(state.timer.labelRes),
                        active = state.timer != TimerMode.OFF,
                        onClick = onTimerClicked
                    )
                    Spacer(Modifier.width(4.dp))
                }
                HeaderIcon(
                    icon = Icons.Filled.Settings,
                    description = stringResource(R.string.settings),
                    active = false,
                    onClick = onSettingsClicked
                )
            }
        }
    }
}

@Composable
private fun HeaderIcon(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) IphoneYellow else ChromeWhite,
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
private fun HeaderText(
    text: String,
    description: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (active) IphoneYellow else ChromeWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp
        )
    }
}

@Composable
private fun RecordingIndicator(nanos: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(ShutterRed)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatRecordingTime(nanos),
            color = ChromeWhite,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** Bottom chrome: zoom pills, PHOTO · VIDEO strip, gallery / shutter / flip row. */
@Composable
fun CameraFooter(
    state: CameraUiState,
    compact: Boolean = false,
    onModeSelected: (CameraMode) -> Unit,
    onFlipClicked: () -> Unit,
    onShutterClicked: () -> Unit,
    onGalleryClicked: () -> Unit,
    onZoomSelected: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.union(WindowInsets.systemGestures).only(
                    WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                )
            )
            .padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state.showsZoomChips && state.zoomChips.size >= 2) {
            ZoomPills(
                chips = state.zoomChips,
                active = state.activeZoomChip,
                onZoomSelected = onZoomSelected
            )
            Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
        }
        if (!state.isRecording && state.visibleModes.size > 1) {
            ModeStrip(
                modes = state.visibleModes,
                selected = state.mode,
                onModeSelected = onModeSelected
            )
            Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
        } else {
            Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GalleryThumb(file = state.thumbnail, onClick = onGalleryClicked)
            Spacer(Modifier.weight(1f))
            ShutterButton(
                state = state,
                onClick = onShutterClicked,
                size = if (compact) 64.dp else 76.dp
            )
            Spacer(Modifier.weight(1f))
            if (state.showsFlipControl) {
                FlipButton(onClick = onFlipClicked)
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun ModeStrip(
    modes: List<CameraMode>,
    selected: CameraMode,
    onModeSelected: (CameraMode) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
        modes.forEach { mode ->
            // Dual Photo is a Photo-header toggle, so the Photo label stays lit while it is on.
            val isSelected = mode == selected ||
                (selected == CameraMode.DUAL_PHOTO && mode == CameraMode.PHOTO)
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onModeSelected(mode) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(mode.labelRes).uppercase(Locale.getDefault()),
                    color = if (isSelected) IphoneYellow else ChromeInactive,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp
                )
            }
        }
    }
}

@Composable
private fun ZoomPills(
    chips: List<Float>,
    active: Float,
    onZoomSelected: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.32f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEach { ratio ->
            val isActive = kotlin.math.abs(ratio - active) < 0.05f
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isActive) ChromeWhite else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onZoomSelected(ratio) }
                    )
                    .semantics { contentDescription = zoomDescription(ratio) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = zoomLabel(ratio),
                    color = if (isActive) Color.Black else ChromeWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun ShutterButton(
    state: CameraUiState,
    onClick: () -> Unit,
    size: Dp = 76.dp
) {
    val recording = state.isRecording
    val video = state.captureAction == CaptureAction.VIDEO
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "shutterScale"
    )
    val description = stringResource(R.string.shutter)
    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .border(4.dp, ChromeWhite, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        when {
            recording -> Box(
                Modifier
                    .size(size * 0.4f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ShutterRed)
            )

            video -> Box(
                Modifier
                    .size(size * 0.72f)
                    .clip(CircleShape)
                    .background(ShutterRed)
            )

            else -> Box(
                Modifier
                    .size(size * 0.84f)
                    .clip(CircleShape)
                    .background(ChromeWhite)
            )
        }
    }
}

@Composable
private fun FlipButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Cameraswitch,
            contentDescription = stringResource(R.string.switch_camera),
            tint = ChromeWhite,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun GalleryThumb(
    file: File?,
    onClick: () -> Unit
) {
    var model by remember(file) { mutableStateOf<Any?>(null) }
    LaunchedEffect(file) {
        model = withContext(Dispatchers.IO) { file?.let { mediaThumbnail(it) } }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(48.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.28f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = stringResource(R.string.gallery_button_alt),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Photo,
                contentDescription = stringResource(R.string.gallery_button_alt),
                tint = ChromeWhite.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun RuleOfThirdsGrid() {
    Canvas(Modifier.fillMaxSize()) {
        val color = Color.White.copy(alpha = 0.24f)
        val stroke = 1.dp.toPx()
        drawLine(color, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), stroke)
        drawLine(
            color,
            Offset(size.width * 2f / 3f, 0f),
            Offset(size.width * 2f / 3f, size.height),
            stroke
        )
        drawLine(color, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), stroke)
        drawLine(
            color,
            Offset(0f, size.height * 2f / 3f),
            Offset(size.width, size.height * 2f / 3f),
            stroke
        )
    }
}

@Composable
fun FocusRing(point: Offset?) {
    AnimatedVisibility(
        visible = point != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        if (point == null) return@AnimatedVisibility
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = IphoneYellow,
                topLeft = Offset(point.x - 34.dp.toPx(), point.y - 34.dp.toPx()),
                size = Size(68.dp.toPx(), 68.dp.toPx()),
                cornerRadius = CornerRadius(10.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}

@Composable
fun CountdownOverlay(value: Int?) {
    if (value == null) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = value.toString(),
            color = ChromeWhite,
            fontSize = 96.sp,
            fontWeight = FontWeight.Light
        )
    }
}

private fun flashIcon(mode: FlashMode): ImageVector = when (mode) {
    FlashMode.OFF -> Icons.Filled.FlashOff
    FlashMode.ON -> Icons.Filled.FlashOn
    FlashMode.AUTO -> Icons.Filled.FlashAuto
    FlashMode.TORCH -> Icons.Filled.FlashlightOn
}

private fun timerIcon(mode: TimerMode): ImageVector = when (mode) {
    TimerMode.OFF -> Icons.Filled.TimerOff
    TimerMode.THREE -> Icons.Filled.Timer3
    TimerMode.TEN -> Icons.Filled.Timer
}

/** ".5", "1", "2" — iPhone zoom pill labels (no trailing x, no leading zero). */
private fun zoomLabel(ratio: Float): String {
    val text = if (kotlin.math.abs(ratio - ratio.toInt()) < 0.05f) {
        ratio.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", ratio)
    }
    return text.removePrefix("0")
}

private fun zoomDescription(ratio: Float): String =
    String.format(Locale.US, "%.1fx", ratio)

internal fun mediaThumbnail(file: File?): Any {
    if (file == null) return R.drawable.ic_photo
    if (file.extension.lowercase() != "mp4") return file
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.getFrameAtTime(0) ?: R.drawable.ic_camera_video
    } catch (_: Exception) {
        R.drawable.ic_camera_video
    } finally {
        retriever.release()
    }
}
