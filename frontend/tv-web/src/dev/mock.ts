import type { Platform, Snapshot } from '../types';
// FIXTURE_ONLY: this module is reachable only under import.meta.env.DEV.
export function createMockPlatform(): Platform {
  const state: Snapshot = {
    protocol: 1, revision: 1, route: 'HOME', routeKey: 'HOME', section: 'HOME', busy: false,
    message: '', quality: 'AUTO', reducedMotion: false, lowMemory: false, connected: false, authorized: false,
    version: 'Browser preview', channel: 'dev', scene: null, shelves: [], items: [], total: 0, pageIndex: 0,
    pageSize: 40, canLoadMore: false, notice: '浏览器开发预览 · 不包含片源', attribution: '', selected: null,
    episodes: [], episodePage: 0, episodeTotal: 0, alternatives: [], query: '', history: [],
    filters: { type: '', year: '', minRating: 0, topOnly: false }, years: [], channels: [], activeChannel: '',
    liveSources: [], activeLiveSource: '', programs: [], libraryFilter: 'ALL', bookmark: null,
    qualityOptions: [{ id: 'AUTO', title: '自动' }, { id: 'BASIC', title: '清晰' }],
  };
  return { native: false, async call() { return { state }; }, subscribe() { return () => {}; }, close() {} };
}
