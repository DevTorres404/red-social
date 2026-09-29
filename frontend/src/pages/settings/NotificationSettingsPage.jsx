import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { Bell, BellOff, AlertCircle, AlertTriangle, Check } from 'lucide-react';
import { currentSubscription, disablePush, enablePush, pushSupported } from '../../lib/push';
import './NotificationSettingsPage.css';

export default function NotificationSettingsPage() {
  const [enabled, setEnabled] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  useEffect(() => {
    currentSubscription().then((subscription) => setEnabled(Boolean(subscription)))
      .catch(() => setMessage('No se pudo consultar la suscripción del navegador.'));
  }, []);

  const toggle = async () => {
    if (busy || !pushSupported()) return;
    setBusy(true);
    setMessage('');
    try {
      if (enabled) await disablePush();
      else await enablePush();
      setEnabled(!enabled);
    } catch (error) {
      setMessage(error.message);
    } finally {
      setBusy(false);
    }
  };

  const isDenied = 'Notification' in window && Notification.permission === 'denied';

  const features = [
    'Enterate al instante cuando alguien que seguís publica.',
    'Recibí avisos de likes y nuevos seguidores.',
    'No te pierdas los mensajes directos importantes.',
  ];

  const hasAlerts = !pushSupported() || isDenied || message;

  return (
    <motion.div
      className="notif-page"
      initial={{ opacity: 0, y: 18 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -18 }}
      transition={{ duration: 0.28, ease: [0.16, 1, 0.3, 1] }}
    >
      {/* Hero */}
      <header className="notif-hero">
        <span className="notif-hero-eyebrow">
          <span className="notif-hero-dot" /> CONFIGURACIÓN
        </span>
        <h1>Notificaciones <span>Push</span></h1>
        <p>Mantenete al tanto de lo que pasa en Orbit, estés donde estés.</p>
      </header>

      {/* Card */}
      <div className="notif-card">
        {/* Toggle row */}
        <div className="notif-status-row">
          <div className="notif-status-info">
            <div className={`notif-icon-wrap ${enabled ? 'active' : ''}`}>
              {enabled ? <Bell size={22} /> : <BellOff size={22} />}
            </div>
            <div className="notif-status-text">
              <h2>Avisos de escritorio</h2>
              <p>{enabled ? 'Recibiendo notificaciones en este equipo.' : 'Las notificaciones están apagadas.'}</p>
            </div>
          </div>

          <button
            type="button"
            role="switch"
            aria-checked={enabled}
            className={`notif-toggle${enabled ? ' active' : ''}${busy || !pushSupported() ? ' disabled' : ''}`}
            onClick={toggle}
            disabled={busy || !pushSupported()}
            aria-label="Activar notificaciones push"
          >
            <div className="notif-knob" />
          </button>
        </div>

        {/* Features */}
        <div className="notif-features">
          {features.map((text) => (
            <div className="notif-feature" key={text}>
              <span className="notif-feature-icon"><Check size={15} strokeWidth={2.5} /></span>
              <span>{text}</span>
            </div>
          ))}
        </div>

        {/* Alerts */}
        {hasAlerts && (
          <div className="notif-alerts">
            {!pushSupported() && (
              <div className="notif-alert error">
                <AlertCircle size={18} />
                <span>Web Push no está disponible aquí. Asegurate de estar en un contexto seguro (HTTPS o localhost).</span>
              </div>
            )}
            {isDenied && (
              <div className="notif-alert warning">
                <AlertTriangle size={18} />
                <span>El permiso fue bloqueado previamente. Cambialo desde el ícono de candado en la barra de URL de tu navegador.</span>
              </div>
            )}
            {message && (
              <div className="notif-alert error">
                <AlertCircle size={18} />
                <span>{message}</span>
              </div>
            )}
          </div>
        )}
      </div>
    </motion.div>
  );
}
