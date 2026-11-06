import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPlatform } from './platform';
import { captureBookmark, focusKey, focusTargets, installSpatialInput, restoreBookmark } from './focus';
import { Button, Empty, Icon, Scene, type Action } from './components';
import { Catalog, Detail, Explore, Home, Live, Search, Settings } from './pages';
import type { Platform, Route, Snapshot } from './types';

const navigation: [Route, string][] = [['HOME', '现在观看'], ['EXPLORE', '发现'], ['MOVIES', '电影'], ['SERIES', '剧集'], ['LIVE', '直播'], ['LIBRARY', '资料库']];
const routeActions = new Set(['navigation.go', 'navigation.back', 'media.open', 'playback.open', 'native.open', 'live.play']);

function defaultFocus(state: Snapshot, content = false): string {
  if (state.route === 'DETAIL') return state.resumeEpisode ? 'detail:play' : 'detail:favorite';
  if (state.route === 'SEARCH' && !content) return 'search:input';
  if (content && state.items[0]) return `card:${state.route === 'EXPLORE' ? 'discover' : 'grid'}:${state.items[0].key}`;
  if (content && state.route === 'LIVE' && state.channels[0]) return `channel:${state.channels[0].id}`;
  return `nav:${state.route}`;
}

function errorMessage(code: string): string {
  if (code === 'TIMEOUT') return '操作尚未确认，请先检查当前状态，再决定是否重试。';
  if (code === 'CONFLICT' || code === 'STALE_ITEM') return '作品资料已更新，请重新选择。';
  if (code === 'BRIDGE_BUSY') return '正在处理，请稍候再试。';
  return '操作暂未完成，请重试。';
}

function contentEntry(state: Snapshot): string {
  if (state.route === 'HOME') return state.scene || state.shelves.some(shelf => shelf.items.length) ? 'hero:open' : 'hero:connect';
  if (state.route === 'EXPLORE') return state.items[0] ? `card:discover:${state.items[0].key}` : 'discover:connect';
  if (state.route === 'MOVIES' || state.route === 'SERIES') return 'catalog:all';
  if (state.route === 'LIBRARY') return `library:${state.libraryFilter}`;
  if (state.route === 'LIVE') return state.liveSources[0] ? `live-source:${state.activeLiveSource || state.liveSources[0].id}` : 'live:setup';
  if (state.route === 'SETTINGS') return `quality:${state.quality}`;
  return defaultFocus(state);
}

export function App() {
  const [state, setState] = useState<Snapshot | null>(null);
  const [error, setError] = useState('');
  const [native, setNative] = useState(true);
  const current = useRef<Snapshot | null>(null);
  const platform = useRef<Platform | null>(null);
  const mounted = useRef(true);
  const routePending = useRef(false);
  const focusRequest = useRef<{ method: string; params: Record<string, unknown> } | null>(null);
  const focusTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const lastFocus = useRef({ route: '', key: '' });
  const focusGeneration = useRef(0);
  const layout = useRef<{ route: string; page: number; episode: number } | null>(null);

  const apply = useCallback((next: Snapshot) => {
    if (!mounted.current || !next || next.protocol !== 1 || !Number.isSafeInteger(next.revision) ||
      (current.current && next.revision <= current.current.revision)) return;
    current.current = next;
    setState(next);
  }, []);

  const send = useCallback((method: string, params: Record<string, unknown> = {}) => {
    const client = platform.current;
    if (!client) return;
    if (routeActions.has(method) && routePending.current) return;
    const navigating = routeActions.has(method);
    if (navigating) routePending.current = true;
    void (async () => {
      const previous = current.current;
      if (previous && navigating) {
        await client.call('navigation.bookmark', { bookmark: captureBookmark(previous.routeKey) });
      }
      const reply = await client.call(method, params);
      if (reply.state) apply(reply.state);
    })().catch((failure: Error) => {
      if (mounted.current && !['media.focus', 'live.focus', 'navigation.bookmark', 'message.dismiss'].includes(method)) setError(errorMessage(failure.message));
    }).finally(() => { if (navigating) routePending.current = false; });
  }, [apply]);

  const act = useCallback<Action>((method, params = {}) => {
    if (method === 'media.focus' || method === 'live.focus') {
      focusRequest.current = { method, params };
      if (focusTimer.current) return;
      // Focus is visible immediately; coalesce only background/native scene work.
      focusTimer.current = setTimeout(() => {
        focusTimer.current = undefined;
        const request = focusRequest.current;
        focusRequest.current = null;
        if (request && document.documentElement.dataset.paused !== 'true') send(request.method, request.params);
      }, 80);
    } else send(method, params);
  }, [send]);

  useEffect(() => {
    mounted.current = true;
    let canceled = false;
    let cleanup = () => {};
    void createPlatform().then(async client => {
      if (canceled) { client.close(); return; }
      platform.current = client;
      setNative(client.native);
      document.documentElement.dataset.native = String(client.native);
      const unsubscribe = client.subscribe((name, payload) => {
        if (name === 'state') apply(payload as Snapshot);
        if (name === 'host.back' && payload && typeof payload === 'object' && 'token' in payload && typeof payload.token === 'string' && payload.token.length <= 160) {
          send('navigation.back', { token: payload.token });
        }
        if (name === 'host.visibility' && payload && typeof payload === 'object' && 'visible' in payload) {
          document.documentElement.dataset.paused = payload.visible ? 'false' : 'true';
          window.dispatchEvent(new Event('cinema:visibility'));
        }
      });
      cleanup = () => { unsubscribe(); client.close(); platform.current = null; };
      const reply = await client.call('app.ready', { protocol: 1 });
      if (reply.state) apply(reply.state);
    }).catch(() => { if (!canceled) setError('无法打开界面，请返回后重试。'); });
    return () => {
      canceled = true;
      mounted.current = false;
      clearTimeout(focusTimer.current);
      cleanup();
    };
  }, [apply, send]);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const save = () => {
      const snapshot = current.current;
      const focused = (document.activeElement as HTMLElement | null)?.dataset.focus;
      if (snapshot && focused) lastFocus.current = { route: snapshot.routeKey, key: focused };
      clearTimeout(timer);
      timer = setTimeout(() => {
        const latest = current.current;
        if (latest) act('navigation.bookmark', { bookmark: captureBookmark(latest.routeKey) });
      }, 120);
    };
    const uninstall = installSpatialInput(() => act('navigation.back'), () => { focusGeneration.current++; save(); });
    document.addEventListener('scroll', save, true);
    return () => { clearTimeout(timer); uninstall(); document.removeEventListener('scroll', save, true); };
  }, [act]);

  useLayoutEffect(() => {
    if (!state) return;
    const previous = layout.current;
    const pageChanged = previous?.route === state.routeKey && previous.page !== state.pageIndex;
    const episodesChanged = previous?.route === state.routeKey && previous.episode !== state.episodePage;
    layout.current = { route: state.routeKey, page: state.pageIndex, episode: state.episodePage };
    const generation = focusGeneration.current;
    const frame = requestAnimationFrame(() => {
      const active = document.activeElement as HTMLElement | null;
      // A slow frame may arrive after the user has already moved into the new
      // page. Deferred restoration must never overwrite that newer focus.
      if (focusGeneration.current !== generation && active?.dataset.focus && active.isConnected &&
        !active.matches('[disabled], [aria-disabled="true"]')) return;
      if (pageChanged) {
        const page = document.querySelector('.content');
        if (page) page.scrollTop = 0;
        restoreBookmark(null, defaultFocus(state, true));
      } else if (episodesChanged) {
        restoreBookmark(null, state.episodes[0] ? `episode:${state.episodes[0].id}` : defaultFocus(state));
      } else restoreBookmark(state.bookmark?.route === state.routeKey ? state.bookmark : null, defaultFocus(state));
    });
    return () => cancelAnimationFrame(frame);
  }, [state?.routeKey, state?.pageIndex, state?.episodePage]);

  useLayoutEffect(() => {
    if (!state) return;
    // Streaming updates and filters can remove a focused item. Preserve a surviving
    // stable key; otherwise recover one reachable target, without moving valid focus.
    const frame = requestAnimationFrame(() => {
      const active = document.activeElement as HTMLElement | null;
      if (active?.dataset.focus && active.isConnected && !active.matches('[disabled], [aria-disabled="true"]')) return;
      if (lastFocus.current.route === state.routeKey && focusKey(lastFocus.current.key)) return;
      if (!focusKey(defaultFocus(state, lastFocus.current.key.startsWith('card:')))) focusTargets()[0]?.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, [state]);

  useEffect(() => {
    if (!error && !state?.message) return;
    const timer = setTimeout(() => { setError(''); if (state?.message) send('message.dismiss'); }, 6000);
    return () => clearTimeout(timer);
  }, [error, state?.message, send]);

  if (!state) return <main className="boot"><span className="wordmark">cinema<span>tv</span></span><span className="boot-orbit" aria-hidden="true" /><h1>{error || '正在打开 CinemaTV'}</h1><p>你的大屏影院</p></main>;
  const entry = contentEntry(state);
  return <div className="tv-app" data-quality={state.effectiveQuality || (state.lowMemory ? 'BASIC' : state.quality)} data-reduced-motion={state.reducedMotion}>
    <Scene item={state.route === 'DETAIL' ? state.selected : state.scene} />
    <header className="topbar"><span className="wordmark" aria-label="CinemaTV">cinema<span>tv</span></span>
      {state.route === 'DETAIL' ? <Button id="detail:back" group="nav" navDown={entry} className="glass back-button" onClick={() => act('navigation.back')}><Icon name="back" />返回</Button> :
        <nav className="glass navigation" aria-label="主导航" data-focus-layout="row">{navigation.map(([route, label]) => <Button key={route} id={`nav:${route}`} group="nav" navDown={entry} className="nav-item" selected={state.route === route} current={state.route === route} onClick={() => act('navigation.go', { route })}>{label}</Button>)}</nav>}
      <div className="tools"><Button id="nav:SEARCH" group="nav" navDown={entry} className="glass icon-button" label="搜索" selected={state.route === 'SEARCH'} current={state.route === 'SEARCH'} onClick={() => act('navigation.go', { route: 'SEARCH' })}><Icon name="search" /></Button>
        <Button id="nav:SETTINGS" group="nav" navDown={entry} className="glass icon-button" label="设置" selected={state.route === 'SETTINGS'} current={state.route === 'SETTINGS'} onClick={() => act('navigation.go', { route: 'SETTINGS' })}><Icon name="settings" /></Button></div>
    </header>
    <main key={state.routeKey} className={`content route-${state.route.toLowerCase()}`} data-scroll="page" aria-busy={state.busy}>
      {state.route === 'HOME' ? <Home state={state} act={act} /> : state.route === 'EXPLORE' ? <Explore state={state} act={act} /> :
        state.route === 'SEARCH' ? <Search state={state} act={act} native={native} /> : state.route === 'DETAIL' ? <Detail state={state} act={act} /> :
          state.route === 'LIVE' ? <Live state={state} act={act} /> : state.route === 'SETTINGS' ? <Settings state={state} act={act} /> :
            state.route === 'PLAYER' ? <Empty title="正在打开影片" detail="即将开始播放" /> : <Catalog state={state} act={act} />}
      {state.notice && !['HOME', 'DETAIL', 'SEARCH'].includes(state.route) && <p className="footnote">{state.notice}</p>}
      {state.attribution && <p className="attribution">{state.attribution}</p>}
      <div className="bottom-safe" />
    </main>
    {state.busy && <div className="loading-line" aria-label="正在加载" />}
    {(error || state.message) && <div className="toast glass" role="status">{error || state.message}</div>}
    {!native && <span className="preview-label">开发预览 · 合成内容，不含片源</span>}
  </div>;
}
