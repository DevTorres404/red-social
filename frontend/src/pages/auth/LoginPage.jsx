import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useAuth } from '../../context/AuthContext';
import AuthPanel from './AuthPanel';
import './Auth.css';

export default function LoginPage() {
  const { login } = useAuth();
  const navigate   = useNavigate();

  const [form,    setForm]    = useState({ identifier: '', password: '' });
  const [error,   setError]   = useState('');
  const [loading, setLoading] = useState(false);

  function handleChange(e) {
    setForm(f => ({ ...f, [e.target.name]: e.target.value }));
    setError('');
  }

  async function handleSubmit(e) {
    e.preventDefault();
    if (!form.identifier.trim() || !form.password) {
      setError('Completa todos los campos para continuar.');
      return;
    }
    setLoading(true);
    try {
      await login({ identifier: form.identifier.trim(), password: form.password });
      navigate('/feed');
    } catch {
      setError('Email, usuario o contraseña incorrectos. Intenta de nuevo.');
    } finally {
      setLoading(false);
    }
  }

  const pageVariants = {
    initial: { opacity: 0, scale: 0.7, x: '-50vw', rotate: -15, y: '5vh' },
    in: { opacity: 1, scale: 1, x: 0, rotate: 0, y: 0 },
    out: { opacity: 0, scale: 0.7, x: '50vw', rotate: 15, y: '-5vh' }
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

          <h1 className="auth-title">¡Bienvenido de vuelta!</h1>
          <p className="auth-subtitle">
            Sintoniza tu órbita. Ingresa para continuar.
          </p>

          <form id="login-form" className="auth-form" onSubmit={handleSubmit} noValidate>

            {/* Email or username */}
            <div className="form-group">
              <label className="form-label" htmlFor="login-identifier">Email o usuario</label>
              <div className="input-wrapper">
                <input
                  id="login-identifier"
                  className="form-input"
                  type="text"
                  name="identifier"
                  placeholder="Tu email o nombre de usuario"
                  value={form.identifier}
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

            {/* Contraseña */}
            <div className="form-group">
              <label className="form-label" htmlFor="login-password">Contraseña</label>
              <div className="input-wrapper">
                <input
                  id="login-password"
                  className="form-input"
                  type="password"
                  name="password"
                  placeholder="••••••••"
                  value={form.password}
                  onChange={handleChange}
                  autoComplete="current-password"
                />
                <span className="input-icon-right" aria-hidden="true">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
                    <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
                  </svg>
                </span>
              </div>
              <div className="form-group__foot">
                <a href="#" className="form-link">¿Olvidaste tu contraseña?</a>
              </div>
            </div>

            {/* Error */}
            {error && (
              <div id="login-error" className="form-error" role="alert">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="12" cy="12" r="10"/>
                  <line x1="12" y1="8" x2="12" y2="12"/>
                  <line x1="12" y1="16" x2="12.01" y2="16"/>
                </svg>
                {error}
              </div>
            )}

            <button id="login-submit" type="submit" className="btn-primary" disabled={loading}>
              {loading ? <span className="btn-spinner" aria-label="Iniciando sesión" /> : 'Iniciar sesión'}
            </button>
          </form>

          <div className="auth-divider"><span>o</span></div>



          <p className="auth-footer">
            ¿No tienes cuenta?{' '}
            <Link id="go-to-register" to="/register">Crea una</Link>
          </p>
        </div>
      </div>

      {/* ── Derecha: panel visual ── */}
      <AuthPanel />
    </motion.div>
  );
}
