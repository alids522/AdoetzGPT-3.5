// See https://kit.svelte.dev/docs/types#app
// for information about these interfaces
declare global {
	namespace App {
		// interface Error {}
		// interface Locals {}
		// interface PageData {}
		// interface Platform {}
	}

	// Vite define plugin
	const APP_VERSION: string;
	const APP_BUILD_HASH: string;

	// Capacitor bridge
	interface Window {
		adoetzgpt?: {
			startMicrophoneService: () => Promise<boolean>;
			stopMicrophoneService: () => Promise<boolean>;
			openExternalUrl: (url: string) => void;
		};
	}
}

export {};
