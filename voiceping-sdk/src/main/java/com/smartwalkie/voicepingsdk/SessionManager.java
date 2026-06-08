package com.smartwalkie.voicepingsdk;





import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.smartwalkie.voicepingsdk.exception.ErrorCode;
import com.smartwalkie.voicepingsdk.exception.VoicePingException;
import com.smartwalkie.voicepingsdk.listener.AudioInterceptor;
import com.smartwalkie.voicepingsdk.listener.OutgoingAudioListener;
import com.smartwalkie.voicepingsdk.listener.OutgoingTalkCallback;
import com.smartwalkie.voicepingsdk.listener.OutgoingVideoCallback;
import com.smartwalkie.voicepingsdk.model.AudioParam;
import com.smartwalkie.voicepingsdk.model.Channel;
import com.smartwalkie.voicepingsdk.model.Message;
import com.smartwalkie.voicepingsdk.model.MessageType;
import com.smartwalkie.voicepingsdk.model.VideoParam;
import com.smartwalkie.voicepingsdk.model.WavMetadata;

import java.io.File;

public class SessionManager implements OutgoingAudioListener{

    private final String TAG = SessionManager.class.getSimpleName();

    private final Context mContext;
    private final Connection mConnection;
    private AudioParam mAudioParam;
    private final Handler mBackgroundHandler;

    private String mUserId;
    private String mReceiverId;
    private int mChannelType;
    private Channel mChannel;

    private OutgoingTalkCallback mOutgoingTalkCallback;
    private volatile boolean mIsRecording;
    private volatile long mStartRecordingTime;
    private long mLastRecordDuration;
    private int mLastMessageType;

    // The three focused collaborators
    private Recorder mRecorder;

    private WavPlayer mWavPlayer;
    private AudioEncoder mAudioEncoder;
    private AudioSender mAudioSender;
    private AudioLocalSaver mAudioLocalSaver;

    // Video PTT fields
    private VideoEncoder mVideoEncoder;
    private VideoSender mVideoSender;
    private VideoParam mVideoParam;
    private OutgoingVideoCallback mOutgoingVideoCallback;
    private volatile long mVideoStartTime;
    private volatile boolean mIsVideoSession;
    private Runnable mStartVideoTalkingRunner;

    private static final int ACK_TIMEOUT_IN_MILLIS = 10 * 1000;



    private final Runnable mStartTalkingRunner = new Runnable() {
        @Override
        public void run() {
            if (mOutgoingTalkCallback != null) {
                mOutgoingTalkCallback.onOutgoingTalkStarted(mRecorder.getAudioSessionId());
            }
            sendAckStart();
            startRecording();
        }
    };

    private final Runnable mStartWavRunner = new Runnable() {
        @Override
        public void run() {
            if (mOutgoingTalkCallback != null) {
                mOutgoingTalkCallback.onOutgoingTalkStarted(0); // no audio session id for WAV
            }
            sendAckStart();
            mStartRecordingTime = System.currentTimeMillis();
            mWavPlayer.start();
        }
    };

    private final Runnable mAckStartTimeoutCheckRunner = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "ACK_START Timeout!");
            if (mIsVideoSession) {
                stopVideoEncoder();
                if (mOutgoingVideoCallback != null) {
                    mOutgoingVideoCallback.onOutgoingVideoError(new VoicePingException(
                            "ACK_START Timeout. Failed to initiate Video PTT!",
                            ErrorCode.ACK_START_TIMEOUT));
                    mOutgoingVideoCallback = null;
                }
            } else {
                stopRecording();
                if (mOutgoingTalkCallback != null) {
                    mOutgoingTalkCallback.onOutgoingTalkError(new VoicePingException(
                            "ACK_START Timeout. Failed to initiate PTT Talk!",
                            ErrorCode.ACK_START_TIMEOUT));
                    mOutgoingTalkCallback = null;
                }
            }
        }
    };

    private final Runnable mAckEndTimeoutCheckRunner = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "ACK_END Timeout!");
            if (mIsRecording) return;
            if (mIsVideoSession) {
                if (mOutgoingVideoCallback != null) {
                    mOutgoingVideoCallback.onOutgoingVideoError(new VoicePingException(
                            "ACK_END Timeout.",
                            ErrorCode.ACK_END_TIMEOUT));
                    mOutgoingVideoCallback = null;
                }
            } else {
                stopRecording();
                if (mOutgoingTalkCallback != null) {
                    mOutgoingTalkCallback.onOutgoingTalkError(new VoicePingException(
                            "ACK_END Timeout. Failed to get download url!",
                            ErrorCode.ACK_END_TIMEOUT));
                    mOutgoingTalkCallback = null;
                }
            }
        }
    };

    // ── Constructor ───────────────────────────────────────────────────────────

    public SessionManager(Context context, Connection connection,
                          AudioParam audioParam, Looper backgroundLooper) {
        mContext = context;
        mConnection = connection;
        mAudioParam = audioParam;
        mBackgroundHandler = new Handler(backgroundLooper);
    }

    public void setAudioParam(AudioParam audioParam) {
        mAudioParam = audioParam;
    }

    public void setUserId(String userId) {
        mUserId = userId;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void startTalking(String receiverId, int channelType,
                             OutgoingTalkCallback callback,
                             String destinationPath,
                             CustomAudioRecorder customRecorder) {

        if (!NetworkUtil.isNetworkConnected(mContext)) {
            callback.onOutgoingTalkError(new VoicePingException(
                    "Please check your internet connection!",
                    ErrorCode.INTERNET_DISCONNECTED));
            return;
        }

        if (mConnection.getConnectionState() == ConnectionState.DISCONNECTED) {
            callback.onOutgoingTalkError(new VoicePingException(
                    "You are disconnected!", ErrorCode.SOCKET_DISCONNECTED));
            return;
        }

        mReceiverId = receiverId;
        mChannelType = channelType;
        mChannel = new Channel(mChannelType, mUserId, mReceiverId);
        mOutgoingTalkCallback = callback;
        mIsRecording = true;

        if (destinationPath != null && !destinationPath.isEmpty()) {
            mAudioLocalSaver = new AudioLocalSaver(destinationPath);
        }

        // Build collaborators for this session
        mAudioEncoder = new AudioEncoder(mAudioParam);
        mAudioSender  = new AudioSender(
                mConnection, mAudioParam, mUserId, mReceiverId, mChannelType);
        mRecorder     = new Recorder(mContext, mAudioParam, new Recorder.FrameCallback() {
            @Override
            public void onFrameCaptured(byte[] pcmData) {
                // Check max duration
                long duration = System.currentTimeMillis() - mStartRecordingTime;
                if (duration > mAudioParam.getMaxDuration()) {
                    mBackgroundHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            stopTalking();
                        }
                    });
                    return;
                }

                // Encode
                byte[] encoded = mAudioEncoder.encode(pcmData, mChannel);

                // Save locally if needed
                if (mAudioLocalSaver != null) mAudioLocalSaver.write(encoded);

                // Send
                mAudioSender.send(encoded);
            }

            @Override
            public void onError(Exception e) {
                if (mOutgoingTalkCallback != null) {
                    mOutgoingTalkCallback.onOutgoingTalkError(
                            new VoicePingException(e.getMessage(), ErrorCode.UNKNOWN));
                }
            }
        });

        mBackgroundHandler.removeCallbacksAndMessages(null);
        mBackgroundHandler.removeCallbacks(mAckEndTimeoutCheckRunner);

        long diffFromLastStartTalking = System.currentTimeMillis() - mStartRecordingTime;
        if (diffFromLastStartTalking < 500) {
            mBackgroundHandler.postDelayed(mStartTalkingRunner, diffFromLastStartTalking);
        } else {
            mStartTalkingRunner.run();
        }
    }
    // New public method — mirrors startTalking() exactly, just swaps source
    public void startTalkingWithWav(String receiverId, int channelType,
                                    OutgoingTalkCallback callback,
                                    File wavFile) {

        if (!NetworkUtil.isNetworkConnected(mContext)) {
            callback.onOutgoingTalkError(new VoicePingException(
                    "Please check your internet connection!",
                    ErrorCode.INTERNET_DISCONNECTED));
            return;
        }

        if (mConnection.getConnectionState() == ConnectionState.DISCONNECTED) {
            callback.onOutgoingTalkError(new VoicePingException(
                    "You are disconnected!", ErrorCode.SOCKET_DISCONNECTED));
            return;
        }

        mReceiverId = receiverId;
        mChannelType = channelType;
        mChannel = new Channel(mChannelType, mUserId, mReceiverId);
        mOutgoingTalkCallback = callback;
        mIsRecording = true;

        // Same collaborators — encoder and sender are identical to mic path
        mAudioEncoder = new AudioEncoder(mAudioParam);
        mAudioSender  = new AudioSender(
                mConnection, mAudioParam, mUserId, mReceiverId, mChannelType);

        // Only difference: source is WavPlayer instead of Recorder
        mWavPlayer = new WavPlayer(mContext, "test.wav", mAudioParam, new FrameCallback() {
            @Override
            public void onFrameCaptured(byte[] pcmData) {
                byte[] encoded = mAudioEncoder.encode(pcmData, mChannel);
                if (mAudioLocalSaver != null) mAudioLocalSaver.write(encoded);
                mAudioSender.send(encoded);
            }

            @Override
            public void onError(Exception e) {
                if (mOutgoingTalkCallback != null) {
                    mOutgoingTalkCallback.onOutgoingTalkError(
                            new VoicePingException(e.getMessage(), ErrorCode.UNKNOWN));
                }
            }

            @Override
            public void onCompleted() {
                mBackgroundHandler.post(() -> stopTalking());
            }
        }, mConnection);

        mBackgroundHandler.removeCallbacksAndMessages(null);
        mBackgroundHandler.removeCallbacks(mAckEndTimeoutCheckRunner);

        long diffFromLastStartTalking = System.currentTimeMillis() - mStartRecordingTime;
        if (diffFromLastStartTalking < 500) {
            mBackgroundHandler.postDelayed(mStartWavRunner, diffFromLastStartTalking);
        } else {
            mStartWavRunner.run();
        }
    }

    public void stopTalking() {
        Log.d(TAG, "stopTalk at: " + System.currentTimeMillis());
        mBackgroundHandler.removeCallbacks(mStartTalkingRunner);
        stopRecording();

        if (mOutgoingTalkCallback != null) {
            boolean isTooShort = mLastRecordDuration < mAudioParam.getMinDuration();
            boolean isTooLong  = mLastRecordDuration > mAudioParam.getMaxDuration();
            mOutgoingTalkCallback.onOutgoingTalkStopped(isTooShort, isTooLong);
        }

        sendAckStop();
    }

    // ── Video PTT ─────────────────────────────────────────────────────────────

    public void setVideoParam(VideoParam videoParam) {
        mVideoParam = videoParam;
    }

    public void startVideoTalking(String receiverId, int channelType,
                                   OutgoingVideoCallback callback) {
        if (!NetworkUtil.isNetworkConnected(mContext)) {
            callback.onOutgoingVideoError(new VoicePingException(
                    "Please check your internet connection!",
                    ErrorCode.INTERNET_DISCONNECTED));
            return;
        }
        if (mConnection.getConnectionState() == ConnectionState.DISCONNECTED) {
            callback.onOutgoingVideoError(new VoicePingException(
                    "You are disconnected!", ErrorCode.SOCKET_DISCONNECTED));
            return;
        }

        mReceiverId = receiverId;
        mChannelType = channelType;
        mOutgoingVideoCallback = callback;
        mIsVideoSession = true;
        Log.i("onMessageReceived", mIsVideoSession+"");
        mIsRecording = true;

        VideoParam param = mVideoParam != null ? mVideoParam : new VideoParam.Builder().build();
        mVideoSender = new VideoSender(mConnection, mUserId, receiverId, channelType);
        mVideoEncoder = new VideoEncoder(mContext, param);

        mBackgroundHandler.removeCallbacksAndMessages(null);

        long diffFromLast = System.currentTimeMillis() - mVideoStartTime;
        mStartVideoTalkingRunner = () -> {
            sendAckStart();
            mVideoStartTime = System.currentTimeMillis();
            mVideoEncoder.start(new VideoEncoder.FrameCallback() {
                @Override
                public void onFrameEncoded(byte[] data, boolean isKeyFrame) {
                    long duration = System.currentTimeMillis() - mVideoStartTime;
                    if (duration > param.getMaxDuration()) {
                        mBackgroundHandler.post(() -> stopVideoTalking());
                        return;
                    }
                    mVideoSender.send(data, isKeyFrame);
                }

                @Override
                public void onError(Exception e) {
                    if (mOutgoingVideoCallback != null) {
                        mOutgoingVideoCallback.onOutgoingVideoError(
                                new VoicePingException(e.getMessage(), ErrorCode.UNKNOWN));
                    }
                }
            });
            if (mOutgoingVideoCallback != null) mOutgoingVideoCallback.onOutgoingVideoStarted();
        };

        if (diffFromLast < 500) {
            mBackgroundHandler.postDelayed(mStartVideoTalkingRunner, diffFromLast);
        } else {
            mStartVideoTalkingRunner.run();
        }
    }

    public void stopVideoTalking() {
        Log.d(TAG, "stopVideoTalking at: " + System.currentTimeMillis());
        mBackgroundHandler.removeCallbacks(mStartVideoTalkingRunner);
        stopVideoEncoder();

        if (mOutgoingVideoCallback != null) {
            long duration = System.currentTimeMillis() - mVideoStartTime;
            int minDur = mVideoParam != null ? mVideoParam.getMinDuration() : 300;
            int maxDur = mVideoParam != null ? mVideoParam.getMaxDuration() : 60_000;
            mOutgoingVideoCallback.onOutgoingVideoStopped(
                    duration < minDur, duration > maxDur);
        }

        sendAckStop();
    }

    private void stopVideoEncoder() {
        mIsRecording = false;
        mIsVideoSession = false;
        Log.i("onMessageReceived", mIsVideoSession+"");
        if (mVideoEncoder != null) {
            mVideoEncoder.stop();
            mVideoEncoder = null;
        }
    }

    // ── Video session API (app manages camera+encoder, SDK handles signaling) ─

    /** Start a video PTT session. SDK sends START_TALKING and waits for ACK. */
    public void startVideoSession(String receiverId, int channelType,
                                  OutgoingVideoCallback callback) {
        if (!NetworkUtil.isNetworkConnected(mContext)) {
            if (callback != null) callback.onOutgoingVideoError(new VoicePingException(
                    "Please check your internet connection!", ErrorCode.INTERNET_DISCONNECTED));
            return;
        }
        if (mConnection.getConnectionState() == ConnectionState.DISCONNECTED) {
            if (callback != null) callback.onOutgoingVideoError(new VoicePingException(
                    "You are disconnected!", ErrorCode.SOCKET_DISCONNECTED));
            return;
        }

        mReceiverId = receiverId;
        mChannelType = channelType;
        mOutgoingVideoCallback = callback;
        mIsVideoSession = true;
        Log.i("onMessageReceived", mIsVideoSession+"");
        mIsRecording = true;
        mVideoSender = new VideoSender(mConnection, mUserId, receiverId, channelType);

        mBackgroundHandler.removeCallbacksAndMessages(null);
        long diff = System.currentTimeMillis() - mVideoStartTime;
        mStartVideoTalkingRunner = () -> {
            sendAckStart();
            mVideoStartTime = System.currentTimeMillis();
            if (mOutgoingVideoCallback != null) mOutgoingVideoCallback.onOutgoingVideoStarted();
        };
        if (diff < 500) {
            mBackgroundHandler.postDelayed(mStartVideoTalkingRunner, diff);
        } else {
            mStartVideoTalkingRunner.run();
        }
    }

    /** Send an encoded H.264 frame over the active video session. */
    public void sendVideoFrame(byte[] data, boolean isKeyFrame) {
        Log.i("onMessageReceived", mIsVideoSession+" ====");
        if (mVideoSender != null && mIsVideoSession) {
            mVideoSender.send(data, isKeyFrame);
        }
    }

    /** Stop the active video session. SDK sends STOP_TALKING. */
    public void stopVideoSession() {
        mBackgroundHandler.removeCallbacks(mStartVideoTalkingRunner);
        mIsRecording = false;
        mIsVideoSession = false;
        Log.i("onMessageReceived", mIsVideoSession+"");

        if (mOutgoingVideoCallback != null) {
            long duration = System.currentTimeMillis() - mVideoStartTime;
            int minDur = mVideoParam != null ? mVideoParam.getMinDuration() : 300;
            int maxDur = mVideoParam != null ? mVideoParam.getMaxDuration() : 60_000;
            mOutgoingVideoCallback.onOutgoingVideoStopped(duration < minDur, duration > maxDur);
            mOutgoingVideoCallback = null;
        }
        sendAckStop();
    }

    // Expose encoder interceptors (same API surface as before on AudioRecorder interface)
    public void setInterceptorBeforeEncoded(AudioInterceptor interceptor) {
        if (mAudioEncoder != null) mAudioEncoder.setInterceptorBeforeEncoded(interceptor);
    }

    public void setInterceptorAfterEncoded(AudioInterceptor interceptor) {
        if (mAudioEncoder != null) mAudioEncoder.setInterceptorAfterEncoded(interceptor);
    }

    public Channel getChannel() {
        return mChannel;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void startRecording() {
        if (mAudioLocalSaver != null) mAudioLocalSaver.init();
        mStartRecordingTime = System.currentTimeMillis();
        mRecorder.start(null);
    }

    // Update stopRecording() to also stop WavPlayer
    private void stopRecording() {
        mIsRecording = false;
        if (mStartRecordingTime > 0) {
            mLastRecordDuration = System.currentTimeMillis() - mStartRecordingTime;
        }
        if (mRecorder   != null) mRecorder.stop();
        if (mWavPlayer  != null) mWavPlayer.stop();   // <-- added
        if (mAudioEncoder != null) mAudioEncoder.release();
        if (mAudioSender  != null) mAudioSender.flush();
        if (mAudioLocalSaver != null) mAudioLocalSaver.close();
    }


    private void sendAckStart() {
        Message message = MessageHelper.createAckStartMessage(
                mUserId, mReceiverId, mChannelType, System.currentTimeMillis());
        mConnection.send(message.getPayload());
        mBackgroundHandler.postDelayed(mAckStartTimeoutCheckRunner, ACK_TIMEOUT_IN_MILLIS);
    }

    private void sendAckStop() {
        byte[] message = MessageHelper.createAckStopMessage(
                mUserId, mReceiverId, mChannelType);
        mConnection.send(message);
        mBackgroundHandler.postDelayed(mAckEndTimeoutCheckRunner, ACK_TIMEOUT_IN_MILLIS);
    }

    // ── OutgoingAudioListener (server ACK messages) ───────────────────────────

    @Override
    public void onMessageReceived(Message message) {
        int messageType = message.getMessageType();
        Log.d(TAG, "onMessageReceived: " + MessageType.getText(messageType));

        if (messageType == MessageType.ACK_START_FAILED
                && mLastMessageType == MessageType.UNAUTHORIZED_GROUP) {
            return;
        }
        mLastMessageType = messageType;

        switch (messageType) {
            case MessageType.ACK_START:
                mBackgroundHandler.removeCallbacks(mAckStartTimeoutCheckRunner);
                break;

            case MessageType.ACK_START_FAILED:
                mBackgroundHandler.removeCallbacks(mAckStartTimeoutCheckRunner);
                if (mIsVideoSession) {
                    stopVideoEncoder();
                    if (mOutgoingVideoCallback != null) {
                        mOutgoingVideoCallback.onOutgoingVideoError(new VoicePingException(
                                "ACK_START_FAILED. Failed to initiate Video PTT!",
                                ErrorCode.ACK_START_FAILED));
                        mOutgoingVideoCallback = null;
                    }
                } else {
                    stopRecording();
                    if (mOutgoingTalkCallback != null) {
                        mOutgoingTalkCallback.onOutgoingTalkError(new VoicePingException(
                                "ACK_START_FAILED. Failed to initiate PTT Talk!",
                                ErrorCode.ACK_START_FAILED));
                        mOutgoingTalkCallback = null;
                    }
                }
                break;

            case MessageType.ACK_END:
                mBackgroundHandler.removeCallbacks(mAckEndTimeoutCheckRunner);
                if (!mIsRecording && mIsVideoSession) {
                    // Video PTT — no recording download URL
                    mOutgoingVideoCallback = null;
                } else if (!mIsRecording && mOutgoingTalkCallback != null) {
                    String serverUrl = mConnection.getServerUrl();
                    if (serverUrl == null) break;
                    if (serverUrl.startsWith("ws")) {
                        serverUrl = serverUrl.replaceFirst("ws", "http");
                    }
                    String downloadUrl = serverUrl + "/files/audio/" + message.getAckIds();
                    if (mLastRecordDuration < mAudioParam.getMinDuration()) {
                        downloadUrl = null;
                    }
                    mLastRecordDuration = 0;
                    mOutgoingTalkCallback = null;
                }
                break;

            case MessageType.MESSAGE_DELIVERED:
                break;

            case MessageType.MESSAGE_READ:
                break;

            case MessageType.UNAUTHORIZED_GROUP:
                mBackgroundHandler.removeCallbacks(mAckStartTimeoutCheckRunner);
                if (mIsVideoSession) {
                    stopVideoEncoder();
                    if (mOutgoingVideoCallback != null) {
                        mOutgoingVideoCallback.onOutgoingVideoError(new VoicePingException(
                                "UNAUTHORIZED_GROUP. Failed to initiate Video PTT!",
                                ErrorCode.UNAUTHORIZED_GROUP));
                        mOutgoingVideoCallback = null;
                    }
                } else {
                    stopRecording();
                    if (mOutgoingTalkCallback != null) {
                        mOutgoingTalkCallback.onOutgoingTalkError(new VoicePingException(
                                "UNAUTHORIZED_GROUP. Failed to initiate PTT Talk!",
                                ErrorCode.UNAUTHORIZED_GROUP));
                        mOutgoingTalkCallback = null;
                    }
                }
                break;
        }
    }

    @Override
    public void onSendMessageFailed(byte[] data, VoicePingException e) {
        Message message = MessageHelper.unpackMessage(data);
        if (message != null && message.getMessageType() == MessageType.ACK_START) {
            if (mOutgoingTalkCallback != null) {
                mOutgoingTalkCallback.onOutgoingTalkError(e);
                mOutgoingTalkCallback = null;
            }
        }
    }

    @Override
    public void onConnectionFailure(VoicePingException e) {
        if (mOutgoingTalkCallback != null) {
            mOutgoingTalkCallback.onOutgoingTalkError(e);
            mOutgoingTalkCallback = null;
        }
    }

    private int calculateFrameSize(int sampleRate) {
        // To maintain a 60ms frame duration:
        // (sampleRate * 60) / 1000
        return (sampleRate * 60) / 1000;
    }


}
