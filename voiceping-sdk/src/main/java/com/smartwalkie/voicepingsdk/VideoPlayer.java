package com.smartwalkie.voicepingsdk;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.smartwalkie.voicepingsdk.listener.IncomingVideoListener;
import com.smartwalkie.voicepingsdk.model.Channel;
import com.smartwalkie.voicepingsdk.model.Message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Receives VIDEO_FRAME messages, manages per-channel IncomingVideoSession instances,
 * and drives MediaCodec decoding to the Surface provided by IncomingVideoListener.
 */
class VideoPlayer {

    private static final String TAG = "VideoPlayer";

    private final Handler mBackgroundHandler;
    private IncomingVideoListener mIncomingVideoListener;
    private final Map<String, IncomingVideoSession> mActiveSessions = new HashMap<>();

    VideoPlayer(Looper backgroundLooper) {
        mBackgroundHandler = new Handler(backgroundLooper);
    }

    void setIncomingVideoListener(IncomingVideoListener listener) {
        mIncomingVideoListener = listener;
    }

    /** Called by Player when a VIDEO_FRAME message arrives. */
    void onVideoFrameReceived(Message message) {
        Channel channel = new Channel(
                message.getChannelType(), message.getSenderId(), message.getReceiverId());

        IncomingVideoSession session = getOrCreateSession(channel);
        if (session == null || !session.isActive()) return;

        byte[] payload = message.getPayload();
        if (payload == null || payload.length < 2) return;

        boolean isKeyFrame = payload[0] == 1;
        byte[] frameData = new byte[payload.length - 1];
        System.arraycopy(payload, 1, frameData, 0, frameData.length);

        session.queueFrame(frameData, isKeyFrame);

        // Reset inactivity timeout
        mBackgroundHandler.removeCallbacks(session.getTimeoutRunner());
        mBackgroundHandler.postDelayed(session.getTimeoutRunner(), 5_000);
    }

    /** Called by Player when a STOP_TALKING message arrives (may affect a video session). */
    void onStopTalkingReceived(Message message) {
        Channel channel = new Channel(
                message.getChannelType(), message.getSenderId(), message.getReceiverId());
        stopSession(channel);
    }

    private IncomingVideoSession getOrCreateSession(Channel channel) {
        IncomingVideoSession session = mActiveSessions.get(channel.toString());
        if (session == null) {
            session = new IncomingVideoSession(channel, newTimeoutRunner(channel));
            mActiveSessions.put(channel.toString(), session);
            configureSessionSurface(session, channel);
        } else if (!session.isActive()) {
            session.start();
            configureSessionSurface(session, channel);
        }
        return session;
    }

    private void configureSessionSurface(IncomingVideoSession session, Channel channel) {
        if (mIncomingVideoListener != null) {
            Surface surface = mIncomingVideoListener.onIncomingVideoStarted(
                    channel, getActiveChannels());
            if (surface != null) {
                session.setOutputSurface(surface);
            }
        }
    }

    private void stopSession(Channel channel) {
        IncomingVideoSession session = mActiveSessions.get(channel.toString());
        if (session == null || !session.isActive()) return;
        session.stop();
        mBackgroundHandler.removeCallbacks(session.getTimeoutRunner());
        Log.d(TAG, "Video session stopped for channel: " + channel);
        if (mIncomingVideoListener != null) {
            mIncomingVideoListener.onIncomingVideoStopped(channel, getActiveChannels());
        }
    }

    private List<Channel> getActiveChannels() {
        List<Channel> list = new ArrayList<>();
        for (Map.Entry<String, IncomingVideoSession> entry : mActiveSessions.entrySet()) {
            if (entry.getValue().isActive()) list.add(entry.getValue().getChannel());
        }
        return list;
    }

    private Runnable newTimeoutRunner(Channel channel) {
        return () -> {
            Log.d(TAG, "Video inactivity timeout for channel: " + channel);
            stopSession(channel);
        };
    }
}
