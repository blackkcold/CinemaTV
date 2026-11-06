import { defineConfig } from '@playwright/test';
import { resolve } from 'node:path';

// Deliberately separate from the development-server interaction suite.
export default defineConfig({
  testDir: './tests/production',
  testMatch: '**/*.spec.ts',
  outputDir: 'production-results',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  updateSnapshots: 'none',
  timeout: 30000,
  reporter: [['list'], ['json', { outputFile: 'production-results/results.json' }]],
  snapshotPathTemplate: resolve('../../design/ui/goldens/hybrid-browser/{projectName}/{arg}{ext}'),
  use: {
    baseURL: 'http://127.0.0.1:4174', locale: 'zh-CN', timezoneId: 'UTC',
    colorScheme: 'dark', reducedMotion: 'reduce', trace: 'retain-on-failure', screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'css540-dpr2', use: { viewport: { width: 960, height: 540 }, deviceScaleFactor: 2 } },
    { name: 'tv720', use: { viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1 } },
    { name: 'tv1080', use: { viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 } },
    { name: 'tv4k', use: { viewport: { width: 3840, height: 2160 }, deviceScaleFactor: 1 } },
  ],
  webServer: {
    command: 'npm run preview -- --port 4174 --strictPort',
    url: 'http://127.0.0.1:4174', reuseExistingServer: false,
  },
});
