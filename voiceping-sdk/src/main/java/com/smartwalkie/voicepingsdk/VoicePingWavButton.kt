package com.smartwalkie.voicepingsdk

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import com.smartwalkie.voicepingsdk.exception.ErrorCode
import com.smartwalkie.voicepingsdk.exception.VoicePingException
import com.smartwalkie.voicepingsdk.listener.OutgoingTalkCallback
import com.smartwalkie.voicepingsdk.model.ChannelType
import java.io.File

class VoicePingWavButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), OutgoingTalkCallback {

    private val TAG = "VoicePingWavButton"
    private val layoutVpButton: FrameLayout
    private val imageVpButton: ImageView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var toast: Toast? = null

    var listener: Listener? = null
    var receiverId: String? = null
    var channelType: Int = 0
    var wavFile: File? = null
    private var buttonEnabled: Boolean = true
    private var isSending: Boolean = false

    init {
        inflate(context, R.layout.view_wav_ping_button, this)  // reuse same layout
        layoutVpButton = findViewById(R.id.layout_vp_button)
        imageVpButton = findViewById(R.id.image_vp_button)
    }

    fun setButtonEnabled(enabled: Boolean) {
        buttonEnabled = enabled
        val resId = if (enabled) R.drawable.bg_rounded_primary else R.drawable.bg_rounded_grey
        layoutVpButton.setBackgroundResource(resId)
    }

    // Single tap — not hold — because WAV has its own duration
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (!buttonEnabled) return super.onTouchEvent(event)
       // val eventAction = event?.action ?: return super.onTouchEvent(event)
       // if (eventAction != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        return proceedTap()
    }

    private fun proceedTap(): Boolean {
        val targetId = receiverId ?: ""
        val file = wavFile

        if (targetId.isBlank() || !ChannelType.isValid(channelType)) {
            layoutVpButton.setBackgroundResource(R.drawable.bg_rounded_grey)
            listener?.onError("Invalid receiverId or channelType")
            return true
        }

        if (file == null || !file.exists()) {
            layoutVpButton.setBackgroundResource(R.drawable.bg_rounded_grey)
            listener?.onError("WAV file not set or not found")
            return true
        }

        if (isSending) {
            // Tap again while sending = cancel
            VoicePing.stopTalking()
            return true
        }

        layoutVpButton.setBackgroundResource(R.drawable.bg_rounded_yellow)
        VoicePing.startTalkingWithWav(targetId, channelType, this, file)
        listener?.onStarted()
        return true
    }

    // ── OutgoingTalkCallback ──────────────────────────────────────────────────

    override fun onOutgoingTalkStarted(audioSessionId: Int) {
        // No NS/AEC/AGC for WAV — those are mic-only effects
        isSending = true
        log("onOutgoingTalkStarted (WAV)")
    }

    override fun onOutgoingTalkStopped(isTooShort: Boolean, isTooLong: Boolean) {
        isSending = false
        mainHandler.post {
            layoutVpButton.setBackgroundResource(R.drawable.bg_rounded_primary)
        }
        log("onOutgoingTalkStopped, isTooShort: $isTooShort, isTooLong: $isTooLong")
        listener?.onStopped()
    }

    override fun onDownloadUrlReceived(downloadUrl: String) {
        log("onDownloadUrlReceived, URL: $downloadUrl")
    }

    override fun onOutgoingTalkError(e: VoicePingException) {
        isSending = false
        mainHandler.post {
            layoutVpButton.setBackgroundResource(R.drawable.bg_rounded_primary)
        }
        log("onOutgoingTalkError, code: ${e.errorCode}, message: ${e.message}")
        val errorMessage = if (e.errorCode == ErrorCode.UNAUTHORIZED_GROUP) {
            "Please join the group first before sending PTT"
        } else {
            e.message
        }
        showToast(errorMessage)
        listener?.onError(errorMessage ?: "Unknown error")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showToast(message: String?) {
        if (message.isNullOrBlank()) return
        mainHandler.post {
            toast?.cancel()
            toast = Toast.makeText(context, message, Toast.LENGTH_SHORT)
            toast?.show()
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    interface Listener {
        fun onStarted()
        fun onStopped()
        fun onError(errorMessage: String)
    }
}