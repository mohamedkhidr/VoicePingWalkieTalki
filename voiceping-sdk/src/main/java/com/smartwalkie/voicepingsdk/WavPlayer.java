package com.smartwalkie.voicepingsdk;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import com.smartwalkie.voicepingsdk.model.AudioParam;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class WavPlayer {

    private static final String TAG = WavPlayer.class.getSimpleName();

    private final WavSource mSource;
    private final AudioParam mAudioParam;
    private final FrameCallback mFrameCallback;

    private final Connection mConnection;

    private volatile boolean mIsPlaying;
    private Thread mPlayThread;

    // ── Constructors ──────────────────────────────────────────────────────────

    /** Play from a file on disk. */
    public WavPlayer(File wavFile, AudioParam audioParam, FrameCallback frameCallback, Connection mConnection) {
        this(new FileSource(wavFile), audioParam, frameCallback, mConnection);
    }

    /** Play from a WAV in the app's assets folder. */
    public WavPlayer(Context context, String assetName, AudioParam audioParam,
                     FrameCallback frameCallback, Connection mConnection) {
        this(new AssetSource(context.getApplicationContext(), assetName), audioParam, frameCallback, mConnection);
    }

    private WavPlayer(WavSource source, AudioParam audioParam,
                      FrameCallback frameCallback, Connection connection) {
        mSource = source;
        mAudioParam = audioParam;
        mFrameCallback = frameCallback;
        mConnection = connection;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() {
        mIsPlaying = true;
        mPlayThread = new PlayThread();
        mPlayThread.start();
    }

    public void stop() {
        mIsPlaying = false;
        if (mPlayThread != null) {
            mPlayThread.interrupt();
            mPlayThread = null;
        }
    }

    // ── Source abstraction ────────────────────────────────────────────────────

    /**
     * Abstracts where WAV bytes come from. We need two operations:
     *   1. Open a fresh InputStream (called twice: once to parse header,
     *      once to stream audio).
     *   2. Give a display name for error messages/logs.
     *
     * Note: we can't rely on a single seekable stream because assets aren't
     * guaranteed to support mark/reset or skip-then-rewind reliably. Opening
     * twice is simple and portable.
     */
    private interface WavSource {
        InputStream open() throws IOException;
        String name();
        /** Total length in bytes, or -1 if unknown. */
        long length() throws IOException;
    }

    private static class FileSource implements WavSource {
        private final File file;
        FileSource(File file) { this.file = file; }

        @Override public InputStream open() throws IOException {
            if (!file.exists()) throw new IOException("WAV file not found: " + file.getPath());
            return new FileInputStream(file);
        }
        @Override public String name() { return file.getName(); }
        @Override public long length() { return file.length(); }
    }

    private static class AssetSource implements WavSource {
        private final Context context;
        private final String assetName;
        AssetSource(Context context, String assetName) {
            this.context = context;
            this.assetName = assetName;
        }

        @Override public InputStream open() throws IOException {
            return context.getAssets().open(assetName);
        }
        @Override public String name() { return "assets/" + assetName; }
        @Override public long length() throws IOException {
            AssetFileDescriptor afd = null;
            try {
                afd = context.getAssets().openFd(assetName);
                return afd.getLength();
            } catch (IOException e) {
                // Asset might be compressed — length unknown without reading it all.
                return -1;
            } finally {
                if (afd != null) try { afd.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ── Header parsing ────────────────────────────────────────────────────────

    private static class WavInfo {
        int sampleRate;
        int channels;
        int bitsPerSample;
        long dataOffset;
        long dataSize;
    }

    private WavInfo parseWavHeader() throws IOException {
        long total = mSource.length();
        if (total != -1 && total < 44) {
            throw new IOException("File too small to be WAV: " + mSource.name());
        }

        WavInfo info = new WavInfo();
        InputStream in = mSource.open();
        try {
            byte[] buf = new byte[12];
            if (readFully(in, buf) != 12) throw new IOException("WAV too small: " + mSource.name());

            String riff = new String(buf, 0, 4);
            String wave = new String(buf, 8, 4);
            if (!"RIFF".equals(riff) || !"WAVE".equals(wave)) {
                throw new IOException("Not a RIFF/WAVE file: " + mSource.name());
            }

            long position = 12;
            boolean fmtFound = false;

            while (true) {
                byte[] chunkHeader = new byte[8];
                if (readFully(in, chunkHeader) != 8) {
                    throw new IOException("Unexpected EOF reading chunk header");
                }
                position += 8;

                String chunkId = new String(chunkHeader, 0, 4);
                int chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).getInt();

                if ("fmt ".equals(chunkId)) {
                    byte[] fmt = new byte[chunkSize];
                    if (readFully(in, fmt) != chunkSize) throw new IOException("Bad fmt chunk");
                    position += chunkSize;

                    int audioFormat = ByteBuffer.wrap(fmt, 0, 2)
                            .order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
                    info.channels = ByteBuffer.wrap(fmt, 2, 2)
                            .order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
                    info.sampleRate = ByteBuffer.wrap(fmt, 4, 4)
                            .order(ByteOrder.LITTLE_ENDIAN).getInt();
                    info.bitsPerSample = ByteBuffer.wrap(fmt, 14, 2)
                            .order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;

                    if (audioFormat != 1) {
                        throw new IOException("Unsupported WAV format (not PCM): " + audioFormat);
                    }
                    fmtFound = true;
                } else if ("data".equals(chunkId)) {
                    if (!fmtFound) throw new IOException("data chunk before fmt chunk");
                    info.dataOffset = position;
                    info.dataSize = chunkSize & 0xFFFFFFFFL;
                    return info;
                } else {
                    // Skip unknown chunk (LIST, JUNK, bext, etc.)
                    long skipped = 0;
                    while (skipped < chunkSize) {
                        long s = in.skip(chunkSize - skipped);
                        if (s <= 0) throw new IOException("Could not skip chunk " + chunkId);
                        skipped += s;
                    }
                    position += chunkSize;
                }
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    /** InputStream.read() can return less than requested; loop until full or EOF. */
    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        while (offset < buf.length) {
            int r = in.read(buf, offset, buf.length - offset);
            if (r == -1) break;
            offset += r;
        }
        return offset;
    }

    // ── Audio conversion ──────────────────────────────────────────────────────

    private static byte[] stereoToMono(byte[] stereo, int validBytes) {
        int samples = validBytes / 4;
        byte[] mono = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            int l = (short) ((stereo[i * 4] & 0xFF) | (stereo[i * 4 + 1] << 8));
            int r = (short) ((stereo[i * 4 + 2] & 0xFF) | (stereo[i * 4 + 3] << 8));
            short mixed = (short) ((l + r) / 2);
            mono[i * 2]     = (byte) (mixed & 0xFF);
            mono[i * 2 + 1] = (byte) ((mixed >> 8) & 0xFF);
        }
        return mono;
    }

    private static byte[] resampleLinear(byte[] src, int srcRate, int dstRate) {
        if (srcRate == dstRate) return src;
        int srcSamples = src.length / 2;
        int dstSamples = (int) ((long) srcSamples * dstRate / srcRate);
        byte[] dst = new byte[dstSamples * 2];

        double ratio = (double) srcRate / dstRate;
        for (int i = 0; i < dstSamples; i++) {
            double srcIndex = i * ratio;
            int idx0 = (int) srcIndex;
            int idx1 = Math.min(idx0 + 1, srcSamples - 1);
            double frac = srcIndex - idx0;

            short s0 = (short) ((src[idx0 * 2] & 0xFF) | (src[idx0 * 2 + 1] << 8));
            short s1 = (short) ((src[idx1 * 2] & 0xFF) | (src[idx1 * 2 + 1] << 8));
            short out = (short) (s0 + (s1 - s0) * frac);

            dst[i * 2]     = (byte) (out & 0xFF);
            dst[i * 2 + 1] = (byte) ((out >> 8) & 0xFF);
        }
        return dst;
    }

    // ── Playback thread ───────────────────────────────────────────────────────

    private class PlayThread extends Thread {

        @Override
        public void run() {
            InputStream in = null;
            try {
                WavInfo info = parseWavHeader();
                Log.d(TAG, "WAV (" + mSource.name() + "): " + info.sampleRate + " Hz, "
                        + info.channels + " ch, " + info.bitsPerSample + " bit");

                if (info.bitsPerSample != 16) {
                    throw new IOException("Only 16-bit PCM WAV is supported. Got: "
                            + info.bitsPerSample);
                }

                int targetSampleRate = mAudioParam.getSampleRate();
                int targetFrameSize  = mAudioParam.getFrameSize();
                int targetChannels   = mAudioParam.getChannelSize();
                int targetFrameBytes = targetFrameSize * 2 * targetChannels;

                int srcSamplesPerFrame = (int) Math.round(
                        (long) targetFrameSize * info.sampleRate / (double) targetSampleRate);
                int srcBytesPerFrame = srcSamplesPerFrame * 2 * info.channels;

                // Open a fresh stream and seek to data offset.
                in = mSource.open();
                long skipped = 0;
                while (skipped < info.dataOffset) {
                    long s = in.skip(info.dataOffset - skipped);
                    if (s <= 0) throw new IOException("Could not seek to data");
                    skipped += s;
                }

                byte[] srcFrame = new byte[srcBytesPerFrame];
                long bytesRemaining = info.dataSize;
                long frameDurationMs = (targetFrameSize * 1000L) / targetSampleRate;
                long nextFrameTime = System.currentTimeMillis();


                while (mIsPlaying && bytesRemaining > 0) {


                    int toRead = (int) Math.min(srcBytesPerFrame, bytesRemaining);
                    int bytesRead = readFully(in, srcFrame);
                    if (bytesRead <= 0) break;
                    bytesRemaining -= bytesRead;

                    if (bytesRead < srcBytesPerFrame) {
                        for (int i = bytesRead; i < srcBytesPerFrame; i++) srcFrame[i] = 0;
                    }

                    byte[] mono = (info.channels == 2)
                            ? stereoToMono(srcFrame, srcBytesPerFrame)
                            : srcFrame;

                    byte[] resampled = resampleLinear(mono, info.sampleRate, targetSampleRate);

                    byte[] out;
                    if (resampled.length == targetFrameBytes) {
                        out = resampled;
                    } else if (resampled.length > targetFrameBytes) {
                        out = new byte[targetFrameBytes];
                        System.arraycopy(resampled, 0, out, 0, targetFrameBytes);
                    } else {
                        out = new byte[targetFrameBytes];
                        System.arraycopy(resampled, 0, out, 0, resampled.length);
                    }

                    mFrameCallback.onFrameCaptured(out);

                    nextFrameTime += frameDurationMs;
                    long sleep = nextFrameTime - System.currentTimeMillis();
                   // if (sleep > 0) Thread.sleep(sleep);
                }

                if (mIsPlaying && bytesRemaining <= 0) {
                    mFrameCallback.onCompleted();
                }

            } catch (Exception e) {
                Log.e(TAG, "WavPlayer error", e);
                mFrameCallback.onError(e);
            } finally {
                if (in != null) {
                    try { in.close(); } catch (IOException ignored) {}
                }
            }
        }
    }
}