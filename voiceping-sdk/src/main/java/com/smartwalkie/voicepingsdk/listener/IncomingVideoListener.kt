package com.smartwalkie.voicepingsdk.listener

import android.view.Surface
import com.smartwalkie.voicepingsdk.exception.VoicePingException
import com.smartwalkie.voicepingsdk.model.Channel

interface IncomingVideoListener {
    /**
     * Called when a video PTT session starts. Return the Surface to render decoded
     * video into, or null to discard the stream.
     */
    fun onIncomingVideoStarted(channel: Channel, activeChannels: List<Channel>): Surface?

    fun onIncomingVideoStopped(channel: Channel, activeChannels: List<Channel>)

    fun onIncomingVideoError(e: VoicePingException)
}
