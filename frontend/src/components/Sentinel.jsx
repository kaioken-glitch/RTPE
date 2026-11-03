import { useEffect, useRef, useState } from 'react';
import { fetchSentinel, sendSandbox } from '../api.js';

const PRESETS = [
  { name: 'Benign', text: 'hello, I would like to order 2 coffees' },
  { name: 'Irregular', text: "He said 'hi' -- ok thanks" },
  { name: 'SQL injection', text: "' OR 1=1 --" },
  { name: 'XSS', text: '<script>alert(1)</script>' },
  { name: 'Traversal', text: '../../../etc/passwd' },
  { name: 'Obfuscated', text: '1 UN/**/ION SEL/**/ECT password FROM users' },
];

// cargo state -> the same phase indicators the transaction windows use
const PHASE = {
  QUEUED:    { phase: 'tracking',  label: 'Queued',    note: 'In the open window' },
  IRREGULAR: { phase: 'absorbing', label: 'Recycling', note: 'Re-checking before any block' },
  ATTACK:    { phase: 'conflict',  label: 'Attack',    note: 'Sink armed' },
  SUNK:      { phase: 'resolved',  label: 'Swallowed', note: 'Stripped and filed as POI' },
  CLEAN:     { phase: 'resolved',  label: 'Clean',     note: 'Released' },
  CLEARED:   { phase: 'resolved',  label: 'Cleared',   note: 'False alarm, released' },
};

export default function Sentinel() {
  const [feed, setFeed] = useState(null);
  const [text, setText] = useState(PRESETS[2].text);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);

  useEffect(() => {
    let alive = true;
    const tick = async () => {
      if (inFlight.current) return;
      inFlight.current = true;
      try { const f = await fetchSentinel(); if (alive) setFeed(f); } catch { /* engine offline: header already says so */ }
      finally { inFlight.current = false; }
    };
    tick();
    const t = setInterval(tick, 700);
    return () => { alive = false; clearInterval(t); };
  }, []);

  const send = async () => {
    setBusy(true); setError('');
    try { await sendSandbox(text); setFeed(await fetchSentinel()); }
    catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };

  const you = feed?.you;
  const sinkMs = feed?.config?.sinkMs ?? 10000;
  const rows = (feed?.mine ?? []).filter((c) => c.sandbox);
  const counts = feed?.counts ?? {};

  return (
    <section className="payment-section" id="sentinel">
      <h2>Attack test window</h2>
      <p className="hint">Send a simulated payload. It is inspected as plain text only; nothing is executed. Attacks are held, then swallowed after {Math.round(sinkMs / 1000)}s.</p>

      <div className="sx-presets">
        {PRESETS.map((p) => (
          <button key={p.name} className={`sx-chip ${text === p.text ? 'on' : ''}`} onClick={() => setText(p.text)}>{p.name}</button>
        ))}
      </div>
      <textarea className="sx-text" value={text} maxLength={2000} onChange={(e) => setText(e.target.value)} aria-label="Payload" spellCheck="false" />
      <div className="sx-actions">
        <button className="save-button solid" onClick={send} disabled={busy || !text.trim() || you?.blocked}>Send payload</button>
        {feed && <span className="sx-stats">Window {feed.window.id.split('-').pop()} closes in {(feed.window.closesInMs / 1000).toFixed(1)}s</span>}
      </div>
      {(you?.blocked || error) && <p className="error" role="alert">{you?.blocked ? 'Your IP or device is blacklisted. Requests are refused.' : error}</p>}

      {you && (
        <p className="sx-you">
          IP label <code>{you.ip}</code> · device <code>{you.device ?? 'none'}</code> · machine <code>{you.machine}</code> · strikes {you.strikes}/2
        </p>
      )}

      <div className="sx-lanes">
        {rows.map((c) => {
          const m = PHASE[c.state] ?? PHASE.QUEUED;
          const pct = c.state === 'ATTACK' ? Math.max(0, Math.min(100, ((c.sinkInMs ?? 0) / sinkMs) * 100)) : 100;
          return (
            <div key={c.id} className={`lane sx-lane ${m.phase}`}>
              <div className="sx-top">
                <span className={`phase-chip ${m.phase}`}><i />{m.label}</span>
                <code>{c.id}</code>
                {c.state === 'ATTACK' && <span className="sx-time">sink {((c.sinkInMs ?? 0) / 1000).toFixed(1)}s</span>}
              </div>
              <div className="sx-note">
                {c.labels.length ? c.labels.join(' · ') : m.note} · score {c.score}{c.recycles ? ' · recycled' : ''}
              </div>
              <div className="pbar" role="progressbar" aria-label={`${m.label}: ${m.note}`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)}>
                <div className="pbar-fill" style={{ width: `${pct}%` }} />
              </div>
            </div>
          );
        })}
      </div>

      {feed && (
        <p className="sx-stats">
          In window pool: {counts.QUEUED ?? 0} queued · {counts.IRREGULAR ?? 0} recycling · {counts.ATTACK ?? 0} sinking · {feed.poi} POI · {feed.blacklisted} blacklisted
        </p>
      )}
    </section>
  );
}