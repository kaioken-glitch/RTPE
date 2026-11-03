import { useCallback, useEffect, useState } from 'react';
import { fetchMe, getToken, login as apiLogin, logout as apiLogout, register as apiRegister, setToken } from './api.js';

const REFRESH_MS = 4000;   // keeps the signed-in user's contribution numbers trickling in

export function useAuth() {
  const [user, setUser] = useState(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    if (!getToken()) { setUser(null); return; }
    try {
      setUser(await fetchMe());
    } catch (e) {
      if (e.status === 401) { setToken(null); setUser(null); }   // expired, or the server restarted
    }
  }, []);

  useEffect(() => {
    refresh().finally(() => setReady(true));
    const t = setInterval(refresh, REFRESH_MS);
    return () => clearInterval(t);
  }, [refresh]);

  const enter = async (fn, name, password) => {
    setBusy(true); setError('');
    try {
      const r = await fn(name.trim(), password);
      setToken(r.token); setUser(r.user);
      return true;
    } catch (e) {
      setError(e.message);
      return false;
    } finally {
      setBusy(false);
    }
  };

  return {
    user, ready, error, busy,
    login: (n, p) => enter(apiLogin, n, p),
    register: (n, p) => enter(apiRegister, n, p),
    logout: async () => { try { await apiLogout(); } catch { /* token is dropped locally either way */ } setToken(null); setUser(null); },
    clearError: () => setError(''),
  };
}
