import { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
	appId: 'app.adoetzgpt.threefive',
	appName: 'AdoetzGPT 3.5',
	webDir: 'build',
	android: {
		allowMixedContent: true,
		captureInput: true,
		webContentsDebuggingEnabled: false
	},
	plugins: {
		LocalNotifications: {
			smallColor: '#171717',
			iconColor: '#171717'
		}
	}
};

export default config;
