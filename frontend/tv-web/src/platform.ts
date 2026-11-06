import type { Platform, Reply } from './types';
export interface NativeTransport { postMessage(message: string): void; onmessage: ((event: { data: string }) => void) | null; }
declare global { interface Window { CinemaNative?: NativeTransport; } }

/** Bounded RPC; timed-out writes are never automatically retried. */
export function createNativePlatform(transport: NativeTransport): Platform {
  let sequence = 0;
  let closed = false;
  const prefix = `${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
  const pending = new Map<string, { resolve(value: Reply): void; reject(error: Error): void; timer: ReturnType<typeof setTimeout> }>();
  const listeners = new Set<(event: string, payload: unknown) => void>();
  const receive = (event: { data: string }) => {
    if (closed || typeof event.data !== 'string' || event.data.length > 512 * 1024) return;
    let value: Record<string, unknown>;
    try { value = JSON.parse(event.data) as Record<string, unknown>; } catch { return; }
    if (!value || typeof value !== 'object' || Array.isArray(value) || value.v !== 1) return;
    if (typeof value.event === 'string') {
      for (const listener of listeners) listener(value.event, value.payload);
      return;
    }
    if (typeof value.id !== 'string') return;
    const task = pending.get(value.id);
    if (!task) return;
    pending.delete(value.id); clearTimeout(task.timer);
    if (value.error) {
      const code = (value.error as { code?: unknown }).code;
      task.reject(new Error(typeof code === 'string' ? code : 'BRIDGE_ERROR'));
    } else if (value.result !== undefined && (!value.result || typeof value.result !== 'object' || Array.isArray(value.result))) {
      task.reject(new Error('BAD_RESPONSE'));
    } else task.resolve((value.result ?? {}) as Reply);
  };
  transport.onmessage = receive;
  return {
    native: true,
    call(method, params = {}) {
      if (closed) return Promise.reject(new Error('BRIDGE_CLOSED'));
      if (pending.size >= 8) return Promise.reject(new Error('BRIDGE_BUSY'));
      const id = `${prefix}_${++sequence}`;
      const raw = JSON.stringify({ v: 1, id, method, params });
      if (new TextEncoder().encode(raw).length > 32768) return Promise.reject(new Error('REQUEST_TOO_LARGE'));
      return new Promise<Reply>((resolve, reject) => {
        const timer = setTimeout(() => { pending.delete(id); reject(new Error('TIMEOUT')); }, 10000);
        pending.set(id, { resolve, reject, timer });
        try { transport.postMessage(raw); } catch {
          clearTimeout(timer); pending.delete(id); reject(new Error('TRANSPORT_FAILED'));
        }
      });
    },
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
    close() {
      closed = true;
      if (transport.onmessage === receive) transport.onmessage = null;
      for (const task of pending.values()) { clearTimeout(task.timer); task.reject(new Error('BRIDGE_CLOSED')); }
      pending.clear(); listeners.clear();
    },
  };
}

export async function createPlatform(): Promise<Platform> {
  if (window.CinemaNative) return createNativePlatform(window.CinemaNative);
  // Vite removes this branch and the entire synthetic fixture module from production.
  if (import.meta.env.DEV) return (await import('./dev/mock')).createMockPlatform();
  throw new Error('请通过 CinemaTV 打开此界面');
}
