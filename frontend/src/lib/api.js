/**
 * API client — thin wrapper over fetch.
 * Base URL: during dev the Vite proxy forwards /api → http://localhost:8080
 * In production, nginx rewrites /api → backend:8080.
 *
 * IMPORTANT: credentials: 'include' is required so that the HttpOnly refresh token
 * cookie is sent with requests to /api/auth/refresh and /api/auth/logout.
 */
const BASE = '/api';

let accessToken = null; // In-memory only, never persisted

function setAccessToken(token) {
  accessToken = token;
}

function getAccessToken() {
  return accessToken;
}

function clearAccessToken() {
  accessToken = null;
}

function authHeader() {
  const token = getAccessToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function request(method, path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    credentials: 'include', // Required for HttpOnly cookie (refresh token)
    headers: {
      'Content-Type': 'application/json',
      ...authHeader(),
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  if (!res.ok) {
    const contentType = res.headers.get('content-type') || '';
    const body = contentType.includes('application/json')
      ? await res.json().catch(() => null)
      : await res.text();
    const error = new Error(body?.message || (typeof body === 'string' ? body : res.statusText));
    error.status = res.status;
    throw error;
  }

  const ct = res.headers.get('content-type') || '';
  return ct.includes('application/json') ? res.json() : res.text();
}

async function requestFormData(method, path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    credentials: 'include',
    headers: {
      ...authHeader(),
    },
    body: body,
  });

  if (!res.ok) {
    const contentType = res.headers.get('content-type') || '';
    const errorBody = contentType.includes('application/json')
      ? await res.json().catch(() => null)
      : await res.text();
    const error = new Error(errorBody?.message || (typeof errorBody === 'string' ? errorBody : res.statusText));
    error.status = res.status;
    throw error;
  }

  const ct = res.headers.get('content-type') || '';
  return ct.includes('application/json') ? res.json() : res.text();
}

// Wrapper that handles 401 -> auto-refresh -> retry once
async function requestWithRefresh(method, path, body) {
  try {
    return await request(method, path, body);
  } catch (err) {
    if (err.status === 401) {
      // Try to refresh the token
      try {
        await authApi.refresh();
        // Retry the original request with new token
        return await request(method, path, body);
      } catch {
        // Refresh failed - clear token and re-throw original 401
        clearAccessToken();
        throw err;
      }
    }
    throw err;
  }
}

async function requestFormDataWithRefresh(method, path, body) {
  try {
    return await requestFormData(method, path, body);
  } catch (err) {
    if (err.status === 401) {
      try {
        await authApi.refresh();
        return await requestFormData(method, path, body);
      } catch {
        clearAccessToken();
        throw err;
      }
    }
    throw err;
  }
}

export const api = {
  post: (path, body) => requestWithRefresh('POST', path, body),
  get:  (path)       => requestWithRefresh('GET',  path),
  put:  (path, body) => requestWithRefresh('PUT',  path, body),
  del:  (path, body) => requestWithRefresh('DELETE', path, body),
  postForm: (path, body) => requestFormDataWithRefresh('POST', path, body),
};

/* ── Auth endpoints ──────────────────────────────────────────────────────── */
export const authApi = {
  login:    (credentials) => request('POST', '/auth/login', credentials)
    .then(data => { setAccessToken(data.token); return data; }),
  register: (data)        => request('POST', '/auth/register', data)
    .then(data => { setAccessToken(data.token); return data; }),
  me:       ()            => requestWithRefresh('GET', '/auth/me'),
  refresh:  ()            => request('POST', '/auth/refresh')
    .then(data => { setAccessToken(data.token); return data; }),
  logout:   ()            => request('POST', '/auth/logout')
    .then(() => { clearAccessToken(); }),
};

/* ── Users & Graph endpoints ─────────────────────────────────────────────── */
export const usersApi = {
  getProfile:     (id) => api.get(`/users/${id}`),
  updateProfile:  (id, data) => api.put(`/users/${id}`, data),
  uploadAvatar:   (id, file) => {
    const formData = new FormData();
    formData.append('image', file);
    return api.postForm(`/users/${id}/avatar`, formData);
  },
  getFollowers:   (id) => api.get(`/users/${id}/followers`),
  getFollowing:   (id) => api.get(`/users/${id}/following`),
  getFollowersPage: (id, page = 0) => api.get(`/users/${id}/followers/page?page=${page}`),
  getFollowingPage: (id, page = 0) => api.get(`/users/${id}/following/page?page=${page}`),
  discover:       () => api.get('/users/discover'),
  followStatus:   (id) => api.get(`/users/${id}/follow-status`),
  follow:         (id) => api.post(`/users/${id}/follow`),
  unfollow:       (id) => api.del(`/users/${id}/follow`),
  getSuggestions: (id) => api.get(`/users/${id}/suggestions`),
  getPosts:       (id) => api.get(`/users/${id}/posts`),
  search:         (query) => api.get(`/users/search?q=${encodeURIComponent(query)}`),
};

/* ── Posts & Feed endpoints ──────────────────────────────────────────────── */
export const postsApi = {
  create:         (data) => api.post('/posts', data),
  createWithImage: (content, file, onProgress) => new Promise((resolve, reject) => {
    const form = new FormData();
    form.append('content', content);
    form.append('image', file);
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${BASE}/posts/with-image`);
    xhr.withCredentials = true; // Required for HttpOnly cookie
    const token = getAccessToken();
    if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`);
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress?.(Math.round(event.loaded * 100 / event.total));
    };
    xhr.onerror = () => reject(new Error('No se pudo conectar con el servidor'));
    xhr.onload = () => {
      let data;
      try { data = JSON.parse(xhr.responseText); } catch { data = null; }
      if (xhr.status >= 200 && xhr.status < 300) resolve(data);
      else reject(new Error(data?.message || `Error al subir imagen (${xhr.status})`));
    };
    xhr.send(form);
  }),
  mediaUrl:       (id) => api.get(`/posts/${id}/media-url`),
  getById:        (id) => api.get(`/posts/${id}`),
  delete:         (id) => api.del(`/posts/${id}`),
  like:           (id) => api.post(`/posts/${id}/like`),
  unlike:         (id) => api.del(`/posts/${id}/like`),
  getComments:    (id) => api.get(`/posts/${id}/comments`),
  addComment:     (id, text) => api.post(`/posts/${id}/comments`, { text }),
};

export const feedApi = {
  getHome:        (skip = 0, limit = 20) => api.get(`/feed?skip=${skip}&limit=${limit}`),
  getExplore:     (skip = 0, limit = 20) => api.get(`/feed/explore?skip=${skip}&limit=${limit}`),
  ticket:         () => api.post('/feed/ws-ticket'),
};

export const messagesApi = {
  partners: () => api.get('/messages/conversations'),
  history: (userId, skip = 0, limit = 50) => api.get(`/messages/${userId}?skip=${skip}&limit=${limit}`),
  ticket: (otherUserId) => api.post('/messages/ws-ticket', { otherUserId }),
  markRead: (userId) => api.post(`/messages/${userId}/read`),
};

export const graphApi = {
  common: (otherId) => api.get(`/graph/common/${encodeURIComponent(otherId)}`),
  reachable: () => api.get('/graph/reachable'),
  recommendations: () => api.get('/graph/recommendations'),
  networkPosts: () => api.get('/graph/network-posts'),
  trendingPosts: () => api.get('/graph/trending-posts'),
};

export const pushApi = {
  publicKey: () => api.get('/push/public-key'),
  subscribe: (subscription) => api.post('/push/subscriptions', subscription),
  unsubscribe: (endpoint) => api.del('/push/subscriptions', { endpoint }),
};

export const notificationsApi = {
  getAll: () => api.get('/notifications'),
  getUnreadCount: () => api.get('/notifications/unread'),
  markAllAsRead: () => api.post('/notifications/read-all'),
};

export const presenceApi = {
  heartbeat: (sessionId) => api.post('/presence/session', { sessionId }),
  disconnect: (sessionId) => api.del(`/presence/session/${sessionId}`),
  disconnectOnUnload: (sessionId) => fetch(`${BASE}/presence/session/${sessionId}`, {
    method: 'DELETE', credentials: 'include', headers: authHeader(), keepalive: true,
  }).catch(() => {}),
  getStatus: (userId) => api.get(`/presence/status/${userId}`),
  getBatchStatus: (userIds) => api.post('/presence/status/batch', userIds),
  getOnlineUsers: () => api.get('/presence/online'),
  getMyStatus: () => api.get('/presence/me'),
};

// Export token management for AuthContext
export const tokenStore = {
  get: getAccessToken,
  set: setAccessToken,
  clear: clearAccessToken,
};
