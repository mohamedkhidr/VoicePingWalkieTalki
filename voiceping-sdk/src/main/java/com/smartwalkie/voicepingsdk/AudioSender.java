package com.smartwalkie.voicepingsdk;



import com.smartwalkie.voicepingsdk.model.AudioParam;
import com.smartwalkie.voicepingsdk.model.Message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class AudioSender {

    private final Connection mConnection;
    private final AudioParam mAudioParam;
    private final String mUserId;
    private final String mReceiverId;
    private final int mChannelType;

    private int mCounter = 0;
    private final ByteArrayOutputStream mOutputStream = new ByteArrayOutputStream();

        private volatile boolean mFailed = false;
        private SendFailureListener mListener;




    public AudioSender(Connection connection, AudioParam audioParam,
                       String userId, String receiverId, int channelType) {
        mConnection = connection;
        mAudioParam = audioParam;
        mUserId = userId;
        mReceiverId = receiverId;
        mChannelType = channelType;
    }

    public void setFailureListener(SendFailureListener l) { mListener = l; }

    public void send(byte[] encodedData) {
        if (mFailed) return;  // stop accumulating after a failure
        try {
            mOutputStream.write(encodedData);
            mCounter++;
            if (mCounter >= mAudioParam.getFramePerSent()) {
                flush();
            }
        } catch (IOException e) {
            mFailed = true;
            if (mListener != null) mListener.onSendFailed(e);
        }
    }

    public void flush() {
        byte[] accumulated = mOutputStream.toByteArray();
        if (accumulated.length == 0) return;
        Message message = MessageHelper.createAudioMessage(
                mUserId, mReceiverId, mChannelType,
                accumulated, accumulated.length);
        if (message == null) { reset(); return; }

        try {
            mConnection.send(message.getPayload());
        } catch (Exception e) {
            mFailed = true;
            if (mListener != null) mListener.onSendFailed(e);
        } finally {
            reset();
        }
    }




    private void reset() {
        mOutputStream.reset();
        mCounter = 0;
    }
}
