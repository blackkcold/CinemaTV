import { open } from 'node:fs/promises';
import { constants } from 'node:fs';
import { createDoubanProvider } from './douban-provider.mjs';

export const CATALOG_MAX_BYTES = 1024 * 1024;
export const CATALOG_MAX_ITEMS = 5000;
const MAX_RESULTS = 50;
const MAX_OFFSET = CATALOG_MAX_ITEMS - MAX_RESULTS;
const TMDB_ATTRIBUTION = 'This product uses the TMDB API but is not endorsed or certified by TMDB.';

function invalid(message = 'Invalid catalog') { throw new Error(message); }
function text(value, max, required = false) {
  if (value == null && !required) return '';
  if (typeof value !== 'string' || value.length > max || /[\x00-\x1f\x7f]/.test(value)) invalid();
  const result = value.trim();
  if (required && !result) invalid();
  return result;
}
function url(value, required = false) {
  const raw = text(value, 2048, required);
  if (!raw) return '';
  const parsed = new URL(raw);
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.hash) invalid();
  return parsed.href;
}
function strings(value) {
  if (value == null) return [];
  if (!Array.isArray(value) || value.length > 30) invalid();
  return [...new Set(value.map(item => text(item, 200, true)))];
}
const normalized = value => value.normalize('NFKC').toLowerCase().replace(/\s+/gu, ' ').trim();

/** Validate the entire operator-supplied document before exposing any metadata.
 * These declarations are required provenance, NOT proof that the operator owns rights.
 */
export function validateLicensedCatalog(document, now = Date.now()) {
  if (!document || document.schemaVersion !== 1) invalid();
  const provider = text(document.provider, 120, true);
  if (provider.toUpperCase() === 'TMDB') invalid(); // Reserved for the actual TMDB adapter.
  const attribution = text(document.attribution, 1000, true);
  const license = {
    name: text(document.license?.name, 200, true),
    url: url(document.license?.url, true),
    expiresAt: text(document.license?.expiresAt, 40, true)
  };
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(license.expiresAt) ||
      !Number.isFinite(Date.parse(license.expiresAt)) ||
      new Date(license.expiresAt).toISOString().slice(0, 19) !== license.expiresAt.slice(0, 19) ||
      Date.parse(license.expiresAt) <= now) invalid();
  if (!Array.isArray(document.items) || document.items.length > CATALOG_MAX_ITEMS) invalid();
  const ids = new Set();
  const items = document.items.map(item => {
    if (!item || !['movie', 'tv'].includes(item.type)) invalid();
    const providerId = text(item.providerId, 120, true);
    const id = 'catalog:' + encodeURIComponent(provider) + ':' + item.type + ':' + encodeURIComponent(providerId);
    if (ids.has(id)) invalid();
    ids.add(id);
    const year = text(item.year, 4);
    if (year && !/^\d{4}$/.test(year)) invalid();
    const rating = item.rating ?? null;
    const ratingSource = rating === null ? null : text(item.ratingSource, 120, true);
    const ratingScale = rating === null ? null : item.ratingScale;
    if (rating !== null && (!Number.isFinite(rating) || !Number.isFinite(ratingScale) ||
        ratingScale <= 0 || ratingScale > 100 || rating < 0 || rating > ratingScale)) invalid();
    const topRank = item.topRank ?? null;
    const topSource = topRank === null ? null : text(item.topSource, 120, true);
    if (topRank !== null && (!Number.isSafeInteger(topRank) || topRank < 1 || topRank > 100000)) invalid();
    return {
      id, provider, providerId, title: text(item.title, 300, true), type: item.type, year,
      aliases: strings(item.aliases), actors: strings(item.actors),
      poster: url(item.poster), backdrop: url(item.backdrop), overview: text(item.overview, 4000),
      rating, ratingSource, ratingScale, topRank, topSource
    };
  });
  return { provider, attribution, license, items };
}

export function selectCatalogPage(items, { query = '', type = 'movie', limit = 24, offset = 0, chart = '' } = {}) {
  const wanted = normalized(query);
  const douban = source => ['douban', '豆瓣'].includes(normalized(source || ''));
  const filtered = items.filter(item => item.type === type && (!wanted ||
    [item.title, ...item.aliases, ...item.actors].some(value => normalized(value).includes(wanted))) &&
    (chart !== 'douban-top250' || (douban(item.topSource) && douban(item.ratingSource) &&
      Number.isSafeInteger(item.topRank) && item.topRank >= 1 && item.topRank <= 250 &&
      item.rating !== null && item.ratingScale === 10)));
  if (chart === 'douban-top250') filtered.sort((a, b) => a.topRank - b.topRank || a.id.localeCompare(b.id));
  const page = filtered.slice(offset, offset + Math.min(MAX_RESULTS, Math.max(1, limit)));
  const next = offset + page.length;
  return { items: page, total: filtered.length,
    nextOffset: page.length && next < filtered.length && next <= MAX_OFFSET ? next : null };
}

export function selectCatalogItems(items, request = {}) {
  return selectCatalogPage(items, request).items;
}

export function parseCatalogRequest(params) {
  const allowed = new Set(['query', 'type', 'limit', 'offset', 'chart']);
  for (const key of params.keys()) {
    if (!allowed.has(key) || params.getAll(key).length !== 1) invalid('Invalid catalog parameter');
  }
  const query = text(params.get('query') ?? '', 100);
  const type = params.get('type') ?? 'movie';
  const rawLimit = params.get('limit') ?? '24';
  const rawOffset = params.get('offset') ?? '0';
  const chart = params.get('chart') ?? '';
  if (!['movie', 'tv'].includes(type) || !/^\d{1,2}$/.test(rawLimit) ||
      Number(rawLimit) < 1 || Number(rawLimit) > MAX_RESULTS ||
      !/^\d{1,4}$/.test(rawOffset) || Number(rawOffset) > MAX_OFFSET ||
      (params.has('chart') && chart !== 'douban-top250')) invalid('Invalid catalog parameter');
  return { query, type, limit: Number(rawLimit), offset: Number(rawOffset), chart };
}

/** Enforces a decoded-byte limit even when upstream omits or lies about Content-Length. */
export async function readBoundedJson(response, maxBytes = CATALOG_MAX_BYTES) {
  if (!response.ok || response.status >= 300 || !response.body) {
    await response.body?.cancel();
    invalid('Catalog provider unavailable');
  }
  if (Number(response.headers.get('content-length')) > maxBytes) {
    await response.body.cancel();
    invalid('Catalog response too large');
  }
  const reader = response.body.getReader();
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > maxBytes) invalid('Catalog response too large');
      chunks.push(value);
    }
    return JSON.parse(Buffer.concat(chunks, length).toString('utf8'));
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

async function readCatalogFile(path) {
  // O_NONBLOCK prevents an accidentally configured FIFO from stalling before stat().
  const file = await open(path, constants.O_RDONLY | constants.O_NONBLOCK);
  try {
    const stat = await file.stat();
    if (!stat.isFile() || stat.size > CATALOG_MAX_BYTES) invalid();
    const buffer = Buffer.alloc(CATALOG_MAX_BYTES + 1);
    let length = 0;
    while (length < buffer.length) {
      const { bytesRead } = await file.read(buffer, length, buffer.length - length, length);
      if (!bytesRead) break;
      length += bytesRead;
    }
    if (length > CATALOG_MAX_BYTES) invalid();
    return JSON.parse(buffer.subarray(0, length).toString('utf8'));
  } finally { await file.close(); }
}

function tmdbImage(path, size) {
  return typeof path === 'string' && /^\/[a-zA-Z0-9_\/-]+\.(?:jpg|jpeg|png|webp)$/i.test(path)
    ? 'https://image.tmdb.org/t/p/' + size + path : '';
}

export function normalizeTmdbCatalogItem(item, type) {
  if (!item || item.adult === true || !Number.isSafeInteger(item.id) || item.id < 1) return null;
  const title = typeof (type === 'tv' ? item.name : item.title) === 'string'
    ? (type === 'tv' ? item.name : item.title).trim().slice(0, 300) : '';
  if (!title) return null;
  const original = type === 'tv' ? item.original_name : item.original_title;
  const date = type === 'tv' ? item.first_air_date : item.release_date;
  const rated = Number.isFinite(item.vote_average) && item.vote_average >= 0 && item.vote_average <= 10 &&
    Number.isSafeInteger(item.vote_count) && item.vote_count > 0;
  return {
    id: 'tmdb:' + type + ':' + item.id, provider: 'TMDB', providerId: String(item.id), type, title,
    year: typeof date === 'string' && /^\d{4}-/.test(date) ? date.slice(0, 4) : '',
    aliases: typeof original === 'string' && original.trim() && original !== title ? [original.trim().slice(0, 200)] : [],
    actors: [], poster: tmdbImage(item.poster_path, 'w500'), backdrop: tmdbImage(item.backdrop_path, 'w1280'),
    overview: typeof item.overview === 'string' ? item.overview.slice(0, 4000) : '',
    rating: rated ? item.vote_average : null, ratingSource: rated ? 'TMDB' : null,
    ratingScale: rated ? 10 : null, topRank: null, topSource: null
  };
}

/** Configuration is operator-only; request parameters can never select a URL/file or upstream headers. */
export function createCatalogService(env = process.env, fetchImpl = fetch, dependencies = {}) {
  const douban = dependencies.doubanProvider || createDoubanProvider(env, { fetchImpl });
  const enabled = env.LICENSED_CATALOG_ENABLED === 'true';
  const catalogFile = String(env.LICENSED_CATALOG_FILE || '');
  const catalogUrl = String(env.LICENSED_CATALOG_URL || '');
  const tmdbToken = String(env.TMDB_READ_ACCESS_TOKEN || '');
  const tmdbEnabled = env.CATALOG_TMDB_ENABLED !== 'false' && Boolean(tmdbToken);
  const tmdbBase = String(env.TMDB_API_URL || 'https://api.themoviedb.org/3').replace(/\/+$/, '');
  const configuredTimeout = Number(env.CATALOG_TIMEOUT_MS || 5000);
  const timeout = Number.isInteger(configuredTimeout) && configuredTimeout >= 100 && configuredTimeout <= 10000
    ? configuredTimeout : 5000;
  async function getJson(target, headers = { accept: 'application/json' }) {
    return readBoundedJson(await fetchImpl(target, {
      headers, signal: AbortSignal.timeout(timeout), redirect: 'error'
    }));
  }
  return async function catalog(request) {
    const attempts = [];
    const chartOnly = request.chart === 'douban-top250';
    const offset = request.offset ?? 0;
    const unavailable = () => ({ status: 'unavailable', provider: null, items: [], attribution: null,
      license: null, attempts, fallback: chartOnly ? null : 'libretv' });
    let emptyResult = null;
    if (chartOnly && request.type === 'movie' && douban.enabled) {
      const { document, acquisition } = await douban.load();
      if (!document) {
        attempts.push({ provider: 'Douban', status: 'unavailable' });
        return { ...unavailable(), acquisition };
      }
      try {
        const result = validateLicensedCatalog(document);
        const page = selectCatalogPage(result.items, request);
        const status = page.items.length ? 'available' : 'empty';
        attempts.push({ provider: 'Douban', status });
        return { ...result, ...page, status, attempts, fallback: null, acquisition };
      } catch {
        attempts.push({ provider: 'Douban', status: 'unavailable' });
        return { ...unavailable(), acquisition: { ...acquisition, refreshing: false, state: 'unavailable',
          warnings: [...acquisition.warnings, 'invalid_or_expired_acquired_catalog'] } };
      }
    }
    if (enabled) {
      try {
        if (Boolean(catalogFile) === Boolean(catalogUrl)) invalid();
        const document = catalogFile ? await readCatalogFile(catalogFile) : await getJson(url(catalogUrl, true));
        const result = validateLicensedCatalog(document);
        const page = selectCatalogPage(result.items, request);
        const { items } = page;
        const status = items.length ? 'available' : 'empty';
        attempts.push({ provider: result.provider, status });
        const response = { ...result, ...page, status, attempts, fallback: items.length || chartOnly ? null : 'libretv' };
        // Never replay TMDB page one for a later licensed page or a chart-only request.
        if (items.length || offset > 0 || chartOnly) return response;
        emptyResult = response;
      } catch {
        attempts.push({ provider: 'licensed', status: 'unavailable' });
      }
    } else attempts.push({ provider: 'licensed', status: 'disabled' });
    if (chartOnly) {
      if (request.type === 'movie' && !enabled) {
        return { ...unavailable(), acquisition: (await douban.load()).acquisition };
      }
      return unavailable();
    }
    if (offset > 0 && tmdbEnabled) {
      attempts.push({ provider: 'TMDB', status: 'unavailable' });
      return { ...unavailable(), reason: 'tmdb_offset_unsupported' };
    }
    if (tmdbEnabled) {
      try {
        const checkedBase = new URL(url(tmdbBase, true));
        if (checkedBase.search) invalid();
        const target = new URL(tmdbBase + (request.query ? '/search/' + request.type : '/' + request.type + '/popular'));
        target.searchParams.set('language', 'zh-CN');
        target.searchParams.set('include_adult', 'false');
        target.searchParams.set('page', '1');
        if (request.query) target.searchParams.set('query', request.query);
        const data = await getJson(target, { Authorization: 'Bearer ' + tmdbToken, accept: 'application/json' });
        if (!Array.isArray(data.results) || data.results.length > CATALOG_MAX_ITEMS) invalid();
        const mapped = data.results.map(item => normalizeTmdbCatalogItem(item, request.type)).filter(Boolean);
        const items = [...new Map(mapped.map(item => [item.id, item])).values()].slice(0, request.limit);
        const status = items.length ? 'available' : 'empty';
        attempts.push({ provider: 'TMDB', status });
        const response = { provider: 'TMDB', attribution: TMDB_ATTRIBUTION, license: null, items,
          status, attempts, fallback: items.length ? null : 'libretv' };
        if (items.length) return response;
        emptyResult = response;
      } catch { attempts.push({ provider: 'TMDB', status: 'unavailable' }); }
    } else attempts.push({ provider: 'TMDB', status: 'disabled' });
    return emptyResult || unavailable();
  };
}
