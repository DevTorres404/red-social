import { createContext, useContext, useState, useCallback, useEffect } from 'react';
import { authApi, presenceApi, tokenStore } from '../lib/api';
import { presenceSessionId } from '../lib/presenceSession';
import { disablePush, restorePushForUser } from '../lib/push';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser]   = useState(null);
  const [token, setToken] = useState(() => tokenStore.get());
  const [restoring, setRestoring] = useState(true);

  // On mount, try to restore session using the access token in memory
  // If no token, try to refresh using the HttpOnly cookie
  useEffect(() => {
    let active = true;

    const tryRestore = async () => {
      const existingToken = tokenStore.get();
      if (!existingToken) {
        // No access token in memory, try to refresh from cookie
        try {
          await authApi.refresh();
        } catch {
          // Refresh failed (no cookie, expired, etc.) - user stays logged out
        }
      }
      
      if (active) {
        const currentToken = tokenStore.get();
        if (currentToken) {
          try {
            const profile = await authApi.me();
            try { await restorePushForUser(profile.id); } catch { /* Push cannot invalidate login. */ }
            setUser(profile);
            setToken(currentToken);
          } catch {
            tokenStore.clear();
            setToken(null);
            setUser(null);
          }
        }
        setRestoring(false);
      }
    };

    tryRestore();
    return () => { active = false; };
  }, []);

  const login = useCallback(async (credentials) => {
    const data = await authApi.login(credentials);
    try { await restorePushForUser(data.user.id); } catch { /* Push cannot invalidate login. */ }
    setToken(data.token);
    setUser(data.user);
    return data;
  }, []);

  const register = useCallback(async (formData) => {
    const data = await authApi.register(formData);
    try { await restorePushForUser(data.user.id); } catch { /* Push cannot invalidate registration. */ }
    setToken(data.token);
    setUser(data.user);
    return data;
  }, []);

  const logout = useCallback(async () => {
    if (tokenStore.get()) {
      try { await presenceApi.disconnect(presenceSessionId); } catch { /* lease will expire */ }
    }
    try { await disablePush(user?.id, { remember: false }); } catch { /* logout must remain available offline */ }
    try { await authApi.logout(); } catch { /* best effort */ }
    tokenStore.clear();
    setToken(null);
    setUser(null);
  }, [user]);

  const updateUser = useCallback((updated) => {
    setUser(current => current ? { ...current, ...updated } : current);
  }, []);

  return (
    <AuthContext.Provider value={{ user, token, login, register, logout, updateUser, restoring, isAuthenticated: !!token && !!user }}>
      {children}
    </AuthContext.Provider>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
