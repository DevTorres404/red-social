import { useState, useEffect } from 'react';
import { motion } from 'framer-motion';
import { Heart, MessageCircle, Trash2 } from 'lucide-react';
import { formatDistanceToNow } from 'date-fns';
import { es } from 'date-fns/locale';
import { Link, useNavigate } from 'react-router-dom';
import { postsApi } from '../../lib/api';
import { useAuth } from '../../context/AuthContext';
import PostImage from './PostImage';
import './PostCard.css';

export default function PostCard({ post, onDeleted, showAuthor = true }) {
  const navigate = useNavigate();
  const { user } = useAuth();
  const [isLiked, setIsLiked] = useState(post.likedByCurrentUser || false);
  const [likesCount, setLikesCount] = useState(post.likeCount || 0);
  const [isLiking, setIsLiking] = useState(false);
  const [actionError, setActionError] = useState('');
  const [isDeleting, setIsDeleting] = useState(false);

  // Sync local like state when the parent re-renders with new props
  // (real-time like-changed events, feed reloads, etc.).
  // eslint-disable-next-line react/set-state-in-effect
  useEffect(() => {
    setIsLiked(post.likedByCurrentUser || false);
    setLikesCount(post.likeCount || 0);
  }, [post.likedByCurrentUser, post.likeCount]);

  const handleDelete = async (event) => {
    event.stopPropagation();
    if (isDeleting || !window.confirm('¿Eliminar esta publicación?')) return;
    setIsDeleting(true);
    setActionError('');
    try {
      await postsApi.delete(post.id);
      onDeleted?.(post.id);
    } catch (error) {
      setActionError(error.message || 'No se pudo eliminar la publicación');
    } finally {
      setIsDeleting(false);
    }
  };

  const handleLike = async () => {
    if (isLiking) return;
    setIsLiking(true);

    // Optimistic UI
    const originalLiked = isLiked;
    const originalCount = likesCount;

    setIsLiked(!isLiked);
    setLikesCount(isLiked ? likesCount - 1 : likesCount + 1);

    try {
      if (!isLiked) {
        await postsApi.like(post.id);
      } else {
        await postsApi.unlike(post.id);
      }
    } catch (error) {
      // Revert on error
      setIsLiked(originalLiked);
      setLikesCount(originalCount);
      console.error('Error toggling like:', error);
    } finally {
      setIsLiking(false);
    }
  };

  const formatDate = (isoString) => {
    if (!isoString) return '';
    try {
      return formatDistanceToNow(new Date(isoString), { addSuffix: true, locale: es });
    } catch {
      return '';
    }
  };

  return (
    <motion.div
      onClick={() => navigate(`/posts/${post.id}`)}
      className="post-card"
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
    >
      {/* Header */}
      <div className="post-card-header">
        {showAuthor ? (
          <Link to={`/users/${post.authorId}`} className="post-card-author" onClick={(event) => event.stopPropagation()} aria-label={`Ver perfil de ${post.authorUsername}`}>
            <div className="post-card-avatar">
              {post.authorAvatarUrl ? (
                <img src={post.authorAvatarUrl} alt={post.authorUsername} />
              ) : (
                <span>{post.authorUsername?.charAt(0).toUpperCase() || '?'}</span>
              )}
            </div>
            <div className="post-card-author-info">
              <div className="post-card-author-name">
                {post.authorUsername}
                <span className="post-card-author-handle">@{post.authorUsername?.toLowerCase()}</span>
              </div>
              <div className="post-card-date">{formatDate(post.createdAt)}</div>
            </div>
          </Link>
        ) : <div className="post-card-date">{formatDate(post.createdAt)}</div>}
        {user?.id === post.authorId && (
          <button
            type="button"
            className="post-card-delete"
            onClick={handleDelete}
            disabled={isDeleting}
            aria-label="Eliminar publicación"
          >
            <Trash2 size={18} />
          </button>
        )}
      </div>

      {/* Content */}
      <div className="post-card-content">{post.content}</div>
      {post.mediaKey && <PostImage postId={post.id} authorUsername={post.authorUsername} />}

      {/* Actions */}
      <div className="post-card-actions" onClick={(e) => e.stopPropagation()}>
        {/* Like Button */}
        <button
          type="button"
          className={`post-card-action ${isLiked ? 'liked' : ''}`}
          onClick={handleLike}
          disabled={isLiking}
          aria-label={isLiked ? 'Quitar me gusta' : 'Me gusta'}
        >
          <motion.div
            whileTap={{ scale: 0.8 }}
            animate={isLiked ? { scale: [1, 1.2, 1] } : {}}
            style={{ display: 'flex' }}
          >
            <Heart size={20} fill={isLiked ? 'currentColor' : 'none'} />
          </motion.div>
          <span>{likesCount}</span>
        </button>

        {/* Comment Button */}
        <button
          type="button"
          className="post-card-action"
          onClick={() => navigate(`/posts/${post.id}`)}
          aria-label="Ver comentarios"
        >
          <MessageCircle size={20} />
          <span>{post.commentCount || 0}</span>
        </button>
      </div>
      {actionError && <p className="post-card-error" role="alert">{actionError}</p>}
    </motion.div>
  );
}
