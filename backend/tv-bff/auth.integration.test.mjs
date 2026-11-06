import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';

const token = 'auth-regression-test-access-token';
const password = 'synthetic-password-for-loopback-tests';
let sequence = 0;

async function serve(t, handler) {
  const server = createServer(handler);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  return { server, base: `http://127.0.0.1:${server.address().port}` };
}

function json(res, status, body, headers = {}) {
  res.writeHead(status, { 'content-type': 'application/json', ...headers });
  res.end(JSON.stringify(body));
}

async function app(t, base) {
  process.env.LIBRETV_URL = base;
  process.env.LIBRETV_PASSWORD = password;
  process.env.BFF_ACCESS_TOKEN = token;
  const { createApp } = await import(`./server.mjs?auth-regression=${++sequence}`);
  const server = createApp();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  return () => fetch(`http://127.0.0.1:${server.address().port}/v1/status`, {
    headers: { authorization: `Bearer ${token}` }
  });
}

for (const status of [302, 307, 308]) {
  test(`authentication rejects HTTP ${status} without forwarding credentials`, async t => {
    const received = [];
    const redirect = await serve(t, async (req, res) => {
      let body = '';
      for await (const chunk of req) body += chunk;
      received.push({ method: req.method, body, cookie: req.headers.cookie });
      json(res, 200, { ok: true }, { 'set-cookie': 'unexpected=session; Path=/' });
    });
    let authenticationRequests = 0;
    const upstream = await serve(t, async (req, res) => {
      if (req.url !== '/api/auth') return json(res, 401, { error: 'authentication required' });
      authenticationRequests++;
      let body = '';
      for await (const chunk of req) body += chunk;
      assert.deepEqual(JSON.parse(body), { password });
      res.writeHead(status, { location: `${redirect.base}/capture` });
      res.end();
    });
    const request = await app(t, upstream.base);
    const response = await request();
    assert.equal(response.status, 502);
    assert.deepEqual(await response.json(), { error: 'Upstream service unavailable' });
    assert.equal(authenticationRequests, 1);
    assert.deepEqual(received, []);
  });
}

test('parallel requests share authentication and accept only the direct origin session', async t => {
  let authenticationRequests = 0;
  const cookies = [];
  const upstream = await serve(t, async (req, res) => {
    if (req.url === '/api/auth') {
      authenticationRequests++;
      // Release the other requests while this single authentication is pending.
      await new Promise(resolve => setTimeout(resolve, 30));
      return json(res, 200, { ok: true }, { 'set-cookie': 'libretv-session=synthetic; Path=/; HttpOnly' });
    }
    cookies.push(req.headers.cookie);
    if (req.headers.cookie !== 'libretv-session=synthetic') return json(res, 401, {});
    json(res, 200, { version: 'fixture', defaultSources: [], defaultLiveSources: [] });
  });
  const request = await app(t, upstream.base);
  const responses = await Promise.all([request(), request(), request()]);
  assert.deepEqual(responses.map(response => response.status), [200, 200, 200]);
  assert.equal(authenticationRequests, 1);
  assert.ok(cookies.includes('libretv-session=synthetic'));
});

for (const status of [401, 503, 200]) {
  test(`failed or cookieless direct authentication (${status}) leaves no accepted session`, async t => {
    let authRequests = 0;
    const upstream = await serve(t, (req, res) => {
      if (req.url === '/api/auth') { authRequests++; return json(res, status, {}); }
      assert.equal(req.headers.cookie, undefined);
      json(res, 401, {});
    });
    const request = await app(t, upstream.base);
    assert.equal((await request()).status, 502);
    assert.equal((await request()).status, 502);
    assert.equal(authRequests, 2, 'a failed attempt must release the authentication single-flight');
  });
}

test('authentication timeout returns a bounded failure and permits a later retry', { timeout: 25000 }, async t => {
  let authRequests = 0;
  const upstream = await serve(t, (req, res) => {
    if (req.url === '/api/auth') {
      authRequests++;
      if (authRequests === 1) return; // Controlled hung authentication, no external network.
      return json(res, 200, {}, { 'set-cookie': 'libretv-session=recovered; Path=/' });
    }
    if (!req.headers.cookie) return json(res, 401, {});
    json(res, 200, { defaultSources: [], defaultLiveSources: [] });
  });
  const request = await app(t, upstream.base);
  assert.equal((await request()).status, 502);
  assert.equal((await request()).status, 200);
  assert.equal(authRequests, 2);
});
