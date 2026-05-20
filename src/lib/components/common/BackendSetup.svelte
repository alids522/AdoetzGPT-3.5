<script lang="ts">
	import { onMount } from 'svelte';
	import Spinner from './Spinner.svelte';

	export let onSave: (url: string) => void;

	let url = '';
	let testing = false;
	let errorMsg = '';
	let successMsg = '';
	let theme = 'dark';

	onMount(() => {
		// Detect dark/light mode preference
		const isDark = document.documentElement.classList.contains('dark') || 
					   window.matchMedia('(prefers-color-scheme: dark)').matches;
		theme = isDark ? 'dark' : 'light';
	});

	const testAndSave = async () => {
		errorMsg = '';
		successMsg = '';
		
		if (!url) {
			errorMsg = 'Please enter a valid URL';
			return;
		}

		// Normalize URL
		let targetUrl = url.trim().replace(/\/$/, '');
		if (!/^https?:\/\//i.test(targetUrl)) {
			targetUrl = 'https://' + targetUrl;
		}

		testing = true;
		try {
			// Test by calling /api/config or /api/v1/config
			const controller = new AbortController();
			const timeoutId = setTimeout(() => controller.abort(), 8000);

			const response = await fetch(`${targetUrl}/api/config`, {
				method: 'GET',
				signal: controller.signal,
				headers: {
					'Content-Type': 'application/json'
				}
			});
			clearTimeout(timeoutId);

			if (response.ok) {
				const config = await response.json();
				successMsg = `Connected successfully! Welcome to ${config.name || 'Open WebUI'}`;
				setTimeout(() => {
					onSave(targetUrl);
				}, 1200);
			} else {
				throw new Error(`Server responded with status ${response.status}`);
			}
		} catch (e: any) {
			console.error('Connection test failed:', e);
			
			// Fallback: test /api/v1/config
			try {
				const controller = new AbortController();
				const timeoutId = setTimeout(() => controller.abort(), 8000);

				const response = await fetch(`${targetUrl}/api/v1/config`, {
					method: 'GET',
					signal: controller.signal
				});
				clearTimeout(timeoutId);

				if (response.ok) {
					successMsg = 'Connected successfully!';
					setTimeout(() => {
						onSave(targetUrl);
					}, 1200);
					return;
				}
			} catch (fallbackError) {
				// both failed
			}

			errorMsg = e.name === 'AbortError' 
				? 'Connection timed out. Please check your network or server status.' 
				: 'Failed to connect. Make sure the URL is correct and CORS is enabled on the server.';
		} finally {
			testing = false;
		}
	};
</script>

<div class="fixed inset-0 z-50 flex items-center justify-center p-6 bg-radial from-[#1e1e24] to-[#0a0a0c] dark:from-[#111115] dark:to-[#020203] font-sans text-white select-none">
	<!-- Dynamic animated background glowing orbs -->
	<div class="absolute inset-0 overflow-hidden pointer-events-none opacity-40">
		<div class="absolute -top-40 -left-40 w-96 h-96 rounded-full bg-cyan-500/20 blur-3xl animate-pulse duration-[8s]"></div>
		<div class="absolute -bottom-40 -right-40 w-96 h-96 rounded-full bg-indigo-500/20 blur-3xl animate-pulse duration-[12s]"></div>
	</div>

	<!-- Main Setup Card -->
	<div class="relative w-full max-w-md p-8 md:p-10 rounded-3xl backdrop-blur-2xl bg-white/5 border border-white/10 shadow-2xl shadow-black/80 transition-all duration-300">
		
		<!-- Brand & Logo -->
		<div class="flex flex-col items-center text-center mb-8">
			<div class="relative flex items-center justify-center w-20 h-20 mb-4 rounded-3xl bg-linear-to-tr from-cyan-500 to-indigo-600 shadow-lg shadow-cyan-500/20 group">
				<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-10 h-10 text-white animate-pulse">
					<path stroke-linecap="round" stroke-linejoin="round" d="M8.25 3v1.5M4.5 8.25H3m18 0h-1.5M4.5 12H3m18 0h-1.5m-15 3.75H3m18 0h-1.5M8.25 19.5V21M12 3v1.5m0 15V21m3.75-18v1.5m0 15V21m-9-1.5h10.5a2.25 2.25 0 0 0 2.25-2.25V6.75a2.25 2.25 0 0 0-2.25-2.25H6.75A2.25 2.25 0 0 0 4.5 6.75v10.5a2.25 2.25 0 0 0 2.25 2.25Zm.75-12h9v9h-9v-9Z" />
				</svg>
			</div>
			<h1 class="text-3xl font-extrabold tracking-tight bg-linear-to-r from-cyan-400 via-teal-300 to-indigo-400 bg-clip-text text-transparent">
				AdoetzGPT 3.5
			</h1>
			<p class="mt-2 text-sm text-gray-400">
				Connect your client app to a remote Open WebUI server
			</p>
		</div>

		<!-- Configuration Form -->
		<form on:submit|preventDefault={testAndSave} class="space-y-6">
			<div>
				<label for="url" class="block text-xs font-semibold uppercase tracking-wider text-gray-400 mb-2">
					Server Backend URL
				</label>
				<div class="relative rounded-2xl bg-black/40 border border-white/10 focus-within:border-cyan-500/50 focus-within:shadow-[0_0_15px_rgba(6,182,212,0.15)] transition-all duration-200">
					<span class="absolute inset-y-0 left-0 flex items-center pl-4 text-gray-500">
						<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-5 h-5">
							<path stroke-linecap="round" stroke-linejoin="round" d="M13.19 8.688a4.5 4.5 0 0 1 1.242 7.244l-4.5 4.5a4.5 4.5 0 0 1-6.364-6.364l1.757-1.757m13.35-.622 1.757-1.757a4.5 4.5 0 0 0-6.364-6.364l-4.5 4.5a4.5 4.5 0 0 0 1.242 7.244" />
						</svg>
					</span>
					<input
						bind:value={url}
						type="text"
						id="url"
						placeholder="https://chat.example.com"
						autocomplete="off"
						disabled={testing}
						class="w-full py-4 pl-12 pr-4 bg-transparent outline-hidden text-white placeholder-gray-600 text-sm"
					/>
				</div>
			</div>

			<!-- Status Messages -->
			{#if errorMsg}
				<div class="p-4 rounded-2xl bg-rose-500/10 border border-rose-500/20 text-rose-300 text-xs leading-relaxed flex gap-3 items-start animate-fadeIn">
					<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="w-5 h-5 shrink-0 text-rose-400">
						<path fill-rule="evenodd" d="M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0Zm-8-5a.75.75 0 0 1 .75.75v4.5a.75.75 0 0 1-1.5 0v-4.5A.75.75 0 0 1 10 5Zm0 10a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z" clip-rule="evenodd" />
					</svg>
					<span>{errorMsg}</span>
				</div>
			{/if}

			{#if successMsg}
				<div class="p-4 rounded-2xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-300 text-xs leading-relaxed flex gap-3 items-start animate-fadeIn">
					<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="w-5 h-5 shrink-0 text-emerald-400">
						<path fill-rule="evenodd" d="M10 18a8 8 0 1 0 0-16 8 8 0 0 0 0 16Zm3.857-9.809a.75.75 0 0 0-1.214-.882l-3.483 4.79-1.88-1.88a.75.75 0 1 0-1.06 1.061l2.5 2.5a.75.75 0 0 0 1.137-.089l4-5.5Z" clip-rule="evenodd" />
					</svg>
					<span>{successMsg}</span>
				</div>
			{/if}

			<!-- Actions -->
			<button
				type="submit"
				disabled={testing}
				class="relative overflow-hidden flex items-center justify-center w-full py-4 rounded-2xl font-semibold text-sm bg-linear-to-r from-cyan-500 to-indigo-600 hover:from-cyan-400 hover:to-indigo-500 text-white shadow-lg shadow-cyan-500/10 hover:shadow-cyan-400/20 hover:scale-[1.01] active:scale-[0.99] disabled:opacity-50 disabled:pointer-events-none transition-all duration-200 group"
			>
				{#if testing}
					<span class="flex items-center gap-2">
						<Spinner className="w-5 h-5 text-white" />
						Testing connection...
					</span>
				{:else}
					<span>Connect & Continue</span>
				{/if}
			</button>
		</form>
	</div>
</div>

<style>
	@keyframes fadeIn {
		from { opacity: 0; transform: translateY(4px); }
		to { opacity: 1; transform: translateY(0); }
	}
	.animate-fadeIn {
		animation: fadeIn 0.25s cubic-bezier(0.16, 1, 0.3, 1) forwards;
	}
</style>
