package dev.loki.android.core.voice.stt

import kotlin.math.sqrt

object WaveformExtractor {
    fun extractAmplitudes(pcmFloats: FloatArray, buckets: Int = 48): ByteArray {
        if (pcmFloats.isEmpty()) return ByteArray(buckets)
        
        val bucketSize = pcmFloats.size / buckets
        val result = ByteArray(buckets)
        
        var globalMaxRms = 0f
        val rmsValues = FloatArray(buckets)
        
        for (i in 0 until buckets) {
            val start = i * bucketSize
            val end = if (i == buckets - 1) pcmFloats.size else (i + 1) * bucketSize
            
            var sumSquares = 0.0
            for (j in start until end) {
                val sample = pcmFloats[j]
                sumSquares += sample * sample
            }
            
            val rms = if (end > start) sqrt(sumSquares / (end - start)).toFloat() else 0f
            rmsValues[i] = rms
            if (rms > globalMaxRms) {
                globalMaxRms = rms
            }
        }
        
        // Normalize to 0-100
        val scale = if (globalMaxRms > 0) 100f / globalMaxRms else 0f
        for (i in 0 until buckets) {
            result[i] = (rmsValues[i] * scale).toInt().coerceIn(0, 100).toByte()
        }
        
        return result
    }
}
