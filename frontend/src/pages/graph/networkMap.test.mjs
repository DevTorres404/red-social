import test from 'node:test';
import assert from 'node:assert/strict';
import { buildNetworkMap } from './networkMap.js';

const self = { id: 'me', username: 'yo' };

test('empty network shows only the current user', () => {
  const map = buildNetworkMap(self);
  assert.deepEqual(map.nodes.map((node) => node.id), ['me']);
  assert.deepEqual(map.edges, []);
  assert.equal(map.hiddenCount, 0);
});

test('large network stays bounded and every edge has visible endpoints', () => {
  const direct = Array.from({ length: 18 }, (_, index) => ({ id: `d${index}`, username: `direct${index}`, distance: 1, viaId: 'me' }));
  const second = Array.from({ length: 60 }, (_, index) => ({ id: `s${index}`, username: `second${index}`, distance: 2, viaId: `d${index % 18}` }));
  const map = buildNetworkMap(self, [...direct, ...second]);
  assert.equal(map.nodes.length, 25);
  assert.equal(map.hiddenCount, 54);
  const visible = new Set(map.nodes.map((node) => node.id));
  assert.ok(map.edges.every((edge) => visible.has(edge.from) && visible.has(edge.to)));
  assert.equal(new Set(map.nodes.map((node) => `${node.x},${node.y}`)).size, map.nodes.length);
});

test('selecting a hidden second-degree person also brings their intermediary into view', () => {
  const direct = Array.from({ length: 15 }, (_, index) => ({ id: `d${index}`, username: `direct${index}`, distance: 1 }));
  const second = [{ id: 'far', username: 'far', distance: 2, viaId: 'd14' }];
  const map = buildNetworkMap(self, [...direct, ...second], 'far');
  assert.ok(map.nodes.some((node) => node.id === 'd14'));
  assert.ok(map.nodes.some((node) => node.id === 'far'));
  assert.ok(map.edges.some((edge) => edge.from === 'd14' && edge.to === 'far'));
});
