package dev.loki.android.core.llm

import android.content.Context
import android.os.Build
import java.io.File

enum class NpuVendor {
    QUALCOMM,
    MEDIATEK,
    GOOGLE_TENSOR,
    SAMSUNG,
    UNKNOWN
}

data class BackendCapabilities(
    val npuVendor: NpuVendor,
    val htpGeneration: String?,
    val npuUsable: Boolean,
    val socModel: String?
)

object NpuCapabilityProbe {

    // Authoritative mapping from supported_soc.csv for Qualcomm HTP generations
    private val SOC_TO_HTP_GENERATION = mapOf(
        "SM8850" to "v81",
        "SM8750" to "v79",
        "SM8650" to "v75",
        "SM8550" to "v73",
        "SM8475" to "v69",
        "SM8450" to "v69",
        "SM7675" to "v73",
        "SM7550" to "v73",
        "SM7475" to "v69"
    )

    fun detectVendor(
        manufacturer: String?,
        model: String?,
        hardware: String?,
        board: String?
    ): NpuVendor {
        val combined = listOfNotNull(manufacturer, model, hardware, board)
            .joinToString(" ")
            .lowercase(java.util.Locale.ROOT)
        return when {
            combined.contains("qcom") || combined.contains("qualcomm") || combined.contains("snapdragon") || combined.contains("sm8") || combined.contains("sm7") -> NpuVendor.QUALCOMM
            combined.contains("mediatek") || combined.contains("dimensity") || combined.contains("mt6") || combined.contains("mt8") -> NpuVendor.MEDIATEK
            combined.contains("tensor") || combined.contains("google") || combined.contains("zuma") -> NpuVendor.GOOGLE_TENSOR
            combined.contains("exynos") || combined.contains("samsung") || combined.contains("s5e") -> NpuVendor.SAMSUNG
            else -> NpuVendor.UNKNOWN
        }
    }

    fun lookupHtpGeneration(socModel: String?): String? {
        if (socModel.isNullOrBlank()) return null
        val normalized = socModel.trim().uppercase()
        SOC_TO_HTP_GENERATION[normalized]?.let { return it }

        // Fallback for full strings containing SoC pattern like "SM8750"
        for ((soc, gen) in SOC_TO_HTP_GENERATION) {
            if (normalized.contains(soc)) {
                return gen
            }
        }
        return null
    }

    fun isNpuUsable(nativeLibraryDir: File?): Boolean {
        if (nativeLibraryDir == null || !nativeLibraryDir.isDirectory) return false
        val files = nativeLibraryDir.list() ?: return false
        val hasQnn = files.any { it.contains("libQnnHtp") || it.contains("libQnnSystem") }
        val hasDispatch = files.any { it.contains("libLiteRtDispatch_Qualcomm") }
        return hasQnn && hasDispatch
    }

    fun probe(context: Context): BackendCapabilities {
        val manufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        val hardware = Build.HARDWARE
        val board = Build.BOARD

        val vendor = detectVendor(manufacturer, socModel, hardware, board)
        val htpGen = lookupHtpGeneration(socModel ?: hardware ?: board)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val usable = isNpuUsable(nativeDir)

        return BackendCapabilities(
            npuVendor = vendor,
            htpGeneration = htpGen,
            npuUsable = usable,
            socModel = socModel ?: hardware
        )
    }
}
