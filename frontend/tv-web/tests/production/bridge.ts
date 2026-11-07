import { createMockPlatform } from '../../src/dev/mock';
import type { NativeTransport } from '../../src/platform';

// TEST PROCESS ONLY. Production image DTOs contain local URLs, not embedded SVGs.
// Keep the original art and complete data set while respecting the real RPC cap.
const backend = createMockPlatform();
const imagePaths = new Map<string, string>();
const images = new Map<string, string>();
let largestReplyBytes = 0;
function boundedImages(value: unknown): unknown {
  if (typeof value === 'string' && value.startsWith('data:image/svg+xml,')) {
    let path = imagePaths.get(value);
    if (!path) {
      path = `/__cinema_visual_art/${imagePaths.size}.svg`;
      imagePaths.set(value, path);
      images.set(path, decodeURIComponent(value.slice('data:image/svg+xml,'.length)));
    }
    return path;
  }
  if (Array.isArray(value)) return value.map(boundedImages);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, boundedImages(child)]));
  }
  return value;
}
function deliver(value: unknown) {
  const data = JSON.stringify(boundedImages(value));
  const bytes = new TextEncoder().encode(data).length;
  largestReplyBytes = Math.max(largestReplyBytes, bytes);
  if (bytes > 512 * 1024) throw new Error(`Test DTO exceeds production bridge limit: ${bytes}`);
  transport.onmessage?.({ data });
}
const transport: NativeTransport = {
  onmessage: null,
  postMessage(raw: string) {
    queueMicrotask(() => {
      const request = JSON.parse(raw) as { v: number; id: string; method: string; params: Record<string, unknown> };
      if (request.v !== 1) throw new Error('Unexpected production RPC protocol');
      void backend.call(request.method, request.params).then(
        result => deliver({ v: 1, id: request.id, result }),
        error => deliver({ v: 1, id: request.id, error: { code: String(error) } }),
      );
    });
  },
};
backend.subscribe((event, payload) => deliver({ v: 1, event, payload }));
window.CinemaNative = transport;
void backend.call('settings.motion', { reduced: true });
Object.assign(window, { CinemaVisualFixture: {
  call: backend.call.bind(backend),
  image: (path: string) => images.get(path) ?? null,
  statistics: () => ({ largestReplyBytes, imageCount: images.size }),
} });
