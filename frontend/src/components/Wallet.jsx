const usd = (cents) => `$${(cents / 100).toLocaleString('en-US', { minimumFractionDigits: 2 })}`;

export default function Wallet({ state }) {
  const w = state?.wallet;
  const m = state?.maps;
  const delta = w ? w.balanceCents - w.initialCents : 0;
  const idle = m && m.active === 0 && m.absorbedKeys === 0 && m.absorbedItems === 0;

  return (
    <section className="service-section" id="wallet">
      <h2>Sandbox wallet</h2>
      <div className="tiles">
        <article className="tile">
          <div className="tile-header">
            <i className="ph ph-wallet" />
            <h3>
              <span>Balance</span>
              <span>{delta === 0 ? 'No net change' : `${delta > 0 ? '+' : '-'}${usd(Math.abs(delta))} from start`}</span>
            </h3>
          </div>
          <p className="tile-figure">{w ? usd(w.balanceCents) : '--'}</p>
        </article>
        <article className="tile">
          <div className="tile-header">
            <i className="ph ph-arrows-left-right" />
            <h3><span>Ledger</span><span>Debits / credits applied</span></h3>
          </div>
          <p className="tile-figure">{w?.debits ?? 0} / {w?.credits ?? 0}</p>
        </article>
        <article className="tile">
          <div className="tile-header">
            <i className="ph ph-lock-simple" />
            <h3><span>Stock locked</span><span>Inventory units</span></h3>
          </div>
          <p className="tile-figure">{w ? `${w.lockedUnits} / ${w.stockUnits}` : '0 / 50'}</p>
        </article>
      </div>
      <p className={`badge ${idle ? 'ok' : 'busy'}`}>
        <i className="dot" />{idle ? 'Idle. Every map is empty.' : 'Processing. Maps drain on completion.'}
      </p>
    </section>
  );
}

export function Outcomes({ state }) {
  const s = state?.stats;
  const m = state?.maps;
  return (
    <section className="payment-section">
      <h2>Queue outcomes</h2>
      <dl className="facts">
        {['submitted', 'absorbed', 'succeeded', 'failed', 'recovered', 'discarded'].map((k) => (
          <div key={k}><dt>{k}</dt><dd>{s?.[k] ?? 0}</dd></div>
        ))}
      </dl>
      <h2 className="sub">Memory footprint</h2>
      <dl className="facts">
        <div><dt>activeTransactions</dt><dd>{m?.active ?? 0}</dd></div>
        <div><dt>absorbedStore keys</dt><dd>{m?.absorbedKeys ?? 0}</dd></div>
        <div><dt>absorbedStore items</dt><dd>{m?.absorbedItems ?? 0}</dd></div>
      </dl>
    </section>
  );
}