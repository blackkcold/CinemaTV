import type { Bookmark } from './types';

export type Direction = 'ArrowLeft' | 'ArrowRight' | 'ArrowUp' | 'ArrowDown';
const selector = '[data-focus]:not([disabled]):not([aria-disabled="true"])';
const groupMemory = new Map<string, string>();
const virtualGroups = new Map<string, {
  focus(id: string): boolean;
  move(id: string, direction: Direction): boolean;
}>();

/** A rail can reveal an unmounted logical item before transferring DOM focus. */
export function registerVirtualGroup(id: string, adapter: { focus(id: string): boolean; move(id: string, direction: Direction): boolean }): () => void {
  virtualGroups.set(id, adapter);
  return () => { if (virtualGroups.get(id) === adapter) virtualGroups.delete(id); };
}

export function focusTargets(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>(selector)).filter(node =>
    node.offsetWidth > 0 && node.offsetHeight > 0 && !node.closest('[inert], [hidden]'));
}

function key(node: HTMLElement): string { return node.dataset.focus ?? ''; }
export function focusKey(id: string): boolean {
  const node = focusTargets().find(candidate => key(candidate) === id);
  if (node) { node.focus({ preventScroll: true }); reveal(node); return true; }
  for (const adapter of virtualGroups.values()) if (adapter.focus(id)) return true;
  return false;
}

function reveal(node: HTMLElement): void {
  node.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'auto' });
  const centered = node.closest<HTMLElement>('[data-focus-center]');
  if (centered) {
    const container = centered.getBoundingClientRect(), rect = node.getBoundingClientRect();
    centered.scrollLeft += rect.left + rect.width / 2 - container.left - container.width / 2;
  }
}

export function captureBookmark(route: string): Bookmark {
  const scrolls: Bookmark['scrolls'] = {};
  document.querySelectorAll<HTMLElement>('[data-scroll]').forEach(node => {
    scrolls[node.dataset.scroll!] = [Math.round(node.scrollLeft), Math.round(node.scrollTop)];
  });
  return { route, focus: (document.activeElement as HTMLElement | null)?.dataset.focus ?? '', scrolls };
}

export function restoreBookmark(bookmark: Bookmark | null, fallback: string): void {
  if (bookmark) for (const node of document.querySelectorAll<HTMLElement>('[data-scroll]')) {
    const offset = bookmark.scrolls[node.dataset.scroll!];
    if (offset) { node.scrollLeft = offset[0]; node.scrollTop = offset[1]; }
  }
  if (!bookmark?.focus || !focusKey(bookmark.focus)) {
    if (!focusKey(fallback)) focusTargets()[0]?.focus({ preventScroll: true });
  }
}

/** Keep wrappers geometrically stable; artwork alone scales and tilts on focus. */
export function installSpatialInput(onBack: () => void, onBookmark: () => void): () => void {
  let lastMove = 0;
  const onFocus = (event: FocusEvent) => {
    const node = event.target as HTMLElement;
    if (!node.dataset.focus) return;
    focusTargets().forEach(target => { target.tabIndex = target === node ? 0 : -1; });
    const group = node.dataset.group;
    if (group) groupMemory.set(group, key(node));
    reveal(node);
    onBookmark();
  };

  const onKey = (event: KeyboardEvent) => {
    if (event.isComposing) return;
    const active = document.activeElement as HTMLElement | null;
    if (event.key === 'Escape' || event.key === 'BrowserBack') { event.preventDefault(); onBack(); return; }
    if (active?.matches('input, textarea, select, [contenteditable="true"]')) return;
    if (event.repeat && ['Enter', ' ', 'NumpadEnter'].includes(event.key)) { event.preventDefault(); return; }
    if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return;
    event.preventDefault();
    const now = performance.now();
    if (event.repeat && now - lastMove < 70) return;
    lastMove = now;
    const nodes = focusTargets();
    if (!active?.dataset.focus || !active.isConnected) { nodes[0]?.focus(); return; }

    const direction = event.key as Direction;
    const horizontal = direction === 'ArrowLeft' || direction === 'ArrowRight';
    const positive = direction === 'ArrowRight' || direction === 'ArrowDown';
    if (!horizontal && active.hasAttribute('data-focus-scroll')) {
      const scroll = active.closest<HTMLElement>('[data-scroll]');
      if (scroll) {
        const content = active.getBoundingClientRect(), viewport = scroll.getBoundingClientRect();
        const top = viewport.top + (parseFloat(getComputedStyle(scroll).scrollPaddingTop) || 0);
        const remaining = positive ? content.bottom - viewport.bottom + 16 : top - content.top;
        if (remaining > 2) {
          scroll.scrollTop += (positive ? 1 : -1) * Math.min((viewport.bottom - top) * .65, remaining);
          onBookmark();
          return;
        }
      }
    }
    const group = active.dataset.group;
    const adapter = group ? virtualGroups.get(group) : undefined;
    if (adapter?.move(key(active), direction)) return;

    const explicit = active.dataset[`nav${direction.slice(5)}`];
    if (explicit && focusKey(explicit)) return;

    const rect = active.getBoundingClientRect();
    const x = rect.left + rect.width / 2, y = rect.top + rect.height / 2;
    const candidates = nodes.filter(node => node !== active).map(node => {
      const box = node.getBoundingClientRect();
      const dx = box.left + box.width / 2 - x, dy = box.top + box.height / 2 - y;
      const forward = (horizontal ? dx : dy) * (positive ? 1 : -1);
      const cross = Math.abs(horizontal ? dy : dx);
      const sameRow = Math.min(rect.bottom, box.bottom) - Math.max(rect.top, box.top) > Math.min(rect.height, box.height) * .45;
      const sameGroup = node.dataset.group === group;
      return { node, forward, cross, sameRow, sameGroup, score: forward + cross * (horizontal ? 4 : .65) };
    }).filter(candidate => candidate.forward > 2);

    const same = candidates.filter(candidate => candidate.sameGroup && (!horizontal || candidate.sameRow));
    let next: HTMLElement | undefined;
    if (same.length) next = same.sort((a, b) => a.score - b.score)[0].node;
    else {
      const layout = active.closest<HTMLElement>('[data-focus-layout]')?.dataset.focusLayout;
      // Horizontal edges stay on the rail/grid row; they never jump to another row.
      if (horizontal && (layout === 'row' || layout === 'grid' || group === 'nav')) return;
      const nearest = candidates.filter(candidate => !horizontal || candidate.sameRow)
        .sort((a, b) => a.score - b.score)[0];
      next = nearest?.node;
      if (!horizontal && next?.dataset.group && next.dataset.group !== group) {
        const remembered = groupMemory.get(next.dataset.group);
        const inGroup = nodes.find(node => key(node) === remembered && node.dataset.group === next?.dataset.group);
        if (inGroup) next = inGroup;
      }
    }
    next?.focus({ preventScroll: true });
  };

  document.addEventListener('focusin', onFocus);
  document.addEventListener('keydown', onKey);
  return () => {
    document.removeEventListener('focusin', onFocus);
    document.removeEventListener('keydown', onKey);
    groupMemory.clear();
  };
}
