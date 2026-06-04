package com.smartwalkie.voicepingdemo

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import com.smartwalkie.voicepingdemo.databinding.ActivityVideoPttBinding
import com.smartwalkie.voicepingsdk.VoicePing
import com.smartwalkie.voicepingsdk.exception.VoicePingException
import com.smartwalkie.voicepingsdk.listener.OutgoingVideoCallback
import java.io.IOException

class VideoPttActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPttBinding

    // Intent extras
    private var receiverId = ""
    private var channelType = 0

    // Camera2
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private val cameraThread = HandlerThread("VideoPtt-Camera").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private var previewSurface: Surface? = null
    private var cameraFacing = CameraCharacteristics.LENS_FACING_FRONT

    // MediaCodec encoder (only active while PTT button is held)
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var encoderThread: Thread? = null
    @Volatile private var encodingRunning = false
    private var spsBuffer: ByteArray? = null

    private var isVideoTalking = false

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPttBinding.inflate(layoutInflater)
        setContentView(binding.root)

        receiverId = intent.getStringExtra(EXTRA_RECEIVER_ID) ?: ""
        channelType = intent.getIntExtra(EXTRA_CHANNEL_TYPE, 0)

        supportActionBar?.title = "Video PTT"
        supportActionBar?.subtitle = "To: $receiverId"

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        binding.texturePreview.surfaceTextureListener = surfaceTextureListener
        wireButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isVideoTalking) {
            VoicePing.stopVideoSession()
            stopEncoder()
        }
        closeCamera()
        cameraThread.quitSafely()
    }

    // ─── Camera preview ───────────────────────────────────────────────────────

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
            texture.setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
            applyNativeAspectRatio(w)
            previewSurface = Surface(texture)
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            previewSurface = null
            return true
        }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    /** Resize the TextureView so it shows the camera at its native aspect ratio. */
    private fun applyNativeAspectRatio(viewWidth: Int) {
        val w = if (viewWidth > 0) viewWidth else resources.displayMetrics.widthPixels
        val nativeHeight = w * PREVIEW_HEIGHT / PREVIEW_WIDTH   // e.g. 640×480 → 4:3
        binding.texturePreview.layoutParams =
            binding.texturePreview.layoutParams.apply { height = nativeHeight }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cameraId = findCameraId() ?: return
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCaptureSession()
                }
                override fun onDisconnected(camera: CameraDevice) = camera.close()
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error $error")
                    camera.close()
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera failed", e)
        }
    }

    /**
     * Create (or recreate) the capture session.
     * When [extraSurface] is non-null (the encoder's input surface), it is included
     * as a second target so Camera2 feeds frames to both preview and encoder.
     */
    private fun startCaptureSession(extraSurface: Surface? = null) {
        val preview = previewSurface ?: return
        val targets = if (extraSurface != null) listOf(preview, extraSurface) else listOf(preview)
        try {
            cameraDevice?.createCaptureSession(targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val req = cameraDevice!!
                                .createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                .apply {
                                    addTarget(preview)
                                    if (extraSurface != null) addTarget(extraSurface)
                                }
                            session.setRepeatingRequest(req.build(), null, cameraHandler)
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "setRepeatingRequest failed", e)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                    }
                }, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createCaptureSession failed", e)
        }
    }

    private fun closeCamera() {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
    }

    private fun findCameraId(): String? {
        for (id in cameraManager.cameraIdList) {
            val facing = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facing == cameraFacing) return id
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    // ─── Button wiring ────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun wireButtons() {
        binding.buttonSwitchCamera.setOnClickListener {
            if (!isVideoTalking) switchCamera()
        }

        binding.buttonVideoPtt.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { startVideoTalking(); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopVideoTalking(); true }
                else -> false
            }
        }
    }

    // ─── Camera switch ────────────────────────────────────────────────────────

    private fun switchCamera() {
        cameraFacing = if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT)
            CameraCharacteristics.LENS_FACING_BACK
        else
            CameraCharacteristics.LENS_FACING_FRONT

        binding.buttonSwitchCamera.text =
            if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) "⇄ Front" else "⇄ Back"

        closeCamera()
        openCamera()
    }

    // ─── Video PTT ────────────────────────────────────────────────────────────

    private fun startVideoTalking() {
        if (isVideoTalking || receiverId.isBlank()) return

        // 1. Create MediaCodec encoder and get its input surface
        val (enc, encSurface) = createEncoder() ?: return
        encoder = enc
        encoderSurface = encSurface

        // 2. Recreate capture session targeting both preview + encoder
        try { captureSession?.stopRepeating(); captureSession?.close() } catch (_: Exception) {}
        startCaptureSession(encSurface)

        // 3. Start draining encoder output → VoicePing.sendVideoFrame()
        spsBuffer = null
        encodingRunning = true
        encoderThread = Thread(::drainEncoder, "VideoPtt-Encode").also { it.start() }

        // 4. Signal the SDK
        isVideoTalking = true
        runOnUiThread {
            binding.buttonVideoPtt.text = "● Sending…"
            binding.buttonSwitchCamera.isEnabled = false
            binding.textVideoStatus.text = "LIVE"
        }

        VoicePing.startVideoSession(receiverId, channelType, object : OutgoingVideoCallback {
            override fun onOutgoingVideoStarted() = Unit
            override fun onOutgoingVideoStopped(isTooShort: Boolean, isTooLong: Boolean) {
                runOnUiThread { resetVideoUi() }
            }
            override fun onOutgoingVideoError(e: VoicePingException) {
                Log.w(TAG, "Video session error: ${e.message}")
                runOnUiThread {
                    binding.textVideoStatus.text = "Error: ${e.message}"
                    resetVideoUi()
                }
            }
        })
    }

    private fun stopVideoTalking() {
        if (!isVideoTalking) return

        // 1. Tell SDK to send STOP_TALKING
        VoicePing.stopVideoSession()

        // 2. Stop encoder + release its surface
        stopEncoder()

        // 3. Restore preview-only capture session
        try { captureSession?.stopRepeating(); captureSession?.close() } catch (_: Exception) {}
        startCaptureSession()

        isVideoTalking = false
        runOnUiThread { resetVideoUi() }
    }

    private fun resetVideoUi() {
        isVideoTalking = false
        binding.buttonVideoPtt.text = "Hold to Send Video"
        binding.buttonSwitchCamera.isEnabled = true
        binding.textVideoStatus.text = ""
    }

    // ─── MediaCodec encoder ───────────────────────────────────────────────────

    private fun createEncoder(): Pair<MediaCodec, Surface>? {
        return try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, ENCODE_WIDTH, ENCODE_HEIGHT)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 500_000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            enc.start()
            enc to surface
        } catch (e: IOException) {
            Log.e(TAG, "createEncoder failed", e)
            null
        }
    }

    private fun drainEncoder() {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        while (encodingRunning) {
            try {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    val buf = enc.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        when {
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> {
                                // SPS+PPS — cache for prepending to future keyframes
                                spsBuffer = ByteArray(info.size).also {
                                    buf.position(info.offset); buf.get(it)
                                }
                            }
                            else -> {
                                val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                var frame = ByteArray(info.size)
                                buf.position(info.offset); buf.get(frame)
                                if (isKey && spsBuffer != null) frame = spsBuffer!! + frame
                                VoicePing.sendVideoFrame(frame, isKey)
                            }
                        }
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            } catch (e: Exception) {
                if (encodingRunning) Log.e(TAG, "drainEncoder error", e)
                break
            }
        }
    }

    private fun stopEncoder() {
        encodingRunning = false
        try { encoder?.signalEndOfInputStream() } catch (_: Exception) {}
        try { encoderThread?.join(1000) } catch (_: Exception) {}
        encoderThread = null
        try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
        encoder = null
        encoderSurface?.release(); encoderSurface = null
        spsBuffer = null
    }

    // ─── Companion ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VideoPttActivity"
        private const val EXTRA_RECEIVER_ID = "receiverId"
        private const val EXTRA_CHANNEL_TYPE = "channelType"
        private const val PREVIEW_WIDTH = 640
        private const val PREVIEW_HEIGHT = 480
        private const val ENCODE_WIDTH = 640
        private const val ENCODE_HEIGHT = 480

        fun start(context: Context, receiverId: String, channelType: Int) {
            context.startActivity(Intent(context, VideoPttActivity::class.java).apply {
                putExtra(EXTRA_RECEIVER_ID, receiverId)
                putExtra(EXTRA_CHANNEL_TYPE, channelType)
            })
        }
    }
}
