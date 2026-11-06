import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { Bookmark, Card, Snapshot } from './types';
import { VirtualRail } from './VirtualRail';

export type Action = (method: string, params?: Record<string, unknown>) => void;
const paths: Record<string, string> = {
  play: 'M8 5l11 7-11 7z', search: 'M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  back: 'M15 5l-7 7 7 7', plus: 'M12 5v14M5 12h14', check: 'M5 12l4 4L20 5',
  settings: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M12 2v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M5 19l2-2M17 7l2-2',
  arrow: 'M5 12h14m-6-6 6 6-6 6', refresh: 'M20 7v5h-5M4 17v-5h5M6 7a7 7 0 0 1 12-1l2 6M4 12l2 6a7 7 0 0 0 12-1',
  discover: 'M16 8l-3 5-5 3 3-5zM22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  clock: 'M12 6v6l4 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  down: 'M5 9l7 7 7-7', info: 'M12 11v6M12 7v.1M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
};
export function Icon({ name }: { name: string }) {
  return <svg viewBox="0 0 24 24" fill={name === 'play' ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name] ?? paths.arrow} /></svg>;
}
export function Button({ id, group = 'actions', children, onClick, disabled = false, primary = false, selected, className = '', onFocus, label, current, navDown, navRight }: {
  id: string; group?: string; children: ReactNode; onClick(): void; disabled?: boolean; primary?: boolean; selected?: boolean;
  className?: string; onFocus?(): void; label?: string; current?: boolean; navDown?: string; navRight?: string;
}) {
  return <button type="button" data-focus={id} data-group={group} data-nav-down={navDown} data-nav-right={navRight} tabIndex={-1}
    className={`control ${primary ? 'primary' : ''} ${selected ? 'selected' : ''} ${className}`}
    disabled={disabled} aria-pressed={selected} aria-current={current ? 'page' : undefined} aria-label={label}
    onClick={onClick} onFocus={onFocus}>{children}</button>;
}
export function Metadata({ item }: { item: Card }) {
  return <div className="metadata"><span>{item.isTv ? '剧集' : '电影'}</span>{item.year && <span>{item.year}</span>}{item.category && <span>{item.category}</span>}{item.rating && <span className="rating">{item.ratingSource} {item.rating}</span>}</div>;
}
export function Scene({ item }: { item: Card | null }) {
  const [loaded, setLoaded] = useState('');
  const candidate = item?.backdrop ?? '';
  const expected = useRef(candidate);
  expected.current = candidate;
  useEffect(() => { if (!candidate) setLoaded(''); }, [candidate]);
  return <div className="scene" aria-hidden="true">
    <div className="atmosphere" />
    {loaded && <img key={loaded} className="scene-art" src={loaded} alt="" />}
    {candidate && candidate !== loaded && <img key={candidate} className="scene-probe" src={candidate} alt="" decoding="async"
      onError={() => { if (expected.current === candidate) setLoaded(''); }}
      onLoad={event => {
        if (expected.current !== candidate) return;
        const image = event.currentTarget;
        setLoaded(image.naturalWidth >= 720 && image.naturalWidth / image.naturalHeight >= 1.45 ? candidate : '');
      }} />}
    <div className="scene-shade" />
  </div>;
}
export function Poster({ item, row, wide, act, index, total, side, onFocused }: {
  item: Card; row: string; wide?: boolean; act: Action; index?: number; total?: number; side?: 'before' | 'active' | 'after'; onFocused?(): void;
}) {
  const progress = item.durationMs > 0 ? Math.max(0, Math.min(100, item.positionMs / item.durationMs * 100)) : 0;
  const src = wide ? item.backdrop || item.poster : item.poster;
  return <button className={`poster ${wide ? 'wide' : ''}`} type="button" tabIndex={-1} data-focus={`card:${row}:${item.key}`} data-group={row} data-side={side}
    onFocus={() => { act('media.focus', { key: item.key }); onFocused?.(); }} onClick={() => act('media.open', { key: item.key })}
    aria-label={`${item.title}${item.year ? `，${item.year}` : ''}${progress > 0 ? '，继续观看' : ''}`}
    aria-posinset={index === undefined ? undefined : index + 1} aria-setsize={total}>
    <span className="poster-art"><span className="poster-fallback"><Icon name="play" /><span>{item.title}</span></span>
      {src && <img key={src} src={src} alt="" loading="lazy" decoding="async" onError={event => { event.currentTarget.style.opacity = '0'; }} />}
      <span className="poster-shine" />{item.favorite && <span className="favorite-mark"><Icon name="check" /></span>}
      {progress > 0 && <span className="progress"><span style={{ width: `${progress}%` }} /></span>}
    </span><span className="poster-label">{item.title}</span><span className="poster-meta">{progress > 0 ? `${Math.floor(item.positionMs / 60000)} 分钟 · 继续观看` : item.year || item.sourceName || (item.isTv ? '剧集' : '电影')}</span>
  </button>;
}
export function Shelf({ id, title, items, act, wide, bookmark }: { id: string; title: string; items: Card[]; act: Action; wide?: boolean; bookmark?: Bookmark | null }) {
  if (!items.length) return null;
  return <section className="shelf" aria-label={title}><h2>{title}<span>{items.length} 项</span></h2>
    <VirtualRail id={id} items={items} keyFor={item => `card:${id}:${item.key}`} wide={wide} initialFocus={bookmark?.focus}
      renderItem={(item, index) => <Poster item={item} row={id} wide={wide} act={act} index={index} total={items.length} />} />
  </section>;
}
export function Empty({ title, detail, children }: { title: string; detail: string; children?: ReactNode }) {
  return <section className="empty"><div className="empty-symbol"><Icon name="play" /></div><h2>{title}</h2><p>{detail}</p>{children && <div className="actions" data-focus-layout="row">{children}</div>}</section>;
}
export function Pager({ state, act, episodes = false }: { state: Snapshot; act: Action; episodes?: boolean }) {
  const page = episodes ? state.episodePage : state.pageIndex, size = episodes ? 40 : state.pageSize;
  const total = episodes ? state.episodeTotal : state.total;
  const method = episodes ? 'detail.episodes' : 'catalog.page';
  const remote = !episodes && state.pagingStatus !== 'unavailable';
  if (total <= size && !remote) return null;
  const loading = state.pagingStatus === 'loading' || state.busy;
  const refresh = state.pagingStatus === 'restart' || state.pagingStatus === 'exhausted';
  const status = ({ ready: '筛选仅适用于已载入内容，还可以继续载入。', loading: '正在载入，已收到的作品可直接查看。', retry: '载入失败，已保留成功读取的结果。', restart: '目录已变化，请刷新后继续。', exhausted: '匹配目录已载完。', unavailable: '' })[state.pagingStatus];
  return <div className="pager" data-focus-layout="row" aria-label={episodes ? '剧集分页' : '目录分页'}>
    {total > size && <><Button id={`pager:${method}:previous`} group="pager" disabled={page === 0} onClick={() => act(method, { page: page - 1 })}><Icon name="back" />上一页</Button>
      <span aria-live="polite">{`${page * size + 1}–${Math.min((page + 1) * size, total)} / ${total}`}</span>
      <Button id={`pager:${method}:next`} group="pager" disabled={(page + 1) * size >= total} onClick={() => act(method, { page: page + 1 })}>下一页<Icon name="arrow" /></Button></>}
    {remote && <><span role="status">{status}</span>
      {/* Keep the same focusable trigger during loading and after the final page. */}
      <Button id="pager:load" group="pager" onClick={() => { if (!loading) act(refresh ? 'catalog.refresh' : 'catalog.more'); }}>
        {loading ? '正在载入…' : refresh ? state.route === 'SEARCH' ? '重新检索目录' : '刷新目录' : state.pagingStatus === 'retry' ? '重试载入' : '载入更多'}
      </Button></>}
  </div>;
}
export function Grid({ state, act }: { state: Snapshot; act: Action }) {
  return <div className={`poster-grid ${state.route === 'SERIES' ? 'landscape-grid' : ''}`} data-focus-layout="grid">
    {state.items.map((item, index) => <Poster key={item.key} item={item} row="grid" wide={state.route === 'SERIES'} act={act} index={state.pageIndex * state.pageSize + index} total={state.total} />)}
  </div>;
}
