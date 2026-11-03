import { useEffect, useRef } from 'react';

const time = (ts) => new Date(ts).toLocaleTimeString('en-GB', { hour12: false }) + '.' + String(ts % 1000).padStart(3, '0');

export default function LogStream({ logs }) {
  const box = useRef(null);
  const pinned = useRef(true);

  useEffect(() => {
    const el = box.current;
    if (el && pinned.current) el.scrollTop = el.scrollHeight;
  }, [logs]);

  const onScroll = () => {
    const el = box.current;
    pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
  };

  return (
    <section className="transfer-section" id="events">
      <div className="transfer-section-header">
        <h2>Event stream</h2>
        <div className="filter-options"><p>{logs.length} events</p></div>
      </div>
      <div className="log-box" ref={box} onScroll={onScroll} role="log" aria-live="off">
        {logs.length === 0 && <p className="empty">Waiting for events.</p>}
        {logs.map((l) => (
          <p key={l.seq} className={l.level.toLowerCase()}>
            <time>{time(l.ts)}</time> <b>{l.level}</b> {l.msg}
          </p>
        ))}
      </div>
    </section>
  );
}