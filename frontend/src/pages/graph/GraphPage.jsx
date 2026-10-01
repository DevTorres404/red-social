import { Link } from 'react-router-dom';
import { ArrowRight, Compass, Heart, Map as MapIcon, RefreshCw, Search, Sparkles, TrendingUp, Users } from 'lucide-react';
import { MAP_HEIGHT, MAP_WIDTH } from './networkMap';
import './GraphPage.css';

const tabs = [
  { id: 'discover', label: 'Descubrir', Icon: Compass },
  { id: 'activity', label: 'Actividad', Icon: TrendingUp },
  { id: 'map', label: 'Mapa', Icon: MapIcon },
];

function Avatar({ username, avatarUrl, tone = 0, className = '' }) {
  return <span className={`network-avatar tone-${tone % 4} ${className}`} aria-hidden="true">
    {avatarUrl ? <img src={avatarUrl} alt="" /> : username?.slice(0, 1).toUpperCase() || '?'}
  </span>;
}

function EmptyState({ title, description, action }) {
  return <div className="network-empty">
    <span className="network-empty-icon"><Sparkles size={21} /></span>
    <strong>{title}</strong><p>{description}</p>
    {action && <Link to="/feed" className="network-text-link">Explorar publicaciones <ArrowRight size={15} /></Link>}
  </div>;
}

function PostPreview({ post, index, trending = false }) {
  const date = new Date(post.createdAt);
  const formattedDate = Number.isNaN(date.getTime()) ? ''
    : date.toLocaleDateString('es-EC', { day: 'numeric', month: 'short' });
  return <article className="network-post-card">
    <div className="network-post-top">
      <Link to={`/users/${post.authorId}`} className="network-post-author">
        <Avatar username={post.authorUsername} tone={index} />
        <span><strong>@{post.authorUsername}</strong><small>{formattedDate}</small></span>
      </Link>
      {trending && <span className="network-post-rank">#{index + 1} en tu red</span>}
    </div>
    <p className="network-post-copy">{post.content}</p>
    <div className="network-post-bottom">
      <span className="network-like-count"><Heart size={16} /> {post.likeCount} Me gusta</span>
      <Link to={`/posts/${post.id}`}>Ver publicación <ArrowRight size={15} /></Link>
    </div>
  </article>;
}

import { useGraph } from './useGraph';

export default function GraphPage() {
  const {
    user,
    data,
    following,
    activeTab,
    setActiveTab,
    peoplePage,
    setPeoplePage,
    postOrder,
    setPostOrder,
    otherId,
    setOtherId,
    common,
    commonError,
    error,
    actionError,
    loading,
    refreshing,
    followingId,
    selectedNodeId,
    setSelectedNodeId,
    mapQuery,
    setMapQuery,
    refresh,
    toggleFollow,
    direct,
    second,
    directById,
    graph,
    selectedPerson,
    visibleMapPeople,
    highlightedEdges,
    graphById,
    posts,
    people,
    PAGE_SIZE
  } = useGraph();

  return <section className="network-page">
    <header className="network-hero">
      <div className="network-hero-content">
        <span className="network-eyebrow"><span className="network-eyebrow-dot" /> TU COMUNIDAD</span>
        <h1>Tu red, <span>tus personas.</span></h1>
        <p>Descubre nuevas voces, encuentra conexiones en común y mira lo que comparte tu círculo.</p>
        <div className="network-stats" aria-label="Resumen de tu red">
          <span><strong>{following.length}</strong> Siguiendo</span>
          <span><strong>{people.length - following.length}</strong> Por descubrir</span>
          <span><strong>{data?.networkPosts?.length || 0}</strong> Publicaciones</span>
        </div>
      </div>
      <div className="network-hero-orbits" aria-hidden="true">
        <span className="orbit orbit-one" /><span className="orbit orbit-two" /><span className="orbit orbit-three" />
        <Avatar username={user.username} avatarUrl={user.avatarUrl} className="hero-avatar hero-avatar-self" />
        {following.slice(0, 3).map((person, index) => <Avatar key={person.id} username={person.username}
          avatarUrl={person.avatarUrl} tone={index + 1} className={`hero-avatar hero-avatar-${index + 1}`} />)}
      </div>
    </header>

    <div className="network-toolbar">
      <div className="network-tabs" role="tablist" aria-label="Vistas de tu red">
        {tabs.map(({ id, label, Icon }) => <button key={id} type="button" role="tab"
          aria-selected={activeTab === id} className={activeTab === id ? 'active' : ''}
          onClick={() => setActiveTab(id)}><Icon size={17} /> {label}</button>)}
      </div>
      <button type="button" className="network-refresh" onClick={refresh} disabled={refreshing || loading}
        aria-label="Actualizar red"><RefreshCw size={17} className={refreshing ? 'spinning' : ''} /></button>
    </div>

    {error && <div className="network-alert" role="alert">{error} <button type="button" onClick={refresh}>Reintentar</button></div>}
    {actionError && <p className="network-action-error" role="alert">{actionError}</p>}
    {loading && <div className="network-loading" role="status">Cargando personas y publicaciones de tu red...</div>}

    {data && activeTab === 'discover' && <div className="network-layout">
      <div className="network-main-column">
        <section className="network-section" aria-labelledby="suggestions-title">
          <div className="network-section-heading"><div><span className="network-section-kicker"><Sparkles size={15} /> PARA TI</span>
            <h2 id="suggestions-title">Personas de tu órbita</h2><p>Tu círculo y nuevas personas por descubrir. Puedes dejar de seguir cuando quieras.</p></div></div>
          {people.length ? (() => {
            const totalPages = Math.ceil(people.length / PAGE_SIZE);
            const pagePeople = people.slice(peoplePage * PAGE_SIZE, (peoplePage + 1) * PAGE_SIZE);
            return (
              <>
                <div className="network-people-grid">
                  {pagePeople.map((person, index) => (
                    <article className="network-person-card" key={person.id}>
                      <Avatar username={person.username} avatarUrl={person.avatarUrl} tone={peoplePage * PAGE_SIZE + index} />
                      <Link to={`/users/${person.id}`} className="network-person-name">@{person.username}</Link>
                      <p>{person.alreadyFollowing ? 'Ya forma parte de tu círculo' : person.mutualCount ? `${person.mutualCount} conexión${person.mutualCount === 1 ? '' : 'es'} en común` : 'Una nueva voz para tu órbita'}</p>
                      <small>{person.alreadyFollowing ? 'Puedes visitar su perfil o dejar de seguir.' : person.viaUsernames?.length ? `A través de ${person.viaUsernames.map((name) => `@${name}`).join(', ')}` : 'Descubre su perfil y sus publicaciones.'}</small>
                      <button type="button" className={`network-follow ${person.alreadyFollowing ? 'network-following' : ''}`} onClick={() => toggleFollow(person)} disabled={Boolean(followingId)}>
                        {followingId === person.id ? 'Actualizando...' : person.alreadyFollowing ? 'Dejar de seguir' : 'Seguir'}
                      </button>
                    </article>
                  ))}
                </div>
                {totalPages > 1 && (
                  <div className="network-pagination" role="navigation" aria-label="Paginación de personas">
                    <button type="button" className="network-page-btn" onClick={() => setPeoplePage((p) => Math.max(0, p - 1))} disabled={peoplePage === 0} aria-label="Página anterior">
                      ← Anterior
                    </button>
                    <span className="network-page-info">{peoplePage + 1} / {totalPages}</span>
                    <button type="button" className="network-page-btn" onClick={() => setPeoplePage((p) => Math.min(totalPages - 1, p + 1))} disabled={peoplePage === totalPages - 1} aria-label="Página siguiente">
                      Siguiente →
                    </button>
                  </div>
                )}
              </>
            );
          })() : <EmptyState
            title={following.length ? 'No hay nuevas sugerencias' : 'Tu círculo está empezando'}
            description={following.length ? 'Ya sigues a las personas cercanas a tu círculo. Vuelve pronto para descubrir más.' : 'Sigue a personas para descubrir a quiénes conocen.'}
            action
          />}
        </section>

        <section className="network-section" aria-labelledby="nearby-title">
          <div className="network-section-heading"><div><span className="network-section-kicker"><Users size={15} /> TU ENTORNO</span>
            <h2 id="nearby-title">Cerca de tu órbita</h2><p>Personas conectadas contigo en uno o dos pasos.</p></div></div>
          {data.reachable.length ? <div className="network-nearby-list">
            {data.reachable.map((person, index) => <Link key={person.id} to={`/users/${person.id}`} className="network-nearby-person">
              <Avatar username={person.username} tone={index + 1} />
              <span className="network-nearby-copy"><strong>@{person.username}</strong>
                <small>{person.distance === 1 ? 'Sigues a esta persona' : `Conectado a través de @${directById.get(person.viaId) || 'tu red'}`}</small></span>
              <span className="network-step">{person.distance === 1 ? 'Tu círculo' : '2 pasos'}</span>
              <ArrowRight size={17} className="network-nearby-arrow" />
            </Link>)}
          </div> : <EmptyState title="Aún no hay conexiones" description="Cuando sigas a alguien, verás aquí cómo crece tu red." action />}
        </section>
      </div>

      <aside className="network-side-column" aria-label="Resumen de conexiones">
        <section className="network-side-card network-circle-card">
          <span className="network-section-kicker"><Users size={15} /> TU CÍRCULO</span><h2>Gente que sigues</h2>
          {following.length ? <div className="network-following-list">
            {following.slice(0, 8).map((person, index) => <Link key={person.id} to={`/users/${person.id}`}>
              <Avatar username={person.username} avatarUrl={person.avatarUrl} tone={index} />
              <span>@{person.username}</span><ArrowRight size={15} />
            </Link>)}
            {following.length > 8 && <p className="network-more">Y {following.length - 8} personas más en tu círculo.</p>}
          </div> : <p className="network-side-empty">Todavía no sigues a nadie.</p>}
          <button className="network-map-link" type="button" onClick={() => setActiveTab('map')}>Ver mapa de conexiones <ArrowRight size={16} /></button>
        </section>

        <section className="network-side-card">
          <span className="network-section-kicker"><Sparkles size={15} /> EN COMÚN</span><h2>Compartimos conexiones</h2>
          <p>Elige alguien de tu círculo para descubrir a quiénes siguen ambos.</p>
          <label htmlFor="compare-user" className="sr-only">Comparar conexiones con</label>
          <select id="compare-user" value={otherId} onChange={(event) => { setOtherId(event.target.value); setCommon([]); setCommonError(''); }}>
            <option value="">Selecciona una persona</option>
            {following.map((person) => <option key={person.id} value={person.id}>@{person.username}</option>)}
          </select>
          {commonError && <p role="alert" className="network-action-error">{commonError}</p>}
          {otherId && !commonError && (common.length ? <div className="network-common-list">
            {common.map((person, index) => <Link to={`/users/${person.id}`} key={person.id}>
              <Avatar username={person.username} tone={index + 2} /><span>@{person.username}</span>
            </Link>)}
          </div> : <p className="network-side-empty">No siguen a las mismas personas por ahora.</p>)}
        </section>
      </aside>
    </div>}

    {data && activeTab === 'activity' && <section className="network-activity" aria-labelledby="activity-title">
      <div className="network-section-heading"><div><span className="network-section-kicker"><TrendingUp size={15} /> ACTIVIDAD</span>
        <h2 id="activity-title">Lo que pasa en tu círculo</h2><p>Publicaciones reales de las personas que sigues.</p></div></div>
      <div className="network-post-filters" aria-label="Orden de publicaciones">
        <button type="button" className={postOrder === 'latest' ? 'active' : ''} onClick={() => setPostOrder('latest')}>Más recientes</button>
        <button type="button" className={postOrder === 'popular' ? 'active' : ''} onClick={() => setPostOrder('popular')}>Más destacadas</button>
      </div>
      {posts.length ? <div className="network-post-list">{posts.map((post, index) => <PostPreview key={post.id} post={post} index={index} trending={postOrder === 'popular'} />)}</div>
        : <EmptyState
          title="Todavía no hay publicaciones"
          description={following.length ? 'Las personas que sigues aún no han publicado. Vuelve pronto para ver sus novedades.' : 'Sigue a personas y vuelve para ver sus novedades.'}
          action
        />}
    </section>}

    {data && activeTab === 'map' && <section className="network-map-section" aria-labelledby="map-title">
      <div className="network-section-heading"><div><span className="network-section-kicker"><MapIcon size={15} /> MAPA SOCIAL</span>
        <h2 id="map-title">Así se conecta tu órbita</h2>
        <p>Explora a quién sigues y descubre las personas a las que puedes llegar a través de tu círculo. Las flechas muestran la dirección del seguimiento.</p></div>
        <span className="network-map-total">{direct.length + second.length} personas en tu red</span>
      </div>
      <div className="network-map-key"><span><i className="key-self" /> Tú</span><span><i className="key-direct" /> Sigues</span><span><i className="key-second" /> A dos pasos</span><small>Selecciona un punto para ver la conexión</small></div>
      <div className="network-map-shell">
        <div className="network-map-canvas" role="region" aria-label={`Gráfica de ${graph.nodes.length} personas y ${graph.edges.length} relaciones`} tabIndex="0">
          <svg viewBox={`0 0 ${MAP_WIDTH} ${MAP_HEIGHT}`} aria-label="Mapa interactivo de tu red">
            <defs>
              <marker id="follow-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0 0 L8 4 L0 8 Z" fill="#8e85b9" /></marker>
              <marker id="follow-arrow-active" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0 0 L8 4 L0 8 Z" fill="#d8b5ff" /></marker>
            </defs>
            <circle cx="450" cy="340" r="176" className="map-ring" /><circle cx="450" cy="340" r="284" className="map-ring map-ring-outer" />
            <circle cx="450" cy="340" r="74" className="map-core-glow" />
            {graph.edges.map((edge) => {
              const from = graphById.get(edge.from);
              const to = graphById.get(edge.to);
              const dx = to.x - from.x; const dy = to.y - from.y; const length = Math.hypot(dx, dy) || 1;
              const active = highlightedEdges.has(`${edge.from}-${edge.to}`);
              return <line key={`${edge.from}-${edge.to}`} className={`map-edge ${active ? 'map-edge-active' : ''}`}
                x1={from.x + dx / length * 31} y1={from.y + dy / length * 31}
                x2={to.x - dx / length * 36} y2={to.y - dy / length * 36}
                markerEnd={`url(#follow-arrow${active ? '-active' : ''})`} />;
            })}
            {graph.nodes.map((node) => <g key={node.id}
              className={`map-node map-node-${node.distance} ${selectedPerson.id === node.id ? 'map-node-selected' : ''}`}
              role="button" tabIndex="0" aria-pressed={selectedPerson.id === node.id}
              aria-label={`@${node.username}, ${node.distance === 0 ? 'tú' : node.distance === 1 ? 'persona que sigues' : 'a dos pasos'}`}
              onClick={() => setSelectedNodeId(node.id)}
              onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); setSelectedNodeId(node.id); } }}>
              <title>@{node.username}</title>
              <circle className="map-node-halo" cx={node.x} cy={node.y} r="34" />
              <circle className="map-node-disc" cx={node.x} cy={node.y} r="26" />
              <text className="map-initial" x={node.x} y={node.y + 6} textAnchor="middle">{node.username.slice(0, 1).toUpperCase()}</text>
              <text className="map-name" x={node.x} y={node.y + 50} textAnchor="middle">@{node.username.length > 13 ? `${node.username.slice(0, 12)}…` : node.username}</text>
            </g>)}
          </svg>
          {!data.reachable.length && <div className="network-map-empty">Tu órbita empieza aquí. Sigue a alguien para verla crecer.</div>}
          <span className="network-map-pan-hint">Desliza para explorar el mapa</span>
        </div>
        <aside className="network-map-explorer" aria-label="Explorador del mapa">
          <div className="network-map-selected" aria-live="polite">
            <span className="network-section-kicker"><Sparkles size={15} /> CONEXIÓN SELECCIONADA</span>
            <Avatar username={selectedPerson.username} avatarUrl={selectedPerson.avatarUrl} className="network-map-selected-avatar" />
            <strong>@{selectedPerson.username}</strong>
            <p>{selectedPerson.id === user.id ? 'Este es el centro de tu red.' : selectedPerson.distance === 1
              ? 'Sigues a esta persona directamente.'
              : `Llegas a esta persona a través de @${directById.get(selectedPerson.viaId) || 'tu círculo'}.`}</p>
            {selectedPerson.id !== user.id && <Link to={`/users/${selectedPerson.id}`}>Visitar perfil <ArrowRight size={16} /></Link>}
          </div>
          <div className="network-map-directory">
            <div className="network-map-directory-heading"><strong>Personas en tu red</strong><span>{data.reachable.length}</span></div>
            <label className="network-map-search"><Search size={17} /><span className="sr-only">Buscar persona en el mapa</span>
              <input type="search" value={mapQuery} onChange={(event) => setMapQuery(event.target.value)} placeholder="Buscar por usuario" /></label>
            <div className="network-map-person-list">
              {visibleMapPeople.map((person) => <button type="button" key={person.id}
                className={selectedPerson.id === person.id ? 'active' : ''}
                onClick={() => setSelectedNodeId(person.id)}>
                <Avatar username={person.username} tone={person.distance} />
                <span><strong>@{person.username}</strong><small>{person.distance === 1 ? 'Sigues' : `Vía @${directById.get(person.viaId) || 'tu círculo'}`}</small></span>
                <ArrowRight size={16} />
              </button>)}
              {!visibleMapPeople.length && <p className="network-map-list-empty">{mapQuery ? 'No encontramos personas con ese usuario.' : 'Todavía no hay personas en tu red.'}</p>}
            </div>
          </div>
        </aside>
      </div>
      <div className="network-map-footer"><span>{direct.length} en tu círculo</span><span>{second.length} a dos pasos</span>
        {graph.hiddenCount > 0 && <span>El mapa muestra una selección clara; busca cualquiera de las {graph.hiddenCount} personas restantes a la derecha.</span>}</div>
    </section>}
  </section>;
}
