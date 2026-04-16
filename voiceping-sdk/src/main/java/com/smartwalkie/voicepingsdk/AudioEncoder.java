package com.smartwalkie.voicepingsdk;

import com.media2359.voiceping.codec.Opus;
import com.smartwalkie.voicepingsdk.listener.AudioInterceptor;
import com.smartwalkie.voicepingsdk.model.AudioParam;
import com.smartwalkie.voicepingsdk.model.Channel;

import java.util.Arrays;

public class AudioEncoder {

    private Opus mOpus;
    private AudioParam mAudioParam;
    private volatile AudioInterceptor mInterceptorBeforeEncoded;
    private volatile AudioInterceptor mInterceptorAfterEncoded;

    public AudioEncoder(AudioParam audioParam) {
        mAudioParam = audioParam;
        mOpus = new Opus(audioParam.getSampleRate(), audioParam.getChannelSize());
    }

    public void setAudioParam(AudioParam audioParam) {
        mAudioParam = audioParam;
        mOpus = new Opus(audioParam.getSampleRate(), audioParam.getChannelSize());
    }

    public void setInterceptorBeforeEncoded(AudioInterceptor interceptor) {
        mInterceptorBeforeEncoded = interceptor;
    }

    public void setInterceptorAfterEncoded(AudioInterceptor interceptor) {
        mInterceptorAfterEncoded = interceptor;
    }

    /**
     * Takes raw PCM, runs interceptors and Opus encoding, returns encoded bytes.
     * channel is passed through to interceptors for context.
     */
    public byte[] encode(byte[] pcmData, Channel channel) {
        byte[] data = pcmData;

        // Intercept before encoding (e.g. amplitude display, pitch shift)
        if (mInterceptorBeforeEncoded != null) {
            data = mInterceptorBeforeEncoded.proceed(data, channel);
        }

        // Opus encode
        if (mAudioParam.isUsingOpusCodec()) {
            byte[] encodedBytes = new byte[data.length];
            int encodedSize = mOpus.encode(
                    data, 0, mAudioParam.getFrameSize(),
                    encodedBytes, 0, encodedBytes.length);
            data = Arrays.copyOfRange(encodedBytes, 0, encodedSize);
        }

        // Intercept after encoding (e.g. packet inspection)
        if (mInterceptorAfterEncoded != null) {
            data = mInterceptorAfterEncoded.proceed(data, channel);
        }

        return data;
    }

    public void release() {
        mInterceptorBeforeEncoded = null;
        mInterceptorAfterEncoded = null;
    }
}