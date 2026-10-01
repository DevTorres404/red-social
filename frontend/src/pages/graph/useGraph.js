import { useEffect, useMemo, useState } from 'react';
import { graphApi, usersApi } from '../../lib/api';
import { useAuth } from '../../context/AuthContext';
import { buildNetworkMap } from './networkMap';

const PAGE_SIZE = 5;

async function fetchNetwork(userId) {
  const [reachable, recommendations, networkPosts, trendingPosts, following, discoverable] = await Promise.all([
    graphApi.reachable(), graphApi.recommendations(), graphApi.networkPosts(),
    graphApi.trendingPosts(), usersApi.getFollowing(userId), usersApi.discover(),
  ]);
  return { reachable, recommendations, networkPosts, trendingPosts, following, discoverable };
}

export function useGraph() {
  const { user } = useAuth();
  const [data, setData] = useState(null);
  const [following, setFollowing] = useState([]);
  const [recentlyInteracted, setRecentlyInteracted] = useState([]);
  const [activeTab, setActiveTab] = useState('discover');
  const [peoplePage, setPeoplePage] = useState(0);
  const [postOrder, setPostOrder] = useState('latest');
  const [otherId, setOtherId] = useState('');
  const [common, setCommon] = useState([]);
  const [commonError, setCommonError] = useState('');
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [followingId, setFollowingId] = useState('');
  const [selectedNodeId, setSelectedNodeId] = useState(user?.id);
  const [mapQuery, setMapQuery] = useState('');

  useEffect(() => {
    if (!user?.id) return;
    let active = true;
    fetchNetwork(user.id).then((result) => {
      if (!active) return;
      setData(result); setFollowing(result.following || []); setError('');
      setPeoplePage(0);
    }).catch((err) => { if (active) setError(err.message || 'No se pudo cargar tu red.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [user?.id]);

  useEffect(() => {
    if (!otherId) return;
    let active = true;
    graphApi.common(otherId).then((result) => {
      if (active) { setCommon(result || []); setCommonError(''); }
    }).catch(() => { if (active) { setCommon([]); setCommonError('No se pudieron consultar las conexiones.'); } });
    return () => { active = false; };
  }, [otherId]);

  const refresh = async () => {
    if (!user?.id) return;
    setRefreshing(true); setError('');
    try {
      const result = await fetchNetwork(user.id);
      setData(result); setFollowing(result.following || []);
    } catch (err) { setError(err.message || 'No se pudo actualizar tu red.'); }
    finally { setRefreshing(false); setLoading(false); }
  };

  const toggleFollow = async (person) => {
    if (followingId) return;
    const wasFollowing = following.some(item => item.id === person.id);
    setFollowingId(person.id); setActionError('');
    try {
      if (wasFollowing) await usersApi.unfollow(person.id);
      else await usersApi.follow(person.id);
      setFollowing(current => wasFollowing
        ? current.filter(item => item.id !== person.id)
        : current.some(item => item.id === person.id) ? current : [...current, person]);
      setRecentlyInteracted(current => current.some(item => item.id === person.id) ? current : [...current, person]);
      await refresh();
    } catch (err) { setActionError(err.message || 'No se pudo actualizar el seguimiento.'); }
    finally { setFollowingId(''); }
  };

  const direct = (data?.reachable || []).filter((person) => person.distance === 1);
  const second = (data?.reachable || []).filter((person) => person.distance === 2);
  const directById = new Map(direct.map((person) => [person.id, person.username]));
  const graph = useMemo(() => {
    if (!user) return { nodes: [], edges: [], hiddenCount: 0 };
    return buildNetworkMap(user, data?.reachable || [], selectedNodeId);
  }, [data, user, selectedNodeId]);
  
  const selectedPerson = (data?.reachable || []).find((person) => person.id === selectedNodeId) || user;
  const visibleMapPeople = (data?.reachable || []).filter((person) =>
    person.username.toLocaleLowerCase().includes(mapQuery.trim().toLocaleLowerCase()));
  const highlightedEdges = new Set(selectedPerson?.distance === 2
    ? [`${user?.id}-${selectedPerson.viaId}`, `${selectedPerson.viaId}-${selectedPerson.id}`]
    : selectedPerson?.distance === 1 ? [`${user?.id}-${selectedPerson.id}`] : []);
  const graphById = new Map(graph.nodes.map((node) => [node.id, node]));
  const posts = postOrder === 'latest' ? data?.networkPosts || [] : data?.trendingPosts || [];
  
  const followedIds = new Set(following.map(person => person.id));
  const peopleById = new Map();
  for (const person of [...following, ...recentlyInteracted, ...(data?.recommendations || []), ...(data?.discoverable || [])]) {
    if (person.id !== user?.id && !peopleById.has(person.id)) {
      peopleById.set(person.id, { ...person, alreadyFollowing: followedIds.has(person.id) });
    }
  }
  const people = [...peopleById.values()];

  return {
    user,
    data,
    following,
    recentlyInteracted,
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
  };
}
