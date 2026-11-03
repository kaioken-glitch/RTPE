import { useEffect, useRef, useState } from 'react';

const FLASH_MS = 1400;
const GHOST_MS = 2600;

export const PHASES = {
  tracking:  { name: 'Tracking',   note: 'Window open, no duplicates' },
  conflict:  { name: 'Concurrent', note: 'Similar request detected' },
  absorbing: { name: 'Absorbing',  note: 'Duplicates held in buffer' },
  resolved:  { name: 'Resolved',   note: 'All flags cleared' },
  failed:    { name: 'Failed',     note: 'No fallback left' },
};

const ICON = { PAYMENT: 'credit-card', REFUND: 'arrow-u-up-left', INVENTORY_LOCK: 'lock-simple' };

export function useLanes(state, logs) {
  const [lanes, setLanes] = useState([]);
  const seen = useRef(new Map());

  useEffect(() => {
    if (!state) return;
    const now = Date.now();
    const live = new Map();

    for (const a of state.active) {
      const key = `${a.label}:${a.id}`;
      const prev = seen.current.get(key);
      let flashUntil = prev?.flashUntil ?? 0;
      if (prev && a.absorbed > prev.view.absorbed) flashUntil = now + FLASH_MS;
      if (!prev && a.absorbed > 0) flashUntil = now + FLASH_MS;
      const phase = flashUntil > now ? 'conflict' : a.absorbed > 0 ? 'absorbing' : 'tracking';
      live.set(key, { view: a, flashUntil, phase, ghostUntil: 0 });
    }

    for (const [key, prev] of seen.current) {
      if (live.has(key)) continue;
      if (prev.ghostUntil) {
        if (prev.ghostUntil > now) live.set(key, prev);
        continue;
      }
      const needle = `#${prev.view.id} `;
      const failed = logs.slice(-60).some((l) => l.msg.includes(needle) && l.msg.includes(prev.view.label) && l.msg.includes('terminal FAILURE'));
      live.set(key, { ...prev, phase: failed ? 'failed' : 'resolved', ghostUntil: now + GHOST_MS });
    }

    seen.current = live;
    setLanes([...live.entries()].map(([key, v]) => ({ key, ...v })));
  }, [state, logs]);

  useEffect(() => {
    const t = setInterval(() => {
      const now = Date.now();
      let changed = false;
      for (const [key, v] of seen.current) {
        if (v.ghostUntil && v.ghostUntil <= now) { seen.current.delete(key); changed = true; }
        else if (v.phase === 'conflict' && v.flashUntil <= now) { v.phase = v.view.absorbed > 0 ? 'absorbing' : 'tracking'; changed = true; }
      }
      if (changed) setLanes([...seen.current.entries()].map(([key, v]) => ({ key, ...v })));
    }, 200);
    return () => clearInterval(t);
  }, []);

  return lanes;
}

export function overallPhase(lanes, online) {
  if (!online) return 'offline';
  if (lanes.some((l) => l.phase === 'conflict')) return 'conflict';
  if (lanes.some((l) => l.phase === 'absorbing')) return 'absorbing';
  if (lanes.some((l) => l.phase === 'tracking')) return 'tracking';
  if (lanes.some((l) => l.phase === 'failed')) return 'failed';
  if (lanes.some((l) => l.phase === 'resolved')) return 'resolved';
  return 'idle';
}

export function StatusBar({ phase, label }) {
  return (
    <div className={`statusbar ${phase}`} role="status" aria-live="polite">
      <span className="statusbar-label">{label}</span>
      <div className="pbar" aria-hidden="true"><div className="pbar-fill" /></div>
    </div>
  );
}

function Legend() {
  return (
    <div className="legend">
      {['tracking', 'conflict', 'absorbing', 'resolved'].map((p) => (
        <span key={p} className={`phase-chip ${p}`}><i />{PHASES[p].name}</span>
      ))}
    </div>
  );
}

export default function Lanes({ lanes }) {
  return (
    <section className="transfer-section" id="windows">
      <div className="transfer-section-header">
        <h2>Live windows</h2>
        <div className="filter-options"><Legend /></div>
      </div>

      {lanes.length === 0 ? (
        <p className="empty">No open windows. Send a transaction to start one.</p>
      ) : (
        <div className="transfers">
          {lanes.map(({ key, view: a, phase }) => {
            const done = phase === 'resolved' || phase === 'failed';
            const pct = done ? 100 : (a.remainingMs / a.windowMs) * 100;
            return (
              <div key={key} className={`transfer lane ${phase}`}>
                <div className="transfer-logo"><i className={`ph ph-${ICON[a.label] ?? 'circle'}`} /></div>
                <dl className="transfer-details">
                  <div><dt>{a.label}</dt><dd><code>{a.id}</code></dd></div>
                  <div>
                    <dt><span className={`phase-chip ${phase}`}><i />{PHASES[phase].name}</span></dt>
                    <dd>{a.absorbed > 0 ? `${a.absorbed} absorbed` : PHASES[phase].note}</dd>
                  </div>
                  <div>
                    <dt>Owner req {a.requestNo}</dt>
                    <dd>{a.attempt > 0 && !done ? 'Retry from buffer' : 'Window owner'}</dd>
                  </div>
                </dl>
                <div className="transfer-number">{done ? '0.0s' : `${(a.remainingMs / 1000).toFixed(1)}s`}</div>
                <div className="pbar big" role="progressbar" aria-label={`${PHASES[phase].name}: ${PHASES[phase].note}`}
                     aria-valuemin={0} aria-valuemax={a.windowMs} aria-valuenow={done ? a.windowMs : a.remainingMs}>
                  <div className="pbar-fill" style={{ width: `${pct}%` }} />
                </div>
                {a.absorbed > 0 && (
                  <div className="buffer" aria-label={`${a.absorbed} absorbed requests`}>
                    {Array.from({ length: Math.min(a.absorbed, 24) }, (_, i) => <i key={i} />)}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}