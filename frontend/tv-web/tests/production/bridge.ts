import { createMockPlatform } from '../../src/dev/mock';
import type { NativeTransport } from '../../src/platform';

// TEST PROCESS ONLY: esbuild injects this into the browser before the real dist.
// Vite never imports this file; verify-build continues rejecting fixture bytes.
const backend = createMockPlatform();
const transport: NativeTransport = {
  onmessage: null,
  postMessage(raw: string) {
    queueMicrotask(() => {
      const request = JSON.parse(raw) as { v: number; id: string; method: string; params: Record<string, unknown> };
      if (request.v !== 1) throw new Error('Unexpected production RPC protocol');
      void backend.call(request.method, request.params).then(
        result => transport.onmessage?.({ data: JSON.stringify({ v: 1, id: request.id, result }) }),
        error => transport.onmessage?.({ data: JSON.stringify({ v: 1, id: request.id, error: { code: String(error) } }) }),
      );
    });
  },
};
backend.subscribe((event, payload) => transport.onmessage?.({ data: JSON.stringify({ v: 1, event, payload }) }));
window.CinemaNative = transport;
void backend.call('settings.motion', { reduced: true });
Object.assign(window, { CinemaVisualFixture: { call: backend.call.bind(backend) } });
