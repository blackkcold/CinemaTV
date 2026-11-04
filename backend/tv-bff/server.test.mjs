import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, normalizeItem } from './server.mjs';

test('search result normalization uses LibreTV protocol names', () => {
  assert.deepEqual(normalizeItem({ vodId: '12', name: 'Demo', pic: 'https://img.invalid/a.jpg',
    sourceKey: 'test', sourceName: 'Test' }), {
    id: '12', title: 'Demo', poster: 'https://img.invalid/a.jpg',
    year: '', category: '', rating: '', sourceKey: 'test', sourceName: 'Test'
  });
});
test('error retains status', () => {
  const e = new ApiError('bad request', 400);
  assert.equal(e.status, 400);
});
