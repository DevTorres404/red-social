import { Outlet, useLocation } from 'react-router-dom';
import Navbar from '../../components/layout/Navbar';
import './Dashboard.css';

export default function DashboardLayout() {
  const location = useLocation();
  
  // Determine page type for background accents
  const getPageClass = () => {
    const path = location.pathname;
    if (path.startsWith('/users')) return 'profile-page';
    if (path.startsWith('/messages')) return 'messages-page';
    if (path.startsWith('/graph')) return 'graph-page';
    if (path.startsWith('/settings')) return 'settings-page';
    return 'feed-page';
  };

  return (
    <div className="dashboard-layout">
      <Navbar />
      
      <main className={`dashboard-main ${getPageClass()}`}>
        <div className="dashboard-content">
          <Outlet />
        </div>
      </main>
    </div>
  );
}