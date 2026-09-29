import { useEffect, useRef, useState } from 'react';
import { Link, useParams, useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { ArrowLeft, CircleAlert, MessageCircle, PenLine, Search, Send, Users, X } from 'lucide-react';
import { messagesApi, usersApi } from '../../lib/api';
import { useAuth } from '../../context/AuthContext';
import { usePresence } from '../../context/PresenceContext';
import './ChatPage.css';

function mergeMessages(previous, incoming) {
  const byId = new Map(previous.map(message => [message.id, message]));
  for (const message of incoming) byId.set(message.id, message);
  return [...byId.values()].sort((a, b) =>
    a.sentAt.localeCompare(b.sentAt) || a.id.localeCompare(b.id));
}

function Avatar({ profile, className = '', size = 40 }) {
  return <span className={`chat-avatar ${className}`} aria-hidden="true" style={{ width: size, height: size, fontSize: size * 0.425 }}>
    {profile?.avatarUrl ? <img src={profile.avatarUrl} alt="" /> : profile?.username?.slice(0, 1).toUpperCase() || '?'}
  </span>;
}

function messageDay(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleDateString('es-EC', { day: 'numeric', month: 'long', year: 'numeric' });
}

function messageTime(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('es-EC', { hour: '2-digit', minute: '2-digit' });
}

export default function ChatPage() {
  const { userId } = useParams();
  const navigate = useNavigate();
  return <ChatConversation key={userId || 'inbox'} userId={userId} navigate={navigate} />;
}

function ChatConversation({ userId, navigate }) {
  const { user } = useAuth();
  const { isOnline } = usePresence();
  const [partners, setPartners] = useState([]);
  const [other, setOther] = useState(null);
  const [messages, setMessages] = useState([]);
  const [draft, setDraft] = useState('');
  const [status, setStatus] = useState('Cargando...');
  const [error, setError] = useState('');
  const [historyLoaded, setHistoryLoaded] = useState(false);
  const [search, setSearch] = useState('');
  const [searchResults, setSearchResults] = useState([]);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState('');
  const [hasOlder, setHasOlder] = useState(false);
  const [sending, setSending] = useState(false);
  const socketRef = useRef(null);
  const pendingRef = useRef(null);
  const messagesContainerRef = useRef(null);
  const searchInputRef = useRef(null);

  useEffect(() => {
    const query = search.trim();
    if (query.length < 2) return;
    let active = true;
    const timer = window.setTimeout(() => {
      usersApi.search(query).then(results => {
        if (active) { setSearchResults((results || []).filter(person => person.id !== user.id)); setSearching(false); }
      }).catch(() => {
        if (active) { setSearchError('No se pudo buscar personas.'); setSearching(false); }
      });
    }, 250);
    return () => { active = false; window.clearTimeout(timer); };
  }, [search, user.id]);

  useEffect(() => {
    let active = true;
    messagesApi.partners().then(ids => Promise.all((ids || []).map(id => usersApi.getProfile(id))))
      .then(profiles => { if (active) setPartners(profiles); })
      .catch(err => { if (active) setError(err.message || 'No se pudieron cargar las conversaciones'); });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!userId) return;
    let active = true;
    let socket;
    let retryTimer;
    let attempt = 0;
    pendingRef.current = null;
    const connect = async () => {
      try {
        const [profile, history, credentials] = await Promise.all([
          usersApi.getProfile(userId),
          messagesApi.history(userId),
          messagesApi.ticket(userId),
        ]);
        if (!active) return;
        setOther(profile);
        setMessages(previous => mergeMessages(previous, history || []));
        setHasOlder((history || []).length === 50);
        setHistoryLoaded(true);
        await messagesApi.markRead(userId);
        const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        socket = new WebSocket(`${scheme}//${window.location.host}/ws/chat/${credentials.conversationId}`,
          ['orbit-chat', `ticket.${credentials.ticket}`]);
        socketRef.current = socket;
        socket.onopen = () => {
          attempt = 0;
          setStatus('Mi conexión activa');
          if (pendingRef.current) socket.send(JSON.stringify(pendingRef.current));
        };
        socket.onmessage = event => {
          let data;
          try { data = JSON.parse(event.data); } catch { return; }
          if (data.type === 'message' && data.message) {
            setMessages(previous => mergeMessages(previous, [data.message]));
            setPartners(previous => previous.some(partner => partner.id === profile.id)
              ? previous : [...previous, profile]);
            if (data.clientMessageId === pendingRef.current?.clientMessageId) {
              pendingRef.current = null;
              setDraft('');
              setSending(false);
            }
            if (data.message.senderId === userId) messagesApi.markRead(userId).catch(() => {});
          } else if (data.type === 'error') {
            setError(data.error || 'No se pudo enviar el mensaje');
            if (data.clientMessageId === pendingRef.current?.clientMessageId) {
              pendingRef.current = null;
              setSending(false);
            }
          }
        };
        socket.onclose = () => {
          if (!active) return;
          setStatus('Reconectando...');
          attempt += 1;
          retryTimer = window.setTimeout(connect, Math.min(1000 * 2 ** attempt, 10000));
        };
      } catch (err) {
        if (!active) return;
        setError(err.message || 'No se pudo abrir la conversación');
        if (err.status === 401) { setStatus('Sesión caducada'); setSending(false); return; }
        setStatus('Reconectando...');
        attempt += 1;
        retryTimer = window.setTimeout(connect, Math.min(1000 * 2 ** attempt, 10000));
      }
    };
    connect();
    return () => {
      active = false;
      window.clearTimeout(retryTimer);
      socket?.close();
      socketRef.current = null;
    };
  }, [userId]);

  useEffect(() => {
    const container = messagesContainerRef.current;
    if (container) container.scrollTo({ top: container.scrollHeight, behavior: 'smooth' });
  }, [messages.length]);

  const send = event => {
    event.preventDefault();
    if (!draft.trim() || sending || socketRef.current?.readyState !== WebSocket.OPEN) return;
    const outgoing = { clientMessageId: crypto.randomUUID(), text: draft.trim() };
    pendingRef.current = outgoing;
    setSending(true);
    setError('');
    socketRef.current.send(JSON.stringify(outgoing));
  };

  const loadOlder = async () => {
    try {
      const older = await messagesApi.history(userId, messages.length, 50);
      setMessages(previous => mergeMessages(previous, older || []));
      setHasOlder((older || []).length === 50);
    } catch (err) {
      setError(err.message || 'No se pudo cargar el historial');
    }
  };

  const handleSearch = event => {
    const value = event.target.value;
    setSearch(value);
    setSearchResults([]);
    setSearchError('');
    setSearching(value.trim().length >= 2);
  };

  const isConnected = status === 'Mi conexión activa';
  const visiblePartners = other && !partners.some(partner => partner.id === other.id)
    ? [other, ...partners] : partners;
  const filteredPartners = visiblePartners.filter(partner =>
    partner.username.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase())
  );
  const newPeople = searchResults.filter(person => !visiblePartners.some(partner => partner.id === person.id));

  const startNewChat = () => {
    setSearch('');
    setSearchResults([]);
    setSearching(false);
    setSearchError('');
    searchInputRef.current?.focus();
  };

  return (
    <motion.div
      className={`messages-page ${userId ? 'with-conversation' : ''}`}
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -10 }}
    >
      <div className={`chat-layout ${userId ? 'with-conversation' : ''}`}>
        {/* Sidebar / Conversation List */}
        <aside className="chat-sidebar" aria-label="Conversaciones">
          <header className="chat-sidebar-header">
            <div className="chat-sidebar-title">
              <h1>Mensajes</h1>
              <span className="chat-count">{visiblePartners.length} {visiblePartners.length === 1 ? 'chat' : 'chats'}</span>
            </div>
            <button
              className="chat-new-chat-btn"
              onClick={startNewChat}
              aria-label="Nueva conversación"
              title="Nueva conversación"
            >
              <PenLine size={20} />
            </button>
          </header>

          <div className="chat-search">
            <Search size={18} aria-hidden="true" />
            <label htmlFor="chat-search-input" className="sr-only">Buscar conversaciones</label>
            <input
              id="chat-search-input"
              ref={searchInputRef}
              value={search}
              onChange={handleSearch}
              placeholder="Buscar..."
              autoComplete="off"
            />
            {search && (
              <button
                type="button"
                className="chat-search-clear"
                onClick={() => { setSearch(''); setSearchResults([]); }}
                aria-label="Limpiar búsqueda"
              >
                <X size={16} />
              </button>
            )}
          </div>

          <div className="chat-sidebar-list">
            {filteredPartners.length ? filteredPartners.map(partner => (
              <Link
                key={partner.id}
                to={`/messages/${partner.id}`}
                className={`chat-partner-item ${partner.id === userId ? 'active' : ''}`}
                aria-current={partner.id === userId ? 'page' : undefined}
              >
                <span className="chat-avatar-wrap">
                  <Avatar profile={partner} size={48} />
                  {isOnline(partner.id) && <span className="chat-online-dot" title="En línea" />}
                </span>
                <span className="chat-partner-copy">
                  <strong>@{partner.username}</strong>
                  <small>{isOnline(partner.id) ? 'En línea' : 'Fuera de línea'}</small>
                </span>
              </Link>
            )) : (
              <div className="chat-list-empty">
                {visiblePartners.length && search ? 'No hay conversaciones con ese nombre.' : 'Aún no tienes conversaciones.'}
              </div>
            )}

            {search.trim().length >= 2 && (
              <>
                <div className="chat-list-label">PERSONAS</div>
                {searching && <p className="chat-list-empty">Buscando personas...</p>}
                {searchError && <p className="chat-search-error" role="alert">{searchError}</p>}
                {!searching && !searchError && newPeople.length > 0 && newPeople.map(person => (
                  <Link
                    key={person.id}
                    to={`/messages/${person.id}`}
                    className="chat-partner-item"
                  >
                    <span className="chat-avatar-wrap">
                      <Avatar profile={person} size={48} />
                      {isOnline(person.id) && <span className="chat-online-dot" title="En línea" />}
                    </span>
                    <span className="chat-partner-copy">
                      <strong>@{person.username}</strong>
                      <small>Iniciar conversación</small>
                    </span>
                  </Link>
                ))}
                {!searching && !searchError && newPeople.length === 0 && (
                  <p className="chat-list-empty">No se encontraron personas nuevas.</p>
                )}
              </>
            )}

            {!visiblePartners.length && !search && (
              <Link to="/graph" className="chat-discover-link">
                <Users size={16} /> Descubrir personas
              </Link>
            )}
          </div>
        </aside>

        {/* Chat View */}
        <section className="chat-main" aria-label="Chat">
          {/* Mobile header when in chat view */}
          {userId && (
            <header className="chat-mobile-header">
              <button
                className="chat-back-btn"
                onClick={() => navigate('/messages')}
                aria-label="Volver a conversaciones"
              >
                <ArrowLeft size={20} />
              </button>
              {other && (
                <Link to={`/users/${other.id}`} className="chat-mobile-header-person">
                  <Avatar profile={other} size={36} />
                  <div className="chat-mobile-header-copy">
                    <strong>@{other.username}</strong>
                    <small>{isOnline(other.id) ? 'En línea' : 'Fuera de línea'}</small>
                  </div>
                </Link>
              )}
              {!isConnected && (
                <div className="chat-status reconnecting" role="status">
                  <span className="chat-status-dot" /><span>{status}</span>
                </div>
              )}
            </header>
          )}

          {!userId ? (
            // Empty inbox state
            <div className="chat-inbox-empty">
              <div className="chat-empty-illustration">
                <MessageCircle size={64} />
              </div>
              <h2>Bienvenido a Mensajes</h2>
              <p>Selecciona una conversación o inicia una nueva para empezar a chatear.</p>
              <Link to="/graph" className="chat-primary-link">
                <PenLine size={16} /> Nueva conversación
              </Link>
            </div>
          ) : (
            <>
              {/* Desktop header */}
              <header className="chat-header">
                  <div className="chat-header-person">
                    <Link to={`/users/${other?.id}`}>
                      <Avatar profile={other} size={44} />
                    </Link>
                    <div className="chat-header-copy">
                      <h2>
                        <Link to={`/users/${other?.id}`}>@{other?.username || 'Cargando...'}</Link>
                      </h2>
                      {other && <span>{isOnline(other.id) ? 'En línea' : 'Fuera de línea'}</span>}
                    </div>
                  </div>
                  {!isConnected && (
                    <div className="chat-status reconnecting" role="status">
                      <span className="chat-status-dot" /><span>{status}</span>
                    </div>
                  )}
              </header>

              {error && <div className="chat-error" role="alert"><CircleAlert size={17} />{error}</div>}

              <div className="chat-messages-container" ref={messagesContainerRef} aria-live="polite">
                {hasOlder && <button className="load-more-btn" type="button" onClick={loadOlder}>Cargar mensajes anteriores</button>}
                {!historyLoaded && !error && <p className="chat-history-loading">Cargando mensajes...</p>}
                {historyLoaded && messages.length === 0 && !error && (
                  <div className="chat-conversation-empty">
                    <Avatar profile={other} className="chat-empty-avatar" size={56} />
                    <h3>Saluda a {other ? `@${other.username}` : 'esta persona'}</h3>
                    <p>Esta conversación todavía no tiene mensajes. Da el primer paso.</p>
                  </div>
                )}
                <AnimatePresence initial={false}>
                  {messages.map((message, index) => {
                    const isMine = message.senderId === user.id;
                    const senderUser = isMine ? user : (partners.find(p => p.id === message.senderId) || other || {});
                    const day = messageDay(message.sentAt);
                    const previousDay = index ? messageDay(messages[index - 1].sentAt) : '';
                    return (
                      <div key={message.id} className="chat-message-block">
                        {day && day !== previousDay && <div className="chat-day-divider"><span>{day}</span></div>}
                        <motion.div
                          initial={{ opacity: 0, y: 10, scale: .98 }}
                          animate={{ opacity: 1, y: 0, scale: 1 }}
                          className={`chat-message-wrapper ${isMine ? 'outgoing' : 'incoming'}`}
                        >
                          {!isMine && (
                            <Link to={`/users/${message.senderId}`} aria-label={`Ver perfil de @${message.senderUsername}`}>
                              <Avatar profile={senderUser} className="chat-message-avatar" size={28} />
                            </Link>
                          )}
                          <div className={`chat-message ${isMine ? 'outgoing' : 'incoming'}`}>
                            <span className="chat-message-text">{message.text}</span>
                            <span className="chat-message-meta">
                              {messageTime(message.sentAt)}
                              {isMine && (
                                <span className="chat-read-mark" title={message.read ? 'Leído' : 'Enviado'} aria-label={message.read ? 'Leído' : 'Enviado'}>
                                  {message.read ? '✓✓' : '✓'}
                                </span>
                              )}
                            </span>
                          </div>
                        </motion.div>
                      </div>
                    );
                  })}
                </AnimatePresence>
              </div>

              <form className="chat-input-area" onSubmit={send}>
                <div className="chat-input-form">
                  <label htmlFor="chat-draft" className="sr-only">Escribe tu mensaje</label>
                  <input
                    id="chat-draft"
                    className="chat-input"
                    value={draft}
                    onChange={event => setDraft(event.target.value)}
                    maxLength={1000}
                    disabled={sending || !isConnected}
                    placeholder={isConnected ? 'Escribe un mensaje...' : 'Esperando conexión...'}
                    autoComplete="off"
                  />
                  <button
                    className="chat-send-btn"
                    type="submit"
                    disabled={!draft.trim() || sending || !isConnected}
                    aria-label="Enviar mensaje"
                  >
                    <Send size={18} />
                    <span>{sending ? 'Enviando' : 'Enviar'}</span>
                  </button>
                </div>
                <span className="chat-compose-hint">Conversación privada en Orbit</span>
              </form>
            </>
          )}
        </section>
      </div>
    </motion.div>
  );
}
