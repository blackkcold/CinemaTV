import { test, expect } from '@playwright/test';
import type { Card, Snapshot } from '../src/types';

test('late detail episodes preserve valid favorite focus and one Left reaches Play', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('/');
  await expect(page.locator('.tv-app')).toBeVisible();
  await expect(page.locator('[data-focus="nav:HOME"]')).toBeFocused();
  const card: Card = {
    key: 'pending-detail', title: 'Original delayed-detail fixture', poster: '', backdrop: '',
    year: '2026', category: 'Synthetic', description: 'An offline asynchronous detail state.',
    rating: '', ratingSource: '', isTv: true, favorite: false, status: '',
    positionMs: 0, durationMs: 0, lastEpisode: 0, sourceName: 'Synthetic source', attribution: '',
  };
  const loading: Partial<Snapshot> = {
    route: 'DETAIL', routeKey: `DETAIL:${card.key}`, section: 'HOME', selected: card,
    busy: true, episodes: [], resumeEpisode: null, episodeTotal: 0, episodePage: 0, bookmark: null,
  };
  await page.evaluate(patch => window.CinemaPreview!.emit(patch), loading);
  const favorite = page.locator('[data-focus="detail:favorite"]');
  const play = page.locator('[data-focus="detail:play"]');
  await expect(favorite).toBeFocused();
  await expect(play).toBeDisabled();
  const episode = { id: 'pending-detail:0', index: 0, label: 'Episode 1', progress: null, completed: false };
  await page.evaluate(patch => window.CinemaPreview!.emit(patch), {
    ...loading, busy: false, episodes: [episode], resumeEpisode: episode, episodeTotal: 1,
  });
  await expect(play).toBeEnabled();
  // Settle the state effects without sleeps or moving the user's focus.
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  await expect(favorite).toBeFocused();
  await page.keyboard.press('ArrowLeft');
  await expect(play).toBeFocused();
  expect(errors).toEqual([]);
});
