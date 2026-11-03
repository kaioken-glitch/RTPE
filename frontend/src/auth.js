import { useCallback, useEffect, useRef, useState } from 'react';
import { exchangeSupabaseToken, fetchMe, getToken, logout as apiLogout, setToken } from './api.js';
import { supabase } from './supabase.js';

export function useAuth() {
  const [user, setUser] = useState(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const activeAccessToken = useRef('');
  const exchangeId = useRef(0);
  const pendingExchange = useRef(null);
  const refreshing = useRef(false);

  const syncSession = useCallback(async (session, force = false) => {
    const accessToken = session?.access_token;
    if (!accessToken) {
      exchangeId.current += 1;
      activeAccessToken.current = '';
      pendingExchange.current = null;
      setToken(null);
      setUser(null);
      setBusy(false);
      return false;
    }
    if (pendingExchange.current?.token === accessToken) return pendingExchange.current.promise;
    if (!force && activeAccessToken.current === accessToken) return Boolean(getToken());

    activeAccessToken.current = accessToken;
    const currentExchange = ++exchangeId.current;
    setBusy(true);
    const promise = (async () => {
      try {
        const result = await exchangeSupabaseToken(accessToken);
        if (currentExchange !== exchangeId.current) return false;
        setToken(result.token);
        setUser(result.user);
        setError('');
        return true;
      } catch (e) {
        if (currentExchange !== exchangeId.current) return false;
        setToken(null);
        setUser(null);
        setError(e.message);
        return false;
      } finally {
        if (currentExchange === exchangeId.current) {
          pendingExchange.current = null;
          setBusy(false);
        }
      }
    })();
    pendingExchange.current = { token: accessToken, promise };
    return promise;
  }, []);

  useEffect(() => {
    if (!supabase) {
      setToken(null);
      setUser(null);
      setReady(true);
      return undefined;
    }

    let active = true;
    const { data: { subscription } } = supabase.auth.onAuthStateChange((_event, session) => {
      queueMicrotask(() => {
        if (active) void syncSession(session);
      });
    });

    supabase.auth.getSession().then(({ data, error: sessionError }) => {
      if (!active) return;
      if (sessionError) setError(sessionError.message);
      void syncSession(data?.session ?? null);
      setReady(true);
    }).catch((e) => {
      if (active) {
        setError(e.message);
        setReady(true);
      }
    });

    return () => {
      active = false;
      subscription.unsubscribe();
    };
  }, [syncSession]);

  useEffect(() => {
    if (!user || !getToken()) return undefined;
    const timer = setInterval(async () => {
      if (refreshing.current) return;
      refreshing.current = true;
      try {
        setUser(await fetchMe());
        setError('');
      } catch (e) {
        if (e.status !== 401) {
          setError(e.message);
          return;
        }
        try {
          const { data, error: sessionError } = await supabase.auth.getSession();
          if (sessionError) throw sessionError;
          await syncSession(data?.session ?? null, true);
        } catch (refreshError) {
          setError(refreshError.message);
        }
      } finally {
        refreshing.current = false;
      }
    }, 4000);
    return () => clearInterval(timer);
  }, [syncSession, user]);

  const run = async (action) => {
    if (!supabase) return false;
    setBusy(true);
    setError('');
    try {
      const { data, error: authError } = await action();
      if (authError) throw authError;
      if (!data?.session) {
        setError('Check your email to confirm your account, then sign in.');
        return false;
      }
      return await syncSession(data.session, true);
    } catch (e) {
      setError(e.message);
      return false;
    } finally {
      setBusy(false);
    }
  };

  const login = (email, password) =>
    run(() => supabase.auth.signInWithPassword({ email: email.trim(), password }));
  const register = (email, password) =>
    run(() => supabase.auth.signUp({
      email: email.trim(),
      password,
      options: { emailRedirectTo: `${window.location.origin}${window.location.pathname}#lab` },
    }));
  const googleLogin = async () => {
    if (!supabase) return false;
    setBusy(true);
    setError('');
    try {
      const { error: authError } = await supabase.auth.signInWithOAuth({
        provider: 'google',
        options: { redirectTo: `${window.location.origin}${window.location.pathname}#lab` },
      });
      if (authError) throw authError;
      return true;
    } catch (e) {
      setError(e.message);
      return false;
    } finally {
      setBusy(false);
    }
  };
  const logout = async () => {
    if (!supabase) return;
    setBusy(true);
    setError('');
    let logoutError = '';
    try {
      await apiLogout();
    } catch (e) {
      logoutError = `Backend sign-out failed: ${e.message}`;
    }
    try {
      const { error: authError } = await supabase.auth.signOut();
      if (authError) throw authError;
    } catch (e) {
      logoutError = `Supabase sign-out failed: ${e.message}`;
    }
    setToken(null);
    setUser(null);
    activeAccessToken.current = '';
    setBusy(false);
    if (logoutError) {
      setError(logoutError);
    }
  };

  return {
    user, ready, error, busy, available: Boolean(supabase),
    login, register, googleLogin, logout,
    clearError: () => setError(''),
  };
}
