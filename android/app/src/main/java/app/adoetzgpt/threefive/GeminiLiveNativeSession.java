package app.adoetzgpt.threefive;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Native WebSocket client for the Gemini Multimodal Live API.
 * Runs entirely in Java, independent of the WebView lifecycle.
 */
public class GeminiLiveNativeSession {

    private static final String TAG = "GeminiLiveNative";
    private static final String WS_ENDPOINT =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

    public interface Listener {
        void onConnected();
        void onSetupComplete();
        void onAudioResponse(byte[] pcmData);
        void onInputTranscription(String text, boolean finished);
        void onOutputTranscription(String text, boolean finished);
        void onTurnComplete();
        void onInterrupted();
        void onError(String message);
        void onDisconnected(int code, String reason);
    }

    private final String apiKey;
    private final String model;
    private final String voice;
    private final String systemPrompt;
    private final String wsUrl;
    private final Listener listener;

    private OkHttpClient client;
    private WebSocket webSocket;
    private volatile boolean isConnected = false;
    private volatile boolean isSetupDone = false;
    private volatile boolean isClosed = false;

    public GeminiLiveNativeSession(String apiKey, String model, String voice,
                                    String systemPrompt, String wsUrl, Listener listener) {
        this.apiKey = apiKey;
        this.model = model;
        this.voice = voice;
        this.systemPrompt = systemPrompt;
        this.wsUrl = wsUrl;
        this.listener = listener;
    }

    public void connect() {
        if (isClosed) return;

        client = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)   // No timeout for WebSocket
            .writeTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)      // Keep-alive pings
            .build();

        Request request;
        try {
            String wsEndpoint = (wsUrl != null && !wsUrl.isEmpty()) ? wsUrl : WS_ENDPOINT;
            String url = wsEndpoint + "?key=" + apiKey;
            request = new Request.Builder()
                .url(url)
                .build();
        } catch (Exception e) {
            Log.e(TAG, "Malformed WebSocket URL: " + e.getMessage(), e);
            listener.onError("Malformed WebSocket URL: " + e.getMessage());
            listener.onDisconnected(-1, "Malformed URL");
            return;
        }

        Log.d(TAG, "Connecting to Gemini Live: " + model);

        try {
            webSocket = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket ws, Response response) {
                    Log.d(TAG, "WebSocket opened");
                    isConnected = true;
                    listener.onConnected();
                    sendSetupMessage();
                }

                @Override
                public void onMessage(WebSocket ws, String text) {
                    handleServerMessage(text);
                }

                @Override
                public void onClosing(WebSocket ws, int code, String reason) {
                    Log.d(TAG, "WebSocket closing: " + code + " " + reason);
                    ws.close(code, reason);
                }

                @Override
                public void onClosed(WebSocket ws, int code, String reason) {
                    Log.d(TAG, "WebSocket closed: " + code + " " + reason);
                    isConnected = false;
                    isSetupDone = false;
                    if (!isClosed) {
                        listener.onDisconnected(code, reason);
                    }
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response response) {
                    String responseDetails = "";
                    if (response != null) {
                        responseDetails = " (HTTP " + response.code() + " " + response.message() + ")";
                    }
                    Log.e(TAG, "WebSocket failure: " + t.getMessage() + responseDetails, t);
                    isConnected = false;
                    isSetupDone = false;
                    if (!isClosed) {
                        String message = t.getMessage() != null ? t.getMessage() : "Connection failed";
                        listener.onError(message + responseDetails);
                        listener.onDisconnected(-1, message + responseDetails);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to create WebSocket: " + e.getMessage(), e);
            isConnected = false;
            isSetupDone = false;
            if (!isClosed) {
                listener.onError("WebSocket initialization failed: " + e.getMessage());
                listener.onDisconnected(-1, "Initialization failed");
            }
        }
    }

    private void sendSetupMessage() {
        try {
            JSONObject message = new JSONObject();
            JSONObject config = new JSONObject();

            // Model
            config.put("model", model);

            // Response modalities
            JSONArray modalities = new JSONArray();
            modalities.put("AUDIO");
            config.put("responseModalities", modalities);

            // Speech config
            JSONObject speechConfig = new JSONObject();
            JSONObject voiceConfig = new JSONObject();
            JSONObject prebuiltVoiceConfig = new JSONObject();
            prebuiltVoiceConfig.put("voiceName", voice);
            voiceConfig.put("prebuiltVoiceConfig", prebuiltVoiceConfig);
            speechConfig.put("voiceConfig", voiceConfig);
            config.put("speechConfig", speechConfig);

            // Enable transcriptions. These belong directly on the Live config message.
            config.put("inputAudioTranscription", new JSONObject());
            config.put("outputAudioTranscription", new JSONObject());

            // System instruction
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                JSONObject systemInstruction = new JSONObject();
                JSONArray parts = new JSONArray();
                JSONObject textPart = new JSONObject();
                textPart.put("text", systemPrompt);
                parts.put(textPart);
                systemInstruction.put("parts", parts);
                config.put("systemInstruction", systemInstruction);
            }

            message.put("config", config);

            String msg = message.toString();
            Log.d(TAG, "Sending Live config message (" + msg.length() + " chars)");
            webSocket.send(msg);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build Live config message", e);
            listener.onError("Failed to build Live config message: " + e.getMessage());
        }
    }

    /**
     * Send a base64-encoded PCM audio chunk to Gemini Live.
     */
    public void sendAudio(String base64PcmData) {
        if (!isConnected || !isSetupDone || isClosed || webSocket == null) return;

        try {
            JSONObject msg = new JSONObject();
            JSONObject realtimeInput = new JSONObject();
            JSONObject audio = new JSONObject();
            audio.put("mimeType", "audio/pcm;rate=16000");
            audio.put("data", base64PcmData);
            realtimeInput.put("audio", audio);
            msg.put("realtimeInput", realtimeInput);

            webSocket.send(msg.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to send audio", e);
        }
    }

    private void handleServerMessage(String text) {
        try {
            JSONObject msg = new JSONObject(text);

            if (msg.has("error")) {
                String message = msg.optJSONObject("error") != null
                    ? msg.optJSONObject("error").optString("message", msg.optJSONObject("error").toString())
                    : msg.optString("error", "Unknown Gemini Live error");
                Log.e(TAG, "Server error: " + message);
                listener.onError(message);
                return;
            }

            // Setup/config complete acknowledgement
            if (msg.has("setupComplete")) {
                Log.d(TAG, "Setup complete");
                isSetupDone = true;
                listener.onSetupComplete();
                return;
            }

            JSONObject serverContent = msg.optJSONObject("serverContent");
            if (serverContent == null) return;

            // Input transcription (what the model heard from the user)
            JSONObject inputTranscription = serverContent.optJSONObject("inputTranscription");
            if (inputTranscription != null) {
                String transcriptText = inputTranscription.optString("text", "");
                boolean finished = inputTranscription.optBoolean("finished", false);
                if (!transcriptText.isEmpty()) {
                    listener.onInputTranscription(transcriptText, finished);
                }
            }

            // Output transcription (text of model's audio response)
            JSONObject outputTranscription = serverContent.optJSONObject("outputTranscription");
            if (outputTranscription != null) {
                String transcriptText = outputTranscription.optString("text", "");
                boolean finished = outputTranscription.optBoolean("finished", false);
                if (!transcriptText.isEmpty()) {
                    listener.onOutputTranscription(transcriptText, finished);
                }
            }

            // Audio response chunks
            JSONObject modelTurn = serverContent.optJSONObject("modelTurn");
            if (modelTurn != null) {
                JSONArray parts = modelTurn.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.getJSONObject(i);
                        JSONObject inlineData = part.optJSONObject("inlineData");
                        if (inlineData != null) {
                            String data = inlineData.optString("data", "");
                            if (!data.isEmpty()) {
                                byte[] pcmBytes = Base64.decode(data, Base64.NO_WRAP);
                                listener.onAudioResponse(pcmBytes);
                            }
                        }
                    }
                }
            }

            // Interrupted
            if (serverContent.optBoolean("interrupted", false)) {
                Log.d(TAG, "Server interrupted");
                listener.onInterrupted();
            }

            // Turn complete
            if (serverContent.optBoolean("turnComplete", false)) {
                Log.d(TAG, "Turn complete");
                listener.onTurnComplete();
            }

        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse server message", e);
        }
    }

    public boolean isConnected() {
        return isConnected && isSetupDone;
    }

    public void close() {
        isClosed = true;
        isConnected = false;
        isSetupDone = false;
        if (webSocket != null) {
            try {
                webSocket.close(1000, "User closed");
            } catch (Exception e) {
                // ignore
            }
            webSocket = null;
        }
        if (client != null) {
            client.dispatcher().executorService().shutdown();
            client = null;
        }
    }
}
