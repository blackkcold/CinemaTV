import type { Bookmark, Card, CatalogFilters, Episode, Platform, Route, Snapshot } from '../types';

// FIXTURE_ONLY: original procedural artwork and fictitious titles; never bundled in APK.
const titles = ['群山之间', '午夜电台', '潮汐以北', '漫长的回声', '雨落之前', '星光旅人', '蓝色公路', '去往明天', '山海来信', '风的形状', '极夜微光', '夏日回廊'];
const descriptions = [
  '在群山与湖泊之间，一位旅行者寻找记忆中的小屋。没有预设终点的旅程，慢慢变成了回到自己的路。',
  '午夜十二点，城市里只剩下一间仍在广播的电台。陌生人的来电，让两段平行的人生第一次靠近。',
  '沿着潮汐的方向一路向北，在熟悉的海岸线之外，发现另一种生活的可能。',
];
function art(index: number, wide: boolean): string {
  const width = wide ? 1280 : 480, height = 720;
  const hue = (index * 39 + 188) % 360;
  const kind = index % 4;
  const title = titles[index % titles.length];
  const landscape = kind === 1 ?
    `<path d="M0 720V420H${width * .12}V290H${width * .25}V390H${width * .38}V230H${width * .49}V340H${width * .6}V180H${width * .7}V360H${width * .83}V260H${width}V720Z" fill="#06111d" opacity=".75"/><path d="M${width * .61} 220v70m30-40v80m-25 40v55M${width * .29} 450v70m20 70v50M${width * .43} 290v30" stroke="#ffd4a2" stroke-width="6" opacity=".65"/>` :
    `<path d="M-50 465L${width * .18} 265L${width * .3} 377L${width * .56} 160L${width * .78} 335L${width + 80} 270V800H-50Z" fill="hsl(${hue + 13},28%,18%)"/><path d="M${width * .56} 160L${width * .49} 238L${width * .56} 209L${width * .6} 230Z" fill="#f0ede2" opacity=".56"/><path d="M-50 590Q${width * .23} 360 ${width * .57} 510T${width + 50} 420V800H-50Z" fill="#071724" opacity=".64"/><path d="M-50 700Q${width * .34} 470 ${width * .69} 620T${width + 100} 570V800H-50Z" fill="#050e1a" opacity=".8"/>`;
  const detail = kind === 2 ? `<path d="M0 570Q${width * .4} 510 ${width} 550M0 590Q${width * .4} 530 ${width} 575M0 625Q${width * .4} 555 ${width} 610" stroke="#c4dce5" stroke-width="2" opacity=".12"/>` :
    `<path d="M${width * .69} 720Q${width * .59} 580 ${width * .77} 508" fill="none" stroke="#b6b7a9" stroke-width="${wide ? 7 : 4}" opacity=".25"/><circle cx="${width * .79}" cy="477" r="4" fill="#050b11"/><path d="M${width * .79} 482v15m0-10-4 8m4-8 5 7m-5 3-3 10m3-10 4 10" stroke="#050b11" stroke-width="3"/>`;
  const lettering = wide ? '' : `<text x="40" y="68" fill="#fff" opacity=".52" font-family="sans-serif" font-size="11" letter-spacing="4">CINEMA ORIGINAL</text><text x="240" y="566" text-anchor="middle" fill="#f5f3ed" font-family="sans-serif" font-weight="600" font-size="48" letter-spacing="5">${title}</text><text x="240" y="601" text-anchor="middle" fill="#e9edf0" opacity=".6" font-family="sans-serif" font-size="10" letter-spacing="3">AN ORIGINAL VISUAL STUDY</text>`;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><defs><linearGradient id="g" x2=".8" y2="1"><stop stop-color="hsl(${hue},25%,${kind === 1 ? 22 : 42}%)"/><stop offset=".55" stop-color="hsl(${hue + 35},22%,27%)"/><stop offset="1" stop-color="#081422"/></linearGradient><radialGradient id="r"><stop stop-color="#f8deb7" stop-opacity=".65"/><stop offset="1" stop-color="#ffffff" stop-opacity="0"/></radialGradient><linearGradient id="v" x2="0" y2="1"><stop stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity=".6"/></linearGradient></defs><rect width="100%" height="100%" fill="url(#g)"/><ellipse cx="${width * .73}" cy="180" rx="${width * .4}" ry="250" fill="url(#r)"/><circle cx="${width * .74}" cy="150" r="${kind === 1 ? 25 : 41}" fill="#ece5d4" opacity=".8"/>${landscape}${detail}<rect width="100%" height="100%" fill="url(#v)"/>${lettering}</svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

interface PreviewControl {
  calls(): { method: string; params: Record<string, unknown> }[];
  emit(patch: Partial<Snapshot>): void;
  event(name: 'host.back' | 'host.visibility', payload: unknown): void;
}
declare global { interface Window { CinemaPreview?: PreviewControl; } }

export function createMockPlatform(): Platform {
  const scenario = new URLSearchParams(location.search).get('fixture') ?? '';
  const empty = scenario === 'empty';
  const catalog: Card[] = Array.from({ length: empty ? 0 : scenario === 'many' ? 1000 : 96 }, (_, index) => ({
    key: `fixture_${index}`, title: `${titles[index % titles.length]}${index >= titles.length ? ` ${Math.floor(index / titles.length) + 1}` : ''}${scenario === 'long' ? '：在山海与城市之间的一段漫长旅程' : ''}`,
    poster: scenario === 'broken-images' ? '/intentionally-missing.png' : art(index, false),
    backdrop: scenario === 'broken-images' ? '/intentionally-missing-wide.png' : art(index, true),
    year: String(2022 + index % 4), category: index % 2 ? '剧情 · 悬疑' : '冒险 · 剧情',
    description: descriptions[index % descriptions.length].repeat(scenario === 'long' ? 64 : 1), rating: '', ratingSource: '',
    isTv: index % 3 === 0, favorite: index % 5 === 0, status: index === 2 ? 'WATCHING' : '',
    positionMs: index < 5 ? 1800000 - index * 180000 : 0, durationMs: index < 5 ? (scenario === 'completed' && index === 0 ? 1800000 : 5400000) : 0,
    lastEpisode: scenario === 'episodes' && index === 0 ? 87 : 0,
    sourceName: '合成开发资料', attribution: '原创合成测试数据',
  }));
  const registry = new Map(catalog.map(item => [item.key, item]));
  let revision = 0, closed = false;
  let route: Route = 'HOME', section: Route = 'HOME', detailReturn: Route = 'HOME';
  let selected: Card | null = null, focused: Card | null = catalog[0] ?? null;
  let query = '', libraryFilter = 'ALL', reduced = false, quality = 'AUTO', busy = false, message = '';
  let episodePage = 0, alternatives: Card[] = [], activeChannel = 'channel_1', liveGroup = '';
  let searchPartial = false;
  const remotePaging = scenario === 'remote-pages' || scenario === 'remote-retry';
  let remoteDelivered = false, remoteFailed = false, remoteAttempts = 0;
  const pages = new Map<string, number>();
  const marks = new Map<string, Bookmark>();
  const history: string[] = [];
  const routeFilters = new Map<string, CatalogFilters>();
  let filters = { type: '', year: '', minRating: 0, ratingSource: '', topOnly: false };
  const listeners = new Set<(event: string, payload: unknown) => void>();
  const calls: { method: string; params: Record<string, unknown> }[] = [];
  const timers = new Set<ReturnType<typeof setTimeout>>();
  const routeKey = () => route === 'DETAIL' ? `DETAIL:${selected?.key}` : route;
  const catalogFilters = () => routeFilters.get(route) ?? { category: '', year: '', rated: false };
  const allEpisodes = (): Episode[] => selected ? Array.from({ length: selected.isTv ? scenario === 'episodes' ? 120 : 12 : 1 }, (_, index) => ({
    id: `${selected!.key}:episode:${index}`, index, label: selected!.isTv ? `第 ${index + 1} 集` : '播放正片', progress: index === selected!.lastEpisode && selected!.positionMs ? scenario === 'completed' ? 1 : .33 : null, completed: scenario === 'completed' && index === selected!.lastEpisode,
  })) : [];
  const scheduleStart = Date.now() - 1200000;
  const channels = [{ id: 'channel_1', name: '山海影像', group: '原创测试频道', logo: '' }, { id: 'channel_2', name: '城市纪录', group: '原创测试频道', logo: '' }, { id: 'channel_3', name: '音乐现场', group: '音乐测试频道', logo: '' }];

  function snapshot(): Snapshot {
    let items = route === 'MOVIES' ? catalog.filter(item => !item.isTv) : route === 'SERIES' ? catalog.filter(item => item.isTv) :
      route === 'SEARCH' ? query ? catalog.filter(item => item.title.includes(query)) : [] : catalog;
    if (route === 'LIBRARY') items = items.filter(item => libraryFilter === 'ALL' ? item.favorite || item.positionMs > 0 || Boolean(item.status) :
      libraryFilter === 'FAVORITES' ? item.favorite : libraryFilter === 'HISTORY' ? item.positionMs > 0 : item.status === libraryFilter);
    if (route === 'SEARCH') {
      items = items.filter(item => (!filters.type || item.isTv === (filters.type === 'SERIES')) && (!filters.year || item.year === filters.year) && !filters.minRating && !filters.topOnly);
      if (searchPartial) items = items.slice(1, 3);
    }
    const cf = catalogFilters();
    const categories = [...new Set(items.map(item => item.category).filter(Boolean))].slice(0, 12);
    const catalogYears = [...new Set(items.map(item => item.year).filter(Boolean))].sort().reverse().slice(0, 16);
    if (['MOVIES', 'SERIES', 'EXPLORE'].includes(route)) {
      items = items.filter(item => (!cf.category || item.category === cf.category) && (!cf.year || item.year === cf.year));
      if (cf.rated) items = [...items].sort((a, b) => Number(b.rating || 0) - Number(a.rating || 0));
    }
    const remoteRoute = remotePaging && ['MOVIES', 'SERIES', 'EXPLORE', 'SEARCH'].includes(route) && (route !== 'SEARCH' || Boolean(query));
    if (remoteRoute && !remoteDelivered) items = [];
    const pageSize = 48;
    const shownChannels = channels.filter(channel => !liveGroup || channel.group === liveGroup);
    const total = route === 'LIVE' ? shownChannels.length : items.length;
    const page = Math.min(pages.get(routeKey()) ?? 0, Math.max(0, Math.ceil(total / pageSize) - 1));
    pages.set(routeKey(), page);
    const episodes = allEpisodes();
    return {
      protocol: 1, revision: ++revision, route, routeKey: routeKey(), section, busy, message, quality,
      effectiveQuality: scenario === 'low-memory' ? 'BASIC' : quality === 'AUTO' ? 'BACKDROP' : quality,
      effectsLimited: scenario === 'low-memory', reducedMotion: reduced, lowMemory: scenario === 'low-memory',
      connected: !empty, authorized: !empty, version: 'Browser preview', channel: 'dev', scene: focused,
      shelves: empty ? [] : [
        { id: 'continue', title: '继续观看', items: catalog.filter(item => item.positionMs > 0) },
        { id: 'movies', title: '为你精选', items: catalog.slice(5, scenario === 'many' ? 53 : 29) },
        { id: 'series', title: '值得追的故事', items: catalog.filter(item => item.isTv).slice(0, 24) },
      ],
      items: items.slice(page * pageSize, (page + 1) * pageSize), total, pageIndex: page, pageSize,
      canLoadMore: remoteRoute && !busy && !remoteDelivered, pagingStatus: !remoteRoute ? 'unavailable' : busy ? 'loading' : remoteFailed ? 'retry' : remoteDelivered ? 'exhausted' : 'ready', notice: scenario === 'offline' ? '暂时无法连接影视服务，已保存的资料仍可查看。' : '', attribution: '', recommendationReason: route === 'DETAIL' ? '因为你最近观看了原创测试作品' : '', selected,
      episodes: episodes.slice(episodePage * 40, (episodePage + 1) * 40), resumeEpisode: episodes[selected?.lastEpisode ?? 0] ?? episodes[0] ?? null,
      episodePage, episodeTotal: episodes.length, alternatives, query, history: [...history], filters: { ...filters },
      years: ['2025', '2024', '2023', '2022'], catalogFilters: cf, categories, catalogYears,
      liveGroups: empty ? [] : [...new Set(channels.map(channel => channel.group))].map(name => ({ id: name, name })), liveGroup,
      channels: empty ? [] : shownChannels, activeChannel, liveSources: empty ? [] : [{ id: 'live_1', name: '合成订阅' }], activeLiveSource: 'live_1',
      programs: empty ? [] : Array.from({ length: 6 }, (_, index) => ({ id: `${activeChannel}:${scheduleStart}:${index}`, title: `${activeChannel === 'channel_2' ? '城市之声' : '山海来信'} · ${['清晨', '回望', '出发', '相遇', '远方', '归途'][index]}`, start: scheduleStart + index * 3600000, stop: scheduleStart + (index + 1) * 3600000 })),
      libraryFilter, hasHistory: catalog.some(item => item.positionMs > 0), quarantinedProgressCount: 0,
      bookmark: marks.get(routeKey()) ?? null,
      qualityOptions: [{ id: 'AUTO', title: '自动' }, { id: 'BASIC', title: '清晰' }, { id: 'BACKDROP', title: '柔和玻璃' }, { id: 'ENHANCED', title: '增强玻璃' }],
    };
  }
  const emit = (state: Snapshot) => { if (!closed) for (const listener of listeners) listener('state', state); };
  window.CinemaPreview = { calls: () => [...calls], emit: patch => emit({ ...snapshot(), ...patch }), event: (name, payload) => { for (const listener of listeners) listener(name, payload); } };
  return {
    native: false,
    async call(method, params = {}) {
      if (closed) throw new Error('BRIDGE_CLOSED');
      calls.push({ method, params });
      if (calls.length > 200) calls.shift();
      if (method === 'navigation.bookmark') {
        const bookmark = params.bookmark as Bookmark;
        marks.set(bookmark.route, bookmark);
        return { accepted: true };
      }
      if (method === 'media.focus') { focused = registry.get(String(params.key)) ?? focused; }
      else if (method === 'live.group') { liveGroup = String(params.id); activeChannel = channels.find(channel => !liveGroup || channel.group === liveGroup)?.id ?? ''; }
      else if (method === 'live.source') { liveGroup = ''; activeChannel = 'channel_1'; }
      else if (method === 'live.focus') activeChannel = String(params.id);
      else if (method === 'navigation.go') { route = params.route as Route; section = route; message = ''; }
      else if (method === 'navigation.back') {
        route = route === 'DETAIL' ? detailReturn : 'HOME';
        section = route;
        selected = null;
        message = '';
      }
      else if (method === 'media.open') {
        const item = registry.get(String(params.key));
        if (!item) throw new Error('CONFLICT');
        if (route !== 'DETAIL') detailReturn = route;
        selected = item;
        route = 'DETAIL';
        episodePage = 0;
        alternatives = [];
        message = '';
      }
      else if (method === 'library.favorite' && selected) selected.favorite = Boolean(params.favorite);
      else if (method === 'library.status' && selected) selected.status = String(params.status);
      else if (method === 'library.filter') { libraryFilter = String(params.filter); pages.set(routeKey(), 0); }
      else if (method === 'library.clearHistory') message = 'Android 版本会先请求确认，清除观看记录后仍保留收藏与观看状态。';
      else if (method === 'catalog.page') pages.set(routeKey(), Math.max(0, Math.floor(Number(params.page))));
      else if (method === 'catalog.filter') {
        routeFilters.set(route, { category: String(params.category ?? ''), year: String(params.year ?? ''), rated: Boolean(params.rated) });
        pages.set(routeKey(), 0);
      }
      else if (method === 'catalog.more' && remotePaging && !busy) {
        busy = true;
        remoteFailed = false;
        remoteAttempts++;
        const timer = setTimeout(() => {
          busy = false;
          remoteFailed = scenario === 'remote-retry' && remoteAttempts === 1;
          remoteDelivered = !remoteFailed;
          timers.delete(timer);
          emit(snapshot());
        }, 450);
        timers.add(timer);
      }
      else if (method === 'catalog.refresh' || method === 'catalog.more') message = '目录已刷新';
      else if (method === 'search.submit') {
        query = String(params.query).trim().slice(0, 120);
        if (query && !history.includes(query)) history.unshift(query);
        history.splice(12);
        pages.set(routeKey(), 0);
        if (scenario === 'streaming') {
          busy = true;
          searchPartial = true;
          const timer = setTimeout(() => { busy = false; searchPartial = false; timers.delete(timer); emit(snapshot()); }, 1000);
          timers.add(timer);
        }
      }
      else if (method === 'search.filter') { filters = { ...filters, ...params } as typeof filters; pages.set(routeKey(), 0); }
      else if (method === 'search.year') { const years = ['', '2025', '2024', '2023', '2022']; filters.year = years[(years.indexOf(filters.year) + 1) % years.length]; pages.set(routeKey(), 0); }
      else if (method === 'detail.episodes') episodePage = Math.max(0, Math.min(Math.floor(Number(params.page)), Math.ceil(allEpisodes().length / 40) - 1));
      else if (method === 'detail.sources' && selected) {
        const alternative = { ...selected, key: `${selected.key}:source-b`, sourceName: '合成片源 B' };
        registry.set(alternative.key, alternative);
        alternatives = [selected, alternative];
      }
      else if (method === 'settings.motion') reduced = Boolean(params.reduced);
      else if (method === 'settings.quality') quality = String(params.quality);
      else if (method === 'native.open') message = params.target === 'DETAIL' ? 'Android 版本会打开分季与作品整理。' : params.target === 'LIBRARY' ? 'Android 版本会打开待核对的观看记录。' : `Android 版本会打开${({ connection: '连接与账号', sources: '片源与直播', updates: '版本与更新', developer: '开发者选项', recommendations: '本地推荐', library: '资料管理', subscriptions: '源列表订阅' } as Record<string, string>)[String(params.section)] || '设置'}。`;
      else if (method === 'playback.open' || method === 'live.play') message = '开发预览不播放真实媒体；Android 版本使用原生 Media3。';
      else if (method === 'message.dismiss') message = '';
      else if (method !== 'app.ready' && method !== 'live.source') throw new Error('UNKNOWN_METHOD');
      const state = snapshot();
      emit(state);
      return { accepted: true, state };
    },
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    close() { closed = true; listeners.clear(); timers.forEach(clearTimeout); delete window.CinemaPreview; },
  };
}
