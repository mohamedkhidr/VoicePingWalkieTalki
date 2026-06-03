package com.smartwalkie.voicepingsdk;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import com.smartwalkie.voicepingsdk.model.Channel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class IncomingVideoSession {

    private static final String TAG = "IncomingVideoSession";
    private static final int FRAME_QUEUE_CAPACITY = 30;

    private final Channel mChannel;
    private final Runnable mTimeoutRunner;
    private long mStartTime;
    private long mStopTime;
    private boolean mHasStartSignal;
    private boolean mHasStopSignal;

    private Surface mOutputSurface;
    private MediaCodec mDecoder;
    private volatile boolean mDecoderRunning;
    private Thread mDecoderThread;

    private final LinkedBlockingQueue<FrameData> mFrameQueue =
            new LinkedBlockingQueue<>(FRAME_QUEUE_CAPACITY);

    private static class FrameData {
        final byte[] data;
        final boolean isKeyFrame;

        FrameData(byte[] d, boolean k) {
            data = d;
            isKeyFrame = k;
        }
    }

    IncomingVideoSession(Channel channel, Runnable timeoutRunner) {
        mChannel = channel;
        mTimeoutRunner = timeoutRunner;
        mStartTime = System.currentTimeMillis();
    }

    boolean isActive() {
        return mStopTime == 0;
    }

    void start() {
        mStartTime = System.currentTimeMillis();
        mStopTime = 0;
    }

    void stop() {
        mStopTime = System.currentTimeMillis();
        releaseDecoder();
    }

    Channel getChannel() { return mChannel; }
    Runnable getTimeoutRunner() { return mTimeoutRunner; }
    long getStartTime() { return mStartTime; }
    long getStopTime() { return mStopTime; }
    void setStartSignal(boolean v) { mHasStartSignal = v; }
    void setStopSignal(boolean v) { mHasStopSignal = v; }
    boolean hasStartSignal() { return mHasStartSignal; }

    void setOutputSurface(Surface surface) {
        mOutputSurface = surface;
    }

    /**
     * Queue an encoded H.264 frame for decoding.
     * Drops the frame if the queue is full (keeps latency low).
     * Non-keyframe data before the first keyframe is silently discarded.
     */
    void queueFrame(byte[] data, boolean isKeyFrame) {
        if (!mDecoderRunning) {
            if (!isKeyFrame) return; // wait for a keyframe to initialize
            if (mOutputSurface == null) return;
            if (!initDecoder()) return;
        }
        // Offer without blocking — drop frame if queue is saturated
        mFrameQueue.offer(new FrameData(data, isKeyFrame));
    }

    private boolean initDecoder() {
        try {
            mDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            // Resolution will be determined from the SPS embedded in the first keyframe.
            // We configure with a placeholder; the decoder adapts on INFO_OUTPUT_FORMAT_CHANGED.
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
            mDecoder.configure(format, mOutputSurface, null, 0);
            mDecoder.start();
            mDecoderRunning = true;

            mDecoderThread = new Thread(this::decodeLoop, "VideoDecoder-" + mChannel.toString());
            mDecoderThread.setDaemon(true);
            mDecoderThread.start();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to create decoder", e);
            return false;
        }
    }

    private void decodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (mDecoderRunning) {
            try {
                FrameData frame = mFrameQueue.poll(100, TimeUnit.MILLISECONDS);
                if (frame == null) continue;

                // Feed encoded data into the decoder
                int inputIndex = mDecoder.dequeueInputBuffer(10_000);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuf = mDecoder.getInputBuffer(inputIndex);
                    if (inputBuf != null) {
                        inputBuf.clear();
                        inputBuf.put(frame.data);
                        mDecoder.queueInputBuffer(inputIndex, 0, frame.data.length, 0, 0);
                    }
                }

                // Drain output — render to surface
                int outputIndex = mDecoder.dequeueOutputBuffer(info, 10_000);
                if (outputIndex >= 0) {
                    mDecoder.releaseOutputBuffer(outputIndex, true); // true = render to surface
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (mDecoderRunning) Log.e(TAG, "Decoder error", e);
                break;
            }
        }
    }

    private void releaseDecoder() {
        mDecoderRunning = false;
        if (mDecoderThread != null) {
            mDecoderThread.interrupt();
            try { mDecoderThread.join(500); } catch (InterruptedException ignored) {}
            mDecoderThread = null;
        }
        if (mDecoder != null) {
            try { mDecoder.stop(); } catch (Exception ignored) {}
            try { mDecoder.release(); } catch (Exception ignored) {}
            mDecoder = null;
        }
        mFrameQueue.clear();
    }
}
