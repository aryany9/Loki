package dev.loki.android.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NpuCapabilityProbeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testDetectVendorQualcomm() {
        val vendor = NpuCapabilityProbe.detectVendor(
            manufacturer = "Qualcomm",
            model = "SM8750",
            hardware = "qcom",
            board = "sun"
        )
        assertEquals(NpuVendor.QUALCOMM, vendor)
    }

    @Test
    fun testDetectVendorMediaTek() {
        val vendor = NpuCapabilityProbe.detectVendor(
            manufacturer = "MediaTek",
            model = "MT6989",
            hardware = "mt6989",
            board = "k6989v1_64"
        )
        assertEquals(NpuVendor.MEDIATEK, vendor)
    }

    @Test
    fun testDetectVendorGoogleTensor() {
        val vendor = NpuCapabilityProbe.detectVendor(
            manufacturer = "Google",
            model = "Tensor G4",
            hardware = "zuma",
            board = "tokay"
        )
        assertEquals(NpuVendor.GOOGLE_TENSOR, vendor)
    }

    @Test
    fun testLookupHtpGeneration() {
        assertEquals("v79", NpuCapabilityProbe.lookupHtpGeneration("SM8750"))
        assertEquals("v75", NpuCapabilityProbe.lookupHtpGeneration("SM8650"))
        assertEquals("v73", NpuCapabilityProbe.lookupHtpGeneration("SM8550"))
        assertEquals("v69", NpuCapabilityProbe.lookupHtpGeneration("SM8450"))
        assertEquals(null, NpuCapabilityProbe.lookupHtpGeneration("Tensor G4"))
    }

    @Test
    fun testIsNpuUsableWithQnnLibs() {
        val dir = tempFolder.newFolder("nativeLibs")
        File(dir, "libQnnHtp.so").createNewFile()
        File(dir, "libLiteRtDispatch_Qualcomm.so").createNewFile()

        assertTrue(NpuCapabilityProbe.isNpuUsable(dir))
    }

    @Test
    fun testIsNpuUsableMissingDispatch() {
        val dir = tempFolder.newFolder("missingDispatch")
        File(dir, "libQnnHtp.so").createNewFile()
        File(dir, "liblitertlm_jni.so").createNewFile()

        assertFalse(NpuCapabilityProbe.isNpuUsable(dir))
    }

    @Test
    fun testIsNpuUsableMissingLibs() {
        val dir = tempFolder.newFolder("emptyNativeLibs")
        File(dir, "libsomething_else.so").createNewFile()

        assertFalse(NpuCapabilityProbe.isNpuUsable(dir))
    }
}
