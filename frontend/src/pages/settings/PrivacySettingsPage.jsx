import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { LockKeyhole, Users, MessageCircle, Heart, Camera, Check } from 'lucide-react';
import { usersApi } from '../../lib/api';
import './PrivacySettingsPage.css';

const defaults = { profilePublic: true, avatarFollowersOnly: false, circleFollowersOnly: false,
  followersFollowersOnly: false, bioFollowersOnly: false, messagesFollowersOnly: false, interactionsFollowersOnly: false,
  instagram: '', reddit: '', discord: '' };

const options = [
  ['profilePublic', 'Perfil público', 'Cualquiera puede encontrar y abrir tu perfil.', LockKeyhole, true],
  ['avatarFollowersOnly', 'Foto de perfil', 'Limita tu foto de perfil a tus seguidores.', Camera],
  ['circleFollowersOnly', 'TU CÍRCULO', 'Limita a tus seguidores la lista de cuentas que sigues.', Users],
  ['followersFollowersOnly', 'Seguidores', 'Solo tus seguidores podrán consultar la lista de quienes te siguen.', Users],
  ['bioFollowersOnly', 'Biografía', 'Limita tu biografía a tus seguidores.', Users],
  ['messagesFollowersOnly', 'Mensajes', 'Solo tus seguidores podrán iniciar una conversación contigo.', MessageCircle],
  ['interactionsFollowersOnly', 'Interacciones', 'Solo tus seguidores podrán dar Me gusta o comentar tus publicaciones.', Heart],
];

export default function PrivacySettingsPage() {
  const [settings, setSettings] = useState(defaults);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState('');
  useEffect(() => { usersApi.getSettings().then(data => setSettings({ ...defaults, ...data }))
    .catch(() => setNotice('No se pudieron cargar tus ajustes.'))
    .finally(() => setLoading(false)); }, []);
  const change = (key, value) => setSettings(prev => ({ ...prev, [key]: value }));
  const save = async event => {
    event.preventDefault(); setSaving(true); setNotice('');
    try { const saved = await usersApi.updateSettings(settings); setSettings({ ...defaults, ...saved }); setNotice('Ajustes guardados.'); }
    catch (error) { setNotice(error.message || 'No se pudieron guardar tus ajustes.'); }
    finally { setSaving(false); }
  };
  return <motion.section className="privacy-settings" initial={{ opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0 }}>
    <header className="privacy-settings__hero"><span>CONFIGURACIÓN</span><h1>Ajustes</h1><p>Controla quién puede ver tu perfil y cómo puede interactuar contigo.</p></header>
    {loading ? <p role="status">Cargando ajustes…</p> : <form onSubmit={save}>
      <section className="privacy-settings__card"><h2><LockKeyhole size={19}/> Privacidad</h2>
        {options.map(([key, title, help, Icon, inverse]) => <label className="privacy-setting" key={key}>
          <span className="privacy-setting__icon"><Icon size={19}/></span><span className="privacy-setting__copy"><strong>{title}</strong><small>{help}</small></span>
          <span className="privacy-setting__choice">{inverse ? (settings[key] ? 'Todos' : 'Seguidores') : (settings[key] ? 'Seguidores' : 'Todos')}</span>
          <input type="checkbox" role="switch" checked={Boolean(settings[key])} onChange={e => change(key, e.target.checked)} aria-label={title}/>
        </label>)}
      </section>
      <section className="privacy-settings__card"><h2><Users size={19}/> Redes sociales</h2><p className="privacy-settings__hint">Los perfiles vacíos no aparecerán en tu biografía.</p>
        {['instagram','reddit','discord'].map(network => <label className="privacy-social" key={network}><span>{network[0].toUpperCase()+network.slice(1)}</span><div><b>@</b><input value={settings[network] || ''} maxLength={40} onChange={e => change(network,e.target.value)} placeholder="username" autoComplete="off" /></div></label>)}
      </section>
      <div className="privacy-settings__actions"><span role="status">{notice}</span><button disabled={saving} type="submit">{saving ? 'Guardando…' : <><Check size={17}/> Guardar ajustes</>}</button></div>
    </form>}
  </motion.section>;
}
