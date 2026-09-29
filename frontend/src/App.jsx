import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { AnimatePresence } from 'framer-motion';
import { AuthProvider, useAuth } from './context/AuthContext';
import { PresenceProvider } from './context/PresenceContext';
import { FeedEventsProvider } from './context/FeedEventsContext';
import LoginPage    from './pages/auth/LoginPage';
import RegisterPage from './pages/auth/RegisterPage';
import FeedPage     from './pages/feed/FeedPage';
import PostDetailPage from './pages/feed/PostDetailPage';
import DashboardLayout from './pages/dashboard/DashboardLayout';
import ProfilePage  from './pages/profile/ProfilePage';
import ChatPage from './pages/messages/ChatPage';
import GraphPage from './pages/graph/GraphPage';
import NotificationSettingsPage from './pages/settings/NotificationSettingsPage';

function PrivateRoute({ children }) {
  const { isAuthenticated, restoring } = useAuth();
  if (restoring) return <div role="status">Restaurando sesión...</div>;
  return isAuthenticated ? children : <Navigate to="/login" replace />;
}

function PublicRoute({ children }) {
  const { isAuthenticated, restoring } = useAuth();
  if (restoring) return <div role="status">Restaurando sesión...</div>;
  return isAuthenticated ? <Navigate to="/feed" replace /> : children;
}

function AppRoutes() {
  const location = useLocation();

  return (
    <AnimatePresence mode="wait">
      <Routes location={location} key={location.pathname}>
        <Route path="/" element={<Navigate to="/login" replace />} />

        <Route path="/login" element={
          <PublicRoute><LoginPage /></PublicRoute>
        } />

        <Route path="/register" element={
          <PublicRoute><RegisterPage /></PublicRoute>
        } />

        <Route element={<PrivateRoute><DashboardLayout /></PrivateRoute>}>
          <Route path="/feed" element={<FeedPage />} />
          <Route path="/users/:id" element={<ProfilePage />} />
          <Route path="/posts/:id" element={<PostDetailPage />} />
          <Route path="/messages" element={<ChatPage />} />
          <Route path="/messages/:userId" element={<ChatPage />} />
          <Route path="/graph" element={<GraphPage />} />
          <Route path="/settings/notifications" element={<NotificationSettingsPage />} />
        </Route>

        {/* Catch-all */}
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    </AnimatePresence>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <PresenceProvider>
          <FeedEventsProvider>
            <AppRoutes />
          </FeedEventsProvider>
        </PresenceProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}
