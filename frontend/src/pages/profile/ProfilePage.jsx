import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { usersApi } from '../../lib/api';
import FollowButton from '../../components/shared/FollowButton';
import UserListModal from '../../components/shared/UserListModal';
import ConnectionPager from '../../components/shared/ConnectionPager';
import PostCard from '../../components/feed/PostCard';
import OnlineIndicator from '../../components/presence/OnlineIndicator';
import { Sparkles, Users, ArrowRight, MessageSquare, RefreshCw, Camera, Pencil } from 'lucide-react';
import './ProfilePage.css';

const toneBackgrounds = ['#e6e0ff', '#f7def4', '#dde8ff', '#e7dcfa'];
const toneColors = ['#5a45ac', '#9d4e9a', '#4a64ae', '#7854a9'];
const MAX_AVATAR_BYTES = 6 * 1024 * 1024;
const EMPTY_CONNECTIONS = { users: [], total: 0, page: 0, size: 4 };

function Avatar({ username, avatarUrl, tone = 0, className = '', size = 'md' }) {
  const bg = toneBackgrounds[tone % 4];
  const color = toneColors[tone % 4];
  const sizeMap = { sm: 39, md: 44, lg: 70, xl: 90 };
  const fontSizeMap = { sm: 15, md: 17, lg: 24, xl: 30 };
  const s = sizeMap[size];
  const fs = fontSizeMap[size];
  const style = { width: s, height: s, fontSize: fs, background: bg, color };
  return (
    <span className={`network-avatar ${className}`} style={style} aria-hidden="true">
      {avatarUrl ? <img src={avatarUrl} alt="" /> : username?.slice(0, 1).toUpperCase() || '?'}
    </span>
  );
}

function EmptyState({ title, description, action }) {
  return (
    <div className="network-empty">
      <span className="network-empty-icon"><Sparkles size={21} /></span>
      <strong>{title}</strong>
      <p>{description}</p>
      {action && <Link to="/feed" className="network-text-link">Crear publicación <ArrowRight size={15} /></Link>}
    </div>
  );
}

export default function ProfilePage() {
  const { id } = useParams();
  const { user: currentUser, updateUser } = useAuth();

  const [profile, setProfile] = useState(null);
  const [followers, setFollowers] = useState(EMPTY_CONNECTIONS);
  const [following, setFollowing] = useState(EMPTY_CONNECTIONS);
  const [connectionsLoading, setConnectionsLoading] = useState({ followers: false, following: false });
  const [connectionsError, setConnectionsError] = useState({ followers: '', following: '' });
  const [isFollowingProfile, setIsFollowingProfile] = useState(false);
  const [posts, setPosts] = useState([]);
  const [editing, setEditing] = useState(false);
  const [bioDraft, setBioDraft] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [_saving, _setSaving] = useState(false);
  const [_saveError, _setSaveError] = useState('');
  const [avatarError, setAvatarError] = useState('');
  const [avatarSuccess, setAvatarSuccess] = useState('');
  const [uploadingAvatar, setUploadingAvatar] = useState(false);
  const avatarInputRef = useRef(null);
  const viewedProfileId = useRef(id);

  useEffect(() => { viewedProfileId.current = id; }, [id]);

  // Modals state
  const [modalConfig, setModalConfig] = useState({ isOpen: false, title: '', type: 'followers' });

  useEffect(() => {
    let active = true;
    const fetchProfile = async () => {
      setLoading(true);
      setError(null);
      try {
        const [profileData, followersData, followingData, postData, followStatus] = await Promise.all([
          usersApi.getProfile(id),
          usersApi.getFollowersPage(id),
          usersApi.getFollowingPage(id),
          usersApi.getPosts(id),
          currentUser?.id && currentUser.id !== id ? usersApi.followStatus(id) : Promise.resolve({ following: false }),
        ]);
        if (!active) return;
        setProfile(profileData);
        setFollowers(followersData);
        setFollowing(followingData);
        setConnectionsError({ followers: '', following: '' });
        setIsFollowingProfile(Boolean(followStatus.following));
        setPosts(postData);
        setBioDraft(profileData.bio || '');
        setEditing(false);
      } catch (err) {
        if (!active) return;
        setError('No se pudo cargar el perfil');
        console.error(err);
      } finally {
        if (active) setLoading(false);
      }
    };
    fetchProfile();
    return () => { active = false; };
  }, [id, currentUser?.id]);

  const selectAvatar = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    
    setAvatarSuccess('');
    setAvatarError('');
    
    const extension = file.name.toLowerCase();
    const validType = (file.type === 'image/png' && extension.endsWith('.png'))
      || (file.type === 'image/jpeg' && (extension.endsWith('.jpg') || extension.endsWith('.jpeg')));
      
    if (!validType || file.size === 0 || file.size > MAX_AVATAR_BYTES) {
      setAvatarError('Elige una imagen PNG o JPG de hasta 6 MB.');
      return;
    }
    
    setUploadingAvatar(true);
    try {
      const updated = await usersApi.uploadAvatar(id, file);
      setProfile(updated);
      updateUser(updated);
      setAvatarSuccess('Tu foto de perfil se actualizó.');
      
      // Clear success message after 3 seconds
      setTimeout(() => setAvatarSuccess(''), 3000);
    } catch (err) {
      setAvatarError(err.message || 'No se pudo subir la foto. Inténtalo de nuevo.');
    } finally {
      setUploadingAvatar(false);
    }
  };

  if (loading) return <div className="profile-loading" role="status">Cargando perfil...</div>;
  if (error) return <div className="profile-error">{error}</div>;
  if (!profile) return <div className="profile-error">Usuario no encontrado</div>;

  const isMe = currentUser?.id === id;
  const changeConnectionsPage = async (type, page) => {
    const fetchPage = type === 'followers' ? usersApi.getFollowersPage : usersApi.getFollowingPage;
    setConnectionsLoading(prev => ({ ...prev, [type]: true }));
    setConnectionsError(prev => ({ ...prev, [type]: '' }));
    try {
      const result = await fetchPage(id, page);
      if (viewedProfileId.current !== id) return;
      if (type === 'followers') setFollowers(result);
      else setFollowing(result);
    } catch (err) {
      if (viewedProfileId.current !== id) return;
      setConnectionsError(prev => ({ ...prev, [type]: err.message || 'No se pudo cargar esta página.' }));
    } finally {
      if (viewedProfileId.current === id) setConnectionsLoading(prev => ({ ...prev, [type]: false }));
    }
  };
  const handleToggleFollow = (newFollowingState) => {
    setIsFollowingProfile(newFollowingState);
    setFollowers(prev => ({ ...prev, total: Math.max(0, prev.total + (newFollowingState ? 1 : -1)) }));
    changeConnectionsPage('followers', 0);
  };

  const saveProfile = async (event) => {
    event.preventDefault();
    _setSaving(true);
    _setSaveError('');
    try {
      const updated = await usersApi.updateProfile(id, { bio: bioDraft.trim() });
      setProfile(updated);
      updateUser(updated);
      setEditing(false);
    } catch (err) {
      _setSaveError(err.message || 'No se pudo guardar el perfil');
    } finally {
      _setSaving(false);
    }
  };

  const cancelEditing = () => {
    setBioDraft(profile.bio || '');
    _setSaveError('');
    setEditing(false);
  };

  const openFollowers = () => setModalConfig({ isOpen: true, title: 'Seguidores', type: 'followers' });
  const openFollowing = () => setModalConfig({ isOpen: true, title: 'Siguiendo', type: 'following' });
  const closeModal = () => setModalConfig(prev => ({ ...prev, isOpen: false }));
  const modalPage = modalConfig.type === 'followers' ? followers : following;

  const tone = Math.abs(profile.username.split('').reduce((a, c) => a + c.charCodeAt(0), 0)) % 4;

  return (
    <section className="profile-page">
      {/* ── Hero ── */}
      <header className="profile-hero">
        <div className="profile-hero-orbits" aria-hidden="true">
          <span className="orbit orbit-one" />
          <span className="orbit orbit-two" />
          <span className="orbit orbit-three" />
        </div>

        <div className="profile-hero-content">
          <div className="profile-identity">
            {isMe ? (
              <div className="profile-hero-photo-wrapper">
                <input ref={avatarInputRef} type="file" accept="image/png,image/jpeg,.png,.jpg,.jpeg"
                  onChange={selectAvatar} hidden aria-label="Seleccionar foto de perfil" />
                <button type="button" className="profile-hero-photo profile-hero-photo-edit" onClick={() => avatarInputRef.current?.click()}
                  disabled={uploadingAvatar} aria-label="Cambiar foto de perfil" title="Cambiar foto de perfil">
                  <Avatar username={profile.username} avatarUrl={profile.avatarUrl} tone={tone} size="xl" />
                  <span className="profile-hero-photo-badge">
                    {uploadingAvatar ? <RefreshCw size={17} className="profile-avatar-spinner" /> : <Camera size={17} />}
                  </span>
                </button>
              </div>
            ) : (
              <div className="profile-hero-photo">
                <Avatar username={profile.username} avatarUrl={profile.avatarUrl} tone={tone} size="xl" />
              </div>
            )}
            <div className="profile-identity-copy">
              <span className="network-eyebrow"><span className="network-eyebrow-dot" />{isMe ? 'MI PERFIL' : 'PERFIL ORBIT'}</span>
              <h1>@{profile.username}</h1>
              {!isMe && <OnlineIndicator userId={id} showText size="sm" />}
            </div>
          </div>

          {avatarError && <p className="profile-avatar-inline-error" role="alert">{avatarError}</p>}
          {avatarSuccess && <p className="profile-avatar-inline-success" role="status">{avatarSuccess}</p>}

          {editing ? (
            <form className="profile-edit-form" onSubmit={saveProfile}>
              <label htmlFor="profile-bio">Tu biografía</label>
              <textarea
                id="profile-bio"
                value={bioDraft}
                maxLength={160}
                onChange={event => setBioDraft(event.target.value)}
                placeholder="Cuéntanos algo sobre ti..."
              />
              <span className="profile-bio-counter">{bioDraft.length}/160</span>
              {_saveError && <p role="alert" className="profile-edit-error">{_saveError}</p>}
              <div className="profile-edit-actions">
                <button type="submit" disabled={_saving} className="network-btn network-btn-primary">
                  {_saving ? 'Guardando...' : 'Guardar cambios'}
                </button>
                <button type="button" onClick={cancelEditing} className="network-btn network-btn-secondary">Cancelar</button>
              </div>
            </form>
          ) : (
            <p className="profile-bio-main">{profile.bio || (isMe ? 'Añade una biografía para que tu comunidad te conozca.' : 'Aún no ha añadido una biografía.')}</p>
          )}

          <div className="profile-hero-footer">
            <div className="profile-stats" aria-label="Resumen del perfil">
              <button type="button" className="stat-item" onClick={openFollowing}>
                <strong>{following.total}</strong><span>Siguiendo</span>
              </button>
              <button type="button" className="stat-item" onClick={openFollowers}>
                <strong>{followers.total}</strong><span>Seguidores</span>
              </button>
              <a href="#posts-title" className="stat-item">
                <strong>{posts.length}</strong><span>Publicaciones</span>
              </a>
            </div>

            {(!isMe || !editing) && <div className="profile-actions">
              {isMe ? (
                <>
                  <button type="button" className="network-btn network-btn-secondary" onClick={() => avatarInputRef.current?.click()}
                    disabled={uploadingAvatar}><Camera size={16} />{uploadingAvatar ? 'Subiendo...' : 'Cambiar foto'}</button>
                  <button type="button" className="network-btn network-btn-primary" onClick={() => setEditing(true)}>
                    <Pencil size={16} />Editar perfil
                  </button>
                </>
              ) : (
                <div className="profile-actions-group">
                  <FollowButton key={id} userId={id} initialIsFollowing={isFollowingProfile} onToggle={handleToggleFollow} />
                  <Link to={`/messages/${id}`} className="network-btn network-btn-secondary" aria-label="Enviar mensaje">
                    <MessageSquare size={16} /> <span className="profile-message-label-desktop">Enviar mensaje</span><span className="profile-message-label-mobile">Mensaje</span>
                  </Link>
                </div>
              )}
            </div>}
          </div>
        </div>
      </header>

      {/* ── Main Content ── */}
      <div className="profile-layout">
        {/* ── Posts Column ── */}
        <main className="profile-main">
          <section className="profile-section" aria-labelledby="posts-title">
            <div className="network-section-heading">
              <div>
                <span className="network-section-kicker"><Sparkles size={15} /> PUBLICACIONES</span>
                <h2 id="posts-title">{isMe ? 'Tus publicaciones' : 'Lo que ha compartido'}</h2>
                <p>{posts.length} publicación{posts.length === 1 ? '' : 'es'} en total.</p>
              </div>
            </div>

            {posts.length === 0 ? (
              <EmptyState
                title={isMe ? 'Aún no has publicado' : 'Este usuario aún no ha publicado'}
                description={isMe ? 'Comparte algo desde el feed para empezar a construir tu historia.' : 'Cuando publique, verás aquí sus contenidos.'}
                action={isMe}
              />
            ) : (
              <div className="profile-posts-grid">
                {posts.map(post => (
                  <PostCard
                    key={post.id}
                    post={post}
                    onDeleted={(postId) => setPosts(previous => previous.filter(item => item.id !== postId))}
                    showAuthor={false}
                  />
                ))}
              </div>
            )}
          </section>
        </main>

        {/* ── Sidebar ── */}
        <aside className="profile-sidebar" aria-label="Resumen de conexiones">
          <section className="network-side-card profile-circle-card">
            <span className="network-section-kicker"><Users size={15} /> {isMe ? 'TU CÍRCULO' : 'SU CÍRCULO'}</span>
            <h2>{isMe ? 'Gente que sigues' : 'Gente que sigue'}</h2>
            {following.total > 0 ? (
              <div className="network-following-list">
                {following.users.map((person, index) => (
                  <Link key={person.id} to={`/users/${person.id}`}>
                    <Avatar username={person.username} avatarUrl={person.avatarUrl} tone={index} size="sm" />
                    <span>@{person.username}</span>
                    <ArrowRight size={15} />
                  </Link>
                ))}
              </div>
            ) : (
              <p className="network-side-empty">{isMe ? 'Todavía no sigues a nadie.' : 'Esta persona no sigue a nadie.'}</p>
            )}
            {connectionsLoading.following && <p className="profile-connection-status" role="status">Cargando...</p>}
            {connectionsError.following && <p className="profile-connection-error" role="alert">{connectionsError.following} <button type="button" onClick={() => changeConnectionsPage('following', following.page)}>Reintentar</button></p>}
            <ConnectionPager page={following.page} total={following.total} size={following.size}
              loading={connectionsLoading.following} onPageChange={page => changeConnectionsPage('following', page)} label="personas que sigue" />
            {!isMe && (
              <button className="network-map-link" type="button" onClick={openFollowing}>
                Ver todos los que sigue <ArrowRight size={16} />
              </button>
            )}
          </section>

          <section className="network-side-card">
            <span className="network-section-kicker"><Users size={15} /> SEGUIDORES</span>
            <h2>{isMe ? 'Tus seguidores' : 'Sus seguidores'}</h2>
            {followers.total > 0 ? (
              <div className="network-following-list">
                {followers.users.map((person, index) => (
                  <Link key={person.id} to={`/users/${person.id}`}>
                    <Avatar username={person.username} avatarUrl={person.avatarUrl} tone={index} size="sm" />
                    <span>@{person.username}</span>
                    <ArrowRight size={15} />
                  </Link>
                ))}
              </div>
            ) : (
              <p className="network-side-empty">{isMe ? 'Todavía no tienes seguidores.' : 'Esta persona no tiene seguidores.'}</p>
            )}
            {connectionsLoading.followers && <p className="profile-connection-status" role="status">Cargando...</p>}
            {connectionsError.followers && <p className="profile-connection-error" role="alert">{connectionsError.followers} <button type="button" onClick={() => changeConnectionsPage('followers', followers.page)}>Reintentar</button></p>}
            <ConnectionPager page={followers.page} total={followers.total} size={followers.size}
              loading={connectionsLoading.followers} onPageChange={page => changeConnectionsPage('followers', page)} label="seguidores" />
            {!isMe && (
              <button className="network-map-link" type="button" onClick={openFollowers}>
                Ver todos los seguidores <ArrowRight size={16} />
              </button>
            )}
          </section>

        </aside>
      </div>

      <UserListModal
        isOpen={modalConfig.isOpen}
        onClose={closeModal}
        title={modalConfig.title}
        users={modalPage.users}
        page={modalPage.page}
        total={modalPage.total}
        size={modalPage.size}
        loading={connectionsLoading[modalConfig.type]}
        error={connectionsError[modalConfig.type]}
        onPageChange={page => changeConnectionsPage(modalConfig.type, page)}
      />
    </section>
  );
}
