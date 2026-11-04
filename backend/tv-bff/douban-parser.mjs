// Clean-room structural parser. No remote requests or third-party DOM dependency.
const MAX_BYTES = 1024 * 1024;
const VOID = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr']);
const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', middot: '·', ndash: '–', mdash: '—' };
function decode(value) {
  return value.replace(/&(#x[0-9a-f]{1,6}|#\d{1,7}|[a-z]{2,10});/gi, (whole, entity) => {
    if (entity[0] !== '#') return ENTITIES[entity] ?? whole;
    const n = entity[1].toLowerCase() === 'x' ? parseInt(entity.slice(2), 16) : Number(entity.slice(1));
    return n > 0 && n <= 0x10ffff && !(n >= 0xd800 && n <= 0xdfff) ? String.fromCodePoint(n) : '\uFFFD';
  });
}
function parseDocument(html) {
  if (typeof html !== 'string' || Buffer.byteLength(html, 'utf8') > MAX_BYTES) throw new Error('HTML input must be a string of at most 1 MiB');
  const root = { tag: 'root', attrs: {}, children: [] };
  const stack = [root];
  let count = 0;
  let malformed = false;
  // Raw text elements cannot contribute records or visible block-page signals.
  html = html.replace(/<(script|style)\b[^>]*>[\s\S]*?(?:<\/\1\s*>|$)/gi, '');
  const tokens = /<!--[\s\S]*?-->|<![^>]*>|<\/?[a-z][^>]*>|[^<]+|</gi;
  for (const match of html.matchAll(tokens)) {
    if (++count > 40000) throw new Error('HTML token limit exceeded');
    const token = match[0];
    if (token.startsWith('<!')) continue;
    if (token === '<') { malformed = true; continue; }
    if (token[0] !== '<') { stack.at(-1).children.push(decode(token)); continue; }
    const tag = /^<\/?([a-z][\w:-]*)/i.exec(token)?.[1].toLowerCase();
    if (!tag) { malformed = true; continue; }
    if (token.startsWith('</')) {
      const index = stack.findLastIndex(node => node.tag === tag);
      if (index > 0) { if (index !== stack.length - 1) malformed = true; stack.length = index; }
      else if (!VOID.has(tag)) malformed = true;
      continue;
    }
    const attrs = Object.create(null);
    const rest = token.slice(tag.length + 1, -1);
    for (const a of rest.matchAll(/([^\s=/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g)) {
      const key = a[1].toLowerCase();
      if (Object.hasOwn(attrs, key)) malformed = true;
      else attrs[key] = decode(a[2] ?? a[3] ?? a[4] ?? '');
    }
    const node = { tag, attrs, children: [] };
    stack.at(-1).children.push(node);
    if (!VOID.has(tag) && !token.endsWith('/>')) {
      stack.push(node);
      if (stack.length > 128) throw new Error('HTML nesting limit exceeded');
    }
  }
  if (stack.length !== 1) malformed = true;
  return { root, malformed };
}
function nodes(node, predicate) {
  const found = [];
  const visit = n => { if (typeof n !== 'string') { if (predicate(n)) found.push(n); for (const child of n.children) visit(child); } };
  visit(node);
  return found;
}
function text(node, breakText = '\n') { return typeof node === 'string' ? node : node.tag === 'br' ? breakText : node.children.map(child => text(child, breakText)).join(''); }
function clean(node) { return text(node).replace(/\s+/g, ' ').trim(); }
function hasClass(node, name) { return (node.attrs.class ?? '').split(/\s+/).includes(name); }
function blocked(root) {
  const headings = nodes(root, n => ['title', 'h1', 'h2'].includes(n.tag)).map(clean).join(' ');
  if (/captcha|access denied|security check|访问受限|访问异常|禁止访问|安全验证|验证码|豆瓣访问限制/i.test(headings)) return true;
  if (nodes(root, n => ['input', 'iframe', 'form'].includes(n.tag) && /captcha|recaptcha|hcaptcha/i.test([n.attrs.id, n.attrs.name, n.attrs.src, n.attrs.action].join(' '))).length) return true;
  return /检测到有异常请求|请求过于频繁|请输入验证码|验证你是真人|verify (?:that )?you are human/i.test(clean(root));
}
export function isBlockedHtml(html) {
  try { return blocked(parseDocument(html).root); } catch { return true; }
}
export function parseTop250Html(html, { start = 0 } = {}) {
  const warnings = [];
  const fail = warning => ({ items: [], warnings: [warning], pageComplete: false });
  if (!Number.isInteger(start) || start < 0 || start > 225 || start % 25 !== 0) return fail('Invalid Top250 page offset');
  let document;
  try { document = parseDocument(html); } catch (e) { return fail(e.message); }
  if (blocked(document.root)) return fail('Upstream returned a verification or blocked page');
  if (document.malformed) warnings.push('Malformed HTML structure');
  const candidates = nodes(document.root, n => n.tag === 'li' && nodes(n, child => hasClass(child, 'item')).length > 0);
  if (candidates.length > 100) return fail('Too many candidate records');
  const items = [];
  for (const li of candidates) {
    const ranks = nodes(li, n => n.tag === 'em').map(clean);
    const rank = ranks.length === 1 && /^\d{1,3}$/.test(ranks[0]) ? Number(ranks[0]) : 0;
    const ids = new Set(nodes(li, n => n.tag === 'a').map(n => /^https:\/\/movie\.douban\.com\/subject\/([1-9]\d{0,15})\/$/.exec(n.attrs.href ?? '')?.[1]).filter(Boolean));
    const titles = nodes(li, n => n.tag === 'span' && hasClass(n, 'title')).map(clean).map(s => s.replace(/^\/\s*/, ''));
    if (rank < start + 1 || rank > start + 25 || ids.size !== 1 || !titles[0] || titles[0].length > 300 || /[\x00-\x1f\x7f]/.test(titles[0])) {
      warnings.push('Skipped record with invalid rank, subject ID, or title'); continue;
    }
    const allAliases = [...new Set(titles.slice(1))].filter(t => t && t !== titles[0]);
    const aliases = allAliases.filter(t => t.length <= 200 && !/[\x00-\x1f\x7f]/.test(t)).slice(0, 30);
    if (aliases.length !== allAliases.length) warnings.push(`Rank ${rank}: omitted invalid or excess aliases`);
    const ratingNodes = nodes(li, n => n.tag === 'span' && hasClass(n, 'rating_num'));
    const ratingText = ratingNodes.length === 1 ? clean(ratingNodes[0]) : '';
    const rating = /^(?:\d(?:\.\d{1,2})?|10(?:\.0{1,2})?)$/.test(ratingText) ? Number(ratingText) : null;
    if (rating === null) warnings.push(`Rank ${rank}: missing or invalid rating`);
    const years = new Set(nodes(li, n => n.tag === 'p').flatMap(p => text(p, '\u0000').split('\u0000').slice(1).map(line => /^\s*((?:18|19|20|21)\d{2})(?=\s|\/|$)/.exec(line)?.[1]).filter(Boolean)));
    const year = years.size === 1 ? [...years][0] : '';
    if (!year) warnings.push(`Rank ${rank}: missing or ambiguous year`);
    // Image rights and transport have separate authorization requirements. Never expose remote images.
    const providerId = [...ids][0];
    items.push({ providerId, title: titles[0], type: 'movie', year, aliases, actors: [], poster: '', backdrop: '', overview: '', rating, ratingSource: rating === null ? null : 'Douban', ratingScale: rating === null ? null : 10, topRank: rank, topSource: 'Douban', sourceUrl: `https://movie.douban.com/subject/${providerId}/` });
  }
  const idCounts = new Map(), rankCounts = new Map();
  for (const item of items) { idCounts.set(item.providerId, (idCounts.get(item.providerId) ?? 0) + 1); rankCounts.set(item.topRank, (rankCounts.get(item.topRank) ?? 0) + 1); }
  const unique = items.filter(item => idCounts.get(item.providerId) === 1 && rankCounts.get(item.topRank) === 1).sort((a, b) => a.topRank - b.topRank);
  if (unique.length !== items.length) warnings.push('Omitted all records with duplicate subject IDs or ranks');
  const pageComplete = !document.malformed && candidates.length === 25 && unique.length === 25 && unique.every((item, i) => item.topRank === start + i + 1 && item.rating !== null);
  if (!pageComplete) warnings.push('Incomplete Top250 page');
  return { items: unique, warnings, pageComplete };
}
