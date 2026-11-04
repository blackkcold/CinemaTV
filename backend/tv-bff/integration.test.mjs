import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';

function json(res, code, payload, headers = {}) {
  res.writeHead(code, { 'content-type': 'application/json', ...headers });
  res.end(JSON.stringify(payload));
}

async function serve(handler) {
  const server = createServer(handler);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  return { server, base: `http://127.0.0.1:${address.port}` };
}

test('BFF end-to-end: bearer auth, LibreTV search normalization, home, detail and input validation', async t => {
  const seen = [];
  const upstream = await serve(async (req, res) => {
    seen.push({ method: req.method, url: req.url, cookie: req.headers.cookie });
    if (req.url === '/api/status') return json(res, 200, {
      version: 'test-1', passwordRequired: false,
      defaultSources: [{ key: 'test', name: 'Demo', url: 'https://stream.example.org/api' }],
      defaultLiveSources: []
    });
    if (req.url?.startsWith('/api/douban')) return json(res, 200, { items: [
      { id: 'db-1', title: 'Example Movie', cover: 'https://cdn.example.org/film.jpg', rating: '9.0' }
    ] });
    if (req.url === '/api/search') {
      let body = '';
      for await (const chunk of req) body += chunk;
      const data = JSON.parse(body);
      assert.equal(data.wd, 'Test Movie');
      assert.equal(data.sources.length, 1);
      assert.equal(data.sources[0].key, 'test');
      return json(res, 200, { list: [
        { vodId: '123', name: 'Test Movie', pic: 'https://cdn.example.org/poster.jpg',
          sourceKey: 'test', sourceName: 'Demo', year: '2025' }
      ], failures: [] });
    }
    if (req.url?.startsWith('/api/detail')) return json(res, 200, {
      videoInfo: { title: 'Test Movie' }, episodes: ['https://stream.example.org/a.m3u8']
    });
    return json(res, 404, { error: 'missing' });
  });
  t.after(() => upstream.server.close());
  process.env.LIBRETV_URL = upstream.base;
  process.env.BFF_ACCESS_TOKEN = 'integration-test-long-bearer-key';
  process.env.LIBRETV_PASSWORD = '';
  const { createApp } = await import('./server.mjs?integration=authsearch');
  const app = await serve(createApp());
  t.after(() => app.server.close());

  const withoutToken = await fetch(app.base + '/v1/status');
  assert.equal(withoutToken.status, 401);

  const request = (path, init = {}) => fetch(app.base + path, {
    ...init, headers: { authorization: 'Bearer integration-test-long-bearer-key',
      ...init.headers }
  });
  const status = await request('/v1/status');
  assert.equal(status.status, 200);
  assert.equal((await status.json()).sourceCount, 1);

  const home = await request('/v1/home');
  assert.equal(home.status, 200);
  assert.equal((await home.json()).movies[0].title, 'Example Movie');

  const search = await request('/v1/search', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ query: 'Test Movie' })
  });
  assert.equal(search.status, 200);
  const searchResult = await search.json();
  assert.equal(searchResult.items[0].id, '123');
  assert.equal(searchResult.items[0].sourceKey, 'test');

  const detail = await request('/v1/title/123?sourceKey=test');
  assert.equal(detail.status, 200);
  assert.deepEqual((await detail.json()).episodes, ['https://stream.example.org/a.m3u8']);

  const missingSource = await request('/v1/title/123?sourceKey=unknown');
  assert.equal(missingSource.status, 404);

  const blankQuery = await request('/v1/search', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ query: '' })
  });
  assert.equal(blankQuery.status, 400);
  assert.ok(seen.some(x => x.url === '/api/search'));
});
