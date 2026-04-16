package com.smartwalkie.voicepingsdk;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class WavUtils {

    private static final int HEADER_SIZE = 44;

    private WavUtils() {}

    public static void validate(File file) throws IOException {
        if (!file.exists()) {
            throw new IOException("WAV file not found: " + file.getPath());
        }
        if (file.length() <= HEADER_SIZE) {
            throw new IOException("File too small to be a valid WAV: " + file.getName());
        }
        byte[] header = readHeader(file);
        String riff = new String(header, 0, 4);
        String wave = new String(header, 8, 4);
        if (!riff.equals("RIFF") || !wave.equals("WAVE")) {
            throw new IOException("Not a valid WAV file: " + file.getName());
        }
    }

    public static int readSampleRate(File file) throws IOException {
        return ByteBuffer.wrap(readHeader(file), 24, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
    }

    public static int readChannelCount(File file) throws IOException {
        return ByteBuffer.wrap(readHeader(file), 22, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort();
    }

    public static int readBitDepth(File file) throws IOException {
        return ByteBuffer.wrap(readHeader(file), 34, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort();
    }

    public static int readDataSize(File file) throws IOException {
        return (int) file.length() - HEADER_SIZE;
    }

    public static int getHeaderSize() {
        return HEADER_SIZE;
    }

    private static byte[] readHeader(File file) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        FileInputStream fis = new FileInputStream(file);
        try {
            fis.read(header);
        } finally {
            fis.close();
        }
        return header;
    }
}
