import { useCallback, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { registerVirtualGroup } from './focus';

const WINDOW = 12;
const OVERSCAN = 3;

interface Props<T> {
  id: string;
  items: T[];
  keyFor(item: T): string;
  renderItem(item: T, index: number): ReactNode;
  wide?: boolean;
  centered?: boolean;
  initialFocus?: string;
}

/** Fixed-size logical rail: <=12 visible/overscan cards plus an existing focused card. */
export function VirtualRail<T>({ id, items, keyFor, renderItem, wide = false, centered = false, initialFocus = '' }: Props<T>) {
  const container = useRef<HTMLDivElement>(null);
  const pending = useRef('');
  const desired = Math.max(0, items.findIndex(item => keyFor(item) === initialFocus));
  const [start, setStart] = useState(Math.max(0, desired - OVERSCAN));
  const [activeKey, setActiveKey] = useState(initialFocus);
  const [metrics, setMetrics] = useState({ width: 1, step: 1, inset: 1, height: 1 });
  const current = useRef({ items, keyFor, metrics });
  current.current = { items, keyFor, metrics };

  const clampStart = (index: number, count: number) => Math.max(0, Math.min(index - OVERSCAN, count - WINDOW));
  const focus = useCallback((key: string): boolean => {
    const state = current.current;
    const index = state.items.findIndex(item => state.keyFor(item) === key);
    if (index < 0) return false;
    const existing = Array.from(container.current?.querySelectorAll<HTMLElement>('[data-focus]') ?? []).find(node => node.dataset.focus === key);
    if (existing) { existing.focus({ preventScroll: true }); return true; }
    pending.current = key;
    setActiveKey(key);
    setStart(clampStart(index, state.items.length));
    return true;
  }, []);

  useLayoutEffect(() => {
    const node = container.current;
    if (!node) return;
    const measure = () => {
      const rootSize = parseFloat(getComputedStyle(document.documentElement).fontSize);
      const portrait = window.innerWidth / window.innerHeight < 4 / 3;
      const width = window.innerWidth * (wide ? portrait ? .42 : .235 : portrait ? .24 : .14);
      const step = width + rootSize * 1.15;
      const inset = centered ? Math.max(0, (node.clientWidth - width) / 2) : window.innerWidth * .052;
      const height = width * (wide ? 9 / 16 : 3 / 2) + rootSize * (centered ? 3.8 : 4.6);
      setMetrics({ width, step, inset, height });
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, [wide, centered]);

  useLayoutEffect(() => registerVirtualGroup(id, {
    focus,
    move(key, direction) {
      if (direction !== 'ArrowLeft' && direction !== 'ArrowRight') return false;
      const state = current.current;
      const index = state.items.findIndex(item => state.keyFor(item) === key);
      if (index < 0) return false;
      const next = Math.min(state.items.length - 1, Math.max(0, index + (direction === 'ArrowRight' ? 1 : -1)));
      if (next !== index) focus(state.keyFor(state.items[next]));
      return true;
    },
  }), [id, focus]);

  useLayoutEffect(() => {
    if (!pending.current) return;
    const node = Array.from(container.current?.querySelectorAll<HTMLElement>('[data-focus]') ?? []).find(candidate => candidate.dataset.focus === pending.current);
    if (node) { pending.current = ''; node.focus({ preventScroll: true }); }
  }, [start, activeKey, items, metrics]);

  useLayoutEffect(() => {
    const node = container.current;
    if (!node) return;
    let frame = 0;
    const onScroll = () => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        const state = current.current;
        if (state.metrics.step <= 1) return;
        const index = Math.floor(Math.max(0, node.scrollLeft) / state.metrics.step);
        setStart(clampStart(index, state.items.length));
      });
    };
    const onFocus = (event: FocusEvent) => setActiveKey((event.target as HTMLElement).dataset.focus ?? '');
    node.addEventListener('scroll', onScroll, { passive: true });
    node.addEventListener('focusin', onFocus);
    return () => { cancelAnimationFrame(frame); node.removeEventListener('scroll', onScroll); node.removeEventListener('focusin', onFocus); };
  }, []);

  const visible = new Set<number>();
  const actualStart = Math.min(start, Math.max(0, items.length - WINDOW));
  for (let index = actualStart; index < Math.min(items.length, actualStart + WINDOW); index++) visible.add(index);
  const active = items.findIndex(item => keyFor(item) === activeKey);
  if (active >= 0) visible.add(active);
  return <div ref={container} className={`rail virtual-rail ${wide ? 'wide-rail' : ''} ${centered ? 'centered-rail' : ''}`}
    data-scroll={`rail:${id}`} data-focus-layout="row" data-focus-center={centered ? 'true' : undefined}>
    <div className="rail-track" style={{ width: `${Math.max(0, items.length * metrics.step - (metrics.step - metrics.width) + metrics.inset * 2)}px`, height: `${metrics.height}px` }}>
      {Array.from(visible).sort((a, b) => a - b).map(index => <div key={keyFor(items[index])} className="rail-slot" style={{ left: `${metrics.inset + index * metrics.step}px`, width: `${metrics.width}px` }}>
        {renderItem(items[index], index)}
      </div>)}
    </div>
  </div>;
}
