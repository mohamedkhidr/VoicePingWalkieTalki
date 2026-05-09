package com.smartwalkie.voicepingdemo

import android.media.MediaRecorder
import android.os.Build

/**
 * Per-OEM audio-source overrides for the PTT recorder.
 *
 * Several Android OEMs ship audio HALs that interact badly with one
 * `MediaRecorder.AudioSource` value or another (echo, low gain, AGC fighting
 * the AEC, etc.). The defaults here were collected from field testing.
 * Add new entries to [overrides]; everything else falls back to
 * VOICE_COMMUNICATION which gives us the OS AEC/AGC/NS chain.
 */
object AudioSourceConfig {

    private val overrides: Map<String, Int> = mapOf(
        "lg"      to MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "tcl"     to MediaRecorder.AudioSource.MIC,
        "moto"    to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        "samsung" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "alps"    to MediaRecorder.AudioSource.MIC,
        "asus"    to MediaRecorder.AudioSource.MIC
    )

    private const val DEFAULT_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION

    fun getSource(): Int {
        val key = (Build.MANUFACTURER ?: "").lowercase()
        return overrides[key] ?: DEFAULT_SOURCE
    }

    fun getAudioSourceText(source: Int): String = when (source) {
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        else -> "UNKNOWN"
    }
}
