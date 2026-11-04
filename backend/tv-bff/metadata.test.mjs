import test from 'node:test';
import assert from 'node:assert/strict';
import { selectMetadata } from './server.mjs';

test('metadata must match exact normalized title and compatible year', () => {
  const results = [
    { id: 1, title: 'Film', release_date: '1998-06-01', backdrop_path: '/a.jpg' },
    { id: 2, title: 'Film', release_date: '2026-03-01', backdrop_path: '/b.jpg' },
    { id: 3, title: 'Film II', release_date: '2026-01-01' }
  ];
  assert.equal(selectMetadata('Film', '2026', results)?.id, 2);
  assert.equal(selectMetadata('Film', '1998', results)?.id, 1);
  assert.equal(selectMetadata('Film', '2020', results), null);
  assert.equal(selectMetadata('Film', '', results), null);
});

test('unicode width and release year protect identity', () => {
  assert.equal(selectMetadata('ＡＢＣ', '2025',
    [{ name: 'ABC', first_air_date: '2025-04-01', id: 11 }], 'tv')?.id, 11);
  assert.equal(selectMetadata('ABC', '2024',
    [{ name: 'ABC', first_air_date: '2025-04-01' }], 'tv'), null);
});

test('missing years and duplicate matches are not guessed', () => {
  assert.equal(selectMetadata('Film', '2026', [{ title: 'Film', id: 1 }]), null);
  assert.equal(selectMetadata('Film', '2026', [
    { title: 'Film', release_date: '2026-01-01', id: 1 },
    { title: 'Film', release_date: '2026-02-01', id: 2 }
  ]), null);
});
