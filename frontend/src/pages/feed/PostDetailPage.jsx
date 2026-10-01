import { useState, useEffect } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, ImagePlus, Send, SmilePlus, Trash2 } from 'lucide-react';
import { formatDistanceToNow } from 'date-fns';
import { es } from 'date-fns/locale';
import { postsApi } from '../../lib/api';
import { usePostDetail } from './usePostDetail';
import PostCard from '../../components/feed/PostCard';
import './PostDetailPage.css';

const EMOJIS = ['❤️', '😂', '😍', '😮', '😢', '👏'];

function relativeDate(value) {
  if (!value) return '';
  try { return formatDistanceToNow(new Date(value), { addSuffix: true, locale: es }); }
  catch { return ''; }
}

function CommentImage({ postId, comment }) {
  const [url, setUrl] = useState('');
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [imageRetries, setImageRetries] = useState(0);

  useEffect(() => {
    let active = true;
    postsApi.commentMediaUrl(postId, comment.id).then((result) => {
      if (active) { setUrl(result.url); setError(''); }
    }).catch((err) => { if (active) setError(err.message || 'No se pudo cargar la imagen.'); });
    return () => { active = false; };
  }, [postId, comment.id, attempt]);

  const handleImageError = () => {
    if (imageRetries < 3) {
      setTimeout(() => {
        setImageRetries(prev => prev + 1);
        setUrl(prev => {
          const separator = prev.includes('?') ? '&' : '?';
          return `${prev.split('&_retry=')[0]}${separator}_retry=${Date.now()}`;
        });
      }, 1000);
    } else {
      setError('La imagen no está disponible o su enlace expiró.');
    }
  };

  if (error) return <button type="button" className="comment-image-retry" onClick={() => { setError(''); setImageRetries(0); setAttempt((value) => value + 1); }}>Reintentar imagen</button>;
  if (!url) return <div className="comment-image-loading" role="status">Cargando imagen...</div>;
  return <img className="comment-image" src={url} alt={`Imagen del comentario de @${comment.authorUsername}`}
    onError={handleImageError} />;
}

export default function PostDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  
  const {
    user,
    post,
    comments,
    loading,
    error,
    newComment,
    setNewComment,
    imageSelection,
    formError,
    isSubmitting,
    reactionMenuId,
    setReactionMenuId,
    reactionBusyId,
    reactionError,
    fileInputRef,
    selectImage,
    removeImage,
    handleCommentSubmit,
    handleReaction
  } = usePostDetail(id);

  if (loading) return <div className="post-detail-status" role="status">Cargando publicación...</div>;
  if (error || !post) return <div className="post-detail-status" role="alert">
    <p>{error || 'Publicación no encontrada.'}</p>
    <button type="button" onClick={() => navigate('/feed')}>Ir al feed</button>
  </div>;

  return <main className="post-detail-page">
    <header className="post-detail-header">
      <button type="button" onClick={() => navigate(-1)} aria-label="Volver"><ArrowLeft size={20} /></button>
      <div><span>ORBIT · CONVERSACIÓN</span><h1>Publicación</h1></div>
    </header>

    <PostCard post={post} onDeleted={() => navigate('/feed')} />

    <section className="post-comments" aria-labelledby="comments-title">
      <div className="post-comments-heading"><div><span>LA CONVERSACIÓN</span><h2 id="comments-title">Comentarios <small>{comments.length}</small></h2></div>
        <p>Comparte una idea o una imagen con tu red.</p></div>

      <form className="comment-composer" onSubmit={handleCommentSubmit}>
        <Link className="comment-composer-avatar" to={`/users/${user.id}`} aria-label="Ir a mi perfil">
          {user.avatarUrl ? <img src={user.avatarUrl} alt="" /> : user.username?.slice(0, 1).toUpperCase()}
        </Link>
        <div className="comment-composer-body">
          <label className="sr-only" htmlFor="new-comment">Escribe un comentario</label>
          <textarea id="new-comment" placeholder="¿Qué piensas?" value={newComment} maxLength={500}
            onChange={(event) => setNewComment(event.target.value)} rows={3} />
          {imageSelection && <div className="comment-preview">
            <img src={imageSelection.preview} alt="Vista previa de la imagen para el comentario" />
            <button type="button" onClick={removeImage} aria-label="Quitar imagen"><Trash2 size={16} /></button>
          </div>}
          <div className="comment-composer-actions">
            <input ref={fileInputRef} type="file" accept="image/png,image/jpeg" onChange={selectImage} hidden />
            <button type="button" className="comment-attach" onClick={() => fileInputRef.current?.click()} disabled={isSubmitting}>
              <ImagePlus size={18} /> Añadir imagen
            </button>
            <span>{newComment.length}/500</span>
            <button type="submit" className="comment-submit" disabled={isSubmitting || (!newComment.trim() && !imageSelection)}>
              <Send size={17} /> {isSubmitting ? 'Publicando...' : 'Comentar'}
            </button>
          </div>
          {formError && <p className="comment-error" role="alert">{formError}</p>}
        </div>
      </form>

      <div className="comment-list">
        {comments.map((comment) => <article key={comment.id} className="comment-card">
          <Link className="comment-avatar" to={`/users/${comment.authorId}`} aria-label={`Ver perfil de ${comment.authorUsername}`}>
            {comment.authorAvatarUrl ? <img src={comment.authorAvatarUrl} alt="" /> : comment.authorUsername?.slice(0, 1).toUpperCase() || '?'}
          </Link>
          <div className="comment-body">
            <div className="comment-meta"><Link to={`/users/${comment.authorId}`}>@{comment.authorUsername}</Link>
              <time dateTime={comment.createdAt}>{relativeDate(comment.createdAt)}</time></div>
            {comment.text && <p className="comment-text">{comment.text}</p>}
            {comment.mediaKey && <CommentImage postId={id} comment={comment} />}
            <div className="comment-reactions">
              {EMOJIS.filter((emoji) => (comment.reactions?.[emoji] || 0) > 0).map((emoji) => <button key={emoji}
                type="button" className={`comment-reaction-pill ${comment.myReaction === emoji ? 'selected' : ''}`}
                aria-label={`${emoji}: ${comment.reactions[emoji]} reacciones${comment.myReaction === emoji ? ', tu reacción' : ''}`}
                aria-pressed={comment.myReaction === emoji} disabled={Boolean(reactionBusyId)}
                onClick={() => handleReaction(comment, emoji)}>{emoji} <span>{comment.reactions[emoji]}</span></button>)}
              <button type="button" className="comment-react-trigger" aria-expanded={reactionMenuId === comment.id}
                aria-label={`Reaccionar al comentario de ${comment.authorUsername}`}
                onClick={() => setReactionMenuId(reactionMenuId === comment.id ? '' : comment.id)}>
                <SmilePlus size={17} /> Reaccionar
              </button>
            </div>
            {reactionMenuId === comment.id && <div className="comment-emoji-picker" aria-label="Elige una reacción">
              {EMOJIS.map((emoji) => <button type="button" key={emoji} aria-label={`Reaccionar con ${emoji}`}
                aria-pressed={comment.myReaction === emoji} disabled={Boolean(reactionBusyId)}
                onClick={() => handleReaction(comment, emoji)}>{emoji}</button>)}
            </div>}
            {reactionError?.commentId === comment.id && <p className="comment-error" role="alert">{reactionError.message}</p>}
          </div>
        </article>)}
        {!comments.length && <div className="comment-empty">Aún no hay comentarios. Inicia la conversación.</div>}
      </div>
    </section>
  </main>;
}
