import { test, expect, type Page, type TestInfo, type Browser } from '@playwright/test';
import { build } from 'esbuild';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

declare global {
  interface Window { CinemaVisualFixture?: {
    call(method: string, params?: Record<string, unknown>): Promise<unknown>;
    image(path: string): string | null;
    statistics(): { largestReplyBytes: number; imageCount: number };
  }; }
}
const captureOnly = process.env.CINEMA_VISUAL_CAPTURE === '1';
const source = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
const sha256 = (value: Buffer | string) => createHash('sha256').update(value).digest('hex');
const errors = new WeakMap<Page, string[]>();
const fontPaths = new Set(['/assets/fonts/NotoSans-Regular.ttf', '/assets/fonts/NotoSans-Bold.ttf']);
let injection = '';
let fontIdentity = '';
let systemFontIdentity = '';

test.beforeAll(async () => {
  const result = await build({ entryPoints: ['tests/production/bridge.ts'], bundle: true, write: false,
    format: 'iife', platform: 'browser', target: 'chrome111' });
  injection = result.outputFiles[0].text;
  fontIdentity = sha256(await readFile('../../scripts/fonts/noto-fonts.lock.json'));
  const systemFont = execFileSync('fc-match', ['-f', '%{file}', 'sans:lang=zh-cn'], { encoding: 'utf8' }).trim();
  systemFontIdentity = sha256(await readFile(systemFont));
});
test.beforeEach(({ page }) => {
  const messages: string[] = [];
  errors.set(page, messages);
  page.on('pageerror', error => messages.push(error.message));
});
test.afterEach(({ page }) => expect(errors.get(page)).toEqual([]));

async function ready(page: Page, fixture = '') {
  // Exact URL predicates avoid interpreting path separators as a regexp literal.
  // Register before the test clock/init scripts; no application assets are changed.
  await page.route(url => fontPaths.has(url.pathname), async route => {
    const pathname = new URL(route.request().url()).pathname;
    if (!fontPaths.has(pathname)) throw new Error('Unexpected font request');
    const name = pathname.split('/').at(-1)!;
    const body = await readFile(resolve('../../.cache/cinematv-noto-fonts', name));
    await route.fulfill({ status: 200, contentType: 'font/ttf', body });
  });
  // Artwork is served outside RPC, like the production Android local image adapter.
  await page.route(url => /^\/__cinema_visual_art\/\d+\.svg$/.test(url.pathname), async route => {
    const path = new URL(route.request().url()).pathname;
    const body = await page.evaluate(path => window.CinemaVisualFixture?.image(path), path);
    if (typeof body !== 'string') throw new Error(`Unregistered synthetic artwork: ${path}`);
    await route.fulfill({ status: 200, contentType: 'image/svg+xml', body });
  });
  // Freeze wall-clock metadata, not requestAnimationFrame or input scheduling.
  await page.clock.setFixedTime(new Date('2026-11-06T10:00:00Z'));
  await page.addInitScript({ content: injection });
  await page.goto(fixture ? `/?fixture=${fixture}` : '/');
  await expect(page.locator('.tv-app')).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('data-native', 'true');
  await expect(page.locator('.tv-app')).toHaveAttribute('data-reduced-motion', 'true');
  await page.evaluate(() => document.fonts.ready);
  const stats = await page.evaluate(() => window.CinemaVisualFixture!.statistics());
  expect(stats.largestReplyBytes).toBeGreaterThan(0);
  expect(stats.largestReplyBytes).toBeLessThanOrEqual(512 * 1024);
  expect(stats.imageCount).toBeGreaterThan(0);
}

async function capture(page: Page, browser: Browser, info: TestInfo, name: string) {
  await page.waitForFunction(() => !document.querySelector('.scene-probe') && [...document.images].filter(image => {
    const r = image.getBoundingClientRect();
    return r.width > 1 && r.height > 1 && r.right > 0 && r.bottom > 0 && r.left < innerWidth && r.top < innerHeight;
  }).every(image => image.complete && image.naturalWidth > 0));
  await expect(page.locator('[data-focus]:focus')).toHaveCount(1);
  const overflow = await page.locator('.content').evaluate(element => element.scrollWidth - element.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
  const environment = {
    family: 'hybrid-browser-production', browser: browser.version(), platform: process.platform,
    viewport: page.viewportSize(), dpr: await page.evaluate(() => devicePixelRatio),
    locale: 'zh-CN', timezone: 'UTC', fontLockSha256: fontIdentity, systemCjkFontSha256: systemFontIdentity,
    quality: await page.locator('.tv-app').getAttribute('data-quality'), reducedMotion: true,
  };
  const folder = resolve('production-captures', info.project.name);
  await mkdir(folder, { recursive: true });
  const image = await page.screenshot({ animations: 'disabled', caret: 'hide' });
  await writeFile(resolve(folder, `${name}.png`), image);
  await writeFile(resolve(folder, `${name}.json`), JSON.stringify({ schema: 1, sourceCommit: source,
    status: captureOnly ? 'candidate-only-not-approved' : 'comparison-pending',
    environment, sha256: sha256(image), image: `${name}.png`, fixture: 'original-synthetic-only',
    focus: await page.locator('[data-focus]:focus').getAttribute('data-focus'),
  }, null, 2) + '\n');
  if (!captureOnly) {
    const baseline = resolve('../../design/ui/goldens/hybrid-browser', info.project.name);
    const review = JSON.parse(await readFile(resolve(baseline, `${name}.json`), 'utf8'));
    expect(review.status, 'Only explicitly reviewed baselines may pass').toBe('reviewed-baseline');
    expect(review.environment, 'Environment mismatch is not a visual difference').toEqual(environment);
    expect(sha256(await readFile(resolve(baseline, `${name}.png`)))).toBe(review.sha256);
    await expect(page).toHaveScreenshot(`${name}.png`, {
      animations: 'disabled', caret: 'hide', threshold: 0.2, maxDiffPixelRatio: 0.002,
    });
  }
}

for (const route of ['HOME', 'EXPLORE', 'MOVIES', 'SERIES', 'SEARCH', 'LIVE', 'LIBRARY', 'SETTINGS']) {
  test(`production ${route}`, async ({ page, browser }, info) => {
    await ready(page);
    if (route !== 'HOME') await page.locator(`[data-focus="nav:${route}"]`).click();
    await expect(page.locator(`.route-${route.toLowerCase()}`)).toBeVisible();
    await capture(page, browser, info, route.toLowerCase());
  });
}

test('production detail and actual primary focus', async ({ page, browser }, info) => {
  await ready(page);
  await page.keyboard.press('ArrowDown');
  await expect(page.locator('[data-focus="hero:open"]')).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.locator('[data-focus="detail:play"]')).toBeFocused();
  await capture(page, browser, info, 'detail');
});

test('production long detail stays within bounds', async ({ page, browser }, info) => {
  await ready(page, 'long');
  await page.locator('[data-focus="hero:open"]').click();
  await expect(page.locator('.route-detail')).toBeVisible();
  await capture(page, browser, info, 'detail-long');
});

test('production empty catalog retains its load control', async ({ page, browser }, info) => {
  await ready(page, 'remote-pages');
  await page.locator('[data-focus="nav:MOVIES"]').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(0);
  const load = page.locator('[data-focus="pager:load"]');
  await expect(load).toBeVisible();
  await load.focus();
  await capture(page, browser, info, 'catalog-empty-load');
});

test('production settings scroll clears fixed navigation', async ({ page, browser }, info) => {
  await ready(page);
  await page.locator('[data-focus="nav:SETTINGS"]').click();
  await page.locator('[data-focus="settings:connection"]').focus();
  for (let index = 0; index < 6; index++) await page.keyboard.press('ArrowDown');
  const last = page.locator('[data-focus="settings:developer"]');
  await expect(last).toBeFocused();
  await expect(last).toBeInViewport({ ratio: 0.99 });
  await capture(page, browser, info, 'settings-scrolled');
});

test('production lightweight material is a separate visual state', async ({ page, browser }, info) => {
  await ready(page, 'low-memory');
  await expect(page.locator('.tv-app')).toHaveAttribute('data-quality', 'BASIC');
  await expect(page.locator('.navigation')).toHaveCSS('backdrop-filter', 'none');
  await capture(page, browser, info, 'home-basic');
});

test('production assets contain no fixture module or development runtime', async ({ page }) => {
  const response = await page.request.get('/');
  const html = await response.text();
  expect(html).not.toContain('/@vite/client');
  const assets = [...html.matchAll(/(?:src|href)="((?:\.\/|\/)assets\/[^"\s]+)"/g)].map(match => match[1]);
  expect(assets.length).toBeGreaterThan(0);
  expect(assets.some(path => path.endsWith('.js'))).toBe(true);
  for (const asset of assets) {
    const response = await page.request.get(asset);
    expect(response.ok()).toBe(true);
    expect(await response.text()).not.toMatch(/FIXTURE_ONLY|CinemaVisualFixture|fixture_0|合成开发资料/);
  }
});
