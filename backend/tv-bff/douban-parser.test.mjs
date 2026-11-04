import test from 'node:test';
import assert from 'node:assert/strict';
import { parseTop250Html, isBlockedHtml } from './douban-parser.mjs';

// Entirely synthetic records; no acquired catalog or upstream source code.
function entry(rank, { id = String(800000 + rank), title = `Synthetic film ${rank}`, rating = '8.4', year = '2004', poster = 'https://img3.doubanio.com/view/photo/synthetic.jpg', alias = 'Imaginary alias' } = {}) {
  return `<li><div class="item"><div class="pic"><em>${rank}</em><a href="https://movie.douban.com/subject/${id}/"><img src="${poster}"></a></div><div class="info"><div class="hd"><a href="https://movie.douban.com/subject/${id}/"><span class="title">${title}</span><span class="title"> / ${alias}</span></a></div><div class="bd"><p>Director: Fictional Person 1998<br>${year} / Fictionland / Drama</p><span class="rating_num">${rating}</span></div></div></div></li>`;
}
const page = entries => `<!doctype html><html><head><title>Synthetic ranking</title></head><body><ol class="grid_view">${entries.join('')}</ol></body></html>`;
const full = (offset = 0) => Array.from({ length: 25 }, (_, i) => entry(offset + i + 1));

test('extracts complete 25-record synthetic page with evidence fields', () => {
  const result = parseTop250Html(page(full()));
  assert.equal(result.pageComplete, true);
  assert.deepEqual(result.warnings, []);
  assert.equal(result.items.length, 25);
  assert.deepEqual(result.items[0], { providerId: '800001', title: 'Synthetic film 1', type: 'movie', year: '2004', aliases: ['Imaginary alias'], actors: [], poster: '', backdrop: '', overview: '', rating: 8.4, ratingSource: 'Douban', ratingScale: 10, topRank: 1, topSource: 'Douban', sourceUrl: 'https://movie.douban.com/subject/800001/' });
  assert.equal(parseTop250Html(page(full(225)), { start: 225 }).pageComplete, true);
});
test('missing year stays blank; never uses director year before br', () => {
  const entries = full(); entries[0] = entry(1, { year: '' });
  const result = parseTop250Html(page(entries));
  assert.equal(result.items[0].year, '');
  assert.equal(result.pageComplete, true);
  assert.match(result.warnings.join(' '), /year/);
  const misleading = entry(1, { year: '' }).replace('Director: Fictional Person 1998', '\n1998 / Fictional director credit');
  assert.equal(parseTop250Html(page([misleading])).items[0].year, '');
});
test('handles alternate whitespace, single quoted attributes, br slash, nested title markup', () => {
  const html = entry(1, { title: '<b>Imaginary</b> &amp; Test', year: ' 1999 ' }).replaceAll('"', "'").replace('<br>', '<br />');
  const result = parseTop250Html(page([html]));
  assert.equal(result.items[0].title, 'Imaginary & Test');
  assert.equal(result.items[0].year, '1999');
});
test('missing, ambiguous, out-of-range, and nonnumeric ratings never fabricated', () => {
  for (const rating of ['', 'N/A', '11', '-1', '8.4 stars']) {
    const entries = full(); entries[0] = entry(1, { rating });
    const result = parseTop250Html(page(entries));
    assert.equal(result.items[0].rating, null);
    assert.equal(result.items[0].ratingSource, null);
    assert.equal(result.items[0].ratingScale, null);
    assert.equal(result.pageComplete, false);
  }
});
test('decodes standard/numeric entities once, retains unknown entities safely', () => {
  const result = parseTop250Html(page([entry(1, { title: 'A&nbsp;&amp; B &#x1F680; &#20320; &quot;Q&quot; &lt;x&gt; &unknown; &#xD800;' })]));
  assert.equal(result.items[0].title, 'A & B 🚀 你 "Q" <x> &unknown; �');
});
test('all ambiguous duplicate IDs or ranks are omitted', () => {
  for (const entries of [[entry(1), entry(2, { id: '800001' })], [entry(1), entry(1, { id: '900001' })]]) {
    const result = parseTop250Html(page(entries));
    assert.equal(result.items.length, 0);
    assert.equal(result.pageComplete, false);
    assert.match(result.warnings.join(' '), /duplicate/);
  }
});
test('missing and out-of-page ranks and invalid or conflicting IDs are skipped', () => {
  for (const html of [entry(0), entry(26), entry(1, { id: '../bad' }), entry(1).replace('<em>1</em>', ''), entry(1).replace('<em>1</em>', '<em>1x</em>'), entry(1).replace('subject/800001/', 'subject/900001/'), entry(1, { title: '', alias: '' })]) {
    assert.equal(parseTop250Html(page([html])).items.length, 0);
  }
  assert.equal(parseTop250Html(page(full().slice(1))).pageComplete, false);
  assert.equal(parseTop250Html(page(full()), { start: 1 }).pageComplete, false);
});
test('remote images are never exposed, including Douban image hosts', () => {
  for (const poster of ['https://img3.doubanio.com/a', 'http://img.doubanio.com/a', 'https://doubanio.com.evil.test/a', 'https://u:p@img.doubanio.com/a', 'https://img.doubanio.com:8443/a', 'data:image/png;base64,AA', '//img.doubanio.com/a', 'https://evil.test/a']) {
    assert.equal(parseTop250Html(page([entry(1, { poster })])).items[0].poster, '');
  }
});
test('malformed HTML cannot be declared a complete page', () => {
  assert.equal(parseTop250Html(page(full()).replace('</div>', '')).pageComplete, false);
  assert.equal(parseTop250Html('<li><div class="item"><em>1').pageComplete, false);
  assert.equal(parseTop250Html('plain text').items.length, 0);
});
test('blocked pages fail closed, incidental script references do not', () => {
  for (const html of ['<html><title>访问异常</title></html>', '<h1>Access denied</h1>', '<form><input name="captcha"></form>', '<p>请输入验证码</p>']) {
    assert.equal(isBlockedHtml(html), true);
    assert.equal(parseTop250Html(html).items.length, 0);
    assert.match(parseTop250Html(html).warnings[0], /blocked/);
  }
  const html = page(full()).replace('</head>', '<script>const x="captcha"; const fake="<h1>Access denied</h1>";</script></head>');
  assert.equal(isBlockedHtml(html), false);
  assert.equal(parseTop250Html(html).pageComplete, true);
});
test('enforces byte, nesting, and token limits without throwing', () => {
  for (const html of ['é'.repeat(600000), '<div>'.repeat(129), '<br>'.repeat(40001), null]) {
    const result = parseTop250Html(html);
    assert.equal(result.pageComplete, false);
    assert.equal(result.items.length, 0);
    assert.ok(result.warnings.length);
  }
});

test('catalog title and alias bounds are enforced without fabricated text', () => {
  for (const title of ['x'.repeat(301), 'Synthetic\u0001Title', '']) {
    const result = parseTop250Html(page([entry(1, { title })]));
    assert.equal(result.items.length, 0);
    assert.match(result.warnings.join(' '), /invalid/);
  }
  for (const alias of ['y'.repeat(201), 'Synthetic\u0001Alias']) {
    const result = parseTop250Html(page([entry(1, { title: 'x'.repeat(300), alias })]));
    assert.equal(result.items[0].title.length, 300);
    assert.deepEqual(result.items[0].aliases, []);
    assert.match(result.warnings.join(' '), /aliases/);
  }
  const extra = Array.from({ length: 35 }, (_, i) => `<span class="title">Synthetic alias ${i}</span>`).join('');
  const result = parseTop250Html(page([entry(1).replace('</a></div><div class="bd">', `${extra}</a></div><div class="bd">`)]));
  assert.equal(result.items[0].aliases.length, 30);
  assert.equal(result.items[0].poster, '');
  assert.equal(result.items[0].backdrop, '');
  assert.equal(result.items[0].overview, '');
  assert.deepEqual(result.items[0].actors, []);
  assert.match(result.warnings.join(' '), /aliases/);
});
