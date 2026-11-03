import { useEffect, useState } from 'react';
import { leaderboard } from '../../api.js';

const ago = (ts) => {
  if (!ts) return 'never';
  const s = Math.max(0, Math.round((Date.now() - ts) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
};

export default function Leaderboard({ me }) {
  const [data, setData] = useState(null);

  useEffect(() => {
    let alive = true;
    const tick = async () => { try { const d = await leaderboard(); if (alive) setData(d); } catch { /* keep last */ } };
    tick();
    const t = setInterval(tick, 4000);
    return () => { alive = false; clearInterval(t); };
  }, []);

  return (
    <section className="payment-section lab-card" id="board">
      <h2>Contributors</h2>
      {data && (
        <p className="sx-stats">
          {data.members} members · {data.tests} tests · {data.attacks} attacks caught · {data.signatures} distinct signatures
        </p>
      )}
      {data && data.top.length === 0 && <p className="hint">No contributions yet. Create an account and send the first payload.</p>}
      {data && data.top.length > 0 && (
        <div className="lab-scroll">
          <table className="lab-table">
            <thead><tr><th>#</th><th>Member</th><th>Points</th><th>Tests</th><th>Attacks</th><th>First</th><th>Active</th></tr></thead>
            <tbody>
              {data.top.map((r, i) => (
                <tr key={r.name} className={me === r.name ? 'me' : ''}>
                  <td>{i + 1}</td><td>{r.name}</td><td>{r.points}</td><td>{r.tests}</td><td>{r.attacks}</td><td>{r.firsts}</td><td>{ago(r.lastActive)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
