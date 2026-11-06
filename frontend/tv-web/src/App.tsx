import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { createPlatform } from './platform';
import { captureBookmark, focusKey, installSpatialInput, restoreBookmark } from './focus';
import type { Card, Platform, Route, Snapshot } from './types';

type Action = (method: string, params?: Record<string, unknown>) => void;
const navigation: [Route, string][] = [['HOME', '现在观看'], ['MOVIES', '电影'], ['SERIES', '剧集'], ['LIVE', '直播'], ['LIBRARY', '资料库']];
const libraryTabs = [['ALL', '全部'], ['HISTORY', '继续观看'], ['FAVORITES', '收藏'], ['WANT_TO_WATCH', '想看'], ['WATCHING', '在看'], ['WATCHED', '看过'], ['DROPPED', '弃剧']];
const iconPaths: Record<string, string> = {
  play: 'M8 5l11 7-11 7z', search: 'M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  back: 'M15 5l-7 7 7 7', plus: 'M12 5v14M5 12h14', check: 'M5 12l4 4L20 5',
  settings: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M12 2v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M5 19l2-2M17 7l2-2',
  arrow: 'M5 12h14m-6-6 6 6-6 6', refresh: 'M20 7v5h-5M4 17v-5h5M6 7a7 7 0 0 1 12-1l2 6M4 12l2 6a7 7 0 0 0 12-1',
};
function Icon({ name }: { name: string }) { return <svg viewBox="0 0 24 24" fill={name === 'play' ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={iconPaths[name] ?? iconPaths.arrow} /></svg>; }
function Button({ id, group = 'actions', children, onClick, disabled = false, primary = false, selected = false, className = '', onFocus }: { id: string; group?: string; children: ReactNode; onClick(): void; disabled?: boolean; primary?: boolean; selected?: boolean; className?: string; onFocus?(): void }) {
  return <button type="button" data-focus={id} data-group={group} tabIndex={-1} className={`control ${primary ? 'primary' : ''} ${selected ? 'selected' : ''} ${className}`} disabled={disabled} onClick={onClick} onFocus={onFocus}>{children}</button>;
}
function Metadata({ item }: { item: Card }) {
  return <div className="metadata"><span>{item.isTv ? '剧集' : '电影'}</span>{item.year && <span>{item.year}</span>}{item.category && <span>{item.category}</span>}{item.rating && <span className="rating">{item.ratingSource} {item.rating}</span>}</div>;
}
function Scene({ item }: { item: Card | null }) {
  const [loaded, setLoaded] = useState('');
  const candidate = item?.backdrop ?? '';
  useEffect(() => { if (!candidate) setLoaded(''); }, [candidate]);
  return <div className="scene" aria-hidden="true">
    <div className="atmosphere" />
    {loaded && <img className="scene-art" src={loaded} alt="" />}
    {candidate && candidate !== loaded && <img className="scene-probe" src={candidate} alt="" onLoad={event => { const image = event.currentTarget; if (image.naturalWidth >= 720 && image.naturalWidth / image.naturalHeight >= 1.45) setLoaded(candidate); }} />}
    <div className="scene-shade" />
  </div>;
}
function Poster({ item, row, wide, act }: { item: Card; row: string; wide?: boolean; act: Action }) {
  const progress = item.durationMs > 0 ? Math.min(100, item.positionMs / item.durationMs * 100) : 0;
  return <button className={`poster ${wide ? 'wide' : ''}`} type="button" tabIndex={-1} data-focus={`card:${row}:${item.key}`} data-group={row}
    onFocus={() => act('media.focus', { key: item.key })} onClick={() => act('media.open', { key: item.key })} aria-label={`${item.title}${item.year ? `，${item.year}` : ''}`}>
    <span className="poster-art"><span className="poster-fallback"><Icon name="play" /><span>{item.title}</span></span>
      {(wide ? item.backdrop || item.poster : item.poster) && <img src={wide ? item.backdrop || item.poster : item.poster} alt="" loading="lazy" decoding="async" onError={event => { event.currentTarget.style.opacity = '0'; }} />}
      <span className="poster-shine" />{item.favorite && <span className="favorite-mark"><Icon name="check" /></span>}
      {progress > 0 && <span className="progress"><span style={{ width: `${progress}%` }} /></span>}
    </span><span className="poster-label">{item.title}</span><span className="poster-meta">{item.year || item.sourceName || (item.isTv ? '剧集' : '电影')}</span>
  </button>;
}
function Shelf({ id, title, items, act, wide }: { id: string; title: string; items: Card[]; act: Action; wide?: boolean }) {
  if (!items.length) return null;
  return <section className="shelf" aria-label={title}><h2>{title}<span>{items.length}</span></h2><div className={`rail ${wide ? 'wide-rail' : ''}`} data-scroll={`rail:${id}`}>{items.map(item => <Poster key={item.key} item={item} row={id} wide={wide} act={act} />)}</div></section>;
}
function Empty({ title, detail, children }: { title: string; detail: string; children?: ReactNode }) {
  return <section className="empty"><div className="empty-symbol"><Icon name="play" /></div><h2>{title}</h2><p>{detail}</p><div className="actions">{children}</div></section>;
}
function Pager({ state, act, episodes = false }: { state: Snapshot; act: Action; episodes?: boolean }) {
  const page = episodes ? state.episodePage : state.pageIndex, size = episodes ? 40 : state.pageSize;
  const total = episodes ? state.episodeTotal : state.total;
  const method = episodes ? 'detail.episodes' : 'catalog.page';
  return <div className="pager" data-group="pager">
    <Button id={`pager:${method}:previous`} group="pager" disabled={page === 0} onClick={() => act(method, { page: page - 1 })}><Icon name="back" />上一页</Button>
    <span>{total ? `${page * size + 1}–${Math.min((page + 1) * size, total)} / ${total}` : '暂无条目'}</span>
    <Button id={`pager:${method}:next`} group="pager" disabled={(page + 1) * size >= total} onClick={() => act(method, { page: page + 1 })}>下一页<Icon name="arrow" /></Button>
    {!episodes && state.canLoadMore && <Button id="pager:load" group="pager" disabled={state.busy} onClick={() => act('catalog.more')}>载入更多</Button>}
  </div>;
}
function Grid({ state, act }: { state: Snapshot; act: Action }) {
  return <><div className="poster-grid">{state.items.map(item => <Poster key={item.key} item={item} row="grid" act={act} />)}</div><Pager state={state} act={act} /></>;
}
function Home({ state, act }: { state: Snapshot; act: Action }) {
  const hero = state.scene ?? state.shelves.flatMap(shelf => shelf.items)[0];
  return <><section className="hero"><div className="hero-copy"><span className="eyebrow">CINEMA TV · 现在观看</span>
    <h1>{hero?.title ?? '影院，回到客厅。'}</h1>
    {hero ? <><Metadata item={hero} /><p className="synopsis">{hero.description || '选择作品，探索片源与剧集。'}</p></> : <p className="synopsis">让好故事占据整个屏幕。连接自己的影视服务，从一部想看的作品开始。</p>}
    <div className="actions">{hero ? <Button id="hero:open" group="hero" primary onClick={() => act('media.open', { key: hero.key })}><Icon name="play" />查看作品</Button> : <Button id="hero:connect" group="hero" primary onClick={() => act('native.open', { target: 'SETTINGS' })}>连接影视服务<Icon name="arrow" /></Button>}
      <Button id="hero:search" group="hero" onClick={() => act('navigation.go', { route: 'SEARCH' })}><Icon name="search" />搜索</Button></div>
    {!hero && <span className="privacy-note">不预置影视内容 · 观看记录保存在本机</span>}
  </div></section>
  <div className="shelves">{state.shelves.map(shelf => <Shelf key={shelf.id} {...shelf} act={act} wide={shelf.id === 'continue'} />)}
    {!state.shelves.length && state.connected && <Empty title="你的故事，从这里开始" detail="搜索作品并加入资料库，或在设置中配置元数据目录。" />}
  </div></>;
}
function Search({ state, act, native }: { state: Snapshot; act: Action; native: boolean }) {
  const [query, setQuery] = useState(state.query);
  useEffect(() => setQuery(state.query), [state.query]);
  return <><div className="page-heading"><span className="eyebrow">探索你的下一部好片</span><h1>搜索</h1></div>
    {native ? <Button id="search:input" group="search" className="search-box" onClick={() => act('search.input')}><Icon name="search" />{state.query || '片名、演员或关键词'}<span className="hint">按确认键输入</span></Button> :
      <form className="search-form" onSubmit={event => { event.preventDefault(); act('search.submit', { query }); }}><input autoComplete="off" data-focus="search:input" data-group="search" tabIndex={-1} aria-label="片名、演员或关键词" placeholder="片名、演员或关键词" value={query} onChange={event => setQuery(event.target.value)} onKeyDown={event => { if (event.key === 'ArrowDown') { event.preventDefault(); event.stopPropagation(); focusKey('search:submit'); } }} /><Button id="search:submit" group="search" primary disabled={!query.trim()} onClick={() => act('search.submit', { query })}><Icon name="search" />搜索</Button></form>}
    {state.history.length > 0 && <div className="chips history" data-scroll="history">{state.history.map((word, index) => <Button key={`${word}:${index}`} id={`history:${index}`} group="history" onClick={() => act('search.submit', { query: word })}>{word}</Button>)}</div>}
    <div className="chips filters" data-scroll="filters">
      {[['', '全部'], ['MOVIES', '电影'], ['SERIES', '剧集']].map(([type, title]) => <Button key={type} id={`filter:${type || 'all'}`} group="filters" selected={state.filters.type === type} onClick={() => act('search.filter', { ...state.filters, type })}>{title}</Button>)}
      <Button id="filter:year" group="filters" onClick={() => act('search.year')}>{state.filters.year || '全部年份'}</Button>
      <Button id="filter:rating" group="filters" selected={state.filters.minRating > 0} onClick={() => act('search.filter', { ...state.filters, minRating: state.filters.minRating ? 0 : 8 })}>{state.filters.minRating ? '评分 8 分及以上' : '全部评分'}</Button>
      <Button id="filter:chart" group="filters" selected={state.filters.topOnly} onClick={() => act('search.filter', { ...state.filters, topOnly: !state.filters.topOnly })}>授权 Top 250</Button>
    </div>
    <p className="status-line" role="status">{state.busy ? '正在检索，已收到的结果可直接查看' : state.query ? `“${state.query}” · ${state.total} 项结果` : '先搜索，再挑选你想看的故事'}</p>
    {state.items.length ? <Grid state={state} act={act} /> : <Empty title={state.busy ? '正在寻找' : state.query ? '暂未找到匹配作品' : '想看什么？'} detail={state.query ? '尝试其他关键词或筛选条件；已配置的片源与本地记录会共同参与搜索。' : '片名、演员、别名，都可以作为开始。'} />}
  </>;
}
function Detail({ state, act }: { state: Snapshot; act: Action }) {
  const item = state.selected;
  if (!item) return <Empty title="正在打开作品" detail="正在读取本机资料与已配置片源。" />;
  const resume = state.episodes.find(episode => episode.index === item.lastEpisode) ?? state.episodes[0];
  return <><section className="detail-hero"><div className="hero-copy"><span className="eyebrow">{item.sourceName || '作品详情'}</span><h1>{item.title}</h1><Metadata item={item} /><p className="synopsis">{item.description || '暂无简介'}</p>
    <div className="actions"><Button id="detail:play" group="detail-actions" primary disabled={!resume || state.busy} onClick={() => act('playback.open', { key: item.key, episodeId: resume?.id })}><Icon name="play" />{item.positionMs > 0 ? '继续观看' : '播放'}</Button>
      <Button id="detail:favorite" group="detail-actions" selected={item.favorite} onClick={() => act('library.favorite', { key: item.key, favorite: !item.favorite })}><Icon name={item.favorite ? 'check' : 'plus'} />{item.favorite ? '已收藏' : '加入收藏'}</Button>
      <Button id="detail:sources" group="detail-actions" disabled={state.busy} onClick={() => act('detail.sources')}>切换片源</Button>
      <Button id="detail:tools" group="detail-actions" onClick={() => act('native.open', { target: 'DETAIL' })}>分季与作品关联</Button></div>
    <div className="chips status-chips">{[['WANT_TO_WATCH', '想看'], ['WATCHING', '在看'], ['WATCHED', '看过'], ['DROPPED', '弃剧']].map(([status, title]) => <Button key={status} id={`status:${status}`} group="status" selected={item.status === status} onClick={() => act('library.status', { key: item.key, status: item.status === status ? '' : status })}>{title}</Button>)}</div>
    {state.notice && <p className="status-line" role="status">{state.notice}</p>}
  </div></section>
  {state.alternatives.length > 0 && <section className="detail-section"><h2>可用片源</h2><div className="chips">{state.alternatives.map(source => <Button key={source.key} id={`source:${source.key}`} group="sources" onClick={() => act('media.open', { key: source.key })}>{source.sourceName || source.title}</Button>)}</div></section>}
  <section className="detail-section"><div className="section-heading"><h2>{item.isTv ? '剧集' : '播放版本'}</h2><span>{state.episodeTotal ? `${state.episodeTotal} 项` : state.busy ? '正在解析片源' : '暂无可用直链'}</span></div>
    <div className="episodes">{state.episodes.map(episode => <Button key={episode.id} id={`episode:${episode.id}`} group="episodes" className="episode" onClick={() => act('playback.open', { key: item.key, episodeId: episode.id })}><span className="episode-number">{String(episode.index + 1).padStart(2, '0')}</span><span>{episode.label}</span><Icon name="play" />{episode.progress !== null && <span className="progress"><span style={{ width: `${Math.min(100, episode.progress * 100)}%` }} /></span>}</Button>)}</div>
    {state.episodeTotal > 40 && <Pager state={state} act={act} episodes />}
  </section></>;
}
function Live({ state, act }: { state: Snapshot; act: Action }) {
  return <><div className="page-heading"><span className="eyebrow">正在发生的世界</span><h1>直播</h1></div>
    <div className="chips" data-scroll="live-sources">{state.liveSources.map(source => <Button key={source.id} id={`live-source:${source.id}`} group="live-sources" selected={source.id === state.activeLiveSource} onClick={() => act('live.source', { id: source.id })}>{source.name}</Button>)}</div>
    {!state.liveSources.length ? <Empty title="连接你的直播订阅" detail="添加有权访问的 M3U 与 XMLTV 服务，频道和节目单会在这里显示。"><Button id="live:setup" primary onClick={() => act('native.open', { target: 'SETTINGS' })}>设置直播来源</Button></Empty> :
      <div className="live-layout"><section className="channels">{state.channels.map(channel => <Button key={channel.id} id={`channel:${channel.id}`} group="channels" className="channel" selected={state.activeChannel === channel.id} onFocus={() => act('live.focus', { id: channel.id })} onClick={() => act('live.play', { id: channel.id })}><span className="channel-logo">{channel.logo ? <img src={channel.logo} alt="" /> : <Icon name="play" />}</span><span><strong>{channel.name}</strong><small>{channel.group}</small></span><Icon name="play" /></Button>)}<Pager state={state} act={act} /></section>
      <aside className="glass epg"><span className="eyebrow">节目指南</span><h2>{state.channels.find(channel => channel.id === state.activeChannel)?.name || '选择频道'}</h2>{state.programs.length ? state.programs.map((program, index) => <div className="program" key={`${program.start}:${index}`}><time>{new Date(program.start).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}</time><span>{program.title}</span></div>) : <p>暂无节目单信息</p>}</aside></div>}
  </>;
}
function Settings({ state, act }: { state: Snapshot; act: Action }) {
  return <><div className="page-heading"><span className="eyebrow">属于你的大屏体验</span><h1>设置</h1></div><div className="settings-grid">
    <section className="glass settings-panel"><span className="eyebrow">画面与交互</span><h2>液态玻璃</h2><p>悬浮、透光与清晰层次。仅在界面背景上呈现材质，不对播放画面做网页采样。</p><div className="chips">{state.qualityOptions.map(option => <Button key={option.id} id={`quality:${option.id}`} group="quality" selected={state.quality === option.id} onClick={() => act('settings.quality', { quality: option.id })}>{option.title}</Button>)}</div>{state.lowMemory && <p className="status-line">当前设备使用轻量材质，保留已选偏好。</p>}
      <Button id="settings:motion" group="settings-motion" className="setting-row" selected={state.reducedMotion} onClick={() => act('settings.motion', { reduced: !state.reducedMotion })}><span><strong>减少动态效果</strong><small>停止场景转场与卡片缩放，保留焦点轮廓</small></span><span className={`switch ${state.reducedMotion ? 'on' : ''}`} aria-label={state.reducedMotion ? '已开启' : '已关闭'} /></Button>
    </section>
    <section className="glass settings-panel"><span className="eyebrow">服务与资料</span><h2>连接你的内容</h2><p>{state.connected ? state.authorized ? '影视服务已连接' : '服务已配置，请完成验证' : '尚未配置影视服务'}</p>
      {[['connection', '连接与账号', '服务地址、验证与元数据目录'], ['sources', '片源与直播', '管理影视来源、M3U 与 XMLTV'], ['updates', '版本与更新', `${state.version} · ${state.channel}`], ['advanced', '高级与开发者选项', '推荐依据、资料管理及已存在的实验功能']].map(([id, title, note]) => <Button key={id} id={`settings:${id}`} group="settings-links" className="setting-row" onClick={() => act('native.open', { target: 'SETTINGS' })}><span><strong>{title}</strong><small>{note}</small></span><Icon name="arrow" /></Button>)}
    </section></div><p className="footnote">影视资料与观看进度仍由原生数据层管理。敏感设置与系统安装流程使用原生界面。</p></>;
}

export function App() {
  const [state, setState] = useState<Snapshot | null>(null);
  const [error, setError] = useState('');
  const [native, setNative] = useState(true);
  const current = useRef<Snapshot | null>(null);
  const platform = useRef<Platform | null>(null);
  const mounted = useRef(true);
  const apply = useCallback((next: Snapshot) => {
    if (!mounted.current || next.protocol !== 1 || (current.current && next.revision < current.current.revision)) return;
    current.current = next; setState(next);
  }, []);
  const act = useCallback<Action>((method, params = {}) => {
    const client = platform.current; if (!client) return;
    void (async () => {
      const previous = current.current;
      if (previous && ['navigation.go', 'media.open', 'playback.open', 'native.open', 'live.play'].includes(method)) await client.call('navigation.bookmark', { bookmark: captureBookmark(previous.routeKey) });
      const reply = await client.call(method, params);
      if (reply.state) apply(reply.state);
    })().catch((failure: Error) => { if (mounted.current) setError(failure.message === 'TIMEOUT' ? '操作未确认，请先检查当前状态，再决定是否重试。' : failure.message === 'CONFLICT' ? '资料已更新，请重新选择。' : '操作暂未完成，请重试。'); });
  }, [apply]);
  useEffect(() => {
    mounted.current = true; let canceled = false; let cleanup = () => {};
    void createPlatform().then(async client => {
      if (canceled) { client.close(); return; }
      platform.current = client; setNative(client.native);
      const unsubscribe = client.subscribe((name, payload) => {
        if (name === 'state') apply(payload as Snapshot);
        if (name === 'host.visibility') document.documentElement.dataset.paused = (payload as { visible: boolean }).visible ? 'false' : 'true';
      });
      cleanup = () => { unsubscribe(); client.close(); platform.current = null; };
      const reply = await client.call('app.ready', { protocol: 1 });
      if (reply.state) apply(reply.state);
    }).catch((failure: Error) => { if (!canceled) setError(failure.message); });
    return () => { canceled = true; mounted.current = false; cleanup(); };
  }, [apply]);
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const save = () => { clearTimeout(timer); timer = setTimeout(() => { const snapshot = current.current; if (snapshot) act('navigation.bookmark', { bookmark: captureBookmark(snapshot.routeKey) }); }, 140); };
    const uninstall = installSpatialInput(() => act('navigation.back'), save);
    document.addEventListener('scroll', save, true);
    return () => { clearTimeout(timer); uninstall(); document.removeEventListener('scroll', save, true); };
  }, [act]);
  useLayoutEffect(() => {
    if (!state) return;
    const frame = requestAnimationFrame(() => restoreBookmark(state.bookmark, state.route === 'DETAIL' ? 'detail:play' : state.route === 'SEARCH' ? 'search:input' : `nav:${state.route === 'EXPLORE' ? 'HOME' : state.route}`));
    return () => cancelAnimationFrame(frame);
  }, [state?.routeKey, state?.pageIndex, state?.episodePage, state?.libraryFilter]);
  useEffect(() => { if (!error) return; const timer = setTimeout(() => setError(''), 5500); return () => clearTimeout(timer); }, [error]);
  if (!state) return <main className="boot"><span className="wordmark">cinema<span>tv</span></span><h1>{error || '正在打开 CinemaTV'}</h1><p>你的大屏影院</p></main>;
  const title = state.route === 'MOVIES' ? '电影' : state.route === 'SERIES' ? '剧集' : '资料库';
  return <div className="tv-app" data-quality={state.lowMemory ? 'BASIC' : state.quality} data-reduced-motion={state.reducedMotion}>
    <Scene item={state.route === 'DETAIL' ? state.selected : state.scene} />
    <header className="topbar"><span className="wordmark" aria-label="CinemaTV">cinema<span>tv</span></span>
      {state.route === 'DETAIL' ? <Button id="detail:back" group="nav" className="glass back-button" onClick={() => act('navigation.back')}><Icon name="back" />返回</Button> :
        <nav className="glass navigation" aria-label="主导航">{navigation.map(([route, label]) => <Button key={route} id={`nav:${route}`} group="nav" className="nav-item" selected={state.route === route || (route === 'HOME' && state.route === 'EXPLORE')} onClick={() => act('navigation.go', { route })}>{label}</Button>)}</nav>}
      <div className="tools"><Button id="nav:SEARCH" group="nav" className="glass icon-button" selected={state.route === 'SEARCH'} onClick={() => act('navigation.go', { route: 'SEARCH' })}><Icon name="search" /><span className="sr-only">搜索</span></Button><Button id="nav:SETTINGS" group="nav" className="glass icon-button" selected={state.route === 'SETTINGS'} onClick={() => act('navigation.go', { route: 'SETTINGS' })}><Icon name="settings" /><span className="sr-only">设置</span></Button></div>
    </header>
    <main key={state.routeKey} className={`content route-${state.route.toLowerCase()}`} data-scroll="page" aria-busy={state.busy}>
      {state.route === 'HOME' || state.route === 'EXPLORE' ? <Home state={state} act={act} /> : state.route === 'SEARCH' ? <Search state={state} act={act} native={native} /> : state.route === 'DETAIL' ? <Detail state={state} act={act} /> : state.route === 'LIVE' ? <Live state={state} act={act} /> : state.route === 'SETTINGS' ? <Settings state={state} act={act} /> : state.route === 'PLAYER' ? <Empty title="正在进入播放器" detail="正在交给原生播放引擎" /> :
        <><div className="page-heading"><span className="eyebrow">{state.route === 'LIBRARY' ? '收藏故事，继续旅程' : '好故事，值得大屏观看'}</span><h1>{title}</h1></div>
          {state.route === 'LIBRARY' && <div className="chips" data-scroll="library-tabs">{libraryTabs.map(([filter, label]) => <Button key={filter} id={`library:${filter}`} group="library-tabs" selected={state.libraryFilter === filter} onClick={() => act('library.filter', { filter })}>{label}</Button>)}</div>}
          {state.items.length ? <Grid state={state} act={act} /> : <Empty title={state.busy ? '正在整理内容' : '这里，留给喜欢的作品'} detail={state.notice || '搜索并收藏作品，或在设置中连接自己的目录。'}><Button id="empty:search" primary onClick={() => act('navigation.go', { route: 'SEARCH' })}>去搜索<Icon name="arrow" /></Button></Empty>}
        </>}
      {state.notice && !['HOME', 'DETAIL', 'SEARCH'].includes(state.route) && <p className="footnote">{state.notice}</p>}
      {state.attribution && <p className="attribution">{state.attribution}</p>}
      <div className="bottom-safe" />
    </main>
    {state.busy && <div className="loading-line" aria-label="正在加载" />}
    {(error || state.message) && <div className="toast glass" role="status">{error || state.message}</div>}
    {!native && <span className="preview-label">开发预览 · 合成内容，不含片源</span>}
  </div>;
}
