export default function AuthPanel() {
  return (
    <aside className="auth-visual-panel" aria-label="Universo Orbit">
      <div className="avp-stars" aria-hidden="true" />
      <div className="avp-orbit avp-orbit--outer" aria-hidden="true" />
      <div className="avp-orbit avp-orbit--inner" aria-hidden="true" />
      <div className="avp-content">
        <span className="avp-eyebrow">UN UNIVERSO DE CONEXIONES</span>
        <img className="avp-logo" src="/logo.png" alt="Orbit" />
        <h2 className="avp-title">Encuentra tu lugar en la órbita.</h2>
        <p className="avp-tagline">Personas, ideas y conversaciones que se acercan a ti.</p>
        <span className="avp-signature">DESCUBRE <span>✦</span> COMPARTE <span>✦</span> CONECTA</span>
      </div>
    </aside>
  );
}
