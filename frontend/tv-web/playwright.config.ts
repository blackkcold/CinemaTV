import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: { baseURL: 'http://127.0.0.1:4173', trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: [
    { name: 'android-tv-css540', use: { viewport: { width: 960, height: 540 }, deviceScaleFactor: 2 } },
    { name: 'tv720', use: { viewport: { width: 1280, height: 720 } } },
    { name: 'tv1080', use: { viewport: { width: 1920, height: 1080 } } },
    { name: 'tv4k', use: { viewport: { width: 3840, height: 2160 } } },
  ],
  webServer: { command: 'npm run dev', url: 'http://127.0.0.1:4173', reuseExistingServer: !process.env.CI },
});
