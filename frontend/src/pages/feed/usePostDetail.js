import { useState, useEffect, useCallback, useRef } from 'react';
import { postsApi } from '../../lib/api';
import { useAuth } from '../../context/AuthContext';
import { useFeedEvents } from '../../context/FeedEventsContext';

const MAX_IMAGE_BYTES = 6 * 1024 * 1024;

export function usePostDetail(id) {
  const { user } = useAuth();
  const { subscribe } = useFeedEvents();
  const fileInputRef = useRef(null);
  const commentsRef = useRef([]);

  const [post, setPost] = useState(null);
  const [comments, setComments] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [newComment, setNewComment] = useState('');
  const [imageSelection, setImageSelection] = useState(null);
  const [formError, setFormError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [reactionMenuId, setReactionMenuId] = useState('');
  const [reactionBusyId, setReactionBusyId] = useState('');
  const [reactionError, setReactionError] = useState(null);

  useEffect(() => { commentsRef.current = comments; }, [comments]);
  useEffect(() => () => { if (imageSelection?.preview) URL.revokeObjectURL(imageSelection.preview); }, [imageSelection]);

  const fetchData = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [postData, commentsData] = await Promise.all([postsApi.getById(id), postsApi.getComments(id)]);
      setPost(postData);
      setComments(commentsData || []);
    } catch (err) {
      setError(err.message || 'No se pudo cargar la publicación.');
    } finally {
      setLoading(false);
    }
  }, [id]);

  // eslint-disable-next-line react/set-state-in-effect
  useEffect(() => { fetchData(); }, [fetchData]);

  useEffect(() => {
    const unsubscribe = subscribe((event) => {
      if (!event || event.postId !== id) return;
      if (event.type === 'comment-created' && event.comment) {
        setComments((previous) => previous.some((comment) => comment.id === event.comment.id)
          ? previous : [...previous, event.comment]);
        setPost((previous) => previous ? { ...previous, commentCount: event.commentCount } : previous);
      } else if (event.type === 'comment-reaction-changed') {
        setComments((previous) => previous.map((comment) => comment.id === event.commentId
          ? { ...comment, reactions: event.reactions,
            myReaction: event.actorId === user?.id ? event.emoji : comment.myReaction }
          : comment));
      } else if (event.type === 'like-changed') {
        setPost((previous) => previous ? {
          ...previous, likeCount: event.likeCount,
          likedByCurrentUser: event.actorId === user?.id ? event.liked : previous.likedByCurrentUser,
        } : previous);
      }
    });
    return unsubscribe;
  }, [subscribe, id, user?.id]);

  const selectImage = (event) => {
    const file = event.target.files?.[0];
    if (!file) return;
    if (!['image/png', 'image/jpeg'].includes(file.type) || !/\.(png|jpe?g)$/i.test(file.name)) {
      setFormError('Elige una imagen PNG o JPEG válida.');
      event.target.value = '';
      return;
    }
    if (!file.size || file.size > MAX_IMAGE_BYTES) {
      setFormError('La imagen debe pesar como máximo 6 MB.');
      event.target.value = '';
      return;
    }
    setImageSelection({ file, preview: URL.createObjectURL(file) });
    setFormError('');
  };

  const removeImage = () => {
    setImageSelection(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleCommentSubmit = async (event) => {
    event.preventDefault();
    const text = newComment.trim();
    if ((!text && !imageSelection) || isSubmitting) return;
    setIsSubmitting(true);
    setFormError('');
    try {
      const added = imageSelection
        ? await postsApi.addCommentWithImage(id, text, imageSelection.file)
        : await postsApi.addComment(id, text);
      const echoed = commentsRef.current.some((comment) => comment.id === added.id);
      setComments((previous) => previous.some((comment) => comment.id === added.id) ? previous : [...previous, added]);
      setPost((previous) => previous ? {
        ...previous, commentCount: echoed ? previous.commentCount : (previous.commentCount || 0) + 1,
      } : previous);
      setNewComment('');
      removeImage();
    } catch (err) {
      setFormError(err.message || 'No se pudo publicar el comentario.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleReaction = async (comment, emoji) => {
    if (reactionBusyId) return;
    setReactionBusyId(comment.id);
    setReactionError(null);
    try {
      const updated = comment.myReaction === emoji
        ? await postsApi.removeCommentReaction(id, comment.id)
        : await postsApi.reactToComment(id, comment.id, emoji);
      setComments((previous) => previous.map((item) => item.id === comment.id ? updated : item));
      setReactionMenuId('');
    } catch (err) {
      setReactionError({ commentId: comment.id, message: err.message || 'No se pudo guardar tu reacción.' });
    } finally {
      setReactionBusyId('');
    }
  };

  return {
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
  };
}
