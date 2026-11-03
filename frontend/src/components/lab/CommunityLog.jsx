import { useEffect, useRef, useState } from 'react';
import { communityStream } from '../../api.js';

const CAP = 300;
const KIND_CLASS = { RECEIVED: 'info', CLEAN: 'ok', CLEARED: 'ok', IRREGULAR: 'warn', ATTACK: 'bad', SUNK: 'bad', BLACKLISTED: 'bad' };
const clock = (ts) => new Date(ts).toLocaleTimeString('en-GB', { hour12: false });

export default function CommunityLog({ me }) {
  const [events, setEvents] = useState([]);
  const [live, setLive] = useState(true);
  const seq = useRef(0);
  const box = useRef(null);
  const pinned = useRef(true);
  const inFlight = useRef(false);

  useEffect(() => {
    let alive = true;
    const tick = async () => {
      if (inFlight.current) return;
      inFlight.current = true;
      try {
        const r = await communityStream(seq.current);
        if (!alive) return;
        if (r.lastSeq < seq.current) { seq.current = 0; setEvents([]); }       // server restarted
        if (r.events.length) { seq.current = r.lastSeq; setEvents((p) => [...p, ...r.events].slice(-CAP)); }
        setLive(true);
      } catch { if (alive) setLive(false); }
      finally { inFlight.current = false; }
    };
    tick();
    const t = setInterval(tick, 1000);
    return () => { alive = false; clearInterval(t); };
  }, []);

  useEffect(() => {
    const el = box.current;
    if (el && pinned.current) el.scrollTop = el.scrollHeight;
  }, [events]);

  const onScroll = () => {
    const el = box.current;
    pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
  };

  return (
    <section className="payment-section lab-card" id="community">
      <div className="lab-row">
        <h2>Community activity</h2>
        <span className={`lab-live ${live ? 'on' : ''}`}>{live ? 'Live' : 'Reconnecting'}</span>
      </div>
      <p className="hint">Everyone's tests as they happen, from this page or from scripts calling the same endpoint. Payload text and IP addresses are never shown.</p>
      <div className="log-box lab-log" ref={box} onScroll={onScroll} role="log" aria-live="off">
        {events.length === 0 && <p className="lab-empty">Waiting for activity.</p>}
        {events.map((e) => (
          <p key={e.seq} className={me && e.who === me ? 'mine' : ''}>
            <time>{clock(e.ts)}</time>{' '}
            <b className={`k-${KIND_CLASS[e.kind] ?? 'info'}`}>{e.kind}</b>{' '}
            <span className="who">{e.who}</span>{' '}
            {e.id && <code>{e.id}</code>} {e.text}
          </p>
        ))}
      </div>
    </section>
  );
}
