package com.example.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.example.domain.model.DeviceCompatibilityInfo
import com.example.domain.model.DeviceTier
import java.io.File

class DeviceCompatibilityManager(private val context: Context) {

    fun inspectDevice(): DeviceCompatibilityInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalRamMb = memInfo.totalMem / (1024L * 1024L)
        val availableRamMb = memInfo.availMem / (1024L * 1024L)

        val statFs = StatFs(context.filesDir.absolutePath)
        val availableStorageMb = (statFs.availableBlocksLong * statFs.blockSizeLong) / (1024L * 1024L)

        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        val primaryAbi = abis.firstOrNull() ?: "unknown"
        val isArm64 = abis.any { it.contains("arm64") }

        val supportsGpu = detectOpenClOrGpu(isArm64 = isArm64, totalRamMb = totalRamMb)

        val tier = when {
            totalRamMb < 4096L -> DeviceTier.LOW_RAM
            totalRamMb < 6144L -> DeviceTier.MEDIUM_RAM
            else -> DeviceTier.HIGH_RAM
        }

        return DeviceCompatibilityInfo(
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            availableStorageMb = availableStorageMb,
            cpuAbi = primaryAbi,
            isArm64 = isArm64,
            supportsGpuAcceleration = supportsGpu,
            tier = tier
        )
    }

    fun isNativeLlmSupported(): Boolean {
        val abis = Build.SUPPORTED_ABIS ?: return false
        return abis.any { it == "arm64-v8a" || it == "x86_64" }
    }

    fun checkModelCompatibility(
        minRamMb: Long,
        minStorageMb: Long
    ): CompatibilityCheckResult {
        val info = inspectDevice()
        if (!info.isArm64 && !isNativeLlmSupported()) {
            return CompatibilityCheckResult(
                isCompatible = false,
                reason = "64-bit CPU (arm64-v8a or x86_64) is required for LiteRT-LM local LLM inference. Detected: ${info.cpuAbi}."
            )
        }
        if (info.totalRamMb < minRamMb) {
            return CompatibilityCheckResult(
                isCompatible = false,
                reason = "Requires at least ${minRamMb} MB RAM. Device has ${info.totalRamMb} MB total RAM."
            )
        }
        if (info.availableStorageMb < minStorageMb) {
            return CompatibilityCheckResult(
                isCompatible = false,
                reason = "Needs ${minStorageMb} MB free storage. Only ${info.availableStorageMb} MB available."
            )
        }
        return CompatibilityCheckResult(
            isCompatible = true,
            reason = "Compatible (${info.tier.label})."
        )
    }

    internal fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val product = Build.PRODUCT.lowercase()
        return fingerprint.contains("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("emulator") ||
            model.contains("android sdk built for") ||
            manufacturer.contains("genymotion") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            product.contains("sdk") ||
            product.contains("vbox")
    }

    private fun detectOpenClOrGpu(isArm64: Boolean, totalRamMb: Long): Boolean {
        if (!isArm64 || totalRamMb < 4096L || isEmulator()) return false
        val candidates = listOf(
            "/system/vendor/lib64/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
            "/vendor/lib64/libOpenCL.so"
        )
        return candidates.any { path ->
            val file = File(path)
            file.exists() && file.canRead()
        }
    }
}

data class CompatibilityCheckResult(
    val isCompatible: Boolean,
    val reason: String
)
