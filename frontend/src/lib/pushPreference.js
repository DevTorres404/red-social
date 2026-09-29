const PREFIX = 'orbit.push.enabled.';
const OWNER_KEY = 'orbit.push.owner';

// Push permission belongs to the browser, but the choice belongs to a user
// on this device. Store only this boolean and user ID, never endpoint or keys.
export function getPushPreference(userId) {
  try {
    const value = window.localStorage.getItem(`${PREFIX}${userId}`);
    return value === null ? null : value === 'true';
  } catch {
    return null;
  }
}

export function savePushPreference(userId, enabled) {
  try {
    window.localStorage.setItem(`${PREFIX}${userId}`, String(enabled));
    return true;
  } catch {
    return false;
  }
}

export function getPushOwner() {
  try { return window.localStorage.getItem(OWNER_KEY); }
  catch { return null; }
}

export function setPushOwner(userId) {
  try {
    if (userId) window.localStorage.setItem(OWNER_KEY, userId);
    else window.localStorage.removeItem(OWNER_KEY);
  } catch { /* Browser storage may be unavailable. */ }
}
