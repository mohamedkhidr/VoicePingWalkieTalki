package com.smartwalkie.voicepingdemo

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.smartwalkie.voicepingdemo.kpi.PttKpiLogger
import com.smartwalkie.voicepingsdk.VoicePing
import com.smartwalkie.voicepingsdk.model.AudioParam

class VoicePingClientApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = this

        val source = AudioSourceConfig.getSource()
        val audioParam = AudioParam.Builder()
            .setAudioSource(source)
            .build()
        Log.d(
            TAG,
            "Manufacturer: ${Build.MANUFACTURER}, audio source: ${AudioSourceConfig.getAudioSourceText(source)}"
        )
        VoicePing.init(this, audioParam)

        // Bring up the KPI logger up front and wire the global listeners so
        // we capture connection-state events even before the user lands on
        // MainActivity. Per-PTT-session hooks are wired in MainActivity.
        val kpi = PttKpiLogger.init(this, deviceTag = Build.MODEL ?: "device")
        VoicePing.setConnectionStateListener(kpi.connectionListener)
        VoicePing.setIncomingTalkListener(kpi.incomingListener)
    }

    companion object {
        private const val TAG = "VoicePingClientApp"

        // Process-wide application context. `private set` prevents external
        // reassignment; it's only set from this class's onCreate().
        lateinit var appContext: Context
            private set

        /** Backward-compat alias for callers that referenced [context]. */
        @JvmStatic
        val context: Context get() = appContext
    }
}
