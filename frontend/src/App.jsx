import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import MainLayout from './layouts/MainLayout';
import Login from './pages/Login';
import Dashboard from './pages/Dashboard';

// Placeholder for Dashboard until implemented
// const Dashboard = () => <div className="p-4"><h2>Dashboard</h2><p>Files will be listed here.</p></div>;

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />

        <Route path="/" element={<MainLayout />}>
          <Route index element={<Navigate to="/dashboard" replace />} />
          <Route path="dashboard" element={<Dashboard />} />
          <Route path="recent" element={<div className="p-4">Recent Files Placeholder</div>} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}

export default App;
