import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const source = readFileSync(fileURLToPath(new URL('./push.js', import.meta.url)), 'utf8')
  .replace("import { pushApi } from './api';", 'const pushApi = new Proxy({}, { get: (_, key) => globalThis.__pushApi[key] });')
  .replace("from './pushPreference';", `from '${new URL('./pushPreference.js', import.meta.url).href}';`);
const push = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

function browser() {
  const storage = new Map();
  let subscription = null;
  let serial = 0;
  const calls = { subscribe: [], unsubscribe: [] };
  const registration = {
    pushManager: {
      getSubscription: async () => subscription,
      subscribe: async () => {
        const endpoint = `https://fcm.googleapis.com/fcm/send/${++serial}`;
        subscription = {
          endpoint,
          toJSON: () => ({ endpoint, keys: { p256dh: 'key', auth: 'auth' } }),
          unsubscribe: async () => { subscription = null; return true; },
        };
        return subscription;
      },
    },
  };
  globalThis.window = {
    isSecureContext: true,
    PushManager: class {},
    Notification: class {},
    localStorage: {
      getItem: (key) => storage.get(key) ?? null,
      setItem: (key, value) => storage.set(key, value),
      removeItem: (key) => storage.delete(key),
    },
  };
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {
    serviceWorker: {
      getRegistration: async () => registration,
      register: async () => registration,
    },
  } });
  globalThis.Notification = { permission: 'granted', requestPermission: async () => 'granted' };
  globalThis.__pushApi = {
    publicKey: async () => ({ publicKey: 'AQ' }),
    subscribe: async (value) => { calls.subscribe.push(value.endpoint); },
    unsubscribe: async (endpoint) => { calls.unsubscribe.push(endpoint); },
  };
  return { storage, calls, getSubscription: () => subscription };
}

test('push stays opted in after logout and is restored for the same account', async () => {
  const state = browser();
  await push.enablePush('alice');
  assert.equal(state.storage.get('orbit.push.enabled.alice'), 'true');
  assert.ok(state.getSubscription());

  await push.disablePush('alice', { remember: false });
  assert.equal(state.getSubscription(), null);
  assert.equal(state.storage.get('orbit.push.enabled.alice'), 'true');
  assert.equal(state.calls.unsubscribe.length, 1);

  assert.equal(await push.restorePushForUser('alice'), true);
  assert.ok(state.getSubscription());
  assert.equal(state.calls.subscribe.length, 2);
});

test('explicit opt-out remains off after the next login', async () => {
  const state = browser();
  await push.enablePush('alice');
  await push.disablePush('alice');
  assert.equal(state.storage.get('orbit.push.enabled.alice'), 'false');
  assert.equal(await push.restorePushForUser('alice'), false);
  assert.equal(state.getSubscription(), null);
  assert.equal(state.calls.subscribe.length, 1);
});

test('another account does not inherit an old browser subscription', async () => {
  const state = browser();
  await push.enablePush('alice');
  assert.equal(await push.restorePushForUser('bob'), false);
  assert.equal(state.getSubscription(), null);
  assert.equal(state.storage.get('orbit.push.enabled.bob'), undefined);
  assert.equal(state.calls.subscribe.length, 1);
});

test('revoked browser permission does not silently re-enable push', async () => {
  const state = browser();
  await push.enablePush('alice');
  await push.disablePush('alice', { remember: false });
  globalThis.Notification.permission = 'denied';
  assert.equal(await push.restorePushForUser('alice'), false);
  assert.equal(state.getSubscription(), null);
});

test('a temporary backend failure does not erase an existing opt-in', async () => {
  const state = browser();
  await push.enablePush('alice');
  globalThis.__pushApi.subscribe = async () => { throw new Error('temporarily unavailable'); };
  assert.equal(await push.restorePushForUser('alice'), false);
  assert.ok(state.getSubscription());
  assert.equal(state.storage.get('orbit.push.enabled.alice'), 'true');
});

test('unsupported browsers do not break session restoration', async () => {
  browser();
  delete globalThis.window.PushManager;
  assert.equal(await push.restorePushForUser('alice'), false);
});
