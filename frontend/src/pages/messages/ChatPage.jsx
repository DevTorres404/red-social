import { Link, useParams, useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { ArrowLeft, CircleAlert, MessageCircle, PenLine, Search, Send, Users, X } from 'lucide-react';
import { useChat } from './useChat';
import './ChatPage.css';

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
  const {
    user,
    isOnline,
    partners,
    other,
    messages,
    draft,
    setDraft,
    status,
    error,
    historyLoaded,
    search,
    setSearch,
    setSearchResults,
    searching,
    searchError,
    hasOlder,
    sending,
    messagesContainerRef,
    searchInputRef,
    send,
    loadOlder,
    handleSearch,
    isConnected,
    visiblePartners,
    filteredPartners,
    newPeople,
    startNewChat
  } = useChat(userId);

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
