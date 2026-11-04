import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';

async function serve(handler) {
  const server = createServer(handler);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return { server, base: 'http://127.0.0.1:' + server.address().port };
}

test('authenticated optional metadata lookup supplies correct matched backdrop', async t => {
  const tmdb = await serve((req, res) => {
    assert.match(req.url, /search\/movie/);
    assert.equal(req.headers.authorization, 'Bearer mocked-tmdb-access-token');
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ results: [
      { id: 1, title: 'Sample', release_date: '1998-03-01', backdrop_path: '/old.jpg' },
      { id: 2, title: 'Sample', release_date: '2025-03-01',
        backdrop_path: '/new.jpg', poster_path: '/poster.jpg', overview: 'Overview' }
    ] }));
  });
  t.after(() => tmdb.server.close());

  process.env.LIBRETV_URL = 'http://127.0.0.1:8788';
  process.env.BFF_ACCESS_TOKEN = 'metadata-test-long-bearer-key';
  process.env.TMDB_READ_ACCESS_TOKEN = 'mocked-tmdb-access-token';
  process.env.TMDB_API_URL = tmdb.base;
  const { createApp } = await import('./server.mjs?metadataIntegration');
  const api = createApp();
  api.listen(0, '127.0.0.1');
  await once(api, 'listening');
  t.after(() => api.close());

  const base = 'http://127.0.0.1:' + api.address().port;
  const unauthorized = await fetch(base + '/v1/metadata?title=Sample&year=2025');
  assert.equal(unauthorized.status, 401);
  const headers = { Authorization: 'Bearer metadata-test-long-bearer-key' };
  const ok = await fetch(base + '/v1/metadata?title=Sample&year=2025&type=movie', { headers });
  assert.equal(ok.status, 200);
  const result = await ok.json();
  assert.equal(result.status, 'matched');
  assert.equal(result.item.providerId, 2);
  assert.equal(result.item.backdrop, 'https://image.tmdb.org/t/p/original/new.jpg');

  const ambiguous = await fetch(base + '/v1/metadata?title=Sample&type=movie', { headers });
  assert.equal((await ambiguous.json()).status, 'unmatched');
  const malformed = await fetch(base + '/v1/metadata?title=Sample&year=2o25', { headers });
  assert.equal(malformed.status, 400);
});
