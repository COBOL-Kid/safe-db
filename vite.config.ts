import tailwindcss from '@tailwindcss/vite';
import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

const TAURI_PORT = 1420;

export default defineConfig({
	plugins: [
		tailwindcss(),
		sveltekit({
			compilerOptions: {
				// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
				runes: ({ filename }) => filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},
			adapter: adapter({ fallback: 'index.html' })
		})
	],
	clearScreen: false,
	server: {
		port: TAURI_PORT,
		strictPort: true
	},
	envPrefix: ['VITE_', 'TAURI_'],
	build: {
		target: 'es2021'
	}
});
