import { pushApi } from './api';
import { getPushOwner, getPushPreference, savePushPreference, setPushOwner } from './pushPreference';

export function pushSupported() {
  return window.isSecureContext && 'serviceWorker' in navigator &&
    'PushManager' in window && 'Notification' in window;
}

function decodeBase64Url(value) {
  const padded = value.padEnd(Math.ceil(value.length / 4) * 4, '=')
    .replace(/-/g, '+').replace(/_/g, '/');
  return Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
}

export async function currentSubscription() {
  if (!pushSupported()) return null;
  const registration = await navigator.serviceWorker.getRegistration('/');
  return registration?.pushManager.getSubscription() || null;
}

export async function enablePush(userId) {
  if (!pushSupported()) throw new Error('Este navegador necesita HTTPS o localhost y soporte Web Push.');
  const { publicKey } = await pushApi.publicKey();
  const registration = await navigator.serviceWorker.register('/sw.js', { scope: '/' });
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') throw new Error('No concediste permiso para notificaciones. Podés publicar igual.');
  let subscription = await registration.pushManager.getSubscription();
  const isNew = !subscription;
  if (!subscription) subscription = await registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: decodeBase64Url(publicKey),
  });
  try {
    await pushApi.subscribe(subscription.toJSON());
  } catch (error) {
    // A newly-created endpoint must not remain enabled without an owner on the
    // server. Preserve an existing endpoint on a transient re-registration error.
    if (isNew) await subscription.unsubscribe().catch(() => {});
    throw error;
  }
  savePushPreference(userId, true);
  setPushOwner(userId);
}

export async function disablePush(userId, { remember = true } = {}) {
  const subscription = await currentSubscription();
  if (!subscription) {
    if (remember) savePushPreference(userId, false);
    return;
  }
  try { await pushApi.unsubscribe(subscription.endpoint); }
  finally {
    const removed = await subscription.unsubscribe();
    if (removed) {
      if (remember) savePushPreference(userId, false);
      setPushOwner(null);
    }
  }
}

// Reconcile the browser endpoint with this account without changing an explicit
// opt-out. Logout removes the endpoint for privacy but keeps the opt-in choice.
export async function restorePushForUser(userId) {
  if (!pushSupported()) return false;
  let subscription;
  try { subscription = await currentSubscription(); } catch { return false; }
  const preference = getPushPreference(userId);
  const owner = getPushOwner();

  if (preference === false || (owner && owner !== userId && preference !== true)) {
    // A stale endpoint may still belong to a prior account after an unclean
    // logout. Never silently opt the new account into its notifications.
    if (subscription) {
      if (preference === false && owner === userId) {
        try { await disablePush(userId); } catch { /* Local unsubscribe still runs. */ }
      } else await subscription.unsubscribe().catch(() => {});
    }
    return false;
  }
  if (preference === true) {
    if (Notification.permission !== 'granted') {
      if (owner && owner !== userId && subscription) await subscription.unsubscribe().catch(() => {});
      return false;
    }
    try { await enablePush(userId); return true; }
    catch {
      if (owner && owner !== userId && subscription) await subscription.unsubscribe().catch(() => {});
      return false;
    }
  }
  if (!subscription || Notification.permission !== 'granted') return false;
  // Upgrade an already-active subscription created before we stored a choice.
  try {
    await pushApi.subscribe(subscription.toJSON());
    savePushPreference(userId, true);
    setPushOwner(userId);
    return true;
  } catch {
    if (owner && owner !== userId) await subscription.unsubscribe().catch(() => {});
    return false;
  }
}
