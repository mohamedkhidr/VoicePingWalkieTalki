package com.smartwalkie.voicepingsdk;

import android.util.Log;

class VideoSender {

    private final Connection mConnection;
    private final String mUserId;
    private final String mReceiverId;
    private final int mChannelType;

    VideoSender(Connection connection, String userId, String receiverId, int channelType) {
        mConnection = connection;
        mUserId = userId;
        mReceiverId = receiverId;
        mChannelType = channelType;
    }

    /**
     * Wrap frameData in a VIDEO_FRAME msgpack envelope and send over the WebSocket.
     * Payload layout: [1-byte isKeyFrame flag (0x01 or 0x00)][H.264 encoded bytes]
     */
    void send(byte[] frameData, boolean isKeyFrame) {
        byte[] payload = new byte[1 + frameData.length];
        payload[0] = isKeyFrame ? (byte) 1 : (byte) 0;
        System.arraycopy(frameData, 0, payload, 1, frameData.length);
        byte[] message = MessageHelper.createVideoFrameMessage(
                mUserId, mReceiverId, mChannelType, payload);
        mConnection.send(message);
    }
}
