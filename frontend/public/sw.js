const TITLES = {
  POST_CREATED:  'Nueva publicación en tu red',
  POST_LIKED:    'A alguien le gustó tu post',
  USER_FOLLOWED: 'Tenés un nuevo seguidor',
  MESSAGE_SENT:  'Recibiste un mensaje nuevo',
};

const BODIES = {
  POST_CREATED:  'Abrí Orbit para verla.',
  POST_LIKED:    'Tocá para ver el post.',
  USER_FOLLOWED: 'Tocá para ver el perfil.',
  MESSAGE_SENT:  'Tocá para responder.',
};

self.addEventListener('push', (event) => {
  let data = {};
  try { data = event.data?.json() || {}; } catch { /* malformed — show generic */ }

  // Support both new payload {type, refId, url} and legacy {postId}
  const type = data.type || 'POST_CREATED';
  const url  = data.url  || (data.postId ? `/posts/${data.postId}` : '/feed');
  const path = url.startsWith('/') ? url : '/feed';

  event.waitUntil(self.registration.showNotification(TITLES[type] || 'Orbit', {
    body:      BODIES[type] || 'Abrí Orbit para más detalles.',
    tag:       data.refId || data.postId || type,
    renotify:  false,
    icon:      '/logo.png',
    data:      { path },
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const path = event.notification.data?.path || '/feed';
  event.waitUntil((async () => {
    const clientsList = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const existing = clientsList.find((c) => new URL(c.url).origin === self.location.origin);
    if (existing) {
      await existing.focus();
      if ('navigate' in existing) await existing.navigate(path);
    } else {
      await self.clients.openWindow(path);
    }
  })());
});
