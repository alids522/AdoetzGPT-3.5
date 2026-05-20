package app.adoetzgpt.threefive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class MicrophoneForegroundService extends Service {

    private static final String CHANNEL_ID = "adoetzgpt_mic_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START = "app.adoetzgpt.threefive.START_MIC_SERVICE";
    public static final String ACTION_STOP = "app.adoetzgpt.threefive.STOP_MIC_SERVICE";

    private AudioRecord audioRecord = null;
    private Thread recordingThread = null;
    private boolean isRecording = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopRecording();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        startRecording();

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }

    private synchronized void startRecording() {
        if (isRecording) return;
        isRecording = true;

        recordingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

                int sampleRate = 16000;
                int channelConfig = AudioFormat.CHANNEL_IN_MONO;
                int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
                int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);

                if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    minBufferSize = sampleRate * 2;
                }

                int bufferSize = Math.max(minBufferSize, 3200); // minimum 1600 samples (100ms)

                try {
                    audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    );

                    if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        audioRecord = new AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            sampleRate,
                            channelConfig,
                            audioFormat,
                            bufferSize
                        );
                    }

                    if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        isRecording = false;
                        return;
                    }

                    audioRecord.startRecording();
                } catch (SecurityException e) {
                    isRecording = false;
                    return;
                } catch (Exception e) {
                    isRecording = false;
                    return;
                }

                short[] buffer = new short[1600]; // 100ms frames at 16000Hz
                while (isRecording) {
                    int readSize = audioRecord.read(buffer, 0, buffer.length);
                    if (readSize > 0) {
                        // Calculate RMS value
                        double sum = 0;
                        for (int i = 0; i < readSize; i++) {
                            sum += buffer[i] * buffer[i];
                        }
                        double rms = Math.sqrt(sum / readSize) / 32768.0;

                        // Convert short buffer to byte array
                        byte[] byteBuffer = new byte[readSize * 2];
                        for (int i = 0; i < readSize; i++) {
                            byteBuffer[i * 2] = (byte) (buffer[i] & 0x00FF);
                            byteBuffer[i * 2 + 1] = (byte) ((buffer[i] & 0xFF00) >> 8);
                        }

                        String base64Data = Base64.encodeToString(byteBuffer, 0, readSize * 2, Base64.NO_WRAP);
                        MicrophoneServicePlugin.sendAudioChunk(base64Data, rms);
                    }
                }

                try {
                    if (audioRecord != null) {
                        if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            audioRecord.stop();
                        }
                        audioRecord.release();
                        audioRecord = null;
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }, "AdoetzGPT-MicRecorder");

        recordingThread.start();
    }

    private synchronized void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        if (recordingThread != null) {
            try {
                recordingThread.join(1000);
            } catch (InterruptedException e) {
                // ignore
            }
            recordingThread = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Microphone Active",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setDescription("Notification shown when microphone is active for live conversation");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Action to stop the microphone service
        Intent stopIntent = new Intent(this, MicrophoneForegroundService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Live Conversation Active")
            .setContentText("Microphone is active for conversation mode")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(null)
            .setVibrate(null)
            .addAction(android.R.drawable.ic_media_pause, "Stop Recording", stopPendingIntent)
            .build();
    }
}

