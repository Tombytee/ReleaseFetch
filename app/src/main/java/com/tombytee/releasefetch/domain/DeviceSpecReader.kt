package com.tombytee.releasefetch.domain

import android.os.Build

object DeviceSpecReader {
    fun readCurrentDevice(): DeviceProfile {
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("arm64-v8a") }

        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val displayModel = when {
            manufacturer.isBlank() && model.isBlank() -> "Android Device"
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model".trim()
        }

        return DeviceProfile(
            primaryAbi = abis.first(),
            supportedAbis = abis,
            androidVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { Build.VERSION.SDK_INT.toString() },
            sdkInt = Build.VERSION.SDK_INT,
            deviceModel = displayModel,
        )
    }
}
