import { useEffect } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import MainLayout from './layouts/MainLayout';
import Login from './pages/Login';
import Setup from './pages/Setup';
import Dashboard from './pages/Dashboard';
import Shared from './pages/Shared';
import Admin from './pages/Admin';
import AccountLink from './pages/AccountLink';
import { ToastProvider } from './contexts/ToastContext';
import { fetchCurrentUser } from './features/authSlice';
import { Loader2 } from 'lucide-react';

function App() {
  const dispatch = useDispatch();
  const { isInitialized } = useSelector((state) => state.auth);

  useEffect(() => {
    dispatch(fetchCurrentUser());
  }, [dispatch]);

  if (!isInitialized) {
    return (
      <div className="flex h-screen items-center justify-center bg-gray-50">
        <Loader2 className="animate-spin h-10 w-10 text-blue-600" />
      </div>
    );
  }

  return (
    <ToastProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/setup" element={<Setup />} />
          {/* Public: what a share link opens, before anything is downloaded. */}
          <Route path="/share/:token" element={<Shared />} />
          {/* Public: the links admins send, to create an account or set a new password. */}
          <Route path="/invite/:token" element={<AccountLink purpose="invite" />} />
          <Route path="/reset-password/:token" element={<AccountLink purpose="reset" />} />

          <Route path="/" element={<MainLayout />}>
            <Route index element={<Navigate to="/dashboard" replace />} />
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="admin" element={<Admin />} />
          </Route>

          {/* Anything else would render an empty page; MainLayout sends it on to sign-in if needed. */}
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </BrowserRouter>
    </ToastProvider>
  );
}

export default App;
