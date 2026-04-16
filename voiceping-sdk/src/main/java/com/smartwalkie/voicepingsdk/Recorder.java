package com.smartwalkie.voicepingsdk;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecord;
import com.smartwalkie.voicepingsdk.model.AudioParam;


public class Recorder {

    private final String TAG = Recorder.class.getSimpleName();

    private Context mContext;
    private AudioParam mAudioParam;
    private AudioManager mAudioManager;
    private AudioRecord mAudioRecord;
    private RecorderThread mRecorderThread;
    private volatile boolean mIsRecording;
    private FrameCallback mFrameCallback;

    public interface FrameCallback {
        void onFrameCaptured(byte[] pcmData);
        void onError(Exception e);
    }

    public Recorder(Context context, AudioParam audioParam, FrameCallback frameCallback) {
        mContext = context;
        mAudioParam = audioParam;
        mFrameCallback = frameCallback;
    }

    public void setAudioParam(AudioParam audioParam) {
        mAudioParam = audioParam;
    }

    public int getAudioSessionId() {
        if (mAudioRecord != null) return mAudioRecord.getAudioSessionId();
        return 0;
    }

    public void start(CustomAudioRecorder customAudioRecorder) {
        mIsRecording = true;
        mRecorderThread = new RecorderThread(customAudioRecorder);
        mRecorderThread.start();
    }

    public void stop() {
        mIsRecording = false;
        mRecorderThread = null;
    }

    private void initAudioRecord() {
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mAudioRecord = new AudioRecord(
                mAudioParam.getAudioSource(),
                mAudioParam.getSampleRate(),
                mAudioParam.getChannelInConfig(),
                mAudioParam.getAudioFormat(),
                mAudioParam.getRecordMinBufferSize());
    }

    private class RecorderThread extends Thread {

        private final CustomAudioRecorder mCustomAudioRecorder;

        RecorderThread(CustomAudioRecorder customAudioRecorder) {
            mCustomAudioRecorder = customAudioRecorder;
        }

        @Override
        public void run() {
            if (mCustomAudioRecorder == null) {
                try {
                    if (mAudioRecord == null ||
                            mAudioRecord.getState() == AudioRecord.STATE_UNINITIALIZED) {
                        initAudioRecord();
                    }
                    mAudioRecord.startRecording();
                } catch (IllegalStateException e) {
                    e.printStackTrace();
                    mFrameCallback.onError(e);
                    return;
                }
            }

            while (mIsRecording) {
                byte[] audioData = new byte[mAudioParam.getFrameSize() * 2
                        * mAudioParam.getChannelSize()];

                if (mCustomAudioRecorder != null) {
                    audioData = mCustomAudioRecorder.record(audioData);
                } else {
                    if (mAudioManager == null) {
                        stopAudioRecording();
                        mIsRecording = false;
                        interrupt();
                        return;
                    }
                    int numOfFrames = mAudioRecord.read(
                            audioData, 0, mAudioParam.getFrameSize() * 2);
                    if (numOfFrames == AudioRecord.ERROR_INVALID_OPERATION
                            || audioData.length == 0) {
                        stopAudioRecording();
                        mIsRecording = false;
                        interrupt();
                        return;
                    }
                    audioData = AudioBooster.boost(
                            mAudioParam.getRecordingBoostInDb(), audioData, numOfFrames);
                }

                if (audioData == null || audioData.length == 0) continue;

                mFrameCallback.onFrameCaptured(audioData);
            }

            stopAudioRecording();
        }

        private void stopAudioRecording() {
            if (mAudioRecord != null &&
                    mAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                try {
                    mAudioRecord.stop();
                    mAudioRecord.release();
                } catch (IllegalStateException e) {
                    e.printStackTrace();
                }
            }
            if (mAudioManager != null) {
                mAudioManager.stopBluetoothSco();
            }
        }
    }
}
