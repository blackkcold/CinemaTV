import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({ plugins: [react()], base: './', build: { target: 'chrome111', cssTarget: 'chrome111', sourcemap: false, assetsInlineLimit: 0 }, server: { host: '127.0.0.1', port: 4173, strictPort: true } });
