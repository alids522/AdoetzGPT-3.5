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

interface MicrophoneServiceInterface {
	startForegroundService(config?: GeminiLiveConfig): Promise<{ started: boolean }>;
	stopForegroundService(): Promise<{ stopped: boolean }>;
	isRunning(): Promise<{ running: boolean }>;
	addListener(event: string, callback: (data: any) => void): Promise<any>;
}

let microphoneService: MicrophoneServiceInterface | null = null;

function getMicrophoneService(): MicrophoneServiceInterface | null {
	if (microphoneService) return microphoneService;
	try {
		// Capacitor plugins are available on window
		const win = window as any;
		if (win?.Capacitor?.Plugins?.MicrophoneService) {
			microphoneService = win.Capacitor.Plugins.MicrophoneService;
		}
	} catch {
		// Not running in Capacitor environment
	}
	return microphoneService;
}

/**
 * Start the native foreground service with optional Gemini Live config.
 * When config is provided, the service connects to Gemini Live natively
 * (WebSocket + mic + audio playback all in Java, independent of WebView).
 */
export async function startMicrophoneForegroundService(config?: GeminiLiveConfig): Promise<boolean> {
	const service = getMicrophoneService();
	if (!service) return false;
	try {
		const result = await service.startForegroundService(config);
		return result?.started ?? false;
	} catch {
		return false;
	}
}

export async function stopMicrophoneForegroundService(): Promise<boolean> {
	const service = getMicrophoneService();
	if (!service) return false;
	try {
		const result = await service.stopForegroundService();
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
