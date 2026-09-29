import { createContext, useContext, useEffect, useState } from 'react';
import { useAuth } from './AuthContext';
import { presenceApi } from '../lib/api';
import { presenceSessionId } from '../lib/presenceSession';

const PresenceContext = createContext(null);
const HEARTBEAT_INTERVAL_MS = 15_000;

export function PresenceProvider({ children }) {
  const { user } = useAuth();
  const [snapshot, setSnapshot] = useState({ viewerId: null, users: new Set() });

  useEffect(() => {
    if (!user?.id) return;
    let active = true;
    let inFlight = false;

    const refresh = async () => {
      if (!active || inFlight || document.visibilityState === 'hidden') return;
      inFlight = true;
      try {
        await presenceApi.heartbeat(presenceSessionId);
        if (!active || document.visibilityState === 'hidden') {
          await presenceApi.disconnect(presenceSessionId).catch(() => {});
          return;
        }
        const ids = await presenceApi.getOnlineUsers();
        if (active) setSnapshot({ viewerId: user.id, users: new Set(ids) });
      } catch {
        if (active) setSnapshot({ viewerId: user.id, users: new Set() });
      } finally {
        inFlight = false;
      }
    };

    const onVisibility = () => {
      if (document.visibilityState === 'hidden') {
        setSnapshot({ viewerId: user.id, users: new Set() });
        presenceApi.disconnect(presenceSessionId).catch(() => {});
      } else {
        refresh();
      }
    };
    const onPageHide = () => presenceApi.disconnectOnUnload(presenceSessionId);

    refresh();
    const interval = window.setInterval(refresh, HEARTBEAT_INTERVAL_MS);
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('pagehide', onPageHide);
    return () => {
      active = false;
      window.clearInterval(interval);
      window.removeEventListener('focus', refresh);
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('pagehide', onPageHide);
      presenceApi.disconnect(presenceSessionId).catch(() => {});
    };
  }, [user?.id]);

  return (
    <PresenceContext.Provider value={{ isOnline: id => !!user && snapshot.viewerId === user.id && snapshot.users.has(id) }}>
      {children}
    </PresenceContext.Provider>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export function usePresence() {
  const context = useContext(PresenceContext);
  if (!context) throw new Error('usePresence must be used within PresenceProvider');
  return context;
}
