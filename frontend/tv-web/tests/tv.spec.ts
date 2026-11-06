import { expect, test, type Page } from '@playwright/test';

const errors = new WeakMap<Page, string[]>();
const button = (page: Page, id: string) => page.locator(`[data-focus=${JSON.stringify(id)}]`);
async function open(page: Page, fixture = '') {
  await page.goto(fixture ? `/?fixture=${fixture}` : '/');
  await expect(page.locator('.tv-app')).toBeVisible();
  await expect(button(page, 'nav:HOME')).toBeFocused();
}
async function lastCall(page: Page, method: string) {
  return page.evaluate(name => window.CinemaPreview?.calls().filter(call => call.method === name).at(-1), method);
}

test.beforeEach(async ({ page }) => {
  const messages: string[] = [];
  errors.set(page, messages);
  page.on('pageerror', error => messages.push(error.message));
  await open(page);
});
test.afterEach(({ page }) => { expect(errors.get(page)).toEqual([]); });

test('five-way remote opens a work and restores exact catalog focus on Back', async ({ page }, info) => {
  await page.keyboard.press('ArrowRight');
  await expect(button(page, 'nav:EXPLORE')).toBeFocused();
  await page.keyboard.press('ArrowRight');
  await expect(button(page, 'nav:MOVIES')).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.locator('.route-movies')).toBeVisible();
  await page.keyboard.press('ArrowDown');
  await expect(button(page, 'catalog:all')).toBeFocused();
  await page.keyboard.press('ArrowDown');
  const key = await page.locator('.poster:focus').getAttribute('data-focus');
  expect(key).toBeTruthy();
  await page.keyboard.press('Enter');
  await expect(page.locator('.route-detail')).toBeVisible();
  await expect(button(page, 'detail:play')).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.locator('.route-movies')).toBeVisible();
  await expect(button(page, key!)).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.locator('.route-home')).toBeVisible();
  await page.screenshot({ path: info.outputPath('home.png') });
});

test('navigation traverses all controls and hero entry is deterministic', async ({ page }) => {
  const ids = ['nav:HOME', 'nav:EXPLORE', 'nav:MOVIES', 'nav:SERIES', 'nav:LIVE', 'nav:LIBRARY', 'nav:SEARCH', 'nav:SETTINGS'];
  for (const id of ids.slice(1)) { await page.keyboard.press('ArrowRight'); await expect(button(page, id)).toBeFocused(); }
  await page.keyboard.press('ArrowRight');
  await expect(button(page, 'nav:SETTINGS')).toBeFocused();
  for (const id of ids.slice(0, -1).reverse()) { await page.keyboard.press('ArrowLeft'); await expect(button(page, id)).toBeFocused(); }
  await page.keyboard.press('ArrowDown');
  await expect(button(page, 'hero:open')).toBeFocused();
  await page.keyboard.press('ArrowLeft');
  await expect(button(page, 'hero:open')).toBeFocused();
  await page.keyboard.press('ArrowUp');
  await expect(button(page, 'nav:HOME')).toBeFocused();
});

test('catalog filters retain exact selected values and focus across detail return', async ({ page }, info) => {
  await button(page, 'nav:MOVIES').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(48);
  const category = page.locator('[data-focus^="catalog:category:"]').first();
  await category.click();
  await expect(category).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.poster-grid .poster')).toHaveCount(32);
  await button(page, 'catalog:year').click();
  await button(page, 'catalog:rated').click();
  const selectedFilters = (await lastCall(page, 'catalog.filter'))?.params;
  expect(selectedFilters?.category).toBeTruthy();
  expect(selectedFilters?.year).toBeTruthy();
  expect(selectedFilters?.rated).toBe(true);
  const first = page.locator('.poster-grid .poster').first();
  const key = await first.getAttribute('data-focus');
  await first.click();
  await expect(page.locator('.route-detail')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(button(page, key!)).toBeFocused();
  await expect(button(page, 'catalog:rated')).toHaveAttribute('aria-pressed', 'true');
  await page.screenshot({ path: info.outputPath('catalog.png') });
});

test('bounded pages preserve page and focus through detail and Back', async ({ page }) => {
  await button(page, 'nav:MOVIES').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(48);
  await button(page, 'pager:catalog.page:next').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(16);
  await expect(page.locator('.poster-grid .poster').first()).toBeFocused();
  await expect(button(page, 'pager:catalog.page:next')).toBeDisabled();
  const key = await page.locator('.poster-grid .poster').first().getAttribute('data-focus');
  await page.keyboard.press('Enter');
  await expect(page.locator('.route-detail')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.locator('.poster-grid .poster')).toHaveCount(16);
  await expect(button(page, key!)).toBeFocused();
  await button(page, 'pager:catalog.page:previous').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(48);
  await expect(page.locator('.poster-grid .poster').first()).toBeFocused();
});

test('grid horizontal edges never jump rows and vertical focus clears the header', async ({ page }) => {
  await button(page, 'nav:MOVIES').click();
  const cards = page.locator('.poster-grid .poster');
  await cards.first().focus();
  await page.keyboard.press('ArrowLeft');
  await expect(cards.first()).toBeFocused();
  for (let index = 0; index < 5; index++) await page.keyboard.press('ArrowRight');
  await expect(cards.nth(5)).toBeFocused();
  await page.keyboard.press('ArrowRight');
  await expect(cards.nth(5)).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(cards.nth(11)).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(cards.nth(17)).toBeFocused();
  const rect = await cards.nth(17).boundingBox();
  const header = await page.locator('.topbar').boundingBox();
  expect(rect!.y).toBeGreaterThan(header!.y + header!.height);
  await page.keyboard.press('ArrowUp');
  await expect(cards.nth(11)).toBeFocused();
});

test('virtual rails reveal unmounted targets and restore a distant card', async ({ page }, info) => {
  await open(page, 'many');
  await button(page, 'card:movies:fixture_5').focus();
  for (let index = 0; index < 25; index++) await page.keyboard.press('ArrowRight');
  const target = button(page, 'card:movies:fixture_30');
  await expect(target).toBeFocused();
  await expect(target).toBeInViewport({ ratio: 1 });
  expect(await page.locator('[data-scroll="rail:movies"] .poster').count()).toBeLessThanOrEqual(13);
  await page.keyboard.press('Enter');
  await expect(page.locator('.route-detail')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(target).toBeFocused();
  await expect(target).toBeInViewport({ ratio: 1 });
  await page.screenshot({ path: info.outputPath('virtual-rail-return.png') });
});

test('discovery centers logical focus and returns to the same distant poster', async ({ page }, info) => {
  await button(page, 'nav:EXPLORE').click();
  await page.keyboard.press('ArrowDown');
  await expect(button(page, 'card:discover:fixture_0')).toBeFocused();
  for (let index = 0; index < 18; index++) await page.keyboard.press('ArrowRight');
  const target = button(page, 'card:discover:fixture_18');
  await expect(target).toBeFocused();
  await expect(target).toHaveAttribute('data-side', 'active');
  expect(await page.locator('.coverflow .poster').count()).toBeLessThanOrEqual(13);
  const rect = await target.boundingBox();
  expect(Math.abs(rect!.x + rect!.width / 2 - page.viewportSize()!.width / 2)).toBeLessThan(2);
  await page.keyboard.press('Enter');
  await expect(page.locator('.route-detail')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(target).toBeFocused();
  await expect(target).toBeInViewport({ ratio: 1 });
  await page.screenshot({ path: info.outputPath('discovery.png') });
});

test('search and provenance-preserving filters recover empty results', async ({ page }, info) => {
  await button(page, 'nav:SEARCH').click();
  await expect(page.getByRole('textbox')).toBeFocused();
  await page.getByRole('textbox').fill('群山');
  await button(page, 'search:submit').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(8);
  await button(page, 'filter:rating').click();
  await expect(page.getByRole('heading', { name: '暂未找到匹配作品' })).toBeVisible();
  await expect(button(page, 'filter:rating')).toBeFocused();
  await button(page, 'filter:rating').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(8);
  await page.screenshot({ path: info.outputPath('search.png') });
});

test('streaming insertion keeps the focused work rather than the previous index', async ({ page }) => {
  await open(page, 'streaming');
  await button(page, 'nav:SEARCH').click();
  await page.getByRole('textbox').fill('群山');
  await button(page, 'search:submit').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(2);
  const target = page.locator('.poster-grid .poster').first();
  const key = await target.getAttribute('data-focus');
  await target.focus();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(8);
  await expect(button(page, key!)).toBeFocused();
});

test('detail explicit favorite and status setters, source choice, and playback identity', async ({ page }, info) => {
  await button(page, 'hero:open').click();
  await button(page, 'detail:favorite').click();
  await expect(button(page, 'detail:favorite')).toHaveAttribute('aria-pressed', 'false');
  await button(page, 'detail:favorite').click();
  await expect(button(page, 'detail:favorite')).toHaveAttribute('aria-pressed', 'true');
  await button(page, 'status:WATCHING').click();
  await expect(button(page, 'status:WATCHING')).toHaveAttribute('aria-pressed', 'true');
  await button(page, 'detail:sources').click();
  await expect(page.locator('[data-focus^="source:"]')).toHaveCount(2);
  await page.locator('[data-focus^="source:"]').last().click();
  await expect(page.locator('.detail-hero .eyebrow')).toContainText('合成片源 B');
  await button(page, 'detail:play').click();
  const call = await lastCall(page, 'playback.open');
  expect(call?.params.key).toBe('fixture_0:source-b');
  expect(call?.params.episodeId).toBe('fixture_0:source-b:episode:0');
  await expect(page.locator('.toast')).toContainText('原生 Media3');
  await page.screenshot({ path: info.outputPath('detail.png') });
});

test('resume identity is independent of the visible episode page', async ({ page }) => {
  await open(page, 'episodes');
  await button(page, 'hero:open').click();
  await expect(page.locator('.episodes .episode')).toHaveCount(40);
  await expect(page.locator('.resume-note')).toContainText('第 88 集');
  await button(page, 'detail:play').click();
  expect((await lastCall(page, 'playback.open'))?.params.episodeId).toBe('fixture_0:episode:87');
  await button(page, 'pager:detail.episodes:next').click();
  await expect(button(page, 'episode:fixture_0:episode:40')).toBeFocused();
  await button(page, 'detail:play').click();
  expect((await lastCall(page, 'playback.open'))?.params.episodeId).toBe('fixture_0:episode:87');
  await button(page, 'episode:fixture_0:episode:44').click();
  expect((await lastCall(page, 'playback.open'))?.params.episodeId).toBe('fixture_0:episode:44');
});

test('library filters and history removal preserve the native confirmation boundary', async ({ page }, info) => {
  await button(page, 'nav:LIBRARY').click();
  await button(page, 'library:FAVORITES').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(20);
  await page.screenshot({ path: info.outputPath('library.png') });
  await button(page, 'library:WATCHING').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(1);
  await button(page, 'library:HISTORY').click();
  await expect(page.locator('.poster-grid .poster')).toHaveCount(5);
  await button(page, 'library:clear-history').click();
  await expect(page.locator('.toast')).toContainText('先请求确认');
  await expect(page.locator('.poster-grid .poster')).toHaveCount(5);
});

test('live channel focus updates EPG and remote can enter and leave the guide', async ({ page }, info) => {
  await button(page, 'nav:LIVE').click();
  await button(page, 'channel:channel_2').focus();
  await expect(page.locator('.epg h2')).toHaveText('城市纪录');
  await page.keyboard.press('ArrowRight');
  await expect(page.locator('.program').first()).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await expect(page.locator('.program').nth(1)).toBeFocused();
  await page.keyboard.press('ArrowLeft');
  await expect(button(page, 'channel:channel_2')).toBeFocused();
  await page.keyboard.press('Enter');
  expect((await lastCall(page, 'live.play'))?.params.id).toBe('channel_2');
  await page.screenshot({ path: info.outputPath('live.png') });
});

test('glass quality, motion and distinct native setting destinations are effective', async ({ page }, info) => {
  await button(page, 'nav:SETTINGS').click();
  await button(page, 'quality:BASIC').click();
  await expect(page.locator('.tv-app')).toHaveAttribute('data-quality', 'BASIC');
  await button(page, 'settings:motion').click();
  await expect(page.locator('.tv-app')).toHaveAttribute('data-reduced-motion', 'true');
  await button(page, 'quality:ENHANCED').click();
  await expect(page.locator('.tv-app')).toHaveAttribute('data-quality', 'ENHANCED');
  await button(page, 'settings:connection').focus();
  for (const section of ['connection', 'sources', 'subscriptions', 'updates', 'recommendations', 'library', 'developer']) {
    await expect(button(page, `settings:${section}`)).toBeFocused();
    await page.keyboard.press('Enter');
    expect((await lastCall(page, 'native.open'))?.params).toEqual({ target: 'SETTINGS', section });
    if (section !== 'developer') await page.keyboard.press('ArrowDown');
  }
  await page.screenshot({ path: info.outputPath('settings.png') });
  await button(page, 'nav:HOME').click();
  const card = button(page, 'card:continue:fixture_0');
  await card.focus();
  await expect(card.locator('.poster-art')).toHaveCSS('transform', 'none');
});

test('limited devices retain the chosen preference while rendering light material', async ({ page }) => {
  await open(page, 'low-memory');
  await button(page, 'nav:SETTINGS').click();
  await button(page, 'quality:ENHANCED').click();
  await expect(button(page, 'quality:ENHANCED')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.tv-app')).toHaveAttribute('data-quality', 'BASIC');
  await expect(page.locator('.navigation')).toHaveCSS('backdrop-filter', 'none');
  await expect(page.getByText('已为此设备启用轻量材质，保留你的外观偏好。')).toBeVisible();
});

test('empty and offline routes retain reachable recovery actions', async ({ page }, info) => {
  await open(page, 'empty');
  await page.keyboard.press('ArrowDown');
  await expect(button(page, 'hero:connect')).toBeFocused();
  await button(page, 'nav:LIVE').click();
  await expect(button(page, 'live:setup')).toBeVisible();
  await page.screenshot({ path: info.outputPath('empty.png') });
  await open(page, 'offline');
  await expect(page.locator('.shelf')).toHaveCount(3);
  await expect(page.getByText('暂时无法连接影视服务，已保存的资料仍可查看。')).toBeAttached();
});

test('failed artwork leaves readable fallbacks and a visible focus outline', async ({ page }) => {
  await open(page, 'broken-images');
  const card = button(page, 'card:continue:fixture_0');
  await card.focus();
  await expect(card.locator('.poster-art > img')).toHaveCSS('opacity', '0');
  await expect(card.locator('.poster-fallback')).toContainText('群山之间');
  await expect(page.locator('.scene-art')).toHaveCount(0);
  await expect(card).toBeFocused();
});

test('long metadata stays within the viewport and is scrollable with the remote', async ({ page }, info) => {
  await open(page, 'long');
  await button(page, 'hero:open').click();
  const overflow = await page.locator('.content').evaluate(element => element.scrollWidth - element.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
  const text = button(page, 'detail:description');
  await text.focus();
  await page.keyboard.press('ArrowUp');
  const before = await page.locator('.content').evaluate(element => element.scrollTop);
  await page.keyboard.press('ArrowDown');
  const after = await page.locator('.content').evaluate(element => element.scrollTop);
  expect(after).toBeGreaterThan(before);
  await expect(text).toBeFocused();
  await page.screenshot({ path: info.outputPath('long-text.png') });
});

test('stale native state cannot undo the current route', async ({ page }) => {
  await button(page, 'nav:MOVIES').click();
  await page.evaluate(() => window.CinemaPreview?.emit({ revision: 0, route: 'SETTINGS', routeKey: 'SETTINGS' }));
  await expect(page.locator('.route-movies')).toBeVisible();
  await expect(page.locator('.route-settings')).toHaveCount(0);
});

test('native Back saves the current focus before returning its bounded token', async ({ page }) => {
  await button(page, 'nav:MOVIES').click();
  await page.locator('.poster-grid .poster').first().click();
  await expect(page.locator('.route-detail')).toBeVisible();
  await button(page, 'detail:favorite').focus();
  await page.evaluate(() => window.CinemaPreview?.event('host.back', { token: 'qa-back-token' }));
  await expect(page.locator('.route-movies')).toBeVisible();
  const calls = await page.evaluate(() => window.CinemaPreview?.calls() ?? []);
  const backIndex = calls.findIndex(call => call.method === 'navigation.back' && call.params.token === 'qa-back-token');
  expect(backIndex).toBeGreaterThan(0);
  const preceding = calls.slice(0, backIndex).filter(call => call.method === 'navigation.bookmark').at(-1);
  expect((preceding?.params.bookmark as { focus: string }).focus).toBe('detail:favorite');
});

test('a delayed first frame never steals newer remote focus', async ({ page }) => {
  await page.addInitScript(() => {
    const request = window.requestAnimationFrame.bind(window);
    const cancel = window.cancelAnimationFrame.bind(window);
    let sequence = 0;
    const pending = new Map<number, { frame: number; timer: number }>();
    window.requestAnimationFrame = callback => {
      const id = ++sequence;
      const entry = { frame: 0, timer: 0 };
      entry.frame = request(time => { entry.timer = window.setTimeout(() => { pending.delete(id); callback(time); }, 200); });
      pending.set(id, entry);
      return id;
    };
    window.cancelAnimationFrame = id => {
      const entry = pending.get(id);
      if (entry) { cancel(entry.frame); window.clearTimeout(entry.timer); pending.delete(id); }
    };
  });
  await open(page);
  await button(page, 'nav:MOVIES').click();
  const cards = page.locator('.poster-grid .poster');
  await cards.first().focus();
  await page.keyboard.press('ArrowRight');
  await expect(cards.nth(1)).toBeFocused();
  await page.waitForTimeout(450);
  await expect(cards.nth(1)).toBeFocused();
});

for (const route of ['MOVIES', 'SERIES', 'EXPLORE', 'SEARCH']) {
  test(`${route}: an empty filtered page retains remote loading and stable D-pad focus`, async ({ page }) => {
    await open(page, 'remote-pages');
    await button(page, `nav:${route}`).click();
    if (route === 'SEARCH') {
      await page.getByRole('textbox').fill('群山');
      await button(page, 'search:submit').click();
    }
    await expect(page.locator('.poster-grid .poster, .coverflow .poster')).toHaveCount(0);
    const load = button(page, 'pager:load');
    await expect(load).toHaveText('载入更多');
    await load.focus();
    await page.keyboard.press('Enter');
    await expect(load).toHaveText('正在载入…');
    await expect(load).toBeFocused();
    await page.keyboard.press('Enter');
    await expect.poll(async () => page.locator('.poster-grid .poster, .coverflow .poster').count()).toBeGreaterThan(0);
    await expect(load).toBeFocused();
    await expect(load).toHaveText(route === 'SEARCH' ? '重新检索目录' : '刷新目录');
    const requests = await page.evaluate(() => window.CinemaPreview?.calls().filter(call => call.method === 'catalog.more').length);
    expect(requests).toBe(1);
    if (route === 'SEARCH') await expect(page.getByRole('textbox')).toHaveValue('群山');
  });
}

test('failed remote loading can retry from an empty page without changing the filter', async ({ page }) => {
  await open(page, 'remote-retry');
  await button(page, 'nav:MOVIES').click();
  const category = page.locator('[data-focus^="catalog:category:"]').first();
  await category.click();
  const load = button(page, 'pager:load');
  await load.focus();
  await page.keyboard.press('Enter');
  await expect(load).toHaveText('重试载入');
  await expect(load).toBeFocused();
  await expect(category).toHaveAttribute('aria-pressed', 'true');
  await page.keyboard.press('Enter');
  await expect.poll(async () => page.locator('.poster-grid .poster').count()).toBeGreaterThan(0);
  await expect(category).toHaveAttribute('aria-pressed', 'true');
  await expect(load).toBeFocused();
});

test('the live guide advances without provider events and keeps a surviving program focused', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-11-06T10:00:00Z') });
  await open(page);
  await button(page, 'nav:LIVE').click();
  const guide = page.locator('.program');
  const current = await guide.first().getAttribute('data-focus');
  const future = await guide.nth(2).getAttribute('data-focus');
  await button(page, future!).focus();
  await page.clock.fastForward(61 * 60 * 1000);
  await expect(button(page, current!)).toHaveCount(0);
  await expect(page.locator('.program.on-air')).toHaveCount(1);
  await expect(page.locator('.program.on-air')).toContainText('回望');
  await expect(button(page, future!)).toBeFocused();
});

test('the live guide pauses in the background and recalibrates focus immediately on return', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-11-06T10:00:00Z') });
  await open(page);
  await button(page, 'nav:LIVE').click();
  const previous = await page.locator('.program').first().getAttribute('data-focus');
  const next = await page.locator('.program').nth(1).getAttribute('data-focus');
  await button(page, previous!).focus();
  await page.evaluate(() => window.CinemaPreview?.event('host.visibility', { visible: false }));
  await page.clock.fastForward(61 * 60 * 1000);
  await expect(button(page, previous!)).toBeFocused();
  await page.evaluate(() => window.CinemaPreview?.event('host.visibility', { visible: true }));
  await expect(button(page, previous!)).toHaveCount(0);
  await expect(button(page, next!)).toBeFocused();
  await expect(button(page, next!)).toHaveClass(/on-air/);
});

test('rating source is retained by type and year filters and Top250 keeps Douban provenance', async ({ page }) => {
  await button(page, 'nav:SEARCH').click();
  await button(page, 'filter:tmdb').click();
  expect((await lastCall(page, 'search.filter'))?.params).toMatchObject({ minRating: 8, ratingSource: 'TMDB', topOnly: false });
  await button(page, 'filter:MOVIES').click();
  expect((await lastCall(page, 'search.filter'))?.params).toMatchObject({ type: 'MOVIES', minRating: 8, ratingSource: 'TMDB' });
  await button(page, 'filter:year').click();
  await button(page, 'filter:douban').click();
  expect((await lastCall(page, 'search.filter'))?.params).toMatchObject({ type: 'MOVIES', year: '2025', minRating: 8, ratingSource: 'Douban', topOnly: false });
  await button(page, 'filter:chart').click();
  expect((await lastCall(page, 'search.filter'))?.params).toMatchObject({ ratingSource: 'Douban', minRating: 0, topOnly: true });
  await expect(page.getByText('豆瓣筛选仅显示有效授权目录；未配置时不会替代为其他评分。')).toBeVisible();
  await button(page, 'filter:reset').click();
  expect((await lastCall(page, 'search.filter'))?.params).toEqual({ type: '', year: '', minRating: 0, ratingSource: '', topOnly: false });
});

test('live groups filter channels and reset cleanly when changing source', async ({ page }) => {
  await button(page, 'nav:LIVE').click();
  await expect(page.locator('.channel')).toHaveCount(3);
  await button(page, 'live-group:音乐测试频道').click();
  await expect(page.locator('.channel')).toHaveCount(1);
  await expect(button(page, 'channel:channel_3')).toBeVisible();
  await expect(button(page, 'live-group:音乐测试频道')).toHaveAttribute('aria-pressed', 'true');
  await button(page, 'live-source:live_1').click();
  await expect(button(page, 'live-group:all')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.channel')).toHaveCount(3);
});

test('detail exposes recommendation reasons and an explicit restart without clearing the library', async ({ page }) => {
  await button(page, 'hero:open').click();
  await expect(page.locator('.recommendation-reason')).toHaveText('因为你最近观看了原创测试作品');
  await expect(button(page, 'detail:play')).toHaveText('继续观看');
  await button(page, 'detail:restart').click();
  expect((await lastCall(page, 'playback.open'))?.params).toEqual({ key: 'fixture_0', episodeId: 'fixture_0:episode:0', action: 'RESTART' });
  await expect(button(page, 'detail:favorite')).toHaveAttribute('aria-pressed', 'true');
  await button(page, 'detail:play').click();
  expect((await lastCall(page, 'playback.open'))?.params.action).toBe('RESUME');
});

test('completed episodes say replay and request a restart for both primary and episode actions', async ({ page }) => {
  await open(page, 'completed');
  await button(page, 'hero:open').click();
  await expect(button(page, 'detail:play')).toHaveText('重新播放');
  await expect(button(page, 'detail:restart')).toHaveCount(0);
  await button(page, 'detail:play').click();
  expect((await lastCall(page, 'playback.open'))?.params.action).toBe('RESTART');
  await button(page, 'episode:fixture_0:episode:0').click();
  expect((await lastCall(page, 'playback.open'))?.params.action).toBe('RESTART');
});

test('library management opens the retained-record review and leaves the current filter intact', async ({ page }) => {
  await button(page, 'nav:LIBRARY').click();
  await button(page, 'library:FAVORITES').click();
  await button(page, 'library:manage').click();
  expect((await lastCall(page, 'native.open'))?.params).toEqual({ target: 'LIBRARY' });
  await expect(button(page, 'library:FAVORITES')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.poster-grid .poster')).toHaveCount(20);
  await expect(page.locator('.toast')).toContainText('待核对的观看记录');
});
