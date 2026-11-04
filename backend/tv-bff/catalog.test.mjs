import test from 'node:test';
import assert from 'node:assert/strict';
import { CATALOG_MAX_BYTES, createCatalogService, normalizeTmdbCatalogItem, parseCatalogRequest,
  readBoundedJson, selectCatalogItems, validateLicensedCatalog } from './catalog.mjs';

import { fixture } from './catalog-fixture.mjs';

test('licensed contract preserves independent score/chart provenance and aliases/actors', () => {
  const result = validateLicensedCatalog(fixture());
  assert.equal(result.items[0].ratingSource, 'Synthetic ratings');
  assert.equal(result.items[0].topSource, 'Synthetic chart');
  assert.equal(result.items[0].topRank, 5);
  assert.equal(result.items[2].rating, null);
  assert.equal(result.items[2].ratingSource, null);
  assert.equal(result.items[2].topRank, null);
  assert.equal(result.license.expiresAt, '2099-01-01T00:00:00Z');
  assert.equal(selectCatalogItems(result.items, { query: 'ＡＬＴＥＲＮＡＴＥ', type: 'movie', limit: 24 }).length, 1);
  assert.equal(selectCatalogItems(result.items, { query: 'fictional actor', type: 'movie', limit: 24 }).length, 1);
  assert.equal(selectCatalogItems(result.items, { type: 'movie', limit: 1 }).length, 1);
  assert.equal(selectCatalogItems(result.items, { type: 'tv', limit: 24 })[0].providerId, 'two');
});

test('licensed metadata fails closed without complete, unexpired provenance', () => {
  const changes = [
    x => { delete x.license; }, x => { delete x.attribution; },
    x => { x.license.expiresAt = '2020-01-01T00:00:00Z'; },
    x => { x.license.expiresAt = 'not-a-date'; },
    x => { x.license.expiresAt = '2099-02-30T00:00:00Z'; },
    x => { x.license.url = 'javascript:alert(1)'; },
    x => { delete x.items[0].ratingSource; }, x => { x.items[0].ratingScale = 0; },
    x => { x.items[0].rating = 11; }, x => { x.items[0].rating = '8.5'; },
    x => { delete x.items[0].topSource; }, x => { x.items[0].topRank = 1.5; },
    x => { x.items[0].poster = 'file:///private'; },
    x => { x.items[0].backdrop = 'https://user:secret@example.test/a.jpg'; },
    x => { x.items.push(x.items[0]); }, x => { x.provider = 'TMDB'; },
    x => { x.items[0].aliases = Array(31).fill('a'); },
    x => { x.items[0].actors = [42]; }, x => { x.items[0].year = '2025-1'; },
    x => { x.schemaVersion = 2; }, x => { x.items = Array(5001).fill(x.items[0]); }
  ];
  for (const change of changes) {
    const document = fixture();
    change(document);
    assert.throws(() => validateLicensedCatalog(document));
  }
  const document = fixture();
  assert.throws(() => validateLicensedCatalog(document, Date.parse(document.license.expiresAt)));
});

test('request input bounds reject URLs, profiles, repeated params, control characters and invalid types', () => {
  assert.deepEqual(parseCatalogRequest(new URLSearchParams()), { query: '', type: 'movie', limit: 24 });
  assert.deepEqual(parseCatalogRequest(new URLSearchParams('query=Film&type=tv&limit=50')), { query: 'Film', type: 'tv', limit: 50 });
  for (const input of ['url=https://evil.test', 'history=private', 'type=person', 'limit=51', 'limit=0',
    'limit=-1', 'limit=1.1', 'type=tv&type=movie', 'query=%00', 'query=' + 'x'.repeat(101)]) {
    assert.throws(() => parseCatalogRequest(new URLSearchParams(input)), input);
  }
});

test('TMDB scores are always labeled TMDB; missing votes/credits/ranks are not invented', () => {
  const item = normalizeTmdbCatalogItem({ id: 2, name: 'Series', original_name: 'Original',
    first_air_date: '2025-01-02', vote_average: 8.3, vote_count: 10, backdrop_path: '/a.jpg' }, 'tv');
  assert.equal(item.provider, 'TMDB');
  assert.equal(item.ratingSource, 'TMDB');
  assert.equal(item.ratingScale, 10);
  assert.deepEqual(item.aliases, ['Original']);
  assert.deepEqual(item.actors, []);
  assert.equal(item.topRank, null);
  assert.equal(item.topSource, null);
  assert.equal(item.backdrop, 'https://image.tmdb.org/t/p/w1280/a.jpg');
  assert.equal(normalizeTmdbCatalogItem({ id: 1, title: 'Unknown', vote_average: 0, vote_count: 0 }, 'movie').rating, null);
  assert.equal(normalizeTmdbCatalogItem({ id: 1, title: 'Adult', adult: true }, 'movie'), null);
  assert.equal(normalizeTmdbCatalogItem({ id: -1, title: 'Bad' }, 'movie'), null);
});

test('bounded JSON read rejects oversized declared and streaming payloads', async () => {
  assert.deepEqual(await readBoundedJson(new Response('{"ok":true}')), { ok: true });
  await assert.rejects(readBoundedJson(new Response('{}', { headers: { 'content-length': CATALOG_MAX_BYTES + 1 } })));
  await assert.rejects(readBoundedJson(new Response('x'.repeat(CATALOG_MAX_BYTES + 1))));
  await assert.rejects(readBoundedJson(new Response('{}', { status: 302, headers: { location: 'https://other.test' } })));
  await assert.rejects(readBoundedJson(new Response('not JSON')));
});

test('provider stays disabled unless enabled; invalid config is graceful and never leaks errors', async () => {
  const neverFetch = () => { throw new Error('Must not fetch'); };
  const disabled = createCatalogService({ LICENSED_CATALOG_URL: 'https://catalog.example.test' }, neverFetch);
  const response = await disabled({ query: '', type: 'movie', limit: 24 });
  assert.equal(response.status, 'unavailable');
  assert.equal(response.fallback, 'libretv');
  assert.deepEqual(response.attempts, [{ provider: 'licensed', status: 'disabled' }, { provider: 'TMDB', status: 'disabled' }]);
  for (const config of [
    { LICENSED_CATALOG_FILE: '/some/path', LICENSED_CATALOG_URL: 'https://catalog.example.test' },
    { LICENSED_CATALOG_URL: 'file:///private' }, {},
    { LICENSED_CATALOG_URL: 'https://user:password@example.test' }
  ]) {
    const service = createCatalogService({ LICENSED_CATALOG_ENABLED: 'true', ...config }, neverFetch);
    const result = await service({ query: '', type: 'movie', limit: 24 });
    assert.equal(result.status, 'unavailable');
    assert.equal(result.attempts[0].status, 'unavailable');
    assert.equal(result.provider, null);
    assert.equal(JSON.stringify(result).includes('password'), false);
  }
});

test('licensed hits take priority and catalog-only TMDB disable is respected', async () => {
  let requests = 0;
  const fetchImpl = async (target, options) => {
    requests++;
    assert.equal(String(target), 'https://catalog.example.test/fixed.json');
    assert.equal(options.redirect, 'error');
    assert.equal(options.headers.Authorization, undefined);
    return new Response(JSON.stringify(fixture()));
  };
  const service = createCatalogService({ LICENSED_CATALOG_ENABLED: 'true',
    LICENSED_CATALOG_URL: 'https://catalog.example.test/fixed.json', TMDB_READ_ACCESS_TOKEN: 'synthetic-token' }, fetchImpl);
  const result = await service({ query: 'Example', type: 'movie', limit: 24 });
  assert.equal(result.provider, 'Synthetic provider');
  assert.equal(result.attempts.length, 1);
  assert.equal(requests, 1);
  const disabled = createCatalogService({ TMDB_READ_ACCESS_TOKEN: 'synthetic-token', CATALOG_TMDB_ENABLED: 'false' },
    () => { throw new Error('TMDB must remain disabled'); });
  assert.equal((await disabled({ query: 'Example', type: 'movie', limit: 24 })).attempts[1].status, 'disabled');
});

test('empty licensed result is retained when fallback fails, without losing provenance', async () => {
  const service = createCatalogService({ LICENSED_CATALOG_ENABLED: 'true',
    LICENSED_CATALOG_URL: 'https://catalog.example.test/fixed.json', TMDB_READ_ACCESS_TOKEN: 'synthetic-token' },
  async target => new Response(JSON.stringify(String(target).includes('catalog.example.test') ? fixture() : { error: 'down' })));
  const result = await service({ query: 'No such film', type: 'movie', limit: 24 });
  assert.equal(result.status, 'empty');
  assert.equal(result.provider, 'Synthetic provider');
  assert.equal(result.fallback, 'libretv');
  assert.ok(result.license);
  assert.deepEqual(result.attempts.map(x => x.status), ['empty', 'unavailable']);
});

test('TMDB empty results remain empty and duplicate provider IDs are collapsed', async () => {
  const config = { TMDB_READ_ACCESS_TOKEN: 'synthetic-token' };
  const empty = createCatalogService(config, async () => new Response('{"results":[]}'));
  assert.equal((await empty({ query: 'Missing', type: 'movie', limit: 24 })).status, 'empty');
  const duplicate = { id: 1, title: 'Synthetic Film' };
  const deduplicated = createCatalogService(config,
    async () => new Response(JSON.stringify({ results: [duplicate, duplicate] })));
  assert.equal((await deduplicated({ query: '', type: 'movie', limit: 24 })).items.length, 1);
});
