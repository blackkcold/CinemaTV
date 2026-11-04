import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createDoubanProvider, parseRetryAfter } from './douban-provider.mjs';
import { createCatalogService } from './catalog.mjs';

// Only synthetic, invented titles/ratings. No live network is used in this suite.
function page(start, { count = 25, duplicate = false } = {}) {
  return '<html><head><title>Synthetic chart fixture</title></head><body><ol>' + Array.from({ length: count }, (_, i) => {
    const rank = start + i + 1;
    const id = duplicate && i === 0 ? 1001 : 1000 + rank;
    return `<li><div class="item"><em>${rank}</em><a href="https://movie.douban.com/subject/${id}/"><span class="title">Synthetic Film ${rank}</span><span class="title"> / Fictional Alias ${rank}</span></a><p>Imaginary credits<br>2025 / Imaginary region</p><span class="rating_num">8.1</span><img src="https://img1.doubanio.com/test.jpg"></div></li>`;
  }).join('') + '</ol></body></html>';
}
async function setup(t, overrides = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'cinematv-douban-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  let time = Date.parse('2026-11-04T00:00:00Z');
  const seen = [];
  const env = { DOUBAN_ACQUISITION_ENABLED: 'true', DOUBAN_WRITTEN_PERMISSION: 'true',
    DOUBAN_STATE_DIR: dir, DOUBAN_LICENSE_NAME: 'Synthetic test declaration only',
    DOUBAN_LICENSE_URL: 'https://permission.example.test/terms', DOUBAN_LICENSE_EXPIRES_AT: '2099-01-01T00:00:00Z',
    DOUBAN_ATTRIBUTION: 'Synthetic test metadata', ...overrides };
  const clock = { now: () => time, sleep: async ms => { time += ms; }, random: () => 0,
    shared: false, advance: ms => { time += ms; } };
  const make = (handler = async (target, options) => {
    assert.equal(new URL(target).origin, 'https://movie.douban.com');
    assert.equal(options.redirect, 'manual');
    assert.equal(options.headers.cookie, undefined);
    return new Response(page(Number(new URL(target).searchParams.get('start'))));
  }, nextEnv = env) => {
    const provider = createDoubanProvider(nextEnv, { ...clock, fetchImpl: async (target, options) => {
      seen.push({ target, time, options }); return handler(target, options);
    } });
    t.after(() => provider.close());
    return provider;
  };
  return { dir, env, clock, seen, make };
}

test('async cold response is immediate; one fill acquires all250 with 3-second spacing and no image URLs', async t => {
  const { make, seen } = await setup(t);
  const provider = make();
  const cold = await provider.load();
  assert.deepEqual(cold.document.items, []);
  assert.equal(cold.acquisition.refreshing, true);
  assert.equal(cold.acquisition.state, 'refreshing');
  await provider.waitForIdle();
  const full = await provider.load();
  assert.equal(full.document.items.length, 250);
  assert.equal(full.acquisition.complete, true);
  assert.equal(full.acquisition.availableTotal, 250);
  assert.equal(full.acquisition.refreshing, false);
  assert.equal(full.acquisition.stale, false);
  assert.equal(full.acquisition.state, 'fresh');
  assert.ok(full.acquisition.snapshotId);
  assert.equal(seen.length, 10);
  assert.deepEqual(seen.map(x => Number(new URL(x.target).searchParams.get('start'))), [0,25,50,75,100,125,150,175,200,225]);
  for (let i = 1; i < seen.length; i++) assert.ok(seen[i].time - seen[i - 1].time >= 3000);
  assert.ok(full.document.items.every(item => item.poster === '' && item.backdrop === '' && item.actors.length === 0));
  await provider.load(); assert.equal(seen.length, 10);
});

test('same provider singleflight covers different queries/pages and integrates with catalog pagination', async t => {
  const { make, seen } = await setup(t);
  const provider = make();
  const service = createCatalogService({}, () => { throw new Error('No other upstream'); }, { doubanProvider: provider });
  const request = { chart: 'douban-top250', type: 'movie', query: '', limit: 50, offset: 0 };
  const cold = await Promise.all(Array.from({ length: 10 }, (_, i) => service({ ...request, offset: i * 25 })));
  assert.ok(cold.every(result => result.acquisition.refreshing));
  await provider.waitForIdle();
  const ids = []; let snapshot;
  for (let offset = 0; offset < 250; offset += 50) {
    const result = await service({ ...request, offset });
    assert.equal(result.status, 'available'); assert.equal(result.total, 250);
    assert.equal(result.items[0].topRank, offset + 1);
    snapshot ||= result.acquisition.snapshotId;
    assert.equal(result.acquisition.snapshotId, snapshot);
    ids.push(...result.items.map(item => item.id));
  }
  assert.equal(new Set(ids).size, 250); assert.equal(seen.length, 10);
  const tv = await service({ ...request, type: 'tv' });
  assert.equal(tv.acquisition, undefined); assert.equal(seen.length, 10);
});

test('failed third page exposes partial count and never marks50items complete', async t => {
  const { make } = await setup(t);
  const provider = make(async target => {
    const start = Number(new URL(target).searchParams.get('start'));
    return start === 50 ? new Response('', { status: 503 }) : new Response(page(start));
  });
  await provider.load(); await provider.waitForIdle();
  const result = await provider.load();
  assert.equal(result.document.items.length, 50);
  assert.equal(result.acquisition.complete, false);
  assert.equal(result.acquisition.state, 'partial');
  assert.ok(result.acquisition.warnings.includes('upstream_error'));
  assert.ok(result.acquisition.retryAt);
});

test('missing ranks and cross-page duplicate IDs remain incomplete and omit ambiguity', async t => {
  for (const mode of ['missing', 'duplicate']) await t.test(mode, async t => {
    const { make } = await setup(t);
    const provider = make(async target => {
      const start = Number(new URL(target).searchParams.get('start'));
      return new Response(page(start, mode === 'missing' ? { count: 24 } : { duplicate: start === 25 }));
    });
    await provider.load(); await provider.waitForIdle();
    const result = await provider.load();
    assert.equal(result.acquisition.complete, false);
    assert.equal(result.document.items.length, mode === 'missing' ? 24 : 48);
    if (mode === 'duplicate') assert.equal(result.document.items.some(item => item.providerId === '1001'), false);
  });
});

test('Retry-After supports seconds and HTTP dates without capping long provider delays', () => {
  const now = Date.parse('2026-11-04T00:00:00Z');
  assert.equal(parseRetryAfter('86400', now), now + 86400000);
  assert.equal(parseRetryAfter('Fri, 06 Nov 2026 00:00:00 GMT', now), now + 2 * 86400000);
  assert.equal(parseRetryAfter('nonsense', now), null);
  assert.equal(parseRetryAfter('9999999999999999999999', now), 8640000000000000);
});

test('429 seconds/date cooldown survives restart and prevents any additional request', async t => {
  for (const retry of ['86400', 'Fri, 06 Nov 2026 00:00:00 GMT']) await t.test(retry, async t => {
    const { make, seen, clock } = await setup(t);
    let provider = make(async () => new Response('', { status: 429, headers: { 'retry-after': retry } }));
    await provider.load(); await provider.waitForIdle();
    const result = await provider.load();
    assert.equal(result.acquisition.state, 'rate_limited');
    const retryAt = result.acquisition.retryAt;
    assert.ok(Date.parse(retryAt) - clock.now() >= 86400000);
    await provider.close(); clock.advance(1000);
    provider = make();
    const restarted = await provider.load();
    assert.equal(restarted.acquisition.retryAt, retryAt);
    assert.equal(restarted.acquisition.refreshing, false);
    assert.equal(seen.length, 1);
  });
});

test('403 and CAPTCHA open a persistent circuit with no automatic retry or evasive request', async t => {
  for (const kind of ['403', 'captcha']) await t.test(kind, async t => {
    const { make, seen, clock } = await setup(t);
    let provider = make(async () => kind === '403' ? new Response('', { status: 403 }) :
      new Response('<html><head><title>安全验证</title></head><body><form><input name="captcha"></form></body></html>'));
    await provider.load(); await provider.waitForIdle();
    assert.equal((await provider.load()).acquisition.state, 'access_blocked');
    await provider.close(); clock.advance(86400000);
    provider = make();
    const result = await provider.load();
    assert.equal(result.acquisition.blocked, true); assert.equal(result.acquisition.refreshing, false);
    assert.equal(result.acquisition.retryAt, null); assert.equal(seen.length, 1);
  });
});

test('fresh disk snapshot survives restart; stale complete snapshot is preferred to failed partial refresh', async t => {
  const { make, seen, clock } = await setup(t);
  let provider = make(); await provider.load(); await provider.waitForIdle();
  const first = await provider.load(); await provider.close();
  provider = make(async () => new Response('', { status: 503 }));
  const cached = await provider.load();
  assert.equal(cached.acquisition.source, 'cache'); assert.equal(seen.length, 10);
  clock.advance(8 * 86400000);
  const refreshing = await provider.load();
  assert.equal(refreshing.acquisition.stale, true); assert.equal(refreshing.acquisition.refreshing, true);
  assert.equal(refreshing.acquisition.snapshotId, first.acquisition.snapshotId);
  await provider.waitForIdle();
  const stale = await provider.load();
  assert.equal(stale.document.items.length, 250); assert.equal(stale.acquisition.complete, true);
  assert.equal(stale.acquisition.source, 'stale-cache'); assert.equal(stale.acquisition.stale, true);
  assert.ok(stale.acquisition.warnings.includes('upstream_error'));
});

test('persistent20-per10minute budget is preserved across restarts', async t => {
  const { make, seen, clock } = await setup(t, { DOUBAN_CACHE_TTL_MS: '1000' });
  let provider = make(); await provider.load(); await provider.waitForIdle(); await provider.close();
  clock.advance(1001); provider = make(); await provider.load(); await provider.waitForIdle();
  assert.equal(seen.length, 20);
  clock.advance(1001); await provider.load(); await provider.waitForIdle();
  const limited = await provider.load();
  assert.equal(limited.acquisition.state, 'rate_limited'); assert.equal(seen.length, 20);
});

test('missing/expired rights never fetch or serve stale licensed data', async t => {
  const { make, env, clock, seen } = await setup(t);
  const missing = make(undefined, { ...env, DOUBAN_WRITTEN_PERMISSION: '' });
  assert.equal((await missing.load()).document, null); assert.equal(seen.length, 0);
  const provider = make(); await provider.load(); await provider.waitForIdle();
  clock.advance(Date.parse(env.DOUBAN_LICENSE_EXPIRES_AT) - clock.now());
  const expired = await provider.load();
  assert.equal(expired.document, null); assert.equal(expired.acquisition.refreshing, false);
  assert.ok(expired.acquisition.warnings.includes('rights_expired')); assert.equal(seen.length, 10);
});

test('body bounds and redirects fail closed without following another origin', async t => {
  for (const mode of ['large', 'redirect']) await t.test(mode, async t => {
    const { make, seen } = await setup(t);
    const provider = make(async (_target, options) => {
      assert.equal(options.redirect, 'manual');
      return mode === 'large' ? new Response(page(0) + ' '.repeat(1024 * 1024)) :
        new Response('', { status: 302, headers: { location: 'https://untrusted.example/secret' } });
    });
    await provider.load(); await provider.waitForIdle();
    const result = await provider.load();
    assert.deepEqual(result.document.items, []); assert.equal(seen.length, 1);
    assert.ok(result.acquisition.warnings.includes(mode === 'large' ? 'body_too_large' : 'redirect_blocked'));
  });
});

test('corrupt persistent state, occupied directory and unapproved origin prevent requests', async t => {
  const a = await setup(t);
  await writeFile(join(a.dir, 'state.json'), '{broken');
  const corrupt = a.make();
  assert.equal((await corrupt.load()).document, null); assert.equal(a.seen.length, 0);
  const b = await setup(t, { DOUBAN_ORIGIN: 'https://untrusted.example' });
  assert.equal((await b.make().load()).document, null); assert.equal(b.seen.length, 0);
  const c = await setup(t);
  const first = c.make(); await first.load(); await first.waitForIdle();
  const second = c.make(); assert.equal((await second.load()).document, null);
  assert.equal(c.seen.length, 10);
  const state = JSON.parse(await readFile(join(c.dir, 'state.json'), 'utf8'));
  assert.equal(state.gate.requests.length, 10);
});

test('an unresponsive upstream cannot hold the catalog request; bounded timeout ends the background fill', async t => {
  const { make } = await setup(t, { DOUBAN_TIMEOUT_MS: '100' });
  let started;
  const didStart = new Promise(resolve => { started = resolve; });
  const provider = make(async (_target, options) => {
    started();
    return new Promise((_resolve, reject) => {
      const keepAlive = setTimeout(() => reject(new Error('Test timeout watchdog')), 1000);
      options.signal.addEventListener('abort', () => { clearTimeout(keepAlive); reject(options.signal.reason); }, { once: true });
    });
  });
  const start = Date.now();
  const cold = await provider.load();
  assert.equal(cold.acquisition.refreshing, true);
  assert.ok(Date.now() - start < 1000);
  await didStart;
  const whileWaiting = await provider.load();
  assert.equal(whileWaiting.acquisition.refreshing, true);
  await provider.waitForIdle();
  const failed = await provider.load();
  assert.equal(failed.acquisition.refreshing, false);
  assert.ok(failed.acquisition.warnings.includes('transport_or_parse_error'));
});

test('disabled movie acquisition exposes setup status, while TV and configured feeds retain their behavior', async () => {
  const service = createCatalogService({}, () => { throw new Error('No network'); });
  const movie = await service({ chart: 'douban-top250', type: 'movie', query: '', limit: 50, offset: 0 });
  assert.equal(movie.acquisition.state, 'disabled');
  assert.equal(movie.acquisition.refreshing, false);
  const tv = await service({ chart: 'douban-top250', type: 'tv', query: '', limit: 50, offset: 0 });
  assert.equal(tv.acquisition, undefined);
});

test('repeated fresh reads retain original fetchedAt and never claim a new network refresh', async t => {
  const { make, clock, seen } = await setup(t);
  const provider = make(); await provider.load(); await provider.waitForIdle();
  const first = await provider.load(); clock.advance(3600000);
  const later = await provider.load();
  assert.equal(first.acquisition.source, 'cache');
  assert.equal(later.acquisition.source, 'cache');
  assert.equal(later.acquisition.fetchedAt, first.acquisition.fetchedAt);
  assert.equal(later.acquisition.snapshotId, first.acquisition.snapshotId);
  assert.equal(later.acquisition.refreshing, false);
  assert.equal(seen.length, 10);
});
