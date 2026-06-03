package com.smartwalkie.voicepingsdk.listener

import com.smartwalkie.voicepingsdk.exception.VoicePingException

interface OutgoingVideoCallback {
    fun onOutgoingVideoStarted()
    fun onOutgoingVideoStopped(isTooShort: Boolean, isTooLong: Boolean)
    fun onOutgoingVideoError(e: VoicePingException)
}
