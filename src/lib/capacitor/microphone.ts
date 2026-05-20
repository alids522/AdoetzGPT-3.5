/**
 * Microphone Service Bridge for Capacitor
 * Controls the Android foreground service for background microphone access
 * and native Gemini Live WebSocket connection.
 */

export interface GeminiLiveConfig {
	apiKey: string;
	model: string;
	voice: string;
	systemPrompt: string;
}

interface MicrophoneServicePlugin {
	startForegroundService(config?: GeminiLiveConfig): Promise<{ started: boolean }>;
	stopForegroundService(): Promise<{ stopped: boolean }>;
	isRunning(): Promise<{ running: boolean }>;
	addListener(event: string, callback: (data: any) => void): Promise<any>;
}

let _plugin: MicrophoneServicePlugin | null = null;

/**
 * Get the MicrophoneService Capacitor plugin.
 * Uses registerPlugin from @capacitor/core for reliable access in Capacitor 5+/6.
 */
export function getMicrophoneServicePlugin(): MicrophoneServicePlugin | null {
	if (_plugin) return _plugin;
	try {
		const cap = (window as any)?.Capacitor;
		if (!cap) return null;

		// Capacitor 6: use registerPlugin for custom native plugins
		if (typeof cap.registerPlugin === 'function') {
			_plugin = cap.registerPlugin('MicrophoneService');
			return _plugin;
		}

		// Fallback: direct access (older Capacitor versions)
		if (cap.Plugins?.MicrophoneService) {
			_plugin = cap.Plugins.MicrophoneService;
			return _plugin;
		}
	} catch {
		// Not running in Capacitor environment
	}
	return null;
}

/**
 * Start the native foreground service with optional Gemini Live config.
 * When config is provided, the service connects to Gemini Live natively
 * (WebSocket + mic + audio playback all in Java, independent of WebView).
 */
export async function startMicrophoneForegroundService(config?: GeminiLiveConfig): Promise<boolean> {
	const plugin = getMicrophoneServicePlugin();
	if (!plugin) return false;
	try {
		const result = await plugin.startForegroundService(config);
		return result?.started ?? false;
	} catch {
		return false;
	}
}

export async function stopMicrophoneForegroundService(): Promise<boolean> {
	const plugin = getMicrophoneServicePlugin();
	if (!plugin) return false;
	try {
		const result = await plugin.stopForegroundService();
		return result?.stopped ?? false;
	} catch {
		return false;
	}
}

export async function requestMicrophonePermission(): Promise<boolean> {
	if (typeof navigator?.mediaDevices?.getUserMedia !== 'function') return false;
	try {
		const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
		stream.getTracks().forEach((track) => track.stop());
		return true;
	} catch {
		return false;
	}
}

export function isCapacitorApp(): boolean {
	try {
		return !!(window as any)?.Capacitor?.isNativePlatform?.();
	} catch {
		return false;
	}
}
