import { useState, useEffect } from 'react';
import { feedApi } from '../../lib/api';
import { useAuth } from '../../context/AuthContext';
import { useFeedEvents } from '../../context/FeedEventsContext';
import CreatePost from '../../components/feed/CreatePost';
import PostCard from '../../components/feed/PostCard';
import { Sparkles, Users, Compass } from 'lucide-react';
import './FeedPage.css';

const tabs = [
  { id: 'home', label: 'Siguiendo', Icon: Users },
  { id: 'explore', label: 'Explorar', Icon: Compass },
];

export default function FeedPage() {
  const { user } = useAuth();
  const { subscribe } = useFeedEvents();
  const [posts, setPosts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [pageError, setPageError] = useState('');
  const [feedType, setFeedType] = useState('home'); // 'home' | 'explore'
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const pageSize = 20;

  const changeFeed = (nextType) => {
    if (nextType === feedType) return;
    setLoading(true);
    setError('');
    setPageError('');
    setFeedType(nextType);
  };

  useEffect(() => {
    let active = true;
    const load = feedType === 'home' ? feedApi.getHome : feedApi.getExplore;
    load(0, pageSize).then(data => {
      if (!active) return;
      setPosts(data || []);
      setHasMore((data || []).length === pageSize);
    }).catch(err => {
      if (active) setError(err.message || 'Error al cargar el feed');
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [feedType]);

  const loadMore = async () => {
    if (loadingMore || !hasMore) return;
    setLoadingMore(true);
    setPageError('');
    try {
      const data = feedType === 'home'
        ? await feedApi.getHome(posts.length, pageSize)
        : await feedApi.getExplore(posts.length, pageSize);
      setPosts(previous => [...previous, ...(data || [])]);
      setHasMore((data || []).length === pageSize);
    } catch (err) {
      setPageError(err.message || 'Error al cargar más publicaciones');
    } finally {
      setLoadingMore(false);
    }
  };

  const handlePostCreated = (newPost) => {
    if (feedType === 'home') {
      setPosts((prev) => [newPost, ...prev]);
    } else {
      setFeedType('home');
    }
  };

  // Real-time feed updates: comment counts and like counts from the
  // broadcast channel, applied only to posts already on screen.
  useEffect(() => {
    const unsubscribe = subscribe((event) => {
      if (!event) return;
      if (event.type === 'comment-created') {
        setPosts((prev) => prev.map((post) =>
          post.id === event.postId ? { ...post, commentCount: event.commentCount } : post));
      } else if (event.type === 'like-changed') {
        setPosts((prev) => prev.map((post) => {
          if (post.id !== event.postId) return post;
          const next = { ...post, likeCount: event.likeCount };
          // Only the actor's own event changes MY like state; others only update the count.
          if (user && event.actorId === user.id) next.likedByCurrentUser = event.liked;
          return next;
        }));
      }
    });
    return unsubscribe;
  }, [subscribe, user]);

  return (
    <section className="feed-page">
      {/* ── Hero ── */}
      <header className="feed-hero">
        <div className="feed-hero-content">
          <span className="feed-hero-eyebrow">
            <span className="feed-hero-eyebrow-dot" /> TU FEED
          </span>
          <h1>Lo que pasa en <span>tu mundo.</span></h1>
          <p>Comparte lo que te inspira y descubre las historias de tu círculo.</p>
        </div>
        <div className="feed-hero-deco" aria-hidden="true">
          <span className="feed-hero-deco-line" />
          <span className="feed-hero-deco-line" />
          <span className="feed-hero-deco-line" />
          <span className="feed-hero-deco-line" />
        </div>
      </header>

      {/* ── Tabs ── */}
      <div className="feed-toolbar">
        <div className="feed-tabs" role="tablist" aria-label="Tipo de feed">
          {tabs.map(({ id, label, Icon }) => (
            <button
              key={id}
              type="button"
              role="tab"
              aria-selected={feedType === id}
              className={feedType === id ? 'active' : ''}
              onClick={() => changeFeed(id)}
            >
              <Icon size={17} />
              {label}
            </button>
          ))}
        </div>
      </div>

      {/* ── Create Post ── */}
      <CreatePost onPostCreated={handlePostCreated} />

      {/* ── Feed Content ── */}
      <div className="feed-content">
        {loading ? (
          <div className="network-loading" role="status">Cargando publicaciones...</div>
        ) : error ? (
          <div className="network-alert" role="alert">{error} <button type="button" onClick={() => setFeedType('home')}>Reintentar</button></div>
        ) : posts.length === 0 ? (
          <div className="network-empty">
            <span className="network-empty-icon"><Sparkles size={21} /></span>
            <strong>No hay nada por aquí</strong>
            <p>
              {feedType === 'home'
                ? 'Sigue a otros usuarios o crea una publicación para darle vida a tu feed.'
                : 'Todavía no hay publicaciones en la red.'}
            </p>
            <p className="network-empty-hint">
              {feedType === 'home'
                ? 'Ve a la pestaña Explorar para descubrir personas y contenido.'
                : 'Sigue a personas y vuelve para ver sus novedades.'}
            </p>
          </div>
        ) : (
          <>
            <div className="feed-posts">
              {posts.map((post) => (
                <PostCard
                  key={post.id}
                  post={post}
                  onDeleted={(id) => setPosts(previous => previous.filter(item => item.id !== id))}
                />
              ))}
            </div>

            {hasMore && (
              <button
                type="button"
                className="feed-load-more"
                onClick={loadMore}
                disabled={loadingMore}
              >
                {loadingMore ? 'Cargando...' : 'Cargar más'}
              </button>
            )}

            {pageError && <p className="feed-page-error" role="alert">{pageError}</p>}
          </>
        )}
      </div>
    </section>
  );
}
