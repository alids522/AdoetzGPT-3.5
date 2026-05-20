package app.adoetzgpt.threefive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Foreground service that manages:
 * 1. Native microphone recording (16kHz PCM)
 * 2. Native WebSocket connection to Gemini Live API
 * 3. Native audio playback of AI responses (24kHz PCM)
 *
 * All three run independently of the WebView lifecycle,
 * enabling true background conversation.
 */
public class MicrophoneForegroundService extends Service
        implements GeminiLiveNativeSession.Listener {

    private static final String TAG = "MicForegroundService";
    private static final String CHANNEL_ID = "adoetzgpt_mic_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START = "app.adoetzgpt.threefive.START_MIC_SERVICE";
    public static final String ACTION_STOP = "app.adoetzgpt.threefive.STOP_MIC_SERVICE";

    // Intent extras for Gemini Live config
    public static final String EXTRA_API_KEY = "apiKey";
    public static final String EXTRA_MODEL = "model";
    public static final String EXTRA_VOICE = "voice";
    public static final String EXTRA_SYSTEM_PROMPT = "systemPrompt";

    // Recording
    private AudioRecord audioRecord = null;
    private Thread recordingThread = null;
    private volatile boolean isRecording = false;

    // Gemini Live WebSocket
    private GeminiLiveNativeSession geminiSession = null;
    private String apiKey = "";
    private String model = "";
    private String voice = "";
    private String systemPrompt = "";

    // Audio playback
    private AudioTrack audioTrack = null;
    private Thread playbackThread = null;
    private final LinkedBlockingQueue<byte[]> playbackQueue = new LinkedBlockingQueue<>();
    private volatile boolean isPlayingBack = false;
    private volatile boolean isPlaybackRunning = false;

    // Reconnection
    private Handler mainHandler = null;
    private volatile boolean shouldReconnect = true;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_DELAY_MS = 30000;

    // Notification
    private NotificationManager notificationManager = null;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        mainHandler = new Handler(Looper.getMainLooper());
        notificationManager = getSystemService(NotificationManager.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                shouldReconnect = false;
                stopRecording();
                stopPlayback();
                closeGeminiSession();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }

            // Extract config from intent
            if (intent.hasExtra(EXTRA_API_KEY)) {
                apiKey = intent.getStringExtra(EXTRA_API_KEY);
            }
            if (intent.hasExtra(EXTRA_MODEL)) {
                model = intent.getStringExtra(EXTRA_MODEL);
            }
            if (intent.hasExtra(EXTRA_VOICE)) {
                voice = intent.getStringExtra(EXTRA_VOICE);
            }
            if (intent.hasExtra(EXTRA_SYSTEM_PROMPT)) {
                systemPrompt = intent.getStringExtra(EXTRA_SYSTEM_PROMPT);
            }
        }

        // Start as foreground service
        Notification notification = buildNotification("Connecting...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        // Start all subsystems
        shouldReconnect = true;
        reconnectAttempts = 0;
        startRecording();
        startPlayback();
        connectGeminiSession();

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        shouldReconnect = false;
        stopRecording();
        stopPlayback();
        closeGeminiSession();
        super.onDestroy();
    }

    // ========================================================================
    // MICROPHONE RECORDING
    // ========================================================================

    private synchronized void startRecording() {
        if (isRecording) return;
        isRecording = true;

        recordingThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            int sampleRate = 16000;
            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);

            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                minBufferSize = sampleRate * 2;
            }

            int bufferSize = Math.max(minBufferSize, 3200);

            try {
                audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate, channelConfig, audioFormat, bufferSize
                );

                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate, channelConfig, audioFormat, bufferSize
                    );
                }

                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize");
                    isRecording = false;
                    return;
                }

                audioRecord.startRecording();
            } catch (SecurityException | Exception e) {
                Log.e(TAG, "Failed to start recording", e);
                isRecording = false;
                return;
            }

            short[] buffer = new short[1600]; // 100ms frames at 16kHz
            while (isRecording) {
                int readSize = audioRecord.read(buffer, 0, buffer.length);
                if (readSize > 0) {
                    // Calculate RMS for visualizer
                    double sum = 0;
                    for (int i = 0; i < readSize; i++) {
                        sum += buffer[i] * buffer[i];
                    }
                    double rms = Math.sqrt(sum / readSize) / 32768.0;

                    // Convert to byte array and base64
                    byte[] byteBuffer = new byte[readSize * 2];
                    for (int i = 0; i < readSize; i++) {
                        byteBuffer[i * 2] = (byte) (buffer[i] & 0x00FF);
                        byteBuffer[i * 2 + 1] = (byte) ((buffer[i] & 0xFF00) >> 8);
                    }
                    String base64Data = Base64.encodeToString(byteBuffer, 0, readSize * 2, Base64.NO_WRAP);

                    // Send audio directly to native Gemini WebSocket
                    if (geminiSession != null && geminiSession.isConnected()) {
                        geminiSession.sendAudio(base64Data);
                    }

                    // Also send RMS to Svelte for visualizer
                    MicrophoneServicePlugin.sendAudioRms(rms);
                }
            }

            // Cleanup AudioRecord
            try {
                if (audioRecord != null) {
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();
                    }
                    audioRecord.release();
                    audioRecord = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioRecord", e);
            }
        }, "AdoetzGPT-MicRecorder");

        recordingThread.start();
    }

    private synchronized void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        if (recordingThread != null) {
            try {
                recordingThread.join(2000);
            } catch (InterruptedException e) {
                // ignore
            }
            recordingThread = null;
        }
    }

    // ========================================================================
    // GEMINI LIVE WEBSOCKET
    // ========================================================================

    private void connectGeminiSession() {
        if (apiKey == null || apiKey.isEmpty()) {
            Log.e(TAG, "Cannot connect: no API key");
            MicrophoneServicePlugin.sendConnectionStatus("error", "No API key configured");
            return;
        }

        closeGeminiSession();

        geminiSession = new GeminiLiveNativeSession(apiKey, model, voice, systemPrompt, this);
        geminiSession.connect();
        updateNotification("Connecting...");
        MicrophoneServicePlugin.sendConnectionStatus("connecting", null);
    }

    private void closeGeminiSession() {
        if (geminiSession != null) {
            geminiSession.close();
            geminiSession = null;
        }
    }

    private void scheduleReconnect() {
        if (!shouldReconnect) return;

        reconnectAttempts++;
        int delay = Math.min(2000 * reconnectAttempts, MAX_RECONNECT_DELAY_MS);
        Log.d(TAG, "Scheduling reconnect in " + delay + "ms (attempt " + reconnectAttempts + ")");

        updateNotification("Reconnecting...");
        MicrophoneServicePlugin.sendConnectionStatus("reconnecting", null);

        mainHandler.postDelayed(() -> {
            if (shouldReconnect && isRecording) {
                connectGeminiSession();
            }
        }, delay);
    }

    // ========================================================================
    // AUDIO PLAYBACK (24kHz PCM from Gemini)
    // ========================================================================

    private void startPlayback() {
        if (isPlaybackRunning) return;
        isPlaybackRunning = true;

        int sampleRate = 24000;
        int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        int bufferSize = Math.max(minBufferSize, 4800); // At least 100ms buffer

        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

        AudioFormat format = new AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(audioFormat)
            .build();

        audioTrack = new AudioTrack(attrs, format, bufferSize, AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);

        playbackThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            audioTrack.play();

            while (isPlaybackRunning) {
                try {
                    byte[] data = playbackQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (data != null && isPlaybackRunning) {
                        isPlayingBack = true;
                        audioTrack.write(data, 0, data.length);
                        // Check if queue is empty after write
                        if (playbackQueue.isEmpty()) {
                            isPlayingBack = false;
                        }
                    } else {
                        isPlayingBack = false;
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }

            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception e) {
                // ignore
            }
        }, "AdoetzGPT-AudioPlayback");

        playbackThread.start();
    }

    private void stopPlayback() {
        isPlaybackRunning = false;
        isPlayingBack = false;
        playbackQueue.clear();
        if (playbackThread != null) {
            playbackThread.interrupt();
            try {
                playbackThread.join(2000);
            } catch (InterruptedException e) {
                // ignore
            }
            playbackThread = null;
        }
    }

    public void clearPlaybackQueue() {
        playbackQueue.clear();
        isPlayingBack = false;
        if (audioTrack != null) {
            try {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.play();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    // ========================================================================
    // GeminiLiveNativeSession.Listener CALLBACKS
    // ========================================================================

    @Override
    public void onConnected() {
        Log.d(TAG, "Gemini Live connected");
    }

    @Override
    public void onSetupComplete() {
        Log.d(TAG, "Gemini Live setup complete");
        reconnectAttempts = 0;
        updateNotification("Listening...");
        MicrophoneServicePlugin.sendConnectionStatus("connected", null);
    }

    @Override
    public void onAudioResponse(byte[] pcmData) {
        if (isPlaybackRunning) {
            playbackQueue.offer(pcmData);
            if (!isPlayingBack) {
                updateNotification("AI Speaking...");
            }
        }
    }

    @Override
    public void onInputTranscription(String text, boolean finished) {
        MicrophoneServicePlugin.sendTranscriptEvent("input_transcription", text, finished);
        if (finished) {
            updateNotification("Listening...");
        }
    }

    @Override
    public void onOutputTranscription(String text, boolean finished) {
        MicrophoneServicePlugin.sendTranscriptEvent("output_transcription", text, finished);
        if (finished) {
            updateNotification("Listening...");
        }
    }

    @Override
    public void onTurnComplete() {
        MicrophoneServicePlugin.sendTranscriptEvent("turn_complete", null, true);
        updateNotification("Listening...");
    }

    @Override
    public void onInterrupted() {
        clearPlaybackQueue();
        MicrophoneServicePlugin.sendTranscriptEvent("interrupted", null, true);
        updateNotification("Listening...");
    }

    @Override
    public void onError(String message) {
        Log.e(TAG, "Gemini Live error: " + message);
        MicrophoneServicePlugin.sendConnectionStatus("error", message);
    }

    @Override
    public void onDisconnected(int code, String reason) {
        Log.d(TAG, "Gemini Live disconnected: " + code + " " + reason);
        scheduleReconnect();
    }

    // ========================================================================
    // NOTIFICATION
    // ========================================================================

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

    private Notification buildNotification(String statusText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, MicrophoneForegroundService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gemini Live Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(null)
            .setVibrate(null)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build();
    }

    private void updateNotification(String statusText) {
        if (notificationManager != null) {
            try {
                notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText));
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
