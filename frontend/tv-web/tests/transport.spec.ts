import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => { await page.goto('/'); await expect(page.locator('.tv-app')).toBeVisible(); });

test('RPC correlates replies and ignores malformed, foreign-version and orphan messages', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const moduleUrl = '/src/platform.ts';
    const { createNativePlatform } = await import(moduleUrl);
    const posts: { id: string }[] = [];
    const transport = { onmessage: null as ((event: { data: string }) => void) | null, postMessage: (raw: string) => posts.push(JSON.parse(raw)) };
    const client = createNativePlatform(transport);
    const events: string[] = [];
    const unsubscribe = client.subscribe((event: string) => events.push(event));
    const pending = client.call('app.ready', { protocol: 1 });
    for (const raw of ['{', 'null', '[]', JSON.stringify({ v: 2, id: posts[0].id, result: { bad: true } }), JSON.stringify({ v: 1, id: 'orphan', result: {} })]) transport.onmessage?.({ data: raw });
    transport.onmessage?.({ data: JSON.stringify({ v: 1, event: 'state', payload: { revision: 1 } }) });
    transport.onmessage?.({ data: JSON.stringify({ v: 1, id: posts[0].id, result: { accepted: true } }) });
    const reply = await pending;
    unsubscribe();
    transport.onmessage?.({ data: JSON.stringify({ v: 1, event: 'state', payload: {} }) });
    client.close();
    return { reply, events, posts: posts.length, detached: transport.onmessage === null };
  });
  expect(result).toEqual({ reply: { accepted: true }, events: ['state'], posts: 1, detached: true });
});

test('RPC limits UTF-8 request size and concurrent requests without posting rejected writes', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const moduleUrl = '/src/platform.ts';
    const { createNativePlatform } = await import(moduleUrl);
    const posts: string[] = [];
    const transport = { onmessage: null, postMessage: (raw: string) => posts.push(raw) };
    const client = createNativePlatform(transport);
    const oversized = await client.call('search.submit', { query: '汉'.repeat(12000) }).catch((error: Error) => error.message);
    const requests = Array.from({ length: 8 }, () => client.call('app.ready').catch((error: Error) => error.message));
    const busy = await client.call('library.favorite', { key: 'opaque', favorite: true }).catch((error: Error) => error.message);
    client.close();
    return { oversized, busy, posts: posts.length, pending: await Promise.all(requests) };
  });
  expect(result.oversized).toBe('REQUEST_TOO_LARGE');
  expect(result.busy).toBe('BRIDGE_BUSY');
  expect(result.posts).toBe(8);
  expect(result.pending).toEqual(Array(8).fill('BRIDGE_CLOSED'));
});

test('timed-out writes are not retried and late responses cannot resolve a closed request', async ({ page }) => {
  await page.clock.install();
  await page.evaluate(async () => {
    const moduleUrl = '/src/platform.ts';
    const { createNativePlatform } = await import(moduleUrl);
    const state = { posts: [] as { id: string }[], result: '', events: 0 };
    const transport = { onmessage: null as ((event: { data: string }) => void) | null, postMessage: (raw: string) => state.posts.push(JSON.parse(raw)) };
    const client = createNativePlatform(transport);
    client.subscribe(() => { state.events++; });
    void client.call('library.favorite', { key: 'opaque', favorite: true }).then(() => { state.result = 'resolved'; }, (error: Error) => { state.result = error.message; });
    Object.assign(window, { bridgeHarness: { state, client, transport } });
  });
  await page.clock.runFor(10001);
  const result = await page.evaluate(() => {
    const harness = (window as unknown as { bridgeHarness: { state: { posts: { id: string }[]; result: string; events: number }; client: { close(): void }; transport: { onmessage: ((event: { data: string }) => void) | null } } }).bridgeHarness;
    harness.transport.onmessage?.({ data: JSON.stringify({ v: 1, id: harness.state.posts[0].id, result: { accepted: true } }) });
    harness.transport.onmessage?.({ data: JSON.stringify({ v: 1, event: 'state', payload: { favorite: true } }) });
    harness.client.close();
    return { result: harness.state.result, posts: harness.state.posts.length, events: harness.state.events };
  });
  expect(result).toEqual({ result: 'TIMEOUT', posts: 1, events: 1 });
});

test('malformed reply shapes reject cleanly and transport exceptions free request capacity', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const moduleUrl = '/src/platform.ts';
    const { createNativePlatform } = await import(moduleUrl);
    let fail = true;
    const posts: { id: string }[] = [];
    const transport = { onmessage: null as ((event: { data: string }) => void) | null, postMessage: (raw: string) => { if (fail) throw new Error('disconnected'); posts.push(JSON.parse(raw)); } };
    const client = createNativePlatform(transport);
    const transportFailure = await client.call('app.ready').catch((error: Error) => error.message);
    fail = false;
    const pending = client.call('app.ready').catch((error: Error) => error.message);
    transport.onmessage?.({ data: JSON.stringify({ v: 1, id: posts[0].id, result: [] }) });
    const badResponse = await pending;
    client.close();
    return { transportFailure, badResponse, posts: posts.length };
  });
  expect(result).toEqual({ transportFailure: 'TRANSPORT_FAILED', badResponse: 'BAD_RESPONSE', posts: 1 });
});
