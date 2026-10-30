import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchState, resetSandbox, submitTransaction } from './api.js';
import Wallet, { Outcomes } from './components/Wallet.jsx';
import Controls from './components/Controls.jsx';
import Lanes, { StatusBar, overallPhase, useLanes } from './components/Lanes.jsx';
import LogStream from './components/LogStream.jsx';
import logo from './assets/rtpeico.svg';

const POLL_MS = 500;
const LOG_CAP = 400;

const PHASE_LABEL = {
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
  const [online, setOnline] = useState(true);
  const [notice, setNotice] = useState('');
  const lanes = useLanes(state, logs);
  const phase = overallPhase(lanes, online);
  const lastSeq = useRef(0);
  const inFlight = useRef(false);

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
        setOnline(true);
      } catch {
        setOnline(false);
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
          <span className={`link ${online ? 'up' : 'down'}`}>
            <i />{online ? 'Engine connected' : 'Engine unreachable, start the backend'}
          </span>
          <div className="app-header-actions-buttons">
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
          <Controls fire={fire} notice={notice} />
          <Outcomes state={state} />
        </div>
      </div>
    </div>
  );
}