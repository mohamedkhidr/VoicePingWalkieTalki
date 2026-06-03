package com.smartwalkie.voicepingdemo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.BassBoost
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import com.smartwalkie.voicepingdemo.databinding.ActivityMainBinding
import com.smartwalkie.voicepingdemo.kpi.PttKpiLogger
import com.smartwalkie.voicepingdemo.net.NetworkMonitor
import com.smartwalkie.voicepingsdk.ConnectionState
import com.smartwalkie.voicepingsdk.VoicePing
import com.smartwalkie.voicepingsdk.VoicePingButton
import com.smartwalkie.voicepingsdk.VoicePingWavButton
import com.smartwalkie.voicepingsdk.callback.ConnectCallback
import com.smartwalkie.voicepingsdk.exception.ErrorCode
import com.smartwalkie.voicepingsdk.exception.VoicePingException
import com.smartwalkie.voicepingsdk.listener.AudioMetaData
import com.smartwalkie.voicepingsdk.listener.AudioReceiver
import com.smartwalkie.voicepingsdk.listener.ConnectionStateListener
import com.smartwalkie.voicepingsdk.listener.IncomingTalkListener
import com.smartwalkie.voicepingsdk.model.Channel
import com.smartwalkie.voicepingsdk.model.ChannelType
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity(),
    AdapterView.OnItemSelectedListener,
    ConnectionStateListener,
    IncomingTalkListener {

    private lateinit var binding: ActivityMainBinding

    private var destinationPath: String? = null
    private var toast: Toast? = null
    private var channelType: Int = ChannelType.GROUP
    private var disconnectConfirmationDialog: DisconnectConfirmationDialog? = null

    // Active audio effects for the currently-playing incoming session.
    // We must release these on session stop / activity destroy.
    private var activeLoudness: LoudnessEnhancer? = null
    private var activeBassBoost: BassBoost? = null

    // Reused per-frame scratch buffer for the amplitude meter — avoids
    // allocating a fresh ShortArray on every audio frame (~17 frames/s).
    private var amplitudeScratch: ShortArray = ShortArray(0)
    private var lastUiAmplitudeUpdateMs: Long = 0L

    // Network state. Reconnect-on-network-return only fires after the first
    // observed loss — i.e. we never spam the SDK with extra connect calls
    // while it's already healthy.
    private lateinit var networkMonitor: NetworkMonitor
    @Volatile private var sawNetworkLoss: Boolean = false

    private val kpi: PttKpiLogger? get() = PttKpiLogger.get()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val userId = MyPrefs.userId.orEmpty()
        val company = MyPrefs.company.orEmpty()
        val serverUrl = MyPrefs.serverUrl.orEmpty()
        if (userId.isBlank() || company.isBlank() || serverUrl.isBlank()) {
            finish(); return
        }

        // Hard requirement for PTT — the recorder will fail silently without it.
        if (!hasRecordAudioPermission()) {
            showToast("Microphone permission missing — please re-login.")
            startActivity(Intent(this, LoginActivity::class.java))
            finish(); return
        }

        initToolbar(userId, company)
        binding.textServerUrl.text = serverUrl

        // Spinner: GROUP / PRIVATE
        val channelTypes = arrayOf("GROUP", "PRIVATE")
        ArrayAdapter(this, android.R.layout.simple_spinner_item, channelTypes).also { a ->
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerChannelType.adapter = a
        }
        binding.spinnerChannelType.onItemSelectedListener = this

        // Group join / leave
        binding.buttonJoin.setOnClickListener { joinGroup() }
        binding.buttonLeave.setOnClickListener { leaveGroup() }
        binding.layoutGroupButtons.visibility = View.VISIBLE

        // Mute / unmute
        binding.buttonMute.setOnClickListener { muteChannel() }
        binding.buttonUnmute.setOnClickListener { unmuteChannel() }

        binding.layoutIncomingTalk.visibility = View.GONE

        // Receiver-id field gates both PTT buttons.
        binding.editReceiverId.addTextChangedListener {
            val receiverId = it.toString()
            binding.voicePingButton.receiverId = receiverId
            binding.wavPingButton.receiverId = receiverId
            binding.voicePingButton.setButtonEnabled(receiverId.isNotBlank())
            binding.wavPingButton.setButtonEnabled(receiverId.isNotBlank())
        }

        wirePttButtons()

        // The KPI logger is the SDK-side singleton listener (registered in
        // VoicePingClientApp) so we never miss a connection/PTT event. We
        // attach to it as a secondary so KPI logging and our UI both run.
        kpi?.addConnectionStateListener(this)
        kpi?.addIncomingTalkListener(this)
        kpi?.addFrameHook(amplitudeFrameHook)
        updateConnectionState(VoicePing.getConnectionState())

        // Default channel type for PTT buttons.
        binding.voicePingButton.channelType = ChannelType.PRIVATE
        binding.wavPingButton.channelType = ChannelType.PRIVATE
        binding.voicePingButton.setButtonEnabled(false)
        binding.wavPingButton.setButtonEnabled(false)
        binding.wavPingButton.wavFile = File(filesDir, "test.wav")

        // Connect on first launch, if not already connected.
        if (VoicePing.getConnectionState() == ConnectionState.DISCONNECTED) {
            connectVoicePing(serverUrl, userId, company)
        }

        // Network monitor — surfaces transport changes to the user and pokes
        // the SDK to reconnect when network returns.
        networkMonitor = NetworkMonitor(this, networkListener)
    }

    override fun onStart() {
        super.onStart()
        networkMonitor.start()
    }

    override fun onStop() {
        super.onStop()
        networkMonitor.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister from the KPI logger fan-out — the KPI logger itself
        // remains attached to the SDK so background events keep being logged.
        kpi?.removeConnectionStateListener(this)
        kpi?.removeIncomingTalkListener(this)
        kpi?.removeFrameHook(amplitudeFrameHook)
        releaseActiveEffects()
        toast?.cancel()
        toast = null
    }

    // ─── PTT button wiring ────────────────────────────────────────────────

    private fun wirePttButtons() {
        binding.voicePingButton.listener = object : VoicePingButton.Listener {
            override fun onStarted() {
                log("VoicePingButton, PTT onStarted")
                kpi?.onPttButtonStarted(currentReceiverId(), channelType)
            }

            override fun onStopped() {
                log("VoicePingButton, PTT onStopped")
                kpi?.onPttButtonStopped()
            }

            override fun onError(errorMessage: String) {
                log("VoicePingButton, PTT error: $errorMessage")
                kpi?.onPttButtonError(errorMessage)
                if (currentReceiverId().isEmpty()) {
                    binding.editReceiverId.error = getString(R.string.cannot_be_blank)
                }
            }
        }
        binding.wavPingButton.listener = object : VoicePingWavButton.Listener {
            override fun onStarted() {
                log("WavPingButton, Wav PTT onStarted")
                kpi?.onPttButtonStarted(currentReceiverId(), channelType)
            }

            override fun onStopped() {
                log("WavPingButton, Wav PTT onStopped")
                kpi?.onPttButtonStopped()
            }

            override fun onError(errorMessage: String) {
                log("WavPingButton, PTT error: $errorMessage")
                kpi?.onPttButtonError(errorMessage)
                if (currentReceiverId().isEmpty()) {
                    binding.editReceiverId.error = getString(R.string.cannot_be_blank)
                }
            }
        }
    }

    private fun currentReceiverId(): String =
        binding.editReceiverId.text.toString().trim()

    // ─── Connection ───────────────────────────────────────────────────────

    private fun connectVoicePing(serverUrl: String, userId: String, company: String) {
        VoicePing.connect(serverUrl, userId, company, object : ConnectCallback {
            override fun onConnected() = Unit
            override fun onFailed(exception: VoicePingException) {
                Log.w(TAG, "VoicePing.connect failed: ${exception.message}")
            }
        })
    }

    private fun initToolbar(userId: String, company: String) {
        supportActionBar?.title = "User ID: $userId"
        supportActionBar?.subtitle = "Company: $company"
    }

    private fun updateConnectionState(state: ConnectionState) {
        log("updateConnectionState: ${state.name}")
        binding.textConnectionState.text = state.name
        val colorResId = when (state) {
            ConnectionState.DISCONNECTED -> R.color.red
            ConnectionState.CONNECTING   -> R.color.yellow
            ConnectionState.CONNECTED    -> R.color.green
        }
        binding.textConnectionState.setTextColor(ContextCompat.getColor(this, colorResId))
    }

    // ─── Network monitor callbacks ────────────────────────────────────────

    private val networkListener = object : NetworkMonitor.Listener {
        override fun onNetworkAvailable(transport: NetworkMonitor.Transport) {
            log("network available: $transport")
            // Only force a reconnect if we previously saw a loss.
            if (sawNetworkLoss && VoicePing.getConnectionState() == ConnectionState.DISCONNECTED) {
                val userId = MyPrefs.userId.orEmpty()
                val company = MyPrefs.company.orEmpty()
                val serverUrl = MyPrefs.serverUrl.orEmpty()
                if (userId.isNotBlank() && company.isNotBlank() && serverUrl.isNotBlank()) {
                    log("triggering reconnect after network return")
                    connectVoicePing(serverUrl, userId, company)
                }
            }
            sawNetworkLoss = false
        }

        override fun onNetworkLost() {
            sawNetworkLoss = true
            runOnUiThread {
                showToast("Network lost — PTT unavailable")
            }
        }

        override fun onTransportChanged(transport: NetworkMonitor.Transport) {
            runOnUiThread {
                showToast("Network: $transport")
            }
        }
    }

    // ─── Menu / spinner ───────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_open_player -> startActivity(
                PlayerActivity.generateIntent(this, destinationPath)
            )
            R.id.action_disconnect -> showDisconnectConfirmationDialog()
        }
        return true
    }

    override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
        if (parent !== binding.spinnerChannelType) return
        when (position) {
            0 -> {
                binding.textReceiverIdLabel.text = "Group ID"
                channelType = ChannelType.GROUP
                binding.layoutGroupButtons.visibility = View.VISIBLE
                binding.voicePingButton.channelType = ChannelType.GROUP
                binding.wavPingButton.channelType = ChannelType.GROUP
            }
            1 -> {
                binding.textReceiverIdLabel.text = "Target User ID"
                channelType = ChannelType.PRIVATE
                binding.layoutGroupButtons.visibility = View.GONE
                binding.voicePingButton.channelType = ChannelType.PRIVATE
                binding.wavPingButton.channelType = ChannelType.PRIVATE
            }
        }
    }

    override fun onNothingSelected(parent: AdapterView<*>?) = Unit

    // ─── ConnectionStateListener ──────────────────────────────────────────

    override fun onConnectionStateChanged(connectionState: ConnectionState) {
        runOnUiThread { updateConnectionState(connectionState) }
    }

    override fun onConnectionError(e: VoicePingException) {
        runOnUiThread {
            if (e.errorCode == ErrorCode.DUPLICATE_CONNECT) {
                if (!isFinishing) {
                    VoicePing.unmuteAll()
                    MyPrefs.clear()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
            } else {
                showToast(e.message)
            }
        }
    }

    // ─── IncomingTalkListener ─────────────────────────────────────────────

    override fun onIncomingTalkStarted(
        audioReceiver: AudioReceiver,
        activeChannels: List<Channel>
    ) {
        log("onIncomingTalkStarted, channel: ${audioReceiver.channel}, session id: ${audioReceiver.audioSessionId}")

        // Audio post-processing — replace any previous effects so we don't
        // leak the native engine across consecutive incoming sessions.
        releaseActiveEffects()
        activeLoudness  = Utils.enhanceLoudnessIfPossible(audioReceiver.audioSessionId, LOUDNESS_GAIN_MILLIBELS)
        activeBassBoost = Utils.boostBassIfPossible(audioReceiver.audioSessionId, BASS_STRENGTH)

        runOnUiThread {
            val ch = audioReceiver.channel
            binding.layoutIncomingTalk.visibility = View.VISIBLE
            binding.textIncomingChannelType.text = ChannelType.getText(ch?.type ?: -1)
            binding.textIncomingSenderId.text = ch?.pureSenderId
        }

        // Amplitude metering is fed via the KPI logger's FrameHook fan-out;
        // no need to set our own AudioInterceptor here.
    }

    override fun onIncomingTalkStopped(
        audioMetaData: AudioMetaData,
        activeChannels: List<Channel>
    ) {
        log("onIncomingTalkStopped, channel: ${audioMetaData.channel}, " +
            "download url: ${audioMetaData.downloadUrl}, " +
            "active channels count: ${activeChannels.size}")
        releaseActiveEffects()
        if (activeChannels.isEmpty()) {
            runOnUiThread { binding.layoutIncomingTalk.visibility = View.GONE }
        }
    }

    override fun onIncomingTalkError(e: VoicePingException) {
        Log.w(TAG, "onIncomingTalkError: ${e.message}", e)
        releaseActiveEffects()
        runOnUiThread { binding.layoutIncomingTalk.visibility = View.GONE }
    }

    // ─── Amplitude meter (throttled, allocation-free) ─────────────────────

    private val amplitudeFrameHook = object : PttKpiLogger.FrameHook {
        override fun onIncomingFrame(channel: Channel, pcm: ByteArray) {
            val shortLen = pcm.size / 2
            if (shortLen <= 0) return
            if (amplitudeScratch.size < shortLen) {
                amplitudeScratch = ShortArray(shortLen)
            }
            val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until shortLen) amplitudeScratch[i] = bb.short

            val amplitude = Utils.getRmsAmplitude(amplitudeScratch, shortLen)

            // Throttle UI updates to ~10 Hz; the underlying frame cadence is
            // ~17 Hz which is wasteful for a progress bar.
            val now = System.currentTimeMillis()
            if (now - lastUiAmplitudeUpdateMs < AMPLITUDE_UI_INTERVAL_MS) return
            lastUiAmplitudeUpdateMs = now
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.progressIncomingTalk.progress = amplitude.toInt() - AMPLITUDE_BIAS
            }
        }
    }

    // ─── Group / mute helpers ─────────────────────────────────────────────

    private fun joinGroup() {
        val groupId = currentReceiverId()
        if (groupId.isBlank()) {
            binding.editReceiverId.error = getString(R.string.cannot_be_blank)
            binding.editReceiverId.requestFocus(); return
        }
        log("joinGroup, group ID: $groupId")
        VoicePing.joinGroup(groupId)
        showToast("Joined to $groupId")
        Utils.closeKeyboard(this, currentFocus)
    }

    private fun leaveGroup() {
        val groupId = currentReceiverId()
        if (groupId.isBlank()) {
            binding.editReceiverId.error = getString(R.string.cannot_be_blank)
            binding.editReceiverId.requestFocus(); return
        }
        log("leaveGroup, group ID: $groupId")
        VoicePing.leaveGroup(groupId)
        showToast("Left from $groupId")
        Utils.closeKeyboard(this, currentFocus)
    }

    private fun muteChannel() {
        val receiverId = currentReceiverId()
        if (receiverId.isBlank()) return
        log("muteChannel, target ID: $receiverId, channel type: ${ChannelType.getText(channelType)}")
        VoicePing.mute(receiverId, channelType)
        showToast("Channel $receiverId muted")
    }

    private fun unmuteChannel() {
        val receiverId = currentReceiverId()
        if (receiverId.isBlank()) return
        log("unmuteChannel, target ID: $receiverId, channel type: ${ChannelType.getText(channelType)}")
        VoicePing.unmute(receiverId, channelType)
        showToast("Channel $receiverId unmuted")
    }

    private fun showDisconnectConfirmationDialog() {
        if (disconnectConfirmationDialog == null) {
            disconnectConfirmationDialog = DisconnectConfirmationDialog(
                this,
                object : DisconnectConfirmationDialog.Listener {
                    override fun onDisconnected() {
                        startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                        finish()
                    }
                }
            )
        }
        disconnectConfirmationDialog?.show()
    }

    private fun releaseActiveEffects() {
        Utils.releaseQuietly(activeLoudness)
        Utils.releaseQuietly(activeBassBoost)
        activeLoudness = null
        activeBassBoost = null
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun showToast(message: String?) {
        if (message == null) return
        runOnUiThread {
            toast?.cancel()
            toast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "MainActivity"
        // Loudness +30 dB = 3000 millibels. The original code used 300.
        // Keep the original value to preserve user-perceived loudness.
        private const val LOUDNESS_GAIN_MILLIBELS = 300
        private const val BASS_STRENGTH: Short = 100
        // The original code subtracted 7000 from the RMS amplitude before
        // setting the progress bar — kept to preserve UI behavior.
        private const val AMPLITUDE_BIAS = 7000
        // 100 ms = ~10 Hz UI updates. Frame cadence is ~60 ms.
        private const val AMPLITUDE_UI_INTERVAL_MS = 100L
    }
}
