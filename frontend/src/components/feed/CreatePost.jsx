import { useEffect, useRef, useState } from 'react';
import { motion } from 'framer-motion';
import { Send, Image as ImageIcon, X } from 'lucide-react';
import { postsApi } from '../../lib/api';
import { useAuth } from '../../context/AuthContext';
import { Link } from 'react-router-dom';
import './CreatePost.css';

export default function CreatePost({ onPostCreated }) {
  const { user } = useAuth();
  const [content, setContent] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [file, setFile] = useState(null);
  const [preview, setPreview] = useState('');
  const [progress, setProgress] = useState(0);
  const fileInput = useRef(null);
  const previewRef = useRef('');

  useEffect(() => {
    return () => { if (previewRef.current) URL.revokeObjectURL(previewRef.current); };
  }, []);

  const chooseFile = (selected) => {
    if (selected && selected.size > 6 * 1024 * 1024) {
      setError('La imagen debe pesar como máximo 6 MB.');
      if (fileInput.current) fileInput.current.value = '';
      return;
    }
    if (previewRef.current) URL.revokeObjectURL(previewRef.current);
    previewRef.current = selected ? URL.createObjectURL(selected) : '';
    setPreview(previewRef.current);
    setFile(selected);
    if (!selected && fileInput.current) fileInput.current.value = '';
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!content.trim()) return;

    setIsSubmitting(true);
    setError('');

    try {
      const newPost = file
        ? await postsApi.createWithImage(content.trim(), file, setProgress)
        : await postsApi.create({ content: content.trim() });
      
      setContent('');
      chooseFile(null);
      setProgress(0);
      if (onPostCreated) {
        onPostCreated(newPost);
      }
    } catch (err) {
      setError(err.message || 'Error al publicar');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <motion.div
      className="create-post-card"
      initial={{ opacity: 0, y: -20 }}
      animate={{ opacity: 1, y: 0 }}
    >
      <form onSubmit={handleSubmit}>
        <div className="create-post-header">
          <Link
            to={`/users/${user?.id}`}
            className="create-post-avatar"
            aria-label="Ir a mi perfil"
          >
            {user?.avatarUrl ? (
              <img src={user.avatarUrl} alt={user.username} />
            ) : (
              <span>{user?.username?.charAt(0).toUpperCase()}</span>
            )}
          </Link>
          
          <div className="create-post-body">
            <textarea
              placeholder="¿Qué está pasando en tu órbita?"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              rows={3}
              disabled={isSubmitting}
            />
            
            {preview && (
              <div className="create-post-preview">
                <img src={preview} alt="Vista previa de la imagen seleccionada" />
                <button
                  type="button"
                  className="create-post-preview-close"
                  onClick={() => chooseFile(null)}
                  aria-label="Quitar imagen"
                >
                  <X size={16} />
                </button>
              </div>
            )}
            
            {file && !preview && (
              <div className="create-post-file-info">
                <span>{file.name}</span>
                <button type="button" onClick={() => chooseFile(null)}>Quitar</button>
              </div>
            )}
            
            {isSubmitting && file && (
              <progress value={progress} max="100" aria-label="Progreso de subida" className="create-post-progress" />
            )}
            
            {error && (
              <div className="create-post-error" role="alert">{error}</div>
            )}
            
            <div className="create-post-footer">
              <div className="create-post-actions">
                <button
                  type="button"
                  className="create-post-btn create-post-btn-image"
                  onClick={() => fileInput.current?.click()}
                  disabled={isSubmitting}
                  aria-label="Añadir imagen"
                >
                  <ImageIcon size={20} />
                </button>
                <input
                  ref={fileInput}
                  type="file"
                  accept="image/png, image/jpeg"
                  hidden
                  onChange={event => {
                    const selected = event.target.files?.[0];
                    chooseFile(selected || null);
                    setError('');
                  }}
                />
              </div>
              
              <button
                type="submit"
                disabled={!content.trim() || isSubmitting}
                className={`create-post-btn create-post-btn-submit ${!content.trim() ? 'disabled' : ''}`}
              >
                {isSubmitting ? 'Publicando...' : 'Publicar'}
                <Send size={16} />
              </button>
            </div>
          </div>
        </div>
      </form>
    </motion.div>
  );
}
