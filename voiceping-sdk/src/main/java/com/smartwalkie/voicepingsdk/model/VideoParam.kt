package com.smartwalkie.voicepingsdk.model

import android.hardware.camera2.CameraCharacteristics

class VideoParam(
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val frameRate: Int,
    val iFrameInterval: Int,
    val cameraFacing: Int,
    val minDuration: Int,
    val maxDuration: Int
) {
    class Builder {
        private var width = 640
        private var height = 480
        private var bitrate = 500_000
        private var frameRate = 30
        private var iFrameInterval = 1
        private var cameraFacing = CameraCharacteristics.LENS_FACING_FRONT
        private var minDuration = 300
        private var maxDuration = 60_000

        fun setWidth(w: Int) = apply { width = w }
        fun setHeight(h: Int) = apply { height = h }
        fun setBitrate(bps: Int) = apply { bitrate = bps }
        fun setFrameRate(fps: Int) = apply { frameRate = fps }
        fun setIFrameInterval(sec: Int) = apply { iFrameInterval = sec }
        fun setCameraFacing(facing: Int) = apply { cameraFacing = facing }
        fun setMinDuration(ms: Int) = apply { minDuration = ms }
        fun setMaxDuration(ms: Int) = apply { maxDuration = ms }

        fun build() = VideoParam(width, height, bitrate, frameRate, iFrameInterval,
            cameraFacing, minDuration, maxDuration)
    }
}
