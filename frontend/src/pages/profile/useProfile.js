import { useState, useEffect, useRef } from 'react';
import { usersApi } from '../../lib/api';
import { useAuth } from '../../context/AuthContext';

const MAX_AVATAR_BYTES = 6 * 1024 * 1024;
const EMPTY_CONNECTIONS = { users: [], total: 0, page: 0, size: 4 };

export function useProfile(id) {
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
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState('');
  const [avatarError, setAvatarError] = useState('');
  const [avatarSuccess, setAvatarSuccess] = useState('');
  const [uploadingAvatar, setUploadingAvatar] = useState(false);
  const [modalConfig, setModalConfig] = useState({ isOpen: false, title: '', type: 'followers' });

  const viewedProfileId = useRef(id);

  useEffect(() => { viewedProfileId.current = id; }, [id]);

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
      
      setTimeout(() => setAvatarSuccess(''), 3000);
    } catch (err) {
      setAvatarError(err.message || 'No se pudo subir la foto. Inténtalo de nuevo.');
    } finally {
      setUploadingAvatar(false);
    }
  };

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
    setSaving(true);
    setSaveError('');
    try {
      const updated = await usersApi.updateProfile(id, { bio: bioDraft.trim() });
      setProfile(updated);
      updateUser(updated);
      setEditing(false);
    } catch (err) {
      setSaveError(err.message || 'No se pudo guardar el perfil');
    } finally {
      setSaving(false);
    }
  };

  const cancelEditing = () => {
    setBioDraft(profile.bio || '');
    setSaveError('');
    setEditing(false);
  };

  const openFollowers = () => setModalConfig({ isOpen: true, title: 'Seguidores', type: 'followers' });
  const openFollowing = () => setModalConfig({ isOpen: true, title: 'Siguiendo', type: 'following' });
  const closeModal = () => setModalConfig(prev => ({ ...prev, isOpen: false }));
  
  const removePost = (postId) => setPosts(previous => previous.filter(item => item.id !== postId));

  return {
    currentUser,
    profile,
    followers,
    following,
    connectionsLoading,
    connectionsError,
    isFollowingProfile,
    posts,
    editing,
    setEditing,
    bioDraft,
    setBioDraft,
    loading,
    error,
    saving,
    saveError,
    avatarError,
    avatarSuccess,
    uploadingAvatar,
    modalConfig,
    selectAvatar,
    changeConnectionsPage,
    handleToggleFollow,
    saveProfile,
    cancelEditing,
    openFollowers,
    openFollowing,
    closeModal,
    removePost,
  };
}
