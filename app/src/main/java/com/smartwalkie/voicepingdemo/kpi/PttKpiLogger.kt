package com.smartwalkie.voicepingdemo.kpi

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.smartwalkie.voicepingsdk.ConnectionState
import com.smartwalkie.voicepingsdk.exception.ErrorCode
import com.smartwalkie.voicepingsdk.exception.VoicePingException
import com.smartwalkie.voicepingsdk.listener.AudioInterceptor
import com.smartwalkie.voicepingsdk.listener.AudioMetaData
import com.smartwalkie.voicepingsdk.listener.AudioReceiver
import com.smartwalkie.voicepingsdk.listener.ConnectionStateListener
import com.smartwalkie.voicepingsdk.listener.IncomingTalkListener
import com.smartwalkie.voicepingsdk.listener.OutgoingTalkCallback
import com.smartwalkie.voicepingsdk.model.Channel
import com.smartwalkie.voicepingsdk.model.ChannelType
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * PttKpiLogger — KPI / telemetry logger for the VoicePing PTT SDK.
 *
 * Designed to be embedded in the iNOS host APK to measure PTT user
 * experience over mobile networks. Writes:
 *   <externalFilesDir>/kpi/ptt_kpi_<tag>_<ts>.csv          (per-event)
 *   <externalFilesDir>/kpi/ptt_kpi_summary_<tag>_<ts>.csv  (per-session row)
 *
 * KPIs:
 *   - PTT Access Time           (request -> onOutgoingTalkStarted)
 *   - End-of-Talk Ack Time      (stop -> onDownloadUrlReceived)
 *   - Call setup success / fail counts + per-ErrorCode distribution
 *   - Connection state dwell, time-to-connect, successful_connects
 *   - Service availability %    (cumulative CONNECTED / total)
 *   - Talk duration delta       (local vs server-acknowledged)
 *   - Incoming inter-arrival jitter (Welford std-dev), max gap, gap count
 *   - Frame loss %              (expected vs received)
 *   - RMS audio level           (avg + peak, incoming PCM-16)
 *   - isTooShort / isTooLong    (talk duration violations)
 *
 * Performance notes:
 *   - All file I/O runs on a dedicated HandlerThread (Process.THREAD_PRIORITY_BACKGROUND),
 *     so the audio capture / playback paths never block on disk.
 *   - Per-session jitter uses Welford's online algorithm (O(1) memory, no growing list).
 *   - Timestamp formatting uses ThreadLocal<SimpleDateFormat> (SDF is not thread-safe).
 *   - No API > 21 features used (project minSdk = 21).
 *
 * Usage (3 lines, in Application.onCreate after VoicePing.init):
 *     val kpi = PttKpiLogger.init(this, deviceTag = "iNOS-DEV-01")
 *     VoicePing.setConnectionStateListener(kpi.connectionListener)
 *     VoicePing.setIncomingTalkListener(kpi.incomingListener)
 *
 * For programmatic talks:
 *     VoicePing.startTalking(receiverId, channelType,
 *         kpi.wrapOutgoing(yourCallback, receiverId, channelType))
 *
 * For VoicePingButton (which hides its OutgoingTalkCallback):
 *     onStarted -> kpi.onPttButtonStarted(receiverId, channelType)
 *     onStopped -> kpi.onPttButtonStopped()
 *     onError   -> kpi.onPttButtonError(msg)
 *
 * End of run (optional but recommended):
 *     kpi.close()
 */
class PttKpiLogger private constructor(
    private val deviceTag: String,
    private val csvFile: File,
    private val summaryFile: File
) {

    companion object {
        private const val TAG = "PttKpiLogger"

        // SDK frame cadence: AudioParam frameSize=960 @ 16kHz => 60 ms; framePerSent=1.
        private const val EXPECTED_FRAME_INTERVAL_MS = 60L

        // Inter-arrival > 3x expected interval is logged as a "gap" (lost / late packets).
        private const val GAP_THRESHOLD_MS = 180L

        private const val MAX_KV_PAIRS = 8
        private const val EVENT_HEADER =
            "timestamp_ms,iso_time,device,event,session_id,channel," +
            "k1,v1,k2,v2,k3,v3,k4,v4,k5,v5,k6,v6,k7,v7,k8,v8"
        private const val SUMMARY_HEADER =
            "timestamp_ms,iso_time,device,kind,session_id,channel," +
            "duration_local_ms,duration_server_ms,frames,jitter_ms,frame_loss_pct," +
            "access_time_ms,ack_end_time_ms,outcome,error_code,is_too_short,is_too_long"

        private const val NA_LONG = -1L
        private const val NA_INT = -1

        // SimpleDateFormat is not thread-safe → one per thread.
        private val isoFmt: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        }

        @Volatile private var instance: PttKpiLogger? = null

        @JvmStatic
        fun init(context: Context, deviceTag: String = Build.MODEL ?: "device"): PttKpiLogger {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: build(context, deviceTag).also { instance = it }
            }
        }

        @JvmStatic
        fun get(): PttKpiLogger? = instance

        private fun build(context: Context, deviceTag: String): PttKpiLogger {
            val dir = File(context.getExternalFilesDir(null), "kpi").apply { mkdirs() }
            val ts = System.currentTimeMillis()
            val safeTag = deviceTag.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            val csv = File(dir, "ptt_kpi_${safeTag}_$ts.csv")
            val sum = File(dir, "ptt_kpi_summary_${safeTag}_$ts.csv")
            return PttKpiLogger(safeTag, csv, sum)
        }
    }

    // ─── I/O thread (off the audio path) ──────────────────────────────────
    private val ioThread: HandlerThread =
        HandlerThread("KpiIo", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val ioHandler = Handler(ioThread.looper)
    private val csvWriter: BufferedWriter = BufferedWriter(FileWriter(csvFile, true))
    private val summaryWriter: BufferedWriter = BufferedWriter(FileWriter(summaryFile, true))

    // ─── Aggregate counters ───────────────────────────────────────────────
    private val outgoingAttempts = AtomicInteger(0)
    private val outgoingCompleted = AtomicInteger(0)
    private val outgoingFailures = AtomicInteger(0)
    private val outgoingTooShort = AtomicInteger(0)
    private val outgoingTooLong = AtomicInteger(0)
    private val incomingTotal = AtomicInteger(0)

    // Connection state aggregates
    @Volatile private var connStateLastTs: Long = System.currentTimeMillis()
    @Volatile private var connStateLast: ConnectionState = ConnectionState.DISCONNECTED
    private val msInConnected = AtomicLong(0)
    private val msInConnecting = AtomicLong(0)
    private val msInDisconnected = AtomicLong(0)
    private val successfulConnects = AtomicInteger(0)
    private val connectionErrors = AtomicInteger(0)
    @Volatile private var firstConnectMs: Long = NA_LONG

    // Errors keyed by ErrorCode value
    private val errorCounts = ConcurrentHashMap<Int, AtomicInteger>()

    // Fan-out registries. The SDK stores only one listener per slot; the
    // logger acts as the singleton listener and broadcasts to whoever
    // attaches via the add*() methods. Iteration is lock-free
    // (CopyOnWriteArrayList) — fine for a small number of UI listeners.
    private val extraConnListeners =
        java.util.concurrent.CopyOnWriteArrayList<ConnectionStateListener>()
    private val extraIncomingListeners =
        java.util.concurrent.CopyOnWriteArrayList<IncomingTalkListener>()
    private val frameHooks =
        java.util.concurrent.CopyOnWriteArrayList<FrameHook>()

    // Sessions in flight
    private val outgoingInFlight = ConcurrentHashMap<String, Outgoing>()
    private val incomingInFlight = ConcurrentHashMap<String, Incoming>()
    @Volatile private var lastButtonSessionId: String? = null

    init {
        if (csvFile.length() == 0L) ioHandler.post { writeRaw(csvWriter, EVENT_HEADER) }
        if (summaryFile.length() == 0L) ioHandler.post { writeRaw(summaryWriter, SUMMARY_HEADER) }
        writeEvent("RUN_START", null, null,
            "device" to deviceTag,
            "android_sdk" to Build.VERSION.SDK_INT,
            "android_release" to (Build.VERSION.RELEASE ?: ""),
            "manufacturer" to (Build.MANUFACTURER ?: ""),
            "model" to (Build.MODEL ?: ""))
        Log.d(TAG, "KPI csv: ${csvFile.absolutePath}")
    }

    // ─── Connection listener ──────────────────────────────────────────────
    val connectionListener: ConnectionStateListener = object : ConnectionStateListener {
        override fun onConnectionStateChanged(connectionState: ConnectionState) {
            val now = System.currentTimeMillis()
            val previous = connStateLast
            val dwell = now - connStateLastTs
            when (previous) {
                ConnectionState.CONNECTED    -> msInConnected.addAndGet(dwell)
                ConnectionState.CONNECTING   -> msInConnecting.addAndGet(dwell)
                ConnectionState.DISCONNECTED -> msInDisconnected.addAndGet(dwell)
            }
            connStateLast = connectionState
            connStateLastTs = now

            if (previous != ConnectionState.CONNECTED &&
                connectionState == ConnectionState.CONNECTED) {
                successfulConnects.incrementAndGet()
                if (firstConnectMs == NA_LONG) firstConnectMs = dwell
                writeEvent("CONNECT_OK", null, null,
                    "connect_time_ms" to dwell,
                    "from" to previous.name)
            }

            writeEvent("CONN_STATE", null, null,
                "from" to previous.name,
                "to" to connectionState.name,
                "dwell_ms" to dwell)

            // Fan out to UI / app-level listeners.
            for (l in extraConnListeners) {
                try { l.onConnectionStateChanged(connectionState) }
                catch (t: Throwable) { Log.w(TAG, "extra conn listener threw: ${t.message}") }
            }
        }

        override fun onConnectionError(e: VoicePingException) {
            connectionErrors.incrementAndGet()
            bumpError(e.errorCode)
            writeEvent("CONN_ERROR", null, null,
                "code" to e.errorCode,
                "code_name" to errorCodeName(e.errorCode),
                "msg" to (e.message ?: ""))
            for (l in extraConnListeners) {
                try { l.onConnectionError(e) }
                catch (t: Throwable) { Log.w(TAG, "extra conn listener threw: ${t.message}") }
            }
        }
    }

    // ─── Incoming listener ────────────────────────────────────────────────
    val incomingListener: IncomingTalkListener = object : IncomingTalkListener {
        override fun onIncomingTalkStarted(
            audioReceiver: AudioReceiver,
            activeChannels: List<Channel>
        ) {
            val now = System.currentTimeMillis()
            val ch = audioReceiver.channel
            val sid = UUID.randomUUID().toString()
            val key = ch?.toString() ?: "?"
            val state = Incoming(sessionId = sid, startTs = now, channelKey = key)
            incomingInFlight[key] = state
            incomingTotal.incrementAndGet()

            audioReceiver.setInterceptorAfterDecoded(object : AudioInterceptor {
                override fun proceed(data: ByteArray, channel: Channel): ByteArray {
                    state.onFrame(System.currentTimeMillis(), data)
                    // Fan out to UI / amplitude-meter hooks.
                    if (frameHooks.isNotEmpty()) {
                        for (h in frameHooks) {
                            try { h.onIncomingFrame(channel, data) }
                            catch (t: Throwable) { Log.w(TAG, "frame hook threw: ${t.message}") }
                        }
                    }
                    return data
                }
            })

            writeEvent("SESSION_IN_START", sid, ch,
                "active_channels" to activeChannels.size,
                "sender" to (ch?.pureSenderId ?: ""),
                "channel_type" to ChannelType.getText(ch?.type ?: -1))

            // Fan out to UI listeners.
            for (l in extraIncomingListeners) {
                try { l.onIncomingTalkStarted(audioReceiver, activeChannels) }
                catch (t: Throwable) { Log.w(TAG, "extra incoming listener threw: ${t.message}") }
            }
        }

        override fun onIncomingTalkStopped(
            audioMetaData: AudioMetaData,
            activeChannels: List<Channel>
        ) {
            val now = System.currentTimeMillis()
            val ch = audioMetaData.channel
            val key = ch?.toString() ?: "?"
            val state = incomingInFlight.remove(key)
            val snap = state?.snapshot()

            val durationLocal = if (state != null) now - state.startTs else 0L
            val durationServer = audioMetaData.durationInServer
            val frames = snap?.frameCount ?: 0
            val expectedFrames = if (durationLocal > 0)
                (durationLocal / EXPECTED_FRAME_INTERVAL_MS).toInt() else 0
            val frameLossPct = if (expectedFrames > 0)
                ((expectedFrames - frames).coerceAtLeast(0) * 100.0) / expectedFrames else 0.0

            writeEvent("SESSION_IN_STOP", state?.sessionId, ch,
                "duration_local_ms" to durationLocal,
                "duration_server_ms" to durationServer,
                "duration_delta_ms" to (durationLocal - durationServer),
                "frames" to frames,
                "expected_frames" to expectedFrames,
                "frame_loss_pct" to fmt2(frameLossPct),
                "gaps" to (snap?.gapCount ?: 0),
                "max_iat_ms" to (snap?.maxIatMs ?: 0L),
                "jitter_ms" to fmt2(snap?.stdDev ?: 0.0))
            writeEvent("SESSION_IN_AUDIO", state?.sessionId, ch,
                "avg_iat_ms" to fmt2(snap?.meanIat ?: 0.0),
                "avg_rms" to fmt1(snap?.meanRms ?: 0.0),
                "peak_rms" to fmt1(snap?.peakRms ?: 0.0),
                "had_start_signal" to audioMetaData.hasStartSignal(),
                "had_stop_signal" to audioMetaData.hasStopSignal(),
                "active_channels" to activeChannels.size,
                "download_url" to (audioMetaData.downloadUrl ?: ""))

            writeSummaryRow(
                kind = "INCOMING",
                sessionId = state?.sessionId ?: "",
                channel = ch,
                durationLocalMs = durationLocal,
                durationServerMs = durationServer,
                frames = frames,
                jitterMs = snap?.stdDev ?: 0.0,
                frameLossPct = frameLossPct,
                accessTimeMs = NA_LONG,
                ackEndTimeMs = NA_LONG,
                outcome = "ok",
                errorCode = NA_INT,
                tooShort = false,
                tooLong = false
            )

            for (l in extraIncomingListeners) {
                try { l.onIncomingTalkStopped(audioMetaData, activeChannels) }
                catch (t: Throwable) { Log.w(TAG, "extra incoming listener threw: ${t.message}") }
            }
        }

        override fun onIncomingTalkError(e: VoicePingException) {
            bumpError(e.errorCode)
            writeEvent("SESSION_IN_ERROR", null, null,
                "code" to e.errorCode,
                "code_name" to errorCodeName(e.errorCode),
                "msg" to (e.message ?: ""))
            for (l in extraIncomingListeners) {
                try { l.onIncomingTalkError(e) }
                catch (t: Throwable) { Log.w(TAG, "extra incoming listener threw: ${t.message}") }
            }
        }
    }

    // ─── Outgoing wrappers ────────────────────────────────────────────────

    /** Wrap your existing OutgoingTalkCallback so the logger sees the lifecycle. */
    fun wrapOutgoing(
        delegate: OutgoingTalkCallback?,
        receiverId: String,
        channelType: Int
    ): OutgoingTalkCallback {
        val state = Outgoing(
            sessionId = UUID.randomUUID().toString(),
            requestTs = System.currentTimeMillis(),
            receiverId = receiverId,
            channelType = channelType
        )
        outgoingInFlight[state.sessionId] = state
        outgoingAttempts.incrementAndGet()
        val ch = Channel(channelType, null, receiverId)
        writeEvent("SESSION_OUT_REQUEST", state.sessionId, ch,
            "receiver" to receiverId,
            "channel_type" to ChannelType.getText(channelType))

        return object : OutgoingTalkCallback {
            override fun onOutgoingTalkStarted(audioSessionId: Int) {
                val now = System.currentTimeMillis()
                state.startedTs = now
                writeEvent("SESSION_OUT_START", state.sessionId, ch,
                    "audio_session_id" to audioSessionId,
                    "access_time_ms" to (now - state.requestTs))
                delegate?.onOutgoingTalkStarted(audioSessionId)
            }

            override fun onOutgoingTalkStopped(isTooShort: Boolean, isTooLong: Boolean) {
                val now = System.currentTimeMillis()
                state.stoppedTs = now
                if (isTooShort) outgoingTooShort.incrementAndGet()
                if (isTooLong) outgoingTooLong.incrementAndGet()
                outgoingCompleted.incrementAndGet()
                val duration = if (state.startedTs > 0) now - state.startedTs else 0L
                writeEvent("SESSION_OUT_STOP", state.sessionId, ch,
                    "duration_local_ms" to duration,
                    "is_too_short" to isTooShort,
                    "is_too_long" to isTooLong)
                delegate?.onOutgoingTalkStopped(isTooShort, isTooLong)
            }

            override fun onDownloadUrlReceived(downloadUrl: String) {
                val now = System.currentTimeMillis()
                val ackEndMs = if (state.stoppedTs > 0) now - state.stoppedTs else NA_LONG
                writeEvent("SESSION_OUT_ACK_END", state.sessionId, ch,
                    "ack_end_time_ms" to ackEndMs,
                    "download_url" to downloadUrl)
                outgoingInFlight.remove(state.sessionId)?.let { s ->
                    writeSummaryRow(
                        kind = "OUTGOING",
                        sessionId = s.sessionId,
                        channel = ch,
                        durationLocalMs = if (s.startedTs > 0 && s.stoppedTs > 0)
                            s.stoppedTs - s.startedTs else 0L,
                        durationServerMs = NA_INT,
                        frames = NA_INT,
                        jitterMs = -1.0,
                        frameLossPct = -1.0,
                        accessTimeMs = if (s.startedTs > 0) s.startedTs - s.requestTs else NA_LONG,
                        ackEndTimeMs = ackEndMs,
                        outcome = "ok",
                        errorCode = NA_INT,
                        tooShort = false,
                        tooLong = false
                    )
                }
                delegate?.onDownloadUrlReceived(downloadUrl)
            }

            override fun onOutgoingTalkError(e: VoicePingException) {
                outgoingFailures.incrementAndGet()
                bumpError(e.errorCode)
                writeEvent("SESSION_OUT_ERROR", state.sessionId, ch,
                    "code" to e.errorCode,
                    "code_name" to errorCodeName(e.errorCode),
                    "msg" to (e.message ?: ""),
                    "elapsed_since_request_ms" to (System.currentTimeMillis() - state.requestTs))
                outgoingInFlight.remove(state.sessionId)?.let { s ->
                    writeSummaryRow(
                        kind = "OUTGOING",
                        sessionId = s.sessionId,
                        channel = ch,
                        durationLocalMs = 0L,
                        durationServerMs = NA_INT,
                        frames = NA_INT,
                        jitterMs = -1.0,
                        frameLossPct = -1.0,
                        accessTimeMs = if (s.startedTs > 0) s.startedTs - s.requestTs else NA_LONG,
                        ackEndTimeMs = NA_LONG,
                        outcome = "error",
                        errorCode = e.errorCode,
                        tooShort = false,
                        tooLong = false
                    )
                }
                delegate?.onOutgoingTalkError(e)
            }
        }
    }

    /** Call from VoicePingButton.Listener.onStarted(). Returns the session id. */
    fun onPttButtonStarted(receiverId: String, channelType: Int): String {
        val now = System.currentTimeMillis()
        val state = Outgoing(
            sessionId = UUID.randomUUID().toString(),
            requestTs = now,
            startedTs = now,
            receiverId = receiverId,
            channelType = channelType
        )
        outgoingInFlight[state.sessionId] = state
        outgoingAttempts.incrementAndGet()
        lastButtonSessionId = state.sessionId
        writeEvent("BUTTON_OUT_START", state.sessionId,
            Channel(channelType, null, receiverId),
            "receiver" to receiverId,
            "channel_type" to ChannelType.getText(channelType))
        return state.sessionId
    }

    /** Call from VoicePingButton.Listener.onStopped(). */
    fun onPttButtonStopped(sessionId: String? = null) {
        val sid = sessionId ?: lastButtonSessionId ?: return
        lastButtonSessionId = null
        val s = outgoingInFlight.remove(sid) ?: return
        val now = System.currentTimeMillis()
        outgoingCompleted.incrementAndGet()
        val duration = now - s.startedTs
        val ch = Channel(s.channelType, null, s.receiverId)
        writeEvent("BUTTON_OUT_STOP", sid, ch, "duration_local_ms" to duration)
        writeSummaryRow(
            kind = "OUTGOING_BTN",
            sessionId = sid,
            channel = ch,
            durationLocalMs = duration,
            durationServerMs = NA_INT,
            frames = NA_INT,
            jitterMs = -1.0,
            frameLossPct = -1.0,
            accessTimeMs = NA_LONG,
            ackEndTimeMs = NA_LONG,
            outcome = "ok",
            errorCode = NA_INT,
            tooShort = false,
            tooLong = false
        )
    }

    /** Call from VoicePingButton.Listener.onError(msg). */
    fun onPttButtonError(message: String, sessionId: String? = null) {
        outgoingFailures.incrementAndGet()
        val sid = sessionId ?: lastButtonSessionId
        if (sid != null) {
            lastButtonSessionId = null
            outgoingInFlight.remove(sid)
        }
        writeEvent("BUTTON_OUT_ERROR", sid, null, "msg" to message)
    }

    // ─── CSV plumbing ─────────────────────────────────────────────────────

    private fun writeEvent(
        event: String,
        sessionId: String?,
        channel: Channel?,
        vararg fields: Pair<String, Any?>
    ) {
        val ts = System.currentTimeMillis()
        val sid = sessionId ?: ""
        val chKey = channelKey(channel)
        // Snapshot fields immediately — the caller might recycle objects.
        val copied: List<Pair<String, Any?>> = if (fields.isEmpty()) emptyList() else fields.toList()
        ioHandler.post {
            val sb = StringBuilder(128)
            sb.append(ts).append(',')
                .append(isoFmt.get()!!.format(Date(ts))).append(',')
                .append(csv(deviceTag)).append(',')
                .append(csv(event)).append(',')
                .append(csv(sid)).append(',')
                .append(csv(chKey))
            val limit = if (copied.size < MAX_KV_PAIRS) copied.size else MAX_KV_PAIRS
            for (i in 0 until MAX_KV_PAIRS) {
                if (i < limit) {
                    val p = copied[i]
                    sb.append(',').append(csv(p.first)).append(',').append(csv(p.second?.toString() ?: ""))
                } else {
                    sb.append(",,")
                }
            }
            writeRaw(csvWriter, sb.toString())
        }
    }

    private fun writeSummaryRow(
        kind: String,
        sessionId: String,
        channel: Channel?,
        durationLocalMs: Long,
        durationServerMs: Int,
        frames: Int,
        jitterMs: Double,
        frameLossPct: Double,
        accessTimeMs: Long,
        ackEndTimeMs: Long,
        outcome: String,
        errorCode: Int,
        tooShort: Boolean,
        tooLong: Boolean
    ) {
        val ts = System.currentTimeMillis()
        val chKey = channelKey(channel)
        ioHandler.post {
            val sb = StringBuilder(160)
            sb.append(ts).append(',')
                .append(isoFmt.get()!!.format(Date(ts))).append(',')
                .append(csv(deviceTag)).append(',')
                .append(csv(kind)).append(',')
                .append(csv(sessionId)).append(',')
                .append(csv(chKey)).append(',')
                .append(durationLocalMs).append(',')
                .append(durationServerMs).append(',')
                .append(frames).append(',')
                .append(fmt2(jitterMs)).append(',')
                .append(fmt2(frameLossPct)).append(',')
                .append(accessTimeMs).append(',')
                .append(ackEndTimeMs).append(',')
                .append(csv(outcome)).append(',')
                .append(errorCode).append(',')
                .append(if (tooShort) 1 else 0).append(',')
                .append(if (tooLong) 1 else 0)
            writeRaw(summaryWriter, sb.toString())
        }
    }

    private fun writeRaw(w: BufferedWriter, line: String) {
        try {
            w.write(line)
            w.newLine()
            w.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "csv write failed: ${t.message}")
        }
    }

    /** Append RUN_SUMMARY rows. Idempotent — safe to call repeatedly. */
    fun exportSummary() {
        val now = System.currentTimeMillis()
        val dwell = now - connStateLastTs
        when (connStateLast) {
            ConnectionState.CONNECTED    -> msInConnected.addAndGet(dwell)
            ConnectionState.CONNECTING   -> msInConnecting.addAndGet(dwell)
            ConnectionState.DISCONNECTED -> msInDisconnected.addAndGet(dwell)
        }
        connStateLastTs = now

        val total = msInConnected.get() + msInConnecting.get() + msInDisconnected.get()
        val availability = if (total > 0) msInConnected.get() * 100.0 / total else 0.0

        writeEvent("RUN_SUMMARY", null, null,
            "outgoing_attempts" to outgoingAttempts.get(),
            "outgoing_completed" to outgoingCompleted.get(),
            "outgoing_failures" to outgoingFailures.get(),
            "incoming_total" to incomingTotal.get(),
            "successful_connects" to successfulConnects.get(),
            "first_connect_ms" to firstConnectMs,
            "ms_connected" to msInConnected.get(),
            "availability_pct" to fmt2(availability))
        writeEvent("RUN_SUMMARY_2", null, null,
            "too_short" to outgoingTooShort.get(),
            "too_long" to outgoingTooLong.get(),
            "ms_connecting" to msInConnecting.get(),
            "ms_disconnected" to msInDisconnected.get(),
            "connection_errors" to connectionErrors.get())
        for ((code, n) in errorCounts) {
            writeEvent("RUN_SUMMARY_ERROR", null, null,
                "code" to code,
                "code_name" to errorCodeName(code),
                "count" to n.get())
        }
    }

    /** Flush, dump summary, and tear down the I/O thread. */
    fun close() {
        exportSummary()
        ioHandler.post {
            try { csvWriter.flush(); csvWriter.close() } catch (_: Throwable) {}
            try { summaryWriter.flush(); summaryWriter.close() } catch (_: Throwable) {}
            ioThread.quitSafely()
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private fun bumpError(code: Int) {
        // API-21-safe: ConcurrentHashMap.putIfAbsent is fine on all SDK levels.
        val existing = errorCounts[code]
        if (existing != null) {
            existing.incrementAndGet()
            return
        }
        val fresh = AtomicInteger(1)
        val raced = errorCounts.putIfAbsent(code, fresh)
        raced?.incrementAndGet()
    }

    // ─── Fan-out registration ─────────────────────────────────────────────

    /**
     * Per-frame hook attached on top of the KPI's after-decoded interceptor.
     * Invoked synchronously on the SDK's player background thread — keep
     * implementations cheap and non-blocking. Do not allocate per call.
     */
    interface FrameHook {
        fun onIncomingFrame(channel: Channel, pcm: ByteArray)
    }

    fun addConnectionStateListener(l: ConnectionStateListener) { extraConnListeners.addIfAbsent(l) }
    fun removeConnectionStateListener(l: ConnectionStateListener) { extraConnListeners.remove(l) }

    fun addIncomingTalkListener(l: IncomingTalkListener) { extraIncomingListeners.addIfAbsent(l) }
    fun removeIncomingTalkListener(l: IncomingTalkListener) { extraIncomingListeners.remove(l) }

    fun addFrameHook(h: FrameHook) { frameHooks.addIfAbsent(h) }
    fun removeFrameHook(h: FrameHook) { frameHooks.remove(h) }

    private fun csv(s: String): String {
        if (s.isEmpty()) return ""
        var needsQuote = false
        for (i in s.indices) {
            val ch = s[i]
            if (ch == ',' || ch == '"' || ch == '\n' || ch == '\r') { needsQuote = true; break }
        }
        if (!needsQuote) return s
        return "\"" + s.replace("\"", "\"\"") + "\""
    }

    private fun channelKey(c: Channel?): String =
        if (c == null) "" else "${ChannelType.getText(c.type)}|${c.pureSenderId ?: ""}|${c.pureReceiverId ?: ""}"

    private fun errorCodeName(code: Int): String = when (code) {
        ErrorCode.UNKNOWN -> "UNKNOWN"
        ErrorCode.INTERNET_DISCONNECTED -> "INTERNET_DISCONNECTED"
        ErrorCode.SOCKET_DISCONNECTED -> "SOCKET_DISCONNECTED"
        ErrorCode.ACK_START_FAILED -> "ACK_START_FAILED"
        ErrorCode.ACK_START_TIMEOUT -> "ACK_START_TIMEOUT"
        ErrorCode.ACK_END_TIMEOUT -> "ACK_END_TIMEOUT"
        ErrorCode.CONNECTION_FAILURE -> "CONNECTION_FAILURE"
        ErrorCode.UNAUTHORIZED_GROUP -> "UNAUTHORIZED_GROUP"
        ErrorCode.DUPLICATE_CONNECT -> "DUPLICATE_CONNECT"
        else -> "CODE_$code"
    }

    private fun fmt1(v: Double) = "%.1f".format(Locale.US, v)
    private fun fmt2(v: Double) = "%.2f".format(Locale.US, v)

    // ─── Per-session state ────────────────────────────────────────────────

    private class Outgoing(
        val sessionId: String,
        val requestTs: Long,
        @Volatile var startedTs: Long = 0L,
        @Volatile var stoppedTs: Long = 0L,
        val receiverId: String,
        val channelType: Int
    )

    /**
     * Incoming session state. All mutations happen on the SDK's player background
     * thread (Player.mPlayerHandler), so we use a single intrinsic lock to publish
     * a consistent snapshot to whatever thread invokes onIncomingTalkStopped.
     *
     * Inter-arrival jitter and RMS use Welford-style online accumulators
     * (O(1) memory regardless of session length).
     */
    private class Incoming(
        val sessionId: String,
        val startTs: Long,
        val channelKey: String
    ) {
        // Frame counters
        private var lastFrameTs: Long = 0L
        private var frameCount: Int = 0
        private var byteCount: Long = 0L
        private var gapCount: Int = 0

        // Welford for inter-arrival
        private var iatN: Long = 0L
        private var iatMean: Double = 0.0
        private var iatM2: Double = 0.0
        private var iatMax: Long = 0L

        // RMS running mean + peak (Welford-style mean)
        private var rmsN: Long = 0L
        private var rmsMean: Double = 0.0
        private var rmsPeak: Double = 0.0

        @Synchronized
        fun onFrame(now: Long, pcm: ByteArray) {
            if (lastFrameTs > 0L) {
                val iat = now - lastFrameTs
                if (iat > GAP_THRESHOLD) gapCount++
                if (iat > iatMax) iatMax = iat
                iatN++
                val delta = iat - iatMean
                iatMean += delta / iatN
                iatM2 += delta * (iat - iatMean)
            }
            lastFrameTs = now
            frameCount++
            byteCount += pcm.size

            val rms = pcm16Rms(pcm)
            rmsN++
            rmsMean += (rms - rmsMean) / rmsN
            if (rms > rmsPeak) rmsPeak = rms
        }

        @Synchronized
        fun snapshot(): IncomingSnapshot = IncomingSnapshot(
            frameCount = frameCount,
            byteCount = byteCount,
            gapCount = gapCount,
            maxIatMs = iatMax,
            meanIat = iatMean,
            stdDev = if (iatN < 2) 0.0 else sqrt(iatM2 / (iatN - 1)),
            meanRms = rmsMean,
            peakRms = rmsPeak
        )

        companion object {
            // Mirror of outer GAP_THRESHOLD_MS; kept local to avoid relying
            // on Kotlin's nested-class access to outer companion privates.
            private const val GAP_THRESHOLD = 180L

            private fun pcm16Rms(pcm: ByteArray): Double {
                if (pcm.size < 2) return 0.0
                val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
                var sum = 0.0
                var n = 0
                while (bb.remaining() >= 2) {
                    val s = bb.short.toInt()
                    sum += s.toDouble() * s.toDouble()
                    n++
                }
                return if (n == 0) 0.0 else sqrt(sum / n)
            }
        }
    }

    private data class IncomingSnapshot(
        val frameCount: Int,
        val byteCount: Long,
        val gapCount: Int,
        val maxIatMs: Long,
        val meanIat: Double,
        val stdDev: Double,
        val meanRms: Double,
        val peakRms: Double
    )
}
