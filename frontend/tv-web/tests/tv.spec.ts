import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => { await page.goto('/'); await expect(page.locator('.tv-app')).toBeVisible(); });
test('home, deterministic initial focus and complete D-pad route navigation', async ({ page }, info) => {
  await expect(page.locator('[data-focus="nav:HOME"]')).toBeFocused();
  await page.keyboard.press('ArrowRight'); await expect(page.locator('[data-focus="nav:MOVIES"]')).toBeFocused();
  await page.keyboard.press('Enter'); await expect(page.locator('.route-movies')).toBeVisible();
  await page.keyboard.press('ArrowDown'); await expect(page.locator('.poster:focus')).toHaveCount(1);
  await page.keyboard.press('Enter'); await expect(page.locator('.route-detail')).toBeVisible();
  await expect(page.locator('[data-focus="detail:play"]')).toBeFocused();
  await page.keyboard.press('Escape'); await expect(page.locator('.route-movies')).toBeVisible();
  await expect(page.locator('.poster:focus')).toHaveCount(1);
  await page.keyboard.press('Escape'); await expect(page.locator('.route-home')).toBeVisible();
  await page.screenshot({ path: `test-results/${info.project.name}-home.png` });
});
test('bounded pages traverse a catalog without trapping remote focus', async ({ page }) => {
  await page.locator('[data-focus="nav:MOVIES"]').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(40);
  await page.locator('[data-focus="pager:catalog.page:next"]').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(24);
  await expect(page.locator('[data-focus="pager:catalog.page:next"]')).toBeDisabled();
  await page.locator('[data-focus="pager:catalog.page:previous"]').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(40);
});
test('search, provenance-preserving filters and empty state', async ({ page }) => {
  await page.locator('[data-focus="nav:SEARCH"]').click();
  await page.getByRole('textbox').fill('群山'); await page.locator('[data-focus="search:submit"]').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(8);
  await page.locator('[data-focus="filter:rating"]').click();
  await expect(page.getByRole('heading', { name: '暂未找到匹配作品' })).toBeVisible();
  await page.locator('[data-focus="filter:rating"]').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(8);
});
test('detail, explicit favorite setter, status and native playback handoff', async ({ page }, info) => {
  await page.locator('[data-focus="hero:open"]').click();
  await page.locator('[data-focus="detail:favorite"]').click();
  await expect(page.locator('[data-focus="detail:favorite"]')).toContainText('加入收藏');
  await page.locator('[data-focus="detail:favorite"]').click();
  await expect(page.locator('[data-focus="detail:favorite"]')).toContainText('已收藏');
  await page.locator('[data-focus="status:WATCHING"]').click();
  await expect(page.locator('[data-focus="status:WATCHING"]')).toHaveClass(/selected/);
  await page.locator('[data-focus="detail:play"]').click();
  await expect(page.getByRole('status')).toContainText('原生 Media3');
  await page.screenshot({ path: `test-results/${info.project.name}-detail.png` });
});
test('glass tier and reduced motion preferences are effective', async ({ page }, info) => {
  await page.locator('[data-focus="nav:SETTINGS"]').click();
  await page.locator('[data-focus="quality:BASIC"]').click();
  await expect(page.locator('.tv-app')).toHaveAttribute('data-quality', 'BASIC');
  await page.locator('[data-focus="settings:motion"]').click();
  await expect(page.locator('.tv-app')).toHaveAttribute('data-reduced-motion', 'true');
  await page.locator('[data-focus="quality:ENHANCED"]').click();
  await page.screenshot({ path: `test-results/${info.project.name}-settings.png` });
});
test('library and live pages expose reachable actions', async ({ page }, info) => {
  await page.locator('[data-focus="nav:LIBRARY"]').click();
  await page.locator('[data-focus="library:FAVORITES"]').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(20);
  await page.screenshot({ path: `test-results/${info.project.name}-library.png` });
  await page.locator('[data-focus="nav:LIVE"]').click();
  await expect(page.getByRole('heading', { name: '直播', exact: true })).toBeVisible();
  await page.locator('[data-focus="channel:channel_1"]').click();
  await expect(page.getByRole('status')).toContainText('原生 Media3');
});
