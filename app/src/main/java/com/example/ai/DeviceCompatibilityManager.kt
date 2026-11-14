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

        val abis = Build.SUPPORTED_ABIS
        val primaryAbi = abis.firstOrNull() ?: "unknown"
        val isArm64 = abis.any { it.contains("arm64") }

        val supportsGpu = detectOpenClOrGpu()

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

    fun checkModelCompatibility(
        minRamMb: Long,
        minStorageMb: Long
    ): CompatibilityCheckResult {
        val info = inspectDevice()
        if (!info.isArm64 && !info.cpuAbi.contains("x86_64")) {
            return CompatibilityCheckResult(
                isCompatible = false,
                reason = "64-bit CPU (arm64-v8a) is required for LiteRT-LM local LLM inference. Detected: ${info.cpuAbi}."
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

    private fun detectOpenClOrGpu(): Boolean {
        val candidates = listOf(
            "/system/vendor/lib64/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
            "/vendor/lib64/libOpenCL.so",
            "/vendor/lib64/egl/libGLES_mali.so"
        )
        return candidates.any { File(it).exists() }
    }
}

data class CompatibilityCheckResult(
    val isCompatible: Boolean,
    val reason: String
)
