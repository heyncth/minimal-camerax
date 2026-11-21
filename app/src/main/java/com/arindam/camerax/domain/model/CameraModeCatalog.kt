package com.arindam.camerax.domain.model

/**
 * Domain: policy for one capture mode. Add a [CameraMode] value and a row in
 * [CameraModeCatalog.profiles] first. Then implement any new CameraX session type in
 * `CameraSession` only if bind flags are not enough.
 */
data class CameraModeProfile(
    val mode: CameraMode,
    val availability: ModeAvailability = ModeAvailability.ALWAYS,
    val fallback: CameraMode = CameraMode.PHOTO,
    val captureAction: CaptureAction = CaptureAction.STILL,
    val showsFlash: Boolean = true,
    val showsTimer: Boolean = false,
    /** Apple-style LIVE toggle (pre-roll + still pair on capture). */
    val showsMotion: Boolean = false,
    val showsZoom: Boolean = true,
    val showsFlip: Boolean = true,
    /** Static PiP outline over the preview (the same rect is burned into the still). */
    val showsPip: Boolean = false,
    val allowsNightAuto: Boolean = false,
    val allowsExtensions: Boolean = true,
    val allowsMotionPhoto: Boolean = false,
    val allowsFps60: Boolean = false,
    val allowsPersistentRecording: Boolean = false,
    val bindDualPhoto: Boolean = false,
    val rebindOnEnter: Boolean = false,
    val clearsSessionExtras: Boolean = false
) {
    fun isAvailable(dualPhotoSupported: Boolean): Boolean = when (availability) {
        ModeAvailability.ALWAYS -> true
        ModeAvailability.DUAL_PHOTO -> dualPhotoSupported
    }
}

/** Domain: whether a mode is always shown or gated on device capabilities. */
enum class ModeAvailability {
    ALWAYS,
    DUAL_PHOTO
}

/** Domain: shutter behavior for the mode (still or video). */
enum class CaptureAction {
    STILL,
    VIDEO
}

fun CameraMode.profile(): CameraModeProfile = CameraModeCatalog.profile(this)

/**
 * Domain: registry of modes. To add a mode: [CameraMode] value, a [CameraModeProfile] here,
 * then `CameraMode.labelRes`. Touch [com.arindam.camerax.data.camera.CameraSession] only
 * when the bind flags are not enough.
 */
object CameraModeCatalog {
    val profiles: Map<CameraMode, CameraModeProfile> = listOf(
        CameraModeProfile(
            mode = CameraMode.PHOTO,
            showsTimer = true,
            showsMotion = true,
            allowsNightAuto = true,
            allowsMotionPhoto = true
        ),
        CameraModeProfile(
            mode = CameraMode.VIDEO,
            captureAction = CaptureAction.VIDEO,
            allowsExtensions = false,
            allowsNightAuto = false,
            allowsFps60 = true,
            allowsPersistentRecording = true,
            // Preview aspect changes with the mode (4:3 stills vs 16:9 video).
            rebindOnEnter = true
        ),
        CameraModeProfile(
            mode = CameraMode.DUAL_PHOTO,
            availability = ModeAvailability.DUAL_PHOTO,
            fallback = CameraMode.PHOTO,
            captureAction = CaptureAction.STILL,
            showsTimer = true,
            showsZoom = false,
            // Flip swaps which lens is the full-frame primary in the merged still.
            showsFlip = true,
            showsPip = true,
            allowsNightAuto = false,
            allowsExtensions = false,
            allowsMotionPhoto = false,
            bindDualPhoto = true,
            rebindOnEnter = true,
            clearsSessionExtras = true
        )
    ).associateBy { it.mode }

    init {
        check(profiles.keys == CameraMode.entries.toSet()) {
            "CameraModeCatalog must declare a profile for every CameraMode"
        }
    }

    fun profile(mode: CameraMode): CameraModeProfile =
        profiles[mode] ?: error("Missing CameraModeProfile for $mode")

    /**
     * Pager modes: Photo and Video only. Dual Photo is entered from the Photo header toggle
     * ([profile] and [resolve] still cover it for saved-mode restore and fallback).
     */
    fun visibleModes(dualPhotoSupported: Boolean): List<CameraMode> =
        CameraMode.entries.filter { mode ->
            mode != CameraMode.DUAL_PHOTO && profile(mode).isAvailable(dualPhotoSupported)
        }

    fun resolve(mode: CameraMode, dualPhotoSupported: Boolean): CameraMode {
        val current = profile(mode)
        return if (current.isAvailable(dualPhotoSupported)) mode else current.fallback
    }
}
