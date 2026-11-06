import type { Bookmark, Card, Platform, Route, Snapshot } from '../types';
// FIXTURE_ONLY: original procedural artwork and fictitious titles; never bundled in APK.
const titles = ['群山之间', '午夜电台', '潮汐以北', '漫长的回声', '雨落之前', '星光旅人', '蓝色公路', '去往明天', '山海来信', '风的形状', '极夜微光', '夏日回廊'];
function art(index: number, wide: boolean): string {
  const hue = (index * 37 + 188) % 360, width = wide ? 1280 : 480, height = 720;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><defs><linearGradient id="g" x2="1" y2="1"><stop stop-color="hsl(${hue},30%,43%)"/><stop offset="1" stop-color="hsl(${hue + 24},30%,9%)"/></linearGradient><radialGradient id="r"><stop stop-color="hsl(${hue + 50},35%,80%)" stop-opacity=".5"/><stop offset="1" stop-color="#ffffff" stop-opacity="0"/></radialGradient></defs><rect width="100%" height="100%" fill="url(#g)"/><ellipse cx="${width * .72}" cy="210" rx="${width * .38}" ry="230" fill="url(#r)"/><path d="M-50 520 Q${width * .22} 210 ${width * .52} 420 T${width + 50} 330 V800 H-50Z" fill="#071724" opacity=".55"/><path d="M-50 660 Q${width * .35} 310 ${width * .65} 560 T${width + 100} 490 V800 H-50Z" fill="#050e1a" opacity=".72"/><circle cx="${width * .72}" cy="170" r="45" fill="#e6e5d4" opacity=".75"/></svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}
const catalog: Card[] = Array.from({ length: 96 }, (_, index) => ({
  key: `fixture_${index}`, title: `${titles[index % titles.length]}${index >= titles.length ? ` ${Math.floor(index / titles.length) + 1}` : ''}`,
  poster: art(index, false), backdrop: art(index, true), year: String(2022 + index % 4), category: index % 2 ? '剧情 · 悬疑' : '冒险 · 剧情',
  description: '一次没有预设终点的旅行，几段意外相遇的人生。在山与海的尽头，重新发现日常里被忽略的辽阔。',
  rating: '', ratingSource: '', isTv: index % 3 === 0, favorite: index % 5 === 0, status: '',
  positionMs: index === 0 ? 1800000 : 0, durationMs: index === 0 ? 5400000 : 0, lastEpisode: 0, sourceName: '合成开发资料', attribution: '原创合成测试数据',
}));
export function createMockPlatform(): Platform {
  let revision = 0; let route: Route = 'HOME'; let section: Route = 'HOME'; let selected: Card | null = null;
  let page = 0; let query = ''; let filter = 'ALL'; let reduced = false; let quality = 'AUTO';
  const marks = new Map<string, Bookmark>(); const history: string[] = [];
  let filters = { type: '', year: '', minRating: 0, topOnly: false };
  const listeners = new Set<(event: string, payload: unknown) => void>();
  const routeKey = () => route === 'DETAIL' ? `DETAIL:${selected?.key}` : route;
  function snapshot(): Snapshot {
    let items = route === 'MOVIES' ? catalog.filter(item => !item.isTv) : route === 'SERIES' ? catalog.filter(item => item.isTv) : route === 'SEARCH' ? catalog.filter(item => item.title.includes(query)) : catalog;
    if (route === 'LIBRARY') items = items.filter(item => filter === 'ALL' || (filter === 'FAVORITES' ? item.favorite : filter === 'HISTORY' ? item.positionMs > 0 : item.status === filter));
    if (route === 'SEARCH') items = items.filter(item => (!filters.type || item.isTv === (filters.type === 'SERIES')) && (!filters.year || item.year === filters.year) && !filters.minRating && !filters.topOnly);
    const episodes = selected ? Array.from({ length: selected.isTv ? 12 : 1 }, (_, index) => ({ id: `${selected!.key}:episode:${index}`, index, label: selected!.isTv ? `第 ${index + 1} 集` : '播放正片', progress: index === 0 && selected!.positionMs ? .33 : null })) : [];
    return { protocol: 1, revision: ++revision, route, routeKey: routeKey(), section, busy: false, message: '', quality, reducedMotion: reduced, lowMemory: false, connected: true, authorized: true, version: 'Browser preview', channel: 'dev', scene: selected ?? catalog[0], shelves: [{ id: 'continue', title: '继续观看', items: catalog.slice(0, 5) }, { id: 'movies', title: '为你精选', items: catalog.slice(5, 17) }, { id: 'series', title: '值得追的故事', items: catalog.filter(item => item.isTv).slice(0, 12) }], items: items.slice(page * 40, (page + 1) * 40), total: items.length, pageIndex: page, pageSize: 40, canLoadMore: false, notice: '', attribution: '', selected, episodes, episodePage: 0, episodeTotal: episodes.length, alternatives: [], query, history, filters, years: ['2025', '2024', '2023', '2022'], channels: [{ id: 'channel_1', name: '原创测试频道', group: '开发预览', logo: '' }], activeChannel: 'channel_1', liveSources: [{ id: 'live_1', name: '合成订阅' }], activeLiveSource: 'live_1', programs: [{ title: '山海来信 · 创作纪录', start: 1793926800000, stop: 1793930400000 }], libraryFilter: filter, bookmark: marks.get(routeKey()) ?? null, qualityOptions: [{ id: 'AUTO', title: '自动' }, { id: 'BASIC', title: '清晰' }, { id: 'BALANCED', title: '柔和玻璃' }, { id: 'ENHANCED', title: '增强玻璃' }] };
  }
  return {
    native: false,
    async call(method, params = {}) {
      if (method === 'navigation.bookmark') { const bookmark = params.bookmark as Bookmark; marks.set(bookmark.route, bookmark); return { accepted: true }; }
      if (method === 'media.focus' || method === 'live.focus') return { accepted: true };
      if (method === 'navigation.go') { route = params.route as Route; section = route; page = 0; selected = null; }
      if (method === 'navigation.back') { route = route === 'DETAIL' ? section : 'HOME'; selected = null; page = 0; }
      if (method === 'media.open') { selected = catalog.find(item => item.key === params.key) ?? null; route = 'DETAIL'; page = 0; }
      if (method === 'library.favorite' && selected) selected.favorite = Boolean(params.favorite);
      if (method === 'library.status' && selected) selected.status = String(params.status);
      if (method === 'library.filter') { filter = String(params.filter); page = 0; }
      if (method === 'catalog.page') page = Math.max(0, Number(params.page));
      if (method === 'search.submit') { query = String(params.query).trim(); if (!history.includes(query)) history.unshift(query); page = 0; }
      if (method === 'search.filter') { filters = { ...filters, ...params } as typeof filters; page = 0; }
      if (method === 'search.year') { const years = ['', '2025', '2024', '2023', '2022']; filters.year = years[(years.indexOf(filters.year) + 1) % years.length]; page = 0; }
      if (method === 'settings.motion') reduced = Boolean(params.reduced);
      if (method === 'settings.quality') quality = String(params.quality);
      if (method === 'native.open') { route = params.target as Route; }
      const state = snapshot();
      if (method === 'playback.open' || method === 'live.play') state.message = '开发预览不播放真实媒体；Android 版本使用原生 Media3。';
      for (const listener of listeners) listener('state', state);
      return { accepted: true, state };
    },
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    close() { listeners.clear(); },
  };
}
