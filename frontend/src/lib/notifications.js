export function describeNotification(notification) {
  const actor = `@${notification.triggeredByUsername || 'alguien'}`;
  switch (notification.type) {
    case 'LIKE':
      return { 
        label: `${actor} le dio like a tu post`, 
        url: notification.postId ? `/posts/${notification.postId}` : '/feed',
        type: 'success'
      };
    case 'COMMENT':
      return { 
        label: `${actor} comentó tu post`, 
        url: notification.postId ? `/posts/${notification.postId}` : '/feed',
        type: 'info'
      };
    case 'FOLLOW':
      return { 
        label: `${actor} te empezó a seguir`, 
        url: notification.triggeredByUserId ? `/users/${notification.triggeredByUserId}` : '/feed',
        type: 'success'
      };
    case 'MENTION':
      return { 
        label: `${actor} te mencionó`, 
        url: notification.postId ? `/posts/${notification.postId}` : '/feed',
        type: 'info'
      };
    case 'POST':
      return { 
        label: `${actor} publicó algo nuevo`, 
        url: notification.postId ? `/posts/${notification.postId}` : '/feed',
        type: 'info'
      };
    case 'MESSAGE':
      return { 
        label: `${actor} te envió un mensaje`, 
        url: notification.triggeredByUserId ? `/messages/${notification.triggeredByUserId}` : '/messages',
        type: 'info'
      };
    default:
      return { 
        label: `Nueva notificación de ${actor}`, 
        url: '/feed',
        type: 'info'
      };
  }
}

// The first response establishes a baseline; old unread items stay in the bell
// rather than generating a burst of toasts at login. Later items are keyed by ID,
// not by unread count, so simultaneous events and read/unread races are covered.
export function collectNewUnread(seenIds, items) {
  const nextSeen = new Set(seenIds || []);
  const fresh = seenIds === null ? [] : items.filter(item => !item.read && !nextSeen.has(item.id)).reverse();
  for (const item of items) nextSeen.add(item.id);
  while (nextSeen.size > 1000) nextSeen.delete(nextSeen.values().next().value);
  return { seenIds: nextSeen, fresh };
}
