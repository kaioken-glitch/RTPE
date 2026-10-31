import { useCallback, useEffect, useRef, useState } from 'react';
import { BASE, fetchState, resetSandbox, submitTransaction } from './api.js';
import Wallet, { Outcomes } from './components/Wallet.jsx';
import Controls from './components/Controls.jsx';
import Lanes, { StatusBar, overallPhase, useLanes } from './components/Lanes.jsx';
import LogStream from './components/LogStream.jsx';
import logo from './assets/rtpeico.svg';
import Sentinel from './components/Sentinel.jsx';

const POLL_MS = 500;
const LOG_CAP = 400;
const FAILS_BEFORE_BANNER = 3;      // one dropped poll is not an outage
const WAKE_GRACE_MS = 90_000;       // Render free tier needs up to ~60s to wake after idling
const ON_RENDER = /\.onrender\.com$/i.test(new URL(BASE, window.location.href).hostname);

const LINK_TEXT = {
  connecting: 'Connecting to engine',
  online: 'Engine connected',
  waking: 'Engine is waking up, this can take a minute',
  offline: `Engine unreachable at ${BASE}`,
};
const LINK_CLASS = { connecting: 'wait', online: 'up', waking: 'wait', offline: 'down' };

const PHASE_LABEL = {
  connecting: 'Connecting to engine',
  offline: 'Engine offline',
  conflict: 'Concurrent request detected',
  absorbing: 'Absorbing duplicates',
  tracking: 'Tracking',
  failed: 'Failed, no fallback',
  resolved: 'Resolved, all flags cleared',
  idle: 'Idle',
};

export default function App() {
  const [state, setState] = useState(null);
  const [logs, setLogs] = useState([]);
  const [conn, setConn] = useState('connecting');   // connecting | online | waking | offline
  const [connError, setConnError] = useState('');
  const [notice, setNotice] = useState('');
  const lanes = useLanes(state, logs);
  const phase = conn === 'offline' ? 'offline'
    : conn === 'connecting' || conn === 'waking' ? 'connecting'
    : overallPhase(lanes, true);
  const lastSeq = useRef(0);
  const inFlight = useRef(false);
  const fails = useRef(0);
  const firstFailAt = useRef(0);

  const [theme, setTheme] = useState(() => {
  try { return localStorage.getItem('rtpe-theme') ?? 'dark'; } catch { return 'dark'; }
  });
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem('rtpe-theme', theme); } catch { /* storage blocked, ignore */ }
  }, [theme]);

  useEffect(() => {
    const tick = async () => {
      if (inFlight.current) return;
      inFlight.current = true;
      try {
        const next = await fetchState(lastSeq.current);
        if (next.lastSeq < lastSeq.current) {
          lastSeq.current = 0;
          setLogs([]);
        }
        if (next.logs.length) {
          lastSeq.current = next.lastSeq;
          setLogs((prev) => [...prev, ...next.logs].slice(-LOG_CAP));
        }
        setState(next);
        fails.current = 0;
        setConn('online');
        setConnError('');
      } catch (err) {
        if (fails.current === 0) firstFailAt.current = Date.now();
        fails.current += 1;
        setConnError(err.message);
        if (fails.current >= FAILS_BEFORE_BANNER) {
          const waking = ON_RENDER && err.network && Date.now() - firstFailAt.current < WAKE_GRACE_MS;
          setConn(waking ? 'waking' : 'offline');
        }
      } finally {
        inFlight.current = false;
      }
    };
    tick();
    const timer = setInterval(tick, POLL_MS);
    return () => clearInterval(timer);
  }, []);

  const fire = useCallback(async (tx) => {
    try {
      setNotice('');
      return await submitTransaction(tx);
    } catch (err) {
      setNotice(err.message);
      return null;
    }
  }, []);

  const reset = useCallback(async () => {
    try {
      await resetSandbox();
      lastSeq.current = 0;
      setLogs([]);
    } catch (err) {
      setNotice(err.message);
    }
  }, []);

  return (
    <div className="stage" data-phase={phase}>
      <Aurora />
      <div className="app">
        <header className="app-header">
          <div className="logo">
            <img src={logo} alt="RTPE Cockpit" />
            <h1 className="logo-title"><span>RTPE</span><span>Cockpit</span></h1>
          </div>
          <div className="app-header-navigation">
            <div className="tabs">
              <a href="#wallet">Wallet</a>
              <a href="#windows">Windows</a>
              <a href="#events">Events</a>
              <a href="#simulate">Simulation</a>
            </div>
          </div>
          <div className="app-header-actions">
            <span className={`link ${LINK_CLASS[conn]}`} title={connError || undefined} role="status">
              <i />{LINK_TEXT[conn]}
            </span>
            <div className="app-header-actions-buttons">
              <button className="icon-button large" onClick={() => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))}
                      title="Invert colours" aria-label="Invert colours">
                <i className="ph ph-circle-half" />
              </button>
              <button className="icon-button large" onClick={reset} title="Reset sandbox" aria-label="Reset sandbox">
                <i className="ph ph-arrow-counter-clockwise" />
              </button>
            </div>
          </div>
        </header>

        <div className="app-body">
          <div className="app-body-navigation">
            <nav className="navigation">
              <a href="#wallet"><i className="ph ph-wallet" /><span>Wallet</span></a>
              <a href="#windows"><i className="ph ph-timer" /><span>Live windows</span></a>
              <a href="#events"><i className="ph ph-terminal-window" /><span>Event stream</span></a>
              <a href="#simulate"><i className="ph ph-lightning" /><span>Simulation</span></a>
              <button className="nav-button" onClick={reset}><i className="ph ph-arrow-counter-clockwise" /><span>Reset sandbox</span></button>
            </nav>
            <footer className="footer">
              <h1>RTPE<small>©</small></h1>
              <div>Debouncing, self-healing<br />transaction queue</div>
            </footer>
          </div>

          <div className="app-body-main-content">
            <StatusBar phase={phase} label={PHASE_LABEL[phase]} />
            <Wallet state={state} />
            <Lanes lanes={lanes} />
            <LogStream logs={logs} />
          </div>

          <div className="app-body-sidebar">
            <Controls fire={fire} notice={notice} balance={state?.wallet?.balanceCents} />
            <a href="#sentinel"><i className="ph ph-shield-checkered" /><span>Sentinel</span></a>
            <Outcomes state={state} />
          </div>
        </div>
      </div>
    </div>
  );
}

function Aurora() {
  return (
    <>
      <svg width="0" height="0" style={{ position: 'absolute' }} aria-hidden="true">
        <filter id="liquid" x="0" y="0" width="100%" height="100%" colorInterpolationFilters="sRGB">
          <feTurbulence type="fractalNoise" baseFrequency="0.008 0.012" numOctaves="2" seed="7" result="noise" />
          <feGaussianBlur in="noise" stdDeviation="2" result="soft" />
          <feDisplacementMap in="SourceGraphic" in2="soft" scale="34" xChannelSelector="R" yChannelSelector="G" />
        </filter>
      </svg>
      <div className="aurora" aria-hidden="true"><span /><span /><span /></div>
    </>
  );
}