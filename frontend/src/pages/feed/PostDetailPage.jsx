import { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { ArrowLeft, Send } from 'lucide-react';
import { formatDistanceToNow } from 'date-fns';
import { es } from 'date-fns/locale';
import { postsApi } from '../../lib/api';
import { useAuth } from '../../context/AuthContext';
import { useFeedEvents } from '../../context/FeedEventsContext';
import PostCard from '../../components/feed/PostCard';

export default function PostDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { user } = useAuth();
  const { subscribe } = useFeedEvents();
  
  const [post, setPost] = useState(null);
  const [comments, setComments] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  
  const [newComment, setNewComment] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  // Latest comments, readable from the submit handler after an await.
  const commentsRef = useRef([]);
  useEffect(() => { commentsRef.current = comments; }, [comments]);

  const fetchData = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [postData, commentsData] = await Promise.all([
        postsApi.getById(id),
        postsApi.getComments(id)
      ]);
      setPost(postData);
      setComments(commentsData || []);
    } catch (err) {
      setError(err.message || 'Error al cargar la publicación');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Real-time updates for this post: append new comments (dedup against the
  // optimistic append of the author's own comment) and sync like state/count.
  useEffect(() => {
    const unsubscribe = subscribe((event) => {
      if (!event || event.postId !== id) return;
      if (event.type === 'comment-created' && event.comment) {
        setComments((prev) =>
          prev.some((comment) => comment.id === event.comment.id) ? prev : [...prev, event.comment]);
        setPost((prev) => prev ? { ...prev, commentCount: event.commentCount } : prev);
      } else if (event.type === 'like-changed') {
        setPost((prev) => {
          if (!prev) return prev;
          const next = { ...prev, likeCount: event.likeCount };
          if (user && event.actorId === user.id) next.likedByCurrentUser = event.liked;
          return next;
        });
      }
    });
    return unsubscribe;
  }, [subscribe, id, user]);

  const handleCommentSubmit = async (e) => {
    e.preventDefault();
    if (!newComment.trim()) return;

    setIsSubmitting(true);
    try {
      const addedComment = await postsApi.addComment(id, newComment.trim());
      // Dedup: the broadcast channel may have delivered this comment already.
      const echoed = commentsRef.current.some(c => c.id === addedComment.id);
      setComments(prev => prev.some(c => c.id === addedComment.id) ? prev : [...prev, addedComment]);
      setNewComment('');
      // The server broadcasts the frame BEFORE the 201 and that frame carries the
      // ABSOLUTE count: only count it here when the echo has not landed yet.
      setPost(prev => prev ? { ...prev, commentCount: echoed ? prev.commentCount : (prev.commentCount || 0) + 1 } : prev);
    } catch (err) {
      alert(err.message || 'Error al enviar comentario');
    } finally {
      setIsSubmitting(false);
    }
  };

  if (loading) {
    return <div style={{ textAlign: 'center', padding: '40px', color: 'var(--text-muted)' }}>Cargando...</div>;
  }

  if (error || !post) {
    return (
      <div style={{ maxWidth: '600px', margin: '0 auto', padding: '24px 16px' }}>
        <button onClick={() => navigate(-1)} style={{ color: 'var(--accent)', marginBottom: '16px', background: 'none' }}>
          &larr; Volver
        </button>
        <div style={{ color: 'var(--error)' }}>{error || 'Publicación no encontrada'}</div>
        <button onClick={() => navigate('/feed')} style={{ color: 'var(--accent)', marginTop: 16, background: 'none' }}>
          Ir al inicio
        </button>
      </div>
    );
  }

  return (
    <div style={{
      maxWidth: '600px',
      margin: '0 auto',
      padding: '24px 16px',
      minHeight: '100vh',
    }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '16px', marginBottom: '24px' }}>
        <button 
          onClick={() => navigate(-1)}
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: '40px',
            height: '40px',
            borderRadius: '50%',
            background: 'transparent',
            color: 'var(--text)',
            transition: 'background 0.2s',
          }}
          onMouseOver={(e) => e.currentTarget.style.background = 'var(--surface-2)'}
          onMouseOut={(e) => e.currentTarget.style.background = 'transparent'}
        >
          <ArrowLeft size={24} />
        </button>
        <h1 style={{ fontSize: '20px', fontWeight: 700 }}>Publicación</h1>
      </div>

      {/* Main Post (reusing PostCard but without cursor pointer) */}
      <div style={{ pointerEvents: 'none' }}>
        <div style={{ pointerEvents: 'auto' }}>
           <PostCard post={post} onDeleted={() => navigate('/feed')} />
        </div>
      </div>

      {/* Comments Section */}
      <div style={{ marginTop: '24px' }}>
        <h2 style={{ fontSize: '18px', fontWeight: 600, marginBottom: '16px' }}>Comentarios</h2>
        
        {/* Create Comment Form */}
        <form onSubmit={handleCommentSubmit} style={{ marginBottom: '32px' }}>
          <div style={{ display: 'flex', gap: '12px' }}>
            <div style={{
              width: '40px',
              height: '40px',
              borderRadius: '50%',
              background: 'var(--accent)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: '#fff',
              fontWeight: 'bold',
              fontSize: '16px',
              flexShrink: 0
            }}>
              {user?.username?.charAt(0).toUpperCase()}
            </div>
            <div style={{ flex: 1, position: 'relative' }}>
              <input
                type="text"
                placeholder="Responde a esta publicación..."
                value={newComment}
                onChange={(e) => setNewComment(e.target.value)}
                style={{
                  width: '100%',
                  background: 'var(--surface)',
                  border: '1px solid var(--border)',
                  borderRadius: 'var(--radius)',
                  padding: '12px 48px 12px 16px',
                  color: 'var(--text)',
                  fontSize: '15px',
                  outline: 'none',
                }}
              />
              <button
                type="submit"
                disabled={!newComment.trim() || isSubmitting}
                style={{
                  position: 'absolute',
                  right: '8px',
                  top: '50%',
                  transform: 'translateY(-50%)',
                  background: 'transparent',
                  color: newComment.trim() ? 'var(--accent)' : 'var(--border)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  cursor: newComment.trim() ? 'pointer' : 'default',
                  opacity: isSubmitting ? 0.5 : 1
                }}
              >
                <Send size={20} />
              </button>
            </div>
          </div>
        </form>

        {/* Comments List */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          {comments.map((comment) => (
            <motion.div 
              key={comment.id}
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              style={{
                display: 'flex',
                gap: '12px',
                padding: '16px',
                background: 'var(--surface)',
                borderRadius: 'var(--radius)',
                border: '1px solid var(--border)'
              }}
            >
              <div style={{
                width: '36px',
                height: '36px',
                borderRadius: '50%',
                background: 'var(--accent-2)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: '#fff',
                fontWeight: 'bold',
                fontSize: '14px',
                flexShrink: 0
              }}>
                {comment.authorUsername?.charAt(0).toUpperCase() || '?'}
              </div>
              <div>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: '8px', marginBottom: '4px' }}>
                  <span style={{ fontWeight: 600, color: 'var(--text)', fontSize: '15px' }}>
                    {comment.authorUsername}
                  </span>
                  <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                    {comment.createdAt ? formatDistanceToNow(new Date(comment.createdAt), { addSuffix: true, locale: es }) : ''}
                  </span>
                </div>
                <div style={{ color: 'var(--text)', fontSize: '15px' }}>
                  {comment.text}
                </div>
              </div>
            </motion.div>
          ))}
          {comments.length === 0 && (
            <div style={{ textAlign: 'center', padding: '24px', color: 'var(--text-muted)' }}>
              Sé el primero en responder.
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
