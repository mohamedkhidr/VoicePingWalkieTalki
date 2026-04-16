package com.smartwalkie.voicepingsdk.model

import java.io.File
import java.io.FileInputStream
import java.io.IOException


data class WavMetadata(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int
)

 fun extractWavMetadata(file: File): WavMetadata {
    try {
        FileInputStream(file).use { fis ->
            val header = ByteArray(44)
            fis.read(header)

            // WAV header offsets:
            // Channels: bytes 22-23
            // Sample Rate: bytes 24-27
            // Bits per sample: bytes 34-35
            val channels = (header[23].toInt() and 0xFF) shl 8 or (header[22].toInt() and 0xFF)
            val sampleRate = ((header[27].toInt() and 0xFF) shl 24) or
                    ((header[26].toInt() and 0xFF) shl 16) or
                    ((header[25].toInt() and 0xFF) shl 8) or
                    (header[24].toInt() and 0xFF)
            val bitsPerSample = (header[35].toInt() and 0xFF) shl 8 or (header[34].toInt() and 0xFF)
            return WavMetadata(sampleRate, channels, bitsPerSample)
        }
    } catch (e: IOException) {
        // Fallback to defaults if file is corrupted
        return WavMetadata(16000, 1, 16)
    }
}
