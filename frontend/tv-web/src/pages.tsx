import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useVisibleClock } from './useVisibleClock';
import { focusKey } from './focus';
import { Button, Empty, Grid, Icon, Metadata, Pager, Poster, Shelf, type Action } from './components';
import { VirtualRail } from './VirtualRail';
import type { Snapshot } from './types';

const statuses = [['WANT_TO_WATCH', '想看'], ['WATCHING', '在看'], ['WATCHED', '看过'], ['DROPPED', '弃剧']];
const libraryTabs = [['ALL', '全部'], ['HISTORY', '继续观看'], ['FAVORITES', '收藏'], ...statuses];

export function Home({ state, act }: { state: Snapshot; act: Action }) {
  const hero = state.scene ?? state.shelves.flatMap(shelf => shelf.items)[0];
  return <><section className="hero"><div className="hero-copy"><span className="eyebrow">CINEMA TV · 现在观看</span>
    <h1>{hero?.title ?? '影院，回到客厅。'}</h1>
    {hero ? <><Metadata item={hero} /><p className="synopsis">{hero.description || '走进一个故事，发现下一段旅程。'}</p></> : <p className="synopsis">让好故事占据整个屏幕。连接你的影视服务，从一部想看的作品开始。</p>}
    <div className="actions" data-focus-layout="row">{hero ? <Button id="hero:open" group="hero" primary onClick={() => act('media.open', { key: hero.key })}><Icon name="play" />{hero.positionMs > 0 ? '继续观看' : '查看作品'}</Button> : <Button id="hero:connect" group="hero" primary onClick={() => act('native.open', { target: 'SETTINGS', section: 'connection' })}>连接影视服务<Icon name="arrow" /></Button>}
      <Button id="hero:discover" group="hero" onClick={() => act('navigation.go', { route: 'EXPLORE' })}><Icon name="discover" />随心发现</Button>
      <Button id="hero:search" group="hero" className="hero-search" onClick={() => act('navigation.go', { route: 'SEARCH' })}><Icon name="search" /><span className="sr-only">搜索</span></Button>
    </div>
    {!hero && <span className="privacy-note">观看记录与资料库保存在此设备</span>}
  </div><span className="hero-scroll-cue" aria-hidden="true"><Icon name="down" /></span></section>
  <div className="shelves">{state.shelves.map(shelf => <Shelf key={shelf.id} {...shelf} act={act} wide={shelf.id === 'continue'} bookmark={state.bookmark} />)}
    {!state.shelves.length && state.connected && <Empty title={state.busy ? '正在准备你的影院' : '你的故事，从这里开始'} detail="搜索作品并加入资料库，或连接元数据目录，发现更多好故事。">
      <Button id="home:refresh" onClick={() => act('catalog.refresh')} disabled={state.busy}><Icon name="refresh" />刷新目录</Button>
    </Empty>}
  </div>{state.notice && <p className="footnote">{state.notice}</p>}</>;
}

function CatalogFilters({ state, act }: { state: Snapshot; act: Action }) {
  const filters = state.catalogFilters;
  const years = ['', ...state.catalogYears];
  return <div className="chips catalog-filters" data-scroll="catalog-filters" data-focus-layout="row" aria-label="目录筛选">
    <Button id="catalog:all" group="catalog-filters" selected={!filters.category && !filters.year && !filters.rated}
      onClick={() => act('catalog.filter', { category: '', year: '', rated: false })}>全部</Button>
    {state.categories.map(category => <Button key={category} id={`catalog:category:${category}`} group="catalog-filters" selected={filters.category === category}
      onClick={() => act('catalog.filter', { ...filters, category: filters.category === category ? '' : category })}>{category}</Button>)}
    <Button id="catalog:year" group="catalog-filters" selected={Boolean(filters.year)} label={`年份：${filters.year || '全部'}，按确认键切换`}
      onClick={() => act('catalog.filter', { ...filters, year: years[(years.indexOf(filters.year) + 1) % years.length] })}>{filters.year || '全部年份'}<Icon name="down" /></Button>
    <Button id="catalog:rated" group="catalog-filters" selected={filters.rated} onClick={() => act('catalog.filter', { ...filters, rated: !filters.rated })}>评分优先</Button>
    <Button id="catalog:refresh" group="catalog-filters" disabled={state.busy} onClick={() => act('catalog.refresh')}><Icon name="refresh" />刷新</Button>
  </div>;
}

export function Catalog({ state, act }: { state: Snapshot; act: Action }) {
  const library = state.route === 'LIBRARY';
  const series = state.route === 'SERIES';
  return <><div className="page-heading heading-with-action"><div><span className="eyebrow">{library ? '收藏故事，继续旅程' : series ? '好故事，慢慢看' : '为大屏而生'}</span><h1>{library ? '资料库' : series ? '剧集' : '电影'}</h1></div>
    {library && <Button id="library:manage" group="library-tools" onClick={() => act('native.open', { target: 'LIBRARY' })}>管理资料<Icon name="arrow" /></Button>}
    {!library && <span className="content-count">{state.total} 部作品</span>}
  </div>
    {library ? <div className="chips library-tabs" data-scroll="library-tabs" data-focus-layout="row" aria-label="资料库分类">
      {libraryTabs.map(([filter, label]) => <Button key={filter} id={`library:${filter}`} group="library-tabs" selected={state.libraryFilter === filter} onClick={() => act('library.filter', { filter })}>{label}</Button>)}
    </div> : <CatalogFilters state={state} act={act} />}
    {state.items.length ? <Grid state={state} act={act} /> : <Empty title={state.busy ? '正在整理内容' : library ? '这里，留给喜欢的作品' : '暂无符合条件的作品'}
      detail={library ? '收藏一部作品，记录观看状态，让下一次观看从这里开始。' : state.notice || '调整筛选条件，或连接元数据目录后刷新。'}>
      <Button id="empty:search" primary onClick={() => act('navigation.go', { route: 'SEARCH' })}>去搜索<Icon name="search" /></Button>
      {!library && <Button id="empty:reset" onClick={() => act('catalog.filter', { category: '', year: '', rated: false })}>重置筛选</Button>}
    </Empty>}
    <Pager state={state} act={act} />
    {library && state.hasHistory && <div className="library-footer"><p>观看进度会在下次打开作品时继续。</p><Button id="library:clear-history" group="library-tools" onClick={() => act('library.clearHistory')}>清除观看记录</Button></div>}
    {library && state.quarantinedProgressCount > 0 && <p className="footnote">有 {state.quarantinedProgressCount} 条旧观看记录需要核对作品信息，可在「管理资料」中查看。</p>}
  </>;
}

export function Explore({ state, act }: { state: Snapshot; act: Action }) {
  const bookmarked = state.bookmark?.focus ?? '';
  const [activeKey, setActiveKey] = useState(() => state.items.find(item => `card:discover:${item.key}` === bookmarked)?.key ?? state.items[0]?.key ?? '');
  const current = state.items.find(item => item.key === activeKey) ?? state.items[0];
  const active = state.items.findIndex(item => item.key === current?.key);
  return <><div className="page-heading discovery-heading"><span className="eyebrow">下一段旅程，随心发现</span><h1>让故事找到你。</h1><p>左右探索 · 按确认键走进故事</p></div>
    {current ? <><section className="coverflow" aria-label="随心发现">
      <VirtualRail id="discover" items={state.items} centered keyFor={item => `card:discover:${item.key}`} initialFocus={state.bookmark?.focus}
        renderItem={(item, index) => <Poster item={item} row="discover" act={act} index={state.pageIndex * state.pageSize + index} total={state.total}
          side={index < active ? 'before' : index > active ? 'after' : 'active'} onFocused={() => setActiveKey(item.key)} />} />
    </section><div className="discovery-caption"><h2>{current.title}</h2><Metadata item={current} /></div></> :
      <Empty title={state.busy ? '正在准备好故事' : '好故事，正等着开始'} detail={state.notice || '连接影视服务与元数据目录，浏览电影和剧集。'}>
        <Button id="discover:connect" primary onClick={() => act('native.open', { target: 'SETTINGS', section: 'connection' })}>连接影视服务<Icon name="arrow" /></Button>
        <Button id="discover:refresh" disabled={state.busy} onClick={() => act('catalog.refresh')}><Icon name="refresh" />刷新目录</Button>
      </Empty>}
    <Pager state={state} act={act} />
  </>;
}

export function Search({ state, act, native }: { state: Snapshot; act: Action; native: boolean }) {
  const [query, setQuery] = useState(state.query);
  useEffect(() => setQuery(state.query), [state.query]);
  return <><div className="page-heading"><span className="eyebrow">探索你的下一部好片</span><h1>搜索</h1></div>
    {native ? <Button id="search:input" group="search" className="search-box" onClick={() => act('search.input')}><Icon name="search" />{state.query || '片名、演员或关键词'}<span className="hint">按确认键输入</span></Button> :
      <form className="search-form" data-focus-layout="row" onSubmit={event => { event.preventDefault(); if (query.trim()) act('search.submit', { query }); }}>
        <input autoComplete="off" data-focus="search:input" data-group="search" tabIndex={-1} maxLength={120} aria-label="片名、演员或关键词" placeholder="片名、演员或关键词" value={query} onChange={event => setQuery(event.target.value)}
          onKeyDown={event => { if (event.key === 'ArrowDown') { event.preventDefault(); event.stopPropagation(); if (!focusKey('search:submit')) focusKey('filter:all'); } }} />
        <Button id="search:submit" group="search" primary disabled={!query.trim()} onClick={() => act('search.submit', { query })}><Icon name="search" />搜索</Button>
      </form>}
    {state.history.length > 0 && <div className="chips history" data-scroll="history" data-focus-layout="row" aria-label="最近搜索">{state.history.map((word, index) => <Button key={`${word}:${index}`} id={`history:${index}`} group="history" onClick={() => act('search.submit', { query: word })}><Icon name="clock" />{word}</Button>)}</div>}
    <div className="chips filters" data-scroll="filters" data-focus-layout="row" aria-label="搜索筛选">
      {[['', '全部'], ['MOVIES', '电影'], ['SERIES', '剧集']].map(([type, title]) => <Button key={type} id={`filter:${type || 'all'}`} group="filters" selected={state.filters.type === type} onClick={() => act('search.filter', { ...state.filters, type })}>{title}</Button>)}
      <Button id="filter:year" group="filters" selected={Boolean(state.filters.year)} label={`年份：${state.filters.year || '全部'}，按确认键切换`} onClick={() => act('search.year')}>{state.filters.year || '全部年份'}<Icon name="down" /></Button>
      <Button id="filter:rating" group="filters" selected={state.filters.minRating > 0 && !state.filters.ratingSource} onClick={() => act('search.filter', { ...state.filters, minRating: state.filters.minRating && !state.filters.ratingSource ? 0 : 8, ratingSource: '', topOnly: false })}>{state.filters.minRating && !state.filters.ratingSource ? '评分 8 分及以上' : '全部来源评分'}</Button>
      <Button id="filter:tmdb" group="filters" selected={state.filters.ratingSource === 'TMDB'} onClick={() => act('search.filter', { ...state.filters, minRating: 8, ratingSource: 'TMDB', topOnly: false })}>TMDB ≥ 8</Button>
      <Button id="filter:douban" group="filters" selected={state.filters.ratingSource === 'Douban' && !state.filters.topOnly} onClick={() => act('search.filter', { ...state.filters, minRating: 8, ratingSource: 'Douban', topOnly: false })}>豆瓣 ≥ 8</Button>
      <Button id="filter:chart" group="filters" selected={state.filters.topOnly} onClick={() => act('search.filter', { ...state.filters, minRating: 0, ratingSource: state.filters.topOnly ? '' : 'Douban', topOnly: !state.filters.topOnly })}>豆瓣 Top 250</Button>
      <Button id="filter:reset" group="filters" onClick={() => act('search.filter', { type: '', year: '', minRating: 0, ratingSource: '', topOnly: false })}>重置筛选</Button>
    </div>
    {state.tags.length > 0 && <div className="chips tags" data-scroll="tags" data-focus-layout="row" aria-label="豆瓣分类">
      {state.tags.map(tag => <Button key={tag} id={`tag:${tag}`} group="tags" selected={state.filters.tag === tag}
        onClick={() => act('search.filter', { ...state.filters, tag: state.filters.tag === tag ? '' : tag, topOnly: false, minRating: 0, ratingSource: '' })}>{tag}</Button>)}
    </div>}
    {state.filters.ratingSource === 'Douban' && <p className="footnote">豆瓣筛选仅显示有效授权目录；未配置时不会替代为其他评分。</p>}
    <p className="status-line" role="status">{state.busy ? '正在检索，已收到的结果可直接查看' : state.query ? `“${state.query}” · ${state.total} 项结果` : '先搜索，再挑选你想看的故事'}</p>
    {state.items.length ? <Grid state={state} act={act} /> : <Empty title={state.busy ? '正在寻找' : state.query ? '暂未找到匹配作品' : '想看什么？'}
      detail={state.query ? '尝试其他关键词或筛选条件；也可以检查影视服务与片源的连接。' : '片名、演员、别名，都可以作为开始。'} />}
    <Pager state={state} act={act} />
    {state.notice && <p className="footnote">{state.notice}</p>}
  </>;
}

export function Detail({ state, act }: { state: Snapshot; act: Action }) {
  const item = state.selected;
  if (!item) return <Empty title="正在打开作品" detail="正在读取作品资料。" />;
  const resume = state.resumeEpisode;
  return <><section className="detail-hero"><div className="hero-copy"><span className="eyebrow">{item.sourceName || '作品详情'}</span><h1>{item.title}</h1><Metadata item={item} /><p className="synopsis">{item.description || '暂无简介'}</p>
    {state.recommendationReason && <p className="recommendation-reason">{state.recommendationReason}</p>}
    <div className="actions" data-focus-layout="row"><Button id="detail:play" group="detail-actions" primary disabled={!resume} onClick={() => act('playback.open', { key: item.key, episodeId: resume?.id, action: resume?.completed ? 'RESTART' : 'RESUME' })}><Icon name="play" />{resume?.completed ? '重新播放' : item.positionMs > 0 ? '继续观看' : '播放'}</Button>
      {resume && !resume.completed && item.positionMs > 0 && <Button id="detail:restart" group="detail-actions" onClick={() => act('playback.open', { key: item.key, episodeId: resume.id, action: 'RESTART' })}>从头播放</Button>}
      <Button id="detail:favorite" group="detail-actions" selected={item.favorite} onClick={() => act('library.favorite', { key: item.key, favorite: !item.favorite })}><Icon name={item.favorite ? 'check' : 'plus'} />{item.favorite ? '已收藏' : '加入收藏'}</Button>
      <Button id="detail:sources" group="detail-actions" disabled={state.busy} onClick={() => act('detail.sources')}>切换片源</Button>
      <Button id="detail:tools" group="detail-actions" onClick={() => act('native.open', { target: 'DETAIL' })}>分季与作品整理</Button></div>
    {resume && item.positionMs > 0 && <p className="resume-note"><Icon name="clock" />{resume.label} · 已观看 {Math.floor(item.positionMs / 60000)} 分钟</p>}
    <div className="chips status-chips" data-focus-layout="row" aria-label="观看状态">{statuses.map(([status, title]) => <Button key={status} id={`status:${status}`} group="status" selected={item.status === status} onClick={() => act('library.status', { key: item.key, status: item.status === status ? '' : status })}>{title}</Button>)}</div>
    {state.notice && <p className="status-line" role="status">{state.notice}</p>}
  </div></section>
    {state.alternatives.length > 0 && <section className="detail-section"><h2>可用片源</h2><div className="chips" data-scroll="detail-sources" data-focus-layout="row">
      {state.alternatives.map(source => <Button key={source.key} id={`source:${source.key}`} group="sources" selected={item.key === source.key} onClick={() => act('media.open', { key: source.key })}>{source.sourceName || source.title}</Button>)}
    </div></section>}
    <section className="detail-section"><div className="section-heading"><h2>{item.isTv ? '剧集' : '播放版本'}</h2><span>{state.episodeTotal ? `${state.episodeTotal} 项` : state.busy ? '正在查找片源' : '暂无可播放版本'}</span></div>
      {state.episodes.length > 0 ? <div className="episodes" data-focus-layout="grid">{state.episodes.map(episode => <Button key={episode.id} id={`episode:${episode.id}`} group="episodes" className="episode" selected={episode.id === resume?.id && item.positionMs > 0} label={`${episode.label}${episode.progress !== null ? `，已观看 ${Math.round(Math.min(1, Math.max(0, episode.progress)) * 100)}%` : ''}`}
        onClick={() => act('playback.open', { key: item.key, episodeId: episode.id, action: episode.completed ? 'RESTART' : 'RESUME' })}><span className="episode-number">{String(episode.index + 1).padStart(2, '0')}</span><span>{episode.label}</span><Icon name="play" />
        {episode.progress !== null && <span className="progress"><span style={{ width: `${Math.min(100, Math.max(0, episode.progress * 100))}%` }} /></span>}</Button>)}</div> :
        <div className="episode-empty"><p>{state.busy ? '正在查找可用版本，请稍候。' : '可切换片源，或检查服务连接后重试。'}</p>
          <Button id="detail:retry" disabled={state.busy} onClick={() => act('detail.sources')}><Icon name="refresh" />查找片源</Button></div>}
      <Pager state={state} act={act} episodes />
    </section>
    {item.description && <section className="detail-section about-work"><h2>关于这部{item.isTv ? '剧集' : '电影'}</h2><p tabIndex={-1} data-focus="detail:description" data-focus-scroll="true" data-group="description">{item.description}</p></section>}
    {item.attribution && <p className="attribution">{item.attribution}</p>}
  </>;
}

export function Live({ state, act }: { state: Snapshot; act: Action }) {
  const now = useVisibleClock();
  const programs = state.programs.filter(program => Number.isFinite(program.start) && Number.isFinite(program.stop) && program.start < program.stop && program.stop > now)
    .sort((a, b) => a.start - b.start || a.stop - b.stop).slice(0, 48);
  const focusedProgram = useRef('');
  useLayoutEffect(() => {
    const active = document.activeElement as HTMLElement | null;
    if (active?.dataset.focus && active.isConnected) return;
    if (!focusedProgram.current || programs.some(program => program.id === focusedProgram.current)) return;
    if (programs[0]) focusKey(`program:${programs[0].id}`);
    else focusKey(`channel:${state.activeChannel}`);
  }, [now, state.programs, state.activeChannel]);
  return <><div className="page-heading"><span className="eyebrow">正在发生的世界</span><h1>直播</h1></div>
    <div className="chips" data-scroll="live-sources" data-focus-layout="row" aria-label="直播订阅">{state.liveSources.map(source => <Button key={source.id} id={`live-source:${source.id}`} group="live-sources" selected={source.id === state.activeLiveSource} onClick={() => act('live.source', { id: source.id })}>{source.name}</Button>)}</div>
    {state.liveSources.length > 0 && <div className="chips" data-scroll="live-groups" data-focus-layout="row" aria-label="频道分组">
      <Button id="live-group:all" group="live-groups" selected={!state.liveGroup} onClick={() => act('live.group', { id: '' })}>全部频道</Button>
      {state.liveGroups.map(group => <Button key={group.id} id={`live-group:${group.id}`} group="live-groups" selected={state.liveGroup === group.id} onClick={() => act('live.group', { id: group.id })}>{group.name}</Button>)}
    </div>}
    {!state.liveSources.length ? <Empty title="连接你的直播订阅" detail="添加有权访问的直播订阅，频道和节目单会在这里显示。"><Button id="live:setup" primary onClick={() => act('native.open', { target: 'SETTINGS', section: 'sources' })}>设置直播来源<Icon name="arrow" /></Button></Empty> :
      <div className="live-layout"><section className="channels" aria-label="频道" data-focus-layout="column">{state.channels.length ? state.channels.map(channel => <Button key={channel.id} id={`channel:${channel.id}`} group="channels" className="channel" navRight={programs[0] ? `program:${programs[0].id}` : undefined} selected={state.activeChannel === channel.id} onFocus={() => act('live.focus', { id: channel.id })} onClick={() => act('live.play', { id: channel.id })}>
        <span className="channel-logo">{channel.logo ? <img key={channel.logo} src={channel.logo} alt="" loading="lazy" onError={event => { event.currentTarget.style.opacity = '0'; }} /> : <Icon name="play" />}</span><span><strong>{channel.name}</strong><small>{channel.group}</small></span><Icon name="play" />
      </Button>) : <Empty title={state.busy ? '正在加载频道' : '暂无频道'} detail="选择其他订阅，或在设置中检查直播来源。" />}<Pager state={state} act={act} /></section>
        <aside className="glass epg" aria-label="节目指南" data-focus-layout="column"><span className="eyebrow">节目指南</span><h2>{state.channels.find(channel => channel.id === state.activeChannel)?.name || '选择频道'}</h2>
          {programs.length ? programs.map(program => <article tabIndex={-1} data-focus={`program:${program.id}`} onFocus={() => { focusedProgram.current = program.id; }} data-group="epg" data-nav-left={`channel:${state.activeChannel}`} className={`program ${program.start <= now && program.stop > now ? 'on-air' : ''}`} key={program.id}>
            <time dateTime={new Date(program.start).toISOString()}>{new Date(program.start).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}</time><span>{program.title}{program.start <= now && program.stop > now && <small>正在播出</small>}</span>
          </article>) : <p>暂无节目单信息</p>}
        </aside>
      </div>}
  </>;
}

export function Settings({ state, act }: { state: Snapshot; act: Action }) {
  return <><div className="page-heading"><span className="eyebrow">属于你的大屏体验</span><h1>设置</h1></div><div className="settings-grid">
    <section className="glass settings-panel"><span className="eyebrow">画面与交互</span><h2>液态玻璃</h2><p>通透的材质，轻盈的层次。为你的大屏选择合适的外观。</p><div className="glass-material-preview" aria-hidden="true"><div className="material-orbit" /><div className="material-sample"><Icon name="play" /></div><span>CinemaTV</span></div>
      <div className="chips" data-focus-layout="row" aria-label="玻璃画质">{state.qualityOptions.map(option => <Button key={option.id} id={`quality:${option.id}`} group="quality" selected={state.quality === option.id} onClick={() => act('settings.quality', { quality: option.id })}>{option.title}</Button>)}</div>
      {state.effectsLimited && <p className="status-line">已为此设备启用轻量材质，保留你的外观偏好。</p>}
      <Button id="settings:motion" group="settings-motion" className="setting-row" selected={state.reducedMotion} onClick={() => act('settings.motion', { reduced: !state.reducedMotion })}><span><strong>减少动态效果</strong><small>简化转场与卡片动效，保留清晰的焦点提示</small></span><span className={`switch ${state.reducedMotion ? 'on' : ''}`} aria-hidden="true" /></Button>
    </section>
    <section className="glass settings-panel"><span className="eyebrow">服务与资料</span><h2>连接你的内容</h2><p className="connection-status"><span className={state.connected && state.authorized ? 'connected' : ''} />{state.connected ? state.authorized ? '影视服务已连接' : '服务已配置，请完成验证' : '尚未配置影视服务'}</p>
      {[['connection', '连接与账号', '影视服务、账号与元数据目录'], ['sources', '片源与直播', '管理影视来源与直播订阅'], ['subscriptions', '源列表订阅', '发现、导入与更新服务器订阅'], ['updates', '版本与更新', `${state.version} · ${state.channel}`], ['recommendations', '本地推荐', '查看推荐信号与偏好'], ['library', '资料管理', '核对已保留的旧观看记录'], ['developer', '开发者选项', 'Dev 实验功能与本机账号设置']].map(([id, title, note]) => <Button key={id} id={`settings:${id}`} group="settings-links" className="setting-row" onClick={() => act('native.open', { target: 'SETTINGS', section: id })}><span><strong>{title}</strong><small>{note}</small></span><Icon name="arrow" /></Button>)}
    </section></div><p className="footnote">观看记录保存在此设备。外观与动态效果可随时调整。</p></>;
}
