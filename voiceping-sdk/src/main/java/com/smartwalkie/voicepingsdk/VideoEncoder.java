package com.smartwalkie.voicepingsdk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.smartwalkie.voicepingsdk.model.VideoParam;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Captures video from Camera2 and encodes it with MediaCodec H.264.
 * Requires API 21+.
 */
class VideoEncoder {

    private static final String TAG = "VideoEncoder";
    private static final long DEQUEUE_TIMEOUT_US = 10_000; // 10 ms

    interface FrameCallback {
        void onFrameEncoded(byte[] data, boolean isKeyFrame);
        void onError(Exception e);
    }

    private final Context mContext;
    private final VideoParam mParam;

    private MediaCodec mEncoder;
    private Surface mEncoderSurface;
    private byte[] mSpsBuffer; // stored SPS+PPS from BUFFER_FLAG_CODEC_CONFIG

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private Handler mCameraHandler;
    private HandlerThread mCameraThread;

    private Thread mOutputThread;
    private volatile boolean mRunning;
    private FrameCallback mCallback;

    VideoEncoder(Context context, VideoParam param) {
        mContext = context;
        mParam = param;
    }

    @SuppressLint("MissingPermission")
    void start(FrameCallback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            callback.onError(new UnsupportedOperationException("Video PTT requires API 21+"));
            return;
        }
        mCallback = callback;
        mRunning = true;

        mCameraThread = new HandlerThread("VideoEncoder-Camera");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());

        try {
            // Configure MediaCodec H.264 encoder with Surface input
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, mParam.getWidth(), mParam.getHeight());
            format.setInteger(MediaFormat.KEY_BIT_RATE, mParam.getBitrate());
            format.setInteger(MediaFormat.KEY_FRAME_RATE, mParam.getFrameRate());
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, mParam.getIFrameInterval());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoderSurface = mEncoder.createInputSurface();
            mEncoder.start();

            // Thread to drain the encoder output buffers
            mOutputThread = new Thread(this::drainOutput, "VideoEncoder-Output");
            mOutputThread.start();

            // Open Camera2 — frames flow directly into the encoder surface
            openCamera();
        } catch (IOException e) {
            mCallback.onError(e);
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        try {
            CameraManager manager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = findCameraId(manager);
            if (cameraId == null) {
                if (mCallback != null)
                    mCallback.onError(new RuntimeException("No camera available"));
                return;
            }
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    mCameraDevice = camera;
                    startCaptureSession();
                }
                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                }
                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    if (mCallback != null)
                        mCallback.onError(new RuntimeException("Camera error: " + error));
                }
            }, mCameraHandler);
        } catch (CameraAccessException e) {
            if (mCallback != null) mCallback.onError(e);
        }
    }

    private String findCameraId(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics chars = manager.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == mParam.getCameraFacing()) return id;
        }
        // fallback to any available camera
        String[] ids = manager.getCameraIdList();
        return ids.length > 0 ? ids[0] : null;
    }

    private void startCaptureSession() {
        try {
            mCameraDevice.createCaptureSession(
                    Collections.singletonList(mEncoderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            mCaptureSession = session;
                            try {
                                CaptureRequest.Builder builder =
                                        mCameraDevice.createCaptureRequest(
                                                CameraDevice.TEMPLATE_RECORD);
                                builder.addTarget(mEncoderSurface);
                                session.setRepeatingRequest(
                                        builder.build(), null, mCameraHandler);
                            } catch (CameraAccessException e) {
                                if (mCallback != null) mCallback.onError(e);
                            }
                        }
                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            if (mCallback != null)
                                mCallback.onError(new RuntimeException(
                                        "Capture session configuration failed"));
                        }
                    }, mCameraHandler);
        } catch (CameraAccessException e) {
            if (mCallback != null) mCallback.onError(e);
        }
    }

    private void drainOutput() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (mRunning) {
            try {
                int index = mEncoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // format change — nothing needed for surface-input encoders
                } else if (index >= 0) {
                    ByteBuffer buf = mEncoder.getOutputBuffer(index);
                    if (buf != null && info.size > 0) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            // SPS/PPS — cache for prepending to future keyframes
                            mSpsBuffer = new byte[info.size];
                            buf.position(info.offset);
                            buf.get(mSpsBuffer);
                        } else {
                            boolean isKeyFrame =
                                    (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            byte[] frameData = new byte[info.size];
                            buf.position(info.offset);
                            buf.get(frameData);

                            if (isKeyFrame && mSpsBuffer != null) {
                                // Prepend SPS+PPS to keyframe so the receiver can
                                // initialize or resync its decoder mid-stream.
                                byte[] combined =
                                        new byte[mSpsBuffer.length + frameData.length];
                                System.arraycopy(mSpsBuffer, 0, combined,
                                        0, mSpsBuffer.length);
                                System.arraycopy(frameData, 0, combined,
                                        mSpsBuffer.length, frameData.length);
                                frameData = combined;
                            }

                            if (mCallback != null)
                                mCallback.onFrameEncoded(frameData, isKeyFrame);
                        }
                    }
                    mEncoder.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            } catch (Exception e) {
                if (mRunning) Log.e(TAG, "Encoder output error", e);
                break;
            }
        }
    }

    void stop() {
        mRunning = false;

        // Stop camera capture
        try {
            if (mCaptureSession != null) {
                mCaptureSession.stopRepeating();
                mCaptureSession.close();
                mCaptureSession = null;
            }
        } catch (Exception ignored) {}

        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }

        if (mCameraThread != null) {
            mCameraThread.quitSafely();
            mCameraThread = null;
        }

        // Signal EOS and wait for output thread
        try {
            if (mEncoder != null) {
                mEncoder.signalEndOfInputStream();
            }
            if (mOutputThread != null) {
                mOutputThread.join(1000);
                mOutputThread = null;
            }
        } catch (Exception ignored) {}

        // Release encoder
        try {
            if (mEncoder != null) {
                mEncoder.stop();
                mEncoder.release();
                mEncoder = null;
            }
        } catch (Exception ignored) {}

        if (mEncoderSurface != null) {
            mEncoderSurface.release();
            mEncoderSurface = null;
        }

        mSpsBuffer = null;
        mCallback = null;
    }
}
