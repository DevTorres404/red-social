import { useState, useEffect, useRef } from 'react';
import { Link, useNavigate, NavLink } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { usersApi, notificationsApi } from '../../lib/api';
import { collectNewUnread, describeNotification } from '../../lib/notifications';
import { Home, MessageSquare, Compass, LogOut, Bell, Heart, UserPlus, AtSign, MessageCircle, Search } from 'lucide-react';
import './Navbar.css';

export default function Navbar() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [results, setResults] = useState([]);
  const [isSearching, setIsSearching] = useState(false);
  const [searchFocused, setSearchFocused] = useState(false);
  const [activeResultIndex, setActiveResultIndex] = useState(-1);
  const [searchError, setSearchError] = useState('');
  const searchRef = useRef(null);
  
  // Notifications state
  const [unreadCount, setUnreadCount] = useState(0);
  const [notifications, setNotifications] = useState([]);
  const [showNotifications, setShowNotifications] = useState(false);
  const [notificationError, setNotificationError] = useState('');
  const notifRef = useRef(null);
  const seenIdsRef = useRef(null);
  const [toasts, setToasts] = useState([]);
  const activeToastId = toasts[0]?.id;

  // Scroll state for navbar elevation
  const [scrolled, setScrolled] = useState(false);

  useEffect(() => {
    const handleScroll = () => {
      setScrolled(window.scrollY > 8);
    };
    window.addEventListener('scroll', handleScroll, { passive: true });
    return () => window.removeEventListener('scroll', handleScroll);
  }, []);

  useEffect(() => { seenIdsRef.current = null; }, [user?.id]);

  useEffect(() => {
    if (!activeToastId) return;
    const timer = window.setTimeout(() => setToasts(current => current.slice(1)), 5_000);
    return () => window.clearTimeout(timer);
  }, [activeToastId]);

  // The in-app toast does not depend on OS notification permission.
  useEffect(() => {
    if (!user) return;
    let active = true;
    let inFlight = false;
    const poll = async () => {
      if (!active || inFlight || document.visibilityState !== 'visible') return;
      inFlight = true;
      try {
        const [items, count] = await Promise.all([
          notificationsApi.getAll(), notificationsApi.getUnreadCount(),
        ]);
        if (!active) return;
        const list = Array.isArray(items) ? items : [];
        const { seenIds, fresh } = collectNewUnread(seenIdsRef.current, list);
        seenIdsRef.current = seenIds;
        if (fresh.length) {
          setToasts(current => [...current, ...fresh.map(item => ({ id: item.id, ...describeNotification(item) }))]);
        }
        if (showNotifications && list.some(item => !item.read)) {
          await notificationsApi.markAllAsRead();
          if (!active) return;
          setNotifications(list.map(item => ({ ...item, read: true })));
          setUnreadCount(0);
        } else {
          if (showNotifications) setNotifications(list);
          setUnreadCount(count.unread || 0);
        }
      } catch (error) { console.error(error); }
      finally { inFlight = false; }
    };
    poll();
    const id = window.setInterval(poll, 5_000);
    window.addEventListener('focus', poll);
    document.addEventListener('visibilitychange', poll);
    return () => {
      active = false;
      window.clearInterval(id);
      window.removeEventListener('focus', poll);
      document.removeEventListener('visibilitychange', poll);
    };
  }, [user, showNotifications]);

  useEffect(() => {
    function handleClickOutside(event) {
      if (notifRef.current && !notifRef.current.contains(event.target)) {
        setShowNotifications(false);
      }
      if (searchRef.current && !searchRef.current.contains(event.target)) {
        setSearchFocused(false);
      }
    }
    function handleEscape(event) {
      if (event.key === 'Escape') {
        setShowNotifications(false);
        setSearchFocused(false);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    document.addEventListener('keydown', handleEscape);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener('keydown', handleEscape);
    };
  }, []);

  useEffect(() => {
    const query = search.trim();
    if (query.length < 2) return;
    let active = true;
    const timer = window.setTimeout(() => {
      usersApi.search(query).then(data => {
        if (active) { setResults(Array.isArray(data) ? data : []); setIsSearching(false); }
      }).catch(() => {
        if (active) { setSearchError('No se pudo buscar personas.'); setIsSearching(false); }
      });
    }, 250);
    return () => { active = false; window.clearTimeout(timer); };
  }, [search]);

  const handleSearch = (e) => {
    const val = e.target.value;
    setSearch(val);
    setResults([]);
    setSearchError('');
    setActiveResultIndex(-1);
    setIsSearching(val.trim().length >= 2);
    setSearchFocused(true);
  };

  const handleSearchKeyDown = (event) => {
    if (event.key === 'Escape') { setSearchFocused(false); return; }
    if (!results.length || !searchFocused) return;
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActiveResultIndex(current => (current + 1) % results.length);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActiveResultIndex(current => (current - 1 + results.length) % results.length);
    } else if (event.key === 'Enter' && activeResultIndex >= 0) {
      event.preventDefault();
      handleResultClick(results[activeResultIndex].id);
    }
  };

  const handleResultClick = (userId) => {
    setSearch('');
    setResults([]);
    setSearchFocused(false);
    setActiveResultIndex(-1);
    navigate(`/users/${userId}`);
  };

  const toggleNotifications = async () => {
    if (!showNotifications) {
      // Opening
      setNotificationError('');
      try {
        const notifs = await notificationsApi.getAll();
        setNotifications(notifs || []);
        seenIdsRef.current = collectNewUnread(seenIdsRef.current, notifs || []).seenIds;
        if ((notifs || []).some(n => !n.read)) {
          await notificationsApi.markAllAsRead();
          setUnreadCount(0);
          setNotifications(notifs.map(n => ({ ...n, read: true })));
        }
      } catch (err) {
        console.error(err);
        setNotificationError('No se pudieron actualizar las notificaciones.');
      }
    }
    setShowNotifications(current => !current);
  };

  const formatTime = (isoString) => {
    const date = new Date(isoString);
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  };

  return (
    <nav className={`navbar ${scrolled ? 'scrolled' : ''}`} aria-label="Navegación principal">
      <div className="navbar__brand">
        <Link to="/feed" style={{ display: 'flex', alignItems: 'center' }}>
          <img src="/logo.png" alt="Orbit — ir al inicio" className="navbar-logo-img" />
        </Link>
      </div>

      <div className="navbar__search" ref={searchRef} onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setSearchFocused(false);
      }}>
        <Search size={18} className="navbar__search-icon" aria-hidden="true" />
        <label htmlFor="navbar-user-search" className="sr-only">Buscar personas</label>
        <input 
          id="navbar-user-search"
          type="text" 
          placeholder="Buscar personas en Orbit..." 
          value={search}
          onChange={handleSearch}
          onFocus={() => setSearchFocused(true)}
          onKeyDown={handleSearchKeyDown}
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={searchFocused && search.trim().length >= 2}
          aria-controls={searchFocused && search.trim().length >= 2 ? 'navbar-search-results' : undefined}
          aria-activedescendant={searchFocused && activeResultIndex >= 0 ? `navbar-result-${activeResultIndex}` : undefined}
          autoComplete="off"
        />
        
        {/* Search Results Dropdown */}
        {searchFocused && search.trim().length >= 2 && (
          <div className="search-dropdown" id="navbar-search-results" role="listbox" aria-label="Personas encontradas">
            {isSearching ? (
              <div className="search-dropdown__status" role="status">Buscando...</div>
            ) : searchError ? (
              <div className="search-dropdown__status" role="alert">{searchError}</div>
            ) : results.length > 0 ? (
              results.map((r, index) => (
                <button
                  key={r.id} 
                  id={`navbar-result-${index}`}
                  type="button"
                  role="option"
                  aria-selected={activeResultIndex === index}
                  className={`search-dropdown__item ${activeResultIndex === index ? 'active' : ''}`}
                  onClick={() => handleResultClick(r.id)}
                >
                  <div className="search-avatar">
                    {r.avatarUrl ? <img src={r.avatarUrl} alt="" /> : <span>{r.username?.charAt(0).toUpperCase()}</span>}
                  </div>
                  <div className="search-info">
                    <span className="search-username">@{r.username}</span>
                  </div>
                </button>
              ))
            ) : (
              <div className="search-dropdown__status">No se encontraron personas.</div>
            )}
          </div>
        )}
      </div>

      <div className="navbar__menu">
        <NavLink to="/feed" aria-label="Inicio" title="Inicio" className={({ isActive }) => `nav-profile-link ${isActive ? 'active' : ''}`}>
          <Home size={18} />
          <span>Feed</span>
        </NavLink>
        <NavLink to="/messages" aria-label="Mensajes" title="Mensajes" className={({ isActive }) => `nav-profile-link ${isActive ? 'active' : ''}`}>
          <MessageSquare size={18} />
          <span>Mensajes</span>
        </NavLink>
        <NavLink to="/graph" aria-label="Red" title="Red" className={({ isActive }) => `nav-profile-link ${isActive ? 'active' : ''}`}>
          <Compass size={18} />
          <span>Red</span>
        </NavLink>
        
        {/* Notifications Tray */}
        <div className="nav-notifications-wrapper" ref={notifRef}>
          <button type="button" className="nav-icon-btn" onClick={toggleNotifications} aria-label={`Notificaciones${unreadCount ? `, ${unreadCount} sin leer` : ''}`} aria-expanded={showNotifications} aria-controls="navbar-notifications">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path>
              <path d="M13.73 21a2 2 0 0 1-3.46 0"></path>
            </svg>
            {unreadCount > 0 && <span className="nav-badge" aria-hidden="true">{unreadCount > 99 ? '99+' : unreadCount}</span>}
          </button>
          
          {showNotifications && (
            <div className="notifications-dropdown" id="navbar-notifications">
              <div className="notifications-header">
                <h3>Notificaciones</h3>
                <Link to="/settings/notifications" className="settings-link" aria-label="Configurar notificaciones" title="Configurar notificaciones" onClick={() => setShowNotifications(false)}>
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
                </Link>
              </div>
              {notificationError && <p className="notifications-error" role="alert">{notificationError}</p>}
              <div className="notifications-list">
                {notifications.length === 0 ? (
                  <div className="notifications-empty">No tienes notificaciones recientes.</div>
                ) : (
                  notifications.map(n => {
                    const { label, url } = describeNotification(n);
                    const isUnread = !n.read;
                    
                    const getIcon = (type) => {
                      switch (type) {
                        case 'LIKE': return <Heart size={18} color="#ec4899" />;
                        case 'COMMENT': return <MessageCircle size={18} color="#3b82f6" />;
                        case 'FOLLOW': return <UserPlus size={18} color="#10b981" />;
                        case 'MENTION': return <AtSign size={18} color="#8b5cf6" />;
                        case 'MESSAGE': return <MessageSquare size={18} color="#6366f1" />;
                        default: return <Bell size={18} color="#64748b" />;
                      }
                    };
                    
                    return (
                      <Link 
                        key={n.id} 
                        to={url} 
                        className={`notification-item ${isUnread ? 'unread' : ''}`}
                        onClick={() => setShowNotifications(false)}
                      >
                        <div className={`notification-icon-badge ${n.type?.toLowerCase() || 'default'}`}>
                          {getIcon(n.type)}
                        </div>
                        <div className="notification-content">
                          <p>{label}</p>
                          <span className="notification-time">{formatTime(n.createdAt)}</span>
                        </div>
                        {isUnread && <div className="unread-dot"></div>}
                      </Link>
                    )
                  })
                )}
              </div>
            </div>
          )}
        </div>

        <NavLink to={`/users/${user?.id}`} aria-label="Mi perfil" title="Mi perfil" className={({ isActive }) => `nav-profile-link ${isActive ? 'active' : ''}`}>
          <div className="nav-avatar">
            {user?.avatarUrl ? <img src={user.avatarUrl} alt="Me" /> : <span>{user?.username?.charAt(0).toUpperCase() || '?'}</span>}
          </div>
          <span>Mi Perfil</span>
        </NavLink>
        <button type="button" onClick={logout} className="btn-logout" aria-label="Cerrar sesión" title="Cerrar sesión">
          <LogOut size={16} />
          <span>Salir</span>
        </button>
      </div>

      {/* Toast Container - supports multiple stacked toasts */}
      {toasts.length > 0 && (
        <div className="nav-toast-container" role="region" aria-label="Notificaciones" aria-live="polite">
          {toasts.map((toast, index) => (
            <div
              key={toast.id}
              className={`nav-toast nav-toast--${toast.type || 'info'} ${toast.removing ? 'removing' : ''}`}
              role="alert"
              style={{ animationDelay: `${index * 80}ms` }}
            >
              {/* Icon */}
              <div className="nav-toast__icon" aria-hidden="true">
                {toast.type === 'success' && (
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="20 6 9 17 4 12"></polyline>
                  </svg>
                )}
                {toast.type === 'warning' && (
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
                    <line x1="12" y1="9" x2="12" y2="13"></line>
                    <line x1="12" y1="17" x2="12.01" y2="17"></line>
                  </svg>
                )}
                {toast.type === 'error' && (
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="12" cy="12" r="10"></circle>
                    <line x1="15" y1="9" x2="9" y2="15"></line>
                    <line x1="9" y1="9" x2="15" y2="15"></line>
                  </svg>
                )}
                {(!toast.type || toast.type === 'info') && (
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="12" cy="12" r="10"></circle>
                    <line x1="12" y1="16" x2="12" y2="12"></line>
                    <line x1="12" y1="8" x2="12.01" y2="8"></line>
                  </svg>
                )}
              </div>

              {/* Content */}
              <div className="nav-toast__content">
                <p className="nav-toast__message">{toast.label}</p>
                {toast.url && (
                  <a
                    href={toast.url}
                    className="nav-toast__action"
                    onClick={(e) => {
                      e.preventDefault();
                      setToasts(current => current.filter(t => t.id !== toast.id));
                      navigate(toast.url);
                    }}
                  >
                    Ver
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                      <line x1="5" y1="12" x2="19" y2="12"></line>
                      <polyline points="12 5 19 12 12 19"></polyline>
                    </svg>
                  </a>
                )}
              </div>

              {/* Close button */}
              <button
                type="button"
                className="nav-toast__close"
                aria-label="Cerrar notificación"
                onClick={() => setToasts(current => current.filter(t => t.id !== toast.id))}
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <line x1="18" y1="6" x2="6" y2="18"></line>
                  <line x1="6" y1="6" x2="18" y2="18"></line>
                </svg>
              </button>

              {/* Progress bar for auto-dismiss */}
              <div className="nav-toast__progress" style={{ animationDuration: '5000ms' }} aria-hidden="true" />
            </div>
          ))}
        </div>
      )}
    </nav>
  );
}
