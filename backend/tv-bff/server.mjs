import { createServer } from 'node:http';

const port = Number(process.env.PORT || 8787);
const upstreamBase = String(process.env.LIBRETV_URL || '').replace(/\/+$/, '');
const adminPassword = String(process.env.LIBRETV_PASSWORD || '');
const accessToken = String(process.env.BFF_ACCESS_TOKEN || '');
const maxBytes = 65536;
const timeoutMs = 18000;
const tmdbToken = String(process.env.TMDB_READ_ACCESS_TOKEN || '');
const tmdbApiBase = String(process.env.TMDB_API_URL || 'https://api.themoviedb.org/3').replace(/\/+$/, '');
const metadataCache = new Map();
const metadataCacheTtl = 24 * 60 * 60 * 1000;
let cookie = '';
let authPromise = null;

export class ApiError extends Error {
  constructor(message, status = 502) { super(message); this.status = status; }
}

function validateConfig() {
  const url = new URL(upstreamBase);
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('LIBRETV_URL must be HTTP(S)');
  if (!accessToken || accessToken.length < 16) throw new Error('BFF_ACCESS_TOKEN must contain 16+ characters');
}

async function authenticate() {
  if (!adminPassword) return;
  if (authPromise) return authPromise;
  authPromise = (async () => {
    const res = await fetch(upstreamBase + '/api/auth', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ password: adminPassword }), signal: AbortSignal.timeout(timeoutMs)
    });
    if (!res.ok) throw new ApiError('LibreTV authentication failed', 502);
    const nextCookie = res.headers.get('set-cookie')?.split(';')[0] || '';
    if (!nextCookie) throw new ApiError('LibreTV did not issue session cookie', 502);
    cookie = nextCookie;
  })();
  try { await authPromise; } finally { authPromise = null; }
}

async function upstream(path, { method = 'GET', body, retry = true } = {}) {
  const headers = { accept: 'application/json' };
  if (cookie) headers.cookie = cookie;
  if (body !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(upstreamBase + path, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs), redirect: 'manual'
  });
  if (res.status === 401 && retry && adminPassword) {
    cookie = '';
    await authenticate();
    return upstream(path, { method, body, retry: false });
  }
  if (!res.ok) throw new ApiError('LibreTV upstream error: HTTP ' + res.status, res.status === 404 ? 404 : 502);
  return res.json();
}

export function normalizeItem(item = {}) {
  return {
    id: String(item.vodId || item.id || ''),
    title: String(item.name || item.title || ''),
    poster: String(item.pic || item.cover || ''),
    year: String(item.year || ''),
    category: String(item.typeName || ''),
    rating: String(item.rating || ''),
    sourceKey: String(item.sourceKey || ''),
    sourceName: String(item.sourceName || '')
  };
}

async function sources() {
  const data = await upstream('/api/status');
  const allowed = (data.defaultSources || []).filter(s => /^https?:\/\//.test(s.url || ''));
  const extra = JSON.parse(process.env.EXTRA_SOURCES_JSON || '[]');
  return { status: data, vod: [...allowed, ...extra.filter(s => /^https?:\/\//.test(s.url || ''))] };
}

async function collect(req) {
  let length = 0;
  const chunks = [];
  for await (const chunk of req) {
    length += chunk.length;
    if (length > maxBytes) throw new ApiError('Request body too large', 413);
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new ApiError('Invalid JSON body', 400); }
}

function send(res, status, body) {
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    'x-frame-options': 'DENY'
  });
  res.end(JSON.stringify(body));
}

function requireKey(s, max = 120) {
  if (!s || s.length > max || /[\x00-\x1f]/.test(s)) throw new ApiError('Invalid parameter', 400);
  return s;
}

/** Exact normalized-name + compatible-year check; never invent a backdrop. */
export function selectMetadata(title, year, candidates = [], type = 'movie') {
  const normalize = value => String(value || '').normalize('NFKC')
    .toLowerCase().replace(/[\p{P}\p{Z}\s]+/gu, '');
  const wanted = normalize(title);
  if (!wanted) return null;
  const allowed = candidates.filter(candidate => {
    const candidateName = type === 'tv' ? candidate.name : candidate.title;
    const original = type === 'tv' ? candidate.original_name : candidate.original_title;
    if (normalize(candidateName) !== wanted && normalize(original) !== wanted) return false;
    const release = String((type === 'tv' ? candidate.first_air_date : candidate.release_date) || '').slice(0, 4);
    return !year || year === release;
  });
  // Ambiguous year-free titles should never receive an assumed image.
  if (allowed.length !== 1) return null;
  return allowed[0] || null;
}

function tmdbImage(path, size = 'w1280') {
  return typeof path === 'string' && /^\/[a-zA-Z0-9_\/-]+\.(?:jpg|jpeg|png|webp)$/i.test(path)
    ? 'https://image.tmdb.org/t/p/' + size + path : '';
}

async function resolveMetadata(title, year, type) {
  if (!tmdbToken) throw new ApiError('Metadata enrichment not configured', 503);
  const key = [type, title, year].join('|');
  const cached = metadataCache.get(key);
  if (cached && cached.expires > Date.now()) return cached.value;
  const target = new URL(tmdbApiBase + '/search/' + type);
  target.searchParams.set('query', title);
  target.searchParams.set('language', 'zh-CN');
  if (year) target.searchParams.set(type === 'movie' ? 'year' : 'first_air_date_year', year);
  const response = await fetch(target, {
    headers: { Authorization: 'Bearer ' + tmdbToken, accept: 'application/json' },
    signal: AbortSignal.timeout(9000)
  });
  if (!response.ok) throw new ApiError('Metadata provider returned HTTP ' + response.status, 502);
  const data = await response.json();
  const result = selectMetadata(title, year, Array.isArray(data.results) ? data.results : [], type);
  const value = result ? {
    backdrop: tmdbImage(result.backdrop_path), poster: tmdbImage(result.poster_path, 'w500'),
    overview: String(result.overview || '').slice(0, 4000),
    releaseDate: String(type === 'tv' ? result.first_air_date || '' : result.release_date || ''),
    provider: 'TMDB', providerId: result.id
  } : null;
  metadataCache.set(key, { value, expires: Date.now() + metadataCacheTtl });
  if (metadataCache.size > 300) metadataCache.delete(metadataCache.keys().next().value);
  return value;
}

async function handle(req, res) {
  const url = new URL(req.url || '/', 'http://localhost');
  if (url.pathname === '/healthz') return send(res, 200, { ok: true, service: 'cinematv-bff' });
  if (req.headers.authorization !== 'Bearer ' + accessToken) throw new ApiError('Unauthorized', 401);

  if (req.method === 'GET' && url.pathname === '/v1/status') {
    const { status, vod } = await sources();
    return send(res, 200, {
      upstreamVersion: status.version, passwordRequired: status.passwordRequired,
      sourceCount: vod.length, liveCount: (status.defaultLiveSources || []).length
    });
  }
  if (req.method === 'GET' && url.pathname === '/v1/home') {
    const [movies, series] = await Promise.all([
      upstream('/api/douban?type=movie&pageSize=24'),
      upstream('/api/douban?type=tv&pageSize=24')
    ]);
    return send(res, 200, {
      movies: (movies.items || []).map(normalizeItem),
      series: (series.items || []).map(normalizeItem)
    });
  }
  if (req.method === 'POST' && url.pathname === '/v1/search') {
    const { query } = await collect(req);
    const wd = requireKey(String(query || '').trim(), 100);
    const { vod } = await sources();
    if (!vod.length) throw new ApiError('No LibreTV sources configured', 422);
    const data = await upstream('/api/search', {
      method: 'POST', body: { wd, sources: vod, filterAdult: true }
    });
    return send(res, 200, { items: (data.list || []).map(normalizeItem), failures: data.failures || [] });
  }
  if (req.method === 'GET' && url.pathname.startsWith('/v1/title/')) {
    const id = requireKey(decodeURIComponent(url.pathname.substring('/v1/title/'.length)), 100);
    if (!/^[\w-]+$/.test(id)) throw new ApiError('Invalid content ID', 400);
    const sourceKey = requireKey(url.searchParams.get('sourceKey') || '');
    const { vod } = await sources();
    const source = vod.find(s => s.key === sourceKey);
    if (!source) throw new ApiError('Source not configured', 404);
    const detail = await upstream('/api/detail?id=' + encodeURIComponent(id) +
      '&source=' + encodeURIComponent(JSON.stringify(source)));
    return send(res, 200, detail);
  }
  if (req.method === 'GET' && url.pathname === '/v1/metadata') {
    const title = requireKey(String(url.searchParams.get('title') || '').trim(), 120);
    const year = String(url.searchParams.get('year') || '').trim();
    if (year && !/^\d{4}$/.test(year)) throw new ApiError('Invalid year', 400);
    const type = url.searchParams.get('type') || 'movie';
    if (!['movie', 'tv'].includes(type)) throw new ApiError('Invalid title type', 400);
    const metadata = await resolveMetadata(title, year, type);
    return send(res, 200, { item: metadata, status: metadata ? 'matched' : 'unmatched' });
  }
  if (req.method === 'GET' && url.pathname === '/v1/live/channels') {
    const sourceKey = requireKey(url.searchParams.get('sourceKey') || '');
    const status = await upstream('/api/status');
    const source = (status.defaultLiveSources || []).find(s => s.key === sourceKey);
    if (!source) throw new ApiError('Live source not configured', 404);
    const data = await upstream('/api/live/playlist?url=' + encodeURIComponent(source.url));
    return send(res, 200, data);
  }
  throw new ApiError('Unknown route', 404);
}

export function createApp() {
  validateConfig();
  return createServer(async (req, res) => {
    try { await handle(req, res); }
    catch (error) {
      console.error('[cinematv-bff]', error?.message || 'unknown error');
      send(res, error instanceof ApiError ? error.status : 502,
        { error: error instanceof ApiError && error.status < 500 ? error.message : 'Upstream service unavailable' });
    }
  });
}

if (process.argv[1]?.endsWith('/server.mjs')) {
  createApp().listen(port, '0.0.0.0', () => console.log('CinemaTV BFF listening on', port));
}
