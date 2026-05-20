package app.adoetzgpt.threefive;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

@CapacitorPlugin(
    name = "MicrophoneService",
    permissions = {
        @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "microphone"),
        @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications"),
        @Permission(strings = { Manifest.permission.FOREGROUND_SERVICE_MICROPHONE }, alias = "foregroundMic")
    }
)
public class MicrophoneServicePlugin extends Plugin {

    private static MicrophoneServicePlugin instance = null;

    @Override
    public void load() {
        super.load();
        instance = this;
    }

    // ========================================================================
    // Events sent from native service TO Svelte
    // ========================================================================

    /**
     * Send RMS level to Svelte for visualizer animation.
     */
    public static void sendAudioRms(double rms) {
        if (instance != null) {
            JSObject js = new JSObject();
            js.put("rms", rms);
            instance.notifyListeners("audioRms", js);
        }
    }

    /**
     * Send transcript events from native Gemini session to Svelte.
     * Types: input_transcription, output_transcription, turn_complete, interrupted
     */
    public static void sendTranscriptEvent(String type, String text, boolean finished) {
        if (instance != null) {
            JSObject js = new JSObject();
            js.put("type", type);
            if (text != null) {
                js.put("text", text);
            }
            js.put("finished", finished);
            instance.notifyListeners("geminiTranscript", js);
        }
    }

    /**
     * Send connection status changes to Svelte.
     * Statuses: connecting, connected, reconnecting, disconnected, error
     */
    public static void sendConnectionStatus(String status, String message) {
        if (instance != null) {
            JSObject js = new JSObject();
            js.put("status", status);
            if (message != null) {
                js.put("message", message);
            }
            instance.notifyListeners("geminiConnectionStatus", js);
        }
    }

    /**
     * Legacy: Send raw audio chunk to Svelte (kept for backwards compatibility).
     * In native mode, audio goes directly to the Gemini WebSocket, not to Svelte.
     */
    public static void sendAudioChunk(String base64Data, double rms) {
        if (instance != null) {
            JSObject js = new JSObject();
            js.put("data", base64Data);
            js.put("rms", rms);
            instance.notifyListeners("audioChunk", js);
        }
    }

    // ========================================================================
    // Plugin methods called FROM Svelte
    // ========================================================================

    @PluginMethod
    public void startForegroundService(PluginCall call) {
        // Check microphone permission
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            call.reject("Microphone permission not granted");
            return;
        }

        Intent serviceIntent = new Intent(getContext(), MicrophoneForegroundService.class);
        serviceIntent.setAction(MicrophoneForegroundService.ACTION_START);

        // Pass Gemini Live configuration to the service
        String apiKey = call.getString("apiKey", "");
        String model = call.getString("model", "");
        String voice = call.getString("voice", "");
        String systemPrompt = call.getString("systemPrompt", "");
        String wsUrl = call.getString("wsUrl", "");
        boolean captureOnly = call.getBoolean("captureOnly", false);

        serviceIntent.putExtra(MicrophoneForegroundService.EXTRA_API_KEY, apiKey);
        serviceIntent.putExtra(MicrophoneForegroundService.EXTRA_MODEL, model);
        serviceIntent.putExtra(MicrophoneForegroundService.EXTRA_VOICE, voice);
        serviceIntent.putExtra(MicrophoneForegroundService.EXTRA_SYSTEM_PROMPT, systemPrompt);
        serviceIntent.putExtra("wsUrl", wsUrl);
        serviceIntent.putExtra(MicrophoneForegroundService.EXTRA_CAPTURE_ONLY, captureOnly);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(serviceIntent);
            } else {
                getContext().startService(serviceIntent);
            }

            JSObject result = new JSObject();
            result.put("started", true);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to start foreground service: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopForegroundService(PluginCall call) {
        Intent serviceIntent = new Intent(getContext(), MicrophoneForegroundService.class);
        serviceIntent.setAction(MicrophoneForegroundService.ACTION_STOP);

        try {
            getContext().startService(serviceIntent);
            JSObject result = new JSObject();
            result.put("stopped", true);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to stop foreground service: " + e.getMessage());
        }
    }

    @PluginMethod
    public void setMuted(PluginCall call) {
        if (!MicrophoneForegroundService.isServiceRunning()) {
            call.reject("Microphone service is not running");
            return;
        }

        boolean muted = call.getBoolean("muted", false);

        Intent serviceIntent = new Intent(getContext(), MicrophoneForegroundService.class);
        serviceIntent.setAction(MicrophoneForegroundService.ACTION_SET_MUTED);
        serviceIntent.putExtra(MicrophoneForegroundService.EXTRA_MUTED, muted);

        try {
            getContext().startService(serviceIntent);
            JSObject result = new JSObject();
            result.put("muted", muted);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to update microphone mute state: " + e.getMessage());
        }
    }

    @PluginMethod
    public void interruptPlayback(PluginCall call) {
        if (!MicrophoneForegroundService.isServiceRunning()) {
            JSObject result = new JSObject();
            result.put("interrupted", false);
            call.resolve(result);
            return;
        }

        Intent serviceIntent = new Intent(getContext(), MicrophoneForegroundService.class);
        serviceIntent.setAction(MicrophoneForegroundService.ACTION_INTERRUPT_PLAYBACK);

        try {
            getContext().startService(serviceIntent);
            JSObject result = new JSObject();
            result.put("interrupted", true);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to interrupt playback: " + e.getMessage());
        }
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        JSObject result = new JSObject();
        result.put("running", MicrophoneForegroundService.isServiceRunning());
        call.resolve(result);
    }
}
