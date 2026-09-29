import { usePresence } from '../../context/PresenceContext';

/**
 * Online status indicator - a green dot or "offline" text
 * @param {string} userId - The user ID to check
 * @param {Object} options
 *   @param {boolean} options.showText - Show "En línea"/"Fuera de línea" text
 *   @param {boolean} options.showTooltip - Show tooltip with status
 *   @param {string} options.size - 'sm' | 'md' | 'lg'
 *   @param {string} options.className - Additional CSS classes
 */
export default function OnlineIndicator({ userId, showText = false, showTooltip = true, size = 'md', className = '' }) {
  const { isOnline } = usePresence();

  const online = isOnline(userId);

  const sizeClasses = {
    sm: 'online-dot-sm',
    md: 'online-dot-md',
    lg: 'online-dot-lg',
  };

  if (!showText && !showTooltip) {
    return (
      <span
        className={`online-indicator ${sizeClasses[size]} ${online ? 'online' : 'offline'} ${className}`}
        aria-label={online ? 'En línea' : 'Fuera de línea'}
      />
    );
  }

  return (
    <span
      className={`online-indicator-wrapper ${className}`}
      title={showTooltip ? (online ? 'En línea' : 'Fuera de línea') : undefined}
    >
      <span
        className={`online-dot ${sizeClasses[size]} ${online ? 'online' : 'offline'}`}
        aria-hidden="true"
      />
      {showText && (
        <span className="online-text">{online ? 'En línea' : 'Fuera de línea'}</span>
      )}
    </span>
  );
}
