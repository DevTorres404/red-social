import { Link } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import ConnectionPager from './ConnectionPager';
import './UserListModal.css';

export default function UserListModal({ isOpen, onClose, title, users, page, total, size, onPageChange, loading, error }) {
  if (!isOpen) return null;

  return (
    <AnimatePresence>
      <div className="modal-backdrop" onClick={onClose}>
        <motion.div
          className="modal-content"
          role="dialog"
          aria-modal="true"
          aria-label={title}
          initial={{ opacity: 0, scale: 0.95, y: 20 }}
          animate={{ opacity: 1, scale: 1, y: 0 }}
          exit={{ opacity: 0, scale: 0.95, y: 20 }}
          onClick={e => e.stopPropagation()} // prevent closing when clicking inside
        >
          <div className="modal-header">
            <h3>{title}</h3>
            <button type="button" className="btn-close" onClick={onClose} aria-label="Cerrar lista">✕</button>
          </div>
          
          <div className="modal-body">
            {loading && <p className="modal-page-status" role="status">Cargando personas...</p>}
            {error && <p className="modal-page-error" role="alert">{error} <button type="button" onClick={() => onPageChange(page)}>Reintentar</button></p>}
            {users.length === 0 ? (
              <p className="modal-empty">No hay usuarios para mostrar.</p>
            ) : (
              <ul className="user-list">
                {users.map(u => (
                  <li key={u.id}>
                    <Link to={`/users/${u.id}`} className="user-list-item" onClick={onClose}>
                      <div className="user-list-avatar">
                        {u.avatarUrl ? <img src={u.avatarUrl} alt={u.username} /> : u.username.charAt(0).toUpperCase()}
                      </div>
                      <span className="user-list-name">@{u.username}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
            <ConnectionPager page={page} total={total} size={size} onPageChange={onPageChange} loading={loading} label={title.toLowerCase()} />
          </div>
        </motion.div>
      </div>
    </AnimatePresence>
  );
}
