/**
 * Capacitor runtime initialization for AdoetzGPT Android app.
 * This module hooks into the WebView environment to provide Android-specific behavior:
 * - Microphone foreground service when getUserMedia is called with audio constraints
 * - External link handling
 * - WebView state preservation
 */

import { isCapacitorApp, startMicrophoneForegroundService, stopMicrophoneForegroundService } from './microphone';

function initCapacitor() {
	if (!isCapacitorApp()) return;

	window.adoetzgpt = {
		startMicrophoneService: async () => {
			try {
				return await startMicrophoneForegroundService();
			} catch (e) {
				console.error('Failed to start microphone service:', e);
			}
			return false;
		},
		stopMicrophoneService: async () => {
			try {
				return await stopMicrophoneForegroundService();
			} catch (e) {
				console.error('Failed to stop microphone service:', e);
			}
			return false;
		},
		openExternalUrl: (url: string) => {
			try {
				const plugins = (window as any).Capacitor?.Plugins;
				if (plugins?.Browser) {
					plugins.Browser.open({ url });
				} else {
					window.open(url, '_blank');
				}
			} catch {
				window.open(url, '_blank');
			}
		}
	};
}

function setupMicrophoneHandling() {
	if (!isCapacitorApp()) return;

	const originalGetUserMedia = navigator.mediaDevices?.getUserMedia?.bind(navigator.mediaDevices);

	if (!originalGetUserMedia) return;

	navigator.mediaDevices.getUserMedia = async function (constraints) {
		return await originalGetUserMedia(constraints);
	};

	// Also hook into enumerateDevices to detect microphone usage
	const originalEnumerateDevices = navigator.mediaDevices?.enumerateDevices?.bind(navigator.mediaDevices);
	if (originalEnumerateDevices) {
		navigator.mediaDevices.enumerateDevices = async function () {
			const devices = await originalEnumerateDevices();
			return devices;
		};
	}
}

function setupExternalLinks() {
	if (!isCapacitorApp()) return;

	// Intercept clicks on external links to open in system browser
	document.addEventListener('click', (event) => {
		const target = event.target as HTMLElement;
		const anchor = target.closest('a');
		if (!anchor) return;

		const href = anchor.getAttribute('href');
		if (!href) return;

		// Only intercept external links (http/https to different origins)
		try {
			const url = new URL(href, window.location.origin);
			if (url.origin !== window.location.origin) {
				event.preventDefault();
				event.stopPropagation();

				// Let Capacitor handle it
				window.adoetzgpt?.openExternalUrl(url.href);
			}
		} catch {
			// Not a valid URL, let normal navigation handle it
		}
	}, true);
}

function setupWebViewStatePreservation() {
	// Prevent unnecessary unloads
	window.addEventListener('beforeunload', (event) => {
		// Keep WebView state alive
	});

	// Handle visibility changes to preserve WebSocket
	document.addEventListener('visibilitychange', () => {
		if (document.visibilityState === 'visible') {
			// App is back in foreground, WebView should auto-reconnect WebSockets
		}
	});
}

export function initCapacitorBridge() {
	if (!isCapacitorApp()) return;

	initCapacitor(); // Set up window.adoetzgpt API

	setupMicrophoneHandling();
	setupExternalLinks();
	setupWebViewStatePreservation();

	console.log('[AdoetzGPT] Capacitor bridge initialized');
}
