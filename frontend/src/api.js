const BASE = import.meta.env.VITE_RTPE_URL ?? 'http://localhost:8080';

async function call(path, method = 'GET') {
  const res = await fetch(`${BASE}${path}`, { method });
  const body = await res.json();
  if (!res.ok) throw new Error(body.error ?? `HTTP ${res.status}`);
  return body;
}

export const fetchState = (since) => call(`/api/state?since=${since}`);
export const resetSandbox = () => call('/api/reset', 'POST');
export const submitTransaction = ({ id, label, amount, fail = false }) =>
  call(`/api/transactions?${new URLSearchParams({ id, label, amount, fail })}`, 'POST');
