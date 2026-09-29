import { useState } from 'react';
import { usersApi } from '../../lib/api';
import './FollowButton.css';

export default function FollowButton({ userId, initialIsFollowing, onToggle }) {
  const [isFollowing, setIsFollowing] = useState(initialIsFollowing);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleToggle = async () => {
    if (loading) return;
    
    const previousState = isFollowing;
    setError('');
    setLoading(true);

    try {
      if (previousState) {
        await usersApi.unfollow(userId);
      } else {
        await usersApi.follow(userId);
      }
      setIsFollowing(!previousState);
      onToggle?.(!previousState);
    } catch (err) {
      setError(err.message || 'No se pudo actualizar el seguimiento');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <button
        type="button"
        className={`btn-follow ${isFollowing ? 'btn-following' : ''} ${loading ? 'loading' : ''}`}
        onClick={handleToggle}
        disabled={loading}
        aria-pressed={isFollowing}
      >
        {loading ? 'Actualizando...' : isFollowing ? 'Dejar de seguir' : 'Seguir'}
      </button>
      {error && <p role="alert">{error}</p>}
    </div>
  );
}
