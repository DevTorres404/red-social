import test from 'node:test';
import assert from 'node:assert/strict';
import { collectNewUnread, describeNotification } from './notifications.js';

test('initial unread items establish a baseline without replaying old toasts', () => {
  const result = collectNewUnread(null, [{ id: 'old', read: false }]);
  assert.deepEqual(result.fresh, []);
  assert.equal(result.seenIds.has('old'), true);
});

test('new unread items are queued oldest first and not repeated', () => {
  const baseline = collectNewUnread(null, [{ id: 'old', read: false }]);
  const first = collectNewUnread(baseline.seenIds, [
    { id: 'newer', read: false }, { id: 'new', read: false }, { id: 'old', read: false },
  ]);
  assert.deepEqual(first.fresh.map(item => item.id), ['new', 'newer']);
  assert.deepEqual(collectNewUnread(first.seenIds, [{ id: 'newer', read: false }]).fresh, []);
});

test('read items never create a toast even when the unread count is unchanged', () => {
  const baseline = collectNewUnread(null, [{ id: 'old', read: false }]);
  const next = collectNewUnread(baseline.seenIds, [
    { id: 'read-now', read: true }, { id: 'new', read: false }, { id: 'old', read: true },
  ]);
  assert.deepEqual(next.fresh.map(item => item.id), ['new']);
});

test('each supported event links to the correct place', () => {
  const base = { triggeredByUsername: 'ana', triggeredByUserId: 'actor', postId: 'post' };
  assert.equal(describeNotification({ ...base, type: 'POST' }).url, '/posts/post');
  assert.equal(describeNotification({ ...base, type: 'LIKE' }).url, '/posts/post');
  assert.equal(describeNotification({ ...base, type: 'COMMENT' }).url, '/posts/post');
  assert.equal(describeNotification({ ...base, type: 'FOLLOW' }).url, '/users/actor');
  assert.equal(describeNotification({ ...base, type: 'MESSAGE' }).url, '/messages/actor');
  assert.equal(describeNotification({ ...base, type: 'MENTION' }).url, '/posts/post');
});
