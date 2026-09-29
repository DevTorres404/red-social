import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useAuth } from '../../context/AuthContext';
import AuthPanel from './AuthPanel';
import './Auth.css';

export default function RegisterPage() {
  const { register } = useAuth();
  const navigate      = useNavigate();

  const [form, setForm] = useState({
    username: '', email: '', password: '', confirm: '',
  });
  const [error,   setError]   = useState('');
  const [loading, setLoading] = useState(false);

  function handleChange(e) {
    setForm(f => ({ ...f, [e.target.name]: e.target.value }));
    setError('');
  }

  function validate() {
    if (!form.username.trim()) return 'El nombre de usuario es obligatorio.';
    if (form.username.length < 3) return 'El usuario debe tener al menos 3 caracteres.';
    if (!form.email.includes('@')) return 'Ingresa una dirección de email válida.';
    if (form.password.length < 8) return 'La contraseña debe tener al menos 8 caracteres.';
    if (form.password !== form.confirm) return 'Las contraseñas no coinciden.';
    return null;
  }

  async function handleSubmit(e) {
    e.preventDefault();
    const err = validate();
    if (err) { setError(err); return; }
    setLoading(true);
    try {
      await register({ username: form.username, email: form.email, password: form.password });
      navigate('/feed');
    } catch {
      setError('Error al registrarse. Intenta de nuevo.');
    } finally {
      setLoading(false);
    }
  }

  function strength(pwd) {
    if (!pwd) return 0;
    let s = 0;
    if (pwd.length >= 8) s++;
    if (/[A-Z]/.test(pwd)) s++;
    if (/[0-9]/.test(pwd)) s++;
    if (/[^A-Za-z0-9]/.test(pwd)) s++;
    return s;
  }
  const s = strength(form.password);
  const strengthLabel = ['', 'Débil', 'Regular', 'Buena', 'Fuerte'];
  const strengthClass = ['', 'weak', 'fair', 'good', 'strong'];

  const pageVariants = {
    initial: { opacity: 0, scale: 0.7, x: '50vw', rotate: 15, y: '5vh' },
    in: { opacity: 1, scale: 1, x: 0, rotate: 0, y: 0 },
    out: { opacity: 0, scale: 0.7, x: '-50vw', rotate: -15, y: '-5vh' }
  };

  const pageTransition = {
    type: "spring",
    stiffness: 100,
    damping: 22,
    mass: 1.2
  };

  return (
    <motion.div 
      className="auth-layout"
      initial="initial"
      animate="in"
      exit="out"
      variants={pageVariants}
      transition={pageTransition}
    >
      {/* ── Izquierda: formulario ── */}
      <div className="auth-form-panel">
        <div className="auth-form-panel__inner">
          <div className="auth-mobile-brand" aria-hidden="true">
            <img src="/logo.png" alt="" />
          </div>

          <span className="auth-card-kicker" aria-hidden="true">EMPIEZA TU ÓRBITA</span>
          <h1 className="auth-title">Crea tu cuenta</h1>
          <p className="auth-subtitle">Expande tu universo. Crea tu cuenta y empieza a orbitar.</p>

          <form id="register-form" className="auth-form" onSubmit={handleSubmit} noValidate>
            <div className="form-group">
              <div className="input-wrapper">
                <input
                  id="reg-username"
                  className="form-input"
                  type="text"
                  name="username"
                  placeholder="Tu nombre de usuario"
                  value={form.username}
                  onChange={handleChange}
                  autoComplete="username"
                  autoFocus
                />
                <span className="input-icon-right" aria-hidden="true">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>
                    <circle cx="12" cy="7" r="4"/>
                  </svg>
                </span>
              </div>
            </div>

            <div className="form-group">
              <div className="input-wrapper">
                <input
                  id="reg-email"
                  className="form-input"
                  type="email"
                  name="email"
                  placeholder="tu@email.com"
                  value={form.email}
                  onChange={handleChange}
                  autoComplete="email"
                />
                <span className="input-icon-right" aria-hidden="true">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z"/>
                    <polyline points="22,6 12,13 2,6"/>
                  </svg>
                </span>
              </div>
            </div>

            <div className="form-group">
              <div className="input-wrapper">
                <input
                  id="reg-password"
                  className="form-input"
                  type="password"
                  name="password"
                  placeholder="Mín. 8 caracteres"
                  value={form.password}
                  onChange={handleChange}
                  autoComplete="new-password"
                />
                <span className="input-icon-right" aria-hidden="true">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
                    <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
                  </svg>
                </span>
              </div>
              {form.password && (
                <div className="strength-bar" aria-label={`Password strength: ${strengthLabel[s]}`}>
                  {[1,2,3,4].map(i => (
                    <span key={i} className={`strength-bar__segment ${i <= s ? strengthClass[s] : ''}`} />
                  ))}
                  <span className={`strength-bar__label strength-bar__label--${strengthClass[s]}`}>
                    {strengthLabel[s]}
                  </span>
                </div>
              )}
            </div>

            <div className="form-group">
              <div className="input-wrapper">
                <input
                  id="reg-confirm"
                  className={`form-input ${form.confirm && form.confirm !== form.password ? 'form-input--error' : ''}`}
                  type="password"
                  name="confirm"
                  placeholder="Repite tu contraseña"
                  value={form.confirm}
                  onChange={handleChange}
                  autoComplete="new-password"
                />
                <span className="input-icon-right" aria-hidden="true">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <polyline points="20 6 9 17 4 12"/>
                  </svg>
                </span>
              </div>
            </div>

            {error && (
              <div id="register-error" className="form-error" role="alert">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="12" cy="12" r="10"/>
                  <line x1="12" y1="8" x2="12" y2="12"/>
                  <line x1="12" y1="16" x2="12.01" y2="16"/>
                </svg>
                {error}
              </div>
            )}

            <button
              id="register-submit"
              type="submit"
              className="btn-primary"
              disabled={loading}
            >
              {loading ? <span className="btn-spinner" aria-label="Registrando" /> : 'Crear cuenta'}
            </button>
          </form>

          <div className="auth-divider"><span>o</span></div>

          <p className="auth-footer">
            ¿Ya tienes cuenta?{' '}
            <Link id="go-to-login" to="/login">Inicia sesión</Link>
          </p>
        </div>
      </div>

      {/* ── Right: Visual panel ── */}
      <AuthPanel />
    </motion.div>
  );
}
