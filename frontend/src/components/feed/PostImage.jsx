import { useEffect, useState } from 'react';
import { postsApi } from '../../lib/api';

export default function PostImage({ postId, authorUsername }) {
  const [url, setUrl] = useState('');
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    postsApi.mediaUrl(postId).then(data => {
      if (active) { setUrl(data.url); setError(''); }
    }).catch(err => {
      if (active) setError(err.message || 'No se pudo cargar la imagen');
    });
    return () => { active = false; };
  }, [postId, attempt]);

  if (error) return <button type="button" onClick={event => {
    event.stopPropagation();
    setError('');
    setAttempt(value => value + 1);
  }}>Reintentar imagen</button>;
  if (!url) return <p role="status">Cargando imagen...</p>;
  return <img src={url} alt={`Imagen publicada por ${authorUsername}`}
    onError={() => setError('La URL de la imagen expiró o no está disponible')}
    style={{ width: '100%', maxHeight: 480, objectFit: 'contain', borderRadius: 12, marginBottom: 16 }} />;
}
