import { useEffect, useRef, useState } from 'react';
import { messagesApi, usersApi } from '../../lib/api';
import { useAuth } from '../../context/AuthContext';
import { usePresence } from '../../context/PresenceContext';

export function mergeMessages(previous, incoming) {
  const byId = new Map(previous.map(message => [message.id, message]));
  for (const message of incoming) byId.set(message.id, message);
  return [...byId.values()].sort((a, b) =>
    a.sentAt.localeCompare(b.sentAt) || a.id.localeCompare(b.id));
}

export function useChat(userId) {
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

  return {
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
    searchResults,
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
  };
}
