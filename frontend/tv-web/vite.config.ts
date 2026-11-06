import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react(), {
    name: 'bundled-runtime-licenses',
    apply: 'build',
    generateBundle() {
      const source = ['react', 'react-dom', 'scheduler'].map(name => {
        const license = readFileSync(new URL(`./node_modules/${name}/LICENSE`, import.meta.url), 'utf8');
        return `${name}\n${'='.repeat(name.length)}\n${license}`;
      }).join('\n\n');
      this.emitFile({ type: 'asset', fileName: 'THIRD_PARTY_LICENSES.txt', source });
    },
  }],
  base: './',
  build: { target: 'chrome111', cssTarget: 'chrome111', sourcemap: false, assetsInlineLimit: 0 },
  server: { host: '127.0.0.1', port: 4173, strictPort: true },
});
