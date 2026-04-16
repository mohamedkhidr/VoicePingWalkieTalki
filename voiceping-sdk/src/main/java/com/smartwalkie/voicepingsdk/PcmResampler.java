package com.smartwalkie.voicepingsdk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class PcmResampler {

    public static byte[] resample441To480(byte[] input, int channels) {

        int inSamples = input.length / 2; // total samples (interleaved)
        int inFrames = inSamples / channels;

        int outFrames = (int) ((long) inFrames * 48000 / 44100);

        short[] in = new short[inSamples];
        ByteBuffer.wrap(input)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(in);

        short[] out = new short[outFrames * channels];

        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < outFrames; i++) {

                float pos = i * (inFrames - 1f) / outFrames;
                int idx = (int) pos;
                float frac = pos - idx;

                int base1 = idx * channels + ch;
                int base2 = Math.min((idx + 1), inFrames - 1) * channels + ch;

                short s1 = in[base1];
                short s2 = in[base2];

                out[i * channels + ch] = (short) (s1 + frac * (s2 - s1));
            }
        }

        ByteBuffer buffer = ByteBuffer
                .allocate(out.length * 2)
                .order(ByteOrder.LITTLE_ENDIAN);

        buffer.asShortBuffer().put(out);
        return buffer.array();
    }
}
