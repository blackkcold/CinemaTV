import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { gzipSync } from 'node:zlib';
import { fixture } from './catalog-fixture.mjs';
import { CATALOG_MAX_BYTES } from './catalog.mjs';

const token = 'catalog-test-bearer-long-enough';
let importCounter = 0;
function json(res, body, status = 200) {
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(JSON.stringify(body));
}
async function serve(t, handler) {
  const server = createServer(handler);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  return 'http://127.0.0.1:' + server.address().port;
}
async function app(t, env = {}) {
  for (const key of ['LICENSED_CATALOG_ENABLED', 'LICENSED_CATALOG_URL', 'LICENSED_CATALOG_FILE',
    'TMDB_READ_ACCESS_TOKEN', 'TMDB_API_URL', 'CATALOG_TMDB_ENABLED', 'CATALOG_TIMEOUT_MS']) delete process.env[key];
  Object.assign(process.env, { LIBRETV_URL: 'http://127.0.0.1:1', BFF_ACCESS_TOKEN: token, LIBRETV_PASSWORD: '', ...env });
  const { createApp } = await import('./server.mjs?catalogIntegration=' + importCounter++);
  const server = createApp();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const base = 'http://127.0.0.1:' + server.address().port;
  return { base, request: path => fetch(base + path, { headers: { authorization: 'Bearer ' + token } }) };
}

test('catalog route authenticates, validates, filters licensed data and forwards no personal/query data', async t => {
  const seen = [];
  const catalogUrl = await serve(t, (req, res) => {
    seen.push({ url: req.url, headers: req.headers });
    json(res, fixture());
  });
  const api = await app(t, { LICENSED_CATALOG_ENABLED: 'true', LICENSED_CATALOG_URL: catalogUrl + '/fixed.json' });
  assert.equal((await fetch(api.base + '/v1/catalog')).status, 401);
  assert.equal((await api.request('/v1/catalog?url=http://evil.test')).status, 400);
  assert.equal(seen.length, 0);
  const response = await api.request('/v1/catalog?query=Fictional%20Actor&type=movie&limit=1');
  assert.equal(response.status, 200);
  const result = await response.json();
  assert.equal(result.status, 'available');
  assert.equal(result.provider, 'Synthetic provider');
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].topSource, 'Synthetic chart');
  assert.equal(result.items[0].ratingSource, 'Synthetic ratings');
  assert.deepEqual(result.items[0].actors, ['Fictional Actor']);
  assert.ok(result.license);
  assert.equal(result.fallback, null);
  assert.equal(seen[0].url, '/fixed.json');
  assert.equal(seen[0].headers.authorization, undefined);
  assert.equal(seen[0].headers.cookie, undefined);
  const empty = await (await api.request('/v1/catalog?query=Missing&type=movie')).json();
  assert.equal(empty.status, 'empty');
  assert.equal(empty.fallback, 'libretv');
  assert.deepEqual(empty.items, []);
});

test('TMDB fallback labels real provider, supports movie search and TV popular, and never invents Douban ranks', async t => {
  const seen = [];
  const licensed = await serve(t, (_req, res) => {
    const data = fixture(); data.license.expiresAt = '2020-01-01T00:00:00Z'; json(res, data);
  });
  const tmdb = await serve(t, (req, res) => {
    seen.push(req.url);
    assert.equal(req.headers.authorization, 'Bearer synthetic-tmdb-token');
    const url = new URL(req.url, 'http://localhost');
    assert.deepEqual([...url.searchParams.keys()].sort(),
      url.pathname.startsWith('/search/') ? ['include_adult', 'language', 'page', 'query'] : ['include_adult', 'language', 'page']);
    json(res, { results: [
      { id: 4, title: 'Search Movie', name: 'Popular Series', original_title: 'Original Movie',
        original_name: 'Original Series', vote_average: 8.2, vote_count: 4 },
      { id: 5, title: 'Adult Movie', name: 'Adult Series', adult: true }
    ] });
  });
  const api = await app(t, { LICENSED_CATALOG_ENABLED: 'true', LICENSED_CATALOG_URL: licensed,
    TMDB_READ_ACCESS_TOKEN: 'synthetic-tmdb-token', TMDB_API_URL: tmdb });
  const search = await (await api.request('/v1/catalog?query=Search&type=movie')).json();
  assert.equal(search.status, 'available');
  assert.equal(search.provider, 'TMDB');
  assert.equal(search.license, null);
  assert.equal(search.items.length, 1);
  assert.equal(search.items[0].ratingSource, 'TMDB');
  assert.equal(search.items[0].topRank, null);
  assert.equal(search.items[0].topSource, null);
  assert.deepEqual(search.items[0].actors, []);
  assert.deepEqual(search.items[0].aliases, ['Original Movie']);
  assert.match(search.attribution, /TMDB/);
  assert.equal(search.attempts[0].status, 'unavailable');
  assert.match(seen[0], /^\/search\/movie\?/);
  assert.match(seen[0], /query=Search/);
  const popular = await (await api.request('/v1/catalog?type=tv&limit=1')).json();
  assert.equal(popular.items[0].title, 'Popular Series');
  assert.match(seen[1], /^\/tv\/popular\?/);
});

test('valid but unmatched licensed catalog falls through to TMDB; all-disabled is graceful', async t => {
  const licensed = await serve(t, (_req, res) => json(res, fixture()));
  const tmdb = await serve(t, (_req, res) => json(res, { results: [{ id: 8, title: 'Different' }] }));
  const enabled = await app(t, { LICENSED_CATALOG_ENABLED: 'true', LICENSED_CATALOG_URL: licensed,
    TMDB_READ_ACCESS_TOKEN: 'synthetic-tmdb-token', TMDB_API_URL: tmdb });
  const result = await (await enabled.request('/v1/catalog?query=Different')).json();
  assert.equal(result.provider, 'TMDB');
  assert.deepEqual(result.attempts.map(x => x.status), ['empty', 'available']);
  const disabled = await app(t);
  const unavailable = await (await disabled.request('/v1/catalog')).json();
  assert.equal(unavailable.status, 'unavailable');
  assert.equal(unavailable.fallback, 'libretv');
  assert.deepEqual(unavailable.items, []);
});

test('catalog URL redirects are rejected and request-selected targets are never contacted', async t => {
  let sinkRequests = 0;
  const sink = await serve(t, (_req, res) => { sinkRequests++; json(res, fixture()); });
  const redirect = await serve(t, (_req, res) => { res.writeHead(302, { location: sink }); res.end(); });
  const api = await app(t, { LICENSED_CATALOG_ENABLED: 'true', LICENSED_CATALOG_URL: redirect,
    TMDB_READ_ACCESS_TOKEN: 'synthetic-token', TMDB_API_URL: redirect });
  const result = await (await api.request('/v1/catalog')).json();
  assert.equal(result.status, 'unavailable');
  assert.equal(sinkRequests, 0);
  assert.equal((await api.request('/v1/catalog?url=' + encodeURIComponent(sink))).status, 400);
  assert.equal(sinkRequests, 0);
});

test('stream size, malformed JSON and upstream timeout fail closed with a LibreTV fallback hint', async t => {
  for (const mode of ['large', 'compressed', 'malformed', 'timeout', 'timeout-body', 'down']) {
    await t.test(mode, async t => {
      const upstream = await serve(t, (_req, res) => {
        if (mode === 'timeout') return; // The configured abort bounds a stalled body/header.
        if (mode === 'down') return json(res, { error: 'private provider detail' }, 503);
        if (mode === 'compressed') {
          const body = gzipSync(JSON.stringify(fixture()) + ' '.repeat(CATALOG_MAX_BYTES));
          res.writeHead(200, { 'content-type': 'application/json', 'content-encoding': 'gzip', 'content-length': body.length });
          return res.end(body);
        }
        res.writeHead(200, { 'content-type': 'application/json' });
        if (mode === 'timeout-body') { res.write('{'); return; }
        res.write(mode === 'large' ? JSON.stringify(fixture()) + ' '.repeat(CATALOG_MAX_BYTES) : '{not json');
        res.end();
      });
      const api = await app(t, { LICENSED_CATALOG_ENABLED: 'true', LICENSED_CATALOG_URL: upstream,
        CATALOG_TIMEOUT_MS: '100' });
      const response = await api.request('/v1/catalog');
      assert.equal(response.status, 200);
      const result = await response.json();
      assert.equal(result.status, 'unavailable');
      assert.equal(result.fallback, 'libretv');
      assert.deepEqual(result.items, []);
      assert.equal(JSON.stringify(result).includes('private'), false);
    });
  }
});

test('operator-supplied local file works, is reread for expiry, and rejects oversized files', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'cinematv-catalog-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const file = join(dir, 'catalog.json');
  await writeFile(file, JSON.stringify(fixture()));
  const api = await app(t, { LICENSED_CATALOG_ENABLED: 'true', LICENSED_CATALOG_FILE: file });
  assert.equal((await (await api.request('/v1/catalog')).json()).status, 'available');
  const expired = fixture(); expired.license.expiresAt = '2020-01-01T00:00:00Z';
  await writeFile(file, JSON.stringify(expired));
  assert.equal((await (await api.request('/v1/catalog')).json()).status, 'unavailable');
  await writeFile(file, ' '.repeat(CATALOG_MAX_BYTES + 1));
  assert.equal((await (await api.request('/v1/catalog')).json()).status, 'unavailable');
});

test('licensed chart pages cover 250 ranked synthetic items and stop after expiry without TMDB fallback', async t => {
  let document = fixture();
  const chartItem = rank => ({ providerId: 'rank-' + rank, title: 'Synthetic Film ' + rank,
    type: 'movie', aliases: ['Common alias'], actors: ['Common actor'],
    rating: 8, ratingScale: 10, ratingSource: 'Douban', topSource: '豆瓣', topRank: rank });
  document.items = Array.from({ length: 250 }, (_, index) => chartItem(250 - index));
  document.items.unshift({ ...chartItem(1), providerId: 'wrong-provenance', ratingSource: 'Other' });
  let licensedRequests = 0;
  let tmdbRequests = 0;
  const licensed = await serve(t, (_req, res) => { licensedRequests++; json(res, document); });
  const tmdb = await serve(t, (_req, res) => { tmdbRequests++; json(res, { results: [{ id: 1, title: 'Must not appear' }] }); });
  const api = await app(t, { LICENSED_CATALOG_ENABLED: 'true', LICENSED_CATALOG_URL: licensed,
    TMDB_READ_ACCESS_TOKEN: 'synthetic-token', TMDB_API_URL: tmdb });
  const collected = [];
  for (let offset = 0; offset < 250; offset += 50) {
    const result = await (await api.request('/v1/catalog?chart=douban-top250&type=movie&query=Common&limit=50&offset=' + offset)).json();
    assert.equal(result.status, 'available');
    assert.equal(result.total, 250);
    assert.equal(result.nextOffset, offset === 200 ? null : offset + 50);
    assert.equal(result.fallback, null);
    collected.push(...result.items.map(item => item.topRank));
  }
  assert.deepEqual(collected, Array.from({ length: 250 }, (_, index) => index + 1));
  assert.equal(licensedRequests, 5);
  assert.equal(tmdbRequests, 0);
  const empty = await (await api.request('/v1/catalog?chart=douban-top250&query=NoMatch&offset=50')).json();
  assert.equal(empty.status, 'empty');
  assert.equal(empty.total, 0);
  assert.equal(empty.nextOffset, null);
  assert.equal(empty.fallback, null);
  document.license.expiresAt = '2020-01-01T00:00:00Z';
  const expired = await (await api.request('/v1/catalog?chart=douban-top250&offset=50')).json();
  assert.equal(expired.status, 'unavailable');
  assert.deepEqual(expired.items, []);
  assert.equal(expired.fallback, null);
  assert.equal(tmdbRequests, 0);
});

test('TMDB pagination is explicitly unsupported, chart mode never falls back, and old requests stay first-page', async t => {
  let requests = 0;
  const tmdb = await serve(t, (_req, res) => { requests++; json(res, { results: [{ id: 1, title: 'Synthetic Film' }] }); });
  const api = await app(t, { TMDB_READ_ACCESS_TOKEN: 'synthetic-token', TMDB_API_URL: tmdb });
  const old = await (await api.request('/v1/catalog')).json();
  assert.equal(old.provider, 'TMDB');
  assert.equal('total' in old, false);
  assert.equal('nextOffset' in old, false);
  assert.equal(requests, 1);
  const offset = await (await api.request('/v1/catalog?offset=50')).json();
  assert.equal(offset.status, 'unavailable');
  assert.equal(offset.reason, 'tmdb_offset_unsupported');
  assert.equal(requests, 1);
  const chart = await (await api.request('/v1/catalog?chart=douban-top250')).json();
  assert.equal(chart.status, 'unavailable');
  assert.equal(chart.fallback, null);
  assert.equal(requests, 1);
  assert.equal((await api.request('/v1/catalog?offset=4951')).status, 400);
  assert.equal((await api.request('/v1/catalog?chart=untrusted')).status, 400);
});
