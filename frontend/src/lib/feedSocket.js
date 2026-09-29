/**
 * Feed WebSocket client — broadcast-only channel for like/comment events.
 *
 * Mirrors the ChatPage socket pattern: a single-use ticket is fetched over
 * REST, then a WebSocket opens with subprotocols ["orbit-feed", `ticket.${t}`].
 * On close we retry with exponential backoff and a FRESH ticket per attempt.
 * api.js already refreshes on 401, so a 401 here means the session is really gone.
 */
import { feedApi } from './api';

const BACKOFF_CAP = 10000;
const BACKOFF_STABLE_MS = 30000;
const MAX_ATTEMPTS = 8;

const handlers = new Set();
let socket = null;
let connecting = null;
let retryTimer = null;
let attempt = 0;
let stopped = false;
let terminated = false;
let connectionGen = 0; // bumped by disconnect(), so an in-flight open sees it lost the race

function wsUrl() {
  const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${scheme}//${window.location.host}/ws/feed`;
}

async function openSocket() {
  const credentials = await feedApi.ticket();

  const ws = new WebSocket(wsUrl(), ['orbit-feed', `ticket.${credentials.ticket}`]);
  let openedAt = 0;
  ws.onopen = () => { openedAt = Date.now(); };
  ws.onmessage = (event) => {
    let data;
    try { data = JSON.parse(event.data); } catch { return; }
    handlers.forEach((handler) => {
      try { handler(data); } catch (err) { console.error('Feed event handler error:', err); }
    });
  };
  ws.onclose = () => {
    // Only a connection that stayed up long enough earns a fresh backoff budget.
    if (Date.now() - openedAt >= BACKOFF_STABLE_MS) attempt = 0;
    if (socket === ws) socket = null;
    scheduleRetry();
  };
  return ws;
}

function scheduleRetry() {
  if (stopped || terminated) return;
  attempt += 1;
  if (attempt > MAX_ATTEMPTS) { terminated = true; return; } // a later connect() restarts us
  retryTimer = window.setTimeout(() => { connect(); }, Math.min(1000 * 2 ** (attempt - 1), BACKOFF_CAP));
}

/**
 * Ensure a single open/in-flight connection (guard against duplicates).
 * Resolves to the WebSocket or null when connection is unavailable.
 */
export function connect() {
  stopped = false;
  if (terminated) { attempt = 0; terminated = false; }
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return socket;
  }
  if (connecting) return connecting;
  const gen = connectionGen;
  connecting = openSocket()
    .then((ws) => {
      if (gen !== connectionGen) { // disconnect() won: drop it, do not resurrect
        ws.onclose = null;
        ws.close();
        return null;
      }
      connecting = null;
      socket = ws;
      return ws;
    })
    .catch(() => {
      if (gen !== connectionGen) return null;
      connecting = null;
      if (!stopped) scheduleRetry();
      return null;
    });
  return connecting;
}

/** Subscribe to feed events. Returns an unsubscribe function. */
export function subscribe(handler) {
  handlers.add(handler);
  return () => handlers.delete(handler);
}

/** Send a frame (e.g. {"type":"subscribe","scope":"home"}). Only when OPEN. */
export function send(obj) {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(obj));
}

export function disconnect() {
  stopped = true;
  connectionGen += 1; // orphan any in-flight open
  connecting = null;  // the next connect() must start clean
  if (retryTimer) { window.clearTimeout(retryTimer); retryTimer = null; }
  if (socket) {
    const ws = socket;
    socket = null;
    ws.onclose = null; // manual close, no reconnect
    if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) ws.close();
  }
}
