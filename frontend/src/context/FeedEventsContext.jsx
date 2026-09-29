import { createContext, useContext, useEffect, useCallback } from 'react';
import { useAuth } from './AuthContext';
import * as feedSocket from '../lib/feedSocket';

const FeedEventsContext = createContext(null);

/**
 * Connects the broadcast-only feed WebSocket while the user is authenticated
 * and disconnects on logout/unmount. Pages consume events via subscribe().
 */
export function FeedEventsProvider({ children }) {
  const { user, isAuthenticated } = useAuth();

  useEffect(() => {
    if (!isAuthenticated || !user) return undefined;
    feedSocket.connect();
    return () => { feedSocket.disconnect(); };
  }, [isAuthenticated, user]);

  const subscribe = useCallback((handler) => feedSocket.subscribe(handler), []);

  return (
    <FeedEventsContext.Provider value={{ subscribe }}>
      {children}
    </FeedEventsContext.Provider>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useFeedEvents() {
  const ctx = useContext(FeedEventsContext);
  if (!ctx) throw new Error('useFeedEvents must be used within FeedEventsProvider');
  return ctx;
}