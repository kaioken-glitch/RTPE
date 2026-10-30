import { useRef, useState } from 'react';

const MODES = [
  { key: 'standard', name: 'Standard', hint: 'One request per order. The wallet is debited once.' },
  { key: 'multi', name: 'Multi-pulse', hint: 'Repeated clicks on one cart. Only the first charges; the rest are buffered.' },
  { key: 'cross', name: 'Cross-type', hint: 'Same transaction id across PAYMENT, REFUND and INVENTORY_LOCK. Each type keeps its own queue.' },
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export default function Controls({ fire, notice }) {
  const [mode, setMode] = useState('standard');
  const [dollars, setDollars] = useState(25);
  const [fail, setFail] = useState(false);
  const counter = useRef(1);
  const [cart, setCart] = useState('cart-1');

  const cents = Math.max(1, Math.round(Number(dollars) * 100) || 0);
  const nextId = (prefix) => `${prefix}-${counter.current++}`;
  const standard = () => fire({ id: nextId('ord'), label: 'PAYMENT', amount: cents, fail });
  const pulse = (id, label, amount, fl) => fire({ id, label, amount, fail: fl });

  const burst = async (n) => {
    for (let i = 0; i < n; i += 1) {
      pulse(cart, 'PAYMENT', cents, fail && i === 0);
      await sleep(40 + Math.random() * 140);
    }
  };

  const crossType = async () => {
    const id = nextId('xt');
    const profiles = [['PAYMENT', cents], ['REFUND', cents], ['INVENTORY_LOCK', 2]];
    for (let round = 0; round < 2; round += 1) {
      await Promise.all(profiles.map(([label, amount]) => pulse(id, label, amount, fail && round === 0 && label === 'PAYMENT')));
      await sleep(80);
    }
  };

  const active = MODES.find((m) => m.key === mode);

  return (
    <section className="payment-section" id="simulate">
      <h2>Simulation</h2>
      <div className="seg" role="tablist">
        {MODES.map((m) => (
          <button key={m.key} role="tab" aria-selected={mode === m.key} className={mode === m.key ? 'on' : ''} onClick={() => setMode(m.key)}>
            {m.name}
          </button>
        ))}
      </div>
      <p className="hint">{active.hint}</p>

      <div className="field">
        <label htmlFor="amt">Amount (USD)</label>
        <input id="amt" type="number" min="0.01" step="0.01" value={dollars} onChange={(e) => setDollars(e.target.value)} />
      </div>
      <label className="check">
        <input type="checkbox" checked={fail} onChange={(e) => setFail(e.target.checked)} />
        Fail the first request downstream
      </label>

      {mode === 'multi' && <p className="hint">Active cart: <code>{cart}</code></p>}

      <div className="payment-section-footer">
        {mode === 'standard' && <button className="save-button solid" onClick={standard}>Pay ${Number(dollars || 0).toFixed(2)}</button>}
        {mode === 'multi' && (
          <>
            <button className="save-button solid" onClick={() => pulse(cart, 'PAYMENT', cents, fail)}>Pay (click fast)</button>
            <button className="save-button" onClick={() => burst(2)}>Double</button>
            <button className="save-button" onClick={() => burst(3)}>Triple</button>
            <button className="settings-button" onClick={() => setCart(nextId('cart'))}><i className="ph ph-plus" /><span>New cart</span></button>
          </>
        )}
        {mode === 'cross' && <button className="save-button solid" onClick={crossType}>Fire all three, twice</button>}
      </div>

      {notice && <p className="error" role="alert">{notice}</p>}
    </section>
  );
}