import { useEffect, useRef, useState } from 'react';
import { BASE, fetchState, resetSandbox, submitTransaction } from './api.js';
import SentinelLab from './components/SentinelLab.jsx';

const LABELS = ['PAYMENT', 'REFUND', 'INVENTORY_LOCK'];
const THEME_KEY = 'rtpe-theme-v2';

function useHashView() {
  const [lab, setLab] = useState(() => window.location.hash === '#lab');

  useEffect(() => {
    const update = () => setLab(window.location.hash === '#lab');
    window.addEventListener('hashchange', update);
    return () => window.removeEventListener('hashchange', update);
  }, []);

  return lab;
}

function formatMoney(cents) {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD' }).format(cents / 100);
}

function formatTime(ts) {
  return new Date(ts).toLocaleTimeString();
}

function Cockpit({ onReset, resetError }) {
  const [state, setState] = useState(null);
  const [events, setEvents] = useState([]);
  const [connectionError, setConnectionError] = useState('');
  const [notice, setNotice] = useState('');
  const [id, setId] = useState(() => `order-${Math.random().toString(36).slice(2, 8)}`);
  const [label, setLabel] = useState('PAYMENT');
  const [amount, setAmount] = useState('1250');
  const [fail, setFail] = useState(false);
  const [busy, setBusy] = useState(false);
  const lastSeq = useRef(0);
  const inFlight = useRef(false);

  useEffect(() => {
    let active = true;
    const poll = async () => {
      if (inFlight.current) return;
      inFlight.current = true;
      try {
        const next = await fetchState(lastSeq.current);
        if (!active) return;
        if (next.lastSeq < lastSeq.current) {
          lastSeq.current = 0;
          setEvents([]);
        }
        lastSeq.current = next.lastSeq;
        if (next.logs.length) {
          setEvents((previous) => [...previous, ...next.logs].slice(-200));
        }
        setState(next);
        setConnectionError('');
      } catch (error) {
        if (active) setConnectionError(error.message);
      } finally {
        inFlight.current = false;
      }
    };

    poll();
    const timer = window.setInterval(poll, 750);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);

  const send = async (event) => {
    event.preventDefault();
    const value = Number(amount);
    if (!id.trim() || !Number.isSafeInteger(value) || value <= 0) {
      setNotice('Enter a transaction ID and a positive whole-number amount.');
      return;
    }

    setBusy(true);
    setNotice('');
    try {
      const result = await submitTransaction({ id: id.trim(), label, amount: value, fail });
      setNotice(`Request ${result.requestNo} ${result.outcome.toLowerCase()}.`);
    } catch (error) {
      setNotice(error.message);
    } finally {
      setBusy(false);
    }
  };

  const handleReset = async () => {
    setNotice('');
    if (await onReset()) {
      lastSeq.current = 0;
      setEvents([]);
    }
  };

  const amountUnit = label === 'INVENTORY_LOCK' ? 'units' : 'cents';
  const wallet = state?.wallet;

  return (
    <div className="app-body">
      <aside className="app-body-navigation">
        <nav className="navigation" aria-label="Cockpit sections">
          <a href="#wallet"><span className="nav-icon">◈</span><span>Overview</span></a>
          <a href="#windows"><span className="nav-icon">◷</span><span>Live windows</span></a>
          <a href="#events"><span className="nav-icon">≋</span><span>Event stream</span></a>
          <a href="#simulate"><span className="nav-icon">↗</span><span>Simulation</span></a>
          <a href="#lab"><span className="nav-icon">⬡</span><span>Sentinel lab</span></a>
        </nav>
        <footer className="footer">
          <h2>RTPE<small>©</small></h2>
          <div>Debouncing, self-healing<br />transaction queue</div>
        </footer>
      </aside>

      <div className="app-body-main-content">
        <div className="page-intro">
          <p className="eyebrow">TRANSACTION RESILIENCE · LIVE SANDBOX</p>
          <h1>Transaction cockpit</h1>
          <p>Explore transaction deduplication, recovery, and failure handling in a simulated ledger.</p>
        </div>
        <section className="connection-banner" role="status" data-online={!connectionError}>
          <span className="connection-dot" />
          {connectionError ? `Engine unavailable at ${BASE}: ${connectionError}` : 'Engine connected'}
          <button type="button" className="quiet-button" onClick={handleReset}>Reset sandbox</button>
        </section>
        {resetError && <p className="error" role="alert">{resetError}</p>}

        <section className="service-section" id="wallet" aria-labelledby="overview-title">
          <div className="service-section-header">
            <div>
              <p className="eyebrow">SANDBOX OVERVIEW</p>
              <h1 id="overview-title">Service</h1>
            </div>
            <p className="service-caption">A live view of your transaction engine</p>
          </div>
          <div className="tiles">
            <a className="tile" data-tone="green" href="#wallet">
              <div className="tile-header">
                <span className="tile-icon" aria-hidden="true">◈</span>
                <span><small>SIMULATED LEDGER</small><strong>Available balance</strong></span>
              </div>
              <span className="tile-value">{wallet ? formatMoney(wallet.balanceCents) : '—'}</span>
              <span className="tile-footer">{wallet ? `${wallet.lockedUnits} of ${wallet.stockUnits} inventory units locked` : 'Waiting for engine state'}</span>
            </a>
            <a className="tile" data-tone="orange" href="#windows">
              <div className="tile-header">
                <span className="tile-icon" aria-hidden="true">◷</span>
                <span><small>DEBOUNCE ENGINE</small><strong>Live windows</strong></span>
              </div>
              <span className="tile-value">{state?.active.length ?? '—'}<small> active</small></span>
              <span className="tile-footer">{state?.active.length ? 'Transactions are being processed' : 'No transactions in progress'}</span>
            </a>
            <a className="tile" data-tone="red" href="#events">
              <div className="tile-header">
                <span className="tile-icon" aria-hidden="true">≋</span>
                <span><small>ENGINE ACTIVITY</small><strong>Requests handled</strong></span>
              </div>
              <span className="tile-value">{state?.stats.submitted ?? '—'}</span>
              <span className="tile-footer">{state?.stats.absorbed ?? 0} duplicate requests absorbed</span>
            </a>
          </div>
          <div className="service-section-footer">
            <p>Transactions run in a simulated ledger. No real funds or external accounts are involved.</p>
          </div>
        </section>

        <section className="transfer-section" id="events">
          <div className="transfer-section-header">
            <div><p className="eyebrow">ENGINE DIAGNOSTICS</p><h2>Latest activity</h2></div>
            <span className="section-meta">{events.length} recent events</span>
          </div>
          <div className="log-box event-log terminal-log" role="log" aria-live="polite">
            {events.length ? [...events].reverse().slice(0, 40).map((entry) => (
              <p key={entry.seq}>
                <time>{formatTime(entry.ts)}</time>
                <b className={`event-${entry.level.toLowerCase()}`}>{entry.level}</b>
                <span className="terminal-message">{entry.msg}</span>
              </p>
            )) : <p className="hint">{connectionError || 'Waiting for engine events…'}</p>}
          </div>
        </section>

        <section className="transfer-section windows-section" id="windows">
          <div className="transfer-section-header">
            <div><p className="eyebrow">DEBOUNCE ENGINE</p><h2>Live windows</h2></div>
            <span className="section-meta">{state?.active.length ?? 0} active</span>
          </div>
          {state?.active.length ? (
            <div className="active-list">
              {state.active.map((item) => (
                <article
                  className="active-item"
                  data-urgent={item.remainingMs <= 500}
                  data-absorbed={item.absorbed > 0}
                  key={`${item.label}-${item.id}`}
                >
                  <div className="active-item-heading"><strong>{item.id}</strong><span>{item.label}</span></div>
                  <div className="progress-track" role="progressbar" aria-label={`${item.label} transaction window`} aria-valuemin={0} aria-valuemax={item.windowMs} aria-valuenow={item.remainingMs}>
                    <span style={{ width: `${Math.min(100, Math.max(0, item.remainingMs / item.windowMs * 100))}%` }} />
                  </div>
                  <small>{(item.remainingMs / 1000).toFixed(1)}s remaining · {item.absorbed} absorbed</small>
                </article>
              ))}
            </div>
          ) : <p className="hint">No active transactions. Submit one to open a debounce window.</p>}
        </section>
      </div>

      <aside className="app-body-sidebar">
        <section className="payment-section" id="simulate">
          <h2>New transaction</h2>
          <div className="payment-section-header"><p>Send a request to the sandbox engine</p></div>
          <form className="transaction-form" onSubmit={send}>
            <label className="field">
              <span>Transaction ID</span>
              <input value={id} onChange={(event) => setId(event.target.value)} maxLength={100} required />
            </label>
            <label className="field">
              <span>Transaction type</span>
              <select value={label} onChange={(event) => setLabel(event.target.value)}>
                {LABELS.map((item) => <option key={item} value={item}>{item.replace('_', ' ')}</option>)}
              </select>
            </label>
            <label className="field">
              <span>Amount ({amountUnit})</span>
              <input type="number" min="1" step="1" value={amount} onChange={(event) => setAmount(event.target.value)} required />
            </label>
            <label className="checkbox-field">
              <input type="checkbox" checked={fail} onChange={(event) => setFail(event.target.checked)} />
              <span>Simulate downstream failure</span>
            </label>
            <div className="payment-section-footer">
              <button type="submit" className="save-button solid" disabled={busy || Boolean(connectionError)}>
                {busy ? 'Submitting…' : 'Submit transaction'}
              </button>
            </div>
            {notice && <p className="form-notice" role="status">{notice}</p>}
          </form>
        </section>

        <section className="payment-section stats-panel">
          <h2>Engine activity</h2>
          <div className="metrics-list">
            <Stat label="Requests" value={state?.stats.submitted ?? '—'} />
            <Stat label="Absorbed" value={state?.stats.absorbed ?? '—'} />
            <Stat label="Recovered" value={state?.stats.recovered ?? '—'} />
          </div>
          <p className="hint">Matching requests share a window. If execution fails, the next valid request is retried.</p>
        </section>
      </aside>
    </div>
  );
}

function Stat({ label, value }) {
  return <div className="stat"><span>{label}</span><strong>{value}</strong></div>;
}

export default function App() {
  const lab = useHashView();
  const [resetError, setResetError] = useState('');
  const [theme, setTheme] = useState(() => {
    try {
      return localStorage.getItem(THEME_KEY) === 'light' ? 'light' : 'dark';
    } catch {
      return 'dark';
    }
  });

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem(THEME_KEY, theme);
    } catch {
      // Theme still works for this page when storage is unavailable.
    }
  }, [theme]);

  const reset = async () => {
    try {
      await resetSandbox();
      setResetError('');
      return true;
    } catch (error) {
      setResetError(error.message);
      return false;
    }
  };

  return (
    <div className="stage" data-theme={theme}>
      <div className="app">
        <header className="app-header">
          <div className="app-header-logo">
            <a className="brand" href="#cockpit" aria-label="RTPE Cockpit home">
              <span className="brand-mark" aria-hidden="true">R</span>
              <span className="brand-title"><strong>RTPE</strong><small>Resilience cockpit</small></span>
            </a>
          </div>
          <div className="app-header-navigation">
            <nav className="tabs" aria-label="Main navigation">
              <a href="#cockpit" className={!lab ? 'active' : ''} aria-current={!lab ? 'page' : undefined}>Cockpit</a>
              <a href="#lab" className={lab ? 'active' : ''} aria-current={lab ? 'page' : undefined}>Sentinel lab</a>
            </nav>
          </div>
          <div className="app-header-actions">
            <span className="environment-badge"><span />LOCAL SANDBOX</span>
            <button
              type="button"
              className="theme-toggle"
              onClick={() => setTheme((current) => current === 'dark' ? 'light' : 'dark')}
              aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
              title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}
            >
              <span aria-hidden="true">{theme === 'dark' ? '◐' : '◑'}</span>
              <span>{theme === 'dark' ? 'Light' : 'Dark'} mode</span>
            </button>
          </div>
        </header>
        {lab ? (
          <main className="app-main lab-main">
            <div className="page-intro">
              <p className="eyebrow">TRANSACTION RESILIENCE · LIVE SANDBOX</p>
              <h1>Sentinel lab</h1>
              <p>Submit sample payloads to the isolated test engine and watch how it responds.</p>
            </div>
            <SentinelLab />
          </main>
        ) : (
          <main className="app-main">
            <Cockpit onReset={reset} resetError={resetError} />
          </main>
        )}
      </div>
    </div>
  );
}
