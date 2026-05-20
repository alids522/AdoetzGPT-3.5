<script lang="ts">
	import { WEBUI_BASE_URL } from '$lib/constants';
	import { safeImageUrl } from '$lib/utils/safeImageUrl';

	import { settings } from '$lib/stores';
	import ImagePreview from './ImagePreview.svelte';
	import XMark from '$lib/components/icons/XMark.svelte';
	import { getContext } from 'svelte';

	export let src = '';
	export let alt = '';

	export let className = ` w-full ${($settings?.highContrastMode ?? false) ? '' : 'outline-hidden focus:outline-hidden'}`;

	export let imageClassName = 'rounded-lg';

	export let dismissible = false;
	export let onDismiss = () => {};

	const i18n = getContext('i18n');

	import { onDestroy } from 'svelte';

	let objectUrl = '';

	onDestroy(() => {
		if (objectUrl) {
			URL.revokeObjectURL(objectUrl);
		}
	});

	let _src = '';
	$: {
		if (src) {
			const resolvedSrc = src.startsWith('/') ? `${WEBUI_BASE_URL}${src}` : src;
			const isRemoteBackend = WEBUI_BASE_URL !== '' && resolvedSrc.startsWith(WEBUI_BASE_URL);
			const isNative = typeof window !== 'undefined' && (window as any).Capacitor?.isNativePlatform?.();

			if (isNative && isRemoteBackend && !resolvedSrc.startsWith('data:') && !resolvedSrc.startsWith('blob:')) {
				const token = localStorage.token;
				if (token) {
					fetch(resolvedSrc, {
						headers: {
							'Authorization': `Bearer ${token}`
						}
					})
						.then((res) => {
							if (!res.ok) throw new Error('Failed to load image');
							return res.blob();
						})
						.then((blob) => {
							if (objectUrl) {
								URL.revokeObjectURL(objectUrl);
							}
							objectUrl = URL.createObjectURL(blob);
							_src = objectUrl;
						})
						.catch((err) => {
							console.error('[Image] Authenticated fetch failed:', err);
							_src = safeImageUrl(resolvedSrc);
						});
				} else {
					_src = safeImageUrl(resolvedSrc);
				}
			} else {
				_src = safeImageUrl(resolvedSrc);
			}
		} else {
			_src = safeImageUrl('');
		}
	}

	let showImagePreview = false;
</script>

<ImagePreview bind:show={showImagePreview} src={_src} {alt} />

<div class=" relative group w-fit flex items-center">
	<button
		class={className}
		on:click={() => {
			showImagePreview = true;
		}}
		aria-label={$i18n.t('Show image preview')}
		type="button"
	>
		<img src={_src} {alt} class={imageClassName} draggable="false" data-cy="image" />
	</button>

	{#if dismissible}
		<div class=" absolute -top-1 -right-1">
			<button
				aria-label={$i18n.t('Remove image')}
				class=" bg-white text-black border border-white rounded-full group-hover:visible invisible transition"
				type="button"
				on:click={() => {
					onDismiss();
				}}
			>
				<XMark className={'size-4'} />
			</button>
		</div>
	{/if}
</div>
