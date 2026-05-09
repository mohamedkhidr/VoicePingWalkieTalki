package com.smartwalkie.voicepingdemo

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

object Utils {
    private const val TAG = "Utils"
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Process-wide singleton. OkHttpClient is heavy (thread pool + connection
     * pool) — building a new one per request leaks threads and FDs.
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Builds a [LoudnessEnhancer] for the given session and returns it so the
     * caller is responsible for `.release()`. Returns null when not supported
     * or creation fails — never throws.
     *
     * IMPORTANT: the previous version of this method built the effect and
     * dropped the reference, leaking the native effect engine across every
     * incoming PTT session. Always release on session stop.
     */
    fun enhanceLoudnessIfPossible(audioSessionId: Int, gainMillibels: Int): LoudnessEnhancer? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return null
        return try {
            LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(gainMillibels)
                enabled = true
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "LoudnessEnhancer unavailable: ${e.message}")
            null
        }
    }

    /**
     * Builds a [BassBoost] for the given session and returns it so the caller
     * is responsible for `.release()`. Returns null when not supported.
     */
    fun boostBassIfPossible(audioSessionId: Int, strength: Short): BassBoost? = try {
        BassBoost(10, audioSessionId).apply {
            setStrength(strength)
            enabled = true
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "BassBoost unavailable: ${e.message}")
        null
    }

    /** Best-effort release of an [AudioEffect]. Safe to call with null / already released. */
    fun releaseQuietly(effect: AudioEffect?) {
        if (effect == null) return
        try {
            effect.enabled = false
            effect.release()
        } catch (e: RuntimeException) {
            // ignore — effect may already be released
        }
    }

    /**
     * RMS over a Short PCM frame. Allocation-free; safe to call on the audio
     * background thread once per frame.
     */
    fun getRmsAmplitude(data: ShortArray, length: Int = data.size): Double {
        if (length <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until length) {
            val s = data[i].toInt()
            sum += (s * s).toDouble()
        }
        return sqrt(sum / length)
    }

    fun getMaxAmplitude(data: ShortArray, length: Int = data.size): Double {
        if (length <= 0) return 0.0
        var max = 0
        for (i in 0 until length) {
            val v = abs(data[i].toInt())
            if (v > max) max = v
        }
        return max.toDouble()
    }

    /**
     * Asynchronous file download. Reuses the singleton [httpClient]. Toast
     * notifications are posted to the main thread (Toast cannot be safely
     * shown from arbitrary threads).
     */
    fun downloadFileAsync(context: Context, downloadUrl: String) {
        Log.d(TAG, "start to download file from: $downloadUrl")
        val appContext = context.applicationContext
        val request = Request.Builder().url(downloadUrl).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "download failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        Log.e(TAG, "Failed to download file (${res.code()})")
                        showToastOnMain(appContext, "Failed to download file!")
                        return
                    }
                    val body = res.body() ?: return
                    val fileName = downloadUrl.substringAfterLast('/').ifEmpty { "download.bin" }
                    val outFile = File(appContext.getExternalFilesDir(null), fileName)
                    body.byteStream().use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "file downloaded to: ${outFile.absolutePath}")
                }
            }
        })
    }

    private fun showToastOnMain(context: Context, message: String) {
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    fun closeKeyboard(context: Context, view: View?) {
        if (view == null) return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
