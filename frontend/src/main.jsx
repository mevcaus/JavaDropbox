import React from 'react';
import ReactDOM from 'react-dom/client';
import { Provider } from 'react-redux';
import { store } from './redux/store';
import { setUnauthorizedHandler } from './services/api';
import { clearUser } from './features/authSlice';
import App from './App.jsx';
import './index.css';

setUnauthorizedHandler(() => store.dispatch(clearUser()));

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <Provider store={store}>
      <App />
    </Provider>
  </React.StrictMode>,
);
