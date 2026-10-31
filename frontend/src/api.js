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

/**
 * device:false sends no custom header. A plain GET then needs no CORS preflight,
 * which halves the polling traffic and removes one way for a waking host to fail.
 */
async function raw(path, { timeoutMs = 8000, device = true, headers, ...opts } = {}) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs);
  let res;
  try {
    res = await fetch(`${BASE}${path}`, {
      ...opts,
      signal: ctl.signal,
      headers: { ...(device ? { 'X-Device-Id': deviceId() } : {}), ...(headers ?? {}) },
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

export const fetchState = (since) => call(`/api/state?since=${since}`, 'GET', { device: false });
export const resetSandbox = () => call('/api/reset', 'POST');
export const submitTransaction = ({ id, label, amount, fail = false }) =>
  call(`/api/transactions?${new URLSearchParams({ id, label, amount, fail })}`, 'POST');

// Sentinel: the attack test window
export const fetchSentinel = () => call('/api/sentinel/feed');
export const sendSandbox = (text) => raw('/sandbox', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: text });
