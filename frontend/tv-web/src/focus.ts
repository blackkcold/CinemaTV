import type { Bookmark } from './types';

type Direction = 'ArrowLeft' | 'ArrowRight' | 'ArrowUp' | 'ArrowDown';
const selector = '[data-focus]:not([disabled]):not([aria-disabled="true"])';
const groups = new Map<string, string>();
function targets(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>(selector)).filter(node => node.offsetWidth > 0 && node.offsetHeight > 0 && !node.closest('[inert]'));
}
function key(node: HTMLElement): string { return node.dataset.focus ?? ''; }
export function focusKey(id: string): boolean {
  const node = targets().find(candidate => key(candidate) === id);
  if (!node) return false;
  node.focus({ preventScroll: true }); node.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'auto' }); return true;
}
export function captureBookmark(route: string): Bookmark {
  const scrolls: Bookmark['scrolls'] = {};
  document.querySelectorAll<HTMLElement>('[data-scroll]').forEach(node => { scrolls[node.dataset.scroll!] = [Math.round(node.scrollLeft), Math.round(node.scrollTop)]; });
  return { route, focus: (document.activeElement as HTMLElement | null)?.dataset.focus ?? '', scrolls };
}
export function restoreBookmark(bookmark: Bookmark | null, fallback: string): void {
  if (bookmark) for (const node of document.querySelectorAll<HTMLElement>('[data-scroll]')) {
    const offset = bookmark.scrolls[node.dataset.scroll!];
    if (offset) { node.scrollLeft = offset[0]; node.scrollTop = offset[1]; }
  }
  if (!bookmark?.focus || !focusKey(bookmark.focus)) {
    if (!focusKey(fallback)) targets()[0]?.focus({ preventScroll: true });
  }
}

/** Fixed geometry wrappers: visual scale/tilt belongs to a child, never this node. */
export function installSpatialInput(onBack: () => void, onBookmark: () => void): () => void {
  let lastMove = 0;
  const onFocus = (event: FocusEvent) => {
    const node = event.target as HTMLElement;
    if (!node.dataset.focus) return;
    targets().forEach(target => { target.tabIndex = target === node ? 0 : -1; });
    const group = node.dataset.group;
    if (group) groups.set(group, key(node));
    node.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'auto' });
    onBookmark();
  };
  const onKey = (event: KeyboardEvent) => {
    if (event.isComposing) return;
    const active = document.activeElement as HTMLElement | null;
    if (event.key === 'Escape' || event.key === 'BrowserBack') { event.preventDefault(); onBack(); return; }
    if (active?.matches('input, textarea, select, [contenteditable="true"]')) return;
    if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return;
    event.preventDefault();
    const now = performance.now();
    if (event.repeat && now - lastMove < 85) return;
    lastMove = now;
    const nodes = targets();
    if (!active?.dataset.focus) { nodes[0]?.focus(); return; }
    const rect = active.getBoundingClientRect();
    const x = rect.left + rect.width / 2, y = rect.top + rect.height / 2;
    const direction = event.key as Direction;
    const horizontal = direction === 'ArrowLeft' || direction === 'ArrowRight';
    const positive = direction === 'ArrowRight' || direction === 'ArrowDown';
    const candidates = nodes.filter(node => node !== active).map(node => {
      const box = node.getBoundingClientRect();
      const dx = box.left + box.width / 2 - x, dy = box.top + box.height / 2 - y;
      const forward = (horizontal ? dx : dy) * (positive ? 1 : -1);
      const cross = Math.abs(horizontal ? dy : dx);
      const sameGroup = node.dataset.group === active.dataset.group;
      return { node, forward, score: forward + cross * (horizontal ? 5 : 2) + (horizontal && !sameGroup ? 10000 : 0) };
    }).filter(candidate => candidate.forward > 2).sort((a, b) => a.score - b.score);
    let next = candidates[0]?.node;
    if (!horizontal && next?.dataset.group && next.dataset.group !== active.dataset.group) {
      const remembered = groups.get(next.dataset.group);
      const inGroup = nodes.find(node => key(node) === remembered && node.dataset.group === next?.dataset.group);
      if (inGroup) next = inGroup;
    }
    next?.focus({ preventScroll: true });
  };
  document.addEventListener('focusin', onFocus);
  document.addEventListener('keydown', onKey);
  return () => { document.removeEventListener('focusin', onFocus); document.removeEventListener('keydown', onKey); };
}
