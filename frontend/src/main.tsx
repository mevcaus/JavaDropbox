import React from 'react';
import ReactDOM from 'react-dom/client';
import { Provider } from 'react-redux';
import { store } from './redux/store';
import { setUnauthorizedHandler } from './services/api';
import { clearUser } from './features/authSlice';
import App from './App';
import './index.css';

setUnauthorizedHandler(() => store.dispatch(clearUser()));

const root = document.getElementById('root');
if (!root) throw new Error('index.html has no #root element');

ReactDOM.createRoot(root).render(
  <React.StrictMode>
    <Provider store={store}>
      <App />
    </Provider>
  </React.StrictMode>,
);
