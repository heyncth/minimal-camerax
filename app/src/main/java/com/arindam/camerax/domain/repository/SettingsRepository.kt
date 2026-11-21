package com.arindam.camerax.domain.repository

import com.arindam.camerax.domain.model.CaptureSettings
import com.arindam.camerax.domain.model.DeviceCaptureFeatures

/**
 * Domain: app preferences for capture. UI must not read SharedPreferences for camera bind flags.
 */
interface SettingsRepository {
    /** Reads persisted capture toggles (aspect, HDR, stabilization, …) for bind and UI. */
    fun loadCaptureSettings(): CaptureSettings

    /** Persists the silent Dual PiP corner pref (no Settings row). */
    fun savePipCorner(prefValue: String)

    /** Persists the silent Dual PiP pinch-scale pref (no Settings row). */
    fun savePipScale(prefValue: String)
}

/** Domain: CameraX capability probe (qualities, slo-mo, Dual, Ultra HDR, …). */
interface DeviceFeaturesRepository {
    /** Probes device once; call from a background dispatcher. */
    suspend fun probe(): DeviceCaptureFeatures
}
