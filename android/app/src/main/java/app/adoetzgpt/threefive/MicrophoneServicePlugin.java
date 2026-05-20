package app.adoetzgpt.threefive;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
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

    public static void sendAudioChunk(String base64Data, double rms) {
        if (instance != null) {
            JSObject js = new JSObject();
            js.put("data", base64Data);
            js.put("rms", rms);
            instance.notifyListeners("audioChunk", js);
        }
    }

    @PluginMethod
    public void startForegroundService(PluginCall call) {
        // Check microphone permission
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            call.reject("Microphone permission not granted");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                call.reject("Notification permission not granted");
                return;
            }
        }

        Intent serviceIntent = new Intent(getContext(), MicrophoneForegroundService.class);
        serviceIntent.setAction(MicrophoneForegroundService.ACTION_START);

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
    public void isRunning(PluginCall call) {
        JSObject result = new JSObject();
        result.put("running", false);
        call.resolve(result);
    }
}
