package com.smartwalkie.voicepingdemo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.smartwalkie.voicepingdemo.databinding.ActivityPlayerBinding
import com.smartwalkie.voicepingsdk.VoicePing
import com.smartwalkie.voicepingsdk.VoicePingPlayer
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding

    private var filePath: String? = null
    private var voicePingPlayer: VoicePingPlayer? = null
    private var progressTimer: Timer? = null

    private val pickFileLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                filePath = uri.path
                binding.filePath.text = filePath
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filePath = intent.getStringExtra(FILE_PATH_DATA)
        if (filePath.isNullOrEmpty()) {
            showToast("You need to do PTT call first!")
            finish()
            return
        }

        binding.filePath.text = filePath
        binding.pickFileButton.setOnClickListener { pickFile() }
        binding.playButton.setOnClickListener { playAudio() }
        binding.pauseButton.setOnClickListener { pauseAudio() }
        binding.stopButton.setOnClickListener { stopAudio() }
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
    }

    override fun onStop() {
        super.onStop()
        cancelTimer()
        voicePingPlayer?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelTimer()
        voicePingPlayer = null
    }

    private fun initPlayer() {
        val audioParam = VoicePing.getAudioParam()
        val bufferSize = if (audioParam.isUsingOpusCodec) OPUS_PLAYBACK_BUFFER else audioParam.rawBufferSize
        val player = VoicePingPlayer(audioParam, bufferSize)
        voicePingPlayer = player
        try {
            player.setDataSource(filePath)
            player.prepare()
            val duration = player.duration
            binding.seekBar.max = duration.toInt()
            binding.timeDuration.text = formatTime(duration)
            binding.seekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, i: Int, b: Boolean) = Unit
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    log("progress updated to: ${seekBar.progress}")
                    voicePingPlayer?.seekTo(seekBar.progress.toLong())
                }
            })
            player.setOnPlaybackStartedListener { audioSessionId ->
                log("OnPlaybackStartedListener, session id: $audioSessionId")
            }
            player.setOnCompletionListener {
                VoicePing.unmuteAll()
                cancelTimer()
                val total = voicePingPlayer?.duration ?: 0L
                binding.seekBar.progress = total.toInt()
                binding.timeProgress.text = formatTime(total)
                showToast("Playback Completed!")
            }
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "file not found: $filePath", e)
            showToast("File not found!")
        }
    }

    private fun playAudio() {
        log("playAudio")
        val player = voicePingPlayer ?: return
        val path = filePath
        if (path == null || !File(path).exists()) {
            showToast("File not exist!")
            return
        }
        VoicePing.muteAll()
        player.start()
        startProgressTimer()
    }

    private fun pauseAudio() {
        log("pauseAudio")
        VoicePing.unmuteAll()
        voicePingPlayer?.pause()
        cancelTimer()
    }

    private fun stopAudio() {
        log("stopAudio")
        VoicePing.unmuteAll()
        voicePingPlayer?.stop()
        cancelTimer()
    }

    private fun startProgressTimer() {
        cancelTimer()
        progressTimer = Timer("VpPlayerProgress", /* isDaemon = */ true).also { timer ->
            timer.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val pos = voicePingPlayer?.currentPosition ?: 0L
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        binding.seekBar.progress = pos.toInt()
                        binding.timeProgress.text = formatTime(pos)
                    }
                }
            }, 0L, PROGRESS_INTERVAL_MS)
        }
    }

    private fun cancelTimer() {
        progressTimer?.cancel()
        progressTimer = null
    }

    private fun pickFile() {
        // Use the modern Activity Result API. Match audio MIME types; the
        // legacy "file/*" was invalid and showed nothing on most devices.
        pickFileLauncher.launch(arrayOf("audio/*", "application/octet-stream"))
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "PlayerActivity"
        private const val FILE_PATH_DATA = "file_path_data"
        private const val PROGRESS_INTERVAL_MS = 500L
        // Original magic constant — playback buffer used when Opus codec is on.
        private const val OPUS_PLAYBACK_BUFFER = 133

        @JvmStatic
        fun generateIntent(context: Context?, filePath: String?): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(FILE_PATH_DATA, filePath)
            }

        private fun formatTime(millis: Long): String {
            val totalSeconds = ((millis + 500) / 1000).toInt()  // half-up rounding
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            return String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }
}
