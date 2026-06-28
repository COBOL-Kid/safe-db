function isTauri(): boolean {
	if (typeof window === 'undefined') return false;
	return '__TAURI_INTERNALS__' in window;
}

export async function syncWindowBackgroundColor(theme: string): Promise<void> {
	if (!isTauri()) return;
	try {
		const { getCurrentWebviewWindow } = await import('@tauri-apps/api/webviewWindow');
		const win = getCurrentWebviewWindow();
		const color = theme === 'dark' ? '#020617' : '#f8fafc';
		await win.setBackgroundColor(color);
	} catch {
		// Non-macOS or older runtimes may not support setBackgroundColor; ignore.
	}
}
