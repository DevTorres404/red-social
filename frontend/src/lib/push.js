import { pushApi } from './api';

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

export async function enablePush() {
  if (!pushSupported()) throw new Error('Este navegador necesita HTTPS o localhost y soporte Web Push.');
  const { publicKey } = await pushApi.publicKey();
  const registration = await navigator.serviceWorker.register('/sw.js', { scope: '/' });
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') throw new Error('No concediste permiso para notificaciones. Podés publicar igual.');
  let subscription = await registration.pushManager.getSubscription();
  if (!subscription) subscription = await registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: decodeBase64Url(publicKey),
  });
  try {
    await pushApi.subscribe(subscription.toJSON());
  } catch (error) {
    await subscription.unsubscribe().catch(() => {});
    throw error;
  }
}

export async function disablePush() {
  const subscription = await currentSubscription();
  if (!subscription) return;
  try { await pushApi.unsubscribe(subscription.endpoint); }
  finally { await subscription.unsubscribe(); }
}

// An already-permitted browser may be used by a different account later.
// Rebind its endpoint to the new JWT owner or stop it from receiving old-user alerts.
export async function rebindExistingPush() {
  let subscription;
  try { subscription = await currentSubscription(); } catch { return; }
  if (!subscription) return;
  try { await pushApi.subscribe(subscription.toJSON()); }
  catch { await subscription.unsubscribe().catch(() => {}); }
}
