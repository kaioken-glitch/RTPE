const RAW_BASE = import.meta.env.VITE_RTPE_URL ?? 'http://localhost:8080';
/** Trim whitespace and trailing slashes: "https://x.com/" would produce "//api/state", which the server 404s. */
export const BASE = String(RAW_BASE).trim().replace(/\/+$/, '');

export class ApiError extends Error {
  constructor(message, { status = 0, network = false } = {}) {
    super(message);
    this.status = status;
    /** true when no usable answer came from the engine: network failure, timeout, or a proxy 502/503/504. */
    this.network = network || status === 502 || status === 503 || status === 504;
  }
}

/** Random id this browser keeps. It labels the device for Sentinel; it is not a credential. */
export function deviceId() {
  try {
    let id = localStorage.getItem('rtpe-device');
    if (!id) {
      id = (crypto.randomUUID?.() ?? `${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`).replace(/[^A-Za-z0-9_-]/g, '');
      localStorage.setItem('rtpe-device', id);
    }
    return id;
  } catch {
    return `anon-${Date.now().toString(36)}`;
  }
}

const TOKEN_KEY = 'rtpe-token';
export const getToken = () => { try { return localStorage.getItem(TOKEN_KEY); } catch { return null; } };
export const setToken = (t) => { try { t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY); } catch { /* storage blocked */ } };

/**
 * device:false / auth:false send no custom headers. A plain GET or form POST then needs no CORS preflight,
 * which halves polling traffic and removes one way for a waking host to fail.
 */
async function raw(path, { timeoutMs = 8000, device = true, auth = true, headers, ...opts } = {}) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  const token = auth ? getToken() : null;
  let res;
  try {
    res = await fetch(`${BASE}${path}`, {
      ...opts,
      signal: ctl.signal,
      headers: {
        ...(device ? { 'X-Device-Id': deviceId() } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(headers ?? {}),
      },
    });
  } catch (e) {
    throw new ApiError(e.name === 'AbortError' ? 'Request timed out' : 'Network error', { network: true });
  } finally {
    clearTimeout(timer);
  }
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(body.error ?? `HTTP ${res.status}`, { status: res.status });
  return body;
}
const call = (path, method = 'GET', extra = {}) => raw(path, { method, ...extra });

// Cockpit
export const fetchState = (since) => call(`/api/state?since=${since}`, 'GET', { device: false, auth: false });
export const resetSandbox = () => call('/api/reset', 'POST');
export const submitTransaction = ({ id, label, amount, fail = false }) =>
  call(`/api/transactions?${new URLSearchParams({ id, label, amount, fail })}`, 'POST');

// Sentinel lab
export const fetchSentinel = () => call('/api/sentinel/feed');
export const sendSandbox = (text) => raw('/sandbox', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: text });
export const communityStream = (since) => call(`/api/community/stream?since=${since}`, 'GET', { device: false, auth: false });
export const leaderboard = () => call('/api/community/leaderboard', 'GET', { device: false, auth: false });

// Accounts (form-encoded so the browser sends a simple request)
const form = (path, name, password) => raw(path, {
  method: 'POST', device: false, auth: false, timeoutMs: 20000,
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({ username: name, password }).toString(),
});
export const register = (name, password) => form('/api/auth/register', name, password);
export const login = (name, password) => form('/api/auth/login', name, password);
export const logout = () => call('/api/auth/logout', 'POST', { device: false });
export const fetchMe = () => call('/api/auth/me', 'GET', { device: false });
