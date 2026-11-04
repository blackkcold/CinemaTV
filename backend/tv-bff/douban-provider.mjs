import { open, mkdir, rename, unlink } from 'node:fs/promises';
import { constants } from 'node:fs';
import { isAbsolute, join, resolve } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { parseTop250Html, isBlockedHtml } from './douban-parser.mjs';

const ORIGIN = 'https://movie.douban.com';
const MAX_BYTES = 1024 * 1024;
const WINDOW_MS = 10 * 60 * 1000;
const GAP_MS = 3000;
const REQUEST_BUDGET = 20;
const MAX_DATE = 8640000000000000;
const providers = new Map();
const iso = value => value == null ? null : new Date(value).toISOString();
const hash = value => createHash('sha256').update(value).digest('hex');
const initial = () => ({ version: 1, gate: { requests: [], nextRequestAt: 0, cooldownUntil: 0,
  failures: 0, blocked: false, reason: null }, partial: null, complete: null });

function boundedNumber(value, fallback, min, max) {
  const number = Number(value);
  return Number.isFinite(number) && number >= min && number <= max ? number : fallback;
}
function declaration(env, now) {
  if (env.DOUBAN_WRITTEN_PERMISSION !== 'true') throw new Error('written_permission_required');
  const name = String(env.DOUBAN_LICENSE_NAME || '').trim();
  const rawUrl = String(env.DOUBAN_LICENSE_URL || '').trim();
  const expiresAt = String(env.DOUBAN_LICENSE_EXPIRES_AT || '');
  const attribution = String(env.DOUBAN_ATTRIBUTION || '').trim();
  if (!name || name.length > 200 || !attribution || attribution.length > 1000 ||
      /[\x00-\x1f\x7f]/.test(name + attribution) || rawUrl.length > 2048) throw new Error('invalid_rights_declaration');
  let url;
  try { url = new URL(rawUrl); } catch { throw new Error('invalid_rights_declaration'); }
  if (url.protocol !== 'https:' || url.username || url.password || url.hash) throw new Error('invalid_rights_declaration');
  const expires = Date.parse(expiresAt);
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(expiresAt) ||
      !Number.isFinite(expires) || new Date(expires).toISOString().slice(0, 19) !== expiresAt.slice(0, 19)) {
    throw new Error('invalid_rights_declaration');
  }
  if (expires <= now) throw new Error('rights_expired');
  return { license: { name, url: url.href, expiresAt }, attribution };
}

export function parseRetryAfter(value, now) {
  if (typeof value !== 'string' || !value.trim()) return null;
  const raw = value.trim();
  if (/^\d+$/.test(raw)) return Math.min(MAX_DATE, now + Math.min(Number(raw) * 1000, MAX_DATE));
  const date = Date.parse(raw);
  return Number.isFinite(date) ? Math.max(now, date) : null;
}

export async function readDoubanHtml(response) {
  if (!response.body) throw new Error('empty_body');
  if (Number(response.headers.get('content-length')) > MAX_BYTES) {
    await response.body.cancel(); throw new Error('body_too_large');
  }
  const reader = response.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BYTES) throw new Error('body_too_large');
      chunks.push(value);
    }
    return Buffer.concat(chunks, size).toString('utf8');
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}

async function boundedFile(path, max = MAX_BYTES) {
  const file = await open(path, constants.O_RDONLY | constants.O_NONBLOCK | constants.O_NOFOLLOW);
  try {
    const stat = await file.stat();
    if (!stat.isFile() || stat.size > max) throw new Error('invalid_state_file');
    const buffer = Buffer.alloc(max + 1);
    let size = 0;
    while (size < buffer.length) {
      const { bytesRead } = await file.read(buffer, size, buffer.length - size, size);
      if (!bytesRead) break;
      size += bytesRead;
    }
    if (size > max) throw new Error('invalid_state_file');
    return JSON.parse(buffer.subarray(0, size).toString('utf8'));
  } finally { await file.close(); }
}

function validateState(value) {
  const gate = value?.gate;
  if (value?.version !== 1 || !gate || !Array.isArray(gate.requests) || gate.requests.length > REQUEST_BUDGET ||
      gate.requests.some(time => !Number.isFinite(time) || time < 0 || time > MAX_DATE) ||
      ['nextRequestAt', 'cooldownUntil', 'failures'].some(key => !Number.isFinite(gate[key]) || gate[key] < 0) ||
      gate.nextRequestAt > MAX_DATE || gate.cooldownUntil > MAX_DATE || typeof gate.blocked !== 'boolean' ||
      !Number.isSafeInteger(gate.failures) || gate.failures > 20 ||
      (gate.reason !== null && (typeof gate.reason !== 'string' || gate.reason.length > 80))) {
    throw new Error('invalid_persistent_state');
  }
  for (const snapshot of [value.partial, value.complete]) {
    if (snapshot == null) continue;
    if (typeof snapshot.id !== 'string' || snapshot.id.length > 100 ||
        typeof snapshot.rights !== 'string' || !/^[a-f0-9]{64}$/.test(snapshot.rights) ||
        !Number.isFinite(snapshot.fetchedAt) || snapshot.fetchedAt < 0 || snapshot.fetchedAt > MAX_DATE || !Array.isArray(snapshot.items) || snapshot.items.length > 250 ||
        !Array.isArray(snapshot.warnings) || snapshot.warnings.length > 100 ||
        snapshot.warnings.some(warning => typeof warning !== 'string' || warning.length > 200 || /[\x00-\x1f\x7f]/.test(warning)) || typeof snapshot.complete !== 'boolean' ||
        !Number.isSafeInteger(snapshot.pages) || snapshot.pages < 0 || snapshot.pages > 10) throw new Error('invalid_persistent_state');
    const ranks = new Set(); const ids = new Set();
    for (const item of snapshot.items) {
      if (!item || !/^\d{1,20}$/.test(item.providerId) || typeof item.title !== 'string' || !item.title ||
          item.title.length > 300 || /[\x00-\x1f\x7f]/.test(item.title) || item.type !== 'movie' || !Number.isSafeInteger(item.topRank) ||
          item.topRank < 1 || item.topRank > 250 || ranks.has(item.topRank) || ids.has(item.providerId) ||
          item.topSource !== 'Douban' || item.poster !== '' || item.backdrop !== '' || item.overview !== '' ||
          !Array.isArray(item.actors) || item.actors.length !== 0 || !Array.isArray(item.aliases) || item.aliases.length > 30 ||
          item.aliases.some(alias => typeof alias !== 'string' || alias.length > 200 || /[\x00-\x1f\x7f]/.test(alias)) ||
          item.sourceUrl !== ORIGIN + '/subject/' + item.providerId + '/' || typeof item.year !== 'string' || (item.year !== '' && !/^\d{4}$/.test(item.year)) ||
          (item.rating !== null && (!Number.isFinite(item.rating) || item.rating < 0 || item.rating > 10 ||
            item.ratingSource !== 'Douban' || item.ratingScale !== 10))) throw new Error('invalid_persistent_state');
      ranks.add(item.topRank); ids.add(item.providerId);
    }
    if (snapshot.complete && (snapshot.items.length !== 250 || snapshot.pages !== 10 ||
        snapshot.items.some(item => item.rating === null))) throw new Error('invalid_persistent_state');
  }
  return value;
}

/** One durable gate per operator state directory, shared by every chart/query/page.
 * Dependencies are injectable for synthetic tests. No env setting can redirect acquisition.
 */
export function createDoubanProvider(env = process.env, dependencies = {}) {
  env = { ...env }; // Configuration changes require restart; requests cannot mutate operator rights.
  const enabled = env.DOUBAN_ACQUISITION_ENABLED === 'true';
  const directory = String(env.DOUBAN_STATE_DIR || '');
  const key = isAbsolute(directory) ? resolve(directory) : directory;
  if (enabled && dependencies.shared !== false && providers.has(key)) return providers.get(key);
  const fetchImpl = dependencies.fetchImpl || fetch;
  const now = dependencies.now || Date.now;
  const sleep = dependencies.sleep || (ms => new Promise(done => setTimeout(done, ms)));
  const random = dependencies.random || Math.random;
  const ttl = boundedNumber(env.DOUBAN_CACHE_TTL_MS, 7 * 86400000, 1000, 7 * 86400000);
  const staleMax = boundedNumber(env.DOUBAN_STALE_MAX_MS, 30 * 86400000, ttl, 90 * 86400000);
  const timeout = boundedNumber(env.DOUBAN_TIMEOUT_MS, 5000, 100, 10000);
  const statePath = join(directory, 'state.json');
  const lockPath = join(directory, 'owner.lock');
  let state = initial(); let initialized; let active = null; let storageError = null; let closed = false;
  let ownedLock = false; const ownerToken = randomUUID();
  const rights = () => declaration(env, now());
  const fingerprint = value => hash(JSON.stringify({ ...value, origin: ORIGIN }));
  const persist = async () => {
    const path = join(directory, 'state.' + ownerToken + '.tmp');
    const file = await open(path, 'w', 0o600);
    try { await file.writeFile(JSON.stringify(state)); await file.sync(); } finally { await file.close(); }
    await rename(path, statePath);
  };
  async function initialize() {
    if (!isAbsolute(directory) || String(env.DOUBAN_ORIGIN || ORIGIN) !== ORIGIN) throw new Error('invalid_provider_configuration');
    await mkdir(directory, { recursive: true, mode: 0o700 });
    let lock;
    try { lock = await open(lockPath, 'wx', 0o600); }
    catch (error) {
      if (error.code !== 'EEXIST') throw error;
      const owner = await boundedFile(lockPath, 1024);
      if (!Number.isSafeInteger(owner.pid) || owner.pid <= 0) throw new Error('invalid_provider_lock');
      try { process.kill(owner.pid, 0); throw new Error('provider_owned_by_another_process'); }
      catch (check) { if (check.code !== 'ESRCH') throw check; }
      await unlink(lockPath);
      lock = await open(lockPath, 'wx', 0o600);
    }
    try { await lock.writeFile(JSON.stringify({ pid: process.pid, token: ownerToken })); await lock.sync(); }
    finally { await lock.close(); }
    ownedLock = true;
    try { state = validateState(await boundedFile(statePath)); }
    catch (error) {
      if (error.code !== 'ENOENT') throw error;
      await persist();
    }
  }
  function usable(snapshot, currentRights) {
    return snapshot && snapshot.rights === fingerprint(currentRights) && snapshot.fetchedAt <= now() &&
      now() - snapshot.fetchedAt <= staleMax;
  }
  function selected(currentRights) {
    return usable(state.complete, currentRights) ? state.complete : usable(state.partial, currentRights) ? state.partial : null;
  }
  function metadata(snapshot, extraWarnings = []) {
    const stale = Boolean(snapshot && now() - snapshot.fetchedAt >= ttl);
    const gate = state.gate;
    const cooling = gate.cooldownUntil > now();
    const warnings = [...new Set([...(snapshot?.warnings || []), ...extraWarnings,
      ...(stale ? ['stale_cache'] : []), ...(snapshot && !snapshot.complete ? ['incomplete_catalog'] : []),
      ...(gate.reason ? [gate.reason] : [])])];
    return { source: snapshot ? (stale ? 'stale-cache' : 'cache') : null,
      fetchedAt: iso(snapshot?.fetchedAt), stale, complete: snapshot?.complete || false,
      expectedTotal: 250, availableTotal: snapshot?.items.length || 0, warnings,
      blocked: gate.blocked, retryAt: !gate.blocked && cooling ? iso(gate.cooldownUntil) : null,
      snapshotId: snapshot?.id || null, refreshing: Boolean(active),
      state: gate.blocked ? 'access_blocked' : cooling && gate.reason === 'rate_limited' ? 'rate_limited' :
        active ? 'refreshing' : snapshot ? (snapshot.complete && !stale ? 'fresh' : 'partial') : 'unavailable' };
  }
  async function cooldown(reason, retryAfter = null, blocked = false) {
    const gate = state.gate;
    gate.failures = Math.min(20, gate.failures + 1);
    const base = reason === 'rate_limited' ? WINDOW_MS : 60000;
    gate.cooldownUntil = Math.max(gate.cooldownUntil, now() + Math.min(2 * 3600000, base * 2 ** (gate.failures - 1)), retryAfter || 0);
    gate.blocked ||= blocked;
    gate.reason = reason;
    await persist();
  }
  async function beforeRequest() {
    rights();
    const gate = state.gate;
    if (closed || gate.blocked || gate.cooldownUntil > now()) return false;
    gate.requests = gate.requests.filter(time => time > now() - WINDOW_MS);
    if (gate.requests.length >= REQUEST_BUDGET) {
      gate.cooldownUntil = Math.max(gate.cooldownUntil, Math.min(...gate.requests) + WINDOW_MS);
      gate.reason = 'rate_limited'; await persist(); return false;
    }
    const delay = Math.max(0, gate.nextRequestAt - now());
    if (delay) await sleep(delay);
    rights();
    if (closed || gate.blocked || gate.cooldownUntil > now()) return false;
    gate.requests = gate.requests.filter(time => time > now() - WINDOW_MS);
    gate.requests.push(now());
    gate.nextRequestAt = now() + GAP_MS + Math.floor(Math.max(0, Math.min(0.999, random())) * 901);
    // Reserve and durably record every attempt BEFORE making the request, including retries after a crash.
    await persist();
    return true;
  }
  async function fill(currentRights) {
    const snapshot = { id: randomUUID(), rights: fingerprint(currentRights), fetchedAt: now(),
      complete: false, items: [], pages: 0, warnings: [] };
    state.partial = null;
    state.gate.reason = null;
    try {
      for (let start = 0; start < 250; start += 25) {
        if (!await beforeRequest()) break;
        let response;
        try {
          response = await fetchImpl(ORIGIN + '/top250?start=' + start, {
            headers: { accept: 'text/html', 'user-agent': 'CinemaTV-BFF/0.1 (authorized metadata acquisition)' },
            redirect: 'manual', signal: AbortSignal.timeout(timeout)
          });
          const retryAfter = parseRetryAfter(response.headers.get('retry-after'), now());
          if ([403, 418].includes(response.status)) {
            await response.body?.cancel(); await cooldown('access_blocked', retryAfter, true); break;
          }
          if (response.status === 429) {
            await response.body?.cancel(); await cooldown('rate_limited', retryAfter); break;
          }
          if (response.status >= 300 && response.status < 400) {
            await response.body?.cancel(); await cooldown('redirect_blocked', retryAfter); break;
          }
          if (!response.ok) {
            await response.body?.cancel(); await cooldown('upstream_error', retryAfter); break;
          }
          const html = await readDoubanHtml(response);
          rights();
          if (isBlockedHtml(html)) { await cooldown('access_blocked', retryAfter, true); break; }
          const parsed = parseTop250Html(html, { start });
          const existingIds = new Set(snapshot.items.map(item => item.providerId));
          const duplicates = new Set(parsed.items.filter(item => existingIds.has(item.providerId)).map(item => item.providerId));
          // Removing ambiguous earlier rows changes pagination positions: invalidate that partial snapshot identity.
          if (duplicates.size) snapshot.id = randomUUID();
          snapshot.items = [...snapshot.items.filter(item => !duplicates.has(item.providerId)),
            ...parsed.items.filter(item => !duplicates.has(item.providerId)).map(item => ({ ...item, poster: '', backdrop: '' }))].sort((a, b) => a.topRank - b.topRank);
          snapshot.pages++;
          snapshot.fetchedAt = now();
          snapshot.warnings = [...new Set([...snapshot.warnings, ...parsed.warnings,
            ...(duplicates.size ? ['duplicate_subject_ids'] : [])])].slice(0, 100);
          state.partial = snapshot;
          await persist();
          if (!parsed.pageComplete || duplicates.size) { await cooldown('incomplete_catalog'); break; }
        } catch (error) {
          if (['rights_expired', 'invalid_rights_declaration', 'written_permission_required'].includes(error.message)) throw error;
          await cooldown(error.message === 'body_too_large' ? 'body_too_large' : 'transport_or_parse_error'); break;
        }
      }
      rights();
      snapshot.complete = snapshot.pages === 10 && snapshot.items.length === 250 &&
        snapshot.items.every((item, index) => item.topRank === index + 1 && item.rating !== null);
      if (snapshot.complete) {
        state.complete = snapshot; state.partial = null;
        state.gate.failures = 0; state.gate.reason = null; state.gate.cooldownUntil = 0;
        await persist();
      }
    } catch (error) {
      if (['rights_expired', 'invalid_rights_declaration', 'written_permission_required'].includes(error.message)) {
        state.partial = null; state.complete = null; state.gate.reason = error.message;
        await persist().catch(() => { storageError = 'persistent_storage_unavailable'; });
      } else storageError = 'persistent_storage_unavailable';
    }
  }
  const provider = {
    enabled,
    async load() {
      if (!enabled || closed) return { document: null, acquisition: { ...metadata(null), state: 'disabled', warnings: ['acquisition_disabled'] } };
      let currentRights;
      try { currentRights = rights(); }
      catch (error) { return { document: null, acquisition: { ...metadata(null), refreshing: false,
        state: 'unavailable', warnings: [error.message] } }; }
      initialized ||= initialize().catch(() => { storageError = 'persistent_storage_unavailable'; });
      await initialized;
      if (storageError) return { document: null, acquisition: { ...metadata(null), state: 'unavailable', warnings: [storageError] } };
      try { currentRights = rights(); } catch (error) {
        return { document: null, acquisition: { ...metadata(null), state: 'unavailable', refreshing: false, warnings: [error.message] } };
      }
      const snapshot = selected(currentRights);
      const fresh = snapshot?.complete && now() - snapshot.fetchedAt < ttl;
      if (!fresh && !active && !state.gate.blocked && state.gate.cooldownUntil <= now()) {
        active = Promise.resolve().then(() => fill(currentRights)).finally(() => { active = null; });
      }
      return { document: { schemaVersion: 1, provider: 'Douban', ...currentRights, items: snapshot?.items || [] },
        acquisition: metadata(snapshot) };
    },
    async waitForIdle() { await active; }, // For shutdown and deterministic synthetic tests, never used by HTTP handler.
    async close() {
      closed = true; await active;
      if (ownedLock) {
        const owner = await boundedFile(lockPath, 1024).catch(() => null);
        if (owner?.token === ownerToken) await unlink(lockPath);
      }
      if (providers.get(key) === provider) providers.delete(key);
    }
  };
  if (enabled && dependencies.shared !== false) providers.set(key, provider);
  return provider;
}
