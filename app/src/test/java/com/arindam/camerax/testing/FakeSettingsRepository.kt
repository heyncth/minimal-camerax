package com.arindam.camerax.testing

import com.arindam.camerax.domain.model.CaptureSettings
import com.arindam.camerax.domain.model.DeviceCaptureFeatures
import com.arindam.camerax.domain.repository.DeviceFeaturesRepository
import com.arindam.camerax.domain.repository.SettingsRepository

/** In-memory [SettingsRepository] for unit tests. */
class FakeSettingsRepository(
    var settings: CaptureSettings = CaptureSettings()
) : SettingsRepository {
    override fun loadCaptureSettings(): CaptureSettings = settings
    var savedPipCorner: String? = null
    override fun savePipCorner(prefValue: String) {
        savedPipCorner = prefValue
    }
    var savedPipScale: String? = null
    override fun savePipScale(prefValue: String) {
        savedPipScale = prefValue
    }
}

/** In-memory [DeviceFeaturesRepository] for unit tests. */
class FakeDeviceFeaturesRepository(
    var features: DeviceCaptureFeatures = DeviceCaptureFeatures()
) : DeviceFeaturesRepository {
    override suspend fun probe(): DeviceCaptureFeatures = features
}
